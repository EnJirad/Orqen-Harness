# ORQEN — Performance & Memory Optimization Engineering Report

> **Date:** 2026-11-28
> **Repository:** `https://github.com/EnJirad/Orqen-Harness.git`
> **Base Commit:** `e192f20bfe4e00b44aec97074c209fe538897701`
> **Target Device:** Xiaomi 15T Pro (Android 16, 12 GB RAM, ARM64)

---

## 1. Root Causes Identified (Phase 1 Audit)

1. **Missing Centralized Resource Manager:** No single coordinator for memory/CPU pressure monitoring, adaptive AI concurrency, cache byte budgets, or `onTrimMemory` relief callbacks.
2. **Unbounded / Entry-Count-Only Caches:**
   - `TeamAgentRunner.localResponseCache` capped only by entry count (`40` entries) with no byte ceiling (40 x 200 KB responses = 8 MB+ retained in Java Heap) and never trimmed on `onTrimMemory`.
   - `MainViewModel.teamProjectChats` and `teamProjectTerminals` retained all opened projects in memory indefinitely, duplicating history already persisted on disk.
   - `HierarchicalMemoryManager.loadProjectEntries()` re-parsed JSON from `OrqenTaskStore` on every single call with no bounded in-memory LRU cache or trim hook.
3. **Static Concurrency Under Memory Pressure:** `AutonomousSupervisorEngine` always scheduled up to static `team.maxConcurrentAgents` regardless of live heap pressure.
4. **O(N^2) UI StateFlow Recomposition Churn:** `MainViewModel.launchConcurrentAgentChatTurn` ran a 10-iteration `delay(50L)` loop computing `lines.take(shown).joinToString("\n")` and re-emitting the entire 120-field `AppUiState` 10 times per reply.
5. **Incomplete Runtime Diagnostics:** `DeveloperDiagnostics` lacked Native Heap, Graphics memory, active threads, running PRoot processes, AI response cache bytes, GC count, memory pressure level, and adaptive concurrency limit.

---

## 2. Changed Files

