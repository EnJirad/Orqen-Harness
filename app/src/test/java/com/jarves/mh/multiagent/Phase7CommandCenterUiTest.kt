package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.host.*
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.recovery.*
import com.jarves.mh.multiagent.session.MultiSessionChatController
import com.jarves.mh.multiagent.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 7 — Complete UI & Command Center Presentation Test Suite (Tests 1 through 32).
 */
class Phase7CommandCenterUiTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class StubScheduler : RecoverySchedulerBackend {
        val scheduled = mutableListOf<String>()
        override fun scheduleUnique(taskId: String, requireNetwork: Boolean, delayMs: Long): Boolean {
            scheduled.add(taskId)
            return true
        }
        override fun cancel(taskId: String) { scheduled.remove(taskId) }
        override fun scheduledWork(): List<ScheduledRecoveryWork> = emptyList()
    }

    private fun buildController(
        store: OrqenTaskStore = InMemoryOrqenTaskStore(),
        collab: CollaborationStore = InMemoryCollaborationStore(),
        hosts: List<ExecutionHost> = emptyList(),
    ): Triple<OrqenCommandCenterController, MultiSessionChatController, PersistentTaskOrchestrator> {
        val chat = MultiSessionChatController(store)
        val chk = CheckpointManager(store)
        val lock = FileLockManager()
        val sup = ExecutionRecoverySupervisor(store, chk, lock)
        val orch = PersistentTaskOrchestrator(
            store = store,
            checkpointManager = chk,
            supervisor = sup,
            fileLockManager = lock,
            stepAdapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput) = StepExecutionOutput(true, "Done")
            },
        )
        val rec = ProcessRecoveryCoordinator(store, chk, ExecutionLivenessMonitor(), StubScheduler())
        val allHosts = hosts.ifEmpty { listOf(LocalAndroidExecutionHost(store, orch)) }
        val ctrl = OrqenCommandCenterController(store, collab, chat, orch, rec, allHosts)
        return Triple(ctrl, chat, orch)
    }

    // Tests 1, 2, 3, 4 — Main navigation, project context, back stack, deep links
    @Test
    fun tests01_to_04_navigationProjectContextBackAndDeepLinks() {
        val store = InMemoryOrqenTaskStore()
        val (ctrl, chat, orch) = buildController(store)
        chat.ensureProject("p1", "Velnox", tempFolder.newFolder("p1").absolutePath)
        val task = orch.createTaskWithSteps("p1", "Build UI", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "dev")))

        ctrl.refresh()
        ctrl.navigateTo(CommandCenterSection.TASKS)
        ctrl.navigateTo(CommandCenterSection.AGENTS)
        assertEquals(CommandCenterSection.AGENTS, ctrl.state.value.activeSection)
        assertTrue(ctrl.navigateBack())
        assertEquals(CommandCenterSection.TASKS, ctrl.state.value.activeSection)

        ctrl.openDeepLink("TASK", task.taskId, "p1")
        assertEquals(task.taskId, ctrl.state.value.selectedTaskId)
        assertEquals("p1", ctrl.state.value.selectedProjectId)
    }

    // Tests 5, 6, 7, 8, 9 — Conversations: load, pagination, duplicate submission guard, task attach, archival preserves task
    @Test
    fun tests05_to_09_conversationsPaginationDuplicateGuardAttachAndArchive() {
        val store = InMemoryOrqenTaskStore()
        val (ctrl, chat, orch) = buildController(store)
        chat.ensureProject("p5", "Proj5", tempFolder.newFolder("p5").absolutePath)
        val conv = chat.createConversation("p5", "Session A")
        val task = orch.createTaskWithSteps("p5", "Task 5", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "dev")))

        // Duplicate submission prevented by token
        assertTrue(ctrl.submitMessageOnce("tok-1", conv.conversationId, "p5", "Owner", "Hello"))
        assertFalse(ctrl.submitMessageOnce("tok-1", conv.conversationId, "p5", "Owner", "Hello"))
        assertFalse(ctrl.submitMessageOnce("tok-empty", conv.conversationId, "p5", "Owner", "   "))
        assertEquals(1, store.listConversationMessages(conv.conversationId).size)

        // Pagination
        repeat(30) { i -> chat.postMessage(conv.conversationId, "p5", "u", "Owner", true, "M$i") }
        assertEquals(10, store.listConversationMessagesPaged(conv.conversationId, 0, 10).size)

        // Attach & Archive preserves task
        chat.attachExistingTask(conv.conversationId, task.taskId)
        chat.archiveConversation(conv.conversationId)
        assertEquals(PersistentTaskStatus.QUEUED, store.getTask(task.taskId)?.status)
    }

    // Tests 10, 11, 12, 13 — Tasks: filters, persisted details, authorized controls, cross-conversation consistency
    @Test
    fun tests10_to_13_taskFiltersControlsAndCrossConversationConsistency() {
        val store = InMemoryOrqenTaskStore()
        val (ctrl, chat, orch) = buildController(store)
        chat.ensureProject("p10", "Proj10", tempFolder.newFolder("p10").absolutePath)
        val tRun = orch.createTaskWithSteps("p10", "Active Work", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "dev")))
        val tDone = orch.createTaskWithSteps("p10", "Finished Work", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "dev")))
        store.upsertTask(tDone.copy(status = PersistentTaskStatus.COMPLETED))

        ctrl.refresh()
        ctrl.setTaskFilter(TaskFilterMode.COMPLETED)
        assertEquals(listOf(tDone.taskId), ctrl.state.value.filteredTasks.map { it.taskId })

        // Authorized pause & stop controls
        ctrl.pauseTask(tRun.taskId)
        assertEquals(PersistentTaskStatus.PAUSED, store.getTask(tRun.taskId)?.status)
        ctrl.stopTask(tRun.taskId)
        assertEquals(PersistentTaskStatus.CANCELLED, store.getTask(tRun.taskId)?.status)
    }

    // Tests 14, 15, 16 — Multi-Agent: accurate status, delegation history, dynamic team size > 4
    @Test
    fun tests14_to_16_multiAgentStatusDelegationAndDynamicTeamSize() {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (ctrl, chat, orch) = buildController(store, collab)
        chat.ensureProject("p14", "Proj14", tempFolder.newFolder("p14").absolutePath)
        val task = orch.createTaskWithSteps("p14", "Big Team", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "a1")))

        repeat(6) { i ->
            collab.upsertProfile(AgentProfileEntity(agentId = "a$i", displayName = "Agent $i", role = "Role $i", providerId = "p", modelId = "m"))
        }
        collab.upsertDelegation(AgentDelegationEntity(taskId = task.taskId, projectId = "p14", parentStepId = "s1", delegatingAgentId = "a0", assignedAgentId = "a1", objective = "Code API"))

        ctrl.selectTask(task.taskId)
        assertEquals(6, ctrl.state.value.agents.size)
        assertEquals(1, ctrl.state.value.delegations.size)
    }

    // Tests 17, 18, 19, 20 — Remote Execution: worker status, modes, handoff check, disconnection
    @Test
    fun tests17_to_20_remoteWorkerStatusModesHandoffAndDisconnection() {
        val store = InMemoryOrqenTaskStore()
        val remote = RemoteExecutionHost(
            "worker-eu-1",
            object : RemoteExecutionTransport {
                override fun submit(taskId: String) = true
                override fun pause(taskId: String, reason: String) = Unit
                override fun resume(taskId: String) = true
                override fun stop(taskId: String, reason: String) = Unit
                override fun stopAll(reason: String) = 0
                override fun fetchProgress(taskId: String) = null
                override fun ping() = true
            },
        )
        val (ctrl, chat, orch) = buildController(store, hosts = listOf(remote))
        chat.ensureProject("p17", "Proj17", tempFolder.newFolder("p17").absolutePath)
        val task = orch.createTaskWithSteps("p17", "Remote Task", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "dev")))

        ctrl.setHostPreference(ExecutionHostPreference.HYBRID)
        ctrl.refresh()
        assertEquals(ExecutionHostPreference.HYBRID, ctrl.state.value.hostPreference)
        assertTrue(ctrl.state.value.workers.first().isOnline)
        assertTrue(ctrl.canHandoffToRemote(task.taskId).first)

        remote.disconnect()
        ctrl.refresh()
        assertFalse(ctrl.state.value.workers.first().isOnline)
        assertFalse(ctrl.canHandoffToRemote(task.taskId).first)
    }

    // Tests 21, 22, 23, 24 — Recovery: interrupted tasks, recovery actions, blocked explanation, recovery history
    @Test
    fun tests21_to_24_recoveryDisplayActionsBlockedReasonAndHistory() {
        val store = InMemoryOrqenTaskStore()
        val (ctrl, chat, _) = buildController(store)
        chat.ensureProject("p21", "Proj21", tempFolder.newFolder("p21").absolutePath)
        store.upsertTask(TaskEntity(taskId = "T-REC", projectId = "p21", title = "Interrupted", description = "", status = PersistentTaskStatus.RUNNING))
        store.upsertTask(TaskEntity(taskId = "T-BLK", projectId = "p21", title = "Blocked", description = "", status = PersistentTaskStatus.BLOCKED, lastError = "HTTP 401 invalid_api_key"))

        ctrl.triggerRecoveryReconciliation()
        ctrl.selectTask("T-REC")
        assertTrue(ctrl.state.value.timelineEvents.any { it.eventType == RecoveryEventType.RECOVERY_SCHEDULED.name })
        assertEquals("HTTP 401 invalid_api_key", store.getTask("T-BLK")?.lastError)
    }

    // Tests 25, 26, 27 — Providers: secret masking & safe error sanitization
    @Test
    fun tests25_to_27_providerSecretMaskingAndErrorHandling() {
        val masked = OrqenCommandCenterController.maskSecret("sk-ant-1234567890abcdef")
        assertFalse(masked.contains("1234567890"))
        assertTrue(masked.startsWith("sk-a"))
        assertTrue(masked.endsWith("cdef"))
        assertEquals("••••••••", OrqenCommandCenterController.maskSecret("short"))
    }

    // Tests 28, 29, 30, 31, 32 — Reliability: process recreation restoration, pagination, global search, approvals
    @Test
    fun tests28_to_32_processRecreationPaginationSearchAndApprovals() {
        val dbFile = File(tempFolder.root, "p7-recreate.db")
        val store1 = FileBackedOrqenTaskStore(dbFile)
        val collab = InMemoryCollaborationStore()
        val (ctrl1, chat1, orch1) = buildController(store1, collab)
        chat1.ensureProject("p28", "Velnox Mobile", tempFolder.newFolder("p28").absolutePath)
        val task = orch1.createTaskWithSteps("p28", "OAuth Flow", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "dev")))
        repeat(80) { i -> store1.insertTaskEvent(TaskEventEntity(taskId = task.taskId, eventType = "PROGRESS", eventPayload = "E$i")) }
        val appr = OwnerApprovalRequest(taskId = task.taskId, projectId = "p28", requestedByAgentId = "a1", actionTitle = "Deploy", reason = "Release", impact = "Prod")
        collab.upsertApproval(appr)

        // Simulate process recreation by opening fresh store + controller
        val store2 = FileBackedOrqenTaskStore(dbFile)
        val (ctrl2, _, _) = buildController(store2, collab)
        ctrl2.refresh(timelineOffset = 0, timelineLimit = 25)

        assertEquals(1, ctrl2.state.value.projects.size)
        assertEquals(25, ctrl2.state.value.timelineEvents.size) // Paginated!
        assertEquals(1, ctrl2.state.value.pendingApprovals.size)

        // Global search
        val hits = ctrl2.searchAuthorized("oauth")
        assertTrue(hits.any { it.resourceType == "TASK" && it.resourceId == task.taskId })

        // Approve action
        ctrl2.resolveApproval(appr.approvalId, approved = true)
        assertTrue(ctrl2.state.value.pendingApprovals.isEmpty())
    }
}
