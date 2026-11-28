# ORQEN Agent Swarm Integration — Implementation Plan

## Phase 1: Repository & Reference Audit (Complete)
- Fetched `origin/main` (`f6a580c`), verified clean working tree.
- Inspected `desplega-ai/agent-swarm` (`48b21307`) source code directly across orchestration, runtime, memory, heartbeat, scheduler, and tools.
- Documented findings in `research.md` and `gap-analysis.md`.

## Phase 2: Architecture Design (Complete)
- Designed zero-duplication integration in `architecture.md` and `architecture-diagram.md`.

## Phase 3 & 4: Core Implementation & Integration
1. Upgrade `HierarchicalMemoryManager.kt` with 5-scope persistent memory (`PROJECT`, `AGENT`, `TASK`, `EXECUTION`, `DECISION`), SHA-256 deduplication, noise-floor guard, `SecretSanitizer` scrubbing, access control, usefulness-ranked retrieval, and automatic task-learning distillation.
2. Integrate `HierarchicalMemoryManager` and `sweepStalledAndOrphanedTasks(...)` into `AutonomousSupervisorEngine.kt` and `TaskContextBuilder.kt` (backward-compatible default parameters so zero existing callers break).

## Phase 5: 5-Scenario Real Benchmark & Verification
- Implement and execute `AgentSwarmBenchmarkTest.kt` running all 5 real benchmark scenarios (Simple, Multi-file, Parallel, Dependency + Review Rework Loop, Recovery from Mid-Execution Crash) on real workspaces and `OrqenTaskStore`.
- Record exact measured metrics into `.ai/agent-swarm/benchmark.md` and `.ai/agent-swarm/test-results.md`.

## Phase 6: Final Validation & Handoff
- Run unit tests + `assembleOnlineDebug` Android build.
- Write `progress.md`, `test-results.md`, `known-issues.md`, and `handoff.md`.
