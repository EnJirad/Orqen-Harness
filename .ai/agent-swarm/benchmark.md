# Agent Swarm vs ORQEN — Benchmark Report

> **Date:** 2026-11-28
> **Target Repository:** `desplega-ai/agent-swarm` (`v2.16.0`, commit `48b21307e9ec5b03fd4a5b68c72795ab7ca4a4f1`) vs ORQEN (`/workspace/clever-curie`, base commit `f6a580c`)
> **Test Harness:** `app/src/test/java/com/jarves/mh/multiagent/AgentSwarmBenchmarkTest.kt`

---

## 1. Environment Transparency & Execution Scope

| Runtime / Dependency | Required by `desplega-ai/agent-swarm` | Available in This PRoot ARM64 Environment |
| --- | --- | --- |
| Bun (`>= 1.4.4`) | Yes (API/MCP server + worker runtime) | **No** (`bun` not installed) |
| Docker / Docker Compose | Yes (isolated worker containers + Redis/Postgres/SQLite volumes) | **No** (`docker` daemon unavailable in Android PRoot) |
| OpenJDK 17 + Gradle 8.14.3 + Android SDK 36 | No (ORQEN native stack) | **Yes** |

**Honesty Notice:** Because Docker and Bun cannot run in this PRoot Android build environment, `desplega-ai/agent-swarm` was audited at the source-code level (workflows engine, recovery sweep, memory store, heartbeat, scheduler, MCP tools) rather than booted live in containers. All **measured numerical benchmark results below** come from real JUnit executions against ORQEN's upgraded production engine (`AutonomousSupervisorEngine`, `HierarchicalMemoryManager`, `TaskContextBuilder`, `OrqenTaskStore`, `FileLockManager`) using deterministic stub LLM executors to isolate orchestration, parallelism, memory, rework, and crash-recovery overhead without network jitter.

---

## 2. Measured ORQEN Benchmark Results (5 Scenarios + Memory Suite)

**Command Executed:**
```bash
timeout 8m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline \
  --init-script /root/.gradle/init.d/pocketdev-android.gradle \
  -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 \
  testOnlineDebugUnitTest \
  --tests "com.jarves.mh.multiagent.AgentSwarmBenchmarkTest" \
  --info --console=plain --stacktrace
```

**Source XML:** `app/build/test-results/testOnlineDebugUnitTest/TEST-com.jarves.mh.multiagent.AgentSwarmBenchmarkTest.xml`

| # | Scenario | Status | Measured Engine Time (`elapsedMs`) | JUnit Case Time (`s`) | Key Measured Metrics |
| --- | --- | --- | ---: | ---: | --- |
| 1 | **Simple Task** (Calculator module + persistent memory distillation) | `COMPLETED` | `639 ms` | `0.700 s` | `agents=2`, `steps=2`, distilled `EXECUTION` + `DECISION` memories persisted to `OrqenTaskStore` |
| 2 | **Multi-file Task** (4-component project + deliverable verification) | `COMPLETED` | `441 ms` | `0.495 s` | `files=4` verified on disk, zero lock collisions, scoped memory injected into worker steps |
| 3 | **Parallel Task** (2 concurrent coding workers on disjoint files) | `COMPLETED` | `2090 ms` | `2.163 s` | `maxConcurrent=2` verified via atomic concurrency counter; both workers ran truly in parallel |
| 4 | **Dependency + Review/Rework Loop** (Independent QA rejection -> Coder rework -> QA pass) | `COMPLETED` | `993 ms` | `1.031 s` | `qaAttempts=2`; first QA review rejected, supervisor routed rework autonomously, second QA passed |
| 5 | **Crash / Stall Recovery** (`sweepStalledAndOrphanedTasks` after simulated worker death) | `COMPLETED` | `852 ms` | `1.091 s` | `step1Replays=0`, `step2Runs=1`; stale locks released, completed Step 1 preserved, only stalled Step 2 resumed |
| + | **5-Scope Hierarchical Memory Unit Verification** | `PASSED` | — | `0.444 s` | Verified 5 scopes (`PROJECT`, `AGENT`, `TASK`, `EXECUTION`, `DECISION`), SHA-256 dedup, `<24` char noise floor, `SecretSanitizer` scrubbing, and `AGENT` ownership access control |

**Total Suite Time:** `5.928 s` (`6/6` passed, `0` skipped, `0` failures, `0` errors).

---

## 3. Architectural Comparison (Measured ORQEN vs Source-Audited Agent Swarm)

| Dimension | `desplega-ai/agent-swarm` (Source Audit) | ORQEN Upgraded Engine (Measured) |
| --- | --- | --- |
| **Orchestration Topology** | Lead + Worker pool via HTTP/MCP + SQLite polling & Docker containers | Single-process in-app Kotlin coroutine `AutonomousSupervisorEngine` + SQLite `OrqenTaskStore` |
| **Dispatch Overhead** | Higher (container lifecycle + HTTP/MCP poll cycle + heartbeat interval) | Sub-second (`441 ms`–`993 ms` for sequential/multi-file/rework; `2090 ms` for 2-worker parallel with simulated delay) |
| **Parallelism** | Container-level parallel workers claiming tasks from queue | Coroutine-level parallel worker wave execution (`maxConcurrent=2` verified) + `FileLockManager` lease isolation |
| **Crash / Stall Recovery** | `src/workflows/recovery.ts` + `src/heartbeat/heartbeat.ts` sweeping stale runs | `AutonomousSupervisorEngine.sweepStalledAndOrphanedTasks(...)` releases expired `FileLockManager` locks & resets only unfinished `RUNNING` steps (`step1Replays=0`) |
| **Memory Architecture** | `src/be/memory/` (scoped memories, task-learning extraction, search/retrieval) | Upgraded `HierarchicalMemoryManager` (5 scopes, SHA-256 dedup, noise floor, `SecretSanitizer`, ownership ACL, Top-K relevance injection in `TaskContextBuilder`, durable `OrqenTaskStore` persistence) |
| **Mobile Footprint** | Requires server/Docker host | Runs natively on Android ARM64 device with zero external services |

---

## 4. Key Takeaways

1. **Zero-Replay Crash Recovery Works:** `sweepStalledAndOrphanedTasks` recovered a simulated mid-task crash in `852 ms` while keeping Step 1 intact (`step1Replays=0`) and releasing orphaned file locks cleanly.
2. **True Parallel Execution Verified:** Two coding workers executed concurrently (`maxConcurrent=2`) with memory distillation and `TaskContextBuilder` Top-K memory injection active.
3. **Autonomous QA Rework Loop Intact:** Independent QA rejection triggered autonomous rework and converged to `COMPLETED` in `993 ms` (`qaAttempts=2`).
4. **No Regressions on Existing SPV Architecture:** `SpvCenteredArchitectureRegressionTest` (`4/4` passed in `1.201 s`) confirmed zero breakage to existing SPV routing, task assignment envelopes, ledger verification, and missing-step recovery.
