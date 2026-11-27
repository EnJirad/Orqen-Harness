package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.recovery.ExecutionLivenessMonitor
import com.jarves.mh.multiagent.supervisor.*
import com.jarves.mh.runtime.TestExecutionEvidence
import com.jarves.mh.runtime.TestExecutionOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression test suite for the two critical P0 fixes:
 * 1. Unified AI Team Execution & Automatic Agent Handoff (SPV -> Developer -> Reviewer/QA -> COMPLETED)
 * 2. Empty Task Steps (`ON DELETE CASCADE` via SQLite `CONFLICT_REPLACE`), Missing Reviewer Approval,
 *    and Missing Real Test Execution Evidence (incident T-6A6806A1).
 */
class P0HandoffAndPersistenceRegressionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newProject(store: OrqenTaskStore, id: String): Pair<ProjectEntity, File> {
        val dir = tmp.newFolder("ws-$id")
        val p = ProjectEntity(
            projectId = id,
            projectName = "Project $id",
            workspacePath = dir.absolutePath,
            projectMemory = "Autonomous Kotlin/Android project",
        )
        store.upsertProject(p)
        return p to dir
    }

    private fun registerStandardTeam(engine: AutonomousSupervisorEngine, projectId: String): AgentTeamEntity {
        val spv = engine.registry.register(
            AgentProfileEntity(
                agentId = "agent-01",
                displayName = "SPV",
                role = "Supervisor & Planner",
                providerId = "provider-primary",
                modelId = "model-spv",
                capabilities = setOf("lead", "architect", "plan", "code"),
                priority = 10,
            ),
        )
        val dev = engine.registry.register(
            AgentProfileEntity(
                agentId = "agent-02",
                displayName = "Developer",
                role = "Software Developer",
                providerId = "provider-primary",
                modelId = "model-dev",
                capabilities = setOf("code", "fix"),
                priority = 9,
            ),
        )
        val qa = engine.registry.register(
            AgentProfileEntity(
                agentId = "agent-03",
                displayName = "QA Reviewer",
                role = "Reviewer & Tester",
                providerId = "provider-qa",
                modelId = "model-qa",
                capabilities = setOf("review", "qa", "test"),
                priority = 8,
            ),
        )
        return engine.teamManager.createTeam(
            projectId = projectId,
            teamName = "ORQEN AI Team",
            memberAgentIds = listOf(spv.agentId, dev.agentId, qa.agentId),
            requireIndependentReview = true,
        )
    }

    private fun fakeRuntime(
        handler: suspend (RuntimeExecutionRequest) -> RuntimeExecutionResponse,
    ): AIExecutionRuntime = object : AIExecutionRuntime {
        override suspend fun execute(
            request: RuntimeExecutionRequest,
            onProgress: suspend (String) -> Unit,
        ): RuntimeExecutionResponse = handler(request)

        override fun cancel(requestId: String): Boolean = true
        override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, false, false)
    }

    private fun passingTestEvidence(cmd: String = "./gradlew testDebugUnitTest") = listOf(
        TestExecutionEvidence(
            command = cmd,
            startedAtMs = 0L,
            completedAtMs = 50L,
            exitCode = 0,
            stdout = "BUILD SUCCESSFUL",
            stderr = "",
            outcome = TestExecutionOutcome.TESTS_PASSED,
            summary = "12 passed, 0 failed",
            passedCount = 12,
            failedCount = 0,
        ),
    )

    // --- 1. Planning automatically assigns Step 1 to Developer (NEVER SPV), even for large 3-Part objectives ---
    @Test
    fun test01_LargeMultiPartPlanAssignsAllImplementationStepsToDeveloperNeverSpv() {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, _) = newProject(store, "p-spv-dev")
        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            fileLockManager = FileLockManager(),
            livenessMonitor = ExecutionLivenessMonitor(store),
            runtime = fakeRuntime { req ->
                RuntimeExecutionResponse(req.requestId, "p", "m", 0L, 1L, true, "ok")
            },
            delayFn = {},
        )
        val team = registerStandardTeam(engine, proj.projectId)

        // Substantial objective triggers 3-Part breakdown (6 steps: 3 impl + 3 review)
        val longObjective = "Build a complete offline-first budget tracker application with full persistence, " +
            "category analytics dashboard, CSV export, unit tests, and clean architecture across multiple screens."
        val task = engine.preparePlan(
            projectId = proj.projectId,
            team = team,
            objective = longObjective,
        )
        assertNotNull(task)
        val steps = store.listStepsForTask(task!!.taskId)
        assertTrue("Expected multi-part steps (>= 6), got ${steps.size}", steps.size >= 6)

        val implSteps = steps.filter { !it.requiresReview }
        val reviewSteps = steps.filter { it.requiresReview }
        assertTrue("Expected implementation steps", implSteps.isNotEmpty())
        assertTrue("Expected review steps", reviewSteps.isNotEmpty())

        // CRITICAL: Step 1 and EVERY implementation step must be assigned to Developer (agent-02), NEVER SPV (agent-01)!
        assertEquals("agent-02", steps.first().assignedAgentId)
        assertTrue(
            "SPV (agent-01) must never be assigned implementation steps when Developer is enabled: ${implSteps.map { it.assignedAgentId }}",
            implSteps.none { it.assignedAgentId == "agent-01" },
        )
        // Review steps must be assigned to independent reviewer (agent-03), never the implementing Developer
        assertTrue(
            "Review steps must be assigned to QA Reviewer (agent-03): ${reviewSteps.map { it.assignedAgentId }}",
            reviewSteps.all { it.assignedAgentId == "agent-03" },
        )
    }

    // --- 2. End-to-End Automatic Handoff: Planning -> Developer -> QA Reviewer (with test evidence) -> COMPLETED ---
    @Test
    fun test02_AutomaticHandoffContinuesToVerifiedCompletedWithoutDuplicateStep1OrRevertingToPlanning() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "p-e2e-handoff")
        val lifecycleEvents = CopyOnWriteArrayList<StepLifecycleUpdate>()
        val agentCallOrder = CopyOnWriteArrayList<String>()

        val runtime = fakeRuntime { req ->
            val agentId = req.agent.config.slot.id
            agentCallOrder.add(agentId)
            if (agentId == "agent-02") {
                val srcFile = File(wsDir, "src/Feature.kt").apply { parentFile?.mkdirs() }
                srcFile.writeText("package demo\nfun greet(): String = \"hello\"\n")
                RuntimeExecutionResponse(
                    requestId = req.requestId,
                    providerId = req.agent.config.providerId,
                    modelId = req.agent.config.modelId,
                    startedAtMs = 0L,
                    completedAtMs = 10L,
                    success = true,
                    outputText = "Implemented Feature.kt",
                    modifiedFiles = listOf("src/Feature.kt"),
                )
            } else {
                RuntimeExecutionResponse(
                    requestId = req.requestId,
                    providerId = req.agent.config.providerId,
                    modelId = req.agent.config.modelId,
                    startedAtMs = 0L,
                    completedAtMs = 10L,
                    success = true,
                    outputText = "REVIEW_APPROVED\nTESTS_PASSED: Feature.kt verified and tests pass.",
                    testEvidence = passingTestEvidence(),
                )
            }
        }

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            fileLockManager = FileLockManager(),
            livenessMonitor = ExecutionLivenessMonitor(store),
            runtime = runtime,
            onStepLifecycleUpdate = { lifecycleEvents.add(it) },
            delayFn = {},
        )
        val team = registerStandardTeam(engine, proj.projectId)

        val finalTask = engine.submitAndSupervise(
            projectId = proj.projectId,
            team = team,
            objective = "Implement greet feature",
            requiredDeliverableFiles = listOf("src/Feature.kt"),
        )

        assertNotNull(finalTask)
        assertEquals(PersistentTaskStatus.COMPLETED, finalTask!!.status)

        // Verify persisted steps, reviewer approval, and real test evidence all remain intact
        val steps = store.listStepsForTask(finalTask.taskId)
        assertTrue(steps.isNotEmpty())
        assertTrue(steps.all { it.taskId == finalTask.taskId && it.status == StepStatus.COMPLETED })

        val reviews = collab.listReviewsForTask(finalTask.taskId)
        assertTrue("Expected persisted APPROVED review", reviews.any { it.outcome == ReviewOutcome.APPROVED })

        val testEvidence = store.listVerificationEvidenceForTask(finalTask.taskId).filter { it.evidenceType == "TEST" }
        assertTrue("Expected persisted TEST evidence with exitCode=0", testEvidence.any { it.exitCode == 0 })

        // Verify NO duplicate Step 1 delegation & NO extra PLANNED events after execution started
        val plannedEvents = lifecycleEvents.filter { it.phase == "PLANNED" }
        assertEquals("PLANNED must be emitted exactly once", 1, plannedEvents.size)

        val step1Id = steps.first().stepId
        val step1StartedEvents = lifecycleEvents.filter { it.phase == "STARTED" && it.stepId == step1Id }
        assertEquals("Step 1 STARTED must be emitted exactly once", 1, step1StartedEvents.size)
        assertEquals("Step 1 must be executed by Developer (agent-02)", "agent-02", step1StartedEvents.first().agentId)
        assertEquals("First agent invoked must be Developer (agent-02)", "agent-02", agentCallOrder.first())
        assertFalse("SPV (agent-01) must not execute worker steps", "agent-01" in agentCallOrder)

        // Snapshot ends in COMPLETED
        assertEquals(SupervisorOperationalStatus.COMPLETED, engine.snapshotFor(finalTask.taskId)?.status)
    }

    // --- 3. Missing Test Evidence Strictly Blocks Completion Even When All Steps Succeed ---
    @Test
    fun test03_MissingTestEvidenceBlocksCompletionEvenWhenAllStepsSucceed() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "p-no-tests")

        val runtime = fakeRuntime { req ->
            val agentId = req.agent.config.slot.id
            if (agentId == "agent-02") {
                File(wsDir, "src/App.kt").apply { parentFile?.mkdirs(); writeText("fun main() {}") }
                RuntimeExecutionResponse(req.requestId, "p", "m", 0L, 10L, true, "Created App.kt", modifiedFiles = listOf("src/App.kt"))
            } else {
                // Reviewer approves WITHOUT running real tests (empty testEvidence!)
                RuntimeExecutionResponse(req.requestId, "p", "m", 0L, 10L, true, "REVIEW_APPROVED: Looks good", testEvidence = emptyList())
            }
        }

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            fileLockManager = FileLockManager(),
            livenessMonitor = ExecutionLivenessMonitor(store),
            runtime = runtime,
            limits = RecoveryLimitsConfig(maxReplanAttempts = 1),
            delayFn = {},
        )
        val team = registerStandardTeam(engine, proj.projectId)

        val finalTask = engine.submitAndSupervise(
            projectId = proj.projectId,
            team = team,
            objective = "Create App.kt",
        )
        assertNotNull(finalTask)
        assertEquals(PersistentTaskStatus.BLOCKED, finalTask!!.status)
        assertTrue(
            "Expected missing real test evidence error, got: ${finalTask.lastError}",
            finalTask.lastError.orEmpty().contains("TestExecutionEvidence", ignoreCase = true) ||
                finalTask.lastError.orEmpty().contains("test execution evidence", ignoreCase = true),
        )
    }

    // --- 4. Rejected Review Work Returns to Developer -> Fixed -> Re-Reviewed -> COMPLETED ---
    @Test
    fun test04_RejectedReviewReturnsToDeveloperFixesAndCompletesWithoutEmittingPlannedAgain() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "p-review-reject")
        val reviewCalls = AtomicInteger(0)
        val lifecycleEvents = CopyOnWriteArrayList<StepLifecycleUpdate>()

        val runtime = fakeRuntime { req ->
            val agentId = req.agent.config.slot.id
            if (agentId == "agent-02") {
                File(wsDir, "src/Calc.kt").apply {
                    parentFile?.mkdirs()
                    writeText(if (reviewCalls.get() == 0) "fun add(a: Int, b: Int) = a - b" else "fun add(a: Int, b: Int) = a + b")
                }
                RuntimeExecutionResponse(req.requestId, "p", "m", 0L, 10L, true, "Updated Calc.kt", modifiedFiles = listOf("src/Calc.kt"))
            } else {
                val attempt = reviewCalls.incrementAndGet()
                if (attempt == 1) {
                    RuntimeExecutionResponse(
                        req.requestId, "p", "m", 0L, 10L, true,
                        "REVIEW_REJECTED: add() subtracts instead of adding",
                    )
                } else {
                    RuntimeExecutionResponse(
                        req.requestId, "p", "m", 0L, 10L, true,
                        "REVIEW_APPROVED\nTESTS_PASSED: Fixed and tests pass",
                        testEvidence = passingTestEvidence(),
                    )
                }
            }
        }

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            fileLockManager = FileLockManager(),
            livenessMonitor = ExecutionLivenessMonitor(store),
            runtime = runtime,
            onStepLifecycleUpdate = { lifecycleEvents.add(it) },
            delayFn = {},
        )
        val team = registerStandardTeam(engine, proj.projectId)

        val finalTask = engine.submitAndSupervise(
            projectId = proj.projectId,
            team = team,
            objective = "Implement Calc.add",
            requiredDeliverableFiles = listOf("src/Calc.kt"),
        )
        assertNotNull(finalTask)
        assertEquals(PersistentTaskStatus.COMPLETED, finalTask!!.status)
        assertTrue("Expected at least 2 reviewer invocations (reject + approve), got ${reviewCalls.get()}", reviewCalls.get() >= 2)
        assertEquals(1, lifecycleEvents.count { it.phase == "PLANNED" })
        assertTrue(File(wsDir, "src/Calc.kt").readText().contains("a + b"))
    }

    // --- 5. Recovery After Simulated Restart Restores Existing Steps Without Duplicating Completed Work ---
    @Test
    fun test05_RecoveryAfterSimulatedRestartPreservesCompletedStepsAndFinishesRemaining() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "p-restart-recover")
        val executedAgents = CopyOnWriteArrayList<String>()

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            fileLockManager = FileLockManager(),
            livenessMonitor = ExecutionLivenessMonitor(store),
            runtime = fakeRuntime { req ->
                executedAgents.add(req.agent.config.slot.id)
                RuntimeExecutionResponse(
                    req.requestId, "p", "m", 0L, 10L, true,
                    "REVIEW_APPROVED\nTESTS_PASSED",
                    testEvidence = passingTestEvidence(),
                )
            },
            delayFn = {},
        )
        val team = registerStandardTeam(engine, proj.projectId)
        val task = engine.preparePlan(proj.projectId, team, "Build feature X")!!
        val steps = store.listStepsForTask(task.taskId)
        assertTrue(steps.size >= 2)

        // Simulate Step 1 already completed before crash/restart
        val step1 = steps[0]
        File(wsDir, "src/FeatureX.kt").apply { parentFile?.mkdirs(); writeText("val x = 1") }
        store.upsertStep(step1.copy(status = StepStatus.COMPLETED, outputSummary = "Done before crash"))
        store.recordIdempotency(
            IdempotencyRecordEntity(
                idempotencyKey = step1.idempotencyKey,
                taskId = task.taskId,
                stepId = step1.stepId,
                operationType = "STEP_EXECUTION",
                resultSummary = "Done before crash",
            ),
        )

        // Resume supervision on the same task ID
        val resumed = engine.superviseTask(task.taskId, team, requiredDeliverableFiles = listOf("src/FeatureX.kt"))
        assertNotNull(resumed)
        assertEquals(PersistentTaskStatus.COMPLETED, resumed!!.status)
        assertFalse("Completed Developer Step 1 must NOT be re-executed after restart", "agent-02" in executedAgents)
        assertTrue("Remaining QA review step must execute", "agent-03" in executedAgents)
    }

    // --- 6. Post-Persist Validation Rejects Empty or Corrupted Step Persistence Before Delegation ---
    @Test
    fun test06_PostPersistValidationRejectsTaskStoreThatDropsStepsOnUpsertTask() {
        val delegate = InMemoryOrqenTaskStore()
        // Simulate the exact SQLite CONFLICT_REPLACE ON DELETE CASCADE bug!
        val buggyCascadeStore = object : OrqenTaskStore by delegate {
            private val wipedTasks = mutableSetOf<String>()
            override fun upsertTask(task: TaskEntity) {
                val existed = delegate.getTask(task.taskId) != null
                delegate.upsertTask(task)
                if (existed) {
                    wipedTasks.add(task.taskId)
                }
            }
            override fun listStepsForTask(taskId: String): List<TaskStepEntity> =
                if (taskId in wipedTasks) emptyList() else delegate.listStepsForTask(taskId)
        }

        val collab = InMemoryCollaborationStore()
        val (proj, _) = newProject(buggyCascadeStore, "p-cascade-guard")
        val engine = AutonomousSupervisorEngine(
            taskStore = buggyCascadeStore,
            collabStore = collab,
            fileLockManager = FileLockManager(),
            livenessMonitor = ExecutionLivenessMonitor(buggyCascadeStore),
            runtime = fakeRuntime { req ->
                RuntimeExecutionResponse(req.requestId, "p", "m", 0L, 1L, true, "ok")
            },
            delayFn = {},
        )
        val team = registerStandardTeam(engine, proj.projectId)

        try {
            engine.preparePlan(proj.projectId, team, "Build feature")
            fail("Expected post-persist validation to throw IllegalArgumentException when task_steps are wiped!")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Expected plan persistence verification error, got: ${e.message}",
                e.message.orEmpty().contains("Plan persistence verification failed"),
            )
        }
    }

    // --- 7. Static Guard: SQLite Parent Tables Must NEVER Use CONFLICT_REPLACE (Prevents ON DELETE CASCADE Wipe!) ---
    @Test
    fun test07_SqliteStoresNeverUseConflictReplaceOnParentTablesWithCascadeChildren() {
        val repoRoot = locateRepoRoot()
        val sqliteTaskDb = File(repoRoot, "app/src/main/java/com/jarves/mh/multiagent/persistence/SqliteOrqenTaskDatabase.kt").readText()
        val sqliteCollab = File(repoRoot, "app/src/main/java/com/jarves/mh/multiagent/collab/SqliteCollaborationStore.kt").readText()
        val mcpStore = File(repoRoot, "app/src/main/java/com/jarves/mh/mcp/McpStore.kt").readText()

        // Parent tables referenced by FOREIGN KEY ... ON DELETE CASCADE MUST use UPDATE-then-INSERT, NEVER CONFLICT_REPLACE!
        val forbiddenTaskDbPatterns = listOf(
            """insertWithOnConflict("projects"""",
            """insertWithOnConflict("conversations"""",
            """insertWithOnConflict("tasks"""",
            """insertWithOnConflict("task_steps"""",
        )
        forbiddenTaskDbPatterns.forEach { pattern ->
            assertFalse(
                "SqliteOrqenTaskDatabase.kt must NEVER use CONFLICT_REPLACE on parent table ($pattern) — causes ON DELETE CASCADE data loss!",
                sqliteTaskDb.contains(pattern),
            )
        }

        val forbiddenCollabPatterns = listOf(
            """insertWithOnConflict("agent_profiles"""",
            """insertWithOnConflict("agent_teams"""",
        )
        forbiddenCollabPatterns.forEach { pattern ->
            assertFalse(
                "SqliteCollaborationStore.kt must NEVER use CONFLICT_REPLACE on parent table ($pattern)!",
                sqliteCollab.contains(pattern),
            )
        }

        assertFalse(
            "McpStore.kt must NEVER use CONFLICT_REPLACE on mcp_servers (cascades to mcp_tools)!",
            mcpStore.contains("""insertWithOnConflict("mcp_servers""""),
        )
    }

    private fun locateRepoRoot(): File {
        var cur = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            if (File(cur, "app/src/main/java/com/jarves/mh/multiagent/persistence/SqliteOrqenTaskDatabase.kt").isFile) {
                return cur
            }
            cur = cur.parentFile ?: return cur
        }
        return cur
    }
}
