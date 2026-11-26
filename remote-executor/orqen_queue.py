#!/usr/bin/env python3
"""
ORQEN Phase 6 — Durable Remote Execution Queue & Fencing Lease Store (SQLite WAL).

Guarantees:
- Durable across server restarts (SQLite WAL + PRAGMA foreign_keys = ON).
- Atomic task claiming across concurrent workers via BEGIN IMMEDIATE transactions.
- Monotonic fencing tokens (`lease_token`) so stale workers that lost their lease
  cannot commit authoritative progress, completion, or failure.
- Idempotent task submission on (owner_user_id, idempotency_key).
- Secret redaction on all stored logs, errors, and audit records.
"""

import hashlib
import json
import os
import re
import sqlite3
import threading
import time
import uuid
from typing import Any, Dict, List, Optional, Tuple


SECRET_PATTERNS = [
    (re.compile(r"(Bearer\s+)[A-Za-z0-9._\-+/=]{8,}", re.IGNORECASE), r"\1[REDACTED]"),
    (re.compile(r"(x-api-key\s*[:=]\s*)[^\s,;\"]+", re.IGNORECASE), r"\1[REDACTED]"),
    (re.compile(r"(Authorization\s*[:=]\s*)[^\r\n]+", re.IGNORECASE), r"\1[REDACTED]"),
    (re.compile(r"\bsk-[A-Za-z0-9_\-]{12,}\b"), "[REDACTED_API_KEY]"),
    (re.compile(r"\bAIza[0-9A-Za-z_\-]{20,}\b"), "[REDACTED_API_KEY]"),
    (re.compile(r"\bghp_[A-Za-z0-9]{20,}\b"), "[REDACTED_TOKEN]"),
]


def redact_secrets(text: Optional[str]) -> str:
    if not text:
        return ""
    out = str(text)
    for pattern, repl in SECRET_PATTERNS:
        out = pattern.sub(repl, out)
    return out


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def now_ms() -> int:
    return int(time.time() * 1000)


