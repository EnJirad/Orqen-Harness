package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.recovery.ExecutionLivenessMonitor
import com.jarves.mh.multiagent.recovery.InterruptionCause
import com.jarves.mh.multiagent.recovery.ProcessRecoveryCoordinator
import com.jarves.mh.multiagent.recovery.RecoveryPolicy
import com.jarves.mh.multiagent.recovery.RecoverySchedulerBackend
import com.jarves.mh.multiagent.recovery.ScheduledRecoveryWork
import com.jarves.mh.multiagent.supervisor.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * ORQEN-Harness PART A.2 — Automated Verification & Acceptance Test Suite.
 *
 * Clearly labeled boundary test doubles are used for external AI provider failures
 * (simulated timeouts, HTTP 429, HTTP 5xx, model unavailability). These tests verify
 * real Supervisor, Watchdog, Health Monitor, Recovery Engine, Checkpoint, Lease,
 * and Persistence orchestration logic; they do NOT claim live API provider verification.
 */
class PartA2AutonomousSupervisorTest {

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

    private fun registerAgents(engine: AutonomousSupervisorEngine, projectId: String): AgentTeamEntity {
        val lead = engine.registry.register(
            AgentProfileEntity(
                agentId = "agent-01",
                displayName = "Lead Architect",
                role = "Coordinator",
                providerId = "provider-primary",
                modelId = "model-lead-v1",
                capabilities = setOf("lead", "architect", "plan"),
                priority = 10,
            ),
        )
        val dev1 = engine.registry.register(
            AgentProfileEntity(
                agentId = "agent-02",
                displayName = "Sun Dev",
                role = "Developer",
                providerId = "provider-primary",
                modelId = "model-dev-v1",
                capabilities = setOf("code", "fix"),
                priority = 9,
            ),
        )
        val dev2 = engine.registry.register(
            AgentProfileEntity(
                agentId = "agent-04",
                displayName = "Backup Dev",
                role = "Developer",
                providerId = "provider-backup",
                modelId = "model-backup-v2",
                capabilities = setOf("code", "fix"),
                priority = 7,
            ),
        )
        val reviewer = engine.registry.register(
            AgentProfileEntity(
                agentId = "agent-03",
                displayName = "QA Reviewer",
                role = "Reviewer & Tester",
                providerId = "provider-qa",
                modelId = "model-qa-v1",
                capabilities = setOf("review", "qa", "test"),
                priority = 8,
            ),
        )
        return engine.teamManager.createTeam(
            projectId = projectId,
            teamName = "Autonomous Delivery Team",
            memberAgentIds = listOf(lead.agentId, dev1.agentId, dev2.agentId, reviewer.agentId),
            requireIndependentReview = true,
        )
    }

    private fun standardModelPool(): List<ModelCandidate> = listOf(
        ModelCandidate("provider-primary", "model-dev-v1", setOf("code", "fix", "lead"), priority = 10),
        ModelCandidate("provider-primary", "model-dev-v2", setOf("code", "fix", "lead"), priority = 9),
        ModelCandidate("provider-backup", "model-backup-v2", setOf("code", "fix", "lead", "review", "qa"), priority = 8),
        ModelCandidate("provider-qa", "model-qa-v1", setOf("review", "qa", "test"), priority = 9),
    )

