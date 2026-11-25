package com.jarves.mh.multiagent.supervisor

import com.jarves.mh.multiagent.model.AgentState

data class ContextTurn(
    val role: String,
    val speakerName: String,
    val content: String,
    val estimatedTokens: Int = (content.length / 4).coerceAtLeast(1),
)

data class PrunedContextResult(
    val turns: List<ContextTurn>,
    val totalTokens: Int,
    val prunedCount: Int,
    val wasCompressed: Boolean,
)

/**
 * Sliding-window context compression engine that prevents context window overflow
 * during long-running autonomous sessions while preserving team identity and recent turns.
 */
class ContextWindowPruner(
    private val maxContextTokens: Int = 32_000,
    private val preserveRecentTurns: Int = 8,
) {
    fun buildTeamRosterPrompt(agents: List<AgentState>): String {
        val activeRoster = agents.filter { it.config.isEnabled }
        if (activeRoster.isEmpty()) return ""
        return buildString {
            appendLine("## Active AI Team Roster (Remember & use these exact names)")
            activeRoster.forEach { agent ->
                appendLine("- **${agent.config.name}** (Slot: ${agent.config.slot.id}, Role: ${agent.config.role.title}, Model: ${agent.config.modelId})")
            }
            appendLine("Always address teammates by their configured names above when collaborating, handing off tasks, or reviewing code.")
        }
    }

    fun prune(turns: List<ContextTurn>, budgetTokens: Int = maxContextTokens): PrunedContextResult {
        val initialTokens = turns.sumOf { it.estimatedTokens }
        if (initialTokens <= budgetTokens || turns.size <= preserveRecentTurns + 1) {
            return PrunedContextResult(
                turns = turns,
                totalTokens = initialTokens,
                prunedCount = 0,
                wasCompressed = false,
            )
        }

        val systemTurns = turns.filter { it.role == "system" }
        val nonSystem = turns.filterNot { it.role == "system" }
        val recentCount = preserveRecentTurns.coerceAtMost(nonSystem.size)
        val recent = nonSystem.takeLast(recentCount)
        val middle = nonSystem.dropLast(recentCount)

        val summaryText = buildString {
            append("[Compressed History Summary: ${middle.size} earlier turns condensed] ")
            middle.takeLast(5).forEach { t ->
                val snippet = t.content.replace('\n', ' ').take(100)
                append("${t.speakerName}: $snippet | ")
            }
        }
        val summaryTurn = ContextTurn(
            role = "system",
            speakerName = "ContextPruner",
            content = summaryText,
        )

        val resultTurns = systemTurns + summaryTurn + recent
        val finalTokens = resultTurns.sumOf { it.estimatedTokens }

        return PrunedContextResult(
            turns = resultTurns,
            totalTokens = finalTokens,
            prunedCount = middle.size,
            wasCompressed = true,
        )
    }
}
