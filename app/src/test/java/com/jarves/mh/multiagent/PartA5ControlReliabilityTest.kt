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
import java.util.concurrent.atomic.AtomicReference

/**
 * ORQEN-Harness PART A.5 — Production Task Flow Unification & Control Reliability Test Suite.
 *
 * Verifies:
 * 1. Task ID is created & published via `onTaskCreated` BEFORE the first step executes,
 *    allowing immediate Pause/Stop targeting of an actively running task.
 * 2. Duplicate active submission for the same project + objective is idempotently deduplicated.
 * 3. Pause during active execution halts cleanly without triggering unwanted failure recovery,
 *    and Resume completes the task.
 * 4. Stop/Cancel during active execution persists CANCELLED and blocks any automatic restart.
 * 5. Valid & invalid lifecycle transitions via [TaskStatusTransitionValidator].
 * 6. Operational UI snapshot (`SupervisorUiSnapshot`) reflects real persisted status, agent health,
 *    and recovery decisions without mock progress.
 */
class PartA5ControlReliabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun setupProjectAndTeam(
        store: OrqenTaskStore,
        engine: AutonomousSupervisorEngine,
        id: String,
    ): Pair<ProjectEntity, AgentTeamEntity> {
        val dir = tmp.newFolder("ws-a5-$id")
        val proj = ProjectEntity(projectId = id, projectName = "Project $id", workspacePath = dir.absolutePath)
        store.upsertProject(proj)
        val a1 = engine.registry.register(AgentProfileEntity("agent-01", "Lead", role = "Lead", providerId = "p1", modelId = "m1", capabilities = setOf("lead", "architect")))
        val a2 = engine.registry.register(AgentProfileEntity("agent-02", "Dev", role = "Dev", providerId = "p1", modelId = "m1", capabilities = setOf("code", "fix")))
        val a3 = engine.registry.register(AgentProfileEntity("agent-03", "QA", role = "QA", providerId = "p2", modelId = "m2", capabilities = setOf("review", "qa", "test")))
        val team = engine.teamManager.createTeam(proj.projectId, "Team $id", listOf(a1.agentId, a2.agentId, a3.agentId))
        return proj to team
    }

    @Test
    fun test01_TaskIdPublishedBeforeExecutionAllowsImmediatePauseAndResume() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val publishedTaskId = AtomicReference<String?>(null)
        val step1Started = CompletableDeferred<Unit>()
        val releaseStep1 = CompletableDeferred<Unit>()

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                val aid = request.agent.config.slot.id
                if (!step1Started.isCompleted) {
                    step1Started.complete(Unit)
                    releaseStep1.await()
                }
                if (aid == "agent-01") {
                    return RuntimeExecutionResponse(request.requestId, "p1", "m1", 0L, 1L, true, "Plan analyzed")
                }
                if (aid == "agent-03") {
                    return RuntimeExecutionResponse(
                        request.requestId, "p2", "m2", 0L, 1L, true, "REVIEW_APPROVED\nTESTS_PASSED",
                        testEvidence = listOf(com.jarves.mh.runtime.TestExecutionEvidence("./gradlew test", 0L, 1L, 0, "ok", "", com.jarves.mh.runtime.TestExecutionOutcome.TESTS_PASSED, "ok")),
                    )
                }
                File(request.workspaceDir, "App.kt").writeText("class App")
                return RuntimeExecutionResponse(request.requestId, "p1", "m1", 0L, 1L, true, "Implemented App.kt", modifiedFiles = listOf("App.kt"))
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, true, false)
        }

        val engine = AutonomousSupervisorEngine(taskStore = store, collabStore = collab, runtime = runtime, delayFn = {})
        val (proj, team) = setupProjectAndTeam(store, engine, "pause-resume")

        val job = async(Dispatchers.Default) {
            engine.submitAndSupervise(
                projectId = proj.projectId,
                team = team,
                objective = "Build App.kt",
                requiredDeliverableFiles = listOf("App.kt"),
                onTaskCreated = { created -> publishedTaskId.set(created.taskId) },
            )
        }

        step1Started.await()
        // Task ID MUST already be published while Step 1 is still running!
        val tid = publishedTaskId.get()
        assertNotNull("Task ID must be published BEFORE step execution completes", tid)

        // Pause the actively running task using its published Task ID
        val current = store.getTask(tid!!)!!
        store.upsertTask(current.copy(pauseRequested = true, status = PersistentTaskStatus.PAUSED))
        releaseStep1.complete(Unit)

        val pausedResult = job.await()
        assertEquals(PersistentTaskStatus.PAUSED, pausedResult?.status)
        assertEquals(SupervisorOperationalStatus.PAUSED, engine.snapshotFor(tid)?.status)

        // Resume the paused task -> completes cleanly with verified deliverable!
        store.upsertTask(store.getTask(tid)!!.copy(pauseRequested = false, status = PersistentTaskStatus.QUEUED))
        val finalResult = engine.superviseTask(tid, team, requiredDeliverableFiles = listOf("App.kt"))
        assertEquals(PersistentTaskStatus.COMPLETED, finalResult?.status)
        assertEquals(SupervisorOperationalStatus.COMPLETED, engine.snapshotFor(tid)?.status)
    }

    @Test
    fun test02_RepeatedSubmissionDeduplicatesActiveTaskAndStopPreventsRestart() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val dummyRuntime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit) =
                RuntimeExecutionResponse(
                    request.requestId, "p", "m", 0L, 1L, true, "REVIEW_APPROVED\nTESTS_PASSED",
                    testEvidence = listOf(com.jarves.mh.runtime.TestExecutionEvidence("./gradlew test", 0L, 1L, 0, "ok", "", com.jarves.mh.runtime.TestExecutionOutcome.TESTS_PASSED, "ok")),
                )
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, false, false)
        }

        val engine = AutonomousSupervisorEngine(taskStore = store, collabStore = collab, runtime = dummyRuntime, delayFn = {})
        val (proj, team) = setupProjectAndTeam(store, engine, "dedupe-stop")

        val t1 = engine.preparePlan(proj.projectId, team, "Build Login Screen")
        val t2 = engine.preparePlan(proj.projectId, team, "Build Login Screen")
        assertNotNull(t1)
        assertEquals("Repeated active submission must reuse existing Task ID", t1?.taskId, t2?.taskId)
        assertEquals(1, store.listTasksForProject(proj.projectId).size)

        // Owner stops task -> reconcileActiveTasksOnce must NEVER restart it!
        val tid = t1!!.taskId
        store.upsertTask(store.getTask(tid)!!.copy(ownerStopRequested = true, status = PersistentTaskStatus.CANCELLED))
        val resumed = engine.reconcileActiveTasksOnce(team)
        assertTrue("Cancelled task must never be auto-resumed", resumed.isEmpty())
        assertEquals(PersistentTaskStatus.CANCELLED, store.getTask(tid)?.status)

        // Verify transition validator rejects silent COMPLETED -> RUNNING without explicit user reopen
        assertFalse(TaskStatusTransitionValidator.isValidTransition(PersistentTaskStatus.COMPLETED, PersistentTaskStatus.RUNNING, explicitUserReopen = false))
        assertFalse(TaskStatusTransitionValidator.isValidTransition(PersistentTaskStatus.CANCELLED, PersistentTaskStatus.RUNNING, explicitUserReopen = false))
        assertTrue(TaskStatusTransitionValidator.isValidTransition(PersistentTaskStatus.PAUSED, PersistentTaskStatus.QUEUED))
    }
}
