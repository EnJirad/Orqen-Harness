package com.jarves.mh.multiagent.collab

import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState

/**
 * Deterministic Owner group-chat routing policy:
 * 1. Explicit @mention (via [explicitTarget] or `@Name` / `@Agent 02` / `@agent-02` / `@Role` in [text]):
 *    - Routes directly to that exact enabled agent.
 *    - If that agent is currently working (`isWorking == true`), sets [shouldInterruptAndResume] = true
 *      so the caller cleanly pauses its active work, lets it answer the Owner in group chat, and then
 *      automatically resumes its paused work right where it left off.
 * 2. General message (no @mention):
 *    - Prefers Supervisor (`AGENT_1`) when enabled and IDLE (`!isWorking`).
 *    - If Supervisor is busy (`isWorking`), picks an enabled IDLE agent (`!isWorking`) so working
 *      agents continue uninterrupted.
 *    - If all enabled agents are busy, falls back to Supervisor (or first enabled agent) without
 *      interrupting active work.
 */
object TeamGroupChatRouter {

    data class RoutingDecision(
        val responder: AgentState,
        val wasExplicitMention: Boolean,
        val shouldInterruptAndResume: Boolean,
    )

    data class LiveHandoverRequest(
        val takeoverAgent: AgentState,
        val relievedAgent: AgentState,
        val instruction: String,
    )

    private fun tokensForAgent(agent: AgentState): Set<String> {
        val slot = agent.config.slot
        val tokens = linkedSetOf(
            agent.config.name.trim(),
            slot.defaultName.trim(),
            slot.id.trim(),
            slot.name.trim(),
            agent.config.role.title.trim(),
            agent.config.role.type.name.trim(),
        )
        // Friendly short aliases so @SPV, @Dev, @QA always match regardless of custom display names
        when (agent.config.role.type) {
            com.jarves.mh.multiagent.model.AgentRoleType.TEAM_LEADER -> {
                tokens += listOf("spv", "supervisor", "ceo", "lead", "architect")
            }
            com.jarves.mh.multiagent.model.AgentRoleType.SOFTWARE_ENGINEER,
            com.jarves.mh.multiagent.model.AgentRoleType.FRONTEND_ENGINEER,
            com.jarves.mh.multiagent.model.AgentRoleType.BACKEND_ENGINEER,
            com.jarves.mh.multiagent.model.AgentRoleType.SPECIALIST,
            -> {
                tokens += listOf("dev", "developer", "coder", "engineer")
            }
            com.jarves.mh.multiagent.model.AgentRoleType.TESTER -> {
                tokens += listOf("qa", "tester", "test", "reviewer")
            }
            com.jarves.mh.multiagent.model.AgentRoleType.DEVOPS_ENGINEER -> {
                tokens += listOf("devops", "ops")
            }
            com.jarves.mh.multiagent.model.AgentRoleType.SECURITY_ENGINEER -> {
                tokens += listOf("sec", "security")
            }
            com.jarves.mh.multiagent.model.AgentRoleType.CUSTOM -> Unit
        }
        if (slot == AgentSlot.AGENT_1) tokens += listOf("spv", "supervisor")
        return tokens.filter { it.isNotBlank() }.map { it.lowercase() }.toSet()
    }

    /**
     * Resolves ALL explicitly @mentioned agent slots in order of appearance (with [explicitTarget] first if provided).
     * Used for concurrent multi-agent @mention chat and AI-to-AI consultation relay.
     */
    fun resolveAllMentionedSlots(
        text: String,
        explicitTarget: AgentSlot?,
        enabledAgents: List<AgentState>,
    ): List<AgentSlot> {
        if (enabledAgents.isEmpty()) return emptyList()
        val found = linkedSetOf<AgentSlot>()
        if (explicitTarget != null && enabledAgents.any { it.config.slot == explicitTarget }) {
            found.add(explicitTarget)
        }
        val trimmed = text.trim()
        if (!trimmed.contains('@')) return found.toList()

        val lowerText = trimmed.lowercase()
        val candidates = enabledAgents.flatMap { agent ->
            tokensForAgent(agent).map { token -> token to agent.config.slot }
        }.sortedByDescending { it.first.length }

        // Collect (startIndex, slot) matches sorted by earliest appearance in text
        val matches = mutableListOf<Pair<Int, AgentSlot>>()
        for ((tokenLower, slot) in candidates) {
            val mention = "@$tokenLower"
            var idx = lowerText.indexOf(mention)
            while (idx >= 0) {
                val endIdx = idx + mention.length
                val validBoundaryBefore = idx == 0 || !lowerText[idx - 1].isLetterOrDigit()
                val validBoundaryAfter = endIdx == lowerText.length || !lowerText[endIdx].isLetterOrDigit()
                if (validBoundaryBefore && validBoundaryAfter) {
                    matches.add(idx to slot)
                }
                idx = lowerText.indexOf(mention, startIndex = idx + 1)
            }
        }
        matches.sortedBy { it.first }.forEach { (_, slot) -> found.add(slot) }
        return found.toList()
    }

