package com.jarves.mh.multiagent.recovery

import android.content.Context
import com.jarves.mh.multiagent.engine.CheckpointManager
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.runtime.TaskRecoveryJobService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

data class ScheduledRecoveryWork(
    val uniqueWorkName: String,
    val taskId: String,
    val requireNetwork: Boolean,
    val delayMs: Long,
    val scheduledAtMs: Long = System.currentTimeMillis(),
)

/**
 * Abstraction over Android durable background scheduling (`JobScheduler` / `WorkManager`).
 * Deduplicates by stable unique work name derived from `taskId`.
 */
interface RecoverySchedulerBackend {
    fun scheduleUnique(
        taskId: String,
        requireNetwork: Boolean,
        delayMs: Long,
    ): Boolean

    fun cancel(taskId: String)
    fun scheduledWork(): List<ScheduledRecoveryWork>
}

class JobSchedulerRecoveryBackend(
    private val context: Context,
) : RecoverySchedulerBackend {
    private val tracked = ConcurrentHashMap<String, ScheduledRecoveryWork>()

    override fun scheduleUnique(taskId: String, requireNetwork: Boolean, delayMs: Long): Boolean {
        val uniqueName = "orqen_recovery_$taskId"
        val ok = TaskRecoveryJobService.scheduleReconciliation(
            context = context,
            taskId = taskId,
            requireNetwork = requireNetwork,
            minLatencyMs = delayMs,
        )
        if (ok) {
            tracked[uniqueName] = ScheduledRecoveryWork(uniqueName, taskId, requireNetwork, delayMs)
        }
        return ok
    }

    override fun cancel(taskId: String) {
        tracked.remove("orqen_recovery_$taskId")
        TaskRecoveryJobService.cancelTaskRecovery(context, taskId)
    }

    override fun scheduledWork(): List<ScheduledRecoveryWork> = tracked.values.toList()
}

/**
 * Exclusive execution lease & liveness monitor preventing duplicate recovery workers
 * from executing the same task/step concurrently.
 */
class ExecutionLivenessMonitor {
    private val leases = ConcurrentHashMap<String, ExecutionLeaseRecord>()

    @Synchronized
    fun tryAcquireLease(
        taskId: String,
        stepId: String?,
        ownerId: String,
        ttlMs: Long = 60_000L,
        nowMs: Long = System.currentTimeMillis(),
    ): ExecutionLeaseRecord? {
        val key = leaseKey(taskId, stepId)
        val existing = leases[key]
        if (existing != null && !existing.isExpired(nowMs) && existing.ownerId != ownerId) {
            return null
        }
        val lease = ExecutionLeaseRecord(
            taskId = taskId,
            stepId = stepId,
            ownerId = ownerId,
            acquiredAtMs = nowMs,
            expiresAtMs = nowMs + ttlMs,
            lastHeartbeatMs = nowMs,
        )
        leases[key] = lease
        return lease
    }

    @Synchronized
    fun renewHeartbeat(taskId: String, stepId: String?, ownerId: String, ttlMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = leaseKey(taskId, stepId)
        val existing = leases[key] ?: return false
        if (existing.ownerId != ownerId || existing.isExpired(nowMs)) return false
        leases[key] = existing.copy(lastHeartbeatMs = nowMs, expiresAtMs = nowMs + ttlMs)
        return true
    }

    @Synchronized
    fun releaseLease(taskId: String, stepId: String?, ownerId: String) {
        val key = leaseKey(taskId, stepId)
        val existing = leases[key]
        if (existing != null && existing.ownerId == ownerId) {
            leases.remove(key)
        }
    }

    @Synchronized
    fun activeLease(taskId: String, stepId: String?, nowMs: Long = System.currentTimeMillis()): ExecutionLeaseRecord? {
        val existing = leases[leaseKey(taskId, stepId)] ?: return null
        return if (existing.isExpired(nowMs)) null else existing
    }

    private fun leaseKey(taskId: String, stepId: String?): String =
        if (stepId.isNullOrBlank()) "task:$taskId" else "task:$taskId:step:$stepId"
}

data class ProcessRecoverySummary(
    val cause: InterruptionCause,
    val scheduledTaskIds: List<String>,
    val resumableAwaitingUserTaskIds: List<String>,
    val blockedTaskIds: List<String>,
    val skippedTaskIds: List<String>,
    val recoveryStates: Map<String, RecoveryState>,
)

/**
 * Unified Phase 5 Android Process Recovery Coordinator.
 *
 * Responsibilities:
 * - Startup & post-boot reconciliation from authoritative [OrqenTaskStore]
 * - Checkpoint validation & partial-write blocking
 * - Exclusive lease reclamation via [ExecutionLivenessMonitor]
 * - Policy-driven deduplicated scheduling via [RecoverySchedulerBackend]
 * - Respects COMPLETED, CANCELLED, PAUSED, and pending-approval tasks
 * - Records structured [RecoveryEventType] history without polluting conversation messages
 */
