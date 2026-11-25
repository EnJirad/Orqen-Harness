package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.persistence.*
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 3 — Multi-Agent Collaboration Automated Test Suite (TEST 01 through TEST 28).
 */
class Phase3MultiAgentCollaborationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sampleProfiles(count: Int = 6): List<AgentProfileEntity> {
        val roles = listOf(
            "Architect" to setOf(AgentCapability.ARCHITECTURE_DESIGN, AgentCapability.CODE_ANALYSIS),
            "Developer" to setOf(AgentCapability.KOTLIN_DEVELOPMENT, AgentCapability.ANDROID_DEVELOPMENT),
            "Reviewer" to setOf(AgentCapability.CODE_REVIEW, AgentCapability.TESTING),
            "Security" to setOf(AgentCapability.SECURITY_REVIEW, AgentCapability.CODE_REVIEW),
            "Researcher" to setOf(AgentCapability.RESEARCH, AgentCapability.DOCUMENTATION),
            "Backend" to setOf(AgentCapability.BACKEND_DEVELOPMENT, AgentCapability.DATABASE_DESIGN),
        )
        return (0 until count).map { i ->
            val (role, caps) = roles[i % roles.size]
            AgentProfileEntity(
                agentId = "agent-${i + 1}",
                displayName = "$role #${i + 1}",
                role = role,
                providerId = if (i % 2 == 0) "preset-antigravity" else "preset-openai",
                modelId = if (i % 2 == 0) "gemini-2.5-pro" else "gpt-4o",
                capabilities = caps,
                priority = 10 - i,
            )
        }
    }

    // TEST 01 — Agent profile persistence survives reopening
    @Test
    fun test01_agentProfilePersistenceSurvivesReopen() {
        val file = File(tempFolder.root, "collab-01.json")
        val reg1 = AgentRegistry(FileBackedCollaborationStore(file))
        reg1.register(
            AgentProfileEntity(
                agentId = "agent-arch",
                displayName = "Lead Architect",
                role = "Architect",
                providerId = "preset-anthropic",
                modelId = "claude-sonnet-4",
                capabilities = setOf(AgentCapability.ARCHITECTURE_DESIGN),
            ),
        )

        val reg2 = AgentRegistry(FileBackedCollaborationStore(file))
        val loaded = reg2.get("agent-arch")
        assertNotNull(loaded)
        assertEquals("Lead Architect", loaded?.displayName)
        assertTrue(AgentCapability.ARCHITECTURE_DESIGN in loaded!!.capabilities)
    }

    // TEST 02 — Dynamic team size (not hardcoded to 2 or 4: test 1, 3, and 7 agents)
    @Test
    fun test02_dynamicConfigurableTeamSizes() {
        val store = InMemoryCollaborationStore()
        val reg = AgentRegistry(store)
        val profiles = sampleProfiles(7).onEach { reg.register(it) }
        val teamMgr = AgentTeamManager(store, reg)

        val t1 = teamMgr.createTeam("p1", "Solo", profiles.take(1).map { it.agentId })
        val t3 = teamMgr.createTeam("p1", "Trio", profiles.take(3).map { it.agentId })
        val t7 = teamMgr.createTeam("p1", "Enterprise 7", profiles.map { it.agentId })

        assertEquals(1, teamMgr.getTeamProfiles(t1.teamId).size)
        assertEquals(3, teamMgr.getTeamProfiles(t3.teamId).size)
        assertEquals(7, teamMgr.getTeamProfiles(t7.teamId).size)
    }

    // TEST 03 — Stable agent identity across executions
    @Test
    fun test03_stableAgentIdentityAcrossExecutions() {
        val taskStore = InMemoryOrqenTaskStore()
        taskStore.upsertProject(ProjectEntity("p3", "P3", tempFolder.newFolder("p3").absolutePath))
        taskStore.upsertTask(TaskEntity(taskId = "T3", projectId = "p3", title = "ID", description = ""))

        taskStore.insertExecution(AgentExecutionEntity(executionId = "e1", taskId = "T3", stepId = "s1", agentId = "agent-dev-stable", providerId = "p", modelId = "m"))
        taskStore.insertExecution(AgentExecutionEntity(executionId = "e2", taskId = "T3", stepId = "s2", agentId = "agent-dev-stable", providerId = "p", modelId = "m"))

        val execs = taskStore.listExecutionsForTask("T3")
        assertEquals(2, execs.size)
        assertTrue(execs.all { it.agentId == "agent-dev-stable" })
    }

    // TEST 04 — Capability-based agent selection
    @Test
    fun test04_capabilityBasedAssignment() {
        val profiles = sampleProfiles(6)
        val resolver = AgentCapabilityResolver()
        val sec = resolver.selectBestAgent(profiles, setOf(AgentCapability.SECURITY_REVIEW))
        val db = resolver.selectBestAgent(profiles, setOf(AgentCapability.DATABASE_DESIGN))
        assertEquals("agent-4", sec?.agentId)
        assertEquals("agent-6", db?.agentId)
    }

    // TEST 05 & 06 — Team orchestration & sequential collaboration
    @Test
    fun test05_06_teamOrchestrationSequentialOrder() {
        val store = InMemoryCollaborationStore()
        val taskStore = InMemoryOrqenTaskStore()
        taskStore.upsertProject(ProjectEntity("p5", "P5", tempFolder.newFolder("p5").absolutePath))
        taskStore.upsertTask(TaskEntity(taskId = "T5", projectId = "p5", title = "Seq", description = ""))
        val reg = AgentRegistry(store)
        val profiles = sampleProfiles(3).onEach { reg.register(it) }
        val teamMgr = AgentTeamManager(store, reg)
        val team = teamMgr.createTeam("p5", "Seq Team", profiles.map { it.agentId }, CollaborationStrategy.SEQUENTIAL)
        val delMgr = AgentDelegationManager(store, taskStore, AgentCapabilityResolver())

        val d1 = delMgr.delegate(team, profiles, "T5", "step-1", "agent-1", "Implement Kotlin", setOf(AgentCapability.KOTLIN_DEVELOPMENT))
        delMgr.completeDelegation(d1.delegationId, "Kotlin done")
        val d2 = delMgr.delegate(team, profiles, "T5", "step-2", "agent-1", "Review code", setOf(AgentCapability.CODE_REVIEW))
        delMgr.completeDelegation(d2.delegationId, "Review done")

        val all = store.listDelegationsForTask("T5")
        assertEquals(listOf("agent-2", "agent-3"), all.map { it.assignedAgentId })
        assertTrue(all.all { it.status == DelegationStatus.COMPLETED })
    }

    // TEST 07 & 22 — Parallel collaboration respects configured concurrency resource limit
    @Test
    fun test07_22_parallelCollaborationRespectsConcurrencyLimit() = runBlocking {
        val store = InMemoryCollaborationStore()
        val taskStore = InMemoryOrqenTaskStore()
        val profiles = sampleProfiles(6)
        val resMgr = AgentResourceManager(maxConcurrentAgents = 2)
        val orch = AgentOrchestrator(store, taskStore, resMgr)

        val assignments = profiles.mapIndexed { idx, p -> "assign-$idx" to p }
        val results = orch.executeAssignmentsParallel(assignments) { id, _ ->
            delay(25L)
            "done-$id"
        }

        assertEquals(6, results.size)
        assertTrue("Peak concurrency ${resMgr.peakObservedConcurrency()} must not exceed 2", resMgr.peakObservedConcurrency() <= 2)
        val snap = resMgr.snapshot(profiles)
        assertEquals(6, snap.configuredAgentCount) // Never silently shrunk!
    }

    // TEST 08, 09 & 10 — Persistent delegation, direct/broadcast messaging, and correlation IDs
    @Test
    fun test08_09_10_delegationMessagingAndCorrelation() {
        val store = InMemoryCollaborationStore()
        val taskStore = InMemoryOrqenTaskStore()
        taskStore.upsertProject(ProjectEntity("p8", "P8", tempFolder.newFolder("p8").absolutePath))
        taskStore.upsertTask(TaskEntity(taskId = "T8", projectId = "p8", title = "Msg", description = ""))
        val reg = AgentRegistry(store)
        val profiles = sampleProfiles(3).onEach { reg.register(it) }
        val team = AgentTeamManager(store, reg).createTeam("p8", "Team", profiles.map { it.agentId })
        val delMgr = AgentDelegationManager(store, taskStore, AgentCapabilityResolver())
        val comm = AgentCommunicationManager(taskStore)

        val del = delMgr.delegate(team, profiles, "T8", "s1", "agent-1", "Build API", setOf(AgentCapability.KOTLIN_DEVELOPMENT))
        delMgr.completeDelegation(del.delegationId, "API built")
        comm.broadcast("T8", "agent-1", AgentMessageKind.SHARED_DECISION, "Use JSON-RPC")

        val correlated = comm.getIsolatedContextMessages("p8", "T8", "agent-2", correlationId = del.correlationId)
        assertEquals(2, correlated.size)
        val broadcastVisible = comm.getIsolatedContextMessages("p8", "T8", "agent-3")
        assertTrue(broadcastVisible.any { it.content.contains("Use JSON-RPC") })
    }

    // TEST 11 & 26 — Context isolation & cross-project access denial
    @Test
    fun test11_26_contextIsolationAndCrossProjectSecurity() {
        val taskStore = InMemoryOrqenTaskStore()
        taskStore.upsertProject(ProjectEntity("proj-A", "A", tempFolder.newFolder("projA").absolutePath))
        taskStore.upsertProject(ProjectEntity("proj-B", "B", tempFolder.newFolder("projB").absolutePath))
        taskStore.upsertTask(TaskEntity(taskId = "TA", projectId = "proj-A", title = "Secret A", description = ""))

        val comm = AgentCommunicationManager(taskStore)
        comm.sendDirect("TA", "agent-1", "agent-2", AgentMessageKind.AGENT_TO_AGENT, "Private note with sk-secret123456789012")

        // Agent 3 must NOT see private message between Agent 1 and Agent 2
        val inbox3 = comm.getIsolatedContextMessages("proj-A", "TA", "agent-3")
        assertTrue(inbox3.isEmpty())

        // Agent 2 sees message with secret redacted
        val inbox2 = comm.getIsolatedContextMessages("proj-A", "TA", "agent-2")
        assertEquals(1, inbox2.size)
        assertFalse(inbox2.first().content.contains("sk-secret123456789012"))

        // Cross-project access to TA from proj-B must throw SecurityException
        assertThrows(SecurityException::class.java) {
            comm.getIsolatedContextMessages("proj-B", "TA", "agent-2")
        }
    }

    // TEST 12, 13 & 14 — Independent peer review: blocks self-approval, rejection -> revision -> approval
    @Test
    fun test12_13_14_independentPeerReviewRejectionAndApproval() {
        val store = InMemoryCollaborationStore()
        val taskStore = InMemoryOrqenTaskStore()
        taskStore.upsertProject(ProjectEntity("p12", "P12", tempFolder.newFolder("p12").absolutePath))
        taskStore.upsertTask(TaskEntity(taskId = "T12", projectId = "p12", title = "Review", description = ""))
        val reviewMgr = AgentReviewManager(store, taskStore)

        // Self-approval blocked
        assertThrows(IllegalArgumentException::class.java) {
            reviewMgr.submitReview("T12", "agent-2", "agent-2", "exec-1", "step-1", ReviewOutcome.APPROVED)
        }

        // Independent reviewer rejects
        val rej = reviewMgr.submitReview(
            taskId = "T12",
            reviewerAgentId = "agent-3",
            implementingAgentId = "agent-2",
            reviewedExecutionId = "exec-1",
            reviewedStepId = "step-1",
            outcome = ReviewOutcome.CHANGES_REQUESTED,
            findings = listOf("Missing null check"),
            severity = ReviewSeverity.HIGH,
            requiredChanges = listOf("Add null guard"),
        )
        assertEquals(ReviewOutcome.CHANGES_REQUESTED, rej.outcome)

        // After revision, reviewer approves
        val appr = reviewMgr.submitReview(
            taskId = "T12",
            reviewerAgentId = "agent-3",
            implementingAgentId = "agent-2",
            reviewedExecutionId = "exec-2",
            reviewedStepId = "step-1",
            outcome = ReviewOutcome.APPROVED,
            evidenceReferences = listOf("Unit tests passed"),
        )
        assertEquals(ReviewOutcome.APPROVED, appr.outcome)
        assertEquals(2, store.listReviewsForStep("step-1").size)
    }

    // TEST 15 — Evidence-based consensus (never lets insecure proposal win)
    @Test
    fun test15_evidenceBasedConsensus() {
        val store = InMemoryCollaborationStore()
        val consensus = AgentConsensusManager(store)
        val decision = consensus.evaluateProposals(
            taskId = "T15",
            topic = "Token Storage",
            proposals = listOf(
                TechnicalProposal("prop-A", "agent-1", "Store plaintext in prefs", listOf("fast", "simple", "short"), passesSecurityCheck = false),
                TechnicalProposal("prop-B", "agent-4", "Use EncryptedSharedPreferences", listOf("AES256-GCM"), passesSecurityCheck = true),
            ),
        )
        assertEquals(ConsensusOutcome.ACCEPT_PROPOSAL_B, decision.outcome)
        assertEquals(1, store.listDecisionsForTask("T15").size)
    }

    // TEST 16, 17 & 18 — File locking, TTL expiration recovery, and stale-write conflict prevention
    @Test
    fun test16_17_18_fileLockingExpirationAndConflictPrevention() {
        val store = InMemoryCollaborationStore()
        val ws = tempFolder.newFolder("ws16")
        val coord = AgentWorkspaceCoordinator(store)

        val now = 10_000L
        val lockA = coord.acquireLock("p16", "T16", ws, "src/Main.kt", "agent-1", "exec-1", ttlMs = 1_000L, nowMs = now)
        assertNotNull(lockA)

        // Conflicting agent cannot acquire active lock
        assertNull(coord.acquireLock("p16", "T16", ws, "src/Main.kt", "agent-2", "exec-2", ttlMs = 1_000L, nowMs = now + 500L))

        // After TTL expiration, agent-2 safely recovers lock; stale agent-1 cannot write
        val lockB = coord.acquireLock("p16", "T16", ws, "src/Main.kt", "agent-2", "exec-2", ttlMs = 5_000L, nowMs = now + 1_500L)
        assertNotNull(lockB)
        assertFalse(coord.canWrite("p16", ws, "src/Main.kt", "agent-1", "exec-1", nowMs = now + 1_600L))
        assertTrue(coord.canWrite("p16", ws, "src/Main.kt", "agent-2", "exec-2", nowMs = now + 1_600L))
    }

    // TEST 19 & 20 — Agent failure preserves history & reassigns unfinished work without duplicating completed work
    @Test
    fun test19_20_agentFailurePreservesHistoryAndSafeReassignment() {
        val store = InMemoryCollaborationStore()
        val taskStore = InMemoryOrqenTaskStore()
        taskStore.upsertProject(ProjectEntity("p19", "P19", tempFolder.newFolder("p19").absolutePath))
        taskStore.upsertTask(TaskEntity(taskId = "T19", projectId = "p19", title = "Failover", description = ""))

        val dev1 = AgentProfileEntity(agentId = "dev-1", displayName = "Dev 1", role = "Dev", providerId = "p1", modelId = "m1", capabilities = setOf(AgentCapability.KOTLIN_DEVELOPMENT))
        val dev2 = AgentProfileEntity(agentId = "dev-2", displayName = "Dev 2", role = "Dev", providerId = "p2", modelId = "m2", capabilities = setOf(AgentCapability.KOTLIN_DEVELOPMENT))
        val del = AgentDelegationEntity(taskId = "T19", projectId = "p19", parentStepId = "s1", delegatingAgentId = "lead", assignedAgentId = "dev-1", objective = "Build feature", requiredCapabilities = setOf(AgentCapability.KOTLIN_DEVELOPMENT))
        store.upsertDelegation(del)

        val failMgr = AgentFailureManager(store, taskStore, AgentCapabilityResolver())
        val reassigned = failMgr.recordFailureAndReassign(del.delegationId, "dev-1", "Provider timeout", listOf(dev1, dev2))

        assertEquals("dev-2", reassigned?.assignedAgentId)
        assertEquals(DelegationStatus.REASSIGNED, reassigned?.status)
        assertTrue(taskStore.listAgentMessagesForTask("T19").any { it.fromAgentId == "dev-1" })

        // Completed delegation must NEVER be reassigned
        store.upsertDelegation(reassigned!!.copy(status = DelegationStatus.COMPLETED))
        val noDup = failMgr.recordFailureAndReassign(del.delegationId, "dev-2", "Late alert", listOf(dev1, dev2))
        assertEquals(DelegationStatus.COMPLETED, noDup?.status)
    }

    // TEST 21 — Provider rate/concurrency limits respected per provider
    @Test
    fun test21_providerConcurrencyLimitsRespected() = runBlocking {
        val resMgr = AgentResourceManager(maxConcurrentAgents = 4, providerConcurrencyLimits = mapOf("preset-openai" to 1))
        val activeOnOpenAi = AtomicInteger(0)
        val peakOnOpenAi = AtomicInteger(0)
        val store = InMemoryCollaborationStore()
        val orch = AgentOrchestrator(store, InMemoryOrqenTaskStore(), resMgr)
        val openAiAgents = (1..3).map {
            "oa-$it" to AgentProfileEntity(agentId = "oa-$it", displayName = "OA $it", role = "Dev", providerId = "preset-openai", modelId = "gpt-4o")
        }
        orch.executeAssignmentsParallel(openAiAgents) { _, _ ->
            val cur = activeOnOpenAi.incrementAndGet()
            peakOnOpenAi.updateAndGet { prev -> maxOf(prev, cur) }
            delay(20L)
            activeOnOpenAi.decrementAndGet()
        }
        assertEquals(1, peakOnOpenAi.get())
    }

    // TEST 23 — Owner approval gate blocks high-risk action until explicitly approved
    @Test
    fun test23_ownerApprovalGateBlocksHighRiskAction() {
        val store = InMemoryCollaborationStore()
        val taskStore = InMemoryOrqenTaskStore()
        taskStore.upsertProject(ProjectEntity("p23", "P23", tempFolder.newFolder("p23").absolutePath))
        taskStore.upsertTask(TaskEntity(taskId = "T23", projectId = "p23", title = "Prod Deploy", description = ""))
        val orch = AgentOrchestrator(store, taskStore, AgentResourceManager())

        val req = orch.requestOwnerApproval(
            OwnerApprovalRequest(
                taskId = "T23",
                projectId = "p23",
                requestedByAgentId = "agent-1",
                actionTitle = "Drop legacy table",
                reason = "Cleanup",
                impact = "Irreversible schema change",
                riskLevel = RiskLevel.CRITICAL,
            ),
        )
        assertFalse(orch.canProceedWithAction(req.approvalId))
        orch.resolveOwnerApproval(req.approvalId, approved = true)
        assertTrue(orch.canProceedWithAction(req.approvalId))
    }

    // TEST 24 — Application restart preserves profiles, tasks, and messages
    @Test
    fun test24_applicationRestartPreservesCollaborationData() {
        val dbFile = File(tempFolder.root, "restart-24.json")
        val taskStore1 = FileBackedOrqenTaskStore(dbFile)
        taskStore1.upsertProject(ProjectEntity("p24", "P24", tempFolder.newFolder("p24").absolutePath))
        taskStore1.upsertTask(TaskEntity(taskId = "T24", projectId = "p24", title = "Survive", description = ""))
        taskStore1.insertAgentMessage(AgentMessageEntity(taskId = "T24", fromAgentId = "a1", toAgentId = "a2", messageType = AgentMessageKind.TASK_DELEGATION, content = "Persisted"))

        val taskStore2 = FileBackedOrqenTaskStore(dbFile)
        assertEquals(1, taskStore2.listAgentMessagesForTask("T24").size)
    }

    // TEST 25 — Duplicate scheduling events do not execute the same assignment twice
    @Test
    fun test25_duplicateSchedulingPrevented() = runBlocking {
        val ran = AtomicInteger(0)
        val orch = AgentOrchestrator(InMemoryCollaborationStore(), InMemoryOrqenTaskStore(), AgentResourceManager(2))
        val agent = sampleProfiles(1).first()
        orch.executeAssignmentsParallel(listOf("dup-1" to agent, "dup-1" to agent)) { _, _ ->
            ran.incrementAndGet()
        }
        assertEquals(1, ran.get())
    }

    // TEST 27 — Legacy compatibility: TaskManager remains fully functional
    @Test
    fun test27_legacyCompatibilityPreserved() {
        val taskStore = InMemoryOrqenTaskStore()
        val tm = TaskManager(durableStore = taskStore, projectId = "legacy-p3")
        tm.addTask(TeamTask(id = "L3", title = "Legacy", description = "OK"))
        tm.updateTaskStatus("L3", TaskStatus.COMPLETED)
        assertEquals(TaskStatus.COMPLETED, tm.tasks.value.first().status)
    }

    // TEST 28 — Schema migration v3 -> v4 preserves existing tasks and execution records
    @Test
    fun test28_collaborationSchemaMigrationPreservesExistingRecords() {
        val store = InMemoryOrqenTaskStore(initialSchemaVersion = 3)
        store.upsertProject(ProjectEntity("p28", "P28", tempFolder.newFolder("p28").absolutePath))
        store.upsertTask(TaskEntity(taskId = "T28", projectId = "p28", title = "Existing", description = "", status = PersistentTaskStatus.RUNNING))
        store.insertExecution(AgentExecutionEntity(executionId = "exec-28", taskId = "T28", stepId = "s1", agentId = "a1", providerId = "p", modelId = "m"))

        val newVer = store.migrateToLatest()
        assertEquals(4, newVer)
        assertTrue(store.validateIntegrity())
        assertEquals(PersistentTaskStatus.RUNNING, store.getTask("T28")?.status)
        assertEquals(1, store.listExecutionsForTask("T28").size)
    }
}
