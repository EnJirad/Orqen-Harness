package com.jarves.mh.multiagent.locks

import com.jarves.mh.multiagent.model.AgentSlot
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

class FileLockManager {
    private val locks = ConcurrentHashMap<String, FileLock>()

    @Synchronized
    fun acquireLock(
        filePath: String,
        agentSlot: AgentSlot,
        taskId: String,
        reason: String = "Editing",
        ttlMs: Long = FileLock.DEFAULT_LOCK_TTL_MS,
    ): Boolean {
        cleanExpiredLocks()
        val normalized = normalizePath(filePath)
        val existing = locks[normalized]

        if (existing != null && !existing.isExpired && existing.agentSlot != agentSlot) {
            // Locked by another agent!
            return false
        }

        locks[normalized] = FileLock(
            filePath = normalized,
            agentSlot = agentSlot,
            taskId = taskId,
            acquiredAtMillis = System.currentTimeMillis(),
            expiresAtMillis = System.currentTimeMillis() + ttlMs,
            reason = reason,
        )
        return true
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
    fun releaseAllForAgent(agentSlot: AgentSlot) {
        val toRemove = locks.filter { it.value.agentSlot == agentSlot }.keys
        toRemove.forEach { locks.remove(it) }
    }

    fun isLockedByOther(filePath: String, agentSlot: AgentSlot): Boolean {
        cleanExpiredLocks()
        val normalized = normalizePath(filePath)
        val existing = locks[normalized] ?: return false
        return !existing.isExpired && existing.agentSlot != agentSlot
    }

    fun getLockOwner(filePath: String): AgentSlot? {
        cleanExpiredLocks()
        val normalized = normalizePath(filePath)
        val lock = locks[normalized] ?: return null
        return if (!lock.isExpired) lock.agentSlot else null
    }

    fun getActiveLocks(): List<FileLock> {
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