    fun resolveMentionedSlot(
        text: String,
        explicitTarget: AgentSlot?,
        enabledAgents: List<AgentState>,
    ): AgentSlot? = resolveAllMentionedSlots(text, explicitTarget, enabledAgents).firstOrNull()

    /**
     * Detects a dynamic live role-handover request from the Owner, e.g.:
     * "@QA ช่วยมาเทสระบบและบิ้ว apk แทน SPV หน่อย ทำต่อจากที่ SPV ทำอยู่เลย เพราะฉันจะคุยกับ spv"
     */
    fun detectLiveHandoverRequest(
        text: String,
        explicitTarget: AgentSlot?,
        enabledAgents: List<AgentState>,
    ): LiveHandoverRequest? {
        if (enabledAgents.size < 2) return null
        val mentioned = resolveAllMentionedSlots(text, explicitTarget, enabledAgents)
        val takeoverSlot = mentioned.firstOrNull() ?: return null
        val takeoverAgent = enabledAgents.firstOrNull { it.config.slot == takeoverSlot } ?: return null

        val lower = text.lowercase()
        val handoverKeywords = listOf(
            "แทน", "ทำต่อจาก", "มาทำแทน", "สลับให้", "รับช่วงต่อ", "ช่วยทำต่อแทน",
            "take over", "takeover", "replace ", "handover", "hand over", "continue for",
        )
        if (handoverKeywords.none { lower.contains(it) }) return null

        // Determine who is being relieved:
        // 1) A second @mentioned agent if different from takeoverAgent
        val secondMentioned = mentioned.firstOrNull { it != takeoverSlot }
            ?.let { s -> enabledAgents.firstOrNull { it.config.slot == s } }
        if (secondMentioned != null) {
            return LiveHandoverRequest(takeoverAgent, secondMentioned, text)
        }

        // 2) An agent referenced by name/alias after "แทน" / "จาก" / "replace" / "for" / "from"
        val otherAgents = enabledAgents.filter { it.config.slot != takeoverSlot }
        val matchedByText = otherAgents.firstOrNull { candidate ->
            tokensForAgent(candidate).any { token ->
                token.length >= 2 && Regex("(?<![a-z0-9])${Regex.escape(token)}(?![a-z0-9])").containsMatchIn(lower)
            }
        }
        val relieved = matchedByText
            ?: otherAgents.firstOrNull { it.config.slot == AgentSlot.AGENT_1 }
            ?: otherAgents.firstOrNull { it.isWorking }
            ?: return null

        return LiveHandoverRequest(takeoverAgent, relieved, text)
    }

