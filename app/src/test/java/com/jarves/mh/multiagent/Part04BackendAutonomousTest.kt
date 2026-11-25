package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.git.HubEnvelope
import com.jarves.mh.multiagent.git.HubExchangeService
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.model.*
import com.jarves.mh.multiagent.supervisor.*
import com.jarves.mh.multiagent.tasks.TaskStatus
import com.jarves.mh.multiagent.tasks.TeamTask
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Part04BackendAutonomousTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `continuous supervisor parses Retry-After header and proactive 45m checkpoint`() {
        val supervisor = ContinuousSupervisor()
        assertEquals(12_000L, supervisor.parseRetryAfterMs("HTTP 429 Rate Limit. Retry-After: 12"))
        assertEquals(30_000L, supervisor.parseRetryAfterMs("retry-after=30"))
        assertNull(supervisor.parseRetryAfterMs("Generic timeout error"))

        supervisor.startContinuous()
        assertFalse(supervisor.shouldProactivelyCheckpoint(System.currentTimeMillis() + 10 * 60 * 1000L))
        assertTrue(supervisor.shouldProactivelyCheckpoint(System.currentTimeMillis() + 46 * 60 * 1000L))

        val chk = supervisor.recordCheckpoint("Proactive 45-min checkpoint", "Task 1")
        assertEquals(1, supervisor.checkpoints.value.size)
        assertEquals(chk.id, supervisor.checkpoints.value.first().id)
    }

    @Test
    fun `continuous supervisor pauses when autoResume is disabled`() = runBlocking {
        val supervisor = ContinuousSupervisor()
        supervisor.updateConfig(ContinuousExecutionConfig(autoResume = false))
        supervisor.startContinuous()

        var continued = false
        supervisor.handleSessionCompletion(
            completedTask = null,
            hasMoreTasks = true,
            exitCode = 0,
            errorMessage = null,
            onContinueNext = { continued = true },
        )
        assertFalse(continued)
        assertFalse(supervisor.isContinuousRunning.value)
        assertTrue(supervisor.lastStopReason.value?.title?.contains("Manual Continue") == true)
    }

    @Test
    fun `context window pruner builds team roster with custom names and compresses middle turns`() {
        val pruner = ContextWindowPruner(maxContextTokens = 80, preserveRecentTurns = 2)
        val role = AgentRole(AgentRoleType.TEAM_LEADER, "Lead", "Lead the team", listOf("Plan"))
        val agents = listOf(
            AgentState(AgentConfig(slot = AgentSlot.AGENT_1, name = "Nova (Lead)", role = role)),
            AgentState(AgentConfig(slot = AgentSlot.AGENT_2, name = "Bolt (Dev)", role = role)),
        )

        val roster = pruner.buildTeamRosterPrompt(agents)
        assertTrue(roster.contains("Nova (Lead)"))
        assertTrue(roster.contains("Bolt (Dev)"))

        val turns = mutableListOf(
            ContextTurn("system", "System", "Base instructions for ORQEN"),
        )
        repeat(10) { idx ->
            turns.add(ContextTurn("assistant", "Bolt (Dev)", "Turn $idx: ".repeat(15)))
        }

        val pruned = pruner.prune(turns, budgetTokens = 80)
        assertTrue(pruned.wasCompressed)
        assertEquals(8, pruned.prunedCount)
        assertTrue(pruned.turns.any { it.speakerName == "ContextPruner" })
        assertEquals(4, pruned.turns.size) // 1 system + 1 summary + 2 recent
    }

    @Test
    fun `autonomous execution engine enforces budget guard and file lock conflict detection`() {
        val lockManager = FileLockManager()
        val engine = AutonomousExecutionEngine(lockManager)
        val role = AgentRole(AgentRoleType.SOFTWARE_ENGINEER, "Dev", "Write Kotlin", listOf("Code"))

        val agent1 = AgentState(AgentConfig(slot = AgentSlot.AGENT_1, name = "Alice", role = role))
        val agent2 = AgentState(AgentConfig(slot = AgentSlot.AGENT_2, name = "Bob", role = role))
        val task = TeamTask(partNumber = 4, title = "Build Engine", description = "Implement loop", assignedSlot = AgentSlot.AGENT_2, status = TaskStatus.READY)

        // Budget exceeded -> paused
        val pausedStep = engine.prepareExecutionStep(
            agents = listOf(agent1, agent2),
            targetAgent = agent2,
            task = task,
            targetFilePath = "app/Engine.kt",
            currentSpendUsd = 12.0,
            budgetLimitUsd = 10.0,
            autoPauseEnabled = true,
        )
        assertTrue(pausedStep.budgetExceeded)

        // Normal step -> acquires lock & injects custom names into prompt
        val okStep = engine.prepareExecutionStep(
            agents = listOf(agent1, agent2),
            targetAgent = agent2,
            task = task,
            targetFilePath = "app/Engine.kt",
            currentSpendUsd = 1.0,
            budgetLimitUsd = 10.0,
            autoPauseEnabled = true,
        )
        assertFalse(okStep.budgetExceeded)
        assertEquals(AgentLifecycleState.EXECUTING, okStep.newLifecycleState)
        assertTrue(okStep.rosterInjectedPrompt.contains("Alice"))
        assertTrue(okStep.rosterInjectedPrompt.contains("Bob"))

        // Agent 1 tries same file -> BLOCKED due to lock conflict
        val blockedStep = engine.prepareExecutionStep(
            agents = listOf(agent1, agent2),
            targetAgent = agent1,
            task = task,
            targetFilePath = "app/Engine.kt",
            currentSpendUsd = 1.0,
            budgetLimitUsd = 10.0,
            autoPauseEnabled = true,
        )
        assertEquals(AgentLifecycleState.BLOCKED, blockedStep.newLifecycleState)
    }

    @Test
    fun `hub exchange service persists custom agent snapshot and exchanges inbox messages`() {
        val service = HubExchangeService(tempFolder.root)
        val role = AgentRole(AgentRoleType.TEAM_LEADER, "Lead", "Coordinate", listOf("Lead"))
        val agent = AgentState(
            config = AgentConfig(slot = AgentSlot.AGENT_1, name = "Captain ORQEN", role = role),
            lifecycleState = AgentLifecycleState.PLANNING,
        )

        service.saveAgentSnapshot(agent)
        val snap = service.readAgentSnapshot(AgentSlot.AGENT_1)
        assertNotNull(snap)
        assertEquals("Captain ORQEN", snap?.getString("name"))
        assertEquals("PLANNING", snap?.getString("lifecycleState"))

        val env = HubEnvelope(
            id = "msg-001",
            fromSlot = AgentSlot.AGENT_1.id,
            fromName = "Captain ORQEN",
            toSlot = AgentSlot.AGENT_2.id,
            subject = "Part 04 Handoff",
            body = "Please review AutonomousExecutionEngine",
        )
        service.dispatchMessage(env)
        val inbox = service.readInbox(AgentSlot.AGENT_2)
        assertEquals(1, inbox.size)
        assertEquals("Captain ORQEN", inbox[0].fromName)
        assertEquals("Part 04 Handoff", inbox[0].subject)
    }
}
