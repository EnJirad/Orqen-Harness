package com.jarves.mh.mcp

import android.content.ContentValues
import android.database.Cursor
import com.jarves.mh.data.ApiKeyVault
import com.jarves.mh.multiagent.persistence.OrqenTaskDatabaseHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Secret credential bridge reusing ORQEN's Android Keystore [ApiKeyVault].
 * Secrets are NEVER stored inside SQLite configuration tables.
 */
interface McpSecretStore {
    fun putSecret(serverId: String, key: String, value: String)
    fun getSecret(serverId: String, key: String): String?
    fun removeAllSecrets(serverId: String, keys: Set<String>)
}

class VaultMcpSecretStore(private val vault: ApiKeyVault) : McpSecretStore {
    private fun vaultKey(serverId: String, key: String) = "mcp_${serverId}_${key}"

    override fun putSecret(serverId: String, key: String, value: String) {
        if (value.isBlank()) return
        vault.put(vaultKey(serverId, key), value)
    }

    override fun getSecret(serverId: String, key: String): String? =
        vault.credentials(vaultKey(serverId, key)).firstOrNull()?.secret

    override fun removeAllSecrets(serverId: String, keys: Set<String>) {
        keys.forEach { key ->
            val vk = vaultKey(serverId, key)
            vault.list(vk).forEach { info -> vault.remove(vk, info.id) }
        }
    }
}

class InMemoryMcpSecretStore : McpSecretStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun putSecret(serverId: String, key: String, value: String) {
        if (value.isNotBlank()) map["$serverId::$key"] = value
    }

    override fun getSecret(serverId: String, key: String): String? = map["$serverId::$key"]

    override fun removeAllSecrets(serverId: String, keys: Set<String>) {
        keys.forEach { map.remove("$serverId::$it") }
    }
}

interface McpStore {
    fun upsertServer(config: McpServerConfig)
    fun getServer(serverId: String): McpServerConfig?
    fun listServers(): List<McpServerConfig>
    fun listServersForWorkspace(workspaceId: String?): List<McpServerConfig>
    fun deleteServer(serverId: String)

    fun replaceToolsForServer(serverId: String, tools: List<McpToolDefinition>)
    fun listTools(): List<McpToolDefinition>
    fun updateToolPolicy(namespacedToolId: String, policy: McpToolPolicy)

    fun upsertBasePermission(perm: McpAgentBasePermission)
    fun listBasePermissions(agentId: String? = null): List<McpAgentBasePermission>

    fun upsertPermissionRequest(req: McpPermissionRequest)
    fun listPermissionRequests(): List<McpPermissionRequest>

    fun upsertGrant(grant: McpScopedPermissionGrant)
    fun listGrants(agentId: String? = null): List<McpScopedPermissionGrant>

    fun insertAudit(record: McpPermissionAuditRecord)
    fun listAuditRecords(limit: Int = 200): List<McpPermissionAuditRecord>
}

class InMemoryMcpStore : McpStore {
    private val servers = ConcurrentHashMap<String, McpServerConfig>()
    private val tools = ConcurrentHashMap<String, McpToolDefinition>()
    private val perms = ConcurrentHashMap<String, McpAgentBasePermission>()
    private val requests = ConcurrentHashMap<String, McpPermissionRequest>()
    private val grants = ConcurrentHashMap<String, McpScopedPermissionGrant>()
    private val audits = java.util.Collections.synchronizedList(mutableListOf<McpPermissionAuditRecord>())

    override fun upsertServer(config: McpServerConfig) {
        servers[config.serverId] = config
    }

    override fun getServer(serverId: String): McpServerConfig? = servers[serverId]

    override fun listServers(): List<McpServerConfig> = servers.values.sortedBy { it.createdAt }

    override fun listServersForWorkspace(workspaceId: String?): List<McpServerConfig> =
        servers.values.filter {
            it.scope == McpScope.GLOBAL || (workspaceId != null && it.workspaceId == workspaceId)
        }.sortedBy { it.createdAt }

