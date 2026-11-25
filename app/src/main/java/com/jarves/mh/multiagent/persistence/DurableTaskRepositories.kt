package com.jarves.mh.multiagent.persistence

import com.jarves.mh.multiagent.tasks.TaskStatus
import com.jarves.mh.multiagent.tasks.TeamTask
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * High-level transactional repository facade for Phase 1 Durable Task Foundation.
 * Enforces:
 * - Valid status transitions ([TaskStatusTransitionValidator])
 * - Secret sanitization ([SecretSanitizer])
 * - Workspace path safety ([WorkspacePathValidator])
 * - Atomic state + event writes inside [OrqenTaskStore.runInTransaction]
 */
class OrqenPersistenceFacade(
    val store: OrqenTaskStore,
) {
    fun createProject(projectId: String, name: String, workspacePath: String, memory: String = ""): ProjectEntity {
        val entity = ProjectEntity(
            projectId = projectId,
            projectName = name,
            workspacePath = workspacePath,
            projectMemory = SecretSanitizer.sanitize(memory),
        )
        store.upsertProject(entity)
        return entity
    }

    fun createConversation(projectId: String, title: String): ConversationEntity {
        val conv = ConversationEntity(projectId = projectId, title = title)
        store.upsertConversation(conv)
        return conv
    }

    fun createTask(
        projectId: String,
        title: String,
        description: String,
        priority: Int = 1,
        taskId: String? = null,
    ): TaskEntity {
        val task = TaskEntity(
            taskId = taskId ?: TaskEntity(projectId = projectId, title = title, description = description).taskId,
            projectId = projectId,
            title = SecretSanitizer.sanitize(title),
            description = SecretSanitizer.sanitize(description),
            status = PersistentTaskStatus.QUEUED,
            priority = priority,
        )
        store.runInTransaction {
            store.upsertTask(task)
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = task.taskId,
                    eventType = "TASK_CREATED",
                    eventPayload = """{"title":"${task.title.replace("\"", "'")}"}""",
                ),
            )
        }
        return task
    }

    fun linkTaskToConversation(taskId: String, conversationId: String, linkedBy: String = "owner") {
        store.linkTaskToConversation(
            TaskConversationCrossRef(
                taskId = taskId,
                conversationId = conversationId,
                linkedBy = linkedBy,
            ),
        )
    }

    fun createTaskWithInitialSteps(
        projectId: String,
        title: String,
        description: String,
        steps: List<TaskStepEntity>,
    ): TaskEntity {
        val task = TaskEntity(
            projectId = projectId,
            title = SecretSanitizer.sanitize(title),
            description = SecretSanitizer.sanitize(description),
            status = PersistentTaskStatus.QUEUED,
        )
        store.runInTransaction {
            store.upsertTask(task)
            steps.forEachIndexed { idx, s ->
                store.upsertStep(
                    s.copy(
                        taskId = task.taskId,
                        sequenceNumber = if (s.sequenceNumber > 0) s.sequenceNumber else idx + 1,
                    ),
                )
            }
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = task.taskId,
                    eventType = "TASK_CREATED",
                    eventPayload = """{"steps":${steps.size}}""",
                ),
            )
        }
        return task
    }

    fun updateTaskStatus(
        taskId: String,
        newStatus: PersistentTaskStatus,
        reason: String = "",
        explicitUserReopen: Boolean = false,
    ): TaskEntity {
        val current = requireNotNull(store.getTask(taskId)) { "Task not found: $taskId" }
        require(TaskStatusTransitionValidator.isValidTransition(current.status, newStatus, explicitUserReopen)) {
            "Invalid task status transition: ${current.status} -> $newStatus (explicitUserReopen=$explicitUserReopen)"
        }
        val updated = current.copy(
            status = newStatus,
            startedAt = if (newStatus == PersistentTaskStatus.RUNNING && current.startedAt == null) System.currentTimeMillis() else current.startedAt,
            completedAt = if (newStatus == PersistentTaskStatus.COMPLETED) System.currentTimeMillis() else current.completedAt,
            updatedAt = System.currentTimeMillis(),
        )
        store.runInTransaction {
            store.upsertTask(updated)
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = taskId,
                    eventType = "TASK_STATUS_CHANGED",
                    eventPayload = """{"from":"${current.status}","to":"$newStatus","reason":"${SecretSanitizer.sanitize(reason)}"}""",
                ),
            )
        }
        return updated
    }

    fun requestTaskPause(taskId: String, reason: String = "Owner requested pause"): TaskEntity {
        val current = requireNotNull(store.getTask(taskId)) { "Task not found: $taskId" }
        val updated = current.copy(
            pauseRequested = true,
            status = if (current.status == PersistentTaskStatus.RUNNING) PersistentTaskStatus.PAUSE_REQUESTED else PersistentTaskStatus.PAUSED,
            updatedAt = System.currentTimeMillis(),
        )
        store.runInTransaction {
            store.upsertTask(updated)
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = taskId,
                    eventType = "TASK_PAUSE_REQUESTED",
                    eventPayload = SecretSanitizer.sanitize(reason),
                ),
            )
        }
        return updated
    }

    fun requestTaskStop(taskId: String, reason: String = "Owner requested stop"): TaskEntity {
        val current = requireNotNull(store.getTask(taskId)) { "Task not found: $taskId" }
        val updated = current.copy(
            ownerStopRequested = true,
            status = PersistentTaskStatus.CANCELLED,
            updatedAt = System.currentTimeMillis(),
        )
        store.runInTransaction {
            store.upsertTask(updated)
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = taskId,
                    eventType = "TASK_STOP_REQUESTED",
                    eventPayload = SecretSanitizer.sanitize(reason),
                ),
            )
        }
        return updated
    }

    fun createTaskStep(step: TaskStepEntity): TaskStepEntity {
        val existingSeqs = store.listStepsForTask(step.taskId).map { it.sequenceNumber }.toSet()
        require(step.sequenceNumber !in existingSeqs) {
            "Duplicate step sequenceNumber ${step.sequenceNumber} for task ${step.taskId}"
        }
        store.runInTransaction {
            store.upsertStep(step)
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = step.taskId,
                    stepId = step.stepId,
                    eventType = "STEP_CREATED",
                    eventPayload = """{"sequence":${step.sequenceNumber},"title":"${SecretSanitizer.sanitize(step.title)}"}""",
                ),
            )
        }
        return step
    }

    fun startTaskStep(taskId: String, stepId: String): TaskStepEntity {
        val step = requireNotNull(store.getStep(stepId)) { "Step not found: $stepId" }
        require(step.taskId == taskId) { "Step $stepId does not belong to task $taskId" }
        val started = step.copy(status = StepStatus.RUNNING, startedAt = step.startedAt ?: System.currentTimeMillis())
        val task = requireNotNull(store.getTask(taskId)) { "Task not found: $taskId" }
        store.runInTransaction {
            store.upsertStep(started)
            store.upsertTask(task.copy(currentStepId = stepId, updatedAt = System.currentTimeMillis()))
            store.insertTaskEvent(
                TaskEventEntity(taskId = taskId, stepId = stepId, eventType = "STEP_STARTED", eventPayload = step.title),
            )
        }
        return started
    }

    /**
     * Atomically completes a single step.
     * Intentionally does NOT mark the parent task COMPLETED merely because one step completed.
     */
    fun completeTaskStep(taskId: String, stepId: String, outputSummary: String): TaskStepEntity {
        val step = requireNotNull(store.getStep(stepId)) { "Step not found: $stepId" }
        require(step.taskId == taskId) { "Step $stepId does not belong to task $taskId" }
        require(step.status == StepStatus.RUNNING || step.status == StepStatus.READY) {
            "Step $stepId is not eligible for completion (status=${step.status})"
        }
        val task = requireNotNull(store.getTask(taskId)) { "Task not found: $taskId" }
        val completed = step.copy(
            status = StepStatus.COMPLETED,
            outputSummary = SecretSanitizer.sanitize(outputSummary),
            completedAt = System.currentTimeMillis(),
        )
        store.runInTransaction {
            store.upsertStep(completed)
            store.upsertTask(task.copy(currentStepId = null, updatedAt = System.currentTimeMillis()))
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = taskId,
                    stepId = stepId,
                    eventType = "STEP_COMPLETED",
                    eventPayload = SecretSanitizer.sanitize(outputSummary),
                ),
            )
        }
        return completed
    }

    fun failTaskStep(taskId: String, stepId: String, error: String): TaskStepEntity {
        val step = requireNotNull(store.getStep(stepId)) { "Step not found: $stepId" }
        require(step.taskId == taskId) { "Step $stepId does not belong to task $taskId" }
        val safeError = SecretSanitizer.sanitize(error)
        val failed = step.copy(
            status = StepStatus.FAILED,
            outputSummary = safeError,
            retryCount = step.retryCount + 1,
        )
        store.runInTransaction {
            store.upsertStep(failed)
            store.insertTaskEvent(
                TaskEventEntity(taskId = taskId, stepId = stepId, eventType = "STEP_FAILED", eventPayload = safeError),
            )
        }
        return failed
    }

    fun createCheckpoint(
        taskId: String,
        stepId: String,
        permittedRoot: File,
        workspacePath: String,
        modifiedFiles: List<String>,
        contextSummary: String,
        gitCommitSha: String? = null,
    ): CheckpointEntity {
        require(WorkspacePathValidator.isPathInsideRoot(permittedRoot, workspacePath)) {
            "Checkpoint workspacePath escapes permitted root: $workspacePath"
        }
        modifiedFiles.forEach { rel ->
            require(WorkspacePathValidator.isPathInsideRoot(File(workspacePath), rel)) {
                "Checkpoint modified file escapes workspace: $rel"
            }
        }
        val checkpoint = CheckpointEntity(
            taskId = taskId,
            stepId = stepId,
            workspacePath = File(workspacePath).canonicalPath,
            modifiedFiles = modifiedFiles,
            gitCommitSha = gitCommitSha,
            contextSummary = SecretSanitizer.sanitize(contextSummary),
        )
        store.runInTransaction {
            store.insertCheckpoint(checkpoint)
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = taskId,
                    stepId = stepId,
                    eventType = "CHECKPOINT_CREATED",
                    eventPayload = """{"checkpointId":"${checkpoint.checkpointId}"}""",
                ),
            )
        }
        return checkpoint
    }

    fun validateCheckpointReference(checkpoint: CheckpointEntity, permittedRoot: File): Boolean {
        if (!checkpoint.isValid) return false
        val path = checkpoint.workspacePath ?: return false
        if (!WorkspacePathValidator.isPathInsideRoot(permittedRoot, path)) return false
        return File(path).isDirectory
    }

    fun markCheckpointInvalid(checkpointId: String) = store.markCheckpointInvalid(checkpointId)

    fun recordAgentExecution(execution: AgentExecutionEntity) {
        store.insertExecution(
            execution.copy(errorMessage = execution.errorMessage?.let { SecretSanitizer.sanitize(it) }),
        )
    }

    fun recordAgentMessage(message: AgentMessageEntity) {
        store.insertAgentMessage(message.copy(content = SecretSanitizer.sanitize(message.content)))
    }

    fun appendTaskEvent(taskId: String, eventType: String, payload: String, stepId: String? = null) {
        store.insertTaskEvent(
            TaskEventEntity(
                taskId = taskId,
                stepId = stepId,
                eventType = eventType,
                eventPayload = SecretSanitizer.sanitize(payload),
            ),
        )
    }
}

