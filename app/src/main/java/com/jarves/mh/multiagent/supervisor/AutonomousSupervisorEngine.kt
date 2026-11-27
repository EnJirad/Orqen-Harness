package com.jarves.mh.multiagent.supervisor

import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.LockAcquisitionResult
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.recovery.ExecutionLivenessMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SupervisorOperationalStatus {
    IDLE,
    PLANNING,
    SUPERVISING,
    RECOVERING,
    REASSIGNING,
    VERIFYING,
    COMPLETED,
    BLOCKED,
    PAUSED,
    CANCELLED,
}

data class SupervisorUiSnapshot(
    val taskId: String,
    val status: SupervisorOperationalStatus,
    val statusDetail: String,
    val agentHealth: Map<String, AgentHealthRecord>,
    val recoveryHistory: List<SupervisorDecisionRecord>,
    val completedSteps: Int,
    val totalSteps: Int,
    val activeAgentId: String? = null,
    val activeAgentName: String? = null,
    val activeStepTitle: String? = null,
    val activePhase: String = status.name,
    val efficiencyMetrics: TaskEfficiencyMetrics = TaskEfficiencyMetrics(),
    val spvConnectionState: ModelConnectionState = ModelConnectionState.CONNECTED,
    val devConnectionState: ModelConnectionState = ModelConnectionState.CONNECTED,
)

data class StepLifecycleUpdate(
    val projectId: String,
    val taskId: String,
    val stepId: String,
    val stepTitle: String,
    val agentId: String,
    val agentDisplayName: String,
    val phase: String, // PLANNED, STARTED, PROGRESS, COMPLETED, REVIEW_REJECTED, FAILED, RECOVERING
    val detail: String = "",
    val modifiedFiles: List<String> = emptyList(),
    val stepNumber: Int = 0,
    val totalSteps: Int = 0,
)

/**
 * AI Supervisor Advisory Layer (strictly separated from the Deterministic Control Layer).
 * If the primary Supervisor model fails or times out, the Deterministic Control Layer
 * preserves all task state and falls back to the next eligible Supervisor model.
 */
interface SupervisorAdvisor {
    suspend fun evaluateMissingRequirements(
        supervisorModel: ModelCandidate,
        task: TaskEntity,
        completedSteps: List<TaskStepEntity>,
        workspaceDir: File,
    ): List<PlannedStep>
}

object DeterministicNoOpAdvisor : SupervisorAdvisor {
    override suspend fun evaluateMissingRequirements(
        supervisorModel: ModelCandidate,
        task: TaskEntity,
        completedSteps: List<TaskStepEntity>,
        workspaceDir: File,
    ): List<PlannedStep> = emptyList()
}

/**
 * ORQEN-Harness PART A.2 — Autonomous Supervisor Engine.
 *
 * Continuously owns the user's objective until verified complete or safely blocked:
 * - Deterministic Control Layer: persistence, leases, file locks, watchdog, recovery limits,
 *   pause/resume/cancel, owner-approval boundary enforcement, duplicate-execution guard.
 * - AI Execution & Failover: executes steps through real [AIExecutionRuntime], monitors health
 *   and idle streams via [AgentHealthMonitor] + [TaskWatchdog], recovers via [AutonomousRecoveryEngine]
 *   (retry -> same-provider model switch -> cross-provider failover -> agent reassignment),
 *   handles reviewer rejection & test failure correction loops, and verifies final deliverables
 *   with automatic follow-up subtask creation when requirements remain missing.
 */
