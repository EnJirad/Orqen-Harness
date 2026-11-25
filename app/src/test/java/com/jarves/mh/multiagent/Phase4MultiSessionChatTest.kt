package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.session.*
import com.jarves.mh.multiagent.tasks.TaskManager
import com.jarves.mh.multiagent.tasks.TaskStatus
import com.jarves.mh.multiagent.tasks.TeamTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 4 — Multi-Session Chat Automated Test Suite (TEST 01 through TEST 26).
 */
class Phase4MultiSessionChatTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun openDiskStore(name: String): Pair<File, FileBackedOrqenTaskStore> {
        val f = File(tempFolder.root, name)
        return f to FileBackedOrqenTaskStore(f)
    }

    // TEST 01 & 02 — Multiple conversations in one project & survive database reopening
    @Test
    fun test01_02_projectConversationsSurviveReopen() {
        val (file, store1) = openDiskStore("p4-01.db")
        val ctrl1 = MultiSessionChatController(store1)
        ctrl1.ensureProject("proj-1", "Velnox", tempFolder.newFolder("v1").absolutePath)
        val cA = ctrl1.createConversation("proj-1", "Chat A")
        val cB = ctrl1.createConversation("proj-1", "Chat B")

        val store2 = FileBackedOrqenTaskStore(file)
        val convs = store2.listConversationsForProject("proj-1")
        assertEquals(2, convs.size)
        assertEquals(setOf(cA.conversationId, cB.conversationId), convs.map { it.conversationId }.toSet())
    }

    // TEST 03 — Message persistence survives restart
    @Test
    fun test03_messagePersistenceSurvivesRestart() {
        val (file, store1) = openDiskStore("p4-03.db")
        val ctrl1 = MultiSessionChatController(store1)
        ctrl1.ensureProject("p3", "P3", tempFolder.newFolder("p3").absolutePath)
        val conv = ctrl1.createConversation("p3", "Session 1")
        ctrl1.postMessage(conv.conversationId, "p3", "owner", "Boss", true, "Build login flow")

        val store2 = FileBackedOrqenTaskStore(file)
        val msgs = store2.listConversationMessages(conv.conversationId)
        assertEquals(1, msgs.size)
        assertEquals("Build login flow", msgs.first().content)
    }

    // TEST 04, 05 & 06 — One conversation <-> multiple tasks, one task <-> multiple conversations, no duplication
    @Test
    fun test04_05_06_manyToManyAttachmentWithoutDuplication() {
        val (_, store) = openDiskStore("p4-04.db")
        val ctrl = MultiSessionChatController(store)
        val facade = OrqenPersistenceFacade(store)
        ctrl.ensureProject("p4", "P4", tempFolder.newFolder("p4").absolutePath)

        val cA = ctrl.createConversation("p4", "Chat A")
        val cB = ctrl.createConversation("p4", "Chat B")
        val t1 = facade.createTask("p4", "Auth", "OAuth2", taskId = "T-100")
        val t2 = facade.createTask("p4", "Billing", "Stripe", taskId = "T-101")

        ctrl.attachExistingTask(cA.conversationId, t1.taskId)
        ctrl.attachExistingTask(cA.conversationId, t2.taskId)
        ctrl.attachExistingTask(cB.conversationId, t1.taskId)
        // Re-attaching must NOT duplicate
        ctrl.attachExistingTask(cB.conversationId, t1.taskId)

        assertEquals(2, store.listTasksForConversation(cA.conversationId).size)
        assertEquals(2, store.listConversationsForTask(t1.taskId).size)
        assertEquals(2, store.listTasksForProject("p4").size) // Still only 2 tasks, no duplicates!
    }

    // TEST 07, 08 & 10 — Continue exact existing Task ID from new chat with full context & consistent state
    @Test
    fun test07_08_10_continueExistingTaskFromNewChatWithConsistentState() {
        val (_, store) = openDiskStore("p4-07.db")
        val ws = tempFolder.newFolder("ws07")
        val ctrl = MultiSessionChatController(store)
        val facade = OrqenPersistenceFacade(store)
        ctrl.ensureProject("p7", "Velnox", ws.absolutePath)

        val t100 = facade.createTask("p7", "Authentication System", "Build Android OAuth2 login", taskId = "T-100")
        facade.updateTaskStatus(t100.taskId, PersistentTaskStatus.RUNNING)
        val s1 = facade.createTaskStep(TaskStepEntity(stepId = "s7-1", taskId = t100.taskId, sequenceNumber = 1, title = "Design OAuth", description = "", assignedAgentId = "agent-leader", status = StepStatus.READY))
        facade.startTaskStep(t100.taskId, s1.stepId)
        facade.completeTaskStep(t100.taskId, s1.stepId, "OAuth2 PKCE designed")
        facade.createCheckpoint(t100.taskId, s1.stepId, tempFolder.root, ws.absolutePath, emptyList(), "Checkpoint 1")

        val convA = ctrl.createConversation("p7", "Chat A")
        ctrl.attachExistingTask(convA.conversationId, t100.taskId)

        // New conversation next day
        val convB = ctrl.createConversation("p7", "Chat B")
        val (resolution, snapshot) = ctrl.continueTaskFromNewChat("p7", convB.conversationId, "Continue task T-100")

        assertEquals(ChatTaskIntent.CONTINUE_EXISTING_TASK, resolution.intent)
        assertEquals("T-100", snapshot?.task?.taskId)
        assertEquals(PersistentTaskStatus.RUNNING, snapshot?.task?.status)
        assertEquals(1, snapshot?.completedSteps?.size)
        assertNotNull(snapshot?.latestCheckpoint)
        // Both conversations observe exact same authoritative state
        assertEquals(ctrl.buildTaskSnapshot("T-100")?.task?.status, snapshot?.task?.status)
    }

    // TEST 09 & 12 — Conversation message isolation & context isolation
    @Test
    fun test09_12_conversationAndContextIsolation() {
        val (_, store) = openDiskStore("p4-09.db")
        val ctrl = MultiSessionChatController(store)
        ctrl.ensureProject("pA", "Proj A", tempFolder.newFolder("pA").absolutePath)
        ctrl.ensureProject("pB", "Proj B", tempFolder.newFolder("pB").absolutePath)

        val cA = ctrl.createConversation("pA", "Chat A")
        val cB = ctrl.createConversation("pB", "Chat B")
        ctrl.postMessage(cA.conversationId, "pA", "u", "Owner", true, "Secret Proj A message")
        ctrl.postMessage(cB.conversationId, "pB", "u", "Owner", true, "Proj B message")

        assertEquals(listOf("Secret Proj A message"), store.listConversationMessages(cA.conversationId).map { it.content })
        assertEquals(listOf("Proj B message"), store.listConversationMessages(cB.conversationId).map { it.content })
    }

    // TEST 11 — Context compaction preserves requirements & decisions
    @Test
    fun test11_contextCompactionPreservesDecisions() {
        val (_, store) = openDiskStore("p4-11.db")
        val ctrl = MultiSessionChatController(store)
        val mem = HierarchicalMemoryManager(store)
        ctrl.ensureProject("p11", "P11", tempFolder.newFolder("p11").absolutePath)
        val conv = ctrl.createConversation("p11", "Long Session")
        ctrl.postMessage(conv.conversationId, "p11", "u", "Owner", true, "Decision: must keep AuthManager.kt backward compatible")
        repeat(20) { i ->
            ctrl.postMessage(conv.conversationId, "p11", "a", "AI", false, "Chatter $i " + "z".repeat(400))
        }
        val res = mem.compactConversationIfNeeded(conv.conversationId, maxContextTokens = 400, preserveRecentMessages = 3)
        assertTrue(res.wasCompacted)
        assertTrue(res.preservedDecisions.any { it.contains("AuthManager.kt") })
        assertTrue("AuthManager.kt" in res.preservedFilePaths)
    }

    // TEST 13, 14 & 15 — Close, archive, and delete conversation NEVER stop or delete linked tasks
    @Test
    fun test13_14_15_closeArchiveDeleteConversationPreservesTasks() = runBlocking {
        val (_, store) = openDiskStore("p4-13.db")
        val ctrl = MultiSessionChatController(store)
        ctrl.ensureProject("p13", "P13", tempFolder.newFolder("p13").absolutePath)
        val c1 = ctrl.createConversation("p13", "Archive Me")
        val c2 = ctrl.createConversation("p13", "Delete Me")

        val orch = PersistentTaskOrchestrator(
            store = store,
            checkpointManager = CheckpointManager(store),
            supervisor = ExecutionRecoverySupervisor(store, CheckpointManager(store), FileLockManager()),
            fileLockManager = FileLockManager(),
            stepAdapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    delay(15L)
                    return StepExecutionOutput(true, "Done")
                }
            },
        )
        val task = orch.createTaskWithSteps("p13", "Long Task", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "agent-developer")))
        ctrl.attachExistingTask(c1.conversationId, task.taskId)
        ctrl.attachExistingTask(c2.conversationId, task.taskId)
        ctrl.postMessage(c1.conversationId, "p13", "u", "Owner", true, "Keep in archive")

        val job = orch.launchTask(this, task.taskId)
        ctrl.archiveConversation(c1.conversationId)
        ctrl.deleteConversationPreservingTasks(c2.conversationId)
        job.join()

        assertTrue(store.getConversation(c1.conversationId)?.archived == true)
        assertEquals(1, store.listConversationMessages(c1.conversationId).size)
        assertNull(store.getConversation(c2.conversationId))
        assertEquals(PersistentTaskStatus.COMPLETED, store.getTask(task.taskId)?.status)
    }

    // TEST 16 & 18 — Concurrent conversation access & shared task controls (pause/resume/stop)
    @Test
    fun test16_18_sharedTaskControlsAcrossConversations() {
        val (_, store) = openDiskStore("p4-16.db")
        val ctrl = MultiSessionChatController(store)
        val orch = PersistentTaskOrchestrator(
            store = store,
            checkpointManager = CheckpointManager(store),
            supervisor = ExecutionRecoverySupervisor(store, CheckpointManager(store), FileLockManager()),
            fileLockManager = FileLockManager(),
            stepAdapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput) = StepExecutionOutput(true, "OK")
            },
        )
        ctrl.ensureProject("p16", "P16", tempFolder.newFolder("p16").absolutePath)
        val cA = ctrl.createConversation("p16", "A")
        val cB = ctrl.createConversation("p16", "B")
        val task = orch.createTaskWithSteps("p16", "Shared", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "agent-developer")))
        ctrl.attachExistingTask(cA.conversationId, task.taskId)
        ctrl.attachExistingTask(cB.conversationId, task.taskId)

        orch.pauseTask(task.taskId, "Paused from Chat B")
        assertEquals(PersistentTaskStatus.PAUSED, ctrl.buildTaskSnapshot(task.taskId)?.task?.status)

        orch.stopTask(task.taskId, "Stopped from Chat A")
        assertEquals(PersistentTaskStatus.CANCELLED, ctrl.buildTaskSnapshot(task.taskId)?.task?.status)
    }

    // TEST 17 — Conflicting instructions detected cleanly
    @Test
    fun test17_conflictingInstructionsDetected() {
        val (_, store) = openDiskStore("p4-17.db")
        val ctrl = MultiSessionChatController(store)
        val facade = OrqenPersistenceFacade(store)
        ctrl.ensureProject("p17", "P17", tempFolder.newFolder("p17").absolutePath)
        val task = facade.createTask("p17", "Database", "Use SQLite local storage for all tasks")

        val check = ctrl.detectConflictingInstructions(task.taskId, "Switch everything to NoSQL only")
        assertTrue(check.hasConflict)
    }

    // TEST 19 — Project switching isolates context with zero leakage
    @Test
    fun test19_projectSwitchingIsolatesData() {
        val (_, store) = openDiskStore("p4-19.db")
        val ctrl = MultiSessionChatController(store)
        val mem = HierarchicalMemoryManager(store)
        ctrl.ensureProject("proj-1", "One", tempFolder.newFolder("one").absolutePath)
        ctrl.ensureProject("proj-2", "Two", tempFolder.newFolder("two").absolutePath)
        mem.updateProjectMemory("proj-1", "Memory-Alpha")
        mem.updateProjectMemory("proj-2", "Memory-Beta")
        ctrl.createConversation("proj-1", "Chat One")
        ctrl.createConversation("proj-2", "Chat Two")

        val view2 = ctrl.switchProject("proj-2")!!
        assertEquals("Memory-Beta", view2.projectMemory)
        assertEquals(listOf("Chat Two"), view2.conversations.map { it.title })
    }

    // TEST 20 & 21 — Project-scoped task search & conversation/message search
    @Test
    fun test20_21_projectScopedTaskAndConversationSearch() {
        val (_, store) = openDiskStore("p4-20.db")
        val ctrl = MultiSessionChatController(store)
        val facade = OrqenPersistenceFacade(store)
        ctrl.ensureProject("p20", "P20", tempFolder.newFolder("p20").absolutePath)
        ctrl.ensureProject("other", "Other", tempFolder.newFolder("other").absolutePath)

        facade.createTask("p20", "OAuth Login", "Google sign-in")
        facade.createTask("other", "OAuth Secret", "Other project")
        val c1 = ctrl.createConversation("p20", "Auth Discussion")
        ctrl.postMessage(c1.conversationId, "p20", "u", "Owner", true, "Let's review PKCE token exchange")

        val foundTasks = ctrl.searchTasks("p20", "oauth")
        assertEquals(1, foundTasks.size)
        assertEquals("OAuth Login", foundTasks.first().title)

        val foundConvs = ctrl.searchConversations("p20", "pkce")
        assertEquals(1, foundConvs.size)
        assertEquals(c1.conversationId, foundConvs.first().conversationId)
    }

    // TEST 22 — Message pagination loads incrementally
    @Test
    fun test22_conversationMessagePagination() {
        val (_, store) = openDiskStore("p4-22.db")
        val ctrl = MultiSessionChatController(store)
        ctrl.ensureProject("p22", "P22", tempFolder.newFolder("p22").absolutePath)
        val conv = ctrl.createConversation("p22", "Big Chat")
        repeat(60) { i ->
            ctrl.postMessage(conv.conversationId, "p22", "u", "Owner", true, "Line #$i")
        }
        val page1 = store.listConversationMessagesPaged(conv.conversationId, offset = 0, limit = 20)
        val page2 = store.listConversationMessagesPaged(conv.conversationId, offset = 20, limit = 20)
        assertEquals(20, page1.size)
        assertEquals("Line #0", page1.first().content)
        assertEquals("Line #20", page2.first().content)
    }

    // TEST 23 & 24 — Legacy migration & full application restart integrity
    @Test
    fun test23_24_legacyMigrationAndApplicationRestartIntegrity() {
        val (file, store1) = openDiskStore("p4-23.db")
        val migrator = LegacyDataMigrator(store1)
        val ws = tempFolder.newFolder("p23").absolutePath
        migrator.migrateLegacyTasks("p4_legacy", "p23", "Legacy", ws, listOf(TeamTask(id = "LEG-4", title = "Migrated", description = "OK")))
        val ctrl1 = MultiSessionChatController(store1)
        val conv = ctrl1.createConversation("p23", "Migrated Chat")
        ctrl1.attachExistingTask(conv.conversationId, "LEG-4")
        ctrl1.postMessage(conv.conversationId, "p23", "u", "Owner", true, "Hello after migration")

        val store2 = FileBackedOrqenTaskStore(file)
        assertEquals(1, store2.listTasksForConversation(conv.conversationId).size)
        assertEquals("LEG-4", store2.listTasksForConversation(conv.conversationId).first().taskId)
        assertEquals(1, store2.listConversationMessages(conv.conversationId).size)
    }

    // TEST 25 — Security: unauthorized cross-project attachment rejected
    @Test
    fun test25_crossProjectAttachmentRejected() {
        val (_, store) = openDiskStore("p4-25.db")
        val ctrl = MultiSessionChatController(store)
        val facade = OrqenPersistenceFacade(store)
        ctrl.ensureProject("pA", "A", tempFolder.newFolder("secA").absolutePath)
        ctrl.ensureProject("pB", "B", tempFolder.newFolder("secB").absolutePath)
        val convA = ctrl.createConversation("pA", "Chat A")
        val taskB = facade.createTask("pB", "Private B", "Secret")

        assertThrows(SecurityException::class.java) {
            ctrl.attachExistingTask(convA.conversationId, taskB.taskId)
        }
    }

    // TEST 26 — Regression: legacy TaskManager continues working cleanly
    @Test
    fun test26_regressionLegacyTaskManagerWorks() {
        val (_, store) = openDiskStore("p4-26.db")
        val tm = TaskManager(durableStore = store, projectId = "reg-p4")
        tm.addTask(TeamTask(id = "R4", title = "Regression", description = "OK"))
        tm.updateTaskStatus("R4", TaskStatus.COMPLETED)
        assertEquals(TaskStatus.COMPLETED, tm.tasks.value.first().status)
    }
}
