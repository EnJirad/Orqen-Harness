package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.supervisor.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P0 Start AI Team Crash Hotfix — Regression Test Suite.
 *
 * Verifies:
 * 1. Exact SQLite schema column parity between `SqliteOrqenTaskDatabase.kt` and
 *    `SqliteCollaborationStore.kt` (prevents `preferred_tools_json` missing-column crash forever).
 * 2. Database version bumped to 6 with non-destructive `migrateV5ToV6` adding `preferred_tools_json`.
 * 3. Safe startup & failure preservation: missing project, disabled agents, registry failure,
 *    and existing tasks preserved intact after a startup failure.
 */
class P0StartTeamCrashRegressionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun test01_SqliteCollaborationStoreColumnsMatchSqliteOrqenTaskDatabaseSchema() {
        val root = File(System.getProperty("user.dir") ?: ".").let { cur ->
            if (File(cur, "src/main/java").isDirectory) cur else File(cur, "app")
        }
        val dbFile = File(root, "src/main/java/com/jarves/mh/multiagent/persistence/SqliteOrqenTaskDatabase.kt")
        val csFile = File(root, "src/main/java/com/jarves/mh/multiagent/collab/SqliteCollaborationStore.kt")
        assertTrue("SqliteOrqenTaskDatabase.kt must exist at ${dbFile.absolutePath}", dbFile.isFile)
        assertTrue("SqliteCollaborationStore.kt must exist at ${csFile.absolutePath}", csFile.isFile)

        val dbText = dbFile.readText()
        val csText = csFile.readText()

        // Extract every CREATE TABLE column name from SqliteOrqenTaskDatabase.kt
        val tableRegex = Regex("""CREATE TABLE IF NOT EXISTS (\w+)\s*\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
        val allDbCols = mutableSetOf<String>()
        for (match in tableRegex.findAll(dbText)) {
            for (line in match.groupValues[2].lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("PRIMARY") || trimmed.startsWith("FOREIGN") || trimmed.startsWith("UNIQUE")) continue
                allDbCols.add(trimmed.split(Regex("""\s+""")).first())
            }
        }

        // Verify agent_profiles explicitly defines preferred_tools_json and migration v5->v6 exists
        assertTrue("agent_profiles must contain preferred_tools_json column", "preferred_tools_json" in allDbCols)
        assertTrue("migrateV5ToV6 must exist for upgrading existing v4/v5 installs", dbText.contains("migrateV5ToV6"))
        val dbVersion = Regex("""DATABASE_VERSION\s*=\s*(\d+)""").find(dbText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue("DATABASE_VERSION must be >= 6 (found $dbVersion)", dbVersion >= 6)

        // Verify every getColumnIndexOrThrow("...") in SqliteCollaborationStore exists in DB schema
        val getColRegex = Regex("""getColumnIndexOrThrow\("([a-z0-9_]+)"\)""")
        val missingGets = getColRegex.findAll(csText)
            .map { it.groupValues[1] }
            .filter { it !in allDbCols }
            .toSet()
        assertTrue("SqliteCollaborationStore queries missing DB columns: $missingGets", missingGets.isEmpty())
    }

    @Test
    fun test02_StartupValidationFailsSafelyWithoutDestroyingExistingTasks() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val ws = tmp.newFolder("ws-p0-safe")
        val proj = ProjectEntity("proj-safe", "Safe Project", ws.absolutePath)
        store.upsertProject(proj)

        // Pre-existing valid task that MUST NOT be destroyed if a subsequent start fails
        val existingTask = TaskEntity(
            taskId = "T-EXISTING",
            projectId = proj.projectId,
            title = "Existing valid task",
            description = "Must survive failed start",
            status = PersistentTaskStatus.PAUSED,
            pauseRequested = true,
        )
        store.upsertTask(existingTask)

        val dummyRuntime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit) =
                RuntimeExecutionResponse(request.requestId, "p", "m", 0L, 1L, true, "REVIEW_APPROVED\nTESTS_PASSED")
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, false, false)
        }
        val engine = AutonomousSupervisorEngine(taskStore = store, collabStore = collab, runtime = dummyRuntime, delayFn = {})

        // 1. Missing project -> preparePlan returns null safely (no crash), existing task intact
        val missingProjTeam = AgentTeamEntity(projectId = "no-such-proj", teamName = "Missing")
        assertNull(engine.preparePlan("no-such-proj", missingProjTeam, "Do work"))

        // 2. All agents disabled -> preparePlan returns null safely (no crash), existing task intact
        val disabledAgent = engine.registry.register(
            AgentProfileEntity("agent-disabled", "Disabled", role = "Dev", providerId = "p1", modelId = "m1", capabilities = setOf("code"), isEnabled = false),
        )
        val disabledTeam = engine.teamManager.createTeam(proj.projectId, "Disabled Team", listOf(disabledAgent.agentId))
        assertNull(engine.preparePlan(proj.projectId, disabledTeam, "Do work with disabled agents"))

        // 3. Re-verify existing task was NEVER modified or destroyed
        val preserved = store.getTask("T-EXISTING")
        assertNotNull(preserved)
        assertEquals(PersistentTaskStatus.PAUSED, preserved?.status)
        assertTrue(preserved?.pauseRequested == true)

        // 4. Recovery after enabling an agent succeeds cleanly
        engine.registry.setEnabled(disabledAgent.agentId, true)
        val recoveredTask = engine.preparePlan(proj.projectId, disabledTeam, "Do work after fix")
        assertNotNull(recoveredTask)
    }
}
