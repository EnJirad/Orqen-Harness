package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.model.*
import org.junit.Assert.*
import org.junit.Test

class Part02ArchitectureContractsTest {

    @Test
    fun `agent state machine strictly validates allowed transitions and records history`() {
        val sm = AgentLifecycleStateMachine(AgentSlot.AGENT_1)
        assertEquals(AgentLifecycleState.UNINITIALIZED, sm.currentState.value)

        // 1. Valid: UNINITIALIZED -> IDLE
        assertTrue(sm.transitionTo(AgentLifecycleState.IDLE, "System boot"))
        assertEquals(AgentLifecycleState.IDLE, sm.currentState.value)

        // 2. Invalid: IDLE -> COMMITTING (illegal jump without executing or analyzing)
        assertFalse(sm.canTransitionTo(AgentLifecycleState.COMMITTING))
        assertFalse(sm.transitionTo(AgentLifecycleState.COMMITTING, "Illegal jump"))
        assertEquals(AgentLifecycleState.IDLE, sm.currentState.value) // State remains IDLE

        // 3. Valid progression: IDLE -> ANALYZING -> PLANNING -> EXECUTING -> TESTING -> WAITING_FOR_REVIEW -> COMMITTING -> IDLE
        assertTrue(sm.transitionTo(AgentLifecycleState.ANALYZING, "Analyzing requirement"))
        assertTrue(sm.transitionTo(AgentLifecycleState.PLANNING, "Formulating task plan"))
        assertTrue(sm.transitionTo(AgentLifecycleState.EXECUTING, "Writing code"))
        assertTrue(sm.transitionTo(AgentLifecycleState.TESTING, "Running unit tests"))
        assertTrue(sm.transitionTo(AgentLifecycleState.WAITING_FOR_REVIEW, "Submitted for peer QA"))
        assertTrue(sm.transitionTo(AgentLifecycleState.COMMITTING, "Review approved, committing"))
        assertTrue(sm.transitionTo(AgentLifecycleState.IDLE, "Task cycle complete"))

        // Verify history tracking
        assertEquals(8, sm.history.size)
        assertEquals(AgentLifecycleState.UNINITIALIZED, sm.history[0].fromState)
        assertEquals(AgentLifecycleState.IDLE, sm.history[0].toState)
        assertEquals(AgentLifecycleState.COMMITTING, sm.history.last().fromState)
        assertEquals(AgentLifecycleState.IDLE, sm.history.last().toState)
    }

    @Test
    fun `state machine activity mapping matches UI visual icons`() {
        assertEquals(ActivityType.WAITING, AgentLifecycleState.IDLE.toActivityType())
        assertEquals(ActivityType.ANALYZING, AgentLifecycleState.ANALYZING.toActivityType())
        assertEquals(ActivityType.PLANNING, AgentLifecycleState.PLANNING.toActivityType())
        assertEquals(ActivityType.WRITING_CODE, AgentLifecycleState.EXECUTING.toActivityType())
        assertEquals(ActivityType.TESTING, AgentLifecycleState.TESTING.toActivityType())
        assertEquals(ActivityType.REVIEWING, AgentLifecycleState.WAITING_FOR_REVIEW.toActivityType())
        assertEquals(ActivityType.COMMITTING, AgentLifecycleState.COMMITTING.toActivityType())
        assertEquals(ActivityType.PAUSED, AgentLifecycleState.BLOCKED.toActivityType())
        assertEquals(ActivityType.ERROR, AgentLifecycleState.ERROR.toActivityType())
    }

    @Test
    fun `file lock coordinator handles tryAcquire conflict and renewLock contracts`() {
        val coordinator: IFileLockCoordinator = FileLockManager()
        val filePath = "app/src/main/java/DomainModel.kt"

        // Agent 02 acquires lock
        val res1 = coordinator.tryAcquire(filePath, AgentSlot.AGENT_2, "task-101", "Implementing domain logic")
        assertTrue(res1 is LockAcquisitionResult.Success)
        val lock = (res1 as LockAcquisitionResult.Success).lock
        assertEquals(AgentSlot.AGENT_2, lock.agentSlot)
        assertEquals(filePath, lock.filePath)

        // Agent 03 attempts acquisition -> Conflict result returned with remaining TTL
        val res2 = coordinator.tryAcquire(filePath, AgentSlot.AGENT_3, "task-102", "Concurrent edit attempt")
        assertTrue(res2 is LockAcquisitionResult.Conflict)
        val conflict = res2 as LockAcquisitionResult.Conflict
        assertEquals(AgentSlot.AGENT_2, conflict.lockedBy)
        assertTrue(conflict.remainingTtlMs > 0)
        assertEquals("Implementing domain logic", conflict.reason)

        // Agent 02 renews lock
        val renewed = coordinator.renewLock(filePath, AgentSlot.AGENT_2, extendByMs = 20 * 60 * 1000L)
        assertTrue(renewed)

        // Agent 03 cannot renew Agent 02's lock
        val invalidRenew = coordinator.renewLock(filePath, AgentSlot.AGENT_3, extendByMs = 5000L)
        assertFalse(invalidRenew)

        // Release all locks for Agent 02
        val releasedCount = coordinator.releaseAllForAgent(AgentSlot.AGENT_2)
        assertEquals(1, releasedCount)
        assertFalse(coordinator.isLockedByOther(filePath, AgentSlot.AGENT_3))

        // Agent 03 can now acquire
        val res3 = coordinator.tryAcquire(filePath, AgentSlot.AGENT_3, "task-102", "Acquiring released lock")
        assertTrue(res3 is LockAcquisitionResult.Success)
    }

    @Test
    fun `standard quota evaluator correctly calculates USD and THB costs`() {
        val evaluator: IQuotaEvaluator = StandardQuotaEvaluator()

        // 100k input tokens + 50k output tokens on standard flash model
        val usage = evaluator.estimateCost(100_000L, 50_000L, "gemini-3.8-flash")
        assertEquals(150_000L, usage.estimatedTokens)
        assertTrue(usage.estimatedCostUsd > 0.0)
        assertTrue(usage.estimatedCostThb > 0.0)
        assertEquals(usage.estimatedCostUsd * 35.0, usage.estimatedCostThb, 0.001)

        // Claude model has higher rate card
        val claudeUsage = evaluator.estimateCost(100_000L, 50_000L, "claude-sonnet-4-6")
        assertTrue(claudeUsage.estimatedCostUsd > usage.estimatedCostUsd)
    }

    @Test
    fun `prioritized events preserve priority levels`() {
        val criticalEvent = PrioritizedCollaborationEvent(
            event = com.jarves.mh.multiagent.events.TeamCollaborationEvent(
                type = com.jarves.mh.multiagent.events.TeamEventType.BUG_FOUND,
                senderSlot = AgentSlot.AGENT_4,
                message = "Critical concurrency bug detected",
            ),
            priority = EventPriority.CRITICAL,
        )
        assertEquals(EventPriority.CRITICAL, criticalEvent.priority)
        assertEquals(AgentSlot.AGENT_4, criticalEvent.event.senderSlot)
    }
}
