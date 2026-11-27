package com.jarves.mh.multiagent.supervisor

import com.jarves.mh.multiagent.collab.AgentCapabilityResolver
import com.jarves.mh.multiagent.collab.AgentProfileEntity
import com.jarves.mh.multiagent.engine.ClassifiedError
import com.jarves.mh.multiagent.engine.ExecutionErrorCategory
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.recovery.ExecutionLivenessMonitor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SupervisorDecisionType {
    AGENT_SELECTED,
    TASK_DELEGATED,
    TIMEOUT_DETECTED,
    RETRY_INITIATED,
    MODEL_CHANGED,
    PROVIDER_CHANGED,
    AGENT_REPLACED,
    TASK_REPLANNED,
    WORK_REJECTED,
    REVISION_REQUESTED,
    TEST_FAILED,
    TASK_RESUMED,
    TASK_COMPLETED,
    TASK_BLOCKED,
    SUPERVISOR_FALLBACK,
}

data class SupervisorDecisionRecord(
    val decisionId: String = "sdec-${UUID.randomUUID().toString().take(8)}",
    val taskId: String,
    val stepId: String? = null,
    val agentId: String? = null,
    val executionId: String? = null,
    val decisionType: SupervisorDecisionType,
    val reason: String,
    val evidence: String,
    val actionTaken: String,
    val result: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * Capability-aware model/provider candidate in the user's configured pool.
 */
data class ModelCandidate(
    val providerId: String,
    val modelId: String,
    val capabilities: Set<String> = emptySet(),
    val supportsTools: Boolean = true,
    val supportsStreaming: Boolean = true,
    val contextWindowTokens: Int = 128_000,
    val estimatedCostPer1kTokens: Double = 0.005,
    val priority: Int = 1,
    val isEnabled: Boolean = true,
)

/**
 * Real per-task efficiency and timing telemetry.
 * Separates active execution time from model/provider waiting and recovery overhead.
 */
data class TaskEfficiencyMetrics(
    val planningDurationMs: Long = 0L,
    val modelWaitDurationMs: Long = 0L,
    val toolExecutionDurationMs: Long = 0L,
    val reviewDurationMs: Long = 0L,
    val recoveryDurationMs: Long = 0L,
    val activeExecutionDurationMs: Long = 0L,
    val wallClockDurationMs: Long = 0L,
    val totalInputTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val replanCount: Int = 0,
    val retryCount: Int = 0,
    val recoveryCount: Int = 0,
    val duplicateStepsPrevented: Int = 0,
)

/**
 * Configurable anti-infinite-loop recovery limits & execution budget.
 */
data class RecoveryLimitsConfig(
    val maxRetriesPerExecution: Int = 2,
    val maxConsecutiveProviderFailures: Int = 3,
    val maxModelSwitchesPerStep: Int = 3,
    val maxAgentReassignments: Int = 2,
    val maxReviewCycles: Int = 3,
    val maxReplanAttempts: Int = 2,
    val maxTotalTaskDurationMs: Long = 3_600_000L,
    val maxActiveExecutionBudgetMs: Long = 3_600_000L,
    val pauseOnDurationBudgetExceeded: Boolean = true,
)

enum class RecoveryStrategyKind {
    RETRY_SAME_MODEL,
    SWITCH_MODEL_SAME_PROVIDER,
    SWITCH_PROVIDER_AND_MODEL,
    REASSIGN_AGENT,
    BLOCK_NEEDS_ATTENTION,
}

data class RecoveryPlan(
    val recoveryActionId: String = "rec-${UUID.randomUUID().toString().take(8)}",
    val strategy: RecoveryStrategyKind,
    val targetAgentId: String,
    val targetProviderId: String,
    val targetModelId: String,
    val backoffMs: Long = 0L,
    val reason: String,
    val preservedModifiedFiles: List<String> = emptyList(),
)

/**
 * Capability-aware Model, Provider & Agent Failover Engine.
 *
 * Selects the least-disruptive valid recovery strategy:
 * 1. Retry current model (for transient errors under retry limit)
 * 2. Switch to another eligible model on same healthy provider
 * 3. Switch to another eligible, non-rate-limited provider/model
 * 4. Reassign unfinished work to another capable enabled agent
 * 5. Block safely with exact explanation when limits or candidates are exhausted
 *
 * Never rotates endlessly through already-failed (provider, model) pairs or exhausted providers.
 */
class AutonomousRecoveryEngine(
    private val taskStore: OrqenTaskStore,
    private val healthMonitor: AgentHealthMonitor,
    private val livenessMonitor: ExecutionLivenessMonitor,
    private val fileLockManager: FileLockManager,
    private val capabilityResolver: AgentCapabilityResolver,
    private val modelPoolProvider: () -> List<ModelCandidate>,
    private val unavailableProviders: () -> Set<String> = { emptySet() },
    private val limits: RecoveryLimitsConfig = RecoveryLimitsConfig(),
) {
    private val decisionAudit = ConcurrentHashMap<String, MutableList<SupervisorDecisionRecord>>()
    private val failedModelKeysPerStep = ConcurrentHashMap<String, MutableSet<String>>()
    private val modelSwitchCountPerStep = ConcurrentHashMap<String, Int>()
    private val reassignmentCountPerStep = ConcurrentHashMap<String, Int>()
    private val providerFailureCounts = ConcurrentHashMap<String, Int>()
    private val processedRecoveryIds = ConcurrentHashMap.newKeySet<String>()

    fun auditTrail(taskId: String): List<SupervisorDecisionRecord> =
        decisionAudit[taskId]?.toList().orEmpty()

    fun recordDecision(record: SupervisorDecisionRecord): SupervisorDecisionRecord {
        decisionAudit.computeIfAbsent(record.taskId) { mutableListOf() }.add(record)
        if (taskStore.getTask(record.taskId) != null) {
            taskStore.insertTaskEvent(
                TaskEventEntity(
                    taskId = record.taskId,
                    stepId = record.stepId,
                    eventType = "SUPERVISOR_${record.decisionType.name}",
                    eventPayload = SecretSanitizer.sanitize(
                        "${record.reason} | action=${record.actionTaken} | result=${record.result}",
                    ),
                    timestamp = record.timestampMs,
                ),
            )
        }
        return record
    }

    /**
     * Evaluates a step failure/timeout and produces a deduplicated, lease-safe [RecoveryPlan].
     */
    fun planRecovery(
        task: TaskEntity,
        step: TaskStepEntity,
        failedAgent: AgentProfileEntity,
        executionId: String,
        error: ClassifiedError,
        requiredCapabilities: Set<String>,
        teamCandidates: List<AgentProfileEntity>,
        preservedFiles: List<String> = emptyList(),
        requireTools: Boolean = false,
        minContextTokens: Int = 0,
        recoveryKey: String = "${step.stepId}:$executionId",
        nowMs: Long = System.currentTimeMillis(),
    ): RecoveryPlan {
        // Deduplicate concurrent recovery requests for the same execution
        if (!processedRecoveryIds.add(recoveryKey)) {
            return RecoveryPlan(
                strategy = RecoveryStrategyKind.BLOCK_NEEDS_ATTENTION,
                targetAgentId = failedAgent.agentId,
                targetProviderId = failedAgent.providerId,
                targetModelId = failedAgent.modelId,
                reason = "Duplicate recovery trigger suppressed for $recoveryKey.",
                preservedModifiedFiles = preservedFiles,
            )
        }

        // Safely release old execution lease & file locks before authorizing replacement
        livenessMonitor.releaseLease(task.taskId, step.stepId, failedAgent.agentId)
        AgentSlot.fromId(failedAgent.agentId)?.let { fileLockManager.releaseAllForAgent(it) }

        val providerFails = providerFailureCounts.merge(failedAgent.providerId, 1, Int::plus) ?: 1
        val failedModels = failedModelKeysPerStep.computeIfAbsent(step.stepId) { ConcurrentHashMap.newKeySet() }

        val isProviderWideFailure = error.category in setOf(
            ExecutionErrorCategory.AUTH_FAILURE,
            ExecutionErrorCategory.QUOTA_EXHAUSTED,
            ExecutionErrorCategory.RATE_LIMIT,
        ) || providerFails >= limits.maxConsecutiveProviderFailures

        // 1. Retry current model if transient, retryable, provider healthy, and under retry limit
        val canRetrySameModel = error.retryable &&
            !isProviderWideFailure &&
            step.retryCount < limits.maxRetriesPerExecution &&
            error.category != ExecutionErrorCategory.INVALID_MODEL

        if (canRetrySameModel) {
            val backoff = calculateExponentialBackoffWithJitterMs(
                attempt = step.retryCount + 1,
                retryAfterMs = error.retryAfterMs,
            )
            val plan = RecoveryPlan(
                strategy = RecoveryStrategyKind.RETRY_SAME_MODEL,
                targetAgentId = failedAgent.agentId,
                targetProviderId = failedAgent.providerId,
                targetModelId = failedAgent.modelId,
                backoffMs = backoff,
                reason = "Transient ${error.category} on ${failedAgent.providerId}/${failedAgent.modelId}; retrying (attempt ${step.retryCount + 1}/${limits.maxRetriesPerExecution}).",
                preservedModifiedFiles = preservedFiles,
            )
            recordDecision(
                SupervisorDecisionRecord(
                    taskId = task.taskId,
                    stepId = step.stepId,
                    agentId = failedAgent.agentId,
                    executionId = executionId,
                    decisionType = SupervisorDecisionType.RETRY_INITIATED,
                    reason = plan.reason,
                    evidence = error.message.take(200),
                    actionTaken = "Retry same model after ${backoff}ms preserving ${preservedFiles.size} modified file(s)",
                    result = "SCHEDULED",
                ),
            )
            return plan
        }

        // Mark this (provider, model) pair as failed for this step so we never rotate back to it
        failedModels.add("${failedAgent.providerId}:${failedAgent.modelId}")

        val currentSwitches = modelSwitchCountPerStep[step.stepId] ?: 0
        val downProviders = unavailableProviders()
        val eligibleModels = modelPoolProvider().filter { cand ->
            cand.isEnabled &&
                "${cand.providerId}:${cand.modelId}" !in failedModels &&
                cand.providerId !in downProviders &&
                !healthMonitor.isProviderRateLimited(cand.providerId, nowMs) &&
                (!requireTools || cand.supportsTools) &&
                cand.contextWindowTokens >= minContextTokens &&
                (requiredCapabilities.isEmpty() || requiredCapabilities.all { it in cand.capabilities })
        }.sortedWith(
            compareByDescending<ModelCandidate> { it.priority }
                .thenBy { it.estimatedCostPer1kTokens },
        )

        if (currentSwitches < limits.maxModelSwitchesPerStep) {
            // 2. Try another model on the SAME provider if provider itself isn't down/rate-limited
            val sameProviderAlt = if (!isProviderWideFailure) {
                eligibleModels.firstOrNull { it.providerId == failedAgent.providerId }
            } else null

            if (sameProviderAlt != null) {
                modelSwitchCountPerStep[step.stepId] = currentSwitches + 1
                val plan = RecoveryPlan(
                    strategy = RecoveryStrategyKind.SWITCH_MODEL_SAME_PROVIDER,
                    targetAgentId = failedAgent.agentId,
                    targetProviderId = sameProviderAlt.providerId,
                    targetModelId = sameProviderAlt.modelId,
                    reason = "Model '${failedAgent.modelId}' failed (${error.category}); switching to '${sameProviderAlt.modelId}' on provider '${sameProviderAlt.providerId}'.",
                    preservedModifiedFiles = preservedFiles,
                )
                recordDecision(
                    SupervisorDecisionRecord(
                        taskId = task.taskId,
                        stepId = step.stepId,
                        agentId = failedAgent.agentId,
                        executionId = executionId,
                        decisionType = SupervisorDecisionType.MODEL_CHANGED,
                        reason = plan.reason,
                        evidence = error.message.take(200),
                        actionTaken = "Switch model ${failedAgent.modelId} -> ${sameProviderAlt.modelId}",
                        result = "SWITCHED",
                    ),
                )
                return plan
            }

            // 3. Try an alternative healthy, non-rate-limited PROVIDER
            val otherProviderAlt = eligibleModels.firstOrNull { it.providerId != failedAgent.providerId }
            if (otherProviderAlt != null) {
                modelSwitchCountPerStep[step.stepId] = currentSwitches + 1
                val plan = RecoveryPlan(
                    strategy = RecoveryStrategyKind.SWITCH_PROVIDER_AND_MODEL,
                    targetAgentId = failedAgent.agentId,
                    targetProviderId = otherProviderAlt.providerId,
                    targetModelId = otherProviderAlt.modelId,
                    reason = "Provider '${failedAgent.providerId}' unavailable/failed (${error.category}); failing over to '${otherProviderAlt.providerId}/${otherProviderAlt.modelId}'.",
                    preservedModifiedFiles = preservedFiles,
                )
                recordDecision(
                    SupervisorDecisionRecord(
                        taskId = task.taskId,
                        stepId = step.stepId,
                        agentId = failedAgent.agentId,
                        executionId = executionId,
                        decisionType = SupervisorDecisionType.PROVIDER_CHANGED,
                        reason = plan.reason,
                        evidence = error.message.take(200),
                        actionTaken = "Failover ${failedAgent.providerId}/${failedAgent.modelId} -> ${otherProviderAlt.providerId}/${otherProviderAlt.modelId}",
                        result = "SWITCHED",
                    ),
                )
                return plan
            }
        }

        // 4. Reassign unfinished work to another capable, enabled agent
        val currentReassigns = reassignmentCountPerStep[step.stepId] ?: 0
        if (currentReassigns < limits.maxAgentReassignments) {
            val replacement = capabilityResolver.selectBestAgent(
                candidates = teamCandidates.filter {
                    "${it.providerId}:${it.modelId}" !in failedModels &&
                        !healthMonitor.isProviderRateLimited(it.providerId, nowMs)
                },
                requiredCapabilities = requiredCapabilities,
                excludeAgentIds = setOf(failedAgent.agentId),
            )
            if (replacement != null) {
                reassignmentCountPerStep[step.stepId] = currentReassigns + 1
                val plan = RecoveryPlan(
                    strategy = RecoveryStrategyKind.REASSIGN_AGENT,
                    targetAgentId = replacement.agentId,
                    targetProviderId = replacement.providerId,
                    targetModelId = replacement.modelId,
                    reason = "Reassigning unfinished step '${step.title}' from ${failedAgent.agentId} to ${replacement.agentId} after ${error.category}.",
                    preservedModifiedFiles = preservedFiles,
                )
                recordDecision(
                    SupervisorDecisionRecord(
                        taskId = task.taskId,
                        stepId = step.stepId,
                        agentId = replacement.agentId,
                        executionId = executionId,
                        decisionType = SupervisorDecisionType.AGENT_REPLACED,
                        reason = plan.reason,
                        evidence = "Preserved ${preservedFiles.size} file(s); previous error=${error.category}",
                        actionTaken = "Reassign ${failedAgent.agentId} -> ${replacement.agentId}",
                        result = "REASSIGNED",
                    ),
                )
                return plan
            }
        }

        // 5. All recovery options / limits exhausted — block safely without infinite loop
        val reason = "Recovery limits or eligible fallback candidates exhausted for step '${step.title}' (last error: ${error.category} - ${error.userExplanation})."
        recordDecision(
            SupervisorDecisionRecord(
                taskId = task.taskId,
                stepId = step.stepId,
                agentId = failedAgent.agentId,
                executionId = executionId,
                decisionType = SupervisorDecisionType.TASK_BLOCKED,
                reason = reason,
                evidence = "switches=$currentSwitches/${limits.maxModelSwitchesPerStep}, reassignments=$currentReassigns/${limits.maxAgentReassignments}",
                actionTaken = "Mark task BLOCKED preserving completed checkpoints",
                result = "BLOCKED",
            ),
        )
        return RecoveryPlan(
            strategy = RecoveryStrategyKind.BLOCK_NEEDS_ATTENTION,
            targetAgentId = failedAgent.agentId,
            targetProviderId = failedAgent.providerId,
            targetModelId = failedAgent.modelId,
            reason = reason,
            preservedModifiedFiles = preservedFiles,
        )
    }

    fun calculateExponentialBackoffWithJitterMs(
        attempt: Int,
        retryAfterMs: Long? = null,
        baseDelayMs: Long = 1_500L,
        maxDelayMs: Long = 60_000L,
        jitterRatio: Double = 0.2,
    ): Long {
        if (retryAfterMs != null && retryAfterMs > 0L) {
            return retryAfterMs.coerceAtMost(maxDelayMs * 2L)
        }
        val safeAttempt = (attempt - 1).coerceIn(0, 10)
        val exp = (baseDelayMs shl safeAttempt).coerceAtMost(maxDelayMs)
        val maxJitter = (exp * jitterRatio).toLong().coerceAtLeast(1L)
        val jitter = kotlin.random.Random.nextLong(0L, maxJitter + 1L)
        return (exp + jitter).coerceAtMost(maxDelayMs)
    }

    fun resetProviderFailures(providerId: String) {
        providerFailureCounts.remove(providerId)
    }
}
