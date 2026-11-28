package com.jarves.mh.multiagent.runtime

import android.content.ComponentCallbacks2
import android.os.Debug
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class MemoryPressureLevel {
    NORMAL,
    MODERATE,
    HIGH,
    CRITICAL,
}

data class ResourceDiagnosticsSnapshot(
    val pssBytes: Long = 0L,
    val javaHeapUsedBytes: Long = 0L,
    val javaHeapMaxBytes: Long = 0L,
    val nativeHeapBytes: Long = 0L,
    val graphicsMemoryBytes: Long = 0L,
    val activeThreadCount: Int = 0,
    val runningProotProcesses: Int = 0,
    val responseCacheBytes: Long = 0L,
    val streamingBufferBytes: Long = 0L,
    val gcCount: Long = 0L,
    val pressureLevel: MemoryPressureLevel = MemoryPressureLevel.NORMAL,
    val adaptiveConcurrencyLimit: Int = 4,
    val capturedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Thread-safe byte-bounded LRU cache with automatic TTL expiration.
 * Prevents large AI responses or workspace artifacts from silently growing Java heap.
 */
class ByteBoundedLruCache<K : Any, V : Any>(
    private val maxBytes: Long,
    private val ttlMs: Long,
    private val sizeOf: (K, V) -> Long,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private data class Entry<V>(
        val value: V,
        val bytes: Long,
        val createdAtMs: Long,
    )

    private val lock = Any()
    private val map = LinkedHashMap<K, Entry<V>>(32, 0.75f, true)
    private var totalBytes: Long = 0L
    private val hits = AtomicLong(0L)
    private val misses = AtomicLong(0L)

    fun get(key: K): V? = synchronized(lock) {
        val entry = map[key]
        if (entry == null) {
            misses.incrementAndGet()
            return null
        }
        val now = clockMs()
        if (ttlMs > 0L && now - entry.createdAtMs > ttlMs) {
            map.remove(key)
            totalBytes = (totalBytes - entry.bytes).coerceAtLeast(0L)
            misses.incrementAndGet()
            return null
        }
        hits.incrementAndGet()
        entry.value
    }

    fun put(key: K, value: V) {
        synchronized(lock) {
            val entryBytes = sizeOf(key, value).coerceAtLeast(64L)
            val existing = map.remove(key)
            if (existing != null) {
                totalBytes = (totalBytes - existing.bytes).coerceAtLeast(0L)
            }
            if (entryBytes > maxBytes) {
                // Single entry exceeds entire cache budget; do not cache it.
                return
            }
            map[key] = Entry(value = value, bytes = entryBytes, createdAtMs = clockMs())
            totalBytes += entryBytes
            evictExpiredLocked()
            trimToBytesLocked(maxBytes)
        }
    }

    fun evictExpired(): Int = synchronized(lock) { evictExpiredLocked() }

    fun trimToBytes(targetBytes: Long): Int = synchronized(lock) { trimToBytesLocked(targetBytes.coerceAtLeast(0L)) }

    fun clear() = synchronized(lock) {
        map.clear()
        totalBytes = 0L
    }

    fun size(): Int = synchronized(lock) { map.size }

    fun currentBytes(): Long = synchronized(lock) { totalBytes }

    fun hitCount(): Long = hits.get()

    fun missCount(): Long = misses.get()

    private fun evictExpiredLocked(): Int {
        if (ttlMs <= 0L || map.isEmpty()) return 0
        val now = clockMs()
        var removed = 0
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.createdAtMs > ttlMs) {
                totalBytes = (totalBytes - e.value.bytes).coerceAtLeast(0L)
                it.remove()
                removed++
            }
        }
        return removed
    }

    private fun trimToBytesLocked(targetBytes: Long): Int {
        var removed = 0
        val it = map.entries.iterator()
        while (totalBytes > targetBytes && it.hasNext()) {
            val eldest = it.next()
            totalBytes = (totalBytes - eldest.value.bytes).coerceAtLeast(0L)
            it.remove()
            removed++
        }
        return removed
    }
}

/**
 * Incremental streaming buffer capped at [maxChars] to avoid O(N^2) allocations
 * and unbounded heap growth when streaming long AI/terminal responses.
 */
class BoundedStreamBuffer(
    private val maxChars: Int = 128 * 1024,
) {
    private val sb = StringBuilder(minOf(4096, maxChars))
    private var truncatedChars: Long = 0L

    @Synchronized
    fun append(chunk: CharSequence) {
        if (chunk.isEmpty()) return
        if (chunk.length >= maxChars) {
            truncatedChars += sb.length + (chunk.length - maxChars)
            sb.setLength(0)
            sb.append(chunk, chunk.length - maxChars, chunk.length)
            return
        }
        val overflow = (sb.length + chunk.length) - maxChars
        if (overflow > 0) {
            sb.delete(0, overflow)
            truncatedChars += overflow
        }
        sb.append(chunk)
    }

    @Synchronized
    fun snapshot(): String = sb.toString()

    @Synchronized
    fun length(): Int = sb.length

    @Synchronized
    fun approxBytes(): Long = sb.length.toLong() * 2L

    @Synchronized
    fun wasTruncated(): Boolean = truncatedChars > 0L

    @Synchronized
    fun clear() {
        sb.setLength(0)
        truncatedChars = 0L
    }
}

/**
 * Centralized ORQEN Resource Manager:
 * - Monitors Java heap, Native heap, PSS, graphics memory, GC count, threads, and PRoot processes.
 * - Dynamically computes adaptive AI concurrency without permanently shrinking user team size.
 * - Coordinates memory pressure relief callbacks across caches and inactive project buffers.
 */
