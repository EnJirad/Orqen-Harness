package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.git.GitHubSyncCoordinator
import com.jarves.mh.multiagent.model.*
import com.jarves.mh.provider.core.ApiProtocol
import com.jarves.mh.provider.core.ProviderConfig
import com.jarves.mh.provider.core.ProviderType
import com.jarves.mh.provider.manager.ProviderOrchestrator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Part05IntegrationOrchestrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `orchestrator supports shared provider ID across multiple agents with different models`() {
        val orchestrator = ProviderOrchestrator()
        val sharedAccount = ProviderConfig(
            id = "account-id-1",
            name = "Anthropic ID #1",
            type = ProviderType.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            protocol = ApiProtocol.ANTHROPIC_MESSAGES,
            selectedModelId = "claude-sonnet-4-6",
            availableModels = listOf("claude-sonnet-4-6", "claude-3-5-haiku-20241022", "claude-opus-4-6"),
        )

        val role = AgentRole(AgentRoleType.SOFTWARE_ENGINEER, "Dev", "Code", listOf("Code"))
        val agent1 = AgentState(
            AgentConfig(slot = AgentSlot.AGENT_1, name = "Alpha", role = role, providerId = "account-id-1", modelId = "claude-sonnet-4-6")
        )
        val agent2 = AgentState(
            AgentConfig(slot = AgentSlot.AGENT_2, name = "Beta", role = role, providerId = "account-id-1", modelId = "claude-3-5-haiku-20241022")
        )

        val routes = orchestrator.resolveAllRoutes(listOf(agent1, agent2), listOf(sharedAccount))
        assertEquals(2, routes.size)

        val route1 = routes[AgentSlot.AGENT_1]!!
        val route2 = routes[AgentSlot.AGENT_2]!!

        assertEquals("account-id-1", route1.providerConfig.id)
        assertEquals("account-id-1", route2.providerConfig.id)
        assertEquals("claude-sonnet-4-6", route1.modelId)
        assertEquals("claude-3-5-haiku-20241022", route2.modelId)
        assertTrue(route1.isSharedProviderId)
        assertTrue(route2.isSharedProviderId)
        assertEquals(listOf("Beta"), route1.sharedWithAgentNames)
        assertEquals(listOf("Alpha"), route2.sharedWithAgentNames)
    }

    @Test
    fun `orchestrator supports separate provider IDs per agent`() {
        val orchestrator = ProviderOrchestrator()
        val id1 = ProviderConfig(id = "id-1", name = "OpenAI #1", type = ProviderType.OPENAI_COMPATIBLE, baseUrl = "https://api.openai.com/v1", selectedModelId = "gpt-4o", availableModels = listOf("gpt-4o"))
        val id2 = ProviderConfig(id = "id-2", name = "OpenAI #2", type = ProviderType.OPENAI_COMPATIBLE, baseUrl = "https://api.openai.com/v1", selectedModelId = "gpt-4o-mini", availableModels = listOf("gpt-4o-mini"))

        val role = AgentRole(AgentRoleType.SOFTWARE_ENGINEER, "Dev", "Code", listOf("Code"))
        val agent1 = AgentState(AgentConfig(slot = AgentSlot.AGENT_1, name = "A1", role = role, providerId = "id-1", modelId = "gpt-4o"))
        val agent2 = AgentState(AgentConfig(slot = AgentSlot.AGENT_2, name = "A2", role = role, providerId = "id-2", modelId = "gpt-4o-mini"))

        val routes = orchestrator.resolveAllRoutes(listOf(agent1, agent2), listOf(id1, id2))
        assertFalse(routes[AgentSlot.AGENT_1]!!.isSharedProviderId)
        assertFalse(routes[AgentSlot.AGENT_2]!!.isSharedProviderId)
        assertEquals("id-1", routes[AgentSlot.AGENT_1]!!.providerConfig.id)
        assertEquals("id-2", routes[AgentSlot.AGENT_2]!!.providerConfig.id)
    }

    @Test
    fun `smart fallback switches to alternate model on same ID then falls back to another provider`() {
        val orchestrator = ProviderOrchestrator()
        val p1 = ProviderConfig(
            id = "p1",
            name = "Provider 1",
            type = ProviderType.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            selectedModelId = "model-a",
            availableModels = listOf("model-a", "model-b"),
        )
        val p2 = ProviderConfig(
            id = "p2",
            name = "Provider 2",
            type = ProviderType.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com",
            selectedModelId = "gemini-2.5-flash",
            availableModels = listOf("gemini-2.5-flash"),
        )

        val role = AgentRole(AgentRoleType.SOFTWARE_ENGINEER, "Dev", "Code", listOf("Code"))
        val agent = AgentState(AgentConfig(slot = AgentSlot.AGENT_1, name = "A1", role = role, providerId = "p1", modelId = "model-a"))
        val primaryRoute = orchestrator.resolveRoute(agent.config, listOf(agent), listOf(p1, p2))!!

        // 1st fallback: switches to model-b on SAME provider ID p1
        val fb1 = orchestrator.resolveFallbackRoute(primaryRoute, listOf(p1, p2), setOf("model-a"))
        assertNotNull(fb1)
        assertEquals("p1", fb1!!.providerConfig.id)
        assertEquals("model-b", fb1.modelId)

        // 2nd fallback: both p1 models failed -> switches to p2
        val fb2 = orchestrator.resolveFallbackRoute(fb1, listOf(p1, p2), setOf("model-a", "model-b"))
        assertNotNull(fb2)
        assertEquals("p2", fb2!!.providerConfig.id)
        assertEquals("gemini-2.5-flash", fb2.modelId)
    }

    @Test
    fun `github sync coordinator handles non-git workspace gracefully`() = runBlocking {
        val coordinator = GitHubSyncCoordinator(tempFolder.root)
        val status = coordinator.getSyncStatus()
        assertFalse(status.isConnected)
        assertEquals("none", status.activeBranch)
        assertEquals(0, status.pendingCommitsCount)
    }
}
