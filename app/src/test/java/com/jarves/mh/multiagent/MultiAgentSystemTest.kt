package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.model.*
import com.jarves.mh.multiagent.supervisor.ContinuousSupervisor
import com.jarves.mh.multiagent.tasks.TaskManager
import com.jarves.mh.multiagent.tasks.TaskStatus
import com.jarves.mh.multiagent.tasks.TeamTask
import com.jarves.mh.provider.core.*
import org.junit.Assert.*
import org.junit.Test

class MultiAgentSystemTest {

    @Test
    fun `maximum 4 agents limit is strictly enforced`() {
        assertEquals(4, AgentSlot.MAX_AGENTS)
        assertEquals(4, AgentSlot.entries.size)

        val validAgents = (1..4).map { i ->
            val slot = AgentSlot.fromSlotNumber(i)!!
            AgentConfig(
                slot = slot,
                name = "Agent 0$i",
                role = AgentRole(
                    type = AgentRoleType.SOFTWARE_ENGINEER,
                    title = "Engineer $i",
                    systemInstructions = "Code",
                    responsibilities = listOf("Write code"),
                ),
            )
        }
        val group = TeamGroup(
            id = "team-1",
            name = "ORQEN Core Team",
            workspacePath = "/workspace/demo",
            agents = validAgents,
        )
        assertEquals(4, group.agents.size)

        // Attempting to add 5th agent throws IllegalArgumentException
        val invalidAgents = validAgents + AgentConfig(
            slot = AgentSlot.AGENT_4,
            name = "Agent 05 (Invalid)",
            role = AgentRole(
                type = AgentRoleType.CUSTOM,
                title = "Invalid 5th Agent",
                systemInstructions = "Fail",
                responsibilities = emptyList(),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            TeamGroup(
                id = "team-2",
                name = "Overloaded Team",
                workspacePath = "/workspace/demo",
                agents = invalidAgents,
            )
        }
    }

    @Test
    fun `agent slots maintain independent configuration and roles`() {
        val agent1 = AgentConfig(
            slot = AgentSlot.AGENT_1,
            name = "Agent 01 (Lead)",
            role = AgentRole(
                type = AgentRoleType.TEAM_LEADER,
                title = "Team Leader",
                systemInstructions = "Lead and review",
                responsibilities = listOf("Analyze", "Review"),
            ),
            providerId = "antigravity",
            modelId = "gemini-3.8-flash-high",
        )

        val agent2 = AgentConfig(
            slot = AgentSlot.AGENT_2,
            name = "Agent 02 (Dev)",
            role = AgentRole(
                type = AgentRoleType.SOFTWARE_ENGINEER,
                title = "Software Engineer",
                systemInstructions = "Write code",
                responsibilities = listOf("Develop"),
            ),
            providerId = "anthropic",
            modelId = "claude-sonnet-4-6",
        )

        assertNotEquals(agent1.slot, agent2.slot)
        assertNotEquals(agent1.providerId, agent2.providerId)
        assertNotEquals(agent1.modelId, agent2.modelId)
        assertNotEquals(agent1.role.type, agent2.role.type)
    }

    @Test
    fun `file lock prevents concurrent edits and detects conflicts`() {
        val lockManager = FileLockManager()
        val filePath = "app/src/main/java/Feature.kt"

        // Agent 02 acquires lock
        val acquired = lockManager.acquireLock(filePath, AgentSlot.AGENT_2, "task-1", "Coding feature")
        assertTrue(acquired)
        assertEquals(AgentSlot.AGENT_2, lockManager.getLockOwner(filePath))

        // Agent 03 attempts to lock the same file -> rejected!
        val conflictAttempt = lockManager.acquireLock(filePath, AgentSlot.AGENT_3, "task-2", "Conflicting edit")
        assertFalse(conflictAttempt)
        assertTrue(lockManager.isLockedByOther(filePath, AgentSlot.AGENT_3))
        assertFalse(lockManager.isLockedByOther(filePath, AgentSlot.AGENT_2))

        // Agent 02 releases lock -> Agent 03 can now acquire
        val released = lockManager.releaseLock(filePath, AgentSlot.AGENT_2)
        assertTrue(released)
        assertNull(lockManager.getLockOwner(filePath))

        val acquiredAfterRelease = lockManager.acquireLock(filePath, AgentSlot.AGENT_3, "task-2", "Now available")
        assertTrue(acquiredAfterRelease)
        assertEquals(AgentSlot.AGENT_3, lockManager.getLockOwner(filePath))
    }

    @Test
    fun `quota is clearly classified into official vs estimated vs local`() {
        val official = OfficialQuota(
            providerId = "antigravity",
            remainingPercent = 0.75f,
            quotaLimit = "100%",
            resetTime = "Daily",
            source = "Provider API",
            isAvailable = true,
        )
        val estimated = EstimatedUsage(
            estimatedTokens = 125_000L,
            estimatedCostUsd = 0.375,
            estimatedCostThb = 13.125,
            requestCount = 14,
            modelId = "gemini-3.8-flash",
        )
        val local = LocalUsage(
            totalRequests = 14,
            completedTasks = 3,
            failedTasks = 0,
            activeAgents = 2,
        )

        assertTrue(official.isAvailable)
        assertEquals(QuotaHealth.NORMAL, calculateQuotaHealth(official))
        assertEquals(125_000L, estimated.estimatedTokens)
        assertEquals(14, local.totalRequests)

        // Unavailable quota displays explicit reason without guessing
        val unavailableQuota = OfficialQuota(
            providerId = "custom",
            isAvailable = false,
            unavailableReason = "Provider has no public quota API",
        )
        assertEquals(QuotaHealth.UNAVAILABLE, calculateQuotaHealth(unavailableQuota))
        assertNull(unavailableQuota.remainingPercent)
    }

    @Test
    fun `continuous supervisor handles stop and recovery conditions`() {
        val supervisor = ContinuousSupervisor(maxRetries = 3)
        assertFalse(supervisor.isContinuousRunning.value)

        supervisor.startContinuous()
        assertTrue(supervisor.isContinuousRunning.value)

        supervisor.stopContinuous("User stopped", "Manual stop pressed")
        assertFalse(supervisor.isContinuousRunning.value)
        assertEquals("User stopped", supervisor.lastStopReason.value?.title)
    }
}