    // --- 1. Watchdog: Idle Stream Timeout vs Active Tool vs Heartbeat vs Deduplication ---
    @Test
    fun test01_WatchdogDistinguishesToolRunHeartbeatSuspectedStallConfirmedStallAndDeduplicates() {
        val store = InMemoryOrqenTaskStore()
        val (proj, _) = newProject(store, "p-wd")
        store.upsertTask(TaskEntity(taskId = "T-WD", projectId = proj.projectId, title = "WD", description = "WD"))

        val health = AgentHealthMonitor(store)
        val policy = GranularTimeoutPolicy(
            firstResponseTimeoutMs = 30_000L,
            streamInactivityTimeoutMs = 90_000L,
            toolExecutionTimeoutMs = 120_000L,
            agentExecutionTimeoutMs = 300_000L,
            heartbeatTimeoutMs = 60_000L,
        )
        val watchdog = TaskWatchdog(health, policy)
        val cancelledExecs = CopyOnWriteArrayList<String>()

        // T=0: Start execution
        health.recordEvent(
            AgentProgressEvent(
                taskId = "T-WD", subtaskId = "S-1", agentId = "agent-02", executionId = "exec-1",
                eventType = ProgressEventType.STARTED, providerId = "provider-primary", modelId = "model-dev-v1",
                timestampMs = 0L,
            ),
        )
        // T=5s: First meaningful progress
        health.recordEvent(
            AgentProgressEvent(
                taskId = "T-WD", subtaskId = "S-1", agentId = "agent-02", executionId = "exec-1",
                eventType = ProgressEventType.PROGRESS, details = "Writing Auth.kt", timestampMs = 5_000L,
            ),
        )
        // T=10s: Legitimate tool starts -> at T=105s (95s elapsed > 90s stream limit, < 120s tool limit) MUST NOT be treated as stall!
        health.recordEvent(
            AgentProgressEvent(
                taskId = "T-WD", subtaskId = "S-1", agentId = "agent-02", executionId = "exec-1",
                eventType = ProgressEventType.TOOL_STARTED, toolName = "gradle_test", timestampMs = 10_000L,
            ),
        )
        val duringTool = watchdog.evaluateExecution("agent-02", nowMs = 105_000L) { cancelledExecs.add(it) }
        assertEquals(WatchdogDiagnosis.TOOL_RUNNING, duringTool?.diagnosis)
        assertFalse(duringTool?.shouldCancelAndRecover == true)

        // T=110s: Tool completes; then T=190s: Heartbeat arrives (liveness only, NOT meaningful progress)
        health.recordEvent(
            AgentProgressEvent(
                taskId = "T-WD", subtaskId = "S-1", agentId = "agent-02", executionId = "exec-1",
                eventType = ProgressEventType.TOOL_COMPLETED, timestampMs = 110_000L,
            ),
        )
        health.recordEvent(
            AgentProgressEvent(
                taskId = "T-WD", subtaskId = "S-1", agentId = "agent-02", executionId = "exec-1",
                eventType = ProgressEventType.HEARTBEAT, timestampMs = 190_000L,
            ),
        )
        // T=205s: 95s since last meaningful progress (110s), heartbeat alive -> FIRST marks SUSPECTED_STALL
        val suspected = watchdog.evaluateExecution("agent-02", nowMs = 205_000L) { cancelledExecs.add(it) }
        assertEquals(WatchdogDiagnosis.SUSPECTED_STALL, suspected?.diagnosis)
        assertEquals(AgentHealthState.SUSPECTED_STALL, health.getHealth("agent-02")?.healthState)
        assertFalse(suspected?.shouldCancelAndRecover == true)

        // T=210s: Still no meaningful progress -> CONFIRMED_IDLE_STREAM_STALL + triggers cancellation once!
        val confirmed = watchdog.evaluateExecution("agent-02", nowMs = 210_000L) { cancelledExecs.add(it) }
        assertEquals(WatchdogDiagnosis.CONFIRMED_IDLE_STREAM_STALL, confirmed?.diagnosis)
        assertTrue(confirmed?.shouldCancelAndRecover == true)
        assertEquals(listOf("exec-1"), cancelledExecs)

        // T=215s: Duplicate watchdog tick for same execution MUST be deduplicated!
        val dup = watchdog.evaluateExecution("agent-02", nowMs = 215_000L) { cancelledExecs.add(it) }
        assertTrue(dup?.deduplicated == true)
        assertFalse(dup?.shouldCancelAndRecover == true)
        assertEquals(1, cancelledExecs.size)
    }