class ProcessRecoveryCoordinator(
    private val store: OrqenTaskStore,
    private val checkpointManager: CheckpointManager,
    private val livenessMonitor: ExecutionLivenessMonitor,
    private val scheduler: RecoverySchedulerBackend,
    private var policy: RecoveryPolicy = RecoveryPolicy(),
    private val hasPendingApproval: (taskId: String) -> Boolean = { false },
    private val isUncertainExternalOp: (step: TaskStepEntity) -> Boolean = { false },
) {
    private val taskRecoveryStates = ConcurrentHashMap<String, RecoveryState>()

    fun updatePolicy(newPolicy: RecoveryPolicy) {
        policy = newPolicy
    }

    fun currentPolicy(): RecoveryPolicy = policy

    fun stateOf(taskId: String): RecoveryState = taskRecoveryStates[taskId] ?: RecoveryState.ACTIVE

    fun calculateRetryDelayMs(attempt: Int): Long {
        val exp = 2.0.pow((attempt - 1).coerceAtLeast(0).toDouble()).toLong()
        return min(policy.maxRetryDelayMs, policy.baseRetryDelayMs * exp)
    }

    fun reconcileOnStartup(
        cause: InterruptionCause = InterruptionCause.UNEXPECTED_PROCESS_DEATH,
        projectScopeId: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        workspaceResolver: (ProjectEntity) -> File = { File(it.workspacePath) },
    ): ProcessRecoverySummary {
        val scheduled = mutableListOf<String>()
        val resumable = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        val candidateTasks = if (projectScopeId != null) {
            store.listTasksForProject(projectScopeId)
        } else {
            store.listAllTasks()
        }

        for (task in candidateTasks) {
            // 1. Terminal & paused tasks are NEVER resumed
            when {
                task.status == PersistentTaskStatus.COMPLETED -> {
                    taskRecoveryStates[task.taskId] = RecoveryState.COMPLETED
                    skipped.add(task.taskId)
                    continue
                }
                task.ownerStopRequested || task.status == PersistentTaskStatus.CANCELLED -> {
                    taskRecoveryStates[task.taskId] = RecoveryState.CANCELLED
                    scheduler.cancel(task.taskId)
                    skipped.add(task.taskId)
                    continue
                }
                task.pauseRequested || task.status == PersistentTaskStatus.PAUSED || task.status == PersistentTaskStatus.PAUSE_REQUESTED -> {
                    taskRecoveryStates[task.taskId] = RecoveryState.RESUMABLE
                    skipped.add(task.taskId)
                    continue
                }
                task.status == PersistentTaskStatus.FAILED -> {
                    taskRecoveryStates[task.taskId] = RecoveryState.FAILED
                    skipped.add(task.taskId)
                    continue
                }
            }

            // 2. Tasks awaiting owner approval remain BLOCKED
            if (hasPendingApproval(task.taskId)) {
                markTaskBlocked(task, "Awaiting required owner approval before recovery.", cause)
                blocked.add(task.taskId)
                continue
            }

            // 3. Respect maxRecoveryAttempts
            if (task.retryCount >= policy.maxRecoveryAttempts) {
                markTaskBlocked(task, "Exceeded maximum recovery attempts (${policy.maxRecoveryAttempts}).", cause)
                blocked.add(task.taskId)
                continue
            }

            // 4. Validate latest checkpoint & workspace integrity
            val project = store.getProject(task.projectId)
            val allCheckpoints = store.listCheckpointsForTask(task.taskId)
            val latestAnyCheckpoint = allCheckpoints.firstOrNull()
            if (latestAnyCheckpoint != null && !latestAnyCheckpoint.isValid) {
                markTaskBlocked(task, "Latest checkpoint ${latestAnyCheckpoint.checkpointId} is marked invalid.", cause)
                blocked.add(task.taskId)
                continue
            }
            if (project != null && latestAnyCheckpoint != null) {
                val ws = workspaceResolver(project)
                val inspection = checkpointManager.verifyWorkspaceAgainstCheckpoint(ws, latestAnyCheckpoint)
                val hasPartialWrite = inspection.unexpectedModifiedFiles.any {
                    it.endsWith(".partial") || it.endsWith(".tmp") || it.endsWith(".orqen_writing")
                }
                if (hasPartialWrite) {
                    markTaskBlocked(task, inspection.summary, cause)
                    blocked.add(task.taskId)
                    continue
                }
            }

            // 5. Inspect stale RUNNING steps for uncertain non-idempotent side effects
            val staleSteps = store.findStaleRunningSteps(task.taskId)
            val uncertainStep = staleSteps.firstOrNull { step ->
                store.findIdempotency(step.idempotencyKey) == null && isUncertainExternalOp(step)
            }
            if (uncertainStep != null) {
                markTaskBlocked(
                    task,
                    "Step '${uncertainStep.title}' was interrupted during an uncertain external side-effecting operation; manual reconciliation required.",
                    cause,
                )
                blocked.add(task.taskId)
                continue
            }

            // 6. Reconcile stale steps transactionally (completed steps are NEVER reset!)
            logRecoveryEvent(task.taskId, RecoveryEventType.PROCESS_INTERRUPTED, "Detected interruption: ${cause.name}")
            store.runInTransaction {
                staleSteps.forEach { step ->
                    val alreadyCompleted = store.findIdempotency(step.idempotencyKey) != null
                    store.upsertStep(
                        step.copy(
                            status = if (alreadyCompleted) StepStatus.COMPLETED else StepStatus.READY,
                            completedAt = if (alreadyCompleted) nowMs else null,
                        ),
                    )
                }
                store.upsertTask(
                    task.copy(
                        status = PersistentTaskStatus.RECOVERING,
                        executionLeaseOwner = null,
                        updatedAt = nowMs,
                    ),
                )
            }
            if (latestAnyCheckpoint != null) {
                logRecoveryEvent(task.taskId, RecoveryEventType.CHECKPOINT_RESTORED, "Validated checkpoint ${latestAnyCheckpoint.checkpointId}")
            }

            // 7. Apply RecoveryPolicy (AUTOMATIC vs ASK_BEFORE_RESUMING / MANUAL_ONLY)
            if (policy.mode == RecoveryMode.AUTOMATIC && scheduled.size < policy.maxConcurrentRecoveries) {
                val delayMs = calculateRetryDelayMs(task.retryCount + 1)
                val ok = scheduler.scheduleUnique(
                    taskId = task.taskId,
                    requireNetwork = policy.requireNetwork,
                    delayMs = delayMs,
                )
                if (ok) {
                    taskRecoveryStates[task.taskId] = RecoveryState.RECOVERY_PENDING
                    scheduled.add(task.taskId)
                    logRecoveryEvent(task.taskId, RecoveryEventType.RECOVERY_SCHEDULED, "Scheduled recovery in ${delayMs}ms (requireNetwork=${policy.requireNetwork})")
                }
            } else {
                taskRecoveryStates[task.taskId] = RecoveryState.RESUMABLE
                resumable.add(task.taskId)
            }
        }

        return ProcessRecoverySummary(
            cause = cause,
            scheduledTaskIds = scheduled,
            resumableAwaitingUserTaskIds = resumable,
            blockedTaskIds = blocked,
            skippedTaskIds = skipped,
            recoveryStates = LinkedHashMap(taskRecoveryStates),
        )
    }

    fun reconcileAfterBoot(): ProcessRecoverySummary =
        reconcileOnStartup(cause = InterruptionCause.DEVICE_REBOOT)

    fun reconcileOnServiceRestart(
        taskId: String,
        newWorkerId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val task = store.getTask(taskId) ?: return false
        if (task.ownerStopRequested || task.pauseRequested ||
            task.status == PersistentTaskStatus.COMPLETED ||
            task.status == PersistentTaskStatus.CANCELLED ||
            task.status == PersistentTaskStatus.PAUSED
        ) {
            return false
        }
        val lease = livenessMonitor.tryAcquireLease(taskId, task.currentStepId, newWorkerId, policy.leaseTtlMs, nowMs)
            ?: return false
        logRecoveryEvent(taskId, RecoveryEventType.EXECUTION_RECLAIMED, "Reclaimed lease by $newWorkerId (${lease.ownerId})")
        taskRecoveryStates[taskId] = RecoveryState.RECOVERING
        return true
    }

    private fun markTaskBlocked(task: TaskEntity, reason: String, cause: InterruptionCause) {
        store.runInTransaction {
            store.upsertTask(
                task.copy(
                    status = PersistentTaskStatus.BLOCKED,
                    lastError = SecretSanitizer.sanitize(reason),
                    executionLeaseOwner = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = task.taskId,
                    eventType = RecoveryEventType.RECOVERY_BLOCKED.name,
                    eventPayload = SecretSanitizer.sanitize("[${cause.name}] $reason"),
                ),
            )
        }
        taskRecoveryStates[task.taskId] = RecoveryState.BLOCKED
    }

    private fun logRecoveryEvent(taskId: String, type: RecoveryEventType, reason: String) {
        store.insertTaskEvent(
            TaskEventEntity(
                taskId = taskId,
                eventType = type.name,
                eventPayload = SecretSanitizer.sanitize(reason),
            ),
        )
    }
}
