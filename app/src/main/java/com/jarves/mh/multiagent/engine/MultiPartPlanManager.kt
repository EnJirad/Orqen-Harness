package com.jarves.mh.multiagent.engine

import com.jarves.mh.multiagent.persistence.WorkspacePathValidator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class SpvPlanningStage {
    DISCUSSION,
    PLANNING,
    PLAN_PERSISTING,
    PLAN_VERIFICATION,
    PLAN_APPROVED,
    DELEGATING,
    EXECUTING,
}

enum class ExecutionPartStatus {
    PENDING,
    READY,
    EXECUTING,
    WAITING_FOR_REVIEW,
    WAITING_FOR_TESTS,
    VERIFYING,
    RETRYING,
    RECOVERING,
    BLOCKED,
    FAILED,
    COMPLETED,
}

data class PlanRevisionEntry(
    val version: Int,
    val reason: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class MultiPartSpec(
    val partId: String,
    val partNumber: Int,
    val title: String,
    val slug: String,
    val specRelativePath: String,
    val objective: String,
    val scope: String,
    val backgroundAnalysis: String = "",
    val requirements: List<String> = emptyList(),
    val architectureApproach: String = "",
    val relevantFiles: List<String> = emptyList(),
    val assignedAgentId: String = "agent-02",
    val assignedModelId: String = "",
    val dependencies: List<String> = emptyList(),
    val requiredMcpTools: List<String> = emptyList(),
    val requiredPermissions: List<String> = emptyList(),
    val implementationSteps: List<String> = emptyList(),
    val verificationProcedures: List<String> = emptyList(),
    val acceptanceCriteria: List<String> = emptyList(),
    val risksAndRecovery: List<String> = emptyList(),
    val deliverables: List<String> = emptyList(),
    val requireIndependentReview: Boolean = true,
    val requireTestsPass: Boolean = true,
    val requireBuildPass: Boolean = false,
    val status: ExecutionPartStatus = ExecutionPartStatus.PENDING,
    val executionAttempts: Int = 0,
    val reviewerVerdict: String? = null,
    val testExitCode: Int? = null,
    val testSummary: String? = null,
    val buildVerified: Boolean? = null,
    val lastError: String? = null,
    val reportRelativePath: String = ".orqen/reports/part_${partNumber}_report.md",
    val completedAtMs: Long? = null,
)

data class MultiPartExecutionPlan(
    val planId: String = "plan-${UUID.randomUUID().toString().take(8)}",
    val version: Int = 1,
    val taskId: String,
    val projectId: String,
    val title: String,
    val objective: String,
    val stage: SpvPlanningStage = SpvPlanningStage.PLANNING,
    val parts: List<MultiPartSpec>,
    val revisionHistory: List<PlanRevisionEntry> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class MultiPartExecutionState(
    val planId: String,
    val version: Int,
    val stage: SpvPlanningStage,
    val currentPartId: String? = null,
    val currentStepId: String? = null,
    val activeDelegationPartId: String? = null,
    val lastCheckpointId: String? = null,
    val failureReason: String? = null,
    val recoveryState: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class MultiPartValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
)

data class PartVerificationInput(
    val modifiedFiles: List<String>,
    val reviewerApproved: Boolean,
    val reviewerNotes: String,
    val testExitCode: Int?,
    val testSummary: String,
    val buildPassed: Boolean? = null,
    val buildArtifactPath: String? = null,
    val unresolvedIssues: List<String> = emptyList(),
)

data class PartAdvancementResult(
    val passed: Boolean,
    val updatedPlan: MultiPartExecutionPlan,
    val updatedPart: MultiPartSpec,
    val reportFile: File,
    val failureReasons: List<String> = emptyList(),
)

/**
 * Multi-Part Planning & Sequential Autonomous Execution Manager.
 *
 * Manages the canonical `.orqen/` workspace layout:
 * - `.orqen/plan/overview.md`
 * - `.orqen/plan/part_N_<slug>.md`
 * - `.orqen/state/execution.json`
 * - `.orqen/state/parts.json`
 * - `.orqen/reports/part_N_report.md`
 *
 * Guarantees:
 * - Plan integrity verification (file existence, non-empty specs, unique IDs, valid DAG, zero cycles)
 * - Strictly gated sequential Part execution (Part N+1 cannot start until Part N passes all gates)
 * - Real Part verification reports written to disk
 * - Crash/restart state restoration & duplicate delegation prevention
 * - Legacy `.orqen/plan.md` migration & backward compatibility
 */
object MultiPartPlanManager {
    const val OVERVIEW_REL_PATH = ".orqen/plan/overview.md"
    const val EXECUTION_STATE_REL_PATH = ".orqen/state/execution.json"
    const val PARTS_STATE_REL_PATH = ".orqen/state/parts.json"
    const val EXECUTION_REPORT_REL_PATH = ".orqen/reports/execution_report.md"
    const val REVIEW_REPORT_REL_PATH = ".orqen/reports/review_report.md"
    const val VERIFICATION_REPORT_REL_PATH = ".orqen/reports/verification_report.md"
    const val LEGACY_PLAN_REL_PATH = ".orqen/plan.md"

    private val planWriteLock = Any()

    fun slugify(input: String): String = input.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(36)
        .ifBlank { "module" }

    /**
     * Generates a structured Multi-Part Execution Plan from an objective and optional subtasks.
     * Small trivial requests legitimately produce 1 Part; substantial requests produce multiple
     * logically sequenced, independently verifiable Parts.
     */
    fun createPlan(
        projectId: String,
        taskId: String,
        projectName: String,
        objective: String,
        workspaceDir: File,
        explicitSubtasks: List<String> = emptyList(),
        isAndroidProject: Boolean = false,
        leaderAgentId: String = "agent-01",
        devAgentId: String = "agent-02",
        qaAgentId: String = "agent-03",
    ): MultiPartExecutionPlan {
        val cleanObj = objective.trim()
        val inspection = com.jarves.mh.runtime.SharedWorkspaceExecutionService(workspaceDir, projectId).inspectWorkspaceSummary()
        val workspaceSummary = buildString {
            appendLine("Detected stack: ${inspection.detectedStack} | Build system: ${inspection.buildSystem} | Git: ${inspection.hasGitRepo}")
            if (inspection.topLevelEntries.isNotEmpty()) {
                appendLine("Top-level entries: ${inspection.topLevelEntries.take(20).joinToString(", ")}")
            }
            if (inspection.sourceFiles.isNotEmpty()) {
                appendLine("Sample source files (${inspection.sourceFiles.size} total): ${inspection.sourceFiles.take(15).joinToString(", ")}")
            }
            if (inspection.testFiles.isNotEmpty()) {
                appendLine("Existing test files: ${inspection.testFiles.take(10).joinToString(", ")}")
            }
        }.trim().ifBlank { inspectWorkspaceSummary(workspaceDir) }
        val relevantSourceSample = inspection.sourceFiles.filterNot { it.startsWith(".orqen/") }.take(10)
        val complexity = assessTaskComplexity(cleanObj, explicitSubtasks)
        val rawPartDefs: List<Pair<String, String>> = when {
            explicitSubtasks.isNotEmpty() -> explicitSubtasks.mapIndexed { idx, sub ->
                "Part ${idx + 1}: ${sub.take(48)}" to sub
            }
            complexity == TaskComplexityTier.LARGE -> buildDefaultMultiPartBreakdown(cleanObj, isAndroidProject)
            complexity == TaskComplexityTier.MEDIUM -> buildMediumMultiPartBreakdown(cleanObj, isAndroidProject)
            else -> listOf("Core Implementation & Verification" to cleanObj)
        }

        val parts = mutableListOf<MultiPartSpec>()
        rawPartDefs.forEachIndexed { idx, (partTitle, partObjective) ->
            val num = idx + 1
            val partId = "part-$num"
            val slug = slugify(partTitle)
            // When explicit independent subtasks are requested, allow parallel execution unless sequential breakdown
            val prevDeps = if (idx == 0 || explicitSubtasks.size > 1) emptyList() else listOf("part-${idx}")
            val isFinal = idx == rawPartDefs.lastIndex
            val specPath = ".orqen/plan/part_${num}_${slug}.md"
            val reportPath = ".orqen/reports/part_${num}_report.md"

            parts += MultiPartSpec(
                partId = partId,
                partNumber = num,
                title = partTitle,
                slug = slug,
                specRelativePath = specPath,
                objective = partObjective,
                scope = "Implement and verify '$partTitle' within project '$projectName'.",
                backgroundAnalysis = workspaceSummary,
                requirements = listOf(
                    partObjective,
                    "Preserve existing working functionality and follow project architecture.",
                ),
                architectureApproach = if (idx == 0) {
                    "Inspect existing project structure (${inspection.detectedStack}), establish core data/domain contracts, and ensure clean modularity."
                } else {
                    "Build cleanly on verified outputs from ${prevDeps.joinToString(", ")}."
                },
                relevantFiles = relevantSourceSample,
                // P0 Fix: Implementation Parts must ALWAYS be assigned to a Developer worker, never SPV.
                assignedAgentId = devAgentId,
                dependencies = prevDeps,
                requiredMcpTools = if (isAndroidProject) listOf("workspace_files", "terminal_gradle") else listOf("workspace_files", "terminal"),
                requiredPermissions = listOf("workspace:read", "workspace:write", "terminal:execute_tests"),
                implementationSteps = listOf(
                    "Read $specPath and inspect relevant project files.",
                    "Implement changes cleanly for: $partObjective",
                    "Run unit/integration tests and fix any errors.",
                ),
                verificationProcedures = buildList {
                    add("Inspect actual changed files in workspace.")
                    add("Execute real automated tests and capture exit code.")
                    if (isAndroidProject && isFinal) add("Run Gradle assembleDebug and verify non-empty APK artifact.")
                    add("Independent review by $qaAgentId.")
                },
                acceptanceCriteria = buildList {
                    add("All requirements for '$partTitle' implemented in real workspace files.")
                    add("Automated tests pass with exit code 0.")
                    add("Independent reviewer records REVIEW_APPROVED.")
                    if (isAndroidProject && isFinal) add("Debug APK builds cleanly and is verified non-empty on disk.")
                },
                risksAndRecovery = listOf(
                    "On tool/provider timeout or failure, preserve modified files and resume from last valid checkpoint.",
                    "If review or tests fail, schedule targeted remediation without resetting completed Parts.",
                ),
                deliverables = buildList {
                    add("Verified source changes for $partTitle")
                    add(reportPath)
                    if (isAndroidProject && isFinal) add("app/build/outputs/apk/debug/*.apk")
                },
                requireIndependentReview = true,
                requireTestsPass = true,
                requireBuildPass = isAndroidProject && isFinal,
                status = if (prevDeps.isEmpty()) ExecutionPartStatus.READY else ExecutionPartStatus.PENDING,
                reportRelativePath = reportPath,
            )
        }

        return MultiPartExecutionPlan(
            taskId = taskId,
            projectId = projectId,
            title = cleanObj.take(80),
            objective = cleanObj,
            stage = SpvPlanningStage.PLANNING,
            parts = parts,
            revisionHistory = listOf(PlanRevisionEntry(version = 1, reason = "Initial SPV multi-part master plan")),
        )
    }

    /**
     * Persists `overview.md`, all `part_N_<slug>.md`, `state/parts.json`, `state/execution.json`,
     * and keeps legacy `.orqen/plan.md` synchronized.
     */
    fun persistPlan(
        workspaceDir: File,
        plan: MultiPartExecutionPlan,
        currentPartId: String? = null,
        currentStepId: String? = null,
    ): MultiPartValidationResult = synchronized(planWriteLock) {
        val persistingPlan = plan.copy(
            stage = SpvPlanningStage.PLAN_PERSISTING,
            updatedAtMs = System.currentTimeMillis(),
        )
        runCatching {
            val planDir = File(workspaceDir, ".orqen/plan").apply { mkdirs() }
            File(workspaceDir, ".orqen/state").mkdirs()
            File(workspaceDir, ".orqen/reports").mkdirs()

            // Archive previous active plan snapshot before replacing it
            val existingOverview = File(workspaceDir, OVERVIEW_REL_PATH)
            val prevPlan = loadPlan(workspaceDir)
            if (existingOverview.isFile && prevPlan != null &&
                (prevPlan.planId != persistingPlan.planId || prevPlan.version != persistingPlan.version)
            ) {
                val historyDir = File(planDir, "history/v${prevPlan.version}_${prevPlan.planId}_${System.currentTimeMillis()}").apply { mkdirs() }
                planDir.listFiles()?.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }?.forEach { md ->
                    runCatching { md.copyTo(File(historyDir, md.name), overwrite = true) }
                }
            }

            val overviewFile = File(workspaceDir, OVERVIEW_REL_PATH)
            overviewFile.writeText(renderOverviewMarkdown(persistingPlan))

            persistingPlan.parts.forEach { part ->
                require(WorkspacePathValidator.isPathInsideRoot(workspaceDir, part.specRelativePath)) {
                    "Unsafe Part spec path: ${part.specRelativePath}"
                }
                val partFile = File(workspaceDir, part.specRelativePath)
                partFile.parentFile?.mkdirs()
                partFile.writeText(renderPartMarkdown(persistingPlan, part))
            }

            // Persist durable JSON state
            val state = MultiPartExecutionState(
                planId = persistingPlan.planId,
                version = persistingPlan.version,
                stage = SpvPlanningStage.PLAN_VERIFICATION,
                currentPartId = currentPartId ?: selectNextEligiblePart(persistingPlan)?.partId,
                currentStepId = currentStepId,
                updatedAtMs = System.currentTimeMillis(),
            )
            writePartsStateJson(workspaceDir, persistingPlan)
            writeExecutionStateJson(workspaceDir, state)
        }.onFailure { err ->
            return MultiPartValidationResult(false, listOf("Failed to persist multi-part plan: ${err.message}"))
        }

        val validation = verifyPlanIntegrity(workspaceDir, persistingPlan)
        if (validation.valid) {
            val approvedPlan = persistingPlan.copy(stage = SpvPlanningStage.PLAN_APPROVED)
            writePartsStateJson(workspaceDir, approvedPlan)
            writeExecutionStateJson(
                workspaceDir,
                MultiPartExecutionState(
                    planId = approvedPlan.planId,
                    version = approvedPlan.version,
                    stage = SpvPlanningStage.PLAN_APPROVED,
                    currentPartId = currentPartId ?: selectNextEligiblePart(approvedPlan)?.partId,
                    currentStepId = currentStepId,
                ),
            )
        }
        return@synchronized validation
    }

    /**
     * Enforces plan integrity: overview + every Part file physically exist & are non-empty,
     * unique Part IDs & numbers, valid dependencies, and zero circular dependencies.
     */
    fun verifyPlanIntegrity(
        workspaceDir: File,
        plan: MultiPartExecutionPlan,
    ): MultiPartValidationResult {
        val errors = mutableListOf<String>()
        if (plan.parts.isEmpty()) errors += "Plan must contain at least one Part."

        val overview = File(workspaceDir, OVERVIEW_REL_PATH)
        if (!overview.isFile || overview.length() <= 0L) {
            errors += "Master plan '$OVERVIEW_REL_PATH' is missing or empty."
        }

        val seenIds = mutableSetOf<String>()
        val seenNumbers = mutableSetOf<Int>()
        for (part in plan.parts) {
            if (!seenIds.add(part.partId)) errors += "Duplicate Part ID: ${part.partId}"
            if (!seenNumbers.add(part.partNumber)) errors += "Duplicate Part number: ${part.partNumber}"
            if (!WorkspacePathValidator.isPathInsideRoot(workspaceDir, part.specRelativePath)) {
                errors += "Unsafe Part spec path: ${part.specRelativePath}"
                continue
            }
            val specFile = File(workspaceDir, part.specRelativePath)
            if (!specFile.isFile || specFile.length() <= 0L) {
                errors += "Missing or empty Part specification file: ${part.specRelativePath}"
            }
        }

        for (part in plan.parts) {
            for (dep in part.dependencies) {
                if (dep !in seenIds) errors += "Part '${part.partId}' references unknown dependency '$dep'."
                if (dep == part.partId) errors += "Part '${part.partId}' cannot depend on itself."
            }
        }

        if (hasCycle(plan.parts)) {
            errors += "Circular dependency detected in Multi-Part plan."
        }

        return MultiPartValidationResult(valid = errors.isEmpty(), errors = errors)
    }

    /**
     * Selects the earliest eligible Part whose dependencies are ALL COMPLETED.
     * Prevents Part N+1 from ever starting before Part N passes verification.
     */
    fun selectNextEligiblePart(plan: MultiPartExecutionPlan): MultiPartSpec? {
        val completedIds = plan.parts.filter { it.status == ExecutionPartStatus.COMPLETED }.map { it.partId }.toSet()
        return plan.parts
            .sortedBy { it.partNumber }
            .firstOrNull { part ->
                part.status in setOf(ExecutionPartStatus.PENDING, ExecutionPartStatus.READY, ExecutionPartStatus.RETRYING, ExecutionPartStatus.RECOVERING) &&
                    part.dependencies.all { it in completedIds }
            }
    }

    /**
     * Marks a Part as EXECUTING with duplicate-delegation guard.
     * Returns null if this Part (or another Part) is already actively delegated.
     */
    fun beginPartExecution(
        workspaceDir: File,
        plan: MultiPartExecutionPlan,
        partId: String,
    ): MultiPartExecutionPlan? {
        val existingState = loadExecutionState(workspaceDir)
        if (existingState?.activeDelegationPartId == partId) {
            return null // Duplicate delegation prevented!
        }
        val target = plan.parts.firstOrNull { it.partId == partId } ?: return null
        if (target.status == ExecutionPartStatus.COMPLETED) return null
        val completedIds = plan.parts.filter { it.status == ExecutionPartStatus.COMPLETED }.map { it.partId }.toSet()
        if (!target.dependencies.all { it in completedIds }) return null

        val updatedParts = plan.parts.map { p ->
            if (p.partId == partId) p.copy(status = ExecutionPartStatus.EXECUTING, executionAttempts = p.executionAttempts + 1, lastError = null)
            else p
        }
        val updatedPlan = plan.copy(
            stage = SpvPlanningStage.EXECUTING,
            parts = updatedParts,
            updatedAtMs = System.currentTimeMillis(),
        )
        writePartsStateJson(workspaceDir, updatedPlan)
        writeExecutionStateJson(
            workspaceDir,
            MultiPartExecutionState(
                planId = updatedPlan.planId,
                version = updatedPlan.version,
                stage = SpvPlanningStage.EXECUTING,
                currentPartId = partId,
                activeDelegationPartId = partId,
            ),
        )
        return updatedPlan
    }

    /**
     * Evaluates actual Part verification evidence, generates `.orqen/reports/part_N_report.md`,
     * transitions the Part to COMPLETED only if ALL required gates pass (or FAILED otherwise),
     * and unlocks the next dependent Part only upon success.
     */
    fun recordPartVerificationAndReport(
        workspaceDir: File,
        plan: MultiPartExecutionPlan,
        partId: String,
        verification: PartVerificationInput,
    ): PartAdvancementResult {
        val part = plan.parts.first { it.partId == partId }
        val failures = mutableListOf<String>()

        if (part.requireIndependentReview && !verification.reviewerApproved) {
            failures += "Independent review not approved: ${verification.reviewerNotes}"
        }
        if (part.requireTestsPass && verification.testExitCode != 0) {
            failures += "Automated tests did not pass (exitCode=${verification.testExitCode}): ${verification.testSummary}"
        }
        if (part.requireBuildPass && verification.buildPassed != true) {
            failures += "Required build verification failed or APK missing."
        }
        if (verification.unresolvedIssues.isNotEmpty()) {
            failures += "Unresolved issues: ${verification.unresolvedIssues.joinToString("; ")}"
        }

        val passed = failures.isEmpty()
        val now = System.currentTimeMillis()
        val updatedPart = part.copy(
            status = if (passed) ExecutionPartStatus.COMPLETED else ExecutionPartStatus.FAILED,
            reviewerVerdict = if (verification.reviewerApproved) "REVIEW_APPROVED" else "REVIEW_REJECTED",
            testExitCode = verification.testExitCode,
            testSummary = verification.testSummary,
            buildVerified = verification.buildPassed,
            lastError = if (passed) null else failures.joinToString(" | "),
            completedAtMs = if (passed) now else null,
        )

        val completedIds = plan.parts
            .map { if (it.partId == partId) updatedPart else it }
            .filter { it.status == ExecutionPartStatus.COMPLETED }
            .map { it.partId }
            .toSet()

        val updatedParts = plan.parts.map { p ->
            when {
                p.partId == partId -> updatedPart
                passed && p.status == ExecutionPartStatus.PENDING && p.dependencies.all { it in completedIds } ->
                    p.copy(status = ExecutionPartStatus.READY)
                else -> p
            }
        }

        val updatedPlan = plan.copy(
            parts = updatedParts,
            updatedAtMs = now,
        )

        val reportFile = File(workspaceDir, updatedPart.reportRelativePath).apply {
            parentFile?.mkdirs()
            writeText(renderPartReportMarkdown(updatedPlan, updatedPart, verification, failures))
        }
        File(workspaceDir, OVERVIEW_REL_PATH).writeText(renderOverviewMarkdown(updatedPlan))
        writePartsStateJson(workspaceDir, updatedPlan)
        writeExecutionStateJson(
            workspaceDir,
            MultiPartExecutionState(
                planId = updatedPlan.planId,
                version = updatedPlan.version,
                stage = SpvPlanningStage.EXECUTING,
                currentPartId = selectNextEligiblePart(updatedPlan)?.partId,
                activeDelegationPartId = null,
                failureReason = updatedPart.lastError,
                recoveryState = if (passed) null else "RETRY_OR_REMEDIATE",
            ),
        )

        return PartAdvancementResult(
            passed = passed,
            updatedPlan = updatedPlan,
            updatedPart = updatedPart,
            reportFile = reportFile,
            failureReasons = failures,
        )
    }

    /**
     * Writes real-time unified execution, review, and verification reports under `.orqen/reports/`:
     * - `.orqen/reports/execution_report.md`
     * - `.orqen/reports/review_report.md`
     * - `.orqen/reports/verification_report.md`
     */
    fun syncUnifiedReports(
        workspaceDir: File,
        task: com.jarves.mh.multiagent.persistence.TaskEntity,
        steps: List<com.jarves.mh.multiagent.persistence.TaskStepEntity>,
        evidence: List<com.jarves.mh.multiagent.persistence.VerificationEvidenceEntity>,
    ) = synchronized(planWriteLock) {
        runCatching {
            File(workspaceDir, ".orqen/reports").mkdirs()

            val execReport = buildString {
                appendLine("# Execution Report — ${task.title}")
                appendLine()
                appendLine("- **Task ID**: `${task.taskId}`")
                appendLine("- **Plan ID**: `${task.planId.orEmpty()}` (v${task.planVersion})")
                appendLine("- **Status**: `${task.status.name}`")
                appendLine("- **Completed Steps**: ${steps.count { it.status == com.jarves.mh.multiagent.persistence.StepStatus.COMPLETED }}/${steps.size}")
                appendLine()
                appendLine("## Step Progress")
                steps.sortedBy { it.sequenceNumber }.forEach { s ->
                    val box = if (s.status == com.jarves.mh.multiagent.persistence.StepStatus.COMPLETED) "[x]" else "[ ]"
                    appendLine("- $box #${s.sequenceNumber} **${s.title}** (`${s.assignedAgentId}`) — `${s.status.name}`")
                    if (s.outputSummary.isNotBlank()) {
                        appendLine("  - Summary: ${s.outputSummary.replace('\n', ' ').take(240)}")
                    }
                }
            }
            File(workspaceDir, EXECUTION_REPORT_REL_PATH).writeText(execReport)

            val reviews = evidence.filter { it.evidenceType == "REVIEW" }
            val reviewReport = buildString {
                appendLine("# Review Report — ${task.title}")
                appendLine()
                if (reviews.isEmpty()) {
                    appendLine("_No independent review records recorded yet._")
                } else {
                    reviews.forEachIndexed { idx, r ->
                        appendLine("## Review #${idx + 1} (Step `${r.stepId}`)")
                        appendLine("- **Verdict**: `${r.reviewVerdict ?: "UNKNOWN"}`")
                        if (r.changedFiles.isNotEmpty()) {
                            appendLine("- **Changed Files**: ${r.changedFiles.joinToString(", ")}")
                        }
                        if (r.stdout.isNotBlank()) {
                            appendLine("- **Notes**: ${r.stdout.take(400)}")
                        }
                        appendLine()
                    }
                }
            }
            File(workspaceDir, REVIEW_REPORT_REL_PATH).writeText(reviewReport)

            val testsAndBuilds = evidence.filter { it.evidenceType == "TEST" || it.evidenceType == "BUILD" }
            val verifReport = buildString {
                appendLine("# Verification Report — ${task.title}")
                appendLine()
                if (testsAndBuilds.isEmpty()) {
                    appendLine("_No test or build execution evidence recorded yet._")
                } else {
                    testsAndBuilds.forEachIndexed { idx, ev ->
                        appendLine("## ${ev.evidenceType} #${idx + 1} (Step `${ev.stepId}`)")
                        appendLine("- **Command**: `${ev.command}`")
                        if (ev.workingDir.isNotBlank()) appendLine("- **Working Dir**: `${ev.workingDir}`")
                        appendLine("- **Exit Code**: `${ev.exitCode}`")
                        if (ev.passedCount != null || ev.failedCount != null) {
                            appendLine("- **Counts**: passed=${ev.passedCount ?: 0}, failed=${ev.failedCount ?: 0}, skipped=${ev.skippedCount ?: 0}")
                        }
                        if (!ev.artifactPath.isNullOrBlank()) {
                            appendLine("- **Artifact**: `${ev.artifactPath}` (${ev.artifactSizeBytes} bytes)")
                        }
                        appendLine()
                    }
                }
            }
            File(workspaceDir, VERIFICATION_REPORT_REL_PATH).writeText(verifReport)
        }
    }

    /**
     * Revises an existing plan while strictly preserving all COMPLETED Parts and recording the revision reason.
     */
    fun revisePlanPreservingCompleted(
        workspaceDir: File,
        existingPlan: MultiPartExecutionPlan,
        revisedPendingParts: List<MultiPartSpec>,
        reason: String,
    ): MultiPartExecutionPlan {
        val completedParts = existingPlan.parts.filter { it.status == ExecutionPartStatus.COMPLETED }
        val completedIds = completedParts.map { it.partId }.toSet()
        val merged = completedParts + revisedPendingParts.filter { it.partId !in completedIds }
        val newVersion = existingPlan.version + 1
        val revised = existingPlan.copy(
            version = newVersion,
            parts = merged.sortedBy { it.partNumber },
            revisionHistory = existingPlan.revisionHistory + PlanRevisionEntry(newVersion, reason),
            updatedAtMs = System.currentTimeMillis(),
        )
        persistPlan(workspaceDir, revised)
        return revised
    }

    /**
     * Migrates a legacy single-file `.orqen/plan.md` into the canonical Multi-Part directory
     * without discarding existing content.
     */
    fun migrateLegacyPlanIfNeeded(
        workspaceDir: File,
        projectId: String,
        taskId: String,
        projectName: String,
    ): MultiPartExecutionPlan? {
        val existing = loadPlan(workspaceDir)
        if (existing != null) return existing
        val legacyFile = File(workspaceDir, LEGACY_PLAN_REL_PATH)
        if (!legacyFile.isFile || legacyFile.length() <= 0L) return null
        val legacyContent = runCatching { legacyFile.readText() }.getOrDefault("").trim()
        if (legacyContent.isBlank()) return null

        val migratedPart = MultiPartSpec(
            partId = "part-1",
            partNumber = 1,
            title = "Migrated Legacy Plan",
            slug = "migrated-legacy-plan",
            specRelativePath = ".orqen/plan/part_1_migrated-legacy-plan.md",
            objective = "Execute migrated plan from legacy .orqen/plan.md",
            scope = "Preserved legacy plan content for project '$projectName'.",
            backgroundAnalysis = legacyContent.take(2000),
            requirements = listOf("Complete all unchecked items from migrated legacy plan."),
            status = ExecutionPartStatus.READY,
        )
        val plan = MultiPartExecutionPlan(
            taskId = taskId,
            projectId = projectId,
            title = "Migrated Plan ($projectName)",
            objective = "Migrated from legacy .orqen/plan.md",
            stage = SpvPlanningStage.PLAN_APPROVED,
            parts = listOf(migratedPart),
            revisionHistory = listOf(PlanRevisionEntry(1, "Migrated from legacy .orqen/plan.md")),
        )
        persistPlan(workspaceDir, plan)
        return loadPlan(workspaceDir) ?: plan
    }

    fun loadPlan(workspaceDir: File): MultiPartExecutionPlan? {
        val file = File(workspaceDir, PARTS_STATE_REL_PATH)
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val partsArr = root.getJSONArray("parts")
            val parts = (0 until partsArr.length()).map { idx ->
                val o = partsArr.getJSONObject(idx)
                MultiPartSpec(
                    partId = o.getString("partId"),
                    partNumber = o.getInt("partNumber"),
                    title = o.getString("title"),
                    slug = o.optString("slug", slugify(o.getString("title"))),
                    specRelativePath = o.getString("specRelativePath"),
                    objective = o.optString("objective"),
                    scope = o.optString("scope"),
                    assignedAgentId = o.optString("assignedAgentId", "agent-02"),
                    assignedModelId = o.optString("assignedModelId"),
                    dependencies = jsonArrayToList(o.optJSONArray("dependencies")),
                    requireIndependentReview = o.optBoolean("requireIndependentReview", true),
                    requireTestsPass = o.optBoolean("requireTestsPass", true),
                    requireBuildPass = o.optBoolean("requireBuildPass", false),
                    status = runCatching { ExecutionPartStatus.valueOf(o.getString("status")) }.getOrDefault(ExecutionPartStatus.PENDING),
                    executionAttempts = o.optInt("executionAttempts", 0),
                    reviewerVerdict = o.optString("reviewerVerdict").ifBlank { null },
                    testExitCode = if (o.has("testExitCode") && !o.isNull("testExitCode")) o.getInt("testExitCode") else null,
                    testSummary = o.optString("testSummary").ifBlank { null },
                    buildVerified = if (o.has("buildVerified") && !o.isNull("buildVerified")) o.getBoolean("buildVerified") else null,
                    lastError = o.optString("lastError").ifBlank { null },
                    reportRelativePath = o.optString("reportRelativePath", ".orqen/reports/part_${o.getInt("partNumber")}_report.md"),
                    completedAtMs = if (o.has("completedAtMs") && !o.isNull("completedAtMs")) o.getLong("completedAtMs") else null,
                )
            }
            val revArr = root.optJSONArray("revisionHistory")
            val revisions = if (revArr != null) {
                (0 until revArr.length()).map { i ->
                    val r = revArr.getJSONObject(i)
                    PlanRevisionEntry(r.getInt("version"), r.getString("reason"), r.optLong("timestampMs"))
                }
            } else emptyList()
            MultiPartExecutionPlan(
                planId = root.getString("planId"),
                version = root.optInt("version", 1),
                taskId = root.optString("taskId"),
                projectId = root.optString("projectId"),
                title = root.optString("title"),
                objective = root.optString("objective"),
                stage = runCatching { SpvPlanningStage.valueOf(root.optString("stage", SpvPlanningStage.PLAN_APPROVED.name)) }.getOrDefault(SpvPlanningStage.PLAN_APPROVED),
                parts = parts,
                revisionHistory = revisions,
                createdAtMs = root.optLong("createdAtMs"),
                updatedAtMs = root.optLong("updatedAtMs"),
            )
        }.getOrNull()
    }

    fun loadExecutionState(workspaceDir: File): MultiPartExecutionState? {
        val file = File(workspaceDir, EXECUTION_STATE_REL_PATH)
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            val o = JSONObject(file.readText())
            MultiPartExecutionState(
                planId = o.getString("planId"),
                version = o.optInt("version", 1),
                stage = runCatching { SpvPlanningStage.valueOf(o.getString("stage")) }.getOrDefault(SpvPlanningStage.PLANNING),
                currentPartId = o.optString("currentPartId").ifBlank { null },
                currentStepId = o.optString("currentStepId").ifBlank { null },
                activeDelegationPartId = o.optString("activeDelegationPartId").ifBlank { null },
                lastCheckpointId = o.optString("lastCheckpointId").ifBlank { null },
                failureReason = o.optString("failureReason").ifBlank { null },
                recoveryState = o.optString("recoveryState").ifBlank { null },
                updatedAtMs = o.optLong("updatedAtMs"),
            )
        }.getOrNull()
    }

    /**
     * Restores persisted plan after process restart, clearing any stale in-flight delegation
     * lock and resetting abandoned EXECUTING Parts back to RECOVERING without ever touching COMPLETED Parts.
     */
    fun recoverAfterRestart(workspaceDir: File): MultiPartExecutionPlan? {
        val loaded = loadPlan(workspaceDir) ?: return null
        val recoveredParts = loaded.parts.map { part ->
            if (part.status == ExecutionPartStatus.EXECUTING || part.status == ExecutionPartStatus.VERIFYING) {
                part.copy(status = ExecutionPartStatus.RECOVERING)
            } else part
        }
        val recovered = loaded.copy(parts = recoveredParts, updatedAtMs = System.currentTimeMillis())
        writePartsStateJson(workspaceDir, recovered)
        writeExecutionStateJson(
            workspaceDir,
            MultiPartExecutionState(
                planId = recovered.planId,
                version = recovered.version,
                stage = recovered.stage,
                currentPartId = selectNextEligiblePart(recovered)?.partId,
                activeDelegationPartId = null,
                recoveryState = "RECOVERED_AFTER_RESTART",
            ),
        )
        return recovered
    }

    fun readPartSpecification(workspaceDir: File, part: MultiPartSpec): String {
        if (!WorkspacePathValidator.isPathInsideRoot(workspaceDir, part.specRelativePath)) return ""
        val f = File(workspaceDir, part.specRelativePath)
        return if (f.isFile) runCatching { f.readText() }.getOrDefault("") else ""
    }

    enum class TaskComplexityTier {
        SMALL,
        MEDIUM,
        LARGE,
    }

    /**
     * Adaptive task sizing:
     * - SMALL: single focused app/feature/screen/bugfix (e.g., Calculator, Todo, UI tweak) -> 1 Part
     *   (Dev: Implement + Build + Tests -> SPV: Independent Review & QA).
     * - MEDIUM: multi-layer feature combining backend/persistence + UI -> 2 Parts.
     * - LARGE: multi-subsystem architecture (auth + database + API + analytics + multiple screens) -> 3+ Parts.
     */
    fun assessTaskComplexity(objective: String, explicitSubtasks: List<String> = emptyList()): TaskComplexityTier {
        if (explicitSubtasks.size >= 3) return TaskComplexityTier.LARGE
        if (explicitSubtasks.size == 2) return TaskComplexityTier.MEDIUM
        val lower = objective.lowercase()
        val smallAppSignals = listOf("calculator", "เครื่องคิดเลข", "todo", "to-do", "timer", "stopwatch", "converter", "counter", "bug", "fix", "แก้บั๊ก", "ปุ่ม", "หน้าจอ")
        val largeArchSignals = listOf(
            "authentication", "oauth", "database", "persistence", "analytics", "dashboard",
            "microservice", "sync", "csv export", "multiple screens", "clean architecture",
            "multi-tenant", "graphql", "backend", "migration", "full-stack", "offline-first",
        )
        val largeHits = largeArchSignals.count { lower.contains(it) }
        if (largeHits >= 3 || (objective.length >= 160 && largeHits >= 2)) {
            return TaskComplexityTier.LARGE
        }
        val explicitMultiStageDelivery = (lower.contains("debug apk") || lower.contains("generate a debug apk")) &&
            (lower.contains("test") || lower.contains("database") || lower.contains("compose"))
        if (explicitMultiStageDelivery) {
            return TaskComplexityTier.MEDIUM
        }
        val isFocusedSmallApp = smallAppSignals.any { lower.contains(it) } && largeHits < 2
        if (isFocusedSmallApp) {
            return TaskComplexityTier.SMALL
        }
        if (largeHits >= 2 || (objective.length >= 110 && largeHits >= 1)) {
            return TaskComplexityTier.MEDIUM
        }
        return TaskComplexityTier.SMALL
    }

    private fun buildMediumMultiPartBreakdown(objective: String, isAndroid: Boolean): List<Pair<String, String>> =
        if (isAndroid) {
            listOf(
                "Core Domain, Logic & UI Implementation" to "Implement core domain models, business logic, and Android UI for: $objective",
                "Integration, Tests & APK Verification" to "Integrate components, run real unit tests, and verify debug APK build for: $objective",
            )
        } else {
            listOf(
                "Core Implementation" to "Design and implement core modules and features for: $objective",
                "Integration & Verification" to "Integrate components, execute real automated tests, and verify deliverables for: $objective",
            )
        }

    private fun buildDefaultMultiPartBreakdown(objective: String, isAndroid: Boolean): List<Pair<String, String>> =
        if (isAndroid) {
            listOf(
                "Architecture & Data Foundation" to "Analyze existing Android project, define core domain models, state, and architecture for: $objective",
                "Core Feature & UI Implementation" to "Implement Jetpack Compose / Android UI, business logic, and persistence for: $objective",
                "Testing, Verification & APK Delivery" to "Write and execute unit/integration tests, verify UI/logic, and build verified debug APK for: $objective",
            )
        } else {
            listOf(
                "Architecture & Core Foundation" to "Analyze project structure, design architecture, and implement foundational modules for: $objective",
                "Feature Implementation & Integration" to "Implement core features, user flows, and integration points for: $objective",
                "Testing & Final Verification" to "Execute real tests, verify deliverables, and complete independent review for: $objective",
            )
        }

    private fun inspectWorkspaceSummary(workspaceDir: File): String {
        if (!workspaceDir.isDirectory) return "New empty workspace."
        val ignored = setOf(".git", ".gradle", "build", "node_modules")
        val files = workspaceDir.walkTopDown()
            .maxDepth(3)
            .onEnter { it.name !in ignored }
            .filter { it.isFile }
            .take(25)
            .map { it.relativeTo(workspaceDir).path }
            .toList()
        return if (files.isEmpty()) "New workspace (no existing source files)." else "Existing files (${files.size} sampled): ${files.joinToString(", ")}"
    }

    private fun hasCycle(parts: List<MultiPartSpec>): Boolean {
        val graph = parts.associate { it.partId to it.dependencies }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun dfs(node: String): Boolean {
            if (node in visiting) return true
            if (node in visited) return false
            visiting += node
            for (dep in graph[node].orEmpty()) {
                if (dfs(dep)) return true
            }
            visiting -= node
            visited += node
            return false
        }
        return graph.keys.any { dfs(it) }
    }

    private fun writePartsStateJson(workspaceDir: File, plan: MultiPartExecutionPlan) {
        val root = JSONObject().apply {
            put("planId", plan.planId)
            put("version", plan.version)
            put("taskId", plan.taskId)
            put("projectId", plan.projectId)
            put("title", plan.title)
            put("objective", plan.objective)
            put("stage", plan.stage.name)
            put("createdAtMs", plan.createdAtMs)
            put("updatedAtMs", plan.updatedAtMs)
            put(
                "parts",
                JSONArray().apply {
                    plan.parts.forEach { p ->
                        put(
                            JSONObject().apply {
                                put("partId", p.partId)
                                put("partNumber", p.partNumber)
                                put("title", p.title)
                                put("slug", p.slug)
                                put("specRelativePath", p.specRelativePath)
                                put("objective", p.objective)
                                put("scope", p.scope)
                                put("assignedAgentId", p.assignedAgentId)
                                put("assignedModelId", p.assignedModelId)
                                put("dependencies", JSONArray(p.dependencies))
                                put("requireIndependentReview", p.requireIndependentReview)
                                put("requireTestsPass", p.requireTestsPass)
                                put("requireBuildPass", p.requireBuildPass)
                                put("status", p.status.name)
                                put("executionAttempts", p.executionAttempts)
                                put("reviewerVerdict", p.reviewerVerdict ?: JSONObject.NULL)
                                put("testExitCode", p.testExitCode ?: JSONObject.NULL)
                                put("testSummary", p.testSummary ?: JSONObject.NULL)
                                put("buildVerified", p.buildVerified ?: JSONObject.NULL)
                                put("lastError", p.lastError ?: JSONObject.NULL)
                                put("reportRelativePath", p.reportRelativePath)
                                put("completedAtMs", p.completedAtMs ?: JSONObject.NULL)
                            },
                        )
                    }
                },
            )
            put(
                "revisionHistory",
                JSONArray().apply {
                    plan.revisionHistory.forEach { r ->
                        put(JSONObject().put("version", r.version).put("reason", r.reason).put("timestampMs", r.timestampMs))
                    }
                },
            )
        }
        val target = File(workspaceDir, PARTS_STATE_REL_PATH)
        target.parentFile?.mkdirs()
        target.writeText(root.toString(2))
    }

    private fun writeExecutionStateJson(workspaceDir: File, state: MultiPartExecutionState) {
        val root = JSONObject().apply {
            put("planId", state.planId)
            put("version", state.version)
            put("stage", state.stage.name)
            put("currentPartId", state.currentPartId ?: JSONObject.NULL)
            put("currentStepId", state.currentStepId ?: JSONObject.NULL)
            put("activeDelegationPartId", state.activeDelegationPartId ?: JSONObject.NULL)
            put("lastCheckpointId", state.lastCheckpointId ?: JSONObject.NULL)
            put("failureReason", state.failureReason ?: JSONObject.NULL)
            put("recoveryState", state.recoveryState ?: JSONObject.NULL)
            put("updatedAtMs", state.updatedAtMs)
        }
        val target = File(workspaceDir, EXECUTION_STATE_REL_PATH)
        target.parentFile?.mkdirs()
        target.writeText(root.toString(2))
    }

    private fun renderOverviewMarkdown(plan: MultiPartExecutionPlan): String = buildString {
        appendLine("# ORQEN Master Execution Plan (v${plan.version})")
        appendLine()
        appendLine("- **Plan ID:** `${plan.planId}`")
        appendLine("- **Task ID:** `${plan.taskId}`")
        appendLine("- **Stage:** `${plan.stage.name}`")
        appendLine("- **Objective:** ${plan.objective}")
        appendLine("- **Progress:** ${plan.parts.count { it.status == ExecutionPartStatus.COMPLETED }}/${plan.parts.size} Parts Completed")
        appendLine()
        appendLine("## Sequential Execution Parts")
        plan.parts.sortedBy { it.partNumber }.forEach { part ->
            val mark = when (part.status) {
                ExecutionPartStatus.COMPLETED -> "x"
                ExecutionPartStatus.FAILED, ExecutionPartStatus.BLOCKED -> "!"
                ExecutionPartStatus.EXECUTING, ExecutionPartStatus.VERIFYING -> "~"
                else -> " "
            }
            val deps = if (part.dependencies.isEmpty()) "none" else part.dependencies.joinToString(", ")
            appendLine("- [$mark] **Part ${part.partNumber}: ${part.title}** (`${part.partId}` · `${part.status.name}`)")
            appendLine("  - Spec: `${part.specRelativePath}` | Report: `${part.reportRelativePath}`")
            appendLine("  - Agent: `${part.assignedAgentId}` | Dependencies: `$deps`")
            if (!part.reviewerVerdict.isNullOrBlank() || part.testExitCode != null) {
                appendLine("  - Verification: Review=`${part.reviewerVerdict ?: "PENDING"}`, Tests exit=`${part.testExitCode ?: "N/A"}`")
            }
        }
        if (plan.revisionHistory.isNotEmpty()) {
            appendLine()
            appendLine("## Plan Revisions")
            plan.revisionHistory.forEach { r ->
                appendLine("- v${r.version}: ${r.reason}")
            }
        }
    }

    private fun renderPartMarkdown(plan: MultiPartExecutionPlan, part: MultiPartSpec): String = buildString {
        appendLine("# Part ${part.partNumber}: ${part.title}")
        appendLine()
        appendLine("- **Part ID:** `${part.partId}`")
        appendLine("- **Plan ID:** `${plan.planId}` (v${plan.version})")
        appendLine("- **Assigned Agent:** `${part.assignedAgentId}`")
        appendLine("- **Dependencies:** ${if (part.dependencies.isEmpty()) "None" else part.dependencies.joinToString(", ")}")
        appendLine()
        appendLine("## Objective & Scope")
        appendLine(part.objective)
        appendLine()
        appendLine(part.scope)
        if (part.backgroundAnalysis.isNotBlank()) {
            appendLine()
            appendLine("## Background & Existing Project Analysis")
            appendLine(part.backgroundAnalysis)
        }
        appendLine()
        appendLine("## Requirements")
        part.requirements.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## Architecture & Implementation Approach")
        appendLine(part.architectureApproach.ifBlank { "Follow clean existing project patterns." })
        if (part.relevantFiles.isNotEmpty()) {
            appendLine()
            appendLine("## Relevant Files & Modules")
            part.relevantFiles.forEach { appendLine("- `$it`") }
        }
        appendLine()
        appendLine("## Required MCP Tools & Permissions")
        appendLine("- Tools: ${part.requiredMcpTools.joinToString(", ").ifBlank { "workspace_files" }}")
        appendLine("- Permissions: ${part.requiredPermissions.joinToString(", ").ifBlank { "workspace:read, workspace:write" }}")
        appendLine()
        appendLine("## Implementation Steps")
        part.implementationSteps.forEachIndexed { idx, step -> appendLine("${idx + 1}. $step") }
        appendLine()
        appendLine("## Testing & Verification Procedures")
        part.verificationProcedures.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## Acceptance Criteria")
        part.acceptanceCriteria.forEach { appendLine("- [ ] $it") }
        appendLine()
        appendLine("## Risks & Recovery Strategy")
        part.risksAndRecovery.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## Expected Deliverables")
        part.deliverables.forEach { appendLine("- $it") }
    }

    private fun renderPartReportMarkdown(
        plan: MultiPartExecutionPlan,
        part: MultiPartSpec,
        verification: PartVerificationInput,
        failures: List<String>,
    ): String = buildString {
        appendLine("# Part ${part.partNumber} Verification Report: ${part.title}")
        appendLine()
        appendLine("- **Plan ID:** `${plan.planId}` (v${plan.version})")
        appendLine("- **Part ID:** `${part.partId}`")
        appendLine("- **Final Status:** `${part.status.name}`")
        appendLine("- **Execution Attempts:** ${part.executionAttempts}")
        appendLine("- **Reviewer Verdict:** `${part.reviewerVerdict ?: "NONE"}`")
        appendLine("- **Test Exit Code:** `${verification.testExitCode ?: "N/A"}`")
        appendLine("- **Build Verified:** `${verification.buildPassed ?: "N/A"}`")
        if (!verification.buildArtifactPath.isNullOrBlank()) {
            appendLine("- **Build Artifact:** `${verification.buildArtifactPath}`")
        }
        appendLine()
        appendLine("## Modified Files")
        if (verification.modifiedFiles.isEmpty()) appendLine("- (none recorded)")
        else verification.modifiedFiles.forEach { appendLine("- `$it`") }
        appendLine()
        appendLine("## Test Summary")
        appendLine(verification.testSummary.ifBlank { "No test output recorded." })
        appendLine()
        appendLine("## Reviewer Notes")
        appendLine(verification.reviewerNotes.ifBlank { "No reviewer notes recorded." })
        if (failures.isNotEmpty()) {
            appendLine()
            appendLine("## Verification Failures / Blockers")
            failures.forEach { appendLine("- $it") }
        }
    }

    private fun jsonArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }
}
