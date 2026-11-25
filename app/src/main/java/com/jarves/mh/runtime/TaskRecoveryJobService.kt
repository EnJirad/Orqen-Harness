package com.jarves.mh.runtime

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import com.jarves.mh.multiagent.engine.CheckpointManager
import com.jarves.mh.multiagent.persistence.SqliteOrqenTaskStore
import com.jarves.mh.multiagent.recovery.ExecutionLivenessMonitor
import com.jarves.mh.multiagent.recovery.InterruptionCause
import com.jarves.mh.multiagent.recovery.JobSchedulerRecoveryBackend
import com.jarves.mh.multiagent.recovery.ProcessRecoveryCoordinator
import com.jarves.mh.multiagent.recovery.RecoveryMode
import com.jarves.mh.multiagent.recovery.RecoveryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Durable Android JobScheduler service for startup/process-death/reboot task reconciliation.
 *
 * Uses native Android [JobScheduler] (API 28+, zero external dependencies, Play Store compliant):
 * - Unique job ID per task or global reconciliation pass
 * - Network constraint (`NETWORK_TYPE_ANY`)
 * - Exponential backoff retry scheduling
 * - Persisted across device reboot when permitted by Android
 * - Idempotent reconciliation (never restarts owner-cancelled tasks)
 */
class TaskRecoveryJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val taskId = params.extras.getString(EXTRA_TASK_ID)
        activeJob = serviceScope.launch {
            var needsReschedule = false
            try {
                val store = SqliteOrqenTaskStore(applicationContext)
                if (!store.validateIntegrity()) {
                    Log.w(TAG, "SQLite integrity check failed during recovery job")
                    jobFinished(params, false)
                    return@launch
                }
                val coordinator = ProcessRecoveryCoordinator(
                    store = store,
                    checkpointManager = CheckpointManager(store),
                    livenessMonitor = ExecutionLivenessMonitor(),
                    scheduler = JobSchedulerRecoveryBackend(applicationContext),
                    policy = RecoveryPolicy(mode = RecoveryMode.ASK_BEFORE_RESUMING),
                )
                val summary = coordinator.reconcileOnStartup(
                    cause = InterruptionCause.UNEXPECTED_PROCESS_DEATH,
                )
                Log.i(
                    TAG,
                    "Reconciliation finished (target=$taskId): resumable=${summary.resumableAwaitingUserTaskIds.size}, " +
                        "scheduled=${summary.scheduledTaskIds.size}, blocked=${summary.blockedTaskIds.size}, skipped=${summary.skippedTaskIds.size}",
                )
                needsReschedule = false
            } catch (err: Throwable) {
                Log.w(TAG, "Recovery job encountered transient error: ${err.message}")
                needsReschedule = true
            } finally {
                jobFinished(params, needsReschedule)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeJob?.cancel()
        return true // Safely reschedule if Android stopped the worker mid-reconciliation
    }

    companion object {
        private const val TAG = "TaskRecoveryJobService"
        const val EXTRA_TASK_ID = "task_id"
        private const val BASE_RECONCILE_JOB_ID = 42_000

        fun scheduleReconciliation(
            context: Context,
            taskId: String? = null,
            requireNetwork: Boolean = true,
            minLatencyMs: Long = 2_000L,
        ): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            val jobId = if (taskId.isNullOrBlank()) {
                BASE_RECONCILE_JOB_ID
            } else {
                BASE_RECONCILE_JOB_ID + (taskId.hashCode() and 0x0FFF)
            }
            val extras = PersistableBundle().apply {
                if (!taskId.isNullOrBlank()) putString(EXTRA_TASK_ID, taskId)
            }
            val builder = JobInfo.Builder(
                jobId,
                ComponentName(context, TaskRecoveryJobService::class.java),
            )
                .setExtras(extras)
                .setMinimumLatency(minLatencyMs.coerceAtLeast(0L))
                .setBackoffCriteria(15_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setRequiredNetworkType(
                    if (requireNetwork) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_NONE,
                )

            // Best-effort reboot persistence (gracefully ignored if RECEIVE_BOOT_COMPLETED isn't granted)
            runCatching { builder.setPersisted(true) }

            return runCatching {
                scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
            }.getOrDefault(false)
        }

        fun cancelTaskRecovery(context: Context, taskId: String) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val jobId = BASE_RECONCILE_JOB_ID + (taskId.hashCode() and 0x0FFF)
            runCatching { scheduler.cancel(jobId) }
        }

        fun cancelAll(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            runCatching { scheduler.cancel(BASE_RECONCILE_JOB_ID) }
        }
    }
}
