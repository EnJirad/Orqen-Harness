package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.recovery.ExecutionLivenessMonitor
import com.jarves.mh.multiagent.supervisor.*
import com.jarves.mh.provider.core.ApiProtocol
import com.jarves.mh.provider.core.ProviderConfig
import com.jarves.mh.provider.core.ProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * ORQEN-Harness FINAL PRODUCTION READINESS — P0/P1 Regression Tests.
 *
 * Verifies:
 * 1. P0 Fix: Real [ProviderConfig] is resolved and delivered into [RuntimeExecutionRequest.providerConfig]
 *    both on initial execution AND after cross-provider failover (never silently null!).
 * 2. P1 Fix: Bounded exponential backoff with jitter + strict Retry-After respect.
 */
class FinalProductionReadinessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun testP0_ProviderConfigResolvedOnInitialRunAndAfterProviderFailover() = runBlocking {
        val store = InMemoryOrqenTaskStore()
        val collab = InMemoryCollaborationStore()
        val ws = tmp.newFolder("ws-p0")
        val proj = ProjectEntity("proj-p0", "P0 Project", ws.absolutePath)
        store.upsertProject(proj)

        val primaryCfg = ProviderConfig(
            id = "prov-openai",
            name = "OpenAI",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api.openai.com/v1",
            protocol = ApiProtocol.OPENAI_CHAT,
            selectedModelId = "gpt-5",
            availableModels = listOf("gpt-5"),
        )
        val backupCfg = ProviderConfig(
            id = "prov-anthropic",
            name = "Anthropic",
            type = ProviderType.ANTHROPIC,
            baseUrl = "https://api.anthropic.com/v1",
            protocol = ApiProtocol.ANTHROPIC_MESSAGES,
            selectedModelId = "claude-sonnet",
            availableModels = listOf("claude-sonnet"),
        )
        val configMap = mapOf(primaryCfg.id to primaryCfg, backupCfg.id to backupCfg)
        val observedConfigs = CopyOnWriteArrayList<Pair<String, String?>>()
        val devCalls = AtomicInteger(0)

        val runtime = object : AIExecutionRuntime {
            override suspend fun execute(request: RuntimeExecutionRequest, onProgress: suspend (String) -> Unit): RuntimeExecutionResponse {
                val aid = request.agent.config.slot.id
                observedConfigs.add(aid to request.providerConfig?.id)
                if (aid == "agent-02" && devCalls.incrementAndGet() == 1) {
                    val err429 = StructuredErrorClassifier.classify("HTTP 429 Too Many Requests Retry-After: 30", httpStatusCode = 429)
                    return RuntimeExecutionResponse(request.requestId, primaryCfg.id, "gpt-5", 0L, 1L, false, err429.message, error = err429)
                }
                val text = if (aid == "agent-03") "REVIEW_APPROVED\nTESTS_PASSED" else "Done"
                return RuntimeExecutionResponse(request.requestId, request.providerConfig?.id.orEmpty(), request.agent.config.modelId, 0L, 1L, true, text)
            }
            override fun cancel(requestId: String) = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, false, true, false)
        }

        val engine = AutonomousSupervisorEngine(
            taskStore = store,
            collabStore = collab,
            runtime = runtime,
            modelPoolProvider = {
                listOf(
                    ModelCandidate("prov-openai", "gpt-5", setOf("lead", "architect", "code", "fix", "review", "qa"), priority = 10),
                    ModelCandidate("prov-anthropic", "claude-sonnet", setOf("lead", "architect", "code", "fix", "review", "qa"), priority = 9),
                )
            },
            providerConfigResolver = { id -> configMap[id] },
            delayFn = {},
        )
        val a1 = engine.registry.register(AgentProfileEntity("agent-01", "Lead", role = "Lead", providerId = "prov-openai", modelId = "gpt-5", capabilities = setOf("lead", "architect")))
        val a2 = engine.registry.register(AgentProfileEntity("agent-02", "Dev", role = "Dev", providerId = "prov-openai", modelId = "gpt-5", capabilities = setOf("code", "fix")))
        val a3 = engine.registry.register(AgentProfileEntity("agent-03", "QA", role = "QA", providerId = "prov-anthropic", modelId = "claude-sonnet", capabilities = setOf("review", "qa", "test")))
        val team = engine.teamManager.createTeam(proj.projectId, "P0 Team", listOf(a1.agentId, a2.agentId, a3.agentId))

        val res = engine.submitAndSupervise(proj.projectId, team, "Verify real provider config propagation")
        assertEquals(PersistentTaskStatus.COMPLETED, res?.status)
        assertTrue("Every invocation must receive non-null ProviderConfig", observedConfigs.all { it.second != null })
        assertTrue("Dev must initially use prov-openai", observedConfigs.any { it.first == "agent-02" && it.second == "prov-openai" })
        assertTrue("Dev must fail over to prov-anthropic with real ProviderConfig", observedConfigs.any { it.first == "agent-02" && it.second == "prov-anthropic" })
    }

    @Test
    fun testP1_ExponentialBackoffWithJitterAndRetryAfterRespect() {
        val store = InMemoryOrqenTaskStore()
        val recovery = AutonomousRecoveryEngine(
            taskStore = store,
            healthMonitor = AgentHealthMonitor(store),
            livenessMonitor = ExecutionLivenessMonitor(store),
            fileLockManager = FileLockManager(),
            capabilityResolver = AgentCapabilityResolver(),
            modelPoolProvider = { emptyList() },
        )
        val d1 = recovery.calculateExponentialBackoffWithJitterMs(attempt = 1)
        val d3 = recovery.calculateExponentialBackoffWithJitterMs(attempt = 3)
        val d10 = recovery.calculateExponentialBackoffWithJitterMs(attempt = 10)
        val dRetryAfter = recovery.calculateExponentialBackoffWithJitterMs(attempt = 1, retryAfterMs = 45_000L)

        assertTrue("Attempt 1 should be >= 1500ms, got $d1", d1 in 1_500L..1_800L)
        assertTrue("Attempt 3 should be >= 6000ms, got $d3", d3 in 6_000L..7_200L)
        assertTrue("High attempt must stay bounded <= 60000ms, got $d10", d10 <= 60_000L)
        assertEquals(45_000L, dRetryAfter)
    }
}