    // --- 2. Rate Limit (HTTP 429) & Provider 5xx Failover ---
    @Test
    fun test02_RateLimit429And5xxTriggerIntelligentProviderModelFailoverWithoutRotatingToDownProvider() {
        val store = InMemoryOrqenTaskStore()
        val (proj, _) = newProject(store, "p-failover")
        val task = TaskEntity(taskId = "T-FO", projectId = proj.projectId, title = "Failover", description = "Test")
        store.upsertTask(task)
        val step = TaskStepEntity(stepId = "S-FO", taskId = "T-FO", sequenceNumber = 1, title = "Code", description = "Code", assignedAgentId = "agent-02")
        val health = AgentHealthMonitor(store)
        val liveness = ExecutionLivenessMonitor()
        val locks = FileLockManager()
        val downProviders = mutableSetOf<String>()

        val recovery = AutonomousRecoveryEngine(
            taskStore = store,
            healthMonitor = health,
            livenessMonitor = liveness,
            fileLockManager = locks,
            capabilityResolver = AgentCapabilityResolver { downProviders },
            modelPoolProvider = { standardModelPool() },
            unavailableProviders = { downProviders },
            limits = RecoveryLimitsConfig(maxRetriesPerExecution = 1, maxModelSwitchesPerStep = 3),
        )

        val agent = AgentProfileEntity(
            agentId = "agent-02", displayName = "Sun Dev", role = "Dev",
            providerId = "provider-primary", modelId = "model-dev-v1", capabilities = setOf("code", "fix"),
        )
        // Simulate HTTP 429 on provider-primary
        health.recordEvent(
            AgentProgressEvent(
                taskId = "T-FO", subtaskId = "S-FO", agentId = "agent-02", executionId = "exec-429",
                eventType = ProgressEventType.FAILED, providerId = "provider-primary", modelId = "model-dev-v1",
                errorCategory = ExecutionErrorCategory.RATE_LIMIT, retryAfterMs = 60_000L, details = "HTTP 429 Retry-After: 60",
                timestampMs = 10_000L,
            ),
        )
        val err429 = StructuredErrorClassifier.classify("HTTP 429 Too Many Requests Retry-After: 60", httpStatusCode = 429)
        val plan = recovery.planRecovery(
            task = task, step = step, failedAgent = agent, executionId = "exec-429",
            error = err429, requiredCapabilities = setOf("code"), teamCandidates = listOf(agent),
            preservedFiles = listOf("src/Auth.kt"), nowMs = 10_000L,
        )
        // Must skip rate-limited provider-primary (including model-dev-v2 on same provider) and switch to provider-backup!
        assertEquals(RecoveryStrategyKind.SWITCH_PROVIDER_AND_MODEL, plan.strategy)
        assertEquals("provider-backup", plan.targetProviderId)
        assertEquals("model-backup-v2", plan.targetModelId)
        assertEquals(listOf("src/Auth.kt"), plan.preservedModifiedFiles)
        assertTrue(recovery.auditTrail("T-FO").any { it.decisionType == SupervisorDecisionType.PROVIDER_CHANGED })
    }

