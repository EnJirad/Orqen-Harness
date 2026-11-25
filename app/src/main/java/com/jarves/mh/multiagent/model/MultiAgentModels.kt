package com.jarves.mh.multiagent.model

import com.jarves.mh.R
import com.jarves.mh.provider.core.EstimatedUsage
import com.jarves.mh.provider.core.LocalUsage
import com.jarves.mh.provider.core.OfficialQuota

/**
 * ORQEN Multi-Agent Core: strictly supports up to 4 agents per workspace.
 */
enum class AgentSlot(val id: String, val defaultName: String, val slotNumber: Int) {
    AGENT_1("agent-01", "Agent 01", 1),
    AGENT_2("agent-02", "Agent 02", 2),
    AGENT_3("agent-03", "Agent 03", 3),
    AGENT_4("agent-04", "Agent 04", 4);

    companion object {
        const val MAX_AGENTS = 4

        fun fromSlotNumber(num: Int): AgentSlot? = entries.firstOrNull { it.slotNumber == num }
        fun fromId(id: String): AgentSlot? = entries.firstOrNull { it.id == id }
    }
}

enum class AgentRoleType(val defaultTitle: String, val titleRes: Int) {
    TEAM_LEADER("Team Leader / Architect / QA", R.string.role_team_leader),
    SOFTWARE_ENGINEER("Software Engineer", R.string.role_software_engineer),
    SPECIALIST("Specialist / Backend Developer", R.string.role_specialist),
    TESTER("Independent Tester / Reviewer", R.string.role_tester),
    FRONTEND_ENGINEER("Frontend Engineer", R.string.role_frontend_engineer),
    BACKEND_ENGINEER("Backend Engineer", R.string.role_backend_engineer),
    SECURITY_ENGINEER("Security Engineer", R.string.role_security_engineer),
    DEVOPS_ENGINEER("DevOps Engineer", R.string.role_devops_engineer),
    CUSTOM("Custom Role", R.string.role_custom),
}

data class AgentRole(
    val type: AgentRoleType,
    val title: String = type.defaultTitle,
    val systemInstructions: String,
    val responsibilities: List<String>,
    val allowedTools: Set<String> = setOf("read_file", "write_file", "run_command"),
    val allowedFilePatterns: List<String> = emptyList(),
)

data class AgentConfig(
    val slot: AgentSlot,
    val name: String = slot.defaultName,
    val role: AgentRole,
    val providerId: String = "preset-antigravity",
    val accountId: String = "account-01",
    val modelId: String = "gemini-3.8-flash-high",
    val reasoningEffort: String = "high",
    val priority: Int = 1,
    val canAssignToSlots: List<AgentSlot> = emptyList(),
    val isEnabled: Boolean = true,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 8192,
    val customPrompt: String = "",
    val personality: String = "", // blank = Auto distinct employee persona per slot
) {
    fun effectivePersonality(): String = personality.trim().ifBlank { defaultEmployeePersonality(slot) }

    companion object {
        fun defaultEmployeePersonality(slot: AgentSlot): String = when (slot) {
            AgentSlot.AGENT_1 -> "สุขุม รอบคอบ คิดเป็นระบบ สั่งงานชัดเจน และคอยรายงานสรุปให้เจ้าของบริษัทอย่างมืออาชีพ"
            AgentSlot.AGENT_2 -> "กระตือรือร้น เขียนโค้ดเนี้ยบ รวดเร็ว ชอบแก้ปัญหาเชิงเทคนิคและอัปเดตงานตรงไปตรงมา"
            AgentSlot.AGENT_3 -> "ละเอียดลออ รักความสะอาดของโครงสร้างโค้ด เน้นความปลอดภัยและประสิทธิภาพสูงสุด"
            AgentSlot.AGENT_4 -> "ช่างสังเกต จริงจังเรื่องคุณภาพ จับบั๊กเก่ง และตรวจสอบทุกจุดอย่างเข้มงวดก่อนส่งมอบ"
        }
    }
}

enum class ActivityType(val iconName: String, val labelRes: Int) {
    ANALYZING("Search", R.string.activity_analyzing),
    PLANNING("Clipboard", R.string.activity_planning),
    READING_FILES("FileText", R.string.activity_reading_files),
    WRITING_CODE("Pencil", R.string.activity_writing_code),
    FIXING_BUG("Wrench", R.string.activity_fixing_bug),
    TESTING("Flask", R.string.activity_testing),
    REVIEWING("Eye", R.string.activity_reviewing),
    BUILDING_APK("Smartphone", R.string.activity_building_apk),
    INSTALLING_DEPS("Package", R.string.activity_installing_deps),
    COMMITTING("GitCommit", R.string.activity_committing),
    PUSHING("Upload", R.string.activity_pushing),
    MERGING("GitMerge", R.string.activity_merging),
    WAITING("Clock", R.string.activity_waiting),
    PAUSED("Pause", R.string.activity_paused),
    COMPLETED("CheckCircle", R.string.activity_completed),
    ERROR("AlertTriangle", R.string.activity_error),
}

data class AgentActivityState(
    val activity: ActivityType = ActivityType.WAITING,
    val currentTask: String = "",
    val currentFile: String = "",
    val lastAction: String = "Idle",
    val startedAtElapsedMs: Long = 0L,
    val errorDetail: String? = null,
)

data class AgentState(
    val config: AgentConfig,
    val activityState: AgentActivityState = AgentActivityState(),
    val lifecycleState: AgentLifecycleState = AgentLifecycleState.IDLE,
    val officialQuota: OfficialQuota? = null,
    val estimatedUsage: EstimatedUsage? = null,
    val localUsage: LocalUsage = LocalUsage(),
    val isWorking: Boolean = false,
    val isPaused: Boolean = false,
)

data class TeamGroup(
    val id: String,
    val name: String,
    val workspacePath: String,
    val agents: List<AgentConfig>, // Up to 4
    val currentPartNumber: Int = 1,
    val isRunning: Boolean = false,
) {
    init {
        require(agents.size <= AgentSlot.MAX_AGENTS) { "ORQEN strictly supports maximum of ${AgentSlot.MAX_AGENTS} agents" }
    }
}
