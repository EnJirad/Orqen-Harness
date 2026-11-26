package com.jarves.mh.multiagent.engine

import com.jarves.mh.multiagent.collab.AgentCommunicationManager
import com.jarves.mh.multiagent.collab.AgentReviewEntity
import com.jarves.mh.multiagent.collab.AgentReviewManager
import com.jarves.mh.multiagent.collab.ReviewOutcome
import com.jarves.mh.multiagent.collab.ReviewSeverity
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.model.AgentRole
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.LockAcquisitionResult
import com.jarves.mh.multiagent.persistence.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Configurable agent profile supporting an unlimited number of agents (default 4).
 */
data class DynamicAgentProfile(
    val agentId: String,
    val name: String,
    val roleTitle: String,
    val legacySlot: AgentSlot? = null,
    val providerId: String = "preset-antigravity",
    val modelId: String = "",
    val systemInstructions: String = "",
    val capabilities: Set<String> = emptySet(),
    val isEnabled: Boolean = true,
) {
    companion object {
        fun defaultTeam(): List<DynamicAgentProfile> = listOf(
            DynamicAgentProfile(
                agentId = "agent-leader",
                name = "Agent 01",
                roleTitle = "AI Leader / Architect",
                legacySlot = AgentSlot.AGENT_1,
                capabilities = setOf("lead", "plan", "architect", "coordinate"),
            ),
            DynamicAgentProfile(
                agentId = "agent-developer",
                name = "Agent 02",
                roleTitle = "AI Developer",
                legacySlot = AgentSlot.AGENT_2,
                capabilities = setOf("code", "backend", "frontend", "fix"),
            ),
            DynamicAgentProfile(
                agentId = "agent-reviewer",
                name = "Agent 03",
                roleTitle = "AI Reviewer / QA",
                legacySlot = AgentSlot.AGENT_3,
                capabilities = setOf("review", "qa", "test", "security"),
            ),
            DynamicAgentProfile(
                agentId = "agent-integrator",
                name = "Agent 04",
                roleTitle = "AI Integrator",
                legacySlot = AgentSlot.AGENT_4,
                capabilities = setOf("code", "ui", "integrate"),
            ),
        )
    }
}

data class StepExecutionInput(
    val task: TaskEntity,
    val step: TaskStepEntity,
    val agent: DynamicAgentProfile,
    val workspaceDir: File,
    val compactContext: String,
    val latestCheckpoint: CheckpointEntity?,
)

data class StepExecutionOutput(
    val success: Boolean,
    val summary: String,
    val modifiedFiles: List<String> = emptyList(),
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val actualCostUsd: Double = 0.0,
    val error: ClassifiedError? = null,
    val reviewApproved: Boolean = true,
    val reviewFeedback: String? = null,
    val verificationSpec: VerificationSpec? = null,
    val spawnedFollowUpSteps: List<TaskStepEntity> = emptyList(),
)

/**
 * Pluggable step execution backend (real TeamAgentRunner in Android, deterministic simulator in unit tests).
 */
interface StepExecutionAdapter {
    suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput
}

/**
 * Unbounded, persistent, dependency-aware task execution orchestrator.
 *
 * Architectural guarantees:
 * - No hardcoded step count ceiling (can execute 50, 100, or unlimited steps).
 * - Each AI call is an individual step cycle; a timeout or crash never destroys completed steps.
 * - Cooperative cancellation + durable Pause / Resume / Stop / Stop-All controls.
 * - Cycle detection, stall/loop protection, idempotency ledger, file-lock coordination,
 *   budget guard, and independent QA/Reviewer quality gates.
 */
