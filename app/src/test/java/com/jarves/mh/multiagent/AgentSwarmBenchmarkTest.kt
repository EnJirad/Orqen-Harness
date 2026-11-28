package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.collab.AgentProfileEntity
import com.jarves.mh.multiagent.collab.InMemoryCollaborationStore
import com.jarves.mh.multiagent.engine.AIExecutionRuntime
import com.jarves.mh.multiagent.engine.RuntimeCapabilities
import com.jarves.mh.multiagent.engine.RuntimeExecutionRequest
import com.jarves.mh.multiagent.engine.RuntimeExecutionResponse
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.session.HierarchicalMemoryManager
import com.jarves.mh.multiagent.session.MemoryScope
import com.jarves.mh.multiagent.session.MemoryStoreOutcome
import com.jarves.mh.multiagent.supervisor.AutonomousSupervisorEngine
import com.jarves.mh.runtime.TestExecutionEvidence
import com.jarves.mh.runtime.TestExecutionOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real 5-Scenario Benchmark & Verification Suite (PART 3 & PART 11):
 * 1. Simple Task: Calculator module
 * 2. Multi-file Task: Multi-component project (Model + Repo + Service + UI)
 * 3. Parallel Task: Concurrent multi-coder execution (proving >= 2 concurrent workers)
 * 4. Dependency Task + Review/Rework Loop: Worker A -> Worker B -> Reviewer Reject -> Fix -> Approve
 * 5. Recovery Task: Mid-execution worker crash/stall -> Heartbeat Sweep -> Lock Release -> Resume without redoing completed steps
 * + 5-Scope Persistent Memory verification (dedup, noise floor, secret scrubbing, access control, relevance retrieval)
 */
class AgentSwarmBenchmarkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `Benchmark 1 - Simple Task builds Calculator module and distills persistent memory`() = runBlocking {
        val startedAt = System.currentTimeMillis()
        val ws = tempFolder.newFolder("bench-1-calc")
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val project = ProjectEntity("bench-p1", "Calculator Bench", ws.absolutePath)
        taskStore.upsertProject(project)

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                val now = System.currentTimeMillis()
                return if (request.agent.config.slot.id == "agent-02") {
                    File(ws, "src/Calculator.kt").apply {
                        parentFile?.mkdirs()
                        writeText("class Calculator { fun add(a: Int, b: Int) = a + b }\n")
                    }
                    RuntimeExecutionResponse(
                        requestId = request.requestId, providerId = "p1", modelId = "m-dev",
                        startedAtMs = now, completedAtMs = now + 5, success = true,
                        outputText = "Implemented Calculator.kt with add/sub/mul/div operations.",
                        modifiedFiles = listOf("src/Calculator.kt"),
                    )
                } else {
                    RuntimeExecutionResponse(
                        requestId = request.requestId, providerId = "p1", modelId = "m-qa",
                        startedAtMs = now, completedAtMs = now + 5, success = true,
                        outputText = "Verified Calculator.kt logic and tests. REVIEW_APPROVED TESTS_PASSED",
                        testEvidence = listOf(TestExecutionEvidence("test", now, now + 5, 0, "ok", "", TestExecutionOutcome.TESTS_PASSED, "ok")),
                    )
                }
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, true, true, true)
        }

        val engine = AutonomousSupervisorEngine(taskStore = taskStore, collabStore = collabStore, runtime = runtime, delayFn = {})
        val mgr = engine.registry.register(AgentProfileEntity("agent-01", "Manager", "Lead", "Mgr", "p1", "m1", capabilities = setOf("lead", "architect", "plan", "review", "qa")))
        val dev = engine.registry.register(AgentProfileEntity("agent-02", "Developer", "Dev", "Dev", "p1", "m2", capabilities = setOf("code", "fix")))
        val team = engine.teamManager.createTeam(project.projectId, "Bench Team 1", listOf(mgr.agentId, dev.agentId), requireIndependentReview = true, maxConcurrentAgents = 2)

        val result = engine.submitAndSupervise(
            projectId = project.projectId,
            team = team,
            objective = "Create a simple Calculator class",
            requiredDeliverableFiles = listOf("src/Calculator.kt"),
            completionCriteria = TaskCompletionCriteria(requireTestsPass = true, requireReviewerApproval = true),
        )
        val elapsedMs = System.currentTimeMillis() - startedAt
        assertNotNull(result)
        assertEquals(PersistentTaskStatus.COMPLETED, result!!.status)
        assertTrue(File(ws, "src/Calculator.kt").isFile)
        assertTrue(engine.memoryManager.listMemoriesForProject(project.projectId, MemoryScope.DECISION).isNotEmpty())
        println("BENCHMARK_1_SIMPLE: status=${result.status} elapsedMs=$elapsedMs agents=2 steps=${taskStore.listStepsForTask(result.taskId).size}")
    }

    @Test
    fun `Benchmark 2 - Multi-file Task builds multi-component project and verifies all deliverables`() = runBlocking {
        val startedAt = System.currentTimeMillis()
        val ws = tempFolder.newFolder("bench-2-multifile")
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val project = ProjectEntity("bench-p2", "Notes MultiFile", ws.absolutePath)
        taskStore.upsertProject(project)

        val deliverables = listOf("src/Model.kt", "src/Repository.kt", "src/Service.kt", "src/Ui.kt")
        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                val now = System.currentTimeMillis()
                return if (request.agent.config.slot.id == "agent-02") {
                    deliverables.forEach { rel ->
                        File(ws, rel).apply { parentFile?.mkdirs(); writeText("// $rel\n") }
                    }
                    RuntimeExecutionResponse(
                        requestId = request.requestId, providerId = "p1", modelId = "m-dev",
                        startedAtMs = now, completedAtMs = now + 10, success = true,
                        outputText = "Created all 4 components cleanly.",
                        modifiedFiles = deliverables,
                    )
                } else {
                    RuntimeExecutionResponse(
                        requestId = request.requestId, providerId = "p1", modelId = "m-qa",
                        startedAtMs = now, completedAtMs = now + 5, success = true,
                        outputText = "All 4 components verified. REVIEW_APPROVED TESTS_PASSED",
                        testEvidence = listOf(TestExecutionEvidence("test", now, now + 5, 0, "ok", "", TestExecutionOutcome.TESTS_PASSED, "ok")),
                    )
                }
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, true, true, true)
        }

        val engine = AutonomousSupervisorEngine(taskStore = taskStore, collabStore = collabStore, runtime = runtime, delayFn = {})
        val mgr = engine.registry.register(AgentProfileEntity("agent-01", "Manager", "Lead", "Mgr", "p1", "m1", capabilities = setOf("lead", "architect", "plan", "review", "qa")))
        val dev = engine.registry.register(AgentProfileEntity("agent-02", "Developer", "Dev", "Dev", "p1", "m2", capabilities = setOf("code", "fix")))
        val team = engine.teamManager.createTeam(project.projectId, "Bench Team 2", listOf(mgr.agentId, dev.agentId), requireIndependentReview = true, maxConcurrentAgents = 2)

        val result = engine.submitAndSupervise(
            projectId = project.projectId,
            team = team,
            objective = "Create a multi-file Notes app with Model, Repository, Service, and UI",
            requiredDeliverableFiles = deliverables,
            completionCriteria = TaskCompletionCriteria(requireTestsPass = true, requireReviewerApproval = true),
        )
        val elapsedMs = System.currentTimeMillis() - startedAt
        assertEquals(PersistentTaskStatus.COMPLETED, result!!.status)
        assertTrue(deliverables.all { File(ws, it).isFile })
        println("BENCHMARK_2_MULTIFILE: status=${result.status} elapsedMs=$elapsedMs files=${deliverables.size}")
    }

    @Test
    fun `Benchmark 3 - Parallel Task executes 2 coding workers concurrently`() = runBlocking {
        val startedAt = System.currentTimeMillis()
        val ws = tempFolder.newFolder("bench-3-parallel")
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val project = ProjectEntity("bench-p3", "Parallel Bench", ws.absolutePath)
        taskStore.upsertProject(project)

        val activeCoders = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                val now = System.currentTimeMillis()
                val aid = request.agent.config.slot.id
                if (aid == "agent-02" || aid == "agent-03") {
                    val cur = activeCoders.incrementAndGet()
                    maxConcurrent.accumulateAndGet(cur, ::maxOf)
                    try {
                        delay(100L)
                        val rel = "src/$aid-module.kt"
                        File(ws, rel).apply { parentFile?.mkdirs(); writeText("// $aid\n") }
                        return RuntimeExecutionResponse(
                            requestId = request.requestId, providerId = "p1", modelId = "m-dev",
                            startedAtMs = now, completedAtMs = System.currentTimeMillis(), success = true,
                            outputText = "Implemented parallel module cleanly for $aid with verified exports.",
                            modifiedFiles = listOf(rel),
                        )
                    } finally {
                        activeCoders.decrementAndGet()
                    }
                }
                return RuntimeExecutionResponse(
                    requestId = request.requestId, providerId = "p1", modelId = "m-qa",
                    startedAtMs = now, completedAtMs = now + 5, success = true,
                    outputText = "Verified parallel modules. REVIEW_APPROVED TESTS_PASSED",
                    testEvidence = listOf(TestExecutionEvidence("test", now, now + 5, 0, "ok", "", TestExecutionOutcome.TESTS_PASSED, "ok")),
                )
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, true, true, true)
        }

        val engine = AutonomousSupervisorEngine(taskStore = taskStore, collabStore = collabStore, runtime = runtime, delayFn = {})
        val mgr = engine.registry.register(AgentProfileEntity("agent-01", "Manager", "Lead", "Mgr", "p1", "m1", capabilities = setOf("lead", "architect", "plan", "review", "qa")))
        val d1 = engine.registry.register(AgentProfileEntity("agent-02", "Frontend Dev", "Frontend", "FE", "p1", "m2", capabilities = setOf("code", "fix")))
        val d2 = engine.registry.register(AgentProfileEntity("agent-03", "Backend Dev", "Backend", "BE", "p1", "m3", capabilities = setOf("code", "fix")))
        val team = engine.teamManager.createTeam(project.projectId, "Bench Team 3", listOf(mgr.agentId, d1.agentId, d2.agentId), requireIndependentReview = true, maxConcurrentAgents = 3)

        val result = engine.submitAndSupervise(
            projectId = project.projectId,
            team = team,
            objective = "Build frontend dashboard and backend API in parallel",
            requestedSubtasks = listOf("Build frontend dashboard", "Build backend API"),
            completionCriteria = TaskCompletionCriteria(requireTestsPass = true, requireReviewerApproval = true),
        )
        val elapsedMs = System.currentTimeMillis() - startedAt
        assertEquals(PersistentTaskStatus.COMPLETED, result!!.status)
        assertTrue("Expected concurrent execution >= 2, got ${maxConcurrent.get()}", maxConcurrent.get() >= 2)
        assertTrue(engine.memoryManager.listMemoriesForProject(project.projectId, MemoryScope.EXECUTION).isNotEmpty())
        println("BENCHMARK_3_PARALLEL: status=${result.status} elapsedMs=$elapsedMs maxConcurrent=${maxConcurrent.get()}")
    }

    @Test
    fun `Benchmark 4 - Dependency Task with Independent Review Rejection and Autonomous Rework Loop`() = runBlocking {
        val startedAt = System.currentTimeMillis()
        val ws = tempFolder.newFolder("bench-4-dependency")
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val project = ProjectEntity("bench-p4", "Dependency Rework Bench", ws.absolutePath)
        taskStore.upsertProject(project)

        val qaAttempts = AtomicInteger(0)
        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                val now = System.currentTimeMillis()
                val aid = request.agent.config.slot.id
                return if (aid == "agent-03") {
                    val attempt = qaAttempts.incrementAndGet()
                    if (attempt == 1) {
                        RuntimeExecutionResponse(
                            requestId = request.requestId, providerId = "p1", modelId = "m-qa",
                            startedAtMs = now, completedAtMs = now + 5, success = true,
                            outputText = "REVIEW_REJECTED: Missing null-safety check in Client.kt",
                        )
                    } else {
                        RuntimeExecutionResponse(
                            requestId = request.requestId, providerId = "p1", modelId = "m-qa",
                            startedAtMs = now, completedAtMs = now + 5, success = true,
                            outputText = "Null-safety fix verified. REVIEW_APPROVED TESTS_PASSED",
                            testEvidence = listOf(TestExecutionEvidence("test", now, now + 5, 0, "ok", "", TestExecutionOutcome.TESTS_PASSED, "ok")),
                        )
                    }
                } else {
                    File(ws, "src/Client.kt").apply {
                        parentFile?.mkdirs()
                        writeText("class Client { fun fetch(id: String?) = id ?: \"default\" }\n")
                    }
                    RuntimeExecutionResponse(
                        requestId = request.requestId, providerId = "p1", modelId = "m-dev",
                        startedAtMs = now, completedAtMs = now + 5, success = true,
                        outputText = "Implemented and fixed Client.kt null-safety check.",
                        modifiedFiles = listOf("src/Client.kt"),
                    )
                }
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, true, true, true)
        }

        val engine = AutonomousSupervisorEngine(taskStore = taskStore, collabStore = collabStore, runtime = runtime, delayFn = {})
        val mgr = engine.registry.register(AgentProfileEntity("agent-01", "Manager", "Lead", "Mgr", "p1", "m1", capabilities = setOf("lead", "architect", "plan")))
        val dev = engine.registry.register(AgentProfileEntity("agent-02", "Developer", "Dev", "Dev", "p1", "m2", capabilities = setOf("code", "fix")))
        val qa = engine.registry.register(AgentProfileEntity("agent-03", "QA Reviewer", "QA", "QA", "p1", "m3", capabilities = setOf("review", "qa", "test")))
        val team = engine.teamManager.createTeam(project.projectId, "Bench Team 4", listOf(mgr.agentId, dev.agentId, qa.agentId), requireIndependentReview = true, maxConcurrentAgents = 2)

        val result = engine.submitAndSupervise(
            projectId = project.projectId,
            team = team,
            objective = "Implement Client.kt with null-safety and independent QA review",
            requiredDeliverableFiles = listOf("src/Client.kt"),
            completionCriteria = TaskCompletionCriteria(requireTestsPass = true, requireReviewerApproval = true),
        )
        val elapsedMs = System.currentTimeMillis() - startedAt
        assertEquals(PersistentTaskStatus.COMPLETED, result!!.status)
        assertTrue("Expected QA to run at least twice (reject -> rework -> approve)", qaAttempts.get() >= 2)
        println("BENCHMARK_4_DEPENDENCY_REWORK: status=${result.status} elapsedMs=$elapsedMs qaAttempts=${qaAttempts.get()}")
    }

    @Test
    fun `Benchmark 5 - Recovery Task recovers stalled worker, releases locks, and completes without redoing finished step`() = runBlocking {
        val startedAt = System.currentTimeMillis()
        val ws = tempFolder.newFolder("bench-5-recovery")
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val fileLockManager = FileLockManager()
        val project = ProjectEntity("bench-p5", "Recovery Bench", ws.absolutePath)
        taskStore.upsertProject(project)

        val step1Executions = AtomicInteger(0)
        val step2Executions = AtomicInteger(0)

        // Simulate a crashed task: Step 1 already COMPLETED, Step 2 stuck RUNNING with held file lock & stale heartbeat!
        val staleTime = System.currentTimeMillis() - 600_000L
        val crashedTask = TaskEntity(
            taskId = "T-CRASH-5",
            projectId = project.projectId,
            title = "Crash Recovery Objective",
            description = "Verify mid-execution recovery",
            status = PersistentTaskStatus.RUNNING,
            lastHeartbeatAt = staleTime,
            completionCriteria = TaskCompletionCriteria(requireTestsPass = true, requireReviewerApproval = false),
        )
        taskStore.upsertTask(crashedTask)
        taskStore.upsertStep(
            TaskStepEntity(
                stepId = "step-1-done",
                taskId = crashedTask.taskId,
                sequenceNumber = 1,
                title = "Step 1 Foundation",
                description = "Already done before crash",
                assignedAgentId = "agent-02",
                status = StepStatus.COMPLETED,
                outputSummary = "Foundation written",
                completedAt = staleTime,
            ),
        )
        taskStore.upsertStep(
            TaskStepEntity(
                stepId = "step-2-crashed",
                taskId = crashedTask.taskId,
                sequenceNumber = 2,
                title = "Step 2 Feature",
                description = "Crashed mid-write",
                assignedAgentId = "agent-02",
                status = StepStatus.RUNNING,
                dependencies = listOf("step-1-done"),
                targetFiles = listOf("src/Feature.kt"),
                startedAt = staleTime,
            ),
        )
        fileLockManager.tryAcquireByAgentId("src/Feature.kt", "agent-02", taskId = crashedTask.taskId, reason = "Pre-crash lock")

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                val now = System.currentTimeMillis()
                if (request.stepId == "step-1-done") step1Executions.incrementAndGet()
                if (request.stepId == "step-2-crashed") step2Executions.incrementAndGet()
                File(ws, "src/Feature.kt").apply { parentFile?.mkdirs(); writeText("// recovered feature\n") }
                return RuntimeExecutionResponse(
                    requestId = request.requestId, providerId = "p1", modelId = "m-dev",
                    startedAtMs = now, completedAtMs = now + 5, success = true,
                    outputText = "Recovered Step 2 cleanly. TESTS_PASSED",
                    modifiedFiles = listOf("src/Feature.kt"),
                    testEvidence = listOf(TestExecutionEvidence("test", now, now + 5, 0, "ok", "", TestExecutionOutcome.TESTS_PASSED, "ok")),
                )
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, true, true, true)
        }

        val engine = AutonomousSupervisorEngine(
            taskStore = taskStore,
            collabStore = collabStore,
            runtime = runtime,
            fileLockManager = fileLockManager,
            delayFn = {},
        )
        val mgr = engine.registry.register(AgentProfileEntity("agent-01", "Manager", "Lead", "Mgr", "p1", "m1", capabilities = setOf("lead", "architect")))
        val dev = engine.registry.register(AgentProfileEntity("agent-02", "Developer", "Dev", "Dev", "p1", "m2", capabilities = setOf("code", "fix")))
        val team = engine.teamManager.createTeam(project.projectId, "Bench Team 5", listOf(mgr.agentId, dev.agentId), requireIndependentReview = false, maxConcurrentAgents = 2)

        // 1. Heartbeat sweep detects stalled task, releases orphaned lock, resets Step 2 to READY
        val recovered = engine.sweepStalledAndOrphanedTasks(project.projectId, staleThresholdMs = 60_000L)
        assertEquals(1, recovered)
        assertEquals(PersistentTaskStatus.RECOVERING, taskStore.getTask(crashedTask.taskId)?.status)
        assertEquals(StepStatus.COMPLETED, taskStore.getStep("step-1-done")?.status)
        assertEquals(StepStatus.READY, taskStore.getStep("step-2-crashed")?.status)

        // 2. Unified project queue resumes and completes Step 2 without redoing Step 1
        val outcomes = engine.superviseProjectQueue(project.projectId, team)
        val elapsedMs = System.currentTimeMillis() - startedAt
        assertEquals(1, outcomes.size)
        assertEquals(PersistentTaskStatus.COMPLETED, outcomes.first().status)
        assertEquals("Completed Step 1 must NEVER be re-executed after recovery", 0, step1Executions.get())
        assertTrue("Recovered Step 2 must execute", step2Executions.get() >= 1)
        assertTrue(File(ws, "src/Feature.kt").isFile)
        println("BENCHMARK_5_RECOVERY: status=${outcomes.first().status} elapsedMs=$elapsedMs step1Replays=${step1Executions.get()} step2Runs=${step2Executions.get()}")
    }

    @Test
    fun `HierarchicalMemoryManager enforces 5 scopes, SHA256 dedup, noise floor, secret scrubbing, and access control`() {
        val store = InMemoryOrqenTaskStore()
        val mem = HierarchicalMemoryManager(store)

        // Noise floor rejection
        val noisy = mem.storeStructuredMemory(MemoryScope.PROJECT, "p1", "Short", "ok done")
        assertEquals(MemoryStoreOutcome.REJECTED_NOISE, noisy.outcome)

        // Secret scrubbing + storage
        val stored = mem.storeStructuredMemory(
            scope = MemoryScope.PROJECT,
            projectId = "p1",
            title = "Gradle Android Build Rule",
            content = "Always pass --init-script /root/.gradle/init.d/pocketdev-android.gradle and never leak sk-ant-secret12345678901234567890",
            tags = listOf("gradle", "android"),
        )
        assertEquals(MemoryStoreOutcome.STORED, stored.outcome)
        assertFalse("Secrets must be scrubbed before storage", stored.entry!!.content.contains("sk-ant-secret1234567890"))

        // SHA-256 deduplication boosts usefulness instead of duplicating
        val dup = mem.storeStructuredMemory(
            scope = MemoryScope.PROJECT,
            projectId = "p1",
            title = "Gradle Android Build Rule",
            content = "Always pass --init-script /root/.gradle/init.d/pocketdev-android.gradle and never leak sk-ant-secret12345678901234567890",
        )
        assertEquals(MemoryStoreOutcome.DEDUPLICATED, dup.outcome)
        assertTrue(dup.entry!!.usefulnessScore > 1.0)
        assertEquals(1, mem.listMemoriesForProject("p1", MemoryScope.PROJECT).size)

        // Agent-scope ownership & access control
        mem.storeStructuredMemory(
            scope = MemoryScope.AGENT,
            projectId = "p1",
            agentId = "agent-02",
            title = "Dev Private Pattern",
            content = "Prefer Compose rememberSaveable for tab selection persistence across rotation.",
        )
        val devHits = mem.retrieveRelevant("p1", "rememberSaveable Compose", requestingAgentId = "agent-02", isManagerOrLead = false)
        assertTrue(devHits.any { it.title == "Dev Private Pattern" })

        val otherWorkerHits = mem.retrieveRelevant("p1", "rememberSaveable Compose", requestingAgentId = "agent-03", isManagerOrLead = false)
        assertFalse("Agent-03 must not read Agent-02 private memory", otherWorkerHits.any { it.title == "Dev Private Pattern" })

        val managerHits = mem.retrieveRelevant("p1", "rememberSaveable Compose", requestingAgentId = "agent-01", isManagerOrLead = true)
        assertTrue("Manager/Lead can inspect team agent memory", managerHits.any { it.title == "Dev Private Pattern" })
    }
}
