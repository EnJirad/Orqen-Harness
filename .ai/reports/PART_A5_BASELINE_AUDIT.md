# ORQEN-Harness — PART A.5 Baseline Audit

- **Starting Commit SHA**: `438ecfbf18e2e6cf7951dedecb2e82e447d3e390` (`main` == `origin/main`)
- **Working Tree Status at Start**: Clean (no uncommitted changes)
- **Prior Docs Inspected**: `.ai/office/QUICK_START.md`, `.ai/office/AI_HANDOFF.md`, `.ai/reports/PART_A4_*.md`, `.ai/testing/PART_A4_*.md`

## Confirmed Gaps at Baseline
1. **Ununified Task Submission Paths (`MainViewModel.kt`)**:
   - `postTeamChatMessageWithImages(...)` still called legacy `startTeamWork()`.
   - `startTeamWork()` still called legacy `runTeamCycle()`.
   - Directives arriving while a task was active were only placed in in-memory `pendingTeamDirectives` without persisting a queued `TaskEntity`.
2. **Task ID Lifecycle Race (`MainViewModel.kt:6338`)**:
   - `activeSupervisedTaskId` was assigned ONLY AFTER `submitAndSupervise(...)` returned, so Pause/Stop during active execution could not target the running persistent task.
3. **In-Memory Collaboration Store (`MainViewModel.kt`)**:
   - `collaborationStore` used `InMemoryCollaborationStore()`, causing delegations, independent reviews, decisions, file locks, and owner approvals to be lost on cold process death despite SQLite v4 tables already existing in `SqliteOrqenTaskDatabase.kt`.
4. **Health Check Permission Bypass (`AntigravityRuntimeBridge.kt:219`)**:
   - `hello()` health probe unconditionally passed `--dangerously-skip-permissions`.
5. **Dashboard Operational Visibility (`TeamDashboardScreen.kt` / `TeamWorkspaceScreen.kt` / `PocketDevApp.kt`)**:
   - `MainUiState.supervisorSnapshots` was populated in `MainViewModel` but not yet passed into or rendered by `TeamDashboardScreen`.
