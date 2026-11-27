package com.jarves.mh.multiagent.collab

import com.jarves.mh.multiagent.chat.GroupChatMessage
import com.jarves.mh.multiagent.chat.GroupMessageType

/**
 * Architecturally Enforced SPV Interaction Mode.
 *
 * - [CHAT_CONSULTATION]: Pure consultation, brainstorming, architecture & requirements refinement.
 *   HARD RESTRICTION: NEVER creates tasks, delegates implementation, invokes Developer/Reviewer/QA,
 *   runs terminal/MCP tools, or modifies workspace files — regardless of phrasing.
 * - [TASK_EXECUTION]: Explicit Owner-selected Task Mode for autonomous planning & execution.
 */
enum class SpvInteractionMode {
    CHAT_CONSULTATION,
    TASK_EXECUTION,
}

data class NormalizedTaskBrief(
    val normalizedObjective: String,
    val finalDecisions: List<String>,
    val rejectedOrSupersededIdeas: List<String>,
    val referencedAttachments: List<String>,
    val sourceMessageCount: Int,
    val renderedBrief: String,
)

object SpvModePolicy {
    private val rejectionMarkers = listOf(
        "don't use", "do not use", "instead of", "rather than", "scrap ", "drop ",
        "never mind", "nevermind", "forget ", "cancel ", "not ", "no longer",
        "เปลี่ยนเป็น", "ไม่ต้องใช้", "ไม่เอา", "ยกเลิก", "เปลี่ยนใจ", "แทนที่จะใช้",
    )

    private val decisionMarkers = listOf(
        "let's use", "we will use", "agreed", "final decision", "decided", "must have",
        "requirement", "implement", "use ", "with ", "architecture", "database", "sqlite",
        "ตกลง", "สรุป", "ใช้ ", "ต้องมี", "ฟีเจอร์", "หน้าจอ",
    )

    /**
     * Resolves the authoritative interaction mode.
     *
     * STRICT RULE (No Automatic Mode Switching):
     * - The selected UI/API [messageType] is authoritative.
     * - [GroupMessageType.NORMAL] (Chat Mode) ALWAYS resolves to [SpvInteractionMode.CHAT_CONSULTATION].
     * - Natural-language phrases (e.g., "Create an app", "Fix this bug", "Start building this")
     *   NEVER switch Chat Mode into Task Mode.
     * - Only explicit Task Mode submissions (`messageType != GroupMessageType.NORMAL`) enter [SpvInteractionMode.TASK_EXECUTION].
     */
    fun resolveMode(
        messageType: GroupMessageType,
        @Suppress("UNUSED_PARAMETER") text: String,
    ): SpvInteractionMode {
        return if (messageType != GroupMessageType.NORMAL) {
            SpvInteractionMode.TASK_EXECUTION
        } else {
            SpvInteractionMode.CHAT_CONSULTATION
        }
    }

    /**
     * Retained as a pure helper for identifying generic "implement what we discussed" instructions
     * INSIDE Task Mode so SPV knows to synthesize the full conversation history.
     * NEVER used to auto-switch Chat Mode into Task Mode.
     */
    fun isGenericHistoryReferenceInstruction(text: String): Boolean {
        val normalized = text.trim().lowercase().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return true
        val patterns = listOf(
            "implement everything we agreed",
            "implement what we discussed",
            "build what we agreed",
            "start building this",
            "proceed with implementation",
            "execute the plan",
            "เริ่มสร้างเลย",
            "ทำตามที่คุยกัน",
            "ลงมือทำเลย",
            "สร้างตามที่ตกลง",
        )
        return patterns.any { normalized.contains(it) }
    }

