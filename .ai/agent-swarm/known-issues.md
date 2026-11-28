# Known Issues & Environment Limitations

> **Last Updated:** 2026-11-28

1. **`desplega-ai/agent-swarm` Live Container Benchmarks Not Runnable in PRoot:**
   - The PocketDev ARM64 Android PRoot container lacks both `docker` and `bun` (`>= 1.4.4`).
   - Consequently, Agent Swarm's Docker-based lead/worker cluster could not be executed live here. Its architectural properties were verified directly from its TypeScript source (`v2.16.0`, commit `48b21307`), while all quantitative metrics in `benchmark.md` are real JUnit measurements from ORQEN.
   - *Future Action:* If a Docker + Bun host is available, run the same 5 scenarios against a live Agent Swarm cluster and append side-by-side latency numbers to `benchmark.md`.

2. **Lexical Token-Overlap Retrieval vs Vector Embeddings:**
   - `HierarchicalMemoryManager.retrieveRelevant(...)` uses deterministic token-overlap + scope/recency/usefulness scoring rather than remote vector embeddings.
   - This is intentional for offline-first Android reliability and zero extra API cost/latency, though semantic synonyms (e.g., `"auth"` vs `"login"`) may score lower than exact term matches.
   - *Future Action:* Optionally add lightweight on-device n-gram/BM25 or local embedding index if memory corpus per project exceeds ~500 entries.

3. **Persistence Backing Store for Structured Memories:**
   - Structured memories are persisted cleanly via `OrqenTaskStore.upsertChatDraft(...)` under the dedicated key `orqen_mem_v1:<projectId>` (`conversationId = "memory"`, `mode = "MEMORY_INDEX"`) to avoid breaking Room database schema migrations.
   - *Future Action:* When the next Room schema version bump is scheduled, optionally promote structured memories into a dedicated `@Entity(tableName = "structured_memories")` table with indexed `scope` and `sha256` columns.

4. **Periodic Background Trigger for `sweepStalledAndOrphanedTasks`:**
   - `AutonomousSupervisorEngine.sweepStalledAndOrphanedTasks(projectId, staleThresholdMs)` is implemented, integrated with `FileLockManager`, and verified by `Benchmark 5`.
   - It should also be invoked automatically on app cold-start / ViewModel project-load and on a periodic coroutine heartbeat tick when a project has active `RUNNING` tasks.

5. **Pre-existing Kotlin Compiler Warning in Unrelated Test:**
   - `app/src/test/java/com/jarves/mh/backup/BackupManagerTest.kt:39:83` emits a pre-existing nullable-receiver warning during `compileOnlineDebugUnitTestKotlin`. Untouched to keep diff strictly scoped to this task.
