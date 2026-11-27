package com.jarves.mh.multiagent.engine

import com.jarves.mh.multiagent.persistence.*
import java.io.File
import java.util.UUID

data class PlannedStep(
    val stepId: String = "step-${UUID.randomUUID().toString().take(8)}",
    val sequenceNumber: Int,
    val title: String,
    val description: String,
    val expectedOutput: String,
    val dependencies: List<String> = emptyList(),
    val requiredCapabilities: Set<String> = emptySet(),
    val verificationSpec: VerificationSpec = VerificationSpec(),
    val assignedAgentId: String = "agent-developer",
    val targetFiles: List<String> = emptyList(),
    val requiresReview: Boolean = false,
    val idempotencyKey: String = "idem-${UUID.randomUUID().toString().take(12)}",
    val partId: String? = null,
)

data class TaskPlan(
    val title: String,
    val objective: String,
    val acceptanceCriteria: List<String>,
    val steps: List<PlannedStep>,
    val risks: List<String> = emptyList(),
)

data class PlanValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
)

object PlanValidator {
    fun validate(plan: TaskPlan): PlanValidationResult {
        val errors = mutableListOf<String>()
        if (plan.title.isBlank()) errors.add("Plan title must not be blank.")
        if (plan.objective.isBlank()) errors.add("Plan objective must not be blank.")
        if (plan.steps.isEmpty()) errors.add("Plan must contain at least one executable step.")

        val seenIds = mutableSetOf<String>()
        val seenSeq = mutableSetOf<Int>()
        for (step in plan.steps) {
            if (step.stepId.isBlank()) errors.add("Step sequence ${step.sequenceNumber} has blank stepId.")
            if (!seenIds.add(step.stepId)) errors.add("Duplicate stepId: ${step.stepId}")
            if (!seenSeq.add(step.sequenceNumber)) errors.add("Duplicate sequenceNumber: ${step.sequenceNumber}")
            if (step.title.isBlank() || step.description.isBlank()) {
                errors.add("Step '${step.stepId}' must have a meaningful title and description.")
            }
            if (step.expectedOutput.isBlank()) {
                errors.add("Step '${step.stepId}' must declare an expected output.")
            }
        }

        for (step in plan.steps) {
            for (dep in step.dependencies) {
                if (dep !in seenIds) {
                    errors.add("Step '${step.stepId}' references missing dependency '$dep'.")
                }
                if (dep == step.stepId) {
                    errors.add("Step '${step.stepId}' cannot depend on itself.")
                }
            }
        }

        if (DependencyResolver.hasCycle(plan.steps.associate { it.stepId to it.dependencies })) {
            errors.add("Plan contains a circular dependency cycle.")
        }

        return PlanValidationResult(valid = errors.isEmpty(), errors = errors)
    }
}

/**
 * Unified Autonomous AI Team Plan Compiler.
 *
 * Replaces the removed legacy `StructuredTaskPlanner` (fixed Inspect -> Implement -> Verify template
 * and `.orqen/plan.md`). Compiles a verified dynamic [MultiPartExecutionPlan] (`.orqen/plan/overview.md`
 * + `.orqen/plan/part_N_<slug>.md`) into a dependency-accurate SQLite execution DAG and persists it
 * atomically to [OrqenTaskStore].
 */
class DynamicTeamPlanCompiler {

