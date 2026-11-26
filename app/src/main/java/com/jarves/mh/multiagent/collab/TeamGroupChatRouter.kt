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

    fun resolveMentionedSlot(
        text: String,
        explicitTarget: AgentSlot?,
        enabledAgents: List<AgentState>,
    ): AgentSlot? {
        if (enabledAgents.isEmpty()) return null
        if (explicitTarget != null && enabledAgents.any { it.config.slot == explicitTarget }) {
            return explicitTarget
        }
        val trimmed = text.trim()
        if (!trimmed.contains('@')) return null

        val lowerText = trimmed.lowercase()
        // Build candidate tokens per agent sorted longest-first so "@Agent 010" matches before "@Agent 01"
        val candidates = enabledAgents.flatMap { agent ->
            val slot = agent.config.slot
            val tokens = linkedSetOf(
                agent.config.name.trim(),
                slot.defaultName.trim(),
                slot.id.trim(),
                slot.name.trim(),
                agent.config.role.title.trim(),
            ).filter { it.isNotBlank() }
            tokens.map { token -> token.lowercase() to slot }
        }.sortedByDescending { it.first.length }

        for ((tokenLower, slot) in candidates) {
            val mention = "@$tokenLower"
            var idx = lowerText.indexOf(mention)
            while (idx >= 0) {
                val endIdx = idx + mention.length
                val validBoundaryBefore = idx == 0 || !lowerText[idx - 1].isLetterOrDigit()
                val validBoundaryAfter = endIdx == lowerText.length || !lowerText[endIdx].isLetterOrDigit()
                if (validBoundaryBefore && validBoundaryAfter) {
                    return slot
                }
                idx = lowerText.indexOf(mention, startIndex = idx + 1)
            }
        }
        return null
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
