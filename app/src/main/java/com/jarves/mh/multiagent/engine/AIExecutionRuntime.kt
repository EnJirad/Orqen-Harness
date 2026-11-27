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
    val taskId: String? = null,
    val stepId: String? = null,
    val enableWorkspaceTools: Boolean = true,
    val readOnlyTools: Boolean = false,
    val sessionId: String? = null,
    val nativeConversationId: String? = null,
    val agentId: String? = null,
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
    val toolEvidence: List<com.jarves.mh.runtime.ToolExecutionEvidence> = emptyList(),
    val testEvidence: List<com.jarves.mh.runtime.TestExecutionEvidence> = emptyList(),
    val buildEvidence: List<com.jarves.mh.runtime.BuildVerificationEvidence> = emptyList(),
    val sessionId: String? = null,
    val nativeConversationId: String? = null,
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
    private val allowSandboxedCliTools: Boolean = false,
    private val fileLockManager: com.jarves.mh.multiagent.locks.FileLockManager? = null,
    private val mcpToolInvoker: (suspend (agentId: String, workspaceId: String, taskId: String?, toolId: String, argsJson: String) -> Pair<Boolean, String>)? = null,
) : AIExecutionRuntime {
    override suspend fun execute(
        request: RuntimeExecutionRequest,
        onProgress: suspend (String) -> Unit,
    ): RuntimeExecutionResponse {
        val started = System.currentTimeMillis()
        val isAntigravity = request.agent.config.providerId == "preset-antigravity" ||
            request.providerConfig?.type == com.jarves.mh.provider.core.ProviderType.ANTIGRAVITY
        val effectiveSkipPerms = request.allowSkipPermissions ||
            (allowSandboxedCliTools && isAntigravity && request.enableWorkspaceTools && !request.readOnlyTools)
        val effectiveAgentId = request.agentId ?: request.agent.config.slot.id
        val scopedMcpInvoker: (suspend (String, String) -> Pair<Boolean, String>)? =
            if (mcpToolInvoker != null && request.enableWorkspaceTools) {
                { toolId, argsJson ->
                    mcpToolInvoker.invoke(effectiveAgentId, request.projectSlug, request.taskId, toolId, argsJson)
                }
            } else null
        val res = runner.executeTurn(
            agent = request.agent,
            providerConfig = request.providerConfig,
            workspaceDir = request.workspaceDir,
            projectSlug = request.projectSlug,
            prompt = request.prompt,
            useIsolatedAgyHome = request.useIsolatedAgyHome,
            enableCache = true,
            allowSkipPermissions = effectiveSkipPerms,
            imageFiles = request.imageFiles,
            knownAgyModels = request.knownAgyModels,
            taskId = request.taskId,
            stepId = request.stepId,
            enableWorkspaceTools = request.enableWorkspaceTools,
            readOnlyTools = request.readOnlyTools,
            requestId = request.requestId,
            agentId = effectiveAgentId,
            nativeConversationId = request.nativeConversationId,
            fileLockManager = fileLockManager,
            mcpToolInvoker = scopedMcpInvoker,
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
            toolEvidence = res.toolEvidence,
            testEvidence = res.testEvidence,
            buildEvidence = res.buildEvidence,
            sessionId = request.sessionId,
            nativeConversationId = res.nativeConversationId,
        )
    }

    override fun cancel(requestId: String): Boolean = runner.cancelRequest(requestId)

    override fun getProviderCapabilities(providerId: String): RuntimeCapabilities {
        val isCli = providerId == "preset-antigravity"
        return RuntimeCapabilities(
            supportsStreaming = true,
            supportsVision = true,
            supportsToolExecution = true, // Both CLI and API providers now run real WorkspaceToolExecutor!
            supportsPersistentSessions = isCli,
        )
    }
}
