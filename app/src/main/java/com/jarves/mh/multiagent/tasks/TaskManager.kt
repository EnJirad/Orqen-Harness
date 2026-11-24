package com.jarves.mh.multiagent.tasks

import com.jarves.mh.multiagent.model.AgentSlot
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

class TaskManager {
    private val _tasks = MutableStateFlow<List<TeamTask>>(emptyList())
    val tasks: StateFlow<List<TeamTask>> = _tasks.asStateFlow()

    private val _parts = MutableStateFlow<List<ProjectPart>>(defaultParts())
    val parts: StateFlow<List<ProjectPart>> = _parts.asStateFlow()

    private val _currentPartNumber = MutableStateFlow(1)
    val currentPartNumber: StateFlow<Int> = _currentPartNumber.asStateFlow()

    fun addTask(task: TeamTask) {
        val current = _tasks.value.toMutableList()
        current.add(task)
        _tasks.value = current
    }

    fun updateTaskStatus(taskId: String, newStatus: TaskStatus, reviewNotes: String? = null, commitSha: String? = null) {
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
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == taskId }
        if (index >= 0) {
            current[index] = current[index].copy(assignedSlot = slot, status = TaskStatus.IN_PROGRESS)
            _tasks.value = current
        }
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
