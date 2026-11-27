# ORQEN-Harness — Quick Start (Canonical Developer & AI Agent Guide)

## Unified Autonomous AI Team Architecture
- **Authoritative Execution Lifecycle**:
  `MainViewModel` (`postTeamChatMessage` / `postTeamChatMessageWithImages` / `submitAutonomousTeamObjective`) -> `AutonomousSupervisorEngine` (`preparePlan` -> `superviseTask`) -> `MultiPartPlanManager` (`.orqen/plan/overview.md` + `part_N_<slug>.md` + `.orqen/reports/{execution,review,verification}_report.md`) -> `DynamicTeamPlanCompiler` -> `SqliteOrqenTaskStore` + `SqliteCollaborationStore` (SQLite WAL DB v8: `orqen_autonomous_tasks.db`) -> `TeamAgentRunnerRuntimeAdapter` + `SharedAgentExecutionEngine` / `WorkspaceToolExecutor` -> `AgentHealthMonitor` + `TaskWatchdog` -> `AutonomousRecoveryEngine` + `AgentFailureManager` -> `ResultVerifier` -> Verified `COMPLETED`.
- **Legacy Planner Removed**: `StructuredTaskPlanner`, fixed Inspect -> Implement -> Verify templates, and automatic `.orqen/plan.md` generation have been completely removed from the active pipeline.
- **Strict Mode Separation**: Chat Mode (`GroupMessageType.NORMAL`) is strictly consultation-only (no task creation, no tool/MCP execution, no keyword auto-switching). Task Mode explicitly synthesizes full conversation history via `SpvModePolicy.synthesizeTaskBrief` and starts autonomous execution.
- **Android Process Recovery**: `TaskRecoveryJobService` (`JobScheduler`, `RecoveryMode.AUTOMATIC`) + `ProcessRecoveryCoordinator` + persistent `ExecutionLivenessMonitor(store)`.
- **Security**: `RuntimeExecutionRequest.allowSkipPermissions` defaults to `false`; sensitive operations require explicit `OwnerApprovalRequest`; MCP tools enforce `McpAuthorizationEngine`.
- **UI Visibility**: `MainUiState.activeSupervisedTaskId` + `MainUiState.supervisorSnapshots` render live in `TeamDashboardScreen`.

## Build & Test Command (Android Debug + Full Unit Suite)
```bash
timeout 10m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline \
  --init-script /root/.gradle/init.d/pocketdev-android.gradle \
  -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 \
  testOnlineDebugUnitTest assembleOnlineDebug --console=plain --stacktrace
```
