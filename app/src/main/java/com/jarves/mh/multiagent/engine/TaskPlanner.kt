package com.jarves.mh.multiagent.engine

import com.jarves.mh.multiagent.persistence.*
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

/**
 * Converts a user development objective into a validated, persistent multi-step execution plan.
 * Never hardcodes a fixed step count — plans scale dynamically with objective complexity.
 */
interface TaskPlanner {
    fun planTask(
        project: ProjectEntity,
        objective: String,
        requestedSubtasks: List<String> = emptyList(),
        acceptanceCriteria: List<String> = emptyList(),
    ): TaskPlan
}

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

        // Verify all dependencies reference existing steps
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

        // Verify DAG has no cycles
        if (DependencyResolver.hasCycle(plan.steps.associate { it.stepId to it.dependencies })) {
            errors.add("Plan contains a circular dependency cycle.")
        }

        return PlanValidationResult(valid = errors.isEmpty(), errors = errors)
    }
}

/**
 * Default structured planner that decomposes development requests into verifiable steps
 * (Inspect -> Implement subtasks -> Verify/Review) and persists them cleanly to [OrqenTaskStore].
 */
class StructuredTaskPlanner : TaskPlanner {
    override fun planTask(
        project: ProjectEntity,
        objective: String,
        requestedSubtasks: List<String>,
        acceptanceCriteria: List<String>,
    ): TaskPlan {
        val cleanObjective = objective.trim()
        val steps = mutableListOf<PlannedStep>()
        var seq = 1

        val inspectStep = PlannedStep(
            stepId = "step-plan-${UUID.randomUUID().toString().take(6)}",
            sequenceNumber = seq++,
            title = "SPV วิเคราะห์ความต้องการและวางแผน (.orqen/plan.md)",
            description = buildString {
                appendLine("Analyze the Owner's request for project '${project.projectName}' and refine it into a crystal-clear, actionable specification.")
                appendLine("Owner's request: $cleanObjective")
                appendLine("REQUIRED DELIVERABLE: Create or update `.orqen/plan.md` inside the workspace with:")
                appendLine("1. Refined goal & user requirements")
                appendLine("2. Numbered implementation phases with `[ ]` checklists and exact target files")
                appendLine("3. Verification, test, and build plan (for Android projects, include `assembleDebug` APK build)")
                appendLine("Then hand off clearly to the coding agent(s) to execute `.orqen/plan.md` step-by-step.")
            },
            expectedOutput = "Master plan saved to .orqen/plan.md and clear delegation brief for developers",
            requiredCapabilities = setOf("lead", "architect"),
            targetFiles = emptyList(),
            verificationSpec = VerificationSpec(mode = VerificationMode.AUTOMATED_VERIFICATION),
            assignedAgentId = "agent-leader",
        )
        steps.add(inspectStep)

        val workItems = if (requestedSubtasks.isNotEmpty()) requestedSubtasks else listOf(cleanObjective)
        val implStepIds = mutableListOf<String>()
        workItems.forEachIndexed { idx, work ->
            val implStep = PlannedStep(
                stepId = "step-impl-${idx + 1}-${UUID.randomUUID().toString().take(6)}",
                sequenceNumber = seq++,
                title = "Implement (พัฒนาโค้ดตามแผน): ${work.take(52)}",
                description = buildString {
                    appendLine("Read `.orqen/plan.md` created by Supervisor and implement all planned steps cleanly for: $work")
                    appendLine("- Follow the exact architecture and checklist in `.orqen/plan.md`, marking completed items `[x]`.")
                    appendLine("- If this is an Android project (has `app/build.gradle` or `app/build.gradle.kts`), run the PocketDev Gradle `assembleDebug` build to verify it compiles into a real APK with zero errors.")
                    appendLine("- Report completed files and test results back to Supervisor for verification.")
                },
                expectedOutput = "Working code modifications implementing .orqen/plan.md for: ${work.take(80)}",
                dependencies = listOf(inspectStep.stepId),
                requiredCapabilities = setOf("code"),
                verificationSpec = VerificationSpec(mode = VerificationMode.AUTOMATED_VERIFICATION),
                assignedAgentId = "agent-developer",
            )
            steps.add(implStep)
            implStepIds.add(implStep.stepId)
        }

        val verifyStep = PlannedStep(
            stepId = "step-verify-${UUID.randomUUID().toString().take(6)}",
            sequenceNumber = seq,
            title = "Verify & test deliverables (ตรวจสอบโค้ดและทดสอบงาน)",
            description = buildString {
                appendLine("Inspect `.orqen/plan.md` and all changed files for: $cleanObjective")
                appendLine("- Verify every requirement in `.orqen/plan.md` is implemented cleanly without bugs.")
                appendLine("- Run tests (and for Android projects, ensure Debug APK builds cleanly with `assembleDebug`).")
                appendLine("- If everything passes cleanly, explicitly include BOTH `REVIEW_APPROVED` and `TESTS_PASSED` in your final response.")
                appendLine("- If bugs or build failures remain, explicitly include `REVIEW_REJECTED: <exact issues>` so Developer can fix them.")
            },
            expectedOutput = "REVIEW_APPROVED and TESTS_PASSED after verifying all deliverables and builds",
            dependencies = implStepIds,
            requiredCapabilities = setOf("review", "qa"),
            verificationSpec = VerificationSpec(mode = VerificationMode.TEST_VERIFICATION),
            assignedAgentId = "agent-reviewer",
            requiresReview = true,
        )
        steps.add(verifyStep)

        val criteria = acceptanceCriteria.ifEmpty {
            listOf("All planned implementation steps completed", "Deliverables verified without unresolved errors")
        }

        return TaskPlan(
            title = cleanObjective.take(80),
            objective = cleanObjective,
            acceptanceCriteria = criteria,
            steps = steps,
        )
    }

    fun persistPlan(
        store: OrqenTaskStore,
        projectId: String,
        plan: TaskPlan,
        budget: BudgetConfiguration = BudgetConfiguration(),
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
                    ),
                )
            }
            store.insertTaskEvent(
                TaskEventEntity(
                    taskId = task.taskId,
                    eventType = "TASK_PLANNED",
                    eventPayload = """{"stepCount":${plan.steps.size},"title":"${task.title.replace("\"", "'")}"}""",
                ),
            )
        }
        return task
    }
}
