package com.jarves.mh.multiagent

import com.jarves.mh.model.Project
import com.jarves.mh.multiagent.collab.AgentProfileEntity
import com.jarves.mh.multiagent.collab.InMemoryCollaborationStore
import com.jarves.mh.multiagent.engine.AIExecutionRuntime
import com.jarves.mh.multiagent.engine.MultiPartPlanManager
import com.jarves.mh.multiagent.engine.RuntimeCapabilities
import com.jarves.mh.multiagent.engine.RuntimeExecutionRequest
import com.jarves.mh.multiagent.engine.RuntimeExecutionResponse
import com.jarves.mh.multiagent.model.ActivityType
import com.jarves.mh.multiagent.model.AgentActivityState
import com.jarves.mh.multiagent.model.AgentConfig
import com.jarves.mh.multiagent.model.AgentRole
import com.jarves.mh.multiagent.model.AgentRoleType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.multiagent.persistence.InMemoryOrqenTaskStore
import com.jarves.mh.multiagent.persistence.PersistentTaskStatus
import com.jarves.mh.multiagent.persistence.ProjectEntity
import com.jarves.mh.multiagent.persistence.TaskCompletionCriteria
import com.jarves.mh.multiagent.supervisor.AutonomousSupervisorEngine
import com.jarves.mh.multiagent.ui.Universe3DProjector
import com.jarves.mh.multiagent.ui.UniverseCamera
import com.jarves.mh.multiagent.ui.UniverseEdgeType
import com.jarves.mh.multiagent.ui.UniverseGraphBuilder
import com.jarves.mh.multiagent.ui.UniverseNodeType
import com.jarves.mh.runtime.TestExecutionEvidence
import com.jarves.mh.runtime.TestExecutionOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ManagerWorkspaceAndParallelCoderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `UniverseGraphBuilder produces empty graph with zero fake stars when no projects or agents exist`() {
        val graph = UniverseGraphBuilder.buildFromRuntime(
            activeProject = null,
            allTeamProjects = emptyList(),
            agents = emptyList(),
            activeSnapshot = null,
            collaborationEvents = emptyList(),
            isTeamRunning = false,
        )
        assertTrue(graph.isEmpty)
        assertEquals(0, graph.nodes.size)
        assertEquals(0, graph.edges.size)
    }

    @Test
    fun `UniverseGraphBuilder builds real nodes and animates particles only on live edges`() {
        val proj = Project(id = "p-1", name = "Orqen App", description = "Android AI Workspace", language = "Kotlin", slug = "orqen-app", rootPath = "/tmp/orqen-app")
        val agents = listOf(
            AgentState(
                config = AgentConfig(
                    slot = AgentSlot.AGENT_1,
                    name = "Manager",
                    role = AgentRole(AgentRoleType.TEAM_LEADER, systemInstructions = "", responsibilities = listOf("Plan")),
                ),
                isWorking = false,
            ),
            AgentState(
                config = AgentConfig(
                    slot = AgentSlot.AGENT_2,
                    name = "Dev Alpha",
                    role = AgentRole(AgentRoleType.SOFTWARE_ENGINEER, systemInstructions = "", responsibilities = listOf("Code")),
                ),
                activityState = AgentActivityState(activity = ActivityType.WRITING_CODE, currentTask = "Write UI"),
                isWorking = true,
            ),
            AgentState(
                config = AgentConfig(
                    slot = AgentSlot.AGENT_3,
                    name = "Dev Beta",
                    role = AgentRole(AgentRoleType.BACKEND_ENGINEER, systemInstructions = "", responsibilities = listOf("API")),
                ),
                activityState = AgentActivityState(activity = ActivityType.WRITING_CODE, currentTask = "Write API"),
                isWorking = true,
            ),
        )

        val graph = UniverseGraphBuilder.buildFromRuntime(
            activeProject = proj,
            allTeamProjects = listOf(proj),
            agents = agents,
            activeSnapshot = null,
            collaborationEvents = emptyList(),
            isTeamRunning = true,
        )

        assertEquals(1, graph.nodes.count { it.type == UniverseNodeType.PROJECT })
        assertEquals(1, graph.nodes.count { it.type == UniverseNodeType.OWNER })
        assertEquals(3, graph.nodes.count { it.type == UniverseNodeType.AGENT })
        // Parallel coders should have an active AGENT_AGENT sync edge
        assertTrue(graph.edges.any { it.type == UniverseEdgeType.AGENT_AGENT && it.hasLiveFlow })
        // Idle manager edge to project must NOT emit fake live particles
        val managerProjEdge = graph.edges.first { it.toId == "agent:agent-01" && it.type == UniverseEdgeType.PROJECT_AGENT }
        assertFalse("Idle agent edge must not fabricate fake live particles", managerProjEdge.hasLiveFlow)

        // Verify 3D projection, hit testing, and camera transforms
        val cam = UniverseCamera.Default.rotated(0.25f, -0.1f).zoomed(1.2f).panned(12f, -8f)
        val projected = Universe3DProjector.projectNodes(graph.nodes, cam, 1080f, 1920f, is2DFallback = false)
        assertEquals(5, projected.size)
        val topNode = projected.last()
        val hit = Universe3DProjector.hitTest(projected, topNode.screenX, topNode.screenY)
        assertNotNull(hit)
        assertEquals(topNode.node.id, hit!!.node.id)
    }

    @Test
    fun `MultiPartPlanManager splits coding parts across multiple coding agents for parallel execution`() {
        val ws = tempFolder.newFolder("parallel-plan-ws")
        val plan = MultiPartPlanManager.createPlan(
            projectId = "proj-p",
            taskId = "T-PAR-1",
            projectName = "Parallel App",
            objective = "Build a complete multi-module dashboard with backend API, frontend UI, state management, and automated tests.",
            workspaceDir = ws,
            coderAgentIds = listOf("agent-02", "agent-03"),
        )
        assertTrue("Expected at least 2 parts", plan.parts.size >= 2)
        val assignedCoders = plan.parts.map { it.assignedAgentId }.toSet()
        assertTrue("Expected both coders assigned across parts, got $assignedCoders", assignedCoders.containsAll(setOf("agent-02", "agent-03")))
        val parallelReadyParts = plan.parts.filter { it.dependencies.isEmpty() }
        assertTrue("Expected at least 2 independent parts ready in parallel", parallelReadyParts.size >= 2)
    }

    @Test
    fun `AutonomousSupervisorEngine executes multiple coding agents concurrently in parallel`() = runBlocking {
        val ws = tempFolder.newFolder("parallel-exec-ws")
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val project = ProjectEntity("proj-par", "Parallel Team App", ws.absolutePath)
        taskStore.upsertProject(project)

        val currentActiveCoders = AtomicInteger(0)
        val maxConcurrentCoders = AtomicInteger(0)

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(
                request: RuntimeExecutionRequest,
                onProgress: suspend (String) -> Unit,
            ): RuntimeExecutionResponse {
                val now = System.currentTimeMillis()
                val agentId = request.agent.config.slot.id
                if (agentId == "agent-02" || agentId == "agent-03") {
                    val active = currentActiveCoders.incrementAndGet()
                    maxConcurrentCoders.accumulateAndGet(active, ::maxOf)
                    try {
                        delay(120L)
                        val outFile = File(ws, "src/$agentId.kt").apply {
                            parentFile?.mkdirs()
                            writeText("// written by $agentId\n")
                        }
                        return RuntimeExecutionResponse(
                            requestId = request.requestId,
                            providerId = "p1",
                            modelId = "m-coder",
                            startedAtMs = now,
                            completedAtMs = System.currentTimeMillis(),
                            success = true,
                            outputText = "Implemented module cleanly by $agentId.",
                            modifiedFiles = listOf(outFile.relativeTo(ws).path),
                        )
                    } finally {
                        currentActiveCoders.decrementAndGet()
                    }
                }
                return RuntimeExecutionResponse(
                    requestId = request.requestId,
                    providerId = "p1",
                    modelId = "m-qa",
                    startedAtMs = now,
                    completedAtMs = now + 5,
                    success = true,
                    outputText = "Verified all parallel modules. REVIEW_APPROVED TESTS_PASSED",
                    testEvidence = listOf(
                        TestExecutionEvidence("./gradlew test", now, now + 5, 0, "ok", "", TestExecutionOutcome.TESTS_PASSED, "passed"),
                    ),
                )
            }

            override fun cancel(requestId: String): Boolean = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, true, true, true)
        }

        val engine = AutonomousSupervisorEngine(taskStore = taskStore, collabStore = collabStore, runtime = runtime, delayFn = {})
        val mgr = engine.registry.register(AgentProfileEntity("agent-01", "Manager", "Lead", "Manager", "p1", "m1", capabilities = setOf("lead", "architect", "plan", "review", "qa")))
        val dev1 = engine.registry.register(AgentProfileEntity("agent-02", "Frontend Dev", "Frontend Engineer", "Frontend", "p1", "m2", capabilities = setOf("code", "fix")))
        val dev2 = engine.registry.register(AgentProfileEntity("agent-03", "Backend Dev", "Backend Engineer", "Backend", "p1", "m3", capabilities = setOf("code", "fix")))
        val team = engine.teamManager.createTeam(
            projectId = project.projectId,
            teamName = "Parallel Squad",
            memberAgentIds = listOf(mgr.agentId, dev1.agentId, dev2.agentId),
            requireIndependentReview = true,
            maxConcurrentAgents = 3,
        )

        val result = engine.submitAndSupervise(
            projectId = project.projectId,
            team = team,
            objective = "Build frontend UI screen and backend service module in parallel.",
            requestedSubtasks = listOf("Implement frontend UI module", "Implement backend service module"),
            completionCriteria = TaskCompletionCriteria(requireBuildPass = false, requireTestsPass = true, requireReviewerApproval = true),
        )

        assertNotNull(result)
        assertEquals(PersistentTaskStatus.COMPLETED, result!!.status)
        assertTrue(
            "Expected 2 coding agents to execute concurrently in parallel, observed maxConcurrent=${maxConcurrentCoders.get()}",
            maxConcurrentCoders.get() >= 2,
        )
        assertTrue(File(ws, "src/agent-02.kt").isFile)
        assertTrue(File(ws, "src/agent-03.kt").isFile)
    }

    @Test
    fun `LocalWorkspaceWebServer hosts workspace index html and assets on 127_0_0_1`() {
        val ws = tempFolder.newFolder("web-host-ws")
        File(ws, "index.html").writeText("<!doctype html><html><body><h1>ORQEN Web</h1></body></html>")
        File(ws, "app.js").writeText("console.log('ok');")

        com.jarves.mh.runtime.LocalWorkspaceWebServer(ws).use { server ->
            val url = server.start()
            assertTrue("Expected localhost URL, got $url", url.startsWith("http://127.0.0.1:"))

            val htmlConn = java.net.URL("$url/").openConnection() as java.net.HttpURLConnection
            assertEquals(200, htmlConn.responseCode)
            assertTrue(htmlConn.contentType.contains("text/html"))
            val html = htmlConn.inputStream.bufferedReader().readText()
            assertTrue(html.contains("ORQEN Web"))

            val jsConn = java.net.URL("$url/app.js").openConnection() as java.net.HttpURLConnection
            assertEquals(200, jsConn.responseCode)
            assertTrue(jsConn.contentType.contains("javascript"))
            val js = jsConn.inputStream.bufferedReader().readText()
            assertTrue(js.contains("console.log('ok')"))
        }
    }
}
