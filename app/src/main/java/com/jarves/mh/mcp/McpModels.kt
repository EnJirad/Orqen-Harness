package com.jarves.mh.mcp

import java.util.UUID

enum class McpTransportType(val displayName: String) {
    STDIO("STDIO (Local PRoot)"),
    STREAMABLE_HTTP("Streamable HTTP"),
    LEGACY_HTTP_SSE("Legacy HTTP + SSE"),
}

enum class McpScope(val displayName: String) {
    GLOBAL("Global"),
    WORKSPACE("Workspace"),
}

enum class McpConnectionStatus(val displayName: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    RECONNECTING("Reconnecting"),
    ERROR("Error"),
}

enum class McpToolPolicy(val displayName: String) {
    ALLOW("Allow"),
    ASK("Ask before execution"),
    DENY("Deny"),
}

/**
 * Granular agent permission levels (Part 16.2).
 * READ never implies WRITE or EXECUTE; destructive operations always require explicit Owner approval.
 */
enum class McpPermissionLevel(val rank: Int, val label: String) {
    DENY(0, "Deny"),
    READ(1, "Read"),
    WRITE(2, "Write"),
    EXECUTE(3, "Execute");

    fun satisfies(required: McpPermissionLevel): Boolean = this.rank >= required.rank && this != DENY
}

enum class McpAuthType {
    NONE,
    BEARER_TOKEN,
    API_KEY_HEADER,
    OAUTH2_PKCE,
}

data class McpServerConfig(
    val serverId: String = "mcp-${UUID.randomUUID().toString().take(8)}",
    val name: String,
    val description: String = "",
    val transportType: McpTransportType,
    val command: String = "",
    val args: List<String> = emptyList(),
    /** Non-secret env vars; secret env var keys are listed in [secretEnvKeys] and stored in ApiKeyVault. */
    val env: Map<String, String> = emptyMap(),
    val secretEnvKeys: Set<String> = emptySet(),
    val workingDirectory: String = "",
    val remoteUrl: String = "",
    /** Non-secret HTTP headers; secret header names are listed in [secretHeaderKeys] and stored in ApiKeyVault. */
    val headers: Map<String, String> = emptyMap(),
    val secretHeaderKeys: Set<String> = emptySet(),
    val authType: McpAuthType = McpAuthType.NONE,
    val authHeaderName: String = "Authorization",
    val enabled: Boolean = true,
    val scope: McpScope = McpScope.GLOBAL,
    val workspaceId: String? = null,
    val defaultToolPolicy: McpToolPolicy = McpToolPolicy.ASK,
    val connectionTimeoutMs: Long = 20_000L,
    val requestTimeoutMs: Long = 45_000L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun slug(): String = name.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { serverId.lowercase().replace(Regex("[^a-z0-9]+"), "_") }
}

data class McpToolDefinition(
    /** Namespaced identifier: mcp__<serverSlug>__<toolName> */
    val namespacedToolId: String,
    val serverId: String,
    val serverName: String,
    val rawToolName: String,
    val description: String = "",
    val inputSchemaJson: String = "{}",
    val requiredPermissionLevel: McpPermissionLevel = McpPermissionLevel.READ,
    val policy: McpToolPolicy = McpToolPolicy.ASK,
    val isHighRisk: Boolean = false,
    val available: Boolean = true,
    val discoveredAt: Long = System.currentTimeMillis(),
)

