package com.jarves.mh.multiagent.collab

import com.jarves.mh.multiagent.model.ActivityType
import com.jarves.mh.multiagent.model.AgentActivityState
import com.jarves.mh.multiagent.model.AgentConfig
import com.jarves.mh.multiagent.model.AgentRole
import com.jarves.mh.multiagent.model.AgentRoleType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamGroupChatPriorityTest {

    private fun makeAgent(
        slot: AgentSlot,
        name: String = slot.defaultName,
        roleType: AgentRoleType = if (slot == AgentSlot.AGENT_1) AgentRoleType.TEAM_LEADER else AgentRoleType.SOFTWARE_ENGINEER,
        activity: ActivityType = ActivityType.WAITING,
        isWorking: Boolean = activity !in setOf(ActivityType.WAITING, ActivityType.PAUSED, ActivityType.COMPLETED, ActivityType.ERROR),
        enabled: Boolean = true,
    ): AgentState = AgentState(
        config = AgentConfig(
            slot = slot,
            name = name,
            role = AgentRole(
                type = roleType,
                systemInstructions = "Test",
                responsibilities = listOf("Test"),
            ),
            isEnabled = enabled,
        ),
        activityState = AgentActivityState(activity = activity),
        isWorking = isWorking,
    )

    @Test
    fun `general message without mention routes to Supervisor when Supervisor is idle`() {
        val agents = listOf(
            makeAgent(AgentSlot.AGENT_1, "Boss", AgentRoleType.TEAM_LEADER, ActivityType.WAITING),
            makeAgent(AgentSlot.AGENT_2, "Dev", AgentRoleType.SOFTWARE_ENGINEER, ActivityType.WAITING),
        )
        val decision = TeamGroupChatRouter.selectResponder("โปรเจกต์ไปถึงไหนแล้ว?", null, agents)
        assertNotNull(decision)
        assertEquals(AgentSlot.AGENT_1, decision!!.responder.config.slot)
        assertFalse(decision.wasExplicitMention)
        assertFalse(decision.shouldInterruptAndResume)
    }

    @Test
    fun `general message without mention routes to idle agent when Supervisor is busy`() {
        val agents = listOf(
            makeAgent(AgentSlot.AGENT_1, "Boss", AgentRoleType.TEAM_LEADER, ActivityType.PLANNING),
            makeAgent(AgentSlot.AGENT_2, "Dev", AgentRoleType.SOFTWARE_ENGINEER, ActivityType.WRITING_CODE),
            makeAgent(AgentSlot.AGENT_3, "QA", AgentRoleType.TESTER, ActivityType.WAITING),
        )
        val decision = TeamGroupChatRouter.selectResponder("ตอนนี้สถานะเป็นยังไงบ้าง", null, agents)
        assertNotNull(decision)
        assertEquals(AgentSlot.AGENT_3, decision!!.responder.config.slot)
        assertFalse(decision.wasExplicitMention)
        assertFalse(decision.shouldInterruptAndResume)
    }

    @Test
    fun `explicit mention of idle agent by custom name routes directly without interrupt`() {
        val agents = listOf(
            makeAgent(AgentSlot.AGENT_1, "Boss", AgentRoleType.TEAM_LEADER, ActivityType.WAITING),
            makeAgent(AgentSlot.AGENT_2, "Luna", AgentRoleType.SOFTWARE_ENGINEER, ActivityType.WAITING),
        )
        val decision = TeamGroupChatRouter.selectResponder("@Luna ช่วยอธิบายโค้ดหน้า login หน่อย", null, agents)
        assertNotNull(decision)
        assertEquals(AgentSlot.AGENT_2, decision!!.responder.config.slot)
        assertTrue(decision.wasExplicitMention)
        assertFalse(decision.shouldInterruptAndResume)
    }

    @Test
    fun `explicit mention of busy agent triggers interrupt-and-resume`() {
        val agents = listOf(
            makeAgent(AgentSlot.AGENT_1, "Boss", AgentRoleType.TEAM_LEADER, ActivityType.WAITING),
            makeAgent(AgentSlot.AGENT_2, "Luna", AgentRoleType.SOFTWARE_ENGINEER, ActivityType.WRITING_CODE),
            makeAgent(AgentSlot.AGENT_3, "QA", AgentRoleType.TESTER, ActivityType.WAITING),
        )
        val decision = TeamGroupChatRouter.selectResponder("@Luna หยุดแป๊บ ขอดูสถานะไฟล์หน่อย", null, agents)
        assertNotNull(decision)
        assertEquals(AgentSlot.AGENT_2, decision!!.responder.config.slot)
        assertTrue(decision.wasExplicitMention)
        assertTrue(decision.shouldInterruptAndResume)
    }

    @Test
    fun `explicit targetSlot chip overrides text and triggers interrupt when busy`() {
        val agents = listOf(
            makeAgent(AgentSlot.AGENT_1, "Boss", AgentRoleType.TEAM_LEADER, ActivityType.WAITING),
            makeAgent(AgentSlot.AGENT_2, "Agent 02", AgentRoleType.SOFTWARE_ENGINEER, ActivityType.FIXING_BUG),
        )
        val decision = TeamGroupChatRouter.selectResponder("ขอดูความคืบหน้าหน่อย", AgentSlot.AGENT_2, agents)
        assertNotNull(decision)
        assertEquals(AgentSlot.AGENT_2, decision!!.responder.config.slot)
        assertTrue(decision.wasExplicitMention)
        assertTrue(decision.shouldInterruptAndResume)
    }

    @Test
    fun `mention parser matches multi-word defaultName cleanly`() {
        val agents = listOf(
            makeAgent(AgentSlot.AGENT_1, "Agent 01", AgentRoleType.TEAM_LEADER, ActivityType.WAITING),
            makeAgent(AgentSlot.AGENT_2, "Agent 02", AgentRoleType.SOFTWARE_ENGINEER, ActivityType.WAITING),
        )
        val resolved = TeamGroupChatRouter.resolveMentionedSlot("เฮ้ @Agent 02 ดูอันนี้หน่อย", null, agents)
        assertEquals(AgentSlot.AGENT_2, resolved)
    }

    @Test
    fun `all agents busy without mention falls back to Supervisor without interrupting work`() {
        val agents = listOf(
            makeAgent(AgentSlot.AGENT_1, "Boss", AgentRoleType.TEAM_LEADER, ActivityType.ANALYZING),
            makeAgent(AgentSlot.AGENT_2, "Dev", AgentRoleType.SOFTWARE_ENGINEER, ActivityType.WRITING_CODE),
        )
        val decision = TeamGroupChatRouter.selectResponder("สรุปความคืบหน้าหน่อย", null, agents)
        assertNotNull(decision)
        assertEquals(AgentSlot.AGENT_1, decision!!.responder.config.slot)
        assertFalse(decision.wasExplicitMention)
        assertFalse(decision.shouldInterruptAndResume)
    }

    @Test
    fun `empty agent list returns null safely`() {
        assertNull(TeamGroupChatRouter.selectResponder("hello", null, emptyList()))
    }

    @Test
    fun `continue confirmation detects Thai and English affirmatives and rejects negatives`() {
        assertTrue(TeamGroupChatRouter.isContinueConfirmation("โอเค ทำต่อเลย"))
        assertTrue(TeamGroupChatRouter.isContinueConfirmation("ทำต่อครับ"))
        assertTrue(TeamGroupChatRouter.isContinueConfirmation("ok go ahead"))
        assertTrue(TeamGroupChatRouter.isContinueConfirmation("Yes, continue"))
        assertTrue(TeamGroupChatRouter.isContinueConfirmation("ลุยเลย"))

        assertFalse(TeamGroupChatRouter.isContinueConfirmation("ยังก่อน เดี๋ยวค่อยทำ"))
        assertFalse(TeamGroupChatRouter.isContinueConfirmation("ไม่ต้องทำ หยุดก่อน"))
        assertFalse(TeamGroupChatRouter.isContinueConfirmation("no cancel"))
        assertFalse(TeamGroupChatRouter.isContinueConfirmation("ขอดูโค้ดก่อน"))
    }
}
