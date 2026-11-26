# ORQEN-Harness — PART A.4 Test Plan

## Test Environment
- **Host**: Linux x86_64 sandbox with JDK 17, Android SDK build-tools 35.0.0, Gradle 8.14.3 (`--no-daemon --max-workers=2 --offline`).
- **Limitations**: No physical Android device/emulator and no live external LLM API keys in sandbox. All automated tests execute real orchestration, watchdog, recovery, failover, lease, and verification logic with controlled boundary doubles for external providers.

## Command Executed
```bash
timeout 14m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline \
  --init-script /root/.gradle/init.d/pocketdev-android.gradle \
  -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 \
  testOnlineDebugUnitTest assembleOnlineDebug --console=plain --stacktrace
```

## Scenarios Covered
1. **A4-01 (`PartA4ProductionIntegrationTest.test92_95_WatchdogToRecoveryEngineHandoffHandlesSunDevIdleTimeoutAndIgnoresUserCancel`)**: Watchdog detects Sun Dev 90s idle stream timeout -> `handleWatchdogEvaluation` cancels execution -> routes to `AutonomousRecoveryEngine` -> switches provider/model -> ignores user-cancelled tasks.
2. **A4-02 (`PartA4ProductionIntegrationTest.test96_AndroidRecoveryAutomaticModeReclaimsExpiredLeaseAndPreservesPauseCancel`)**: Persistent `ExecutionLivenessMonitor(store)` detects expired lease -> `ProcessRecoveryCoordinator` with `RecoveryMode.AUTOMATIC` schedules recovery while skipping paused/cancelled tasks.
3. **A4-03 (`PartA4ProductionIntegrationTest.test98_SecurityDefaultsDisableUnsafePermissionBypassAndPreserveWorkspaceBoundaries`)**: Verifies `RuntimeExecutionRequest.allowSkipPermissions == false` by default and `WorkspacePathValidator` rejects `../` traversal.
4. **Regression Suites (`PartAMultiAgentWorkflowTest`, `PartA2AutonomousSupervisorTest`, `PartA3IntegrationGapClosureTest`, `Phase5AndroidProcessRecoveryTest`, `AutonomousPlatformLifecycleTest`, + 35 other suites)**: Full regression across 40 suites / 279 tests.
5. **APK Compilation & Packaging (`assembleOnlineDebug`)**: Produces `ORQEN-debug.apk`.
