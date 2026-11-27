package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.locks.FileLockManager
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
 * Phase 2 — Step Execution Engine Automated Test Suite (TEST 01 through TEST 27).
 */
class Phase2StepExecutionEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun setup(
        name: String,
        adapter: StepExecutionAdapter = object : StepExecutionAdapter {
            override suspend fun executeStep(input: StepExecutionInput) = StepExecutionOutput(true, "Done ${input.step.title}")
        },
        policy: RequestExecutionPolicy = RequestExecutionPolicy(initialBackoffMs = 1L, maxBackoffMs = 50L, maxRetriesPerStep = 3),
        recordedDelays: MutableList<Long>? = null,
    ): Quad<InMemoryOrqenTaskStore, ProjectEntity, File, PersistentTaskOrchestrator> {
        val store = InMemoryOrqenTaskStore()
        val dir = tempFolder.newFolder(name)
        val project = ProjectEntity(projectId = "proj-$name", projectName = "ORQEN-$name", workspacePath = dir.absolutePath)
        store.upsertProject(project)
        val checkpointMgr = CheckpointManager(store)
        val lockMgr = FileLockManager()
        val supervisor = ExecutionRecoverySupervisor(store, checkpointMgr, lockMgr, policy)
        val orch = PersistentTaskOrchestrator(
            store = store,
            checkpointManager = checkpointMgr,
            supervisor = supervisor,
            fileLockManager = lockMgr,
            stepAdapter = adapter,
            policy = policy,
            delayFn = { ms -> recordedDelays?.add(ms) },
        )
        return Quad(store, project, dir, orch)
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    // TEST 01 — Dynamic team planning creates a structured, valid multi-part plan
    @Test
    fun test01_taskPlanningCreatesStructuredPlan() {
        val (_, project, dir, _) = setup("p2-01")
        val compiler = DynamicTeamPlanCompiler()
        val mp = MultiPartPlanManager.createPlan(
            projectId = project.projectId,
            taskId = "T-p2-01",
            projectName = project.projectName,
            objective = "Build Auth Module",
            workspaceDir = dir,
            explicitSubtasks = listOf("Create User model", "Add JWT login"),
        )
        assertTrue(MultiPartPlanManager.persistPlan(dir, mp).valid)
        val plan = compiler.planFromMultiPart(project, mp, dir)
        val validation = PlanValidator.validate(plan)

        assertTrue(validation.valid)
        assertEquals(4, plan.steps.size) // 2 parts x (impl + review/QA)
        assertTrue(plan.steps.last().requiresReview)
        assertFalse("Legacy .orqen/plan.md must NOT be auto-created", File(dir, ".orqen/plan.md").exists())
    }

    // TEST 02 — Plan persistence survives database reopening
    @Test
    fun test02_planPersistenceSurvivesReopen() {
        val dbFile = File(tempFolder.root, "p2-02.db")
        val store1 = FileBackedOrqenTaskStore(dbFile)
        val wsDir = tempFolder.newFolder("p02")
        val proj = ProjectEntity("p02", "Proj02", wsDir.absolutePath)
        store1.upsertProject(proj)
        val compiler = DynamicTeamPlanCompiler()
        val mp = MultiPartPlanManager.createPlan(proj.projectId, "T-p02", proj.projectName, "Add Dark Mode", wsDir)
        assertTrue(MultiPartPlanManager.persistPlan(wsDir, mp).valid)
        val task = compiler.persistPlan(store1, proj.projectId, compiler.planFromMultiPart(proj, mp, wsDir), planId = mp.planId, planVersion = mp.version)

        val store2 = FileBackedOrqenTaskStore(dbFile)
        val reloaded = store2.getTask(task.taskId)
        assertNotNull(reloaded)
        assertEquals(PersistentTaskStatus.READY, reloaded?.status)
    }

    // TEST 03 — Dependency resolution executes DAG (A -> B, A -> C, B + C -> D) in valid order
    @Test
    fun test03_dependencyResolutionDagOrder() = runBlocking {
        val executed = mutableListOf<String>()
        val (store, project, _, orch) = setup(
            "p2-03",
            adapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    executed.add(input.step.stepId)
                    return StepExecutionOutput(true, "Done ${input.step.stepId}")
                }
            },
        )
        val steps = listOf(
            TaskStepEntity(stepId = "A", taskId = "", sequenceNumber = 1, title = "A", description = "Inspect", assignedAgentId = "agent-leader"),
            TaskStepEntity(stepId = "B", taskId = "", sequenceNumber = 2, title = "B", description = "Backend", assignedAgentId = "agent-developer", dependencies = listOf("A")),
            TaskStepEntity(stepId = "C", taskId = "", sequenceNumber = 3, title = "C", description = "Frontend", assignedAgentId = "agent-integrator", dependencies = listOf("A")),
            TaskStepEntity(stepId = "D", taskId = "", sequenceNumber = 4, title = "D", description = "Integrate", assignedAgentId = "agent-reviewer", dependencies = listOf("B", "C")),
        )
        val task = orch.createTaskWithSteps(project.projectId, "DAG Task", "", steps)
        val done = orch.runTaskLoop(task.taskId)

        assertEquals(PersistentTaskStatus.COMPLETED, done?.status)
        assertEquals("A", executed.first())
        assertEquals("D", executed.last())
        assertEquals(setOf("B", "C"), executed.subList(1, 3).toSet())
    }

    // TEST 04 — Circular dependency rejected by PlanValidator and DependencyResolver
    @Test
    fun test04_circularDependencyRejected() {
        val cyclicPlan = TaskPlan(
            title = "Bad Plan",
            objective = "Loop",
            acceptanceCriteria = listOf("None"),
            steps = listOf(
                PlannedStep(stepId = "X", sequenceNumber = 1, title = "X", description = "X", expectedOutput = "X", dependencies = listOf("Y")),
                PlannedStep(stepId = "Y", sequenceNumber = 2, title = "Y", description = "Y", expectedOutput = "Y", dependencies = listOf("X")),
            ),
        )
        val res = PlanValidator.validate(cyclicPlan)
        assertFalse(res.valid)
        assertTrue(res.errors.any { it.contains("circular", ignoreCase = true) })
    }

    // TEST 05 — Step scheduling selects only eligible steps
    @Test
    fun test05_stepSchedulerSelectsEligibleSteps() {
        val (store, project, _, orch) = setup("p2-05")
        val task = orch.createTaskWithSteps(
            project.projectId,
            "Schedule Test",
            "",
            listOf(
                TaskStepEntity(stepId = "s1", taskId = "", sequenceNumber = 1, title = "First", description = "", assignedAgentId = "dev"),
                TaskStepEntity(stepId = "s2", taskId = "", sequenceNumber = 2, title = "Second", description = "", assignedAgentId = "dev", dependencies = listOf("s1")),
            ),
        )
        val scheduler = StepScheduler(store)
        val eligible = scheduler.findEligibleSteps(task.taskId)
        assertEquals(listOf("s1"), eligible.map { it.stepId })
    }

    // TEST 06 — Atomic claiming prevents two workers from claiming the same step
    @Test
    fun test06_atomicClaimingPreventsDoubleClaim() {
        val (store, project, _, orch) = setup("p2-06")
        val task = orch.createTaskWithSteps(
            project.projectId,
            "Claim Test",
            "",
            listOf(TaskStepEntity(stepId = "c1", taskId = "", sequenceNumber = 1, title = "Exclusive", description = "", assignedAgentId = "dev")),
        )
        val scheduler = StepScheduler(store)
        val lease1 = scheduler.tryClaimStep(task.taskId, "c1", "worker-1")
        val lease2 = scheduler.tryClaimStep(task.taskId, "c1", "worker-2")

        assertNotNull(lease1)
        assertNull(lease2)
        assertTrue(scheduler.isLeaseOwner("c1", "worker-1"))
        assertFalse(scheduler.isLeaseOwner("c1", "worker-2"))
    }

    // TEST 07 — Every execution attempt creates a persistent execution record
    @Test
    fun test07_executionRecordsPersistedPerAttempt() = runBlocking {
        val attempts = AtomicInteger(0)
        val (store, project, _, orch) = setup(
            "p2-07",
            adapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    return if (attempts.incrementAndGet() == 1) {
                        StepExecutionOutput(false, "", error = StructuredErrorClassifier.classify("Socket reset"))
                    } else {
                        StepExecutionOutput(true, "Succeeded on attempt 2", inputTokens = 120, outputTokens = 80)
                    }
                }
            },
        )
        val task = orch.createTaskWithSteps(
            project.projectId, "Exec History", "",
            listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "Try Twice", description = "", assignedAgentId = "agent-developer")),
        )
        orch.runTaskLoop(task.taskId)

        val execs = store.listExecutionsForTask(task.taskId)
        assertEquals(2, execs.size)
        assertEquals("FAILED", execs[0].executionStatus)
        assertEquals("COMPLETED", execs[1].executionStatus)
        assertNotEquals(execs[0].executionId, execs[1].executionId)
    }

    // TEST 08 — Request timeout does NOT fail the entire task
    @Test
    fun test08_requestTimeoutRecoversWithoutFailingTask() = runBlocking {
        val attempts = AtomicInteger(0)
        val (store, project, _, orch) = setup(
            "p2-08",
            adapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    if (input.step.sequenceNumber == 2 && attempts.incrementAndGet() == 1) {
                        return StepExecutionOutput(false, "", error = StructuredErrorClassifier.classify("Timed out", isTimeout = true))
                    }
                    return StepExecutionOutput(true, "OK #${input.step.sequenceNumber}")
                }
            },
        )
        val task = orch.createTaskWithSteps(
            project.projectId, "Timeout Safe", "",
            listOf(
                TaskStepEntity(stepId = "t1", taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "agent-developer"),
                TaskStepEntity(stepId = "t2", taskId = "", sequenceNumber = 2, title = "S2", description = "", assignedAgentId = "agent-developer", dependencies = listOf("t1")),
            ),
        )
        val done = orch.runTaskLoop(task.taskId)
        assertEquals(PersistentTaskStatus.COMPLETED, done?.status)
        assertEquals(StepStatus.COMPLETED, store.getStep("t1")?.status)
    }

    // TEST 09 — Provider rate limit respects Retry-After recovery policy
    @Test
    fun test09_providerRateLimitRecoveryPolicy() = runBlocking {
        val delays = mutableListOf<Long>()
        val attempts = AtomicInteger(0)
        val (_, project, _, orch) = setup(
            "p2-09",
            adapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    return if (attempts.incrementAndGet() == 1) {
                        StepExecutionOutput(false, "", error = StructuredErrorClassifier.classify("HTTP 429 Retry-After: 4", httpStatusCode = 429))
                    } else {
                        StepExecutionOutput(true, "Recovered after 429")
                    }
                }
            },
            policy = RequestExecutionPolicy(initialBackoffMs = 100L, maxBackoffMs = 10_000L, respectRetryAfter = true),
            recordedDelays = delays,
        )
        val task = orch.createTaskWithSteps(project.projectId, "429", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "API", description = "", assignedAgentId = "agent-developer")))
        assertEquals(PersistentTaskStatus.COMPLETED, orch.runTaskLoop(task.taskId)?.status)
        assertEquals(listOf(4_000L), delays)
    }

    // TEST 10 — Authentication failure stops immediately without endless retry
    @Test
    fun test10_authenticationFailureNoEndlessRetry() = runBlocking {
        val attempts = AtomicInteger(0)
        val (_, project, _, orch) = setup(
            "p2-10",
            adapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    attempts.incrementAndGet()
                    return StepExecutionOutput(false, "", error = StructuredErrorClassifier.classify("HTTP 401 invalid_api_key", httpStatusCode = 401))
                }
            },
        )
        val task = orch.createTaskWithSteps(project.projectId, "401", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "API", description = "", assignedAgentId = "agent-developer")))
        assertEquals(PersistentTaskStatus.BLOCKED, orch.runTaskLoop(task.taskId)?.status)
        assertEquals(1, attempts.get())
    }

    // TEST 11 — Partial execution after workspace modification detected before retry
    @Test
    fun test11_partialExecutionInspectedBeforeRetry() {
        val (store, project, dir, _) = setup("p2-11")
        val chkMgr = CheckpointManager(store)
        val supervisor = ExecutionRecoverySupervisor(store, chkMgr, FileLockManager())
        val task = TaskEntity(projectId = project.projectId, title = "Partial", description = "", status = PersistentTaskStatus.RUNNING, lastHeartbeatAt = 1L)
        store.upsertTask(task)
        File(dir, "app.kt").writeText("fun main() {}")
        chkMgr.createCheckpoint(task.taskId, "s1", dir, listOf("app.kt"), "Clean")
        File(dir, "app.kt.partial").writeText("fun main(")

        val report = supervisor.reconcileOnStartup(staleThresholdMs = 1L)
        assertTrue(task.taskId in report.blockedTaskIds)
    }

    // TEST 12 — Duplicate scheduler events do not execute a step twice
    @Test
    fun test12_duplicateSchedulerEventsDoNotDoubleExecute() {
        val (store, project, _, orch) = setup("p2-12")
        val task = orch.createTaskWithSteps(project.projectId, "Dup Sched", "", listOf(TaskStepEntity(stepId = "d1", taskId = "", sequenceNumber = 1, title = "Once", description = "", assignedAgentId = "dev")))
        val scheduler = StepScheduler(store)
        assertNotNull(scheduler.tryClaimStep(task.taskId, "d1", "w1"))
        assertNull(scheduler.tryClaimStep(task.taskId, "d1", "w1")) // Already RUNNING
    }

    // TEST 13 — Idempotency prevents duplicating an already-completed side effect
    @Test
    fun test13_idempotencyPreventsDuplicateSideEffect() = runBlocking {
        val count = AtomicInteger(0)
        val (store, project, _, orch) = setup(
            "p2-13",
            adapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    count.incrementAndGet()
                    return StepExecutionOutput(true, "Side effect applied")
                }
            },
        )
        val step = TaskStepEntity(stepId = "i1", taskId = "", sequenceNumber = 1, title = "Apply", description = "", assignedAgentId = "agent-developer", idempotencyKey = "idem-p2-13")
        val task = orch.createTaskWithSteps(project.projectId, "Idem", "", listOf(step))
        orch.runTaskLoop(task.taskId)
        store.upsertStep(store.getStep("i1")!!.copy(status = StepStatus.READY))
        store.upsertTask(store.getTask(task.taskId)!!.copy(status = PersistentTaskStatus.QUEUED))
        orch.runTaskLoop(task.taskId)
        assertEquals(1, count.get())
    }

    // TEST 14 — Checkpoint recovery resumes using a valid checkpoint
    @Test
    fun test14_checkpointRecoveryResumesFromValidCheckpoint() {
        val (store, project, dir, _) = setup("p2-14")
        val chkMgr = CheckpointManager(store)
        val task = TaskEntity(projectId = project.projectId, title = "Resume", description = "", status = PersistentTaskStatus.RUNNING)
        store.upsertTask(task)
        File(dir, "good.txt").writeText("v1")
        val chk = chkMgr.createCheckpoint(task.taskId, "s1", dir, listOf("good.txt"), "Good")
        val inspection = chkMgr.verifyWorkspaceAgainstCheckpoint(dir, chk)
        assertTrue(inspection.consistent)
    }

    // TEST 15 — Stale / invalidated checkpoint is NOT blindly trusted
    @Test
    fun test15_staleOrInvalidCheckpointRejected() {
        val (store, project, dir, _) = setup("p2-15")
        val facade = OrqenPersistenceFacade(store)
        val task = facade.createTask(project.projectId, "Stale Chk", "")
        val chk = facade.createCheckpoint(task.taskId, "s1", tempFolder.root, dir.absolutePath, emptyList(), "Old")
        facade.markCheckpointInvalid(chk.checkpointId)
        val reloaded = store.listCheckpointsForTask(task.taskId).first()
        assertFalse(facade.validateCheckpointReference(reloaded, tempFolder.root))
    }

    // TEST 16 & 17 — Pause stops scheduling new steps; Resume continues from next eligible step
    @Test
    fun test16_17_pauseAndResumeFromNextEligibleStep() = runBlocking {
        val ran = mutableListOf<String>()
        var orchestratorRef: PersistentTaskOrchestrator? = null
        val (store, project, _, orch) = setup(
            "p2-16",
            adapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    ran.add(input.step.stepId)
                    if (input.step.stepId == "pr1") {
                        orchestratorRef?.pauseTask(input.task.taskId, "Pause after step 1")
                    }
                    return StepExecutionOutput(true, "Done ${input.step.stepId}")
                }
            },
        )
        orchestratorRef = orch
        val task = orch.createTaskWithSteps(
            project.projectId, "PauseResume", "",
            listOf(
                TaskStepEntity(stepId = "pr1", taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "agent-developer"),
                TaskStepEntity(stepId = "pr2", taskId = "", sequenceNumber = 2, title = "S2", description = "", assignedAgentId = "agent-developer", dependencies = listOf("pr1")),
            ),
        )
        val paused = orch.runTaskLoop(task.taskId)
        assertEquals(PersistentTaskStatus.PAUSED, paused?.status)
        assertEquals(listOf("pr1"), ran)

        val job = orch.resumeTask(this, task.taskId)
        job?.join()
        assertEquals(PersistentTaskStatus.COMPLETED, store.getTask(task.taskId)?.status)
        assertEquals(listOf("pr1", "pr2"), ran)
    }

    // TEST 18 — Stop cancels active execution safely and preserves completed steps
    @Test
    fun test18_stopCancelsActiveExecutionSafely() = runBlocking {
        val (store, project, _, orch) = setup(
            "p2-18",
            adapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    delay(300L)
                    return StepExecutionOutput(true, "Done")
                }
            },
        )
        val task = orch.createTaskWithSteps(project.projectId, "Stop Test", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "Slow", description = "", assignedAgentId = "agent-developer")))
        val job = orch.launchTask(this, task.taskId)
        delay(20L)
        orch.stopTask(task.taskId, "Owner stopped")
        job.join()
        assertEquals(PersistentTaskStatus.CANCELLED, store.getTask(task.taskId)?.status)
        assertTrue(store.getTask(task.taskId)?.ownerStopRequested == true)
    }

    // TEST 19 — Verification failure prevents step completion
    @Test
    fun test19_verificationFailurePreventsStepCompletion() = runBlocking {
        val (store, project, _, orch) = setup(
            "p2-19",
            adapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput) = StepExecutionOutput(
                    success = true,
                    summary = "Claimed I created missing.kt",
                    verificationSpec = VerificationSpec(
                        mode = VerificationMode.FILE_VERIFICATION,
                        expectedFiles = listOf("missing.kt"),
                    ),
                )
            },
        )
        val task = orch.createTaskWithSteps(project.projectId, "Verify Fail", "", listOf(TaskStepEntity(stepId = "vf1", taskId = "", sequenceNumber = 1, title = "Write", description = "", assignedAgentId = "agent-developer")))
        val result = orch.runTaskLoop(task.taskId)

        assertEquals(PersistentTaskStatus.BLOCKED, result?.status)
        assertEquals(StepStatus.BLOCKED, store.getStep("vf1")?.status)
    }

    // TEST 20 — Task completion requires all steps & verification to pass
    @Test
    fun test20_taskCompletionRequiresAllStepsAndVerification() = runBlocking {
        val (_, project, _, orch) = setup(
            "p2-20",
            adapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    File(input.workspaceDir, "deliverable.kt").writeText("object Deliverable")
                    return StepExecutionOutput(
                        success = true,
                        summary = "Created deliverable.kt",
                        modifiedFiles = listOf("deliverable.kt"),
                        verificationSpec = VerificationSpec(VerificationMode.FILE_VERIFICATION, listOf("deliverable.kt")),
                    )
                }
            },
        )
        val task = orch.createTaskWithSteps(project.projectId, "Complete Gate", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "Deliver", description = "", assignedAgentId = "agent-developer")))
        assertEquals(PersistentTaskStatus.COMPLETED, orch.runTaskLoop(task.taskId)?.status)
    }

    // TEST 21 — Blocked task preserves state and can be retried/resumed
    @Test
    fun test21_blockedTaskPreservesStateAndCanResume() = runBlocking {
        val attempts = AtomicInteger(0)
        val (store, project, _, orch) = setup(
            "p2-21",
            adapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    return if (attempts.incrementAndGet() == 1) {
                        StepExecutionOutput(false, "", error = StructuredErrorClassifier.classify("HTTP 401 unauthorized", httpStatusCode = 401))
                    } else {
                        StepExecutionOutput(true, "Worked after key fixed")
                    }
                }
            },
        )
        val task = orch.createTaskWithSteps(project.projectId, "Blocked->Resume", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "agent-developer")))
        assertEquals(PersistentTaskStatus.BLOCKED, orch.runTaskLoop(task.taskId)?.status)

        val retryJob = orch.retryFailedStep(this, task.taskId)
        retryJob?.join()
        assertEquals(PersistentTaskStatus.COMPLETED, store.getTask(task.taskId)?.status)
    }

    // TEST 22 — Context assembly excludes irrelevant historical steps
    @Test
    fun test22_contextAssemblyExcludesIrrelevantHistory() {
        val (_, project, _, _) = setup("p2-22")
        val task = TaskEntity(projectId = project.projectId, title = "Large Task", description = "Build platform")
        val steps = (1..20).map { i ->
            TaskStepEntity(
                stepId = "st-$i",
                taskId = task.taskId,
                sequenceNumber = i,
                title = "Step $i",
                description = "Work $i",
                assignedAgentId = "dev",
                status = if (i < 20) StepStatus.COMPLETED else StepStatus.READY,
                outputSummary = "Historical output $i",
                dependencies = if (i == 20) listOf("st-19") else emptyList(),
            )
        }
        val ctx = TaskContextBuilder.buildStepContext(project, task, steps.last(), steps, null)
        assertTrue(ctx.contains("Step 19"))
        assertTrue(ctx.contains("Step 20"))
        assertFalse(ctx.contains("- #1 Step 1:")) // Old irrelevant step excluded!
    }

    // TEST 23 — Budget enforcement prevents additional execution
    @Test
    fun test23_budgetEnforcementStopsAdditionalExecution() = runBlocking {
        val (store, project, _, orch) = setup(
            "p2-23",
            adapter = object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput) = StepExecutionOutput(true, "Costly", actualCostUsd = 2.0)
            },
        )
        val task = orch.createTaskWithSteps(
            project.projectId, "Budget", "",
            listOf(
                TaskStepEntity(stepId = "b1", taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "agent-developer"),
                TaskStepEntity(stepId = "b2", taskId = "", sequenceNumber = 2, title = "S2", description = "", assignedAgentId = "agent-developer", dependencies = listOf("b1")),
            ),
            budget = BudgetConfiguration(maxTaskBudgetUsd = 1.5, autoPauseOnBudgetExceeded = true),
        )
        val paused = orch.runTaskLoop(task.taskId)
        assertEquals(PersistentTaskStatus.PAUSED, paused?.status)
        assertEquals(StepStatus.PENDING, store.getStep("b2")?.status)
    }

    // TEST 24 — Concurrent state updates stay consistent under transactions
    @Test
    fun test24_concurrentStateUpdatesConsistent() {
        val (store, project, _, orch) = setup("p2-24")
        val task = orch.createTaskWithSteps(project.projectId, "Txn", "", listOf(TaskStepEntity(stepId = "x1", taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "dev")))
        val facade = OrqenPersistenceFacade(store)
        try {
            store.runInTransaction {
                facade.updateTaskStatus(task.taskId, PersistentTaskStatus.RUNNING)
                throw RuntimeException("Simulated concurrent conflict")
            }
        } catch (_: RuntimeException) { }
        assertEquals(PersistentTaskStatus.QUEUED, store.getTask(task.taskId)?.status)
    }

    // TEST 25 — App process interruption reconstructs task state safely
    @Test
    fun test25_appProcessInterruptionReconstructsState() = runBlocking {
        val (store, project, _, orch) = setup("p2-25")
        val task = TaskEntity(taskId = "T-INT", projectId = project.projectId, title = "Interrupted", description = "", status = PersistentTaskStatus.RUNNING, lastHeartbeatAt = 1L)
        store.upsertTask(task)
        store.upsertStep(TaskStepEntity(stepId = "i1", taskId = task.taskId, sequenceNumber = 1, title = "Done", description = "", assignedAgentId = "agent-developer", status = StepStatus.COMPLETED))
        store.upsertStep(TaskStepEntity(stepId = "i2", taskId = task.taskId, sequenceNumber = 2, title = "Mid-run", description = "", assignedAgentId = "agent-developer", status = StepStatus.RUNNING, dependencies = listOf("i1")))

        val supervisor = ExecutionRecoverySupervisor(store, CheckpointManager(store), FileLockManager())
        val report = supervisor.reconcileOnStartup(staleThresholdMs = 1L)
        assertTrue(task.taskId in report.recoveredTaskIds)
        assertEquals(PersistentTaskStatus.COMPLETED, orch.runTaskLoop(task.taskId)?.status)
    }

    // TEST 26 — Workspace safety: recovery never overwrites unrelated user modifications
    @Test
    fun test26_workspaceSafetyPreservesUserModifications() {
        val (store, project, dir, _) = setup("p2-26")
        val chkMgr = CheckpointManager(store)
        val userFile = File(dir, "user_notes.txt").apply { writeText("User edited content — DO NOT OVERWRITE") }
        val task = TaskEntity(projectId = project.projectId, title = "Safe", description = "", status = PersistentTaskStatus.RUNNING, lastHeartbeatAt = 1L)
        store.upsertTask(task)
        chkMgr.createCheckpoint(task.taskId, "s1", dir, emptyList(), "Baseline")
        userFile.writeText("User edited content — NEWER")

        val supervisor = ExecutionRecoverySupervisor(store, chkMgr, FileLockManager())
        supervisor.reconcileOnStartup(staleThresholdMs = 1L)
        assertEquals("User edited content — NEWER", userFile.readText())
    }

    // TEST 27 — Legacy TaskManager integration remains functional
    @Test
    fun test27_legacyTaskManagerIntegrationFunctional() {
        val (store, _, _, _) = setup("p2-27")
        val tm = TaskManager(durableStore = store, projectId = "legacy-p27")
        tm.addTask(TeamTask(id = "L-27", title = "Existing UI Task", description = "Compat"))
        tm.updateTaskStatus("L-27", TaskStatus.COMPLETED)
        assertEquals(TaskStatus.COMPLETED, tm.tasks.value.first().status)
        assertEquals(PersistentTaskStatus.COMPLETED, store.getTask("L-27")?.status)
    }
}
