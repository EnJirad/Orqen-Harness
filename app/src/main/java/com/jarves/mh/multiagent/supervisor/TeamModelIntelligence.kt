package com.jarves.mh.multiagent.supervisor

import com.jarves.mh.multiagent.model.AgentRoleType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.provider.core.ProviderConfig
import com.jarves.mh.provider.core.ProviderType

enum class TeamOptimizationGoal {
    BALANCED,
    LOW_COST,
    HIGH_QUALITY,
    ANDROID_DEVELOPMENT,
}

data class ModelCapabilityProfile(
    val providerId: String,
    val providerName: String,
    val providerType: ProviderType,
    val modelId: String,
    val codingScore: Int,         // 1..10
    val reasoningScore: Int,      // 1..10
    val reviewScore: Int,         // 1..10
    val speedScore: Int,          // 1..10
    val costTier: Int,            // 1 = cheapest, 5 = most expensive
    val supportsTools: Boolean,
    val supportsStreaming: Boolean,
    val contextWindowTokens: Int,
)

data class AgentModelRecommendation(
    val slot: AgentSlot,
    val agentName: String,
    val roleType: AgentRoleType,
    val previousProviderId: String,
    val previousModelId: String,
    val recommendedProviderId: String,
    val recommendedModelId: String,
    val rationale: String,
) {
    val changed: Boolean
        get() = previousProviderId != recommendedProviderId || previousModelId != recommendedModelId
}

enum class SpvManagementCommandKind {
    CONFIGURE_ANDROID_TEAM,
    CHOOSE_BEST_MODELS,
    OPTIMIZE_LOW_COST,
    CHECK_AGENT_TOOLS,
    FIND_FAILING_AGENT,
    RECONFIGURE_TEAM,
}

data class SpvManagementCommand(
    val kind: SpvManagementCommandKind,
    val rawText: String,
)

/**
 * Phase H — Model Management & Intelligent Agent Configuration.
 *
 * Evaluates actual configured providers & models (never hardcoding unavailable models),
 * scores them by coding/reasoning/review/speed/cost/tool-calling capabilities,
 * recommends optimal role-based assignments, and parses natural-language Owner
 * team-management commands for SPV.
 */
object TeamModelIntelligence {

    fun buildModelPool(
        providerConfigs: List<ProviderConfig>,
        hasApiKey: (String) -> Boolean,
        isAntigravitySignedIn: Boolean,
        knownAgyModels: List<String> = emptyList(),
    ): List<ModelCapabilityProfile> {
        val profiles = mutableListOf<ModelCapabilityProfile>()
        for (cfg in providerConfigs.filter { it.isEnabled }) {
            val available = when (cfg.type) {
                ProviderType.ANTIGRAVITY -> isAntigravitySignedIn
                else -> hasApiKey(cfg.id) || cfg.type == ProviderType.CUSTOM_API
            }
            if (!available) continue
            val models = when {
                cfg.type == ProviderType.ANTIGRAVITY && knownAgyModels.isNotEmpty() -> knownAgyModels
                cfg.availableModels.isNotEmpty() -> cfg.availableModels
                cfg.selectedModelId.isNotBlank() -> listOf(cfg.selectedModelId)
                else -> emptyList()
            }.distinct().filter { it.isNotBlank() }

            for (modelId in models) {
                profiles += scoreModel(cfg, modelId)
            }
        }
        return profiles
    }

