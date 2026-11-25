package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.tasks.TaskManager
import com.jarves.mh.multiagent.tasks.TaskStatus
import com.jarves.mh.multiagent.tasks.TeamTask
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 1 — Durable Task Foundation Automated Test Suite (TEST 01 through TEST 22).
 */
class Phase1DurableFoundationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun openStore(name: String = "orqen-phase1.db.json"): Pair<File, FileBackedOrqenTaskStore> {
        val file = File(tempFolder.root, name)
        return file to FileBackedOrqenTaskStore(file)
    }

    // TEST 01 — Database initialization: opens and validates schema
    @Test
    fun test01_databaseInitializationAndSchemaValidation() {
        val (_, store) = openStore("t01.db")
        assertEquals(4, store.schemaVersion)
        assertTrue(store.validateIntegrity())
    }

    // TEST 02 — Project persistence: survives close & reopen
    @Test
    fun test02_projectPersistenceSurvivesCloseAndReopen() {
        val (file, store1) = openStore("t02.db")
        val facade1 = OrqenPersistenceFacade(store1)
        facade1.createProject("proj-velnox", "Velnox", tempFolder.newFolder("velnox").absolutePath, "Kotlin + SQLite WAL")

        // Reopen from disk
        val store2 = FileBackedOrqenTaskStore(file)
        val loaded = store2.getProject("proj-velnox")
        assertNotNull(loaded)
        assertEquals("Velnox", loaded?.projectName)
        assertEquals("Kotlin + SQLite WAL", loaded?.projectMemory)
    }

    // TEST 03 — Conversation persistence: multiple independent conversations
    @Test
    fun test03_conversationPersistenceIndependentHistories() {
        val (_, store) = openStore("t03.db")
        val facade = OrqenPersistenceFacade(store)
        facade.createProject("p3", "Proj 3", tempFolder.newFolder("p3").absolutePath)
        val c1 = facade.createConversation("p3", "Chat A")
        val c2 = facade.createConversation("p3", "Chat B")

        store.insertConversationMessage(ConversationMessageEntity(conversationId = c1.conversationId, projectId = "p3", senderId = "u", senderName = "Owner", isUser = true, content = "Msg A"))
        store.insertConversationMessage(ConversationMessageEntity(conversationId = c2.conversationId, projectId = "p3", senderId = "u", senderName = "Owner", isUser = true, content = "Msg B"))

        assertEquals(1, store.listConversationMessages(c1.conversationId).size)
        assertEquals("Msg A", store.listConversationMessages(c1.conversationId).first().content)
        assertEquals("Msg B", store.listConversationMessages(c2.conversationId).first().content)
    }

    // TEST 04 — Task persistence: survives reopen
    @Test
    fun test04_taskPersistenceSurvivesReopen() {
        val (file, store1) = openStore("t04.db")
        val facade1 = OrqenPersistenceFacade(store1)
        facade1.createProject("p4", "Proj 4", tempFolder.newFolder("p4").absolutePath)
        val created = facade1.createTask("p4", "Build Auth", "OAuth2 login", taskId = "T-004")

        val store2 = FileBackedOrqenTaskStore(file)
        val loaded = store2.getTask(created.taskId)
        assertNotNull(loaded)
        assertEquals("Build Auth", loaded?.title)
        assertEquals(PersistentTaskStatus.QUEUED, loaded?.status)
    }

    // TEST 05 — Task-conversation many-to-many relationship
    @Test
    fun test05_taskConversationManyToManyRelationship() {
        val (_, store) = openStore("t05.db")
        val facade = OrqenPersistenceFacade(store)
        facade.createProject("p5", "Proj 5", tempFolder.newFolder("p5").absolutePath)
        val convA = facade.createConversation("p5", "Chat A")
        val convB = facade.createConversation("p5", "Chat B")
        val task = facade.createTask("p5", "Shared Backend Task", "Core API")

        facade.linkTaskToConversation(task.taskId, convA.conversationId)
        facade.linkTaskToConversation(task.taskId, convB.conversationId)

        assertEquals(listOf(task.taskId), store.listTasksForConversation(convA.conversationId).map { it.taskId })
        assertEquals(listOf(task.taskId), store.listTasksForConversation(convB.conversationId).map { it.taskId })
        assertEquals(2, store.listConversationsForTask(task.taskId).size)
    }

    // TEST 06 — Conversation deletion does NOT delete linked tasks
    @Test
    fun test06_conversationDeletionPreservesLinkedTasks() {
        val (_, store) = openStore("t06.db")
        val facade = OrqenPersistenceFacade(store)
        facade.createProject("p6", "Proj 6", tempFolder.newFolder("p6").absolutePath)
        val conv = facade.createConversation("p6", "Disposable Chat")
        val task = facade.createTask("p6", "Durable Task", "Must survive")
        facade.linkTaskToConversation(task.taskId, conv.conversationId)

        store.deleteConversationOnly(conv.conversationId)

        assertNull(store.getConversation(conv.conversationId))
        assertNotNull(store.getTask(task.taskId))
        assertEquals(PersistentTaskStatus.QUEUED, store.getTask(task.taskId)?.status)
    }

    // TEST 07 — Task step persistence: sequence ordering and dependencies
    @Test
    fun test07_taskStepPersistenceOrderingAndDependencies() {
        val (_, store) = openStore("t07.db")
        val facade = OrqenPersistenceFacade(store)
        facade.createProject("p7", "Proj 7", tempFolder.newFolder("p7").absolutePath)
        val task = facade.createTask("p7", "Multi-Step", "Ordered DAG")

        facade.createTaskStep(TaskStepEntity(stepId = "st2", taskId = task.taskId, sequenceNumber = 2, title = "Code", description = "", assignedAgentId = "dev", dependencies = listOf("st1")))
        facade.createTaskStep(TaskStepEntity(stepId = "st1", taskId = task.taskId, sequenceNumber = 1, title = "Plan", description = "", assignedAgentId = "lead"))

        val ordered = store.listStepsForTask(task.taskId)
        assertEquals(listOf("st1", "st2"), ordered.map { it.stepId })
        assertEquals(listOf("st1"), ordered[1].dependencies)
    }

    // TEST 08 — Transaction integrity: failure rolls back partial changes
    @Test
    fun test08_transactionRollbackOnFailure() {
        val (_, store) = openStore("t08.db")
        val facade = OrqenPersistenceFacade(store)
        facade.createProject("p8", "Proj 8", tempFolder.newFolder("p8").absolutePath)
        val task = facade.createTask("p8", "Original Title", "Original Desc")

        try {
            store.runInTransaction {
                store.upsertTask(task.copy(title = "Mutated Before Crash"))
                throw IllegalStateException("Simulated disk/write failure")
            }
            fail("Expected exception")
        } catch (_: IllegalStateException) {
            // Expected
        }

        assertEquals("Original Title", store.getTask(task.taskId)?.title)
    }

    // TEST 09 — Step completion: atomic status + event write, does NOT auto-complete task
    @Test
    fun test09_stepCompletionAtomicAndDoesNotAutoCompleteTask() {
        val (_, store) = openStore("t09.db")
        val facade = OrqenPersistenceFacade(store)
        facade.createProject("p9", "Proj 9", tempFolder.newFolder("p9").absolutePath)
        val task = facade.createTask("p9", "Two-Step Task", "")
        facade.updateTaskStatus(task.taskId, PersistentTaskStatus.RUNNING)
        val s1 = facade.createTaskStep(TaskStepEntity(stepId = "s9-1", taskId = task.taskId, sequenceNumber = 1, title = "Step 1", description = "", assignedAgentId = "dev", status = StepStatus.READY))
        facade.startTaskStep(task.taskId, s1.stepId)

        val done = facade.completeTaskStep(task.taskId, s1.stepId, "Implemented module A")

        assertEquals(StepStatus.COMPLETED, done.status)
        assertNotNull(done.completedAt)
        assertEquals(PersistentTaskStatus.RUNNING, store.getTask(task.taskId)?.status) // Task NOT auto-completed!
        assertTrue(store.listTaskEvents(task.taskId).any { it.eventType == "STEP_COMPLETED" })
    }

    // TEST 10 — Stop request survives reopen
    @Test
    fun test10_stopRequestSurvivesReopen() {
        val (file, store1) = openStore("t10.db")
        val facade1 = OrqenPersistenceFacade(store1)
        facade1.createProject("p10", "Proj 10", tempFolder.newFolder("p10").absolutePath)
        val task = facade1.createTask("p10", "Stoppable", "")
        facade1.requestTaskStop(task.taskId, "User pressed Stop")

        val store2 = FileBackedOrqenTaskStore(file)
        val loaded = store2.getTask(task.taskId)
        assertEquals(PersistentTaskStatus.CANCELLED, loaded?.status)
        assertTrue(loaded?.ownerStopRequested == true)
    }

    // TEST 11 — Pause request survives reopen
    @Test
    fun test11_pauseRequestSurvivesReopen() {
        val (file, store1) = openStore("t11.db")
        val facade1 = OrqenPersistenceFacade(store1)
        facade1.createProject("p11", "Proj 11", tempFolder.newFolder("p11").absolutePath)
        val task = facade1.createTask("p11", "Pausable", "")
        facade1.updateTaskStatus(task.taskId, PersistentTaskStatus.RUNNING)
        facade1.requestTaskPause(task.taskId, "User pressed Pause")

        val store2 = FileBackedOrqenTaskStore(file)
        val loaded = store2.getTask(task.taskId)
        assertTrue(loaded?.pauseRequested == true)
        assertEquals(PersistentTaskStatus.PAUSE_REQUESTED, loaded?.status)
    }

    // TEST 12 — Checkpoint persistence & validation
    @Test
    fun test12_checkpointPersistenceAndInvalidation() {
        val (file, store1) = openStore("t12.db")
        val ws = tempFolder.newFolder("ws12")
        val facade1 = OrqenPersistenceFacade(store1)
        facade1.createProject("p12", "Proj 12", ws.absolutePath)
        val task = facade1.createTask("p12", "Checkpoint Task", "")

        val chk = facade1.createCheckpoint(
            taskId = task.taskId,
            stepId = "step-1",
            permittedRoot = tempFolder.root,
            workspacePath = ws.absolutePath,
            modifiedFiles = listOf("Main.kt"),
            contextSummary = "Built Main.kt",
        )
        assertTrue(facade1.validateCheckpointReference(chk, tempFolder.root))

        val store2 = FileBackedOrqenTaskStore(file)
        val loaded = store2.getLatestCheckpoint(task.taskId)
        assertNotNull(loaded)
        assertEquals(chk.checkpointId, loaded?.checkpointId)

        store2.markCheckpointInvalid(chk.checkpointId)
        assertFalse(store2.listCheckpointsForTask(task.taskId).first().isValid)
    }

    // TEST 13 — Agent message persistence survives reopen
    @Test
    fun test13_agentMessagePersistenceSurvivesReopen() {
        val (file, store1) = openStore("t13.db")
        val facade1 = OrqenPersistenceFacade(store1)
        facade1.createProject("p13", "Proj 13", tempFolder.newFolder("p13").absolutePath)
        val task = facade1.createTask("p13", "Collab", "")
        facade1.recordAgentMessage(
            AgentMessageEntity(
                taskId = task.taskId,
                fromAgentId = "agent-leader",
                toAgentId = "agent-developer",
                messageType = AgentMessageKind.TASK_DELEGATION,
                content = "Implement repository layer",
            ),
        )

        val store2 = FileBackedOrqenTaskStore(file)
        val msgs = store2.listAgentMessagesForTask(task.taskId)
        assertEquals(1, msgs.size)
        assertEquals("Implement repository layer", msgs.first().content)
    }

    // TEST 14 — Event ordering is strictly chronological by sequenceNumber
    @Test
    fun test14_eventOrderingMonotonicSequence() {
        val (_, store) = openStore("t14.db")
        val facade = OrqenPersistenceFacade(store)
        facade.createProject("p14", "Proj 14", tempFolder.newFolder("p14").absolutePath)
        val task = facade.createTask("p14", "Events", "")
        facade.appendTaskEvent(task.taskId, "STEP_CREATED", "one")
        facade.appendTaskEvent(task.taskId, "STEP_STARTED", "two")
        facade.appendTaskEvent(task.taskId, "STEP_COMPLETED", "three")

        val events = store.listTaskEvents(task.taskId)
        assertEquals(listOf(1L, 2L, 3L, 4L), events.map { it.sequenceNumber })
        assertEquals(listOf("TASK_CREATED", "STEP_CREATED", "STEP_STARTED", "STEP_COMPLETED"), events.map { it.eventType })
    }

    // TEST 15 — Migration: upgrade v1 -> v3 without losing active/completed user data
    @Test
    fun test15_databaseSchemaMigrationPreservesUserData() {
        val store = InMemoryOrqenTaskStore(initialSchemaVersion = 1)
        store.upsertProject(ProjectEntity("p15", "Migrated", tempFolder.newFolder("p15").absolutePath))
        store.upsertTask(TaskEntity(taskId = "T-15-ACT", projectId = "p15", title = "Active", description = "", status = PersistentTaskStatus.RUNNING))
        store.upsertTask(TaskEntity(taskId = "T-15-DONE", projectId = "p15", title = "Done", description = "", status = PersistentTaskStatus.COMPLETED))
        store.upsertConversation(ConversationEntity(conversationId = "c15", projectId = "p15", title = "Chat", linkedTaskIds = listOf("T-15-ACT")))

        val migratedVersion = store.migrateToLatest()
        assertEquals(4, migratedVersion)
        assertTrue(store.validateIntegrity())
        assertEquals(PersistentTaskStatus.RUNNING, store.getTask("T-15-ACT")?.status)
        assertEquals(PersistentTaskStatus.COMPLETED, store.getTask("T-15-DONE")?.status)
        assertEquals(listOf("T-15-ACT"), store.listTasksForConversation("c15").map { it.taskId })
    }

    // TEST 16 — Duplicate migration is idempotent and does NOT duplicate records
    @Test
    fun test16_duplicateLegacyMigrationIsIdempotent() {
        val (_, store) = openStore("t16.db")
        val migrator = LegacyDataMigrator(store)
        val legacy = listOf(
            TeamTask(id = "LEG-1", title = "Legacy 1", description = "Desc 1", status = TaskStatus.IN_PROGRESS),
            TeamTask(id = "LEG-2", title = "Legacy 2", description = "Desc 2", status = TaskStatus.COMPLETED),
        )
        val ws = tempFolder.newFolder("p16").absolutePath
        val first = migrator.migrateLegacyTasks("legacy_v1", "p16", "Legacy Proj", ws, legacy)
        val second = migrator.migrateLegacyTasks("legacy_v1", "p16", "Legacy Proj", ws, legacy)

        assertFalse(first.alreadyMigrated)
        assertEquals(2, first.importedTasks)
        assertTrue(second.alreadyMigrated)
        assertEquals(0, second.importedTasks)
        assertEquals(2, store.listTasksForProject("p16").size)
    }

    // TEST 17 — Foreign-key integrity rejects invalid relationships
    @Test
    fun test17_foreignKeyIntegrityRejectsOrphans() {
        val (_, store) = openStore("t17.db")
        assertThrows(IllegalArgumentException::class.java) {
            store.upsertTask(TaskEntity(taskId = "T-ORPHAN", projectId = "missing-proj", title = "Bad", description = ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.upsertStep(TaskStepEntity(stepId = "S-ORPHAN", taskId = "missing-task", sequenceNumber = 1, title = "Bad", description = "", assignedAgentId = "dev"))
        }
    }

    // TEST 18 — Status transition validation rejects invalid terminal transitions
    @Test
    fun test18_statusTransitionValidationRejectsInvalidTerminalTransitions() {
        val (_, store) = openStore("t18.db")
        val facade = OrqenPersistenceFacade(store)
        facade.createProject("p18", "Proj 18", tempFolder.newFolder("p18").absolutePath)
        val task = facade.createTask("p18", "Terminal Test", "")
        facade.updateTaskStatus(task.taskId, PersistentTaskStatus.RUNNING)
        facade.updateTaskStatus(task.taskId, PersistentTaskStatus.COMPLETED)

        // Silent transition COMPLETED -> RUNNING must fail
        assertThrows(IllegalArgumentException::class.java) {
            facade.updateTaskStatus(task.taskId, PersistentTaskStatus.RUNNING)
        }
        // Explicit auditable user reopen COMPLETED -> QUEUED is allowed
        val reopened = facade.updateTaskStatus(task.taskId, PersistentTaskStatus.QUEUED, "Owner reopened", explicitUserReopen = true)
        assertEquals(PersistentTaskStatus.QUEUED, reopened.status)
    }

    // TEST 19 — Large history pagination for task events and agent messages
    @Test
    fun test19_largeHistoryPagination() {
        val (_, store) = openStore("t19.db")
        val facade = OrqenPersistenceFacade(store)
        facade.createProject("p19", "Proj 19", tempFolder.newFolder("p19").absolutePath)
        val task = facade.createTask("p19", "Large History", "")

        repeat(150) { i ->
            facade.appendTaskEvent(task.taskId, "PROGRESS", "Event #$i")
            facade.recordAgentMessage(
                AgentMessageEntity(
                    taskId = task.taskId,
                    fromAgentId = "dev",
                    toAgentId = "qa",
                    messageType = AgentMessageKind.AGENT_TO_AGENT,
                    content = "Message #$i",
                ),
            )
        }

        val page1Events = store.listTaskEventsPaged(task.taskId, offset = 0, limit = 25)
        val page2Events = store.listTaskEventsPaged(task.taskId, offset = 25, limit = 25)
        assertEquals(25, page1Events.size)
        assertEquals(25, page2Events.size)
        assertTrue(page1Events.last().sequenceNumber < page2Events.first().sequenceNumber)

        val page1Msgs = store.listAgentMessagesPaged(task.taskId, offset = 0, limit = 20)
        val page2Msgs = store.listAgentMessagesPaged(task.taskId, offset = 20, limit = 20)
        assertEquals(20, page1Msgs.size)
        assertEquals("Message #0", page1Msgs.first().content)
        assertEquals("Message #20", page2Msgs.first().content)
    }

    // TEST 20 — Legacy TaskManager compatibility backed by durable store
    @Test
    fun test20_legacyTaskManagerBackedByDurableStore() {
        val (_, store) = openStore("t20.db")
        val mgr = TaskManager(durableStore = store, projectId = "team-p20")
        val teamTask = TeamTask(id = "TM-20", title = "Legacy UI Task", description = "Verify StateFlow + DB")
        mgr.addTask(teamTask)
        mgr.updateTaskStatus("TM-20", TaskStatus.COMPLETED)

        assertEquals(1, mgr.tasks.value.size)
        assertEquals(TaskStatus.COMPLETED, mgr.tasks.value.first().status)
        assertEquals(PersistentTaskStatus.COMPLETED, store.getTask("TM-20")?.status)
    }

    // TEST 21 — Workspace safety: blocks path traversal outside permitted root
    @Test
    fun test21_workspaceSafetyRejectsPathTraversal() {
        val (_, store) = openStore("t21.db")
        val ws = tempFolder.newFolder("ws21")
        val facade = OrqenPersistenceFacade(store)
        facade.createProject("p21", "Proj 21", ws.absolutePath)
        val task = facade.createTask("p21", "Safety", "")

        assertThrows(IllegalArgumentException::class.java) {
            facade.createCheckpoint(
                taskId = task.taskId,
                stepId = "s1",
                permittedRoot = ws,
                workspacePath = "${ws.absolutePath}/../../etc",
                modifiedFiles = emptyList(),
                contextSummary = "Escape attempt",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            facade.createCheckpoint(
                taskId = task.taskId,
                stepId = "s1",
                permittedRoot = tempFolder.root,
                workspacePath = ws.absolutePath,
                modifiedFiles = listOf("../secrets.txt"),
                contextSummary = "File traversal attempt",
            )
        }
    }

    // TEST 22 — Secret protection: API keys and Authorization headers are never persisted
    @Test
    fun test22_secretProtectionRedactsApiKeysAndAuthHeaders() {
        val (_, store) = openStore("t22.db")
        val facade = OrqenPersistenceFacade(store)
        facade.createProject("p22", "Proj 22", tempFolder.newFolder("p22").absolutePath)
        val task = facade.createTask(
            "p22",
            "Auth Debug",
            "Failed with sk-ant-secret1234567890 and Bearer eyJhbGciOiJIUzI1NiJ9.payload",
        )
        facade.appendTaskEvent(task.taskId, "ERROR", "Authorization: Bearer supersecret999999 x-api-key: AIzaSyD1234567890123456789012345")

        val storedTask = store.getTask(task.taskId)!!
        val storedEvent = store.listTaskEvents(task.taskId).last()

        assertFalse(storedTask.description.contains("sk-ant-secret"))
        assertFalse(storedTask.description.contains("eyJhbGci"))
        assertFalse(storedEvent.eventPayload.contains("supersecret999999"))
        assertFalse(storedEvent.eventPayload.contains("AIzaSyD123456"))
        assertTrue(storedTask.description.contains("[REDACTED"))
        assertTrue(storedEvent.eventPayload.contains("[REDACTED"))
    }
}
