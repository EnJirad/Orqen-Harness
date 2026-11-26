# ORQEN-Harness — Quick Start (Canonical Developer & AI Agent Guide)

## Architecture Overview
- **Authoritative Task Lifecycle**: `MainViewModel` (`postTeamChatMessage` / `postTeamChatMessageWithImages` / `startTeamWork` -> `submitAutonomousTeamObjective`) -> `AutonomousSupervisorEngine` (`preparePlan` + `onTaskCreated` -> `superviseTask`) -> `SqliteOrqenTaskStore` + `SqliteCollaborationStore` (single SQLite WAL DB v5: `orqen_autonomous_tasks.db`) -> `TeamAgentRunnerRuntimeAdapter` -> `AgentHealthMonitor` + `TaskWatchdog` -> `AutonomousRecoveryEngine` + `AgentFailureManager` -> `ResultVerifier` -> Verified `COMPLETED`.
- **Android Process Recovery**: `TaskRecoveryJobService` (`JobScheduler`, `RecoveryMode.AUTOMATIC`) + `ProcessRecoveryCoordinator` + persistent `ExecutionLivenessMonitor(store)`.
- **Security**: `RuntimeExecutionRequest.allowSkipPermissions` defaults to `false`; `AntigravityRuntimeBridge.hello()` does not pass `--dangerously-skip-permissions`; sensitive operations require explicit `OwnerApprovalRequest`.
- **UI Visibility**: `MainUiState.activeSupervisedTaskId` + `MainUiState.supervisorSnapshots` render live in `TeamDashboardScreen`.

## Build & Test Command (Android Debug + Full Unit Suite)
```bash
timeout 14m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline \
  --init-script /root/.gradle/init.d/pocketdev-android.gradle \
  -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 \
  testOnlineDebugUnitTest assembleOnlineDebug --console=plain --stacktrace
```
