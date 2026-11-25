package com.jarves.mh.multiagent.persistence

/**
 * Durable storage abstraction for ORQEN's autonomous multi-agent platform.
 *
 * Production Android builds use [SqliteOrqenTaskStore] (SQLite WAL + foreign keys + transactions).
 * Pure JVM unit tests use [InMemoryOrqenTaskStore] with identical semantics and schema migration support.
 */
interface OrqenTaskStore {
    val schemaVersion: Int

    fun <T> runInTransaction(block: () -> T): T

    fun validateIntegrity(): Boolean

    // Projects
    fun upsertProject(project: ProjectEntity)
    fun getProject(projectId: String): ProjectEntity?
    fun listProjects(): List<ProjectEntity>

    // Conversations (independent of Task lifecycle)
    fun upsertConversation(conversation: ConversationEntity)
    fun getConversation(conversationId: String): ConversationEntity?
    fun listConversationsForProject(projectId: String, includeArchived: Boolean = false): List<ConversationEntity>
    fun deleteConversationOnly(conversationId: String)

    // Conversation Messages
    fun insertConversationMessage(message: ConversationMessageEntity)
    fun listConversationMessages(conversationId: String, limit: Int = 500): List<ConversationMessageEntity>
    fun listConversationMessagesPaged(conversationId: String, offset: Int, limit: Int): List<ConversationMessageEntity>

    // Many-to-many Task <-> Conversation links
    fun linkTaskToConversation(ref: TaskConversationCrossRef)
    fun unlinkTaskFromConversation(conversationId: String, taskId: String)
    fun listTasksForConversation(conversationId: String): List<TaskEntity>
    fun listConversationsForTask(taskId: String): List<ConversationEntity>
    // Tasks
    fun upsertTask(task: TaskEntity)
    fun getTask(taskId: String): TaskEntity?
    fun listTasksForProject(projectId: String): List<TaskEntity>
    fun listAllTasks(): List<TaskEntity>
    fun findStaleRunningTasks(staleThresholdMs: Long, nowMs: Long = System.currentTimeMillis()): List<TaskEntity>
    fun findRecoverableTasks(): List<TaskEntity>

    // Task Steps
    fun upsertStep(step: TaskStepEntity)
    fun getStep(stepId: String): TaskStepEntity?
    fun listStepsForTask(taskId: String): List<TaskStepEntity>
    fun findStaleRunningSteps(taskId: String): List<TaskStepEntity>

    // Agent Executions
    fun insertExecution(execution: AgentExecutionEntity)
    fun updateExecution(execution: AgentExecutionEntity)
    fun listExecutionsForTask(taskId: String): List<AgentExecutionEntity>

    // Checkpoints
    fun insertCheckpoint(checkpoint: CheckpointEntity)
    fun getLatestCheckpoint(taskId: String): CheckpointEntity?
    fun listCheckpointsForTask(taskId: String): List<CheckpointEntity>
    fun markCheckpointInvalid(checkpointId: String)

    // Agent Messages (structured inter-agent communication)
    fun insertAgentMessage(message: AgentMessageEntity)
    fun listAgentMessagesForTask(taskId: String, limit: Int = 200): List<AgentMessageEntity>
    fun listAgentMessagesPaged(taskId: String, offset: Int, limit: Int): List<AgentMessageEntity>

    // Task Events (durable audit log)
    fun insertTaskEvent(event: TaskEventEntity)
    fun listTaskEvents(taskId: String, limit: Int = 300): List<TaskEventEntity>
    fun listTaskEventsPaged(taskId: String, offset: Int, limit: Int): List<TaskEventEntity>

    // Migration Markers (idempotent legacy migration)
    fun isMigrationCompleted(markerKey: String): Boolean
    fun markMigrationCompleted(markerKey: String, details: String = "")

    // Idempotency Ledger
    fun recordIdempotency(record: IdempotencyRecordEntity)
    fun findIdempotency(idempotencyKey: String): IdempotencyRecordEntity?

    /**
     * Transaction-safe step completion + task progress update + optional checkpoint + event.
     */
    fun completeStepTransactionally(
        task: TaskEntity,
        step: TaskStepEntity,
        execution: AgentExecutionEntity,
        checkpoint: CheckpointEntity? = null,
        event: TaskEventEntity? = null,
        idempotency: IdempotencyRecordEntity? = null,
    ) {
        runInTransaction {
            upsertStep(step)
            updateExecution(execution)
            if (checkpoint != null) insertCheckpoint(checkpoint)
            if (idempotency != null) recordIdempotency(idempotency)
            upsertTask(task)
            if (event != null) insertTaskEvent(event)
        }
    }
}

