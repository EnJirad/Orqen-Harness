package com.jarves.mh.mcp

import com.jarves.mh.multiagent.persistence.SecretSanitizer

/**
 * PART 16 — Centralized AI Supervisor (SPV) Authorization & Deterministic Policy Engine.
 *
 * Enforces:
 * - Independent per-agent base permissions + scoped temporary delegated grants
 * - Execution-time authorization checks (never trusting cached model tool lists)
 * - SPV evaluation with deterministic policy validation (SPV cannot approve its own privilege
 *   escalation, cannot override owner policies, and non-SPV agents cannot re-delegate)
 * - Mandatory Owner approval for high-risk/destructive operations
 * - Automatic expiration & revocation + immutable audit trail
 */
class McpAuthorizationEngine(
    private val store: McpStore,
    private val ownerMaxDelegableLevel: () -> McpPermissionLevel = { McpPermissionLevel.EXECUTE },
    private val isSupervisorAvailable: () -> Boolean = { true },
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val supervisorAgentIds = setOf("agent-01", "supervisor")

    // ==================== BASE PERMISSIONS ====================

    fun setBasePermission(perm: McpAgentBasePermission) {
        store.upsertBasePermission(perm.copy(updatedAt = nowProvider()))
    }

    fun getBasePermissions(agentId: String? = null): List<McpAgentBasePermission> =
        store.listBasePermissions(agentId)

    // ==================== EXECUTION-TIME ENFORCEMENT (16.9) ====================

    fun authorizeExecution(
        agentId: String,
        server: McpServerConfig,
        tool: McpToolDefinition,
        isServerConnected: Boolean,
        workspaceId: String? = null,
        taskId: String? = null,
    ): McpAuthorizationCheckResult {
        val now = nowProvider()

        // 1. Server must be enabled and actively connected
        if (!server.enabled) {
            return McpAuthorizationCheckResult(false, "MCP server '${server.name}' is disabled.")
        }
        if (!isServerConnected || !tool.available) {
            return McpAuthorizationCheckResult(false, "MCP server '${server.name}' is disconnected; tool execution blocked.")
        }

        // 2. Workspace scope boundary check
        if (server.scope == McpScope.WORKSPACE && server.workspaceId != null && server.workspaceId != workspaceId) {
            return McpAuthorizationCheckResult(
                allowed = false,
                reason = "Workspace scope violation: server '${server.name}' belongs to workspace '${server.workspaceId}', not '$workspaceId'.",
            )
        }

        // 3. Tool-level DENY policy (Owner hard block)
        if (tool.policy == McpToolPolicy.DENY) {
            return McpAuthorizationCheckResult(false, "Tool '${tool.namespacedToolId}' is explicitly DENIED by owner policy.")
        }

        // 4. High-risk destructive operations require explicit Owner grant (SPV alone cannot override!)
        if (tool.isHighRisk) {
            val ownerGrant = findMatchingActiveGrant(
                agentId = agentId,
                serverId = server.serverId,
                toolId = tool.namespacedToolId,
                requiredLevel = tool.requiredPermissionLevel,
                workspaceId = workspaceId,
                taskId = taskId,
                nowMs = now,
                requireOwnerGrant = true,
            )
            if (ownerGrant != null) {
                return McpAuthorizationCheckResult(
                    allowed = true,
                    reason = "Authorized by explicit Owner grant (${ownerGrant.grantId}).",
                    grantUsed = ownerGrant,
                )
            }
            return McpAuthorizationCheckResult(
                allowed = false,
                reason = "High-risk operation '${tool.namespacedToolId}' requires explicit Owner approval.",
                requiresOwnerApproval = true,
                permissionRequired = true,
            )
        }

        // 5. Check agent's base permissions (server-specific takes precedence over wildcard "*")
        val basePerms = store.listBasePermissions(agentId)
        val effectiveBase = basePerms.firstOrNull { it.serverId == server.serverId }
            ?: basePerms.firstOrNull { it.serverId == "*" }

        if (effectiveBase != null) {
            if (tool.namespacedToolId in effectiveBase.deniedToolIds || tool.rawToolName in effectiveBase.deniedToolIds) {
                return McpAuthorizationCheckResult(false, "Tool '${tool.namespacedToolId}' is explicitly denied for agent '$agentId'.")
            }
            val toolAllowedByBaseList = effectiveBase.allowedToolIds.isEmpty() ||
                tool.namespacedToolId in effectiveBase.allowedToolIds ||
                tool.rawToolName in effectiveBase.allowedToolIds
            if (toolAllowedByBaseList && effectiveBase.maxLevel.satisfies(tool.requiredPermissionLevel)) {
                return McpAuthorizationCheckResult(
                    allowed = true,
                    reason = "Authorized by agent base permission (${effectiveBase.maxLevel.name}).",
                )
            }
        }

        // 6. Check active, non-expired, non-revoked scoped delegated grants (Part 16.5 & 16.6)
        val activeGrant = findMatchingActiveGrant(
            agentId = agentId,
            serverId = server.serverId,
            toolId = tool.namespacedToolId,
            requiredLevel = tool.requiredPermissionLevel,
            workspaceId = workspaceId,
            taskId = taskId,
            nowMs = now,
            requireOwnerGrant = false,
        )
        if (activeGrant != null) {
            return McpAuthorizationCheckResult(
                allowed = true,
                reason = "Authorized by active scoped grant ${activeGrant.grantId} (expires ${activeGrant.expiresAt}).",
                grantUsed = activeGrant,
            )
        }

        // 7. Not authorized -> signal PERMISSION_REQUIRED so agent can submit a dynamic request to SPV!
        return McpAuthorizationCheckResult(
            allowed = false,
            reason = "PERMISSION_REQUIRED: Agent '$agentId' lacks ${tool.requiredPermissionLevel.name} permission for '${tool.namespacedToolId}'.",
            permissionRequired = true,
        )
    }

    // ==================== DYNAMIC PERMISSION REQUEST & SPV EVALUATION (16.3 - 16.8) ====================

    /**
     * Evaluates a dynamic [McpPermissionRequest] through SPV + Deterministic Policy Engine.
     * Returns the persisted [McpSupervisorDecisionKind] and optional [McpScopedPermissionGrant].
     */
    fun evaluatePermissionRequest(
        request: McpPermissionRequest,
        server: McpServerConfig,
        tool: McpToolDefinition,
        activeTaskAgentIds: Set<String> = emptySet(),
        supervisorId: String = "agent-01",
    ): Pair<McpSupervisorDecisionKind, McpScopedPermissionGrant?> {
        val now = nowProvider()

        // Guard 1: SPV cannot approve its own privilege escalation request (Part 16.1)
        if (request.requesterAgentId in supervisorAgentIds && request.requesterAgentId == supervisorId) {
            return recordDecision(
                request = request,
                supervisorId = supervisorId,
                decision = McpSupervisorDecisionKind.ESCALATED_TO_OWNER,
                grantedLevel = McpPermissionLevel.DENY,
                reason = "SPV cannot approve its own privilege escalation; escalated to Owner.",
                now = now,
            ) to null
        }

        // Guard 2: Non-SPV agent attempting to act as supervisor / re-delegate (Part 16.7)
        if (supervisorId !in supervisorAgentIds && supervisorId != "owner") {
            return recordDecision(
                request = request,
                supervisorId = supervisorId,
                decision = McpSupervisorDecisionKind.DENIED,
                grantedLevel = McpPermissionLevel.DENY,
                reason = "Delegation rejected: only SPV or Owner can grant permissions (no circular/agent re-delegation).",
                now = now,
            ) to null
        }

        // Guard 3: If SPV is unavailable, fail closed & escalate to Owner (Test scenario 15)
        if (!isSupervisorAvailable() && supervisorId != "owner") {
            return recordDecision(
                request = request,
                supervisorId = supervisorId,
                decision = McpSupervisorDecisionKind.ESCALATED_TO_OWNER,
                grantedLevel = McpPermissionLevel.DENY,
                reason = "Supervisor is currently unavailable; escalated to Owner for approval.",
                now = now,
            ) to null
        }

        // Guard 4: Tool explicitly DENIED by owner policy -> hard reject
        if (tool.policy == McpToolPolicy.DENY) {
            return recordDecision(
                request = request,
                supervisorId = supervisorId,
                decision = McpSupervisorDecisionKind.DENIED,
                grantedLevel = McpPermissionLevel.DENY,
                reason = "Denied by Owner policy: tool '${tool.namespacedToolId}' is blocked.",
                now = now,
            ) to null
        }

        // Guard 5: Workspace boundary enforcement
        if (server.scope == McpScope.WORKSPACE && server.workspaceId != null && server.workspaceId != request.workspaceId) {
            return recordDecision(
                request = request,
                supervisorId = supervisorId,
                decision = McpSupervisorDecisionKind.DENIED,
                grantedLevel = McpPermissionLevel.DENY,
                reason = "Denied: requested workspace '${request.workspaceId}' does not match server workspace '${server.workspaceId}'.",
                now = now,
            ) to null
        }

        // Guard 6: Task ownership check (when activeTaskAgentIds is provided)
        if (activeTaskAgentIds.isNotEmpty() && request.requesterAgentId !in activeTaskAgentIds) {
            return recordDecision(
                request = request,
                supervisorId = supervisorId,
                decision = McpSupervisorDecisionKind.DENIED,
                grantedLevel = McpPermissionLevel.DENY,
                reason = "Denied: agent '${request.requesterAgentId}' is not assigned to active task '${request.taskId}'.",
                now = now,
            ) to null
        }

        // Guard 7: High-risk destructive operations MUST escalate to Owner (Part 16.8)
        if (tool.isHighRisk && supervisorId != "owner") {
            return recordDecision(
                request = request,
                supervisorId = supervisorId,
                decision = McpSupervisorDecisionKind.ESCALATED_TO_OWNER,
                grantedLevel = McpPermissionLevel.DENY,
                reason = "High-risk operation '${tool.namespacedToolId}' requires mandatory Owner approval.",
                now = now,
            ) to null
        }

        // Guard 8: Owner maximum delegable authority check
        val maxOwnerAllowed = ownerMaxDelegableLevel()
        if (!maxOwnerAllowed.satisfies(request.requestedPermissionLevel)) {
            if (maxOwnerAllowed == McpPermissionLevel.DENY) {
                return recordDecision(
                    request = request,
                    supervisorId = supervisorId,
                    decision = McpSupervisorDecisionKind.DENIED,
                    grantedLevel = McpPermissionLevel.DENY,
                    reason = "Denied: Owner policy disables dynamic MCP permission delegation.",
                    now = now,
                ) to null
            }
            // Partial approval if Owner allows a lower level that still satisfies READ
            if (maxOwnerAllowed.satisfies(tool.requiredPermissionLevel)) {
                val grant = issueScopedGrant(request, supervisorId, maxOwnerAllowed, now)
                recordDecision(
                    request = request,
                    supervisorId = supervisorId,
                    decision = McpSupervisorDecisionKind.PARTIALLY_APPROVED,
                    grantedLevel = maxOwnerAllowed,
                    reason = "Partially approved at ${maxOwnerAllowed.name} (capped by Owner max authority).",
                    now = now,
                    grant = grant,
                )
                return McpSupervisorDecisionKind.PARTIALLY_APPROVED to grant
            }
            return recordDecision(
                request = request,
                supervisorId = supervisorId,
                decision = McpSupervisorDecisionKind.ESCALATED_TO_OWNER,
                grantedLevel = McpPermissionLevel.DENY,
                reason = "Requested ${request.requestedPermissionLevel.name} exceeds SPV authority (${maxOwnerAllowed.name}); escalated to Owner.",
                now = now,
            ) to null
        }

        // Guard 9: Role relevance & least-privilege partial approval
        val roleUpper = request.assignedRole.uppercase()
        val isReadOnlyRole = roleUpper.contains("REVIEW") || roleUpper.contains("TEST") || roleUpper.contains("QA")
        if (isReadOnlyRole && request.requestedPermissionLevel == McpPermissionLevel.WRITE && tool.requiredPermissionLevel == McpPermissionLevel.READ) {
            val grant = issueScopedGrant(request, supervisorId, McpPermissionLevel.READ, now)
            recordDecision(
                request = request,
                supervisorId = supervisorId,
                decision = McpSupervisorDecisionKind.PARTIALLY_APPROVED,
                grantedLevel = McpPermissionLevel.READ,
                reason = "Partially approved READ-only scoped grant for reviewer/QA role (least privilege).",
                now = now,
                grant = grant,
            )
            return McpSupervisorDecisionKind.PARTIALLY_APPROVED to grant
        }

        // Excessive permission request beyond what the tool needs -> cap to exact tool requirement (PARTIALLY_APPROVED)
        if (request.requestedPermissionLevel.rank > tool.requiredPermissionLevel.rank) {
            val grant = issueScopedGrant(request, supervisorId, tool.requiredPermissionLevel, now)
            recordDecision(
                request = request,
                supervisorId = supervisorId,
                decision = McpSupervisorDecisionKind.PARTIALLY_APPROVED,
                grantedLevel = tool.requiredPermissionLevel,
                reason = "Partially approved: capped from ${request.requestedPermissionLevel.name} to exact required level ${tool.requiredPermissionLevel.name}.",
                now = now,
                grant = grant,
            )
            return McpSupervisorDecisionKind.PARTIALLY_APPROVED to grant
        }

        // Approved with strict single-tool + current-task/session scope!
        val grant = issueScopedGrant(request, supervisorId, request.requestedPermissionLevel, now)
        recordDecision(
            request = request,
            supervisorId = supervisorId,
            decision = McpSupervisorDecisionKind.APPROVED,
            grantedLevel = request.requestedPermissionLevel,
            reason = "Approved scoped ${request.requestedPermissionLevel.name} grant for '${tool.namespacedToolId}'.",
            now = now,
            grant = grant,
        )
        return McpSupervisorDecisionKind.APPROVED to grant
    }

    /** Owner explicit approval for an escalated or pending request (Part 16.8). */
    fun ownerApproveRequest(
        requestId: String,
        grantedLevel: McpPermissionLevel? = null,
        durationMs: Long = 30 * 60_000L,
    ): McpScopedPermissionGrant? {
        val req = store.listPermissionRequests().firstOrNull { it.requestId == requestId } ?: return null
        val now = nowProvider()
        val level = grantedLevel ?: req.requestedPermissionLevel
        val grant = issueScopedGrant(req.copy(requestedDurationMs = durationMs), "owner", level, now)
        recordDecision(
            request = req,
            supervisorId = "owner",
            decision = if (level == req.requestedPermissionLevel) McpSupervisorDecisionKind.APPROVED else McpSupervisorDecisionKind.PARTIALLY_APPROVED,
            grantedLevel = level,
            reason = "Explicitly approved by Owner (${level.name}).",
            now = now,
            grant = grant,
        )
        return grant
    }

    fun ownerDenyRequest(requestId: String, reason: String = "Denied by Owner"): Boolean {
        val req = store.listPermissionRequests().firstOrNull { it.requestId == requestId } ?: return false
        recordDecision(
            request = req,
            supervisorId = "owner",
            decision = McpSupervisorDecisionKind.DENIED,
            grantedLevel = McpPermissionLevel.DENY,
            reason = reason,
            now = nowProvider(),
        )
        return true
    }

    // ==================== EXPIRATION & REVOCATION (16.6) ====================

    fun revokeGrant(grantId: String, reason: String, revokedBy: String = "supervisor"): Boolean {
        val existing = store.listGrants().firstOrNull { it.grantId == grantId } ?: return false
        if (existing.revokedAt != null) return false
        val now = nowProvider()
        store.upsertGrant(existing.copy(revokedAt = now, revokeReason = reason))
        store.insertAudit(
            McpPermissionAuditRecord(
                requestId = existing.requestId,
                requestingAgentId = existing.agentId,
                supervisorId = revokedBy,
                toolId = existing.toolId ?: "*",
                mcpServerId = existing.mcpServerId,
                requestedPermission = existing.grantedLevel,
                grantedPermission = McpPermissionLevel.DENY,
                decision = McpSupervisorDecisionKind.DENIED,
                decisionReason = "REVOKED: ${SecretSanitizer.sanitize(reason)}",
                resourceScope = existing.resourceScope,
                taskId = existing.taskId,
                timestampMs = now,
                expirationMs = existing.expiresAt,
                revocationTimestampMs = now,
            ),
        )
        return true
    }

    fun revokeGrantsForTask(taskId: String, reason: String = "Task finished/cancelled"): Int {
        var count = 0
        store.listGrants().filter { it.taskId == taskId && it.revokedAt == null }.forEach { g ->
            if (revokeGrant(g.grantId, reason, "supervisor")) count++
        }
        return count
    }

    fun revokeGrantsForAgent(agentId: String, reason: String = "Agent removed/disabled"): Int {
        var count = 0
        store.listGrants(agentId).filter { it.revokedAt == null }.forEach { g ->
            if (revokeGrant(g.grantId, reason, "supervisor")) count++
        }
        return count
    }

    fun revokeGrantsForServer(serverId: String, reason: String = "MCP server disabled/removed"): Int {
        var count = 0
        store.listGrants().filter { it.mcpServerId == serverId && it.revokedAt == null }.forEach { g ->
            if (revokeGrant(g.grantId, reason, "supervisor")) count++
        }
        return count
    }

    fun listRequests(): List<McpPermissionRequest> = store.listPermissionRequests()
    fun listGrants(agentId: String? = null): List<McpScopedPermissionGrant> = store.listGrants(agentId)
    fun listAuditLog(limit: Int = 200): List<McpPermissionAuditRecord> = store.listAuditRecords(limit)

    private fun issueScopedGrant(
        req: McpPermissionRequest,
        grantedBy: String,
        level: McpPermissionLevel,
        now: Long,
    ): McpScopedPermissionGrant {
        val duration = req.requestedDurationMs.coerceIn(60_000L, 4 * 60 * 60_000L)
        val grant = McpScopedPermissionGrant(
            requestId = req.requestId,
            agentId = req.requesterAgentId,
            grantedBy = grantedBy,
            mcpServerId = req.mcpServerId,
            toolId = req.toolId,
            grantedLevel = level,
            scopeType = if (!req.taskId.isNullOrBlank()) McpGrantScopeType.CURRENT_TASK else McpGrantScopeType.SINGLE_TOOL,
            taskId = req.taskId,
            workspaceId = req.workspaceId,
            resourceScope = req.resourceScope,
            canDelegateFurther = false,
            createdAt = now,
            expiresAt = now + duration,
        )
        store.upsertGrant(grant)
        return grant
    }

    private fun recordDecision(
        request: McpPermissionRequest,
        supervisorId: String,
        decision: McpSupervisorDecisionKind,
        grantedLevel: McpPermissionLevel,
        reason: String,
        now: Long,
        grant: McpScopedPermissionGrant? = null,
    ): McpSupervisorDecisionKind {
        val reqStatus = when (decision) {
            McpSupervisorDecisionKind.APPROVED -> McpRequestStatus.APPROVED
            McpSupervisorDecisionKind.PARTIALLY_APPROVED -> McpRequestStatus.PARTIALLY_APPROVED
            McpSupervisorDecisionKind.DENIED -> McpRequestStatus.DENIED
            McpSupervisorDecisionKind.ESCALATED_TO_OWNER -> McpRequestStatus.ESCALATED_TO_OWNER
        }
        val cleanReason = SecretSanitizer.sanitize(reason)
        store.upsertPermissionRequest(
            request.copy(
                status = reqStatus,
                decisionReason = cleanReason,
                resolvedAt = if (decision == McpSupervisorDecisionKind.ESCALATED_TO_OWNER) null else now,
            ),
        )
        store.insertAudit(
            McpPermissionAuditRecord(
                requestId = request.requestId,
                requestingAgentId = request.requesterAgentId,
                supervisorId = supervisorId,
                toolId = request.toolId,
                mcpServerId = request.mcpServerId,
                requestedPermission = request.requestedPermissionLevel,
                grantedPermission = grantedLevel,
                decision = decision,
                decisionReason = cleanReason,
                resourceScope = request.resourceScope,
                taskId = request.taskId,
                timestampMs = now,
                expirationMs = grant?.expiresAt,
            ),
        )
        return decision
    }

    private fun findMatchingActiveGrant(
        agentId: String,
        serverId: String,
        toolId: String,
        requiredLevel: McpPermissionLevel,
        workspaceId: String?,
        taskId: String?,
        nowMs: Long,
        requireOwnerGrant: Boolean,
    ): McpScopedPermissionGrant? = store.listGrants(agentId).firstOrNull { g ->
        g.isActive(nowMs) &&
            (!requireOwnerGrant || g.grantedBy == "owner") &&
            g.mcpServerId == serverId &&
            (g.toolId == null || g.toolId == toolId) &&
            g.grantedLevel.satisfies(requiredLevel) &&
            (g.workspaceId == null || g.workspaceId == workspaceId) &&
            (g.taskId == null || g.taskId == taskId)
    }
}
