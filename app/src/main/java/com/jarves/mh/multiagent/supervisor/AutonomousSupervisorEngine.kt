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
    private val planner: StructuredTaskPlanner = StructuredTaskPlanner(),
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

    fun snapshotFor(taskId: String): SupervisorUiSnapshot? = _snapshots.value[taskId]

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
        val project = taskStore.getProject(projectId) ?: return null
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

        val rawPlan = planner.planTask(project, normalizedObj, requestedSubtasks, acceptanceCriteria)
        val workloads = mutableMapOf<String, Int>()
        val baseSteps = rawPlan.steps.ifEmpty {
            // Guarantee at least 2 concrete steps so a task NEVER enters supervision with "No steps planned"!
            val dev = capabilityResolver.selectBestAgent(candidates, setOf("code")) ?: candidates.first()
            val reviewer = capabilityResolver.selectBestAgent(candidates, setOf("review", "qa"), excludeAgentIds = setOf(dev.agentId)) ?: dev
            val impl = com.jarves.mh.multiagent.engine.PlannedStep(
                sequenceNumber = 1,
                title = "วิเคราะห์และพัฒนา: ${normalizedObj.take(56)}",
                description = normalizedObj,
                expectedOutput = "Implemented changes in project workspace",
                requiredCapabilities = setOf("code"),
                assignedAgentId = dev.agentId,
                requiresReview = false,
            )
            val verify = com.jarves.mh.multiagent.engine.PlannedStep(
                sequenceNumber = 2,
                title = "ตรวจสอบโค้ดและยืนยันผลทดสอบ",
                description = "Review implemented changes and verify tests pass for: $normalizedObj",
                expectedOutput = "REVIEW_APPROVED and TESTS_PASSED",
                dependencies = listOf(impl.stepId),
                requiredCapabilities = setOf("review", "qa"),
                assignedAgentId = reviewer.agentId,
                requiresReview = true,
            )
            listOf(impl, verify)
        }
        val assignedSteps = baseSteps.map { step ->
            val selected = capabilityResolver.selectBestAgent(candidates, step.requiredCapabilities, activeWorkloads = workloads)
                ?: candidates.first()
            workloads[selected.agentId] = (workloads[selected.agentId] ?: 0) + 1
            step.copy(assignedAgentId = selected.agentId)
        }
        val plannedTask = planner.persistPlan(
            store = taskStore,
            projectId = projectId,
            plan = rawPlan.copy(steps = assignedSteps),
            budget = budget,
        ).let { t ->
            val updated = t.copy(
                completionCriteria = completionCriteria,
                taskMemory = TaskAttachmentCodec.encodeIntoTaskMemory(t.taskMemory, attachmentPaths),
            )
            taskStore.upsertTask(updated)
            updated
        }
        updateSnapshot(plannedTask.taskId, SupervisorOperationalStatus.PLANNING, "Plan created with ${assignedSteps.size} steps")
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
        var reviewRejectCycles = 0
        var replanAttempts = 0

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
                if ((nowProvider() - loopStartedAt) > limits.maxTotalTaskDurationMs) {
                    return blockTask(task, "Exceeded maximum autonomous task duration (${limits.maxTotalTaskDurationMs}ms).")
                }

                // 4. Load steps & find next runnable step
                val allSteps = taskStore.listStepsForTask(taskId)
                val completedIds = allSteps.filter { it.status == StepStatus.COMPLETED || it.status == StepStatus.SKIPPED }
                    .map { it.stepId }
                    .toSet()

                val runnableSteps = allSteps.filter { step ->
                    (step.status == StepStatus.PENDING || step.status == StepStatus.READY) &&
                        step.dependencies.all { it in completedIds }
                }

                // Phase 2 (Part A.5.1): Execute independent non-review subtasks concurrently up to team.maxConcurrentAgents
                val parallelBatch = if (runnableSteps.size > 1 && team.maxConcurrentAgents > 1) {
                    val seenAgents = mutableSetOf<String>()
                    val seenFiles = mutableSetOf<String>()
                    runnableSteps.filter { s ->
                        !s.requiresReview &&
                            taskStore.findIdempotency(s.idempotencyKey) == null &&
                            s.assignedAgentId !in seenAgents &&
                            s.targetFiles.none { it in seenFiles }
                    }.take(team.maxConcurrentAgents).also { batch ->
                        batch.forEach {
                            seenAgents.add(it.assignedAgentId)
                            seenFiles.addAll(it.targetFiles)
                        }
                    }
                } else emptyList()

                if (parallelBatch.size > 1) {
                    val teamProfiles = teamManager.getTeamProfiles(team.teamId, onlyEnabled = true)
                    val accessibleImages = TaskAttachmentCodec.resolveAccessibleFiles(task.taskMemory, workspaceDir)
                    coroutineScope {
                        parallelBatch.map { pStep ->
                            async {
                                val prof = teamProfiles.firstOrNull { it.agentId == pStep.assignedAgentId }
                                    ?: teamProfiles.firstOrNull() ?: return@async
                                val execId = "exec-${UUID.randomUUID().toString().take(10)}"
                                val lease = livenessMonitor.tryAcquireLease(taskId, pStep.stepId, prof.agentId, timeoutPolicy.agentExecutionTimeoutMs, nowProvider())
                                    ?: return@async
                                val slot = AgentSlot.fromId(prof.agentId) ?: AgentSlot.AGENT_1
                                val locked = mutableListOf<String>()
                                for (path in pStep.targetFiles) {
                                    if (fileLockManager.tryAcquire(path, slot, taskId, "Parallel ${pStep.title}") is LockAcquisitionResult.Success) {
                                        locked.add(path)
                                    } else {
                                        locked.forEach { fileLockManager.release(it, slot) }
                                        livenessMonitor.releaseLease(taskId, pStep.stepId, prof.agentId)
                                        return@async
                                    }
                                }
                                try {
                                    val runStep = pStep.copy(status = StepStatus.RUNNING, startedAt = nowProvider())
                                    val execRec = AgentExecutionEntity(execId, taskId, pStep.stepId, prof.agentId, prof.providerId, prof.modelId, "RUNNING")
                                    taskStore.runInTransaction {
                                        taskStore.upsertStep(runStep)
                                        taskStore.insertExecution(execRec)
                                    }
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
                                    if (out.success) {
                                        val cp = checkpointManager.buildCheckpoint(taskId, runStep.stepId, workspaceDir, out.modifiedFiles, out.summary)
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
                                    } else {
                                        // Return failed step to READY so main sequential recovery path handles failover/reassignment cleanly
                                        taskStore.upsertStep(runStep.copy(status = StepStatus.READY))
                                        taskStore.updateExecution(execRec.copy(executionStatus = "FAILED", completedAt = nowProvider(), errorMessage = out.error?.message ?: out.summary))
                                    }
                                } finally {
                                    locked.forEach { fileLockManager.release(it, slot) }
                                    livenessMonitor.releaseLease(taskId, pStep.stepId, prof.agentId)
                                }
                            }
                        }.awaitAll()
                    }
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
                    if (replanAttempts < limits.maxReplanAttempts) {
                        replanAttempts++
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

                // 6. Resolve active agent profile (respecting dynamic model/provider overrides)
                val teamProfiles = teamManager.getTeamProfiles(team.teamId, onlyEnabled = true)
                val agentProfile = teamProfiles.firstOrNull { it.agentId == nextStep.assignedAgentId }
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
                val totalPlannedSteps = allSteps.size.coerceAtLeast(nextStep.sequenceNumber)
                val retrySuffix = if (nextStep.retryCount > 0) " (ลองใหม่รอบที่ ${nextStep.retryCount + 1})" else ""
                updateSnapshot(taskId, SupervisorOperationalStatus.SUPERVISING, "ขั้นตอน ${nextStep.sequenceNumber}/$totalPlannedSteps: ${nextStep.title}$retrySuffix (${agentProfile.displayName} / ${agentProfile.modelId})")
                onStepLifecycleUpdate(
                    StepLifecycleUpdate(
                        projectId = task.projectId,
                        taskId = taskId,
                        stepId = nextStep.stepId,
                        stepTitle = nextStep.title,
                        agentId = agentProfile.agentId,
                        agentDisplayName = agentProfile.displayName,
                        phase = "STARTED",
                        detail = "📌 เริ่มขั้นตอนที่ ${nextStep.sequenceNumber}/$totalPlannedSteps: ${nextStep.title}$retrySuffix (ใช้โมเดล ${agentProfile.modelId})",
                        stepNumber = nextStep.sequenceNumber,
                        totalSteps = totalPlannedSteps,
                    ),
                )
                val runningStep = nextStep.copy(status = StepStatus.RUNNING, startedAt = nextStep.startedAt ?: nowProvider())
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

                val afterSnap = checkpointManager.snapshotWorkspace(workspaceDir)
                val changedFiles = (output.modifiedFiles + checkpointManager.diffSnapshots(beforeSnap, afterSnap)).distinct().sorted()
                val verificationOutcome = output.verificationSpec?.let { ResultVerifier.verify(workspaceDir, output.summary, it) }
                val verificationFailed = output.success && verificationOutcome != null && !verificationOutcome.passed

                if (output.success && !verificationFailed) {
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
                        val devAgent = capabilityResolver.selectBestAgent(
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
                        val fixStep = TaskStepEntity(
                            taskId = taskId,
                            sequenceNumber = nextSeq,
                            title = "Fix review feedback: ${runningStep.title}",
                            description = output.reviewFeedback ?: "Address reviewer findings",
                            assignedAgentId = devAgent.agentId,
                            status = StepStatus.READY,
                            dependencies = listOf(runningStep.stepId),
                            targetFiles = runningStep.targetFiles,
                            requiresReview = false,
                        )
                        val reReviewStep = TaskStepEntity(
                            taskId = taskId,
                            sequenceNumber = nextSeq + 1,
                            title = "Re-verify: ${runningStep.title}",
                            description = "Independently verify fixes and tests for ${runningStep.title}",
                            assignedAgentId = runningStep.assignedAgentId,
                            status = StepStatus.PENDING,
                            dependencies = listOf(fixStep.stepId),
                            targetFiles = runningStep.targetFiles,
                            requiresReview = true,
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
                    val completedTotalSteps = allSteps.size.coerceAtLeast(runningStep.sequenceNumber)
                    val nextPendingStep = allSteps.firstOrNull {
                        it.sequenceNumber > runningStep.sequenceNumber && it.status != StepStatus.COMPLETED
                    }
                    val completionDetail = buildString {
                        appendLine("✅ ทำขั้นตอนที่ ${runningStep.sequenceNumber}/$completedTotalSteps เสร็จแล้ว: ${runningStep.title}")
                        if (changedFiles.isNotEmpty()) {
                            appendLine("📁 ไฟล์ที่อัปเดต (${changedFiles.size}): ${changedFiles.take(4).joinToString(", ")}")
                        }
                        val cleanOut = output.summary.trim().take(320)
                        if (cleanOut.isNotBlank()) {
                            appendLine(cleanOut)
                        }
                        if (nextPendingStep != null) {
                            append("➡️ ขั้นตอนถัดไป (#${nextPendingStep.sequenceNumber}/$completedTotalSteps): ${nextPendingStep.title}")
                        } else {
                            append("🔍 ส่งต่อให้ Supervisor ตรวจสอบความครบถ้วนและผลทดสอบครับ")
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
                    // Failure OR Failed Test Verification!
                    if (verificationFailed && output.verificationSpec?.mode == VerificationMode.TEST_VERIFICATION) {
                        // Failed tests trigger automatic correction + retest cycle!
                        if (replanAttempts < limits.maxReplanAttempts) {
                            replanAttempts++
                            val devAgent = capabilityResolver.selectBestAgent(teamProfiles, setOf("code"), excludeAgentIds = setOf(agentProfile.agentId)) ?: agentProfile
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
                            val fixStep = TaskStepEntity(
                                taskId = taskId,
                                sequenceNumber = nextSeq,
                                title = "Fix failing tests: ${runningStep.title}",
                                description = "Fix test failures reported by ${agentProfile.displayName}: ${output.summary.take(300)}",
                                assignedAgentId = devAgent.agentId,
                                status = StepStatus.READY,
                                dependencies = listOf(runningStep.stepId),
                                requiresReview = false,
                            )
                            val retestStep = TaskStepEntity(
                                taskId = taskId,
                                sequenceNumber = nextSeq + 1,
                                title = "Re-run verification tests",
                                description = "Verify failing tests now pass for ${task.title}",
                                assignedAgentId = agentProfile.agentId,
                                status = StepStatus.PENDING,
                                dependencies = listOf(fixStep.stepId),
                                requiresReview = runningStep.requiresReview,
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

                    when (recoveryPlan.strategy) {
                        RecoveryStrategyKind.RETRY_SAME_MODEL -> {
                            if (recoveryPlan.backoffMs > 0L) delayFn(recoveryPlan.backoffMs)
                            taskStore.upsertStep(runningStep.copy(status = StepStatus.READY, retryCount = runningStep.retryCount + 1))
                        }

                        RecoveryStrategyKind.SWITCH_MODEL_SAME_PROVIDER,
                        RecoveryStrategyKind.SWITCH_PROVIDER_AND_MODEL -> {
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

                        RecoveryStrategyKind.BLOCK_NEEDS_ATTENTION -> {
                            taskStore.upsertStep(runningStep.copy(status = StepStatus.BLOCKED, outputSummary = recoveryPlan.reason))
                            return blockTask(task, recoveryPlan.reason)
                        }
                    }
                }
            }
            return taskStore.getTask(taskId)
        } finally {
            activeSupervisedTasks.remove(taskId)
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
        var lastErr: String? = null
        for (model in pool) {
            try {
                return advisor.evaluateMissingRequirements(model, task, completedSteps, workspaceDir)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Throwable) {
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
            RecoveryStrategyKind.RETRY_SAME_MODEL -> {
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
            val testsPassed = steps.any {
                it.status == StepStatus.COMPLETED &&
                    (it.title.contains("test", ignoreCase = true) || it.title.contains("verify", ignoreCase = true)) &&
                    it.outputSummary.contains("TESTS_PASSED", ignoreCase = true)
            }
            if (!testsPassed) gaps.add("Required tests have not reported TESTS_PASSED")
        }
        return gaps
    }

    private fun buildRemediationSteps(
        task: TaskEntity,
        team: AgentTeamEntity,
        existingSteps: List<TaskStepEntity>,
        missing: List<String>,
        requiredDeliverableFiles: List<String>,
    ): List<PlannedStep> {
        val profiles = teamManager.getTeamProfiles(team.teamId, onlyEnabled = true)
        val dev = capabilityResolver.selectBestAgent(profiles, setOf("code")) ?: profiles.firstOrNull() ?: return emptyList()
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
                    title = "ตรวจสอบโค้ดและยืนยันผลทดสอบ (รอบที่ $roundNum)",
                    description = "Verify completed work and run tests for: ${task.title}. Remaining checks: ${missing.joinToString("; ")}",
                    expectedOutput = "REVIEW_APPROVED and TESTS_PASSED",
                    dependencies = lastStepIds,
                    requiredCapabilities = setOf("review", "qa"),
                    assignedAgentId = reviewer.agentId,
                    requiresReview = true,
                ),
            )
        }

        val implTitle = if (existingSteps.isEmpty()) {
            "วิเคราะห์และพัฒนาตามคำสั่ง: ${task.title.take(48)}"
        } else {
            "พัฒนาและแก้ไขส่วนที่ยังขาดให้ครบ (รอบที่ $roundNum)"
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
            title = "ตรวจสอบโค้ดและยืนยันผลทดสอบ (รอบที่ $roundNum)",
            description = "Verify deliverables are complete and run tests for: ${task.title}",
            expectedOutput = "REVIEW_APPROVED and TESTS_PASSED",
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
        taskStore.runInTransaction {
            newSteps.forEach { p ->
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
                    ),
                )
            }
        }
        if (createdSummary.isNotEmpty()) {
            onStepLifecycleUpdate(
                StepLifecycleUpdate(
                    projectId = task.projectId,
                    taskId = task.taskId,
                    stepId = newSteps.first().stepId,
                    stepTitle = newSteps.first().title,
                    agentId = AgentSlot.AGENT_1.id,
                    agentDisplayName = "Supervisor",
                    phase = "PLANNED",
                    detail = "📋 SPV วางแผนขั้นตอนเพิ่มเติม (${createdSummary.size} ขั้นตอน):\n" + createdSummary.joinToString("\n") { "• $it" },
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
        step.requiresReview || step.title.contains("verify", ignoreCase = true) || step.title.contains("review", ignoreCase = true) -> setOf("review", "qa")
        step.title.contains("inspect", ignoreCase = true) || step.title.contains("plan", ignoreCase = true) -> setOf("lead")
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

    private fun updateSnapshot(taskId: String, status: SupervisorOperationalStatus, detail: String) {
        val steps = taskStore.listStepsForTask(taskId)
        val snap = SupervisorUiSnapshot(
            taskId = taskId,
            status = status,
            statusDetail = detail,
            agentHealth = healthMonitor.snapshotAll(),
            recoveryHistory = recoveryEngine.auditTrail(taskId),
            completedSteps = steps.count { it.status == StepStatus.COMPLETED },
            totalSteps = steps.size,
        )
        val next = LinkedHashMap(_snapshots.value)
        next[taskId] = snap
        _snapshots.value = next
    }
}
