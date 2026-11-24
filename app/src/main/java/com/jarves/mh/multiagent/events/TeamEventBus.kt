package com.jarves.mh.multiagent.events

import com.jarves.mh.multiagent.model.AgentSlot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class TeamEventType {
    TASK_CREATED,
    TASK_ASSIGNED,
    TASK_STARTED,
    FILE_LOCKED,
    FILE_UPDATED,
    FILE_UNLOCKED,
    TASK_BLOCKED,
    TASK_COMPLETED,
    REVIEW_REQUESTED,
    BUG_FOUND,
    COMMIT_CREATED,
    CHECKPOINT_CREATED,
    PART_APPROVED,
}

data class TeamCollaborationEvent(
    val type: TeamEventType,
    val senderSlot: AgentSlot?,
    val targetSlot: AgentSlot? = null,
    val taskId: String? = null,
    val filePath: String? = null,
    val commitSha: String? = null,
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

class TeamEventBus {
    private val _events = MutableSharedFlow<TeamCollaborationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TeamCollaborationEvent> = _events.asSharedFlow()

    fun emit(event: TeamCollaborationEvent) {
        _events.tryEmit(event)
    }

    fun broadcast(
        type: TeamEventType,
        senderSlot: AgentSlot?,
        targetSlot: AgentSlot? = null,
        taskId: String? = null,
        filePath: String? = null,
        commitSha: String? = null,
        message: String = "",
    ) {
        emit(
            TeamCollaborationEvent(
                type = type,
                senderSlot = senderSlot,
                targetSlot = targetSlot,
                taskId = taskId,
                filePath = filePath,
                commitSha = commitSha,
                message = message,
            )
        )
    }
}
