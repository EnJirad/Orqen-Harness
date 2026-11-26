package com.jarves.mh.multiagent.collab

import com.jarves.mh.multiagent.chat.GroupMessageType
import com.jarves.mh.multiagent.chat.TeamChatManager
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.model.AgentConfig
import com.jarves.mh.multiagent.model.AgentRoleType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.persistence.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * UI projection sink bridging persistent task-scoped [AgentMessageEntity] records from
 * [OrqenTaskStore] (single source of truth) into [TeamChatManager] or any UI observer.
 */
fun interface TeamChatProjectionSink {
    fun onAgentMessage(message: AgentMessageEntity, senderDisplayName: String, senderSlot: AgentSlot?)
}

object TeamChatManagerBridge {
    fun createSink(chatManager: TeamChatManager): TeamChatProjectionSink =
        TeamChatProjectionSink { message, senderDisplayName, senderSlot ->
            val groupType = when (message.messageType) {
                AgentMessageKind.TASK_DELEGATION -> GroupMessageType.TASK_ASSIGNMENT
                AgentMessageKind.REVIEW_REQUEST -> GroupMessageType.REVIEW_REQUEST
                AgentMessageKind.REVIEW_APPROVAL -> GroupMessageType.APPROVAL
                AgentMessageKind.CHANGE_REQUEST, AgentMessageKind.CONFLICT_REPORT -> GroupMessageType.BUG_REPORT
                AgentMessageKind.COMPLETION_REPORT -> GroupMessageType.COMPLETION_REPORT
                AgentMessageKind.SHARED_DECISION, AgentMessageKind.ESCALATION_TO_LEADER -> GroupMessageType.PROGRESS_UPDATE
                AgentMessageKind.AGENT_TO_AGENT -> GroupMessageType.NORMAL
            }
            chatManager.postMessage(
                senderSlot = senderSlot,
                senderName = senderDisplayName,
                text = message.content,
                messageType = groupType,
                relatedTaskId = message.taskId,
            )
        }
}

/**
 * Backward-compatible mapping between existing [AgentConfig]/[AgentSlot] UI models
 * and dynamic [AgentProfileEntity]/[DynamicAgentProfile] multi-agent entities.
 */
object AgentProfileMapper {
    fun capabilitiesForRole(roleType: AgentRoleType): Set<String> = when (roleType) {
        AgentRoleType.TEAM_LEADER -> setOf("lead", "plan", "architect", "coordinate", AgentCapability.ARCHITECTURE_DESIGN)
        AgentRoleType.SOFTWARE_ENGINEER -> setOf("code", "fix", AgentCapability.KOTLIN_DEVELOPMENT, AgentCapability.ANDROID_DEVELOPMENT)
        AgentRoleType.SPECIALIST, AgentRoleType.BACKEND_ENGINEER -> setOf("code", "backend", "api", AgentCapability.BACKEND_DEVELOPMENT, AgentCapability.API_INTEGRATION)
        AgentRoleType.FRONTEND_ENGINEER -> setOf("code", "frontend", "ui", AgentCapability.WEB_DEVELOPMENT)
        AgentRoleType.TESTER -> setOf("review", "qa", "test", AgentCapability.CODE_REVIEW, AgentCapability.TESTING)
        AgentRoleType.SECURITY_ENGINEER -> setOf("review", "security", AgentCapability.SECURITY_REVIEW, AgentCapability.CODE_REVIEW)
        AgentRoleType.DEVOPS_ENGINEER -> setOf("build", "deploy", AgentCapability.BUILD_VERIFICATION)
        AgentRoleType.CUSTOM -> setOf("code")
    }

    fun fromAgentConfig(config: AgentConfig): AgentProfileEntity = AgentProfileEntity(
        agentId = config.slot.id,
        displayName = config.name,
        description = config.role.title,
        role = config.role.title,
        providerId = config.providerId,
        modelId = config.modelId,
        systemInstructions = config.role.systemInstructions,
        capabilities = capabilitiesForRole(config.role.type),
        priority = config.priority,
        isEnabled = config.isEnabled,
    )

    fun toDynamicProfile(profile: AgentProfileEntity): DynamicAgentProfile = DynamicAgentProfile(
        agentId = profile.agentId,
        name = profile.displayName,
        roleTitle = profile.role,
        legacySlot = AgentSlot.fromId(profile.agentId),
        providerId = profile.providerId,
        modelId = profile.modelId,
        systemInstructions = profile.systemInstructions.orEmpty(),
        capabilities = profile.capabilities,
        isEnabled = profile.isEnabled,
    )
}

