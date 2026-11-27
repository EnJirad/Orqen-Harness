package com.jarves.mh.multiagent.engine

import com.jarves.mh.multiagent.collab.AgentCommunicationManager
import com.jarves.mh.multiagent.collab.AgentReviewEntity
import com.jarves.mh.multiagent.persistence.*

/**
 * Targeted context assembler for Multi-Agent Step Execution.
 *
 * Includes ONLY authorized, task-scoped information:
 * 1. Project-level memory/constraints
 * 2. Original task objective & acceptance criteria
 * 3. Compact task plan overview & dependencies
 * 4. Concise summaries of relevant completed dependency steps
 * 5. Latest valid checkpoint metadata & target file references
 * 6. Relevant review feedback for this step/agent
 * 7. Isolated task-scoped agent messages (direct to this agent + task broadcasts)
 * 8. Current step instructions
 */
object TaskContextBuilder {
    fun buildStepContext(
        project: ProjectEntity,
        task: TaskEntity,
        step: TaskStepEntity,
        allSteps: List<TaskStepEntity>,
        latestCheckpoint: CheckpointEntity?,
        recentMessages: List<AgentMessageEntity> = emptyList(),
        reviewFeedback: List<AgentReviewEntity> = emptyList(),
        maxChars: Int = 16_000,
    ): String {
        require(task.projectId == project.projectId) {
            "Cross-project context violation: task ${task.taskId} belongs to ${task.projectId}, not ${project.projectId}"
        }
        val relevantIds = step.dependencies.toSet()
        val relevantCompleted = allSteps.filter {
            it.status == StepStatus.COMPLETED &&
                (it.stepId in relevantIds || it.sequenceNumber >= (step.sequenceNumber - 3))
        }.takeLast(5)

        val relevantMsgs = recentMessages.filter { msg ->
            msg.taskId == task.taskId && (
                msg.toAgentId == step.assignedAgentId ||
                    msg.toAgentId == AgentCommunicationManager.BROADCAST_TARGET ||
                    msg.fromAgentId == step.assignedAgentId
                )
        }.takeLast(8)

        val relevantReviews = reviewFeedback.filter { rev ->
            rev.taskId == task.taskId &&
                (rev.reviewedStepId == step.stepId || rev.implementingAgentId == step.assignedAgentId || rev.reviewedStepId in relevantIds)
        }.takeLast(4)

        val workspaceDir = project.workspacePath.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
        val multiPartPlan = workspaceDir?.let { MultiPartPlanManager.loadPlan(it) }
        val matchedPart = multiPartPlan?.parts?.firstOrNull { p ->
            step.stepId.contains(p.partId, ignoreCase = true) ||
                step.title.contains("Part ${p.partNumber}", ignoreCase = true) ||
                step.description.contains(p.specRelativePath, ignoreCase = true)
        } ?: multiPartPlan?.let { MultiPartPlanManager.selectNextEligiblePart(it) }
        val partSpecContent = if (workspaceDir != null && matchedPart != null) {
            MultiPartPlanManager.readPartSpecification(workspaceDir, matchedPart)
        } else ""
        val overviewContent = if (workspaceDir != null) {
            val f = java.io.File(workspaceDir, MultiPartPlanManager.OVERVIEW_REL_PATH)
            if (f.isFile) runCatching { f.readText().take(900) }.getOrDefault("") else ""
        } else ""

        val envelope = TaskAssignmentEnvelope(
            taskId = task.taskId,
            planId = multiPartPlan?.planId ?: "plan-${task.taskId}",
            planVersion = multiPartPlan?.version ?: 1,
            partId = matchedPart?.partId ?: "part-1",
            stepId = step.stepId,
            assignedAgentId = step.assignedAgentId,
            specificationPath = matchedPart?.specRelativePath ?: MultiPartPlanManager.OVERVIEW_REL_PATH,
            specificationContent = partSpecContent,
            immutableUserGoal = "${task.title}: ${task.description.take(500)}",
            overallPlanOverview = overviewContent,
            currentScope = "${step.title} — ${step.description}",
            expectedDeliverables = matchedPart?.deliverables?.ifEmpty { listOf(step.inputContext).filter(String::isNotBlank) }
                ?: listOf(step.inputContext).filter(String::isNotBlank),
            acceptanceCriteria = matchedPart?.acceptanceCriteria?.ifEmpty { task.completionCriteria.acceptanceChecks }
                ?: task.completionCriteria.acceptanceChecks,
            dependencies = step.dependencies,
            relevantPreviousResults = relevantCompleted.map { "#${it.sequenceNumber} ${it.title}: ${it.outputSummary.take(200)}" },
        )

        return buildString {
            appendLine(envelope.renderEnvelopeText())
            if (project.projectMemory.isNotBlank()) {
                appendLine("## Project Memory (${project.projectName})")
                appendLine(project.projectMemory.take(800))
                appendLine()
            }
            if (allSteps.size > 1) {
                appendLine("## Task Plan Overview (${allSteps.count { it.status == StepStatus.COMPLETED }}/${allSteps.size} completed)")
                allSteps.take(12).forEach { s ->
                    val deps = if (s.dependencies.isEmpty()) "" else " [deps: ${s.dependencies.joinToString(",")}]"
                    appendLine("- #${s.sequenceNumber} [${s.status}] ${s.title} (${s.assignedAgentId})$deps")
                }
                val peerCoders = allSteps.filter {
                    !it.requiresReview &&
                        it.stepId != step.stepId &&
                        it.assignedAgentId != step.assignedAgentId &&
                        it.status in setOf(StepStatus.READY, StepStatus.RUNNING, StepStatus.PENDING)
                }
                if (!step.requiresReview && peerCoders.isNotEmpty()) {
                    appendLine()
                    appendLine("## Parallel Multi-Coder Collaboration Protocol")
                    appendLine("- Multiple coding agents are dividing this project to finish faster:")
                    peerCoders.take(4).forEach { peer ->
                        appendLine("  - ${peer.assignedAgentId} owns #${peer.sequenceNumber} '${peer.title}'")
                    }
                    appendLine("- Inspect current workspace files first, build your assigned scope cleanly without duplicating teammate work, and coordinate shared contracts via `[MSG to=<agentId>]`.")
                }
                appendLine()
            }
            if (latestCheckpoint != null && latestCheckpoint.isValid) {
                appendLine("## Latest Valid Checkpoint (${latestCheckpoint.checkpointId})")
                if (latestCheckpoint.modifiedFiles.isNotEmpty()) {
                    appendLine("Modified files: ${latestCheckpoint.modifiedFiles.take(15).joinToString(", ")}")
                }
                if (latestCheckpoint.contextSummary.isNotBlank()) {
                    appendLine("Summary: ${latestCheckpoint.contextSummary.take(250)}")
                }
                appendLine()
            }
            if (relevantReviews.isNotEmpty()) {
                appendLine("## Review Feedback")
                relevantReviews.forEach { r ->
                    val details = (r.findings + r.requiredChanges).joinToString("; ").ifBlank { r.outcome.name }
                    appendLine("- [${r.outcome}] by ${r.reviewerAgentId}: ${details.take(220)}")
                }
                appendLine()
            }
            if (relevantMsgs.isNotEmpty()) {
                appendLine("## Relevant Internal Agent Messages")
                relevantMsgs.forEach { m ->
                    appendLine("- [${m.messageType}] ${m.fromAgentId} -> ${m.toAgentId}: ${m.content.take(200)}")
                }
                appendLine()
            }
            appendLine("## Current Step (#${step.sequenceNumber} / ${step.stepId})")
            appendLine("Title: ${step.title}")
            appendLine("Assigned Agent: ${step.assignedAgentId}")
            appendLine("Instructions: ${step.description}")
            if (step.targetFiles.isNotEmpty()) {
                appendLine("Target Files: ${step.targetFiles.joinToString(", ")}")
            }
            val attachedPaths = TaskAttachmentCodec.decodePaths(task.taskMemory)
            if (attachedPaths.isNotEmpty()) {
                appendLine("Attached Files: ${attachedPaths.joinToString(", ")}")
            }
            if (step.inputContext.isNotBlank()) {
                appendLine("Expected Output: ${step.inputContext}")
            }
            if (step.requiresReview) {
                appendLine()
                appendLine("## Required Review & Verification Output Format")
                appendLine("- Inspect the actual workspace files and verify acceptance criteria.")
                appendLine("- If all requirements and tests pass, include `REVIEW_APPROVED` and `TESTS_PASSED` in your response.")
                appendLine("- If defects or missing work remain, include `REVIEW_REJECTED: <specific actionable feedback>` in your response.")
            }
        }.take(maxChars)
    }

    /**
     * Strictly isolated context builder using [AgentCommunicationManager.getIsolatedContextMessages]
     * to guarantee zero cross-project, cross-task, or unrelated-agent message leakage.
     */
    fun buildAgentStepContext(
        project: ProjectEntity,
        task: TaskEntity,
        step: TaskStepEntity,
        allSteps: List<TaskStepEntity>,
        latestCheckpoint: CheckpointEntity?,
        communicationManager: AgentCommunicationManager,
        reviewFeedback: List<AgentReviewEntity> = emptyList(),
        maxChars: Int = 16_000,
    ): String {
        val isolatedMessages = communicationManager.getIsolatedContextMessages(
            projectId = project.projectId,
            taskId = task.taskId,
            agentId = step.assignedAgentId,
        )
        return buildStepContext(
            project = project,
            task = task,
            step = step,
            allSteps = allSteps,
            latestCheckpoint = latestCheckpoint,
            recentMessages = isolatedMessages,
            reviewFeedback = reviewFeedback,
            maxChars = maxChars,
        )
    }
}
