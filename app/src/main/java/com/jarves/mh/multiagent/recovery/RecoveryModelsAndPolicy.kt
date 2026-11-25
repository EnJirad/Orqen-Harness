package com.jarves.mh.multiagent.recovery

import java.util.UUID

enum class RecoveryState {
    ACTIVE,
    INTERRUPTED,
    RECOVERY_PENDING,
    RECOVERING,
    RESUMABLE,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

object RecoveryStateTransitionValidator {
    fun isValid(from: RecoveryState, to: RecoveryState): Boolean {
        if (from == to) return true
        return when (from) {
            RecoveryState.ACTIVE -> to in setOf(
                RecoveryState.INTERRUPTED,
                RecoveryState.BLOCKED,
                RecoveryState.COMPLETED,
                RecoveryState.FAILED,
                RecoveryState.CANCELLED,
            )
            RecoveryState.INTERRUPTED -> to in setOf(
                RecoveryState.RECOVERY_PENDING,
                RecoveryState.RESUMABLE,
                RecoveryState.BLOCKED,
                RecoveryState.CANCELLED,
            )
            RecoveryState.RECOVERY_PENDING -> to in setOf(
                RecoveryState.RECOVERING,
                RecoveryState.BLOCKED,
                RecoveryState.CANCELLED,
            )
            RecoveryState.RECOVERING -> to in setOf(
                RecoveryState.ACTIVE,
                RecoveryState.RESUMABLE,
                RecoveryState.BLOCKED,
                RecoveryState.FAILED,
                RecoveryState.CANCELLED,
            )
            RecoveryState.RESUMABLE -> to in setOf(
                RecoveryState.RECOVERY_PENDING,
                RecoveryState.RECOVERING,
                RecoveryState.ACTIVE,
                RecoveryState.CANCELLED,
            )
            RecoveryState.BLOCKED -> to in setOf(
                RecoveryState.RESUMABLE,
                RecoveryState.RECOVERY_PENDING,
                RecoveryState.CANCELLED,
            )
            RecoveryState.COMPLETED, RecoveryState.FAILED, RecoveryState.CANCELLED -> false
        }
    }
}

enum class InterruptionCause {
    NORMAL_SHUTDOWN,
    ACTIVITY_RECREATION,
    BACKGROUND_TERMINATION,
    UNEXPECTED_PROCESS_DEATH,
    DEVICE_REBOOT,
    USER_FORCE_STOP,
    USER_CANCELLATION,
    EXECUTION_TIMEOUT,
    PROVIDER_NETWORK_FAILURE,
}

enum class RecoveryMode {
    AUTOMATIC,
    ASK_BEFORE_RESUMING,
    MANUAL_ONLY,
}

/**
 * Centralized, persistable recovery policy (no hardcoded scattered retry constants).
 */
data class RecoveryPolicy(
    val mode: RecoveryMode = RecoveryMode.AUTOMATIC,
    val maxRecoveryAttempts: Int = 5,
    val baseRetryDelayMs: Long = 2_000L,
    val maxRetryDelayMs: Long = 120_000L,
    val requireNetwork: Boolean = true,
    val batteryAware: Boolean = true,
    val maxConcurrentRecoveries: Int = 2,
    val heartbeatIntervalMs: Long = 15_000L,
    val leaseTtlMs: Long = 60_000L,
    val notifyOnRecovery: Boolean = true,
)

enum class RecoveryEventType {
    PROCESS_INTERRUPTED,
    RECOVERY_SCHEDULED,
    RECOVERY_STARTED,
    CHECKPOINT_RESTORED,
    EXECUTION_RECLAIMED,
    AGENT_RESTORED,
    RECOVERY_RETRY,
    RECOVERY_BLOCKED,
    RECOVERY_COMPLETED,
    RECOVERY_FAILED,
}

data class RecoveryEventRecord(
    val eventId: String = "revt-${UUID.randomUUID().toString().take(8)}",
    val taskId: String,
    val stepOrAgentId: String? = null,
    val eventType: RecoveryEventType,
    val attempt: Int = 1,
    val reason: String,
    val errorDetails: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

data class ExecutionLeaseRecord(
    val taskId: String,
    val stepId: String?,
    val ownerId: String,
    val acquiredAtMs: Long,
    val expiresAtMs: Long,
    val lastHeartbeatMs: Long,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

/**
 * Honest documentation of Android platform execution boundaries.
 */
object AndroidPlatformGuarantees {
    data class PlatformCondition(
        val condition: String,
        val canContinueLocally: Boolean,
        val canRecoverAutomatically: Boolean,
        val honestBehavior: String,
    )

    val documentedConditions: List<PlatformCondition> = listOf(
        PlatformCondition(
            condition = "App moved to background",
            canContinueLocally = true,
            canRecoverAutomatically = true,
            honestBehavior = "Active user-started Foreground Service may continue subject to Android background limits.",
        ),
        PlatformCondition(
            condition = "Process killed by Android",
            canContinueLocally = false,
            canRecoverAutomatically = true,
            honestBehavior = "Persisted state is reconciled on next app launch or scheduled JobScheduler recovery.",
        ),
        PlatformCondition(
            condition = "Device reboot",
            canContinueLocally = false,
            canRecoverAutomatically = true,
            honestBehavior = "BootRecoveryReceiver schedules deferred reconciliation via JobScheduler for eligible tasks.",
        ),
        PlatformCondition(
            condition = "User force-stops application",
            canContinueLocally = false,
            canRecoverAutomatically = false,
            honestBehavior = "Android blocks all background work and boot receivers until the user explicitly launches ORQEN again. Never bypassed.",
        ),
        PlatformCondition(
            condition = "Device powered off",
            canContinueLocally = false,
            canRecoverAutomatically = false,
            honestBehavior = "Local execution stops; requires Remote Execution Host (Phase 6) for off-device continuity.",
        ),
        PlatformCondition(
            condition = "Provider or network outage",
            canContinueLocally = true,
            canRecoverAutomatically = true,
            honestBehavior = "Task stays persisted and retries according to RecoveryPolicy with network constraints.",
        ),
    )
}