class PersistentTaskOrchestrator(
    private val store: OrqenTaskStore,
    private val checkpointManager: CheckpointManager,
    private val supervisor: ExecutionRecoverySupervisor,
    private val fileLockManager: FileLockManager,
    private val stepAdapter: StepExecutionAdapter,
    private val policy: RequestExecutionPolicy = RequestExecutionPolicy(),
    private val teamRosterProvider: () -> List<DynamicAgentProfile> = { DynamicAgentProfile.defaultTeam() },
    private val delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
    private val communicationManager: AgentCommunicationManager? = null,
    private val reviewManager: AgentReviewManager? = null,
    private val reviewFeedbackProvider: ((taskId: String) -> List<AgentReviewEntity>)? = null,
    private val finalCompletionVerifier: ((TaskEntity, List<TaskStepEntity>) -> Pair<Boolean, String>)? = null,
    private val maxReviewRejectCycles: Int = MAX_REVIEW_REJECT_CYCLES,
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val inFlightTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val _activeTaskIds = MutableStateFlow<Set<String>>(emptySet())
    val activeTaskIds: StateFlow<Set<String>> = _activeTaskIds.asStateFlow()

    private val instanceOwnerId = "orch-${UUID.randomUUID().toString().take(8)}"

    fun isTaskRunning(taskId: String): Boolean = inFlightTaskIds.contains(taskId) || (activeJobs[taskId]?.isActive == true)

    fun createTaskWithSteps(
        projectId: String,
        title: String,
        description: String,
        steps: List<TaskStepEntity>,
        budget: BudgetConfiguration = BudgetConfiguration(),
        criteria: TaskCompletionCriteria = TaskCompletionCriteria(),
    ): TaskEntity {
        val task = TaskEntity(
            projectId = projectId,
            title = title,
            description = description,
            status = PersistentTaskStatus.QUEUED,
            budgetConfiguration = budget,
            completionCriteria = criteria,
        )
        store.runInTransaction {
            store.upsertTask(task)
            steps.forEachIndexed { idx, s ->
                store.upsertStep(
                    s.copy(
                        taskId = task.taskId,
                        sequenceNumber = if (s.sequenceNumber > 0) s.sequenceNumber else idx + 1,
                        status = if (s.dependencies.isEmpty()) StepStatus.READY else StepStatus.PENDING,
                    ),
                )
            }
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = task.taskId,
                    eventType = "TASK_CREATED",
                    eventPayload = "Created with ${steps.size} step(s): $title",
                ),
            )
        }
        return task
    }

    fun launchTask(scope: CoroutineScope, taskId: String): Job {
        activeJobs[taskId]?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch(Dispatchers.Default) {
            runTaskLoop(taskId)
        }
        activeJobs[taskId] = job
        _activeTaskIds.value = activeJobs.filterValues { it.isActive }.keys.toSet()
        job.invokeOnCompletion {
            activeJobs.remove(taskId)
            _activeTaskIds.value = activeJobs.filterValues { it.isActive }.keys.toSet()
        }
        return job
    }

    fun pauseTask(taskId: String, reason: String = "Paused by owner") {
        val task = store.getTask(taskId) ?: return
        store.runInTransaction {
            store.upsertTask(
                task.copy(
                    pauseRequested = true,
                    status = PersistentTaskStatus.PAUSED,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            store.insertTaskEvent(
                TaskEventEntity(taskId = taskId, eventType = "TASK_PAUSED", eventPayload = reason),
            )
        }
        supervisor.transition(taskId, ExecutionLifecycleState.PAUSED, reason)
        activeJobs[taskId]?.cancel(CancellationException(reason))
    }

    fun resumeTask(scope: CoroutineScope, taskId: String): Job? {
        val task = store.getTask(taskId) ?: return null
        if (task.ownerStopRequested || task.status == PersistentTaskStatus.CANCELLED) {
            return null // Owner explicitly cancelled — never auto-resume!
        }
        store.runInTransaction {
            store.upsertTask(
                task.copy(
                    pauseRequested = false,
                    status = PersistentTaskStatus.QUEUED,
                    lastError = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            store.insertTaskEvent(
                TaskEventEntity(taskId = taskId, eventType = "TASK_RESUMED", eventPayload = "Resumed by owner"),
            )
        }
        return launchTask(scope, taskId)
    }

    fun stopTask(taskId: String, reason: String = "Stopped by owner") {
        val task = store.getTask(taskId) ?: return
        store.runInTransaction {
            store.upsertTask(
                task.copy(
                    ownerStopRequested = true,
                    pauseRequested = false,
                    status = PersistentTaskStatus.CANCELLED,
                    executionLeaseOwner = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            store.findStaleRunningSteps(taskId).forEach { step ->
                store.upsertStep(step.copy(status = StepStatus.CANCELLED))
            }
            store.insertTaskEvent(
                TaskEventEntity(taskId = taskId, eventType = "TASK_CANCELLED", eventPayload = reason),
            )
        }
        supervisor.transition(taskId, ExecutionLifecycleState.CANCELLED, reason)
        activeJobs[taskId]?.cancel(CancellationException(reason))
        AgentSlot.entries.forEach { fileLockManager.releaseAllForAgent(it) }
    }

    fun stopAllTasks(reason: String = "Stop All requested by owner"): Int {
        val toStop = (activeJobs.keys + store.listAllTasks()
            .filter { it.status == PersistentTaskStatus.RUNNING || it.status == PersistentTaskStatus.QUEUED }
            .map { it.taskId }).distinct()
        toStop.forEach { stopTask(it, reason) }
        return toStop.size
    }

    fun retryFailedStep(scope: CoroutineScope, taskId: String): Job? {
        val task = store.getTask(taskId) ?: return null
        if (task.ownerStopRequested) return null
        store.runInTransaction {
            val steps = store.listStepsForTask(taskId)
            steps.filter { it.status == StepStatus.FAILED || it.status == StepStatus.BLOCKED }.forEach { step ->
                store.upsertStep(step.copy(status = StepStatus.READY, retryCount = 0))
            }
            store.upsertTask(
                task.copy(
                    status = PersistentTaskStatus.QUEUED,
                    lastError = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            store.insertTaskEvent(
                TaskEventEntity(taskId = taskId, eventType = "RETRY_FAILED_STEPS", eventPayload = "Owner triggered retry"),
            )
        }
        return launchTask(scope, taskId)
    }

    /**
     * Core unbounded step loop. Runs until completion criteria are satisfied,
     * owner stops/pauses, budget is exceeded, or an unrecoverable blocker occurs.
     */
    suspend fun runTaskLoop(taskId: String): TaskEntity? {
        var task = store.getTask(taskId) ?: return null
        if (task.ownerStopRequested || task.status == PersistentTaskStatus.CANCELLED) {
            supervisor.transition(taskId, ExecutionLifecycleState.CANCELLED, "Owner stop flag set")
            return task
        }
        if (task.pauseRequested || task.status == PersistentTaskStatus.PAUSED) {
            supervisor.transition(taskId, ExecutionLifecycleState.PAUSED, "Pause flag set")
            return task
        }

        if (!inFlightTaskIds.add(taskId)) {
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = taskId,
                    eventType = "DUPLICATE_EXECUTION_PREVENTED",
                    eventPayload = "Task $taskId is already executing; duplicate run rejected.",
                ),
            )
            return task
        }

        val project = store.getProject(task.projectId) ?: run {
            inFlightTaskIds.remove(taskId)
            return task
        }
        val workspaceDir = File(project.workspacePath).apply { mkdirs() }

        supervisor.transition(taskId, ExecutionLifecycleState.STARTING, "Acquiring lease")
        val acquiredTask = store.runInTransaction {
            val fresh = store.getTask(taskId) ?: return@runInTransaction null
            if (fresh.ownerStopRequested || fresh.status == PersistentTaskStatus.CANCELLED ||
                fresh.pauseRequested || fresh.status == PersistentTaskStatus.PAUSED
            ) {
                return@runInTransaction fresh
            }
            val started = fresh.copy(
                status = PersistentTaskStatus.RUNNING,
                startedAt = fresh.startedAt ?: System.currentTimeMillis(),
                executionLeaseOwner = instanceOwnerId,
                lastHeartbeatAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            store.upsertTask(started)
            started
        } ?: run {
            inFlightTaskIds.remove(taskId)
            return null
        }
        task = acquiredTask
        if (task.ownerStopRequested || task.status == PersistentTaskStatus.CANCELLED) {
            inFlightTaskIds.remove(taskId)
            supervisor.transition(taskId, ExecutionLifecycleState.CANCELLED, "Owner cancelled before start")
            return task
        }
        if (task.pauseRequested || task.status == PersistentTaskStatus.PAUSED) {
            inFlightTaskIds.remove(taskId)
            supervisor.transition(taskId, ExecutionLifecycleState.PAUSED, "Owner paused before start")
            return task
        }

        val recentFailureSignatures = mutableListOf<String>()
        var reviewRejectCycles = 0

        try {
            while (currentCoroutineContext().isActive) {
                // 1. Reload authoritative durable task state
                task = store.getTask(taskId) ?: break
                if (task.ownerStopRequested || task.status == PersistentTaskStatus.CANCELLED) {
                    supervisor.transition(taskId, ExecutionLifecycleState.CANCELLED, "Owner cancelled")
                    return task
                }
                if (task.pauseRequested || task.status == PersistentTaskStatus.PAUSED) {
                    supervisor.transition(taskId, ExecutionLifecycleState.PAUSED, "Owner paused")
                    return task
                }

                // 2. Budget guard check
                if (task.budgetConfiguration.autoPauseOnBudgetExceeded &&
                    task.actualCostUsd >= task.budgetConfiguration.maxTaskBudgetUsd
                ) {
                    val msg = "Budget limit reached ($${String.format("%.4f", task.actualCostUsd)} / $${String.format("%.2f", task.budgetConfiguration.maxTaskBudgetUsd)}). Task paused safely."
                    task = task.copy(
                        status = PersistentTaskStatus.PAUSED,
                        pauseRequested = true,
                        lastError = msg,
                        updatedAt = System.currentTimeMillis(),
                    )
                    store.runInTransaction {
                        store.upsertTask(task)
                        store.insertTaskEvent(TaskEventEntity(taskId = taskId, eventType = "BUDGET_PAUSED", eventPayload = msg))
                    }
                    supervisor.transition(taskId, ExecutionLifecycleState.PAUSED, msg)
                    return task
                }

                // 3. Load steps & check for circular dependencies
                val allSteps = store.listStepsForTask(taskId)
                if (hasCircularDependencies(allSteps)) {
                    return markBlocked(task, "Circular step dependency detected in task graph.")
                }

                // 4. Find next runnable step whose dependencies are all COMPLETED
                val completedIds = allSteps.filter { it.status == StepStatus.COMPLETED || it.status == StepStatus.SKIPPED }
                    .map { it.stepId }
                    .toSet()

                val nextStep = allSteps.firstOrNull { step ->
                    (step.status == StepStatus.PENDING || step.status == StepStatus.READY) &&
                        step.dependencies.all { dep -> dep in completedIds }
                }

                if (nextStep == null) {
                    val hasFailedOrBlocked = allSteps.any { it.status == StepStatus.FAILED || it.status == StepStatus.BLOCKED }
                    val allDone = allSteps.isNotEmpty() && allSteps.all { it.status == StepStatus.COMPLETED || it.status == StepStatus.SKIPPED }
                    return when {
                        allDone -> {
                            val verificationResult = finalCompletionVerifier?.invoke(task, allSteps)
                            if (verificationResult != null && !verificationResult.first) {
                                return markBlocked(task, "Final verification failed: ${verificationResult.second}")
                            }
                            task = task.copy(
                                status = PersistentTaskStatus.COMPLETED,
                                completedAt = System.currentTimeMillis(),
                                currentStepId = null,
                                executionLeaseOwner = null,
                                updatedAt = System.currentTimeMillis(),
                            )
                            store.runInTransaction {
                                store.upsertTask(task)
                                store.insertTaskEvent(
                                    TaskEventEntity(
                                        taskId = taskId,
                                        eventType = "TASK_COMPLETED",
                                        eventPayload = verificationResult?.second ?: "All ${allSteps.size} steps verified and completed.",
                                    ),
                                )
                            }
                            supervisor.transition(taskId, ExecutionLifecycleState.COMPLETED, "All criteria satisfied")
                            task
                        }
                        hasFailedOrBlocked -> markBlocked(task, task.lastError ?: "Remaining steps are blocked by failed dependencies.")
                        else -> markBlocked(task, "No runnable steps found (unsatisfied dependencies).")
                    }
                }

                // 5. Idempotency check — NEVER re-execute an already-completed side effect on recovery replay!
                val existingIdem = store.findIdempotency(nextStep.idempotencyKey)
                if (existingIdem != null) {
                    val skippedStep = nextStep.copy(
                        status = StepStatus.COMPLETED,
                        outputSummary = existingIdem.resultSummary,
                        completedAt = existingIdem.completedAt,
                    )
                    store.runInTransaction {
                        store.upsertStep(skippedStep)
                        store.insertTaskEvent(
                            TaskEventEntity(
                                taskId = taskId,
                                eventType = "IDEMPOTENCY_SKIP",
                                eventPayload = "Step ${nextStep.stepId} already completed (${existingIdem.idempotencyKey}); skipped duplicate execution.",
                            ),
                        )
                    }
                    continue
                }

                // 6. Select agent & acquire file locks
                val roster = teamRosterProvider().filter { it.isEnabled }
                val agent = roster.firstOrNull { it.agentId == nextStep.assignedAgentId }
                    ?: roster.firstOrNull()
                    ?: return markBlocked(task, "No enabled AI agents available.")

                val lockedFiles = mutableListOf<String>()
                val lockSlot = agent.legacySlot ?: AgentSlot.AGENT_1
                var lockConflict: String? = null
                for (path in nextStep.targetFiles) {
                    when (val res = fileLockManager.tryAcquire(path, lockSlot, taskId, "Step ${nextStep.title}")) {
                        is LockAcquisitionResult.Success -> lockedFiles.add(path)
                        is LockAcquisitionResult.Conflict -> {
                            lockConflict = "File '$path' is locked by ${res.lockedBy.defaultName}"
                            break
                        }
                        is LockAcquisitionResult.Error -> {
                            lockConflict = res.message
                            break
                        }
                    }
                }
                if (lockConflict != null) {
                    lockedFiles.forEach { fileLockManager.release(it, lockSlot) }
                    store.insertAgentMessage(
                        AgentMessageEntity(
                            taskId = taskId,
                            fromAgentId = agent.agentId,
                            toAgentId = "agent-leader",
                            messageType = AgentMessageKind.CONFLICT_REPORT,
                            content = lockConflict,
                            relatedStepId = nextStep.stepId,
                        ),
                    )
                    delayFn(250L)
                    continue
                }

                // 7. Execute the individual model step cycle
                val execution = AgentExecutionEntity(
                    taskId = taskId,
                    stepId = nextStep.stepId,
                    agentId = agent.agentId,
                    providerId = agent.providerId,
                    modelId = agent.modelId,
                    executionStatus = "RUNNING",
                )
                val runningStep = nextStep.copy(
                    status = StepStatus.RUNNING,
                    startedAt = nextStep.startedAt ?: System.currentTimeMillis(),
                )
                task = task.copy(
                    currentStepId = runningStep.stepId,
                    lastHeartbeatAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
                store.runInTransaction {
                    store.upsertStep(runningStep)
                    store.insertExecution(execution)
                    store.upsertTask(task)
                }

                supervisor.transition(taskId, ExecutionLifecycleState.REQUESTING_MODEL, "Step ${runningStep.sequenceNumber}: ${runningStep.title}")

                val latestCheckpoint = store.getLatestCheckpoint(taskId)
                val beforeSnap = checkpointManager.snapshotWorkspace(workspaceDir)
                val reviewFeedback = reviewFeedbackProvider?.invoke(taskId).orEmpty()
                val compactContext = if (communicationManager != null) {
                    TaskContextBuilder.buildAgentStepContext(
                        project = project,
                        task = task,
                        step = runningStep,
                        allSteps = allSteps,
                        latestCheckpoint = latestCheckpoint,
                        communicationManager = communicationManager,
                        reviewFeedback = reviewFeedback,
                    )
                } else {
                    TaskContextBuilder.buildStepContext(
                        project = project,
                        task = task,
                        step = runningStep,
                        allSteps = allSteps,
                        latestCheckpoint = latestCheckpoint,
                        recentMessages = store.listAgentMessagesForTask(taskId, limit = 20),
                        reviewFeedback = reviewFeedback,
                    )
                }

                val output = try {
                    stepAdapter.executeStep(
                        StepExecutionInput(
                            task = task,
                            step = runningStep,
                            agent = agent,
                            workspaceDir = workspaceDir,
                            compactContext = compactContext,
                            latestCheckpoint = latestCheckpoint,
                        ),
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (err: Throwable) {
                    val classified = StructuredErrorClassifier.classify(
                        message = err.message ?: err.javaClass.simpleName,
                        isTimeout = err is TimeoutCancellationException,
                    )
                    StepExecutionOutput(success = false, summary = "", error = classified)
                } finally {
                    lockedFiles.forEach { fileLockManager.release(it, lockSlot) }
                }

                val afterSnap = checkpointManager.snapshotWorkspace(workspaceDir)
                val detectedChanges = (output.modifiedFiles + checkpointManager.diffSnapshots(beforeSnap, afterSnap)).distinct().sorted()

                // Enforce ResultVerifier whenever a VerificationSpec is declared on the step output
                val verificationFailed = output.success && output.verificationSpec != null &&
                    !ResultVerifier.verify(workspaceDir, output.summary, output.verificationSpec).passed

                if (output.success && !verificationFailed) {
                    recentFailureSignatures.clear()

                    // Quality Gate: if reviewer rejected the work, schedule a corrective dev step + re-review!
                    if (runningStep.requiresReview && !output.reviewApproved) {
                        reviewRejectCycles++
                        if (reviewRejectCycles > maxReviewRejectCycles) {
                            return markBlocked(task, "Quality gate rejected work $reviewRejectCycles consecutive times: ${output.reviewFeedback.orEmpty()}")
                        }
                        val nextSeq = (allSteps.maxOfOrNull { it.sequenceNumber } ?: 0) + 1
                        val devAgentId = roster.firstOrNull { "code" in it.capabilities && it.agentId != agent.agentId }?.agentId
                            ?: roster.firstOrNull { "code" in it.capabilities }?.agentId
                            ?: agent.agentId
                        reviewManager?.submitReview(
                            taskId = taskId,
                            reviewerAgentId = agent.agentId,
                            implementingAgentId = devAgentId,
                            reviewedExecutionId = execution.executionId,
                            reviewedStepId = runningStep.stepId,
                            outcome = ReviewOutcome.CHANGES_REQUESTED,
                            findings = listOf(output.reviewFeedback ?: output.summary),
                            severity = ReviewSeverity.HIGH,
                            requiredChanges = listOf(output.reviewFeedback ?: "Address reviewer findings"),
                            requireIndependentReviewer = (agent.agentId != devAgentId),
                        )
                        val fixStep = TaskStepEntity(
                            taskId = taskId,
                            sequenceNumber = nextSeq,
                            title = "Fix review feedback: ${runningStep.title}",
                            description = output.reviewFeedback ?: "Address reviewer findings",
                            assignedAgentId = devAgentId,
                            status = StepStatus.READY,
                            dependencies = listOf(runningStep.stepId),
                            targetFiles = runningStep.targetFiles,
                            requiresReview = false,
                        )
                        val reReviewStep = TaskStepEntity(
                            taskId = taskId,
                            sequenceNumber = nextSeq + 1,
                            title = "Re-verify: ${runningStep.title}",
                            description = "Independently verify fixes for ${runningStep.title}",
                            assignedAgentId = runningStep.assignedAgentId,
                            status = StepStatus.PENDING,
                            dependencies = listOf(fixStep.stepId),
                            targetFiles = runningStep.targetFiles,
                            requiresReview = true,
                        )
                        store.runInTransaction {
                            store.upsertStep(
                                runningStep.copy(
                                    status = StepStatus.COMPLETED,
                                    outputSummary = "REJECTED: ${output.reviewFeedback ?: output.summary}",
                                    completedAt = System.currentTimeMillis(),
                                ),
                            )
                            store.updateExecution(
                                execution.copy(
                                    executionStatus = "REVIEW_REJECTED",
                                    completedAt = System.currentTimeMillis(),
                                    inputTokens = output.inputTokens,
                                    outputTokens = output.outputTokens,
                                    estimatedCostUsd = output.actualCostUsd,
                                ),
                            )
                            store.insertAgentMessage(
                                AgentMessageEntity(
                                    taskId = taskId,
                                    fromAgentId = agent.agentId,
                                    toAgentId = devAgentId,
                                    messageType = AgentMessageKind.CHANGE_REQUEST,
                                    content = output.reviewFeedback ?: output.summary,
                                    relatedStepId = runningStep.stepId,
                                ),
                            )
                            store.upsertStep(fixStep)
                            store.upsertStep(reReviewStep)
                            store.upsertTask(
                                task.copy(
                                    totalInputTokens = task.totalInputTokens + output.inputTokens,
                                    totalOutputTokens = task.totalOutputTokens + output.outputTokens,
                                    actualCostUsd = task.actualCostUsd + output.actualCostUsd,
                                    lastHeartbeatAt = System.currentTimeMillis(),
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        continue
                    }

                    // Record structured review approval when a review step passes
                    if (runningStep.requiresReview && output.reviewApproved && reviewManager != null) {
                        val implAgentId = allSteps.lastOrNull {
                            it.stepId in runningStep.dependencies && it.assignedAgentId != agent.agentId
                        }?.assignedAgentId ?: roster.firstOrNull { "code" in it.capabilities && it.agentId != agent.agentId }?.agentId ?: "agent-developer"
                        reviewManager.submitReview(
                            taskId = taskId,
                            reviewerAgentId = agent.agentId,
                            implementingAgentId = implAgentId,
                            reviewedExecutionId = execution.executionId,
                            reviewedStepId = runningStep.stepId,
                            outcome = ReviewOutcome.APPROVED,
                            findings = listOf(output.summary.take(300)),
                            severity = ReviewSeverity.INFO,
                            evidenceReferences = detectedChanges,
                            requireIndependentReviewer = (agent.agentId != implAgentId),
                        )
                    }

                    // Step succeeded! Build checkpoint and commit atomically with step + task + idempotency
                    supervisor.transition(taskId, ExecutionLifecycleState.CHECKPOINTING, "Checkpointing step ${runningStep.sequenceNumber}")
                    val checkpoint = checkpointManager.buildCheckpoint(
                        taskId = taskId,
                        stepId = runningStep.stepId,
                        workspaceDir = workspaceDir,
                        modifiedFiles = detectedChanges,
                        contextSummary = output.summary,
                    )
                    val completedStep = runningStep.copy(
                        status = StepStatus.COMPLETED,
                        outputSummary = output.summary,
                        completedAt = System.currentTimeMillis(),
                        checkpointId = checkpoint.checkpointId,
                    )
                    val completedExec = execution.copy(
                        executionStatus = "COMPLETED",
                        completedAt = System.currentTimeMillis(),
                        inputTokens = output.inputTokens,
                        outputTokens = output.outputTokens,
                        estimatedCostUsd = output.actualCostUsd,
                    )
                    val latestTask = store.getTask(taskId) ?: task
                    val updatedTask = latestTask.copy(
                        totalInputTokens = latestTask.totalInputTokens + output.inputTokens,
                        totalOutputTokens = latestTask.totalOutputTokens + output.outputTokens,
                        actualCostUsd = latestTask.actualCostUsd + output.actualCostUsd,
                        retryCount = 0,
                        lastError = null,
                        lastHeartbeatAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                    store.completeStepTransactionally(
                        task = updatedTask,
                        step = completedStep,
                        execution = completedExec,
                        checkpoint = checkpoint,
                        event = TaskEventEntity(
                            taskId = taskId,
                            eventType = "STEP_COMPLETED",
                            eventPayload = "Step #${runningStep.sequenceNumber} '${runningStep.title}' completed (${detectedChanges.size} file(s))",
                        ),
                        idempotency = IdempotencyRecordEntity(
                            idempotencyKey = runningStep.idempotencyKey,
                            taskId = taskId,
                            stepId = runningStep.stepId,
                            operationType = "STEP_EXECUTION",
                            resultSummary = output.summary.take(500),
                        ),
                    )
                    // Persist any dynamically spawned follow-up steps
                    if (output.spawnedFollowUpSteps.isNotEmpty()) {
                        var seq = (allSteps.maxOfOrNull { it.sequenceNumber } ?: 0) + 1
                        store.runInTransaction {
                            output.spawnedFollowUpSteps.forEach { child ->
                                store.upsertStep(child.copy(taskId = taskId, sequenceNumber = seq++))
                            }
                        }
                    }
                    supervisor.transition(taskId, ExecutionLifecycleState.RUNNING, "Advancing to next step")
                } else {
                    // Failure handling: classify, check non-retryable, check stall, backoff, retry
                    val err = if (verificationFailed && output.verificationSpec != null) {
                        val vOutcome = ResultVerifier.verify(workspaceDir, output.summary, output.verificationSpec)
                        ClassifiedError(
                            category = ExecutionErrorCategory.UNKNOWN,
                            retryable = false,
                            message = vOutcome.details,
                            userExplanation = vOutcome.details,
                        )
                    } else {
                        output.error ?: StructuredErrorClassifier.classify(output.summary.ifBlank { "Step failed" })
                    }
                    val failedExec = execution.copy(
                        executionStatus = "FAILED",
                        completedAt = System.currentTimeMillis(),
                        errorType = err.category.name,
                        errorMessage = err.message,
                        inputTokens = output.inputTokens,
                        outputTokens = output.outputTokens,
                        estimatedCostUsd = output.actualCostUsd,
                    )
                    store.updateExecution(failedExec)

                    if (!err.retryable) {
                        store.upsertStep(runningStep.copy(status = StepStatus.BLOCKED, outputSummary = err.userExplanation))
                        return markBlocked(task, err.userExplanation)
                    }

                    val nextRetry = runningStep.retryCount + 1
                    val sig = "${runningStep.stepId}:${err.category}:${err.message.take(80)}"
                    recentFailureSignatures.add(sig)
                    val repeatedIdentical = recentFailureSignatures.count { it == sig }

                    if (nextRetry > policy.maxRetriesPerStep || repeatedIdentical > policy.maxRetriesPerStep) {
                        store.upsertStep(runningStep.copy(status = StepStatus.FAILED, retryCount = nextRetry, outputSummary = err.userExplanation))
                        return markBlocked(task, "Step '${runningStep.title}' exceeded max retries (${policy.maxRetriesPerStep}): ${err.userExplanation}")
                    }

                    val backoffMs = supervisor.calculateBackoffMs(nextRetry, err)
                    supervisor.transition(taskId, ExecutionLifecycleState.RETRY_WAIT, "${err.category}: waiting ${backoffMs}ms (attempt $nextRetry)")
                    store.runInTransaction {
                        store.upsertStep(runningStep.copy(status = StepStatus.READY, retryCount = nextRetry))
                        store.upsertTask(
                            task.copy(
                                retryCount = task.retryCount + 1,
                                lastError = err.userExplanation,
                                lastHeartbeatAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                        store.insertTaskEvent(
                            TaskEventEntity(
                                taskId = taskId,
                                eventType = "STEP_RETRY_SCHEDULED",
                                eventPayload = "${err.category} on '${runningStep.title}': retry $nextRetry/${policy.maxRetriesPerStep} in ${backoffMs}ms",
                            ),
                        )
                    }
                    delayFn(backoffMs)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            inFlightTaskIds.remove(taskId)
        }
        return store.getTask(taskId)
    }

    private fun markBlocked(task: TaskEntity, reason: String): TaskEntity {
        val blocked = task.copy(
            status = PersistentTaskStatus.BLOCKED,
            lastError = reason,
            executionLeaseOwner = null,
            updatedAt = System.currentTimeMillis(),
        )
        store.runInTransaction {
            store.upsertTask(blocked)
            store.insertTaskEvent(TaskEventEntity(taskId = task.taskId, eventType = "TASK_BLOCKED", eventPayload = reason))
        }
        supervisor.transition(task.taskId, ExecutionLifecycleState.BLOCKED, reason)
        return blocked
    }

    internal fun hasCircularDependencies(steps: List<TaskStepEntity>): Boolean {
        val adj = steps.associate { it.stepId to it.dependencies }
        val state = HashMap<String, Int>() // 0 = unvisited, 1 = visiting, 2 = visited
        fun dfs(node: String): Boolean {
            state[node] = 1
            for (dep in adj[node].orEmpty()) {
                if (!adj.containsKey(dep)) continue
                when (state[dep] ?: 0) {
                    1 -> return true
                    0 -> if (dfs(dep)) return true
                }
            }
            state[node] = 2
            return false
        }
        return steps.any { (state[it.stepId] ?: 0) == 0 && dfs(it.stepId) }
    }

    private fun buildCompactStepContext(
        project: ProjectEntity,
        task: TaskEntity,
        step: TaskStepEntity,
        allSteps: List<TaskStepEntity>,
        latestCheckpoint: CheckpointEntity?,
    ): String = buildString {
        if (project.projectMemory.isNotBlank()) {
            appendLine("## Project Memory")
            appendLine(project.projectMemory.take(1_200))
        }
        appendLine("## Task Objective: ${task.title}")
        appendLine(task.description.take(800))
        val doneSteps = allSteps.filter { it.status == StepStatus.COMPLETED }.takeLast(6)
        if (doneSteps.isNotEmpty()) {
            appendLine("## Recent Completed Steps (${allSteps.count { it.status == StepStatus.COMPLETED }}/${allSteps.size})")
            doneSteps.forEach { appendLine("- #${it.sequenceNumber} ${it.title}: ${it.outputSummary.take(180)}") }
        }
        if (latestCheckpoint != null && latestCheckpoint.modifiedFiles.isNotEmpty()) {
            appendLine("## Latest Checkpoint (${latestCheckpoint.checkpointId})")
            appendLine("Modified files: ${latestCheckpoint.modifiedFiles.take(15).joinToString(", ")}")
        }
        appendLine("## Current Step (#${step.sequenceNumber}): ${step.title}")
        appendLine(step.description)
    }

    companion object {
        private const val MAX_REVIEW_REJECT_CYCLES = 6
    }
}
