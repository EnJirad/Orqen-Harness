# ORQEN-Harness — PART A.5 Remaining Issues & Risks

1. **Android Runtime Verification (`ANDROID_RUNTIME_TESTED` = BLOCKED)**
   - `SqliteCollaborationStore` (SQLite v4->v5 upgrade), `TaskRecoveryJobService` (`JobScheduler`), and `TeamDashboardScreen` Compose rendering are compiled in `ORQEN-debug.apk` (`70M`) but have not yet been executed on a physical Android device/emulator.
   - **Next Action**: Install `ORQEN-debug.apk` on an Android device, submit a real task in Team Chat, verify Supervisor card updates, force-kill process (`am kill`), and verify automatic recovery.

2. **Live External Provider Verification (`REAL_PROVIDER_TESTED` = BLOCKED)**
   - No live OpenAI/Anthropic/Gemini/Antigravity API keys are available in this sandbox.
   - **Next Action**: Run controlled live-provider verification with real user credentials on device.

3. **Legacy `runTeamCycle()` Method Retained as Unused Compatibility Code**
   - Production entry points (`postTeamChatMessage`, `postTeamChatMessageWithImages`, `startTeamWork`) no longer call `runTeamCycle()`, though the private helper method remains in `MainViewModel.kt` to avoid risky large-scale deletion before on-device testing.
   - **Next Action**: Remove unused `runTeamCycle()` helper after on-device confirmation of `AutonomousSupervisorEngine`.

4. **`antigravityCommand(...)` Solo CLI Flag**
   - `AntigravityRuntimeBridge.hello()` no longer passes `--dangerously-skip-permissions`, while `antigravityCommand(...)` still includes it for solo CLI sessions as asserted by existing `AntigravityBridgeTest.kt:129`.
   - **Next Action**: Introduce an explicit user-consent setting if solo CLI sessions should require per-command approval prompts.
