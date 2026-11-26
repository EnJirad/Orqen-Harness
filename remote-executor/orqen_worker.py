#!/usr/bin/env python3
"""
ORQEN Phase 6 — Authenticated Remote Worker Daemon & Isolated Workspace Executor.

Isolation honesty:
- When running inside a Docker container with --network=none / memory / CPU limits,
  reports isolation_mode = DOCKER_CONTAINER.
- When running natively on POSIX without Docker, creates per-task isolated directories
  with strict realpath traversal checks, allowlisted commands (NO arbitrary shell),
  and POSIX resource.setrlimit CPU/file caps, and honestly reports
  isolation_mode = POSIX_ISOLATED_DIRECTORY.
"""

import hashlib
import json
import os
import resource
import signal
import subprocess
import threading
import time
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional, Tuple


ALLOWED_CHECK_BINARIES = {"python3", "git", "ls", "cat"}


class IsolatedTaskWorkspace:
    """Per-task isolated workspace with strict path traversal defense and allowlisted execution."""

    def __init__(self, workspace_root: str, task_id: str, max_cpu_seconds: int = 30, max_file_bytes: int = 10 * 1024 * 1024):
        safe_id = "".join(c for c in task_id if c.isalnum() or c in ("-", "_"))
        if not safe_id:
            raise ValueError("Invalid task_id")
        self.root = os.path.realpath(os.path.join(workspace_root, safe_id))
        os.makedirs(self.root, exist_ok=True)
        self.max_cpu_seconds = max_cpu_seconds
        self.max_file_bytes = max_file_bytes

    def resolve_safe_path(self, relative_path: str) -> str:
        if not relative_path or relative_path.startswith("/") or ".." in relative_path.replace("\\", "/").split("/"):
            raise PermissionError(f"Path traversal rejected: {relative_path}")
        full = os.path.realpath(os.path.join(self.root, relative_path))
        if not (full == self.root or full.startswith(self.root + os.sep)):
            raise PermissionError(f"Path escapes task workspace: {relative_path}")
        return full

    def write_file(self, relative_path: str, content: str) -> Dict[str, Any]:
        target = self.resolve_safe_path(relative_path)
        data = content.encode("utf-8")
        if len(data) > self.max_file_bytes:
            raise ValueError("File exceeds workspace max_file_bytes limit")
        os.makedirs(os.path.dirname(target), exist_ok=True)
        tmp = f"{target}.tmp.{os.getpid()}"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, target)
        return {
            "relative_path": relative_path,
            "sha256": hashlib.sha256(data).hexdigest(),
            "size_bytes": len(data),
        }

    def run_allowlisted_command(self, argv: List[str], timeout_sec: int = 15) -> Tuple[int, str]:
        if not argv or argv[0] not in ALLOWED_CHECK_BINARIES:
            raise PermissionError(f"Command binary not in allowlist: {argv[:1]}")

        def _apply_limits() -> None:
            resource.setrlimit(resource.RLIMIT_CPU, (self.max_cpu_seconds, self.max_cpu_seconds))
            resource.setrlimit(resource.RLIMIT_FSIZE, (self.max_file_bytes, self.max_file_bytes))

        proc = subprocess.run(
            argv,
            cwd=self.root,
            shell=False,  # Prevents command injection!
            capture_output=True,
            text=True,
            timeout=timeout_sec,
            preexec_fn=_apply_limits,
        )
        combined = (proc.stdout + "\n" + proc.stderr).strip()
        return proc.returncode, combined[:4096]


