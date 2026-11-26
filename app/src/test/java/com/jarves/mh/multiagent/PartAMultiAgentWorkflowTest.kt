package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.model.AgentConfig
import com.jarves.mh.multiagent.model.AgentRole
import com.jarves.mh.multiagent.model.AgentRoleType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.persistence.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * ORQEN-Harness PART A — Automated Verification Suite for Core Multi-Agent Workflow.
 *
 * Uses boundary test doubles for [AIExecutionRuntime] ONLY (no live API credentials required),
 * while exercising the REAL [MultiAgentTeamCoordinator], [PersistentTaskOrchestrator],
 * [RuntimeStepExecutionAdapter], [AgentRegistry], [AgentTeamManager], [AgentCapabilityResolver],
 * [AgentDelegationManager], [AgentCommunicationManager], [AgentReviewManager],
 * [TaskContextBuilder], [ResultVerifier], and [InMemoryOrqenTaskStore].
 */
class PartAMultiAgentWorkflowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun createProject(store: OrqenTaskStore, id: String = "proj-a"): ProjectEntity {
        val dir = tmp.newFolder("ws-$id")
        val p = ProjectEntity(
            projectId = id,
            projectName = "Project $id",
            workspacePath = dir.absolutePath,
            projectMemory = "Kotlin Android project with strict review & test gates.",
        )
        store.upsertProject(p)
        return p
    }

    private fun registerStandardTeam(coordinator: MultiAgentTeamCoordinator, projectId: String): AgentTeamEntity {
        val leader = coordinator.registry.register(
            AgentProfileEntity(
                agentId = "agent-01",
                displayName = "Coordinator Lead",
                role = "Coordinator / Architect",
                providerId = "provider-openai",
                modelId = "gpt-5",
                capabilities = setOf("lead", "architect", "plan"),
                priority = 10,
            ),
        )
        val dev = coordinator.registry.register(
            AgentProfileEntity(
                agentId = "agent-02",
                displayName = "Kotlin Developer",
                role = "Software Engineer",
                providerId = "provider-anthropic",
                modelId = "claude-sonnet",
                capabilities = setOf("code", "fix"),
                priority = 8,
            ),
        )
        val reviewer = coordinator.registry.register(
            AgentProfileEntity(
                agentId = "agent-03",
                displayName = "Independent QA Reviewer",
                role = "Reviewer / Tester",
                providerId = "provider-gemini",
                modelId = "gemini-2.5-pro",
                capabilities = setOf("review", "qa", "test"),
                priority = 9,
            ),
        )
        return coordinator.teamManager.createTeam(
            projectId = projectId,
            teamName = "Core Delivery Team",
            memberAgentIds = listOf(leader.agentId, dev.agentId, reviewer.agentId),
            maxDelegationDepth = 2,
            requireIndependentReview = true,
        )
    }

    @Test
    fun test01_FullCoordinatedWorkflowWithReviewRejectionCorrectionAndFinalVerification() = runBlocking {
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val project = createProject(taskStore, "proj-e2e")
        val chatMirror = CopyOnWriteArrayList<AgentMessageEntity>()
        val runtimeInvocations = CopyOnWriteArrayList<Pair<String, String>>()
        val reviewAttempt = AtomicInteger(0)

        val boundaryRuntime = object : AIExecutionRuntime {
            override suspend fun execute(
                request: RuntimeExecutionRequest,
                onProgress: suspend (String) -> Unit,
            ): RuntimeExecutionResponse {
                val agentSlotId = request.agent.config.slot.id
                runtimeInvocations.add(agentSlotId to request.providerConfig?.id.orEmpty())
                val output = when (agentSlotId) {
                    "agent-01" -> "Architecture mapped.\n[MSG to=agent-02] Focus on edge cases in AuthService."
                    "agent-02" -> if (request.prompt.contains("Fix review feedback")) {
                        "Fixed null-safety bug per reviewer feedback.\n[MSG to=agent-03] Ready for re-review."
                    } else {
                        "Implemented AuthService initial version.\n[MSG to=*] AuthService draft ready."
                    }
                    "agent-03" -> {
                        if (reviewAttempt.incrementAndGet() == 1) {
                            "REVIEW_REJECTED: Missing null-safety guard on token input."
                        } else {
                            "REVIEW_APPROVED\nTESTS_PASSED: All 12 AuthService tests passed."
                        }
                    }
                    else -> "Done"
                }
                return RuntimeExecutionResponse(
                    requestId = request.requestId,
                    providerId = request.agent.config.providerId,
                    modelId = request.agent.config.modelId,
                    startedAtMs = System.currentTimeMillis(),
                    completedAtMs = System.currentTimeMillis(),
                    success = true,
                    outputText = output,
                    usage = TokenUsageReport(inputTokens = 100, outputTokens = 60, costUsd = 0.01),
                )
            }

            override fun cancel(requestId: String): Boolean = true
            override fun getProviderCapabilities(providerId: String) =
                RuntimeCapabilities(true, false, true, false)
        }

        val coordinator = MultiAgentTeamCoordinator(
            taskStore = taskStore,
            collabStore = collabStore,
            runtime = boundaryRuntime,
            chatProjectionSink = { msg, _, _ -> chatMirror.add(msg) },
            delayFn = {},
        )
        val team = registerStandardTeam(coordinator, project.projectId)

        // 1-3. Create task, plan, and delegate by capability
        val sub = coordinator.createPlanAndDelegate(
            projectId = project.projectId,
            team = team,
            objective = "Implement AuthService with null-safety and unit tests",
            acceptanceCriteria = listOf("Null token rejected", "Unit tests pass"),
        )
        assertTrue("Submission should succeed: ${sub.failureReason}", sub.accepted)
        val task = requireNotNull(sub.task)
        assertEquals(3, sub.plannedSteps.size)
        assertEquals(2, sub.delegations.size)

        // 4-7. Execute full loop: Lead -> Dev -> Reviewer (REJECTS) -> Dev (FIXES) -> Reviewer (APPROVES + TESTS_PASSED) -> Final Verification
        val finalTask = coordinator.executeTask(task.taskId)
        assertNotNull(finalTask)
        assertEquals(PersistentTaskStatus.COMPLETED, finalTask?.status)

        // Verify real adapter was invoked 5 times (Lead, Dev, Reviewer#1 reject, Dev fix, Reviewer#2 approve)
        assertEquals(5, runtimeInvocations.size)

        // Verify independent reviews were recorded (first CHANGES_REQUESTED, second APPROVED)
        val reviews = collabStore.listReviewsForTask(task.taskId)
        assertEquals(2, reviews.size)
        assertEquals(ReviewOutcome.CHANGES_REQUESTED, reviews[0].outcome)
        assertEquals(ReviewOutcome.APPROVED, reviews[1].outcome)

        // Verify inter-agent messages persisted (delegations + direct + broadcast + review feedback)
        val messages = taskStore.listAgentMessagesForTask(task.taskId)
        assertTrue(messages.any { it.fromAgentId == "agent-01" && it.toAgentId == "agent-02" && it.content.contains("edge cases") })
        assertTrue(messages.any { it.fromAgentId == "agent-02" && it.toAgentId == AgentCommunicationManager.BROADCAST_TARGET })
        assertTrue(messages.any { it.messageType == AgentMessageKind.CHANGE_REQUEST })
        assertTrue(messages.any { it.messageType == AgentMessageKind.REVIEW_APPROVAL })
        assertTrue("Chat projection sink should receive mirrored kickoff message", chatMirror.isNotEmpty())
    }

    @Test
    fun test02_CapabilitySelectionAndUnavailableAgentReporting() {
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val project = createProject(taskStore, "proj-cap")
        var downProviders = setOf("provider-gemini")

        val dummyRuntime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit) =
                RuntimeExecutionResponse(request.requestId, "p", "m", 0L, 1L, true, "ok")
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, false, false)
        }

        val coordinator = MultiAgentTeamCoordinator(
            taskStore = taskStore,
            collabStore = collabStore,
            runtime = dummyRuntime,
            unavailableProviders = { downProviders },
        )
        val team = registerStandardTeam(coordinator, project.projectId)

        // Reviewer's provider (provider-gemini) is unavailable -> must report actual failure reason, not pretend assigned!
        val failedSub = coordinator.createPlanAndDelegate(
            projectId = project.projectId,
            team = team,
            objective = "Build feature X",
        )
        assertFalse(failedSub.accepted)
        assertTrue(failedSub.failureReason.orEmpty().contains("review"))

        // Bring provider back online -> succeeds
        downProviders = emptySet()
        val okSub = coordinator.createPlanAndDelegate(
            projectId = project.projectId,
            team = team,
            objective = "Build feature X",
        )
        assertTrue(okSub.accepted)
    }

    @Test
    fun test03_ContextIsolationBetweenTasksAndProjects() {
        val taskStore = InMemoryOrqenTaskStore()
        val projA = createProject(taskStore, "proj-iso-a")
        val projB = createProject(taskStore, "proj-iso-b")
        val comm = AgentCommunicationManager(taskStore)

        val taskA = TaskEntity(taskId = "T-A", projectId = projA.projectId, title = "Task A", description = "Secret A")
        val taskB = TaskEntity(taskId = "T-B", projectId = projB.projectId, title = "Task B", description = "Secret B")
        taskStore.upsertTask(taskA)
        taskStore.upsertTask(taskB)

        // Direct message to agent-02 in Task A, private message to agent-03 in Task A, and message in Task B
        comm.sendDirect("T-A", "agent-01", "agent-02", AgentMessageKind.AGENT_TO_AGENT, "For Dev only")
        comm.sendDirect("T-A", "agent-01", "agent-03", AgentMessageKind.AGENT_TO_AGENT, "Private for Reviewer only")
        comm.broadcast("T-A", "agent-01", AgentMessageKind.SHARED_DECISION, "Task A broadcast")
        comm.broadcast("T-B", "agent-01", AgentMessageKind.SHARED_DECISION, "Project B confidential")

        val stepForDev = TaskStepEntity(
            stepId = "s1",
            taskId = "T-A",
            sequenceNumber = 1,
            title = "Dev step",
            description = "Do work",
            assignedAgentId = "agent-02",
        )
        val ctx = TaskContextBuilder.buildAgentStepContext(
            project = projA,
            task = taskA,
            step = stepForDev,
            allSteps = listOf(stepForDev),
            latestCheckpoint = null,
            communicationManager = comm,
        )
        assertTrue(ctx.contains("For Dev only"))
        assertTrue(ctx.contains("Task A broadcast"))
        assertFalse("Private message to another agent must not leak", ctx.contains("Private for Reviewer only"))
        assertFalse("Other project/task message must not leak", ctx.contains("Project B confidential"))

        // Cross-project read must throw SecurityException
        try {
            comm.getIsolatedContextMessages(projectId = projA.projectId, taskId = "T-B", agentId = "agent-02")
            fail("Expected SecurityException on cross-project access")
        } catch (_: SecurityException) {
            // expected
        }
    }

    @Test
    fun test04_DelegationDepthAndReviewRetryLimitsEnforced() = runBlocking {
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val project = createProject(taskStore, "proj-limits")

        // Always-rejecting reviewer to test maxReviewRejectCycles limit
        val rejectingRuntime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit) =
                RuntimeExecutionResponse(
                    requestId = request.requestId,
                    providerId = request.agent.config.providerId,
                    modelId = request.agent.config.modelId,
                    startedAtMs = 0L,
                    completedAtMs = 1L,
                    success = true,
                    outputText = if (request.agent.config.slot.id == "agent-03") "REVIEW_REJECTED: Still broken" else "Implemented",
                )
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, false, false)
        }

        val coordinator = MultiAgentTeamCoordinator(
            taskStore = taskStore,
            collabStore = collabStore,
            runtime = rejectingRuntime,
            maxReviewRejectCycles = 2,
            delayFn = {},
        )
        val team = registerStandardTeam(coordinator, project.projectId)

        // Enforce delegation depth limit
        try {
            coordinator.delegationManager.delegate(
                team = team,
                candidates = coordinator.teamManager.getTeamProfiles(team.teamId),
                taskId = "T-DEPTH",
                parentStepId = "S-1",
                delegatingAgentId = "agent-01",
                objective = "Too deep",
                requiredCapabilities = setOf("code"),
                currentDepth = team.maxDelegationDepth + 1,
            )
            fail("Expected IllegalArgumentException for exceeding maxDelegationDepth")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        // Enforce maxReviewRejectCycles -> task becomes BLOCKED instead of looping forever or auto-approving!
        val sub = coordinator.createPlanAndDelegate(project.projectId, team, "Fix infinite loop")
        val result = coordinator.executeTask(requireNotNull(sub.task).taskId)
        assertEquals(PersistentTaskStatus.BLOCKED, result?.status)
        assertTrue(result?.lastError.orEmpty().contains("Quality gate rejected"))
    }

    @Test
    fun test05_CompletionVerificationBlocksTaskWithoutTestsOrReview() = runBlocking {
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val project = createProject(taskStore, "proj-verify")

        // Reviewer approves text, but omits TESTS_PASSED while requireTestsPass = true
        val noTestRuntime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit) =
                RuntimeExecutionResponse(
                    requestId = request.requestId,
                    providerId = request.agent.config.providerId,
                    modelId = request.agent.config.modelId,
                    startedAtMs = 0L,
                    completedAtMs = 1L,
                    success = true,
                    outputText = if (request.agent.config.slot.id == "agent-03") "REVIEW_APPROVED (no tests run)" else "Code done",
                )
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, false, false)
        }

        val coordinator = MultiAgentTeamCoordinator(
            taskStore = taskStore,
            collabStore = collabStore,
            runtime = noTestRuntime,
            delayFn = {},
        )
        val team = registerStandardTeam(coordinator, project.projectId)
        val sub = coordinator.createPlanAndDelegate(project.projectId, team, "Feature requiring tests")
        val result = coordinator.executeTask(requireNotNull(sub.task).taskId)

        assertEquals(PersistentTaskStatus.BLOCKED, result?.status)
        assertTrue(result?.lastError.orEmpty().contains("TESTS_PASSED"))
    }

    @Test
    fun test06_ProviderFailureTimeoutPauseResumeCancelAndDuplicateExecutionPrevention() = runBlocking {
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val project = createProject(taskStore, "proj-safety")
        val calls = AtomicInteger(0)

        // 1) Timeout handling
        val slowRuntime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                calls.incrementAndGet()
                delay(500L)
                return RuntimeExecutionResponse(request.requestId, "p", "m", 0L, 1L, true, "too late")
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, false, false)
        }
        val timeoutCoord = MultiAgentTeamCoordinator(
            taskStore = taskStore,
            collabStore = collabStore,
            runtime = slowRuntime,
            policy = RequestExecutionPolicy(maxRetriesPerStep = 1),
            stepTimeoutMs = 40L,
            delayFn = {},
        )
        val team = registerStandardTeam(timeoutCoord, project.projectId)
        val timeoutSub = timeoutCoord.createPlanAndDelegate(project.projectId, team, "Timeout task")
        val timeoutRes = timeoutCoord.executeTask(requireNotNull(timeoutSub.task).taskId)
        assertEquals(PersistentTaskStatus.BLOCKED, timeoutRes?.status)
        assertTrue(timeoutRes?.lastError.orEmpty().contains("exceeded max retries"))

        // 2) Duplicate active execution prevention + Pause / Resume / Cancel
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val execCount = AtomicInteger(0)
        val controlledRuntime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                execCount.incrementAndGet()
                started.complete(Unit)
                gate.await()
                val text = if (request.agent.config.slot.id == "agent-03") "REVIEW_APPROVED\nTESTS_PASSED" else "Done"
                return RuntimeExecutionResponse(request.requestId, "p", "m", 0L, 1L, true, text)
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, false, false)
        }
        val safeCoord = MultiAgentTeamCoordinator(
            taskStore = taskStore,
            collabStore = collabStore,
            runtime = controlledRuntime,
            delayFn = {},
        )
        val sub2 = safeCoord.createPlanAndDelegate(project.projectId, team, "Duplicate guard task")
        val tid2 = requireNotNull(sub2.task).taskId

        val firstRun = async(Dispatchers.Default) { safeCoord.executeTask(tid2) }
        started.await()
        // Concurrent second run on same taskId must be rejected immediately!
        safeCoord.executeTask(tid2)
        assertTrue(taskStore.listTaskEvents(tid2).any { it.eventType == "DUPLICATE_EXECUTION_PREVENTED" })

        // Pause -> Resume -> Cancel
        safeCoord.pauseTask(tid2, "User pause")
        gate.complete(Unit)
        firstRun.await()
        assertEquals(PersistentTaskStatus.PAUSED, taskStore.getTask(tid2)?.status)

        safeCoord.stopTask(tid2, "User stop")
        assertEquals(PersistentTaskStatus.CANCELLED, taskStore.getTask(tid2)?.status)
        assertNull("Cancelled task must never auto-resume", safeCoord.resumeTask(this, tid2))
    }

    @Test
    fun test07_BackwardCompatibleAgentConfigMapping() {
        val cfg = AgentConfig(
            slot = AgentSlot.AGENT_2,
            name = "Legacy Dev",
            role = AgentRole(
                type = AgentRoleType.SOFTWARE_ENGINEER,
                systemInstructions = "Write clean Kotlin",
                responsibilities = listOf("Implement features"),
            ),
            providerId = "preset-antigravity",
            modelId = "gemini-3.8-flash-high",
        )
        val entity = AgentProfileMapper.fromAgentConfig(cfg)
        val dynamic = AgentProfileMapper.toDynamicProfile(entity)
        assertEquals("agent-02", entity.agentId)
        assertEquals(AgentSlot.AGENT_2, dynamic.legacySlot)
        assertTrue("code" in entity.capabilities)
    }
}