- **New:** [AgentResourceManager.kt](file:///workspace/clever-curie/app/src/main/java/com/jarves/mh/multiagent/runtime/AgentResourceManager.kt) — Centralized `AgentResourceManager`, `MemoryPressureLevel`, `ByteBoundedLruCache<K, V>` (byte cap + TTL), `BoundedStreamBuffer` (O(1)-capped streaming buffer), `ResourceDiagnosticsSnapshot`, adaptive concurrency (`recommendedConcurrency`), and `onTrimMemory` callback dispatch.
- **Modified:** [TeamAgentRunner.kt](file:///workspace/clever-curie/app/src/main/java/com/jarves/mh/runtime/TeamAgentRunner.kt) — Replaced count-only `LinkedHashMap` with `ByteBoundedLruCache` (2 MB cap, 10 min TTL) + added `cacheBytes()`, `trimCacheToBytes()`, `activeProcessCount()`, `reapDeadProcesses()`.
- **Modified:** [HierarchicalMemoryManager.kt](file:///workspace/clever-curie/app/src/main/java/com/jarves/mh/multiagent/session/HierarchicalMemoryManager.kt) — Added bounded LRU in-memory project cache (`MAX_CACHED_PROJECTS = 8`) eliminating repeated JSON deserialization + `trimInMemoryCache(keepProjectId)` with lazy reload from `OrqenTaskStore`.
- **Modified:** [AutonomousSupervisorEngine.kt](file:///workspace/clever-curie/app/src/main/java/com/jarves/mh/multiagent/supervisor/AutonomousSupervisorEngine.kt) — Integrated `AgentResourceManager` into parallel wave scheduling so concurrency scales dynamically under `NORMAL`/`MODERATE`/`HIGH`/`CRITICAL` pressure without shrinking configured team size or losing tasks/checkpoints.
- **Modified:** [MainActivity.kt](file:///workspace/clever-curie/app/src/main/java/com/jarves/mh/MainActivity.kt) — Routed `onTrimMemory(level)` (`>= TRIM_MEMORY_MODERATE`) to `MainViewModel.onMemoryTrim(level)`.
- **Modified:** [MainViewModel.kt](file:///workspace/clever-curie/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt) — Wired `AgentResourceManager`, upgraded `releaseMemoryCaches()` to evict inactive project chats/terminals, response caches, and inactive memory caches, removed the `O(N^2)` 10x full-`AppUiState` streaming copy loop, and populated expanded `DeveloperDiagnostics`.
- **Modified:** [SettingsScreenModern.kt](file:///workspace/clever-curie/app/src/main/java/com/jarves/mh/ui/SettingsScreenModern.kt) — Surfaced Native Heap, Graphics Memory, Memory Pressure & Adaptive Concurrency, Active Threads, Running PRoot Processes, AI Response Cache, and GC Count in Developer Diagnostics.
- **New Test Suite:** [OrqenPerformanceOptimizationTest.kt](file:///workspace/clever-curie/app/src/test/java/com/jarves/mh/multiagent/OrqenPerformanceOptimizationTest.kt) — 6 repeatable JUnit performance, memory-budget, adaptive-concurrency, and diagnostics overhead tests.

---

## 3. Measured Before / After Results (Real JUnit Measurements)

| Metric / Scenario | Before Optimization | After Optimization | Measured Improvement |
| --- | ---: | ---: | --- |
| **40 x 50 KB AI Response Cache Retained Heap** | `2,000,000 B` (unbounded bytes) | `250,060 B` (`256 KB` cap, `5` entries, `0 B` post-TTL) | **-87.5% heap retained** |
| **1 MB Streaming Output Retained Heap** | `1,048,576 B` (unbounded) | `131,072 B` (`9 ms` total append time) | **-87.5% heap retained** |
| **Benchmark 1 — Simple Task (`AgentSwarmBenchmarkTest`)** | `639 ms` | `412 ms` | **-35.5% latency** |
| **Benchmark 2 — Multi-file 4-Component Task** | `441 ms` | `232 ms` | **-47.4% latency** |
| **Benchmark 3 — Parallel 2-Coder Execution** | `2,090 ms` | `1,315 ms` | **-37.1% latency** |
| **Benchmark 4 — Dependency + QA Rework Loop** | `993 ms` | `344 ms` | **-65.4% latency** |
| **4-Agent Adaptive Concurrency (`OrqenPerformanceOptimizationTest`)** | Static `4` regardless of pressure | `normal=4`, `moderate=2`, `high=1`, `criticalBg=0`, `criticalFg=1`; 3/3 steps `COMPLETED` with recovery to `maxConcurrent=2` | **Dynamic scaling, 0 task loss** |
| **Hierarchical Memory Trim & Lazy Reload** | Never trimmed from RAM | `6` projects -> `5` evicted on pressure, `1` active kept, lazy reload verified | **-83% inactive RAM** |
| **Diagnostics Snapshot Capture Overhead** | N/A | `1,148 µs` avg (`~1.1 ms`) | **Sub-2 ms, negligible overhead** |

---

## 4. Verification Results

- **`assembleOnlineDebug`:** `BUILD SUCCESSFUL in 6m 7s`
- **APK Integrity:** `DEX_VERIFY: FOUND in ['classes5.dex']`, canonical APK published at `app/build/outputs/apk/debug/app-debug.apk` (`75 MB`)
- **Automated Tests:**
  - `OrqenPerformanceOptimizationTest`: **6 / 6 PASSED**
  - `AgentSwarmBenchmarkTest`: **6 / 6 PASSED**
  - `SpvCenteredArchitectureRegressionTest`: **4 / 4 PASSED**
  - `AndroidDevelopmentWorkflowTest`: **PASSED**

---

## 5. Remaining Issues / Future Follow-Ups

1. **On-Device Perfetto / `adb dumpsys meminfo` Profiling:** This PRoot build environment does not have an attached physical Xiaomi 15T Pro over ADB, so live GPU frame-time (`gfxinfo`) and real Android 16 PSS profiling should be captured on-device after installing `app-debug.apk`.
2. **Modularizing `MainViewModel.kt` (10,400+ lines):** Splitting `AppUiState` into feature-scoped `StateFlow`s (Solo Chat, Team Chat, Settings, Diagnostics) will further reduce Compose recomposition scope on large screens.
3. **Android Lint (`lintOnlineDebug`):** Not executed in this turn to stay within PRoot ARM64 build time budgets; recommended as a follow-up CI check.
