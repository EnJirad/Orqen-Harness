package com.jarves.mh.provider.adapters

import com.jarves.mh.provider.core.*
import kotlinx.coroutines.flow.Flow

class CustomProviderAdapter : ProviderAdapter {
    override val type: ProviderType = ProviderType.CUSTOM_API
    override val protocol: ApiProtocol = ApiProtocol.OPENAI_CHAT

    private val openAiAdapter = OpenAiCompatibleAdapter()
    private val anthropicAdapter = AnthropicAdapter()
    private val geminiAdapter = GeminiAdapter()

    private fun delegate(config: ProviderConfig): ProviderAdapter = when (config.protocol) {
        ApiProtocol.ANTHROPIC_MESSAGES -> anthropicAdapter
        ApiProtocol.GEMINI_DEV_API -> geminiAdapter
        else -> openAiAdapter
    }

    override suspend fun testConnection(config: ProviderConfig, apiKey: String): ConnectionTestResult {
        return delegate(config).testConnection(config, apiKey)
    }

    override suspend fun discoverModels(config: ProviderConfig, apiKey: String): List<ModelInfo> {
        return delegate(config).discoverModels(config, apiKey)
    }

    override suspend fun sendChatCompletion(
        config: ProviderConfig,
        apiKey: String,
        messages: List<AiMessage>,
        systemInstruction: String,
        modelId: String,
        temperature: Double,
        maxTokens: Int,
    ): Flow<AiStreamChunk> {
        return delegate(config).sendChatCompletion(
            config, apiKey, messages, systemInstruction, modelId, temperature, maxTokens
        )
    }

    override suspend fun fetchOfficialQuota(config: ProviderConfig, apiKey: String): OfficialQuota {
        return delegate(config).fetchOfficialQuota(config, apiKey)
    }
}
