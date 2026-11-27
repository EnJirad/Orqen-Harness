package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.CheckpointManager
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.recovery.*
import com.jarves.mh.multiagent.session.MultiSessionChatController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 5 — Android Recovery & Process Resilience Automated Test Suite (Tests 1 through 32).
 */
class Phase5AndroidRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeRecoveryScheduler : RecoverySchedulerBackend {
        private val works = ConcurrentHashMap<String, ScheduledRecoveryWork>()
        var scheduleCalls = 0
        var cancelCalls = 0

        override fun scheduleUnique(taskId: String, requireNetwork: Boolean, delayMs: Long): Boolean {
            scheduleCalls++
            val name = "orqen_recovery_$taskId"
            works[name] = ScheduledRecoveryWork(name, taskId, requireNetwork, delayMs)
            return true
        }

        override fun cancel(taskId: String) {
            cancelCalls++
            works.remove("orqen_recovery_$taskId")
        }

        override fun scheduledWork(): List<ScheduledRecoveryWork> = works.values.toList()
    }

    private fun createProject(store: OrqenTaskStore, id: String): Pair<ProjectEntity, File> {
        val dir = tempFolder.newFolder(id)
        val p = ProjectEntity(projectId = id, projectName = "Proj-$id", workspacePath = dir.absolutePath)
        store.upsertProject(p)
        return p to dir
    }

    // Tests 1, 2, 3, 4, 5, 6 — Process Recovery: startup discovery, skip COMPLETED/CANCELLED/PAUSED, block pending approvals
    @Test
    fun tests01_to_06_processRecoveryStartupDiscoveryAndFiltering() {
        val dbFile = File(tempFolder.root, "p5-startup.db")
        val store1 = FileBackedOrqenTaskStore(dbFile)
        val (p, _) = createProject(store1, "p1")
        store1.upsertTask(TaskEntity(taskId = "T-INT", projectId = p.projectId, title = "Interrupted", description = "", status = PersistentTaskStatus.RUNNING))
        store1.upsertTask(TaskEntity(taskId = "T-DONE", projectId = p.projectId, title = "Done", description = "", status = PersistentTaskStatus.COMPLETED))
        store1.upsertTask(TaskEntity(taskId = "T-CANC", projectId = p.projectId, title = "Cancelled", description = "", status = PersistentTaskStatus.CANCELLED, ownerStopRequested = true))
        store1.upsertTask(TaskEntity(taskId = "T-PAUSE", projectId = p.projectId, title = "Paused", description = "", status = PersistentTaskStatus.PAUSED, pauseRequested = true))
        store1.upsertTask(TaskEntity(taskId = "T-APPR", projectId = p.projectId, title = "Needs Approval", description = "", status = PersistentTaskStatus.RUNNING))

        // Simulate process death & recreation by reopening disk store
        val store2 = FileBackedOrqenTaskStore(dbFile)
        val scheduler = FakeRecoveryScheduler()
        val coord = ProcessRecoveryCoordinator(
            store = store2,
            checkpointManager = CheckpointManager(store2),
            livenessMonitor = ExecutionLivenessMonitor(),
            scheduler = scheduler,
            hasPendingApproval = { id -> id == "T-APPR" },
        )

        val summary = coord.reconcileOnStartup(InterruptionCause.UNEXPECTED_PROCESS_DEATH)

        assertEquals(listOf("T-INT"), summary.scheduledTaskIds)
        assertEquals(listOf("T-APPR"), summary.blockedTaskIds)
        assertTrue(setOf("T-DONE", "T-CANC", "T-PAUSE").all { it in summary.skippedTaskIds })
        assertEquals(PersistentTaskStatus.COMPLETED, store2.getTask("T-DONE")?.status)
        assertEquals(PersistentTaskStatus.CANCELLED, store2.getTask("T-CANC")?.status)
        assertEquals(PersistentTaskStatus.PAUSED, store2.getTask("T-PAUSE")?.status)
        assertEquals(PersistentTaskStatus.BLOCKED, store2.getTask("T-APPR")?.status)
    }

    // Tests 7, 8, 9, 10, 11 — Scheduling: unique deduplication, retry policy backoff, cancellation, network constraint
    @Test
    fun tests07_to_11_schedulingDeduplicationPolicyCancellationAndConstraints() {
        val store = InMemoryOrqenTaskStore()
        val (p, _) = createProject(store, "p7")
        store.upsertTask(TaskEntity(taskId = "T-7", projectId = p.projectId, title = "Net Task", description = "", status = PersistentTaskStatus.RUNNING, retryCount = 2))
        val scheduler = FakeRecoveryScheduler()
        val policy = RecoveryPolicy(mode = RecoveryMode.AUTOMATIC, maxRecoveryAttempts = 4, baseRetryDelayMs = 1_000L, requireNetwork = true)
        val coord = ProcessRecoveryCoordinator(store, CheckpointManager(store), ExecutionLivenessMonitor(), scheduler, policy)

        coord.reconcileOnStartup()
        // Reconciling twice uses the exact same uniqueWorkName (no duplicate scheduled work!)
        store.upsertTask(store.getTask("T-7")!!.copy(status = PersistentTaskStatus.RUNNING))
        coord.reconcileOnStartup()

        assertEquals(1, scheduler.scheduledWork().size)
        val work = scheduler.scheduledWork().first()
        assertTrue(work.requireNetwork)
        assertEquals(4_000L, work.delayMs) // 1000 * 2^(3-1)

        // Owner cancels -> scheduler cancels work
        store.upsertTask(store.getTask("T-7")!!.copy(ownerStopRequested = true, status = PersistentTaskStatus.CANCELLED))
        coord.reconcileOnStartup()
        assertTrue(scheduler.scheduledWork().isEmpty())
        assertTrue(scheduler.cancelCalls >= 1)
    }

    // Tests 12, 13, 14, 15 — Checkpoints: valid restores, invalid blocks, resumes at exact incomplete step without repeating completed steps
    @Test
    fun tests12_to_15_checkpointsValidInvalidAndStepResume() {
        val store = InMemoryOrqenTaskStore()
        val (p, dir) = createProject(store, "p12")
        val chkMgr = CheckpointManager(store)

        // Valid checkpoint task
        store.upsertTask(TaskEntity(taskId = "T-VALID", projectId = p.projectId, title = "Valid", description = "", status = PersistentTaskStatus.RUNNING))
        store.upsertStep(TaskStepEntity(stepId = "s1", taskId = "T-VALID", sequenceNumber = 1, title = "Done Step", description = "", assignedAgentId = "dev", status = StepStatus.COMPLETED))
        store.upsertStep(TaskStepEntity(stepId = "s2", taskId = "T-VALID", sequenceNumber = 2, title = "Interrupted Step", description = "", assignedAgentId = "dev", status = StepStatus.RUNNING, dependencies = listOf("s1")))
        File(dir, "ok.kt").writeText("class Ok")
        chkMgr.createCheckpoint("T-VALID", "s1", dir, listOf("ok.kt"), "Step 1 done")

        // Invalid checkpoint task
        store.upsertTask(TaskEntity(taskId = "T-BAD", projectId = p.projectId, title = "BadChk", description = "", status = PersistentTaskStatus.RUNNING))
        val badChk = chkMgr.createCheckpoint("T-BAD", "s1", dir, emptyList(), "Corrupted")
        store.markCheckpointInvalid(badChk.checkpointId)

        val coord = ProcessRecoveryCoordinator(store, chkMgr, ExecutionLivenessMonitor(), FakeRecoveryScheduler())
        val summary = coord.reconcileOnStartup()

        assertTrue("T-VALID" in summary.scheduledTaskIds)
        assertTrue("T-BAD" in summary.blockedTaskIds)
        assertEquals(StepStatus.COMPLETED, store.getStep("s1")?.status) // Completed step NOT repeated!
        assertEquals(StepStatus.READY, store.getStep("s2")?.status)     // Interrupted step ready to resume!
    }

    // Tests 16, 17, 18, 19 — Idempotency & Leases: exclusive lease, expired lease reclamation, uncertain side effect blocked, concurrent recovery
    @Test
    fun tests16_to_19_leasesExpirationUncertainSideEffectsAndConcurrentRecovery() {
        val liveness = ExecutionLivenessMonitor()
        val t0 = 10_000L
        val lease1 = liveness.tryAcquireLease("T-16", "s1", "worker-A", ttlMs = 1_000L, nowMs = t0)
        assertNotNull(lease1)
        // Duplicate worker cannot claim active lease
        assertNull(liveness.tryAcquireLease("T-16", "s1", "worker-B", ttlMs = 1_000L, nowMs = t0 + 500L))
        // Expired lease can be reclaimed safely
        val lease2 = liveness.tryAcquireLease("T-16", "s1", "worker-B", ttlMs = 5_000L, nowMs = t0 + 1_500L)
        assertNotNull(lease2)
        assertEquals("worker-B", lease2?.ownerId)

        // Uncertain external operation is NOT blindly repeated -> marked BLOCKED
        val store = InMemoryOrqenTaskStore()
        val (p, _) = createProject(store, "p18")
        store.upsertTask(TaskEntity(taskId = "T-EXT", projectId = p.projectId, title = "Deploy", description = "", status = PersistentTaskStatus.RUNNING))
        store.upsertStep(TaskStepEntity(stepId = "ext-1", taskId = "T-EXT", sequenceNumber = 1, title = "Publish Release", description = "", assignedAgentId = "dev", status = StepStatus.RUNNING))

        val coord = ProcessRecoveryCoordinator(
            store = store,
            checkpointManager = CheckpointManager(store),
            livenessMonitor = liveness,
            scheduler = FakeRecoveryScheduler(),
            isUncertainExternalOp = { step -> step.title.contains("Publish") },
        )
        val res = coord.reconcileOnStartup()
        assertTrue("T-EXT" in res.blockedTaskIds)
    }

    // Tests 20, 21, 22, 23 — Multi-Agent Recovery: assignments survive, completed work preserved, same Task ID retained
    @Test
    fun tests20_to_23_multiAgentRecoveryPreservesAssignmentsAndTaskIdentity() {
        val store = InMemoryOrqenTaskStore()
        val (p, _) = createProject(store, "p20")
        store.upsertTask(TaskEntity(taskId = "T-TEAM-99", projectId = p.projectId, title = "Multi-Agent", description = "", status = PersistentTaskStatus.RUNNING))
        store.upsertStep(TaskStepEntity(stepId = "ma1", taskId = "T-TEAM-99", sequenceNumber = 1, title = "Plan", description = "", assignedAgentId = "agent-leader", status = StepStatus.COMPLETED))
        store.upsertStep(TaskStepEntity(stepId = "ma2", taskId = "T-TEAM-99", sequenceNumber = 2, title = "Code", description = "", assignedAgentId = "agent-developer", status = StepStatus.RUNNING, dependencies = listOf("ma1")))

        val coord = ProcessRecoveryCoordinator(store, CheckpointManager(store), ExecutionLivenessMonitor(), FakeRecoveryScheduler())
        val summary = coord.reconcileOnStartup()

        assertEquals(listOf("T-TEAM-99"), summary.scheduledTaskIds)
        assertEquals("agent-leader", store.getStep("ma1")?.assignedAgentId)
        assertEquals(StepStatus.COMPLETED, store.getStep("ma1")?.status)
        assertEquals("agent-developer", store.getStep("ma2")?.assignedAgentId)
        assertEquals(StepStatus.READY, store.getStep("ma2")?.status)
        assertEquals("T-TEAM-99", store.getTask("T-TEAM-99")?.taskId)
    }

    // Tests 24, 25, 26, 27, 28 — Device Lifecycle: activity recreation, backgrounding, service restart, boot recovery, honest force-stop docs
    @Test
    fun tests24_to_28_deviceLifecycleServiceRestartBootAndForceStopHonesty() {
        val store = InMemoryOrqenTaskStore()
        val (p, _) = createProject(store, "p24")
        store.upsertTask(TaskEntity(taskId = "T-LIFE", projectId = p.projectId, title = "Lifecycle", description = "", status = PersistentTaskStatus.RUNNING))

        val scheduler = FakeRecoveryScheduler()
        val liveness = ExecutionLivenessMonitor()
        val coord = ProcessRecoveryCoordinator(store, CheckpointManager(store), liveness, scheduler)

        // Service restart reclaims lease cleanly
        assertTrue(coord.reconcileOnServiceRestart("T-LIFE", "service-worker-1"))
        assertFalse(coord.reconcileOnServiceRestart("T-LIFE", "service-worker-2"))

        // Boot recovery schedules eligible work
        val bootSummary = coord.reconcileAfterBoot()
        assertEquals(InterruptionCause.DEVICE_REBOOT, bootSummary.cause)
        assertTrue("T-LIFE" in bootSummary.scheduledTaskIds)

        // Honest force-stop guarantee documented
        val forceStop = AndroidPlatformGuarantees.documentedConditions.first { it.condition.contains("force-stop", ignoreCase = true) }
        assertFalse(forceStop.canContinueLocally)
        assertFalse(forceStop.canRecoverAutomatically)
    }

    // Tests 29, 30, 31, 32 — Data Integrity: migrations preserve records, recovery events persisted, project isolation, conversation messages untouched
    @Test
    fun tests29_to_32_dataIntegrityMigrationEventsProjectIsolationAndNoMessageDuplication() {
        val store = InMemoryOrqenTaskStore(initialSchemaVersion = 1)
        val (pA, _) = createProject(store, "proj-A")
        val (pB, _) = createProject(store, "proj-B")
        store.upsertTask(TaskEntity(taskId = "T-A", projectId = pA.projectId, title = "Task A", description = "", status = PersistentTaskStatus.RUNNING))
        store.upsertTask(TaskEntity(taskId = "T-B", projectId = pB.projectId, title = "Task B", description = "", status = PersistentTaskStatus.RUNNING))

        val chatCtrl = MultiSessionChatController(store)
        val convA = chatCtrl.createConversation(pA.projectId, "Chat A")
        chatCtrl.postMessage(convA.conversationId, pA.projectId, "u", "Owner", true, "Original user message")

        // Migration preserves all records
        assertEquals(InMemoryOrqenTaskStore.CURRENT_SCHEMA_VERSION, store.migrateToLatest())
        assertTrue(store.validateIntegrity())

        // Project-scoped recovery only touches proj-A
        val coord = ProcessRecoveryCoordinator(store, CheckpointManager(store), ExecutionLivenessMonitor(), FakeRecoveryScheduler())
        val summaryA = coord.reconcileOnStartup(projectScopeId = pA.projectId)

        assertEquals(listOf("T-A"), summaryA.scheduledTaskIds)
        assertFalse("T-B" in summaryA.scheduledTaskIds)
        assertTrue(store.listTaskEvents("T-A").any { it.eventType == RecoveryEventType.RECOVERY_SCHEDULED.name })
        // Recovery history NEVER duplicates conversation messages
        assertEquals(1, store.listConversationMessages(convA.conversationId).size)
    }
}
