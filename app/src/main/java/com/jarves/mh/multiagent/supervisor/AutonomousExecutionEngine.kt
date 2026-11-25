package com.jarves.mh.multiagent.supervisor

import com.jarves.mh.multiagent.model.*
import com.jarves.mh.multiagent.tasks.TaskStatus
import com.jarves.mh.multiagent.tasks.TeamTask

data class AutonomousStepResult(
    val executedSlot: AgentSlot,
    val agentName: String,
    val taskId: String,
    val newLifecycleState: AgentLifecycleState,
    val estimatedCostUsd: Double,
    val budgetExceeded: Boolean,
    val rosterInjectedPrompt: String,
)

/**
 * Coordinates autonomous multi-agent execution steps using the Part 02 architecture contracts:
 * - Enforces enabled status & max 4-agent cap
 * - Enforces budget guard auto-pause
 * - Injects custom agent names into team prompts
 * - Coordinates file locks & lifecycle state transitions
 */
class AutonomousExecutionEngine(
    private val lockCoordinator: IFileLockCoordinator,
    private val quotaEvaluator: IQuotaEvaluator = StandardQuotaEvaluator(),
    private val contextPruner: ContextWindowPruner = ContextWindowPruner(),
) {
    private val stateMachines = AgentSlot.entries.associateWith { slot -> AgentLifecycleStateMachine(slot) }

    fun getStateMachine(slot: AgentSlot): AgentLifecycleStateMachine {
        return stateMachines[slot] ?: AgentLifecycleStateMachine(slot)
    }

    fun isBudgetExceeded(currentSpendUsd: Double, budgetLimitUsd: Double, autoPauseEnabled: Boolean): Boolean {
        if (!autoPauseEnabled) return false
        return currentSpendUsd >= budgetLimitUsd
    }

    fun selectNextRunnableAgent(agents: List<AgentState>, tasks: List<TeamTask>): Pair<AgentState, TeamTask>? {
        val enabledAgents = agents.filter { it.config.isEnabled }
        if (enabledAgents.isEmpty()) return null

        val readyTasks = tasks.filter { it.status == TaskStatus.READY || it.status == TaskStatus.PLANNED }
        for (task in readyTasks) {
            val assigned = enabledAgents.firstOrNull { it.config.slot == task.assignedSlot }
            if (assigned != null) return assigned to task
        }
        val firstTask = readyTasks.firstOrNull() ?: return null
        return enabledAgents.first() to firstTask
    }

    fun prepareExecutionStep(
        agents: List<AgentState>,
        targetAgent: AgentState,
        task: TeamTask,
        targetFilePath: String?,
        currentSpendUsd: Double,
        budgetLimitUsd: Double,
        autoPauseEnabled: Boolean,
    ): AutonomousStepResult {
        val budgetExceeded = isBudgetExceeded(currentSpendUsd, budgetLimitUsd, autoPauseEnabled)
        val machine = getStateMachine(targetAgent.config.slot)

        if (budgetExceeded) {
            val cur = machine.currentState.value
            if (cur == AgentLifecycleState.EXECUTING || cur == AgentLifecycleState.PLANNING) {
                runCatching { machine.transitionTo(AgentLifecycleState.BLOCKED, "Budget limit reached") }
            }
            return AutonomousStepResult(
                executedSlot = targetAgent.config.slot,
                agentName = targetAgent.config.name,
                taskId = task.id,
                newLifecycleState = machine.currentState.value,
                estimatedCostUsd = 0.0,
                budgetExceeded = true,
                rosterInjectedPrompt = "",
            )
        }

        // Acquire lock if targeting a file
        if (!targetFilePath.isNullOrBlank()) {
            val lockRes = lockCoordinator.tryAcquire(
                filePath = targetFilePath,
                slot = targetAgent.config.slot,
                taskId = task.id,
                reason = "Autonomous task: ${task.title}",
            )
            if (lockRes is LockAcquisitionResult.Conflict) {
                runCatching {
                    if (machine.currentState.value == AgentLifecycleState.UNINITIALIZED) machine.transitionTo(AgentLifecycleState.IDLE, "Init")
                    if (machine.currentState.value == AgentLifecycleState.IDLE) machine.transitionTo(AgentLifecycleState.PLANNING, "Plan")
                    if (machine.currentState.value == AgentLifecycleState.PLANNING) machine.transitionTo(AgentLifecycleState.EXECUTING, "Execute")
                    machine.transitionTo(AgentLifecycleState.BLOCKED, "Lock conflict on $targetFilePath")
                }
                return AutonomousStepResult(
                    executedSlot = targetAgent.config.slot,
                    agentName = targetAgent.config.name,
                    taskId = task.id,
                    newLifecycleState = machine.currentState.value,
                    estimatedCostUsd = 0.0,
                    budgetExceeded = false,
                    rosterInjectedPrompt = "",
                )
            }
        }

        // Progress lifecycle UNINITIALIZED -> IDLE -> PLANNING -> EXECUTING
        runCatching {
            if (machine.currentState.value == AgentLifecycleState.UNINITIALIZED) machine.transitionTo(AgentLifecycleState.IDLE, "Init")
            if (machine.currentState.value == AgentLifecycleState.IDLE) machine.transitionTo(AgentLifecycleState.PLANNING, "Plan")
            if (machine.currentState.value == AgentLifecycleState.PLANNING) machine.transitionTo(AgentLifecycleState.EXECUTING, "Execute")
        }

        val rosterPrompt = contextPruner.buildTeamRosterPrompt(agents)
        val fullPrompt = buildString {
            appendLine(rosterPrompt)
            appendLine()
            appendLine("You are **${targetAgent.config.name}** (${targetAgent.config.role.title}).")
            appendLine(targetAgent.config.role.systemInstructions)
            if (targetAgent.config.customPrompt.isNotBlank()) {
                appendLine(targetAgent.config.customPrompt)
            }
            appendLine("Current Task: ${task.title} — ${task.description}")
        }

        val estUsage = quotaEvaluator.estimateCost(
            inputTokens = (fullPrompt.length / 4L).coerceAtLeast(250L),
            outputTokens = 1_200L,
            modelId = targetAgent.config.modelId,
        )

        return AutonomousStepResult(
            executedSlot = targetAgent.config.slot,
            agentName = targetAgent.config.name,
            taskId = task.id,
            newLifecycleState = machine.currentState.value,
            estimatedCostUsd = estUsage.estimatedCostUsd,
            budgetExceeded = false,
            rosterInjectedPrompt = fullPrompt,
        )
    }
}