    // --- 3. Supervisor Model Failure & Fallback + All-Models-Unavailable Safety ---
    @Test
    fun test03_SupervisorModelFailureFallsBackAndPreservesTaskWhenAllSupervisorModelsUnavailable() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, ws) = newProject(store, "p-supfail")
        val task = TaskEntity(taskId = "T-SUP", projectId = proj.projectId, title = "Sup Fail", description = "Test")
        store.upsertTask(task)

        val triedModels = CopyOnWriteArrayList<String>()
        val flakyAdvisor = object : SupervisorAdvisor {
            override suspend fun evaluateMissingRequirements(
                supervisorModel: ModelCandidate,
                task: TaskEntity,
                completedSteps: List<TaskStepEntity>,
                workspaceDir: File,
            ): List<PlannedStep> {
                triedModels.add("${supervisorModel.providerId}/${supervisorModel.modelId}")
                if (supervisorModel.modelId == "sup-primary") {
                    throw RuntimeException("Simulated Supervisor model timeout")
                }
                return listOf(
                    PlannedStep(
                        sequenceNumber = 99, title = "Follow-up from backup supervisor",
                        description = "Add missing docs", expectedOutput = "README updated",
                        requiredCapabilities = setOf("code"),
                    ),
                )
            }
        }
        val dummyRuntime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit) =
                RuntimeExecutionResponse(request.requestId, "p", "m", 0L, 1L, true, "ok")
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, false, false)
        }

        // Case A: Primary Supervisor model times out -> fallback Supervisor model succeeds!
        val engine1 = AutonomousSupervisorEngine(
            taskStore = store, collabStore = collab, runtime = dummyRuntime,
            supervisorModelPoolProvider = {
                listOf(
                    ModelCandidate("prov-a", "sup-primary"),
                    ModelCandidate("prov-b", "sup-backup"),
                )
            },
            advisor = flakyAdvisor,
            delayFn = {},
        )
        val followUps = engine1.runAdvisorWithFailover(task, emptyList(), ws)
        assertEquals(listOf("prov-a/sup-primary", "prov-b/sup-backup"), triedModels)
        assertEquals(1, followUps.size)
        assertTrue(engine1.recoveryEngine.auditTrail("T-SUP").any { it.decisionType == SupervisorDecisionType.SUPERVISOR_FALLBACK })

        // Case B: ALL Supervisor models unavailable -> deterministic control preserves task safely without throwing!
        val engine2 = AutonomousSupervisorEngine(
            taskStore = store, collabStore = collab, runtime = dummyRuntime,
            supervisorModelPoolProvider = { listOf(ModelCandidate("prov-a", "sup-primary")) },
            advisor = flakyAdvisor,
            delayFn = {},
        )
        val emptyResult = engine2.runAdvisorWithFailover(task, emptyList(), ws)
        assertTrue(emptyResult.isEmpty())
        assertNotNull(store.getTask("T-SUP"))
        assertTrue(store.listTaskEvents("T-SUP").any { it.eventType == "SUPERVISOR_MODELS_UNAVAILABLE" })
    }

    // --- 4. Owner Approval Security Boundary Respected ---
    @Test
    fun test04_AutonomousSupervisorRespectsOwnerApprovalBoundaryAndPauseCancel() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, _) = newProject(store, "p-sec")
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
        val team = registerAgents(engine, proj.projectId)

        val task = TaskEntity(taskId = "T-APPROVAL", projectId = proj.projectId, title = "Deploy", description = "Push to main")
        store.upsertTask(task)
        store.upsertStep(TaskStepEntity(stepId = "S-1", taskId = "T-APPROVAL", sequenceNumber = 1, title = "Push", description = "Push", assignedAgentId = "agent-02", status = StepStatus.READY))
        collab.upsertApproval(
            OwnerApprovalRequest(
                taskId = "T-APPROVAL", projectId = proj.projectId, requestedByAgentId = "agent-02",
                actionTitle = "Git push to protected branch", reason = "Release", impact = "Production",
            ),
        )

        val blocked = engine.superviseTask("T-APPROVAL", team)
        assertEquals(PersistentTaskStatus.BLOCKED, blocked?.status)
        assertTrue(blocked?.lastError.orEmpty().contains("owner approval"))
    }

    // --- 5. SECTION 19 ACCEPTANCE TEST: Real Autonomous Project Completion ---
    // Objective: "Build a To-do application with authentication, persistent database storage, and a dashboard."
    // Failure injections (clearly labeled test scenario):
    //   1) Sun Dev encounters a simulated timeout after writing partial file (Auth.kt)
    //   2) Supervisor recovers partial progress & switches model/provider -> writes TodoDb.kt
    //   3) Reviewer rejects incomplete work (missing Dashboard.kt) -> Dev fixes it
    //   4) Tester reports TESTS_FAILED once -> Dev fixes test bug -> Retest reports TESTS_PASSED + REVIEW_APPROVED
    //   5) Missing deliverable file (Dashboard.kt) triggers automatic follow-up subtask & re-verification
    //   6) Task completes ONLY after all deliverables exist on disk, review approved, and tests passed
    //   7) Simulated app restart verifies task history, checkpoints, and recovery audit persist intact
    @Test
    fun test05_Acceptance_RealAutonomousTodoProjectCompletionWithRecoveryReviewTestAndRestartPersistence() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "proj-todo-app")

        val devAttempts = AtomicInteger(0)
        val qaAttempts = AtomicInteger(0)

        val scenarioRuntime = object : AIExecutionRuntime {
            override suspend fun execute(
                request: RuntimeExecutionRequest,
                onProgress: suspend (String) -> Unit,
            ): RuntimeExecutionResponse {
                val agentId = request.agent.config.slot.id
                val modelId = request.agent.config.modelId
                onProgress("$agentId ($modelId) running")

                return when (agentId) {
                    "agent-01" -> RuntimeExecutionResponse(
                        requestId = request.requestId, providerId = request.agent.config.providerId, modelId = modelId,
                        startedAtMs = 0L, completedAtMs = 10L, success = true,
                        outputText = "Architecture designed: Auth.kt, TodoDb.kt, Dashboard.kt.",
                    )

                    "agent-02", "agent-04" -> {
                        val attempt = devAttempts.incrementAndGet()
                        when (attempt) {
                            1 -> {
                                // TEST SCENARIO: partial progress written before simulated timeout!
                                File(wsDir, "Auth.kt").writeText("class AuthService { fun login() = true }")
                                delay(120L) // Exceeds test step timeout (50ms) -> triggers Watchdog/Timeout recovery
                                RuntimeExecutionResponse(request.requestId, request.agent.config.providerId, modelId, 0L, 120L, false, "Timeout")
                            }
                            2 -> {
                                // Recovered on fallback model/provider -> continues unfinished work (does NOT overwrite Auth.kt)
                                File(wsDir, "TodoDb.kt").writeText("class TodoDb { val items = mutableListOf<String>() }")
                                RuntimeExecutionResponse(
                                    request.requestId, request.agent.config.providerId, modelId, 0L, 10L, true,
                                    "Implemented TodoDb.kt preserving Auth.kt.",
                                    modifiedFiles = listOf("TodoDb.kt"),
                                )
                            }
                            3 -> {
                                // Fixing reviewer rejection
                                File(wsDir, "Auth.kt").appendText("\n// Fixed null check")
                                RuntimeExecutionResponse(
                                    request.requestId, request.agent.config.providerId, modelId, 0L, 10L, true,
                                    "Fixed null check in Auth.kt per reviewer feedback.",
                                    modifiedFiles = listOf("Auth.kt"),
                                )
                            }
                            4 -> {
                                // Fixing failing test
                                File(wsDir, "TodoDb.kt").appendText("\n// Fixed persistence query")
                                RuntimeExecutionResponse(
                                    request.requestId, request.agent.config.providerId, modelId, 0L, 10L, true,
                                    "Fixed TodoDb query for failing unit test.",
                                    modifiedFiles = listOf("TodoDb.kt"),
                                )
                            }
                            else -> {
                                // Auto-replanned missing deliverable: Dashboard.kt!
                                File(wsDir, "Dashboard.kt").writeText("class DashboardScreen { fun render() = \"Todos\" }")
                                RuntimeExecutionResponse(
                                    request.requestId, request.agent.config.providerId, modelId, 0L, 10L, true,
                                    "Implemented missing Dashboard.kt deliverable.",
                                    modifiedFiles = listOf("Dashboard.kt"),
                                )
                            }
                        }
                    }

                    "agent-03" -> {
                        when (qaAttempts.incrementAndGet()) {
                            1 -> RuntimeExecutionResponse(
                                request.requestId, request.agent.config.providerId, modelId, 0L, 10L, true,
                                "REVIEW_REJECTED: AuthService needs null token validation.",
                                testEvidence = listOf(
                                    com.jarves.mh.runtime.TestExecutionEvidence(
                                        command = "./gradlew test", startedAtMs = 0L, completedAtMs = 5L, exitCode = 0,
                                        stdout = "1 tests passed", stderr = "", outcome = com.jarves.mh.runtime.TestExecutionOutcome.TESTS_PASSED, summary = "Passed",
                                    ),
                                ),
                            )
                            2 -> RuntimeExecutionResponse(
                                request.requestId, request.agent.config.providerId, modelId, 0L, 10L, true,
                                "REVIEW_APPROVED\nTESTS_FAILED: TodoDb persistence query assertion failed.",
                                testEvidence = listOf(
                                    com.jarves.mh.runtime.TestExecutionEvidence(
                                        command = "./gradlew test", startedAtMs = 0L, completedAtMs = 5L, exitCode = 1,
                                        stdout = "0 tests passed, 1 failed", stderr = "TodoDb assertion failed", outcome = com.jarves.mh.runtime.TestExecutionOutcome.TESTS_FAILED, summary = "Failed",
                                    ),
                                ),
                            )
                            else -> RuntimeExecutionResponse(
                                request.requestId, request.agent.config.providerId, modelId, 0L, 10L, true,
                                "REVIEW_APPROVED\nTESTS_PASSED: Auth, TodoDb, and Dashboard tests all green.",
                                testEvidence = listOf(
                                    com.jarves.mh.runtime.TestExecutionEvidence(
                                        command = "./gradlew test", startedAtMs = 0L, completedAtMs = 5L, exitCode = 0,
                                        stdout = "3 tests passed", stderr = "", outcome = com.jarves.mh.runtime.TestExecutionOutcome.TESTS_PASSED, summary = "Passed",
                                    ),
                                ),
                            )
                        }
                    }

                    else -> RuntimeExecutionResponse(request.requestId, "p", modelId, 0L, 1L, true, "OK")
                }
            }

            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, true, false)
        }

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            runtime = scenarioRuntime,
            timeoutPolicy = GranularTimeoutPolicy(agentExecutionTimeoutMs = 50L),
            limits = RecoveryLimitsConfig(
                maxRetriesPerExecution = 0, // Immediately escalate simulated timeout to model switch
                maxModelSwitchesPerStep = 3,
                maxReviewCycles = 3,
                maxReplanAttempts = 3,
            ),
            modelPoolProvider = { standardModelPool() },
            delayFn = {},
        )
        val team = registerAgents(engine, proj.projectId)

        // User submits objective ONCE; Supervisor drives everything autonomously!
        val result = engine.submitAndSupervise(
            projectId = proj.projectId,
            team = team,
            objective = "Build a To-do application with authentication, persistent database storage, and a dashboard.",
            acceptanceCriteria = listOf("Auth.kt exists", "TodoDb.kt exists", "Dashboard.kt exists", "Review & tests pass"),
            requiredDeliverableFiles = listOf("Auth.kt", "TodoDb.kt", "Dashboard.kt"),
        )

        assertNotNull(result)
        val tid = result!!.taskId
        assertEquals("Task must reach COMPLETED only after all recoveries, fixes, tests & deliverables pass", PersistentTaskStatus.COMPLETED, result.status)

        // Verify all 3 deliverables actually exist on disk
        assertTrue(File(wsDir, "Auth.kt").isFile)
        assertTrue(File(wsDir, "TodoDb.kt").isFile)
        assertTrue(File(wsDir, "Dashboard.kt").isFile)

        // Verify full audit trail of recovery, review rejection, test failure, replanning, and completion
        val decisions = engine.recoveryEngine.auditTrail(tid).map { it.decisionType }
        assertTrue("Expected MODEL_CHANGED or PROVIDER_CHANGED on simulated timeout", SupervisorDecisionType.MODEL_CHANGED in decisions || SupervisorDecisionType.PROVIDER_CHANGED in decisions)
        assertTrue("Expected REVISION_REQUESTED on reviewer rejection", SupervisorDecisionType.REVISION_REQUESTED in decisions)
        assertTrue("Expected TEST_FAILED triggering auto-fix", SupervisorDecisionType.TEST_FAILED in decisions)
        assertTrue("Expected TASK_COMPLETED after verification", SupervisorDecisionType.TASK_COMPLETED in decisions)

        // Verify Observability UI Snapshot reflects COMPLETED & recovery history
        val uiSnap = engine.snapshotFor(tid)
        assertEquals(SupervisorOperationalStatus.COMPLETED, uiSnap?.status)
        assertTrue((uiSnap?.recoveryHistory?.size ?: 0) >= 3)

        // Simulate Android process recreation: ProcessRecoveryCoordinator inspects persisted store & never re-runs COMPLETED task!
        val fakeScheduler = object : RecoverySchedulerBackend {
            val works = mutableListOf<ScheduledRecoveryWork>()
            override fun scheduleUnique(taskId: String, requireNetwork: Boolean, delayMs: Long): Boolean {
                works.add(ScheduledRecoveryWork("w-$taskId", taskId, requireNetwork, delayMs))
                return true
            }
            override fun cancel(taskId: String) {}
            override fun scheduledWork() = works
        }
        val procRecovery = ProcessRecoveryCoordinator(
            store = store,
            checkpointManager = CheckpointManager(store),
            livenessMonitor = ExecutionLivenessMonitor(),
            scheduler = fakeScheduler,
            policy = RecoveryPolicy(),
        )
        val summary = procRecovery.reconcileOnStartup(InterruptionCause.UNEXPECTED_PROCESS_DEATH)
        assertTrue("Completed task must be skipped on restart", tid in summary.skippedTaskIds)
        assertTrue("Persisted checkpoints survive restart", store.listCheckpointsForTask(tid).isNotEmpty())
        assertTrue("Persisted audit events survive restart", store.listTaskEvents(tid).any { it.eventType == "SUPERVISOR_TASK_COMPLETED" })
    }
}
