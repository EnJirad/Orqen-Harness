package com.jarves.mh.multiagent.persistence

import java.util.UUID

/**
 * Durable status for a top-level autonomous task.
 * Independent of Conversation and ModelRequest lifecycles.
 */
enum class PersistentTaskStatus {
    DRAFT,
    QUEUED,
    PLANNING,
    READY,
    RUNNING,
    PAUSE_REQUESTED,
    PAUSED,
    RECOVERING,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * Enforces valid task status transitions.
 * Terminal states (COMPLETED, CANCELLED, FAILED) cannot silently transition back to RUNNING
 * unless explicitly reopened via an auditable user action.
 */
object TaskStatusTransitionValidator {
    private val terminalStates = setOf(
        PersistentTaskStatus.COMPLETED,
        PersistentTaskStatus.CANCELLED,
        PersistentTaskStatus.FAILED,
    )

    fun isValidTransition(
        from: PersistentTaskStatus,
        to: PersistentTaskStatus,
        explicitUserReopen: Boolean = false,
    ): Boolean {
        if (from == to) return true
        if (from in terminalStates) {
            return explicitUserReopen && (to == PersistentTaskStatus.QUEUED || to == PersistentTaskStatus.READY)
        }
        return when (from) {
            PersistentTaskStatus.DRAFT -> to in setOf(PersistentTaskStatus.QUEUED, PersistentTaskStatus.PLANNING, PersistentTaskStatus.CANCELLED)
            PersistentTaskStatus.QUEUED -> to in setOf(PersistentTaskStatus.PLANNING, PersistentTaskStatus.READY, PersistentTaskStatus.RUNNING, PersistentTaskStatus.PAUSED, PersistentTaskStatus.BLOCKED, PersistentTaskStatus.CANCELLED)
            PersistentTaskStatus.PLANNING -> to in setOf(PersistentTaskStatus.READY, PersistentTaskStatus.RUNNING, PersistentTaskStatus.PAUSED, PersistentTaskStatus.BLOCKED, PersistentTaskStatus.FAILED, PersistentTaskStatus.CANCELLED)
            PersistentTaskStatus.READY -> to in setOf(PersistentTaskStatus.RUNNING, PersistentTaskStatus.PAUSED, PersistentTaskStatus.BLOCKED, PersistentTaskStatus.CANCELLED)
            PersistentTaskStatus.RUNNING -> to in setOf(PersistentTaskStatus.PAUSE_REQUESTED, PersistentTaskStatus.PAUSED, PersistentTaskStatus.RECOVERING, PersistentTaskStatus.BLOCKED, PersistentTaskStatus.COMPLETED, PersistentTaskStatus.FAILED, PersistentTaskStatus.CANCELLED, PersistentTaskStatus.QUEUED)
            PersistentTaskStatus.PAUSE_REQUESTED -> to in setOf(PersistentTaskStatus.PAUSED, PersistentTaskStatus.CANCELLED, PersistentTaskStatus.FAILED)
            PersistentTaskStatus.PAUSED -> to in setOf(PersistentTaskStatus.QUEUED, PersistentTaskStatus.READY, PersistentTaskStatus.RUNNING, PersistentTaskStatus.CANCELLED)
            PersistentTaskStatus.RECOVERING -> to in setOf(PersistentTaskStatus.QUEUED, PersistentTaskStatus.RUNNING, PersistentTaskStatus.BLOCKED, PersistentTaskStatus.FAILED, PersistentTaskStatus.CANCELLED)
            PersistentTaskStatus.BLOCKED -> to in setOf(PersistentTaskStatus.QUEUED, PersistentTaskStatus.READY, PersistentTaskStatus.RUNNING, PersistentTaskStatus.PAUSED, PersistentTaskStatus.FAILED, PersistentTaskStatus.CANCELLED)
            PersistentTaskStatus.COMPLETED, PersistentTaskStatus.FAILED, PersistentTaskStatus.CANCELLED -> false
        }
    }
}

/**
 * Strips API keys, bearer tokens, and authorization headers before any text is persisted
 * in task metadata, error logs, or event payloads.
 */
object SecretSanitizer {
    private val patterns = listOf(
        Regex("""(Bearer\s+)[A-Za-z0-9._\-+/=]{8,}""", RegexOption.IGNORE_CASE) to "$1[REDACTED]",
        Regex("""(x-api-key\s*[:=]\s*)[^\s,;"]+""", RegexOption.IGNORE_CASE) to "$1[REDACTED]",
        Regex("""(Authorization\s*[:=]\s*)[^\r\n]+""", RegexOption.IGNORE_CASE) to "$1[REDACTED]",
        Regex("""\bsk-[A-Za-z0-9_\-]{12,}\b""") to "[REDACTED_API_KEY]",
        Regex("""\bAIza[0-9A-Za-z_\-]{20,}\b""") to "[REDACTED_API_KEY]",
        Regex("""\bghp_[A-Za-z0-9]{20,}\b""") to "[REDACTED_TOKEN]",
    )

