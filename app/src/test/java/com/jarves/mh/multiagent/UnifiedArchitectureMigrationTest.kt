package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.chat.GroupMessageType
import com.jarves.mh.multiagent.collab.AgentProfileEntity
import com.jarves.mh.multiagent.collab.AgentTeamEntity
import com.jarves.mh.multiagent.collab.AgentTeamMemberEntity
import com.jarves.mh.multiagent.collab.InMemoryCollaborationStore
import com.jarves.mh.multiagent.collab.SpvConversationRouter
import com.jarves.mh.multiagent.collab.SpvInteractionMode
import com.jarves.mh.multiagent.collab.SpvModePolicy
import com.jarves.mh.multiagent.engine.AIExecutionRuntime
import com.jarves.mh.multiagent.engine.CheckpointManager
import com.jarves.mh.multiagent.engine.DynamicTeamPlanCompiler
import com.jarves.mh.multiagent.engine.MultiPartPlanManager
import com.jarves.mh.multiagent.engine.RuntimeCapabilities
import com.jarves.mh.multiagent.engine.RuntimeExecutionRequest
import com.jarves.mh.multiagent.engine.RuntimeExecutionResponse
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.model.AgentConfig
import com.jarves.mh.multiagent.model.AgentRole as UiAgentRole
import com.jarves.mh.multiagent.model.AgentRoleType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.multiagent.persistence.InMemoryOrqenTaskStore
import com.jarves.mh.multiagent.persistence.PersistentTaskStatus
import com.jarves.mh.multiagent.persistence.ProjectEntity
import com.jarves.mh.multiagent.persistence.TaskCompletionCriteria
import com.jarves.mh.multiagent.recovery.ExecutionLivenessMonitor
import com.jarves.mh.multiagent.supervisor.AutonomousSupervisorEngine
import com.jarves.mh.runtime.TestExecutionEvidence
import com.jarves.mh.runtime.TestExecutionOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 10 Verification Suite:
 * Proves the legacy StructuredTaskPlanner, fixed 3-step template, and automatic `.orqen/plan.md`
 * generation are completely removed and replaced by the Unified Autonomous AI Team Architecture.
 */
class UnifiedArchitectureMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `dynamic plan scales from small to large tasks without fixed 3-step legacy labels or plan_md`() {
        val wsSmall = tempFolder.newFolder("ws-small")
        val wsLarge = tempFolder.newFolder("ws-large")
        val projSmall = ProjectEntity("p-s", "SmallProj", wsSmall.absolutePath)
        val projLarge = ProjectEntity("p-l", "LargeProj", wsLarge.absolutePath)
        val compiler = DynamicTeamPlanCompiler()

        // Small task -> 1 Part
        val smallMp = MultiPartPlanManager.createPlan("p-s", "T-s", "SmallProj", "Fix typo in README", wsSmall)
        assertEquals(1, smallMp.parts.size)
        assertTrue(MultiPartPlanManager.persistPlan(wsSmall, smallMp).valid)
        val smallPlan = compiler.planFromMultiPart(projSmall, smallMp, wsSmall)
        assertEquals(2, smallPlan.steps.size) // 1 impl + 1 review/QA

        // Large task -> multiple Parts
        val largeMp = MultiPartPlanManager.createPlan(
            "p-l", "T-l", "LargeProj",
            "Build full Android e-commerce application with authentication, database, cart, checkout UI, and unit tests",
            wsLarge,
            isAndroidProject = true,
        )
        assertTrue("Large task should dynamically produce >1 Part", largeMp.parts.size > 1)
        assertTrue(MultiPartPlanManager.persistPlan(wsLarge, largeMp).valid)
        val largePlan = compiler.planFromMultiPart(projLarge, largeMp, wsLarge)

        // Zero legacy fixed-step labels or IDs
        val allSteps = smallPlan.steps + largePlan.steps
        assertTrue(allSteps.none { it.stepId.startsWith("step-plan-") })
        assertTrue(allSteps.none { it.title.contains("SPV วิเคราะห์ความต้องการและวางแผน") })
        assertTrue(allSteps.none { it.title.contains("Implement (พัฒนาโค้ดตามแผน)") })
        assertTrue(allSteps.none { it.title.contains("Verify & test deliverables") })
        assertTrue(allSteps.none { it.description.contains(".orqen/plan.md") })