data class CoordinationSubmissionResult(
    val accepted: Boolean,
    val task: TaskEntity?,
    val plannedSteps: List<TaskStepEntity> = emptyList(),
    val delegations: List<AgentDelegationEntity> = emptyList(),
    val failureReason: String? = null,
)

data class FinalVerificationReport(
    val passed: Boolean,
    val missingRequirements: List<String> = emptyList(),
    val summary: String,
)

/**
 * Part A Unified Multi-Agent Coordinator / Supervisor.
 *
 * Orchestrates the complete 7-step collaborative workflow by integrating existing ORQEN
 * components without duplicating persistence or execution engines:
 * 1. Create persistent task bound to project.
 * 2. Plan actionable dependency graph via [TaskPlanner] + [PlanValidator].
 * 3. Select enabled, provider-available agents by capability via [AgentCapabilityResolver]
 *    and record delegations via [AgentDelegationManager].
 * 4. Execute through [PersistentTaskOrchestrator] + [RuntimeStepExecutionAdapter] (real [AIExecutionRuntime]).
 * 5. Persist & isolate task-scoped inter-agent messages via [AgentCommunicationManager]
 *    (and optionally mirror to [TeamChatManager] via [TeamChatProjectionSink]).
 * 6. Enforce independent peer review & feedback loops via [AgentReviewManager].
 * 7. Perform final acceptance/review/test verification before allowing COMPLETED status.
 */
