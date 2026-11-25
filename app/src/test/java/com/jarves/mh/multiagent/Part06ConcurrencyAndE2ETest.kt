package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.git.HubEnvelope
import com.jarves.mh.multiagent.git.HubExchangeService
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.model.*
import com.jarves.mh.multiagent.supervisor.AutonomousExecutionEngine
import com.jarves.mh.multiagent.supervisor.ContextWindowPruner
import com.jarves.mh.multiagent.supervisor.ContinuousSupervisor
import com.jarves.mh.multiagent.tasks.TaskStatus
import com.jarves.mh.multiagent.tasks.TeamTask
import com.jarves.mh.provider.core.ApiProtocol
import com.jarves.mh.provider.core.ProviderConfig
import com.jarves.mh.provider.core.ProviderType
import com.jarves.mh.provider.manager.ProviderOrchestrator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Part06ConcurrencyAndE2ETest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `four concurrent agents competing for same file yield strictly one lock owner`() {
        val lockManager = FileLockManager()
        val executor = Executors.newFixedThreadPool(4)
        val startGate = CountDownLatch(1)
        val pathVariants = listOf(
            "app/src/main/java/SharedFeature.kt",
            "/app/src/main/java/SharedFeature.kt",
            "app\\src\\main\\java\\SharedFeature.kt",
            "  app/src/main/java/SharedFeature.kt  ",
        )

        val futures = AgentSlot.entries.mapIndexed { idx, slot ->
            executor.submit(Callable {
                startGate.await(5, TimeUnit.SECONDS)
                lockManager.tryAcquire(
                    filePath = pathVariants[idx],
                    slot = slot,
                    taskId = "stress-task-$idx",
                    reason = "Concurrent edit by ${slot.defaultName}",
                )
            })
        }

        startGate.countDown()
        val results = futures.map { it.get(10, TimeUnit.SECONDS) }
        executor.shutdown()

        val successes = results.filterIsInstance<LockAcquisitionResult.Success>()
        val conflicts = results.filterIsInstance<LockAcquisitionResult.Conflict>()

        assertEquals("Exactly 1 agent must win the lock", 1, successes.size)
        assertEquals("Other 3 agents must receive Conflict", 3, conflicts.size)

        val winner = successes.first().lock.agentSlot
        conflicts.forEach { conflict ->
            assertEquals(winner, conflict.lockedBy)
            assertTrue(conflict.remainingTtlMs > 0L)
        }

        // Release winner's lock -> another agent can acquire cleanly
        assertEquals(1, lockManager.releaseAllForAgent(winner))
        val nextSlot = AgentSlot.entries.first { it != winner }
        val afterRelease = lockManager.tryAcquire(pathVariants[0], nextSlot, "next-task", "After release")
        assertTrue(afterRelease is LockAcquisitionResult.Success)
    }

    @Test
    fun `end to end 4-agent shared-ID multi-model workflow persists hub state and respects budget`() {
        val lockManager = FileLockManager()
        val engine = AutonomousExecutionEngine(lockManager, StandardQuotaEvaluator(), ContextWindowPruner())
        val orchestrator = ProviderOrchestrator()
        val hub = HubExchangeService(tempFolder.root)
        val supervisor = ContinuousSupervisor()

        val sharedProvider = ProviderConfig(
            id = "shared-id-1",
            name = "ORQEN Unified ID #1",
            type = ProviderType.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            protocol = ApiProtocol.ANTHROPIC_MESSAGES,
            selectedModelId = "claude-sonnet-4-6",
            availableModels = listOf("claude-sonnet-4-6", "claude-3-5-haiku-20241022", "gemini-3.8-flash-high", "gpt-4o"),
        )

        val role = AgentRole(AgentRoleType.SOFTWARE_ENGINEER, "Engineer", "Build ORQEN", listOf("Implement"))
        val agents = listOf(
            AgentState(AgentConfig(AgentSlot.AGENT_1, "Nova", role, "shared-id-1", modelId = "claude-sonnet-4-6")),
            AgentState(AgentConfig(AgentSlot.AGENT_2, "Bolt", role, "shared-id-1", modelId = "claude-3-5-haiku-20241022")),
            AgentState(AgentConfig(AgentSlot.AGENT_3, "Cipher", role, "shared-id-1", modelId = "gemini-3.8-flash-high")),
            AgentState(AgentConfig(AgentSlot.AGENT_4, "Vex", role, "shared-id-1", modelId = "gpt-4o")),
        )

        // 1. Verify all 4 routes share ID #1 with 4 distinct models
        val routes = orchestrator.resolveAllRoutes(agents, listOf(sharedProvider))
        assertEquals(4, routes.size)
        assertEquals(4, routes.values.map { it.modelId }.toSet().size)
        routes.values.forEach { assertTrue(it.isSharedProviderId) }

        // 2. Execute autonomous step for Bolt on FileA.kt
        val task = TeamTask(partNumber = 6, title = "Verify E2E", description = "Run full pipeline", assignedSlot = AgentSlot.AGENT_2, status = TaskStatus.READY)
        val step = engine.prepareExecutionStep(
            agents = agents,
            targetAgent = agents[1],
            task = task,
            targetFilePath = "app/src/FileA.kt",
            currentSpendUsd = 0.5,
            budgetLimitUsd = 10.0,
            autoPauseEnabled = true,
        )
        assertFalse(step.budgetExceeded)
        assertEquals(AgentLifecycleState.EXECUTING, step.newLifecycleState)
        assertTrue(step.rosterInjectedPrompt.contains("Nova"))
        assertTrue(step.rosterInjectedPrompt.contains("Bolt"))
        assertTrue(step.rosterInjectedPrompt.contains("Cipher"))
        assertTrue(step.rosterInjectedPrompt.contains("Vex"))

        // 3. Persist snapshots & exchange hub message
        agents.forEach { hub.saveAgentSnapshot(it) }
        assertEquals("Vex", hub.readAgentSnapshot(AgentSlot.AGENT_4)?.getString("name"))

        hub.dispatchMessage(
            HubEnvelope(
                id = "e2e-001",
                fromSlot = AgentSlot.AGENT_2.id,
                fromName = "Bolt",
                toSlot = AgentSlot.AGENT_4.id,
                subject = "Ready for QA",
                body = "FileA.kt implemented, please review.",
            )
        )
        val vexInbox = hub.readInbox(AgentSlot.AGENT_4)
        assertEquals(1, vexInbox.size)
        assertEquals("Bolt", vexInbox[0].fromName)

        // 4. Supervisor checkpoint verification
        supervisor.startContinuous()
        val chk = supervisor.recordCheckpoint("E2E Milestone", task.title)
        assertEquals("E2E Milestone", chk.reason)
        assertEquals(1, supervisor.checkpoints.value.size)
    }
}
