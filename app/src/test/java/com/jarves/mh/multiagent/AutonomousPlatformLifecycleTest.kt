package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.host.HostTaskProgress
import com.jarves.mh.multiagent.host.LocalAndroidExecutionHost
import com.jarves.mh.multiagent.host.RemoteExecutionHost
import com.jarves.mh.multiagent.host.RemoteExecutionTransport
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.session.HierarchicalMemoryManager
import com.jarves.mh.multiagent.session.MultiSessionChatController
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production lifecycle test suite covering TEST A through TEST T
 * for ORQEN's Persistent Autonomous AI Platform.
 */
class AutonomousPlatformLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newProject(store: InMemoryOrqenTaskStore, id: String = "proj-velnox"): Pair<ProjectEntity, File> {
        val dir = tempFolder.newFolder(id)
        val project = ProjectEntity(projectId = id, projectName = "Velnox", workspacePath = dir.absolutePath)
        store.upsertProject(project)
        return project to dir
    }

    private fun newOrchestrator(
        store: InMemoryOrqenTaskStore,
        adapter: StepExecutionAdapter,
        policy: RequestExecutionPolicy = RequestExecutionPolicy(
            initialBackoffMs = 1L,
            maxBackoffMs = 50L,
            maxRetriesPerStep = 3,
        ),
        recordedDelays: MutableList<Long>? = null,
    ): Triple<PersistentTaskOrchestrator, ExecutionRecoverySupervisor, CheckpointManager> {
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
        return Triple(orch, supervisor, checkpointMgr)
    }

    // TEST A — Long-running task: 55 consecutive model execution steps (no fixed step cap)
    @Test
    fun testA_longRunningTask55ConsecutiveSteps() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-a")
        val executedCount = AtomicInteger(0)
        val (orch, _, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    executedCount.incrementAndGet()
                    return StepExecutionOutput(
                        success = true,
                        summary = "Completed step ${input.step.sequenceNumber}",
                        inputTokens = 100,
                        outputTokens = 50,
                        actualCostUsd = 0.001,
                    )
                }
            },
        )
        val steps = (1..55).map { i ->
            TaskStepEntity(
                stepId = "s-$i",
                taskId = "",
                sequenceNumber = i,
                title = "Step $i",
                description = "Execute work unit $i",
                assignedAgentId = "agent-developer",
                dependencies = if (i == 1) emptyList() else listOf("s-${i - 1}"),
            )
        }
        val task = orch.createTaskWithSteps(project.projectId, "55-Step Platform Build", "Build full system", steps)
        val finished = orch.runTaskLoop(task.taskId)

        assertEquals(PersistentTaskStatus.COMPLETED, finished?.status)
        assertEquals(55, executedCount.get())
        assertEquals(55, store.listCheckpointsForTask(task.taskId).size)
    }

    // TEST B — Model timeout during step 12: earlier steps remain completed and step 12 recovers
    @Test
    fun testB_modelTimeoutAtStep12PreservesPriorStepsAndRecovers() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-b")
        val step12Attempts = AtomicInteger(0)
        val (orch, _, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    if (input.step.sequenceNumber == 12 && step12Attempts.incrementAndGet() == 1) {
                        return StepExecutionOutput(
                            success = false,
                            summary = "",
                            error = StructuredErrorClassifier.classify("Timed out waiting for response", isTimeout = true),
                        )
                    }
                    return StepExecutionOutput(success = true, summary = "OK #${input.step.sequenceNumber}")
                }
            },
        )
        val steps = (1..15).map { i ->
            TaskStepEntity(
                stepId = "b-$i",
                taskId = "",
                sequenceNumber = i,
                title = "Step $i",
                description = "Work $i",
                assignedAgentId = "agent-developer",
                dependencies = if (i == 1) emptyList() else listOf("b-${i - 1}"),
            )
        }
        val task = orch.createTaskWithSteps(project.projectId, "Timeout Recovery", "15 steps", steps)
        val finished = orch.runTaskLoop(task.taskId)

        assertEquals(PersistentTaskStatus.COMPLETED, finished?.status)
        assertEquals(2, step12Attempts.get())
        assertTrue(store.listStepsForTask(task.taskId).all { it.status == StepStatus.COMPLETED })
    }

    // TEST C — CLI crash: supervisor identifies failure and safely resumes
    @Test
    fun testC_cliCrashClassifiedAndRecovered() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-c")
        val attempts = AtomicInteger(0)
        val (orch, _, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    if (attempts.incrementAndGet() == 1) {
                        val err = StructuredErrorClassifier.classify("CLI process killed (SIGKILL)", exitCode = 137)
                        assertEquals(ExecutionErrorCategory.CLI_CRASH, err.category)
                        return StepExecutionOutput(success = false, summary = "", error = err)
                    }
                    return StepExecutionOutput(success = true, summary = "Recovered after CLI crash")
                }
            },
        )
        val task = orch.createTaskWithSteps(
            project.projectId,
            "CLI Crash Test",
            "Survive SIGKILL",
            listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "Run CLI", description = "Build", assignedAgentId = "agent-developer")),
        )
        val finished = orch.runTaskLoop(task.taskId)
        assertEquals(PersistentTaskStatus.COMPLETED, finished?.status)
        assertEquals(2, attempts.get())
    }

    // TEST D — Partial file write: recovery detects inconsistent workspace
    @Test
    fun testD_partialFileWriteDetectedOnRecovery() {
        val store = InMemoryOrqenTaskStore()
        val (project, dir) = newProject(store, "proj-d")
        val checkpointMgr = CheckpointManager(store)
        val supervisor = ExecutionRecoverySupervisor(store, checkpointMgr, FileLockManager())

        val task = TaskEntity(
            projectId = project.projectId,
            title = "Write Critical File",
            description = "Update config",
            status = PersistentTaskStatus.RUNNING,
            lastHeartbeatAt = System.currentTimeMillis() - 60_000L,
        )
        store.upsertTask(task)
        File(dir, "config.json").writeText("""{"ok":true}""")
        checkpointMgr.createCheckpoint(task.taskId, "step-1", dir, listOf("config.json"), "Initial")

        // Simulate crash mid-write leaving a partial temp file
        File(dir, "config.json.partial").writeText("""{"ok":""")

        val report = supervisor.reconcileOnStartup(staleThresholdMs = 1_000L)
        assertTrue(task.taskId in report.blockedTaskIds)
        assertEquals(PersistentTaskStatus.BLOCKED, store.getTask(task.taskId)?.status)
        assertTrue(report.workspaceWarnings[task.taskId]?.contains("partial", ignoreCase = true) == true)
    }

    // TEST E — Close chat: closing/deleting conversation while task runs does NOT stop the task
    @Test
    fun testE_closingOrDeletingChatDoesNotCancelTask() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-e")
        val chatCtrl = MultiSessionChatController(store)
        val convA = chatCtrl.createConversation(project.projectId, "Chat A")

        val (orch, _, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    delay(20L)
                    return StepExecutionOutput(success = true, summary = "Done ${input.step.sequenceNumber}")
                }
            },
        )
        val task = orch.createTaskWithSteps(
            project.projectId,
            "Background Task",
            "Keep running",
            listOf(
                TaskStepEntity(stepId = "e1", taskId = "", sequenceNumber = 1, title = "S1", description = "D1", assignedAgentId = "agent-developer"),
                TaskStepEntity(stepId = "e2", taskId = "", sequenceNumber = 2, title = "S2", description = "D2", assignedAgentId = "agent-developer", dependencies = listOf("e1")),
            ),
        )
        chatCtrl.attachExistingTask(convA.conversationId, task.taskId)

        val job = orch.launchTask(this, task.taskId)
        // Close and delete Chat A while task is running
        chatCtrl.archiveConversation(convA.conversationId)
        chatCtrl.deleteConversationPreservingTasks(convA.conversationId)
        job.join()

        assertNull(store.getConversation(convA.conversationId))
        assertEquals(PersistentTaskStatus.COMPLETED, store.getTask(task.taskId)?.status)
    }

    // TEST F — New conversation: attach existing task ("Continue Existing Task") hydrates full context
    @Test
    fun testF_newConversationAttachesExistingTaskWithFullContext() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (project, dir) = newProject(store, "proj-f")
        val chatCtrl = MultiSessionChatController(store)
        val memoryMgr = HierarchicalMemoryManager(store)
        memoryMgr.updateProjectMemory(project.projectId, "Architecture: Clean SQLite WAL + Kotlin Coroutines")

        val (orch, _, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    File(dir, "Backend.kt").writeText("class Backend")
                    return StepExecutionOutput(success = true, summary = "Implemented Backend.kt", modifiedFiles = listOf("Backend.kt"))
                }
            },
        )
        val task = orch.createTaskWithSteps(
            project.projectId,
            "Implement Velnox Backend",
            "Build API + storage",
            listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "Backend", description = "Code", assignedAgentId = "agent-developer")),
        )
        orch.runTaskLoop(task.taskId)
        store.insertAgentMessage(
            AgentMessageEntity(
                taskId = task.taskId,
                fromAgentId = "agent-leader",
                toAgentId = "agent-developer",
                messageType = AgentMessageKind.SHARED_DECISION,
                content = "Use SHA-256 checkpoints",
            ),
        )

        val convC = chatCtrl.createConversation(project.projectId, "Chat C (Next Day)")
        val snapshot = chatCtrl.attachExistingTask(convC.conversationId, task.taskId)

        assertNotNull(snapshot)
        assertEquals(task.taskId, snapshot!!.task.taskId)
        assertEquals(1, snapshot.completedSteps.size)
        assertTrue("Backend.kt" in snapshot.changedFiles)
        assertNotNull(snapshot.latestCheckpoint)
        assertEquals(1, snapshot.recentDecisions.size)
        assertTrue(snapshot.projectMemory.contains("Clean SQLite WAL"))
    }

    // TEST G — Application process death: stale RUNNING task & step recover on relaunch
    @Test
    fun testG_processDeathReconcilesStaleRunningTaskAndCompletes() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-g")
        val task = TaskEntity(
            taskId = "T-CRASH",
            projectId = project.projectId,
            title = "Crash Recovery Task",
            description = "Survive process death",
            status = PersistentTaskStatus.RUNNING,
            lastHeartbeatAt = System.currentTimeMillis() - 120_000L,
        )
        store.upsertTask(task)
        store.upsertStep(TaskStepEntity(stepId = "g1", taskId = task.taskId, sequenceNumber = 1, title = "Done", description = "", assignedAgentId = "agent-developer", status = StepStatus.COMPLETED))
        store.upsertStep(TaskStepEntity(stepId = "g2", taskId = task.taskId, sequenceNumber = 2, title = "Interrupted", description = "", assignedAgentId = "agent-developer", status = StepStatus.RUNNING, dependencies = listOf("g1")))

        val executed = mutableListOf<String>()
        val (orch, supervisor, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    executed.add(input.step.stepId)
                    return StepExecutionOutput(success = true, summary = "Finished ${input.step.stepId}")
                }
            },
        )
        val report = supervisor.reconcileOnStartup(staleThresholdMs = 30_000L)
        assertTrue(task.taskId in report.recoveredTaskIds)

        val finalTask = orch.runTaskLoop(task.taskId)
        assertEquals(PersistentTaskStatus.COMPLETED, finalTask?.status)
        assertEquals(listOf("g2"), executed) // g1 was NOT re-executed!
    }

    // TEST H — Device reboot: eligible tasks recover while PAUSED/CANCELLED stay untouched
    @Test
    fun testH_deviceRebootRecoversOnlyEligibleTasks() {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-h")
        val tRunning = TaskEntity(taskId = "T-RUN", projectId = project.projectId, title = "Run", description = "", status = PersistentTaskStatus.RUNNING, lastHeartbeatAt = 1L)
        val tPaused = TaskEntity(taskId = "T-PAUSE", projectId = project.projectId, title = "Pause", description = "", status = PersistentTaskStatus.PAUSED, pauseRequested = true)
        val tCancelled = TaskEntity(taskId = "T-STOP", projectId = project.projectId, title = "Stop", description = "", status = PersistentTaskStatus.CANCELLED, ownerStopRequested = true)
        store.upsertTask(tRunning)
        store.upsertTask(tPaused)
        store.upsertTask(tCancelled)

        val supervisor = ExecutionRecoverySupervisor(store, CheckpointManager(store), FileLockManager())
        val report = supervisor.reconcileOnStartup(staleThresholdMs = 1_000L)

        assertEquals(listOf("T-RUN"), report.recoveredTaskIds)
        assertEquals(listOf("T-STOP"), report.skippedCancelledTaskIds)
        assertEquals(PersistentTaskStatus.PAUSED, store.getTask("T-PAUSE")?.status)
        assertEquals(PersistentTaskStatus.CANCELLED, store.getTask("T-STOP")?.status)
    }

    // TEST I — Owner cancellation: cancelled task NEVER auto-retries or auto-resumes
    @Test
    fun testI_ownerCancellationPreventsAutoResume() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-i")
        val (orch, supervisor, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput) = StepExecutionOutput(true, "OK")
            },
        )
        val task = orch.createTaskWithSteps(
            project.projectId, "Cancellable", "Stop me",
            listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "agent-developer")),
        )
        orch.stopTask(task.taskId, "Owner clicked Stop")

        assertEquals(PersistentTaskStatus.CANCELLED, store.getTask(task.taskId)?.status)
        assertTrue(store.getTask(task.taskId)?.ownerStopRequested == true)
        assertNull(orch.resumeTask(this, task.taskId))
        assertTrue(task.taskId in supervisor.reconcileOnStartup().skippedCancelledTaskIds)
    }

    // TEST J — Provider rate limit: HTTP 429 + Retry-After respected
    @Test
    fun testJ_providerRateLimitRespectsRetryAfter() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-j")
        val recordedDelays = mutableListOf<Long>()
        val attempts = AtomicInteger(0)
        val (orch, _, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    if (attempts.incrementAndGet() == 1) {
                        return StepExecutionOutput(
                            success = false,
                            summary = "",
                            error = StructuredErrorClassifier.classify("HTTP 429 Too Many Requests. Retry-After: 5", httpStatusCode = 429),
                        )
                    }
                    return StepExecutionOutput(success = true, summary = "OK after backoff")
                }
            },
            policy = RequestExecutionPolicy(initialBackoffMs = 100L, maxBackoffMs = 10_000L, respectRetryAfter = true),
            recordedDelays = recordedDelays,
        )
        val task = orch.createTaskWithSteps(
            project.projectId, "429 Test", "Rate limit",
            listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "Call API", description = "", assignedAgentId = "agent-developer")),
        )
        val finished = orch.runTaskLoop(task.taskId)
        assertEquals(PersistentTaskStatus.COMPLETED, finished?.status)
        assertEquals(listOf(5_000L), recordedDelays)
    }

    // TEST K — Provider authentication failure: HTTP 401 does NOT retry indefinitely
    @Test
    fun testK_providerAuthFailureStopsImmediatelyWithoutRetry() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-k")
        val attempts = AtomicInteger(0)
        val (orch, _, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    attempts.incrementAndGet()
                    return StepExecutionOutput(
                        success = false,
                        summary = "",
                        error = StructuredErrorClassifier.classify("HTTP 401 Unauthorized: invalid_api_key", httpStatusCode = 401),
                    )
                }
            },
        )
        val task = orch.createTaskWithSteps(
            project.projectId, "401 Test", "Invalid key",
            listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "Call API", description = "", assignedAgentId = "agent-developer")),
        )
        val finished = orch.runTaskLoop(task.taskId)
        assertEquals(PersistentTaskStatus.BLOCKED, finished?.status)
        assertEquals(1, attempts.get())
    }

    // TEST L — Context overflow: automatic compaction preserves decisions & file paths
    @Test
    fun testL_contextOverflowCompactsPreservingDecisionsAndFiles() {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-l")
        val chatCtrl = MultiSessionChatController(store)
        val memoryMgr = HierarchicalMemoryManager(store)
        val conv = chatCtrl.createConversation(project.projectId, "Long Chat")

        chatCtrl.postMessage(conv.conversationId, project.projectId, "u", "Boss", true, "Decision: must use SQLite WAL in SqliteOrqenTaskDatabase.kt")
        repeat(25) { i ->
            chatCtrl.postMessage(conv.conversationId, project.projectId, "a", "Agent 01", false, "Verbose log output #$i " + "x".repeat(400))
        }

        val res = memoryMgr.compactConversationIfNeeded(conv.conversationId, maxContextTokens = 500, preserveRecentMessages = 4)
        assertTrue(res.wasCompacted)
        assertTrue(res.compactedEstimatedTokens < res.originalEstimatedTokens)
        assertTrue(res.preservedDecisions.any { it.contains("SQLite WAL") })
        assertTrue("SqliteOrqenTaskDatabase.kt" in res.preservedFilePaths)
        assertTrue(store.getConversation(conv.conversationId)?.contextSummary?.contains("SQLite WAL") == true)
    }

    // TEST M — Multiple agents: 3 agents collaborate with dependencies and file locks
    @Test
    fun testM_multipleAgentsCollaborateWithDependenciesAndLocks() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-m")
        val order = mutableListOf<String>()
        val (orch, _, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    order.add("${input.agent.agentId}:${input.step.stepId}")
                    return StepExecutionOutput(success = true, summary = "Done by ${input.agent.name}")
                }
            },
        )
        val steps = listOf(
            TaskStepEntity(stepId = "plan", taskId = "", sequenceNumber = 1, title = "Plan", description = "", assignedAgentId = "agent-leader", targetFiles = listOf("PLAN.md")),
            TaskStepEntity(stepId = "code", taskId = "", sequenceNumber = 2, title = "Code", description = "", assignedAgentId = "agent-developer", dependencies = listOf("plan"), targetFiles = listOf("App.kt")),
            TaskStepEntity(stepId = "qa", taskId = "", sequenceNumber = 3, title = "Review", description = "", assignedAgentId = "agent-reviewer", dependencies = listOf("code"), targetFiles = listOf("App.kt"), requiresReview = true),
        )
        val task = orch.createTaskWithSteps(project.projectId, "3-Agent Flow", "Collaborate", steps)
        val finished = orch.runTaskLoop(task.taskId)

        assertEquals(PersistentTaskStatus.COMPLETED, finished?.status)
        assertEquals(listOf("agent-leader:plan", "agent-developer:code", "agent-reviewer:qa"), order)
    }

    // TEST N — Concurrent tasks: isolated state, context, and workspaces
    @Test
    fun testN_concurrentTasksIsolatedWorkspaces() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (p1, d1) = newProject(store, "proj-n1")
        val (p2, d2) = newProject(store, "proj-n2")
        val (orch, _, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    val fileName = "${input.task.projectId}.txt"
                    File(input.workspaceDir, fileName).writeText(input.task.taskId)
                    delay(15L)
                    return StepExecutionOutput(success = true, summary = "Wrote $fileName", modifiedFiles = listOf(fileName))
                }
            },
        )
        val t1 = orch.createTaskWithSteps(p1.projectId, "Task 1", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "W1", description = "", assignedAgentId = "agent-developer")))
        val t2 = orch.createTaskWithSteps(p2.projectId, "Task 2", "", listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "W2", description = "", assignedAgentId = "agent-integrator")))

        val dA = async { orch.runTaskLoop(t1.taskId) }
        val dB = async { orch.runTaskLoop(t2.taskId) }
        assertEquals(PersistentTaskStatus.COMPLETED, dA.await()?.status)
        assertEquals(PersistentTaskStatus.COMPLETED, dB.await()?.status)
        assertTrue(File(d1, "proj-n1.txt").isFile)
        assertFalse(File(d1, "proj-n2.txt").exists())
        assertTrue(File(d2, "proj-n2.txt").isFile)
        assertFalse(File(d2, "proj-n1.txt").exists())
    }

    // TEST O — Quality gate: reviewer rejects failing work -> dev fixes -> re-review passes
    @Test
    fun testO_qualityGateRejectionSchedulesCorrectiveFixAndReReview() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-o")
        val reviewCalls = AtomicInteger(0)
        val (orch, _, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    if (input.step.requiresReview) {
                        val call = reviewCalls.incrementAndGet()
                        return if (call == 1) {
                            StepExecutionOutput(
                                success = true,
                                summary = "Unit test failed in AuthService",
                                reviewApproved = false,
                                reviewFeedback = "Fix NPE in AuthService.kt line 42",
                            )
                        } else {
                            StepExecutionOutput(success = true, summary = "All tests PASS", reviewApproved = true)
                        }
                    }
                    return StepExecutionOutput(success = true, summary = "Fixed AuthService.kt")
                }
            },
        )
        val task = orch.createTaskWithSteps(
            project.projectId,
            "Quality Gate",
            "Verify & fix",
            listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "QA Review", description = "Run tests", assignedAgentId = "agent-reviewer", requiresReview = true)),
        )
        val finished = orch.runTaskLoop(task.taskId)

        assertEquals(PersistentTaskStatus.COMPLETED, finished?.status)
        assertEquals(2, reviewCalls.get())
        assertEquals(3, store.listStepsForTask(task.taskId).size) // original review + dynamic fix + re-review
        assertTrue(store.listAgentMessagesForTask(task.taskId).any { it.messageType == AgentMessageKind.CHANGE_REQUEST })
    }

    // TEST P — Budget protection: pauses safely when maxTaskBudgetUsd is reached
    @Test
    fun testP_budgetProtectionPausesSafely() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-p")
        val (orch, _, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput) = StepExecutionOutput(
                    success = true,
                    summary = "Expensive step",
                    actualCostUsd = 1.50,
                )
            },
        )
        val task = orch.createTaskWithSteps(
            project.projectId,
            "Budget Test",
            "Cap at $1.00",
            listOf(
                TaskStepEntity(stepId = "p1", taskId = "", sequenceNumber = 1, title = "S1", description = "", assignedAgentId = "agent-developer"),
                TaskStepEntity(stepId = "p2", taskId = "", sequenceNumber = 2, title = "S2", description = "", assignedAgentId = "agent-developer", dependencies = listOf("p1")),
            ),
            budget = BudgetConfiguration(maxTaskBudgetUsd = 1.00, autoPauseOnBudgetExceeded = true),
        )
        val paused = orch.runTaskLoop(task.taskId)
        assertEquals(PersistentTaskStatus.PAUSED, paused?.status)
        assertTrue(paused?.pauseRequested == true)
        assertEquals(StepStatus.COMPLETED, store.getStep("p1")?.status)
        assertEquals(StepStatus.PENDING, store.getStep("p2")?.status)
    }

    // TEST Q — Remote disconnection & reconnection preserves exact taskId and progress
    @Test
    fun testQ_remoteDisconnectAndReconnectPreservesTaskIdAndProgress() {
        var remoteCompleted = 3
        val fakeServer = object : RemoteExecutionTransport {
            override fun submit(taskId: String) = true
            override fun pause(taskId: String, reason: String) = Unit
            override fun resume(taskId: String) = true
            override fun stop(taskId: String, reason: String) = Unit
            override fun stopAll(reason: String) = 1
            override fun ping() = true
            override fun fetchProgress(taskId: String) = HostTaskProgress(
                taskId = taskId,
                status = PersistentTaskStatus.RUNNING,
                completedSteps = remoteCompleted,
                totalSteps = 10,
                currentStepTitle = "Step ${remoteCompleted + 1}",
                actualCostUsd = 0.25,
                lastError = null,
            )
        }
        val remoteHost = RemoteExecutionHost("remote-eu-1", fakeServer)
        val before = remoteHost.queryTaskProgress("T-REMOTE-99")
        assertEquals(3, before?.completedSteps)

        remoteHost.disconnect()
        assertFalse(remoteHost.isConnected)
        assertNull(remoteHost.queryTaskProgress("T-REMOTE-99"))

        // Remote worker keeps progressing while phone is disconnected
        remoteCompleted = 8
        assertTrue(remoteHost.reconnect())
        val after = remoteHost.queryTaskProgress("T-REMOTE-99")
        assertEquals("T-REMOTE-99", after?.taskId)
        assertEquals(8, after?.completedSteps)
    }

    // TEST R — Duplicate execution replay: idempotency ledger prevents duplicate side effect
    @Test
    fun testR_duplicateExecutionPreventedByIdempotencyKey() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-r")
        val sideEffectCount = AtomicInteger(0)
        val (orch, _, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    sideEffectCount.incrementAndGet()
                    return StepExecutionOutput(success = true, summary = "Executed once")
                }
            },
        )
        val step = TaskStepEntity(
            stepId = "r1",
            taskId = "",
            sequenceNumber = 1,
            title = "Deploy Migration",
            description = "Idempotent op",
            assignedAgentId = "agent-developer",
            idempotencyKey = "idem-unique-deploy-001",
        )
        val task = orch.createTaskWithSteps(project.projectId, "Idempotency Test", "", listOf(step))
        orch.runTaskLoop(task.taskId)
        assertEquals(1, sideEffectCount.get())

        // Simulate a recovery replay resetting the step to READY + task to QUEUED
        store.upsertStep(store.getStep("r1")!!.copy(status = StepStatus.READY))
        store.upsertTask(store.getTask(task.taskId)!!.copy(status = PersistentTaskStatus.QUEUED))
        orch.runTaskLoop(task.taskId)

        assertEquals(1, sideEffectCount.get()) // Side effect NOT executed a second time!
        assertTrue(store.listTaskEvents(task.taskId).any { it.eventType == "IDEMPOTENCY_SKIP" })
    }

    // TEST S — Stop All: stops multiple active/queued tasks safely
    @Test
    fun testS_stopAllCancelsMultipleTasks() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val (project, _) = newProject(store, "proj-s")
        val (orch, _, _) = newOrchestrator(
            store,
            object : StepExecutionAdapter {
                override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
                    delay(500L)
                    return StepExecutionOutput(true, "Done")
                }
            },
        )
        val host = LocalAndroidExecutionHost(store, orch)
        val ids = (1..3).map { i ->
            orch.createTaskWithSteps(
                project.projectId,
                "Task $i",
                "",
                listOf(TaskStepEntity(taskId = "", sequenceNumber = 1, title = "Long $i", description = "", assignedAgentId = "agent-developer")),
            ).taskId
        }
        ids.forEach { host.submitTask(this, it) }
        kotlinx.coroutines.delay(50L)
        val stopped = host.stopAllTasks("Owner emergency stop")

        assertEquals(3, stopped)
        ids.forEach { id ->
            val t = store.getTask(id)
            assertEquals(PersistentTaskStatus.CANCELLED, t?.status)
            assertTrue(t?.ownerStopRequested == true)
        }
    }

    // TEST T — Database migration v1 -> v2 preserves active and completed tasks
    @Test
    fun testT_databaseMigrationPreservesAllTasks() {
        val store = InMemoryOrqenTaskStore(initialSchemaVersion = 1)
        val (project, _) = newProject(store, "proj-t")
        val activeTask = TaskEntity(taskId = "T-ACTIVE", projectId = project.projectId, title = "Active", description = "", status = PersistentTaskStatus.RUNNING)
        val doneTask = TaskEntity(taskId = "T-DONE", projectId = project.projectId, title = "Done", description = "", status = PersistentTaskStatus.COMPLETED)
        store.upsertTask(activeTask)
        store.upsertTask(doneTask)

        assertEquals(1, store.schemaVersion)
        val newVer = store.migrateToLatest()

        assertEquals(InMemoryOrqenTaskStore.CURRENT_SCHEMA_VERSION, newVer)
        assertTrue(store.validateIntegrity())
        assertEquals(PersistentTaskStatus.RUNNING, store.getTask("T-ACTIVE")?.status)
        assertEquals(PersistentTaskStatus.COMPLETED, store.getTask("T-DONE")?.status)
        assertTrue((store.getTask("T-ACTIVE")?.lastHeartbeatAt ?: 0L) > 0L)
    }
}
