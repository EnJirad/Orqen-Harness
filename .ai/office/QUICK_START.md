# ORQEN-Harness — Quick Start (Canonical Developer & AI Agent Guide)

## Architecture Overview
- **Authoritative Task Lifecycle**: `MainViewModel.submitAutonomousTeamObjective(...)` -> `AutonomousSupervisorEngine` -> `SqliteOrqenTaskStore` -> `TeamAgentRunnerRuntimeAdapter` (`TeamAgentRunner`) -> `AgentHealthMonitor` + `TaskWatchdog` -> `AutonomousRecoveryEngine` + `AgentFailureManager` -> `ResultVerifier` -> Verified `COMPLETED`.
- **Android Process Recovery**: `TaskRecoveryJobService` (`JobScheduler`, `RecoveryMode.AUTOMATIC`) + `ProcessRecoveryCoordinator` + persistent `ExecutionLivenessMonitor(store)`.
- **Security**: `RuntimeExecutionRequest.allowSkipPermissions` defaults to `false`; sensitive operations require explicit `OwnerApprovalRequest`.

## Build & Test Command (Android Debug + Full Unit Suite)
```bash
timeout 14m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline \
  --init-script /root/.gradle/init.d/pocketdev-android.gradle \
  -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 \
  testOnlineDebugUnitTest assembleOnlineDebug --console=plain --stacktrace
```

## Key Directories
- `app/src/main/java/com/jarves/mh/multiagent/supervisor/`: `AutonomousSupervisorEngine`, `AutonomousRecoveryEngine`, `TaskWatchdogAndHealth`
- `app/src/main/java/com/jarves/mh/multiagent/collab/`: `MultiAgentTeamCoordinator`, `AgentCollaborationCore`, `AgentGovernanceAndOrchestration`
- `app/src/main/java/com/jarves/mh/multiagent/engine/`: `PersistentTaskOrchestrator`, `RuntimeStepExecutionAdapter`, `AIExecutionRuntime`, `ResultVerifier`
- `.ai/reports/` & `.ai/testing/`: Part A.4 audit, implementation, test plan/results, acceptance, and remaining issues.
