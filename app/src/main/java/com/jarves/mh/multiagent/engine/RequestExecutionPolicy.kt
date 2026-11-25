package com.jarves.mh.multiagent.engine

/**
 * Configurable per-request execution policy.
 * Each model request is an individual step cycle, NOT the lifetime of the entire task.
 */
data class RequestExecutionPolicy(
    val requestTimeoutMs: Long = 5 * 60_000L,      // 5 minutes per individual model request
    val idleStreamTimeoutMs: Long = 90_000L,       // 90s without any streaming bytes/progress -> idle stream recovery
    val cliPrintTimeoutMinutes: Int = 5,           // --print-timeout per step
    val maxResponseBytes: Int = 2 * 1024 * 1024,   // 2 MB safety cap per response
    val maxRetriesPerStep: Int = 4,
    val initialBackoffMs: Long = 1_500L,
    val maxBackoffMs: Long = 60_000L,
    val respectRetryAfter: Boolean = true,
)

enum class ExecutionErrorCategory {
    TIMEOUT,
    IDLE_STREAM,
    RATE_LIMIT,
    AUTH_FAILURE,
    INVALID_MODEL,
    QUOTA_EXHAUSTED,
    CLI_CRASH,
    NETWORK_ERROR,
    PARTIAL_WRITE_CONFLICT,
    UNKNOWN,
}

data class ClassifiedError(
    val category: ExecutionErrorCategory,
    val retryable: Boolean,
    val retryAfterMs: Long? = null,
    val httpStatusCode: Int? = null,
    val message: String,
    val userExplanation: String,
)

object StructuredErrorClassifier {

    private val retryAfterRegex = Regex("""retry[- ]after[:\s=]+(\d+)""", RegexOption.IGNORE_CASE)

    fun parseRetryAfterMs(text: String?, maxBackoffMs: Long = 120_000L): Long? {
        if (text.isNullOrBlank()) return null
        val match = retryAfterRegex.find(text) ?: return null
        val seconds = match.groupValues[1].toLongOrNull() ?: return null
        return (seconds * 1_000L).coerceIn(500L, maxBackoffMs)
    }

    fun classify(
        message: String,
        httpStatusCode: Int? = null,
        exitCode: Int? = null,
        isTimeout: Boolean = false,
        isIdleStream: Boolean = false,
        maxBackoffMs: Long = 120_000L,
    ): ClassifiedError {
        val lower = message.lowercase()
        val detectedCode = httpStatusCode ?: extractHttpStatusCode(lower)

        // 1. Auth failures (401 / 403 / invalid key) — NEVER retry blindly
        if (detectedCode == 401 || detectedCode == 403 ||
            lower.contains("unauthorized") ||
            lower.contains("invalid_api_key") ||
            lower.contains("invalid api key") ||
            lower.contains("authentication failed") ||
            lower.contains("api key not set") ||
            lower.contains("sign in")
        ) {
            return ClassifiedError(
                category = ExecutionErrorCategory.AUTH_FAILURE,
                retryable = false,
                httpStatusCode = detectedCode ?: 401,
                message = message,
                userExplanation = "Provider authentication failed (HTTP ${detectedCode ?: 401}). Verify your API key or account sign-in before resuming.",
            )
        }

        // 2. Invalid model selection — non-retryable without model change
        if (lower.contains("invalid model") ||
            lower.contains("model not found") ||
            lower.contains("not recognized as a known model")
        ) {
            return ClassifiedError(
                category = ExecutionErrorCategory.INVALID_MODEL,
                retryable = false,
                httpStatusCode = detectedCode,
                message = message,
                userExplanation = "Selected model is unavailable or invalid for this provider.",
            )
        }

        // 3. Quota / billing exhaustion — non-retryable on same provider without fallback
        if (lower.contains("quota") && (lower.contains("exhaust") || lower.contains("exceed") || lower.contains("depleted")) ||
            lower.contains("insufficient_quota") ||
            lower.contains("billing") ||
            lower.contains("credits exhausted")
        ) {
            return ClassifiedError(
                category = ExecutionErrorCategory.QUOTA_EXHAUSTED,
                retryable = false,
                httpStatusCode = detectedCode,
                message = message,
                userExplanation = "Provider quota or credits are exhausted. Configure a fallback provider or top up credits.",
            )
        }

        // 4. Rate limit (HTTP 429) — retryable respecting Retry-After
        if (detectedCode == 429 || lower.contains("rate limit") || lower.contains("too many requests")) {
            val retryAfter = parseRetryAfterMs(message, maxBackoffMs)
            return ClassifiedError(
                category = ExecutionErrorCategory.RATE_LIMIT,
                retryable = true,
                retryAfterMs = retryAfter,
                httpStatusCode = 429,
                message = message,
                userExplanation = if (retryAfter != null) {
                    "Provider rate-limited (HTTP 429). Waiting ${retryAfter / 1000}s per Retry-After."
                } else {
                    "Provider rate-limited (HTTP 429). Backing off before retry."
                },
            )
        }

        // 5. Idle stream timeout
        if (isIdleStream || lower.contains("idle stream")) {
            return ClassifiedError(
                category = ExecutionErrorCategory.IDLE_STREAM,
                retryable = true,
                message = message,
                userExplanation = "Model stream became idle. Recovering request from last checkpoint.",
            )
        }

        // 6. Request timeout
        if (isTimeout || lower.contains("timed out") || lower.contains("timeout")) {
            return ClassifiedError(
                category = ExecutionErrorCategory.TIMEOUT,
                retryable = true,
                message = message,
                userExplanation = "Individual model request timed out. Completed steps are preserved; recovering current step.",
            )
        }

        // 7. CLI process crash / non-zero exit
        if ((exitCode != null && exitCode != 0) ||
            lower.contains("process killed") ||
            lower.contains("sigkill") ||
            lower.contains("sigterm") ||
            lower.contains("cli crash") ||
            lower.contains("exited with code")
        ) {
            return ClassifiedError(
                category = ExecutionErrorCategory.CLI_CRASH,
                retryable = true,
                message = message,
                userExplanation = "CLI worker process exited unexpectedly. Inspecting workspace and resuming from safe checkpoint.",
            )
        }

        // 8. Network / transient 5xx
        if ((detectedCode != null && detectedCode in 500..599) ||
            lower.contains("unable to resolve host") ||
            lower.contains("connection reset") ||
            lower.contains("socket") ||
            lower.contains("network") ||
            lower.contains("eof")
        ) {
            return ClassifiedError(
                category = ExecutionErrorCategory.NETWORK_ERROR,
                retryable = true,
                httpStatusCode = detectedCode,
                message = message,
                userExplanation = "Transient network or provider server error. Retrying safely.",
            )
        }

        return ClassifiedError(
            category = ExecutionErrorCategory.UNKNOWN,
            retryable = true,
            httpStatusCode = detectedCode,
            message = message,
            userExplanation = message.take(240),
        )
    }

    private fun extractHttpStatusCode(lower: String): Int? {
        val m = Regex("""\b(401|403|404|408|429|500|502|503|504)\b""").find(lower)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }
}
