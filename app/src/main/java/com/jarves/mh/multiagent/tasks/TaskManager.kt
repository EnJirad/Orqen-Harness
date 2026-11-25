package com.jarves.mh.multiagent.tasks

import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.persistence.OrqenTaskStore
import com.jarves.mh.multiagent.persistence.PersistentTaskStatus
import com.jarves.mh.multiagent.persistence.ProjectEntity
import com.jarves.mh.multiagent.persistence.TaskEntity
import com.jarves.mh.multiagent.persistence.TaskEventEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class TaskStatus {
    BACKLOG,
    PLANNED,
    READY,
    IN_PROGRESS,
    BLOCKED,
    WAITING_FOR_REVIEW,
    TESTING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class TeamTask(
    val id: String = UUID.randomUUID().toString(),
    val partNumber: Int = 1,
    val title: String,
    val description: String,
    val assignedSlot: AgentSlot? = null,
    val priority: Int = 1,
    val dependencies: List<String> = emptyList(),
    val filesInvolved: List<String> = emptyList(),
    val acceptanceCriteria: List<String> = emptyList(),
    val status: TaskStatus = TaskStatus.READY,
    val startTimestamp: Long? = null,
    val completionTimestamp: Long? = null,
    val reviewNotes: String? = null,
    val commitSha: String? = null,
)

data class ProjectPart(
    val partNumber: Int,
    val title: String,
    val goal: String,
    val scope: String,
    val taskIds: List<String> = emptyList(),
    val isApproved: Boolean = false,
)

class TaskManager(
    private val durableStore: OrqenTaskStore? = null,
    private val projectId: String = DEFAULT_PROJECT_ID,
) {
    private val _tasks = MutableStateFlow<List<TeamTask>>(emptyList())
    val tasks: StateFlow<List<TeamTask>> = _tasks.asStateFlow()

    private val _parts = MutableStateFlow<List<ProjectPart>>(defaultParts())
    val parts: StateFlow<List<ProjectPart>> = _parts.asStateFlow()

    private val _currentPartNumber = MutableStateFlow(1)
    val currentPartNumber: StateFlow<Int> = _currentPartNumber.asStateFlow()

    init {
        if (durableStore != null) {
            ensureProjectRow(durableStore)
            refreshFromStore()
        }
    }

    private fun ensureProjectRow(store: OrqenTaskStore) {
        if (store.getProject(projectId) == null) {
            store.upsertProject(
                ProjectEntity(
                    projectId = projectId,
                    projectName = "ORQEN Team Project",
                    workspacePath = "",
                ),
            )
        }
    }

    fun refreshFromStore() {
        val store = durableStore ?: return
        val persisted = store.listTasksForProject(projectId).map { entity ->
            TeamTask(
                id = entity.taskId,
                partNumber = 1,
                title = entity.title,
                description = entity.description,
                priority = entity.priority,
                status = entity.status.toLegacyTaskStatus(),
                startTimestamp = entity.startedAt,
                completionTimestamp = entity.completedAt,
            )
        }
        _tasks.value = persisted
    }

    fun addTask(task: TeamTask) {
        val store = durableStore
        if (store != null) {
            ensureProjectRow(store)
            store.runInTransaction {
                store.upsertTask(
                    TaskEntity(
                        taskId = task.id,
                        projectId = projectId,
                        title = task.title,
                        description = task.description,
                        status = task.status.toPersistentTaskStatus(),
                        priority = task.priority,
                        startedAt = task.startTimestamp,
                        completedAt = task.completionTimestamp,
                    ),
                )
                store.insertTaskEvent(
                    TaskEventEntity(
                        taskId = task.id,
                        eventType = "TASK_CREATED",
                        eventPayload = task.title,
                    ),
                )
            }
            refreshFromStore()
            return
        }
        val current = _tasks.value.toMutableList()
        current.add(task)
        _tasks.value = current
    }

    fun updateTaskStatus(taskId: String, newStatus: TaskStatus, reviewNotes: String? = null, commitSha: String? = null) {
        val store = durableStore
        if (store != null) {
            val existing = store.getTask(taskId)
            if (existing != null) {
                val now = System.currentTimeMillis()
                val updated = existing.copy(
                    status = newStatus.toPersistentTaskStatus(),
                    startedAt = if (newStatus == TaskStatus.IN_PROGRESS && existing.startedAt == null) now else existing.startedAt,
                    completedAt = if (newStatus == TaskStatus.COMPLETED) now else existing.completedAt,
                    updatedAt = now,
                )
                store.runInTransaction {
                    store.upsertTask(updated)
                    store.insertTaskEvent(
                        TaskEventEntity(
                            taskId = taskId,
                            eventType = "TASK_STATUS_CHANGED",
                            eventPayload = "${existing.status} -> ${updated.status}",
                        ),
                    )
                }
                refreshFromStore()
            }
            return
        }
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == taskId }
        if (index >= 0) {
            val old = current[index]
            val completedTime = if (newStatus == TaskStatus.COMPLETED) System.currentTimeMillis() else old.completionTimestamp
            val startTime = if (newStatus == TaskStatus.IN_PROGRESS && old.startTimestamp == null) System.currentTimeMillis() else old.startTimestamp
            current[index] = old.copy(
                status = newStatus,
                startTimestamp = startTime,
                completionTimestamp = completedTime,
                reviewNotes = reviewNotes ?: old.reviewNotes,
                commitSha = commitSha ?: old.commitSha,
            )
            _tasks.value = current
        }
    }

    fun assignTask(taskId: String, slot: AgentSlot) {
        val store = durableStore
        if (store != null) {
            updateTaskStatus(taskId, TaskStatus.IN_PROGRESS)
            return
        }
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == taskId }
        if (index >= 0) {
            current[index] = current[index].copy(assignedSlot = slot, status = TaskStatus.IN_PROGRESS)
            _tasks.value = current
        }
    }

    private fun TaskStatus.toPersistentTaskStatus(): PersistentTaskStatus = when (this) {
        TaskStatus.BACKLOG -> PersistentTaskStatus.DRAFT
        TaskStatus.PLANNED -> PersistentTaskStatus.PLANNING
        TaskStatus.READY -> PersistentTaskStatus.READY
        TaskStatus.IN_PROGRESS, TaskStatus.WAITING_FOR_REVIEW, TaskStatus.TESTING -> PersistentTaskStatus.RUNNING
        TaskStatus.BLOCKED -> PersistentTaskStatus.BLOCKED
        TaskStatus.COMPLETED -> PersistentTaskStatus.COMPLETED
        TaskStatus.FAILED -> PersistentTaskStatus.FAILED
        TaskStatus.CANCELLED -> PersistentTaskStatus.CANCELLED
    }

    private fun PersistentTaskStatus.toLegacyTaskStatus(): TaskStatus = when (this) {
        PersistentTaskStatus.DRAFT -> TaskStatus.BACKLOG
        PersistentTaskStatus.QUEUED, PersistentTaskStatus.READY -> TaskStatus.READY
        PersistentTaskStatus.PLANNING -> TaskStatus.PLANNED
        PersistentTaskStatus.RUNNING, PersistentTaskStatus.PAUSE_REQUESTED, PersistentTaskStatus.RECOVERING -> TaskStatus.IN_PROGRESS
        PersistentTaskStatus.PAUSED, PersistentTaskStatus.BLOCKED -> TaskStatus.BLOCKED
        PersistentTaskStatus.COMPLETED -> TaskStatus.COMPLETED
        PersistentTaskStatus.FAILED -> TaskStatus.FAILED
        PersistentTaskStatus.CANCELLED -> TaskStatus.CANCELLED
    }

    fun approvePart(partNumber: Int) {
        val current = _parts.value.toMutableList()
        val index = current.indexOfFirst { it.partNumber == partNumber }
        if (index >= 0) {
            current[index] = current[index].copy(isApproved = true)
            _parts.value = current
            if (partNumber >= _currentPartNumber.value && partNumber < 10) {
                _currentPartNumber.value = partNumber + 1
            }
        }
    }

    fun isPartCompleted(partNumber: Int): Boolean {
        val partTasks = _tasks.value.filter { it.partNumber == partNumber }
        if (partTasks.isEmpty()) return true
        return partTasks.all { it.status == TaskStatus.COMPLETED }
    }

    companion object {
        const val DEFAULT_PROJECT_ID = "default-team-project"

        fun defaultParts(): List<ProjectPart> = listOf(
            ProjectPart(1, "Part 01 — Analyze Existing Project", "Inspect codebase, architecture & requirements", "All modules"),
            ProjectPart(2, "Part 02 — Design Architecture", "Design data contracts & component interfaces", "Architecture"),
            ProjectPart(3, "Part 03 — Frontend Development", "Implement UI screens, views & layouts", "UI layer"),
            ProjectPart(4, "Part 04 — Backend Development", "Implement API endpoints, models & services", "Data / Backend"),
            ProjectPart(5, "Part 05 — Integration", "Connect frontend with backend & adapters", "Integration"),
            ProjectPart(6, "Part 06 — Testing", "Unit tests, UI tests, and validation", "Test suite"),
            ProjectPart(7, "Part 07 — Bug Fixing", "Address issues reported by QA / Tester", "Bug fixes"),
            ProjectPart(8, "Part 08 — Final Review", "Code review, security & performance audit", "All modules"),
            ProjectPart(9, "Part 09 — Git Checkpoint", "Create commit, push to GitHub & tag release", "Git repository"),
            ProjectPart(10, "Part 10 — Next Development Cycle", "Prepare next milestone", "Roadmap"),
        )
    }
}