class DurableRemoteQueue:
    """SQLite WAL-backed durable queue, lease coordinator, and audit store."""

    def __init__(self, db_path: str):
        self.db_path = db_path
        parent = os.path.dirname(os.path.abspath(db_path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        self._lock = threading.RLock()
        self._init_schema()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path, timeout=30.0, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode = WAL")
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute("PRAGMA busy_timeout = 10000")
        return conn

    def _init_schema(self) -> None:
        with self._lock:
            conn = self._connect()
            try:
                conn.executescript(
                    """
                    CREATE TABLE IF NOT EXISTS workers (
                        worker_id TEXT PRIMARY KEY,
                        worker_name TEXT NOT NULL,
                        token_hash TEXT NOT NULL,
                        version TEXT NOT NULL,
                        capabilities_json TEXT NOT NULL DEFAULT '[]',
                        isolation_mode TEXT NOT NULL DEFAULT 'POSIX_ISOLATED_DIRECTORY',
                        status TEXT NOT NULL DEFAULT 'ONLINE',
                        revoked INTEGER NOT NULL DEFAULT 0,
                        last_heartbeat_at INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    );

                    CREATE TABLE IF NOT EXISTS remote_tasks (
                        task_id TEXT PRIMARY KEY,
                        owner_user_id TEXT NOT NULL,
                        project_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        idempotency_key TEXT NOT NULL,
                        status TEXT NOT NULL,
                        retry_count INTEGER NOT NULL DEFAULT 0,
                        max_retries INTEGER NOT NULL DEFAULT 3,
                        next_retry_at INTEGER NOT NULL DEFAULT 0,
                        lease_worker_id TEXT,
                        lease_token INTEGER NOT NULL DEFAULT 0,
                        lease_expires_at INTEGER NOT NULL DEFAULT 0,
                        cancel_requested INTEGER NOT NULL DEFAULT 0,
                        pause_requested INTEGER NOT NULL DEFAULT 0,
                        completed_steps INTEGER NOT NULL DEFAULT 0,
                        total_steps INTEGER NOT NULL DEFAULT 1,
                        current_step_title TEXT,
                        result_summary TEXT,
                        last_error TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        UNIQUE(owner_user_id, idempotency_key)
                    );
                    CREATE INDEX IF NOT EXISTS idx_remote_tasks_status_retry
                        ON remote_tasks(status, next_retry_at, created_at);
                    CREATE INDEX IF NOT EXISTS idx_remote_tasks_owner
                        ON remote_tasks(owner_user_id, updated_at);

                    CREATE TABLE IF NOT EXISTS task_logs (
                        log_id TEXT PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        sequence_no INTEGER NOT NULL,
                        level TEXT NOT NULL DEFAULT 'INFO',
                        message TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(task_id) REFERENCES remote_tasks(task_id) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_task_logs_task_seq
                        ON task_logs(task_id, sequence_no);

                    CREATE TABLE IF NOT EXISTS task_checkpoints (
                        checkpoint_id TEXT PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        step_title TEXT NOT NULL,
                        modified_files_json TEXT NOT NULL DEFAULT '[]',
                        summary TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(task_id) REFERENCES remote_tasks(task_id) ON DELETE CASCADE
                    );

                    CREATE TABLE IF NOT EXISTS task_artifacts (
                        artifact_id TEXT PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        relative_path TEXT NOT NULL,
                        sha256 TEXT NOT NULL,
                        size_bytes INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(task_id) REFERENCES remote_tasks(task_id) ON DELETE CASCADE
                    );

                    CREATE TABLE IF NOT EXISTS audit_logs (
                        audit_id TEXT PRIMARY KEY,
                        actor TEXT NOT NULL,
                        action TEXT NOT NULL,
                        task_id TEXT,
                        detail TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    );
                    """
                )
            finally:
                conn.close()

    def _audit(self, conn: sqlite3.Connection, actor: str, action: str, task_id: Optional[str], detail: str) -> None:
        conn.execute(
            "INSERT INTO audit_logs(audit_id, actor, action, task_id, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            (f"aud-{uuid.uuid4().hex[:10]}", actor, action, task_id, redact_secrets(detail), now_ms()),
        )

    # ---------------- Worker Registration & Auth ----------------

    def register_worker(
        self,
        worker_id: str,
        worker_name: str,
        version: str,
        capabilities: List[str],
        isolation_mode: str = "POSIX_ISOLATED_DIRECTORY",
    ) -> Tuple[Dict[str, Any], str]:
        raw_token = f"wkr_{uuid.uuid4().hex}{uuid.uuid4().hex[:12]}"
        ts = now_ms()
        with self._lock:
            conn = self._connect()
            try:
                conn.execute("BEGIN IMMEDIATE")
                conn.execute(
                    """
                    INSERT INTO workers(worker_id, worker_name, token_hash, version, capabilities_json, isolation_mode, status, revoked, last_heartbeat_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'ONLINE', 0, ?, ?)
                    ON CONFLICT(worker_id) DO UPDATE SET
                        worker_name = excluded.worker_name,
                        token_hash = excluded.token_hash,
                        version = excluded.version,
                        capabilities_json = excluded.capabilities_json,
                        isolation_mode = excluded.isolation_mode,
                        status = 'ONLINE',
                        revoked = 0,
                        last_heartbeat_at = excluded.last_heartbeat_at
                    """,
                    (worker_id, worker_name, hash_token(raw_token), version, json.dumps(capabilities), isolation_mode, ts, ts),
                )
                self._audit(conn, worker_id, "WORKER_REGISTERED", None, f"version={version} isolation={isolation_mode}")
                conn.execute("COMMIT")
                row = conn.execute("SELECT * FROM workers WHERE worker_id = ?", (worker_id,)).fetchone()
                return self._worker_to_dict(row), raw_token
            except Exception:
                conn.execute("ROLLBACK")
                raise
            finally:
                conn.close()

    def authenticate_worker(self, worker_id: str, raw_token: str) -> bool:
        if not worker_id or not raw_token:
            return False
        conn = self._connect()
        try:
            row = conn.execute(
                "SELECT token_hash, revoked FROM workers WHERE worker_id = ?",
                (worker_id,),
            ).fetchone()
            if not row or row["revoked"] == 1:
                return False
            return hash_token(raw_token) == row["token_hash"]
        finally:
            conn.close()

    def revoke_worker(self, worker_id: str, actor: str = "admin") -> bool:
        with self._lock:
            conn = self._connect()
            try:
                conn.execute("BEGIN IMMEDIATE")
                cur = conn.execute(
                    "UPDATE workers SET revoked = 1, status = 'REVOKED' WHERE worker_id = ?",
                    (worker_id,),
                )
                self._audit(conn, actor, "WORKER_REVOKED", None, f"worker_id={worker_id}")
                conn.execute("COMMIT")
                return cur.rowcount > 0
            except Exception:
                conn.execute("ROLLBACK")
                raise
            finally:
                conn.close()

    def record_worker_heartbeat(self, worker_id: str) -> bool:
        with self._lock:
            conn = self._connect()
            try:
                cur = conn.execute(
                    "UPDATE workers SET last_heartbeat_at = ?, status = 'ONLINE' WHERE worker_id = ? AND revoked = 0",
                    (now_ms(), worker_id),
                )
                return cur.rowcount > 0
            finally:
                conn.close()

    # ---------------- Task Submission & Queries ----------------

    def submit_task(
        self,
        owner_user_id: str,
        project_id: str,
        title: str,
        payload: Dict[str, Any],
        idempotency_key: str,
        task_id: Optional[str] = None,
        max_retries: int = 3,
    ) -> Tuple[Dict[str, Any], bool]:
        """Returns (task_dict, created_new). Idempotent on (owner_user_id, idempotency_key)."""
        ts = now_ms()
        tid = task_id or f"RT-{uuid.uuid4().hex[:8].upper()}"
        steps = payload.get("steps", [])
        total_steps = len(steps) if isinstance(steps, list) and len(steps) > 0 else 1

        with self._lock:
            conn = self._connect()
            try:
                conn.execute("BEGIN IMMEDIATE")
                existing = conn.execute(
                    "SELECT * FROM remote_tasks WHERE owner_user_id = ? AND idempotency_key = ?",
                    (owner_user_id, idempotency_key),
                ).fetchone()
                if existing:
                    conn.execute("COMMIT")
                    return self._task_to_dict(existing), False

                conn.execute(
                    """
                    INSERT INTO remote_tasks(
                        task_id, owner_user_id, project_id, title, payload_json, idempotency_key,
                        status, retry_count, max_retries, next_retry_at,
                        lease_worker_id, lease_token, lease_expires_at,
                        cancel_requested, pause_requested, completed_steps, total_steps,
                        current_step_title, result_summary, last_error, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', 0, ?, 0, NULL, 0, 0, 0, 0, 0, ?, NULL, NULL, NULL, ?, ?)
                    """,
                    (
                        tid,
                        owner_user_id,
                        project_id,
                        redact_secrets(title),
                        json.dumps(payload),
                        idempotency_key,
                        max(0, int(max_retries)),
                        total_steps,
                        ts,
                        ts,
                    ),
                )
                self._audit(conn, owner_user_id, "TASK_SUBMITTED", tid, f"project={project_id}")
                conn.execute("COMMIT")
                row = conn.execute("SELECT * FROM remote_tasks WHERE task_id = ?", (tid,)).fetchone()
                return self._task_to_dict(row), True
            except Exception:
                conn.execute("ROLLBACK")
                raise
            finally:
                conn.close()

    def get_task_for_owner(self, task_id: str, owner_user_id: str) -> Tuple[Optional[Dict[str, Any]], int]:
        """Returns (task_dict, http_status_code: 200 / 403 / 404)."""
        conn = self._connect()
        try:
            row = conn.execute("SELECT * FROM remote_tasks WHERE task_id = ?", (task_id,)).fetchone()
            if not row:
                return None, 404
            if row["owner_user_id"] != owner_user_id:
                return None, 403
            return self._task_to_dict(row), 200
        finally:
            conn.close()

    def list_tasks_for_owner(self, owner_user_id: str) -> List[Dict[str, Any]]:
        conn = self._connect()
        try:
            rows = conn.execute(
                "SELECT * FROM remote_tasks WHERE owner_user_id = ? ORDER BY updated_at DESC",
                (owner_user_id,),
            ).fetchall()
            return [self._task_to_dict(r) for r in rows]
        finally:
            conn.close()

    def request_cancel(self, task_id: str, owner_user_id: str) -> Tuple[Optional[Dict[str, Any]], int]:
        ts = now_ms()
        with self._lock:
            conn = self._connect()
            try:
                conn.execute("BEGIN IMMEDIATE")
                row = conn.execute("SELECT * FROM remote_tasks WHERE task_id = ?", (task_id,)).fetchone()
                if not row:
                    conn.execute("ROLLBACK")
                    return None, 404
                if row["owner_user_id"] != owner_user_id:
                    conn.execute("ROLLBACK")
                    return None, 403
                if row["status"] in ("COMPLETED", "CANCELLED"):
                    conn.execute("COMMIT")
                    return self._task_to_dict(row), 200
                conn.execute(
                    """
                    UPDATE remote_tasks
                    SET cancel_requested = 1, status = 'CANCELLED', lease_worker_id = NULL, lease_expires_at = 0, updated_at = ?
                    WHERE task_id = ?
                    """,
                    (ts, task_id),
                )
                self._audit(conn, owner_user_id, "TASK_CANCELLED", task_id, "Owner cancelled remote task")
                conn.execute("COMMIT")
                updated = conn.execute("SELECT * FROM remote_tasks WHERE task_id = ?", (task_id,)).fetchone()
                return self._task_to_dict(updated), 200
            except Exception:
                conn.execute("ROLLBACK")
                raise
            finally:
                conn.close()

    def request_pause_or_resume(self, task_id: str, owner_user_id: str, pause: bool) -> Tuple[Optional[Dict[str, Any]], int]:
        ts = now_ms()
        with self._lock:
            conn = self._connect()
            try:
                conn.execute("BEGIN IMMEDIATE")
                row = conn.execute("SELECT * FROM remote_tasks WHERE task_id = ?", (task_id,)).fetchone()
                if not row:
                    conn.execute("ROLLBACK")
                    return None, 404
                if row["owner_user_id"] != owner_user_id:
                    conn.execute("ROLLBACK")
                    return None, 403
                if row["cancel_requested"] == 1 or row["status"] in ("COMPLETED", "CANCELLED"):
                    conn.execute("COMMIT")
                    return self._task_to_dict(row), 200
                new_status = "PAUSED" if pause else "QUEUED"
                conn.execute(
                    "UPDATE remote_tasks SET pause_requested = ?, status = ?, updated_at = ? WHERE task_id = ?",
                    (1 if pause else 0, new_status, ts, task_id),
                )
                self._audit(conn, owner_user_id, "TASK_PAUSED" if pause else "TASK_RESUMED", task_id, new_status)
                conn.execute("COMMIT")
                updated = conn.execute("SELECT * FROM remote_tasks WHERE task_id = ?", (task_id,)).fetchone()
                return self._task_to_dict(updated), 200
            except Exception:
                conn.execute("ROLLBACK")
                raise
            finally:
                conn.close()

    # ---------------- Atomic Claim, Lease & Fencing ----------------

    def reclaim_expired_leases(self, current_ms: Optional[int] = None) -> int:
        ts = current_ms if current_ms is not None else now_ms()
        reclaimed = 0
        with self._lock:
            conn = self._connect()
            try:
                conn.execute("BEGIN IMMEDIATE")
                expired = conn.execute(
                    """
                    SELECT * FROM remote_tasks
                    WHERE status IN ('CLAIMED', 'RUNNING')
                      AND cancel_requested = 0
                      AND lease_expires_at > 0
                      AND lease_expires_at <= ?
                    """,
                    (ts,),
                ).fetchall()
                for row in expired:
                    next_retry = row["retry_count"] + 1
                    if next_retry > row["max_retries"]:
                        conn.execute(
                            """
                            UPDATE remote_tasks
                            SET status = 'FAILED', retry_count = ?, lease_worker_id = NULL, lease_expires_at = 0,
                                last_error = 'Worker lease expired and max retries exceeded', updated_at = ?
                            WHERE task_id = ?
                            """,
                            (next_retry, ts, row["task_id"]),
                        )
                    else:
                        conn.execute(
                            """
                            UPDATE remote_tasks
                            SET status = 'RETRYING', retry_count = ?, next_retry_at = ?,
                                lease_worker_id = NULL, lease_expires_at = 0,
                                last_error = 'Worker lease expired; reclaimed for retry', updated_at = ?
                            WHERE task_id = ?
                            """,
                            (next_retry, ts, ts, row["task_id"]),
                        )
                    self._audit(conn, "supervisor", "LEASE_EXPIRED_RECLAIMED", row["task_id"], f"old_worker={row['lease_worker_id']}")
                    reclaimed += 1
                conn.execute("COMMIT")
                return reclaimed
            except Exception:
                conn.execute("ROLLBACK")
                raise
            finally:
                conn.close()

    def claim_next_task(
        self,
        worker_id: str,
        lease_ttl_ms: int = 30_000,
        current_ms: Optional[int] = None,
    ) -> Optional[Dict[str, Any]]:
        """Atomically claims one eligible task and increments its monotonic fencing token."""
        ts = current_ms if current_ms is not None else now_ms()
        self.reclaim_expired_leases(ts)
        with self._lock:
            conn = self._connect()
            try:
                conn.execute("BEGIN IMMEDIATE")
                row = conn.execute(
                    """
                    SELECT * FROM remote_tasks
                    WHERE status IN ('QUEUED', 'RETRYING')
                      AND cancel_requested = 0
                      AND pause_requested = 0
                      AND next_retry_at <= ?
                    ORDER BY created_at ASC
                    LIMIT 1
                    """,
                    (ts,),
                ).fetchone()
                if not row:
                    conn.execute("COMMIT")
                    return None
                new_token = int(row["lease_token"]) + 1
                expires_at = ts + max(500, int(lease_ttl_ms))
                conn.execute(
                    """
                    UPDATE remote_tasks
                    SET status = 'CLAIMED', lease_worker_id = ?, lease_token = ?, lease_expires_at = ?, updated_at = ?
                    WHERE task_id = ? AND status IN ('QUEUED', 'RETRYING')
                    """,
                    (worker_id, new_token, expires_at, ts, row["task_id"]),
                )
                self._audit(conn, worker_id, "TASK_CLAIMED", row["task_id"], f"lease_token={new_token}")
                conn.execute("COMMIT")
                claimed = conn.execute("SELECT * FROM remote_tasks WHERE task_id = ?", (row["task_id"],)).fetchone()
                return self._task_to_dict(claimed)
            except Exception:
                conn.execute("ROLLBACK")
                raise
            finally:
                conn.close()

    def renew_lease(self, task_id: str, worker_id: str, lease_token: int, lease_ttl_ms: int = 30_000) -> Tuple[bool, bool]:
        """Returns (renewed_ok, cancel_requested)."""
        ts = now_ms()
        with self._lock:
            conn = self._connect()
            try:
                conn.execute("BEGIN IMMEDIATE")
                row = conn.execute("SELECT * FROM remote_tasks WHERE task_id = ?", (task_id,)).fetchone()
                if not row:
                    conn.execute("ROLLBACK")
                    return False, False
                if row["cancel_requested"] == 1 or row["status"] == "CANCELLED":
                    conn.execute("COMMIT")
                    return False, True
                if row["lease_worker_id"] != worker_id or int(row["lease_token"]) != int(lease_token):
                    conn.execute("ROLLBACK")
                    return False, False
                conn.execute(
                    "UPDATE remote_tasks SET lease_expires_at = ?, status = 'RUNNING', updated_at = ? WHERE task_id = ?",
                    (ts + max(500, int(lease_ttl_ms)), ts, task_id),
                )
                conn.execute("COMMIT")
                return True, False
            except Exception:
                conn.execute("ROLLBACK")
                raise
            finally:
                conn.close()

    def report_progress(
        self,
        task_id: str,
        worker_id: str,
        lease_token: int,
        completed_steps: int,
        current_step_title: str,
        log_message: Optional[str] = None,
        checkpoint: Optional[Dict[str, Any]] = None,
    ) -> Tuple[bool, int]:
        """Validates fencing token before writing progress, logs, and checkpoints."""
        ts = now_ms()
        with self._lock:
            conn = self._connect()
            try:
                conn.execute("BEGIN IMMEDIATE")
                row = conn.execute("SELECT * FROM remote_tasks WHERE task_id = ?", (task_id,)).fetchone()
                if not row:
                    conn.execute("ROLLBACK")
                    return False, 404
                if row["cancel_requested"] == 1 or row["status"] == "CANCELLED":
                    conn.execute("ROLLBACK")
                    return False, 409
                if row["lease_worker_id"] != worker_id or int(row["lease_token"]) != int(lease_token):
                    conn.execute("ROLLBACK")
                    return False, 409
                conn.execute(
                    """
                    UPDATE remote_tasks
                    SET status = 'RUNNING', completed_steps = ?, current_step_title = ?, updated_at = ?
                    WHERE task_id = ?
                    """,
                    (completed_steps, redact_secrets(current_step_title), ts, task_id),
                )
                if log_message:
                    seq_row = conn.execute(
                        "SELECT COALESCE(MAX(sequence_no), 0) + 1 AS next_seq FROM task_logs WHERE task_id = ?",
                        (task_id,),
                    ).fetchone()
                    conn.execute(
                        "INSERT INTO task_logs(log_id, task_id, sequence_no, level, message, created_at) VALUES (?, ?, ?, 'INFO', ?, ?)",
                        (f"log-{uuid.uuid4().hex[:10]}", task_id, int(seq_row["next_seq"]), redact_secrets(log_message), ts),
                    )
                if checkpoint:
                    conn.execute(
                        "INSERT INTO task_checkpoints(checkpoint_id, task_id, step_title, modified_files_json, summary, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                        (
                            f"chk-{uuid.uuid4().hex[:10]}",
                            task_id,
                            redact_secrets(checkpoint.get("step_title", current_step_title)),
                            json.dumps(checkpoint.get("modified_files", [])),
                            redact_secrets(checkpoint.get("summary", "")),
                            ts,
                        ),
                    )
                conn.execute("COMMIT")
                return True, 200
            except Exception:
                conn.execute("ROLLBACK")
                raise
            finally:
                conn.close()

    def complete_task(
        self,
        task_id: str,
        worker_id: str,
        lease_token: int,
        result_summary: str,
        completed_steps: int,
        artifacts: Optional[List[Dict[str, Any]]] = None,
    ) -> Tuple[Optional[Dict[str, Any]], int]:
        """Idempotent completion with strict fencing token enforcement."""
        ts = now_ms()
        with self._lock:
            conn = self._connect()
            try:
                conn.execute("BEGIN IMMEDIATE")
                row = conn.execute("SELECT * FROM remote_tasks WHERE task_id = ?", (task_id,)).fetchone()
                if not row:
                    conn.execute("ROLLBACK")
                    return None, 404
                # Idempotent retry of already-completed task by same lease owner!
                if row["status"] == "COMPLETED" and int(row["lease_token"]) == int(lease_token):
                    conn.execute("COMMIT")
                    return self._task_to_dict(row), 200
                if row["lease_worker_id"] != worker_id or int(row["lease_token"]) != int(lease_token):
                    conn.execute("ROLLBACK")
                    return None, 409
                conn.execute(
                    """
                    UPDATE remote_tasks
                    SET status = 'COMPLETED', completed_steps = ?, result_summary = ?,
                        lease_worker_id = NULL, lease_expires_at = 0, last_error = NULL, updated_at = ?
                    WHERE task_id = ?
                    """,
                    (completed_steps, redact_secrets(result_summary), ts, task_id),
                )
                for art in (artifacts or []):
                    conn.execute(
                        "INSERT INTO task_artifacts(artifact_id, task_id, relative_path, sha256, size_bytes, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                        (
                            f"art-{uuid.uuid4().hex[:10]}",
                            task_id,
                            str(art["relative_path"]),
                            str(art["sha256"]),
                            int(art.get("size_bytes", 0)),
                            ts,
                        ),
                    )
                self._audit(conn, worker_id, "TASK_COMPLETED", task_id, f"artifacts={len(artifacts or [])}")
                conn.execute("COMMIT")
                updated = conn.execute("SELECT * FROM remote_tasks WHERE task_id = ?", (task_id,)).fetchone()
                return self._task_to_dict(updated), 200
            except Exception:
                conn.execute("ROLLBACK")
                raise
            finally:
                conn.close()

    def fail_task(
        self,
        task_id: str,
        worker_id: str,
        lease_token: int,
        error_message: str,
        retryable: bool = True,
        backoff_ms: int = 2_000,
    ) -> Tuple[Optional[Dict[str, Any]], int]:
        ts = now_ms()
        with self._lock:
            conn = self._connect()
            try:
                conn.execute("BEGIN IMMEDIATE")
                row = conn.execute("SELECT * FROM remote_tasks WHERE task_id = ?", (task_id,)).fetchone()
                if not row:
                    conn.execute("ROLLBACK")
                    return None, 404
                if row["lease_worker_id"] != worker_id or int(row["lease_token"]) != int(lease_token):
                    conn.execute("ROLLBACK")
                    return None, 409
                next_retry = row["retry_count"] + 1
                safe_err = redact_secrets(error_message)
                if retryable and next_retry <= row["max_retries"]:
                    conn.execute(
                        """
                        UPDATE remote_tasks
                        SET status = 'RETRYING', retry_count = ?, next_retry_at = ?,
                            lease_worker_id = NULL, lease_expires_at = 0, last_error = ?, updated_at = ?
                        WHERE task_id = ?
                        """,
                        (next_retry, ts + max(0, int(backoff_ms)), safe_err, ts, task_id),
                    )
                else:
                    conn.execute(
                        """
                        UPDATE remote_tasks
                        SET status = 'FAILED', retry_count = ?, lease_worker_id = NULL,
                            lease_expires_at = 0, last_error = ?, updated_at = ?
                        WHERE task_id = ?
                        """,
                        (next_retry, safe_err, ts, task_id),
                    )
                self._audit(conn, worker_id, "TASK_FAILED", task_id, safe_err)
                conn.execute("COMMIT")
                updated = conn.execute("SELECT * FROM remote_tasks WHERE task_id = ?", (task_id,)).fetchone()
                return self._task_to_dict(updated), 200
            except Exception:
                conn.execute("ROLLBACK")
                raise
            finally:
                conn.close()

    def get_logs_for_owner(self, task_id: str, owner_user_id: str) -> Tuple[Optional[List[Dict[str, Any]]], int]:
        _, code = self.get_task_for_owner(task_id, owner_user_id)
        if code != 200:
            return None, code
        conn = self._connect()
        try:
            rows = conn.execute(
                "SELECT * FROM task_logs WHERE task_id = ? ORDER BY sequence_no ASC",
                (task_id,),
            ).fetchall()
            return [dict(r) for r in rows], 200
        finally:
            conn.close()

    def get_artifacts_for_owner(self, task_id: str, owner_user_id: str) -> Tuple[Optional[List[Dict[str, Any]]], int]:
        _, code = self.get_task_for_owner(task_id, owner_user_id)
        if code != 200:
            return None, code
        conn = self._connect()
        try:
            rows = conn.execute(
                "SELECT * FROM task_artifacts WHERE task_id = ? ORDER BY created_at ASC",
                (task_id,),
            ).fetchall()
            return [dict(r) for r in rows], 200
        finally:
            conn.close()

    def observability_snapshot(self, offline_threshold_ms: int = 45_000) -> Dict[str, Any]:
        ts = now_ms()
        conn = self._connect()
        try:
            workers = [self._worker_to_dict(r) for r in conn.execute("SELECT * FROM workers").fetchall()]
            online = [w for w in workers if not w["revoked"] and (ts - w["last_heartbeat_at"]) <= offline_threshold_ms]
            offline = [w for w in workers if w not in online]
            counts = {}
            for r in conn.execute("SELECT status, COUNT(*) AS cnt FROM remote_tasks GROUP BY status").fetchall():
                counts[r["status"]] = int(r["cnt"])
            return {
                "online_workers": len(online),
                "offline_workers": len(offline),
                "workers": workers,
                "queue_depth": counts.get("QUEUED", 0) + counts.get("RETRYING", 0),
                "queued_tasks": counts.get("QUEUED", 0),
                "running_tasks": counts.get("RUNNING", 0) + counts.get("CLAIMED", 0),
                "completed_tasks": counts.get("COMPLETED", 0),
                "failed_tasks": counts.get("FAILED", 0),
                "cancelled_tasks": counts.get("CANCELLED", 0),
                "timestamp_ms": ts,
            }
        finally:
            conn.close()

    def _task_to_dict(self, row: sqlite3.Row) -> Dict[str, Any]:
        return {
            "taskId": row["task_id"],
            "ownerUserId": row["owner_user_id"],
            "projectId": row["project_id"],
            "title": row["title"],
            "payload": json.loads(row["payload_json"]),
            "idempotencyKey": row["idempotency_key"],
            "status": row["status"],
            "retryCount": int(row["retry_count"]),
            "maxRetries": int(row["max_retries"]),
            "leaseWorkerId": row["lease_worker_id"],
            "leaseToken": int(row["lease_token"]),
            "leaseExpiresAt": int(row["lease_expires_at"]),
            "cancelRequested": bool(row["cancel_requested"]),
            "pauseRequested": bool(row["pause_requested"]),
            "completedSteps": int(row["completed_steps"]),
            "totalSteps": int(row["total_steps"]),
            "currentStepTitle": row["current_step_title"],
            "resultSummary": row["result_summary"],
            "lastError": row["last_error"],
            "createdAt": int(row["created_at"]),
            "updatedAt": int(row["updated_at"]),
        }

    def _worker_to_dict(self, row: sqlite3.Row) -> Dict[str, Any]:
        return {
            "worker_id": row["worker_id"],
            "worker_name": row["worker_name"],
            "version": row["version"],
            "capabilities": json.loads(row["capabilities_json"]),
            "isolation_mode": row["isolation_mode"],
            "status": row["status"],
            "revoked": bool(row["revoked"]),
            "last_heartbeat_at": int(row["last_heartbeat_at"]),
        }