class AutonomousSupervisorEngine(
    private val taskStore: OrqenTaskStore,
    private val collabStore: CollaborationStore,
    private val runtime: AIExecutionRuntime,
    private val checkpointManager: CheckpointManager = CheckpointManager(taskStore),
    private val fileLockManager: FileLockManager = FileLockManager(),
    private val livenessMonitor: ExecutionLivenessMonitor = ExecutionLivenessMonitor(taskStore),
    private val planner: DynamicTeamPlanCompiler = DynamicTeamPlanCompiler(),
    private val timeoutPolicy: GranularTimeoutPolicy = GranularTimeoutPolicy(),
    private val limits: RecoveryLimitsConfig = RecoveryLimitsConfig(),
    private val modelPoolProvider: () -> List<ModelCandidate> = { emptyList() },
    private val providerConfigResolver: (providerId: String) -> com.jarves.mh.provider.core.ProviderConfig? = { null },
    private val supervisorModelPoolProvider: () -> List<ModelCandidate> = { emptyList() },
    private val unavailableProviders: () -> Set<String> = { emptySet() },
    private val advisor: SupervisorAdvisor = DeterministicNoOpAdvisor,
    private val hasPendingOwnerApproval: (taskId: String) -> Boolean = { tid ->
        collabStore.listApprovalsForTask(tid).any { it.status == ApprovalStatus.PENDING }
    },
    private val chatProjectionSink: TeamChatProjectionSink? = null,
    private val onStepLifecycleUpdate: (StepLifecycleUpdate) -> Unit = {},
    private val mcpManager: com.jarves.mh.mcp.McpConnectionManager? = null,
    private val delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    val registry = AgentRegistry(collabStore)
    val teamManager = AgentTeamManager(collabStore, registry)
    val capabilityResolver = AgentCapabilityResolver(unavailableProviders)
    val delegationManager = AgentDelegationManager(collabStore, taskStore, capabilityResolver)
    val communicationManager = AgentCommunicationManager(taskStore)
    val reviewManager = AgentReviewManager(collabStore, taskStore)
    val failureManager = AgentFailureManager(collabStore, taskStore, capabilityResolver)
    val healthMonitor = AgentHealthMonitor(taskStore)
    val watchdog = TaskWatchdog(healthMonitor, timeoutPolicy)
    val assignmentLedger = AssignmentResultLedger()
    val recoveryEngine = AutonomousRecoveryEngine(
        taskStore = taskStore,
        healthMonitor = healthMonitor,
        livenessMonitor = livenessMonitor,
        fileLockManager = fileLockManager,
        capabilityResolver = capabilityResolver,
        modelPoolProvider = modelPoolProvider,
        unavailableProviders = unavailableProviders,
        limits = limits,
    )

    private val activeSupervisedTasks = ConcurrentHashMap.newKeySet<String>()
    private val _snapshots = MutableStateFlow<Map<String, SupervisorUiSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, SupervisorUiSnapshot>> = _snapshots.asStateFlow()
    private val efficiencyMetricsByTask = ConcurrentHashMap<String, TaskEfficiencyMetrics>()
    private val spvConnectionStates = ConcurrentHashMap<String, ModelConnectionState>()
    private val devConnectionStates = ConcurrentHashMap<String, ModelConnectionState>()

    fun snapshotFor(taskId: String): SupervisorUiSnapshot? = _snapshots.value[taskId]

    fun efficiencyMetricsFor(taskId: String): TaskEfficiencyMetrics =
        efficiencyMetricsByTask[taskId] ?: TaskEfficiencyMetrics()

    fun connectionStateFor(taskId: String, isSupervisor: Boolean): ModelConnectionState =
        if (isSupervisor) spvConnectionStates[taskId] ?: ModelConnectionState.CONNECTED
        else devConnectionStates[taskId] ?: ModelConnectionState.CONNECTED

    /**
     * Marks an agent as healthy/connected (e.g. after a successful Chat Consultation reply)
     * and refreshes active snapshots so the Supervisor UI never displays stale disconnect badges.
     */
    fun markAgentConnected(agentId: String, providerId: String = "", modelId: String = "") {
        healthMonitor.markHealthy(agentId, providerId, modelId, nowProvider())
        val isSpv = agentId == AgentSlot.AGENT_1.id
        val current = LinkedHashMap(_snapshots.value)
        current.keys.forEach { tid ->
            if (isSpv) spvConnectionStates[tid] = ModelConnectionState.CONNECTED
            else devConnectionStates[tid] = ModelConnectionState.CONNECTED
            val snap = current[tid] ?: return@forEach
            current[tid] = snap.copy(
                agentHealth = healthMonitor.snapshotAll(),
                spvConnectionState = spvConnectionStates[tid] ?: snap.spvConnectionState,
                devConnectionState = devConnectionStates[tid] ?: snap.devConnectionState,
            )
        }
        _snapshots.value = current
    }

    private fun updateMetrics(taskId: String, transform: (TaskEfficiencyMetrics) -> TaskEfficiencyMetrics) {
        efficiencyMetricsByTask.compute(taskId) { _, cur ->
            transform(cur ?: TaskEfficiencyMetrics())
        }
    }

    /**
     * Phase 2 (Part A.5): Creates and persists the task + plan BEFORE execution starts,
     * with idempotent duplicate-submission protection for active tasks on the same project.
     */
    fun preparePlan(
        projectId: String,
        team: AgentTeamEntity,
        objective: String,
        requestedSubtasks: List<String> = emptyList(),
        acceptanceCriteria: List<String> = emptyList(),
        attachmentPaths: List<String> = emptyList(),
        budget: BudgetConfiguration = BudgetConfiguration(),
        completionCriteria: TaskCompletionCriteria = TaskCompletionCriteria(
            requireReviewerApproval = team.requireIndependentReview,
            requireTestsPass = true,
            requireDeliverableVerification = true,
            acceptanceChecks = acceptanceCriteria,
        ),
    ): TaskEntity? {
        val planStartMs = nowProvider()
        val project = taskStore.getProject(projectId) ?: return null
        val allTeamMembers = teamManager.getTeamProfiles(team.teamId, onlyEnabled = false)
        val candidates = teamManager.getTeamProfiles(team.teamId, onlyEnabled = true)
        if (candidates.isEmpty()) return null

        val normalizedObj = objective.trim()
        // Idempotency guard: if an active non-terminal task for the exact same project & objective
        // already exists, reuse it (merging any new attachment paths) instead of creating duplicate tasks!
        val existingActive = taskStore.listTasksForProject(projectId).firstOrNull { existing ->
            existing.description.trim().equals(normalizedObj, ignoreCase = true) &&
                existing.status in setOf(
                    PersistentTaskStatus.QUEUED,
                    PersistentTaskStatus.PLANNING,
                    PersistentTaskStatus.READY,
                    PersistentTaskStatus.RUNNING,
                    PersistentTaskStatus.RECOVERING,
                )
        }
        if (existingActive != null) {
            if (attachmentPaths.isNotEmpty()) {
                val merged = existingActive.copy(
                    taskMemory = TaskAttachmentCodec.encodeIntoTaskMemory(existingActive.taskMemory, attachmentPaths),
                    updatedAt = nowProvider(),
                )
                taskStore.upsertTask(merged)
                return merged
            }
            return existingActive
        }

        // Phase 4: SPV inspects the real workspace, creates `.orqen/plan/overview.md` + `part_N_*.md`,
        // and strictly verifies plan integrity BEFORE creating or delegating any SQLite steps!
        val wsDir = File(project.workspacePath).apply { mkdirs() }
        val isAndroid = File(wsDir, "app/build.gradle.kts").isFile ||
            File(wsDir, "app/build.gradle").isFile ||
            normalizedObj.contains("android", ignoreCase = true) ||
            normalizedObj.contains("apk", ignoreCase = true)
        val leaderAgentId = candidates.firstOrNull { "lead" in it.capabilities || "architect" in it.capabilities }?.agentId
            ?: candidates.first().agentId
        // Strict Role Ownership: In a multi-agent team, never silently fall back to SPV when all
        // Developer members are disabled — fail explicitly with an actionable assignment error.
        val hasDisabledDeveloper = allTeamMembers.any {
            !it.isEnabled && it.agentId != leaderAgentId && ("code" in it.capabilities || it.role.contains("dev", ignoreCase = true))
        }
        val nonLeaderDev = capabilityResolver.selectBestAgent(candidates, setOf("code"), excludeAgentIds = setOf(leaderAgentId))
            ?: candidates.firstOrNull { it.agentId != leaderAgentId && ("code" in it.capabilities || it.role.contains("dev", ignoreCase = true)) }
        if (nonLeaderDev == null && hasDisabledDeveloper) {
            throw IllegalStateException(
                "Role assignment error: No enabled Developer agent is available in team '${team.teamName}'. " +
                    "Enable a Developer agent instead of silently assigning implementation to SPV ($leaderAgentId).",
            )
        }
        val devAgentId = nonLeaderDev?.agentId
            ?: capabilityResolver.selectBestAgent(candidates, setOf("code"))?.agentId
            ?: candidates.firstOrNull { it.agentId != leaderAgentId }?.agentId
            ?: candidates.first().agentId
        val coderAgentIds = candidates
            .filter { it.agentId != leaderAgentId && ("code" in it.capabilities || it.role.contains("dev", ignoreCase = true) || it.role.contains("engineer", ignoreCase = true) || it.role.contains("specialist", ignoreCase = true)) }
            .map { it.agentId }
            .distinct()
            .ifEmpty { listOf(devAgentId) }
        // 2-Agent Team Rule: Manager acts as Planner + Supervisor + Independent QA when only Manager + Dev exist.
        val qaAgentId = capabilityResolver.selectBestAgent(candidates, setOf("review", "qa"), excludeAgentIds = setOf(devAgentId))?.agentId
            ?: capabilityResolver.selectBestAgent(candidates, setOf("review", "qa"))?.agentId
            ?: candidates.firstOrNull { it.agentId != devAgentId }?.agentId
            ?: candidates.last().agentId

        val preTaskId = "T-${UUID.randomUUID().toString().take(8).uppercase()}"
        val existingValidPlan = MultiPartPlanManager.loadPlan(wsDir)?.takeIf { existing ->
            existing.objective.equals(normalizedObj, ignoreCase = true) &&
                MultiPartPlanManager.verifyPlanIntegrity(wsDir, existing).valid
        }
        val mpPlan = existingValidPlan ?: MultiPartPlanManager.createPlan(
            projectId = projectId,
            taskId = preTaskId,
            projectName = project.projectName,
            objective = normalizedObj,
            workspaceDir = wsDir,
            explicitSubtasks = requestedSubtasks,
            isAndroidProject = isAndroid,
            leaderAgentId = leaderAgentId,
            devAgentId = devAgentId,
            qaAgentId = qaAgentId,
            coderAgentIds = coderAgentIds,
        )
        val planValidation = if (existingValidPlan != null) {
            MultiPartPlanManager.verifyPlanIntegrity(wsDir, mpPlan)
        } else {
            MultiPartPlanManager.persistPlan(wsDir, mpPlan)
        }
        require(planValidation.valid) {
            "SPV refused delegation because plan verification failed: ${planValidation.errors.joinToString("; ")}"
        }

        val rawPlan = planner.planFromMultiPart(project, mpPlan, wsDir)
        val workloads = mutableMapOf<String, Int>()
        val enabledById = candidates.associateBy { it.agentId }
        val assignedSteps = rawPlan.steps.map { step ->
            val preAssigned = if (!step.requiresReview && step.assignedAgentId in coderAgentIds) {
                enabledById[step.assignedAgentId]
            } else null
            val exclude = when {
                step.requiresReview -> setOf(devAgentId)
                candidates.size > 1 -> setOf(leaderAgentId)
                else -> emptySet()
            }
            val selected = preAssigned
                ?: capabilityResolver.selectBestAgent(
                    candidates = candidates,
                    requiredCapabilities = step.requiredCapabilities,
                    excludeAgentIds = exclude,
                    activeWorkloads = workloads,
                ) ?: capabilityResolver.selectBestAgent(candidates, step.requiredCapabilities, activeWorkloads = workloads)
                ?: candidates.firstOrNull { it.agentId == if (step.requiresReview) qaAgentId else devAgentId }
                ?: candidates.first()
            workloads[selected.agentId] = (workloads[selected.agentId] ?: 0) + 1
            step.copy(assignedAgentId = selected.agentId)
        }
        val plannedTask = planner.persistPlan(
            store = taskStore,
            projectId = projectId,
            plan = rawPlan.copy(steps = assignedSteps),
            budget = budget,
            planId = mpPlan.planId,
            planVersion = mpPlan.version,
        ).let { t ->
            val updated = t.copy(
                completionCriteria = completionCriteria,
                taskMemory = TaskAttachmentCodec.encodeIntoTaskMemory(t.taskMemory, attachmentPaths),
            )
            taskStore.upsertTask(updated)
            updated
        }
        // P0 Fix: Mandatory post-persist validation before leaving PLANNING or delegating Step 1.
        val persistedSteps = taskStore.listStepsForTask(plannedTask.taskId)
        require(persistedSteps.isNotEmpty() && persistedSteps.size == assignedSteps.size) {
            "Plan persistence verification failed for task ${plannedTask.taskId}: expected ${assignedSteps.size} step(s), found ${persistedSteps.size}."
        }
        require(persistedSteps.all { it.taskId == plannedTask.taskId }) {
            "Plan persistence verification failed: persisted step taskId mismatch for ${plannedTask.taskId}."
        }
        require(persistedSteps.any { it.status == StepStatus.READY || it.dependencies.isEmpty() }) {
            "Plan persistence verification failed: no dispatchable Step 1 found for ${plannedTask.taskId}."
        }
        val planElapsedMs = (nowProvider() - planStartMs).coerceAtLeast(0L)
        updateMetrics(plannedTask.taskId) { it.copy(planningDurationMs = it.planningDurationMs + planElapsedMs) }
        spvConnectionStates[plannedTask.taskId] = ModelConnectionState.CONNECTED
        devConnectionStates[plannedTask.taskId] = ModelConnectionState.CONNECTED
        updateSnapshot(
            taskId = plannedTask.taskId,
            status = SupervisorOperationalStatus.PLANNING,
            detail = "Plan approved with ${persistedSteps.size} steps — handing off Step 1",
            activeAgentId = leaderAgentId,
            activeAgentName = candidates.firstOrNull { it.agentId == leaderAgentId }?.displayName ?: "SPV",
            activeStepTitle = plannedTask.title,
            activePhase = "PLANNING",
        )
        val planSummary = assignedSteps.joinToString("\n") { s ->
            val name = candidates.firstOrNull { it.agentId == s.assignedAgentId }?.displayName ?: s.assignedAgentId
            "• #${s.sequenceNumber} ${s.title} → $name"
        }
        onStepLifecycleUpdate(
            StepLifecycleUpdate(
                projectId = projectId,
                taskId = plannedTask.taskId,
                stepId = assignedSteps.firstOrNull()?.stepId.orEmpty(),
                stepTitle = plannedTask.title,
                agentId = candidates.first().agentId,
                agentDisplayName = candidates.first().displayName,
                phase = "PLANNED",
                detail = "วางแผนงานเสร็จแล้ว (${assignedSteps.size} ขั้นตอน):\n$planSummary",
            ),
        )
        return plannedTask
    }

    /**
     * Submits a user objective once, creates a validated persistent plan, publishes the Task ID
     * via [onTaskCreated] BEFORE execution starts, and runs the autonomous supervision loop
     * until verified COMPLETED, PAUSED, CANCELLED, or BLOCKED.
     */
    suspend fun submitAndSupervise(
        projectId: String,
        team: AgentTeamEntity,
        objective: String,
        requestedSubtasks: List<String> = emptyList(),
        acceptanceCriteria: List<String> = emptyList(),
        requiredDeliverableFiles: List<String> = emptyList(),
        attachmentPaths: List<String> = emptyList(),
        budget: BudgetConfiguration = BudgetConfiguration(),
        completionCriteria: TaskCompletionCriteria = TaskCompletionCriteria(
            requireReviewerApproval = team.requireIndependentReview,
            requireTestsPass = true,
            requireDeliverableVerification = true,
            acceptanceChecks = acceptanceCriteria,
        ),
        onTaskCreated: (TaskEntity) -> Unit = {},
    ): TaskEntity? {
        val plannedTask = preparePlan(
            projectId = projectId,
            team = team,
            objective = objective,
            requestedSubtasks = requestedSubtasks,
            acceptanceCriteria = acceptanceCriteria,
            attachmentPaths = attachmentPaths,
            budget = budget,
            completionCriteria = completionCriteria,
        ) ?: return null

        onTaskCreated(plannedTask)

        return superviseTask(
            taskId = plannedTask.taskId,
            team = team,
            requiredDeliverableFiles = requiredDeliverableFiles,
        )
    }

    /**
     * Phase 1 (Part A.5.1): Persistent Autonomous Project Queue Continuation.
     *
     * Automatically drains all eligible QUEUED / READY / RECOVERING tasks for [projectId]
     * from [OrqenTaskStore] in deterministic order (priority DESC, createdAt ASC) without
     * requiring another user message. Never restarts COMPLETED, CANCELLED, PAUSED, BLOCKED,
     * FAILED, or owner-stopped tasks.
     */
    suspend fun superviseProjectQueue(
        projectId: String,
        team: AgentTeamEntity,
        requiredDeliverableFilesByTask: Map<String, List<String>> = emptyMap(),
        onTaskStarted: (TaskEntity) -> Unit = {},
        onTaskFinished: (TaskEntity) -> Unit = {},
    ): List<TaskEntity> {
        val processed = mutableListOf<TaskEntity>()
        val attemptedIds = mutableSetOf<String>()
        while (currentCoroutineContext().isActive) {
            val nextEligible = taskStore.listTasksForProject(projectId)
                .filter { task ->
                    !task.ownerStopRequested &&
                        !task.pauseRequested &&
                        task.taskId !in activeSupervisedTasks &&
                        task.taskId !in attemptedIds &&
                        task.status in setOf(
                            PersistentTaskStatus.QUEUED,
                            PersistentTaskStatus.READY,
                            PersistentTaskStatus.RECOVERING,
                        )
                }
                .sortedWith(compareByDescending<TaskEntity> { it.priority }.thenBy { it.createdAt })
                .firstOrNull() ?: break

            attemptedIds.add(nextEligible.taskId)
            onTaskStarted(nextEligible)
            val outcome = superviseTask(
                taskId = nextEligible.taskId,
                team = team,
                requiredDeliverableFiles = requiredDeliverableFilesByTask[nextEligible.taskId].orEmpty(),
            ) ?: continue
            processed.add(outcome)
            onTaskFinished(outcome)
        }
        return processed
    }

    /**
     * Event-driven, persistent Autonomous Supervisor Control Loop.
     * Can be called after initial submission OR after Android process recreation / restart
     * to resume an existing active task without requiring a new user prompt.
     */
    suspend fun superviseTask(
        taskId: String,
        team: AgentTeamEntity,
        requiredDeliverableFiles: List<String> = emptyList(),
    ): TaskEntity? {
        if (!activeSupervisedTasks.add(taskId)) {
            taskStore.insertTaskEvent(
                TaskEventEntity(
                    taskId = taskId,
                    eventType = "DUPLICATE_SUPERVISION_PREVENTED",
                    eventPayload = "Task $taskId is already actively supervised.",
                ),
            )
            return taskStore.getTask(taskId)
        }

        val loopStartedAt = nowProvider()
        var windowStartedAt = loopStartedAt
        var sessionActiveExecutionMs = 0L
        var reviewRejectCycles = 0
        var replanAttempts = 0
        var lastReplanProgressSignature: String? = null
        val emittedStartedSteps = mutableSetOf<String>()

        try {
            val initialTask = taskStore.getTask(taskId) ?: return null
            val project = taskStore.getProject(initialTask.projectId) ?: return initialTask
            val workspaceDir = File(project.workspacePath).apply { mkdirs() }

            // Transactional start check respecting owner cancel/pause
            val startedTask = taskStore.runInTransaction {
                val fresh = taskStore.getTask(taskId) ?: return@runInTransaction null
                if (fresh.ownerStopRequested || fresh.status == PersistentTaskStatus.CANCELLED ||
                    fresh.pauseRequested || fresh.status == PersistentTaskStatus.PAUSED
                ) {
                    return@runInTransaction fresh
                }
                val running = fresh.copy(
                    status = PersistentTaskStatus.RUNNING,
                    startedAt = fresh.startedAt ?: nowProvider(),
                    lastHeartbeatAt = nowProvider(),
                    updatedAt = nowProvider(),
                )
                taskStore.upsertTask(running)
                running
            } ?: return null

            if (startedTask.ownerStopRequested || startedTask.status == PersistentTaskStatus.CANCELLED) {
                updateSnapshot(taskId, SupervisorOperationalStatus.CANCELLED, "Owner cancelled")
                return startedTask
            }
            if (startedTask.pauseRequested || startedTask.status == PersistentTaskStatus.PAUSED) {
                updateSnapshot(taskId, SupervisorOperationalStatus.PAUSED, "Owner paused")
                return startedTask
            }

            while (currentCoroutineContext().isActive) {
                val task = taskStore.getTask(taskId) ?: break

                // 1. Respect owner cancellation & pause
                if (task.ownerStopRequested || task.status == PersistentTaskStatus.CANCELLED) {
                    updateSnapshot(taskId, SupervisorOperationalStatus.CANCELLED, "Cancelled by owner")
                    return task
                }
                if (task.pauseRequested || task.status == PersistentTaskStatus.PAUSED) {
                    updateSnapshot(taskId, SupervisorOperationalStatus.PAUSED, "Paused by owner")
                    return task
                }

                // 2. Respect existing security & owner-approval boundaries (autonomy never bypasses approvals!)
                if (hasPendingOwnerApproval(taskId)) {
                    return blockTask(task, "Awaiting required owner approval for sensitive operation.")
                }

                // 3. Respect budget & total duration limits
                if (task.budgetConfiguration.autoPauseOnBudgetExceeded &&
                    task.actualCostUsd >= task.budgetConfiguration.maxTaskBudgetUsd
                ) {
                    val paused = task.copy(
                        status = PersistentTaskStatus.PAUSED,
                        pauseRequested = true,
                        lastError = "Task budget limit reached ($${String.format("%.4f", task.actualCostUsd)}).",
                        updatedAt = nowProvider(),
                    )
                    taskStore.upsertTask(paused)
                    updateSnapshot(taskId, SupervisorOperationalStatus.PAUSED, paused.lastError.orEmpty())
                    return paused
                }
                val wallClockElapsedMs = (nowProvider() - loopStartedAt).coerceAtLeast(0L)
                val windowElapsedMs = (nowProvider() - windowStartedAt).coerceAtLeast(0L)
                updateMetrics(taskId) { it.copy(wallClockDurationMs = wallClockElapsedMs) }
                val activeBudgetExceeded = sessionActiveExecutionMs > limits.maxActiveExecutionBudgetMs
                val wallClockBudgetExceeded = windowElapsedMs > limits.maxTotalTaskDurationMs
                if (activeBudgetExceeded || wallClockBudgetExceeded) {
                    if (limits.autoContinueAfterCheckpoint) {
                        // Continuous Rolling Execution: persist durable checkpoint, refresh lease/heartbeat,
                        // reset slice window, and keep executing seamlessly past 1 hour with zero wasted tokens!
                        val rolloverCheckpoint = checkpointManager.buildCheckpoint(
                            taskId = taskId,
                            stepId = task.currentStepId ?: "rolling-checkpoint",
                            workspaceDir = workspaceDir,
                            modifiedFiles = emptyList(),
                            contextSummary = "Rolling execution checkpoint (sliceActive=${sessionActiveExecutionMs}ms, totalWall=${wallClockElapsedMs}ms). Continuing autonomously.",
                        )
                        taskStore.insertCheckpoint(rolloverCheckpoint)
                        taskStore.upsertTask(task.copy(lastHeartbeatAt = nowProvider(), updatedAt = nowProvider()))
                        taskStore.insertTaskEvent(
                            TaskEventEntity(
                                taskId = taskId,
                                eventType = "TASK_CHECKPOINT_ROLLOVER",
                                eventPayload = "Auto-checkpointed after ${windowElapsedMs}ms slice (totalWall=${wallClockElapsedMs}ms); continuing execution window.",
                            ),
                        )
                        sessionActiveExecutionMs = 0L
                        windowStartedAt = nowProvider()
                    } else if (limits.pauseOnDurationBudgetExceeded) {
                        val budgetCheckpoint = checkpointManager.buildCheckpoint(
                            taskId = taskId,
                            stepId = task.currentStepId ?: "budget-checkpoint",
                            workspaceDir = workspaceDir,
                            modifiedFiles = emptyList(),
                            contextSummary = "Safe execution budget checkpoint (active=${sessionActiveExecutionMs}ms, wall=${wallClockElapsedMs}ms). Resume continues from latest step.",
                        )
                        taskStore.insertCheckpoint(budgetCheckpoint)
                        val paused = task.copy(
                            status = PersistentTaskStatus.PAUSED,
                            pauseRequested = false,
                            executionLeaseOwner = null,
                            lastError = "Execution budget checkpoint reached (active=${sessionActiveExecutionMs}ms, wall=${wallClockElapsedMs}ms). Safe to resume from latest step.",
                            updatedAt = nowProvider(),
                        )
                        taskStore.upsertTask(paused)
                        taskStore.insertTaskEvent(
                            TaskEventEntity(
                                taskId = taskId,
                                eventType = "TASK_CHECKPOINT_PAUSED",
                                eventPayload = paused.lastError.orEmpty(),
                            ),
                        )
                        updateSnapshot(taskId, SupervisorOperationalStatus.PAUSED, paused.lastError.orEmpty(), activePhase = "PAUSED")
                        return paused
                    } else {
                        return blockTask(task, "Exceeded maximum autonomous task duration (${limits.maxTotalTaskDurationMs}ms).")
                    }
                }

                // 4. Load steps & self-recover if an active task ever lost its steps ("No steps planned" guard!)
                var allSteps = taskStore.listStepsForTask(taskId)
                if (allSteps.isEmpty() && replanAttempts < limits.maxReplanAttempts) {
                    replanAttempts++
                    val recoveredMp = MultiPartPlanManager.loadPlan(workspaceDir)
                        ?: MultiPartPlanManager.migrateLegacyPlanIfNeeded(workspaceDir, project.projectId, taskId, project.projectName)
                        ?: MultiPartPlanManager.createPlan(
                            projectId = project.projectId,
                            taskId = taskId,
                            projectName = project.projectName,
                            objective = task.description.ifBlank { task.title },
                            workspaceDir = workspaceDir,
                            isAndroidProject = task.completionCriteria.requireBuildPass,
                        ).also { MultiPartPlanManager.persistPlan(workspaceDir, it) }
                    val repairedPlan = planner.planFromMultiPart(project, recoveredMp)
                    appendFollowUpSteps(task, team, emptyList(), repairedPlan.steps, "Recovered missing planned steps from persistent Multi-Part plan")
                    allSteps = taskStore.listStepsForTask(taskId)
                }
                // Guard against circular step dependencies before scheduling
                if (DependencyResolver.hasCycle(allSteps.associate { it.stepId to it.dependencies })) {
                    return blockTask(task, "Circular dependency detected in task step graph.")
                }

                val completedIds = allSteps.filter { it.status == StepStatus.COMPLETED || it.status == StepStatus.SKIPPED }
                    .map { it.stepId }
                    .toSet()

                val runnableSteps = allSteps.filter { step ->
                    (step.status == StepStatus.PENDING || step.status == StepStatus.READY) &&
                        step.dependencies.all { it in completedIds }
                }

                // Execute independent non-review subtasks concurrently up to team.maxConcurrentAgents
                // across distinct coding agents (FileLockManager enforces per-file write safety at runtime!)
                val parallelBatch = if (runnableSteps.size > 1 && team.maxConcurrentAgents > 1) {
                    val seenAgents = mutableSetOf<String>()
                    val selected = mutableListOf<TaskStepEntity>()
                    for (s in runnableSteps) {
                        if (selected.size >= team.maxConcurrentAgents) break
                        if (s.requiresReview) continue
                        if (taskStore.findIdempotency(s.idempotencyKey) != null) continue
                        if (s.assignedAgentId in seenAgents) continue
                        selected.add(s)
                        seenAgents.add(s.assignedAgentId)
                    }
                    selected
                } else emptyList()

                if (parallelBatch.size > 1) {
                    val teamProfiles = teamManager.getTeamProfiles(team.teamId, onlyEnabled = true)
                    val accessibleImages = TaskAttachmentCodec.resolveAccessibleFiles(task.taskMemory, workspaceDir)
                    val parallelNames = parallelBatch.mapNotNull { st ->
                        teamProfiles.firstOrNull { it.agentId == st.assignedAgentId }?.displayName ?: st.assignedAgentId
                    }.distinct().joinToString(" + ")
                    updateSnapshot(
                        taskId = taskId,
                        status = SupervisorOperationalStatus.SUPERVISING,
                        detail = "กำลังรันเขียนโค้ดแบบขนาน (${parallelBatch.size} ส่วนพร้อมกัน: $parallelNames)",
                        activeAgentId = parallelBatch.first().assignedAgentId,
                        activeAgentName = parallelNames,
                        activeStepTitle = parallelBatch.joinToString(" | ") { it.title }.take(120),
                        activePhase = "PARALLEL_EXECUTING",
                    )
                    var anyLaunched = false
                    coroutineScope {
                        parallelBatch.map { pStep ->
                            async {
                                val prof = teamProfiles.firstOrNull { it.agentId == pStep.assignedAgentId }
                                    ?: teamProfiles.firstOrNull() ?: return@async
                                val execId = "exec-${UUID.randomUUID().toString().take(10)}"
                                val lease = livenessMonitor.tryAcquireLease(taskId, pStep.stepId, prof.agentId, timeoutPolicy.agentExecutionTimeoutMs, nowProvider())
                                    ?: return@async
                                val locked = mutableListOf<String>()
                                for (path in pStep.targetFiles) {
                                    if (fileLockManager.tryAcquireByAgentId(path, prof.agentId, taskId = taskId, reason = "Parallel ${pStep.title}") is LockAcquisitionResult.Success) {
                                        locked.add(path)
                                    } else {
                                        locked.forEach { fileLockManager.releaseByAgentId(it, prof.agentId) }
                                        livenessMonitor.releaseLease(taskId, pStep.stepId, prof.agentId)
                                        return@async
                                    }
                                }
                                anyLaunched = true
                                try {
                                    val beforeSnap = checkpointManager.snapshotWorkspace(workspaceDir)
                                    val runStep = pStep.copy(status = StepStatus.RUNNING, startedAt = nowProvider())
                                    val execRec = AgentExecutionEntity(execId, taskId, pStep.stepId, prof.agentId, prof.providerId, prof.modelId, "RUNNING")
                                    taskStore.runInTransaction {
                                        taskStore.upsertStep(runStep)
                                        taskStore.insertExecution(execRec)
                                    }
                                    healthMonitor.markHealthy(prof.agentId, prof.providerId, prof.modelId, nowProvider())
                                    onStepLifecycleUpdate(
                                        StepLifecycleUpdate(
                                            projectId = project.projectId,
                                            taskId = taskId,
                                            stepId = runStep.stepId,
                                            stepTitle = runStep.title,
                                            agentId = prof.agentId,
                                            agentDisplayName = prof.displayName,
                                            phase = "STARTED",
                                            detail = "Parallel coding: ${runStep.title}",
                                            stepNumber = runStep.sequenceNumber,
                                            totalSteps = allSteps.size,
                                        ),
                                    )
                                    val ctx = TaskContextBuilder.buildAgentStepContext(
                                        project = project,
                                        task = task,
                                        step = runStep,
                                        allSteps = allSteps,
                                        latestCheckpoint = taskStore.getLatestCheckpoint(taskId),
                                        communicationManager = communicationManager,
                                        reviewFeedback = collabStore.listReviewsForTask(taskId),
                                    )
                                    val pAdapter = RuntimeStepExecutionAdapter(
                                        runtime = runtime,
                                        providerConfigResolver = providerConfigResolver,
                                        communicationManager = communicationManager,
                                        stepTimeoutMs = timeoutPolicy.forProvider(prof.providerId).agentExecutionTimeoutMs,
                                        mcpManager = mcpManager,
                                        teamRosterProvider = {
                                            teamManager.getTeamProfiles(team.teamId, onlyEnabled = true)
                                                .map(AgentProfileMapper::toDynamicProfile)
                                        },
                                        onProgress = { _, _, _, prog ->
                                            onStepLifecycleUpdate(
                                                StepLifecycleUpdate(
                                                    projectId = project.projectId,
                                                    taskId = taskId,
                                                    stepId = runStep.stepId,
                                                    stepTitle = runStep.title,
                                                    agentId = prof.agentId,
                                                    agentDisplayName = prof.displayName,
                                                    phase = "PROGRESS",
                                                    detail = prog,
                                                    stepNumber = runStep.sequenceNumber,
                                                    totalSteps = allSteps.size,
                                                ),
                                            )
                                        },
                                    )
                                    val out = pAdapter.executeStep(
                                        StepExecutionInput(
                                            task = task,
                                            step = runStep,
                                            agent = AgentProfileMapper.toDynamicProfile(prof),
                                            workspaceDir = workspaceDir,
                                            compactContext = ctx,
                                            latestCheckpoint = taskStore.getLatestCheckpoint(taskId),
                                            imageFiles = accessibleImages,
                                        ),
                                    )
                                    val afterSnap = checkpointManager.snapshotWorkspace(workspaceDir)
                                    val changed = (out.modifiedFiles + checkpointManager.diffSnapshots(beforeSnap, afterSnap)).distinct()
                                    val verOutcome = out.verificationSpec?.let { ResultVerifier.verify(workspaceDir, out.summary, it) }
                                    val passed = out.success && (verOutcome == null || verOutcome.passed)
                                    persistStepVerificationEvidence(taskId, runStep, out, changed)
                                    if (passed) {
                                        val cp = checkpointManager.buildCheckpoint(taskId, runStep.stepId, workspaceDir, changed, out.summary)
                                        val latestT = taskStore.getTask(taskId) ?: task
                                        taskStore.completeStepTransactionally(
                                            task = latestT.copy(
                                                totalInputTokens = latestT.totalInputTokens + out.inputTokens,
                                                totalOutputTokens = latestT.totalOutputTokens + out.outputTokens,
                                                actualCostUsd = latestT.actualCostUsd + out.actualCostUsd,
                                                lastHeartbeatAt = nowProvider(),
                                                updatedAt = nowProvider(),
                                            ),
                                            step = runStep.copy(status = StepStatus.COMPLETED, outputSummary = out.summary, completedAt = nowProvider(), checkpointId = cp.checkpointId),
                                            execution = execRec.copy(executionStatus = "COMPLETED", completedAt = nowProvider(), inputTokens = out.inputTokens, outputTokens = out.outputTokens, estimatedCostUsd = out.actualCostUsd),
                                            checkpoint = cp,
                                            event = TaskEventEntity(taskId = taskId, stepId = runStep.stepId, eventType = "PARALLEL_STEP_COMPLETED", eventPayload = "Parallel step '${runStep.title}' completed by ${prof.agentId}"),
                                            idempotency = IdempotencyRecordEntity(runStep.idempotencyKey, taskId, runStep.stepId, "STEP_EXECUTION", out.summary.take(500)),
                                        )
                                        healthMonitor.markHealthy(prof.agentId, prof.providerId, prof.modelId, nowProvider())
                                        onStepLifecycleUpdate(
                                            StepLifecycleUpdate(
                                                projectId = project.projectId,
                                                taskId = taskId,
                                                stepId = runStep.stepId,
                                                stepTitle = runStep.title,
                                                agentId = prof.agentId,
                                                agentDisplayName = prof.displayName,
                                                phase = "COMPLETED",
                                                detail = out.summary.take(300),
                                                modifiedFiles = changed,
                                                stepNumber = runStep.sequenceNumber,
                                                totalSteps = allSteps.size,
                                            ),
                                        )
                                    } else {
                                        taskStore.upsertStep(runStep.copy(status = StepStatus.READY))
                                        taskStore.updateExecution(execRec.copy(executionStatus = "FAILED", completedAt = nowProvider(), errorMessage = verOutcome?.details ?: out.error?.message ?: out.summary))
                                    }
                                } finally {
                                    locked.forEach { fileLockManager.releaseByAgentId(it, prof.agentId) }
                                    livenessMonitor.releaseLease(taskId, pStep.stepId, prof.agentId)
                                }
                            }
                        }.awaitAll()
                    }
                    updateSnapshot(taskId, SupervisorOperationalStatus.SUPERVISING, "อัปเดตความคืบหน้าหลังทำงานแบบขนานเสร็จสิ้น")
                    if (!anyLaunched) kotlinx.coroutines.delay(150)
                    continue
                }

                val nextStep = runnableSteps.firstOrNull()

                if (nextStep == null) {
                    // All currently planned steps finished -> run Independent Completion Verifier!
                    updateSnapshot(taskId, SupervisorOperationalStatus.VERIFYING, "Verifying deliverables, reviews, and tests")
                    val missing = evaluateCompletionGaps(task, allSteps, workspaceDir, requiredDeliverableFiles)
                    if (missing.isEmpty()) {
                        // Also consult AI Supervisor Advisor (with failover!) for any remaining semantic gaps
                        val advisorFollowUps = runAdvisorWithFailover(task, allSteps, workspaceDir)
                        if (advisorFollowUps.isEmpty()) {
                            val completed = task.copy(
                                status = PersistentTaskStatus.COMPLETED,
                                completedAt = nowProvider(),
                                currentStepId = null,
                                executionLeaseOwner = null,
                                updatedAt = nowProvider(),
                            )
                            taskStore.upsertTask(completed)
                            recoveryEngine.recordDecision(
                                SupervisorDecisionRecord(
                                    taskId = taskId,
                                    decisionType = SupervisorDecisionType.TASK_COMPLETED,
                                    reason = "All deliverables, reviews, tests, and acceptance criteria verified.",
                                    evidence = "${allSteps.size} steps completed, ${requiredDeliverableFiles.size} deliverable(s) verified",
                                    actionTaken = "Transition task to COMPLETED",
                                    result = "COMPLETED",
                                ),
                            )
                            updateSnapshot(taskId, SupervisorOperationalStatus.COMPLETED, "Verified complete")
                            return completed
                        } else if (replanAttempts < limits.maxReplanAttempts) {
                            replanAttempts++
                            appendFollowUpSteps(task, team, allSteps, advisorFollowUps, "Advisor identified missing requirements")
                            continue
                        } else {
                            return blockTask(task, "Exceeded max replan attempts (${limits.maxReplanAttempts}) with remaining missing requirements.")
                        }
                    }

                    // Deterministic Completion Verifier found missing deliverables/tests/reviews -> auto-replan!
                    val progressSignature = "${allSteps.count { it.status == StepStatus.COMPLETED }}:${taskStore.listVerificationEvidenceForTask(taskId).size}:${collabStore.listReviewsForTask(taskId).size}"
                    if (replanAttempts > 0 && lastReplanProgressSignature == progressSignature) {
                        return blockTask(task, "Stopped safely: zero forward progress across consecutive recovery/replan cycles. Remaining gaps: ${missing.joinToString(" | ")}")
                    }
                    if (replanAttempts < limits.maxReplanAttempts) {
                        replanAttempts++
                        lastReplanProgressSignature = progressSignature
                        updateMetrics(taskId) { it.copy(replanCount = it.replanCount + 1) }
                        val followUpSteps = buildRemediationSteps(task, team, allSteps, missing, requiredDeliverableFiles)
                        if (followUpSteps.isNotEmpty()) {
                            appendFollowUpSteps(task, team, allSteps, followUpSteps, "Missing completion criteria: ${missing.joinToString("; ")}")
                            continue
                        }
                    }
                    return blockTask(task, "Verification incomplete: ${missing.joinToString(" | ")}")
                }

                // 5. Idempotency skip (never repeat already-completed side effects on restart!)
                val existingIdem = taskStore.findIdempotency(nextStep.idempotencyKey)
                if (existingIdem != null) {
                    taskStore.upsertStep(
                        nextStep.copy(
                            status = StepStatus.COMPLETED,
                            outputSummary = existingIdem.resultSummary,
                            completedAt = existingIdem.completedAt,
                        ),
                    )
                    continue
                }

                // 6. Resolve active agent profile with Strict Role Ownership Enforcement
                val teamProfiles = teamManager.getTeamProfiles(team.teamId, onlyEnabled = true)
                val leaderProfile = teamProfiles.firstOrNull { "lead" in it.capabilities || "architect" in it.capabilities }
                var effectiveStep = nextStep
                if (!effectiveStep.requiresReview && leaderProfile != null && effectiveStep.assignedAgentId == leaderProfile.agentId && teamProfiles.size > 1) {
                    val enforcedDev = capabilityResolver.selectBestAgent(
                        candidates = teamProfiles,
                        requiredCapabilities = setOf("code"),
                        excludeAgentIds = setOf(leaderProfile.agentId),
                    ) ?: teamProfiles.firstOrNull { it.agentId != leaderProfile.agentId && ("code" in it.capabilities || it.role.contains("dev", ignoreCase = true)) }
                    if (enforcedDev != null) {
                        effectiveStep = effectiveStep.copy(assignedAgentId = enforcedDev.agentId)
                        taskStore.upsertStep(effectiveStep)
                        taskStore.insertTaskEvent(
                            TaskEventEntity(
                                taskId = taskId,
                                stepId = effectiveStep.stepId,
                                eventType = "ROLE_ENFORCEMENT_REDIRECTED",
                                eventPayload = "Implementation step '${effectiveStep.title}' redirected from SPV (${leaderProfile.agentId}) to Developer (${enforcedDev.agentId}).",
                            ),
                        )
                    }
                }
                val agentProfile = teamProfiles.firstOrNull { it.agentId == effectiveStep.assignedAgentId }
                    ?: teamProfiles.firstOrNull()
                    ?: return blockTask(task, "No enabled agents available in team '${team.teamName}'.")

                // 7. Acquire exclusive execution lease & file locks
                val executionId = "exec-${UUID.randomUUID().toString().take(10)}"
                val lease = livenessMonitor.tryAcquireLease(taskId, nextStep.stepId, agentProfile.agentId, ttlMs = timeoutPolicy.agentExecutionTimeoutMs, nowMs = nowProvider())
                if (lease == null) {
                    delayFn(100L)
                    continue
                }

                val lockSlot = AgentSlot.fromId(agentProfile.agentId) ?: AgentSlot.AGENT_1
                val acquiredLocks = mutableListOf<String>()
                var lockError: String? = null
                for (path in nextStep.targetFiles) {
                    when (val res = fileLockManager.tryAcquire(path, lockSlot, taskId, "Step ${nextStep.title}")) {
                        is LockAcquisitionResult.Success -> acquiredLocks.add(path)
                        is LockAcquisitionResult.Conflict -> {
                            lockError = "File '$path' locked by ${res.lockedBy.defaultName}"
                            break
                        }
                        is LockAcquisitionResult.Error -> {
                            lockError = res.message
                            break
                        }
                    }
                }
                if (lockError != null) {
                    acquiredLocks.forEach { fileLockManager.release(it, lockSlot) }
                    livenessMonitor.releaseLease(taskId, nextStep.stepId, agentProfile.agentId)
                    delayFn(100L)
                    continue
                }

                // 8. Execute step via real RuntimeStepExecutionAdapter with HealthMonitor + Watchdog
                val assignmentEnvelope = TaskAssignmentEnvelope(
                    taskId = taskId,
                    planId = task.planId ?: "plan-$taskId",
                    planVersion = task.planVersion,
                    partId = nextStep.partId ?: "part-${nextStep.sequenceNumber}",
                    stepId = nextStep.stepId,
                    assignmentId = "asgn-${nextStep.stepId}-r${nextStep.retryCount}",
                    assignedAgentId = agentProfile.agentId,
                    specificationPath = ".orqen/plan/overview.md",
                    specificationContent = nextStep.description,
                    immutableUserGoal = task.description.ifBlank { task.title },
                    currentScope = nextStep.title,
                    expectedDeliverables = nextStep.targetFiles,
                    dependencies = nextStep.dependencies,
                )
                if (!assignmentLedger.registerAssignment(assignmentEnvelope)) {
                    acquiredLocks.forEach { fileLockManager.release(it, lockSlot) }
                    livenessMonitor.releaseLease(taskId, nextStep.stepId, agentProfile.agentId)
                    delayFn(100L)
                    continue
                }
                if (!nextStep.requiresReview && !nextStep.partId.isNullOrBlank()) {
                    runCatching {
                        MultiPartPlanManager.loadPlan(workspaceDir)?.let { mpPlan ->
                            MultiPartPlanManager.beginPartExecution(workspaceDir, mpPlan, nextStep.partId)
                        }
                    }
                }
                val totalPlannedSteps = allSteps.size.coerceAtLeast(nextStep.sequenceNumber)
                val retrySuffix = if (nextStep.retryCount > 0) " (ลองใหม่รอบที่ ${nextStep.retryCount + 1})" else ""
                val stepOperationalStatus = if (effectiveStep.requiresReview) {
                    SupervisorOperationalStatus.VERIFYING
                } else {
                    SupervisorOperationalStatus.SUPERVISING
                }
                val stepPhase = if (effectiveStep.requiresReview) "REVIEW" else "IMPLEMENTATION"
                updateSnapshot(
                    taskId = taskId,
                    status = stepOperationalStatus,
                    detail = "ขั้นตอน ${effectiveStep.sequenceNumber}/$totalPlannedSteps: ${effectiveStep.title}$retrySuffix (${agentProfile.displayName} / ${agentProfile.modelId})",
                    activeAgentId = agentProfile.agentId,
                    activeAgentName = agentProfile.displayName,
                    activeStepTitle = effectiveStep.title,
                    activePhase = stepPhase,
                )
                val startKey = "${effectiveStep.stepId}:${effectiveStep.retryCount}"
                if (emittedStartedSteps.add(startKey)) {
                    onStepLifecycleUpdate(
                        StepLifecycleUpdate(
                            projectId = task.projectId,
                            taskId = taskId,
                            stepId = effectiveStep.stepId,
                            stepTitle = effectiveStep.title,
                            agentId = agentProfile.agentId,
                            agentDisplayName = agentProfile.displayName,
                            phase = "STARTED",
                            detail = "เริ่มขั้นตอนที่ ${effectiveStep.sequenceNumber}/$totalPlannedSteps: ${effectiveStep.title}$retrySuffix (ใช้โมเดล ${agentProfile.modelId})",
                            stepNumber = effectiveStep.sequenceNumber,
                            totalSteps = totalPlannedSteps,
                        ),
                    )
                }
                val runningStep = effectiveStep.copy(status = StepStatus.RUNNING, startedAt = effectiveStep.startedAt ?: nowProvider())
                val execRecord = AgentExecutionEntity(
                    executionId = executionId,
                    taskId = taskId,
                    stepId = nextStep.stepId,
                    agentId = agentProfile.agentId,
                    providerId = agentProfile.providerId,
                    modelId = agentProfile.modelId,
                    executionStatus = "RUNNING",
                )
                taskStore.runInTransaction {
                    taskStore.upsertStep(runningStep)
                    taskStore.insertExecution(execRecord)
                    taskStore.upsertTask(task.copy(currentStepId = runningStep.stepId, lastHeartbeatAt = nowProvider(), updatedAt = nowProvider()))
                }

                healthMonitor.recordEvent(
                    AgentProgressEvent(
                        taskId = taskId,
                        subtaskId = runningStep.stepId,
                        agentId = agentProfile.agentId,
                        executionId = executionId,
                        eventType = ProgressEventType.STARTED,
                        details = "Started '${runningStep.title}'",
                        providerId = agentProfile.providerId,
                        modelId = agentProfile.modelId,
                        timestampMs = nowProvider(),
                    ),
                )

                val beforeSnap = checkpointManager.snapshotWorkspace(workspaceDir)
                val latestCheckpoint = taskStore.getLatestCheckpoint(taskId)
                val compactContext = TaskContextBuilder.buildAgentStepContext(
                    project = project,
                    task = task,
                    step = runningStep,
                    allSteps = allSteps,
                    latestCheckpoint = latestCheckpoint,
                    communicationManager = communicationManager,
                    reviewFeedback = collabStore.listReviewsForTask(taskId),
                )

                val adapter = RuntimeStepExecutionAdapter(
                    runtime = runtime,
                    providerConfigResolver = providerConfigResolver,
                    communicationManager = communicationManager,
                    stepTimeoutMs = timeoutPolicy.forProvider(agentProfile.providerId).agentExecutionTimeoutMs,
                    mcpManager = mcpManager,
                    teamRosterProvider = {
                        teamManager.getTeamProfiles(team.teamId, onlyEnabled = true)
                            .map(AgentProfileMapper::toDynamicProfile)
                    },
                    onProgress = { tid, sid, aid, prog ->
                        val (evType, toolName, cleanDetails) = when {
                            prog.startsWith("TOOL_STARTED:") -> {
                                val parts = prog.removePrefix("TOOL_STARTED:").split(":", limit = 2)
                                Triple(ProgressEventType.TOOL_STARTED, parts[0].trim(), parts.getOrElse(1) { "" }.take(200))
                            }
                            prog.startsWith("TOOL_COMPLETED:") -> {
                                val tName = prog.removePrefix("TOOL_COMPLETED:").trim()
                                Triple(ProgressEventType.TOOL_COMPLETED, tName, "Tool completed: $tName")
                            }
                            prog.startsWith("HEARTBEAT") -> {
                                Triple(ProgressEventType.HEARTBEAT, null, prog.take(200))
                            }
                            else -> Triple(ProgressEventType.PROGRESS, null, prog.take(200))
                        }
                        healthMonitor.recordEvent(
                            AgentProgressEvent(
                                taskId = tid,
                                subtaskId = sid,
                                agentId = aid,
                                executionId = executionId,
                                eventType = evType,
                                details = cleanDetails,
                                toolName = toolName,
                                providerId = agentProfile.providerId,
                                modelId = agentProfile.modelId,
                                timestampMs = nowProvider(),
                            ),
                        )
                        onStepLifecycleUpdate(
                            StepLifecycleUpdate(
                                projectId = task.projectId,
                                taskId = tid,
                                stepId = sid,
                                stepTitle = runningStep.title,
                                agentId = aid,
                                agentDisplayName = agentProfile.displayName,
                                phase = "PROGRESS",
                                detail = cleanDetails,
                            ),
                        )
                    },
                )

                val stepExecStartMs = nowProvider()
                val accessibleImages = TaskAttachmentCodec.resolveAccessibleFiles(task.taskMemory, workspaceDir)
                val output = try {
                    adapter.executeStep(
                        StepExecutionInput(
                            task = task,
                            step = runningStep,
                            agent = AgentProfileMapper.toDynamicProfile(agentProfile),
                            workspaceDir = workspaceDir,
                            compactContext = compactContext,
                            latestCheckpoint = latestCheckpoint,
                            imageFiles = accessibleImages,
                        ),
                    )
                } catch (cancel: CancellationException) {
                    assignmentLedger.releaseActive(assignmentEnvelope.assignmentId)
                    healthMonitor.recordEvent(
                        AgentProgressEvent(
                            taskId = taskId,
                            subtaskId = runningStep.stepId,
                            agentId = agentProfile.agentId,
                            executionId = executionId,
                            eventType = ProgressEventType.CANCELLED,
                            details = "Cancelled",
                            timestampMs = nowProvider(),
                        ),
                    )
                    throw cancel
                } finally {
                    acquiredLocks.forEach { fileLockManager.release(it, lockSlot) }
                    livenessMonitor.releaseLease(taskId, runningStep.stepId, agentProfile.agentId)
                }
                val stepElapsedMs = (nowProvider() - stepExecStartMs).coerceAtLeast(0L)
                sessionActiveExecutionMs += stepElapsedMs
                val isSpvStep = (leaderProfile != null && agentProfile.agentId == leaderProfile.agentId) || runningStep.requiresReview
                updateMetrics(taskId) { m ->
                    m.copy(
                        activeExecutionDurationMs = m.activeExecutionDurationMs + stepElapsedMs,
                        reviewDurationMs = if (runningStep.requiresReview) m.reviewDurationMs + stepElapsedMs else m.reviewDurationMs,
                        totalInputTokens = m.totalInputTokens + output.inputTokens,
                        totalOutputTokens = m.totalOutputTokens + output.outputTokens,
                    )
                }
                if (output.success) {
                    if (isSpvStep) spvConnectionStates[taskId] = ModelConnectionState.CONNECTED
                    else devConnectionStates[taskId] = ModelConnectionState.CONNECTED
                }

                val afterSnap = checkpointManager.snapshotWorkspace(workspaceDir)
                val changedFiles = (output.modifiedFiles + checkpointManager.diffSnapshots(beforeSnap, afterSnap)).distinct().sorted()
                persistStepVerificationEvidence(taskId, runningStep, output, changedFiles)
                // P0 Fix: If Developer already executed and persisted real passing tests (exitCode=0)
                // for this task, include that persisted evidence when verifying the Review/QA step!
                val effectiveVerificationSpec = output.verificationSpec?.let { spec ->
                    if (spec.mode == VerificationMode.TEST_VERIFICATION && spec.testEvidence.isEmpty()) {
                        val persistedPassingTests = taskStore.listVerificationEvidenceForTask(taskId)
                            .filter { it.evidenceType == "TEST" && it.exitCode == 0 }
                            .map { ev ->
                                com.jarves.mh.runtime.TestExecutionEvidence(
                                    command = ev.command,
                                    startedAtMs = ev.timestampMs,
                                    completedAtMs = ev.timestampMs,
                                    exitCode = ev.exitCode ?: 0,
                                    stdout = ev.stdout,
                                    stderr = ev.stderr,
                                    outcome = com.jarves.mh.runtime.TestExecutionOutcome.TESTS_PASSED,
                                    summary = "Persisted task test evidence (exitCode=0)",
                                    workingDir = ev.workingDir,
                                    passedCount = ev.passedCount,
                                    failedCount = ev.failedCount,
                                )
                            }
                        if (persistedPassingTests.isNotEmpty()) spec.copy(testEvidence = persistedPassingTests) else spec
                    } else spec
                }
                val verificationOutcome = effectiveVerificationSpec?.let { ResultVerifier.verify(workspaceDir, output.summary, it) }
                val verificationFailed = output.success && verificationOutcome != null && !verificationOutcome.passed

                // P0 Fix: Explicit REVIEW_REJECTED must always route to the Developer fix + Re-Review
                // cycle even if tests failed or were not yet passing (verificationFailed == true).
                val isExplicitReviewRejection = output.success && runningStep.requiresReview && !output.reviewApproved
                if (output.success && (!verificationFailed || isExplicitReviewRejection)) {
                    assignmentLedger.validateAndComplete(
                        assignmentId = assignmentEnvelope.assignmentId,
                        partId = assignmentEnvelope.partId,
                        stepId = assignmentEnvelope.stepId,
                    )
                    recoveryEngine.resetProviderFailures(agentProfile.providerId)
                    healthMonitor.recordEvent(
                        AgentProgressEvent(
                            taskId = taskId,
                            subtaskId = runningStep.stepId,
                            agentId = agentProfile.agentId,
                            executionId = executionId,
                            eventType = ProgressEventType.COMPLETED,
                            details = "Completed (${changedFiles.size} modified file(s))",
                            providerId = agentProfile.providerId,
                            modelId = agentProfile.modelId,
                            timestampMs = nowProvider(),
                        ),
                    )

                    // Handle Reviewer Rejection -> schedule corrective Dev fix + Re-Review!
                    if (runningStep.requiresReview && !output.reviewApproved) {
                        reviewRejectCycles++
                        if (reviewRejectCycles > limits.maxReviewCycles) {
                            return blockTask(task, "Review rejection cycle limit (${limits.maxReviewCycles}) exceeded: ${output.reviewFeedback.orEmpty()}")
                        }
                        val leaderId = teamProfiles.firstOrNull { "lead" in it.capabilities || "architect" in it.capabilities }?.agentId
                        val devAgent = capabilityResolver.selectBestAgent(
                            candidates = teamProfiles,
                            requiredCapabilities = setOf("code"),
                            excludeAgentIds = setOfNotNull(agentProfile.agentId, leaderId),
                        ) ?: capabilityResolver.selectBestAgent(
                            candidates = teamProfiles,
                            requiredCapabilities = setOf("code"),
                            excludeAgentIds = setOf(agentProfile.agentId),
                        ) ?: agentProfile

                        reviewManager.submitReview(
                            taskId = taskId,
                            reviewerAgentId = agentProfile.agentId,
                            implementingAgentId = devAgent.agentId,
                            reviewedExecutionId = executionId,
                            reviewedStepId = runningStep.stepId,
                            outcome = ReviewOutcome.CHANGES_REQUESTED,
                            findings = listOf(output.reviewFeedback ?: output.summary),
                            severity = ReviewSeverity.HIGH,
                            requiredChanges = listOf(output.reviewFeedback ?: "Address reviewer findings"),
                            requireIndependentReviewer = (agentProfile.agentId != devAgent.agentId),
                        )
                        recoveryEngine.recordDecision(
                            SupervisorDecisionRecord(
                                taskId = taskId,
                                stepId = runningStep.stepId,
                                agentId = devAgent.agentId,
                                executionId = executionId,
                                decisionType = SupervisorDecisionType.REVISION_REQUESTED,
                                reason = "Reviewer ${agentProfile.displayName} rejected work: ${output.reviewFeedback.orEmpty()}",
                                evidence = output.summary.take(200),
                                actionTaken = "Scheduled fix step for ${devAgent.agentId} + re-review for ${agentProfile.agentId}",
                                result = "SCHEDULED",
                            ),
                        )
                        val nextSeq = (allSteps.maxOfOrNull { it.sequenceNumber } ?: 0) + 1
                        val feedbackBody = output.reviewFeedback ?: "Address reviewer findings"
                        communicationManager.sendDirect(
                            taskId = taskId,
                            fromAgentId = agentProfile.agentId,
                            toAgentId = devAgent.agentId,
                            type = AgentMessageKind.CHANGE_REQUEST,
                            content = "REVIEW_REJECTED on '${runningStep.title}': $feedbackBody",
                            relatedStepId = runningStep.stepId,
                        )
                        val fixStep = TaskStepEntity(
                            taskId = taskId,
                            sequenceNumber = nextSeq,
                            title = "Fix review feedback: ${runningStep.title}",
                            description = feedbackBody,
                            assignedAgentId = devAgent.agentId,
                            status = StepStatus.READY,
                            dependencies = listOf(runningStep.stepId),
                            targetFiles = runningStep.targetFiles,
                            requiresReview = false,
                            partId = runningStep.partId,
                        )
                        val reReviewStep = TaskStepEntity(
                            taskId = taskId,
                            sequenceNumber = nextSeq + 1,
                            title = "Re-verify: ${runningStep.title}",
                            description = "Independently verify fixes and run real tests for ${runningStep.title}",
                            assignedAgentId = runningStep.assignedAgentId,
                            status = StepStatus.PENDING,
                            dependencies = listOf(fixStep.stepId),
                            targetFiles = runningStep.targetFiles,
                            requiresReview = true,
                            partId = runningStep.partId,
                        )
                        taskStore.runInTransaction {
                            taskStore.upsertStep(runningStep.copy(status = StepStatus.COMPLETED, outputSummary = "REJECTED: ${output.reviewFeedback}", completedAt = nowProvider()))
                            taskStore.updateExecution(execRecord.copy(executionStatus = "REVIEW_REJECTED", completedAt = nowProvider()))
                            taskStore.upsertStep(fixStep)
                            taskStore.upsertStep(reReviewStep)
                        }
                        continue
                    }

                    // Record review approval if review step passed
                    if (runningStep.requiresReview && output.reviewApproved) {
                        val implAgentId = allSteps.lastOrNull { it.stepId in runningStep.dependencies && it.assignedAgentId != agentProfile.agentId }?.assignedAgentId
                            ?: teamProfiles.firstOrNull { "code" in it.capabilities && it.agentId != agentProfile.agentId }?.agentId
                            ?: "agent-02"
                        reviewManager.submitReview(
                            taskId = taskId,
                            reviewerAgentId = agentProfile.agentId,
                            implementingAgentId = implAgentId,
                            reviewedExecutionId = executionId,
                            reviewedStepId = runningStep.stepId,
                            outcome = ReviewOutcome.APPROVED,
                            findings = listOf(output.summary.take(300)),
                            severity = ReviewSeverity.INFO,
                            evidenceReferences = changedFiles,
                            requireIndependentReviewer = (agentProfile.agentId != implAgentId),
                        )
                    }

                    // Commit checkpoint + completed step atomically
                    val checkpoint = checkpointManager.buildCheckpoint(
                        taskId = taskId,
                        stepId = runningStep.stepId,
                        workspaceDir = workspaceDir,
                        modifiedFiles = changedFiles,
                        contextSummary = output.summary,
                    )
                    val latestTask = taskStore.getTask(taskId) ?: task
                    taskStore.completeStepTransactionally(
                        task = latestTask.copy(
                            totalInputTokens = latestTask.totalInputTokens + output.inputTokens,
                            totalOutputTokens = latestTask.totalOutputTokens + output.outputTokens,
                            actualCostUsd = latestTask.actualCostUsd + output.actualCostUsd,
                            lastError = null,
                            lastHeartbeatAt = nowProvider(),
                            updatedAt = nowProvider(),
                        ),
                        step = runningStep.copy(
                            status = StepStatus.COMPLETED,
                            outputSummary = output.summary,
                            completedAt = nowProvider(),
                            checkpointId = checkpoint.checkpointId,
                        ),
                        execution = execRecord.copy(
                            executionStatus = "COMPLETED",
                            completedAt = nowProvider(),
                            inputTokens = output.inputTokens,
                            outputTokens = output.outputTokens,
                            estimatedCostUsd = output.actualCostUsd,
                        ),
                        checkpoint = checkpoint,
                        event = TaskEventEntity(
                            taskId = taskId,
                            stepId = runningStep.stepId,
                            eventType = "STEP_COMPLETED",
                            eventPayload = "Step #${runningStep.sequenceNumber} completed (${changedFiles.size} file(s))",
                        ),
                        idempotency = IdempotencyRecordEntity(
                            idempotencyKey = runningStep.idempotencyKey,
                            taskId = taskId,
                            stepId = runningStep.stepId,
                            operationType = "STEP_EXECUTION",
                            resultSummary = output.summary.take(500),
                        ),
                    )
                    // Synchronize real step progress & evidence to `.orqen/reports/{execution,review,verification,part_N}_report.md`!
                    runCatching {
                        val updatedTaskForPlan = taskStore.getTask(taskId) ?: latestTask
                        MultiPartPlanManager.syncUnifiedReports(
                            workspaceDir = workspaceDir,
                            task = updatedTaskForPlan,
                            steps = taskStore.listStepsForTask(taskId),
                            evidence = taskStore.listVerificationEvidenceForTask(taskId),
                        )
                        val partId = runningStep.partId
                        if (runningStep.requiresReview && !partId.isNullOrBlank()) {
                            val mpPlan = MultiPartPlanManager.loadPlan(workspaceDir)
                            if (mpPlan != null && mpPlan.parts.any { it.partId == partId }) {
                                val lastTest = output.testEvidence.lastOrNull()
                                val lastBuild = output.buildEvidence.lastOrNull()
                                MultiPartPlanManager.recordPartVerificationAndReport(
                                    workspaceDir = workspaceDir,
                                    plan = mpPlan,
                                    partId = partId,
                                    verification = PartVerificationInput(
                                        modifiedFiles = changedFiles,
                                        reviewerApproved = output.reviewApproved,
                                        reviewerNotes = output.summary.take(500),
                                        testExitCode = lastTest?.exitCode,
                                        testSummary = lastTest?.summary ?: "No test command recorded",
                                        buildPassed = lastBuild?.success,
                                        buildArtifactPath = lastBuild?.apkPath,
                                    ),
                                )
                            }
                        }
                    }
                    val completedTotalSteps = allSteps.size.coerceAtLeast(runningStep.sequenceNumber)
                    val nextPendingStep = allSteps.firstOrNull {
                        it.sequenceNumber > runningStep.sequenceNumber && it.status != StepStatus.COMPLETED
                    }
                    val completionDetail = buildString {
                        appendLine("ทำขั้นตอนที่ ${runningStep.sequenceNumber}/$completedTotalSteps เสร็จแล้ว: ${runningStep.title}")
                        if (changedFiles.isNotEmpty()) {
                            appendLine("ไฟล์ที่อัปเดต (${changedFiles.size}): ${changedFiles.take(4).joinToString(", ")}")
                        }
                        val cleanOut = output.summary.trim().take(320)
                        if (cleanOut.isNotBlank()) {
                            appendLine(cleanOut)
                        }
                        if (nextPendingStep != null) {
                            append("ขั้นตอนถัดไป (#${nextPendingStep.sequenceNumber}/$completedTotalSteps): ${nextPendingStep.title}")
                        } else {
                            append("ส่งต่อให้ Supervisor ตรวจสอบความครบถ้วนและผลทดสอบครับ")
                        }
                    }
                    onStepLifecycleUpdate(
                        StepLifecycleUpdate(
                            projectId = task.projectId,
                            taskId = taskId,
                            stepId = runningStep.stepId,
                            stepTitle = runningStep.title,
                            agentId = agentProfile.agentId,
                            agentDisplayName = agentProfile.displayName,
                            phase = "COMPLETED",
                            detail = completionDetail,
                            modifiedFiles = changedFiles,
                            stepNumber = runningStep.sequenceNumber,
                            totalSteps = completedTotalSteps,
                        ),
                    )
                } else {
                    assignmentLedger.releaseActive(assignmentEnvelope.assignmentId)
                    // Failure OR Failed Test Verification!
                    val hasActualFailedTestCommand = output.testEvidence.any { it.exitCode != 0 }
                    if (verificationFailed && output.verificationSpec?.mode == VerificationMode.TEST_VERIFICATION && hasActualFailedTestCommand) {
                        // Failed tests trigger automatic correction + retest cycle!
                        if (replanAttempts < limits.maxReplanAttempts) {
                            replanAttempts++
                            val leaderId = teamProfiles.firstOrNull { "lead" in it.capabilities || "architect" in it.capabilities }?.agentId
                            val devAgent = capabilityResolver.selectBestAgent(teamProfiles, setOf("code"), excludeAgentIds = setOfNotNull(agentProfile.agentId, leaderId))
                                ?: capabilityResolver.selectBestAgent(teamProfiles, setOf("code"), excludeAgentIds = setOf(agentProfile.agentId))
                                ?: agentProfile
                            recoveryEngine.recordDecision(
                                SupervisorDecisionRecord(
                                    taskId = taskId,
                                    stepId = runningStep.stepId,
                                    agentId = devAgent.agentId,
                                    executionId = executionId,
                                    decisionType = SupervisorDecisionType.TEST_FAILED,
                                    reason = "Automated tests failed on '${runningStep.title}'; scheduling automatic fix & retest.",
                                    evidence = output.summary.take(240),
                                    actionTaken = "Assign fix step to ${devAgent.agentId} and re-run verification",
                                    result = "SCHEDULED",
                                ),
                            )
                            val nextSeq = (allSteps.maxOfOrNull { it.sequenceNumber } ?: 0) + 1
                            val lastTestEv = output.testEvidence.lastOrNull()
                            val detailedFailure = buildString {
                                appendLine("Fix test failures reported by ${agentProfile.displayName} on '${runningStep.title}':")
                                if (lastTestEv != null) {
                                    appendLine("Command: ${lastTestEv.command}")
                                    appendLine("Exit code: ${lastTestEv.exitCode}")
                                    if (lastTestEv.stderr.isNotBlank()) appendLine("STDERR: ${lastTestEv.stderr.take(600)}")
                                    if (lastTestEv.stdout.isNotBlank()) appendLine("STDOUT: ${lastTestEv.stdout.take(600)}")
                                } else {
                                    appendLine(verificationOutcome?.details ?: output.summary.take(500))
                                }
                            }.trim()
                            communicationManager.sendDirect(
                                taskId = taskId,
                                fromAgentId = agentProfile.agentId,
                                toAgentId = devAgent.agentId,
                                type = AgentMessageKind.CHANGE_REQUEST,
                                content = detailedFailure,
                                relatedStepId = runningStep.stepId,
                            )
                            val fixStep = TaskStepEntity(
                                taskId = taskId,
                                sequenceNumber = nextSeq,
                                title = "Fix failing tests: ${runningStep.title}",
                                description = detailedFailure,
                                assignedAgentId = devAgent.agentId,
                                status = StepStatus.READY,
                                dependencies = listOf(runningStep.stepId),
                                targetFiles = runningStep.targetFiles,
                                requiresReview = false,
                                partId = runningStep.partId,
                            )
                            val retestStep = TaskStepEntity(
                                taskId = taskId,
                                sequenceNumber = nextSeq + 1,
                                title = "Re-run verification tests",
                                description = "Run REAL tests via run_command and verify exit code 0 for ${task.title}",
                                assignedAgentId = agentProfile.agentId,
                                status = StepStatus.PENDING,
                                dependencies = listOf(fixStep.stepId),
                                targetFiles = runningStep.targetFiles,
                                requiresReview = runningStep.requiresReview,
                                partId = runningStep.partId,
                            )
                            taskStore.runInTransaction {
                                taskStore.upsertStep(runningStep.copy(status = StepStatus.COMPLETED, outputSummary = "TESTS_FAILED: ${output.summary}", completedAt = nowProvider()))
                                taskStore.updateExecution(execRecord.copy(executionStatus = "TESTS_FAILED", completedAt = nowProvider()))
                                taskStore.upsertStep(fixStep)
                                taskStore.upsertStep(retestStep)
                            }
                            continue
                        }
                    }

                    // Preserve partial progress checkpoint before recovery!
                    if (changedFiles.isNotEmpty()) {
                        val partialCp = checkpointManager.buildCheckpoint(
                            taskId = taskId,
                            stepId = runningStep.stepId,
                            workspaceDir = workspaceDir,
                            modifiedFiles = changedFiles,
                            contextSummary = "Partial progress preserved before recovery: ${changedFiles.joinToString()}",
                        )
                        taskStore.insertCheckpoint(partialCp)
                    }

                    val err = output.error ?: StructuredErrorClassifier.classify(
                        verificationOutcome?.details ?: output.summary.ifBlank { "Execution failed" },
                    )
                    healthMonitor.recordEvent(
                        AgentProgressEvent(
                            taskId = taskId,
                            subtaskId = runningStep.stepId,
                            agentId = agentProfile.agentId,
                            executionId = executionId,
                            eventType = ProgressEventType.FAILED,
                            details = err.message,
                            providerId = agentProfile.providerId,
                            modelId = agentProfile.modelId,
                            errorCategory = err.category,
                            retryAfterMs = err.retryAfterMs,
                            timestampMs = nowProvider(),
                        ),
                    )
                    taskStore.updateExecution(
                        execRecord.copy(
                            executionStatus = "FAILED",
                            completedAt = nowProvider(),
                            errorType = err.category.name,
                            errorMessage = err.message,
                        ),
                    )

                    updateSnapshot(taskId, SupervisorOperationalStatus.RECOVERING, "Recovering from ${err.category} on ${agentProfile.displayName}")
                    onStepLifecycleUpdate(
                        StepLifecycleUpdate(
                            projectId = task.projectId,
                            taskId = taskId,
                            stepId = runningStep.stepId,
                            stepTitle = runningStep.title,
                            agentId = agentProfile.agentId,
                            agentDisplayName = agentProfile.displayName,
                            phase = "RECOVERING",
                            detail = "${err.category}: ${err.userExplanation}",
                        ),
                    )
                    val requiredCaps = inferRequiredCapabilities(runningStep)
                    val recoveryPlan = recoveryEngine.planRecovery(
                        task = task,
                        step = runningStep,
                        failedAgent = agentProfile,
                        executionId = executionId,
                        error = err,
                        requiredCapabilities = requiredCaps,
                        teamCandidates = teamProfiles,
                        preservedFiles = changedFiles,
                        nowMs = nowProvider(),
                    )

                    val recoveryStartMs = nowProvider()
                    when (recoveryPlan.strategy) {
                        RecoveryStrategyKind.RETRY_SAME_MODEL -> {
                            if (isSpvStep) spvConnectionStates[taskId] = ModelConnectionState.RETRYING
                            else devConnectionStates[taskId] = ModelConnectionState.RETRYING
                            updateMetrics(taskId) { it.copy(retryCount = it.retryCount + 1, recoveryCount = it.recoveryCount + 1) }
                            if (recoveryPlan.backoffMs > 0L) delayFn(recoveryPlan.backoffMs)
                            updateMetrics(taskId) { it.copy(recoveryDurationMs = it.recoveryDurationMs + (nowProvider() - recoveryStartMs).coerceAtLeast(0L)) }
                            taskStore.upsertStep(runningStep.copy(status = StepStatus.READY, retryCount = runningStep.retryCount + 1))
                        }

                        RecoveryStrategyKind.SWITCH_MODEL_SAME_PROVIDER,
                        RecoveryStrategyKind.SWITCH_PROVIDER_AND_MODEL -> {
                            if (isSpvStep) spvConnectionStates[taskId] = ModelConnectionState.FAILOVER
                            else devConnectionStates[taskId] = ModelConnectionState.FAILOVER
                            updateMetrics(taskId) { it.copy(recoveryCount = it.recoveryCount + 1) }
                            val updatedProfile = agentProfile.copy(
                                providerId = recoveryPlan.targetProviderId,
                                modelId = recoveryPlan.targetModelId,
                                updatedAt = nowProvider(),
                            )
                            registry.register(updatedProfile)
                            taskStore.upsertStep(
                                runningStep.copy(
                                    status = StepStatus.READY,
                                    retryCount = 0,
                                    inputContext = buildRecoveryHandoffContext(runningStep.inputContext, recoveryPlan),
                                ),
                            )
                        }

                        RecoveryStrategyKind.REASSIGN_AGENT -> {
                            updateSnapshot(taskId, SupervisorOperationalStatus.REASSIGNING, "Reassigned to ${recoveryPlan.targetAgentId}")
                            // Wire existing AgentFailureManager directly into the real failure/reassignment path!
                            val existingDelegation = collabStore.listDelegationsForTask(taskId)
                                .lastOrNull { it.parentStepId == runningStep.stepId && it.status != DelegationStatus.COMPLETED }
                                ?: delegationManager.delegate(
                                    team = team,
                                    candidates = listOf(agentProfile),
                                    taskId = taskId,
                                    parentStepId = runningStep.stepId,
                                    delegatingAgentId = "supervisor",
                                    objective = runningStep.description,
                                    requiredCapabilities = requiredCaps,
                                    currentDepth = 1,
                                )
                            failureManager.recordFailureAndReassign(
                                delegationId = existingDelegation.delegationId,
                                failedAgentId = agentProfile.agentId,
                                reason = "${err.category}: ${err.message}",
                                candidates = teamProfiles,
                            )
                            taskStore.upsertStep(
                                runningStep.copy(
                                    assignedAgentId = recoveryPlan.targetAgentId,
                                    status = StepStatus.READY,
                                    retryCount = 0,
                                    inputContext = buildRecoveryHandoffContext(runningStep.inputContext, recoveryPlan),
                                ),
                            )
                            communicationManager.sendDirect(
                                taskId = taskId,
                                fromAgentId = "supervisor",
                                toAgentId = recoveryPlan.targetAgentId,
                                type = AgentMessageKind.TASK_DELEGATION,
                                content = "Continuing unfinished step '${runningStep.title}' (from ${agentProfile.agentId}, prevExec=$executionId). Preserved files: ${recoveryPlan.preservedModifiedFiles.joinToString()}",
                                relatedStepId = runningStep.stepId,
                            )
                        }

                        RecoveryStrategyKind.WAIT_FOR_QUOTA_RESET -> {
                            if (isSpvStep) spvConnectionStates[taskId] = ModelConnectionState.RETRYING
                            else devConnectionStates[taskId] = ModelConnectionState.RETRYING
                            val waitMs = recoveryPlan.backoffMs.coerceAtLeast(1_000L)
                            val waitMins = waitMs / 60_000L
                            val waitSecs = (waitMs % 60_000L) / 1000L
                            val waitMsg = "โควต้าโมเดลทุกตัวหมดชั่วคราว — ระบบจะรอโควต้าของ ${recoveryPlan.targetModelId} รีเซ็ต (อีก ${waitMins} นาที ${waitSecs} วินาที) แล้วปลุกทีมกลับมาทำงานต่อให้อัตโนมัติทันทีครับ"
                            updateSnapshot(taskId, SupervisorOperationalStatus.RECOVERING, waitMsg, activePhase = "WAITING_QUOTA_RESET")
                            onStepLifecycleUpdate(
                                StepLifecycleUpdate(
                                    projectId = task.projectId,
                                    taskId = taskId,
                                    stepId = runningStep.stepId,
                                    stepTitle = runningStep.title,
                                    agentId = agentProfile.agentId,
                                    agentDisplayName = agentProfile.displayName,
                                    phase = "RECOVERING",
                                    detail = waitMsg,
                                ),
                            )
                            var remainingMs = waitMs
                            while (remainingMs > 0L && currentCoroutineContext().isActive) {
                                val slice = minOf(remainingMs, 5_000L)
                                delayFn(slice)
                                remainingMs -= slice
                                val latest = taskStore.getTask(taskId)
                                if (latest == null || latest.ownerStopRequested || latest.pauseRequested ||
                                    latest.status == PersistentTaskStatus.CANCELLED || latest.status == PersistentTaskStatus.PAUSED
                                ) {
                                    break
                                }
                                taskStore.upsertTask(latest.copy(lastHeartbeatAt = nowProvider(), updatedAt = nowProvider()))
                            }
                            recoveryEngine.clearFailedModelsForStep(runningStep.stepId)
                            registry.register(
                                agentProfile.copy(
                                    providerId = recoveryPlan.targetProviderId,
                                    modelId = recoveryPlan.targetModelId,
                                    updatedAt = nowProvider(),
                                ),
                            )
                            taskStore.upsertStep(runningStep.copy(status = StepStatus.READY, retryCount = 0))
                        }

                        RecoveryStrategyKind.BLOCK_NEEDS_ATTENTION -> {
                            if (isSpvStep) spvConnectionStates[taskId] = ModelConnectionState.DISCONNECTED
                            else devConnectionStates[taskId] = ModelConnectionState.DISCONNECTED
                            taskStore.upsertStep(runningStep.copy(status = StepStatus.BLOCKED, outputSummary = recoveryPlan.reason))
                            return blockTask(task, recoveryPlan.reason)
                        }
                    }
                }
            }
            return taskStore.getTask(taskId)
        } finally {
            activeSupervisedTasks.remove(taskId)
            val finalStatus = taskStore.getTask(taskId)?.status
            if (finalStatus == PersistentTaskStatus.COMPLETED ||
                finalStatus == PersistentTaskStatus.FAILED ||
                finalStatus == PersistentTaskStatus.CANCELLED ||
                finalStatus == PersistentTaskStatus.BLOCKED
            ) {
                mcpManager?.let { mgr ->
                    mgr.authEngine.revokeGrantsForTask(
                        taskId = taskId,
                        reason = "Task ended ($finalStatus)",
                    )
                    mgr.refreshStateFlows()
                }
            }
        }
    }

    /**
     * Runs the AI Supervisor Advisor with automatic failover across [supervisorModelPoolProvider].
     * If a Supervisor model times out or fails, the deterministic layer preserves all task state
     * and falls back to the next eligible Supervisor model. If ALL Supervisor models fail,
     * deterministic control continues safely without destroying task state.
     */
    suspend fun runAdvisorWithFailover(
        task: TaskEntity,
        completedSteps: List<TaskStepEntity>,
        workspaceDir: File,
    ): List<PlannedStep> {
        val pool = supervisorModelPoolProvider().filter { it.isEnabled && it.providerId !in unavailableProviders() }
        if (pool.isEmpty()) return emptyList()
        spvConnectionStates[task.taskId] = ModelConnectionState.CONNECTING
        var lastErr: String? = null
        for ((idx, model) in pool.withIndex()) {
            try {
                if (idx > 0) spvConnectionStates[task.taskId] = ModelConnectionState.FAILOVER
                val result = advisor.evaluateMissingRequirements(model, task, completedSteps, workspaceDir)
                spvConnectionStates[task.taskId] = ModelConnectionState.CONNECTED
                return result
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Throwable) {
                spvConnectionStates[task.taskId] = ModelConnectionState.RETRYING
                lastErr = "${model.providerId}/${model.modelId}: ${err.message}"
                recoveryEngine.recordDecision(
                    SupervisorDecisionRecord(
                        taskId = task.taskId,
                        decisionType = SupervisorDecisionType.SUPERVISOR_FALLBACK,
                        reason = "Supervisor model ${model.providerId}/${model.modelId} failed; trying next eligible Supervisor model.",
                        evidence = err.message ?: err.javaClass.simpleName,
                        actionTaken = "Fallback Supervisor AI model while preserving deterministic task state",
                        result = "FALLBACK",
                    ),
                )
            }
        }
        spvConnectionStates[task.taskId] = ModelConnectionState.DISCONNECTED
        taskStore.insertTaskEvent(
            TaskEventEntity(
                taskId = task.taskId,
                eventType = "SUPERVISOR_MODELS_UNAVAILABLE",
                eventPayload = "All Supervisor AI models unavailable ($lastErr); deterministic control preserved task state.",
            ),
        )
        return emptyList()
    }

    /**
     * Bounded periodic / post-restart reconciliation pass for missed events and expired leases.
     * Evaluates active executions via [TaskWatchdog], identifies expired persistent leases via
     * [ExecutionLivenessMonitor.restoreAndIdentifyExpiredLeases], and resumes eligible tasks
     * without requiring a new user message.
     */
    suspend fun reconcileActiveTasksOnce(
        team: AgentTeamEntity,
        requiredDeliverableFilesByTask: Map<String, List<String>> = emptyMap(),
    ): List<String> {
        val now = nowProvider()
        val expiredLeaseTasks = livenessMonitor.restoreAndIdentifyExpiredLeases(
            leaseTtlMs = timeoutPolicy.agentExecutionTimeoutMs,
            nowMs = now,
        )
        // Run watchdog check on all tracked active agents and route confirmed stalls into Recovery Engine!
        healthMonitor.snapshotAll().keys.forEach { aid ->
            val eval = watchdog.evaluateExecution(aid, nowMs = now) { execId ->
                runtime.cancel(execId)
            }
            if (eval != null && eval.shouldCancelAndRecover) {
                handleWatchdogEvaluation(eval, team, now)
            }
        }
        val resumed = mutableListOf<String>()
        for (task in taskStore.listAllTasks()) {
            if (task.ownerStopRequested || task.pauseRequested) continue
            if (task.status == PersistentTaskStatus.COMPLETED ||
                task.status == PersistentTaskStatus.CANCELLED ||
                task.status == PersistentTaskStatus.PAUSED ||
                task.status == PersistentTaskStatus.FAILED ||
                task.status == PersistentTaskStatus.BLOCKED
            ) continue
            if (task.taskId in activeSupervisedTasks) continue
            // Skip legacy unplanned tasks with 0 steps (e.g. sample tasks from TaskManager)
            if (taskStore.listStepsForTask(task.taskId).isEmpty() && task.status != PersistentTaskStatus.RECOVERING) continue

            if (task.taskId in expiredLeaseTasks || task.status == PersistentTaskStatus.QUEUED || task.status == PersistentTaskStatus.RECOVERING || task.status == PersistentTaskStatus.READY) {
                // Reset any abandoned RUNNING steps back to READY (never resetting COMPLETED steps)
                taskStore.findStaleRunningSteps(task.taskId).forEach { staleStep ->
                    if (taskStore.findIdempotency(staleStep.idempotencyKey) == null) {
                        taskStore.upsertStep(staleStep.copy(status = StepStatus.READY))
                    }
                }
                recoveryEngine.recordDecision(
                    SupervisorDecisionRecord(
                        taskId = task.taskId,
                        decisionType = SupervisorDecisionType.TASK_RESUMED,
                        reason = "Bounded reconciliation detected eligible active/expired-lease task; resuming autonomously.",
                        evidence = "status=${task.status}, expiredLease=${task.taskId in expiredLeaseTasks}",
                        actionTaken = "Resume superviseTask(${task.taskId})",
                        result = "RESUMED",
                    ),
                )
                superviseTask(
                    taskId = task.taskId,
                    team = team,
                    requiredDeliverableFiles = requiredDeliverableFilesByTask[task.taskId].orEmpty(),
                )
                resumed.add(task.taskId)
            }
        }
        return resumed
    }

    /**
     * Explicit Watchdog -> Recovery Engine handoff.
     * Consumes a typed [WatchdogEvaluation], verifies the task was NOT user-cancelled/paused,
     * records [SupervisorDecisionType.TIMEOUT_DETECTED], invokes [AutonomousRecoveryEngine.planRecovery],
     * and applies the selected strategy (retry / model switch / provider failover / agent reassignment / block).
     */
    fun handleWatchdogEvaluation(
        eval: WatchdogEvaluation,
        team: AgentTeamEntity,
        nowMs: Long = nowProvider(),
    ): RecoveryPlan? {
        if (!eval.shouldCancelAndRecover || eval.deduplicated) return null
        val task = taskStore.getTask(eval.taskId) ?: return null
        // Never treat user cancellation or pause as a retryable failure!
        if (task.ownerStopRequested || task.status == PersistentTaskStatus.CANCELLED ||
            task.pauseRequested || task.status == PersistentTaskStatus.PAUSED
        ) {
            return null
        }
        val step = taskStore.getStep(eval.subtaskId) ?: return null
        val teamProfiles = teamManager.getTeamProfiles(team.teamId, onlyEnabled = true)
        val failedAgent = teamProfiles.firstOrNull { it.agentId == eval.agentId } ?: return null

        runtime.cancel(eval.executionId)
        recoveryEngine.recordDecision(
            SupervisorDecisionRecord(
                taskId = task.taskId,
                stepId = step.stepId,
                agentId = failedAgent.agentId,
                executionId = eval.executionId,
                decisionType = SupervisorDecisionType.TIMEOUT_DETECTED,
                reason = eval.reason,
                evidence = "diagnosis=${eval.diagnosis.name}, recoveryActionId=${eval.recoveryActionId}",
                actionTaken = "Cancel stalled execution and invoke AutonomousRecoveryEngine",
                result = "ROUTED_TO_RECOVERY",
                timestampMs = nowMs,
            ),
        )

        val err = StructuredErrorClassifier.classify(
            message = eval.reason,
            isTimeout = eval.diagnosis != WatchdogDiagnosis.CONFIRMED_IDLE_STREAM_STALL,
            isIdleStream = eval.diagnosis == WatchdogDiagnosis.CONFIRMED_IDLE_STREAM_STALL,
        )
        val preserved = taskStore.getLatestCheckpoint(task.taskId)?.modifiedFiles.orEmpty()
        val plan = recoveryEngine.planRecovery(
            task = task,
            step = step,
            failedAgent = failedAgent,
            executionId = eval.executionId,
            error = err,
            requiredCapabilities = inferRequiredCapabilities(step),
            teamCandidates = teamProfiles,
            preservedFiles = preserved,
            recoveryKey = "wd:${eval.executionId}:${eval.diagnosis.name}",
            nowMs = nowMs,
        )

        when (plan.strategy) {
            RecoveryStrategyKind.RETRY_SAME_MODEL,
            RecoveryStrategyKind.WAIT_FOR_QUOTA_RESET -> {
                taskStore.upsertStep(step.copy(status = StepStatus.READY, retryCount = step.retryCount + 1))
            }
            RecoveryStrategyKind.SWITCH_MODEL_SAME_PROVIDER,
            RecoveryStrategyKind.SWITCH_PROVIDER_AND_MODEL -> {
                registry.register(
                    failedAgent.copy(
                        providerId = plan.targetProviderId,
                        modelId = plan.targetModelId,
                        updatedAt = nowMs,
                    ),
                )
                taskStore.upsertStep(
                    step.copy(
                        status = StepStatus.READY,
                        retryCount = 0,
                        inputContext = buildRecoveryHandoffContext(step.inputContext, plan),
                    ),
                )
            }
            RecoveryStrategyKind.REASSIGN_AGENT -> {
                val existingDel = collabStore.listDelegationsForTask(task.taskId)
                    .lastOrNull { it.parentStepId == step.stepId && it.status != DelegationStatus.COMPLETED }
                    ?: delegationManager.delegate(
                        team = team,
                        candidates = listOf(failedAgent),
                        taskId = task.taskId,
                        parentStepId = step.stepId,
                        delegatingAgentId = "supervisor",
                        objective = step.description,
                        requiredCapabilities = inferRequiredCapabilities(step),
                        currentDepth = 1,
                    )
                failureManager.recordFailureAndReassign(
                    delegationId = existingDel.delegationId,
                    failedAgentId = failedAgent.agentId,
                    reason = eval.reason,
                    candidates = teamProfiles,
                )
                taskStore.upsertStep(
                    step.copy(
                        assignedAgentId = plan.targetAgentId,
                        status = StepStatus.READY,
                        retryCount = 0,
                        inputContext = buildRecoveryHandoffContext(step.inputContext, plan),
                    ),
                )
            }
            RecoveryStrategyKind.BLOCK_NEEDS_ATTENTION -> {
                taskStore.upsertStep(step.copy(status = StepStatus.BLOCKED, outputSummary = plan.reason))
                blockTask(task, plan.reason)
            }
        }
        updateSnapshot(task.taskId, SupervisorOperationalStatus.RECOVERING, plan.reason)
        return plan
    }

    private fun evaluateCompletionGaps(
        task: TaskEntity,
        steps: List<TaskStepEntity>,
        workspaceDir: File,
        requiredDeliverableFiles: List<String>,
    ): List<String> {
        val gaps = mutableListOf<String>()
        if (steps.isEmpty()) gaps.add("No steps planned")
        // SKIPPED must NEVER automatically count as successful completion for mandatory steps!
        val unfinished = steps.filter { it.status != StepStatus.COMPLETED }
        if (unfinished.isNotEmpty()) gaps.add("Mandatory steps not completed: ${unfinished.joinToString { "${it.title}(${it.status})" }}")

        if (requiredDeliverableFiles.isNotEmpty()) {
            val fileVerify = ResultVerifier.verify(
                workspaceDir = workspaceDir,
                outputSummary = "Deliverable check",
                spec = VerificationSpec(
                    mode = VerificationMode.FILE_VERIFICATION,
                    expectedFiles = requiredDeliverableFiles,
                ),
            )
            if (!fileVerify.passed) gaps.add(fileVerify.details)
        }

        if (task.completionCriteria.requireReviewerApproval) {
            val latestReview = collabStore.listReviewsForTask(task.taskId).maxByOrNull { it.createdAt }
            val approved = latestReview != null &&
                (latestReview.outcome == ReviewOutcome.APPROVED || latestReview.outcome == ReviewOutcome.APPROVED_WITH_WARNINGS)
            if (!approved) gaps.add("Independent reviewer approval missing")
        }

        if (task.completionCriteria.requireTestsPass) {
            val persistedTests = taskStore.listVerificationEvidenceForTask(task.taskId)
                .filter { it.evidenceType == "TEST" }
            val realTestPassed = persistedTests.any { it.exitCode == 0 }
            if (!realTestPassed) {
                gaps.add("Required real test execution evidence (exitCode=0) is missing")
            }
        }

        if (task.completionCriteria.requireBuildPass) {
            val buildVerify = ResultVerifier.verify(
                workspaceDir = workspaceDir,
                outputSummary = "Android APK build verification",
                spec = VerificationSpec(
                    mode = VerificationMode.BUILD_VERIFICATION,
                    expectedApkVariant = "debug",
                ),
            )
            if (!buildVerify.passed) gaps.add(buildVerify.details)
        }
        return gaps
    }

    private fun persistStepVerificationEvidence(
        taskId: String,
        step: TaskStepEntity,
        output: StepExecutionOutput,
        changedFiles: List<String>,
    ) {
        output.testEvidence.forEach { ev ->
            taskStore.insertVerificationEvidence(
                VerificationEvidenceEntity(
                    taskId = taskId,
                    partId = step.partId,
                    stepId = step.stepId,
                    evidenceType = "TEST",
                    command = ev.command,
                    workingDir = ev.workingDir,
                    exitCode = ev.exitCode,
                    stdout = ev.stdout,
                    stderr = ev.stderr,
                    passedCount = ev.passedCount,
                    failedCount = ev.failedCount,
                    skippedCount = ev.skippedCount,
                    changedFiles = changedFiles,
                    artifactPath = ev.artifactPath,
                    artifactSizeBytes = ev.artifactSizeBytes,
                    timestampMs = ev.completedAtMs,
                ),
            )
        }
        output.buildEvidence.forEach { bev ->
            taskStore.insertVerificationEvidence(
                VerificationEvidenceEntity(
                    taskId = taskId,
                    partId = step.partId,
                    stepId = step.stepId,
                    evidenceType = "BUILD",
                    command = bev.command,
                    exitCode = bev.exitCode,
                    stderr = bev.errorSummary.orEmpty(),
                    changedFiles = changedFiles,
                    artifactPath = bev.apkPath,
                    artifactSizeBytes = bev.apkSizeBytes,
                    timestampMs = nowProvider(),
                ),
            )
        }
        if (step.requiresReview) {
            taskStore.insertVerificationEvidence(
                VerificationEvidenceEntity(
                    taskId = taskId,
                    partId = step.partId,
                    stepId = step.stepId,
                    evidenceType = "REVIEW",
                    reviewVerdict = if (output.reviewApproved) "REVIEW_APPROVED" else "REVIEW_REJECTED",
                    stdout = output.reviewFeedback ?: output.summary.take(500),
                    changedFiles = changedFiles,
                    timestampMs = nowProvider(),
                ),
            )
        }
    }

    private fun buildRemediationSteps(
        task: TaskEntity,
        team: AgentTeamEntity,
        existingSteps: List<TaskStepEntity>,
        missing: List<String>,
        requiredDeliverableFiles: List<String>,
    ): List<PlannedStep> {
        val profiles = teamManager.getTeamProfiles(team.teamId, onlyEnabled = true)
        val leaderId = profiles.firstOrNull { "lead" in it.capabilities || "architect" in it.capabilities }?.agentId
        val dev = capabilityResolver.selectBestAgent(profiles, setOf("code"), excludeAgentIds = setOfNotNull(leaderId))
            ?: capabilityResolver.selectBestAgent(profiles, setOf("code"))
            ?: profiles.firstOrNull()
            ?: return emptyList()
        val reviewer = capabilityResolver.selectBestAgent(profiles, setOf("review", "qa"), excludeAgentIds = setOf(dev.agentId)) ?: dev
        val nextSeq = (existingSteps.maxOfOrNull { it.sequenceNumber } ?: 0) + 1
        val lastStepIds = existingSteps.lastOrNull()?.let { listOf(it.stepId) }.orEmpty()
        val roundNum = (existingSteps.size / 2) + 1

        // If ALL missing criteria are strictly review/test sign-offs (no missing deliverables or code),
        // go straight to the Reviewer/Test verification step instead of repeating Dev work!
        val onlyReviewOrTestMissing = existingSteps.isNotEmpty() && missing.all { m ->
            val lower = m.lowercase()
            lower.contains("review") || lower.contains("test")
        }
        if (onlyReviewOrTestMissing) {
            return listOf(
                PlannedStep(
                    sequenceNumber = nextSeq,
                    title = "Independent Review & Verification (round $roundNum)",
                    description = "Inspect actual workspace changes and run real verification tests for: ${task.title}. Remaining checks: ${missing.joinToString("; ")}.",
                    expectedOutput = "Independent REVIEW_APPROVED + real passing test execution evidence",
                    dependencies = lastStepIds,
                    requiredCapabilities = setOf("review", "qa"),
                    assignedAgentId = reviewer.agentId,
                    requiresReview = true,
                ),
            )
        }

        val implTitle = if (existingSteps.isEmpty()) {
            "Execute dynamic plan: ${task.title.take(48)}"
        } else {
            "Remediation for missing deliverables (round $roundNum)"
        }
        val implStep = PlannedStep(
            sequenceNumber = nextSeq,
            title = implTitle,
            description = "Address missing requirements: ${missing.joinToString("; ")}",
            expectedOutput = "All required deliverables and fixes in place",
            dependencies = lastStepIds,
            requiredCapabilities = setOf("code"),
            assignedAgentId = dev.agentId,
            targetFiles = requiredDeliverableFiles,
            requiresReview = false,
        )
        val verifyStep = PlannedStep(
            sequenceNumber = nextSeq + 1,
            title = "Independent Review & Verification (round $roundNum)",
            description = "Inspect deliverables and run real verification tests for: ${task.title}.",
            expectedOutput = "Independent REVIEW_APPROVED + real passing test execution evidence",
            dependencies = listOf(implStep.stepId),
            requiredCapabilities = setOf("review", "qa"),
            assignedAgentId = reviewer.agentId,
            requiresReview = true,
        )
        return listOf(implStep, verifyStep)
    }

    private fun appendFollowUpSteps(
        task: TaskEntity,
        team: AgentTeamEntity,
        existingSteps: List<TaskStepEntity>,
        newSteps: List<PlannedStep>,
        reason: String,
    ) {
        val profiles = teamManager.getTeamProfiles(team.teamId, onlyEnabled = true)
        var seq = (existingSteps.maxOfOrNull { it.sequenceNumber } ?: 0) + 1
        val createdSummary = mutableListOf<String>()
        val activeSteps = existingSteps.filter { it.status in setOf(StepStatus.PENDING, StepStatus.READY, StepStatus.RUNNING) }
        taskStore.runInTransaction {
            newSteps.forEach { p ->
                // Duplicate Step Prevention: never append a duplicate remediation step when an
                // active/pending step with the same review/implementation purpose already exists.
                val duplicateExists = activeSteps.any { existing ->
                    existing.requiresReview == p.requiresReview &&
                        (existing.title.equals(p.title, ignoreCase = true) ||
                            (p.partId != null && existing.partId == p.partId))
                }
                if (duplicateExists) {
                    updateMetrics(task.taskId) { it.copy(duplicateStepsPrevented = it.duplicateStepsPrevented + 1) }
                    return@forEach
                }
                val assignee = profiles.firstOrNull { it.agentId == p.assignedAgentId }
                    ?: capabilityResolver.selectBestAgent(profiles, p.requiredCapabilities)
                    ?: profiles.first()
                val assignedSeq = seq++
                createdSummary += "#$assignedSeq ${p.title} (${assignee.displayName})"
                taskStore.upsertStep(
                    TaskStepEntity(
                        stepId = p.stepId,
                        taskId = task.taskId,
                        sequenceNumber = assignedSeq,
                        title = p.title,
                        description = p.description,
                        assignedAgentId = assignee.agentId,
                        status = if (p.dependencies.isEmpty()) StepStatus.READY else StepStatus.PENDING,
                        dependencies = p.dependencies,
                        inputContext = p.expectedOutput,
                        targetFiles = p.targetFiles,
                        requiresReview = p.requiresReview,
                        partId = p.partId,
                    ),
                )
            }
        }
        if (createdSummary.isNotEmpty()) {
            // P0 Fix: Emit "REMEDIATION" (not "PLANNED") so the UI never jumps back to PLANNING
            // or spams "Updated active plan" during active execution/verification.
            onStepLifecycleUpdate(
                StepLifecycleUpdate(
                    projectId = task.projectId,
                    taskId = task.taskId,
                    stepId = newSteps.first().stepId,
                    stepTitle = newSteps.first().title,
                    agentId = AgentSlot.AGENT_1.id,
                    agentDisplayName = "Supervisor",
                    phase = "REMEDIATION",
                    detail = "Scheduled ${createdSummary.size} remediation step(s):\n" + createdSummary.joinToString("\n") { "• $it" },
                    stepNumber = (existingSteps.maxOfOrNull { it.sequenceNumber } ?: 0) + 1,
                    totalSteps = seq - 1,
                ),
            )
        }
        recoveryEngine.recordDecision(
            SupervisorDecisionRecord(
                taskId = task.taskId,
                decisionType = SupervisorDecisionType.TASK_REPLANNED,
                reason = reason,
                evidence = "Appended ${newSteps.size} follow-up step(s)",
                actionTaken = "Persist follow-up subtasks preserving completed work",
                result = "REPLANNED",
            ),
        )
    }

    private fun inferRequiredCapabilities(step: TaskStepEntity): Set<String> = when {
        step.requiresReview || step.title.contains("verify", ignoreCase = true) || step.title.contains("review", ignoreCase = true) || step.title.contains("ทดสอบ") -> setOf("review", "qa")
        step.title.startsWith("Implement", ignoreCase = true) || step.title.contains("พัฒนาโค้ด") -> setOf("code")
        step.title.contains("inspect", ignoreCase = true) || step.title.contains("วางแผน") || step.title.contains("plan", ignoreCase = true) -> setOf("lead")
        else -> setOf("code")
    }

    private fun buildRecoveryHandoffContext(previousInput: String, plan: RecoveryPlan): String = buildString {
        if (previousInput.isNotBlank()) appendLine(previousInput)
        appendLine("RECOVERY HANDOFF: ${plan.reason}")
        if (plan.preservedModifiedFiles.isNotEmpty()) {
            appendLine("PRESERVED MODIFIED FILES (inspect diff before overlapping edits): ${plan.preservedModifiedFiles.joinToString(", ")}")
        }
    }

    private fun blockTask(task: TaskEntity, reason: String): TaskEntity {
        val blocked = task.copy(
            status = PersistentTaskStatus.BLOCKED,
            lastError = SecretSanitizer.sanitize(reason),
            executionLeaseOwner = null,
            updatedAt = nowProvider(),
        )
        taskStore.upsertTask(blocked)
        updateSnapshot(task.taskId, SupervisorOperationalStatus.BLOCKED, reason)
        return blocked
    }

    private fun updateSnapshot(
        taskId: String,
        status: SupervisorOperationalStatus,
        detail: String,
        activeAgentId: String? = null,
        activeAgentName: String? = null,
        activeStepTitle: String? = null,
        activePhase: String = status.name,
    ) {
        val steps = taskStore.listStepsForTask(taskId)
        val snap = SupervisorUiSnapshot(
            taskId = taskId,
            status = status,
            statusDetail = detail,
            agentHealth = healthMonitor.snapshotAll(),
            recoveryHistory = recoveryEngine.auditTrail(taskId),
            completedSteps = steps.count { it.status == StepStatus.COMPLETED },
            totalSteps = steps.size,
            activeAgentId = activeAgentId,
            activeAgentName = activeAgentName,
            activeStepTitle = activeStepTitle,
            activePhase = activePhase,
            efficiencyMetrics = efficiencyMetricsFor(taskId),
            spvConnectionState = spvConnectionStates[taskId] ?: ModelConnectionState.CONNECTED,
            devConnectionState = devConnectionStates[taskId] ?: ModelConnectionState.CONNECTED,
        )
        val next = LinkedHashMap(_snapshots.value)
        next[taskId] = snap
        _snapshots.value = next
    }
}