    fun scoreModel(cfg: ProviderConfig, modelId: String): ModelCapabilityProfile {
        val lower = modelId.lowercase()
        val isMiniOrFlash = lower.contains("mini") || lower.contains("flash") || lower.contains("haiku") || lower.contains("nano") || lower.contains("small")
        val isReasoning = lower.contains("o1") || lower.contains("o3") || lower.contains("r1") || lower.contains("opus") || lower.contains("pro") || lower.contains("thinking") || lower.contains("sonnet")
        val isStrongCoder = lower.contains("sonnet") || lower.contains("codex") || lower.contains("coder") || lower.contains("deepseek") || lower.contains("gpt-4") || lower.contains("gpt-5") || lower.contains("gemini-2.5") || lower.contains("gemini-3") || lower.contains("claude")

        val coding = when {
            lower.contains("sonnet") || lower.contains("codex") || lower.contains("gpt-5") -> 10
            lower.contains("opus") || lower.contains("deepseek-chat") || lower.contains("gemini-2.5-pro") || lower.contains("gemini-3") -> 9
            isStrongCoder -> 8
            isMiniOrFlash -> 7
            else -> 6
        }
        val reasoning = when {
            lower.contains("opus") || lower.contains("o3") || lower.contains("r1") || lower.contains("pro") || lower.contains("thinking") -> 10
            isReasoning -> 9
            isMiniOrFlash -> 7
            else -> 6
        }
        val review = ((reasoning * 6 + coding * 4) / 10).coerceIn(1, 10)
        val speed = when {
            isMiniOrFlash -> 9
            lower.contains("sonnet") || lower.contains("gpt-4o") || lower.contains("deepseek") -> 7
            lower.contains("opus") || lower.contains("o1") || lower.contains("r1") -> 4
            else -> 6
        }
        val costTier = when {
            cfg.type == ProviderType.ANTIGRAVITY -> 1
            isMiniOrFlash || lower.contains("deepseek") -> 1
            lower.contains("opus") || lower.contains("o1") || lower.contains("o3") -> 5
            lower.contains("sonnet") || lower.contains("pro") || lower.contains("gpt-4") -> 3
            else -> 2
        }
        val contextWindow = when {
            lower.contains("gemini") -> 1_000_000
            lower.contains("claude") -> 200_000
            lower.contains("gpt-4") || lower.contains("gpt-5") || lower.contains("o3") -> 128_000
            else -> 64_000
        }
        return ModelCapabilityProfile(
            providerId = cfg.id,
            providerName = cfg.name,
            providerType = cfg.type,
            modelId = modelId,
            codingScore = coding,
            reasoningScore = reasoning,
            reviewScore = review,
            speedScore = speed,
            costTier = costTier,
            supportsTools = true,
            supportsStreaming = true,
            contextWindowTokens = contextWindow,
        )
    }

    fun toModelCandidates(profiles: List<ModelCapabilityProfile>): List<ModelCandidate> =
        profiles.map { p ->
            val caps = mutableSetOf("code", "fix")
            if (p.reasoningScore >= 7) caps.addAll(listOf("lead", "architect", "plan"))
            if (p.reviewScore >= 7) caps.addAll(listOf("review", "qa", "test"))
            ModelCandidate(
                providerId = p.providerId,
                modelId = p.modelId,
                capabilities = caps,
                supportsTools = p.supportsTools,
                supportsStreaming = p.supportsStreaming,
                contextWindowTokens = p.contextWindowTokens,
                estimatedCostPer1kTokens = p.costTier * 0.002,
                priority = ((p.codingScore + p.reasoningScore) / 2),
                isEnabled = true,
            )
        }