    /**
     * Compiles a verified [MultiPartExecutionPlan] into a dependency-accurate SQLite step DAG.
     * - Dynamic part count: 1 Part for small tasks, N Parts for larger tasks.
     * - Every implementation step receives the actual verified Part specification and workspace path.
     * - Every Part includes an independent Review & QA gate requiring real test/build evidence.
     * - Dependencies honor [MultiPartSpec.dependencies] (enabling safe parallel Parts when independent
     *   and strict sequential gating when dependent).
     */
    fun planFromMultiPart(
        project: ProjectEntity,
        multiPartPlan: MultiPartExecutionPlan,
        workspaceDir: File? = null,
    ): TaskPlan {
        val steps = mutableListOf<PlannedStep>()
        var seq = 1
        val verifyStepByPartId = linkedMapOf<String, String>()
        val sortedParts = multiPartPlan.parts.sortedBy { it.partNumber }

        for (part in sortedParts) {
            val implStepId = "step-${part.partId}-impl-${UUID.randomUUID().toString().take(6)}"
            val verifyStepId = "step-${part.partId}-verify-${UUID.randomUUID().toString().take(6)}"
            val implDeps = part.dependencies.mapNotNull { depPartId -> verifyStepByPartId[depPartId] }

            val specContent = workspaceDir?.let { ws ->
                val specFile = File(ws, part.specRelativePath)
                if (specFile.isFile) runCatching { specFile.readText().take(4_000) }.getOrNull() else null
            }

            steps += PlannedStep(
                stepId = implStepId,
                sequenceNumber = seq++,
                title = "Part ${part.partNumber}: ${part.title}",
                description = buildString {
                    appendLine("Execute Part ${part.partNumber} (${part.partId}) according to `${part.specRelativePath}`.")
                    if (workspaceDir != null) {
                        appendLine("Workspace path: `${workspaceDir.absolutePath}`")
                    }
                    appendLine("Scope: ${part.scope}")
                    appendLine("Objective: ${part.objective}")
                    if (!specContent.isNullOrBlank()) {
                        appendLine()
                        appendLine("--- PART SPECIFICATION (${part.specRelativePath}) ---")
                        appendLine(specContent)
                        appendLine("--- END SPECIFICATION ---")
                    }
                    appendLine("Report actual modified files and real command/test outputs back to SPV.")
                },
                expectedOutput = part.deliverables.joinToString("; ").ifBlank { "Verified implementation for ${part.partId}" },
                dependencies = implDeps,
                requiredCapabilities = setOf("code"),
                verificationSpec = VerificationSpec(mode = VerificationMode.AUTOMATED_VERIFICATION),
                assignedAgentId = part.assignedAgentId,
                targetFiles = part.relevantFiles,
                requiresReview = false,
                partId = part.partId,
            )

            steps += PlannedStep(
                stepId = verifyStepId,
                sequenceNumber = seq++,
                title = "Part ${part.partNumber} Review & QA: ${part.title}",
                description = buildString {
                    appendLine("Independently inspect changed files and run real verification tests for Part ${part.partNumber} (${part.partId}) against `${part.specRelativePath}`.")
                    appendLine("Execute actual test commands via `run_command` (and build verification if required). Real exitCode=0 evidence is mandatory.")
                    appendLine("Emit `REVIEW_APPROVED` only after inspecting real changes and confirming tests pass, or `REVIEW_REJECTED: <specific defects>`.")
                },
                expectedOutput = "Independent REVIEW_APPROVED + real passing test execution evidence for ${part.partId}",
                dependencies = listOf(implStepId),
                requiredCapabilities = setOf("review", "qa"),
                verificationSpec = VerificationSpec(mode = VerificationMode.TEST_VERIFICATION),
                assignedAgentId = "agent-reviewer",
                targetFiles = part.relevantFiles,
                requiresReview = true,
                partId = part.partId,
            )
            verifyStepByPartId[part.partId] = verifyStepId
        }

        return TaskPlan(
            title = multiPartPlan.title.ifBlank { project.projectName },
            objective = multiPartPlan.objective,
            acceptanceCriteria = multiPartPlan.parts.flatMap { it.acceptanceCriteria }.distinct().take(12),
            steps = steps,
        )
    }

    fun persistPlan(
        store: OrqenTaskStore,
        projectId: String,
        plan: TaskPlan,
        budget: BudgetConfiguration = BudgetConfiguration(),
        planId: String? = null,
        planVersion: Int = 1,
    ): TaskEntity {
        val validation = PlanValidator.validate(plan)
        require(validation.valid) { "Invalid task plan: ${validation.errors.joinToString("; ")}" }

        val task = TaskEntity(
            projectId = projectId,
            title = SecretSanitizer.sanitize(plan.title),
            description = SecretSanitizer.sanitize(plan.objective),
            status = PersistentTaskStatus.READY,
            budgetConfiguration = budget,
            completionCriteria = TaskCompletionCriteria(acceptanceChecks = plan.acceptanceCriteria),
            planId = planId,
            planVersion = planVersion,
            currentPartId = plan.steps.firstOrNull()?.partId,
        )
        store.runInTransaction {
            store.upsertTask(task)
            plan.steps.forEach { p ->
                store.upsertStep(
                    TaskStepEntity(
                        stepId = p.stepId,
                        taskId = task.taskId,
                        sequenceNumber = p.sequenceNumber,
                        title = SecretSanitizer.sanitize(p.title),
                        description = SecretSanitizer.sanitize(p.description),
                        assignedAgentId = p.assignedAgentId,
                        status = if (p.dependencies.isEmpty()) StepStatus.READY else StepStatus.PENDING,
                        dependencies = p.dependencies,
                        inputContext = p.expectedOutput,
                        targetFiles = p.targetFiles,
                        requiresReview = p.requiresReview,
                        idempotencyKey = p.idempotencyKey,
                        partId = p.partId,
                    ),
                )
            }
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = task.taskId,
                    eventType = "UNIFIED_PLAN_CREATED",
                    eventPayload = """{"stepCount":${plan.steps.size},"planId":"${planId.orEmpty()}","planVersion":$planVersion,"title":"${task.title.replace("\"", "'")}"}""",
                ),
            )
        }
        return task
    }
}
