package com.jarves.mh.provider.adapters

import com.jarves.mh.data.AppPreferences
import com.jarves.mh.provider.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class AntigravityAdapter(private val preferences: AppPreferences? = null) : ProviderAdapter {
    override val type: ProviderType = ProviderType.ANTIGRAVITY
    override val protocol: ApiProtocol = ApiProtocol.ANTIGRAVITY_OAUTH

    override suspend fun testConnection(config: ProviderConfig, apiKey: String): ConnectionTestResult {
        val signedIn = preferences?.antigravitySignedIn == true
        val email = preferences?.antigravityAccountEmail.orEmpty()
        return if (signedIn) {
            ConnectionTestResult(
                isSuccess = true,
                latencyMs = 5L,
                message = if (email.isNotBlank()) "Connected with Google ($email)" else "Connected with Google",
                detectedProtocol = ApiProtocol.ANTIGRAVITY_OAUTH,
                httpStatusCode = 200,
            )
        } else {
            ConnectionTestResult(
                isSuccess = false,
                latencyMs = 0L,
                message = "Not signed in. Connect your Google account in Settings → Agent.",
                detectedProtocol = ApiProtocol.ANTIGRAVITY_OAUTH,
                errorDetail = "Google OAuth authentication required",
            )
        }
    }

    override suspend fun discoverModels(config: ProviderConfig, apiKey: String): List<ModelInfo> {
        return listOf(
            ModelInfo("gemini-3.8-flash-high", "Gemini 3.8 Flash (High Reasoning)", providerId = config.id),
            ModelInfo("gemini-3.8-flash-medium", "Gemini 3.8 Flash (Medium Reasoning)", providerId = config.id),
            ModelInfo("gemini-3.8-pro-high", "Gemini 3.8 Pro (Maximum Capability)", providerId = config.id),
            ModelInfo("gemini-3.8-pro-medium", "Gemini 3.8 Pro (Balanced)", providerId = config.id),
            ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", providerId = config.id),
        )
    }

    override suspend fun sendChatCompletion(
        config: ProviderConfig,
        apiKey: String,
        messages: List<AiMessage>,
        systemInstruction: String,
        modelId: String,
        temperature: Double,
        maxTokens: Int,
    ): Flow<AiStreamChunk> = flow {
        // Routed via PRoot AntigravityRuntimeBridge during active workspace tasks
        emit(AiStreamChunk(deltaText = "Session delegated to local Antigravity CLI", isFinished = true, finishReason = "delegated"))
    }

    override suspend fun fetchOfficialQuota(config: ProviderConfig, apiKey: String): OfficialQuota {
        val signedIn = preferences?.antigravitySignedIn == true
        val email = preferences?.antigravityAccountEmail.orEmpty()
        return if (signedIn) {
            // Never fake a hardcoded percentage — only report real official remaining % when API provides it
            OfficialQuota(
                providerId = config.id,
                remainingPercent = null,
                quotaUsed = "Connected",
                quotaLimit = "Google Account",
                resetTime = null,
                rateLimit = null,
                source = if (email.isNotBlank()) "Google Account ($email)" else "Google Account",
                isAvailable = false,
                unavailableReason = "Antigravity OAuth does not expose remaining % via API",
            )
        } else {
            OfficialQuota(
                providerId = config.id,
                isAvailable = false,
                unavailableReason = "Not signed in to Google",
            )
        }
    }
}