data class LegacyMigrationResult(
    val markerKey: String,
    val alreadyMigrated: Boolean,
    val importedProjects: Int,
    val importedTasks: Int,
    val verified: Boolean,
)

/**
 * Idempotent, verified migration of legacy in-memory / JSON tasks into [OrqenTaskStore].
 * Preserves original data until verification succeeds and never duplicates records on re-run.
 */
class LegacyDataMigrator(
    private val store: OrqenTaskStore,
) {
    fun migrateLegacyTasks(
        markerKey: String,
        projectId: String,
        projectName: String,
        workspacePath: String,
        legacyTasks: List<TeamTask>,
    ): LegacyMigrationResult {
        if (store.isMigrationCompleted(markerKey)) {
            return LegacyMigrationResult(
                markerKey = markerKey,
                alreadyMigrated = true,
                importedProjects = 0,
                importedTasks = 0,
                verified = true,
            )
        }

        var importedProjects = 0
        var importedTasks = 0
        store.runInTransaction {
            if (store.getProject(projectId) == null) {
                store.upsertProject(
                    ProjectEntity(
                        projectId = projectId,
                        projectName = projectName,
                        workspacePath = workspacePath,
                    ),
                )
                importedProjects++
            }
            legacyTasks.forEach { legacy ->
                if (store.getTask(legacy.id) == null) {
                    store.upsertTask(
                        TaskEntity(
                            taskId = legacy.id,
                            projectId = projectId,
                            title = legacy.title,
                            description = legacy.description,
                            status = legacy.status.toPersistentStatus(),
                            priority = legacy.priority,
                            startedAt = legacy.startTimestamp,
                            completedAt = legacy.completionTimestamp,
                        ),
                    )
                    importedTasks++
                }
            }
            val verified = legacyTasks.all { store.getTask(it.id) != null }
            check(verified) { "Legacy migration verification failed for $markerKey" }
            store.markMigrationCompleted(markerKey, "importedProjects=$importedProjects,importedTasks=$importedTasks")
        }

        return LegacyMigrationResult(
            markerKey = markerKey,
            alreadyMigrated = false,
            importedProjects = importedProjects,
            importedTasks = importedTasks,
            verified = true,
        )
    }

    private fun TaskStatus.toPersistentStatus(): PersistentTaskStatus = when (this) {
        TaskStatus.BACKLOG -> PersistentTaskStatus.DRAFT
        TaskStatus.PLANNED -> PersistentTaskStatus.PLANNING
        TaskStatus.READY -> PersistentTaskStatus.READY
        TaskStatus.IN_PROGRESS, TaskStatus.WAITING_FOR_REVIEW, TaskStatus.TESTING -> PersistentTaskStatus.RUNNING
        TaskStatus.BLOCKED -> PersistentTaskStatus.BLOCKED
        TaskStatus.COMPLETED -> PersistentTaskStatus.COMPLETED
        TaskStatus.FAILED -> PersistentTaskStatus.FAILED
        TaskStatus.CANCELLED -> PersistentTaskStatus.CANCELLED
    }
}

