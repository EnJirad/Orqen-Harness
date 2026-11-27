package com.jarves.mh.skills

import com.jarves.mh.multiagent.model.ActivityType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.ui.theme.AppThemeMode
import org.json.JSONObject

/**
 * Structured action emitted by AI inside a hidden <orqen_action>{...}</orqen_action> tag
 * so the Owner can configure ORQEN directly via natural chat.
 */
data class AppControlAction(
    val action: String,
    val value: String = "",
    val target: String = "",
    val reason: String = "",
    val requiresApproval: Boolean = false,
)

data class ParsedAppActions(
    val cleanReplyText: String,
    val safeActions: List<AppControlAction>,
    val approvalActions: List<AppControlAction>,
)

object AppSettingsActionDispatcher {

    private val ACTION_TAG_REGEX = Regex("""<orqen_action>\s*(\{[\s\S]*?\})\s*</orqen_action>""", RegexOption.IGNORE_CASE)

    private val SAFE_ACTIONS = setOf(
        "set_theme",
        "set_language",
        "set_owner_name",
        "set_auto_skills",
        "toggle_skill",
        "set_auto_push_git",
        "toggle_agent",
        "set_agent_model",
    )

    private val SENSITIVE_ACTIONS = setOf(
        "clear_chat",
        "git_push",
        "delete_project",
        "stop_team",
    )

    fun buildReadOnlySettingsContext(
        themeMode: AppThemeMode,
        languageCode: String,
        ownerName: String,
        autoSkillsEnabled: Boolean,
        skillCount: Int,
        autoPushGitEnabled: Boolean,
        agents: List<AgentState>,
    ): String = buildString {
        appendLine("<app_settings_context>")
        appendLine("Theme: ${themeMode.name} (options: DARK, LIGHT, SYSTEM)")
        appendLine("Language: $languageCode (options: th, en, system)")
        appendLine("Owner Name: ${ownerName.ifBlank { "default" }}")
        appendLine("Auto-Skill Mode: $autoSkillsEnabled ($skillCount skills in catalog)")
        appendLine("Auto-Push Git on Complete: $autoPushGitEnabled")
        appendLine("Agents: " + agents.joinToString("; ") { a ->
            "${a.config.slot.id}=${a.config.name}(${a.config.role.title}, enabled=${a.config.isEnabled}, model=${a.config.modelId.ifBlank { "default" }})"
        })
        appendLine("APP CONTROL INSTRUCTIONS:")
        appendLine("When the Owner asks you to change an app setting directly in chat, append a hidden tag at the end of your reply:")
        appendLine("<orqen_action>{\"action\":\"<name>\",\"target\":\"<optional>\",\"value\":\"<val>\",\"reason\":\"<short reason>\"}</orqen_action>")
        appendLine("Safe actions (applied immediately): set_theme (DARK|LIGHT|SYSTEM), set_language (th|en|system), set_owner_name, set_auto_skills (true|false), toggle_skill (target=skillId, value=true|false), set_auto_push_git (true|false), toggle_agent (target=agent-01..agent-05, value=true|false), set_agent_model (target=agent-01..agent-05, value=modelId).")
        appendLine("Sensitive actions (will ask Owner approval before running): clear_chat, git_push, delete_project, stop_team.")
        appendLine("Only emit <orqen_action> when the Owner explicitly asks to change a setting or perform that action.")
        appendLine("</app_settings_context>")
    }

    fun parseAndStripActions(rawReply: String): ParsedAppActions {
        val safe = mutableListOf<AppControlAction>()
        val approval = mutableListOf<AppControlAction>()

        ACTION_TAG_REGEX.findAll(rawReply).forEach { match ->
            runCatching {
                val obj = JSONObject(match.groupValues[1])
                val name = obj.optString("action", "").trim().lowercase()
                val value = obj.optString("value", "").trim()
                val target = obj.optString("target", "").trim()
                val reason = obj.optString("reason", "").trim()
                when {
                    name in SAFE_ACTIONS -> safe.add(
                        AppControlAction(name, value, target, reason, requiresApproval = false),
                    )
                    name in SENSITIVE_ACTIONS -> approval.add(
                        AppControlAction(name, value, target, reason, requiresApproval = true),
                    )
                }
            }
        }

        val cleaned = ACTION_TAG_REGEX.replace(rawReply, "").trim()
        return ParsedAppActions(
            cleanReplyText = cleaned,
            safeActions = safe,
            approvalActions = approval,
        )
    }

