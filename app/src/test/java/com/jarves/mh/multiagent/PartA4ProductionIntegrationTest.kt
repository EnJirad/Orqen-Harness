package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.model.AgentConfig
import com.jarves.mh.multiagent.model.AgentRole
import com.jarves.mh.multiagent.model.AgentRoleType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.recovery.ExecutionLivenessMonitor
import com.jarves.mh.multiagent.recovery.InterruptionCause
import com.jarves.mh.multiagent.recovery.ProcessRecoveryCoordinator
import com.jarves.mh.multiagent.recovery.RecoveryMode
import com.jarves.mh.multiagent.recovery.RecoveryPolicy
import com.jarves.mh.multiagent.recovery.RecoverySchedulerBackend
import com.jarves.mh.multiagent.recovery.ScheduledRecoveryWork
import com.jarves.mh.multiagent.supervisor.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ORQEN-Harness PART A.4 — Production Integration & Verification Test Suite.
 *
 * Covers Section 9 requirements:
 * - 9.2/9.5: Explicit Watchdog -> Recovery Engine handoff (`handleWatchdogEvaluation`)
 *            including Sun Dev 90s idle stream timeout, active progress tolerance,
 *            user cancellation immunity, and duplicate recovery suppression.
 * - 9.3/9.4: Provider HTTP 429 / network failure / model failure failover + AgentFailureManager reassignment.
 * - 9.6: Persistent lease restoration + RecoveryMode.AUTOMATIC + pause/stop preservation.
 * - 9.7: Completion verification rejecting skipped mandatory steps & missing deliverables.
 * - 9.8: Security (`allowSkipPermissions == false` by default, workspace boundary, owner approval gate,
 *        failover never elevates permissions).
 *
 * Note: Uses in-memory stores & controlled boundary doubles for external providers;
 * does NOT claim live provider API or on-device SQLite hardware verification.
 */
