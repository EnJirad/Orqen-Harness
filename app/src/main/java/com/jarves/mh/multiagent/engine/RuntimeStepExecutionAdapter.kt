package com.jarves.mh.multiagent.engine

import com.jarves.mh.multiagent.collab.AgentCommunicationManager
import com.jarves.mh.multiagent.model.AgentConfig
import com.jarves.mh.multiagent.model.AgentRole
import com.jarves.mh.multiagent.model.AgentRoleType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.multiagent.persistence.AgentMessageKind
import com.jarves.mh.provider.core.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Production [StepExecutionAdapter] that bridges [PersistentTaskOrchestrator] directly to
 * ORQEN's real [AIExecutionRuntime] (backed by [TeamAgentRunnerRuntimeAdapter] / [TeamAgentRunner]).
 *
 * Guarantees:
 * - Never substitutes simulated/fake AI output in production.
 * - Uses an **activity-resetting inactivity watchdog**: as long as the agent emits progress,
 *   streaming chunks, or file writes, the timer continuously resets so long coding turns never
 *   prematurely time out; only true stalls with zero activity trigger recovery.
 * - Routes structured inter-agent messages emitted by real agents through [AgentCommunicationManager].
 * - Parses explicit review verdicts (`REVIEW_APPROVED` / `REVIEW_REJECTED: ...`) and test verdicts
 *   (`TESTS_PASSED` / `TESTS_FAILED`) without auto-approving rejected work.
 */