        // No automatic `.orqen/plan.md` created in either workspace
        assertFalse(File(wsSmall, ".orqen/plan.md").exists())
        assertFalse(File(wsLarge, ".orqen/plan.md").exists())
        assertTrue(File(wsSmall, ".orqen/plan/overview.md").isFile)
        assertTrue(File(wsLarge, ".orqen/plan/overview.md").isFile)
    }

    @Test
    fun `only one authoritative active plan exists per objective and unified reports are generated`() = runBlocking {
        val ws = tempFolder.newFolder("ws-unified")
        File(ws, "App.kt").writeText("fun greet() = \"hi\"\n")

        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val proj = ProjectEntity("p-u", "UnifiedApp", ws.absolutePath)
        store.upsertProject(proj)

        listOf(
            AgentProfileEntity("agent-01", "SPV", role = "SUPERVISOR", providerId = "p1", modelId = "m1", capabilities = setOf("lead", "architect")),
            AgentProfileEntity("agent-02", "Developer", role = "DEVELOPER", providerId = "p1", modelId = "m1", capabilities = setOf("code")),
            AgentProfileEntity("agent-03", "QA Reviewer", role = "REVIEWER", providerId = "p1", modelId = "m1", capabilities = setOf("review", "qa", "test")),
        ).forEach { collab.upsertProfile(it) }

        val team = AgentTeamEntity("team-u", "p-u", "Unified Team")
        collab.upsertTeam(team)
        collab.upsertTeamMember(AgentTeamMemberEntity("team-u", "agent-01", "SUPERVISOR"))
        collab.upsertTeamMember(AgentTeamMemberEntity("team-u", "agent-02", "DEVELOPER"))
        collab.upsertTeamMember(AgentTeamMemberEntity("team-u", "agent-03", "REVIEWER"))

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                val now = System.currentTimeMillis()
                val aid = request.agentId ?: request.agent.config.slot.id
                return if (aid == "agent-03") {
                    RuntimeExecutionResponse(
                        requestId = request.requestId, providerId = "p1", modelId = "m1",
                        startedAtMs = now, completedAtMs = now + 5, success = true,
                        outputText = "REVIEW_APPROVED — verified real changes and passing tests.",
                        testEvidence = listOf(
                            TestExecutionEvidence("./gradlew test", now, now + 4, 0, "3 tests passed", "", TestExecutionOutcome.TESTS_PASSED, "Passed", workingDir = ws.absolutePath, passedCount = 3, failedCount = 0),
                        ),
                    )
                } else {
                    File(ws, "App.kt").writeText("fun greet() = \"hello unified\"\n")
                    RuntimeExecutionResponse(
                        requestId = request.requestId, providerId = "p1", modelId = "m1",
                        startedAtMs = now, completedAtMs = now + 5, success = true,
                        outputText = "Updated App.kt cleanly.",
                        modifiedFiles = listOf("App.kt"),
                    )
                }
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, true, true, true)
        }

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            runtime = runtime,
            checkpointManager = CheckpointManager(store),
            fileLockManager = FileLockManager(),
            livenessMonitor = ExecutionLivenessMonitor(store),
            delayFn = {},
        )

        // Calling preparePlan twice for the same active objective returns the SAME single authoritative task!
        val t1 = engine.preparePlan("p-u", team, "Update greeting message")
        val t2 = engine.preparePlan("p-u", team, "Update greeting message")
        assertNotNull(t1)
        assertEquals("Duplicate competing plan must not be created for active objective", t1!!.taskId, t2?.taskId)
        assertEquals(1, store.listTasksForProject("p-u").size)

        // Supervise to verified completion
        val done = engine.superviseTask(t1.taskId, team, requiredDeliverableFiles = listOf("App.kt"))
        assertEquals(PersistentTaskStatus.COMPLETED, done?.status)

        // Verify unified `.orqen/plan/` and `.orqen/reports/` artifacts exist and NO `.orqen/plan.md` exists!
        assertFalse(File(ws, ".orqen/plan.md").exists())
        assertTrue(File(ws, ".orqen/plan/overview.md").isFile)
        assertTrue(File(ws, ".orqen/reports/execution_report.md").isFile)
        assertTrue(File(ws, ".orqen/reports/review_report.md").isFile)
        assertTrue(File(ws, ".orqen/reports/verification_report.md").isFile)
        assertTrue(File(ws, ".orqen/reports/verification_report.md").readText().contains("./gradlew test"))
        assertTrue(File(ws, ".orqen/reports/review_report.md").readText().contains("REVIEW_APPROVED"))
    }

    @Test
    fun `chat mode remains strictly isolated from task creation`() {
        val agents = listOf(
            AgentState(AgentConfig(AgentSlot.AGENT_1, "SPV", UiAgentRole(AgentRoleType.TEAM_LEADER, "SPV", "Lead", listOf("lead")), isEnabled = true)),
        )
        assertEquals(SpvInteractionMode.CHAT_CONSULTATION, SpvModePolicy.resolveMode(GroupMessageType.NORMAL, "Start building this app now"))
        val route = SpvConversationRouter.routeOwnerMessage("Create an Android app", GroupMessageType.NORMAL, agents)
        assertNotNull(route)
        assertFalse(route!!.shouldCreateTask)
    }
}