    fun describeActionForUser(action: AppControlAction): String = when (action.action) {
        "set_theme" -> "เปลี่ยนธีมเป็น ${action.value}"
        "set_language" -> "เปลี่ยนภาษาเป็น ${action.value}"
        "set_owner_name" -> "เปลี่ยนชื่อเรียกเป็น ${action.value}"
        "set_auto_skills" -> if (action.value.equals("true", true)) "เปิดโหมดเลือกสกิลอัตโนมัติ" else "ปิดโหมดเลือกสกิลอัตโนมัติ"
        "toggle_skill" -> "ปรับสถานะสกิล ${action.target} -> ${action.value}"
        "set_auto_push_git" -> if (action.value.equals("true", true)) "เปิด Push ขึ้น Git อัตโนมัติ" else "ปิด Push ขึ้น Git อัตโนมัติ"
        "toggle_agent" -> "ปรับสถานะ ${action.target} -> ${if (action.value.equals("true", true)) "เปิด" else "ปิด"}"
        "set_agent_model" -> "เปลี่ยนโมเดล ${action.target} เป็น ${action.value}"
        "clear_chat" -> "ล้างประวัติแชททั้งหมดของโปรเจกต์นี้"
        "git_push" -> "Push โค้ดขึ้น Git Repository"
        "delete_project" -> "ลบโปรเจกต์นี้ถาวร"
        "stop_team" -> "หยุดการทำงานของทีม AI ทันที"
        else -> "${action.action}: ${action.value}"
    }
}

/**
 * Generates lively, human-friendly status labels (strictly zero emojis!)
 * combining the real operational state with a playful subtitle: "สถานะจริง | ลูกเล่น"
 */
object PlayfulAgentStatus {

