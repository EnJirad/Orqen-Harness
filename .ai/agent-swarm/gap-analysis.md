# ORQEN vs Agent Swarm — Gap Analysis

- **ORQEN Baseline Commit:** `f6a580c17bbc18edef011fcd67bcc519d49ceed2`
- **Reference Commit:** `desplega-ai/agent-swarm@48b21307e9ec5b03fd4a5b68c72795ab7ca4a4f1`

| Capability | ORQEN Existing System | Gap Identified | Planned Enhancement (Reuse Existing) |
|---|---|---|---|
| **Orchestration & DAG** | `AutonomousSupervisorEngine`, `DynamicTeamPlanCompiler`, `MultiPartPlanManager` | Parallel coder execution works, but lacks proactive stalled-task heartbeat sweep and deferred task gating inside `superviseProjectQueue`. | Add `sweepStalledAndOrphanedTasks()` & deferred-task gating directly in `AutonomousSupervisorEngine` (no duplicate orchestrator). |
| **Persistent Memory** | `HierarchicalMemoryManager` (plain-text append to `projectMemory` / `taskMemory` + conversation compaction) | Missing 5-scope structured storage (`PROJECT`, `AGENT`, `TASK`, `EXECUTION`, `DECISION`), SHA-256 deduplication, noise-floor guard, secret scrubbing, usefulness-ranked retrieval, and automatic task-learning distillation. | Upgrade `HierarchicalMemoryManager` in-place with structured 5-scope durable entries, dedup, access control, relevance retrieval, and auto-distillation. |
| **Context Injection** | `TaskContextBuilder` (16k char budget + parallel coder protocol) | Always injects static `projectMemory.take(800)` instead of query-relevant filtered memories across scopes. | Wire `HierarchicalMemoryManager.retrieveRelevantPromptBlock(...)` into `TaskContextBuilder` so workers only receive relevant memories. |
| **Crash & Stall Recovery** | `AutonomousRecoveryEngine`, `ExecutionLivenessMonitor`, `FileLockManager`, `ProcessRecoveryCoordinator` | Orphaned `RUNNING` steps after sudden crash/timeout need deterministic sweep to release stale file locks + leases and re-queue ready steps without repeating completed steps. | Add `sweepStalledAndOrphanedTasks()` in `AutonomousSupervisorEngine` releasing expired leases + stale file locks and re-enqueuing only unfinished steps. |
| **MCP & Security** | `McpAuthorizationEngine`, `AgentPermissionGuard`, `SecretSanitizer`, `ApiKeyVault` | Strong baseline; memory persistence must also scrub secrets before write. | Enforce `SecretSanitizer.sanitize(...)` inside `HierarchicalMemoryManager` before storing any memory entry. |
| **Android Build & Runtime** | PRoot Linux + `TeamAgentRunner` + `LocalWorkspaceWebServer` | Already native Android-first (superior to Docker-only Agent Swarm on mobile). | Preserve 100% compatibility and verify with 5-scenario benchmark + debug APK build. |