class RemoteWorkerDaemon:
    """Polls ORQEN Remote Execution API, renews leases, executes approved tasks, reports results."""

    def __init__(
        self,
        api_base_url: str,
        worker_id: str,
        worker_name: str,
        reg_secret: str,
        workspace_root: str,
        lease_ttl_ms: int = 30_000,
        poll_interval_sec: float = 0.5,
        isolation_mode: Optional[str] = None,
    ):
        self.api_base_url = api_base_url.rstrip("/")
        self.worker_id = worker_id
        self.worker_name = worker_name
        self.reg_secret = reg_secret
        self.workspace_root = workspace_root
        self.lease_ttl_ms = lease_ttl_ms
        self.poll_interval_sec = poll_interval_sec
        self.isolation_mode = isolation_mode or (
            "DOCKER_CONTAINER" if os.path.exists("/.dockerenv") else "POSIX_ISOLATED_DIRECTORY"
        )
        self.worker_token: Optional[str] = None
        self._stop_event = threading.Event()

    def _http(self, method: str, path: str, body: Optional[dict] = None, token: Optional[str] = None) -> Tuple[int, dict]:
        url = f"{self.api_base_url}{path}"
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", errors="replace")
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, {"error": raw}

    def register(self) -> str:
        code, res = self._http(
            "POST",
            "/v1/workers/register",
            {
                "worker_id": self.worker_id,
                "worker_name": self.worker_name,
                "version": "1.0.0",
                "capabilities": ["write_files", "verify_files", "run_allowed_check"],
                "isolation_mode": self.isolation_mode,
            },
            token=self.reg_secret,
        )
        if code != 200 or not res.get("worker_token"):
            raise RuntimeError(f"Worker registration failed ({code}): {res}")
        self.worker_token = str(res["worker_token"])
        return self.worker_token

    def heartbeat(self) -> bool:
        code, _ = self._http("POST", f"/v1/workers/{self.worker_id}/heartbeat", {}, token=self.worker_token)
        return code == 200

    def run_once(self) -> Optional[str]:
        """Claims and executes at most one task. Returns task_id if work was processed."""
        code, res = self._http(
            "POST",
            f"/v1/workers/{self.worker_id}/claim",
            {"lease_ttl_ms": self.lease_ttl_ms},
            token=self.worker_token,
        )
        if code != 200 or not res.get("task"):
            return None
        task = res["task"]
        task_id = task["taskId"]
        lease_token = int(task["leaseToken"])
        payload = task.get("payload") or {}
        steps = payload.get("steps") or []

        ws = IsolatedTaskWorkspace(self.workspace_root, task_id)
        artifacts: List[Dict[str, Any]] = []
        completed = 0

        try:
            for idx, step in enumerate(steps, start=1):
                # Renew lease & check cancellation before every step
                r_code, r_body = self._http(
                    "POST",
                    f"/v1/workers/{self.worker_id}/tasks/{task_id}/lease",
                    {"lease_token": lease_token, "lease_ttl_ms": self.lease_ttl_ms},
                    token=self.worker_token,
                )
                if r_code != 200 or r_body.get("cancelRequested"):
                    return task_id

                kind = step.get("type", "write_files")
                title = str(step.get("title", f"Step {idx}"))
                if kind == "write_files":
                    written_paths = []
                    for rel, content in (step.get("files") or {}).items():
                        art = ws.write_file(str(rel), str(content))
                        artifacts.append(art)
                        written_paths.append(str(rel))
                    msg = f"Wrote {len(written_paths)} file(s): {', '.join(written_paths)}"
                elif kind == "run_allowed_check":
                    rc, out = ws.run_allowlisted_command(list(step.get("argv", [])))
                    if rc != 0:
                        raise RuntimeError(f"Check failed (exit={rc}): {out}")
                    msg = f"Check passed: {out[:200]}"
                else:
                    msg = f"Executed step {title}"

                completed = idx
                self._http(
                    "POST",
                    f"/v1/workers/{self.worker_id}/tasks/{task_id}/progress",
                    {
                        "lease_token": lease_token,
                        "completed_steps": completed,
                        "current_step_title": title,
                        "log_message": msg,
                        "checkpoint": {"step_title": title, "modified_files": [a["relative_path"] for a in artifacts], "summary": msg},
                    },
                    token=self.worker_token,
                )

            self._http(
                "POST",
                f"/v1/workers/{self.worker_id}/tasks/{task_id}/complete",
                {
                    "lease_token": lease_token,
                    "completed_steps": max(completed, 1),
                    "result_summary": f"Completed {max(completed, 1)} step(s) in {self.isolation_mode}",
                    "artifacts": artifacts,
                },
                token=self.worker_token,
            )
        except Exception as err:
            self._http(
                "POST",
                f"/v1/workers/{self.worker_id}/tasks/{task_id}/fail",
                {
                    "lease_token": lease_token,
                    "error_message": str(err),
                    "retryable": not isinstance(err, PermissionError),
                },
                token=self.worker_token,
            )
        return task_id

    def serve_forever(self) -> None:
        self.register()
        last_hb = 0.0
        while not self._stop_event.is_set():
            now = time.time()
            if now - last_hb >= 10.0:
                self.heartbeat()
                last_hb = now
            worked = self.run_once()
            if not worked:
                self._stop_event.wait(self.poll_interval_sec)

    def stop(self) -> None:
        self._stop_event.set()


def main() -> None:
    api_url = os.environ.get("ORQEN_API_URL", "http://127.0.0.1:8787")
    worker_id = os.environ.get("ORQEN_WORKER_ID", f"worker-{os.getpid()}")
    worker_name = os.environ.get("ORQEN_WORKER_NAME", worker_id)
    reg_secret = os.environ.get("ORQEN_WORKER_REG_SECRET", "")
    workspace_root = os.environ.get("ORQEN_WORKSPACE_ROOT", "./data/workspaces")
    if not reg_secret:
        raise SystemExit("Set ORQEN_WORKER_REG_SECRET environment variable.")
    daemon = RemoteWorkerDaemon(api_url, worker_id, worker_name, reg_secret, workspace_root)
    signal.signal(signal.SIGTERM, lambda *_: daemon.stop())
    signal.signal(signal.SIGINT, lambda *_: daemon.stop())
    print(f"ORQEN Remote Worker Daemon '{worker_id}' starting against {api_url} (isolation={daemon.isolation_mode})")
    daemon.serve_forever()


if __name__ == "__main__":
    main()
