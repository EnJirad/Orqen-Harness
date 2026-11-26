# ORQEN-Harness — AI Handoff

## Current Project State
- **Branch**: `main`
- **Latest Hotfix**: **P0 Start AI Team Crash Fixed** — Fixed missing SQLite column `agent_profiles.preferred_tools_json` (bumped `DATABASE_VERSION = 6` with non-destructive `migrateV5ToV6`), hardened `SqliteCollaborationStore`, and moved `MainViewModel.startTeamWork` / `resumeTeamWork` / `submitAutonomousTeamObjective` SQLite sync onto `Dispatchers.IO` with safe error boundaries, duplicate-start guard, and UI error reporting.
- **Build & Test Status**: `44 suites / 288 tests / 288 PASSED / 0 FAILED / 0 SKIPPED`; `ORQEN-debug.apk` (`70M`) `BUILD SUCCESSFUL`.
- **Remaining Verification**: Install `ORQEN-debug.apk` on physical Android device to confirm Start AI Team stays open and capture `adb logcat` if any device-specific issue appears.
