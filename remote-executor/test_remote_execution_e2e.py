#!/usr/bin/env python3
"""
Real End-to-End Integration Tests for ORQEN Phase 6 Remote Execution
(Durable SQLite WAL Queue + Live HTTP API + Authenticated Multi-Worker Daemons + Isolated Workspaces).
"""

import json
import os
import shutil
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

from orqen_api import create_remote_api_server
from orqen_queue import DurableRemoteQueue
from orqen_worker import IsolatedTaskWorkspace, RemoteWorkerDaemon


class RemoteExecutionE2ETest(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix="orqen_p6_")
        self.db_path = os.path.join(self.tmpdir, "remote_queue.db")
        self.ws_root = os.path.join(self.tmpdir, "workspaces")
        self.client_tokens = {"tok-alice": "user-alice", "tok-bob": "user-bob"}
        self.reg_secret = "test-reg-secret-999"
        self._start_server()

    def _start_server(self):
        self.queue = DurableRemoteQueue(self.db_path)
        self.server = create_remote_api_server("127.0.0.1", 0, self.queue, self.client_tokens, self.reg_secret)
        self.port = self.server.server_address[1]
        self.base_url = f"http://127.0.0.1:{self.port}"
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()

    def _stop_server(self):
        self.server.shutdown()
        self.server.server_close()

    def tearDown(self):
        self._stop_server()
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def _http(self, method: str, path: str, body=None, token=None, headers=None):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(f"{self.base_url}{path}", data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8"))

    # End-to-end: submit -> authenticated worker claims -> executes in isolated workspace -> logs & artifacts returned
    def test_01_full_e2e_submit_claim_execute_artifacts_and_logs(self):
        worker = RemoteWorkerDaemon(self.base_url, "wkr-1", "Worker 1", self.reg_secret, self.ws_root)
        worker.register()
        self.assertTrue(worker.heartbeat())

        code, task = self._http(
            "POST",
            "/v1/tasks",
            {
                "taskId": "RT-E2E-1",
                "projectId": "proj-velnox",
                "title": "Build module",
                "payload": {
                    "steps": [
                        {"type": "write_files", "title": "Write code", "files": {"src/hello.py": "print('ok')\n"}},
                        {"type": "run_allowed_check", "title": "Verify python", "argv": ["python3", "src/hello.py"]},
                    ]
                },
            },
            token="tok-alice",
            headers={"Idempotency-Key": "idem-e2e-1"},
        )
        self.assertEqual(202, code)
        self.assertEqual("RT-E2E-1", task["taskId"])

        processed = worker.run_once()
        self.assertEqual("RT-E2E-1", processed)

        code, status = self._http("GET", "/v1/tasks/RT-E2E-1", token="tok-alice")
        self.assertEqual(200, code)
        self.assertEqual("COMPLETED", status["status"])
        self.assertEqual(2, status["completedSteps"])

        code, logs = self._http("GET", "/v1/tasks/RT-E2E-1/logs", token="tok-alice")
        self.assertEqual(200, code)
        self.assertEqual(2, len(logs["logs"]))

        code, arts = self._http("GET", "/v1/tasks/RT-E2E-1/artifacts", token="tok-alice")
        self.assertEqual(200, code)
        self.assertEqual("src/hello.py", arts["artifacts"][0]["relative_path"])

        # Observability health reflects real state
        code, health = self._http("GET", "/v1/health")
        self.assertEqual(200, code)
        self.assertEqual(1, health["online_workers"])
        self.assertEqual(1, health["completed_tasks"])

    # Scenario D: Server restart preserves durable queued tasks
    def test_02_server_restart_preserves_durable_queue(self):
        code, _ = self._http(
            "POST", "/v1/tasks",
            {"taskId": "RT-RESTART", "projectId": "p", "title": "Survive Restart", "payload": {}},
            token="tok-alice", headers={"Idempotency-Key": "idem-restart"},
        )
        self.assertEqual(202, code)

        # Restart HTTP server & reopen SQLite DB from disk
        self._stop_server()
        self._start_server()

        code, task = self._http("GET", "/v1/tasks/RT-RESTART", token="tok-alice")
        self.assertEqual(200, code)
        self.assertEqual("QUEUED", task["status"])

    # Scenario E & F: Idempotent submission + idempotent completion retry
    def test_03_idempotent_submission_and_completion_retry(self):
        code1, t1 = self._http(
            "POST", "/v1/tasks",
            {"taskId": "RT-IDEM", "projectId": "p", "title": "Idem", "payload": {}},
            token="tok-alice", headers={"Idempotency-Key": "same-key"},
        )
        code2, t2 = self._http(
            "POST", "/v1/tasks",
            {"taskId": "RT-IDEM-DUP", "projectId": "p", "title": "Idem", "payload": {}},
            token="tok-alice", headers={"Idempotency-Key": "same-key"},
        )
        self.assertEqual(202, code1)
        self.assertEqual(200, code2)
        self.assertEqual(t1["taskId"], t2["taskId"])

        _, w_tok = self.queue.register_worker("w-idem", "W", "1.0", [])
        claimed = self.queue.claim_next_task("w-idem")
        c1, _ = self._http("POST", f"/v1/workers/w-idem/tasks/{claimed['taskId']}/complete", {"lease_token": claimed["leaseToken"], "result_summary": "Done", "completed_steps": 1}, token=w_tok)
        c2, _ = self._http("POST", f"/v1/workers/w-idem/tasks/{claimed['taskId']}/complete", {"lease_token": claimed["leaseToken"], "result_summary": "Done", "completed_steps": 1}, token=w_tok)
        self.assertEqual(200, c1)
        self.assertEqual(200, c2)

    # Scenario H: Two concurrent workers race to claim 1 task -> only 1 wins!
    def test_04_concurrent_multi_worker_atomic_claim(self):
        self.queue.submit_task("user-alice", "p", "Race Task", {}, "idem-race", task_id="RT-RACE")
        self.queue.register_worker("w-A", "A", "1.0", [])
        self.queue.register_worker("w-B", "B", "1.0", [])

        winners = []
        lock = threading.Lock()

        def try_claim(wid):
            res = self.queue.claim_next_task(wid)
            if res:
                with lock:
                    winners.append((wid, res["taskId"]))

        t1 = threading.Thread(target=try_claim, args=("w-A",))
        t2 = threading.Thread(target=try_claim, args=("w-B",))
        t1.start(); t2.start(); t1.join(); t2.join()
        self.assertEqual(1, len(winners))

    # Scenario C & J: Worker crash -> lease expires -> second worker reclaims -> stale worker rejected (409)
    def test_05_worker_crash_lease_expiry_and_stale_fencing(self):
        self.queue.submit_task("user-alice", "p", "Crash Task", {}, "idem-crash", task_id="RT-CRASH")
        _, tok1 = self.queue.register_worker("w-dead", "Dead", "1.0", [])
        _, tok2 = self.queue.register_worker("w-live", "Live", "1.0", [])

        claimed1 = self.queue.claim_next_task("w-dead", lease_ttl_ms=500, current_ms=10_000)
        self.assertEqual(1, claimed1["leaseToken"])

        # Lease expires at t=11_000; w-live reclaims with fencing token 2
        claimed2 = self.queue.claim_next_task("w-live", lease_ttl_ms=30_000, current_ms=11_000)
        self.assertIsNotNone(claimed2)
        self.assertEqual("RT-CRASH", claimed2["taskId"])
        self.assertEqual(2, claimed2["leaseToken"])

        # Stale worker w-dead tries to commit result -> REJECTED with 409!
        stale_code, _ = self._http(
            "POST", "/v1/workers/w-dead/tasks/RT-CRASH/complete",
            {"lease_token": 1, "result_summary": "Stale overwrite", "completed_steps": 1},
            token=tok1,
        )
        self.assertEqual(409, stale_code)

        # Active worker w-live commits cleanly with fencing token 2
        live_code, done = self._http(
            "POST", "/v1/workers/w-live/tasks/RT-CRASH/complete",
            {"lease_token": 2, "result_summary": "Recovered by w-live", "completed_steps": 1},
            token=tok2,
        )
        self.assertEqual(200, live_code)
        self.assertEqual("COMPLETED", done["status"])

    # Scenario I + Worker Revocation + Cancellation (Scenario G)
    def test_06_authorization_revocation_and_cancellation(self):
        self._http("POST", "/v1/tasks", {"taskId": "RT-PRIV", "projectId": "p", "title": "Alice Only", "payload": {}}, token="tok-alice", headers={"Idempotency-Key": "idem-priv"})

        # Bob cannot read or cancel Alice's task -> 403
        code_get, _ = self._http("GET", "/v1/tasks/RT-PRIV", token="tok-bob")
        code_cancel, _ = self._http("POST", "/v1/tasks/RT-PRIV/cancel", {}, token="tok-bob")
        self.assertEqual(403, code_get)
        self.assertEqual(403, code_cancel)

        # Alice cancels -> 200 CANCELLED
        code_ok, cancelled = self._http("POST", "/v1/tasks/RT-PRIV/cancel", {}, token="tok-alice")
        self.assertEqual(200, code_ok)
        self.assertEqual("CANCELLED", cancelled["status"])

        # Revoked worker rejected -> 401
        _, w_tok = self.queue.register_worker("w-rev", "Rev", "1.0", [])
        self._http("POST", "/v1/workers/w-rev/revoke", {}, token=self.reg_secret)
        code_hb, _ = self._http("POST", "/v1/workers/w-rev/heartbeat", {}, token=w_tok)
        self.assertEqual(401, code_hb)

    # Security: Path traversal, command injection & secret redaction in logs
    def test_07_workspace_isolation_command_allowlist_and_secret_redaction(self):
        ws = IsolatedTaskWorkspace(self.ws_root, "RT-SEC")
        with self.assertRaises(PermissionError):
            ws.write_file("../other_task/pwn.txt", "bad")
        with self.assertRaises(PermissionError):
            ws.run_allowlisted_command(["sh", "-c", "echo pwned"])

        self.queue.submit_task("user-alice", "p", "Secret Log", {}, "idem-sec", task_id="RT-SEC")
        _, w_tok = self.queue.register_worker("w-sec", "Sec", "1.0", [])
        claimed = self.queue.claim_next_task("w-sec")
        self._http(
            "POST", "/v1/workers/w-sec/tasks/RT-SEC/progress",
            {"lease_token": claimed["leaseToken"], "completed_steps": 1, "current_step_title": "Run", "log_message": "Error with sk-ant-secret1234567890123"},
            token=w_tok,
        )
        _, logs = self._http("GET", "/v1/tasks/RT-SEC/logs", token="tok-alice")
        self.assertNotIn("sk-ant-secret", logs["logs"][0]["message"])
        self.assertIn("[REDACTED", logs["logs"][0]["message"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
