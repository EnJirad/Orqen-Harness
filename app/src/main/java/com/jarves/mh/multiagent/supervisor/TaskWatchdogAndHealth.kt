package com.jarves.mh.multiagent.supervisor

import com.jarves.mh.multiagent.engine.ExecutionErrorCategory
import com.jarves.mh.multiagent.persistence.OrqenTaskStore
import com.jarves.mh.multiagent.persistence.SecretSanitizer
import com.jarves.mh.multiagent.persistence.TaskEventEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Granular, provider-overridable timeout configuration.
 * Distinguishes first-token latency, stream inactivity, active tool runs,
 * agent execution duration, task deadline, and heartbeat liveness.
 */
data class GranularTimeoutPolicy(
    val firstResponseTimeoutMs: Long = 180_000L,
    val streamInactivityTimeoutMs: Long = 300_000L,
    val toolExecutionTimeoutMs: Long = 480_000L,
    val agentExecutionTimeoutMs: Long = 1_800_000L,
    val taskDeadlineMs: Long = 3_600_000L,
    val heartbeatTimeoutMs: Long = 120_000L,
    val providerOverrides: Map<String, GranularTimeoutPolicy> = emptyMap(),
) {
    fun forProvider(providerId: String): GranularTimeoutPolicy =
        providerOverrides[providerId] ?: this
}

enum class ModelConnectionState {
    CONNECTED,
    CONNECTING,
    DEGRADED,
    RETRYING,
    FAILOVER,
    DISCONNECTED,
}

enum class AgentHealthState {
    HEALTHY,
    BUSY,
    IDLE,
    SUSPECTED_STALL,
    TIMEOUT,
    PROVIDER_ERROR,
    RATE_LIMITED,
    UNAVAILABLE,
    RECOVERING,
    DISABLED,
}

enum class ProgressEventType {
    STARTED,
    HEARTBEAT,
    PROGRESS,
    BLOCKED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    CHECKPOINT_CREATED,
    RESULT_SUBMITTED,
    REVIEW_REQUESTED,
    FAILED,
    CANCELLED,
    COMPLETED,
}

