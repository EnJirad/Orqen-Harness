package com.jarves.mh.multiagent.supervisor

import android.util.Log
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.tasks.TaskStatus
import com.jarves.mh.multiagent.tasks.TeamTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

data class StopReason(
    val title: String,
    val detail: String,
    val isRecoverable: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

class ContinuousSupervisor(
    val maxRetries: Int = 5,
    val initialBackoffMs: Long = 2_000L,
    val maxBackoffMs: Long = 60_000L,
) {
    private val tag = "ContinuousSupervisor"

    private val _isContinuousRunning = MutableStateFlow(false)
    val isContinuousRunning: StateFlow<Boolean> = _isContinuousRunning.asStateFlow()

    private val _lastStopReason = MutableStateFlow<StopReason?>(null)
    val lastStopReason: StateFlow<StopReason?> = _lastStopReason.asStateFlow()

    private var retryCount = 0

    private fun logI(msg: String) {
        runCatching { Log.i(tag, msg) }
    }

    private fun logW(msg: String) {
        runCatching { Log.w(tag, msg) }
    }

    fun startContinuous() {
        _isContinuousRunning.value = true
        _lastStopReason.value = null
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

    suspend fun handleSessionCompletion(
        completedTask: TeamTask?,
        hasMoreTasks: Boolean,
        exitCode: Int = 0,
        errorMessage: String? = null,
        onContinueNext: suspend () -> Unit,
    ) {
        if (!_isContinuousRunning.value) return

        if (errorMessage != null) {
            val isRateLimit = errorMessage.contains("rate limit", ignoreCase = true) || errorMessage.contains("429")
            val isQuotaExhausted = errorMessage.contains("quota", ignoreCase = true) || errorMessage.contains("credits", ignoreCase = true)
            val isTimeout = errorMessage.contains("timeout", ignoreCase = true) || errorMessage.contains("60m")

            if (isQuotaExhausted) {
                stopContinuous("Quota Depleted", "Provider reported credits/quota exhausted: $errorMessage")
                return
            }

            if (isRateLimit || isTimeout || exitCode != 0) {
                if (retryCount >= maxRetries) {
                    stopContinuous("Retry Limit Exceeded", "Exceeded $maxRetries retries. Last error: $errorMessage")
                    return
                }

                retryCount++
                val backoff = calculateBackoff(retryCount)
                _lastStopReason.value = StopReason(
                    title = if (isRateLimit) "Rate Limited" else "Recovering Session",
                    detail = "Retrying in ${backoff / 1000}s (attempt $retryCount/$maxRetries)",
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
        if (hasMoreTasks) {
            logI("Task finished cleanly. Auto-advancing to next task without manual continue.")
            delay(1000L) // Brief breather
            onContinueNext()
        } else {
            _isContinuousRunning.value = false
            _lastStopReason.value = StopReason(
                title = "All Tasks Completed",
                detail = "The AI Team finished all tasks successfully.",
                isRecoverable = false,
            )
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        val exp = 2.0.pow(attempt.toDouble()).toLong()
        val raw = min(maxBackoffMs, initialBackoffMs * exp)
        // Add jitter +/- 20%
        val jitter = (raw * 0.2 * Random.nextDouble()).toLong()
        return raw + jitter
    }
}
