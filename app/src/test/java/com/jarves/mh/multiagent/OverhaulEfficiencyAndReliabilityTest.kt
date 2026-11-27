package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.chat.*
import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.model.*
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.recovery.ExecutionLivenessMonitor
import com.jarves.mh.multiagent.supervisor.*
import com.jarves.mh.runtime.TestExecutionEvidence
import com.jarves.mh.runtime.TestExecutionOutcome
import com.jarves.mh.runtime.WorkspaceToolExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ORQEN — Autonomous AI Team Efficiency & Reliability Overhaul
 *
 * Covers PART 11 (Tests A–I) and PART 12 (Real-World Acceptance Test: 2-AI Team Bee Calculator).
 */
class OverhaulEfficiencyAndReliabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newProject(store: OrqenTaskStore, id: String): Pair<ProjectEntity, File> {
        val dir = tmp.newFolder("ws-$id")
        val p = ProjectEntity(
            projectId = id,
            projectName = "Project $id",
            workspacePath = dir.absolutePath,
            projectMemory = "Android Kotlin / Jetpack Compose project",
        )
        store.upsertProject(p)
        return p to dir
    }

    /**
     * Registers a 2-AI Team:
     * - SPV (agent-01): Planning + Supervision + Independent QA
     * - Dev (agent-02): Implementation + Build + Test Execution
     */
    private fun registerTwoAgentTeam(engine: AutonomousSupervisorEngine, projectId: String): AgentTeamEntity {
        val spv = engine.registry.register(
            AgentProfileEntity(
                agentId = "agent-01",
                displayName = "SPV",
                role = "Supervisor, Planner & QA",
                providerId = "provider-spv",
                modelId = "model-spv-primary",
                capabilities = setOf("lead", "architect", "plan", "review", "qa", "code"),
                priority = 10,
            ),
        )
        val dev = engine.registry.register(
            AgentProfileEntity(
                agentId = "agent-02",
                displayName = "Dev",
                role = "Developer",
                providerId = "provider-dev",
                modelId = "model-dev-primary",
                capabilities = setOf("code", "fix", "test"),
                priority = 9,
            ),
        )
        return engine.teamManager.createTeam(
            projectId = projectId,
            teamName = "2-AI Team",
            memberAgentIds = listOf(spv.agentId, dev.agentId),
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

    private fun realShellTestEvidence(workspaceDir: File, command: String): TestExecutionEvidence {
        val started = System.currentTimeMillis()
        val proc = ProcessBuilder("bash", "-lc", command)
            .directory(workspaceDir)
            .redirectErrorStream(false)
            .start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        val completed = System.currentTimeMillis()
        return TestExecutionEvidence(
            command = command,
            startedAtMs = started,
            completedAtMs = completed,
            exitCode = exitCode,
            stdout = stdout.trim(),
            stderr = stderr.trim(),
            outcome = if (exitCode == 0) TestExecutionOutcome.TESTS_PASSED else TestExecutionOutcome.TESTS_FAILED,
            summary = if (exitCode == 0) "Real command exited 0" else "Real command failed ($exitCode)",
            workingDir = workspaceDir.absolutePath,
            passedCount = if (exitCode == 0) 1 else 0,
            failedCount = if (exitCode == 0) 0 else 1,
        )
    }

    // --- Test A: Two-Agent Small Project (Android Calculator) ---
    @Test
    fun testA_TwoAgentSmallProjectUsesCompactPlanDevImplementsAndSpvReviews() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "test-a-calc")
        val invokedAgents = CopyOnWriteArrayList<String>()

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            runtime = fakeRuntime { req ->
                val aid = req.agent.config.slot.id
                invokedAgents.add(aid)
                if (aid == "agent-02") {
                    File(wsDir, "app/src/main/java/Calculator.kt").apply {
                        parentFile?.mkdirs()
                        writeText("class Calculator { fun add(a: Double, b: Double) = a + b }")
                    }
                    RuntimeExecutionResponse(req.requestId, "provider-dev", "model-dev-primary", 0L, 10L, true, "Implemented Calculator.kt", modifiedFiles = listOf("app/src/main/java/Calculator.kt"))
                } else {
                    val ev = realShellTestEvidence(wsDir, "test -s app/src/main/java/Calculator.kt && echo CALC_OK")
                    RuntimeExecutionResponse(req.requestId, "provider-spv", "model-spv-primary", 0L, 10L, true, "REVIEW_APPROVED\nTESTS_PASSED", testEvidence = listOf(ev))
                }
            },
            delayFn = {},
        )
        val team = registerTwoAgentTeam(engine, proj.projectId)

        val objective = "สร้างแอปเครื่องคิดเลข Android ธีมผึ้ง ปุ่มหกเหลี่ยม มีเสียงเอฟเฟกต์ รองรับเด็กอายุ 5-16 ปี"
        val tier = MultiPartPlanManager.assessTaskComplexity(objective)
        assertEquals("Android Calculator must be classified as SMALL adaptive tier", MultiPartPlanManager.TaskComplexityTier.SMALL, tier)

        val finished = engine.submitAndSupervise(
            projectId = proj.projectId,
            team = team,
            objective = objective,
            requiredDeliverableFiles = listOf("app/src/main/java/Calculator.kt"),
        )
        assertNotNull(finished)
        assertEquals(PersistentTaskStatus.COMPLETED, finished!!.status)

        val steps = store.listStepsForTask(finished.taskId)
        assertEquals("Small project must produce compact 2-step DAG (Dev Impl + SPV QA)", 2, steps.size)
        assertEquals("Implementation must be assigned to Dev (agent-02)", "agent-02", steps[0].assignedAgentId)
        assertEquals("Independent Review must be assigned to SPV (agent-01)", "agent-01", steps[1].assignedAgentId)
        assertEquals(listOf("agent-02", "agent-01"), invokedAgents)
    }

    // --- Test B: Strict Role Enforcement (No Silent Fallback to SPV) ---
    @Test
    fun testB_RoleEnforcementRedirectsSpvImplementationToDevAndRefusesSilentFallbackWhenDevDisabled() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "test-b-role")
        val actualExecutor = CopyOnWriteArrayList<String>()

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            runtime = fakeRuntime { req ->
                val aid = req.agent.config.slot.id
                actualExecutor.add(aid)
                if (aid == "agent-02") {
                    File(wsDir, "src/Work.kt").apply { parentFile?.mkdirs(); writeText("val ok = true") }
                    RuntimeExecutionResponse(req.requestId, "provider-dev", "model-dev-primary", 0L, 5L, true, "Done", modifiedFiles = listOf("src/Work.kt"))
                } else {
                    val ev = realShellTestEvidence(wsDir, "echo OK")
                    RuntimeExecutionResponse(req.requestId, "provider-spv", "model-spv-primary", 0L, 5L, true, "REVIEW_APPROVED", testEvidence = listOf(ev))
                }
            },
            delayFn = {},
        )
        val team = registerTwoAgentTeam(engine, proj.projectId)

        // Case 1: Manually force an implementation step assigned to SPV while Dev is enabled
        val task = TaskEntity(taskId = "T-ROLE-B", projectId = proj.projectId, title = "Role Guard", description = "Test role guard")
        store.upsertTask(task)
        val rogueStep = TaskStepEntity(
            stepId = "S-ROGUE",
            taskId = task.taskId,
            sequenceNumber = 1,
            title = "Implement Work.kt",
            description = "Write Work.kt",
            assignedAgentId = "agent-01", // Improperly assigned to SPV!
            status = StepStatus.READY,
            requiresReview = false,
        )
        val reviewStep = TaskStepEntity(
            stepId = "S-REV",
            taskId = task.taskId,
            sequenceNumber = 2,
            title = "Independent Review",
            description = "Review Work.kt",
            assignedAgentId = "agent-01",
            status = StepStatus.PENDING,
            dependencies = listOf("S-ROGUE"),
            requiresReview = true,
        )
        store.upsertStep(rogueStep)
        store.upsertStep(reviewStep)

        val result = engine.superviseTask(task.taskId, team)
        assertEquals(PersistentTaskStatus.COMPLETED, result?.status)
        assertEquals("Implementation must have been redirected to Dev (agent-02)", "agent-02", actualExecutor.first())
        val events = store.listTaskEvents(task.taskId)
        assertTrue(
            "Expected ROLE_ENFORCEMENT_REDIRECTED audit event",
            events.any { it.eventType == "ROLE_ENFORCEMENT_REDIRECTED" },
        )

        // Case 2: Disable Dev -> preparePlan must fail explicitly instead of silently assigning implementation to SPV!
        engine.registry.setEnabled("agent-02", false)
        try {
            engine.preparePlan(proj.projectId, team, "Build another feature")
            fail("Expected IllegalStateException when Developer is disabled in multi-agent team")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("No enabled Developer", ignoreCase = true))
        }
    }

    // --- Test C: No Duplicate Planning When Only Test Evidence Is Missing ---
    @Test
    fun testC_MissingTestEvidenceSchedulesOnlyVerificationStepWithoutDuplicatingImplementationPlan() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "test-c-nodup")
        val qaCalls = AtomicInteger(0)
        val devCalls = AtomicInteger(0)

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            runtime = fakeRuntime { req ->
                val aid = req.agent.config.slot.id
                if (aid == "agent-02") {
                    devCalls.incrementAndGet()
                    File(wsDir, "src/Main.kt").apply { parentFile?.mkdirs(); writeText("fun main() {}") }
                    RuntimeExecutionResponse(req.requestId, "provider-dev", "model-dev-primary", 0L, 5L, true, "Created Main.kt", modifiedFiles = listOf("src/Main.kt"))
                } else {
                    val call = qaCalls.incrementAndGet()
                    if (call == 1) {
                        // First pass: missing test evidence
                        RuntimeExecutionResponse(req.requestId, "provider-spv", "model-spv-primary", 0L, 5L, true, "REVIEW_APPROVED", testEvidence = emptyList())
                    } else {
                        val ev = realShellTestEvidence(wsDir, "echo TESTS_OK")
                        RuntimeExecutionResponse(req.requestId, "provider-spv", "model-spv-primary", 0L, 5L, true, "REVIEW_APPROVED", testEvidence = listOf(ev))
                    }
                }
            },
            delayFn = {},
        )
        val team = registerTwoAgentTeam(engine, proj.projectId)

        val finished = engine.submitAndSupervise(proj.projectId, team, "Create Main.kt")
        assertEquals(PersistentTaskStatus.COMPLETED, finished?.status)
        assertEquals("Developer implementation must run only ONCE (no duplicate implementation plan)", 1, devCalls.get())
        assertTrue("QA/verification must have retried/remediated to collect real test evidence", qaCalls.get() >= 2)
    }

    // --- Test D: Real Test Evidence Required (Prose "TESTS_PASSED" Rejected; exitCode=0 Accepted) ---
    @Test
    fun testD_ProseTestsPassedRejectedAndRealExitCodeZeroEvidencePersistedAndAccepted() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "test-d-evidence")

        // Part 1: Prose "TESTS_PASSED" without TestExecutionEvidence MUST fail ResultVerifier
        val proseVerdict = ResultVerifier.verify(
            workspaceDir = wsDir,
            outputSummary = "TESTS_PASSED — all 100 tests passed!",
            spec = VerificationSpec(mode = VerificationMode.TEST_VERIFICATION, testEvidence = emptyList()),
        )
        assertFalse("Prose 'TESTS_PASSED' must NEVER pass verification without real TestExecutionEvidence", proseVerdict.passed)

        // Part 2: Real shell command producing exitCode=0 persists and passes Completion Verifier
        val realEvidence = realShellTestEvidence(wsDir, "echo 'PASS 5/5'")
        assertEquals(0, realEvidence.exitCode)
        val realVerdict = ResultVerifier.verify(
            workspaceDir = wsDir,
            outputSummary = "Real tests executed",
            spec = VerificationSpec(mode = VerificationMode.TEST_VERIFICATION, testEvidence = listOf(realEvidence)),
        )
        assertTrue("Real exitCode=0 TestExecutionEvidence must pass verification", realVerdict.passed)

        // Persist and reload from store
        val task = TaskEntity(taskId = "T-EVID", projectId = proj.projectId, title = "Evidence", description = "Verify persistence")
        store.upsertTask(task)
        store.insertVerificationEvidence(
            VerificationEvidenceEntity(
                taskId = task.taskId,
                stepId = "S-1",
                evidenceType = "TEST",
                command = realEvidence.command,
                workingDir = realEvidence.workingDir,
                exitCode = realEvidence.exitCode,
                stdout = realEvidence.stdout,
                passedCount = realEvidence.passedCount,
                failedCount = realEvidence.failedCount,
            ),
        )
        val reloaded = store.listVerificationEvidenceForTask(task.taskId)
        assertEquals(1, reloaded.size)
        assertEquals(0, reloaded.first().exitCode)
        assertEquals("TEST", reloaded.first().evidenceType)
    }

    // --- Test E: SPV Model Disconnect -> Retry / Failover -> Resume Without Losing Dev Progress ---
    @Test
    fun testE_SpvModelDisconnectRetriesOrFailsOverAndResumesWithoutLosingDevProgress() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "test-e-spv-disconnect")
        val spvCalls = AtomicInteger(0)
        val devCalls = AtomicInteger(0)

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            modelPoolProvider = {
                listOf(
                    ModelCandidate("provider-spv", "model-spv-primary", setOf("review", "qa", "lead"), priority = 10),
                    ModelCandidate("provider-spv-backup", "model-spv-backup", setOf("review", "qa", "lead"), priority = 8),
                )
            },
            runtime = fakeRuntime { req ->
                val aid = req.agent.config.slot.id
                if (aid == "agent-02") {
                    devCalls.incrementAndGet()
                    File(wsDir, "src/SafeWork.kt").apply { parentFile?.mkdirs(); writeText("val preserved = 42") }
                    RuntimeExecutionResponse(req.requestId, "provider-dev", "model-dev-primary", 0L, 10L, true, "Saved SafeWork.kt", modifiedFiles = listOf("src/SafeWork.kt"))
                } else {
                    val attempt = spvCalls.incrementAndGet()
                    if (attempt == 1) {
                        // Simulate SPV provider timeout / disconnect
                        RuntimeExecutionResponse(
                            req.requestId, req.agent.config.providerId, req.agent.config.modelId, 0L, 10L, false, "",
                            error = StructuredErrorClassifier.classify("SocketTimeoutException: connection reset", isTimeout = true),
                        )
                    } else {
                        val ev = realShellTestEvidence(wsDir, "test -s src/SafeWork.kt")
                        RuntimeExecutionResponse(req.requestId, req.agent.config.providerId, req.agent.config.modelId, 0L, 10L, true, "REVIEW_APPROVED\nTESTS_PASSED", testEvidence = listOf(ev))
                    }
                }
            },
            delayFn = {},
        )
        val team = registerTwoAgentTeam(engine, proj.projectId)

        val finished = engine.submitAndSupervise(proj.projectId, team, "Create SafeWork.kt", requiredDeliverableFiles = listOf("src/SafeWork.kt"))
        assertEquals(PersistentTaskStatus.COMPLETED, finished?.status)
        assertEquals("Dev work must NOT be re-executed when SPV disconnects", 1, devCalls.get())
        assertTrue("SPV must have recovered via retry/failover", spvCalls.get() >= 2)
        assertEquals(ModelConnectionState.CONNECTED, engine.connectionStateFor(finished!!.taskId, isSupervisor = true))
        assertTrue("Task must not be recreated; single task in project", store.listTasksForProject(proj.projectId).size == 1)
        assertTrue(File(wsDir, "src/SafeWork.kt").readText().contains("preserved = 42"))
    }

    // --- Test F: One-Hour Limit (> 3,600,000ms) -> Checkpoint & PAUSED (NOT BLOCKED), Safely Resumable ---
    @Test
    fun testF_ExceedingOneHourDoesNotBlockTaskInsteadCheckpointsPausesAndResumesCleanly() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "test-f-one-hour")
        val fakeClock = AtomicLong(1_000_000L)

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            limits = RecoveryLimitsConfig(
                maxTotalTaskDurationMs = 3_600_000L,
                maxActiveExecutionBudgetMs = 3_600_000L,
                pauseOnDurationBudgetExceeded = true,
            ),
            nowProvider = { fakeClock.get() },
            runtime = fakeRuntime { req ->
                val aid = req.agent.config.slot.id
                if (aid == "agent-02") {
                    File(wsDir, "src/LongTask.kt").apply { parentFile?.mkdirs(); writeText("val step1Done = true") }
                    // Simulate > 1 hour elapsed during Step 1
                    fakeClock.addAndGet(3_700_000L)
                    RuntimeExecutionResponse(req.requestId, "provider-dev", "model-dev-primary", 0L, 10L, true, "Step 1 done", modifiedFiles = listOf("src/LongTask.kt"))
                } else {
                    val ev = realShellTestEvidence(wsDir, "test -s src/LongTask.kt")
                    RuntimeExecutionResponse(req.requestId, "provider-spv", "model-spv-primary", 0L, 10L, true, "REVIEW_APPROVED\nTESTS_PASSED", testEvidence = listOf(ev))
                }
            },
            delayFn = {},
        )
        val team = registerTwoAgentTeam(engine, proj.projectId)

        val pausedTask = engine.submitAndSupervise(proj.projectId, team, "Long running feature", requiredDeliverableFiles = listOf("src/LongTask.kt"))
        assertNotNull(pausedTask)
        assertEquals("Task exceeding 1h budget must be PAUSED safely, NEVER BLOCKED", PersistentTaskStatus.PAUSED, pausedTask!!.status)
        assertTrue("Checkpoint must be persisted before pausing", store.listCheckpointsForTask(pausedTask.taskId).isNotEmpty())
        assertTrue(store.listTaskEvents(pausedTask.taskId).any { it.eventType == "TASK_CHECKPOINT_PAUSED" })

        // Resume from latest step -> completes cleanly without re-running Step 1!
        store.upsertTask(pausedTask.copy(status = PersistentTaskStatus.READY, pauseRequested = false))
        val resumed = engine.superviseTask(pausedTask.taskId, team, requiredDeliverableFiles = listOf("src/LongTask.kt"))
        assertEquals(PersistentTaskStatus.COMPLETED, resumed?.status)
    }

    // --- Test G: SQLite CASCADE Safety Guard ---
    @Test
    fun testG_SqliteParentUpdatesPreserveStepsAndNeverUseConflictReplace() {
        val repoRoot = locateRepoRoot()
        val taskDb = File(repoRoot, "app/src/main/java/com/jarves/mh/multiagent/persistence/SqliteOrqenTaskDatabase.kt").readText()
        assertTrue("SqliteOrqenTaskDatabase must define upsertSafe()", taskDb.contains("private fun upsertSafe("))
        assertFalse(taskDb.contains("""insertWithOnConflict("tasks""""))
        assertFalse(taskDb.contains("""insertWithOnConflict("projects""""))
        assertFalse(taskDb.contains("""insertWithOnConflict("task_steps""""))
    }

    // --- Test H: No Infinite Recovery Loop on Repeated Failure ---
    @Test
    fun testH_RepeatedProviderFailureStopsSafelyWithinLimitsWithoutDuplicateSteps() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, _) = newProject(store, "test-h-no-loop")
        val calls = AtomicInteger(0)

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            limits = RecoveryLimitsConfig(maxRetriesPerExecution = 2, maxReplanAttempts = 1),
            runtime = fakeRuntime { req ->
                calls.incrementAndGet()
                RuntimeExecutionResponse(
                    req.requestId, "provider-dev", "model-dev-primary", 0L, 5L, false, "",
                    error = StructuredErrorClassifier.classify("HTTP 503 Service Unavailable", httpStatusCode = 503),
                )
            },
            delayFn = {},
        )
        val team = registerTwoAgentTeam(engine, proj.projectId)

        val stopped = engine.submitAndSupervise(proj.projectId, team, "Build failing task")
        assertNotNull(stopped)
        assertEquals(PersistentTaskStatus.BLOCKED, stopped!!.status)
        assertTrue("Calls must be strictly bounded (<= 6), got ${calls.get()}", calls.get() <= 6)
        assertEquals("No duplicate steps should be created on failure", 2, store.listStepsForTask(stopped.taskId).size)
        assertEquals(ModelConnectionState.DISCONNECTED, engine.connectionStateFor(stopped.taskId, isSupervisor = false))
    }

    // --- Test I: Single-Agent vs Team Capability Parity & Security Boundaries ---
    @Test
    fun testI_TeamAgentHasFullWorkspaceToolParityWithSingleAgentAndRespectsSecurityBoundaries() = runBlocking {
        val wsDir = tmp.newFolder("ws-test-i-tools")
        val executor = WorkspaceToolExecutor(
            workspaceDir = wsDir,
            projectSlug = "test-i",
            readOnly = false,
            commandRunner = { dir, _, cmd, _ ->
                val ev = realShellTestEvidence(dir, cmd)
                com.jarves.mh.runtime.CommandExecutionResult(cmd, ev.exitCode, ev.stdout, ev.stderr, ev.startedAtMs, ev.completedAtMs)
            },
        )

        // Team Developer can write, read, edit, list, search, and run commands (full Single-Agent parity!)
        val (writeRes, _, _) = executor.executeTool("write_file", org.json.JSONObject().put("path", "src/Hello.kt").put("content", "fun hi() = \"bee\"").toString())
        assertTrue("Team agent must write files", writeRes.success)

        val (readRes, _, _) = executor.executeTool("read_file", org.json.JSONObject().put("path", "src/Hello.kt").toString())
        assertTrue("Team agent must read files", readRes.success && readRes.stdout.contains("bee"))

        val (editRes, _, _) = executor.executeTool("edit_file", org.json.JSONObject().put("path", "src/Hello.kt").put("target", "bee").put("replacement", "honeybee").toString())
        assertTrue("Team agent must edit files", editRes.success)

        val (cmdRes, _, _) = executor.executeTool("run_command", org.json.JSONObject().put("command", "grep -q honeybee src/Hello.kt && echo VERIFIED").toString())
        assertTrue("Team agent must execute real commands", cmdRes.success && cmdRes.exitCode == 0)

        // Security boundary: path traversal outside workspace MUST be blocked!
        val (escapeRes, _, _) = executor.executeTool("read_file", org.json.JSONObject().put("path", "../../etc/passwd").toString())
        assertFalse("Path traversal outside workspace must remain blocked", escapeRes.success)

        // Security boundary: read-only Reviewer cannot write files!
        val readOnlyExecutor = WorkspaceToolExecutor(workspaceDir = wsDir, projectSlug = "test-i", readOnly = true)
        val (blockedWrite, _, _) = readOnlyExecutor.executeTool("write_file", org.json.JSONObject().put("path", "src/Hack.kt").put("content", "bad").toString())
        assertFalse("Read-only reviewer must be blocked from writing source files", blockedWrite.success)
    }

    // --- PART 12: Real-World Acceptance Test (2-AI Team Builds Real Bee-Themed Android Calculator) ---
    @Test
    fun testPart12_RealWorldAcceptance_TwoAiTeamBuildsVerifiedBeeCalculator() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "acceptance-bee-calc")
        val lifecyclePhases = CopyOnWriteArrayList<String>()
        val executorByStep = CopyOnWriteArrayList<Pair<String, String>>()

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            onStepLifecycleUpdate = { lifecyclePhases.add("${it.phase}:${it.agentId}") },
            runtime = fakeRuntime { req ->
                val aid = req.agent.config.slot.id
                executorByStep.add(aid to (req.stepId ?: ""))
                if (aid == "agent-02") {
                    // Dev creates REAL, non-mock Bee-Themed Android Calculator source files + real test script!
                    val pkgDir = File(wsDir, "app/src/main/java/com/orqen/beecalc").apply { mkdirs() }
                    File(pkgDir, "BeeAgeMode.kt").writeText(
                        """
                        package com.orqen.beecalc

                        enum class BeeAgeMode(
                            val label: String,
                            val minAge: Int,
                            val maxAge: Int,
                            val allowDecimals: Boolean,
                            val allowScientific: Boolean,
                        ) {
                            HONEY_CUB("5-8 Basic Bee", 5, 8, allowDecimals = false, allowScientific = false),
                            EXPLORER_BEE("9-12 Explorer Bee", 9, 12, allowDecimals = true, allowScientific = false),
                            QUEEN_SCIENTIST("13-16 Scientific Bee", 13, 16, allowDecimals = true, allowScientific = true),
                        }
                        """.trimIndent(),
                    )
                    File(pkgDir, "BeeCalculatorEngine.kt").writeText(
                        """
                        package com.orqen.beecalc

                        import kotlin.math.pow
                        import kotlin.math.sqrt

                        class BeeCalculatorEngine {
                            fun evaluate(a: Double, op: String, b: Double, mode: BeeAgeMode): Result<Double> {
                                if (op in setOf("^", "sqrt") && !mode.allowScientific) {
                                    return Result.failure(IllegalArgumentException("Scientific operator not enabled for ${'$'}{mode.label}"))
                                }
                                return when (op) {
                                    "+" -> Result.success(a + b)
                                    "-" -> Result.success(a - b)
                                    "*" -> Result.success(a * b)
                                    "/" -> if (b == 0.0) Result.failure(ArithmeticException("Cannot divide by zero")) else Result.success(a / b)
                                    "^" -> Result.success(a.pow(b))
                                    "sqrt" -> if (a < 0.0) Result.failure(ArithmeticException("Negative sqrt")) else Result.success(sqrt(a))
                                    else -> Result.failure(IllegalArgumentException("Unsupported op: ${'$'}op"))
                                }
                            }
                        }
                        """.trimIndent(),
                    )
                    File(pkgDir, "HoneycombUiAndSound.kt").writeText(
                        """
                        package com.orqen.beecalc

                        // Honeycomb hexagonal button layout + bee buzz sound effect controller
                        data class HoneycombButtonSpec(val label: String, val hexSides: Int = 6, val soundEffect: String = "bee_buzz_pop")
                        class BeeSoundEffectPlayer(var enabled: Boolean = true) {
                            fun playFor(button: HoneycombButtonSpec): String = if (enabled) "PLAY:${'$'}{button.soundEffect}" else "MUTED"
                        }
                        """.trimIndent(),
                    )
                    // Real executable verification script verifying Bee Calculator files & arithmetic rules
                    val verifyScript = File(wsDir, "verify_bee_calc.py").apply {
                        writeText(
                            """
                            import os, sys
                            files = [
                                "app/src/main/java/com/orqen/beecalc/BeeAgeMode.kt",
                                "app/src/main/java/com/orqen/beecalc/BeeCalculatorEngine.kt",
                                "app/src/main/java/com/orqen/beecalc/HoneycombUiAndSound.kt",
                            ]
                            for f in files:
                                assert os.path.isfile(f) and os.path.getsize(f) > 100, f"Missing {f}"
                            age = open(files[0]).read()
                            eng = open(files[1]).read()
                            ui  = open(files[2]).read()
                            assert "5-8 Basic Bee" in age and "9-12 Explorer Bee" in age and "13-16 Scientific Bee" in age
                            assert "Cannot divide by zero" in eng and "sqrt" in eng
                            assert "hexSides: Int = 6" in ui and "bee_buzz_pop" in ui
                            print("BEE_CALCULATOR_ACCEPTANCE_TESTS_PASSED: 3/3 suites verified")
                            """.trimIndent(),
                        )
                    }
                    RuntimeExecutionResponse(
                        requestId = req.requestId,
                        providerId = "provider-dev",
                        modelId = "model-dev-primary",
                        startedAtMs = 0L,
                        completedAtMs = 25L,
                        success = true,
                        outputText = "Implemented Bee Calculator (BeeAgeMode, BeeCalculatorEngine, HoneycombUiAndSound).",
                        modifiedFiles = listOf(
                            "app/src/main/java/com/orqen/beecalc/BeeAgeMode.kt",
                            "app/src/main/java/com/orqen/beecalc/BeeCalculatorEngine.kt",
                            "app/src/main/java/com/orqen/beecalc/HoneycombUiAndSound.kt",
                            verifyScript.name,
                        ),
                    )
                } else {
                    // SPV performs Independent Review + executes real test command
                    val ev = realShellTestEvidence(wsDir, "python3 verify_bee_calc.py")
                    RuntimeExecutionResponse(
                        requestId = req.requestId,
                        providerId = "provider-spv",
                        modelId = "model-spv-primary",
                        startedAtMs = 0L,
                        completedAtMs = 20L,
                        success = true,
                        outputText = "REVIEW_APPROVED: Inspected Bee Calculator source, honeycomb hex layout, sound player, and 5-16 age modes.\n${ev.stdout}",
                        testEvidence = listOf(ev),
                    )
                }
            },
            delayFn = {},
        )
        val team = registerTwoAgentTeam(engine, proj.projectId)

        val finished = engine.submitAndSupervise(
            projectId = proj.projectId,
            team = team,
            objective = "Build Android Calculator with bee theme, hexagonal honeycomb buttons, sound effects, and age modes for kids 5-16",
            requiredDeliverableFiles = listOf(
                "app/src/main/java/com/orqen/beecalc/BeeAgeMode.kt",
                "app/src/main/java/com/orqen/beecalc/BeeCalculatorEngine.kt",
                "app/src/main/java/com/orqen/beecalc/HoneycombUiAndSound.kt",
            ),
        )

        assertNotNull(finished)
        assertEquals(PersistentTaskStatus.COMPLETED, finished!!.status)
        // Verify all acceptance criteria
        assertEquals(1, lifecyclePhases.count { it.startsWith("PLANNED:") })
        assertEquals("agent-02", executorByStep[0].first) // Dev implemented
        assertEquals("agent-01", executorByStep[1].first) // SPV independently reviewed
        assertTrue(store.listVerificationEvidenceForTask(finished.taskId).any { it.evidenceType == "TEST" && it.exitCode == 0 })
        assertTrue(collab.listReviewsForTask(finished.taskId).any { it.outcome == ReviewOutcome.APPROVED && it.reviewerAgentId == "agent-01" })

        val metrics = engine.efficiencyMetricsFor(finished.taskId)
        assertEquals(0, metrics.replanCount)
        assertEquals(0, metrics.retryCount)
        assertTrue("Active execution duration should be recorded", metrics.activeExecutionDurationMs >= 0L)
    }

    // --- Test J: Non-Provider Step Failure Must NOT Show Disconnected; Chat Reply Resets SPV Health ---
    @Test
    fun testJ_NonProviderFailureKeepsConnectionConnectedAndMarkAgentConnectedResetsStaleHealth() {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, _) = newProject(store, "test-j-health")
        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            runtime = fakeRuntime { req -> RuntimeExecutionResponse(req.requestId, "p", "m", 0L, 1L, true, "ok") },
            delayFn = {},
        )
        store.upsertTask(TaskEntity(taskId = "T-HLTH", projectId = proj.projectId, title = "Health", description = "Check"))

        // Non-provider failure (e.g. verification failure / UNKNOWN) must NOT mark PROVIDER_ERROR or DISCONNECTED
        val rec1 = engine.healthMonitor.recordEvent(
            AgentProgressEvent(
                taskId = "T-HLTH",
                subtaskId = "S-1",
                agentId = "agent-01",
                executionId = "exec-1",
                eventType = ProgressEventType.FAILED,
                details = "Test verification rejected: no real TestExecutionEvidence",
                providerId = "provider-spv",
                modelId = "model-spv-primary",
                errorCategory = ExecutionErrorCategory.UNKNOWN,
            ),
        )
        assertEquals(AgentHealthState.RECOVERING, rec1.healthState)
        assertEquals(ModelConnectionState.CONNECTED, rec1.connectionState)
        assertTrue(rec1.connectionAlive)

        // Actual network failure marks PROVIDER_ERROR / DISCONNECTED, and markAgentConnected() resets it back to HEALTHY
        val rec2 = engine.healthMonitor.recordEvent(
            AgentProgressEvent(
                taskId = "T-HLTH",
                subtaskId = "S-2",
                agentId = "agent-01",
                executionId = "exec-2",
                eventType = ProgressEventType.FAILED,
                details = "Connection reset",
                providerId = "provider-spv",
                modelId = "model-spv-primary",
                errorCategory = ExecutionErrorCategory.NETWORK_ERROR,
            ),
        )
        assertEquals(AgentHealthState.PROVIDER_ERROR, rec2.healthState)
        assertEquals(ModelConnectionState.DISCONNECTED, rec2.connectionState)

        engine.markAgentConnected("agent-01", "provider-spv", "model-spv-primary")
        val recovered = engine.healthMonitor.snapshotAll()["agent-01"]
        assertEquals(AgentHealthState.HEALTHY, recovered?.healthState)
        assertEquals(ModelConnectionState.CONNECTED, recovered?.connectionState)
        assertTrue(recovered?.connectionAlive == true)
    }

    // --- Test K: Continuous Rolling Execution Runs Past 3+ Hours Without Stopping via Auto-Checkpoint ---
    @Test
    fun testK_ContinuousRollingExecutionAutoCheckpointsPastThreeHoursWithoutStopping() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "test-k-continuous")
        val fakeClock = AtomicLong(1_000_000L)

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            limits = RecoveryLimitsConfig(
                maxTotalTaskDurationMs = 3_600_000L,
                maxActiveExecutionBudgetMs = 3_600_000L,
                autoContinueAfterCheckpoint = true,
            ),
            nowProvider = { fakeClock.get() },
            runtime = fakeRuntime { req ->
                val aid = req.agent.config.slot.id
                // Simulate 2 hours per step (> 4 hours total across Dev + SPV!)
                fakeClock.addAndGet(7_200_000L)
                if (aid == "agent-02") {
                    File(wsDir, "src/Marathon.kt").apply { parentFile?.mkdirs(); writeText("val hours = 4") }
                    RuntimeExecutionResponse(req.requestId, "provider-dev", "model-dev-primary", 0L, 10L, true, "Done", modifiedFiles = listOf("src/Marathon.kt"))
                } else {
                    val ev = realShellTestEvidence(wsDir, "test -s src/Marathon.kt")
                    RuntimeExecutionResponse(req.requestId, "provider-spv", "model-spv-primary", 0L, 10L, true, "REVIEW_APPROVED\nTESTS_PASSED", testEvidence = listOf(ev))
                }
            },
            delayFn = {},
        )
        val team = registerTwoAgentTeam(engine, proj.projectId)

        val finished = engine.submitAndSupervise(proj.projectId, team, "Marathon build", requiredDeliverableFiles = listOf("src/Marathon.kt"))
        assertNotNull(finished)
        assertEquals("Continuous mode must run past 1h+ slices and finish COMPLETED (lastError=${finished!!.lastError})", PersistentTaskStatus.COMPLETED, finished.status)
        assertTrue(
            "Expected TASK_CHECKPOINT_ROLLOVER event(s) during multi-hour execution",
            store.listTaskEvents(finished.taskId).any { it.eventType == "TASK_CHECKPOINT_ROLLOVER" },
        )
    }

    // --- Test L: Read-Only Reviewer Can Execute Real Tests & Accepts Developer's Persisted Passing Tests ---
    @Test
    fun testL_ReadOnlyReviewerCanRunTestCommandsAndAcceptsPersistedDevTestEvidence() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "test-l-reviewer-tests")

        // 1. Read-only Reviewer executor with allowShellCommands=true CAN run real test commands,
        //    while STILL blocking write_file/edit_file/delete_file!
        val reviewerExec = WorkspaceToolExecutor(
            workspaceDir = wsDir,
            projectSlug = "test-l",
            allowShellCommands = true,
            readOnly = true,
            commandRunner = { dir, _, cmd, _ ->
                val ev = realShellTestEvidence(dir, cmd)
                com.jarves.mh.runtime.CommandExecutionResult(cmd, ev.exitCode, ev.stdout, ev.stderr, ev.startedAtMs, ev.completedAtMs)
            },
        )
        val (shellRes, testEv, _) = reviewerExec.executeTool("run_command", org.json.JSONObject().put("command", "echo pytest_passed").toString())
        assertTrue("Read-only Reviewer must be able to execute test commands", shellRes.success && shellRes.exitCode == 0)
        val (blockedWrite, _, _) = reviewerExec.executeTool("write_file", org.json.JSONObject().put("path", "src/Nope.kt").put("content", "x").toString())
        assertFalse("Read-only Reviewer must still be blocked from writing files", blockedWrite.success)

        // 2. When Developer runs real tests (exitCode=0) in Step 1 and SPV reviews & approves in Step 2,
        //    the persisted passing test evidence satisfies verification cleanly!
        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            runtime = fakeRuntime { req ->
                val aid = req.agent.config.slot.id
                if (aid == "agent-02") {
                    File(wsDir, "src/BeeCalc.kt").apply { parentFile?.mkdirs(); writeText("fun sum(a: Int, b: Int) = a + b") }
                    val ev = realShellTestEvidence(wsDir, "test -s src/BeeCalc.kt && echo UNIT_TESTS_OK")
                    RuntimeExecutionResponse(req.requestId, "provider-dev", "model-dev-primary", 0L, 10L, true, "Implemented & tested BeeCalc.kt", modifiedFiles = listOf("src/BeeCalc.kt"), testEvidence = listOf(ev))
                } else {
                    RuntimeExecutionResponse(req.requestId, "provider-spv", "model-spv-primary", 0L, 10L, true, "REVIEW_APPROVED: Code and Dev test evidence verified.", testEvidence = emptyList())
                }
            },
            delayFn = {},
        )
        val team = registerTwoAgentTeam(engine, proj.projectId)
        val finished = engine.submitAndSupervise(proj.projectId, team, "Build BeeCalc", requiredDeliverableFiles = listOf("src/BeeCalc.kt"))
        assertEquals(PersistentTaskStatus.COMPLETED, finished?.status)
    }

    // --- Test M: FileProvider file_paths.xml MUST Expose team_workspaces/ for Team APK Install ---
    @Test
    fun testM_FilePathsXmlConfiguresTeamWorkspacesRootForApkInstall() {
        val repoRoot = locateRepoRoot()
        val filePathsXml = File(repoRoot, "app/src/main/res/xml/file_paths.xml").readText()
        assertTrue(
            "file_paths.xml must declare team_workspaces/ so FileProvider can install/open Team APKs!",
            filePathsXml.contains("""path="team_workspaces/""""),
        )
    }

    // --- Test N: Individual Quota Auto-Switch & Earliest-Reset Auto-Wakeup When All Models Exhausted ---
    @Test
    fun testN_IndividualQuotaReachedAutoSwitchesModelAndWaitsForEarliestResetWhenAllExhausted() = runBlocking {
        // 1. Verify classifier parses "Individual quota reached ... Resets in 29m25s."
        val classified = StructuredErrorClassifier.classify(
            "Individual quota reached. Please upgrade your subscription to increase your limits. Resets in 29m25s.",
        )
        assertEquals(ExecutionErrorCategory.QUOTA_EXHAUSTED, classified.category)
        assertEquals((29L * 60L + 25L) * 1000L, classified.retryAfterMs)

        // 2. Same-provider individual quota failover: primary model hits quota -> automatically switches to backup model on same provider!
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "test-n-quota-switch")
        val usedDevModels = CopyOnWriteArrayList<String>()

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            modelPoolProvider = {
                listOf(
                    ModelCandidate("provider-dev", "model-dev-primary", setOf("code", "fix"), priority = 10),
                    ModelCandidate("provider-dev", "model-dev-flash", setOf("code", "fix"), priority = 8),
                )
            },
            runtime = fakeRuntime { req ->
                val aid = req.agent.config.slot.id
                if (aid == "agent-02") {
                    usedDevModels.add(req.agent.config.modelId)
                    if (req.agent.config.modelId == "model-dev-primary") {
                        RuntimeExecutionResponse(
                            req.requestId, "provider-dev", "model-dev-primary", 0L, 10L, false, "",
                            error = StructuredErrorClassifier.classify("Individual quota reached. Resets in 29m25s."),
                        )
                    } else {
                        File(wsDir, "src/QuotaRecovered.kt").apply { parentFile?.mkdirs(); writeText("val ok = true") }
                        RuntimeExecutionResponse(req.requestId, "provider-dev", req.agent.config.modelId, 0L, 10L, true, "Done on backup model", modifiedFiles = listOf("src/QuotaRecovered.kt"))
                    }
                } else {
                    val ev = realShellTestEvidence(wsDir, "test -s src/QuotaRecovered.kt")
                    RuntimeExecutionResponse(req.requestId, "provider-spv", "model-spv-primary", 0L, 10L, true, "REVIEW_APPROVED\nTESTS_PASSED", testEvidence = listOf(ev))
                }
            },
            delayFn = {},
        )
        val team = registerTwoAgentTeam(engine, proj.projectId)
        val finished = engine.submitAndSupervise(proj.projectId, team, "Build QuotaRecovered", requiredDeliverableFiles = listOf("src/QuotaRecovered.kt"))
        assertEquals(PersistentTaskStatus.COMPLETED, finished?.status)
        assertEquals(listOf("model-dev-primary", "model-dev-flash"), usedDevModels)

        // 3. When ALL models hit quota (primary resets in 29m25s, flash resets in 10s), engine picks earliest reset (flash, ~10s) and auto-resumes!
        val store2 = InMemoryOrqenTaskStore()
        val collab2 = InMemoryCollaborationStore()
        val (proj2, wsDir2) = newProject(store2, "test-n-quota-wait")
        val devCalls = AtomicInteger(0)
        val waitedMs = AtomicLong(0L)
        val resumedModels = CopyOnWriteArrayList<String>()
        val engine2 = AutonomousSupervisorEngine(
            taskStore = store2,
            collabStore = collab2,
            modelPoolProvider = {
                listOf(
                    ModelCandidate("provider-dev", "model-dev-primary", setOf("code", "fix"), priority = 10),
                    ModelCandidate("provider-dev", "model-dev-flash", setOf("code", "fix"), priority = 8),
                )
            },
            runtime = fakeRuntime { req ->
                val aid = req.agent.config.slot.id
                if (aid == "agent-02") {
                    val call = devCalls.incrementAndGet()
                    resumedModels.add(req.agent.config.modelId)
                    when (call) {
                        1 -> RuntimeExecutionResponse(
                            req.requestId, "provider-dev", "model-dev-primary", 0L, 10L, false, "",
                            error = StructuredErrorClassifier.classify("Individual quota reached. Resets in 29m25s."),
                        )
                        2 -> RuntimeExecutionResponse(
                            req.requestId, "provider-dev", "model-dev-flash", 0L, 10L, false, "",
                            error = StructuredErrorClassifier.classify("Individual quota reached. Resets in 10s."),
                        )
                        else -> {
                            File(wsDir2, "src/AfterReset.kt").apply { parentFile?.mkdirs(); writeText("val reset = true") }
                            RuntimeExecutionResponse(req.requestId, "provider-dev", req.agent.config.modelId, 0L, 10L, true, "Done after earliest quota reset", modifiedFiles = listOf("src/AfterReset.kt"))
                        }
                    }
                } else {
                    val ev = realShellTestEvidence(wsDir2, "test -s src/AfterReset.kt")
                    RuntimeExecutionResponse(req.requestId, "provider-spv", "model-spv-primary", 0L, 10L, true, "REVIEW_APPROVED\nTESTS_PASSED", testEvidence = listOf(ev))
                }
            },
            delayFn = { ms -> waitedMs.addAndGet(ms) },
        )
        val team2 = registerTwoAgentTeam(engine2, proj2.projectId)
        // Ensure SPV has no "code" capability so when BOTH Dev models hit quota, no other agent can steal Dev's step
        // and the engine enters WAIT_FOR_QUOTA_RESET for the earliest-resetting Dev model!
        engine2.registry.register(
            engine2.registry.get("agent-01")!!.copy(capabilities = setOf("lead", "architect", "plan", "review", "qa")),
        )
        val finished2 = engine2.submitAndSupervise(proj2.projectId, team2, "Build AfterReset", requiredDeliverableFiles = listOf("src/AfterReset.kt"))
        assertEquals(PersistentTaskStatus.COMPLETED, finished2?.status)
        assertTrue("Expected auto-wait around earliest 10s reset (< 60s), got ${waitedMs.get()}ms", waitedMs.get() in 10_000L..60_000L)
        assertEquals("Should resume automatically on earliest-resetting model", "model-dev-flash", resumedModels.last())
    }

    // --- Test O: OpenCode x-opencode-session Header, Eye-Icon Toggle, Multi-@mention & Live Role Handover ---
    @Test
    fun testO_OpenCodeSessionHeaderProviderEyeToggleAndLiveRoleHandover() {
        val repoRoot = locateRepoRoot()

        // 1. OpenAiCompatibleAdapter must inject x-opencode-session automatically for OpenCode endpoints
        val adapterSrc = File(repoRoot, "app/src/main/java/com/jarves/mh/provider/adapters/OpenAiCompatibleAdapter.kt").readText()
        assertTrue("OpenAiCompatibleAdapter must inject x-opencode-session header", adapterSrc.contains("x-opencode-session"))

        // 2. ProviderManagementScreen must have Visibility / VisibilityOff eye-icon toggle & OpenCode Go preset
        val providerUiSrc = File(repoRoot, "app/src/main/java/com/jarves/mh/multiagent/ui/ProviderManagementScreen.kt").readText()
        assertTrue("ProviderManagementScreen must include VisibilityOff toggle", providerUiSrc.contains("Icons.Default.VisibilityOff"))
        assertTrue("ProviderManagementScreen must include OpenCode Go quick preset", providerUiSrc.contains("https://opencode.ai/zen/go/v1"))

        // 3. Multi-@mention resolution & Dynamic Live Role Handover (@QA takes over from SPV)
        val spv = AgentState(
            config = AgentConfig(
                slot = AgentSlot.AGENT_1,
                name = "Agent 01",
                role = AgentRole(AgentRoleType.TEAM_LEADER, "Supervisor", "", emptyList()),
                isEnabled = true,
            ),
        )
        val dev = AgentState(
            config = AgentConfig(
                slot = AgentSlot.AGENT_2,
                name = "Agent 02",
                role = AgentRole(AgentRoleType.SOFTWARE_ENGINEER, "Developer", "", emptyList()),
                isEnabled = true,
            ),
        )
        val qa = AgentState(
            config = AgentConfig(
                slot = AgentSlot.AGENT_3,
                name = "Agent 03",
                role = AgentRole(AgentRoleType.TESTER, "QA Engineer", "", emptyList()),
                isEnabled = true,
            ),
        )
        val enabled = listOf(spv, dev, qa)

        // Multi-@mention relay: "@SPV ช่วยถาม @Dev หน่อย" -> [AGENT_1, AGENT_2]
        val mentions = com.jarves.mh.multiagent.collab.TeamGroupChatRouter.resolveAllMentionedSlots(
            "@SPV ช่วยถาม @Dev หน่อยเรื่อง architecture",
            null,
            enabled,
        )
        assertEquals(listOf(AgentSlot.AGENT_1, AgentSlot.AGENT_2), mentions)

        // Live Role Handover detection & step reassignment in OrqenTaskStore
        val handoverPrompt = "@QA ช่วยมาเทสระบบและบิ้ว apk แทน SPV หน่อย ทำต่อจากที่ SPV ทำอยู่เลย เพราะฉันจะคุยกับ spv"
        val req = com.jarves.mh.multiagent.collab.TeamGroupChatRouter.detectLiveHandoverRequest(handoverPrompt, null, enabled)
        assertNotNull("Expected live handover request to be detected", req)
        assertEquals(AgentSlot.AGENT_3, req!!.takeoverAgent.config.slot)
        assertEquals(AgentSlot.AGENT_1, req.relievedAgent.config.slot)

        val store = InMemoryOrqenTaskStore()
        val (proj, _) = newProject(store, "test-o-handover")
        store.upsertTask(TaskEntity(taskId = "T-HO", projectId = proj.projectId, title = "Build App", description = "Test", status = PersistentTaskStatus.RUNNING))
        // Step 1 (Dev) already COMPLETED — must NOT be touched!
        store.upsertStep(TaskStepEntity("S-1", "T-HO", 0, "Implement", "code", "agent-02", StepStatus.COMPLETED))
        // Step 2 (SPV) currently RUNNING — must be reassigned to QA (agent-03) and reset to READY!
        store.upsertStep(TaskStepEntity("S-2", "T-HO", 1, "Test & Build APK", "verify", "agent-01", StepStatus.RUNNING))

        val count = com.jarves.mh.multiagent.collab.TeamGroupChatRouter.applyLiveRoleHandover(
            store = store,
            projectId = proj.projectId,
            takeoverAgentId = "agent-03",
            relievedAgentId = "agent-01",
        )
        assertEquals(1, count)
        val stepsAfter = store.listStepsForTask("T-HO").associateBy { it.stepId }
        assertEquals(StepStatus.COMPLETED, stepsAfter["S-1"]?.status)
        assertEquals("agent-02", stepsAfter["S-1"]?.assignedAgentId)
        assertEquals("agent-03", stepsAfter["S-2"]?.assignedAgentId)
        assertEquals(StepStatus.READY, stepsAfter["S-2"]?.status)
    }

    // --- Test P: Zero Emojis Allowed Anywhere in app/src/main (Use Vector Icons Instead) ---
    @Test
    fun testP_ZeroEmojisInAppMainSourceAndResources() {
        val repoRoot = locateRepoRoot()
        val mainDir = File(repoRoot, "app/src/main")
        val offenders = mutableListOf<String>()
        mainDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    val hasEmoji = line.codePoints().anyMatch { cp ->
                        cp in 0x1F300..0x1FAFF ||
                            cp in 0x2600..0x27BF ||
                            cp in 0x231A..0x23FF ||
                            cp in 0x2B50..0x2B55 ||
                            cp == 0xFE0F
                    }
                    if (hasEmoji) {
                        offenders.add("${file.relativeTo(repoRoot).path}:${idx + 1}")
                    }
                }
            }
        assertTrue(
            "Emojis are forbidden in app/src/main (use Material Icons instead). Found in: ${offenders.take(10)}",
            offenders.isEmpty(),
        )
    }

    // --- Test Q: Auto-Skill Project Detection, Custom SKILL.md Import & Manual Mode ---
    @Test
    fun testQ_AutoSkillDetectionCustomSkillImportAndPromptInjection() {
        val manager = com.jarves.mh.skills.SkillManager(context = null)
        assertTrue("Auto-Skill mode should be enabled by default", manager.autoSelectEnabled.value)

        // 1. Android Compose project -> auto-detects Android & Compose + UI/UX skills
        val androidDir = tmp.newFolder("skill-android-proj")
        File(androidDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        File(androidDir, "AndroidManifest.xml").writeText("<manifest/>")
        File(androidDir, "MainActivity.kt").writeText("class MainActivity")
        val androidResolved = manager.resolveSkillsForWorkspace(androidDir, "Build a clean Android Compose UI")
        assertTrue(androidResolved.isAutoMode)
        assertTrue(
            "Expected Android Compose skill to be auto-selected, got ${androidResolved.skills.map { it.id }}",
            androidResolved.skills.any { it.id == "skill-android-compose" },
        )

        // 2. Modern Web project -> auto-detects Modern Web skill
        val webDir = tmp.newFolder("skill-web-proj")
        File(webDir, "package.json").writeText("""{"dependencies":{"react":"^18.0.0","vite":"^5.0.0"}}""")
        File(webDir, "index.html").writeText("<html></html>")
        val webResolved = manager.resolveSkillsForWorkspace(webDir, "Improve responsive landing page")
        assertTrue(
            "Expected Modern Web skill to be auto-selected, got ${webResolved.skills.map { it.id }}",
            webResolved.skills.any { it.id == "skill-web-modern" },
        )

        // 3. Import custom SKILL.md with YAML frontmatter -> injected into prompt section
        val customMd = """
            ---
            name: Thai E-Commerce Checkout
            description: PromptPay QR & Thai VAT rules
            tags: [promptpay, checkout, thai]
            ---
            Always validate 7% VAT and generate EMVCo PromptPay QR payloads.
        """.trimIndent()
        val imported = manager.importSkillFromMarkdown(customMd)
        assertEquals("Thai E-Commerce Checkout", imported.name)
        val promptBlock = manager.buildSkillsPromptSection(webDir, "Add PromptPay checkout")
        assertTrue("Expected <active_skills> prompt block", promptBlock.contains("<active_skills"))
        assertTrue("Expected imported custom skill in prompt block", promptBlock.contains("Thai E-Commerce Checkout"))

        // 4. Manual mode respects explicit user toggles
        manager.setAutoSelectEnabled(false)
        manager.skills.value.forEach { manager.toggleSkill(it.id, it.id == imported.id) }
        val manualResolved = manager.resolveSkillsForWorkspace(androidDir)
        assertFalse(manualResolved.isAutoMode)
        assertEquals(listOf(imported.id), manualResolved.skills.map { it.id })
    }

    // --- Test R: App Control Bridge, Fresh Objective Window After Completion, Playful Status & ETA ---
    @Test
    fun testR_AppControlActionsFreshObjectiveWindowPlayfulStatusAndEta() {
        // 1. AppSettingsActionDispatcher strips <orqen_action> and classifies safe vs sensitive
        val rawAi = """
            เปลี่ยนเป็นโหมดสว่างให้เรียบร้อยครับ
            <orqen_action>{"action":"set_theme","value":"LIGHT","reason":"Owner requested"}</orqen_action>
            <orqen_action>{"action":"delete_project","value":"p1","reason":"Owner requested"}</orqen_action>
        """.trimIndent()
        val parsed = com.jarves.mh.skills.AppSettingsActionDispatcher.parseAndStripActions(rawAi)
        assertFalse("Hidden action tag must be stripped from visible chat", parsed.cleanReplyText.contains("orqen_action"))
        assertEquals(1, parsed.safeActions.size)
        assertEquals("set_theme", parsed.safeActions.first().action)
        assertEquals(1, parsed.approvalActions.size)
        assertEquals("delete_project", parsed.approvalActions.first().action)

        // 2. SpvModePolicy.synthesizeTaskBrief ignores messages BEFORE the last COMPLETION_REPORT
        val history = listOf(
            GroupChatMessage(id = "m1", senderSlot = null, senderName = "En", text = "แก้เครื่องหมาย ## ในแชทด้วย", messageType = GroupMessageType.TASK_ASSIGNMENT),
            GroupChatMessage(id = "m2", senderSlot = AgentSlot.AGENT_1, senderName = "SPV", text = "เสร็จเรียบร้อยแล้วครับ", messageType = GroupMessageType.COMPLETION_REPORT),
        )
        val brief = SpvModePolicy.synthesizeTaskBrief(history, explicitInstruction = "บิ้ว APK ให้ผมหน่อย")
        assertEquals("บิ้ว APK ให้ผมหน่อย", brief.normalizedObjective)
        assertFalse("Must NOT pull in already-completed old task from before COMPLETION_REPORT", brief.renderedBrief.contains("แก้เครื่องหมาย ##"))

        // 3. Playful status & dynamic ETA (strictly zero emojis!)
        val status = com.jarves.mh.skills.PlayfulAgentStatus.format(
            activity = ActivityType.WRITING_CODE,
            isWorking = true,
            isTyping = false,
            isEnabled = true,
            slot = AgentSlot.AGENT_2,
        )
        assertTrue("Playful status should contain '|' separator", status.contains("|"))
        val eta = com.jarves.mh.skills.TaskEtaEstimator.estimateWorkingAgentEta(
            activity = ActivityType.WRITING_CODE,
            pendingTasksCount = 2,
            elapsedMs = 0L,
            taskTitle = "Part 1: Core Implementation",
        )
        assertTrue("ETA should provide a Thai time estimate", eta.contains("นาที") || eta.contains("ชม."))
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
