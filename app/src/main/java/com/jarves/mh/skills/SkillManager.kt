package com.jarves.mh.skills

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages built-in and user-created AI Skills with automatic project-aware skill selection
 * for both AI Team and Solo Studio.
 */
class SkillManager(context: Context? = null) {

    private val prefs = context?.getSharedPreferences("orqen_skills_prefs", Context.MODE_PRIVATE)

    private val _autoSelectEnabled = MutableStateFlow(prefs?.getBoolean(KEY_AUTO_SELECT, true) ?: true)
    val autoSelectEnabled: StateFlow<Boolean> = _autoSelectEnabled.asStateFlow()

    private val _skills = MutableStateFlow(loadSkills())
    val skills: StateFlow<List<AgentSkill>> = _skills.asStateFlow()

    fun setAutoSelectEnabled(enabled: Boolean) {
        _autoSelectEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_AUTO_SELECT, enabled)?.apply()
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        val updated = _skills.value.map { if (it.id == skillId) it.copy(isEnabled = enabled) else it }
        _skills.value = updated
        persistSkills(updated)
    }

    fun saveCustomSkill(
        name: String,
        description: String,
        category: SkillCategory,
        tags: List<String>,
        instructions: String,
        existingId: String? = null,
    ): AgentSkill {
        val cleanName = name.trim().ifBlank { "Custom Skill" }
        val cleanTags = tags.map { it.trim().lowercase() }.filter { it.isNotBlank() }
            .ifEmpty { cleanName.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 } }
        val skill = AgentSkill(
            id = existingId ?: "custom-${System.currentTimeMillis()}",
            name = cleanName,
            description = description.trim().ifBlank { "Custom AI skill" },
            category = category,
            instructions = instructions.trim(),
            tags = cleanTags,
            isBuiltIn = false,
            isEnabled = true,
        )
        val current = _skills.value.toMutableList()
        val idx = current.indexOfFirst { it.id == skill.id }
        if (idx >= 0) current[idx] = skill else current.add(0, skill)
        _skills.value = current
        persistSkills(current)
        return skill
    }

    fun importSkillFromMarkdown(rawMarkdown: String, fallbackName: String = "Imported Skill"): AgentSkill {
        val parsed = AutoSkillProjectAnalyzer.parseMarkdownSkill(rawMarkdown, fallbackName)
        val updated = listOf(parsed) + _skills.value
        _skills.value = updated
        persistSkills(updated)
        return parsed
    }

    fun deleteCustomSkill(skillId: String) {
        val updated = _skills.value.filterNot { it.id == skillId && !it.isBuiltIn }
        _skills.value = updated
        persistSkills(updated)
    }

    /**
     * Resolves active skills for [workspaceDir]:
     * - When Auto-Skill Mode is ON: analyzes the project workspace & prompt and automatically picks the best matching skills.
     * - When Auto-Skill Mode is OFF: uses only the skills explicitly toggled ON by the user (plus any workspace-local SKILL.md).
     */
    fun resolveSkillsForWorkspace(
        workspaceDir: File?,
        userPrompt: String = "",
        maxSkills: Int = 4,
    ): ResolvedSkillsResult {
        val currentSkills = _skills.value
        if (_autoSelectEnabled.value) {
            return AutoSkillProjectAnalyzer.selectForProject(
                allSkills = currentSkills,
                workspaceDir = workspaceDir,
                userPrompt = userPrompt,
                maxSkills = maxSkills,
            )
        }
        val localSkills = AutoSkillProjectAnalyzer.discoverWorkspaceLocalSkills(workspaceDir)
        val manual = (localSkills + currentSkills.filter { it.isEnabled })
            .distinctBy { it.id }
            .take(maxSkills)
        return ResolvedSkillsResult(
            skills = manual,
            detectedSignals = emptyList(),
            isAutoMode = false,
        )
    }

    /**
     * Builds a compact prompt block injected into AI Team and Solo Studio requests.
     */
    fun buildSkillsPromptSection(workspaceDir: File?, userPrompt: String = ""): String {
        val resolved = resolveSkillsForWorkspace(workspaceDir, userPrompt)
        if (resolved.skills.isEmpty()) return ""
        return buildString {
            appendLine("<active_skills mode=\"${if (resolved.isAutoMode) "auto" else "manual"}\">")
            if (resolved.detectedSignals.isNotEmpty()) {
                appendLine("Detected project signals: ${resolved.detectedSignals.joinToString(", ")}")
            }
            resolved.skills.forEach { skill ->
                appendLine("## Skill: ${skill.name} (${skill.category.label})")
                appendLine(skill.instructions.trim().take(900))
                appendLine()
            }
            appendLine("</active_skills>")
        }
    }

    private fun loadSkills(): List<AgentSkill> {
        val raw = prefs?.getString(KEY_SKILLS_JSON, null) ?: return BuiltInSkillCatalog.defaults
        val savedById = mutableMapOf<String, AgentSkill>()
        val customList = mutableListOf<AgentSkill>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val cat = runCatching { SkillCategory.valueOf(obj.optString("category", "GENERAL")) }
                    .getOrDefault(SkillCategory.GENERAL)
                val tagsArr = obj.optJSONArray("tags")
                val tags = buildList {
                    if (tagsArr != null) {
                        for (t in 0 until tagsArr.length()) {
                            add(tagsArr.optString(t))
                        }
                    }
                }
                val skill = AgentSkill(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    description = obj.optString("description", ""),
                    category = cat,
                    instructions = obj.optString("instructions", ""),
                    tags = tags,
                    isBuiltIn = obj.optBoolean("isBuiltIn", false),
                    isEnabled = obj.optBoolean("isEnabled", true),
                )
                savedById[skill.id] = skill
                if (!skill.isBuiltIn) customList.add(skill)
            }
        }
        // Merge saved enabled flags onto latest built-in catalog + append user custom skills
        val mergedBuiltIns = BuiltInSkillCatalog.defaults.map { def ->
            val saved = savedById[def.id]
            if (saved != null) def.copy(isEnabled = saved.isEnabled) else def
        }
        return customList + mergedBuiltIns
    }

    private fun persistSkills(list: List<AgentSkill>) {
        val arr = JSONArray()
        list.forEach { s ->
            val obj = JSONObject()
                .put("id", s.id)
                .put("name", s.name)
                .put("description", s.description)
                .put("category", s.category.name)
                .put("instructions", s.instructions)
                .put("tags", JSONArray(s.tags))
                .put("isBuiltIn", s.isBuiltIn)
                .put("isEnabled", s.isEnabled)
            arr.put(obj)
        }
        prefs?.edit()?.putString(KEY_SKILLS_JSON, arr.toString())?.apply()
    }

    companion object {
        private const val KEY_AUTO_SELECT = "auto_select_enabled"
        private const val KEY_SKILLS_JSON = "skills_json_v1"
    }
}
