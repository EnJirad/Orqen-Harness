package com.jarves.mh.multiagent.session

import com.jarves.mh.multiagent.persistence.*

/**
 * Hydrated snapshot returned when a conversation attaches to or continues an existing task.
 */
data class AttachedTaskSnapshot(
    val task: TaskEntity,
    val completedSteps: List<TaskStepEntity>,
    val pendingSteps: List<TaskStepEntity>,
    val activeStep: TaskStepEntity?,
    val latestCheckpoint: CheckpointEntity?,
    val changedFiles: List<String>,
    val recentDecisions: List<AgentMessageEntity>,
    val unresolvedIssues: List<String>,
    val projectMemory: String,
)

data class ProjectWorkspaceView(
    val project: ProjectEntity,
    val conversations: List<ConversationEntity>,
    val tasks: List<TaskEntity>,
    val projectMemory: String,
)

data class InstructionConflictCheck(
    val hasConflict: Boolean,
    val reason: String,
    val existingSummary: String,
    val newInstruction: String,
)

/**
 * Manages independent Multi-Session Conversations within a persistent Project.
 *
 * Enforces the non-negotiable lifecycle separation:
 * - Creating a conversation never duplicates a project.
 * - Closing, archiving, or deleting a conversation NEVER cancels or deletes its tasks.
 * - Any new conversation can attach to an existing task ("Continue Existing Task")
 *   and immediately hydrate full task state, checkpoints, decisions, and changed files.
 * - Cross-project task attachment is explicitly denied (`SecurityException`).
 */