    fun sanitize(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        var out: String = input
        for ((regex, replacement) in patterns) {
            out = regex.replace(out, replacement)
        }
        return out
    }
}

/**
 * Validates workspace and checkpoint paths to block path traversal outside permitted roots.
 */
object WorkspacePathValidator {
    fun isPathInsideRoot(rootDir: java.io.File, candidatePath: String): Boolean {
        if (candidatePath.isBlank()) return false
        val normalized = candidatePath.replace('\\', '/')
        if (normalized.split('/').any { it == ".." }) return false
        return runCatching {
            val rootCanonical = rootDir.canonicalFile.toPath()
            val candidate = if (java.io.File(candidatePath).isAbsolute) {
                java.io.File(candidatePath).canonicalFile.toPath()
            } else {
                java.io.File(rootDir, candidatePath).canonicalFile.toPath()
            }
            candidate.startsWith(rootCanonical)
        }.getOrDefault(false)
    }
}

/**
 * Persists task attachment file paths (e.g., images) inside [TaskEntity.taskMemory]
 * so attachments survive queuing, delegation, and Android process recreation without
 * requiring a separate schema migration or temporary in-memory queue.
 */
object TaskAttachmentCodec {
    private const val PREFIX = "ORQEN_ATTACHMENTS="

    fun encodeIntoTaskMemory(existingMemory: String, attachmentPaths: List<String>): String {
        val clean = attachmentPaths.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return existingMemory
        val merged = (decodePaths(existingMemory) + clean).distinct()
        val stripped = existingMemory.lines().filterNot { it.startsWith(PREFIX) }.joinToString("\n").trim()
        val encoded = PREFIX + merged.joinToString("|")
        return if (stripped.isBlank()) encoded else "$stripped\n$encoded"
    }

