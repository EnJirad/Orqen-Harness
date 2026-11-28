# Agent Swarm Integration — Test & Build Results

> **Executed:** 2026-11-28 (UTC)
> **Workspace:** `/workspace/clever-curie`

---

## 1. Android Debug APK Build

- **Command:**
  ```bash
  timeout 10m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline \
    --init-script /root/.gradle/init.d/pocketdev-android.gradle \
    -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 \
    assembleOnlineDebug --console=plain --stacktrace
  ```
- **Result:** `BUILD SUCCESSFUL in 7m 45s` (exit code `0`)
- **Artifact Published:**
  - `app/build/outputs/apk/online/debug/app-online-debug.apk`
  - Copied to canonical path: `app/build/outputs/apk/debug/app-debug.apk` (`78 MB`)

---

## 2. Unit & Benchmark Test Execution

- **Command:**
  ```bash
  timeout 8m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline \
    --init-script /root/.gradle/init.d/pocketdev-android.gradle \
    -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 \
    testOnlineDebugUnitTest \
    --tests "com.jarves.mh.multiagent.AgentSwarmBenchmarkTest" \
    --tests "com.jarves.mh.multiagent.SpvCenteredArchitectureRegressionTest" \
    --info --console=plain --stacktrace
  ```
- **Result:** `BUILD SUCCESSFUL in 3m 34s` (exit code `0`, `26 actionable tasks: 4 executed, 22 up-to-date`)
- **Summary:** **10 / 10 tests PASSED** (`0` skipped, `0` failures, `0` errors)

### 2.1 `com.jarves.mh.multiagent.AgentSwarmBenchmarkTest` (`6/6` PASSED, `5.928 s`)
XML Report: `app/build/test-results/testOnlineDebugUnitTest/TEST-com.jarves.mh.multiagent.AgentSwarmBenchmarkTest.xml`

| Test Case | Time | Result | Captured Stdout |
| --- | ---: | --- | --- |
| `HierarchicalMemoryManager enforces 5 scopes, SHA256 dedup, noise floor, secret scrubbing, and access control` | `0.444 s` | PASS | — |
| `Benchmark 1 - Simple Task builds Calculator module and distills persistent memory` | `0.700 s` | PASS | `BENCHMARK_1_SIMPLE: status=COMPLETED elapsedMs=639 agents=2 steps=2` |
| `Benchmark 2 - Multi-file Task builds multi-component project and verifies all deliverables` | `0.495 s` | PASS | `BENCHMARK_2_MULTIFILE: status=COMPLETED elapsedMs=441 files=4` |
| `Benchmark 3 - Parallel Task executes 2 coding workers concurrently` | `2.163 s` | PASS | `BENCHMARK_3_PARALLEL: status=COMPLETED elapsedMs=2090 maxConcurrent=2` |
| `Benchmark 4 - Dependency Task with Independent Review Rejection and Autonomous Rework Loop` | `1.031 s` | PASS | `BENCHMARK_4_DEPENDENCY_REWORK: status=COMPLETED elapsedMs=993 qaAttempts=2` |
| `Benchmark 5 - Recovery Task recovers stalled worker, releases locks, and completes without redoing finished step` | `1.091 s` | PASS | `BENCHMARK_5_RECOVERY: status=COMPLETED elapsedMs=852 step1Replays=0 step2Runs=1` |

### 2.2 `com.jarves.mh.multiagent.SpvCenteredArchitectureRegressionTest` (`4/4` PASSED, `1.201 s`)
XML Report: `app/build/test-results/testOnlineDebugUnitTest/TEST-com.jarves.mh.multiagent.SpvCenteredArchitectureRegressionTest.xml`

| Test Case | Time | Result |
| --- | ---: | --- |
| `Scenario A - user question routes solely to SPV without creating a task or invoking Developer` | `0.022 s` | PASS |
| `Scenario B - Developer receives strict TASK_ASSIGNMENT envelope per Part and advances sequentially` | `0.854 s` | PASS |
| `Scenario C - assignment ledger rejects duplicate or mismatched worker results and SPV owns Owner status` | `0.042 s` | PASS |
| `Scenario D and E - task with missing steps self-recovers from persistent plan instead of failing with No steps planned` | `0.281 s` | PASS |
