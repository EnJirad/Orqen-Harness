package com.jarves.mh.multiagent.engine

import com.jarves.mh.multiagent.persistence.*

/**
 * Targeted context assembler for Phase 2 Step Execution.
 *
 * Includes ONLY:
 * 1. Project-level memory/constraints
 * 2. Original task objective & acceptance criteria
 * 3. Concise summaries of relevant completed dependency steps (NOT irrelevant historical bloat)
 * 4. Latest valid checkpoint metadata
 * 5. Current step instructions
 * 6. Relevant recent agent messages for this step/agent
 */
object TaskContextBuilder {
    fun buildStepContext(
        project: ProjectEntity,
        task: TaskEntity,
        step: TaskStepEntity,
        allSteps: List<TaskStepEntity>,
        latestCheckpoint: CheckpointEntity?,
        recentMessages: List<AgentMessageEntity> = emptyList(),
        maxChars: Int = 8_000,
    ): String {
        val relevantIds = step.dependencies.toSet()
        val relevantCompleted = allSteps.filter {
            it.status == StepStatus.COMPLETED &&
                (it.stepId in relevantIds || it.sequenceNumber >= (step.sequenceNumber - 3))
        }.takeLast(5)

        val relevantMsgs = recentMessages.filter {
            it.relatedStepId == step.stepId ||
                it.toAgentId == step.assignedAgentId ||
                it.messageType == AgentMessageKind.SHARED_DECISION ||
                it.messageType == AgentMessageKind.CHANGE_REQUEST
        }.takeLast(5)

        return buildString {
            if (project.projectMemory.isNotBlank()) {
                appendLine("## Project Memory (${project.projectName})")
                appendLine(project.projectMemory.take(1_200))
                appendLine()
            }
            appendLine("## Task Objective (${task.taskId})")
            appendLine("${task.title}: ${task.description.take(800)}")
            if (task.completionCriteria.acceptanceChecks.isNotEmpty()) {
                appendLine("Acceptance Criteria:")
                task.completionCriteria.acceptanceChecks.take(8).forEach { appendLine("- $it") }
            }
            appendLine()
            if (relevantCompleted.isNotEmpty()) {
                appendLine("## Relevant Completed Steps")
                relevantCompleted.forEach { s ->
                    appendLine("- #${s.sequenceNumber} ${s.title}: ${s.outputSummary.take(200)}")
                }
                appendLine()
            }
            if (latestCheckpoint != null && latestCheckpoint.isValid) {
                appendLine("## Latest Valid Checkpoint (${latestCheckpoint.checkpointId})")
                if (latestCheckpoint.modifiedFiles.isNotEmpty()) {
                    appendLine("Modified files: ${latestCheckpoint.modifiedFiles.take(15).joinToString(", ")}")
                }
                if (latestCheckpoint.contextSummary.isNotBlank()) {
                    appendLine("Summary: ${latestCheckpoint.contextSummary.take(300)}")
                }
                appendLine()
            }
            if (relevantMsgs.isNotEmpty()) {
                appendLine("## Relevant Agent Messages")
                relevantMsgs.forEach { m ->
                    appendLine("- [${m.messageType}] ${m.fromAgentId} -> ${m.toAgentId}: ${m.content.take(200)}")
                }
                appendLine()
            }
            appendLine("## Current Step (#${step.sequenceNumber} / ${step.stepId})")
            appendLine("Title: ${step.title}")
            appendLine("Instructions: ${step.description}")
            if (step.inputContext.isNotBlank()) {
                appendLine("Expected Output: ${step.inputContext}")
            }
        }.take(maxChars)
    }
}
