package com.jarves.mh.multiagent.model

import com.jarves.mh.multiagent.events.TeamCollaborationEvent
import com.jarves.mh.multiagent.events.TeamEventType
import com.jarves.mh.multiagent.locks.FileLock
import com.jarves.mh.multiagent.tasks.TeamTask
import com.jarves.mh.provider.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Formal Agent Lifecycle States for the ORQEN Multi-Agent Workspace.
 */
enum class AgentLifecycleState {
    UNINITIALIZED,
    IDLE,
    ANALYZING,
    PLANNING,
    EXECUTING,
    TESTING,
    WAITING_FOR_REVIEW,
    COMMITTING,
    BLOCKED,
    ERROR,
    TERMINATED;

    /**
     * Map lifecycle state to visual activity icon.
     */
    fun toActivityType(): ActivityType = when (this) {
        UNINITIALIZED -> ActivityType.WAITING
        IDLE -> ActivityType.WAITING
        ANALYZING -> ActivityType.ANALYZING
        PLANNING -> ActivityType.PLANNING
        EXECUTING -> ActivityType.WRITING_CODE
        TESTING -> ActivityType.TESTING
        WAITING_FOR_REVIEW -> ActivityType.REVIEWING
        COMMITTING -> ActivityType.COMMITTING
        BLOCKED -> ActivityType.PAUSED
        ERROR -> ActivityType.ERROR
        TERMINATED -> ActivityType.COMPLETED
    }
}

/**
 * State transition validation record.
 */
data class StateTransitionRecord(
    val fromState: AgentLifecycleState,
    val toState: AgentLifecycleState,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Formal State Machine governing an Agent's execution flow.
 * Validates allowed state transitions to prevent corrupted or race condition states.
 */
class AgentLifecycleStateMachine(val slot: AgentSlot) {

    private val _currentState = MutableStateFlow(AgentLifecycleState.UNINITIALIZED)
    val currentState: StateFlow<AgentLifecycleState> = _currentState.asStateFlow()

    private val _history = mutableListOf<StateTransitionRecord>()
    val history: List<StateTransitionRecord> get() = _history.toList()

    companion object {
        private val ALLOWED_TRANSITIONS = mapOf(
            AgentLifecycleState.UNINITIALIZED to setOf(AgentLifecycleState.IDLE, AgentLifecycleState.ERROR),
            AgentLifecycleState.IDLE to setOf(AgentLifecycleState.ANALYZING, AgentLifecycleState.PLANNING, AgentLifecycleState.EXECUTING, AgentLifecycleState.TERMINATED),
            AgentLifecycleState.ANALYZING to setOf(AgentLifecycleState.PLANNING, AgentLifecycleState.EXECUTING, AgentLifecycleState.BLOCKED, AgentLifecycleState.ERROR, AgentLifecycleState.IDLE),
            AgentLifecycleState.PLANNING to setOf(AgentLifecycleState.EXECUTING, AgentLifecycleState.BLOCKED, AgentLifecycleState.ERROR, AgentLifecycleState.IDLE),
            AgentLifecycleState.EXECUTING to setOf(AgentLifecycleState.TESTING, AgentLifecycleState.WAITING_FOR_REVIEW, AgentLifecycleState.COMMITTING, AgentLifecycleState.BLOCKED, AgentLifecycleState.ERROR),
            AgentLifecycleState.TESTING to setOf(AgentLifecycleState.WAITING_FOR_REVIEW, AgentLifecycleState.COMMITTING, AgentLifecycleState.EXECUTING, AgentLifecycleState.ERROR),
            AgentLifecycleState.WAITING_FOR_REVIEW to setOf(AgentLifecycleState.COMMITTING, AgentLifecycleState.EXECUTING, AgentLifecycleState.IDLE, AgentLifecycleState.ERROR),
            AgentLifecycleState.COMMITTING to setOf(AgentLifecycleState.IDLE, AgentLifecycleState.EXECUTING, AgentLifecycleState.TERMINATED, AgentLifecycleState.ERROR),
            AgentLifecycleState.BLOCKED to setOf(AgentLifecycleState.ANALYZING, AgentLifecycleState.EXECUTING, AgentLifecycleState.IDLE, AgentLifecycleState.ERROR),
            AgentLifecycleState.ERROR to setOf(AgentLifecycleState.IDLE, AgentLifecycleState.ANALYZING, AgentLifecycleState.TERMINATED),
            AgentLifecycleState.TERMINATED to setOf(AgentLifecycleState.IDLE),
        )
    }

    @Synchronized
    fun transitionTo(newState: AgentLifecycleState, reason: String = ""): Boolean {
        val current = _currentState.value
        val allowed = ALLOWED_TRANSITIONS[current] ?: emptySet()
        if (newState !in allowed) {
            return false
        }
        _currentState.value = newState
        _history.add(StateTransitionRecord(current, newState, reason))
        if (_history.size > 100) {
            _history.removeAt(0)
        }
        return true
    }

    fun canTransitionTo(newState: AgentLifecycleState): Boolean {
        val allowed = ALLOWED_TRANSITIONS[_currentState.value] ?: emptySet()
        return newState in allowed
    }
}

/**
 * Result contract for file lock operations.
 */
sealed interface LockAcquisitionResult {
    data class Success(val lock: FileLock) : LockAcquisitionResult
    data class Conflict(val lockedBy: AgentSlot, val remainingTtlMs: Long, val reason: String) : LockAcquisitionResult
    data class Error(val message: String) : LockAcquisitionResult
}

/**
 * Formal Concurrency & File Lock Coordinator interface.
 */
interface IFileLockCoordinator {
    fun tryAcquire(
        filePath: String,
        slot: AgentSlot,
        taskId: String,
        reason: String = "Editing file",
        ttlMs: Long = FileLock.DEFAULT_LOCK_TTL_MS,
    ): LockAcquisitionResult

    fun release(filePath: String, slot: AgentSlot): Boolean
    fun renewLock(filePath: String, slot: AgentSlot, extendByMs: Long = FileLock.DEFAULT_LOCK_TTL_MS): Boolean
    fun releaseAllForAgent(slot: AgentSlot): Int
    fun getActiveLocks(): List<FileLock>
    fun isLockedByOther(filePath: String, slot: AgentSlot): Boolean
}

/**
 * Event Priority classification for inter-agent messaging.
 */
enum class EventPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL,
}

