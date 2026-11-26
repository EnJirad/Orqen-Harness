package com.jarves.mh.multiagent.engine

import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.provider.core.ProviderConfig
import com.jarves.mh.runtime.TeamAgentRunner
import java.io.File
import java.util.UUID

enum class UsageSource {
    ACTUAL_PROVIDER,
    ESTIMATED_LOCAL,
    UNKNOWN,
}

data class TokenUsageReport(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val costUsd: Double? = null,
    val source: UsageSource = UsageSource.UNKNOWN,
)

data class RuntimeCapabilities(
    val supportsStreaming: Boolean,
    val supportsVision: Boolean,
    val supportsToolExecution: Boolean,
    val supportsPersistentSessions: Boolean,
)

data class RuntimeExecutionRequest(
    val requestId: String = "req-${UUID.randomUUID().toString().take(8)}",
    val agent: AgentState,
    val providerConfig: ProviderConfig?,
    val workspaceDir: File,
    val projectSlug: String,
    val prompt: String,
    val useIsolatedAgyHome: Boolean = false,
    val allowSkipPermissions: Boolean = false,
    val imageFiles: List<File> = emptyList(),
    val knownAgyModels: List<String> = emptyList(),
)

data class RuntimeExecutionResponse(
    val requestId: String,
    val providerId: String,
    val modelId: String,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val success: Boolean,
    val outputText: String,
    val modifiedFiles: List<String> = emptyList(),
    val usage: TokenUsageReport = TokenUsageReport(),
    val error: ClassifiedError? = null,
    val cancelled: Boolean = false,
)

/**
 * Unified AI execution runtime interface isolating provider/CLI protocols
 * from the persistent task orchestrator.
 */
interface AIExecutionRuntime {
    suspend fun execute(
        request: RuntimeExecutionRequest,
        onProgress: suspend (String) -> Unit = {},
    ): RuntimeExecutionResponse

    fun cancel(requestId: String): Boolean
    fun getProviderCapabilities(providerId: String): RuntimeCapabilities
}

/**
 * Production adapter delegating to ORQEN's real [TeamAgentRunner]
 * (Antigravity CLI, Anthropic, OpenAI/OpenRouter, Gemini, DeepSeek, Custom).
 */
class TeamAgentRunnerRuntimeAdapter(
    private val runner: TeamAgentRunner,
) : AIExecutionRuntime {
    override suspend fun execute(
        request: RuntimeExecutionRequest,
        onProgress: suspend (String) -> Unit,
    ): RuntimeExecutionResponse {
        val started = System.currentTimeMillis()
        val res = runner.executeTurn(
            agent = request.agent,
            providerConfig = request.providerConfig,
            workspaceDir = request.workspaceDir,
            projectSlug = request.projectSlug,
            prompt = request.prompt,
            useIsolatedAgyHome = request.useIsolatedAgyHome,
            enableCache = true,
            allowSkipPermissions = request.allowSkipPermissions,
            imageFiles = request.imageFiles,
            knownAgyModels = request.knownAgyModels,
            onProgress = onProgress,
        )
        val completed = System.currentTimeMillis()
        val estIn = (request.prompt.length / 4L).coerceAtLeast(1L)
        val estOut = (res.responseText.length / 4L).coerceAtLeast(0L)
        return RuntimeExecutionResponse(
            requestId = request.requestId,
            providerId = request.agent.config.providerId,
            modelId = request.agent.config.modelId,
            startedAtMs = started,
            completedAtMs = completed,
            success = !res.isError,
            outputText = res.responseText,
            modifiedFiles = res.writtenFiles,
            usage = TokenUsageReport(
                inputTokens = estIn,
                outputTokens = estOut,
                costUsd = null,
                source = UsageSource.ESTIMATED_LOCAL,
            ),
            error = res.classifiedError,
        )
    }

    override fun cancel(requestId: String): Boolean = true

    override fun getProviderCapabilities(providerId: String): RuntimeCapabilities {
        val isCli = providerId == "preset-antigravity"
        return RuntimeCapabilities(
            supportsStreaming = true,
            supportsVision = true,
            supportsToolExecution = isCli,
            supportsPersistentSessions = isCli,
        )
    }
}