class MultiSessionChatController(
    private val store: OrqenTaskStore,
) {
    private val referenceResolver = TaskReferenceResolver(store)
    fun ensureProject(projectId: String, projectName: String, workspacePath: String): ProjectEntity {
        val existing = store.getProject(projectId)
        if (existing != null) return existing
        val created = ProjectEntity(
            projectId = projectId,
            projectName = projectName,
            workspacePath = workspacePath,
        )
        store.upsertProject(created)
        return created
    }

    fun createConversation(projectId: String, title: String, activeAgentId: String? = null): ConversationEntity {
        val conv = ConversationEntity(
            projectId = projectId,
            title = title.trim().ifBlank { "New Session" },
            activeAgentId = activeAgentId,
        )
        store.upsertConversation(conv)
        return conv
    }

    fun renameConversation(conversationId: String, newTitle: String): ConversationEntity? {
        val conv = store.getConversation(conversationId) ?: return null
        val updated = conv.copy(
            title = newTitle.trim().ifBlank { conv.title },
            updatedAt = System.currentTimeMillis(),
        )
        store.upsertConversation(updated)
        return updated
    }

    fun archiveConversation(conversationId: String): ConversationEntity? {
        val conv = store.getConversation(conversationId) ?: return null
        val updated = conv.copy(archived = true, updatedAt = System.currentTimeMillis())
        store.upsertConversation(updated)
        return updated
    }

    fun restoreArchivedConversation(conversationId: String): ConversationEntity? {
        val conv = store.getConversation(conversationId) ?: return null
        val updated = conv.copy(archived = false, updatedAt = System.currentTimeMillis())
        store.upsertConversation(updated)
        return updated
    }

    fun detachTaskFromConversation(conversationId: String, taskId: String) {
        val conv = store.getConversation(conversationId) ?: return
        store.runInTransaction {
            store.unlinkTaskFromConversation(conversationId, taskId)
            store.upsertConversation(
                conv.copy(
                    linkedTaskIds = conv.linkedTaskIds.filterNot { it == taskId },
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteConversationPreservingTasks(conversationId: String) {
        // Explicitly deletes ONLY the conversation and its chat messages — never tasks or workspace!
        store.deleteConversationOnly(conversationId)
    }

    fun listConversations(projectId: String, includeArchived: Boolean = false): List<ConversationEntity> =
        store.listConversationsForProject(projectId, includeArchived)

    fun postMessage(
        conversationId: String,
        projectId: String,
        senderId: String,
        senderName: String,
        isUser: Boolean,
        content: String,
        relatedTaskId: String? = null,
    ): ConversationMessageEntity {
        val msg = ConversationMessageEntity(
            conversationId = conversationId,
            projectId = projectId,
            senderId = senderId,
            senderName = senderName,
            isUser = isUser,
            content = content,
            relatedTaskId = relatedTaskId,
        )
        store.insertConversationMessage(msg)
        store.getConversation(conversationId)?.let { conv ->
            store.upsertConversation(conv.copy(updatedAt = System.currentTimeMillis()))
        }
        return msg
    }

    /**
     * "Continue Existing Task": attaches [taskId] to [conversationId] transactionally
     * (rejecting unauthorized cross-project attachment) and returns the complete durable state.
     */
    fun attachExistingTask(conversationId: String, taskId: String): AttachedTaskSnapshot? {
        val conv = store.getConversation(conversationId) ?: return null
        val task = store.getTask(taskId) ?: return null
        if (conv.projectId != task.projectId) {
            throw SecurityException("Cross-project task attachment denied: conversation project=${conv.projectId}, task project=${task.projectId}")
        }
        store.runInTransaction {
            store.linkTaskToConversation(TaskConversationCrossRef(taskId = taskId, conversationId = conversationId))
            if (taskId !in conv.linkedTaskIds) {
                store.upsertConversation(
                    conv.copy(
                        linkedTaskIds = conv.linkedTaskIds + taskId,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = taskId,
                    eventType = "TASK_ATTACHED_TO_CONVERSATION",
                    eventPayload = """{"conversationId":"$conversationId"}""",
                ),
            )
        }
        return buildTaskSnapshot(taskId)
    }

    fun continueTaskFromNewChat(projectId: String, conversationId: String, userQuery: String): Pair<TaskReferenceResolution, AttachedTaskSnapshot?> {
        val resolution = referenceResolver.resolve(projectId, userQuery)
        val exact = resolution.exactTask
        val snapshot = if (exact != null && !resolution.isAmbiguous) {
            attachExistingTask(conversationId, exact.taskId)
        } else null
        return resolution to snapshot
    }

    fun switchProject(toProjectId: String): ProjectWorkspaceView? {
        val project = store.getProject(toProjectId) ?: return null
        return ProjectWorkspaceView(
            project = project,
            conversations = store.listConversationsForProject(toProjectId, includeArchived = false),
            tasks = store.listTasksForProject(toProjectId),
            projectMemory = project.projectMemory,
        )
    }

    fun detectConflictingInstructions(taskId: String, newInstruction: String): InstructionConflictCheck {
        val task = store.getTask(taskId)
            ?: return InstructionConflictCheck(false, "Task not found", "", newInstruction)
        val existingText = "${task.title} ${task.description} ${task.taskMemory}".lowercase()
        val incoming = newInstruction.lowercase()
        val opposingPairs = listOf(
            "sqlite" to "nosql",
            "local" to "cloud only",
            "remove" to "keep",
            "disable" to "require",
            "do not" to "must ",
            "delete" to "preserve",
        )
        for ((a, b) in opposingPairs) {
            if ((existingText.contains(a) && incoming.contains(b)) || (existingText.contains(b) && incoming.contains(a))) {
                return InstructionConflictCheck(
                    hasConflict = true,
                    reason = "Potential conflict detected between existing requirements ('$a') and new instruction ('$b'). Preserving both for owner clarification.",
                    existingSummary = task.description.take(200),
                    newInstruction = newInstruction,
                )
            }
        }
        return InstructionConflictCheck(false, "No conflict detected.", task.description.take(200), newInstruction)
    }

    fun searchTasks(
        projectId: String,
        query: String,
        statusFilter: PersistentTaskStatus? = null,
        offset: Int = 0,
        limit: Int = 20,
    ): List<TaskEntity> {
        val q = query.trim().lowercase()
        return store.listTasksForProject(projectId)
            .filter { t ->
                (statusFilter == null || t.status == statusFilter) &&
                    (q.isBlank() || t.taskId.lowercase().contains(q) || t.title.lowercase().contains(q) || t.description.lowercase().contains(q))
            }
            .drop(offset.coerceAtLeast(0))
            .take(limit.coerceAtLeast(0))
    }

    fun searchConversations(
        projectId: String,
        query: String,
        includeArchived: Boolean = false,
        offset: Int = 0,
        limit: Int = 20,
    ): List<ConversationEntity> {
        val q = query.trim().lowercase()
        return store.listConversationsForProject(projectId, includeArchived)
            .filter { conv ->
                if (q.isBlank()) return@filter true
                if (conv.title.lowercase().contains(q) || conv.contextSummary.lowercase().contains(q)) return@filter true
                store.listConversationMessages(conv.conversationId, 200).any { it.content.lowercase().contains(q) }
            }
            .drop(offset.coerceAtLeast(0))
            .take(limit.coerceAtLeast(0))
    }

    fun buildTaskSnapshot(taskId: String): AttachedTaskSnapshot? {
        val task = store.getTask(taskId) ?: return null
        val project = store.getProject(task.projectId)
        val steps = store.listStepsForTask(taskId)
        val latestCheckpoint = store.getLatestCheckpoint(taskId)
        val allCheckpoints = store.listCheckpointsForTask(taskId)
        val changedFiles = allCheckpoints.flatMap { it.modifiedFiles }.distinct().sorted()
        val messages = store.listAgentMessagesForTask(taskId, limit = 50)
        val decisions = messages.filter {
            it.messageType == AgentMessageKind.SHARED_DECISION ||
                it.messageType == AgentMessageKind.REVIEW_APPROVAL ||
                it.messageType == AgentMessageKind.CHANGE_REQUEST ||
                it.messageType == AgentMessageKind.COMPLETION_REPORT
        }
        val unresolved = buildList {
            if (!task.lastError.isNullOrBlank()) add(task.lastError)
            steps.filter { it.status == StepStatus.FAILED || it.status == StepStatus.BLOCKED }
                .forEach { add("Step #${it.sequenceNumber} '${it.title}': ${it.outputSummary.ifBlank { it.status.name }}") }
        }.distinct()

        return AttachedTaskSnapshot(
            task = task,
            completedSteps = steps.filter { it.status == StepStatus.COMPLETED },
            pendingSteps = steps.filter { it.status == StepStatus.PENDING || it.status == StepStatus.READY },
            activeStep = steps.firstOrNull { it.status == StepStatus.RUNNING },
            latestCheckpoint = latestCheckpoint,
            changedFiles = changedFiles,
            recentDecisions = decisions,
            unresolvedIssues = unresolved,
            projectMemory = project?.projectMemory.orEmpty(),
        )
    }

    /**
     * Appends an additional owner instruction as a new step on an active task
     * without interrupting or discarding completed steps.
     */
    fun appendInstructionToTask(
        taskId: String,
        instruction: String,
        assignedAgentId: String = "agent-developer",
    ): TaskStepEntity? {
        val task = store.getTask(taskId) ?: return null
        val existingSteps = store.listStepsForTask(taskId)
        val nextSeq = (existingSteps.maxOfOrNull { it.sequenceNumber } ?: 0) + 1
        val newStep = TaskStepEntity(
            taskId = taskId,
            sequenceNumber = nextSeq,
            title = "Owner directive #$nextSeq",
            description = instruction,
            assignedAgentId = assignedAgentId,
            status = StepStatus.READY,
        )
        store.runInTransaction {
            store.upsertStep(newStep)
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = taskId,
                    eventType = "OWNER_INSTRUCTION_ADDED",
                    eventPayload = instruction.take(300),
                ),
            )
            store.upsertTask(task.copy(updatedAt = System.currentTimeMillis()))
        }
        return newStep
    }
}
