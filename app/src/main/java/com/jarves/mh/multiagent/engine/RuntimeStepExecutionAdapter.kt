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
import kotlinx.coroutines.withTimeout

/**
 * Production [StepExecutionAdapter] that bridges [PersistentTaskOrchestrator] directly to
 * ORQEN's real [AIExecutionRuntime] (backed by [TeamAgentRunnerRuntimeAdapter] / [TeamAgentRunner]).
 *
 * Guarantees:
 * - Never substitutes simulated/fake AI output in production.
 * - Enforces configurable per-step execution timeouts and propagates cooperative cancellation.
 * - Routes structured inter-agent messages emitted by real agents through [AgentCommunicationManager].
 * - Parses explicit review verdicts (`REVIEW_APPROVED` / `REVIEW_REJECTED: ...`) and test verdicts
 *   (`TESTS_PASSED` / `TESTS_FAILED`) without auto-approving rejected work.
 */
class RuntimeStepExecutionAdapter(
    private val runtime: AIExecutionRuntime,
    private val providerConfigResolver: (providerId: String) -> ProviderConfig? = { null },
    private val communicationManager: AgentCommunicationManager? = null,
    private val stepTimeoutMs: Long = 180_000L,
    private val onProgress: suspend (taskId: String, stepId: String, agentId: String, progress: String) -> Unit = { _, _, _, _ -> },
) : StepExecutionAdapter {

    override suspend fun executeStep(input: StepExecutionInput): StepExecutionOutput {
        val agentState = toAgentState(input.agent)
        val providerConfig = providerConfigResolver(input.agent.providerId)
        val projectSlug = input.workspaceDir.name.ifBlank { input.task.projectId }
        val request = RuntimeExecutionRequest(
            agent = agentState,
            providerConfig = providerConfig,
            workspaceDir = input.workspaceDir,
            projectSlug = projectSlug,
            prompt = input.compactContext,
        )

        val response = try {
            withTimeout(stepTimeoutMs) {
                runtime.execute(request) { progress ->
                    onProgress(input.task.taskId, input.step.stepId, input.agent.agentId, progress)
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
            throw cancel
        }

        if (response.cancelled) {
            throw CancellationException("Runtime cancelled request ${response.requestId}")
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

        val (reviewApproved, reviewFeedback) = parseReviewVerdict(
            requiresReview = input.step.requiresReview,
            outputText = response.outputText,
        )
        val verificationSpec = buildVerificationSpec(input, response.outputText)

        return StepExecutionOutput(
            success = true,
            summary = response.outputText.trim(),
            modifiedFiles = response.modifiedFiles,
            inputTokens = response.usage.inputTokens,
            outputTokens = response.usage.outputTokens,
            actualCostUsd = response.usage.costUsd ?: 0.0,
            reviewApproved = reviewApproved,
            reviewFeedback = reviewFeedback,
            verificationSpec = verificationSpec,
        )
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
        val approved = Regex("""\bREVIEW_APPROVED\b|\bAPPROVED\b""", RegexOption.IGNORE_CASE).containsMatchIn(outputText)
        return if (approved) {
            true to null
        } else {
            // Never auto-approve a review step that did not explicitly approve!
            false to "Reviewer output did not include explicit approval (REVIEW_APPROVED)."
        }
    }

    private fun buildVerificationSpec(
        input: StepExecutionInput,
        outputText: String,
    ): VerificationSpec? {
        val isTestStep = "test" in input.agent.capabilities ||
            input.step.title.contains("test", ignoreCase = true) ||
            input.step.title.contains("verify", ignoreCase = true)
        if (!isTestStep) return null

        val explicitPass = Regex("""\bTESTS_PASSED\b""", RegexOption.IGNORE_CASE).containsMatchIn(outputText)
        val explicitFail = Regex("""\bTESTS_FAILED\b""", RegexOption.IGNORE_CASE).containsMatchIn(outputText)
        if (!explicitPass && !explicitFail) return null
        return VerificationSpec(
            mode = VerificationMode.TEST_VERIFICATION,
            testsPassed = explicitPass && !explicitFail,
        )
    }
}
