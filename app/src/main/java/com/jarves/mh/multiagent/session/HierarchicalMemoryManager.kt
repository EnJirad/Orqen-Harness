package com.jarves.mh.multiagent.session

import com.jarves.mh.multiagent.persistence.*

data class CompactionResult(
    val wasCompacted: Boolean,
    val originalEstimatedTokens: Int,
    val compactedEstimatedTokens: Int,
    val preservedDecisions: List<String>,
    val preservedFilePaths: List<String>,
    val summaryText: String,
    val recentMessages: List<ConversationMessageEntity>,
)

/**
 * 4-tier Persistent Hierarchical Memory & Automatic Context Compaction engine:
 * 1. Project Memory — architecture, decisions, conventions, security, dependencies
 * 2. Task Memory — objective, acceptance criteria, progress, known failures
 * 3. Agent Memory — role, prior assignments, findings, review feedback
 * 4. Conversation Memory — recent turns + durable compacted summary
 */
class HierarchicalMemoryManager(
    private val store: OrqenTaskStore,
) {
    private val filePathRegex = Regex(
        """([A-Za-z0-9_\-./]+\.(?:kt|java|xml|html|css|js|ts|tsx|jsx|json|py|md|gradle|kts|yml|yaml|sql|sh))""",
        RegexOption.IGNORE_CASE,
    )

    fun updateProjectMemory(projectId: String, decisionOrNote: String) {
        val project = store.getProject(projectId) ?: return
        val existing = project.projectMemory.trim()
        val appended = if (existing.isBlank()) decisionOrNote.trim() else "$existing\n- ${decisionOrNote.trim()}"
        store.upsertProject(
            project.copy(
                projectMemory = appended.takeLast(MAX_PROJECT_MEMORY_CHARS),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun updateTaskMemory(taskId: String, note: String) {
        val task = store.getTask(taskId) ?: return
        val existing = task.taskMemory.trim()
        val appended = if (existing.isBlank()) note.trim() else "$existing\n- ${note.trim()}"
        store.upsertTask(
            task.copy(
                taskMemory = appended.takeLast(MAX_TASK_MEMORY_CHARS),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun buildAgentMemoryPrompt(taskId: String, agentId: String): String {
        val steps = store.listStepsForTask(taskId).filter { it.assignedAgentId == agentId }
        val msgs = store.listAgentMessagesForTask(taskId, limit = 40)
            .filter { it.fromAgentId == agentId || it.toAgentId == agentId }
            .takeLast(8)
        return buildString {
            if (steps.isNotEmpty()) {
                appendLine("Previous assignments for $agentId:")
                steps.takeLast(5).forEach { s ->
                    appendLine("- #${s.sequenceNumber} ${s.title} [${s.status}]: ${s.outputSummary.take(160)}")
                }
            }
            if (msgs.isNotEmpty()) {
                appendLine("Recent structured messages:")
                msgs.forEach { m ->
                    appendLine("- [${m.messageType}] ${m.fromAgentId} -> ${m.toAgentId}: ${m.content.take(160)}")
                }
            }
        }.trim()
    }

    /**
     * Automatically compacts a conversation when its estimated token count exceeds [maxContextTokens].
     * Preserves:
     * - Technical decisions & constraints (lines mentioning decision, must, constraint, architecture, security)
     * - Referenced file paths
     * - Unresolved errors / blockers
     * - Most recent [preserveRecentMessages] verbatim
     * Saves the resulting summary durably into [ConversationEntity.contextSummary].
     */
    fun compactConversationIfNeeded(
        conversationId: String,
        maxContextTokens: Int = 24_000,
        preserveRecentMessages: Int = 8,
    ): CompactionResult {
        val conv = store.getConversation(conversationId)
        val messages = store.listConversationMessages(conversationId, limit = 1_000)
        val initialTokens = messages.sumOf { estimateTokens(it.content) } + estimateTokens(conv?.contextSummary.orEmpty())

        if (initialTokens <= maxContextTokens || messages.size <= preserveRecentMessages) {
            return CompactionResult(
                wasCompacted = false,
                originalEstimatedTokens = initialTokens,
                compactedEstimatedTokens = initialTokens,
                preservedDecisions = emptyList(),
                preservedFilePaths = emptyList(),
                summaryText = conv?.contextSummary.orEmpty(),
                recentMessages = messages,
            )
        }

        val recent = messages.takeLast(preserveRecentMessages)
        val older = messages.dropLast(preserveRecentMessages)

        val preservedDecisions = linkedSetOf<String>()
        val preservedFiles = linkedSetOf<String>()
        val preservedIssues = linkedSetOf<String>()

        older.forEach { msg ->
            filePathRegex.findAll(msg.content).forEach { m -> preservedFiles.add(m.groupValues[1]) }
            msg.content.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                val lower = line.lowercase()
                when {
                    lower.contains("decision") || lower.contains("constraint") ||
                        lower.contains("must ") || lower.contains("architecture") ||
                        lower.contains("security") || lower.contains("requirement") ||
                        lower.contains("ตัดสินใจ") || lower.contains("ข้อกำหนด") -> {
                        preservedDecisions.add(line.take(180))
                    }
                    lower.contains("error") || lower.contains("fail") || lower.contains("blocked") ||
                        lower.contains("bug") || lower.contains("unresolved") || lower.contains("ปัญหา") -> {
                        preservedIssues.add(line.take(180))
                    }
                }
            }
        }

        val summary = buildString {
            if (conv != null && conv.contextSummary.isNotBlank()) {
                appendLine(conv.contextSummary.take(1_200))
                appendLine()
            }
            appendLine("[Compacted ${older.size} earlier messages]")
            if (preservedDecisions.isNotEmpty()) {
                appendLine("Decisions & Constraints:")
                preservedDecisions.take(12).forEach { appendLine("- $it") }
            }
            if (preservedFiles.isNotEmpty()) {
                appendLine("Key File Paths: ${preservedFiles.take(25).joinToString(", ")}")
            }
            if (preservedIssues.isNotEmpty()) {
                appendLine("Unresolved Issues / Findings:")
                preservedIssues.take(8).forEach { appendLine("- $it") }
            }
        }.trim()

        if (conv != null) {
            store.upsertConversation(
                conv.copy(
                    contextSummary = summary.take(MAX_CONVERSATION_SUMMARY_CHARS),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

        val compactedTokens = estimateTokens(summary) + recent.sumOf { estimateTokens(it.content) }
        return CompactionResult(
            wasCompacted = true,
            originalEstimatedTokens = initialTokens,
            compactedEstimatedTokens = compactedTokens,
            preservedDecisions = preservedDecisions.toList(),
            preservedFilePaths = preservedFiles.toList(),
            summaryText = summary,
            recentMessages = recent,
        )
    }

    fun estimateTokens(text: String): Int = if (text.isEmpty()) 0 else (text.length / 4).coerceAtLeast(1)

    companion object {
        private const val MAX_PROJECT_MEMORY_CHARS = 8_000
        private const val MAX_TASK_MEMORY_CHARS = 6_000
        private const val MAX_CONVERSATION_SUMMARY_CHARS = 6_000
    }
}