    fun format(
        activity: ActivityType,
        isWorking: Boolean,
        isTyping: Boolean,
        isEnabled: Boolean,
        slot: AgentSlot,
    ): String {
        val seed = (slot.ordinal + (System.currentTimeMillis() / 45_000L).toInt()) and 0x7FFF
        if (!isEnabled) {
            return "ปิดใช้งาน | พักร้อนอยู่ครับ"
        }
        if (isTyping) {
            val jokes = listOf("กำลังเค้นสมองตอบอยู่ค้าบ", "กำลังเรียบเรียงคำตอบให้เป๊ะ", "กำลังคิดคำตอบแบบตั้งใจสุดๆ")
            return "กำลังคิด | ${jokes[seed % jokes.size]}"
        }
        return when (activity) {
            ActivityType.ERROR -> {
                val jokes = listOf("หนีไปเข้าห้องน้ำ กำลังตามกลับมา", "สะดุดสายไฟ กำลังสลับตัวสำรอง", "ขอพักหายใจแป๊บ กำลังกู้คืน")
                "กำลังกู้คืน | ${jokes[seed % jokes.size]}"
            }
            ActivityType.WRITING_CODE -> {
                val jokes = listOf("เขียนโค้ดอยู่ค้าบ", "รัวคีย์บอร์ดไฟแลบ", "กำลังปั้นฟีเจอร์ให้อย่างตั้งใจ")
                "กำลังทำงาน | ${jokes[seed % jokes.size]}"
            }
            ActivityType.FIXING_BUG -> {
                val jokes = listOf("กำลังไล่ตีบั๊กอยู่ครับ", "ส่องกล้องหาบั๊กอยู่", "ซ่อมจุดพังให้กลับมาเนียน")
                "กำลังแก้บั๊ก | ${jokes[seed % jokes.size]}"
            }
            ActivityType.TESTING, ActivityType.REVIEWING -> {
                val jokes = listOf("กำลังสวมแว่นตรวจงาน", "เทสทุกปุ่มให้ชัวร์ก่อนส่ง", "กำลังตรวจความเรียบร้อยครับ")
                "กำลังตรวจสอบ | ${jokes[seed % jokes.size]}"
            }
            ActivityType.BUILDING_APK -> {
                val jokes = listOf("กำลังแพ็ค APK ให้อยู่ค้าบ", "เร่งเตาบิ้ว APK เต็มกำลัง")
                "กำลังบิ้ว APK | ${jokes[seed % jokes.size]}"
            }
            ActivityType.ANALYZING, ActivityType.PLANNING, ActivityType.READING_FILES -> {
                val jokes = listOf("กำลังอ่านโค้ดและวางแผนครับ", "กำลังสำรวจโปรเจกต์อยู่")
                "กำลังวิเคราะห์ | ${jokes[seed % jokes.size]}"
            }
            ActivityType.PAUSED -> "พักชั่วคราว | นั่งจิบกาแฟรอคำสั่ง"
            ActivityType.COMPLETED -> "เสร็จแล้ว | นั่งยิ้มรอดูฟีดแบ็ก"
            ActivityType.WAITING -> {
                if (isWorking) {
                    "กำลังทำงาน | กำลังประสานงานกับทีม"
                } else {
                    val idleJokes = listOf("นั่งรอ..", "ว่างเกินไม่มีอะไรทำ", "แอบงีบแป๊บ เรียกได้เลย", "พร้อมลุยตลอดเวลา")
                    "ปกติ | ${idleJokes[seed % idleJokes.size]}"
                }
            }
            else -> if (isWorking) "กำลังทำงาน | ลุยงานให้อยู่ครับ" else "ปกติ | นั่งรอ.."
        }
    }
}

/**
 * Estimates realistic remaining time (ETA) for chat replies and multi-step coding tasks,
 * dynamically adjusting as steps complete or new work is added.
 */
object TaskEtaEstimator {

    fun estimateChatReplyLabel(isBusyWithCode: Boolean): String {
        return if (isBusyWithCode) "ประมาณ ~20-45 วินาที" else "ประมาณ ~10-25 วินาที"
    }

    fun estimateWorkingAgentEta(
        activity: ActivityType,
        pendingTasksCount: Int,
        elapsedMs: Long,
        taskTitle: String,
    ): String {
        val lower = taskTitle.lowercase()
        val baseMinutes = when {
            activity == ActivityType.BUILDING_APK -> 6
            activity == ActivityType.TESTING || activity == ActivityType.REVIEWING -> 4
            lower.contains("part 2") || lower.contains("integration") || lower.contains("refactor") -> 18
            lower.contains("part 1") || lower.contains("core") || lower.contains("full") -> 25
            else -> 10
        }
        val extraFromQueue = (pendingTasksCount.coerceAtLeast(0)) * 8
        val elapsedMin = (elapsedMs / 60_000L).toInt().coerceAtLeast(0)
        val remainingMin = (baseMinutes + extraFromQueue - elapsedMin).coerceAtLeast(2)
        val upperMin = (remainingMin * 1.4).toInt().coerceAtLeast(remainingMin + 2)

        return if (upperMin >= 60) {
            val lowH = remainingMin / 60
            val lowM = remainingMin % 60
            val highH = upperMin / 60
            val highM = upperMin % 60
            val lowStr = if (lowH > 0) "${lowH} ชม. ${lowM} นาที" else "${lowM} นาที"
            val highStr = if (highH > 0) "${highH} ชม. ${highM} นาที" else "${highM} นาที"
            "คาดว่าจะเสร็จใน $lowStr - $highStr"
        } else {
            "คาดว่าจะเสร็จในประมาณ $remainingMin-$upperMin นาที"
        }
    }
}
