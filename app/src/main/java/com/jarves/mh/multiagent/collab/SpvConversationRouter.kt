package com.jarves.mh.multiagent.collab

import com.jarves.mh.multiagent.chat.GroupMessageType
import com.jarves.mh.multiagent.model.AgentRoleType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState

/**
 * Phase 3 — Explicit Message & Routing Taxonomy separating Owner-facing SPV
 * conversation from internal worker execution & verification messages.
 */
enum class OrqenMessageKind {
    USER_MESSAGE,
    USER_QUESTION,
    USER_IMPLEMENTATION_REQUEST,
    SPV_RESPONSE,
    INTERNAL_TASK_ASSIGNMENT,
    AGENT_PROGRESS,
    AGENT_RESULT,
    REVIEW_RESULT,
    TEST_RESULT,
    BLOCKER_REPORT,
    SUPERVISOR_DECISION,
}

data class OwnerRoutingResult(
    val messageKind: OrqenMessageKind,
    val interactionMode: SpvInteractionMode,
    val ownerFacingAgent: AgentState,
    val shouldCreateTask: Boolean,
)

/**
 * Phase 2 & 3 — SPV-Centered Conversation & Response Router.
 *
 * Architectural guarantees:
 * - SPV is the SOLE Owner-facing supervisor.
 * - Developer, Reviewer, and QA operate strictly as internal workers.
 * - User questions (e.g., "Why can't AI agents work independently?") are ALWAYS classified
 *   as [OrqenMessageKind.USER_QUESTION] and answered by SPV without creating a task or
 *   invoking Developer.
 * - Internal worker outputs are NEVER displayed directly as if they were SPV responses;
 *   SPV evaluates internal results and formats concise Owner-facing progress/completion updates.
 */
object SpvConversationRouter {
    private val questionPrefixes = listOf(
        "why ", "how ", "what ", "when ", "where ", "who ", "which ", "can ", "could ",
        "should ", "is ", "are ", "do ", "does ", "explain ", "tell me ",
        "ทำไม", "อย่างไร", "ยังไง", "อะไร", "อธิบาย", "ช่วยบอก", "คิดเห็นยังไง",
    )

    fun isInternalOnlyMessage(kind: OrqenMessageKind): Boolean = kind in setOf(
        OrqenMessageKind.INTERNAL_TASK_ASSIGNMENT,
        OrqenMessageKind.AGENT_PROGRESS,
        OrqenMessageKind.AGENT_RESULT,
        OrqenMessageKind.REVIEW_RESULT,
        OrqenMessageKind.TEST_RESULT,
        OrqenMessageKind.BLOCKER_REPORT,
        OrqenMessageKind.SUPERVISOR_DECISION,
    )

    fun isQuestionText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.endsWith("?") || trimmed.endsWith("ไหม") || trimmed.endsWith("หรือเปล่า") || trimmed.endsWith("หรือยัง")) {
            return true
        }
        val lower = trimmed.lowercase()
        return questionPrefixes.any { lower.startsWith(it) }
    }

    fun classifyOwnerMessage(
        text: String,
        messageType: GroupMessageType,
    ): OrqenMessageKind {
        // STRICT MODE SEPARATION: Only explicit Task Mode submissions trigger execution.
        // Chat Mode (GroupMessageType.NORMAL) NEVER triggers tasks based on keywords or phrases.
        if (messageType != GroupMessageType.NORMAL) {
            return OrqenMessageKind.USER_IMPLEMENTATION_REQUEST
        }
        if (isQuestionText(text)) {
            return OrqenMessageKind.USER_QUESTION
        }
        return OrqenMessageKind.USER_MESSAGE
    }

    /**
     * Always resolves SPV (AGENT_1 / TEAM_LEADER) as the sole Owner-facing supervisor.
     * Never routes general Owner conversation to Developer or QA even when SPV is busy.
     */
    fun selectSoleOwnerFacingSupervisor(enabledAgents: List<AgentState>): AgentState? {
        if (enabledAgents.isEmpty()) return null
        return enabledAgents.firstOrNull { it.config.slot == AgentSlot.AGENT_1 }
            ?: enabledAgents.firstOrNull { it.config.role.type == AgentRoleType.TEAM_LEADER }
            ?: enabledAgents.first()
    }

    fun routeOwnerMessage(
        text: String,
        messageType: GroupMessageType,
        enabledAgents: List<AgentState>,
    ): OwnerRoutingResult? {
        val spv = selectSoleOwnerFacingSupervisor(enabledAgents) ?: return null
        val kind = classifyOwnerMessage(text, messageType)
        val shouldCreateTask = kind == OrqenMessageKind.USER_IMPLEMENTATION_REQUEST
        val mode = if (shouldCreateTask) SpvInteractionMode.TASK_EXECUTION else SpvInteractionMode.CHAT_CONSULTATION
        return OwnerRoutingResult(
            messageKind = kind,
            interactionMode = mode,
            ownerFacingAgent = spv,
            shouldCreateTask = shouldCreateTask,
        )
    }

    /**
     * Constructs a concise, SPV-authored Owner-facing status update from internal worker events.
     * Preserves worker attribution without exposing raw Developer conversational prose as SPV output.
     */
    fun formatSpvStatusReport(
        phase: String,
        workerName: String,
        stepNumber: Int,
        totalSteps: Int,
        stepTitle: String,
        modifiedFiles: List<String> = emptyList(),
        detail: String = "",
    ): String {
        val stepTag = if (stepNumber > 0 && totalSteps > 0) "Step $stepNumber/$totalSteps" else "Step"
        return when (phase) {
            "STARTED" -> "[SPV] Delegated $stepTag ($stepTitle) to $workerName."
            "COMPLETED" -> buildString {
                append("[SPV] $workerName completed $stepTag: $stepTitle")
                if (modifiedFiles.isNotEmpty()) {
                    append(" (${modifiedFiles.size} file(s): ${modifiedFiles.take(4).joinToString(", ")})")
                }
            }
            "RECOVERING", "FAILED" -> "[SPV] $workerName reported an issue on $stepTag ($stepTitle): ${detail.take(180)}. SPV is managing recovery."
            else -> "[SPV] $stepTag ($stepTitle): ${detail.take(180)}"
        }
    }
}
