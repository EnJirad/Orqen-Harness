# ORQEN-Harness — PART A.5 Test Results

- **Starting Commit SHA**: `438ecfbf18e2e6cf7951dedecb2e82e447d3e390`
- **Build Artifact**: `ORQEN-debug.apk` (`70M`) — `BUILD SUCCESSFUL in 9m 48s` (exit code 0)

| Test ID | Test Name | Command | Status | Evidence | Notes |
|---|---|---|---|---|---|
| A5-BUILD | Full Kotlin compile + `assembleOnlineDebug` | `gradle ... testOnlineDebugUnitTest assembleOnlineDebug` | **COMPILED / PASS** | `BUILD SUCCESSFUL in 9m 48s`, `ORQEN-debug.apk` (`70M`) | 0 compile errors |
| A5-01 | `PartA5ControlReliabilityTest.test01_TaskIdPublishedBeforeExecutionAllowsImmediatePauseAndResume` | `gradle ... testOnlineDebugUnitTest` | **INTEGRATION_TESTED / PASS** | `TEST-com.jarves.mh.multiagent.PartA5ControlReliabilityTest.xml` | Simulated runtime boundary |
| A5-02 | `PartA5ControlReliabilityTest.test02_RepeatedSubmissionDeduplicatesActiveTaskAndStopPreventsRestart` | `gradle ... testOnlineDebugUnitTest` | **INTEGRATION_TESTED / PASS** | `TEST-com.jarves.mh.multiagent.PartA5ControlReliabilityTest.xml` | Simulated runtime boundary |
| A5-REG | Full Repository Regression (Parts A–A.5 + Phases 1–7 + Runtime) | `gradle ... testOnlineDebugUnitTest` | **UNIT_TESTED / PASS** | `TOTAL_SUITES=41 TESTS=281 FAILURES=0 ERRORS=0 SKIPPED=0` | Includes `AntigravityBridgeTest` & `PartA4ProductionIntegrationTest` |
| A5-SQLITE-DEV | On-Device SQLite v4->v5 Upgrade & `SqliteCollaborationStore` Runtime | N/A | **BLOCKED** | No physical Android device/emulator in sandbox | Compiled cleanly; requires Android runtime |
| A5-LIVE-API | Real External Provider API Failover (OpenAI/Anthropic/Gemini/PRoot `agy`) | N/A | **BLOCKED** | No live API credentials in sandbox | Requires real keys |
