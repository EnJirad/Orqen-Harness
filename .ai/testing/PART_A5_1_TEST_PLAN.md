# ORQEN-Harness — PART A.5.1 Test Plan

## Command Executed
```bash
timeout 14m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline \
  --init-script /root/.gradle/init.d/pocketdev-android.gradle \
  -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 \
  testOnlineDebugUnitTest assembleOnlineDebug --console=plain --stacktrace
```

## Scenarios Covered
- **Test A (`PartA51TeamContinuityTest.testA_SequentialTaskQueueContinuesAutomaticallyThroughTasksABC`)**: Task A runs while Tasks B and C are queued -> A completes -> B and C run and complete automatically in order.
- **Test C (`PartA51TeamContinuityTest.testC_ParallelIndependentSubtasksExecuteConcurrentlyWhileDependentStepWaits`)**: Two independent subtasks run concurrently (`peakActive >= 2`) while dependent QA step waits until both finish.
- **Test G (`PartA51TeamContinuityTest.testG_ImageAttachmentPersistsAcrossQueueAndProcessRecreationAndReachesAgents`)**: Image file persists via `TaskAttachmentCodec`, survives simulated process recreation, and reaches Dev + QA in `RuntimeExecutionRequest.imageFiles`.
- **Tests B, D, E, F, H, I, J**: Covered by `PartAMultiAgentWorkflowTest` (7/7), `PartA2AutonomousSupervisorTest` (5/5), `PartA3IntegrationGapClosureTest` (3/3), `PartA4ProductionIntegrationTest` (3/3), `PartA5ControlReliabilityTest` (2/2), plus 36 existing regression suites (42 suites / 284 tests total).
