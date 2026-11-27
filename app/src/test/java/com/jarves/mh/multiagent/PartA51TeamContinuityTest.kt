package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.supervisor.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * ORQEN-Harness PART A.5.1 — Team Execution Completion & Autonomous Task Continuity Tests.
 *
 * Covers:
 * - Test A: Sequential persistent task queue (Task A running while Tasks B & C queued ->
 *           automatic A -> B -> C completion with zero duplicates and zero dropped tasks).
 * - Test C: Parallel independent subtasks execute concurrently within `team.maxConcurrentAgents`
 *           while dependent tasks wait until all prerequisites complete.
 * - Test G: Image attachment persists in [TaskEntity.taskMemory] via [TaskAttachmentCodec],
 *           survives queuing & process recreation, and reaches assigned/delegated agents in
 *           [RuntimeExecutionRequest.imageFiles].
 *
 * Note: Uses controlled boundary doubles for external AI providers; does not claim live API verification.
 */
class PartA51TeamContinuityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun setupProjectAndTeam(
        store: OrqenTaskStore,
        engine: AutonomousSupervisorEngine,
        id: String,
        maxConcurrentAgents: Int = 2,
    ): Pair<ProjectEntity, AgentTeamEntity> {
        val dir = tmp.newFolder("ws-a51-$id")
        val proj = ProjectEntity(projectId = id, projectName = "Project $id", workspacePath = dir.absolutePath)
        store.upsertProject(proj)
        val a1 = engine.registry.register(AgentProfileEntity("agent-01", "Architect", role = "Lead", providerId = "p1", modelId = "m1", capabilities = setOf("lead", "architect")))
        val a2 = engine.registry.register(AgentProfileEntity("agent-02", "Dev 1", role = "Dev", providerId = "p1", modelId = "m1", capabilities = setOf("code", "fix"), priority = 10))
        val a4 = engine.registry.register(AgentProfileEntity("agent-04", "Dev 2", role = "Dev", providerId = "p2", modelId = "m2", capabilities = setOf("code", "fix"), priority = 9))
        val a3 = engine.registry.register(AgentProfileEntity("agent-03", "QA Reviewer", role = "QA", providerId = "p3", modelId = "m3", capabilities = setOf("review", "qa", "test")))
        val team = engine.teamManager.createTeam(
            projectId = proj.projectId,
            teamName = "Team $id",
            memberAgentIds = listOf(a1.agentId, a2.agentId, a4.agentId, a3.agentId),
            maxConcurrentAgents = maxConcurrentAgents,
        )
        return proj to team
    }

    @Test
    fun testA_SequentialTaskQueueContinuesAutomaticallyThroughTasksABC() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val taskAStarted = CompletableDeferred<Unit>()
        val releaseTaskA = CompletableDeferred<Unit>()
        val completedOrder = CopyOnWriteArrayList<String>()

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                if (request.prompt.contains("Task A") && !taskAStarted.isCompleted) {
                    taskAStarted.complete(Unit)
                    releaseTaskA.await()
                }
                val isQa = request.agent.config.slot.id == "agent-03"
                val out = if (isQa) "REVIEW_APPROVED\nTESTS_PASSED" else "Done"
                val testEv = if (isQa) listOf(com.jarves.mh.runtime.TestExecutionEvidence("./gradlew test", 0L, 1L, 0, "ok", "", com.jarves.mh.runtime.TestExecutionOutcome.TESTS_PASSED, "ok")) else emptyList()
                return RuntimeExecutionResponse(request.requestId, "p", "m", 0L, 1L, true, out, testEvidence = testEv)
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, true, false)
        }

        val engine = AutonomousSupervisorEngine(taskStore = store, collabStore = collab, runtime = runtime, delayFn = {})
        val (proj, team) = setupProjectAndTeam(store, engine, "seq-queue")

        // Start Task A
        val runnerJob = async(Dispatchers.Default) {
            val resA = engine.submitAndSupervise(proj.projectId, team, "Task A: Build Auth")
            if (resA != null) completedOrder.add(resA.title)
            engine.superviseProjectQueue(
                projectId = proj.projectId,
                team = team,
                onTaskFinished = { finished -> completedOrder.add(finished.title) },
            )
        }

        taskAStarted.await()
        // While Task A is actively running, queue Task B and Task C in persistent store
        val taskB = engine.preparePlan(proj.projectId, team, "Task B: Build Database")
        val taskC = engine.preparePlan(proj.projectId, team, "Task C: Build Dashboard")
        assertNotNull(taskB)
        assertNotNull(taskC)
        assertEquals(PersistentTaskStatus.READY, store.getTask(taskB!!.taskId)?.status)
        assertEquals(PersistentTaskStatus.READY, store.getTask(taskC!!.taskId)?.status)

        // Release Task A -> Queue MUST automatically continue to Task B and then Task C!
        releaseTaskA.complete(Unit)
        runnerJob.await()

        assertEquals(
            listOf("Task A: Build Auth", "Task B: Build Database", "Task C: Build Dashboard"),
            completedOrder.toList(),
        )
        assertTrue(store.listTasksForProject(proj.projectId).all { it.status == PersistentTaskStatus.COMPLETED })
    }

    @Test
    fun testC_ParallelIndependentSubtasksExecuteConcurrentlyWhileDependentStepWaits() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val activeNow = AtomicInteger(0)
        val peakActive = AtomicInteger(0)
        val bothEntered = CompletableDeferred<Unit>()
        val enteredCount = AtomicInteger(0)
        val executionLog = CopyOnWriteArrayList<String>()

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                val aid = request.agent.config.slot.id
                if (aid == "agent-02" || aid == "agent-04") {
                    val cur = activeNow.incrementAndGet()
                    peakActive.updateAndGet { prev -> maxOf(prev, cur) }
                    executionLog.add("START:$aid")
                    if (enteredCount.incrementAndGet() >= 2) {
                        bothEntered.complete(Unit)
                    } else {
                        withTimeoutOrNull(1_500L) { bothEntered.await() }
                    }
                    activeNow.decrementAndGet()
                    executionLog.add("END:$aid")
                    return RuntimeExecutionResponse(request.requestId, "p", "m", 0L, 1L, true, "Parallel impl done by $aid")
                }
                if (aid == "agent-03") {
                    executionLog.add("START:agent-03")
                    return RuntimeExecutionResponse(
                        request.requestId, "p", "m", 0L, 1L, true, "REVIEW_APPROVED\nTESTS_PASSED",
                        testEvidence = listOf(
                            com.jarves.mh.runtime.TestExecutionEvidence(
                                command = "./gradlew test", startedAtMs = 0L, completedAtMs = 1L, exitCode = 0,
                                stdout = "2 tests passed", stderr = "", outcome = com.jarves.mh.runtime.TestExecutionOutcome.TESTS_PASSED, summary = "Passed",
                            ),
                        ),
                    )
                }
                return RuntimeExecutionResponse(request.requestId, "p", "m", 0L, 1L, true, "Inspected")
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, true, false)
        }

        val engine = AutonomousSupervisorEngine(taskStore = store, collabStore = collab, runtime = runtime, delayFn = {})
        val (proj, team) = setupProjectAndTeam(store, engine, "parallel", maxConcurrentAgents = 2)

        // Two independent implementation subtasks -> assigned to agent-02 and agent-04, followed by dependent QA review!
        val result = engine.submitAndSupervise(
            projectId = proj.projectId,
            team = team,
            objective = "Build parallel frontend and backend",
            requestedSubtasks = listOf("Implement Backend API", "Implement Frontend UI"),
        )

        assertEquals(PersistentTaskStatus.COMPLETED, result?.status)
        assertTrue("Expected concurrent execution peak >= 2, got ${peakActive.get()}", peakActive.get() >= 2)
        // Dependent QA step (agent-03) MUST start only AFTER both parallel devs finished!
        val qaIdx = executionLog.indexOf("START:agent-03")
        assertTrue(qaIdx > executionLog.indexOf("END:agent-02"))
        assertTrue(qaIdx > executionLog.indexOf("END:agent-04"))
    }

    @Test
    fun testG_ImageAttachmentPersistsAcrossQueueAndProcessRecreationAndReachesAgents() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val seenImagesByAgent = CopyOnWriteArrayList<Pair<String, List<String>>>()

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                val aid = request.agent.config.slot.id
                seenImagesByAgent.add(aid to request.imageFiles.map { it.name })
                val out = if (aid == "agent-03") "REVIEW_APPROVED\nTESTS_PASSED" else "Built UI matching reference image"
                val testEv = if (aid == "agent-03") {
                    listOf(
                        com.jarves.mh.runtime.TestExecutionEvidence(
                            command = "./gradlew test", startedAtMs = 0L, completedAtMs = 1L, exitCode = 0,
                            stdout = "1 tests passed", stderr = "", outcome = com.jarves.mh.runtime.TestExecutionOutcome.TESTS_PASSED, summary = "Passed",
                        ),
                    )
                } else emptyList()
                return RuntimeExecutionResponse(request.requestId, "p", "m", 0L, 1L, true, out, testEvidence = testEv)
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, true, true, false)
        }

        val engine1 = AutonomousSupervisorEngine(taskStore = store, collabStore = collab, runtime = runtime, delayFn = {})
        val (proj, team) = setupProjectAndTeam(store, engine1, "img-handoff")

        // Create a real non-empty image file inside the project's workspace
        val uploadsDir = File(proj.workspacePath, ".orqen_uploads").apply { mkdirs() }
        val mockImg = File(uploadsDir, "ui_mockup.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        // Persist queued task with image attachment BEFORE process recreation
        val queuedTask = engine1.preparePlan(
            projectId = proj.projectId,
            team = team,
            objective = "Match attached UI mockup",
            attachmentPaths = listOf(mockImg.absolutePath),
        )
        assertNotNull(queuedTask)
        assertTrue(TaskAttachmentCodec.decodePaths(store.getTask(queuedTask!!.taskId)!!.taskMemory).contains(mockImg.absolutePath))

        // Simulate Android process recreation: fresh AutonomousSupervisorEngine reading ONLY from persisted store!
        val engine2 = AutonomousSupervisorEngine(taskStore = store, collabStore = collab, runtime = runtime, delayFn = {})
        val processed = engine2.superviseProjectQueue(proj.projectId, team)

        assertEquals(1, processed.size)
        assertEquals(PersistentTaskStatus.COMPLETED, processed.first().status)
        assertTrue("Developer must receive actual ui_mockup.jpg file", seenImagesByAgent.any { it.first == "agent-02" && "ui_mockup.jpg" in it.second })
        assertTrue("Reviewer must receive actual ui_mockup.jpg file", seenImagesByAgent.any { it.first == "agent-03" && "ui_mockup.jpg" in it.second })
    }
}