    override fun deleteServer(serverId: String) {
        servers.remove(serverId)
        tools.values.filter { it.serverId == serverId }.forEach { tools.remove(it.namespacedToolId) }
    }

    override fun replaceToolsForServer(serverId: String, tools: List<McpToolDefinition>) {
        this.tools.values.filter { it.serverId == serverId }.forEach { this.tools.remove(it.namespacedToolId) }
        tools.forEach { this.tools[it.namespacedToolId] = it }
    }

    override fun listTools(): List<McpToolDefinition> = tools.values.sortedBy { it.namespacedToolId }

    override fun updateToolPolicy(namespacedToolId: String, policy: McpToolPolicy) {
        tools[namespacedToolId]?.let { tools[namespacedToolId] = it.copy(policy = policy) }
    }

    override fun upsertBasePermission(perm: McpAgentBasePermission) {
        perms["${perm.agentId}::${perm.serverId}"] = perm
    }

    override fun listBasePermissions(agentId: String?): List<McpAgentBasePermission> =
        perms.values.filter { agentId == null || it.agentId == agentId }.sortedBy { it.agentId }

    override fun upsertPermissionRequest(req: McpPermissionRequest) {
        requests[req.requestId] = req
    }

    override fun listPermissionRequests(): List<McpPermissionRequest> =
        requests.values.sortedByDescending { it.createdAt }

    override fun upsertGrant(grant: McpScopedPermissionGrant) {
        grants[grant.grantId] = grant
    }

    override fun listGrants(agentId: String?): List<McpScopedPermissionGrant> =
        grants.values.filter { agentId == null || it.agentId == agentId }.sortedByDescending { it.createdAt }

    override fun insertAudit(record: McpPermissionAuditRecord) {
        audits.add(record)
    }

    override fun listAuditRecords(limit: Int): List<McpPermissionAuditRecord> =
        synchronized(audits) { audits.sortedByDescending { it.timestampMs }.take(limit) }
}

