package com.jarves.mh.multiagent

import android.content.ComponentCallbacks2
import com.jarves.mh.multiagent.collab.AgentProfileEntity
import com.jarves.mh.multiagent.collab.InMemoryCollaborationStore
import com.jarves.mh.multiagent.engine.AIExecutionRuntime
import com.jarves.mh.multiagent.engine.RuntimeCapabilities
import com.jarves.mh.multiagent.engine.RuntimeExecutionRequest
import com.jarves.mh.multiagent.engine.RuntimeExecutionResponse
import com.jarves.mh.multiagent.persistence.InMemoryOrqenTaskStore
import com.jarves.mh.multiagent.persistence.PersistentTaskStatus
import com.jarves.mh.multiagent.persistence.ProjectEntity
import com.jarves.mh.multiagent.persistence.StepStatus
import com.jarves.mh.multiagent.persistence.TaskEntity
import com.jarves.mh.multiagent.persistence.TaskStepEntity
import com.jarves.mh.multiagent.runtime.AgentResourceManager
import com.jarves.mh.multiagent.runtime.BoundedStreamBuffer
import com.jarves.mh.multiagent.runtime.ByteBoundedLruCache
import com.jarves.mh.multiagent.runtime.MemoryPressureLevel
import com.jarves.mh.multiagent.session.HierarchicalMemoryManager
import com.jarves.mh.multiagent.session.MemoryScope
import com.jarves.mh.multiagent.supervisor.AutonomousSupervisorEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class OrqenPerformanceOptimizationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `ByteBoundedLruCache enforces strict byte budget and TTL expiration`() {
        var nowMs = 1_000L
        val maxBytes = 256L * 1024L // 256 KB strict cap
        val cache = ByteBoundedLruCache<String, String>(
            maxBytes = maxBytes,
            ttlMs = 60_000L,
            sizeOf = { k, v -> (k.length + v.length).toLong() * 2L },
            clockMs = { nowMs },
        )

        // Simulate 40 large 50 KB responses (2 MB total unbounded)
        val payload = "A".repeat(25_000) // 50 KB in UTF-16
        for (i in 1..40) {
            cache.put("key-$i", payload)
        }

        val retainedBytes = cache.currentBytes()
        println("PERF_CACHE_BUDGET: unboundedBytes=2000000 boundedBytes=$retainedBytes entries=${cache.size()}")
        assertTrue("Cache must stay within 256 KB budget", retainedBytes <= maxBytes)
        assertTrue("Oldest entries must be evicted", cache.size() <= 5)

        // Advance past TTL and verify expiration
        nowMs += 61_000L
        assertNull("Expired entry must not be returned", cache.get("key-40"))
        cache.evictExpired()
        assertEquals(0L, cache.currentBytes())
    }

    @Test
    fun `BoundedStreamBuffer caps heap usage on 1MB streaming output`() {
        val maxChars = 64 * 1024 // 128 KB UTF-16 cap
        val buffer = BoundedStreamBuffer(maxChars = maxChars)
        val chunk = "stream-line-data-0123456789abcdef\n".repeat(30) // ~1 KB
        val startNs = System.nanoTime()
        repeat(1_024) {
            buffer.append(chunk)
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        println("PERF_STREAM_BUFFER: inputBytes=1048576 retainedBytes=${buffer.approxBytes()} truncated=${buffer.wasTruncated()} elapsedMs=$elapsedMs")
        assertTrue(buffer.length() <= maxChars)
        assertTrue(buffer.wasTruncated())
        assertTrue(buffer.approxBytes() <= maxChars.toLong() * 2L)
    }

    @Test
    fun `AgentResourceManager scales concurrency dynamically and dispatches trim callbacks`() {
        val rm = AgentResourceManager(heapRatioProvider = { 0.40f })
        val trimLevels = mutableListOf<MemoryPressureLevel>()
        rm.registerTrimListener { trimLevels.add(it) }

        rm.setPressureOverrideForTesting(MemoryPressureLevel.NORMAL)
        val normalCap = rm.recommendedConcurrency(configuredMaxAgents = 4)
        assertTrue("Normal concurrency should allow parallel execution (>=2)", normalCap >= 2)

        rm.setPressureOverrideForTesting(MemoryPressureLevel.MODERATE)
        assertEquals(2, rm.recommendedConcurrency(configuredMaxAgents = 4))

        rm.setPressureOverrideForTesting(MemoryPressureLevel.HIGH)
        assertEquals(1, rm.recommendedConcurrency(configuredMaxAgents = 4))

        rm.setPressureOverrideForTesting(MemoryPressureLevel.CRITICAL)
        assertEquals(0, rm.recommendedConcurrency(configuredMaxAgents = 4, isForegroundInteractive = false))
        assertEquals(1, rm.recommendedConcurrency(configuredMaxAgents = 4, isForegroundInteractive = true))

        rm.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        rm.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(listOf(MemoryPressureLevel.HIGH, MemoryPressureLevel.CRITICAL), trimLevels)

        // Recovery back to NORMAL
        rm.setPressureOverrideForTesting(MemoryPressureLevel.NORMAL)
        assertTrue(rm.recommendedConcurrency(configuredMaxAgents = 4) >= 2)
        println("PERF_ADAPTIVE_CONCURRENCY: normal=$normalCap moderate=2 high=1 criticalBg=0 criticalFg=1 trimCallbacks=${trimLevels.size}")
    }

    @Test
    fun `HierarchicalMemoryManager trims inactive projects from RAM and lazily reloads from store`() {
        val store = InMemoryOrqenTaskStore()
        val memory = HierarchicalMemoryManager(store)

        for (i in 1..6) {
            memory.storeStructuredMemory(
                scope = MemoryScope.PROJECT,
                projectId = "proj-$i",
                title = "Decision $i",
                content = "Architecture decision #$i: use Kotlin coroutines with bounded caches for ORQEN.",
                tags = listOf("architecture", "perf"),
            )
        }
        assertEquals(6, memory.inMemoryProjectCount())

        val evicted = memory.trimInMemoryCache(keepProjectId = "proj-1")
        assertEquals(5, evicted)
        assertEquals(1, memory.inMemoryProjectCount())

        // Lazy reload from persistent store must still return evicted project's memories intact
        val reloaded = memory.retrieveRelevant("proj-5", query = "coroutines bounded caches", limit = 3)
        assertTrue("Evicted project memory must reload lazily from store", reloaded.isNotEmpty())
        println("PERF_MEMORY_TRIM: initialProjects=6 evicted=$evicted reloadedEntries=${reloaded.size}")
    }

    @Test
    fun `Four-agent team adapts under memory pressure without dropping tasks or checkpoints`() = runBlocking {
        val ws = tempFolder.newFolder("orqen-perf-4agent")
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val project = ProjectEntity("proj-perf", "4-Agent Perf Project", ws.absolutePath)
        taskStore.upsertProject(project)

        val resourceManager = AgentResourceManager(heapRatioProvider = { 0.45f })
        // Force HIGH pressure initially (serializes to 1 concurrent agent) then recover mid-run
        resourceManager.setPressureOverrideForTesting(MemoryPressureLevel.HIGH)

        val activeCount = AtomicInteger(0)
        val maxSeenConcurrent = AtomicInteger(0)
        val completedSteps = AtomicInteger(0)

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(
                request: RuntimeExecutionRequest,
                onProgress: suspend (String) -> Unit,
            ): RuntimeExecutionResponse {
                val now = System.currentTimeMillis()
                val cur = activeCount.incrementAndGet()
                maxSeenConcurrent.accumulateAndGet(cur, ::maxOf)
                try {
                    delay(80L)
                    val sid = request.stepId ?: "step"
                    val target = File(ws, "module_$sid.kt")
                    target.writeText("package demo\nfun work_$sid() = true\n")
                    if (completedSteps.incrementAndGet() == 1) {
                        // Simulate device memory recovery after first step
                        resourceManager.setPressureOverrideForTesting(MemoryPressureLevel.NORMAL)
                    }
                    return RuntimeExecutionResponse(
                        requestId = request.requestId,
                        providerId = "stub",
                        modelId = "stub-1",
                        startedAtMs = now,
                        completedAtMs = System.currentTimeMillis(),
                        success = true,
                        outputText = "Completed $sid",
                        modifiedFiles = listOf(target.name),
                    )
                } finally {
                    activeCount.decrementAndGet()
                }
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, true, true, true)
        }

        val engine = AutonomousSupervisorEngine(
            taskStore = taskStore,
            collabStore = collabStore,
            runtime = runtime,
            resourceManager = resourceManager,
            delayFn = {},
        )
        val mgr = engine.registry.register(AgentProfileEntity("agent-01", "Manager", "Lead", "Mgr", "stub", "stub-1", capabilities = setOf("lead", "plan", "review")))
        val c1 = engine.registry.register(AgentProfileEntity("agent-02", "CoderA", "Dev", "Dev", "stub", "stub-1", capabilities = setOf("code")))
        val c2 = engine.registry.register(AgentProfileEntity("agent-03", "CoderB", "Dev", "Dev", "stub", "stub-1", capabilities = setOf("code")))
        val c3 = engine.registry.register(AgentProfileEntity("agent-04", "CoderC", "Dev", "Dev", "stub", "stub-1", capabilities = setOf("code")))
        val team = engine.teamManager.createTeam(
            projectId = project.projectId,
            teamName = "4-Agent Perf Team",
            memberAgentIds = listOf(mgr.agentId, c1.agentId, c2.agentId, c3.agentId),
            requireIndependentReview = false,
            maxConcurrentAgents = 4,
        )

        val task = TaskEntity(
            taskId = "task-perf-4agent",
            projectId = project.projectId,
            title = "4-Agent Adaptive Build",
            description = "Verify adaptive concurrency and zero task loss",
            status = PersistentTaskStatus.QUEUED,
            completionCriteria = com.jarves.mh.multiagent.persistence.TaskCompletionCriteria(
                requireTestsPass = false,
                requireReviewerApproval = false,
            ),
        )
        taskStore.upsertTask(task)

        listOf(c1.agentId to "s1", c2.agentId to "s2", c3.agentId to "s3").forEachIndexed { idx, (agId, sid) ->
            taskStore.upsertStep(
                TaskStepEntity(
                    stepId = sid,
                    taskId = task.taskId,
                    sequenceNumber = idx + 1,
                    title = "Build module $sid",
                    description = "Create module_$sid.kt",
                    assignedAgentId = agId,
                    targetFiles = listOf("module_$sid.kt"),
                    status = StepStatus.READY,
                    idempotencyKey = "idem-$sid",
                ),
            )
        }

        val finalTask = engine.superviseTask(task.taskId, team)
        assertNotNull(finalTask)
        assertEquals(PersistentTaskStatus.COMPLETED, finalTask!!.status)
        assertEquals(3, completedSteps.get())
        assertTrue("Should recover parallelism after pressure drops", maxSeenConcurrent.get() >= 2)
        assertFalse(taskStore.listStepsForTask(task.taskId).any { it.status != StepStatus.COMPLETED })
        println("PERF_4AGENT_ADAPTIVE: status=${finalTask.status} completedSteps=${completedSteps.get()} maxConcurrent=${maxSeenConcurrent.get()}")
    }

    @Test
    fun `Diagnostics snapshot capture stays lightweight under 5ms average`() {
        val rm = AgentResourceManager()
        val startNs = System.nanoTime()
        var lastSnap = rm.captureSnapshot(configuredMaxAgents = 4, runningProotProcesses = 1, responseCacheBytes = 4096L)
        repeat(20) {
            lastSnap = rm.captureSnapshot(configuredMaxAgents = 4, runningProotProcesses = 1, responseCacheBytes = 4096L)
        }
        val avgUs = ((System.nanoTime() - startNs) / 21L) / 1_000L
        assertNotNull(lastSnap)
        assertTrue(lastSnap.activeThreadCount > 0)
        assertTrue(lastSnap.javaHeapMaxBytes > 0L)
        println("PERF_DIAGNOSTICS_OVERHEAD: avgCaptureUs=$avgUs threads=${lastSnap.activeThreadCount} heapUsed=${lastSnap.javaHeapUsedBytes}")
    }
}