/**
 * Disk-backed durable [OrqenTaskStore] for pure JVM lifecycle tests (close & reopen verification).
 * Persists all entities atomically to a JSON snapshot file on every write/transaction commit.
 */
class FileBackedOrqenTaskStore(
    private val storageFile: File,
    initialSchemaVersion: Int = InMemoryOrqenTaskStore.CURRENT_SCHEMA_VERSION,
) : OrqenTaskStore {
    private val delegate = InMemoryOrqenTaskStore(initialSchemaVersion)

    init {
        if (storageFile.isFile && storageFile.length() > 0L) {
            loadFromDisk()
        }
    }

    override val schemaVersion: Int get() = delegate.schemaVersion

    override fun <T> runInTransaction(block: () -> T): T {
        val result = delegate.runInTransaction(block)
        flushToDisk()
        return result
    }

    override fun validateIntegrity(): Boolean = delegate.validateIntegrity()

    override fun upsertProject(project: ProjectEntity) { delegate.upsertProject(project); flushToDisk() }
    override fun getProject(projectId: String) = delegate.getProject(projectId)
    override fun listProjects() = delegate.listProjects()

    override fun upsertConversation(conversation: ConversationEntity) { delegate.upsertConversation(conversation); flushToDisk() }
    override fun getConversation(conversationId: String) = delegate.getConversation(conversationId)
    override fun listConversationsForProject(projectId: String, includeArchived: Boolean) = delegate.listConversationsForProject(projectId, includeArchived)
    override fun deleteConversationOnly(conversationId: String) { delegate.deleteConversationOnly(conversationId); flushToDisk() }

    override fun insertConversationMessage(message: ConversationMessageEntity) { delegate.insertConversationMessage(message); flushToDisk() }
    override fun listConversationMessages(conversationId: String, limit: Int) = delegate.listConversationMessages(conversationId, limit)
    override fun listConversationMessagesPaged(conversationId: String, offset: Int, limit: Int) = delegate.listConversationMessagesPaged(conversationId, offset, limit)

    override fun upsertTask(task: TaskEntity) { delegate.upsertTask(task); flushToDisk() }
    override fun getTask(taskId: String) = delegate.getTask(taskId)
    override fun listTasksForProject(projectId: String) = delegate.listTasksForProject(projectId)
    override fun listAllTasks() = delegate.listAllTasks()
    override fun findStaleRunningTasks(staleThresholdMs: Long, nowMs: Long) = delegate.findStaleRunningTasks(staleThresholdMs, nowMs)
    override fun findRecoverableTasks() = delegate.findRecoverableTasks()

    override fun upsertStep(step: TaskStepEntity) { delegate.upsertStep(step); flushToDisk() }
    override fun getStep(stepId: String) = delegate.getStep(stepId)
    override fun listStepsForTask(taskId: String) = delegate.listStepsForTask(taskId)
    override fun findStaleRunningSteps(taskId: String) = delegate.findStaleRunningSteps(taskId)

    override fun insertExecution(execution: AgentExecutionEntity) { delegate.insertExecution(execution); flushToDisk() }
    override fun updateExecution(execution: AgentExecutionEntity) { delegate.updateExecution(execution); flushToDisk() }
    override fun listExecutionsForTask(taskId: String) = delegate.listExecutionsForTask(taskId)

    override fun linkTaskToConversation(ref: TaskConversationCrossRef) { delegate.linkTaskToConversation(ref); flushToDisk() }
    override fun unlinkTaskFromConversation(conversationId: String, taskId: String) { delegate.unlinkTaskFromConversation(conversationId, taskId); flushToDisk() }
    override fun listTasksForConversation(conversationId: String) = delegate.listTasksForConversation(conversationId)
    override fun listConversationsForTask(taskId: String) = delegate.listConversationsForTask(taskId)

    override fun insertCheckpoint(checkpoint: CheckpointEntity) { delegate.insertCheckpoint(checkpoint); flushToDisk() }
    override fun getLatestCheckpoint(taskId: String) = delegate.getLatestCheckpoint(taskId)
    override fun listCheckpointsForTask(taskId: String) = delegate.listCheckpointsForTask(taskId)
    override fun markCheckpointInvalid(checkpointId: String) { delegate.markCheckpointInvalid(checkpointId); flushToDisk() }

    override fun insertAgentMessage(message: AgentMessageEntity) { delegate.insertAgentMessage(message); flushToDisk() }
    override fun listAgentMessagesForTask(taskId: String, limit: Int) = delegate.listAgentMessagesForTask(taskId, limit)
    override fun listAgentMessagesPaged(taskId: String, offset: Int, limit: Int) = delegate.listAgentMessagesPaged(taskId, offset, limit)

    override fun insertTaskEvent(event: TaskEventEntity) { delegate.insertTaskEvent(event); flushToDisk() }
    override fun listTaskEvents(taskId: String, limit: Int) = delegate.listTaskEvents(taskId, limit)
    override fun listTaskEventsPaged(taskId: String, offset: Int, limit: Int) = delegate.listTaskEventsPaged(taskId, offset, limit)

    override fun isMigrationCompleted(markerKey: String) = delegate.isMigrationCompleted(markerKey)
    override fun markMigrationCompleted(markerKey: String, details: String) { delegate.markMigrationCompleted(markerKey, details); flushToDisk() }

    override fun recordIdempotency(record: IdempotencyRecordEntity) { delegate.recordIdempotency(record); flushToDisk() }
    override fun findIdempotency(idempotencyKey: String) = delegate.findIdempotency(idempotencyKey)

    private fun flushToDisk() {
        val root = JSONObject()
        root.put("schemaVersion", delegate.schemaVersion)
        val projArr = JSONArray()
        delegate.listProjects().forEach { p ->
            projArr.put(
                JSONObject()
                    .put("projectId", p.projectId)
                    .put("projectName", p.projectName)
                    .put("workspacePath", p.workspacePath)
                    .put("projectMemory", p.projectMemory)
                    .put("createdAt", p.createdAt)
                    .put("updatedAt", p.updatedAt),
            )
        }
        root.put("projects", projArr)

        val convArr = JSONArray()
        val convMsgArr = JSONArray()
        val linkArr = JSONArray()
        delegate.listProjects().forEach { p ->
            delegate.listConversationsForProject(p.projectId, includeArchived = true).forEach { c ->
                convArr.put(
                    JSONObject()
                        .put("conversationId", c.conversationId)
                        .put("projectId", c.projectId)
                        .put("title", c.title)
                        .put("archived", c.archived)
                        .put("contextSummary", c.contextSummary)
                        .put("createdAt", c.createdAt)
                        .put("updatedAt", c.updatedAt),
                )
                delegate.listConversationMessages(c.conversationId, 1_000).forEach { cm ->
                    convMsgArr.put(
                        JSONObject()
                            .put("messageId", cm.messageId)
                            .put("conversationId", cm.conversationId)
                            .put("projectId", cm.projectId)
                            .put("senderId", cm.senderId)
                            .put("senderName", cm.senderName)
                            .put("isUser", cm.isUser)
                            .put("content", cm.content)
                            .put("relatedTaskId", cm.relatedTaskId ?: "")
                            .put("timestamp", cm.timestamp),
                    )
                }
                delegate.listTasksForConversation(c.conversationId).forEach { t ->
                    linkArr.put(
                        JSONObject()
                            .put("taskId", t.taskId)
                            .put("conversationId", c.conversationId),
                    )
                }
            }
        }
        root.put("conversations", convArr)
        root.put("conversationMessages", convMsgArr)
        root.put("taskConversationLinks", linkArr)

        val taskArr = JSONArray()
        val msgArr = JSONArray()
        val chkArr = JSONArray()
        val evtArr = JSONArray()
        delegate.listAllTasks().forEach { t ->
            taskArr.put(
                JSONObject()
                    .put("taskId", t.taskId)
                    .put("projectId", t.projectId)
                    .put("title", t.title)
                    .put("description", t.description)
                    .put("status", t.status.name)
                    .put("pauseRequested", t.pauseRequested)
                    .put("ownerStopRequested", t.ownerStopRequested)
                    .put("createdAt", t.createdAt)
                    .put("updatedAt", t.updatedAt),
            )
            delegate.listAgentMessagesForTask(t.taskId, 500).forEach { m ->
                msgArr.put(
                    JSONObject()
                        .put("messageId", m.messageId)
                        .put("taskId", m.taskId)
                        .put("fromAgentId", m.fromAgentId)
                        .put("toAgentId", m.toAgentId)
                        .put("messageType", m.messageType.name)
                        .put("content", m.content)
                        .put("timestamp", m.timestamp),
                )
            }
            delegate.listCheckpointsForTask(t.taskId).forEach { c ->
                chkArr.put(
                    JSONObject()
                        .put("checkpointId", c.checkpointId)
                        .put("taskId", c.taskId)
                        .put("stepId", c.stepId)
                        .put("workspacePath", c.workspacePath ?: "")
                        .put("contextSummary", c.contextSummary)
                        .put("isValid", c.isValid)
                        .put("timestamp", c.timestamp),
                )
            }
            delegate.listTaskEvents(t.taskId, 500).forEach { e ->
                evtArr.put(
                    JSONObject()
                        .put("eventId", e.eventId)
                        .put("taskId", e.taskId)
                        .put("eventType", e.eventType)
                        .put("eventPayload", e.eventPayload)
                        .put("sequenceNumber", e.sequenceNumber)
                        .put("timestamp", e.timestamp),
                )
            }
        }
        root.put("tasks", taskArr)
        root.put("agentMessages", msgArr)
        root.put("checkpoints", chkArr)
        root.put("taskEvents", evtArr)

        storageFile.parentFile?.mkdirs()
        storageFile.writeText(root.toString())
    }

    private fun loadFromDisk() {
        val root = JSONObject(storageFile.readText())
        val projArr = root.optJSONArray("projects") ?: JSONArray()
        for (i in 0 until projArr.length()) {
            val o = projArr.getJSONObject(i)
            delegate.upsertProject(
                ProjectEntity(
                    projectId = o.getString("projectId"),
                    projectName = o.getString("projectName"),
                    workspacePath = o.getString("workspacePath"),
                    projectMemory = o.optString("projectMemory", ""),
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                ),
            )
        }
        val convArr = root.optJSONArray("conversations") ?: JSONArray()
        for (i in 0 until convArr.length()) {
            val o = convArr.getJSONObject(i)
            delegate.upsertConversation(
                ConversationEntity(
                    conversationId = o.getString("conversationId"),
                    projectId = o.getString("projectId"),
                    title = o.getString("title"),
                    archived = o.optBoolean("archived", false),
                    contextSummary = o.optString("contextSummary", ""),
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                ),
            )
        }
        val taskArr = root.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until taskArr.length()) {
            val o = taskArr.getJSONObject(i)
            delegate.upsertTask(
                TaskEntity(
                    taskId = o.getString("taskId"),
                    projectId = o.getString("projectId"),
                    title = o.getString("title"),
                    description = o.getString("description"),
                    status = PersistentTaskStatus.valueOf(o.getString("status")),
                    pauseRequested = o.optBoolean("pauseRequested", false),
                    ownerStopRequested = o.optBoolean("ownerStopRequested", false),
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                ),
            )
        }
        val msgArr = root.optJSONArray("agentMessages") ?: JSONArray()
        for (i in 0 until msgArr.length()) {
            val o = msgArr.getJSONObject(i)
            delegate.insertAgentMessage(
                AgentMessageEntity(
                    messageId = o.getString("messageId"),
                    taskId = o.getString("taskId"),
                    fromAgentId = o.getString("fromAgentId"),
                    toAgentId = o.getString("toAgentId"),
                    messageType = AgentMessageKind.valueOf(o.getString("messageType")),
                    content = o.getString("content"),
                    timestamp = o.optLong("timestamp"),
                ),
            )
        }
        val chkArr = root.optJSONArray("checkpoints") ?: JSONArray()
        for (i in 0 until chkArr.length()) {
            val o = chkArr.getJSONObject(i)
            delegate.insertCheckpoint(
                CheckpointEntity(
                    checkpointId = o.getString("checkpointId"),
                    taskId = o.getString("taskId"),
                    stepId = o.getString("stepId"),
                    workspacePath = o.optString("workspacePath").takeIf(String::isNotBlank),
                    contextSummary = o.optString("contextSummary", ""),
                    isValid = o.optBoolean("isValid", true),
                    timestamp = o.optLong("timestamp"),
                ),
            )
        }
        val evtArr = root.optJSONArray("taskEvents") ?: JSONArray()
        for (i in 0 until evtArr.length()) {
            val o = evtArr.getJSONObject(i)
            delegate.insertTaskEvent(
                TaskEventEntity(
                    eventId = o.getString("eventId"),
                    taskId = o.getString("taskId"),
                    eventType = o.getString("eventType"),
                    eventPayload = o.getString("eventPayload"),
                    sequenceNumber = o.optLong("sequenceNumber"),
                    timestamp = o.optLong("timestamp"),
                ),
            )
        }
        val cmArr = root.optJSONArray("conversationMessages") ?: JSONArray()
        for (i in 0 until cmArr.length()) {
            val o = cmArr.getJSONObject(i)
            delegate.insertConversationMessage(
                ConversationMessageEntity(
                    messageId = o.getString("messageId"),
                    conversationId = o.getString("conversationId"),
                    projectId = o.getString("projectId"),
                    senderId = o.getString("senderId"),
                    senderName = o.getString("senderName"),
                    isUser = o.optBoolean("isUser", true),
                    content = o.getString("content"),
                    relatedTaskId = o.optString("relatedTaskId").takeIf(String::isNotBlank),
                    timestamp = o.optLong("timestamp"),
                ),
            )
        }
        val linkArr = root.optJSONArray("taskConversationLinks") ?: JSONArray()
        for (i in 0 until linkArr.length()) {
            val o = linkArr.getJSONObject(i)
            runCatching {
                delegate.linkTaskToConversation(
                    TaskConversationCrossRef(
                        taskId = o.getString("taskId"),
                        conversationId = o.getString("conversationId"),
                    ),
                )
            }
        }
    }
}
