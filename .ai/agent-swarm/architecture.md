# ORQEN Enhanced Multi-Agent Architecture Design

## 1. Design Principle: Zero Duplication
All enhancements extend ORQEN's existing production subsystems:
- **Unified Orchestration & Autonomous Manager:** `AutonomousSupervisorEngine` + `MultiPartPlanManager` + `DynamicTeamPlanCompiler`
- **Independent Agent Runtime:** `RuntimeStepExecutionAdapter` + `TeamAgentRunner` + `AgentSessionManager` + `WorkspaceToolExecutor` + `LocalWorkspaceWebServer`
- **Structured Communication:** `AgentCommunicationManager` + `TeamEventBus` + `OrqenTaskStore`
- **5-Scope Persistent Memory Engine:** Upgraded `HierarchicalMemoryManager` (persisted via `OrqenTaskStore`)
- **Self-Recovery & Watchdog:** `AutonomousRecoveryEngine` + `TaskWatchdog` + `ExecutionLivenessMonitor` + `FileLockManager` + `ProcessRecoveryCoordinator`
- **Security & MCP:** `McpAuthorizationEngine` + `AgentPermissionGuard` + `SecretSanitizer` + `ApiKeyVault`

## 2. 5-Scope Persistent Memory Engine (`HierarchicalMemoryManager`)
- **Scopes:**
  - `PROJECT`: Shared architecture, conventions, build rules, verified deliverables for a project.
  - `AGENT`: Agent-specific expertise, role learnings, prior review findings (access-controlled to owner agent + Manager/Lead).
  - `TASK`: Task objective, constraints, acceptance criteria, active blockers.
  - `EXECUTION`: Verified tool/step outcomes, modified files, test/build results.
  - `DECISION`: Manager decisions, model failovers, retries, replans, and rationale.
- **Reliability & Efficiency Guarantees:**
  - **Secret Scrubbing:** Every entry passes through `SecretSanitizer.sanitize(...)` before hashing or storage.
  - **Noise Floor:** Rejects empty or trivial status noise (`< 24` chars).
  - **SHA-256 Deduplication:** Identical/replayed learnings increment `usefulnessScore` on the existing entry instead of duplicating rows.
  - **Relevance-Filtered Retrieval:** Offline-first scoring combining token overlap with current step/query, recency decay, `usefulnessScore`, and `accessCount`. Only Top-K entries are injected into `TaskContextBuilder`.
  - **Durable Persistence:** Stored atomically via `OrqenTaskStore` (`PersistentChatDraftEntity` JSON index per project + legacy `projectMemory` compatibility), surviving app restarts and process death with zero destructive DB migration.

## 3. Autonomous Execution & Stalled-Task Heartbeat Sweep
- `AutonomousSupervisorEngine.sweepStalledAndOrphanedTasks(projectId, staleThresholdMs)`:
  - Scans `RUNNING` tasks/steps whose heartbeat/lease is stale.
  - Releases orphaned `FileLockManager` locks held by stalled agents.
  - Transitions unfinished `RUNNING` steps back to `READY` (while preserving all `COMPLETED` steps and valid checkpoints!).
  - Records a `STALLED_TASK_RECOVERED` audit event and `DECISION` memory entry.
- Post-step and post-decision hooks automatically distill verified learnings into `HierarchicalMemoryManager`.
- `superviseProjectQueue` honors deferred/scheduled readiness and drains queued tasks cleanly under one unified loop.
