package com.jarves.mh.multiagent.engine

import com.jarves.mh.multiagent.persistence.StepStatus
import com.jarves.mh.multiagent.persistence.TaskEntity
import com.jarves.mh.multiagent.persistence.TaskStepEntity
import com.jarves.mh.multiagent.persistence.WorkspacePathValidator
import java.io.File

data class PlanVerificationResult(
    val exists: Boolean,
    val valid: Boolean,
    val planFilePath: String,
    val completedStepsCount: Int = 0,
    val totalStepsCount: Int = 0,
    val reason: String = "",
)

/**
 * Phase C (5.2) — Durable Plan Persistence & Verification Manager.
 *
 * Guarantees that `.orqen/plan.md` is actually written, verified on disk, synchronized
 * with real step statuses/dependencies/assignments/evidence, and never blindly recreated
 * over completed work.
 */
@Deprecated(
    message = "Legacy single-file .orqen/plan.md helper. Active execution uses MultiPartPlanManager (.orqen/plan/overview.md + part_N_<slug>.md) and DynamicTeamPlanCompiler.",
)
object PlanPersistenceManager {
    const val PLAN_RELATIVE_PATH = ".orqen/plan.md"

    /**
     * Writes or updates `<workspaceDir>/.orqen/plan.md` with the authoritative task plan,
     * step statuses (`[x]` vs `[ ]`), dependencies, assigned agents, and execution evidence.
     * Verifies the file actually exists and is non-empty on disk before returning.
     */
    fun syncPlanFile(
        workspaceDir: File,
        task: TaskEntity,
        steps: List<TaskStepEntity>,
        forceOverwrite: Boolean = true,
    ): PlanVerificationResult {
        if (!WorkspacePathValidator.isPathInsideRoot(workspaceDir, PLAN_RELATIVE_PATH)) {
            return PlanVerificationResult(
                exists = false,
                valid = false,
                planFilePath = PLAN_RELATIVE_PATH,
                reason = "Unsafe plan path rejected.",
            )
        }
        val planFile = File(workspaceDir, PLAN_RELATIVE_PATH)
        if (!forceOverwrite && planFile.isFile && planFile.length() > 0L) {
            return verifyPlanExists(workspaceDir, steps)
        }

        val content = renderPlanMarkdown(task, steps)
        return runCatching {
            planFile.parentFile?.mkdirs()
            planFile.writeText(content)
            verifyPlanExists(workspaceDir, steps)
        }.getOrElse { err ->
            PlanVerificationResult(
                exists = planFile.isFile,
                valid = false,
                planFilePath = PLAN_RELATIVE_PATH,
                reason = "Failed to persist plan: ${err.message ?: err.javaClass.simpleName}",
            )
        }
    }

    /**
     * Verifies that `.orqen/plan.md` physically exists on disk, is non-empty,
     * and contains structured plan content. Displaying `.orqen/plan.md` in chat
     * is never treated as proof of persistence.
     */
    fun verifyPlanExists(
        workspaceDir: File,
        steps: List<TaskStepEntity> = emptyList(),
    ): PlanVerificationResult {
        if (!WorkspacePathValidator.isPathInsideRoot(workspaceDir, PLAN_RELATIVE_PATH)) {
            return PlanVerificationResult(false, false, PLAN_RELATIVE_PATH, reason = "Invalid workspace path.")
        }
        val planFile = File(workspaceDir, PLAN_RELATIVE_PATH)
        if (!planFile.isFile || planFile.length() <= 0L) {
            return PlanVerificationResult(
                exists = false,
                valid = false,
                planFilePath = PLAN_RELATIVE_PATH,
                completedStepsCount = steps.count { it.status == StepStatus.COMPLETED },
                totalStepsCount = steps.size,
                reason = "Plan file '$PLAN_RELATIVE_PATH' does not exist or is empty on disk.",
            )
        }
        val text = runCatching { planFile.readText() }.getOrDefault("")
        val hasContent = text.isNotBlank() && (text.contains("#") || text.contains("["))
        return PlanVerificationResult(
            exists = true,
            valid = hasContent,
            planFilePath = PLAN_RELATIVE_PATH,
            completedStepsCount = steps.count { it.status == StepStatus.COMPLETED },
            totalStepsCount = steps.size,
            reason = if (hasContent) "Verified '$PLAN_RELATIVE_PATH' (${planFile.length()} bytes)." else "Plan file has no structured content.",
        )
    }

    fun renderPlanMarkdown(
        task: TaskEntity,
        steps: List<TaskStepEntity>,
    ): String = buildString {
        appendLine("# ORQEN Autonomous Execution Plan")
        appendLine()
        appendLine("- **Task ID:** `${task.taskId}`")
        appendLine("- **Status:** `${task.status.name}`")
        appendLine("- **Objective:** ${task.description}")
        if (task.completionCriteria.acceptanceChecks.isNotEmpty()) {
            appendLine()
            appendLine("## Acceptance Criteria")
            task.completionCriteria.acceptanceChecks.forEach { check ->
                appendLine("- [ ] $check")
            }
        }
        appendLine()
        appendLine("## Execution Steps (${steps.count { it.status == StepStatus.COMPLETED }}/${steps.size} Completed)")
        steps.sortedBy { it.sequenceNumber }.forEach { step ->
            val mark = when (step.status) {
                StepStatus.COMPLETED -> "x"
                StepStatus.FAILED, StepStatus.BLOCKED -> "!"
                StepStatus.RUNNING -> "~"
                else -> " "
            }
            val deps = if (step.dependencies.isEmpty()) "none" else step.dependencies.joinToString(", ")
            appendLine("- [$mark] **#${step.sequenceNumber} ${step.title}** (`${step.stepId}`)")
            appendLine("  - Agent: `${step.assignedAgentId}` | Status: `${step.status.name}` | Dependencies: `$deps`")
            if (step.targetFiles.isNotEmpty()) {
                appendLine("  - Target Files: `${step.targetFiles.joinToString(", ")}`")
            }
            if (step.outputSummary.isNotBlank()) {
                val snippet = step.outputSummary.replace("\n", " ").take(240)
                appendLine("  - Evidence: $snippet")
            }
        }
    }
}
