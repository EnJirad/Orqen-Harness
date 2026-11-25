package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.events.TeamCollaborationEvent
import com.jarves.mh.multiagent.events.TeamEventType
import com.jarves.mh.multiagent.locks.FileLock
import com.jarves.mh.multiagent.model.*
import com.jarves.mh.ui.TeamScreenMode
import org.junit.Assert.*
import org.junit.Test

class Part03FrontendStateTest {

    @Test
    fun `team screen mode supports all 8 dashboard navigation states`() {
        val modes = TeamScreenMode.entries
        assertEquals(8, modes.size)
        assertTrue(modes.contains(TeamScreenMode.PROJECT_LIST))
        assertTrue(modes.contains(TeamScreenMode.DASHBOARD))
        assertTrue(modes.contains(TeamScreenMode.AGENT_DETAIL))
        assertTrue(modes.contains(TeamScreenMode.QUOTA_DASHBOARD))
        assertTrue(modes.contains(TeamScreenMode.ACTIVITY_FEED))
        assertTrue(modes.contains(TeamScreenMode.GROUP_CHAT))
        assertTrue(modes.contains(TeamScreenMode.PRIVATE_CHAT))
        assertTrue(modes.contains(TeamScreenMode.PROVIDER_SETTINGS))
    }

    @Test
    fun `agent config supports custom prompt temperature and enable toggle`() {
        val baseRole = AgentRole(
            type = AgentRoleType.TEAM_LEADER,
            systemInstructions = "Coordinate team tasks",
            responsibilities = listOf("Architecture", "Task Breakdown"),
        )

        val config = AgentConfig(
            slot = AgentSlot.AGENT_1,
            name = "Lead Architect",
            role = baseRole,
            modelId = "claude-sonnet-4-6",
            isEnabled = true,
            temperature = 0.5f,
            maxTokens = 4096,
            customPrompt = "Focus on clean architecture and high cohesion",
        )

        assertEquals("Lead Architect", config.name)
        assertEquals("claude-sonnet-4-6", config.modelId)
        assertTrue(config.isEnabled)
        assertEquals(0.5f, config.temperature, 0.001f)
        assertEquals(4096, config.maxTokens)
        assertEquals("Focus on clean architecture and high cohesion", config.customPrompt)

        // Toggle disabled
        val disabled = config.copy(isEnabled = false)
        assertFalse(disabled.isEnabled)
    }

    @Test
    fun `agent state correctly associates with lifecycle state and held locks`() {
        val role = AgentRole(
            type = AgentRoleType.SOFTWARE_ENGINEER,
            systemInstructions = "Write code",
            responsibilities = listOf("Implementation"),
        )
        val config = AgentConfig(slot = AgentSlot.AGENT_2, role = role)

        val state = AgentState(
            config = config,
            lifecycleState = AgentLifecycleState.EXECUTING,
            isWorking = true,
        )

        assertEquals(AgentLifecycleState.EXECUTING, state.lifecycleState)
        assertTrue(state.isWorking)

        val allLocks = listOf(
            FileLock("app/src/main/A.kt", AgentSlot.AGENT_2, "task-1"),
            FileLock("app/src/main/B.kt", AgentSlot.AGENT_3, "task-2"),
            FileLock("app/src/main/C.kt", AgentSlot.AGENT_2, "task-1"),
        )

        val agent2Locks = allLocks.filter { it.agentSlot == state.config.slot }
        assertEquals(2, agent2Locks.size)
        assertEquals("app/src/main/A.kt", agent2Locks[0].filePath)
        assertEquals("app/src/main/C.kt", agent2Locks[1].filePath)
    }

    @Test
    fun `budget progress calculation remains bounded between 0 and 1`() {
        val budgetLimitUsd = 10.0

        val zeroUsage = 0.0
        val zeroProgress = (zeroUsage / budgetLimitUsd).toFloat().coerceIn(0f, 1f)
        assertEquals(0f, zeroProgress, 0.001f)

        val halfUsage = 5.0
        val halfProgress = (halfUsage / budgetLimitUsd).toFloat().coerceIn(0f, 1f)
        assertEquals(0.5f, halfProgress, 0.001f)

        val overUsage = 15.0
        val overProgress = (overUsage / budgetLimitUsd).toFloat().coerceIn(0f, 1f)
        assertEquals(1.0f, overProgress, 0.001f)
    }

    @Test
    fun `activity feed event filtering separates critical and lock events`() {
        val events = listOf(
            PrioritizedCollaborationEvent(
                event = TeamCollaborationEvent(
                    type = TeamEventType.BUG_FOUND,
                    senderSlot = AgentSlot.AGENT_4,
                    message = "Regression test failure in AuthModule",
                ),
                priority = EventPriority.CRITICAL,
            ),
            PrioritizedCollaborationEvent(
                event = TeamCollaborationEvent(
                    type = TeamEventType.FILE_LOCKED,
                    senderSlot = AgentSlot.AGENT_2,
                    filePath = "app/src/main/Auth.kt",
                    message = "Acquired lock on Auth.kt",
                ),
                priority = EventPriority.NORMAL,
            ),
            PrioritizedCollaborationEvent(
                event = TeamCollaborationEvent(
                    type = TeamEventType.COMMIT_CREATED,
                    senderSlot = AgentSlot.AGENT_2,
                    commitSha = "abc1234",
                    message = "feat(auth): fix token refresh",
                ),
                priority = EventPriority.HIGH,
            ),
        )

        val critical = events.filter { it.priority == EventPriority.CRITICAL }
        assertEquals(1, critical.size)
        assertEquals(AgentSlot.AGENT_4, critical[0].event.senderSlot)

        val locks = events.filter { it.event.type == TeamEventType.FILE_LOCKED || it.event.type == TeamEventType.FILE_UNLOCKED }
        assertEquals(1, locks.size)
        assertEquals("app/src/main/Auth.kt", locks[0].event.filePath)
    }
}