data class McpDiagnosticEvent(
    val eventId: String = "mdiag-${UUID.randomUUID().toString().take(8)}",
    val serverId: String,
    val level: String = "INFO", // INFO, WARN, ERROR
    val category: String,       // CONNECT, INITIALIZE, PROTOCOL, AUTH, TIMEOUT, CRASH, TOOL_CALL, RECOVERY
    val message: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class McpServerRuntimeState(
    val serverId: String,
    val status: McpConnectionStatus = McpConnectionStatus.DISCONNECTED,
    val sessionId: String? = null,
    val protocolVersion: String? = null,
    val serverInfoName: String? = null,
    val discoveredToolCount: Int = 0,
    val lastConnectedAtMs: Long? = null,
    val lastError: String? = null,
    val reconnectAttempts: Int = 0,
    val processAlive: Boolean = false,
    val recentLogs: List<String> = emptyList(),
)

data class McpToolCallResult(
    val toolId: String,
    val serverId: String,
    val success: Boolean,
    val contentText: String,
    val isError: Boolean = !success,
    val errorCode: String? = null,
    val durationMs: Long = 0L,
)

// ==================== PART 16: SPV DYNAMIC PERMISSION DELEGATION ====================

enum class McpGrantScopeType {
    SINGLE_TOOL,
    SPECIFIC_SERVER,
    SPECIFIC_WORKSPACE,
    CURRENT_TASK,
    TIME_LIMITED_SESSION,
}

enum class McpSupervisorDecisionKind {
    APPROVED,
    PARTIALLY_APPROVED,
    DENIED,
    ESCALATED_TO_OWNER,
}

enum class McpRequestStatus {
    PENDING,
    APPROVED,
    PARTIALLY_APPROVED,
    DENIED,
    ESCALATED_TO_OWNER,
    EXPIRED,
    REVOKED,
}

/** Persistent base permission assignment for an agent on a server (or "*" wildcard server). */
data class McpAgentBasePermission(
    val permissionId: String = "mperm-${UUID.randomUUID().toString().take(8)}",
    val agentId: String,
    val serverId: String, // "*" = all enabled servers in scope
    val maxLevel: McpPermissionLevel = McpPermissionLevel.READ,
    val allowedToolIds: Set<String> = emptySet(), // empty = all tools on server up to maxLevel
    val deniedToolIds: Set<String> = emptySet(),
    val allowDynamicRequest: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Structured permission request generated when an agent lacks permission for an MCP tool (Part 16.3). */
data class McpPermissionRequest(
    val requestId: String = "mpreq-${UUID.randomUUID().toString().take(8)}",
    val requesterAgentId: String,
    val assignedRole: String,
    val taskId: String? = null,
    val workspaceId: String? = null,
    val mcpServerId: String,
    val toolId: String,
    val requestedAction: String,
    val requestedPermissionLevel: McpPermissionLevel,
    val resourceScope: String = "",
    val justification: String = "",
    val requestedDurationMs: Long = 30 * 60_000L,
    val status: McpRequestStatus = McpRequestStatus.PENDING,
    val decisionReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null,
)

/** Scoped, revocable, auto-expiring permission grant issued by SPV or Owner (Part 16.5-16.6). */
data class McpScopedPermissionGrant(
    val grantId: String = "mgrant-${UUID.randomUUID().toString().take(8)}",
    val requestId: String? = null,
    val agentId: String,
    val grantedBy: String, // "supervisor" or "owner"
    val mcpServerId: String,
    val toolId: String?,   // null = server-level up to grantedLevel; non-null = single tool
    val grantedLevel: McpPermissionLevel,
    val scopeType: McpGrantScopeType = McpGrantScopeType.CURRENT_TASK,
    val taskId: String? = null,
    val workspaceId: String? = null,
    val resourceScope: String = "",
    val canDelegateFurther: Boolean = false, // Always false for non-SPV agents (Part 16.7)
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long,
    val revokedAt: Long? = null,
    val revokeReason: String? = null,
) {
    fun isActive(nowMs: Long = System.currentTimeMillis()): Boolean =
        revokedAt == null && nowMs < expiresAt
}

/** Immutable audit trail record (Part 16.11). Never editable by AI agents; never contains secrets. */
data class McpPermissionAuditRecord(
    val eventId: String = "maud-${UUID.randomUUID().toString().take(8)}",
    val requestId: String? = null,
    val requestingAgentId: String,
    val supervisorId: String,
    val toolId: String,
    val mcpServerId: String,
    val requestedPermission: McpPermissionLevel,
    val grantedPermission: McpPermissionLevel,
    val decision: McpSupervisorDecisionKind,
    val decisionReason: String,
    val resourceScope: String = "",
    val taskId: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val expirationMs: Long? = null,
    val revocationTimestampMs: Long? = null,
)

data class McpAuthorizationCheckResult(
    val allowed: Boolean,
    val reason: String,
    val requiresOwnerApproval: Boolean = false,
    val permissionRequired: Boolean = false,
    val grantUsed: McpScopedPermissionGrant? = null,
)

data class McpDriverCapability(
    val driverName: String,
    val supportsToolDiscovery: Boolean,
    val supportsToolInvocation: Boolean,
    val supportsNativeMcp: Boolean,
    val supportsToolResultContinuation: Boolean,
    val notes: String,
)
