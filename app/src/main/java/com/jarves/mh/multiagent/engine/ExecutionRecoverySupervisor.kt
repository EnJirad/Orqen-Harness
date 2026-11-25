package com.jarves.mh.multiagent.engine

import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.persistence.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

data class RecoveryReport(
    val recoveredTaskIds: List<String>,
    val blockedTaskIds: List<String>,
    val skippedCancelledTaskIds: List<String>,
    val workspaceWarnings: Map<String, String>,
)

/**
 * Dedicated Execution Recovery Supervisor implementing the 13-state recovery state machine.
 *
 * Monitors task lifecycle states, detects stale RUNNING tasks after Android process death,
 * inspects workspace consistency against checkpoints, releases unsafe file locks,
 * calculates provider-aware backoff, and NEVER restarts owner-cancelled tasks.
 */
class ExecutionRecoverySupervisor(
    private val store: OrqenTaskStore,
    private val checkpointManager: CheckpointManager,
    private val fileLockManager: FileLockManager,
    private val policy: RequestExecutionPolicy = RequestExecutionPolicy(),
) {
    private val _taskStates = MutableStateFlow<Map<String, ExecutionLifecycleState>>(emptyMap())
    val taskStates: StateFlow<Map<String, ExecutionLifecycleState>> = _taskStates.asStateFlow()

    private val stateMap = ConcurrentHashMap<String, ExecutionLifecycleState>()

    fun stateOf(taskId: String): ExecutionLifecycleState =
        stateMap[taskId] ?: ExecutionLifecycleState.IDLE

    fun transition(taskId: String, newState: ExecutionLifecycleState, reason: String = "") {
        val prev = stateMap[taskId] ?: ExecutionLifecycleState.IDLE
        stateMap[taskId] = newState
        _taskStates.value = LinkedHashMap(stateMap)
        if (store.getTask(taskId) != null) {
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = taskId,
                    eventType = "LIFECYCLE_TRANSITION",
                    eventPayload = "$prev -> $newState${if (reason.isNotBlank()) " ($reason)" else ""}",
                ),
            )
        }
    }

    fun calculateBackoffMs(attempt: Int, error: ClassifiedError): Long {
        if (policy.respectRetryAfter && error.retryAfterMs != null) {
            return error.retryAfterMs.coerceAtMost(policy.maxBackoffMs * 2)
        }
        val exp = 2.0.pow((attempt - 1).coerceAtLeast(0).toDouble()).toLong()
        return min(policy.maxBackoffMs, policy.initialBackoffMs * exp)
    }

    /**
     * Startup / process-death reconciliation:
     * 1. Finds all tasks left in RUNNING/QUEUED state.
     * 2. Skips any task where `ownerStopRequested == true` or `pauseRequested == true`.
     * 3. Resets abandoned RUNNING steps to READY (never re-running COMPLETED steps).
     * 4. Inspects the actual workspace against the latest checkpoint; if partial-write markers
     *    or severe corruption are found, marks the task BLOCKED with a clear explanation instead
     *    of blindly overwriting user files.
     */
    fun reconcileOnStartup(
        staleThresholdMs: Long = 0L,
        workspaceResolver: (ProjectEntity) -> File = { File(it.workspacePath) },
    ): RecoveryReport {
        val recovered = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val skippedCancelled = mutableListOf<String>()
        val warnings = LinkedHashMap<String, String>()

        val allTasks = store.listAllTasks()
        for (task in allTasks) {
            if (task.ownerStopRequested || task.status == PersistentTaskStatus.CANCELLED) {
                transition(task.taskId, ExecutionLifecycleState.CANCELLED, "Owner stop preserved on startup")
                skippedCancelled.add(task.taskId)
                continue
            }
            if (task.pauseRequested || task.status == PersistentTaskStatus.PAUSED) {
                transition(task.taskId, ExecutionLifecycleState.PAUSED, "Paused state preserved on startup")
                continue
            }
            if (task.status == PersistentTaskStatus.COMPLETED || task.status == PersistentTaskStatus.FAILED || task.status == PersistentTaskStatus.BLOCKED) {
                continue
            }

            val isStale = task.status == PersistentTaskStatus.QUEUED ||
                (task.status == PersistentTaskStatus.RUNNING &&
                    (staleThresholdMs <= 0L || (System.currentTimeMillis() - task.lastHeartbeatAt) >= staleThresholdMs))
            if (!isStale) continue

            transition(task.taskId, ExecutionLifecycleState.RECOVERING, "Startup reconciliation")

            // Release any abandoned in-memory locks
            fileLockManager.cleanExpiredLocks()

            // Inspect real workspace against latest checkpoint
            val project = store.getProject(task.projectId)
            val latestCheckpoint = store.getLatestCheckpoint(task.taskId)
            val inspection = if (project != null) {
                checkpointManager.verifyWorkspaceAgainstCheckpoint(workspaceResolver(project), latestCheckpoint)
            } else null

            val hasPartialWriteMarkers = inspection != null && inspection.unexpectedModifiedFiles.any {
                it.endsWith(".tmp") || it.endsWith(".partial") || it.endsWith(".orqen_writing")
            }

            if (hasPartialWriteMarkers && inspection != null) {
                warnings[task.taskId] = inspection.summary
                store.runInTransaction {
                    store.upsertTask(
                        task.copy(
                            status = PersistentTaskStatus.BLOCKED,
                            lastError = inspection.summary,
                            executionLeaseOwner = null,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    store.insertTaskEvent(
                        TaskEventEntity(
                            taskId = task.taskId,
                            eventType = "WORKSPACE_INCONSISTENCY_DETECTED",
                            eventPayload = inspection.summary,
                        ),
                    )
                }
                transition(task.taskId, ExecutionLifecycleState.BLOCKED, inspection.summary)
                blocked.add(task.taskId)
                continue
            }

            if (inspection != null && !inspection.consistent) {
                warnings[task.taskId] = inspection.summary
            }

            // Reset any abandoned RUNNING steps back to READY so they can resume safely
            store.runInTransaction {
                val staleSteps = store.findStaleRunningSteps(task.taskId)
                staleSteps.forEach { step ->
                    val alreadyDone = store.findIdempotency(step.idempotencyKey) != null
                    store.upsertStep(
                        step.copy(
                            status = if (alreadyDone) StepStatus.COMPLETED else StepStatus.READY,
                            completedAt = if (alreadyDone) System.currentTimeMillis() else null,
                        ),
                    )
                }
                store.upsertTask(
                    task.copy(
                        status = PersistentTaskStatus.QUEUED,
                        executionLeaseOwner = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                store.insertTaskEvent(
                    TaskEventEntity(
                        taskId = task.taskId,
                        eventType = "TASK_RECOVERED_ON_STARTUP",
                        eventPayload = "Reset ${staleSteps.size} stale running step(s). ${inspection?.summary.orEmpty()}",
                    ),
                )
            }
            recovered.add(task.taskId)
        }

        return RecoveryReport(
            recoveredTaskIds = recovered,
            blockedTaskIds = blocked,
            skippedCancelledTaskIds = skippedCancelled,
            workspaceWarnings = warnings,
        )
    }
}