class SqliteMcpStore(
    private val dbHelper: OrqenTaskDatabaseHelper,
) : McpStore {

    override fun upsertServer(config: McpServerConfig) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("server_id", config.serverId)
            put("name", config.name)
            put("description", config.description)
            put("transport_type", config.transportType.name)
            put("command", config.command)
            put("args_json", JSONArray(config.args).toString())
            put("env_json", JSONObject(config.env).toString())
            put("secret_env_keys_json", JSONArray(config.secretEnvKeys.toList()).toString())
            put("working_directory", config.workingDirectory)
            put("remote_url", config.remoteUrl)
            put("headers_json", JSONObject(config.headers).toString())
            put("secret_header_keys_json", JSONArray(config.secretHeaderKeys.toList()).toString())
            put("auth_type", config.authType.name)
            put("auth_header_name", config.authHeaderName)
            put("enabled", if (config.enabled) 1 else 0)
            put("scope", config.scope.name)
            put("workspace_id", config.workspaceId)
            put("default_tool_policy", config.defaultToolPolicy.name)
            put("connection_timeout_ms", config.connectionTimeoutMs)
            put("request_timeout_ms", config.requestTimeoutMs)
            put("created_at", config.createdAt)
            put("updated_at", config.updatedAt)
        }
        db.insertWithOnConflict("mcp_servers", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getServer(serverId: String): McpServerConfig? {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM mcp_servers WHERE server_id = ?", arrayOf(serverId)).use { c ->
            return if (c.moveToFirst()) readServer(c) else null
        }
    }

    override fun listServers(): List<McpServerConfig> {
        val out = mutableListOf<McpServerConfig>()
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM mcp_servers ORDER BY created_at ASC", null).use { c ->
            while (c.moveToNext()) out += readServer(c)
        }
        return out
    }

    override fun listServersForWorkspace(workspaceId: String?): List<McpServerConfig> =
        listServers().filter {
            it.scope == McpScope.GLOBAL || (workspaceId != null && it.workspaceId == workspaceId)
        }

    override fun deleteServer(serverId: String) {
        val db = dbHelper.writableDatabase
        db.delete("mcp_tools", "server_id = ?", arrayOf(serverId))
        db.delete("mcp_servers", "server_id = ?", arrayOf(serverId))
    }

    override fun replaceToolsForServer(serverId: String, tools: List<McpToolDefinition>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("mcp_tools", "server_id = ?", arrayOf(serverId))
            tools.forEach { t ->
                val cv = ContentValues().apply {
                    put("namespaced_tool_id", t.namespacedToolId)
                    put("server_id", t.serverId)
                    put("server_name", t.serverName)
                    put("raw_tool_name", t.rawToolName)
                    put("description", t.description)
                    put("input_schema_json", t.inputSchemaJson)
                    put("required_permission_level", t.requiredPermissionLevel.name)
                    put("policy", t.policy.name)
                    put("is_high_risk", if (t.isHighRisk) 1 else 0)
                    put("available", if (t.available) 1 else 0)
                    put("discovered_at", t.discoveredAt)
                }
                db.insertWithOnConflict("mcp_tools", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun listTools(): List<McpToolDefinition> {
        val out = mutableListOf<McpToolDefinition>()
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM mcp_tools ORDER BY namespaced_tool_id ASC", null).use { c ->
            while (c.moveToNext()) {
                out += McpToolDefinition(
                    namespacedToolId = c.getString(c.getColumnIndexOrThrow("namespaced_tool_id")),
                    serverId = c.getString(c.getColumnIndexOrThrow("server_id")),
                    serverName = c.getString(c.getColumnIndexOrThrow("server_name")),
                    rawToolName = c.getString(c.getColumnIndexOrThrow("raw_tool_name")),
                    description = c.getString(c.getColumnIndexOrThrow("description")),
                    inputSchemaJson = c.getString(c.getColumnIndexOrThrow("input_schema_json")),
                    requiredPermissionLevel = runCatching { McpPermissionLevel.valueOf(c.getString(c.getColumnIndexOrThrow("required_permission_level"))) }.getOrDefault(McpPermissionLevel.READ),
                    policy = runCatching { McpToolPolicy.valueOf(c.getString(c.getColumnIndexOrThrow("policy"))) }.getOrDefault(McpToolPolicy.ASK),
                    isHighRisk = c.getInt(c.getColumnIndexOrThrow("is_high_risk")) != 0,
                    available = c.getInt(c.getColumnIndexOrThrow("available")) != 0,
                    discoveredAt = c.getLong(c.getColumnIndexOrThrow("discovered_at")),
                )
            }
        }
        return out
    }

    override fun updateToolPolicy(namespacedToolId: String, policy: McpToolPolicy) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply { put("policy", policy.name) }
        db.update("mcp_tools", cv, "namespaced_tool_id = ?", arrayOf(namespacedToolId))
    }

    override fun upsertBasePermission(perm: McpAgentBasePermission) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("permission_id", perm.permissionId)
            put("agent_id", perm.agentId)
            put("server_id", perm.serverId)
            put("max_level", perm.maxLevel.name)
            put("allowed_tools_json", JSONArray(perm.allowedToolIds.toList()).toString())
            put("denied_tools_json", JSONArray(perm.deniedToolIds.toList()).toString())
            put("allow_dynamic_request", if (perm.allowDynamicRequest) 1 else 0)
            put("updated_at", perm.updatedAt)
        }
        db.insertWithOnConflict("mcp_agent_permissions", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun listBasePermissions(agentId: String?): List<McpAgentBasePermission> {
        val out = mutableListOf<McpAgentBasePermission>()
        val db = dbHelper.readableDatabase
        val sql = if (agentId != null) "SELECT * FROM mcp_agent_permissions WHERE agent_id = ?" else "SELECT * FROM mcp_agent_permissions"
        val args = if (agentId != null) arrayOf(agentId) else null
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out += McpAgentBasePermission(
                    permissionId = c.getString(c.getColumnIndexOrThrow("permission_id")),
                    agentId = c.getString(c.getColumnIndexOrThrow("agent_id")),
                    serverId = c.getString(c.getColumnIndexOrThrow("server_id")),
                    maxLevel = runCatching { McpPermissionLevel.valueOf(c.getString(c.getColumnIndexOrThrow("max_level"))) }.getOrDefault(McpPermissionLevel.READ),
                    allowedToolIds = parseStringList(c.getString(c.getColumnIndexOrThrow("allowed_tools_json"))).toSet(),
                    deniedToolIds = parseStringList(c.getString(c.getColumnIndexOrThrow("denied_tools_json"))).toSet(),
                    allowDynamicRequest = c.getInt(c.getColumnIndexOrThrow("allow_dynamic_request")) != 0,
                    updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
                )
            }
        }
        return out
    }

    override fun upsertPermissionRequest(req: McpPermissionRequest) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("request_id", req.requestId)
            put("requester_agent_id", req.requesterAgentId)
            put("assigned_role", req.assignedRole)
            put("task_id", req.taskId)
            put("workspace_id", req.workspaceId)
            put("mcp_server_id", req.mcpServerId)
            put("tool_id", req.toolId)
            put("requested_action", req.requestedAction)
            put("requested_level", req.requestedPermissionLevel.name)
            put("resource_scope", req.resourceScope)
            put("justification", req.justification)
            put("requested_duration_ms", req.requestedDurationMs)
            put("status", req.status.name)
            put("decision_reason", req.decisionReason)
            put("created_at", req.createdAt)
            put("resolved_at", req.resolvedAt)
        }
        db.insertWithOnConflict("mcp_permission_requests", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun listPermissionRequests(): List<McpPermissionRequest> {
        val out = mutableListOf<McpPermissionRequest>()
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM mcp_permission_requests ORDER BY created_at DESC LIMIT 200", null).use { c ->
            while (c.moveToNext()) {
                val resIdx = c.getColumnIndexOrThrow("resolved_at")
                out += McpPermissionRequest(
                    requestId = c.getString(c.getColumnIndexOrThrow("request_id")),
                    requesterAgentId = c.getString(c.getColumnIndexOrThrow("requester_agent_id")),
                    assignedRole = c.getString(c.getColumnIndexOrThrow("assigned_role")),
                    taskId = c.getString(c.getColumnIndexOrThrow("task_id")),
                    workspaceId = c.getString(c.getColumnIndexOrThrow("workspace_id")),
                    mcpServerId = c.getString(c.getColumnIndexOrThrow("mcp_server_id")),
                    toolId = c.getString(c.getColumnIndexOrThrow("tool_id")),
                    requestedAction = c.getString(c.getColumnIndexOrThrow("requested_action")),
                    requestedPermissionLevel = runCatching { McpPermissionLevel.valueOf(c.getString(c.getColumnIndexOrThrow("requested_level"))) }.getOrDefault(McpPermissionLevel.READ),
                    resourceScope = c.getString(c.getColumnIndexOrThrow("resource_scope")),
                    justification = c.getString(c.getColumnIndexOrThrow("justification")),
                    requestedDurationMs = c.getLong(c.getColumnIndexOrThrow("requested_duration_ms")),
                    status = runCatching { McpRequestStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status"))) }.getOrDefault(McpRequestStatus.PENDING),
                    decisionReason = c.getString(c.getColumnIndexOrThrow("decision_reason")),
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                    resolvedAt = if (c.isNull(resIdx)) null else c.getLong(resIdx),
                )
            }
        }
        return out
    }

    override fun upsertGrant(grant: McpScopedPermissionGrant) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("grant_id", grant.grantId)
            put("request_id", grant.requestId)
            put("agent_id", grant.agentId)
            put("granted_by", grant.grantedBy)
            put("mcp_server_id", grant.mcpServerId)
            put("tool_id", grant.toolId)
            put("granted_level", grant.grantedLevel.name)
            put("scope_type", grant.scopeType.name)
            put("task_id", grant.taskId)
            put("workspace_id", grant.workspaceId)
            put("resource_scope", grant.resourceScope)
            put("can_delegate_further", if (grant.canDelegateFurther) 1 else 0)
            put("created_at", grant.createdAt)
            put("expires_at", grant.expiresAt)
            put("revoked_at", grant.revokedAt)
            put("revoke_reason", grant.revokeReason)
        }
        db.insertWithOnConflict("mcp_permission_grants", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun listGrants(agentId: String?): List<McpScopedPermissionGrant> {
        val out = mutableListOf<McpScopedPermissionGrant>()
        val db = dbHelper.readableDatabase
        val sql = if (agentId != null) "SELECT * FROM mcp_permission_grants WHERE agent_id = ? ORDER BY created_at DESC" else "SELECT * FROM mcp_permission_grants ORDER BY created_at DESC"
        val args = if (agentId != null) arrayOf(agentId) else null
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val revIdx = c.getColumnIndexOrThrow("revoked_at")
                out += McpScopedPermissionGrant(
                    grantId = c.getString(c.getColumnIndexOrThrow("grant_id")),
                    requestId = c.getString(c.getColumnIndexOrThrow("request_id")),
                    agentId = c.getString(c.getColumnIndexOrThrow("agent_id")),
                    grantedBy = c.getString(c.getColumnIndexOrThrow("granted_by")),
                    mcpServerId = c.getString(c.getColumnIndexOrThrow("mcp_server_id")),
                    toolId = c.getString(c.getColumnIndexOrThrow("tool_id")),
                    grantedLevel = runCatching { McpPermissionLevel.valueOf(c.getString(c.getColumnIndexOrThrow("granted_level"))) }.getOrDefault(McpPermissionLevel.READ),
                    scopeType = runCatching { McpGrantScopeType.valueOf(c.getString(c.getColumnIndexOrThrow("scope_type"))) }.getOrDefault(McpGrantScopeType.CURRENT_TASK),
                    taskId = c.getString(c.getColumnIndexOrThrow("task_id")),
                    workspaceId = c.getString(c.getColumnIndexOrThrow("workspace_id")),
                    resourceScope = c.getString(c.getColumnIndexOrThrow("resource_scope")),
                    canDelegateFurther = c.getInt(c.getColumnIndexOrThrow("can_delegate_further")) != 0,
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                    expiresAt = c.getLong(c.getColumnIndexOrThrow("expires_at")),
                    revokedAt = if (c.isNull(revIdx)) null else c.getLong(revIdx),
                    revokeReason = c.getString(c.getColumnIndexOrThrow("revoke_reason")),
                )
            }
        }
        return out
    }

    override fun insertAudit(record: McpPermissionAuditRecord) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("event_id", record.eventId)
            put("request_id", record.requestId)
            put("requesting_agent_id", record.requestingAgentId)
            put("supervisor_id", record.supervisorId)
            put("tool_id", record.toolId)
            put("mcp_server_id", record.mcpServerId)
            put("requested_permission", record.requestedPermission.name)
            put("granted_permission", record.grantedPermission.name)
            put("decision", record.decision.name)
            put("decision_reason", record.decisionReason)
            put("resource_scope", record.resourceScope)
            put("task_id", record.taskId)
            put("timestamp_ms", record.timestampMs)
            put("expiration_ms", record.expirationMs)
            put("revocation_timestamp_ms", record.revocationTimestampMs)
        }
        db.insertWithOnConflict("mcp_permission_audit", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun listAuditRecords(limit: Int): List<McpPermissionAuditRecord> {
        val out = mutableListOf<McpPermissionAuditRecord>()
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM mcp_permission_audit ORDER BY timestamp_ms DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) {
                val expIdx = c.getColumnIndexOrThrow("expiration_ms")
                val revIdx = c.getColumnIndexOrThrow("revocation_timestamp_ms")
                out += McpPermissionAuditRecord(
                    eventId = c.getString(c.getColumnIndexOrThrow("event_id")),
                    requestId = c.getString(c.getColumnIndexOrThrow("request_id")),
                    requestingAgentId = c.getString(c.getColumnIndexOrThrow("requesting_agent_id")),
                    supervisorId = c.getString(c.getColumnIndexOrThrow("supervisor_id")),
                    toolId = c.getString(c.getColumnIndexOrThrow("tool_id")),
                    mcpServerId = c.getString(c.getColumnIndexOrThrow("mcp_server_id")),
                    requestedPermission = runCatching { McpPermissionLevel.valueOf(c.getString(c.getColumnIndexOrThrow("requested_permission"))) }.getOrDefault(McpPermissionLevel.READ),
                    grantedPermission = runCatching { McpPermissionLevel.valueOf(c.getString(c.getColumnIndexOrThrow("granted_permission"))) }.getOrDefault(McpPermissionLevel.DENY),
                    decision = runCatching { McpSupervisorDecisionKind.valueOf(c.getString(c.getColumnIndexOrThrow("decision"))) }.getOrDefault(McpSupervisorDecisionKind.DENIED),
                    decisionReason = c.getString(c.getColumnIndexOrThrow("decision_reason")),
                    resourceScope = c.getString(c.getColumnIndexOrThrow("resource_scope")),
                    taskId = c.getString(c.getColumnIndexOrThrow("task_id")),
                    timestampMs = c.getLong(c.getColumnIndexOrThrow("timestamp_ms")),
                    expirationMs = if (c.isNull(expIdx)) null else c.getLong(expIdx),
                    revocationTimestampMs = if (c.isNull(revIdx)) null else c.getLong(revIdx),
                )
            }
        }
        return out
    }

    private fun readServer(c: Cursor): McpServerConfig = McpServerConfig(
        serverId = c.getString(c.getColumnIndexOrThrow("server_id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        description = c.getString(c.getColumnIndexOrThrow("description")),
        transportType = runCatching { McpTransportType.valueOf(c.getString(c.getColumnIndexOrThrow("transport_type"))) }.getOrDefault(McpTransportType.STDIO),
        command = c.getString(c.getColumnIndexOrThrow("command")),
        args = parseStringList(c.getString(c.getColumnIndexOrThrow("args_json"))),
        env = parseStringMap(c.getString(c.getColumnIndexOrThrow("env_json"))),
        secretEnvKeys = parseStringList(c.getString(c.getColumnIndexOrThrow("secret_env_keys_json"))).toSet(),
        workingDirectory = c.getString(c.getColumnIndexOrThrow("working_directory")),
        remoteUrl = c.getString(c.getColumnIndexOrThrow("remote_url")),
        headers = parseStringMap(c.getString(c.getColumnIndexOrThrow("headers_json"))),
        secretHeaderKeys = parseStringList(c.getString(c.getColumnIndexOrThrow("secret_header_keys_json"))).toSet(),
        authType = runCatching { McpAuthType.valueOf(c.getString(c.getColumnIndexOrThrow("auth_type"))) }.getOrDefault(McpAuthType.NONE),
        authHeaderName = c.getString(c.getColumnIndexOrThrow("auth_header_name")),
        enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0,
        scope = runCatching { McpScope.valueOf(c.getString(c.getColumnIndexOrThrow("scope"))) }.getOrDefault(McpScope.GLOBAL),
        workspaceId = c.getString(c.getColumnIndexOrThrow("workspace_id")),
        defaultToolPolicy = runCatching { McpToolPolicy.valueOf(c.getString(c.getColumnIndexOrThrow("default_tool_policy"))) }.getOrDefault(McpToolPolicy.ASK),
        connectionTimeoutMs = c.getLong(c.getColumnIndexOrThrow("connection_timeout_ms")),
        requestTimeoutMs = c.getLong(c.getColumnIndexOrThrow("request_timeout_ms")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
    )

    private fun parseStringList(json: String): List<String> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.optString(it) }
    }.getOrDefault(emptyList())

    private fun parseStringMap(json: String): Map<String, String> = runCatching {
        val obj = JSONObject(json)
        val map = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.optString(k)
        }
        map
    }.getOrDefault(emptyMap())
}