    /**
     * Reassigns active/pending steps in [store] for [projectId] from [relievedAgentId] to [takeoverAgentId],
     * preserving all COMPLETED steps so the takeover agent continues right where the relieved agent left off.
     */
    fun applyLiveRoleHandover(
        store: com.jarves.mh.multiagent.persistence.OrqenTaskStore,
        projectId: String,
        takeoverAgentId: String,
        relievedAgentId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val activeStatuses = setOf(
            com.jarves.mh.multiagent.persistence.PersistentTaskStatus.RUNNING,
            com.jarves.mh.multiagent.persistence.PersistentTaskStatus.PAUSED,
            com.jarves.mh.multiagent.persistence.PersistentTaskStatus.QUEUED,
            com.jarves.mh.multiagent.persistence.PersistentTaskStatus.PLANNING,
            com.jarves.mh.multiagent.persistence.PersistentTaskStatus.READY,
            com.jarves.mh.multiagent.persistence.PersistentTaskStatus.RECOVERING,
            com.jarves.mh.multiagent.persistence.PersistentTaskStatus.BLOCKED,
        )
        val nonTerminalStepStatuses = setOf(
            com.jarves.mh.multiagent.persistence.StepStatus.PENDING,
            com.jarves.mh.multiagent.persistence.StepStatus.READY,
            com.jarves.mh.multiagent.persistence.StepStatus.RUNNING,
            com.jarves.mh.multiagent.persistence.StepStatus.WAITING_REVIEW,
            com.jarves.mh.multiagent.persistence.StepStatus.FAILED,
            com.jarves.mh.multiagent.persistence.StepStatus.BLOCKED,
        )
        var reassigned = 0
        val tasks = store.listTasksForProject(projectId).filter { it.status in activeStatuses }
        for (task in tasks) {
            val steps = store.listStepsForTask(task.taskId)
            for (step in steps) {
                if (step.assignedAgentId == relievedAgentId && step.status in nonTerminalStepStatuses) {
                    val nextStatus = if (step.status == com.jarves.mh.multiagent.persistence.StepStatus.RUNNING) {
                        com.jarves.mh.multiagent.persistence.StepStatus.READY
                    } else {
                        step.status
                    }
                    store.upsertStep(
                        step.copy(
                            assignedAgentId = takeoverAgentId,
                            status = nextStatus,
                        ),
                    )
                    reassigned++
                }
            }
            if (reassigned > 0) {
                store.insertTaskEvent(
                    com.jarves.mh.multiagent.persistence.TaskEventEntity(
                        eventId = "evt-handover-${java.util.UUID.randomUUID().toString().take(8)}",
                        taskId = task.taskId,
                        eventType = "LIVE_ROLE_HANDOVER",
                        eventPayload = "Handover: $relievedAgentId -> $takeoverAgentId ($reassigned steps reassigned)",
                        timestamp = nowMs,
                    ),
                )
            }
        }
        return reassigned
    }

    fun selectResponder(
        text: String,
        explicitTarget: AgentSlot?,
        enabledAgents: List<AgentState>,
    ): RoutingDecision? {
        if (enabledAgents.isEmpty()) return null

        val mentionedSlot = resolveMentionedSlot(text, explicitTarget, enabledAgents)
        if (mentionedSlot != null) {
            val mentionedAgent = enabledAgents.firstOrNull { it.config.slot == mentionedSlot }
            if (mentionedAgent != null) {
                return RoutingDecision(
                    responder = mentionedAgent,
                    wasExplicitMention = true,
                    shouldInterruptAndResume = mentionedAgent.isWorking,
                )
            }
        }

        // No explicit @mention:
        // 1. Prefer Supervisor (AGENT_1) if enabled and IDLE
        val supervisor = enabledAgents.firstOrNull { it.config.slot == AgentSlot.AGENT_1 }
        if (supervisor != null && !supervisor.isWorking) {
            return RoutingDecision(
                responder = supervisor,
                wasExplicitMention = false,
                shouldInterruptAndResume = false,
            )
        }

        // 2. If Supervisor is busy, pick an enabled IDLE agent so working agents are not interrupted
        val idleAgent = enabledAgents.firstOrNull { !it.isWorking }
        if (idleAgent != null) {
            return RoutingDecision(
                responder = idleAgent,
                wasExplicitMention = false,
                shouldInterruptAndResume = false,
            )
        }

        // 3. All agents are busy: Supervisor (or first enabled) answers without interrupting ongoing work
        return RoutingDecision(
            responder = supervisor ?: enabledAgents.first(),
            wasExplicitMention = false,
            shouldInterruptAndResume = false,
        )
    }

    /**
     * Detects whether the Owner's reply confirms continuing pending project work
     * after Supervisor reported unfinished tasks upon pressing Start.
     */
    fun isContinueConfirmation(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.isBlank()) return false

        val negativeKeywords = listOf(
            "ไม่", "หยุด", "ยกเลิก", "รอ", "เดี๋ยว", "ยังก่อน", "ไม่ต้อง",
            "no", "nope", "cancel", "stop", "wait", "not yet", "don't",
        )
        if (negativeKeywords.any { normalized.contains(it) }) return false

        val affirmativeKeywords = listOf(
            "โอเค", "ตกลง", "ทำต่อ", "ทำงานต่อ", "เริ่มเลย", "ลุยเลย", "ได้เลย", "ได้ครับ", "ได้ค่ะ",
            "ใช่", "จัดไป", "เอาเลย", "ดำเนินการต่อ", "เริ่มทำ",
            "ok", "okay", "yes", "yep", "yeah", "sure", "continue", "proceed", "go ahead", "start", "resume",
        )
        return affirmativeKeywords.any { kw ->
            if (kw.all { it.isLetter() && it.code < 128 }) {
                Regex("""(?<![a-z0-9])${Regex.escape(kw)}(?![a-z0-9])""").containsMatchIn(normalized)
            } else {
                normalized.contains(kw)
            }
        }
    }
}