/**
 * Priority-Aware Collaboration Event.
 */
data class PrioritizedCollaborationEvent(
    val event: TeamCollaborationEvent,
    val priority: EventPriority = EventPriority.NORMAL,
)

/**
 * Formal GitHub Synchronization Status and Contract.
 */
data class GitSyncStatus(
    val isConnected: Boolean,
    val remoteUrl: String,
    val activeBranch: String,
    val lastSyncTimestamp: Long = System.currentTimeMillis(),
    val pendingCommitsCount: Int = 0,
)

interface IGitHubSyncCoordinator {
    suspend fun getSyncStatus(): GitSyncStatus
    suspend fun pullLatestTasks(): List<TeamTask>
    suspend fun pushCheckpoint(partNumber: Int, taskTitle: String, changedFiles: List<String>): String
}

/**
 * Quota Evaluation & Cost Estimator Contract.
 */
class StandardQuotaEvaluator : IQuotaEvaluator {

    override fun evaluateHealth(official: OfficialQuota?): QuotaHealth {
        return calculateQuotaHealth(official)
    }

    override fun estimateCost(inputTokens: Long, outputTokens: Long, modelId: String): EstimatedUsage {
        // Standard baseline pricing (per 1M tokens): $0.50 input, $1.50 output (USD)
        val inputCostPerM = when {
            modelId.contains("pro", ignoreCase = true) -> 1.25
            modelId.contains("claude-sonnet", ignoreCase = true) -> 3.00
            else -> 0.35
        }
        val outputCostPerM = when {
            modelId.contains("pro", ignoreCase = true) -> 5.00
            modelId.contains("claude-sonnet", ignoreCase = true) -> 15.00
            else -> 1.05
        }

        val costUsd = (inputTokens * inputCostPerM / 1_000_000.0) + (outputTokens * outputCostPerM / 1_000_000.0)
        val costThb = costUsd * 35.0 // Current USD to THB standard conversion rate

        return EstimatedUsage(
            estimatedTokens = inputTokens + outputTokens,
            estimatedCostUsd = costUsd,
            estimatedCostThb = costThb,
            modelId = modelId,
        )
    }
}

interface IQuotaEvaluator {
    fun evaluateHealth(official: OfficialQuota?): QuotaHealth
    fun estimateCost(inputTokens: Long, outputTokens: Long, modelId: String): EstimatedUsage
}
