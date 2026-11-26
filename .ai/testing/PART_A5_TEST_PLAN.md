# ORQEN-Harness — PART A.5 Test Plan

## Command Executed
```bash
timeout 14m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline \
  --init-script /root/.gradle/init.d/pocketdev-android.gradle \
  -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 \
  testOnlineDebugUnitTest assembleOnlineDebug --console=plain --stacktrace
```

## Scenarios
- **A5-01**: Task ID published via `onTaskCreated` before Step 1 completes -> Pause during active execution -> Resume -> Verified `COMPLETED`.
- **A5-02**: Repeated active submission idempotently reuses existing Task ID; owner Stop persists `CANCELLED` and blocks `reconcileActiveTasksOnce`; `TaskStatusTransitionValidator` rejects invalid transitions.
- **A5-REG**: Full regression across Parts A, A.2, A.3, A.4, Phase 1–7, `AntigravityBridgeTest`, `RuntimeLaunchConfigBuilderTest` (41 suites / 281 tests) + Debug APK build (`assembleOnlineDebug`).
- **A5-LIVE / A5-DEVICE**: Real external provider APIs & physical Android SQLite/JobScheduler runtime (marked `BLOCKED` in sandbox due to no live API keys or Android device).
