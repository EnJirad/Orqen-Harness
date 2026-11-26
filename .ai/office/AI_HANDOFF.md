# ORQEN-Harness — AI Handoff

## Current Project State
- **Branch**: `main`
- **Previous Verified Commit**: `3677ea844df981df75d3416ecfe9f589274f81ab` (Parts A, A.2, A.3)
- **Current Milestone**: **PART A.4 Completed** (Production Supervisor Activation, Watchdog -> Recovery Engine handoff, `TaskRecoveryJobService` `AUTOMATIC` recovery, `allowSkipPermissions = false` default, `.ai/` evidence suite).
- **Build & Test Status**: `40 suites / 279 tests / 279 PASSED / 0 FAILED / 0 SKIPPED`; `ORQEN-debug.apk` (`72M`) `BUILD SUCCESSFUL`.

## Completed Work
- `MainViewModel.kt`: Production `autonomousSupervisorEngine` wired to `SqliteOrqenTaskStore`, `TeamAgentRunnerRuntimeAdapter`, `ExecutionLivenessMonitor(orqenTaskStore)`, `providerManager.configs`, and `TeamChatManagerBridge`. User task submissions in `postTeamChatMessage(...)` invoke `submitAutonomousTeamObjective(...)`. Live `supervisorSnapshots` exposed in `MainUiState`.
- `AutonomousSupervisorEngine.kt`: Added `handleWatchdogEvaluation(...)` routing confirmed Watchdog stalls directly into `AutonomousRecoveryEngine.planRecovery(...)` while ignoring user-cancelled/paused tasks.
- `TaskRecoveryJobService.kt`: Uses SQLite-backed `ExecutionLivenessMonitor(store)` + `restoreAndIdentifyExpiredLeases()` + `RecoveryMode.AUTOMATIC`.
- `TeamAgentRunner.kt` & `AIExecutionRuntime.kt`: Default `allowSkipPermissions = false` (no implicit `--dangerously-skip-permissions`).

## Active Issues & Next Recommended Action
1. Back `MainViewModel.collaborationStore` with SQLite (`SqliteOrqenTaskDatabase` v4) so reviews/delegations/approvals survive cold process kills alongside tasks.
2. Add a concise Supervisor Recovery & Health card in `TeamDashboardScreen.kt` bound to `MainUiState.supervisorSnapshots`.
3. Perform on-device live provider & Android `JobScheduler` verification on a physical Android device.

## Instructions for the Next AI Agent
- Read `.ai/office/QUICK_START.md` and `.ai/reports/PART_A4_REMAINING_ISSUES.md` before making changes.
- Never use plain `gradle build`; use the cached offline debug build command documented in `QUICK_START.md`.
- User standing rule: always commit and `git push origin main` after completing, testing, and building each round of work (never commit `ORQEN-debug.apk`).
