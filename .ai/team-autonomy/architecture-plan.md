# ORQEN — Autonomous Goal-Driven Multi-Agent Team Architecture Plan

> **Date:** 2026-11-28
> **Objective:** Enable ORQEN's AI agents to think independently, collaborate cleanly as a team anchored to the Owner's immutable goal, and persist all state reliably without SQLite lock contention.

---

## 1. Reliability Foundation: Zero `SQLITE_BUSY` Architecture

- **Process-Wide Singleton Connection Pool:** `OrqenTaskDatabaseHelper.getInstance(context)` guarantees that `MainViewModel`, `TaskRecoveryJobService`, `SqliteCollaborationStore`, and `McpStore` all share ONE `SQLiteOpenHelper` instance per database file.
- **WAL + `PRAGMA busy_timeout = 10000` + `PRAGMA synchronous = NORMAL`:** Configured directly in `onConfigure(db)` so concurrent readers and queued writers wait gracefully up to 10 seconds instead of failing immediately with `code 5 SQLITE_BUSY`.
- **Non-Exclusive WAL Transactions + Retry Backoff:** `SqliteOrqenTaskStore.runInTransaction` uses `beginTransactionNonExclusive()` (preventing reader starvation) with automatic exponential backoff (`50ms -> 150ms -> 350ms -> 700ms`) on transient `SQLiteDatabaseLockedException`.

---

## 2. Production Multi-Agent Team Topology (2025–2026 Best Practices)

```text
  [ Owner Goal ]
        │
        ▼
┌──────────────────────────────────────────┐
│  Goal-Driven Orchestrator (Manager/SPV)  │  <- Decomposes goal into DAG,
│  AutonomousSupervisorEngine              │     tracks dependencies, adaptive concurrency
└───────────────────┬──────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────┐
│       ACID Shared Blackboard             │  <- Single source of truth:
│  • Immutable User Goal                   │     OrqenTaskStore (SQLite WAL)
│  • DAG Steps & Checkpoints               │     + HierarchicalMemoryManager (5 scopes)
│  • FileLockManager (write isolation)     │     + MultiPartPlanManager
│  • Critic Feedback & Verification Logs   │
└──────▲──────────────▲──────────────▲─────┘
       │              │              │
┌──────┴──────┐ ┌─────┴───────┐ ┌────┴────────┐
│ Specialist  │ │ Specialist  │ │ Two-Tier    │
│ Coder A     │ │ Coder B     │ │ Critic / QA │
│ (Parallel)  │ │ (Parallel)  │ │ (Build+LLM) │
└─────────────┘ └─────────────┘ └─────────────┘
```

### Key Operating Principles Built Into ORQEN
1. **Immutable Goal Anchor:** Every step envelope (`TaskContextBuilder`) pins the Owner's original goal at the top of the Shared Blackboard so agents never drift during multi-part execution.
2. **Independent Critical Thinking:** Workers operate as specialized senior engineers — reading real files, questioning bad assumptions, detecting edge cases, and writing concise verified outputs back to the Blackboard.
3. **Blackboard-Mediated Collaboration (No Noisy Agent-to-Agent Loops):** Agents share state via `OrqenTaskStore`, `HierarchicalMemoryManager` (Top-K relevance retrieval), and `FileLockManager` instead of burning tokens on unstructured chatter.
4. **Two-Tier Critic Loop:**
   - *Tier 1 (Programmatic):* Deterministic build/test/deliverable verification (`ResultVerifier`, `assembleOnlineDebug`, unit tests).
   - *Tier 2 (Semantic):* Independent QA/Reviewer agent verifying requirements, edge cases, and architecture; rejections feed structured rework instructions back through the Blackboard with bounded retry circuit breakers.
5. **Resource-Aware Adaptive Concurrency:** `AgentResourceManager` dynamically scales parallel agents (`4 -> 2 -> 1 -> pause`) based on live heap/PSS pressure without shrinking the user's configured team or dropping checkpoints.
