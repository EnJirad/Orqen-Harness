package com.jarves.mh.multiagent.engine

import com.jarves.mh.multiagent.persistence.*
import java.util.concurrent.ConcurrentHashMap

data class DependencyResolutionResult(
    val eligibleSteps: List<TaskStepEntity>,
    val waitingSteps: List<TaskStepEntity>,
    val missingDependencies: Map<String, List<String>>,
    val hasCycle: Boolean,
)

/**
 * Deterministic DAG dependency resolver for task steps.
 */
object DependencyResolver {
    fun resolve(steps: List<TaskStepEntity>): DependencyResolutionResult {
        val byId = steps.associateBy { it.stepId }
        val completedIds = steps
            .filter { it.status == StepStatus.COMPLETED || it.status == StepStatus.SKIPPED }
            .map { it.stepId }
            .toSet()

        val missing = LinkedHashMap<String, List<String>>()
        steps.forEach { step ->
            val bad = step.dependencies.filter { dep -> dep !in byId }
            if (bad.isNotEmpty()) missing[step.stepId] = bad
        }

        val cycle = hasCycle(steps.associate { it.stepId to it.dependencies })
        if (cycle || missing.isNotEmpty()) {
            return DependencyResolutionResult(
                eligibleSteps = emptyList(),
                waitingSteps = steps.filter { it.status == StepStatus.PENDING || it.status == StepStatus.READY },
                missingDependencies = missing,
                hasCycle = cycle,
            )
        }

        val candidates = steps
            .filter { it.status == StepStatus.PENDING || it.status == StepStatus.READY }
            .sortedWith(compareBy<TaskStepEntity> { it.sequenceNumber }.thenBy { it.createdAt })

        val eligible = mutableListOf<TaskStepEntity>()
        val waiting = mutableListOf<TaskStepEntity>()
        for (step in candidates) {
            if (step.dependencies.all { it in completedIds }) {
                eligible.add(step)
            } else {
                waiting.add(step)
            }
        }
        return DependencyResolutionResult(
            eligibleSteps = eligible,
            waitingSteps = waiting,
            missingDependencies = emptyMap(),
            hasCycle = false,
        )
    }

    fun hasCycle(adjacency: Map<String, List<String>>): Boolean {
        val color = HashMap<String, Int>() // 0=unvisited, 1=visiting, 2=visited
        fun dfs(node: String): Boolean {
            color[node] = 1
            for (dep in adjacency[node].orEmpty()) {
                if (!adjacency.containsKey(dep)) continue
                when (color[dep] ?: 0) {
                    1 -> return true
                    0 -> if (dfs(dep)) return true
                }
            }
            color[node] = 2
            return false
        }
        return adjacency.keys.any { (color[it] ?: 0) == 0 && dfs(it) }
    }
}

data class StepClaimLease(
    val stepId: String,
    val taskId: String,
    val workerId: String,
    val claimedAtMs: Long,
    val expiresAtMs: Long,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

/**
 * Authoritative step scheduler with atomic lease-based claiming.
 *
 * Guarantees:
 * - Queries durable [OrqenTaskStore] before claiming work (never trusts stale cache).
 * - Respects task pause and stop requests.
 * - Two workers cannot atomically claim the same step simultaneously.
 * - Validates active lease ownership before committing results so stale workers
 *   cannot overwrite newer execution outcomes.
 */
class StepScheduler(
    private val store: OrqenTaskStore,
) {
    private val activeLeases = ConcurrentHashMap<String, StepClaimLease>()

    fun findEligibleSteps(taskId: String): List<TaskStepEntity> {
        val task = store.getTask(taskId) ?: return emptyList()
        if (task.ownerStopRequested || task.pauseRequested) return emptyList()
        if (task.status == PersistentTaskStatus.PAUSED ||
            task.status == PersistentTaskStatus.PAUSE_REQUESTED ||
            task.status == PersistentTaskStatus.CANCELLED ||
            task.status == PersistentTaskStatus.COMPLETED
        ) {
            return emptyList()
        }
        val resolution = DependencyResolver.resolve(store.listStepsForTask(taskId))
        if (resolution.hasCycle || resolution.missingDependencies.isNotEmpty()) return emptyList()
        return resolution.eligibleSteps
    }

    /**
     * Atomically claims [stepId] for [workerId] inside a store transaction.
     * Returns a [StepClaimLease] if claimed, or `null` if already claimed/running/ineligible.
     */
    fun tryClaimStep(
        taskId: String,
        stepId: String,
        workerId: String,
        leaseTtlMs: Long = 5 * 60_000L,
        nowMs: Long = System.currentTimeMillis(),
    ): StepClaimLease? = store.runInTransaction {
        val task = store.getTask(taskId) ?: return@runInTransaction null
        if (task.ownerStopRequested || task.pauseRequested) return@runInTransaction null

        val existingLease = activeLeases[stepId]
        if (existingLease != null && !existingLease.isExpired(nowMs) && existingLease.workerId != workerId) {
            return@runInTransaction null
        }

        val step = store.getStep(stepId) ?: return@runInTransaction null
        if (step.taskId != taskId) return@runInTransaction null
        if (step.status != StepStatus.READY && step.status != StepStatus.PENDING) return@runInTransaction null

        val resolution = DependencyResolver.resolve(store.listStepsForTask(taskId))
        if (resolution.eligibleSteps.none { it.stepId == stepId }) return@runInTransaction null

        val claimedStep = step.copy(
            status = StepStatus.RUNNING,
            startedAt = step.startedAt ?: nowMs,
        )
        store.upsertStep(claimedStep)
        store.upsertTask(
            task.copy(
                currentStepId = stepId,
                executionLeaseOwner = workerId,
                lastHeartbeatAt = nowMs,
                updatedAt = nowMs,
            ),
        )
        val lease = StepClaimLease(
            stepId = stepId,
            taskId = taskId,
            workerId = workerId,
            claimedAtMs = nowMs,
            expiresAtMs = nowMs + leaseTtlMs,
        )
        activeLeases[stepId] = lease
        lease
    }

    fun isLeaseOwner(stepId: String, workerId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val lease = activeLeases[stepId] ?: return false
        return lease.workerId == workerId && !lease.isExpired(nowMs)
    }

    fun releaseClaim(stepId: String, workerId: String) {
        val existing = activeLeases[stepId]
        if (existing != null && existing.workerId == workerId) {
            activeLeases.remove(stepId)
        }
    }
}
