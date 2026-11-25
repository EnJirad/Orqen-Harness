package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.model.AgentLifecycleState
import com.jarves.mh.multiagent.model.AgentLifecycleStateMachine
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.LockAcquisitionResult
import com.jarves.mh.multiagent.supervisor.ContextTurn
import com.jarves.mh.multiagent.supervisor.ContextWindowPruner
import org.junit.Assert.*
import org.junit.Test

class Part07HardeningTest {

    @Test
    fun `state machine caps transition history at 100 entries for 24h continuous runs`() {
        val machine = AgentLifecycleStateMachine(AgentSlot.AGENT_1)
        assertTrue(machine.transitionTo(AgentLifecycleState.IDLE, "init"))

        repeat(120) { idx ->
            assertTrue(machine.transitionTo(AgentLifecycleState.PLANNING, "plan-$idx"))
            assertTrue(machine.transitionTo(AgentLifecycleState.IDLE, "idle-$idx"))
        }

        assertEquals(100, machine.history.size)
        assertEquals(AgentLifecycleState.IDLE, machine.currentState.value)
    }

    @Test
    fun `file lock manager cleanly expires stale locks and frees memory`() {
        val lockManager = FileLockManager()
        val res = lockManager.tryAcquire(
            filePath = "app/src/Stale.kt",
            slot = AgentSlot.AGENT_1,
            taskId = "stale-task",
            reason = "Short TTL lock",
            ttlMs = 1L,
        )
        assertTrue(res is LockAcquisitionResult.Success)
        Thread.sleep(15L)

        lockManager.cleanExpiredLocks()
        assertTrue(lockManager.getActiveLocks().isEmpty())
        assertFalse(lockManager.isLockedByOther("app/src/Stale.kt", AgentSlot.AGENT_2))
    }

    @Test
    fun `context window pruner stays bounded under 100-turn autonomous stress`() {
        val pruner = ContextWindowPruner(maxContextTokens = 200, preserveRecentTurns = 4)
        val turns = mutableListOf(ContextTurn("system", "System", "Core ORQEN system prompt"))
        repeat(100) { i ->
            turns.add(ContextTurn("assistant", "Agent 02", "Autonomous step $i completed with diff summary ".repeat(4)))
        }

        val result = pruner.prune(turns, budgetTokens = 200)
        assertTrue(result.wasCompressed)
        assertEquals(96, result.prunedCount)
        assertEquals(6, result.turns.size) // 1 system + 1 summary + 4 recent
    }
}
