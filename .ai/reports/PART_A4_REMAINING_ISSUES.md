# ORQEN-Harness — PART A.4 Remaining Issues

1. **Live Provider API & PRoot `agy` End-to-End Verification (Environmental Limitation)**
   - **Status**: Not verified in sandbox (no live API credentials or Android PRoot runtime).
   - **Recommended Next Action**: Install `ORQEN-debug.apk` on a physical Android device with real provider keys and run a real task through Team Chat to observe live streaming, idle-stream recovery, and failover.

2. **Physical Android Process-Kill & Reboot Verification (Environmental Limitation)**
   - **Status**: `TaskRecoveryJobService` (`RecoveryMode.AUTOMATIC` + `ExecutionLivenessMonitor(store)`) is compiled and unit-tested, but not verified against Android OS `JobScheduler` under Doze/process kill.
   - **Recommended Next Action**: Test `adb shell am kill` and `adb shell cmd jobscheduler run` on an Android test device.

3. **Collaboration Store SQLite Persistence (`MainViewModel.kt`) (Incomplete Persistence Scope)**
   - **Status**: `MainViewModel.kt` persists tasks/steps/checkpoints/messages/events in `SqliteOrqenTaskStore`, while `collaborationStore` currently uses `InMemoryCollaborationStore` (so delegations/reviews/approvals reset on cold process death).
   - **Recommended Next Action**: Wire a SQLite-backed `CollaborationStore` implementation sharing `SqliteOrqenTaskDatabase` (schema v4) into `MainViewModel.kt`.

4. **Dedicated Supervisor Observability Card in Compose UI (UI Polish)**
   - **Status**: `MainUiState.supervisorSnapshots` is live-populated from `AutonomousSupervisorEngine.snapshots`, and completion/progress summaries post to Team Chat, but `TeamDashboardScreen.kt` does not yet render a dedicated recovery-history timeline card.
   - **Recommended Next Action**: Render `state.supervisorSnapshots` (status badge, per-agent health, retry count, recovery history) in `TeamDashboardScreen.kt`.

5. **Legacy `--dangerously-skip-permissions` in `AntigravityRuntimeBridge.kt` (Security Review)**
   - **Status**: `TeamAgentRunner.kt` and `RuntimeExecutionRequest` now default to `allowSkipPermissions = false`, while `AntigravityRuntimeBridge.kt` (`L219`, `L704`) retains `--dangerously-skip-permissions` for solo CLI sessions.
   - **Recommended Next Action**: Align `AntigravityRuntimeBridge.kt` with an explicit user-opt-in workspace permission setting.
