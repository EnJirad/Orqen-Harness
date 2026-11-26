#!/usr/bin/env python3
"""
ORQEN Phase 6 — Authenticated Remote Execution HTTP API Server.

Configuration via environment variables (never hardcoded secrets):
- ORQEN_BIND_HOST (default: 127.0.0.1)
- ORQEN_BIND_PORT (default: 8787)
- ORQEN_DB_PATH (default: ./data/orqen_remote.db)
- ORQEN_CLIENT_TOKENS (comma-separated "user_id:token" pairs)
- ORQEN_WORKER_REG_SECRET (shared secret required to register a new worker)
"""

import hmac
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Dict, Optional, Tuple
from urllib.parse import urlparse

from orqen_queue import DurableRemoteQueue

MAX_PAYLOAD_BYTES = 1024 * 1024  # 1 MB


class RateLimiter:
    def __init__(self, max_requests: int = 120, window_seconds: int = 60):
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self._hits: Dict[str, list] = {}

    def allow(self, key: str) -> bool:
        now = time.time()
        bucket = [t for t in self._hits.get(key, []) if now - t < self.window_seconds]
        if len(bucket) >= self.max_requests:
            self._hits[key] = bucket
            return False
        bucket.append(now)
        self._hits[key] = bucket
        return True


def parse_client_tokens(raw: str) -> Dict[str, str]:
    """Parses 'user1:tok1,user2:tok2' -> {tok1: user1, tok2: user2}."""
    mapping: Dict[str, str] = {}
    for entry in (raw or "").split(","):
        entry = entry.strip()
        if ":" in entry:
            user_id, tok = entry.split(":", 1)
            if user_id.strip() and tok.strip():
                mapping[tok.strip()] = user_id.strip()
    return mapping


