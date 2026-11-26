package com.jarves.mh.mcp

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized MCP Tool Registry (Part 7).
 * - Maintains strict namespacing: `mcp__<serverSlug>__<toolName>`
 * - Maps Tool ID -> Server ID -> Schema
 * - Classifies required permission levels (READ/WRITE/EXECUTE) & high-risk destructive operations
 * - Validates JSON arguments against discovered input schemas before execution
 */
class McpToolRegistry(
    private val store: McpStore,
) {
    private val toolsById = ConcurrentHashMap<String, McpToolDefinition>()

    init {
        store.listTools().forEach { toolsById[it.namespacedToolId] = it }
    }

    fun registerDiscoveredTools(
        server: McpServerConfig,
        discovered: List<McpRawToolInfo>,
    ): List<McpToolDefinition> {
        val slug = server.slug()
        val existingPolicies = toolsById.values
            .filter { it.serverId == server.serverId }
            .associate { it.namespacedToolId to it.policy }

        // Remove stale tools for this server first
        toolsById.values.filter { it.serverId == server.serverId }.forEach {
            toolsById.remove(it.namespacedToolId)
        }

        val seenRaw = mutableSetOf<String>()
        val registered = mutableListOf<McpToolDefinition>()
        for (raw in discovered) {
            val cleanRaw = raw.name.trim()
            if (cleanRaw.isBlank() || !seenRaw.add(cleanRaw)) continue
            val namespaced = buildNamespacedToolId(slug, cleanRaw)
            val inferredLevel = inferRequiredPermissionLevel(cleanRaw, raw.description)
            val highRisk = isHighRiskOperation(cleanRaw, raw.description)
            val def = McpToolDefinition(
                namespacedToolId = namespaced,
                serverId = server.serverId,
                serverName = server.name,
                rawToolName = cleanRaw,
                description = raw.description,
                inputSchemaJson = raw.inputSchemaJson.ifBlank { "{}" },
                requiredPermissionLevel = inferredLevel,
                policy = existingPolicies[namespaced] ?: if (highRisk) McpToolPolicy.ASK else server.defaultToolPolicy,
                isHighRisk = highRisk,
                available = true,
                discoveredAt = System.currentTimeMillis(),
            )
            toolsById[namespaced] = def
            registered += def
        }
        store.replaceToolsForServer(server.serverId, registered)
        return registered
    }

    fun markServerToolsUnavailable(serverId: String) {
        val updated = mutableListOf<McpToolDefinition>()
        toolsById.values.filter { it.serverId == serverId }.forEach { tool ->
            val disabled = tool.copy(available = false)
            toolsById[tool.namespacedToolId] = disabled
            updated += disabled
        }
        if (updated.isNotEmpty()) {
            store.replaceToolsForServer(serverId, updated)
        }
    }

    fun removeToolsForServer(serverId: String) {
        toolsById.values.filter { it.serverId == serverId }.forEach {
            toolsById.remove(it.namespacedToolId)
        }
    }

    fun getTool(namespacedToolId: String): McpToolDefinition? = toolsById[namespacedToolId]

    fun listAllTools(): List<McpToolDefinition> = toolsById.values.sortedBy { it.namespacedToolId }

    fun listAvailableTools(): List<McpToolDefinition> =
        toolsById.values.filter { it.available }.sortedBy { it.namespacedToolId }

    fun listToolsForServer(serverId: String): List<McpToolDefinition> =
        toolsById.values.filter { it.serverId == serverId }.sortedBy { it.namespacedToolId }

    fun updateToolPolicy(namespacedToolId: String, policy: McpToolPolicy) {
        toolsById[namespacedToolId]?.let { current ->
            toolsById[namespacedToolId] = current.copy(policy = policy)
            store.updateToolPolicy(namespacedToolId, policy)
        }
    }

    /** Validates JSON arguments against the tool's discovered JSON Schema `required` fields. */
    fun validateArguments(tool: McpToolDefinition, argumentsJson: String): String? {
        val argsObj = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrElse {
            return "Invalid JSON arguments for ${tool.namespacedToolId}: ${it.message}"
        }
        val schema = runCatching { JSONObject(tool.inputSchemaJson.ifBlank { "{}" }) }.getOrNull() ?: return null
        val requiredArr = schema.optJSONArray("required") ?: return null
        val missing = mutableListOf<String>()
        for (i in 0 until requiredArr.length()) {
            val reqField = requiredArr.optString(i)
            if (reqField.isNotBlank() && !argsObj.has(reqField)) {
                missing += reqField
            }
        }
        return if (missing.isNotEmpty()) {
            "Missing required argument(s) for ${tool.namespacedToolId}: ${missing.joinToString(", ")}"
        } else null
    }

    companion object {
        fun buildNamespacedToolId(serverSlug: String, rawToolName: String): String {
            val cleanTool = rawToolName.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
            return "mcp__${serverSlug}__$cleanTool"
        }

        fun inferRequiredPermissionLevel(toolName: String, description: String): McpPermissionLevel {
            val combined = "$toolName $description".lowercase()
            val executeKeywords = listOf("exec", "execute", "run_command", "shell", "deploy", "migrate", "trigger", "workflow", "bash", "sql")
            val writeKeywords = listOf("create", "write", "update", "edit", "delete", "remove", "drop", "push", "commit", "merge", "insert", "alter", "patch", "post", "modify", "set_")
            return when {
                executeKeywords.any { combined.contains(it) } -> McpPermissionLevel.EXECUTE
                writeKeywords.any { combined.contains(it) } -> McpPermissionLevel.WRITE
                else -> McpPermissionLevel.READ
            }
        }

        fun isHighRiskOperation(toolName: String, description: String): Boolean {
            val combined = "$toolName $description".lowercase()
            val highRiskMarkers = listOf(
                "delete_repo", "delete_repository", "delete_branch", "delete_project", "delete_table",
                "delete_database", "drop_database", "drop_table", "truncate", "permanently delete",
                "permanent_delete", "destroy", "billing", "payment", "transfer_funds",
                "rotate_secret", "manage_token", "delete_secret", "org_permission",
                "organization_settings", "force_push", "production_db", "format_disk",
            )
            return highRiskMarkers.any { combined.contains(it) }
        }
    }
}
