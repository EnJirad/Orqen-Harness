package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.recovery.ExecutionLivenessMonitor
import com.jarves.mh.multiagent.supervisor.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * ORQEN-Harness PART A.3 — Integration Gap Closure Verification Suite.
 *
 * Verifies the exact closed gaps:
 * - Gap A: Tool-start/tool-complete progress propagation & idle stream timeout -> Supervisor recovery.
 * - Gap B: Real [AgentFailureManager] participation in agent reassignment with preserved files & audit trail.
 * - Gap E: Mandatory SKIPPED step is NEVER accepted as task completion.
 * - Gap F & G: Persistent [ExecutionLivenessMonitor] leases + post-restart autonomous reconciliation
 *   via [AutonomousSupervisorEngine.reconcileActiveTasksOnce] without new user messages.
 */
class PartA3IntegrationGapClosureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newProject(store: OrqenTaskStore, id: String): Pair<ProjectEntity, File> {
        val dir = tmp.newFolder("ws-a3-$id")
        val p = ProjectEntity(
            projectId = id,
            projectName = "Project $id",
            workspacePath = dir.absolutePath,
        )
        store.upsertProject(p)
        return p to dir
    }

    @Test
    fun testGapAB_IdleStreamTimeoutTriggersAgentFailureManagerReassignmentAndPreservesPartialWork() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "gap-ab")
        val toolEventsSeen = AtomicInteger(0)

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(
                request: RuntimeExecutionRequest,
                onProgress: suspend (String) -> Unit,
            ): RuntimeExecutionResponse {
                val aid = request.agent.config.slot.id
                if (aid == "agent-02") {
                    // Simulate tool progress followed by idle stream timeout after writing partial file
                    onProgress("TOOL_STARTED:write_file:Auth.kt")
                    File(wsDir, "Auth.kt").writeText("class Auth")
                    onProgress("TOOL_COMPLETED:write_file")
                    toolEventsSeen.incrementAndGet()
                    val idleErr = StructuredErrorClassifier.classify(
                        "Idle stream timeout (90s without output)",
                        isIdleStream = true,
                    )
                    return RuntimeExecutionResponse(
                        requestId = request.requestId,
                        providerId = request.agent.config.providerId,
                        modelId = request.agent.config.modelId,
                        startedAtMs = 0L,
                        completedAtMs = 90_000L,
                        success = false,
                        outputText = idleErr.message,
                        modifiedFiles = listOf("Auth.kt"),
                        error = idleErr,
                    )
                }
                if (aid == "agent-04") {
                    // Replacement agent receives preserved file reference & finishes remaining work
                    assertTrue("Replacement must receive preserved Auth.kt context", request.prompt.contains("Auth.kt"))
                    File(wsDir, "Database.kt").writeText("class Database")
                    return RuntimeExecutionResponse(
                        requestId = request.requestId,
                        providerId = request.agent.config.providerId,
                        modelId = request.agent.config.modelId,
                        startedAtMs = 0L,
                        completedAtMs = 10L,
                        success = true,
                        outputText = "Finished Database.kt while preserving Auth.kt.",
                        modifiedFiles = listOf("Database.kt"),
                    )
                }
                if (aid == "agent-03") {
                    return RuntimeExecutionResponse(
                        requestId = request.requestId,
                        providerId = request.agent.config.providerId,
                        modelId = request.agent.config.modelId,
                        startedAtMs = 0L,
                        completedAtMs = 10L,
                        success = true,
                        outputText = "REVIEW_APPROVED\nTESTS_PASSED: All tests green.",
                    )
                }
                return RuntimeExecutionResponse(request.requestId, "p", "m", 0L, 1L, true, "Plan ready")
            }

            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, true, false)
        }

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            runtime = runtime,
            limits = RecoveryLimitsConfig(
                maxRetriesPerExecution = 0, // Immediately escalate idle stream timeout
                maxModelSwitchesPerStep = 0, // Force direct agent reassignment via AgentFailureManager
                maxAgentReassignments = 2,
            ),
            delayFn = {},
        )
        val lead = engine.registry.register(AgentProfileEntity("agent-01", "Lead", role = "Lead", providerId = "p1", modelId = "m1", capabilities = setOf("lead", "architect")))
        val sunDev = engine.registry.register(AgentProfileEntity("agent-02", "Sun Dev", role = "Dev", providerId = "p1", modelId = "m1", capabilities = setOf("code", "fix"), priority = 10))
        val backupDev = engine.registry.register(AgentProfileEntity("agent-04", "Backup Dev", role = "Dev", providerId = "p2", modelId = "m2", capabilities = setOf("code", "fix"), priority = 8))
        val qa = engine.registry.register(AgentProfileEntity("agent-03", "QA", role = "QA", providerId = "p3", modelId = "m3", capabilities = setOf("review", "qa", "test")))
        val team = engine.teamManager.createTeam(proj.projectId, "Team AB", listOf(lead.agentId, sunDev.agentId, backupDev.agentId, qa.agentId))

        val finalTask = engine.submitAndSupervise(
            projectId = proj.projectId,
            team = team,
            objective = "Build Auth and Database modules",
            requiredDeliverableFiles = listOf("Auth.kt", "Database.kt"),
        )

        assertEquals(PersistentTaskStatus.COMPLETED, finalTask?.status)
        val tid = finalTask!!.taskId
        assertTrue(File(wsDir, "Auth.kt").isFile)
        assertTrue(File(wsDir, "Database.kt").isFile)
        assertTrue("Tool events must be persisted in task event log", store.listTaskEvents(tid).any { it.eventType == "AGENT_TOOL_STARTED" })
        // Verify AgentFailureManager actually ran and persisted CONFLICT_REPORT + AGENT_WORK_REASSIGNED + REASSIGNED delegation!
        assertTrue(store.listAgentMessagesForTask(tid).any { it.messageType == AgentMessageKind.CONFLICT_REPORT && it.fromAgentId == "agent-02" })
        assertTrue(store.listTaskEvents(tid).any { it.eventType == "AGENT_WORK_REASSIGNED" })
        assertTrue(collab.listDelegationsForTask(tid).any { it.status == DelegationStatus.REASSIGNED && it.assignedAgentId == "agent-04" })
    }

    @Test
    fun testGapE_SkippedMandatoryStepCannotBeMarkedCompleted() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, _) = newProject(store, "gap-e")

        val dummyRuntime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit) =
                RuntimeExecutionResponse(request.requestId, "p", "m", 0L, 1L, true, "REVIEW_APPROVED\nTESTS_PASSED")
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, false, false)
        }

        val coord = MultiAgentTeamCoordinator(taskStore = store, collabStore = collab, runtime = dummyRuntime)
        val task = TaskEntity(
            taskId = "T-SKIP",
            projectId = proj.projectId,
            title = "Mandatory check",
            description = "Must not complete with SKIPPED mandatory step",
            completionCriteria = TaskCompletionCriteria(requireReviewerApproval = false, requireTestsPass = false),
        )
        store.upsertTask(task)
        val skippedStep = TaskStepEntity(
            stepId = "S-SKIP",
            taskId = "T-SKIP",
            sequenceNumber = 1,
            title = "Implement login",
            description = "Mandatory login step",
            assignedAgentId = "agent-02",
            status = StepStatus.SKIPPED,
        )
        store.upsertStep(skippedStep)

        val report = coord.verifyTaskCompletion(task, listOf(skippedStep))
        assertFalse("SKIPPED mandatory step must fail completion verification", report.passed)
        assertTrue(report.summary.contains("SKIPPED"))
    }

    @Test
    fun testGapFG_PersistentLeaseExpiryAndPostRestartAutonomousReconciliationWithoutUserPrompt() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "gap-fg")
        val step1Calls = AtomicInteger(0)
        val step2Calls = AtomicInteger(0)

        // Simulate state persisted BEFORE Android process death:
        // Step 1 ALREADY completed (with idempotency record), Step 2 was RUNNING with an expired lease!
        val staleHeartbeat = 1_000L
        val currentNow = 200_000L // Well past lease TTL
        val persistedTask = TaskEntity(
            taskId = "T-RESTART",
            projectId = proj.projectId,
            title = "Resume after crash",
            description = "Build Dashboard.kt",
            status = PersistentTaskStatus.RUNNING,
            currentStepId = "S-2",
            executionLeaseOwner = "agent-02",
            lastHeartbeatAt = staleHeartbeat,
            completionCriteria = TaskCompletionCriteria(requireReviewerApproval = true, requireTestsPass = true),
        )
        store.upsertTask(persistedTask)
        store.upsertStep(
            TaskStepEntity(
                stepId = "S-1", taskId = "T-RESTART", sequenceNumber = 1,
                title = "Inspect", description = "Inspect", assignedAgentId = "agent-01",
                status = StepStatus.COMPLETED, outputSummary = "Inspected", idempotencyKey = "idem-s1",
            ),
        )
        store.recordIdempotency(IdempotencyRecordEntity("idem-s1", "T-RESTART", "S-1", "STEP_EXECUTION", "Inspected"))
        store.upsertStep(
            TaskStepEntity(
                stepId = "S-2", taskId = "T-RESTART", sequenceNumber = 2,
                title = "Implement Dashboard", description = "Write Dashboard.kt", assignedAgentId = "agent-02",
                status = StepStatus.RUNNING, dependencies = listOf("S-1"),
            ),
        )
        store.upsertStep(
            TaskStepEntity(
                stepId = "S-3", taskId = "T-RESTART", sequenceNumber = 3,
                title = "Verify & test", description = "Review and test", assignedAgentId = "agent-03",
                status = StepStatus.PENDING, dependencies = listOf("S-2"), requiresReview = true,
            ),
        )

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                return when (request.agent.config.slot.id) {
                    "agent-01" -> {
                        step1Calls.incrementAndGet()
                        RuntimeExecutionResponse(request.requestId, "p", "m", 0L, 1L, true, "Inspected")
                    }
                    "agent-02" -> {
                        step2Calls.incrementAndGet()
                        File(wsDir, "Dashboard.kt").writeText("class Dashboard")
                        RuntimeExecutionResponse(request.requestId, "p", "m", 0L, 1L, true, "Dashboard.kt created", modifiedFiles = listOf("Dashboard.kt"))
                    }
                    else -> RuntimeExecutionResponse(request.requestId, "p", "m", 0L, 1L, true, "REVIEW_APPROVED\nTESTS_PASSED")
                }
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, true, false)
        }

        // Fresh AutonomousSupervisorEngine + ExecutionLivenessMonitor(store) after simulated process recreation!
        val liveness = ExecutionLivenessMonitor(store)
        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            runtime = runtime,
            livenessMonitor = liveness,
            timeoutPolicy = GranularTimeoutPolicy(agentExecutionTimeoutMs = 60_000L),
            delayFn = {},
            nowProvider = { currentNow },
        )
        val a1 = engine.registry.register(AgentProfileEntity("agent-01", "Lead", role = "Lead", providerId = "p1", modelId = "m1", capabilities = setOf("lead")))
        val a2 = engine.registry.register(AgentProfileEntity("agent-02", "Dev", role = "Dev", providerId = "p1", modelId = "m1", capabilities = setOf("code")))
        val a3 = engine.registry.register(AgentProfileEntity("agent-03", "QA", role = "QA", providerId = "p2", modelId = "m2", capabilities = setOf("review", "qa", "test")))
        val team = engine.teamManager.createTeam(proj.projectId, "Restart Team", listOf(a1.agentId, a2.agentId, a3.agentId))

        // Bounded periodic / post-restart reconciliation resumes T-RESTART WITHOUT any new user message!
        val resumed = engine.reconcileActiveTasksOnce(
            team = team,
            requiredDeliverableFilesByTask = mapOf("T-RESTART" to listOf("Dashboard.kt")),
        )

        assertEquals(listOf("T-RESTART"), resumed)
        assertEquals(0, step1Calls.get()) // Already-completed Step 1 was NEVER re-executed!
        assertEquals(1, step2Calls.get()) // Abandoned Step 2 was resumed & completed once!
        assertEquals(PersistentTaskStatus.COMPLETED, store.getTask("T-RESTART")?.status)
        assertTrue(File(wsDir, "Dashboard.kt").isFile)
        assertTrue(engine.recoveryEngine.auditTrail("T-RESTART").any { it.decisionType == SupervisorDecisionType.TASK_RESUMED })
    }
}