class MultiAgentTeamCoordinator(
    private val taskStore: OrqenTaskStore,
    private val collabStore: CollaborationStore,
    private val runtime: AIExecutionRuntime,
    private val checkpointManager: CheckpointManager = CheckpointManager(taskStore),
    private val fileLockManager: FileLockManager = FileLockManager(),
    private val supervisor: ExecutionRecoverySupervisor = ExecutionRecoverySupervisor(taskStore, checkpointManager, fileLockManager),
    private val planner: StructuredTaskPlanner = StructuredTaskPlanner(),
    private val policy: RequestExecutionPolicy = RequestExecutionPolicy(),
    private val unavailableProviders: () -> Set<String> = { emptySet() },
    private val chatProjectionSink: TeamChatProjectionSink? = null,
    private val maxReviewRejectCycles: Int = 3,
    private val stepTimeoutMs: Long = 180_000L,
    private val delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    val registry = AgentRegistry(collabStore)
    val teamManager = AgentTeamManager(collabStore, registry)
    val capabilityResolver = AgentCapabilityResolver(unavailableProviders)
    val delegationManager = AgentDelegationManager(collabStore, taskStore, capabilityResolver)
    val communicationManager = AgentCommunicationManager(taskStore)
    val reviewManager = AgentReviewManager(collabStore, taskStore)
    val failureManager = AgentFailureManager(collabStore, taskStore, capabilityResolver)

    private val stepAdapter = RuntimeStepExecutionAdapter(
        runtime = runtime,
        communicationManager = communicationManager,
        stepTimeoutMs = stepTimeoutMs,
    )

    val orchestrator: PersistentTaskOrchestrator = PersistentTaskOrchestrator(
        store = taskStore,
        checkpointManager = checkpointManager,
        supervisor = supervisor,
        fileLockManager = fileLockManager,
        stepAdapter = stepAdapter,
        policy = policy,
        teamRosterProvider = {
            registry.listAll(onlyEnabled = true).map(AgentProfileMapper::toDynamicProfile)
        },
        delayFn = delayFn,
        communicationManager = communicationManager,
        reviewManager = reviewManager,
        reviewFeedbackProvider = { tid -> collabStore.listReviewsForTask(tid) },
        finalCompletionVerifier = { task, steps ->
            val report = verifyTaskCompletion(task, steps)
            report.passed to report.summary
        },
        maxReviewRejectCycles = maxReviewRejectCycles,
    )

    fun registerConfiguredAgents(configs: List<AgentConfig>): List<AgentProfileEntity> =
        configs.map { registry.register(AgentProfileMapper.fromAgentConfig(it)) }

    /**
     * Steps 1-3: Create task, plan steps, resolve capable/available agents, and record delegations.
     * Reports the exact reason if no capable/available agent exists instead of pretending assignment succeeded.
     */
    fun createPlanAndDelegate(
        projectId: String,
        team: AgentTeamEntity,
        objective: String,
        requestedSubtasks: List<String> = emptyList(),
        acceptanceCriteria: List<String> = emptyList(),
        budget: BudgetConfiguration = BudgetConfiguration(),
        completionCriteria: TaskCompletionCriteria = TaskCompletionCriteria(
            requireReviewerApproval = team.requireIndependentReview,
            requireTestsPass = true,
            requireDeliverableVerification = true,
            acceptanceChecks = acceptanceCriteria,
        ),
        coordinatorAgentId: String? = null,
    ): CoordinationSubmissionResult {
        val project = taskStore.getProject(projectId)
            ?: return CoordinationSubmissionResult(
                accepted = false,
                task = null,
                failureReason = "Project not found: $projectId",
            )
        if (team.projectId != projectId) {
            return CoordinationSubmissionResult(
                accepted = false,
                task = null,
                failureReason = "Cross-project team assignment denied: team '${team.teamId}' belongs to '${team.projectId}', not '$projectId'.",
            )
        }

        val candidates = teamManager.getTeamProfiles(team.teamId, onlyEnabled = true)
        if (candidates.isEmpty()) {
            return CoordinationSubmissionResult(
                accepted = false,
                task = null,
                failureReason = "No enabled agents are configured in team '${team.teamName}'.",
            )
        }

        val rawPlan = planner.planTask(
            project = project,
            objective = objective,
            requestedSubtasks = requestedSubtasks,
            acceptanceCriteria = acceptanceCriteria,
        )

        val leaderId = coordinatorAgentId
            ?: capabilityResolver.selectBestAgent(candidates, setOf("lead"))?.agentId
            ?: candidates.first().agentId

        // Dynamically assign each planned step based on actual capabilities & provider availability
        val workloads = mutableMapOf<String, Int>()
        val assignedPlannedSteps = mutableListOf<PlannedStep>()
        for (step in rawPlan.steps) {
            val selected = capabilityResolver.selectBestAgent(
                candidates = candidates,
                requiredCapabilities = step.requiredCapabilities,
                activeWorkloads = workloads,
            ) ?: return CoordinationSubmissionResult(
                accepted = false,
                task = null,
                failureReason = "No enabled agent with available provider satisfies required capabilities ${step.requiredCapabilities} for step '${step.title}'.",
            )
            workloads[selected.agentId] = (workloads[selected.agentId] ?: 0) + 1
            assignedPlannedSteps.add(step.copy(assignedAgentId = selected.agentId))
        }

        val resolvedPlan = rawPlan.copy(steps = assignedPlannedSteps)
        val plannedTask = planner.persistPlan(
            store = taskStore,
            projectId = projectId,
            plan = resolvedPlan,
            budget = budget,
        ).let { persisted ->
            val updated = persisted.copy(completionCriteria = completionCriteria)
            taskStore.upsertTask(updated)
            updated
        }

        val persistedSteps = taskStore.listStepsForTask(plannedTask.taskId)
        val delegations = mutableListOf<AgentDelegationEntity>()
        for (step in persistedSteps) {
            if (step.assignedAgentId == leaderId) continue
            val requiredCaps = assignedPlannedSteps.firstOrNull { it.stepId == step.stepId }?.requiredCapabilities.orEmpty()
            val delegation = delegationManager.delegate(
                team = team,
                candidates = candidates.filter { it.agentId == step.assignedAgentId },
                taskId = plannedTask.taskId,
                parentStepId = step.stepId,
                delegatingAgentId = leaderId,
                objective = step.description,
                requiredCapabilities = requiredCaps,
                acceptanceCriteria = completionCriteria.acceptanceChecks,
                currentDepth = 1,
            )
            delegations.add(delegation)
        }

        val kickoffMsg = broadcastTaskUpdate(
            taskId = plannedTask.taskId,
            fromAgentId = leaderId,
            type = AgentMessageKind.SHARED_DECISION,
            content = "Task ${plannedTask.taskId} planned with ${persistedSteps.size} steps for objective: ${plannedTask.title}",
        )
        projectMessageToChat(kickoffMsg)

        return CoordinationSubmissionResult(
            accepted = true,
            task = plannedTask,
            plannedSteps = persistedSteps,
            delegations = delegations,
        )
    }

    /**
     * Step 4: Execute the planned task through [PersistentTaskOrchestrator].
     */
    suspend fun executeTask(taskId: String): TaskEntity? = orchestrator.runTaskLoop(taskId)

    fun launchTask(scope: CoroutineScope, taskId: String): Job = orchestrator.launchTask(scope, taskId)

    fun pauseTask(taskId: String, reason: String = "Paused by owner") = orchestrator.pauseTask(taskId, reason)

    fun resumeTask(scope: CoroutineScope, taskId: String): Job? = orchestrator.resumeTask(scope, taskId)

    fun stopTask(taskId: String, reason: String = "Stopped by owner") = orchestrator.stopTask(taskId, reason)

    /**
     * Step 5: Direct & task-scoped broadcast communication backed by [OrqenTaskStore],
     * with deduplication and optional mirror projection to [TeamChatManager].
     */
    fun sendDirectMessage(
        taskId: String,
        fromAgentId: String,
        toAgentId: String,
        type: AgentMessageKind,
        content: String,
        relatedStepId: String? = null,
        correlationId: String? = null,
    ): AgentMessageEntity {
        val msg = communicationManager.sendDirect(
            taskId = taskId,
            fromAgentId = fromAgentId,
            toAgentId = toAgentId,
            type = type,
            content = content,
            relatedStepId = relatedStepId,
            correlationId = correlationId,
        )
        projectMessageToChat(msg)
        return msg
    }

    fun broadcastTaskUpdate(
        taskId: String,
        fromAgentId: String,
        type: AgentMessageKind,
        content: String,
        relatedStepId: String? = null,
    ): AgentMessageEntity {
        val msg = communicationManager.broadcast(
            taskId = taskId,
            fromAgentId = fromAgentId,
            type = type,
            content = content,
            relatedStepId = relatedStepId,
        )
        projectMessageToChat(msg)
        return msg
    }

    /**
     * Step 7: Final Coordinator Verification Gate.
     * Task can ONLY be marked COMPLETED when:
     * - All steps are COMPLETED/SKIPPED (no FAILED/BLOCKED/PENDING/RUNNING)
     * - Required independent review approval exists with no unresolved rejection
     * - Required test verification actually executed and passed
     * - No unresolved critical error remains
     */
    fun verifyTaskCompletion(
        task: TaskEntity,
        steps: List<TaskStepEntity> = taskStore.listStepsForTask(task.taskId),
    ): FinalVerificationReport {
        val missing = mutableListOf<String>()

        if (steps.isEmpty()) {
            missing.add("Task has no planned steps.")
        }
        val incomplete = steps.filter { it.status != StepStatus.COMPLETED }
        if (incomplete.isNotEmpty()) {
            missing.add("Mandatory steps not completed: ${incomplete.joinToString { "${it.title}(${it.status})" }}")
        }

        if (task.completionCriteria.requireReviewerApproval) {
            val reviews = collabStore.listReviewsForTask(task.taskId)
            val latestReview = reviews.maxByOrNull { it.createdAt }
            val approved = latestReview != null &&
                (latestReview.outcome == ReviewOutcome.APPROVED || latestReview.outcome == ReviewOutcome.APPROVED_WITH_WARNINGS)
            if (!approved) {
                missing.add("Required independent reviewer approval is missing or rejected (latest=${latestReview?.outcome ?: "NONE"}).")
            }
        }

        if (task.completionCriteria.requireTestsPass) {
            val testVerified = steps.any { step ->
                step.status == StepStatus.COMPLETED &&
                    (step.title.contains("test", ignoreCase = true) || step.title.contains("verify", ignoreCase = true)) &&
                    step.outputSummary.contains("TESTS_PASSED", ignoreCase = true)
            }
            if (!testVerified) {
                missing.add("Required test verification did not report TESTS_PASSED.")
            }
        }

        val failedExecutionsAfterLastSuccess = taskStore.listExecutionsForTask(task.taskId)
            .lastOrNull()
            ?.takeIf { it.executionStatus == "FAILED" }
        if (failedExecutionsAfterLastSuccess != null) {
            missing.add("Unresolved execution failure remains: ${failedExecutionsAfterLastSuccess.errorMessage}")
        }

        return if (missing.isEmpty()) {
            FinalVerificationReport(
                passed = true,
                summary = "Final verification passed: ${steps.size} steps completed, review & test gates satisfied.",
            )
        } else {
            FinalVerificationReport(
                passed = false,
                missingRequirements = missing,
                summary = missing.joinToString(" | "),
            )
        }
    }

    private fun projectMessageToChat(msg: AgentMessageEntity) {
        if (chatProjectionSink == null) return
        if (!communicationManager.markProcessedOnce("chat-mirror:${msg.messageId}")) return
        val senderProfile = registry.get(msg.fromAgentId)
        val displayName = senderProfile?.displayName ?: msg.fromAgentId
        val slot = AgentSlot.fromId(msg.fromAgentId)
        chatProjectionSink.onAgentMessage(msg, displayName, slot)
    }
}
