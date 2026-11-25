package com.jarves.mh.multiagent.host

import com.jarves.mh.multiagent.engine.PersistentTaskOrchestrator
import com.jarves.mh.multiagent.persistence.ExecutionMode
import com.jarves.mh.multiagent.persistence.OrqenTaskStore
import com.jarves.mh.multiagent.persistence.PersistentTaskStatus
import com.jarves.mh.multiagent.persistence.StepStatus
import kotlinx.coroutines.CoroutineScope

data class HostTaskProgress(
    val taskId: String,
    val status: PersistentTaskStatus,
    val completedSteps: Int,
    val totalSteps: Int,
    val currentStepTitle: String?,
    val actualCostUsd: Double,
    val lastError: String?,
    val isConnected: Boolean = true,
    val executionMode: ExecutionMode = ExecutionMode.LOCAL_ANDROID,
)

/**
 * Abstraction separating execution from the UI/mobile client.
 * - [LocalAndroidExecutionHost]: executes tasks on-device via [PersistentTaskOrchestrator].
 * - [RemoteExecutionHost]: control-panel adapter for a remote worker backend (survives phone power-off
 *   or force-stop; client can disconnect and reconnect to the exact same taskId without losing progress).
 */
interface ExecutionHost {
    val hostId: String
    val mode: ExecutionMode
    val isConnected: Boolean

    fun submitTask(scope: CoroutineScope, taskId: String): Boolean
    fun pauseTask(taskId: String, reason: String = "Paused by owner")
    fun resumeTask(scope: CoroutineScope, taskId: String): Boolean
    fun stopTask(taskId: String, reason: String = "Stopped by owner")
    fun stopAllTasks(reason: String = "Stop All requested"): Int
    fun queryTaskProgress(taskId: String): HostTaskProgress?
    fun disconnect()
    fun reconnect(): Boolean
}

class LocalAndroidExecutionHost(
    private val store: OrqenTaskStore,
    private val orchestrator: PersistentTaskOrchestrator,
) : ExecutionHost {
    override val hostId: String = "local-android"
    override val mode: ExecutionMode = ExecutionMode.LOCAL_ANDROID
    override val isConnected: Boolean = true

    override fun submitTask(scope: CoroutineScope, taskId: String): Boolean {
        if (store.getTask(taskId) == null) return false
        orchestrator.launchTask(scope, taskId)
        return true
    }

    override fun pauseTask(taskId: String, reason: String) {
        orchestrator.pauseTask(taskId, reason)
    }

    override fun resumeTask(scope: CoroutineScope, taskId: String): Boolean {
        return orchestrator.resumeTask(scope, taskId) != null
    }

    override fun stopTask(taskId: String, reason: String) {
        orchestrator.stopTask(taskId, reason)
    }

    override fun stopAllTasks(reason: String): Int = orchestrator.stopAllTasks(reason)

    override fun queryTaskProgress(taskId: String): HostTaskProgress? {
        val task = store.getTask(taskId) ?: return null
        val steps = store.listStepsForTask(taskId)
        return HostTaskProgress(
            taskId = taskId,
            status = task.status,
            completedSteps = steps.count { it.status == StepStatus.COMPLETED },
            totalSteps = steps.size,
            currentStepTitle = steps.firstOrNull { it.stepId == task.currentStepId }?.title,
            actualCostUsd = task.actualCostUsd,
            lastError = task.lastError,
            isConnected = true,
            executionMode = ExecutionMode.LOCAL_ANDROID,
        )
    }

    override fun disconnect() = Unit
    override fun reconnect(): Boolean = true
}

/**
 * Transport contract for a Remote Execution Host server.
 * See `docs/REMOTE_EXECUTION_CONTRACT.md` for the HTTP/WebSocket protocol specification.
 * Credentials/tokens must NEVER be hardcoded; inject from secure storage only when a remote
 * backend is explicitly configured by the user.
 */
interface RemoteExecutionTransport {
    fun submit(taskId: String): Boolean
    fun pause(taskId: String, reason: String)
    fun resume(taskId: String): Boolean
    fun stop(taskId: String, reason: String)
    fun stopAll(reason: String): Int
    fun fetchProgress(taskId: String): HostTaskProgress?
    fun ping(): Boolean
}

class RemoteExecutionHost(
    override val hostId: String,
    private val transport: RemoteExecutionTransport,
) : ExecutionHost {
    override val mode: ExecutionMode = ExecutionMode.REMOTE_HOST
    @Volatile
    private var connected: Boolean = true
    override val isConnected: Boolean get() = connected

    override fun submitTask(scope: CoroutineScope, taskId: String): Boolean {
        if (!connected) return false
        return transport.submit(taskId)
    }

    override fun pauseTask(taskId: String, reason: String) {
        if (connected) transport.pause(taskId, reason)
    }

    override fun resumeTask(scope: CoroutineScope, taskId: String): Boolean {
        return connected && transport.resume(taskId)
    }

    override fun stopTask(taskId: String, reason: String) {
        if (connected) transport.stop(taskId, reason)
    }

    override fun stopAllTasks(reason: String): Int {
        return if (connected) transport.stopAll(reason) else 0
    }

    override fun queryTaskProgress(taskId: String): HostTaskProgress? {
        if (!connected) return null
        return transport.fetchProgress(taskId)?.copy(isConnected = true, executionMode = ExecutionMode.REMOTE_HOST)
    }

    override fun disconnect() {
        // Disconnecting the mobile client does NOT stop remote workers!
        connected = false
    }

    override fun reconnect(): Boolean {
        val ok = runCatching { transport.ping() }.getOrDefault(false)
        connected = ok
        return ok
    }
}
