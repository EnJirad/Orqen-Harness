package com.jarves.mh.provider.manager

import com.jarves.mh.multiagent.model.AgentConfig
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.provider.core.ProviderConfig

data class AgentExecutionRoute(
    val slot: AgentSlot,
    val agentName: String,
    val providerConfig: ProviderConfig,
    val modelId: String,
    val isSharedProviderId: Boolean,
    val sharedWithAgentNames: List<String>,
)

/**
 * Multi-Agent Provider & Model Orchestrator (Part 05):
 * - Supports BOTH shared Provider/Account ID across multiple agents using DIFFERENT models
 *   AND separate Provider/Account IDs per agent.
 * - Provides smart fallback routing when a model hits rate limits or errors.
 */
class ProviderOrchestrator {

    fun resolveRoute(
        targetAgent: AgentConfig,
        allAgents: List<AgentState>,
        providerConfigs: List<ProviderConfig>,
    ): AgentExecutionRoute? {
        val provider = providerConfigs.firstOrNull { it.id == targetAgent.providerId }
            ?: providerConfigs.firstOrNull { it.isEnabled }
            ?: return null

        val effectiveModel = targetAgent.modelId.ifBlank {
            provider.selectedModelId.ifBlank {
                provider.availableModels.firstOrNull().orEmpty()
            }
        }

        val peersSharingProvider = allAgents
            .filter { it.config.slot != targetAgent.slot && it.config.providerId == provider.id }
            .map { it.config.name }

        return AgentExecutionRoute(
            slot = targetAgent.slot,
            agentName = targetAgent.name,
            providerConfig = provider,
            modelId = effectiveModel,
            isSharedProviderId = peersSharingProvider.isNotEmpty(),
            sharedWithAgentNames = peersSharingProvider,
        )
    }

    fun resolveAllRoutes(
        agents: List<AgentState>,
        providerConfigs: List<ProviderConfig>,
    ): Map<AgentSlot, AgentExecutionRoute> {
        val result = mutableMapOf<AgentSlot, AgentExecutionRoute>()
        agents.filter { it.config.isEnabled }.forEach { agent ->
            resolveRoute(agent.config, agents, providerConfigs)?.let { route ->
                result[agent.config.slot] = route
            }
        }
        return result
    }

    /**
     * Resolves an authorized fallback model when the current model encounters rate limits or errors.
     * Prefers another available model on the same Provider/Account ID first, then falls back to
     * other enabled providers.
     */
    fun resolveFallbackRoute(
        failedRoute: AgentExecutionRoute,
        providerConfigs: List<ProviderConfig>,
        triedModels: Set<String> = setOf(failedRoute.modelId),
    ): AgentExecutionRoute? {
        // 1. Try another model on the SAME provider/account ID
        val sameProviderAlt = failedRoute.providerConfig.availableModels
            .firstOrNull { it.isNotBlank() && it !in triedModels }
        if (sameProviderAlt != null) {
            return failedRoute.copy(modelId = sameProviderAlt)
        }

        // 2. Try another enabled provider
        for (cfg in providerConfigs) {
            if (!cfg.isEnabled || cfg.id == failedRoute.providerConfig.id) continue
            val candidate = (listOf(cfg.selectedModelId) + cfg.availableModels)
                .firstOrNull { it.isNotBlank() && it !in triedModels }
            if (candidate != null) {
                return failedRoute.copy(
                    providerConfig = cfg,
                    modelId = candidate,
                )
            }
        }
        return null
    }
}
