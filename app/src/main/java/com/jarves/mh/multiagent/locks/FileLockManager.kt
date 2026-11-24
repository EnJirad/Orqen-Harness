package com.jarves.mh.multiagent.locks

import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.IFileLockCoordinator
import com.jarves.mh.multiagent.model.LockAcquisitionResult
import java.util.concurrent.ConcurrentHashMap

data class FileLock(
    val filePath: String,
    val agentSlot: AgentSlot,
    val taskId: String,
    val acquiredAtMillis: Long = System.currentTimeMillis(),
    val expiresAtMillis: Long = System.currentTimeMillis() + DEFAULT_LOCK_TTL_MS,
    val reason: String = "Editing file",
) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtMillis

    companion object {
        const val DEFAULT_LOCK_TTL_MS = 10 * 60 * 1000L // 10 minutes
    }
}

class FileLockManager : IFileLockCoordinator {
    private val locks = ConcurrentHashMap<String, FileLock>()

    override fun tryAcquire(
        filePath: String,
        slot: AgentSlot,
        taskId: String,
        reason: String,
        ttlMs: Long,
    ): LockAcquisitionResult {
        cleanExpiredLocks()
        val normalized = normalizePath(filePath)
        val existing = locks[normalized]

        if (existing != null && !existing.isExpired && existing.agentSlot != slot) {
            val remaining = (existing.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            return LockAcquisitionResult.Conflict(
                lockedBy = existing.agentSlot,
                remainingTtlMs = remaining,
                reason = existing.reason,
            )
        }

        val newLock = FileLock(
            filePath = normalized,
            agentSlot = slot,
            taskId = taskId,
            acquiredAtMillis = System.currentTimeMillis(),
            expiresAtMillis = System.currentTimeMillis() + ttlMs,
            reason = reason,
        )
        locks[normalized] = newLock
        return LockAcquisitionResult.Success(newLock)
    }

    override fun renewLock(filePath: String, slot: AgentSlot, extendByMs: Long): Boolean {
        val normalized = normalizePath(filePath)
        val existing = locks[normalized] ?: return false
        if (existing.agentSlot != slot || existing.isExpired) return false
        locks[normalized] = existing.copy(
            expiresAtMillis = System.currentTimeMillis() + extendByMs
        )
        return true
    }

    @Synchronized
    fun acquireLock(
        filePath: String,
        agentSlot: AgentSlot,
        taskId: String,
        reason: String = "Editing",
        ttlMs: Long = FileLock.DEFAULT_LOCK_TTL_MS,
    ): Boolean {
        return tryAcquire(filePath, agentSlot, taskId, reason, ttlMs) is LockAcquisitionResult.Success
    }

    override fun release(filePath: String, slot: AgentSlot): Boolean {
        return releaseLock(filePath, slot)
    }

    @Synchronized
    fun releaseLock(filePath: String, agentSlot: AgentSlot): Boolean {
        val normalized = normalizePath(filePath)
        val existing = locks[normalized] ?: return true
        if (existing.agentSlot == agentSlot || existing.isExpired) {
            locks.remove(normalized)
            return true
        }
        return false
    }

    @Synchronized
    override fun releaseAllForAgent(slot: AgentSlot): Int {
        val toRemove = locks.filter { it.value.agentSlot == slot }.keys
        toRemove.forEach { locks.remove(it) }
        return toRemove.size
    }

    override fun isLockedByOther(filePath: String, slot: AgentSlot): Boolean {
        cleanExpiredLocks()
        val normalized = normalizePath(filePath)
        val existing = locks[normalized] ?: return false
        return !existing.isExpired && existing.agentSlot != slot
    }

    fun getLockOwner(filePath: String): AgentSlot? {
        cleanExpiredLocks()
        val normalized = normalizePath(filePath)
        val lock = locks[normalized] ?: return null
        return if (!lock.isExpired) lock.agentSlot else null
    }

    override fun getActiveLocks(): List<FileLock> {
        cleanExpiredLocks()
        return locks.values.filterNot { it.isExpired }
    }

    @Synchronized
    fun cleanExpiredLocks() {
        val now = System.currentTimeMillis()
        val expired = locks.filter { it.value.expiresAtMillis <= now }.keys
        expired.forEach { locks.remove(it) }
    }

    private fun normalizePath(path: String): String {
        return path.trim().replace('\\', '/').trimStart('/')
    }
}