data class AgentProgressEvent(
    val eventId: String = "pev-${UUID.randomUUID().toString().take(8)}",
    val taskId: String,
    val subtaskId: String,
    val agentId: String,
    val executionId: String,
    val eventType: ProgressEventType,
    val details: String = "",
    val toolName: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val errorCategory: ExecutionErrorCategory? = null,
    val retryAfterMs: Long? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class AgentHealthRecord(
    val agentId: String,
    val providerId: String,
    val modelId: String,
    val healthState: AgentHealthState = AgentHealthState.IDLE,
    val currentTaskId: String? = null,
    val currentSubtaskId: String? = null,
    val currentExecutionId: String? = null,
    val startedAtMs: Long = 0L,
    val firstOutputAtMs: Long? = null,
    val lastActivityAtMs: Long = 0L,
    val lastHeartbeatAtMs: Long = 0L,
    val lastMeaningfulProgressAtMs: Long = 0L,
    val lastSuccessfulExecutionAtMs: Long? = null,
    val activeToolName: String? = null,
    val toolStartedAtMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val recentProviderErrors: List<String> = emptyList(),
    val rateLimitedUntilMs: Long = 0L,
    val connectionAlive: Boolean = true,
    val connectionState: ModelConnectionState = ModelConnectionState.CONNECTED,
)

/**
 * Evidence-based Agent Health Monitor.
 * Tracks real execution evidence (progress, tool runs, heartbeats, errors, rate limits)
 * and persists every [AgentProgressEvent] into [OrqenTaskStore].
 * Explicitly distinguishes keep-alive HEARTBEAT from meaningful task PROGRESS.
 */
class AgentHealthMonitor(
    private val taskStore: OrqenTaskStore,
) {
    private val records = ConcurrentHashMap<String, AgentHealthRecord>()
    private val processedEventIds = ConcurrentHashMap.newKeySet<String>()

    fun recordEvent(event: AgentProgressEvent): AgentHealthRecord {
        if (!processedEventIds.add(event.eventId)) {
            return records[event.agentId] ?: AgentHealthRecord(
                agentId = event.agentId,
                providerId = event.providerId.orEmpty(),
                modelId = event.modelId.orEmpty(),
            )
        }

        // Persist meaningful progress event in durable task audit log
        if (taskStore.getTask(event.taskId) != null) {
            taskStore.insertTaskEvent(
                TaskEventEntity(
                    taskId = event.taskId,
                    stepId = event.subtaskId,
                    eventType = "AGENT_${event.eventType.name}",
                    eventPayload = SecretSanitizer.sanitize(
                        "[agent=${event.agentId} exec=${event.executionId}] ${event.details}",
                    ),
                    timestamp = event.timestampMs,
                ),
            )
        }

        val current = records[event.agentId] ?: AgentHealthRecord(
            agentId = event.agentId,
            providerId = event.providerId.orEmpty(),
            modelId = event.modelId.orEmpty(),
        )
        val provider = event.providerId ?: current.providerId
        val model = event.modelId ?: current.modelId
        val now = event.timestampMs

        val updated = when (event.eventType) {
            ProgressEventType.STARTED -> current.copy(
                providerId = provider,
                modelId = model,
                healthState = AgentHealthState.BUSY,
                currentTaskId = event.taskId,
                currentSubtaskId = event.subtaskId,
                currentExecutionId = event.executionId,
                startedAtMs = now,
                firstOutputAtMs = null,
                lastActivityAtMs = now,
                lastHeartbeatAtMs = now,
                lastMeaningfulProgressAtMs = now,
                activeToolName = null,
                toolStartedAtMs = null,
                connectionAlive = true,
                connectionState = ModelConnectionState.CONNECTED,
            )

            ProgressEventType.HEARTBEAT -> current.copy(
                // Heartbeat confirms liveness, NOT meaningful task progress!
                lastHeartbeatAtMs = now,
                lastActivityAtMs = now,
                connectionAlive = true,
            )

            ProgressEventType.PROGRESS,
            ProgressEventType.CHECKPOINT_CREATED,
            ProgressEventType.RESULT_SUBMITTED,
            ProgressEventType.REVIEW_REQUESTED -> current.copy(
                healthState = AgentHealthState.BUSY,
                firstOutputAtMs = current.firstOutputAtMs ?: now,
                lastActivityAtMs = now,
                lastMeaningfulProgressAtMs = now,
                connectionAlive = true,
            )

            ProgressEventType.TOOL_STARTED -> current.copy(
                healthState = AgentHealthState.BUSY,
                lastActivityAtMs = now,
                lastMeaningfulProgressAtMs = now,
                activeToolName = event.toolName ?: "tool",
                toolStartedAtMs = now,
                connectionAlive = true,
            )

            ProgressEventType.TOOL_COMPLETED -> current.copy(
                healthState = AgentHealthState.BUSY,
                lastActivityAtMs = now,
                lastMeaningfulProgressAtMs = now,
                activeToolName = null,
                toolStartedAtMs = null,
                connectionAlive = true,
                connectionState = ModelConnectionState.CONNECTED,
            )

            ProgressEventType.COMPLETED -> current.copy(
                healthState = AgentHealthState.HEALTHY,
                currentTaskId = null,
                currentSubtaskId = null,
                currentExecutionId = null,
                activeToolName = null,
                toolStartedAtMs = null,
                lastActivityAtMs = now,
                lastMeaningfulProgressAtMs = now,
                lastSuccessfulExecutionAtMs = now,
                consecutiveFailures = 0,
                connectionAlive = true,
            )

            ProgressEventType.CANCELLED -> current.copy(
                healthState = AgentHealthState.IDLE,
                currentTaskId = null,
                currentSubtaskId = null,
                currentExecutionId = null,
                activeToolName = null,
                toolStartedAtMs = null,
                lastActivityAtMs = now,
            )

            ProgressEventType.BLOCKED -> current.copy(
                healthState = AgentHealthState.UNAVAILABLE,
                lastActivityAtMs = now,
            )

            ProgressEventType.FAILED -> {
                val isActualModelOrProviderFailure = event.errorCategory in setOf(
                    ExecutionErrorCategory.NETWORK_ERROR,
                    ExecutionErrorCategory.AUTH_FAILURE,
                    ExecutionErrorCategory.INVALID_MODEL,
                    ExecutionErrorCategory.QUOTA_EXHAUSTED,
                )
                val state = when (event.errorCategory) {
                    ExecutionErrorCategory.RATE_LIMIT -> AgentHealthState.RATE_LIMITED
                    ExecutionErrorCategory.TIMEOUT, ExecutionErrorCategory.IDLE_STREAM -> AgentHealthState.TIMEOUT
                    ExecutionErrorCategory.NETWORK_ERROR,
                    ExecutionErrorCategory.AUTH_FAILURE,
                    ExecutionErrorCategory.INVALID_MODEL,
                    ExecutionErrorCategory.QUOTA_EXHAUSTED -> AgentHealthState.PROVIDER_ERROR
                    else -> AgentHealthState.RECOVERING
                }
                val rateLimitUntil = if (event.errorCategory == ExecutionErrorCategory.RATE_LIMIT) {
                    now + (event.retryAfterMs ?: 30_000L)
                } else current.rateLimitedUntilMs
                val connState = when (event.errorCategory) {
                    ExecutionErrorCategory.NETWORK_ERROR, ExecutionErrorCategory.AUTH_FAILURE -> ModelConnectionState.DISCONNECTED
                    ExecutionErrorCategory.RATE_LIMIT, ExecutionErrorCategory.TIMEOUT, ExecutionErrorCategory.IDLE_STREAM,
                    ExecutionErrorCategory.INVALID_MODEL, ExecutionErrorCategory.QUOTA_EXHAUSTED -> ModelConnectionState.DEGRADED
                    else -> ModelConnectionState.CONNECTED
                }
                current.copy(
                    healthState = state,
                    activeToolName = null,
                    toolStartedAtMs = null,
                    lastActivityAtMs = now,
                    consecutiveFailures = current.consecutiveFailures + 1,
                    recentProviderErrors = (current.recentProviderErrors + SecretSanitizer.sanitize(event.details)).takeLast(10),
                    rateLimitedUntilMs = rateLimitUntil,
                    connectionAlive = !isActualModelOrProviderFailure,
                    connectionState = connState,
                )
            }
        }
        records[event.agentId] = updated
        return updated
    }

    fun markHealthy(agentId: String, providerId: String = "", modelId: String = "", nowMs: Long = System.currentTimeMillis()): AgentHealthRecord {
        val current = records[agentId] ?: AgentHealthRecord(
            agentId = agentId,
            providerId = providerId,
            modelId = modelId,
        )
        val updated = current.copy(
            providerId = providerId.ifBlank { current.providerId },
            modelId = modelId.ifBlank { current.modelId },
            healthState = AgentHealthState.HEALTHY,
            lastActivityAtMs = nowMs,
            lastSuccessfulExecutionAtMs = nowMs,
            consecutiveFailures = 0,
            connectionAlive = true,
            connectionState = ModelConnectionState.CONNECTED,
        )
        records[agentId] = updated
        return updated
    }

    fun markState(agentId: String, state: AgentHealthState, connectionAlive: Boolean? = null): AgentHealthRecord? {
        val current = records[agentId] ?: return null
        val updated = current.copy(
            healthState = state,
            connectionAlive = connectionAlive ?: current.connectionAlive,
        )
        records[agentId] = updated
        return updated
    }

    fun getHealth(agentId: String): AgentHealthRecord? = records[agentId]

    fun snapshotAll(): Map<String, AgentHealthRecord> = LinkedHashMap(records)

    fun isProviderRateLimited(providerId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        records.values.any { it.providerId == providerId && it.rateLimitedUntilMs > nowMs }
}

enum class WatchdogDiagnosis {
    HEALTHY_ACTIVE,
    TOOL_RUNNING,
    SUSPECTED_STALL,
    CONFIRMED_IDLE_STREAM_STALL,
    FIRST_RESPONSE_TIMEOUT,
    TOOL_TIMEOUT,
    AGENT_EXECUTION_TIMEOUT,
    HEARTBEAT_TIMEOUT,
}

data class WatchdogEvaluation(
    val recoveryActionId: String = "wd-${UUID.randomUUID().toString().take(8)}",
    val taskId: String,
    val subtaskId: String,
    val agentId: String,
    val executionId: String,
    val diagnosis: WatchdogDiagnosis,
    val shouldCancelAndRecover: Boolean,
    val reason: String,
    val deduplicated: Boolean = false,
)

/**
 * Deterministic Task Watchdog operating completely independently of AI reasoning.
 *
 * Evaluates active agent executions against [GranularTimeoutPolicy]:
 * 1. Never mistakes an active legitimate tool run (within toolExecutionTimeoutMs) for a stall.
 * 2. Never mistakes a keep-alive heartbeat for meaningful task progress.
 * 3. Marks SUSPECTED_STALL before confirming genuine stall if connection/heartbeat is alive.
 * 4. Deduplicates recovery triggers per (executionId, diagnosis) so duplicate watchdog ticks
 *    never launch duplicate recovery actions.
 */
class TaskWatchdog(
    private val healthMonitor: AgentHealthMonitor,
    private val timeoutPolicy: GranularTimeoutPolicy = GranularTimeoutPolicy(),
) {
    private val triggeredRecoveryKeys = ConcurrentHashMap.newKeySet<String>()

    fun evaluateExecution(
        agentId: String,
        nowMs: Long = System.currentTimeMillis(),
        onCancelExecution: ((executionId: String) -> Unit)? = null,
    ): WatchdogEvaluation? {
        val rec = healthMonitor.getHealth(agentId) ?: return null
        val taskId = rec.currentTaskId ?: return null
        val subtaskId = rec.currentSubtaskId ?: return null
        val executionId = rec.currentExecutionId ?: return null

        val policy = timeoutPolicy.forProvider(rec.providerId)
        val totalElapsed = (nowMs - rec.startedAtMs).coerceAtLeast(0L)
        val sinceProgress = (nowMs - rec.lastMeaningfulProgressAtMs).coerceAtLeast(0L)
        val sinceHeartbeat = (nowMs - rec.lastHeartbeatAtMs).coerceAtLeast(0L)

        // 1. Total agent execution timeout (hard ceiling)
        if (totalElapsed >= policy.agentExecutionTimeoutMs) {
            return triggerOnce(
                rec = rec,
                taskId = taskId,
                subtaskId = subtaskId,
                executionId = executionId,
                diagnosis = WatchdogDiagnosis.AGENT_EXECUTION_TIMEOUT,
                reason = "Agent $agentId exceeded total execution limit (${totalElapsed}ms >= ${policy.agentExecutionTimeoutMs}ms).",
                onCancelExecution = onCancelExecution,
            )
        }

        // 2. Active tool execution check — legitimate tool runs are NOT stream stalls!
        if (rec.activeToolName != null && rec.toolStartedAtMs != null) {
            val toolElapsed = (nowMs - rec.toolStartedAtMs).coerceAtLeast(0L)
            return if (toolElapsed >= policy.toolExecutionTimeoutMs) {
                triggerOnce(
                    rec = rec,
                    taskId = taskId,
                    subtaskId = subtaskId,
                    executionId = executionId,
                    diagnosis = WatchdogDiagnosis.TOOL_TIMEOUT,
                    reason = "Tool '${rec.activeToolName}' exceeded timeout (${toolElapsed}ms >= ${policy.toolExecutionTimeoutMs}ms).",
                    onCancelExecution = onCancelExecution,
                )
            } else {
                WatchdogEvaluation(
                    taskId = taskId,
                    subtaskId = subtaskId,
                    agentId = agentId,
                    executionId = executionId,
                    diagnosis = WatchdogDiagnosis.TOOL_RUNNING,
                    shouldCancelAndRecover = false,
                    reason = "Tool '${rec.activeToolName}' legitimately running (${toolElapsed}ms).",
                )
            }
        }

        // 3. First-response timeout (no output at all since start)
        if (rec.firstOutputAtMs == null && totalElapsed >= policy.firstResponseTimeoutMs) {
            return triggerOnce(
                rec = rec,
                taskId = taskId,
                subtaskId = subtaskId,
                executionId = executionId,
                diagnosis = WatchdogDiagnosis.FIRST_RESPONSE_TIMEOUT,
                reason = "No first response from ${rec.providerId}/${rec.modelId} after ${totalElapsed}ms.",
                onCancelExecution = onCancelExecution,
            )
        }

        // 4. Stream inactivity check (progress stall):
        //    First transitions to SUSPECTED_STALL if heartbeat/connection still alive,
        //    and confirms stall once already SUSPECTED_STALL or connection/heartbeat also dead.
        if (sinceProgress >= policy.streamInactivityTimeoutMs) {
            val heartbeatAlive = rec.connectionAlive && sinceHeartbeat < policy.heartbeatTimeoutMs
            if (rec.healthState != AgentHealthState.SUSPECTED_STALL &&
                rec.healthState != AgentHealthState.TIMEOUT &&
                heartbeatAlive
            ) {
                healthMonitor.markState(agentId, AgentHealthState.SUSPECTED_STALL)
                return WatchdogEvaluation(
                    taskId = taskId,
                    subtaskId = subtaskId,
                    agentId = agentId,
                    executionId = executionId,
                    diagnosis = WatchdogDiagnosis.SUSPECTED_STALL,
                    shouldCancelAndRecover = false,
                    reason = "No meaningful progress for ${sinceProgress}ms (heartbeat still alive; marked SUSPECTED_STALL for verification).",
                )
            }
            return triggerOnce(
                rec = rec,
                taskId = taskId,
                subtaskId = subtaskId,
                executionId = executionId,
                diagnosis = WatchdogDiagnosis.CONFIRMED_IDLE_STREAM_STALL,
                reason = "Confirmed idle stream stall (${sinceProgress}ms without meaningful progress; connectionAlive=${rec.connectionAlive}).",
                onCancelExecution = onCancelExecution,
            )
        }

        // 5. Heartbeat timeout when connection dropped
        if (!rec.connectionAlive || sinceHeartbeat >= policy.heartbeatTimeoutMs) {
            return triggerOnce(
                rec = rec,
                taskId = taskId,
                subtaskId = subtaskId,
                executionId = executionId,
                diagnosis = WatchdogDiagnosis.HEARTBEAT_TIMEOUT,
                reason = "Agent heartbeat lost (${sinceHeartbeat}ms; connectionAlive=${rec.connectionAlive}).",
                onCancelExecution = onCancelExecution,
            )
        }

        return WatchdogEvaluation(
            taskId = taskId,
            subtaskId = subtaskId,
            agentId = agentId,
            executionId = executionId,
            diagnosis = WatchdogDiagnosis.HEALTHY_ACTIVE,
            shouldCancelAndRecover = false,
            reason = "Execution healthy (elapsed=${totalElapsed}ms, sinceProgress=${sinceProgress}ms).",
        )
    }

    private fun triggerOnce(
        rec: AgentHealthRecord,
        taskId: String,
        subtaskId: String,
        executionId: String,
        diagnosis: WatchdogDiagnosis,
        reason: String,
        onCancelExecution: ((executionId: String) -> Unit)?,
    ): WatchdogEvaluation {
        val dedupeKey = "$executionId:${diagnosis.name}"
        val isFirst = triggeredRecoveryKeys.add(dedupeKey)
        if (isFirst) {
            healthMonitor.markState(rec.agentId, AgentHealthState.TIMEOUT)
            onCancelExecution?.invoke(executionId)
        }
        return WatchdogEvaluation(
            taskId = taskId,
            subtaskId = subtaskId,
            agentId = rec.agentId,
            executionId = executionId,
            diagnosis = diagnosis,
            shouldCancelAndRecover = isFirst,
            reason = reason,
            deduplicated = !isFirst,
        )
    }
}
