# P0 Crash Root Cause Report — App Crashes Immediately When Pressing Start AI Team

- **Base Commit SHA**: `752e0e13fe073aabc595a215d6f7be556124a8b2`
- **Severity**: P0 (Fatal Main-Thread Crash on Start AI Team)

## Exact Root Cause (Confirmed from Source Code Schema Audit)
1. **Primary Crash Cause — Missing SQLite Column `agent_profiles.preferred_tools_json`**:
   - In `SqliteOrqenTaskDatabase.kt` (`migrateV3ToV4`), table `agent_profiles` was created **without** column `preferred_tools_json`.
   - In `SqliteCollaborationStore.kt`, `upsertProfile(...)` wrote `cv.put("preferred_tools_json", ...)` via `db.insertWithOnConflict("agent_profiles", ...)` (which fails on Android SQLite with `SQLiteException: table agent_profiles has no column named preferred_tools_json` and returns `-1`) AND `readProfile(...)` called `c.getColumnIndexOrThrow("preferred_tools_json")`.
   - When the user tapped **Start AI Team**:
     1. `MainViewModel.startTeamWork()` (Main UI Thread) called `syncTeamProfilesForProject(activeProj)` synchronously.
     2. `syncTeamProfilesForProject` called `autonomousSupervisorEngine.registry.register(...)` → `SqliteCollaborationStore.upsertProfile(...)` (failed to insert due to missing `preferred_tools_json`).
     3. Next line called `autonomousSupervisorEngine.teamManager.createTeam(...)` → `registry.get(agentId)` returned `null` (or threw on `getColumnIndexOrThrow("preferred_tools_json")`) → threw **`IllegalArgumentException: Unknown agent profile: agent-01`** on the Main Thread, immediately crashing and closing the app!
2. **Secondary Crash Amplification**:
   - `startTeamWork()`, `resumeTeamWork()`, and `submitAutonomousTeamObjective()` executed `syncTeamProfilesForProject(...)` synchronously on the Main UI Thread without an error boundary, and lacked duplicate simultaneous Start button protection.

## Minimal Hotfix Applied
1. **`app/src/main/java/com/jarves/mh/multiagent/persistence/SqliteOrqenTaskDatabase.kt`**:
   - Added `preferred_tools_json TEXT NOT NULL DEFAULT '[]'` to `CREATE TABLE IF NOT EXISTS agent_profiles`.
   - Bumped `DATABASE_VERSION = 6` and added non-destructive `migrateV5ToV6(db)` (`ALTER TABLE agent_profiles ADD COLUMN preferred_tools_json TEXT NOT NULL DEFAULT '[]'`) so existing devices upgrading from v4/v5 never crash.
2. **`app/src/main/java/com/jarves/mh/multiagent/collab/SqliteCollaborationStore.kt`**:
   - Validated `insertWithOnConflict(...) != -1L` with clear diagnostic message.
   - Replaced `getColumnIndexOrThrow("preferred_tools_json")` with safe `getColumnIndex("preferred_tools_json")` lookup.
3. **`app/src/main/java/com/jarves/mh/ui/MainViewModel.kt`**:
   - Added duplicate-start guard and enabled-agent validation before starting.
   - Moved `syncTeamProfilesForProject(...)` and Supervisor execution onto `Dispatchers.IO` inside `viewModelScope.launch` with explicit `try / catch (CancellationException) / catch (Throwable)` logging full stack trace (`Log.e`), cleaning up `teamIsRunning` / foreground service only if started, preserving existing valid tasks untouched, and surfacing clear UI error banners/toasts instead of crashing.
   - Added `Log.e` diagnostic logging on `startTeamForegroundService` failure.

## Note on Physical Device Logcat
- No physical Android device or `adb` emulator is attached to this sandbox (`NO_ADB_DEVICE`). To verify on a physical device after installing `ORQEN-debug.apk`:
  ```bash
  adb logcat -c && adb logcat *:E AndroidRuntime:E OrqenMainViewModel:V SQLiteDatabase:E
  ```
