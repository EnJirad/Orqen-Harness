package com.jarves.mh.multiagent.engine

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 5 — Strict Internal Task Assignment Envelope.
 *
 * Strictly separates:
 * 1. Immutable background User Goal (read-only context, NEVER reinterpreted as a new task)
 * 2. Overall project plan (read-only overview)
 * 3. Current Part specification (exact persisted `.orqen/plan/part_N_*.md`)
 * 4. Current Step instructions (the ONLY scope the worker is authorized to execute now)
 * 5. Previous completed results
 */
data class TaskAssignmentEnvelope(
    val taskId: String,
    val planId: String,
    val planVersion: Int,
    val partId: String,
    val stepId: String,
    val assignmentId: String = "asgn-${UUID.randomUUID().toString().take(10)}",
    val assignedAgentId: String,
    val specificationPath: String,
    val specificationContent: String,
    val immutableUserGoal: String,
    val overallPlanOverview: String = "",
    val currentScope: String,
    val expectedDeliverables: List<String> = emptyList(),
    val acceptanceCriteria: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val relevantPreviousResults: List<String> = emptyList(),
    val authorizedTools: List<String> = listOf("read_file", "write_file", "edit_file", "list_dir", "search_files", "run_command", "verify_apk"),
    val requiredPermissions: List<String> = listOf("workspace:read", "workspace:write"),
    val reportingRequirements: String = "Return structured internal result referencing assignment_id, part_id, and step_id. Do not answer the Owner directly or start another Part.",
) {
    fun renderEnvelopeText(): String = buildString {
        appendLine("TASK_ASSIGNMENT")
        appendLine("  task_id: $taskId")
        appendLine("  plan_id: $planId")
        appendLine("  plan_version: $planVersion")
        appendLine("  part_id: $partId")
        appendLine("  step_id: $stepId")
        appendLine("  assignment_id: $assignmentId")
        appendLine("  assigned_agent: $assignedAgentId")
        appendLine("  specification_path: ${specificationPath.ifBlank { ".orqen/plan/overview.md" }}")
        appendLine("  current_scope: $currentScope")
        if (expectedDeliverables.isNotEmpty()) {
            appendLine("  expected_deliverables: ${expectedDeliverables.joinToString("; ")}")
        }
        if (acceptanceCriteria.isNotEmpty()) {
            appendLine("  acceptance_criteria: ${acceptanceCriteria.joinToString("; ")}")
        }
        if (dependencies.isNotEmpty()) {
            appendLine("  dependencies: ${dependencies.joinToString(", ")}")
        }
        appendLine("  authorized_tools: ${authorizedTools.joinToString(", ")}")
        appendLine("  required_permissions: ${requiredPermissions.joinToString(", ")}")
        appendLine("  reporting_requirements: $reportingRequirements")
        appendLine("END_TASK_ASSIGNMENT")
        appendLine()
        appendLine("## STRICT INTERNAL WORKER RULES")
        appendLine("- You are an internal team worker operating strictly under SPV's assignment `$assignmentId`.")
        appendLine("- Execute ONLY `current_scope` for `$partId` / `$stepId`.")
        appendLine("- DO NOT reinterpret the immutable background User Goal as a new independent task.")
        appendLine("- DO NOT create a new master plan or start another Part without SPV authorization.")
        appendLine("- DO NOT write Owner-facing conversational replies; report real changed files, command/test evidence, and blockers back to SPV.")
        appendLine()
        appendLine("## 1. IMMUTABLE BACKGROUND USER GOAL (Read-Only Context — Do NOT Re-Execute As Whole Task)")
        appendLine(immutableUserGoal.take(600))
        if (overallPlanOverview.isNotBlank()) {
            appendLine()
            appendLine("## 2. OVERALL PROJECT PLAN (Read-Only)")
            appendLine(overallPlanOverview.take(1_200))
        }
        if (specificationContent.isNotBlank()) {
            appendLine()
            appendLine("## 3. CURRENT PART SPECIFICATION ($specificationPath)")
            appendLine(specificationContent.take(2_500))
        }
        if (relevantPreviousResults.isNotEmpty()) {
            appendLine()
            appendLine("## 4. PREVIOUS COMPLETED RESULTS")
            relevantPreviousResults.takeLast(5).forEach { appendLine("- ${it.take(240)}") }
        }
    }
}

data class AssignmentValidationResult(
    val accepted: Boolean,
    val reason: String,
)

/**
 * Tracks active and completed assignment IDs to reject stale, duplicate,
 * or mismatched worker results before they can mutate task state.
 */
class AssignmentResultLedger {
    private val activeAssignments = ConcurrentHashMap<String, TaskAssignmentEnvelope>()
    private val completedAssignmentIds = ConcurrentHashMap.newKeySet<String>()

    fun registerAssignment(envelope: TaskAssignmentEnvelope): Boolean {
        if (envelope.assignmentId in completedAssignmentIds) return false
        return activeAssignments.putIfAbsent(envelope.assignmentId, envelope) == null
    }

    fun validateAndComplete(
        assignmentId: String,
        partId: String,
        stepId: String,
    ): AssignmentValidationResult {
        if (assignmentId in completedAssignmentIds) {
            return AssignmentValidationResult(false, "Duplicate result rejected: assignment '$assignmentId' already completed.")
        }
        val expected = activeAssignments[assignmentId]
            ?: return AssignmentValidationResult(false, "Stale or unknown assignment '$assignmentId' rejected.")
        if (expected.partId != partId || expected.stepId != stepId) {
            return AssignmentValidationResult(
                false,
                "Mismatched assignment result for '$assignmentId': expected (${expected.partId}/${expected.stepId}), got ($partId/$stepId).",
            )
        }
        activeAssignments.remove(assignmentId)
        completedAssignmentIds.add(assignmentId)
        return AssignmentValidationResult(true, "Assignment '$assignmentId' validated and recorded.")
    }

    fun releaseActive(assignmentId: String) {
        activeAssignments.remove(assignmentId)
    }

    fun isActive(assignmentId: String): Boolean = activeAssignments.containsKey(assignmentId)
}
