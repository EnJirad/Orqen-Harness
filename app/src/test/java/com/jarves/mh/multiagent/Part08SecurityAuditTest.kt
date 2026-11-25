package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.git.HubEnvelope
import com.jarves.mh.multiagent.git.HubExchangeService
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Part08SecurityAuditTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `file lock manager rejects path traversal and blank paths`() {
        val lockManager = FileLockManager()

        val traversalRes = lockManager.tryAcquire("../../etc/passwd", AgentSlot.AGENT_2, "t1", "Malicious attempt")
        assertTrue(traversalRes is LockAcquisitionResult.Error)

        val windowsTraversal = lockManager.tryAcquire("app\\..\\..\\secret.key", AgentSlot.AGENT_2, "t1", "Win traversal")
        assertTrue(windowsTraversal is LockAcquisitionResult.Error)

        val blankRes = lockManager.tryAcquire("   ", AgentSlot.AGENT_2, "t1", "Blank")
        assertTrue(blankRes is LockAcquisitionResult.Error)

        assertTrue(lockManager.getActiveLocks().isEmpty())
    }

    @Test
    fun `hub exchange service sanitizes path traversal attempts in envelope IDs and slots`() {
        val service = HubExchangeService(tempFolder.root)
        val malicious = HubEnvelope(
            id = "../../escape",
            fromSlot = "../agent-01",
            fromName = "Tester",
            toSlot = "../../agent-02",
            subject = "Traversal test",
            body = "Should remain strictly inside .agent/communication",
        )

        val written = service.dispatchMessage(malicious)
        val hubCanonical = tempFolder.root.canonicalPath
        assertTrue(
            "Written file must remain strictly inside workspace root",
            written.canonicalPath.startsWith(hubCanonical),
        )
        assertFalse(written.name.contains(".."))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `team group strictly enforces max 4 agents security invariant`() {
        val role = AgentRole(AgentRoleType.SOFTWARE_ENGINEER, "Dev", "Code", listOf("Code"))
        val fiveAgents = listOf(
            AgentConfig(AgentSlot.AGENT_1, "A1", role),
            AgentConfig(AgentSlot.AGENT_2, "A2", role),
            AgentConfig(AgentSlot.AGENT_3, "A3", role),
            AgentConfig(AgentSlot.AGENT_4, "A4", role),
            AgentConfig(AgentSlot.AGENT_1, "A5", role),
        )
        TeamGroup(id = "team-overflow", name = "Overflow", workspacePath = "/workspace", agents = fiveAgents)
    }
}