    fun recommendModelAssignments(
        agents: List<AgentState>,
        pool: List<ModelCapabilityProfile>,
        goal: TeamOptimizationGoal = TeamOptimizationGoal.BALANCED,
    ): List<AgentModelRecommendation> {
        if (pool.isEmpty()) return emptyList()
        return agents.filter { it.config.isEnabled }.map { agent ->
            val roleType = agent.config.role.type
            val ranked = pool.sortedByDescending { m ->
                when (goal) {
                    TeamOptimizationGoal.LOW_COST -> {
                        val quality = when (roleType) {
                            AgentRoleType.TEAM_LEADER -> m.reasoningScore
                            AgentRoleType.TESTER, AgentRoleType.SECURITY_ENGINEER -> m.reviewScore
                            else -> m.codingScore
                        }
                        (6 - m.costTier) * 15 + m.speedScore * 4 + quality * 3
                    }
                    TeamOptimizationGoal.HIGH_QUALITY -> {
                        when (roleType) {
                            AgentRoleType.TEAM_LEADER -> m.reasoningScore * 12 + m.codingScore * 5
                            AgentRoleType.TESTER, AgentRoleType.SECURITY_ENGINEER -> m.reviewScore * 12 + m.reasoningScore * 5
                            else -> m.codingScore * 12 + m.reasoningScore * 4 + m.speedScore * 2
                        }
                    }
                    TeamOptimizationGoal.ANDROID_DEVELOPMENT,
                    TeamOptimizationGoal.BALANCED,
                    -> {
                        val roleFit = when (roleType) {
                            AgentRoleType.TEAM_LEADER -> m.reasoningScore * 10 + m.codingScore * 4
                            AgentRoleType.TESTER, AgentRoleType.SECURITY_ENGINEER -> m.reviewScore * 9 + m.speedScore * 3
                            else -> m.codingScore * 10 + m.speedScore * 3
                        }
                        roleFit - (m.costTier * 4)
                    }
                }
            }
            val best = ranked.first()
            val rationale = when (goal) {
                TeamOptimizationGoal.LOW_COST -> "Cost-optimized (tier ${best.costTier}/5, speed ${best.speedScore}/10) for ${agent.config.role.title}"
                TeamOptimizationGoal.HIGH_QUALITY -> "Top quality (coding ${best.codingScore}/10, reasoning ${best.reasoningScore}/10) for ${agent.config.role.title}"
                TeamOptimizationGoal.ANDROID_DEVELOPMENT -> "Android dev fit (coding ${best.codingScore}/10, reasoning ${best.reasoningScore}/10, cost tier ${best.costTier}/5)"
                TeamOptimizationGoal.BALANCED -> "Balanced quality/cost (coding ${best.codingScore}/10, reasoning ${best.reasoningScore}/10, cost tier ${best.costTier}/5)"
            }
            AgentModelRecommendation(
                slot = agent.config.slot,
                agentName = agent.config.name,
                roleType = roleType,
                previousProviderId = agent.config.providerId,
                previousModelId = agent.config.modelId,
                recommendedProviderId = best.providerId,
                recommendedModelId = best.modelId,
                rationale = rationale,
            )
        }
    }

    /**
     * Parses natural-language Owner management commands (Phase H 10.3) so SPV can
     * autonomously configure models, roles, tools, and diagnostics.
     */
    fun parseManagementCommand(text: String): SpvManagementCommand? {
        val lower = text.trim().lowercase()
        if (lower.length < 8) return null
        return when {
            (lower.contains("configure") || lower.contains("setup") || lower.contains("ตั้งค่าทีม")) &&
                lower.contains("android") -> SpvManagementCommand(SpvManagementCommandKind.CONFIGURE_ANDROID_TEAM, text)

            (lower.contains("choose") || lower.contains("select") || lower.contains("assign") || lower.contains("เลือกโมเดล")) &&
                lower.contains("model") && (lower.contains("agent") || lower.contains("team") || lower.contains("best")) ->
                SpvManagementCommand(SpvManagementCommandKind.CHOOSE_BEST_MODELS, text)

            (lower.contains("optimize") || lower.contains("reduce") || lower.contains("ประหยัด")) &&
                (lower.contains("cost") || lower.contains("cheap") || lower.contains("low api") || lower.contains("ค่า api")) ->
                SpvManagementCommand(SpvManagementCommandKind.OPTIMIZE_LOW_COST, text)

            (lower.contains("check") || lower.contains("verify") || lower.contains("ตรวจสอบ")) &&
                (lower.contains("tool") || lower.contains("mcp") || lower.contains("permission")) &&
                (lower.contains("agent") || lower.contains("team")) ->
                SpvManagementCommand(SpvManagementCommandKind.CHECK_AGENT_TOOLS, text)

            (lower.contains("find") || lower.contains("which agent") || lower.contains("หา")) &&
                (lower.contains("failure") || lower.contains("failing") || lower.contains("error") || lower.contains("ล้มเหลว")) ->
                SpvManagementCommand(SpvManagementCommandKind.FIND_FAILING_AGENT, text)

            (lower.contains("reconfigure") || lower.contains("improve the team")) &&
                lower.contains("team") ->
                SpvManagementCommand(SpvManagementCommandKind.RECONFIGURE_TEAM, text)

            else -> null
        }
    }
}
