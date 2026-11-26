# ORQEN-Harness — PART A.4 Test Results

**Base Tested SHA**: `3677ea844df981df75d3416ecfe9f589274f81ab` + Part A.4 working changes (committed in the Part A.4 commit).
**Build Status**: `BUILD SUCCESSFUL in 7m 57s` — `ORQEN-debug.apk` (`72M`) generated.

| Test ID | Test Name | Command | Result | Evidence | Commit | Notes |
|---|---|---|---|---|---|---|
| A4-BUILD | Kotlin compile + Debug APK (`assembleOnlineDebug`) | `gradle ... testOnlineDebugUnitTest assembleOnlineDebug` | **PASS** | `BUILD SUCCESSFUL in 7m 57s`, `ORQEN-debug.apk` (`72M`) | Part A.4 | 0 compile errors |
| A4-01 | `PartA4ProductionIntegrationTest.test92_95_WatchdogToRecoveryEngineHandoffHandlesSunDevIdleTimeoutAndIgnoresUserCancel` | `gradle ... testOnlineDebugUnitTest` | **PASS** | `TEST-com.jarves.mh.multiagent.PartA4ProductionIntegrationTest.xml` (`tests=3 failures=0 errors=0`) | Part A.4 | Simulated provider boundary |
| A4-02 | `PartA4ProductionIntegrationTest.test96_AndroidRecoveryAutomaticModeReclaimsExpiredLeaseAndPreservesPauseCancel` | `gradle ... testOnlineDebugUnitTest` | **PASS** | `TEST-com.jarves.mh.multiagent.PartA4ProductionIntegrationTest.xml` | Part A.4 | In-memory `OrqenTaskStore` |
| A4-03 | `PartA4ProductionIntegrationTest.test98_SecurityDefaultsDisableUnsafePermissionBypassAndPreserveWorkspaceBoundaries` | `gradle ... testOnlineDebugUnitTest` | **PASS** | `TEST-com.jarves.mh.multiagent.PartA4ProductionIntegrationTest.xml` | Part A.4 | Unit verified |
| A4-REG-A | `PartAMultiAgentWorkflowTest` (7 tests) | `gradle ... testOnlineDebugUnitTest` | **PASS** | `tests=7 failures=0 errors=0` | Part A.4 | Simulated provider boundary |
| A4-REG-A2 | `PartA2AutonomousSupervisorTest` (5 tests incl. To-Do App E2E) | `gradle ... testOnlineDebugUnitTest` | **PASS** | `tests=5 failures=0 errors=0` | Part A.4 | Simulated failure injection |
| A4-REG-A3 | `PartA3IntegrationGapClosureTest` (3 tests) | `gradle ... testOnlineDebugUnitTest` | **PASS** | `tests=3 failures=0 errors=0` | Part A.4 | Simulated provider boundary |
| A4-REG-ALL | Full Repository Suite (40 suites, 279 tests) | `gradle ... testOnlineDebugUnitTest` | **PASS** | `TOTAL_SUITES=40 TESTS=279 FAILURES=0 ERRORS=0 SKIPPED=0` | Part A.4 | All JVM unit/integration tests |
| A4-LIVE-API | Live Provider API Failover (OpenAI/Anthropic/Gemini/PRoot `agy`) | N/A | **NOT RUN** | No live API keys in sandbox | Part A.4 | Requires live credentials |
| A4-DEVICE | Physical Android Runtime (`JobScheduler` + SQLite process kill) | N/A | **NOT RUN** | No Android device/emulator in sandbox | Part A.4 | Requires physical Android device |
