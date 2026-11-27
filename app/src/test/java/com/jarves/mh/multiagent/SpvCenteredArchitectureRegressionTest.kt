package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.chat.GroupMessageType
import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.model.*
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.supervisor.*
import com.jarves.mh.runtime.TestExecutionEvidence
import com.jarves.mh.runtime.TestExecutionOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 11 — Regression Tests Reproducing the Reported SPV / Developer / BLOCKED Failures
 * (including Task T-72FABA38 style "No steps planned / missing review / missing TESTS_PASSED").
 */
class SpvCenteredArchitectureRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun teamAgents(spvBusy: Boolean = false): List<AgentState> = listOf(
        AgentState(
            config = AgentConfig(
                slot = AgentSlot.AGENT_1,
                name = "SPV Leader",
                role = AgentRole(AgentRoleType.TEAM_LEADER, systemInstructions = "", responsibilities = emptyList()),
            ),
            isWorking = spvBusy,
        ),
        AgentState(
            config = AgentConfig(
                slot = AgentSlot.AGENT_2,
                name = "Developer",
                role = AgentRole(AgentRoleType.SOFTWARE_ENGINEER, systemInstructions = "", responsibilities = emptyList()),
            ),
            isWorking = false,
        ),
        AgentState(
            config = AgentConfig(
                slot = AgentSlot.AGENT_3,
                name = "QA Reviewer",
                role = AgentRole(AgentRoleType.TESTER, systemInstructions = "", responsibilities = emptyList()),
            ),
            isWorking = false,
        ),
    )

    // ==================== SCENARIO A: USER ASKS A QUESTION ====================

    @Test
    fun `Scenario A - user question routes solely to SPV without creating a task or invoking Developer`() {
        val agents = teamAgents(spvBusy = true) // Even when SPV is busy, Developer must NEVER hijack Owner chat!
        val route = SpvConversationRouter.routeOwnerMessage(
            text = "Why can't AI agents work independently?",
            messageType = GroupMessageType.NORMAL,
            enabledAgents = agents,
        )
        assertNotNull(route)
        assertEquals(OrqenMessageKind.USER_QUESTION, route!!.messageKind)
        assertEquals(SpvInteractionMode.CHAT_CONSULTATION, route.interactionMode)
        assertEquals(AgentSlot.AGENT_1, route.ownerFacingAgent.config.slot)
        assertFalse("Questions must NEVER create implementation tasks", route.shouldCreateTask)
    }

    // ==================== SCENARIO B: MULTI-PART IMPLEMENTATION & STRICT ASSIGNMENT ENVELOPE ====================

    @Test
    fun `Scenario B - Developer receives strict TASK_ASSIGNMENT envelope per Part and advances sequentially`() = runBlocking {
        val ws = tempFolder.newFolder("calc-bee")
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val project = ProjectEntity("proj-bee", "Calculator Bee", ws.absolutePath)
        taskStore.upsertProject(project)

        val capturedPromptsByAgent = mutableListOf<Pair<String, String>>()

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(
                request: RuntimeExecutionRequest,
                onProgress: suspend (String) -> Unit,
            ): RuntimeExecutionResponse {
                val now = System.currentTimeMillis()
                val agentId = request.agent.config.slot.id
                capturedPromptsByAgent += agentId to request.prompt
                return if (agentId == "agent-03") {
                    RuntimeExecutionResponse(
                        requestId = request.requestId,
                        providerId = "p1",
                        modelId = "m-qa",
                        startedAtMs = now,
                        completedAtMs = now + 5,
                        success = true,
                        outputText = "Verified part deliverables and tests. REVIEW_APPROVED TESTS_PASSED",
                        testEvidence = listOf(
                            TestExecutionEvidence("./gradlew testDebugUnitTest", now, now + 5, 0, "ok", "", TestExecutionOutcome.TESTS_PASSED, "passed"),
                        ),
                    )
                } else {
                    File(ws, "app/build/outputs/apk/debug/app-debug.apk").apply {
                        parentFile?.mkdirs()
                        writeBytes(ByteArray(1024) { 0x50 })
                    }
                    RuntimeExecutionResponse(
                        requestId = request.requestId,
                        providerId = "p1",
                        modelId = "m-dev",
                        startedAtMs = now,
                        completedAtMs = now + 5,
                        success = true,
                        outputText = "Completed assigned step scope cleanly.",
                        modifiedFiles = listOf("app/build/outputs/apk/debug/app-debug.apk"),
                    )
                }
            }
            override fun cancel(requestId: String): Boolean = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, true, true, true)
        }

        val engine = AutonomousSupervisorEngine(taskStore = taskStore, collabStore = collabStore, runtime = runtime, delayFn = {})
        val spv = engine.registry.register(AgentProfileEntity("agent-01", "SPV", "Lead", "SPV", "p1", "m1", capabilities = setOf("lead", "architect", "plan")))
        val dev = engine.registry.register(AgentProfileEntity("agent-02", "Developer", "Dev", "Dev", "p1", "m2", capabilities = setOf("code", "fix")))
        val qa = engine.registry.register(AgentProfileEntity("agent-03", "QA Reviewer", "QA", "QA", "p1", "m3", capabilities = setOf("review", "qa", "test")))
        val team = engine.teamManager.createTeam(project.projectId, "Bee Team", listOf(spv.agentId, dev.agentId, qa.agentId), requireIndependentReview = true, maxConcurrentAgents = 1)

        val result = engine.submitAndSupervise(
            projectId = project.projectId,
            team = team,
            objective = "Build Calculator Bee Android application, test it, and generate a debug APK.",
            completionCriteria = TaskCompletionCriteria(requireBuildPass = true, requireTestsPass = true, requireReviewerApproval = true),
        )

        assertNotNull(result)
        assertEquals(PersistentTaskStatus.COMPLETED, result!!.status)
        // Verify Developer received strict TASK_ASSIGNMENT envelope separating immutable goal from current scope
        val devPrompts = capturedPromptsByAgent.filter { it.first == "agent-02" }.map { it.second }
        assertTrue(devPrompts.isNotEmpty())
        assertTrue(devPrompts.all { it.contains("TASK_ASSIGNMENT") && it.contains("END_TASK_ASSIGNMENT") })
        assertTrue(devPrompts.all { it.contains("IMMUTABLE BACKGROUND USER GOAL") })
        assertTrue(devPrompts.all { it.contains("STRICT INTERNAL WORKER RULES") })
        // Verify real Multi-Part plan files exist on disk
        assertTrue(File(ws, MultiPartPlanManager.OVERVIEW_REL_PATH).isFile)
        val mp = MultiPartPlanManager.loadPlan(ws)
        assertNotNull(mp)
        assertTrue(mp!!.parts.size >= 2)
    }

    // ==================== SCENARIO C: STALE / MISMATCHED ASSIGNMENT REJECTION & SPV-ONLY FORMATTING ====================

    @Test
    fun `Scenario C - assignment ledger rejects duplicate or mismatched worker results and SPV owns Owner status`() {
        val ledger = AssignmentResultLedger()
        val env = TaskAssignmentEnvelope(
            taskId = "T-72FABA38",
            planId = "plan-1",
            planVersion = 1,
            partId = "part-1",
            stepId = "step-1",
            assignmentId = "asgn-001",
            assignedAgentId = "agent-02",
            specificationPath = ".orqen/plan/part_1_core.md",
            specificationContent = "# Part 1",
            immutableUserGoal = "Build Calculator Bee",
            currentScope = "Implement parser",
        )
        assertTrue(ledger.registerAssignment(env))
        assertFalse("Duplicate assignment registration rejected", ledger.registerAssignment(env))

        val mismatch = ledger.validateAndComplete("asgn-001", "part-2", "step-1")
        assertFalse(mismatch.accepted)
        assertTrue(mismatch.reason.contains("Mismatched"))

        val valid = ledger.validateAndComplete("asgn-001", "part-1", "step-1")
        assertTrue(valid.accepted)

        val duplicate = ledger.validateAndComplete("asgn-001", "part-1", "step-1")
        assertFalse(duplicate.accepted)
        assertTrue(duplicate.reason.contains("Duplicate"))

        val ownerStatus = SpvConversationRouter.formatSpvStatusReport(
            phase = "COMPLETED",
            workerName = "Developer",
            stepNumber = 1,
            totalSteps = 4,
            stepTitle = "Part 1 Implement",
            modifiedFiles = listOf("Calc.kt"),
        )
        assertTrue(ownerStatus.startsWith("[SPV]"))
        assertTrue(ownerStatus.contains("Developer"))
    }

    // ==================== SCENARIO D & E: EMPTY-PLAN SELF-RECOVERY (T-72FABA38) & RESTART ====================

    @Test
    fun `Scenario D and E - task with missing steps self-recovers from persistent plan instead of failing with No steps planned`() = runBlocking {
        val ws = tempFolder.newFolder("recover-t72")
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val project = ProjectEntity("proj-t72", "Recovery Project", ws.absolutePath)
        taskStore.upsertProject(project)

        // Reproduce the exact T-72FABA38 failure state: a persistent task exists in SQLite with 0 steps!
        val orphanTask = TaskEntity(
            taskId = "T-72FABA38",
            projectId = project.projectId,
            title = "Build Calculator Bee",
            description = "Build Calculator Bee with scientific functions and unit tests",
            status = PersistentTaskStatus.RECOVERING,
            completionCriteria = TaskCompletionCriteria(requireReviewerApproval = true, requireTestsPass = true, requireBuildPass = false),
        )
        taskStore.upsertTask(orphanTask)
        assertTrue(taskStore.listStepsForTask("T-72FABA38").isEmpty())

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(
                request: RuntimeExecutionRequest,
                onProgress: suspend (String) -> Unit,
            ): RuntimeExecutionResponse {
                val now = System.currentTimeMillis()
                val isReview = request.agent.config.slot == AgentSlot.AGENT_3
                return RuntimeExecutionResponse(
                    requestId = request.requestId,
                    providerId = "p1",
                    modelId = "m1",
                    startedAtMs = now,
                    completedAtMs = now + 5,
                    success = true,
                    outputText = if (isReview) "Verified. REVIEW_APPROVED TESTS_PASSED" else "Implemented assigned Part cleanly.",
                    testEvidence = if (isReview) listOf(TestExecutionEvidence("./gradlew test", now, now + 5, 0, "ok", "", TestExecutionOutcome.TESTS_PASSED, "ok")) else emptyList(),
                )
            }
            override fun cancel(requestId: String): Boolean = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, true, true, true)
        }

        val engine = AutonomousSupervisorEngine(taskStore = taskStore, collabStore = collabStore, runtime = runtime, delayFn = {})
        val spv = engine.registry.register(AgentProfileEntity("agent-01", "SPV", "Lead", "SPV", "p1", "m1", capabilities = setOf("lead", "architect")))
        val dev = engine.registry.register(AgentProfileEntity("agent-02", "Developer", "Dev", "Dev", "p1", "m1", capabilities = setOf("code")))
        val qa = engine.registry.register(AgentProfileEntity("agent-03", "QA Reviewer", "QA", "QA", "p1", "m1", capabilities = setOf("review", "qa", "test")))
        val team = engine.teamManager.createTeam(project.projectId, "Recovery Team", listOf(spv.agentId, dev.agentId, qa.agentId), requireIndependentReview = true, maxConcurrentAgents = 1)

        val recovered = engine.superviseTask("T-72FABA38", team)
        assertNotNull(recovered)
        assertEquals(PersistentTaskStatus.COMPLETED, recovered!!.status)
        assertTrue("Self-recovery must populate persistent steps in SQLite", taskStore.listStepsForTask("T-72FABA38").isNotEmpty())
        assertTrue(File(ws, MultiPartPlanManager.OVERVIEW_REL_PATH).isFile)
    }
}
