package com.jarves.mh.mcp

import com.jarves.mh.multiagent.persistence.SecretSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Central MCP Connection Manager & Runtime Orchestrator (Parts 2, 3, 4, 7, 8, 9, 12, 16).
 *
 * - Manages independent server lifecycles with crash isolation (one failing server never crashes ORQEN or others)
 * - Resolves secrets securely at runtime from [McpSecretStore] (never logged, never stored in SQLite)
 * - Performs real protocol initialization & tool discovery into [McpToolRegistry]
 * - Enforces [McpAuthorizationEngine] at execution time on every AI agent tool call
 */
class McpConnectionManager(
    val store: McpStore,
    private val secretStore: McpSecretStore,
    val toolRegistry: McpToolRegistry = McpToolRegistry(store),
    val authEngine: McpAuthorizationEngine = McpAuthorizationEngine(store),
    private val transportFactory: (McpServerConfig) -> McpTransportClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap<String, McpTransportClient>()
    /**
     * Phase G (9.2): Team/workspace-specific enabled MCP server selection.
     * Separates Global MCP connections from which servers are enabled for a specific AI Team workspace.
     * Null entry for a workspaceId means all enabled connected servers in scope are available by default.
     */
    private val teamEnabledServers = ConcurrentHashMap<String, Set<String>>()
    private val _teamEnabledServersFlow = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val teamEnabledServersFlow: StateFlow<Map<String, Set<String>>> = _teamEnabledServersFlow.asStateFlow()

    fun setTeamServerEnabled(workspaceId: String, serverId: String, enabled: Boolean) {
        val allIds = _servers.value.map { it.serverId }.toSet()
        val current = teamEnabledServers[workspaceId] ?: allIds
        val updated = if (enabled) current + serverId else current - serverId
        teamEnabledServers[workspaceId] = updated
        _teamEnabledServersFlow.value = teamEnabledServers.toMap()
        if (!enabled) {
            authEngine.revokeGrantsForServer(serverId, "Disabled for team workspace $workspaceId")
            refreshStateFlows()
        }
    }

    fun getTeamEnabledServerIds(workspaceId: String?): Set<String>? =
        workspaceId?.let { teamEnabledServers[it] }

    fun isServerEnabledForTeam(workspaceId: String?, serverId: String): Boolean {
        val allowed = getTeamEnabledServerIds(workspaceId) ?: return true
        return serverId in allowed
    }

    /**
     * Phase G (9.3): SPV least-privilege team MCP permission configurator.
     * Configures role-appropriate base permissions for connected team servers while preserving
     * Owner security boundaries (never grants high-risk bypass, never escalates SPV itself).
     */
    fun applyRecommendedTeamPermissions(
        workspaceId: String?,
        agents: List<com.jarves.mh.multiagent.model.AgentState>,
    ): List<String> {
        val visibleServers = store.listServersForWorkspace(workspaceId).filter { s ->
            s.enabled && isServerEnabledForTeam(workspaceId, s.serverId)
        }
        val changes = mutableListOf<String>()
        for (agent in agents.filter { it.config.isEnabled }) {
            val agentId = agent.config.slot.id
            val roleType = agent.config.role.type
            val level = when (roleType) {
                com.jarves.mh.multiagent.model.AgentRoleType.TEAM_LEADER -> McpPermissionLevel.READ
                com.jarves.mh.multiagent.model.AgentRoleType.TESTER,
                com.jarves.mh.multiagent.model.AgentRoleType.SECURITY_ENGINEER,
                -> McpPermissionLevel.READ
                com.jarves.mh.multiagent.model.AgentRoleType.SOFTWARE_ENGINEER,
                com.jarves.mh.multiagent.model.AgentRoleType.BACKEND_ENGINEER,
                com.jarves.mh.multiagent.model.AgentRoleType.FRONTEND_ENGINEER,
                com.jarves.mh.multiagent.model.AgentRoleType.SPECIALIST,
                com.jarves.mh.multiagent.model.AgentRoleType.DEVOPS_ENGINEER,
                com.jarves.mh.multiagent.model.AgentRoleType.CUSTOM,
                -> McpPermissionLevel.WRITE
            }
            if (visibleServers.isEmpty()) {
                authEngine.setBasePermission(
                    McpAgentBasePermission(
                        agentId = agentId,
                        serverId = "*",
                        maxLevel = level,
                        allowDynamicRequest = true,
                    ),
                )
                changes += "${agent.config.name} (${agent.config.role.title}): default MCP level -> ${level.label}"
            } else {
                for (srv in visibleServers) {
                    val srvLevel = if (srv.name.contains("terminal", ignoreCase = true) || srv.name.contains("gradle", ignoreCase = true)) {
                        if (roleType == com.jarves.mh.multiagent.model.AgentRoleType.SOFTWARE_ENGINEER ||
                            roleType == com.jarves.mh.multiagent.model.AgentRoleType.DEVOPS_ENGINEER ||
                            roleType == com.jarves.mh.multiagent.model.AgentRoleType.TESTER
                        ) McpPermissionLevel.EXECUTE else McpPermissionLevel.READ
                    } else level
                    authEngine.setBasePermission(
                        McpAgentBasePermission(
                            agentId = agentId,
                            serverId = srv.serverId,
                            maxLevel = srvLevel,
                            allowDynamicRequest = true,
                        ),
                    )
                    changes += "${agent.config.name} -> ${srv.name}: ${srvLevel.label}"
                }
            }
        }
        refreshStateFlows()
        return changes
    }

    private val _servers = MutableStateFlow(runCatching { store.listServers() }.getOrDefault(emptyList()))
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    private val _runtimeStates = MutableStateFlow<Map<String, McpServerRuntimeState>>(
        _servers.value.associate { s -> s.serverId to McpServerRuntimeState(serverId = s.serverId) },
    )
    val runtimeStates: StateFlow<Map<String, McpServerRuntimeState>> = _runtimeStates.asStateFlow()

    private val _tools = MutableStateFlow(runCatching { toolRegistry.listAllTools() }.getOrDefault(emptyList()))
    val tools: StateFlow<List<McpToolDefinition>> = _tools.asStateFlow()

    private val _diagnostics = MutableStateFlow<List<McpDiagnosticEvent>>(emptyList())
    val diagnostics: StateFlow<List<McpDiagnosticEvent>> = _diagnostics.asStateFlow()

    private val _requests = MutableStateFlow(runCatching { authEngine.listRequests() }.getOrDefault(emptyList()))
    val permissionRequests: StateFlow<List<McpPermissionRequest>> = _requests.asStateFlow()

    private val _grants = MutableStateFlow(runCatching { authEngine.listGrants() }.getOrDefault(emptyList()))
    val permissionGrants: StateFlow<List<McpScopedPermissionGrant>> = _grants.asStateFlow()

    private val _auditLog = MutableStateFlow(runCatching { authEngine.listAuditLog() }.getOrDefault(emptyList()))
    val permissionAuditLog: StateFlow<List<McpPermissionAuditRecord>> = _auditLog.asStateFlow()

    fun refreshStateFlows() {
        runCatching {
            _servers.value = store.listServers()
            _tools.value = toolRegistry.listAllTools()
            _requests.value = authEngine.listRequests()
            _grants.value = authEngine.listGrants()
            _auditLog.value = authEngine.listAuditLog()
        }
    }

    fun saveServer(
        config: McpServerConfig,
        secretEnv: Map<String, String> = emptyMap(),
        secretHeaders: Map<String, String> = emptyMap(),
    ): McpServerConfig {
        val mergedSecretEnvKeys = config.secretEnvKeys + secretEnv.keys
        val mergedSecretHeaderKeys = config.secretHeaderKeys + secretHeaders.keys
        val sanitizedConfig = config.copy(
            // Strip any accidentally passed secret values out of plain maps before persisting to SQLite!
            env = config.env.filterKeys { it !in mergedSecretEnvKeys },
            headers = config.headers.filterKeys { it !in mergedSecretHeaderKeys },
            secretEnvKeys = mergedSecretEnvKeys,
            secretHeaderKeys = mergedSecretHeaderKeys,
            updatedAt = System.currentTimeMillis(),
        )
        secretEnv.forEach { (k, v) -> if (v.isNotBlank()) secretStore.putSecret(sanitizedConfig.serverId, "env_$k", v) }
        secretHeaders.forEach { (k, v) -> if (v.isNotBlank()) secretStore.putSecret(sanitizedConfig.serverId, "hdr_$k", v) }
        store.upsertServer(sanitizedConfig)
        _runtimeStates.update { map ->
            if (sanitizedConfig.serverId in map) map
            else map + (sanitizedConfig.serverId to McpServerRuntimeState(serverId = sanitizedConfig.serverId))
        }
        if (!sanitizedConfig.enabled) {
            authEngine.revokeGrantsForServer(sanitizedConfig.serverId, "MCP server disabled")
            scope.launch { disconnectServer(sanitizedConfig.serverId) }
        }
        refreshStateFlows()
        return sanitizedConfig
    }

    suspend fun deleteServer(serverId: String) {
        val existing = store.getServer(serverId)
        disconnectServer(serverId)
        authEngine.revokeGrantsForServer(serverId, "MCP server deleted")
        toolRegistry.removeToolsForServer(serverId)
        if (existing != null) {
            secretStore.removeAllSecrets(
                serverId,
                existing.secretEnvKeys.map { "env_$it" }.toSet() + existing.secretHeaderKeys.map { "hdr_$it" }.toSet(),
            )
        }
        store.deleteServer(serverId)
        _runtimeStates.update { it - serverId }
        refreshStateFlows()
    }

    suspend fun connectServer(serverId: String, maxRetries: Int = 2): Result<McpInitializeResult> = withContext(Dispatchers.IO) {
        val config = store.getServer(serverId)
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown MCP server: $serverId"))
        if (!config.enabled) {
            return@withContext Result.failure(IllegalStateException("MCP server '${config.name}' is disabled."))
        }

        updateRuntimeState(serverId) {
            it.copy(status = McpConnectionStatus.CONNECTING, lastError = null)
        }
        recordDiagnostic(serverId, "INFO", "CONNECT", "Connecting to '${config.name}' (${config.transportType.displayName})")

        val resolvedEnv = buildMap {
            putAll(config.env)
            config.secretEnvKeys.forEach { key ->
                secretStore.getSecret(serverId, "env_$key")?.let { put(key, it) }
            }
        }
        val resolvedHeaders = buildMap {
            putAll(config.headers)
            config.secretHeaderKeys.forEach { key ->
                secretStore.getSecret(serverId, "hdr_$key")?.let { secret ->
                    val finalVal = if (key.equals("Authorization", ignoreCase = true) &&
                        !secret.startsWith("Bearer ", ignoreCase = true) &&
                        config.authType == McpAuthType.BEARER_TOKEN
                    ) "Bearer $secret" else secret
                    put(key, finalVal)
                }
            }
        }

        var attempt = 0
        var lastErr: Throwable? = null
        while (attempt <= maxRetries) {
            val client = transportFactory(config)
            try {
                val init = client.connect(config, resolvedEnv, resolvedHeaders) { logLine ->
                    appendServerLog(serverId, logLine)
                }
                clients.put(serverId, client)?.let { old -> runCatching { old.disconnect() } }

                val discovered = runCatching { client.listTools(config.requestTimeoutMs) }.getOrElse { err ->
                    recordDiagnostic(serverId, "WARN", "PROTOCOL", "Connected but tools/list failed: ${SecretSanitizer.sanitize(err.message.orEmpty())}")
                    emptyList()
                }
                val registered = toolRegistry.registerDiscoveredTools(config, discovered)

                updateRuntimeState(serverId) {
                    it.copy(
                        status = McpConnectionStatus.CONNECTED,
                        sessionId = init.sessionId,
                        protocolVersion = init.protocolVersion,
                        serverInfoName = init.serverName,
                        discoveredToolCount = registered.size,
                        lastConnectedAtMs = System.currentTimeMillis(),
                        lastError = null,
                        reconnectAttempts = attempt,
                        processAlive = client.isProcessAlive,
                    )
                }
                recordDiagnostic(serverId, "INFO", "INITIALIZE", "Connected (${init.protocolVersion}); discovered ${registered.size} tool(s).")
                refreshStateFlows()
                return@withContext Result.success(init)
            } catch (err: Throwable) {
                lastErr = err
                runCatching { client.disconnect() }
                val cleanErr = SecretSanitizer.sanitize(err.message ?: err.javaClass.simpleName)
                val isAuthErr = cleanErr.contains("401") || cleanErr.contains("403") || cleanErr.contains("auth", ignoreCase = true)
                recordDiagnostic(serverId, "ERROR", if (isAuthErr) "AUTH" else "CONNECT", "Attempt ${attempt + 1} failed: $cleanErr")
                if (isAuthErr || attempt >= maxRetries) {
                    updateRuntimeState(serverId) {
                        it.copy(
                            status = McpConnectionStatus.ERROR,
                            lastError = cleanErr,
                            reconnectAttempts = attempt + 1,
                            processAlive = false,
                        )
                    }
                    toolRegistry.markServerToolsUnavailable(serverId)
                    refreshStateFlows()
                    return@withContext Result.failure(err)
                }
                attempt++
                val backoff = (500L * (1L shl attempt)) + Random.nextLong(100L, 400L)
                updateRuntimeState(serverId) {
                    it.copy(status = McpConnectionStatus.RECONNECTING, reconnectAttempts = attempt, lastError = cleanErr)
                }
                delay(backoff)
            }
        }
        Result.failure(lastErr ?: IllegalStateException("Failed to connect to $serverId"))
    }

    suspend fun disconnectServer(serverId: String) = withContext(Dispatchers.IO) {
        clients.remove(serverId)?.let { client ->
            runCatching { client.disconnect() }
        }
        toolRegistry.markServerToolsUnavailable(serverId)
        updateRuntimeState(serverId) {
            it.copy(
                status = McpConnectionStatus.DISCONNECTED,
                sessionId = null,
                processAlive = false,
            )
        }
        recordDiagnostic(serverId, "INFO", "CONNECT", "Disconnected cleanly.")
        refreshStateFlows()
    }

    suspend fun refreshTools(serverId: String): Result<List<McpToolDefinition>> = withContext(Dispatchers.IO) {
        val config = store.getServer(serverId) ?: return@withContext Result.failure(IllegalArgumentException("Unknown server: $serverId"))
        val client = clients[serverId]
        if (client == null || !client.isConnected) {
            return@withContext Result.failure(IllegalStateException("Server '${config.name}' is not connected."))
        }
        runCatching {
            val raw = client.listTools(config.requestTimeoutMs)
            val registered = toolRegistry.registerDiscoveredTools(config, raw)
            updateRuntimeState(serverId) { it.copy(discoveredToolCount = registered.size) }
            recordDiagnostic(serverId, "INFO", "PROTOCOL", "Refreshed ${registered.size} tool(s).")
            refreshStateFlows()
            registered
        }
    }

    /**
     * Execution-time authorized MCP tool invocation for AI agents (Parts 8, 9, 16.9).
     * Never allows disconnected servers, invalid schemas, or unauthorized agents to execute tools!
     */
    suspend fun executeToolForAgent(
        agentId: String,
        agentRole: String,
        namespacedToolId: String,
        argumentsJson: String,
        workspaceId: String? = null,
        taskId: String? = null,
        activeTaskAgentIds: Set<String> = emptySet(),
        autoRequestFromSpv: Boolean = true,
    ): McpToolCallResult = withContext(Dispatchers.IO) {
        val tool = toolRegistry.getTool(namespacedToolId)
            ?: return@withContext McpToolCallResult(namespacedToolId, "", false, "Unknown MCP tool: $namespacedToolId", errorCode = "UNKNOWN_TOOL")
        val server = store.getServer(tool.serverId)
            ?: return@withContext McpToolCallResult(namespacedToolId, tool.serverId, false, "MCP server not found for tool $namespacedToolId", errorCode = "UNKNOWN_SERVER")
        if (!isServerEnabledForTeam(workspaceId, server.serverId)) {
            return@withContext McpToolCallResult(
                toolId = namespacedToolId,
                serverId = server.serverId,
                success = false,
                contentText = "MCP server '${server.name}' is disabled for this AI Team workspace in Team MCP Settings.",
                errorCode = "TEAM_SERVER_DISABLED",
            )
        }
        val client = clients[server.serverId]
        val connected = client != null && client.isConnected

        // 1. Validate arguments against discovered JSON schema
        val schemaErr = toolRegistry.validateArguments(tool, argumentsJson)
        if (schemaErr != null) {
            return@withContext McpToolCallResult(namespacedToolId, server.serverId, false, schemaErr, errorCode = "INVALID_ARGS")
        }

        // 2. Execution-time authorization check (Part 16.9)
        var auth = authEngine.authorizeExecution(
            agentId = agentId,
            server = server,
            tool = tool,
            isServerConnected = connected,
            workspaceId = workspaceId,
            taskId = taskId,
        )

        // 3. Dynamic SPV Permission Request when PERMISSION_REQUIRED (Part 16.3 - 16.4)
        if (!auth.allowed && auth.permissionRequired && autoRequestFromSpv) {
            val req = McpPermissionRequest(
                requesterAgentId = agentId,
                assignedRole = agentRole,
                taskId = taskId,
                workspaceId = workspaceId,
                mcpServerId = server.serverId,
                toolId = tool.namespacedToolId,
                requestedAction = tool.rawToolName,
                requestedPermissionLevel = tool.requiredPermissionLevel,
                resourceScope = workspaceId.orEmpty(),
                justification = "Required for task ${taskId ?: "chat"} by $agentId ($agentRole)",
            )
            val (decision, grant) = authEngine.evaluatePermissionRequest(
                request = req,
                server = server,
                tool = tool,
                activeTaskAgentIds = activeTaskAgentIds,
            )
            refreshStateFlows()
            if (grant != null && (decision == McpSupervisorDecisionKind.APPROVED || decision == McpSupervisorDecisionKind.PARTIALLY_APPROVED)) {
                auth = authEngine.authorizeExecution(agentId, server, tool, connected, workspaceId, taskId)
            } else {
                val code = if (decision == McpSupervisorDecisionKind.ESCALATED_TO_OWNER) "OWNER_APPROVAL_REQUIRED" else "PERMISSION_DENIED"
                return@withContext McpToolCallResult(namespacedToolId, server.serverId, false, "Authorization $decision: ${auth.reason}", errorCode = code)
            }
        }

        if (!auth.allowed) {
            val code = if (auth.requiresOwnerApproval) "OWNER_APPROVAL_REQUIRED" else "PERMISSION_DENIED"
            return@withContext McpToolCallResult(namespacedToolId, server.serverId, false, auth.reason, errorCode = code)
        }

        // 4. Execute real MCP tool call against the verified server session
        val activeClient = client ?: return@withContext McpToolCallResult(namespacedToolId, server.serverId, false, "MCP server '${server.name}' is disconnected.", errorCode = "DISCONNECTED")
        return@withContext try {
            val result = activeClient.callTool(tool.rawToolName, argumentsJson, server.requestTimeoutMs)
                .copy(toolId = namespacedToolId)
            recordDiagnostic(
                server.serverId,
                if (result.success) "INFO" else "WARN",
                "TOOL_CALL",
                "[$agentId] $namespacedToolId -> ${if (result.success) "OK" else "ERR"} (${result.durationMs}ms)",
            )
            result
        } catch (err: Throwable) {
            val clean = SecretSanitizer.sanitize(err.message ?: err.javaClass.simpleName)
            recordDiagnostic(server.serverId, "ERROR", "TOOL_CALL", "[$agentId] $namespacedToolId failed: $clean")
            if (!activeClient.isConnected) {
                updateRuntimeState(server.serverId) { it.copy(status = McpConnectionStatus.ERROR, lastError = clean, processAlive = false) }
                toolRegistry.markServerToolsUnavailable(server.serverId)
                refreshStateFlows()
            }
            McpToolCallResult(namespacedToolId, server.serverId, false, "Tool execution error: $clean", errorCode = "EXECUTION_ERROR")
        }
    }

    /** Builds a concise prompt summary of connected & authorized MCP tools for an AI agent. */
    fun buildAuthorizedToolPromptBlock(agentId: String, workspaceId: String?): String {
        val visibleServers = store.listServersForWorkspace(workspaceId).filter { s ->
            s.enabled &&
                isServerEnabledForTeam(workspaceId, s.serverId) &&
                _runtimeStates.value[s.serverId]?.status == McpConnectionStatus.CONNECTED
        }
        if (visibleServers.isEmpty()) return ""
        val serverIds = visibleServers.mapTo(mutableSetOf()) { it.serverId }
        val tools = toolRegistry.listAvailableTools().filter { it.serverId in serverIds }
        if (tools.isEmpty()) return ""

        return buildString {
            appendLine("## CONNECTED MCP TOOLS (Real MCP Servers)")
            appendLine("You may invoke any authorized MCP tool by emitting a line:")
            appendLine("[MCP_CALL tool=\"<namespacedToolId>\" args={\"key\":\"value\"}]")
            tools.take(40).forEach { t ->
                appendLine("- ${t.namespacedToolId} [${t.requiredPermissionLevel.name}${if (t.isHighRisk) ", OWNER_APPROVAL" else ""}]: ${t.description.take(120)}")
            }
        }
    }

    fun getDriverCapabilityMatrix(): List<McpDriverCapability> = listOf(
        McpDriverCapability(
            driverName = "API Providers (OpenAI / Anthropic / Gemini / DeepSeek / OpenRouter / Custom)",
            supportsToolDiscovery = true,
            supportsToolInvocation = true,
            supportsNativeMcp = false,
            supportsToolResultContinuation = true,
            notes = "Authorized MCP tools are exposed via ORQEN tool-call bridge ([MCP_CALL ...]), executed with SPV authorization, and real results are returned to the agent loop.",
        ),
        McpDriverCapability(
            driverName = "Antigravity CLI (PRoot)",
            supportsToolDiscovery = true,
            supportsToolInvocation = true,
            supportsNativeMcp = false,
            supportsToolResultContinuation = true,
            notes = "Runs inside PRoot alongside STDIO/HTTP MCP servers; authorized MCP calls are executed through ORQEN's authorization layer and results fed back into the step.",
        ),
    )

    private fun updateRuntimeState(serverId: String, transform: (McpServerRuntimeState) -> McpServerRuntimeState) {
        _runtimeStates.update { map ->
            val current = map[serverId] ?: McpServerRuntimeState(serverId = serverId)
            map + (serverId to transform(current))
        }
    }

    private fun appendServerLog(serverId: String, line: String) {
        val clean = SecretSanitizer.sanitize(line)
        updateRuntimeState(serverId) { st ->
            st.copy(recentLogs = (st.recentLogs + clean).takeLast(60))
        }
    }

    private fun recordDiagnostic(serverId: String, level: String, category: String, message: String) {
        val ev = McpDiagnosticEvent(
            serverId = serverId,
            level = level,
            category = category,
            message = SecretSanitizer.sanitize(message),
        )
        _diagnostics.update { (listOf(ev) + it).take(200) }
    }
}
