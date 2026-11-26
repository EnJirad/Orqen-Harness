# ORQEN-Harness — PART A.5.1 Remaining Issues

1. **Real External Provider Execution (`REAL_PROVIDER_TESTED` = BLOCKED)**
   - Sandbox does not contain live OpenAI/Anthropic/Gemini/Antigravity API keys; all 284 passing tests use controlled runtime boundaries for external LLM calls.
   - **Next Action**: Run live provider end-to-end verification on a device with real user credentials.

2. **Physical Android Runtime Verification (`ANDROID_RUNTIME_TESTED` = BLOCKED)**
   - `ORQEN-debug.apk` (`70M`) builds cleanly with SQLite v5 (`SqliteOrqenTaskStore` + `SqliteCollaborationStore`), `TaskRecoveryJobService` (`RecoveryMode.AUTOMATIC`), and the Dashboard Supervisor card, but has not been executed on a physical Android device/emulator.
   - **Next Action**: Install `ORQEN-debug.apk` on Android hardware and test multi-task queuing (Tasks A, B, C), image attachment handoff, Pause/Stop, and `adb shell am kill` recovery.

3. **Legacy Unused Helper `runTeamCycle()` in `MainViewModel.kt`**
   - No longer called by production task submission entry points; kept temporarily until on-device smoke testing confirms full parity.