/**
 * Thread-safe in-memory implementation of [OrqenTaskStore] supporting atomic rollback
 * on transaction failure, foreign key checks, and schema migration simulation (v1 -> v2).
 */
class InMemoryOrqenTaskStore(
    initialSchemaVersion: Int = CURRENT_SCHEMA_VERSION,
) : OrqenTaskStore {
    private var _schemaVersion: Int = initialSchemaVersion
    override val schemaVersion: Int get() = _schemaVersion

    private val lock = Any()
    private val projects = LinkedHashMap<String, ProjectEntity>()
    private val conversations = LinkedHashMap<String, ConversationEntity>()
    private val conversationMessages = mutableListOf<ConversationMessageEntity>()
    private val tasks = LinkedHashMap<String, TaskEntity>()
    private val steps = LinkedHashMap<String, TaskStepEntity>()
    private val executions = LinkedHashMap<String, AgentExecutionEntity>()
    private val checkpoints = mutableListOf<CheckpointEntity>()
    private val agentMessages = mutableListOf<AgentMessageEntity>()
    private val taskEvents = mutableListOf<TaskEventEntity>()
    private val idempotencyRecords = LinkedHashMap<String, IdempotencyRecordEntity>()
    private val taskConversationLinks = LinkedHashSet<TaskConversationCrossRef>()
    private val migrationMarkers = LinkedHashMap<String, String>()

    fun migrateToLatest(): Int = synchronized(lock) {
        if (_schemaVersion < 2) {
            tasks.keys.toList().forEach { id ->
                val t = tasks[id] ?: return@forEach
                tasks[id] = t.copy(
                    executionLeaseOwner = t.executionLeaseOwner,
                    lastHeartbeatAt = if (t.lastHeartbeatAt == 0L) t.updatedAt else t.lastHeartbeatAt,
                )
            }
            _schemaVersion = 2
        }
        if (_schemaVersion < 3) {
            // v2 -> v3 preserves all existing data and populates cross-refs from ConversationEntity.linkedTaskIds
            conversations.values.forEach { conv ->
                conv.linkedTaskIds.forEach { tid ->
                    if (tasks.containsKey(tid)) {
                        taskConversationLinks.add(TaskConversationCrossRef(taskId = tid, conversationId = conv.conversationId))
                    }
                }
            }
            _schemaVersion = 3
        }
        if (_schemaVersion < 4) {
            // v3 -> v4 adds collaboration tables non-destructively while preserving all tasks/executions
            _schemaVersion = 4
        }
        _schemaVersion
    }

    override fun <T> runInTransaction(block: () -> T): T = synchronized(lock) {
        val snapProjects = LinkedHashMap(projects)
        val snapConvs = LinkedHashMap(conversations)
        val snapConvMsgs = ArrayList(conversationMessages)
        val snapTasks = LinkedHashMap(tasks)
        val snapSteps = LinkedHashMap(steps)
        val snapExecs = LinkedHashMap(executions)
        val snapCheckpoints = ArrayList(checkpoints)
        val snapAgentMsgs = ArrayList(agentMessages)
        val snapEvents = ArrayList(taskEvents)
        val snapIdem = LinkedHashMap(idempotencyRecords)
        try {
            block()
        } catch (t: Throwable) {
            projects.clear(); projects.putAll(snapProjects)
            conversations.clear(); conversations.putAll(snapConvs)
            conversationMessages.clear(); conversationMessages.addAll(snapConvMsgs)
            tasks.clear(); tasks.putAll(snapTasks)
            steps.clear(); steps.putAll(snapSteps)
            executions.clear(); executions.putAll(snapExecs)
            checkpoints.clear(); checkpoints.addAll(snapCheckpoints)
            agentMessages.clear(); agentMessages.addAll(snapAgentMsgs)
            taskEvents.clear(); taskEvents.addAll(snapEvents)
            idempotencyRecords.clear(); idempotencyRecords.putAll(snapIdem)
            throw t
        }
    }

    override fun validateIntegrity(): Boolean = synchronized(lock) {
        tasks.values.all { projects.containsKey(it.projectId) } &&
            steps.values.all { tasks.containsKey(it.taskId) } &&
            conversations.values.all { projects.containsKey(it.projectId) }
    }

    override fun upsertProject(project: ProjectEntity) = synchronized(lock) {
        projects[project.projectId] = project
    }

    override fun getProject(projectId: String): ProjectEntity? = synchronized(lock) { projects[projectId] }

    override fun listProjects(): List<ProjectEntity> = synchronized(lock) {
        projects.values.sortedByDescending { it.updatedAt }
    }

    override fun upsertConversation(conversation: ConversationEntity) = synchronized(lock) {
        require(projects.containsKey(conversation.projectId)) { "Foreign key violation: project ${conversation.projectId} not found" }
        conversations[conversation.conversationId] = conversation
    }

    override fun getConversation(conversationId: String): ConversationEntity? = synchronized(lock) {
        conversations[conversationId]
    }

    override fun listConversationsForProject(projectId: String, includeArchived: Boolean): List<ConversationEntity> =
        synchronized(lock) {
            conversations.values
                .filter { it.projectId == projectId && (includeArchived || !it.archived) }
                .sortedByDescending { it.updatedAt }
        }

    override fun deleteConversationOnly(conversationId: String) {
        synchronized(lock) {
            conversations.remove(conversationId)
            conversationMessages.removeAll { it.conversationId == conversationId }
            taskConversationLinks.removeAll { it.conversationId == conversationId }
            // Intentionally NEVER deletes tasks or projects!
        }
    }

    override fun linkTaskToConversation(ref: TaskConversationCrossRef) {
        synchronized(lock) {
            require(tasks.containsKey(ref.taskId)) { "Foreign key violation: task ${ref.taskId} not found" }
            require(conversations.containsKey(ref.conversationId)) { "Foreign key violation: conversation ${ref.conversationId} not found" }
            taskConversationLinks.removeAll { it.taskId == ref.taskId && it.conversationId == ref.conversationId }
            taskConversationLinks.add(ref)
        }
    }

    override fun unlinkTaskFromConversation(conversationId: String, taskId: String) {
        synchronized(lock) {
            taskConversationLinks.removeAll { it.conversationId == conversationId && it.taskId == taskId }
        }
    }

    override fun listTasksForConversation(conversationId: String): List<TaskEntity> = synchronized(lock) {
        val ids = taskConversationLinks.filter { it.conversationId == conversationId }.map { it.taskId }.toSet()
        tasks.values.filter { it.taskId in ids }.sortedByDescending { it.updatedAt }
    }

    override fun listConversationsForTask(taskId: String): List<ConversationEntity> = synchronized(lock) {
        val ids = taskConversationLinks.filter { it.taskId == taskId }.map { it.conversationId }.toSet()
        conversations.values.filter { it.conversationId in ids }.sortedByDescending { it.updatedAt }
    }

    override fun insertConversationMessage(message: ConversationMessageEntity) {
        synchronized(lock) {
            conversationMessages.add(message.copy(content = SecretSanitizer.sanitize(message.content)))
        }
    }

    override fun listConversationMessages(conversationId: String, limit: Int): List<ConversationMessageEntity> =
        synchronized(lock) {
            conversationMessages.filter { it.conversationId == conversationId }.takeLast(limit)
        }

    override fun listConversationMessagesPaged(conversationId: String, offset: Int, limit: Int): List<ConversationMessageEntity> =
        synchronized(lock) {
            conversationMessages
                .filter { it.conversationId == conversationId }
                .drop(offset.coerceAtLeast(0))
                .take(limit.coerceAtLeast(0))
        }

    override fun upsertTask(task: TaskEntity) = synchronized(lock) {
        require(projects.containsKey(task.projectId)) { "Foreign key violation: project ${task.projectId} not found" }
        tasks[task.taskId] = task
    }

    override fun getTask(taskId: String): TaskEntity? = synchronized(lock) { tasks[taskId] }

    override fun listTasksForProject(projectId: String): List<TaskEntity> = synchronized(lock) {
        tasks.values.filter { it.projectId == projectId }.sortedByDescending { it.updatedAt }
    }

    override fun listAllTasks(): List<TaskEntity> = synchronized(lock) {
        tasks.values.sortedByDescending { it.updatedAt }
    }

    override fun findStaleRunningTasks(staleThresholdMs: Long, nowMs: Long): List<TaskEntity> = synchronized(lock) {
        tasks.values.filter {
            it.status == PersistentTaskStatus.RUNNING &&
                !it.ownerStopRequested &&
                (nowMs - it.lastHeartbeatAt) >= staleThresholdMs
        }
    }

    override fun findRecoverableTasks(): List<TaskEntity> = synchronized(lock) {
        tasks.values.filter {
            !it.ownerStopRequested &&
                !it.pauseRequested &&
                (it.status == PersistentTaskStatus.RUNNING || it.status == PersistentTaskStatus.QUEUED)
        }
    }

    override fun upsertStep(step: TaskStepEntity) = synchronized(lock) {
        require(tasks.containsKey(step.taskId)) { "Foreign key violation: task ${step.taskId} not found" }
        steps[step.stepId] = step
    }

    override fun getStep(stepId: String): TaskStepEntity? = synchronized(lock) { steps[stepId] }

    override fun listStepsForTask(taskId: String): List<TaskStepEntity> = synchronized(lock) {
        steps.values.filter { it.taskId == taskId }.sortedBy { it.sequenceNumber }
    }

    override fun findStaleRunningSteps(taskId: String): List<TaskStepEntity> = synchronized(lock) {
        steps.values.filter { it.taskId == taskId && it.status == StepStatus.RUNNING }
    }

    override fun insertExecution(execution: AgentExecutionEntity) = synchronized(lock) {
        executions[execution.executionId] = execution
    }

    override fun updateExecution(execution: AgentExecutionEntity) = synchronized(lock) {
        executions[execution.executionId] = execution
    }

    override fun listExecutionsForTask(taskId: String): List<AgentExecutionEntity> = synchronized(lock) {
        executions.values.filter { it.taskId == taskId }.sortedBy { it.startedAt }
    }

    override fun insertCheckpoint(checkpoint: CheckpointEntity) {
        synchronized(lock) {
            checkpoints.add(checkpoint)
        }
    }

    override fun getLatestCheckpoint(taskId: String): CheckpointEntity? = synchronized(lock) {
        checkpoints.filter { it.taskId == taskId }.maxByOrNull { it.timestamp }
    }

    override fun listCheckpointsForTask(taskId: String): List<CheckpointEntity> = synchronized(lock) {
        checkpoints.filter { it.taskId == taskId }.sortedByDescending { it.timestamp }
    }

    override fun markCheckpointInvalid(checkpointId: String) {
        synchronized(lock) {
            val idx = checkpoints.indexOfFirst { it.checkpointId == checkpointId }
            if (idx >= 0) {
                checkpoints[idx] = checkpoints[idx].copy(isValid = false)
            }
        }
    }

    override fun insertAgentMessage(message: AgentMessageEntity) {
        synchronized(lock) {
            require(tasks.containsKey(message.taskId)) { "Foreign key violation: task ${message.taskId} not found" }
            agentMessages.add(message.copy(content = SecretSanitizer.sanitize(message.content)))
        }
    }

    override fun listAgentMessagesForTask(taskId: String, limit: Int): List<AgentMessageEntity> = synchronized(lock) {
        agentMessages.filter { it.taskId == taskId }.takeLast(limit)
    }

    override fun listAgentMessagesPaged(taskId: String, offset: Int, limit: Int): List<AgentMessageEntity> = synchronized(lock) {
        agentMessages.filter { it.taskId == taskId }.drop(offset.coerceAtLeast(0)).take(limit.coerceAtLeast(0))
    }

    override fun insertTaskEvent(event: TaskEventEntity) {
        synchronized(lock) {
            require(tasks.containsKey(event.taskId)) { "Foreign key violation: task ${event.taskId} not found" }
            val nextSeq = if (event.sequenceNumber > 0L) {
                event.sequenceNumber
            } else {
                (taskEvents.filter { it.taskId == event.taskId }.maxOfOrNull { it.sequenceNumber } ?: 0L) + 1L
            }
            taskEvents.add(
                event.copy(
                    sequenceNumber = nextSeq,
                    eventPayload = SecretSanitizer.sanitize(event.eventPayload),
                ),
            )
        }
    }

    override fun listTaskEvents(taskId: String, limit: Int): List<TaskEventEntity> = synchronized(lock) {
        taskEvents.filter { it.taskId == taskId }
            .sortedWith(compareBy<TaskEventEntity> { it.sequenceNumber }.thenBy { it.timestamp })
            .takeLast(limit)
    }

    override fun listTaskEventsPaged(taskId: String, offset: Int, limit: Int): List<TaskEventEntity> = synchronized(lock) {
        taskEvents.filter { it.taskId == taskId }
            .sortedWith(compareBy<TaskEventEntity> { it.sequenceNumber }.thenBy { it.timestamp })
            .drop(offset.coerceAtLeast(0))
            .take(limit.coerceAtLeast(0))
    }

    override fun isMigrationCompleted(markerKey: String): Boolean = synchronized(lock) {
        migrationMarkers.containsKey(markerKey)
    }

    override fun markMigrationCompleted(markerKey: String, details: String) {
        synchronized(lock) {
            migrationMarkers[markerKey] = details
        }
    }

    override fun recordIdempotency(record: IdempotencyRecordEntity) = synchronized(lock) {
        idempotencyRecords[record.idempotencyKey] = record
    }

    override fun findIdempotency(idempotencyKey: String): IdempotencyRecordEntity? = synchronized(lock) {
        idempotencyRecords[idempotencyKey]
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 4
    }
}