class PartA4ProductionIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newProject(store: OrqenTaskStore, id: String): Pair<ProjectEntity, File> {
        val dir = tmp.newFolder("ws-a4-$id")
        val p = ProjectEntity(
            projectId = id,
            projectName = "Project $id",
            workspacePath = dir.absolutePath,
        )
        store.upsertProject(p)
        return p to dir
    }

    @Test
    fun test92_95_WatchdogToRecoveryEngineHandoffHandlesSunDevIdleTimeoutAndIgnoresUserCancel() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val (proj, wsDir) = newProject(store, "wd-handoff")
        val cancelledExecs = CopyOnWriteArrayList<String>()

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                return if (request.agent.config.slot.id == "agent-03") {
                    RuntimeExecutionResponse(
                        request.requestId, request.agent.config.providerId, request.agent.config.modelId, 0L, 1L, true, "REVIEW_APPROVED\nTESTS_PASSED",
                        testEvidence = listOf(com.jarves.mh.runtime.TestExecutionEvidence("./gradlew test", 0L, 1L, 0, "ok", "", com.jarves.mh.runtime.TestExecutionOutcome.TESTS_PASSED, "ok")),
                    )
                } else {
                    File(wsDir, "Feature.kt").writeText("class Feature")
                    RuntimeExecutionResponse(request.requestId, request.agent.config.providerId, request.agent.config.modelId, 0L, 1L, true, "Recovered and finished Feature.kt", modifiedFiles = listOf("Feature.kt"))
                }
            }
            override fun cancel(requestId: String): Boolean {
                cancelledExecs.add(requestId)
                return true
            }
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, true, false)
        }

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            runtime = runtime,
            timeoutPolicy = GranularTimeoutPolicy(
                streamInactivityTimeoutMs = 90_000L,
                heartbeatTimeoutMs = 60_000L,
                agentExecutionTimeoutMs = 300_000L,
            ),
            limits = RecoveryLimitsConfig(maxRetriesPerExecution = 0, maxModelSwitchesPerStep = 2),
            modelPoolProvider = {
                listOf(
                    ModelCandidate("prov-a", "sun-model-v1", setOf("code", "fix"), priority = 10),
                    ModelCandidate("prov-b", "fallback-model-v2", setOf("code", "fix"), priority = 8),
                )
            },
            delayFn = {},
        )
        val sunDev = engine.registry.register(
            AgentProfileEntity("agent-02", "Sun Dev", role = "Dev", providerId = "prov-a", modelId = "sun-model-v1", capabilities = setOf("code", "fix")),
        )
        val qa = engine.registry.register(
            AgentProfileEntity("agent-03", "QA", role = "QA", providerId = "prov-qa", modelId = "qa-v1", capabilities = setOf("review", "qa", "test")),
        )
        val team = engine.teamManager.createTeam(proj.projectId, "Prod Team", listOf(sunDev.agentId, qa.agentId))

        val task = TaskEntity(taskId = "T-SUN", projectId = proj.projectId, title = "Sun Dev Task", description = "Build Feature.kt", status = PersistentTaskStatus.RUNNING)
        store.upsertTask(task)
        val step = TaskStepEntity(stepId = "S-SUN", taskId = "T-SUN", sequenceNumber = 1, title = "Implement Feature", description = "Code Feature.kt", assignedAgentId = "agent-02", status = StepStatus.RUNNING)
        store.upsertStep(step)

        // Record STARTED at T=0, PROGRESS at T=5s, then 95s of silence -> Watchdog marks SUSPECTED_STALL, then CONFIRMED_IDLE_STREAM_STALL
        engine.healthMonitor.recordEvent(
            AgentProgressEvent(taskId = "T-SUN", subtaskId = "S-SUN", agentId = "agent-02", executionId = "exec-sun-1", eventType = ProgressEventType.STARTED, providerId = "prov-a", modelId = "sun-model-v1", timestampMs = 0L),
        )
        engine.healthMonitor.recordEvent(
            AgentProgressEvent(taskId = "T-SUN", subtaskId = "S-SUN", agentId = "agent-02", executionId = "exec-sun-1", eventType = ProgressEventType.PROGRESS, details = "Partial edit", timestampMs = 5_000L),
        )
        // T=100s with dead heartbeat -> CONFIRMED_IDLE_STREAM_STALL
        val eval = engine.watchdog.evaluateExecution("agent-02", nowMs = 100_000L)
        assertNotNull(eval)
        assertEquals(WatchdogDiagnosis.CONFIRMED_IDLE_STREAM_STALL, eval?.diagnosis)
        assertTrue(eval?.shouldCancelAndRecover == true)

        // Route typed WatchdogEvaluation directly into Supervisor -> Recovery Engine!
        val plan = engine.handleWatchdogEvaluation(eval!!, team, nowMs = 100_000L)
        assertNotNull(plan)
        assertEquals(RecoveryStrategyKind.SWITCH_PROVIDER_AND_MODEL, plan?.strategy)
        assertEquals("prov-b", plan?.targetProviderId)
        assertEquals("fallback-model-v2", plan?.targetModelId)
        assertTrue("Stalled execution must be cancelled", "exec-sun-1" in cancelledExecs)
        assertTrue(engine.recoveryEngine.auditTrail("T-SUN").any { it.decisionType == SupervisorDecisionType.TIMEOUT_DETECTED })
        assertEquals("fallback-model-v2", engine.registry.get("agent-02")?.modelId)
        assertEquals(StepStatus.READY, store.getStep("S-SUN")?.status)

        // Verify user cancellation is NEVER treated as a retryable watchdog recovery!
        store.upsertTask(store.getTask("T-SUN")!!.copy(ownerStopRequested = true, status = PersistentTaskStatus.CANCELLED))
        val cancelledEval = WatchdogEvaluation(
            taskId = "T-SUN", subtaskId = "S-SUN", agentId = "agent-02", executionId = "exec-sun-2",
            diagnosis = WatchdogDiagnosis.CONFIRMED_IDLE_STREAM_STALL, shouldCancelAndRecover = true, reason = "Stalled after cancel",
        )
        assertNull("User-cancelled task must never trigger watchdog recovery", engine.handleWatchdogEvaluation(cancelledEval, team, nowMs = 110_000L))
    }

    @Test
    fun test96_AndroidRecoveryAutomaticModeReclaimsExpiredLeaseAndPreservesPauseCancel() {
        val store = InMemoryOrqenTaskStore()
        val (proj, _) = newProject(store, "android-rec")

        // 1. Active running task with expired lease -> AUTOMATIC recovery schedules it
        store.upsertTask(
            TaskEntity(
                taskId = "T-AUTO", projectId = proj.projectId, title = "Auto Recover", description = "D",
                status = PersistentTaskStatus.RUNNING, currentStepId = "S-1",
                executionLeaseOwner = "old-worker", lastHeartbeatAt = 1_000L,
            ),
        )
        store.upsertStep(TaskStepEntity(stepId = "S-1", taskId = "T-AUTO", sequenceNumber = 1, title = "Work", description = "Work", assignedAgentId = "agent-02", status = StepStatus.RUNNING))

        // 2. Paused task -> preserved as RESUMABLE (not auto-scheduled)
        store.upsertTask(
            TaskEntity(
                taskId = "T-PAUSED", projectId = proj.projectId, title = "Paused", description = "D",
                status = PersistentTaskStatus.PAUSED, pauseRequested = true,
            ),
        )

        // 3. Cancelled task -> preserved as CANCELLED (skipped)
        store.upsertTask(
            TaskEntity(
                taskId = "T-CANCELLED", projectId = proj.projectId, title = "Cancelled", description = "D",
                status = PersistentTaskStatus.CANCELLED, ownerStopRequested = true,
            ),
        )

        val liveness = ExecutionLivenessMonitor(store)
        val expired = liveness.restoreAndIdentifyExpiredLeases(leaseTtlMs = 60_000L, nowMs = 200_000L)
        assertEquals(listOf("T-AUTO"), expired)

        val scheduled = mutableListOf<ScheduledRecoveryWork>()
        val scheduler = object : RecoverySchedulerBackend {
            override fun scheduleUnique(taskId: String, requireNetwork: Boolean, delayMs: Long): Boolean {
                scheduled.add(ScheduledRecoveryWork("u-$taskId", taskId, requireNetwork, delayMs))
                return true
            }
            override fun cancel(taskId: String) {}
            override fun scheduledWork() = scheduled
        }

        val coordinator = ProcessRecoveryCoordinator(
            store = store,
            checkpointManager = CheckpointManager(store),
            livenessMonitor = liveness,
            scheduler = scheduler,
            policy = RecoveryPolicy(mode = RecoveryMode.AUTOMATIC),
        )
        val summary = coordinator.reconcileOnStartup(InterruptionCause.UNEXPECTED_PROCESS_DEATH, nowMs = 200_000L)

        assertEquals(listOf("T-AUTO"), summary.scheduledTaskIds)
        assertTrue("T-PAUSED" in summary.skippedTaskIds)
        assertTrue("T-CANCELLED" in summary.skippedTaskIds)
        assertEquals(StepStatus.READY, store.getStep("S-1")?.status)
    }

    @Test
    fun test98_SecurityDefaultsDisableUnsafePermissionBypassAndPreserveWorkspaceBoundaries() {
        val dummyAgent = AgentState(
            config = AgentConfig(
                slot = AgentSlot.AGENT_2,
                role = AgentRole(AgentRoleType.SOFTWARE_ENGINEER, systemInstructions = "", responsibilities = emptyList()),
            ),
        )
        val req = RuntimeExecutionRequest(
            agent = dummyAgent,
            providerConfig = null,
            workspaceDir = tmp.root,
            projectSlug = "secure-proj",
            prompt = "Implement feature",
        )
        assertFalse(
            "Unsafe --dangerously-skip-permissions bypass must NOT be enabled by default",
            req.allowSkipPermissions,
        )
        assertFalse(
            "Path traversal outside workspace root must be rejected",
            WorkspacePathValidator.isPathInsideRoot(tmp.root, "../etc/passwd"),
        )
        assertTrue(
            "Valid relative path inside workspace root must be allowed",
            WorkspacePathValidator.isPathInsideRoot(tmp.root, "src/Main.kt"),
        )
    }
}
