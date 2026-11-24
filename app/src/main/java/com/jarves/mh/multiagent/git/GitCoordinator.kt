package com.jarves.mh.multiagent.git

import com.jarves.mh.multiagent.model.AgentConfig
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.tasks.ProjectPart
import com.jarves.mh.multiagent.tasks.TeamTask
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GitCheckpoint(
    val id: String,
    val partNumber: Int,
    val commitSha: String,
    val commitMessage: String,
    val timestamp: Long = System.currentTimeMillis(),
    val changedFiles: List<String> = emptyList(),
)

class GitCoordinator(private val workspaceDir: File) {

    fun ensureAgentStructure(agents: List<AgentConfig>) {
        val agentRoot = File(workspaceDir, ".agent")
        if (!agentRoot.exists()) agentRoot.mkdirs()

        // 1. README.md
        val readme = File(agentRoot, "README.md")
        if (!readme.exists()) {
            readme.writeText(
                """
                # ORQEN Multi-Agent Workspace Hub
                This directory coordinates asynchronous development, tasks, reviews, and test plans between up to 4 agents.
                """.trimIndent()
            )
        }

        // 2. Communication directories
        for (slot in AgentSlot.entries) {
            File(agentRoot, "communication/inbox/${slot.id}").mkdirs()
            File(agentRoot, "communication/outbox/${slot.id}").mkdirs()
        }
        File(agentRoot, "communication/history").mkdirs()

        // 3. Management files
        val mgmt = File(agentRoot, "management").apply { mkdirs() }
        touchIfNotExists(File(mgmt, "PROJECT_STATUS.md"), "# Project Status\nInitialized ORQEN Multi-Agent AI Workspace.\n")
        touchIfNotExists(File(mgmt, "CURRENT_TASK.md"), "# Current Task\nIdle\n")
        touchIfNotExists(File(mgmt, "TASK_QUEUE.md"), "# Task Queue\n")
        touchIfNotExists(File(mgmt, "DEVELOPMENT_PLAN.md"), "# Development Plan\nParts 01 to 10\n")

        // 4. Instructions
        val inst = File(agentRoot, "instructions").apply { mkdirs() }
        for (agent in agents) {
            val fileName = "AGENT_${agent.slot.slotNumber.toString().padStart(2, '0')}_${agent.role.type.name}.md"
            val file = File(inst, fileName)
            file.writeText(
                """
                # Instructions for ${agent.name} (${agent.role.title})
                - Slot: ${agent.slot.id}
                - Responsibilities:
                ${agent.role.responsibilities.joinToString("\n") { "  - $it" }}
                
                System Prompt:
                ${agent.role.systemInstructions}
                """.trimIndent()
            )
        }

        // 5. Reports & testing
        File(agentRoot, "reports/testing").mkdirs()
        File(agentRoot, "reports/bugs").mkdirs()
        File(agentRoot, "reports/completed").mkdirs()
        File(agentRoot, "reports/reviews").mkdirs()

        val testing = File(agentRoot, "testing").apply { mkdirs() }
        touchIfNotExists(File(testing, "TEST_PLAN.md"), "# Test Plan\n")
        touchIfNotExists(File(testing, "TEST_CASES.md"), "# Test Cases\n")
        touchIfNotExists(File(testing, "TEST_RESULTS.md"), "# Test Results\n")

        // 6. State
        val state = File(agentRoot, "state").apply { mkdirs() }
        for (agent in agents) {
            val stateFile = File(state, "${agent.slot.id}.json")
            if (!stateFile.exists()) {
                val stateJson = JSONObject()
                    .put("slot", agent.slot.id)
                    .put("name", agent.name)
                    .put("role", agent.role.type.name)
                    .put("provider", agent.providerId)
                    .put("model", agent.modelId)
                    .put("status", "READY")
                stateFile.writeText(stateJson.toString(2))
            }
        }
    }

    fun syncManagementState(tasks: List<TeamTask>, parts: List<ProjectPart>, currentPart: Int) {
        val agentRoot = File(workspaceDir, ".agent")
        val mgmt = File(agentRoot, "management").apply { mkdirs() }

        // Update PROJECT_STATUS.md
        val statusText = buildString {
            appendLine("# ORQEN Team Project Status")
            appendLine("Current Part: Part ${currentPart.toString().padStart(2, '0')}")
            appendLine("Active Tasks: ${tasks.count { it.status == com.jarves.mh.multiagent.tasks.TaskStatus.IN_PROGRESS }}")
            appendLine("Completed Tasks: ${tasks.count { it.status == com.jarves.mh.multiagent.tasks.TaskStatus.COMPLETED }}")
            appendLine()
            appendLine("## Sequential Parts Overview")
            for (p in parts) {
                val check = if (p.isApproved) "[x]" else "[ ]"
                appendLine("- $check Part ${p.partNumber.toString().padStart(2, '0')}: ${p.title} (${p.goal})")
            }
        }
        File(mgmt, "PROJECT_STATUS.md").writeText(statusText)

        // Update TASK_QUEUE.md
        val queueText = buildString {
            appendLine("# Task Queue")
            for (t in tasks) {
                val assigned = t.assignedSlot?.defaultName ?: "Unassigned"
                appendLine("- [${t.status}] **${t.title}** (Assigned: $assigned) - Part ${t.partNumber}")
            }
        }
        File(mgmt, "TASK_QUEUE.md").writeText(queueText)
    }

    fun formatCommitMessage(partNumber: Int, taskTitle: String, type: String = "feat"): String {
        val partStr = "part-${partNumber.toString().padStart(2, '0')}"
        return "$type($partStr): ${taskTitle.trim().lowercase()}"
    }

    private fun touchIfNotExists(file: File, defaultContent: String) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(defaultContent)
        }
    }
}
