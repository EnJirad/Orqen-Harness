package com.jarves.mh.multiagent.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.ui.graphics.vector.ImageVector
import com.jarves.mh.multiagent.model.AgentConfig
import com.jarves.mh.multiagent.model.AgentRoleType

data class AgentIconOption(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * Role-appropriate icon catalog for AI Team agents.
 * - Auto-selects a fitting icon when [AgentConfig.iconKey] is blank.
 * - Supports user selection or role-appropriate randomization in Agent Settings.
 */
object AgentIconCatalog {

    val allOptions: List<AgentIconOption> = listOf(
        AgentIconOption("hub", "Supervisor", Icons.Default.Hub),
        AgentIconOption("psychology", "Planner", Icons.Default.Psychology),
        AgentIconOption("code", "Coder", Icons.Default.Code),
        AgentIconOption("terminal", "Terminal", Icons.Default.Terminal),
        AgentIconOption("palette", "Frontend", Icons.Default.Palette),
        AgentIconOption("storage", "Backend", Icons.Default.Storage),
        AgentIconOption("bug", "QA / Bug", Icons.Default.BugReport),
        AgentIconOption("verified", "Reviewer", Icons.Default.VerifiedUser),
        AgentIconOption("security", "Security", Icons.Default.Security),
        AgentIconOption("cloud", "DevOps", Icons.Default.Cloud),
        AgentIconOption("rocket", "Launcher", Icons.Default.RocketLaunch),
        AgentIconOption("build", "Builder", Icons.Default.Build),
        AgentIconOption("spark", "Specialist", Icons.Default.AutoAwesome),
    )

    fun defaultKeyForRole(roleType: AgentRoleType): String = when (roleType) {
        AgentRoleType.TEAM_LEADER -> "hub"
        AgentRoleType.SOFTWARE_ENGINEER -> "code"
        AgentRoleType.FRONTEND_ENGINEER -> "palette"
        AgentRoleType.BACKEND_ENGINEER -> "storage"
        AgentRoleType.TESTER -> "verified"
        AgentRoleType.SECURITY_ENGINEER -> "security"
        AgentRoleType.DEVOPS_ENGINEER -> "cloud"
        AgentRoleType.SPECIALIST -> "terminal"
        AgentRoleType.CUSTOM -> "spark"
    }

    fun recommendedKeysForRole(roleType: AgentRoleType): List<String> = when (roleType) {
        AgentRoleType.TEAM_LEADER -> listOf("hub", "psychology", "rocket")
        AgentRoleType.SOFTWARE_ENGINEER -> listOf("code", "terminal", "build")
        AgentRoleType.FRONTEND_ENGINEER -> listOf("palette", "code", "spark")
        AgentRoleType.BACKEND_ENGINEER -> listOf("storage", "terminal", "build")
        AgentRoleType.TESTER -> listOf("verified", "bug", "psychology")
        AgentRoleType.SECURITY_ENGINEER -> listOf("security", "verified", "bug")
        AgentRoleType.DEVOPS_ENGINEER -> listOf("cloud", "rocket", "terminal")
        AgentRoleType.SPECIALIST -> listOf("spark", "build", "code", "terminal")
        AgentRoleType.CUSTOM -> allOptions.map { it.key }
    }

    fun randomKeyForRole(roleType: AgentRoleType, currentKey: String = ""): String {
        val pool = recommendedKeysForRole(roleType)
        val candidates = pool.filter { it != currentKey }.ifEmpty { pool }
        return candidates.random()
    }

    fun iconForKey(key: String, fallbackRole: AgentRoleType = AgentRoleType.SOFTWARE_ENGINEER): ImageVector {
        val resolvedKey = key.trim().ifBlank { defaultKeyForRole(fallbackRole) }
        return allOptions.firstOrNull { it.key == resolvedKey }?.icon
            ?: allOptions.firstOrNull { it.key == defaultKeyForRole(fallbackRole) }?.icon
            ?: Icons.Default.Code
    }

    fun iconFor(config: AgentConfig): ImageVector = iconForKey(config.iconKey, config.role.type)
}