class RuntimeStepExecutionAdapter(
    private val runtime: AIExecutionRuntime,
    private val providerConfigResolver: (providerId: String) -> ProviderConfig? = { null },
    private val communicationManager: AgentCommunicationManager? = null,
    private val stepTimeoutMs: Long = 1_800_000L,
    private val mcpManager: com.jarves.mh.mcp.McpConnectionManager? = null,
    private val teamRosterProvider: () -> List<DynamicAgentProfile> = { emptyList() },
    private val sessionManager: com.jarves.mh.runtime.AgentSessionManager? = null,
    private val onProgress: suspend (taskId: String, stepId: String, agentId: String, progress: String) -> Unit = { _, _, _, _ -> },
) : StepExecutionAdapter {

    override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
        val agentState = toAgentState(input.agent)
        val providerConfig = providerConfigResolver(input.agent.providerId)
        val rawSlug = input.workspaceDir.name.ifBlank { input.task.projectId }
        val projectSlug = rawSlug.lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(60)
            .ifBlank { "team-project" }

        // Inject connected & authorized MCP tools into the agent's prompt context (Part 8)
        val mcpPromptBlock = mcpManager?.buildAuthorizedToolPromptBlock(
            agentId = input.agent.agentId,
            workspaceId = input.task.projectId,
        ).orEmpty()
        val effectivePrompt = if (mcpPromptBlock.isNotBlank()) {
            "${input.compactContext}\n\n$mcpPromptBlock"
        } else {
            input.compactContext
        }

        // Owner policy: ALL AI Team agents in Task Mode receive unrestricted access to ALL workspace,
        // shell, build, test, and MCP tools without read-only blocks or permission prompts so testing
        // and cross-agent collaboration are never blocked. (Chat Consultation remains strictly tool-free.)
        val agentSession = sessionManager?.getOrCreateSession(
            agentId = input.agent.agentId,
            projectId = input.task.projectId,
            taskId = input.task.taskId,
            assignmentId = input.step.stepId,
            providerId = input.agent.providerId,
            modelId = input.agent.modelId,
        )

        val request = RuntimeExecutionRequest(
            agent = agentState,
            providerConfig = providerConfig,
            workspaceDir = input.workspaceDir,
            projectSlug = projectSlug,
            prompt = effectivePrompt,
            imageFiles = input.imageFiles,
            taskId = input.task.taskId,
            stepId = input.step.stepId,
            enableWorkspaceTools = true,
            readOnlyTools = false,
            allowSkipPermissions = true,
            sessionId = agentSession?.sessionId,
            nativeConversationId = agentSession?.nativeConversationId,
            agentId = input.agent.agentId,
        )

        val lastActivityAtMs = AtomicLong(System.currentTimeMillis())
        val timedOutByWatchdog = AtomicBoolean(false)
        // For short test timeouts (< 60s), honor exact stepTimeoutMs; in production, allow up to
        // stepTimeoutMs of inactivity while resetting on every streaming/file progress heartbeat.
        val idleLimitMs = stepTimeoutMs.coerceAtLeast(10L)
        val pollIntervalMs = (idleLimitMs / 4L).coerceIn(10L, 2_000L)

        val response = try {
            coroutineScope {
                val execDeferred = async {
                    runtime.execute(request) { progress ->
                        lastActivityAtMs.set(System.currentTimeMillis())
                        onProgress(input.task.taskId, input.step.stepId, input.agent.agentId, progress)
                    }
                }
                val watchdogJob = launch {
                    while (execDeferred.isActive) {
                        delay(pollIntervalMs)
                        val idleFor = System.currentTimeMillis() - lastActivityAtMs.get()
                        if (idleFor >= idleLimitMs) {
                            timedOutByWatchdog.set(true)
                            runtime.cancel(request.requestId)
                            execDeferred.cancel(CancellationException("Inactivity timeout after ${idleLimitMs}ms"))
                            break
                        }
                    }
                }
                try {
                    execDeferred.await()
                } finally {
                    watchdogJob.cancel()
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            runtime.cancel(request.requestId)
            return StepExecutionOutput(
                success = false,
                summary = "",
                error = StructuredErrorClassifier.classify(
                    message = "Execution timed out after ${stepTimeoutMs}ms for step '${input.step.title}'",
                    isTimeout = true,
                ),
            )
        } catch (cancel: CancellationException) {
            runtime.cancel(request.requestId)
            if (timedOutByWatchdog.get()) {
                return StepExecutionOutput(
                    success = false,
                    summary = "",
                    error = StructuredErrorClassifier.classify(
                        message = "No output or progress for ${idleLimitMs / 1000}s on step '${input.step.title}'",
                        isTimeout = true,
                    ),
                )
            }
            throw cancel
        }

        if (response.cancelled) {
            throw CancellationException("Runtime cancelled request ${response.requestId}")
        }

        if (agentSession != null && !response.nativeConversationId.isNullOrBlank()) {
            sessionManager?.updateNativeConversationId(agentSession.sessionId, response.nativeConversationId)
        }

        if (!response.success) {
            val classified = response.error ?: StructuredErrorClassifier.classify(
                response.outputText.ifBlank { "Provider execution failed (${response.providerId})" },
            )
            return StepExecutionOutput(
                success = false,
                summary = response.outputText,
                modifiedFiles = response.modifiedFiles,
                inputTokens = response.usage.inputTokens,
                outputTokens = response.usage.outputTokens,
                actualCostUsd = response.usage.costUsd ?: 0.0,
                error = classified,
            )
        }

        // Route any structured inter-agent messages emitted in the agent's real response
        if (communicationManager != null) {
            dispatchEmbeddedMessages(
                taskId = input.task.taskId,
                stepId = input.step.stepId,
                fromAgentId = input.agent.agentId,
                outputText = response.outputText,
            )
        }

        // Execute any authorized [MCP_CALL tool="mcp__..." args={...}] requests and return real results (Parts 8, 9, 16)
        var finalOutputText = response.outputText
        var combinedModifiedFiles = response.modifiedFiles
        var combinedToolEvidence = response.toolEvidence
        var combinedTestEvidence = response.testEvidence
        var combinedBuildEvidence = response.buildEvidence

        // Phase D (6.3): Handle inter-agent assistance requests [HELP to=<agentId>] <question>
        val helpRegex = Regex("""\[HELP\s+to=([A-Za-z0-9_.-]+)\]\s*(.+)""")
        val helpMatch = helpRegex.find(finalOutputText)
        if (helpMatch != null) {
            val helperTarget = helpMatch.groupValues[1].trim()
            val question = helpMatch.groupValues[2].trim()
            val roster = teamRosterProvider().filter { it.isEnabled && it.agentId != input.agent.agentId }
            val helperProfile = roster.firstOrNull {
                it.agentId.equals(helperTarget, ignoreCase = true) ||
                    it.name.equals(helperTarget, ignoreCase = true) ||
                    it.roleTitle.contains(helperTarget, ignoreCase = true) ||
                    helperTarget.lowercase() in it.capabilities
            } ?: roster.firstOrNull { "architect" in it.capabilities || "lead" in it.capabilities }
            if (helperProfile != null && question.isNotBlank()) {
                communicationManager?.sendDirect(
                    taskId = input.task.taskId,
                    fromAgentId = input.agent.agentId,
                    toAgentId = helperProfile.agentId,
                    type = AgentMessageKind.AGENT_TO_AGENT,
                    content = "HELP REQUEST: $question",
                    relatedStepId = input.step.stepId,
                )
                onProgress(input.task.taskId, input.step.stepId, input.agent.agentId, "Requesting assistance from ${helperProfile.name}…")
                val helperReq = RuntimeExecutionRequest(
                    agent = toAgentState(helperProfile),
                    providerConfig = providerConfigResolver(helperProfile.providerId),
                    workspaceDir = input.workspaceDir,
                    projectSlug = projectSlug,
                    prompt = "Assistance requested by ${input.agent.name} (${input.agent.roleTitle}) on step '${input.step.title}':\n$question\n\nInspect the workspace if needed and provide a concise, actionable solution.",
                    taskId = input.task.taskId,
                    stepId = input.step.stepId,
                    enableWorkspaceTools = true,
                    readOnlyTools = true,
                )
                val helperRes = runCatching {
                    runtime.execute(helperReq) { prog ->
                        onProgress(input.task.taskId, input.step.stepId, helperProfile.agentId, prog)
                    }
                }.getOrNull()
                if (helperRes != null && helperRes.success && helperRes.outputText.isNotBlank()) {
                    communicationManager?.sendDirect(
                        taskId = input.task.taskId,
                        fromAgentId = helperProfile.agentId,
                        toAgentId = input.agent.agentId,
                        type = AgentMessageKind.AGENT_TO_AGENT,
                        content = "HELP RESPONSE: ${helperRes.outputText.take(1500)}",
                        relatedStepId = input.step.stepId,
                    )
                    val resumeReq = request.copy(
                        prompt = "$effectivePrompt\n\nPrevious attempt:\n${finalOutputText.take(1800)}\n\nAssistance from ${helperProfile.name} (${helperProfile.roleTitle}):\n${helperRes.outputText.take(2000)}\n\nApply this solution and complete your step now.",
                    )
                    val resumed = runCatching {
                        runtime.execute(resumeReq) { prog ->
                            onProgress(input.task.taskId, input.step.stepId, input.agent.agentId, prog)
                        }
                    }.getOrNull()
                    if (resumed != null && resumed.success && resumed.outputText.isNotBlank()) {
                        finalOutputText = resumed.outputText
                        combinedModifiedFiles = (combinedModifiedFiles + resumed.modifiedFiles).distinct()
                        combinedToolEvidence = combinedToolEvidence + resumed.toolEvidence
                        combinedTestEvidence = combinedTestEvidence + resumed.testEvidence
                        combinedBuildEvidence = combinedBuildEvidence + resumed.buildEvidence
                    }
                }
            }
        }

        if (mcpManager != null) {
            val mcpResults = executeEmbeddedMcpCalls(
                input = input,
                outputText = finalOutputText,
            )
            if (mcpResults.isNotEmpty()) {
                val resultsBlock = mcpResults.joinToString("\n") { res ->
                    "[MCP_RESULT tool=\"${res.toolId}\" ok=${res.success}] ${res.contentText.take(1200)}"
                }
                // Continue the model execution loop with the real MCP tool results!
                val followUpReq = request.copy(
                    prompt = "$effectivePrompt\n\nPrevious response:\n${finalOutputText.take(2000)}\n\nReal MCP tool results:\n$resultsBlock\n\nContinue and complete the step using these real MCP tool results.",
                )
                val followUp = runCatching {
                    runtime.execute(followUpReq) { progress ->
                        onProgress(input.task.taskId, input.step.stepId, input.agent.agentId, progress)
                    }
                }.getOrNull()
                finalOutputText = if (followUp != null && followUp.success && followUp.outputText.isNotBlank()) {
                    combinedModifiedFiles = (combinedModifiedFiles + followUp.modifiedFiles).distinct()
                    combinedToolEvidence = combinedToolEvidence + followUp.toolEvidence
                    combinedTestEvidence = combinedTestEvidence + followUp.testEvidence
                    combinedBuildEvidence = combinedBuildEvidence + followUp.buildEvidence
                    "${followUp.outputText.trim()}\n\n$resultsBlock"
                } else {
                    "${finalOutputText.trim()}\n\n$resultsBlock"
                }
            }
        }

        val (reviewApproved, reviewFeedback) = parseReviewVerdict(
            requiresReview = input.step.requiresReview,
            outputText = finalOutputText,
        )
        val verificationSpec = buildVerificationSpec(
            input = input,
            outputText = finalOutputText,
            testEvidence = combinedTestEvidence,
            buildEvidence = combinedBuildEvidence,
        )

        return StepExecutionOutput(
            success = true,
            summary = finalOutputText.trim(),
            modifiedFiles = combinedModifiedFiles,
            inputTokens = response.usage.inputTokens,
            outputTokens = response.usage.outputTokens,
            actualCostUsd = response.usage.costUsd ?: 0.0,
            reviewApproved = reviewApproved,
            reviewFeedback = reviewFeedback,
            verificationSpec = verificationSpec,
            toolEvidence = combinedToolEvidence,
            testEvidence = combinedTestEvidence,
            buildEvidence = combinedBuildEvidence,
        )
    }

    private suspend fun executeEmbeddedMcpCalls(
        input: StepExecutionInput,
        outputText: String,
    ): List<com.jarves.mh.mcp.McpToolCallResult> {
        val manager = mcpManager ?: return emptyList()
        val callRegex = Regex("""\[MCP_CALL\s+tool="([^"]+)"\s+args=(\{[^\n]*\})\]""")
        val results = mutableListOf<com.jarves.mh.mcp.McpToolCallResult>()
        for (match in callRegex.findAll(outputText).take(6)) {
            val toolId = match.groupValues[1].trim()
            val argsJson = match.groupValues[2].trim()
            val res = manager.executeToolForAgent(
                agentId = input.agent.agentId,
                agentRole = input.agent.roleTitle,
                namespacedToolId = toolId,
                argumentsJson = argsJson,
                workspaceId = input.task.projectId,
                taskId = input.task.taskId,
                activeTaskAgentIds = setOf(input.agent.agentId),
                autoRequestFromSpv = true,
            )
            results += res
        }
        return results
    }

    private fun toAgentState(profile: DynamicAgentProfile): AgentState {
        val slot = profile.legacySlot ?: AgentSlot.AGENT_1
        val roleType = when {
            "lead" in profile.capabilities || "architect" in profile.capabilities -> AgentRoleType.TEAM_LEADER
            "review" in profile.capabilities || "qa" in profile.capabilities || "test" in profile.capabilities -> AgentRoleType.TESTER
            "backend" in profile.capabilities -> AgentRoleType.BACKEND_ENGINEER
            "frontend" in profile.capabilities -> AgentRoleType.FRONTEND_ENGINEER
            else -> AgentRoleType.SOFTWARE_ENGINEER
        }
        val config = AgentConfig(
            slot = slot,
            name = profile.name,
            role = AgentRole(
                type = roleType,
                title = profile.roleTitle,
                systemInstructions = profile.systemInstructions,
                responsibilities = profile.capabilities.toList(),
            ),
            providerId = profile.providerId,
            modelId = profile.modelId,
            isEnabled = profile.isEnabled,
        )
        return AgentState(config = config)
    }

    private fun dispatchEmbeddedMessages(
        taskId: String,
        stepId: String,
        fromAgentId: String,
        outputText: String,
    ) {
        val msgRegex = Regex("""\[MSG\s+to=([A-Za-z0-9_.*-]+)\]\s*(.+)""")
        for (match in msgRegex.findAll(outputText)) {
            val target = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            if (body.isBlank()) continue
            if (target == AgentCommunicationManager.BROADCAST_TARGET || target.equals("ALL", ignoreCase = true)) {
                communicationManager?.broadcast(
                    taskId = taskId,
                    fromAgentId = fromAgentId,
                    type = AgentMessageKind.AGENT_TO_AGENT,
                    content = body,
                    relatedStepId = stepId,
                )
            } else {
                communicationManager?.sendDirect(
                    taskId = taskId,
                    fromAgentId = fromAgentId,
                    toAgentId = target,
                    type = AgentMessageKind.AGENT_TO_AGENT,
                    content = body,
                    relatedStepId = stepId,
                )
            }
        }
    }

    private fun parseReviewVerdict(
        requiresReview: Boolean,
        outputText: String,
    ): Pair<Boolean, String?> {
        if (!requiresReview) return true to null
        val rejectMatch = Regex("""(?:REVIEW_REJECTED|CHANGES_REQUESTED)\s*:?\s*(.*)""", RegexOption.IGNORE_CASE)
            .find(outputText)
        if (rejectMatch != null) {
            val feedback = rejectMatch.groupValues[1].trim().ifBlank { outputText.take(400) }
            return false to feedback
        }
        val approved = Regex("""\bREVIEW_APPROVED\b""", RegexOption.IGNORE_CASE).containsMatchIn(outputText)
        return if (approved) {
            true to null
        } else {
            // Never auto-approve a review step that did not explicitly emit REVIEW_APPROVED!
            false to "Reviewer output did not include explicit approval (REVIEW_APPROVED)."
        }
    }

    private fun buildVerificationSpec(
        input: StepExecutionInput,
        outputText: String,
        testEvidence: List<com.jarves.mh.runtime.TestExecutionEvidence> = emptyList(),
        buildEvidence: List<com.jarves.mh.runtime.BuildVerificationEvidence> = emptyList(),
    ): VerificationSpec? {
        if (testEvidence.isNotEmpty()) {
            val lastTest = testEvidence.last()
            return VerificationSpec(
                mode = VerificationMode.TEST_VERIFICATION,
                testsPassed = lastTest.outcome == com.jarves.mh.runtime.TestExecutionOutcome.TESTS_PASSED && lastTest.exitCode == 0,
                testEvidence = testEvidence,
            )
        }
        if (buildEvidence.isNotEmpty()) {
            val lastBuild = buildEvidence.last()
            return VerificationSpec(
                mode = VerificationMode.BUILD_VERIFICATION,
                commandExitCode = lastBuild.exitCode,
                buildEvidence = buildEvidence,
                expectedApkVariant = if (lastBuild.apkPath != null) lastBuild.buildVariant else null,
            )
        }
        // Only explicit Review/QA/Verification steps (requiresReview == true) require mandatory
        // TEST_VERIFICATION evidence on that step; Developer implementation steps (in any language)
        // are never misclassified as QA steps just because the title lacks the English word "implement".
        if (!input.step.requiresReview) return null

        // NEVER treat the prose text "TESTS_PASSED" as proof of test execution!
        // Require real TestExecutionEvidence with exitCode == 0.
        return VerificationSpec(
            mode = VerificationMode.TEST_VERIFICATION,
            testsPassed = false,
            testEvidence = emptyList(),
        )
    }
}