def create_remote_api_server(
    host: str,
    port: int,
    queue: DurableRemoteQueue,
    client_tokens: Dict[str, str],
    worker_reg_secret: str,
) -> ThreadingHTTPServer:
    limiter = RateLimiter(max_requests=300, window_seconds=60)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format: str, *args) -> None:
            pass  # Quiet structured logs; never leak headers/tokens to stdout

        def _send_json(self, code: int, body: dict) -> None:
            data = json.dumps(body).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _read_json(self) -> Tuple[Optional[dict], Optional[str]]:
            length = int(self.headers.get("Content-Length", "0") or "0")
            if length > MAX_PAYLOAD_BYTES:
                return None, "Payload exceeds 1 MB limit"
            if length <= 0:
                return {}, None
            raw = self.rfile.read(length)
            try:
                obj = json.loads(raw.decode("utf-8"))
                return (obj if isinstance(obj, dict) else {}), None
            except Exception:
                return None, "Invalid JSON payload"

        def _bearer(self) -> str:
            auth = self.headers.get("Authorization", "")
            if auth.lower().startswith("bearer "):
                return auth[7:].strip()
            return ""

        def _auth_user(self) -> Optional[str]:
            tok = self._bearer()
            if not tok:
                return None
            for expected_tok, user_id in client_tokens.items():
                if hmac.compare_digest(tok, expected_tok):
                    return user_id
            return None

        def _auth_worker(self, worker_id: str) -> bool:
            return queue.authenticate_worker(worker_id, self._bearer())

        def do_GET(self) -> None:
            path = urlparse(self.path).path.rstrip("/")
            if not limiter.allow(self.client_address[0]):
                return self._send_json(429, {"error": "Rate limit exceeded"})

            if path == "/v1/health":
                return self._send_json(200, {"status": "ok", **queue.observability_snapshot()})

            user_id = self._auth_user()
            if not user_id:
                return self._send_json(401, {"error": "Unauthorized"})

            if path == "/v1/tasks":
                return self._send_json(200, {"tasks": queue.list_tasks_for_owner(user_id)})

            parts = path.split("/")
            # /v1/tasks/{taskId}
            if len(parts) == 4 and parts[1] == "v1" and parts[2] == "tasks":
                task, code = queue.get_task_for_owner(parts[3], user_id)
                return self._send_json(code, task if task else {"error": "Access denied or not found"})

            # /v1/tasks/{taskId}/logs
            if len(parts) == 5 and parts[1] == "v1" and parts[2] == "tasks" and parts[4] == "logs":
                logs, code = queue.get_logs_for_owner(parts[3], user_id)
                return self._send_json(code, {"logs": logs} if logs is not None else {"error": "Access denied or not found"})

            # /v1/tasks/{taskId}/artifacts
            if len(parts) == 5 and parts[1] == "v1" and parts[2] == "tasks" and parts[4] == "artifacts":
                arts, code = queue.get_artifacts_for_owner(parts[3], user_id)
                return self._send_json(code, {"artifacts": arts} if arts is not None else {"error": "Access denied or not found"})

            self._send_json(404, {"error": "Not found"})

        def do_POST(self) -> None:
            path = urlparse(self.path).path.rstrip("/")
            if not limiter.allow(self.client_address[0]):
                return self._send_json(429, {"error": "Rate limit exceeded"})

            body, err = self._read_json()
            if err:
                return self._send_json(400, {"error": err})
            body = body or {}
            parts = path.split("/")

            # Worker registration (uses ORQEN_WORKER_REG_SECRET)
            if path == "/v1/workers/register":
                if not worker_reg_secret or not hmac.compare_digest(self._bearer(), worker_reg_secret):
                    return self._send_json(401, {"error": "Invalid worker registration secret"})
                wid = str(body.get("worker_id", "")).strip()
                if not wid:
                    return self._send_json(400, {"error": "worker_id required"})
                worker, token = queue.register_worker(
                    worker_id=wid,
                    worker_name=str(body.get("worker_name", wid)),
                    version=str(body.get("version", "1.0.0")),
                    capabilities=list(body.get("capabilities", [])),
                    isolation_mode=str(body.get("isolation_mode", "POSIX_ISOLATED_DIRECTORY")),
                )
                return self._send_json(200, {"worker": worker, "worker_token": token})

            # Worker endpoints: /v1/workers/{workerId}/...
            if len(parts) >= 5 and parts[1] == "v1" and parts[2] == "workers":
                worker_id = parts[3]
                action = parts[4]
                if action == "revoke":
                    if not worker_reg_secret or not hmac.compare_digest(self._bearer(), worker_reg_secret):
                        return self._send_json(401, {"error": "Unauthorized"})
                    ok = queue.revoke_worker(worker_id)
                    return self._send_json(200 if ok else 404, {"revoked": ok})

                if not self._auth_worker(worker_id):
                    return self._send_json(401, {"error": "Worker unauthorized or revoked"})

                if action == "heartbeat":
                    return self._send_json(200, {"ok": queue.record_worker_heartbeat(worker_id)})

                if action == "claim":
                    ttl = int(body.get("lease_ttl_ms", 30_000))
                    task = queue.claim_next_task(worker_id, lease_ttl_ms=ttl)
                    return self._send_json(200, {"task": task})

                if len(parts) == 7 and parts[4] == "tasks":
                    task_id = parts[5]
                    sub = parts[6]
                    lease_token = int(body.get("lease_token", 0))
                    if sub == "lease":
                        ok, cancelled = queue.renew_lease(task_id, worker_id, lease_token, int(body.get("lease_ttl_ms", 30_000)))
                        return self._send_json(200 if ok else 409, {"renewed": ok, "cancelRequested": cancelled})
                    if sub == "progress":
                        ok, code = queue.report_progress(
                            task_id=task_id,
                            worker_id=worker_id,
                            lease_token=lease_token,
                            completed_steps=int(body.get("completed_steps", 0)),
                            current_step_title=str(body.get("current_step_title", "")),
                            log_message=body.get("log_message"),
                            checkpoint=body.get("checkpoint"),
                        )
                        return self._send_json(code, {"ok": ok})
                    if sub == "complete":
                        task, code = queue.complete_task(
                            task_id=task_id,
                            worker_id=worker_id,
                            lease_token=lease_token,
                            result_summary=str(body.get("result_summary", "Completed")),
                            completed_steps=int(body.get("completed_steps", 1)),
                            artifacts=body.get("artifacts", []),
                        )
                        return self._send_json(code, task if task else {"error": "Lease lost or task not found"})
                    if sub == "fail":
                        task, code = queue.fail_task(
                            task_id=task_id,
                            worker_id=worker_id,
                            lease_token=lease_token,
                            error_message=str(body.get("error_message", "Execution failed")),
                            retryable=bool(body.get("retryable", True)),
                            backoff_ms=int(body.get("backoff_ms", 2_000)),
                        )
                        return self._send_json(code, task if task else {"error": "Lease lost or task not found"})

            # Authenticated User Client endpoints
            user_id = self._auth_user()
            if not user_id:
                return self._send_json(401, {"error": "Unauthorized"})

            if path == "/v1/tasks":
                idem = self.headers.get("Idempotency-Key") or str(body.get("idempotencyKey", "")).strip() or str(body.get("taskId", "")).strip()
                if not idem:
                    return self._send_json(400, {"error": "Idempotency-Key header or idempotencyKey field required"})
                task, created = queue.submit_task(
                    owner_user_id=user_id,
                    project_id=str(body.get("projectId", "default")),
                    title=str(body.get("title", "Remote Task")),
                    payload=body.get("payload") if isinstance(body.get("payload"), dict) else {},
                    idempotency_key=idem,
                    task_id=body.get("taskId"),
                    max_retries=int(body.get("maxRetries", 3)),
                )
                return self._send_json(202 if created else 200, task)

            if len(parts) == 5 and parts[1] == "v1" and parts[2] == "tasks":
                task_id = parts[3]
                act = parts[4]
                if act == "cancel" or act == "stop":
                    task, code = queue.request_cancel(task_id, user_id)
                    return self._send_json(code, task if task else {"error": "Access denied or not found"})
                if act == "pause":
                    task, code = queue.request_pause_or_resume(task_id, user_id, pause=True)
                    return self._send_json(code, task if task else {"error": "Access denied or not found"})
                if act == "resume":
                    task, code = queue.request_pause_or_resume(task_id, user_id, pause=False)
                    return self._send_json(code, task if task else {"error": "Access denied or not found"})

            self._send_json(404, {"error": "Not found"})

    return ThreadingHTTPServer((host, port), Handler)


def main() -> None:
    host = os.environ.get("ORQEN_BIND_HOST", "127.0.0.1")
    port = int(os.environ.get("ORQEN_BIND_PORT", "8787"))
    db_path = os.environ.get("ORQEN_DB_PATH", "./data/orqen_remote.db")
    client_tokens = parse_client_tokens(os.environ.get("ORQEN_CLIENT_TOKENS", ""))
    worker_reg_secret = os.environ.get("ORQEN_WORKER_REG_SECRET", "")
    if not client_tokens or not worker_reg_secret:
        raise SystemExit("Set ORQEN_CLIENT_TOKENS (user:token) and ORQEN_WORKER_REG_SECRET environment variables.")
    queue = DurableRemoteQueue(db_path)
    server = create_remote_api_server(host, port, queue, client_tokens, worker_reg_secret)
    print(f"ORQEN Remote Execution API listening on http://{host}:{port} (db={db_path})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.shutdown()


if __name__ == "__main__":
    main()
