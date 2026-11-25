# ORQEN Remote Execution Host Contract

## Status
- **Implemented in this repository**:
  - `ExecutionHost` abstraction (`app/src/main/java/com/jarves/mh/multiagent/host/ExecutionHost.kt`)
  - `LocalAndroidExecutionHost` (production on-device execution)
  - `RemoteExecutionHost` mobile client adapter + `RemoteExecutionTransport` interface (supports disconnect/reconnect with preserved `taskId` and progress)
- **Not deployed in this repository** (requires external server infrastructure):
  - Remote worker daemon, PostgreSQL/Redis durable queue, and remote container workspace host.

## Backend API Contract (HTTP/JSON + SSE/WebSocket)

All endpoints require `Authorization: Bearer <user_scoped_token>` stored in Android Keystore/EncryptedSharedPreferences. Provider API keys remain server-side or encrypted per-user vault and are never hardcoded.

### 1. Submit / Attach Task
`POST /v1/tasks`
- Request: `{ "taskId": "T-...", "projectId": "...", "title": "...", "steps": [...], "budget": {...}, "completionCriteria": {...} }`
- Response `202 Accepted`: `{ "taskId": "T-...", "leaseId": "lease-...", "status": "QUEUED" }`

### 2. Query Task Progress (Disconnect/Reconnect Safe)
`GET /v1/tasks/{taskId}`
- Response `200 OK`:
  ```json
  {
    "taskId": "T-...",
    "status": "RUNNING",
    "completedSteps": 14,
    "totalSteps": 22,
    "currentStepTitle": "Implement auth middleware",
    "latestCheckpointId": "chk-...",
    "actualCostUsd": 0.42,
    "lastHeartbeatAt": 1764080000000,
    "lastError": null
  }
  ```

### 3. Stream Live Events (Optional SSE)
`GET /v1/tasks/{taskId}/events?afterEventId=...`
- Reconnectable Server-Sent Events stream for step completions, agent messages, checkpoints, and errors.

### 4. Owner Execution Controls
- `POST /v1/tasks/{taskId}/pause` — stops scheduling new steps, preserves checkpoint
- `POST /v1/tasks/{taskId}/resume` — resumes from next safe step after workspace verification
- `POST /v1/tasks/{taskId}/stop` — sets `ownerStopRequested=true`, cancels active worker lease, never auto-restarts
- `POST /v1/tasks/stop-all` — cancels all active tasks owned by the authenticated user

### 5. Worker Lease & Heartbeat Semantics
- Workers acquire time-bounded leases (`leaseTtlMs = 60_000`) and renew heartbeats every `15_000ms`.
- If a worker crashes and misses 3 heartbeats, the server supervisor reconciles the workspace against the latest checkpoint, checks the idempotency ledger (`idempotencyKey`), and reassigns only uncompleted steps.
- Every model request is an individual bounded step — never a single long-lived HTTP call for the entire task.