    fun decodePaths(taskMemory: String): List<String> {
        val line = taskMemory.lines().firstOrNull { it.startsWith(PREFIX) } ?: return emptyList()
        return line.removePrefix(PREFIX)
            .split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun resolveAccessibleFiles(taskMemory: String, workspaceDir: java.io.File): List<java.io.File> {
        return decodePaths(taskMemory).mapNotNull { path ->
            val file = java.io.File(path)
            if (file.isFile && file.length() > 0L && WorkspacePathValidator.isPathInsideRoot(workspaceDir, file.absolutePath)) {
                file
            } else null
        }
    }
}

data class TaskConversationCrossRef(
    val taskId: String,
    val conversationId: String,
    val linkedAt: Long = System.currentTimeMillis(),
    val linkedBy: String = "owner",
    val relationshipType: String = "ATTACHED",
)

enum class StepStatus {
    PENDING,
    READY,
    RUNNING,
    WAITING_REVIEW,
    COMPLETED,
    FAILED,
    BLOCKED,
    SKIPPED,
    CANCELLED,
}

/**
 * 13-state Execution Recovery Supervisor state machine.
 */
enum class ExecutionLifecycleState {
    IDLE,
    STARTING,
    RUNNING,
    REQUESTING_MODEL,
    EXECUTING_TOOL,
    CHECKPOINTING,
    RETRY_WAIT,
    RECOVERING,
    PAUSED,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class AgentMessageKind {
    TASK_DELEGATION,
    AGENT_TO_AGENT,
    REVIEW_REQUEST,
    REVIEW_APPROVAL,
    CHANGE_REQUEST,
    CONFLICT_REPORT,
    COMPLETION_REPORT,
    SHARED_DECISION,
    ESCALATION_TO_LEADER,
}

enum class ExecutionMode {
    LOCAL_ANDROID,
    REMOTE_HOST,
}

data class BudgetConfiguration(
    val maxTaskBudgetUsd: Double = 10.0,
    val dailyBudgetUsd: Double = 25.0,
    val autoPauseOnBudgetExceeded: Boolean = true,
    val maxParallelAgents: Int = 2,
    val maxParallelTasks: Int = 2,
)

data class TaskCompletionCriteria(
    val requireBuildPass: Boolean = false,
    val requireTestsPass: Boolean = true,
    val requireReviewerApproval: Boolean = true,
    val requireDeliverableVerification: Boolean = true,
    val acceptanceChecks: List<String> = emptyList(),
)

data class ProjectEntity(
    val projectId: String,
    val projectName: String,
    val workspacePath: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val projectMemory: String = "",
    val activeTaskIds: List<String> = emptyList(),
)

data class ConversationEntity(
    val conversationId: String = "conv-${UUID.randomUUID()}",
    val projectId: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val contextSummary: String = "",
    val linkedTaskIds: List<String> = emptyList(),
    val activeAgentId: String? = null,
    val archived: Boolean = false,
)

data class ConversationMessageEntity(
    val messageId: String = "cmsg-${UUID.randomUUID()}",
    val conversationId: String,
    val projectId: String,
    val senderId: String,
    val senderName: String,
    val isUser: Boolean,
    val content: String,
    val relatedTaskId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

data class TaskEntity(
    val taskId: String = "T-${UUID.randomUUID().toString().take(8).uppercase()}",
    val projectId: String,
    val title: String,
    val description: String,
    val status: PersistentTaskStatus = PersistentTaskStatus.QUEUED,
    val priority: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val ownerStopRequested: Boolean = false,
    val pauseRequested: Boolean = false,
    val currentStepId: String? = null,
    val executionMode: ExecutionMode = ExecutionMode.LOCAL_ANDROID,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val budgetConfiguration: BudgetConfiguration = BudgetConfiguration(),
    val completionCriteria: TaskCompletionCriteria = TaskCompletionCriteria(),
    val totalInputTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val actualCostUsd: Double = 0.0,
    val taskMemory: String = "",
    val executionLeaseOwner: String? = null,
    val lastHeartbeatAt: Long = 0L,
)

data class TaskStepEntity(
    val stepId: String = "step-${UUID.randomUUID().toString().take(8)}",
    val taskId: String,
    val sequenceNumber: Int,
    val title: String,
    val description: String,
    val assignedAgentId: String,
    val status: StepStatus = StepStatus.PENDING,
    val dependencies: List<String> = emptyList(),
    val inputContext: String = "",
    val outputSummary: String = "",
    val targetFiles: List<String> = emptyList(),
    val requiresReview: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val checkpointId: String? = null,
    val retryCount: Int = 0,
    val idempotencyKey: String = "idem-${UUID.randomUUID().toString().take(12)}",
)

data class AgentExecutionEntity(
    val executionId: String = "exec-${UUID.randomUUID().toString().take(10)}",
    val taskId: String,
    val stepId: String,
    val agentId: String,
    val providerId: String,
    val modelId: String,
    val requestId: String = "req-${UUID.randomUUID().toString().take(8)}",
    val executionStatus: String = "RUNNING",
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val errorType: String? = null,
    val errorMessage: String? = null,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val estimatedCostUsd: Double = 0.0,
    val processPid: Long? = null,
)

data class CheckpointEntity(
    val checkpointId: String = "chk-${UUID.randomUUID().toString().take(10)}",
    val taskId: String,
    val stepId: String,
    val checkpointType: String = "STEP_SNAPSHOT",
    val workspacePath: String? = null,
    val workspaceSnapshot: Map<String, String> = emptyMap(), // relativePath -> SHA-256
    val modifiedFiles: List<String> = emptyList(),
    val gitCommitSha: String? = null,
    val contextSummary: String = "",
    val isValid: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
)

data class AgentMessageEntity(
    val messageId: String = "amsg-${UUID.randomUUID().toString().take(10)}",
    val taskId: String,
    val fromAgentId: String,
    val toAgentId: String,
    val messageType: AgentMessageKind,
    val content: String,
    val relatedStepId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

data class TaskEventEntity(
    val eventId: String = "evt-${UUID.randomUUID().toString().take(10)}",
    val taskId: String,
    val stepId: String? = null,
    val eventType: String,
    val eventPayload: String,
    val sequenceNumber: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
)

data class IdempotencyRecordEntity(
    val idempotencyKey: String,
    val taskId: String,
    val stepId: String,
    val operationType: String,
    val resultSummary: String,
    val completedAt: Long = System.currentTimeMillis(),
)