class AgentResourceManager(
    private val heapRatioProvider: () -> Float = { defaultJavaHeapRatio() },
) {
    private val trimListeners = CopyOnWriteArrayList<(MemoryPressureLevel) -> Unit>()
    private val manualPressureOverride = AtomicInteger(-1)
    private val activeStreamingBytes = AtomicLong(0L)

    fun registerTrimListener(listener: (MemoryPressureLevel) -> Unit) {
        trimListeners.addIfAbsent(listener)
    }

    fun unregisterTrimListener(listener: (MemoryPressureLevel) -> Unit) {
        trimListeners.remove(listener)
    }

    fun updateActiveStreamingBytes(bytes: Long) {
        activeStreamingBytes.set(bytes.coerceAtLeast(0L))
    }

    /** Test/diagnostics hook to simulate or clear memory pressure deterministically. */
    fun setPressureOverrideForTesting(level: MemoryPressureLevel?) {
        manualPressureOverride.set(level?.ordinal ?: -1)
    }

    fun currentPressureLevel(): MemoryPressureLevel {
        val overrideIdx = manualPressureOverride.get()
        if (overrideIdx in MemoryPressureLevel.entries.indices) {
            return MemoryPressureLevel.entries[overrideIdx]
        }
        val heapRatio = heapRatioProvider().coerceIn(0f, 1f)
        return when {
            heapRatio >= 0.88f -> MemoryPressureLevel.CRITICAL
            heapRatio >= 0.76f -> MemoryPressureLevel.HIGH
            heapRatio >= 0.62f -> MemoryPressureLevel.MODERATE
            else -> MemoryPressureLevel.NORMAL
        }
    }

    /**
     * Computes adaptive execution concurrency based on live device pressure while
     * preserving the user's configured team size.
     * - Interactive foreground chat always retains at least 1 slot unless critically OOM.
     * - Background/heavy parallel coding scales down smoothly and pauses new starts on CRITICAL.
     */
    fun recommendedConcurrency(
        configuredMaxAgents: Int,
        isForegroundInteractive: Boolean = false,
    ): Int {
        val clamped = configuredMaxAgents.coerceAtLeast(1)
        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val cpuCap = (cpuCores / 2).coerceIn(2, 4)
        val baseMax = minOf(clamped, cpuCap)
        return when (currentPressureLevel()) {
            MemoryPressureLevel.NORMAL -> baseMax
            MemoryPressureLevel.MODERATE -> minOf(baseMax, 2)
            MemoryPressureLevel.HIGH -> 1
            MemoryPressureLevel.CRITICAL -> if (isForegroundInteractive) 1 else 0
        }
    }

    fun onTrimMemory(androidTrimLevel: Int) {
        val pressure = when {
            androidTrimLevel >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryPressureLevel.CRITICAL
            androidTrimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryPressureLevel.CRITICAL
            androidTrimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryPressureLevel.HIGH
            androidTrimLevel >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> MemoryPressureLevel.MODERATE
            else -> MemoryPressureLevel.MODERATE
        }
        dispatchMemoryRelief(pressure)
    }

    fun triggerMemoryRelief() {
        dispatchMemoryRelief(currentPressureLevel().coerceAtLeast(MemoryPressureLevel.HIGH))
    }

    private fun dispatchMemoryRelief(level: MemoryPressureLevel) {
        trimListeners.forEach { listener ->
            runCatching { listener(level) }
        }
    }

    fun captureSnapshot(
        configuredMaxAgents: Int = 4,
        runningProotProcesses: Int = 0,
        responseCacheBytes: Long = 0L,
    ): ResourceDiagnosticsSnapshot {
        val runtime = Runtime.getRuntime()
        val heapUsed = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val heapMax = runtime.maxMemory().coerceAtLeast(1L)

        var pssBytes = 0L
        var graphicsBytes = 0L
        var nativeHeap = 0L
        var gcCount = 0L

        runCatching {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            pssBytes = memInfo.totalPss.toLong() * 1_024L
            val graphicsKb = memInfo.getMemoryStat("summary.graphics")?.toLongOrNull() ?: 0L
            graphicsBytes = graphicsKb * 1_024L
        }
        runCatching {
            nativeHeap = Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L)
        }
        runCatching {
            gcCount = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L
        }

        val pressure = currentPressureLevel()
        return ResourceDiagnosticsSnapshot(
            pssBytes = pssBytes,
            javaHeapUsedBytes = heapUsed,
            javaHeapMaxBytes = heapMax,
            nativeHeapBytes = nativeHeap,
            graphicsMemoryBytes = graphicsBytes,
            activeThreadCount = Thread.activeCount(),
            runningProotProcesses = runningProotProcesses.coerceAtLeast(0),
            responseCacheBytes = responseCacheBytes.coerceAtLeast(0L),
            streamingBufferBytes = activeStreamingBytes.get(),
            gcCount = gcCount,
            pressureLevel = pressure,
            adaptiveConcurrencyLimit = recommendedConcurrency(configuredMaxAgents),
            capturedAtMs = System.currentTimeMillis(),
        )
    }

    companion object {
        fun defaultJavaHeapRatio(): Float {
            val runtime = Runtime.getRuntime()
            val max = runtime.maxMemory().coerceAtLeast(1L)
            val used = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
            return (used.toDouble() / max.toDouble()).toFloat().coerceIn(0f, 1f)
        }
    }
}
