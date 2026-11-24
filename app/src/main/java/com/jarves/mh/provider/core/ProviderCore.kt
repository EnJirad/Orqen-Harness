package com.jarves.mh.provider.core

import kotlinx.coroutines.flow.Flow
import java.util.UUID

enum class ProviderType(val displayName: String, val supportsOAuth: Boolean = false) {
    ANTIGRAVITY("Antigravity OAuth", supportsOAuth = true),
    OPENAI_COMPATIBLE("OpenAI-Compatible"),
    ANTHROPIC("Anthropic API"),
    GEMINI("Google Gemini Developer API"),
    DEEPSEEK("DeepSeek"),
    OPENROUTER("OpenRouter"),
    CUSTOM_API("Custom API Endpoint"),
}

enum class ApiProtocol(val title: String, val defaultEndpointPath: String) {
    OPENAI_CHAT("OpenAI Chat Completions", "/v1/chat/completions"),
    ANTHROPIC_MESSAGES("Anthropic Messages", "/v1/messages"),
    GEMINI_DEV_API("Google Gemini generateContent", "/v1beta/models"),
    ANTIGRAVITY_OAUTH("Antigravity OAuth Loopback", ""),
}

data class ProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val apiKey: String = "",
    val protocol: ApiProtocol = ApiProtocol.OPENAI_CHAT,
    val customHeaders: Map<String, String> = emptyMap(),
    val timeoutMs: Int = 30_000,
    val isStreamingEnabled: Boolean = true,
    val isEnabled: Boolean = true,
    val selectedModelId: String = "",
    val availableModels: List<String> = emptyList(),
    val organizationId: String? = null,
)

data class ConnectionTestResult(
    val isSuccess: Boolean,
    val latencyMs: Long = 0L,
    val message: String,
    val detectedProtocol: ApiProtocol? = null,
    val httpStatusCode: Int = 0,
    val errorDetail: String? = null,
    val modelsFoundCount: Int = 0,
)

data class ModelInfo(
    val id: String,
    val displayName: String = id,
    val providerId: String = "",
    val contextWindow: Int? = null,
    val isToolSupported: Boolean = true,
    val isVisionSupported: Boolean = false,
    val inputPricePerM: Double = 0.0,
    val outputPricePerM: Double = 0.0,
    val isFree: Boolean = false,
)

data class ToolCallInfo(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val argumentsJson: String,
)

data class AiMessage(
    val role: String, // "system", "user", "assistant", "tool"
    val content: String,
    val name: String? = null,
    val toolCalls: List<ToolCallInfo> = emptyList(),
    val toolCallId: String? = null,
)

data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val cachedTokens: Int = 0,
)

data class AiStreamChunk(
    val deltaText: String,
    val isFinished: Boolean = false,
    val finishReason: String? = null,
    val usage: TokenUsage? = null,
    val toolCall: ToolCallInfo? = null,
)

interface ProviderAdapter {
    val type: ProviderType
    val protocol: ApiProtocol

    suspend fun testConnection(config: ProviderConfig, apiKey: String): ConnectionTestResult

    suspend fun discoverModels(config: ProviderConfig, apiKey: String): List<ModelInfo>

    suspend fun sendChatCompletion(
        config: ProviderConfig,
        apiKey: String,
        messages: List<AiMessage>,
        systemInstruction: String = "",
        modelId: String,
        temperature: Double = 0.7,
        maxTokens: Int = 4096,
    ): Flow<AiStreamChunk>

    suspend fun fetchOfficialQuota(config: ProviderConfig, apiKey: String): OfficialQuota
}
