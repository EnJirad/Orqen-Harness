# ORQEN Remote Execution Implementation & API Contract (Phase 6)

## Implementation & Deployment Status

| Component | Status | Location |
| :--- | :--- | :--- |
| **Durable SQLite WAL Queue & Fencing Leases** | **Implemented & Tested Locally** | `remote-executor/orqen_queue.py` |
| **Authenticated Remote Execution HTTP API** | **Implemented & Tested Locally** | `remote-executor/orqen_api.py` |
| **Authenticated Remote Worker Daemon** | **Implemented & Tested Locally** | `remote-executor/orqen_worker.py` |
| **Isolated Task Workspace (`POSIX_ISOLATED_DIRECTORY`)** | **Implemented & Tested Locally** | `remote-executor/orqen_worker.py` (`IsolatedTaskWorkspace`) |
| **Android HTTP Client Transport** | **Implemented & Tested Locally** | `app/src/main/java/com/jarves/mh/multiagent/host/HttpRemoteExecutionTransport.kt` |
| **Docker Compose Deployment Config** | **Implemented (NOT DEPLOYED)** | `remote-executor/Dockerfile`, `remote-executor/docker-compose.yml` |
| **External Cloud / Production Server** | **NOT DEPLOYED** | Requires owner-provisioned host & TLS reverse proxy |

---

## Workspace Isolation Model (Honest Guarantees)
- **Inside Docker (`DOCKER_CONTAINER`)**: `remote-executor/docker-compose.yml` runs the worker as non-root (`uid 10001`) with `mem_limit: 1g` and `cpus: 1.0`.
- **Native POSIX without Docker (`POSIX_ISOLATED_DIRECTORY`)**: Creates per-task directories with `os.path.realpath` traversal blocking, allowlisted non-shell commands (`shell=False`), and POSIX `resource.setrlimit` (`RLIMIT_CPU`, `RLIMIT_FSIZE`). It explicitly reports `POSIX_ISOLATED_DIRECTORY` and never claims container isolation when Docker is unavailable.

---

## Implemented HTTP API Endpoints

### Health & Observability
- `GET /v1/health` — Returns online/offline worker counts, queue depth, running/completed/failed counts.

### Authenticated Client Endpoints (`Authorization: Bearer <user_token>`)
- `POST /v1/tasks` (requires `Idempotency-Key` header) — Idempotent task submission (`202` created / `200` existing).
- `GET /v1/tasks` — Lists tasks owned by the authenticated user only.
- `GET /v1/tasks/{taskId}` — Returns task status, progress, lease info (`403` for other users).
- `POST /v1/tasks/{taskId}/cancel` — Persists cancellation (`owner_user_id` verified).
- `POST /v1/tasks/{taskId}/pause` / `POST /v1/tasks/{taskId}/resume` — Owner-verified pause/resume.
- `GET /v1/tasks/{taskId}/logs` — Returns secret-redacted chronological execution logs.
- `GET /v1/tasks/{taskId}/artifacts` — Returns SHA-256 artifact manifests.

### Authenticated Worker Endpoints
- `POST /v1/workers/register` (`Authorization: Bearer <ORQEN_WORKER_REG_SECRET>`) — Registers worker and issues per-worker token.
- `POST /v1/workers/{workerId}/revoke` — Revokes worker token immediately.
- `POST /v1/workers/{workerId}/heartbeat` (`Authorization: Bearer <worker_token>`) — Updates worker liveness.
- `POST /v1/workers/{workerId}/claim` — Atomically claims 1 eligible task (`BEGIN IMMEDIATE`), reclaims expired leases, increments monotonic `leaseToken`.
- `POST /v1/workers/{workerId}/tasks/{taskId}/lease` — Renews lease & returns `cancelRequested`.
- `POST /v1/workers/{workerId}/tasks/{taskId}/progress` — Commits progress, logs, and checkpoints (validates `leaseToken`, rejects stale workers with `409`).
- `POST /v1/workers/{workerId}/tasks/{taskId}/complete` — Idempotent completion with `leaseToken` fencing.
- `POST /v1/workers/{workerId}/tasks/{taskId}/fail` — Records failure with bounded retry backoff.

---

## Running Locally

```bash
# Terminal 1: Start Remote Execution API
export ORQEN_CLIENT_TOKENS="owner:replace-with-strong-user-token"
export ORQEN_WORKER_REG_SECRET="replace-with-strong-worker-secret"
python3 remote-executor/orqen_api.py

# Terminal 2: Start Authenticated Remote Worker Daemon
export ORQEN_API_URL="http://127.0.0.1:8787"
export ORQEN_WORKER_ID="worker-1"
export ORQEN_WORKER_REG_SECRET="replace-with-strong-worker-secret"
python3 remote-executor/orqen_worker.py

# Run Python End-to-End Integration Tests
python3 -m unittest discover -s remote-executor -p "test_*.py" -v
```
