# Agent Swarm Architecture Research (Source-Verified)

- **Reference Repository:** `https://github.com/desplega-ai/agent-swarm.git`
- **Inspected Commit SHA:** `48b21307e9ec5b03fd4a5b68c72795ab7ca4a4f1` (`v2.16.0`)
- **Audit Date:** 2026-11-27
- **Method:** Direct source code inspection (`src/`, `package.json`, `CLAUDE.md`, `src/workflows/`, `src/be/memory/`, `src/heartbeat/`, `src/scheduler/`, `src/tools/`, `src/commands/`). No README-only claims included.

---

## 2.1 Agent Orchestration

- **How it works (Source-Verified):**
  - Central API server (`src/http.ts`, `src/server.ts`) owns the database (SQLite WAL or Postgres). Workers (`src/commands/runner.ts`, `worker.ts`, `lead.ts`) never touch the DB directly; they communicate over HTTP + MCP tools (`send-task.ts`, `poll-task.ts`, `steer-task.ts`, `cancel-task.ts`, `task-dedup.ts`).
  - Workflow DAG engine (`src/workflows/engine.ts`) supports entry nodes, successor routing (`successor-routing.ts`), `foreach` fan-out/join (`foreach-driver.ts`, `foreach-join.ts`), sub-workflows, node deadlines (`node-timeout.ts`), output schema validation (`json-schema-validator.ts`), and pinned workflow definitions per run.
- **Tech Stack:** Bun 1.4.4, TypeScript, `bun:sqlite` / Postgres, MCP HTTP/Stdio, Zod.
- **Strengths:** Strong DB-boundary discipline; pinned workflow definitions prevent mid-run drift; explicit `foreach` parallel fan-out/join; idempotency keys per step; task deduplication (`task-dedup.ts`).
- **Limitations:** Heavy server/client split designed for always-on cloud/Docker hosts; massive runner (`src/commands/runner.ts` ~330 KB) tightly coupled to Bun & Docker.
- **ORQEN Applicability & Android Adaptation:** Adopt **pinned plan/step idempotency, task deduplication, and DAG recovery** directly inside ORQEN's existing `AutonomousSupervisorEngine` + `OrqenTaskStore` (in-process Kotlin/SQLite WAL instead of an external Bun HTTP server).

## 2.2 Agent Runtime

- **How it works (Source-Verified):**
  - Workers run inside isolated Docker containers (`Dockerfile.worker`, `src/commands/runner.ts`, `e2b.ts`) executing Claude Code / Codex / Gemini CLI with per-agent `X-Agent-ID` headers.
  - Heartbeat monitor (`src/heartbeat/heartbeat.ts`, default 90s interval) sweeps stalled in-progress tasks (`getStalledInProgressTasks`), releases stale offered tasks/inbox locks, expires stale runtime instances, and prevents control-plane infinite auto-resume loops via `SKIP_AUTO_RESUME_TYPES`.
  - Recovery (`src/workflows/recovery.ts`) resumes interrupted `running` runs from checkpointed completed node IDs (`recoverRunningRuns`, `recoverWaitingRuns`, `recoverApprovalWaitingRuns`).
- **Strengths:** Clean crash-recovery sweep separating control-plane tasks from user tasks; explicit lease/session cleanup; secret scrubbing at every egress (`src/utils/secret-scrubber.ts`).
- **Limitations:** Requires Docker daemon or E2B sandboxes (unavailable natively on Android).
- **ORQEN Applicability & Android Adaptation:** Replace Docker containers with ORQEN's existing **PRoot Linux guest + `AgentSessionManager` + `FileLockManager` + `RuntimeExecutionService` (Foreground Service)**. Adopt the **stalled-task heartbeat sweep & orphaned-lock cleanup** directly in `AutonomousSupervisorEngine`.

## 2.3 Memory System

- **How it works (Source-Verified):**
  - `src/be/memory/types.ts` defines a swappable `MemoryStore` with explicit scopes (`agent` vs `swarm`), `key` paths (`/learnings/<workstream>/<slug>`, `/longterm/...`), `sourceTaskId`, `accessCount`, expiry (`expiresAt`), and Beta-Binomial usefulness posteriors (`alpha`, `beta`) used by `reranker.ts` (composite score = recency x source x usefulness x access).
  - `src/be/memory/task-learning.ts` automatically distills learnings on `store-progress`, applying:
    1. Secret scrubbing (`scrubSecrets`) before deriving keys/hashes.
    2. Noise-floor filtering (`LEARNING_MIN_CHARS`, rejects trivial progress restatements).
    3. Replay/duplicate suppression via SHA-256 hash (`contentSha256`) and similarity check (incrementing usefulness rating on duplicates instead of inserting noise).
- **Strengths:** Prevents context bloat by retrieving only top-ranked relevant memories; automatic task learning compounds team intelligence across sessions; built-in secret scrubbing.
- **Limitations:** Relies on external OpenAI embedding API calls (`OpenAIEmbeddingProvider`) for vector search, which adds latency/cost and fails offline on mobile.
- **ORQEN Applicability & Android Adaptation:** Adopt **5-scope persistent memory (`PROJECT`, `AGENT`, `TASK`, `EXECUTION`, `DECISION`), SHA-256 deduplication, noise-floor guard, secret scrubbing (`SecretSanitizer`), usefulness-weighted relevance retrieval (offline-first TF/keyword + recency + usefulness scoring — zero extra API cost!), and automatic task-learning distillation** inside ORQEN's existing `HierarchicalMemoryManager`.

## 2.4 Collaboration System

- **How it works (Source-Verified):**
  - Structured inter-agent tools (`post-message.ts`, `read-messages.ts`, `steer-task.ts`, `accept-steer.ts`, `send-task.ts`).
  - Unhandled steering messages are carried over (`src/be/steering`) so owner instructions never vanish mid-step.
  - Independent review gates & approval sweeps (`src/be/approval-sweeps.ts`).
- **ORQEN Applicability & Android Adaptation:** ORQEN already has `AgentCommunicationManager`, `AgentReviewManager`, `TeamEventBus`, `FileLockManager`, and `McpAuthorizationEngine`. Enhance by feeding structured review findings & inter-agent outcomes into `HierarchicalMemoryManager` and enforcing non-empty rework feedback loops.

## 2.5 Automation

- **How it works (Source-Verified):**
  - `src/scheduler/scheduler.ts` polls cron schedules (`cron-parser`) and deferred task waits (`deferred-task-waits.ts`), dispatching scripts, workflows, or standalone tasks through the same orchestration path.
- **ORQEN Applicability & Android Adaptation:** Android restricts arbitrary background polling (Doze mode). ORQEN already uses `TaskRecoveryJobService` (`JobScheduler`) + `superviseProjectQueue`. Adding `scheduledAtMs` / deferred readiness support directly in `AutonomousSupervisorEngine.superviseProjectQueue` keeps everything Android-compliant and unified.

## 2.6 Infrastructure

- **How it works (Source-Verified):**
  - Docker Compose (`docker-compose.local.yml`, `Dockerfile`, `Dockerfile.worker`, `Dockerfile.ui`), Bun runtime, SQLite WAL or PostgreSQL with forward-only SQL migrations, RBAC (`src/rbac/`), OpenAPI codegen.
- **Limitations on Android:** Neither Docker nor Bun runs natively inside Android app sandboxes.
- **ORQEN Adaptation:** Keep ORQEN's 100% native Android stack (Kotlin + Jetpack Compose + SQLite WAL + Android Keystore + PRoot Linux + Foreground Service) while adopting Agent Swarm's proven orchestration, memory, and recovery algorithms.