    /**
     * Synthesizes a structured [NormalizedTaskBrief] from the COMPLETE relevant conversation history,
     * distinguishing final agreed decisions from rejected/superseded ideas and preserving attachments.
     */
    fun synthesizeTaskBrief(
        conversationHistory: List<GroupChatMessage>,
        explicitInstruction: String = "",
        attachmentPaths: List<String> = emptyList(),
    ): NormalizedTaskBrief {
        val relevant = conversationHistory.filter {
            it.messageType == GroupMessageType.NORMAL || it.messageType == GroupMessageType.TASK_ASSIGNMENT
        }
        val cleanExplicit = explicitInstruction.trim()
        val finalDecisions = linkedSetOf<String>()
        val rejectedIdeas = linkedSetOf<String>()

        for (msg in relevant) {
            val lines = msg.text.lines()
                .map { it.trim().removePrefix("-").removePrefix("*").trim() }
                .filter { it.length >= 6 }
            for (line in lines) {
                val lower = line.lowercase()
                val isRejection = rejectionMarkers.any { lower.contains(it) }
                if (isRejection) {
                    rejectedIdeas.add(line.take(220))
                    // If a line contains an "instead of X, use Y" pattern, also capture the positive part as a decision
                    val insteadIdx = lower.indexOf("instead of")
                    if (insteadIdx > 6) {
                        finalDecisions.add(line.substring(0, insteadIdx).trim().take(220))
                    }
                    val thaiChangeIdx = lower.indexOf("เปลี่ยนเป็น")
                    if (thaiChangeIdx >= 0) {
                        val after = line.substring(thaiChangeIdx).trim()
                        if (after.length > 12) finalDecisions.add(after.take(220))
                    }
                } else if (msg.senderSlot == null || decisionMarkers.any { lower.contains(it) }) {
                    finalDecisions.add(line.take(240))
                }
            }
        }

        // Remove any earlier decision that was later explicitly superseded/rejected
        val filteredDecisions = finalDecisions.filter { dec ->
            val decLower = dec.lowercase()
            rejectedIdeas.none { rej ->
                val rejLower = rej.lowercase()
                rejLower.contains("don't use") && decLower.split(" ").any { token ->
                    token.length >= 5 && rejLower.contains("don't use $token")
                } || rejLower.contains("ไม่ต้องใช้") && decLower.split(" ").any { token ->
                    token.length >= 4 && rejLower.contains("ไม่ต้องใช้ $token")
                }
            }
        }.takeLast(25)

        val primaryObjective = when {
            cleanExplicit.isNotBlank() && !isGenericHistoryReferenceInstruction(cleanExplicit) -> cleanExplicit
            filteredDecisions.isNotEmpty() -> filteredDecisions.first()
            else -> relevant.lastOrNull { it.senderSlot == null }?.text?.trim()?.take(500).orEmpty().ifBlank { cleanExplicit }
        }

        val cleanAttachments = attachmentPaths.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val rendered = buildString {
            appendLine("## Normalized Task Brief")
            appendLine("**Primary Objective:** $primaryObjective")
            if (cleanExplicit.isNotBlank() && cleanExplicit != primaryObjective) {
                appendLine("**Owner Execution Instruction:** $cleanExplicit")
            }
            if (filteredDecisions.isNotEmpty()) {
                appendLine()
                appendLine("### Final Agreed Requirements & Decisions")
                filteredDecisions.forEach { appendLine("- $it") }
            }
            if (rejectedIdeas.isNotEmpty()) {
                appendLine()
                appendLine("### Rejected / Superseded Ideas (DO NOT IMPLEMENT)")
                rejectedIdeas.toList().takeLast(15).forEach { appendLine("- $it") }
            }
            if (cleanAttachments.isNotEmpty()) {
                appendLine()
                appendLine("### Referenced Attachments & Files")
                cleanAttachments.forEach { appendLine("- $it") }
            }
            if (relevant.isNotEmpty()) {
                appendLine()
                appendLine("### Full Conversation Context (${relevant.size} messages)")
                relevant.forEach { msg ->
                    val role = if (msg.senderSlot == null) "Owner" else msg.senderName
                    appendLine("- $role: ${msg.text.trim().take(600)}")
                }
            }
        }.trim()

        return NormalizedTaskBrief(
            normalizedObjective = primaryObjective,
            finalDecisions = filteredDecisions,
            rejectedOrSupersededIdeas = rejectedIdeas.toList(),
            referencedAttachments = cleanAttachments,
            sourceMessageCount = relevant.size,
            renderedBrief = rendered,
        )
    }

    /**
     * Backward-compatible wrapper returning the full synthesized task brief string.
     */
    fun buildObjectiveFromDiscussion(
        recentMessages: List<GroupChatMessage>,
        explicitInstruction: String = "",
        attachmentPaths: List<String> = emptyList(),
    ): String {
        val cleanExplicit = explicitInstruction.trim()
        if (recentMessages.isEmpty()) return cleanExplicit
        return synthesizeTaskBrief(recentMessages, cleanExplicit, attachmentPaths).renderedBrief
    }
}
