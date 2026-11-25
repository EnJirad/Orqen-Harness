package com.jarves.mh.multiagent.supervisor

import android.util.Log
import com.jarves.mh.multiagent.tasks.TeamTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

data class ContinuousExecutionConfig(
    val autoResume: Boolean = true,
    val autoRetryRateLimit: Boolean = true,
    val autoCheckpoint: Boolean = true,
    val maxRetries: Int = 5,
    val initialBackoffMs: Long = 2_000L,
    val maxBackoffMs: Long = 60_000L,
    val proactiveCheckpointIntervalMs: Long = 45 * 60 * 1000L, // 45 mins to safely beat 60-min PRoot limit
)

data class StopReason(
    val title: String,
    val detail: String,
    val isRecoverable: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

data class CheckpointRecord(
    val id: String,
    val reason: String,
    val taskTitle: String?,
    val timestamp: Long = System.currentTimeMillis(),
)

class ContinuousSupervisor(
    val maxRetries: Int = 5,
    val initialBackoffMs: Long = 2_000L,
    val maxBackoffMs: Long = 60_000L,
) {
    private val tag = "ContinuousSupervisor"

    private val _config = MutableStateFlow(
        ContinuousExecutionConfig(
            maxRetries = maxRetries,
            initialBackoffMs = initialBackoffMs,
            maxBackoffMs = maxBackoffMs,
        )
    )
    val config: StateFlow<ContinuousExecutionConfig> = _config.asStateFlow()

    private val _isContinuousRunning = MutableStateFlow(false)
    val isContinuousRunning: StateFlow<Boolean> = _isContinuousRunning.asStateFlow()

    private val _lastStopReason = MutableStateFlow<StopReason?>(null)
    val lastStopReason: StateFlow<StopReason?> = _lastStopReason.asStateFlow()

    private val _checkpoints = MutableStateFlow<List<CheckpointRecord>>(emptyList())
    val checkpoints: StateFlow<List<CheckpointRecord>> = _checkpoints.asStateFlow()

    private var sessionStartedAtMs: Long = 0L
    private var retryCount = 0

    private fun logI(msg: String) {
        runCatching { Log.i(tag, msg) }
    }

    private fun logW(msg: String) {
        runCatching { Log.w(tag, msg) }
    }

    fun updateConfig(newConfig: ContinuousExecutionConfig) {
        _config.value = newConfig
    }

    fun startContinuous() {
        _isContinuousRunning.value = true
        _lastStopReason.value = null
        sessionStartedAtMs = System.currentTimeMillis()
        retryCount = 0
    }

    fun stopContinuous(reason: String, detail: String = "") {
        _isContinuousRunning.value = false
        _lastStopReason.value = StopReason(title = reason, detail = detail, isRecoverable = false)
        logI("Continuous execution stopped: $reason - $detail")
    }

    fun pauseContinuous(reason: String) {
        _isContinuousRunning.value = false
        _lastStopReason.value = StopReason(title = reason, detail = "Paused", isRecoverable = true)
    }

    fun shouldProactivelyCheckpoint(nowMs: Long = System.currentTimeMillis()): Boolean {
        val cfg = _config.value
        if (!cfg.autoCheckpoint || !_isContinuousRunning.value || sessionStartedAtMs == 0L) return false
        return (nowMs - sessionStartedAtMs) >= cfg.proactiveCheckpointIntervalMs
    }

    fun recordCheckpoint(reason: String, taskTitle: String? = null): CheckpointRecord {
        val record = CheckpointRecord(
            id = "chk-${System.currentTimeMillis()}",
            reason = reason,
            taskTitle = taskTitle,
        )
        _checkpoints.value = (listOf(record) + _checkpoints.value).take(30)
        sessionStartedAtMs = System.currentTimeMillis()
        logI("Checkpoint recorded: ${record.id} ($reason)")
        return record
    }

    fun parseRetryAfterMs(errorMessage: String?): Long? {
        if (errorMessage.isNullOrBlank()) return null
        val regex = Regex("""retry[- ]after[:\s=]+(\d+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(errorMessage) ?: return null
        val seconds = match.groupValues[1].toLongOrNull() ?: return null
        return (seconds * 1000L).coerceIn(1_000L, _config.value.maxBackoffMs * 2)
    }

    suspend fun handleSessionCompletion(
        completedTask: TeamTask?,
        hasMoreTasks: Boolean,
        exitCode: Int = 0,
        errorMessage: String? = null,
        onSaveCheckpoint: (suspend (CheckpointRecord) -> Unit)? = null,
        onContinueNext: suspend () -> Unit,
    ) {
        if (!_isContinuousRunning.value) return
        val cfg = _config.value

        if (cfg.autoCheckpoint && (completedTask != null || shouldProactivelyCheckpoint())) {
            val rec = recordCheckpoint(
                reason = if (shouldProactivelyCheckpoint()) "Proactive 45-min PRoot boundary checkpoint" else "Milestone task checkpoint",
                taskTitle = completedTask?.title,
            )
            onSaveCheckpoint?.invoke(rec)
        }

        if (errorMessage != null) {
            val isRateLimit = errorMessage.contains("rate limit", ignoreCase = true) || errorMessage.contains("429")
            val isQuotaExhausted = errorMessage.contains("quota", ignoreCase = true) || errorMessage.contains("credits", ignoreCase = true)
            val isTimeout = errorMessage.contains("timeout", ignoreCase = true) || errorMessage.contains("60m")

            if (isQuotaExhausted) {
                stopContinuous("Quota Depleted", "Provider reported credits/quota exhausted: $errorMessage")
                return
            }

            if (isRateLimit && !cfg.autoRetryRateLimit) {
                stopContinuous("Rate Limited", "Auto-retry disabled: $errorMessage")
                return
            }

            if (isRateLimit || isTimeout || exitCode != 0) {
                if (retryCount >= cfg.maxRetries) {
                    stopContinuous("Retry Limit Exceeded", "Exceeded ${cfg.maxRetries} retries. Last error: $errorMessage")
                    return
                }

                retryCount++
                val retryAfterMs = parseRetryAfterMs(errorMessage)
                val backoff = retryAfterMs ?: calculateBackoff(retryCount)
                _lastStopReason.value = StopReason(
                    title = if (isRateLimit) "Rate Limited (Retry-After)" else "Recovering Session",
                    detail = "Retrying in ${backoff / 1000}s (attempt $retryCount/${cfg.maxRetries})",
                    isRecoverable = true,
                )
                logW("Encountered recoverable condition: $errorMessage. Backoff ${backoff}ms")
                delay(backoff)
                onContinueNext()
                return
            }
        }

        // Clean completion of turn/task
        retryCount = 0
        if (hasMoreTasks && cfg.autoResume) {
            logI("Task finished cleanly. Auto-advancing to next task without manual continue.")
            delay(1000L)
            onContinueNext()
        } else if (hasMoreTasks && !cfg.autoResume) {
            pauseContinuous("Awaiting Manual Continue (Auto-Resume disabled)")
        } else {
            _isContinuousRunning.value = false
            _lastStopReason.value = StopReason(
                title = "All Tasks Completed",
                detail = "Continuous execution finished all tasks successfully.",
                isRecoverable = false,
            )
        }
    }

    internal fun calculateBackoff(attempt: Int): Long {
        val cfg = _config.value
        val exp = 2.0.pow(attempt.toDouble()).toLong()
        val raw = min(cfg.maxBackoffMs, cfg.initialBackoffMs * exp)
        val jitter = (raw * 0.2 * Random.nextDouble()).toLong()
        return raw + jitter
    }
}
