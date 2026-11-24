package com.jarves.mh.provider.core

data class OfficialQuota(
    val providerId: String,
    val remainingPercent: Float? = null,
    val quotaUsed: String? = null,
    val quotaLimit: String? = null,
    val resetTime: String? = null,
    val rateLimit: String? = null,
    val source: String = "Provider API",
    val isAvailable: Boolean = false,
    val unavailableReason: String? = null,
    val lastUpdatedMillis: Long = System.currentTimeMillis(),
)

data class EstimatedUsage(
    val estimatedTokens: Long = 0L,
    val estimatedCostUsd: Double = 0.0,
    val estimatedCostThb: Double = 0.0,
    val requestCount: Int = 0,
    val modelId: String = "",
)

data class LocalUsage(
    val totalRequests: Int = 0,
    val completedTasks: Int = 0,
    val failedTasks: Int = 0,
    val activeAgents: Int = 0,
    val sessionTimeMillis: Long = 0L,
    val lastActiveMillis: Long = System.currentTimeMillis(),
)

enum class QuotaHealth {
    NORMAL, // Green (> 20%)
    LOW,    // Yellow (<= 20%)
    DEPLETED, // Red (0% or Rate Limited)
    UNAVAILABLE, // Gray
}

fun calculateQuotaHealth(quota: OfficialQuota?): QuotaHealth {
    if (quota == null || !quota.isAvailable) return QuotaHealth.UNAVAILABLE
    val remaining = quota.remainingPercent ?: return QuotaHealth.NORMAL
    return when {
        remaining <= 0.01f -> QuotaHealth.DEPLETED
        remaining <= 0.20f -> QuotaHealth.LOW
        else -> QuotaHealth.NORMAL
    }
}
