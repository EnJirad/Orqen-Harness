package com.jarves.mh.multiagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.mcp.*
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.ui.theme.PocketOrange

/**
 * Phase G (9.2 & 9.3) — Dedicated AI Team MCP Settings Screen.
 *
 * Cleanly separated from Global MCP Settings (which manages connections & credentials):
 * - Select which globally connected MCP servers are enabled for this AI Team workspace
 * - Configure per-agent MCP permissions (DENY / READ / WRITE / EXECUTE)
 * - Let SPV auto-apply least-privilege permissions for the team
 * - Inspect pending/escalated requests (Owner Approve / Deny), active grants (Revoke), and audit log
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamMcpSettingsScreen(
    workspaceId: String,
    teamName: String,
    manager: McpConnectionManager,
    agents: List<AgentState>,
    onOpenGlobalMcpSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val servers by manager.servers.collectAsState()
    val runtimeStates by manager.runtimeStates.collectAsState()
    val teamEnabledMap by manager.teamEnabledServersFlow.collectAsState()
    val requests by manager.permissionRequests.collectAsState()
    val grants by manager.permissionGrants.collectAsState()
    val auditLog by manager.permissionAuditLog.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0=Team Servers & Agents, 1=Requests & Grants, 2=Audit
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var permRefreshTick by remember { mutableIntStateOf(0) }

    val enabledForTeam = teamEnabledMap[workspaceId]

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text("AI Team MCP Settings", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(teamName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    TextButton(onClick = onOpenGlobalMcpSettings) {
                        Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Global MCP", fontSize = 11.sp)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Servers & Agents", fontSize = 12.sp) })
                val pendingCount = requests.count { it.status == McpRequestStatus.ESCALATED_TO_OWNER || it.status == McpRequestStatus.PENDING }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Grants${if (pendingCount > 0) " ($pendingCount)" else ""}", fontSize = 12.sp) })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Audit (${auditLog.size})", fontSize = 12.sp) })
            }

            statusMessage?.let { msg ->
                Surface(
                    color = Color(0xFF10B981).copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(msg, fontSize = 11.5.sp, color = Color(0xFF059669), modifier = Modifier.padding(10.dp))
                }
            }

            when (selectedTab) {
                0 -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        FilledTonalButton(
                            onClick = {
                                val applied = manager.applyRecommendedTeamPermissions(workspaceId, agents)
                                permRefreshTick++
                                statusMessage = "Manager configured least-privilege permissions (${applied.size} rule(s)). High-risk tools still require Owner approval."
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Manager Auto-Configure Least-Privilege Permissions", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                    }

                    item {
                        Text("Connected Global MCP Servers for This Team", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    if (servers.isEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("No global MCP servers configured yet.", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Connect MCP servers in Global MCP Settings first, then enable them for this team here.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else {
                        items(servers, key = { it.serverId }) { srv ->
                            val rt = runtimeStates[srv.serverId]
                            val isConnected = rt?.status == McpConnectionStatus.CONNECTED
                            val isEnabledForThisTeam = enabledForTeam == null || srv.serverId in enabledForTeam
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(srv.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(
                                            text = "${if (isConnected) "Connected" else (rt?.status?.displayName ?: "Disconnected")} · ${rt?.discoveredToolCount ?: 0} tools",
                                            fontSize = 11.sp,
                                            color = if (isConnected) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = isEnabledForThisTeam,
                                        onCheckedChange = { checked ->
                                            manager.setTeamServerEnabled(workspaceId, srv.serverId, checked)
                                            statusMessage = if (checked) "Enabled '${srv.name}' for $teamName" else "Disabled '${srv.name}' for $teamName"
                                        },
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(4.dp))
                        Text("Per-Agent MCP Access Levels", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    items(agents, key = { it.config.slot.id }) { agent ->
                        val agentId = agent.config.slot.id
                        val currentPerm = remember(permRefreshTick, agentId) {
                            manager.authEngine.getBasePermissions(agentId).firstOrNull { it.serverId == "*" }
                                ?: manager.authEngine.getBasePermissions(agentId).firstOrNull()
                        }
                        val currentLevel = currentPerm?.maxLevel ?: McpPermissionLevel.READ
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(agent.config.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    Text(agent.config.role.title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    McpPermissionLevel.entries.forEach { level ->
                                        FilterChip(
                                            selected = currentLevel == level,
                                            onClick = {
                                                manager.authEngine.setBasePermission(
                                                    McpAgentBasePermission(
                                                        agentId = agentId,
                                                        serverId = "*",
                                                        maxLevel = level,
                                                        allowDynamicRequest = level != McpPermissionLevel.DENY,
                                                    ),
                                                )
                                                manager.refreshStateFlows()
                                                permRefreshTick++
                                                statusMessage = "Updated ${agent.config.name} MCP access -> ${level.label}"
                                            },
                                            label = { Text(level.label, fontSize = 10.5.sp) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val escalated = requests.filter { it.status == McpRequestStatus.ESCALATED_TO_OWNER || it.status == McpRequestStatus.PENDING }
                    item { Text("Pending Owner Approvals (${escalated.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    items(escalated, key = { it.requestId }) { req ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${req.requesterAgentId} (${req.assignedRole}) -> ${req.toolId}", fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text(req.decisionReason ?: req.justification, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            manager.authEngine.ownerApproveRequest(req.requestId)
                                            manager.refreshStateFlows()
                                            statusMessage = "Approved ${req.toolId} for ${req.requesterAgentId}"
                                        },
                                    ) { Text("Approve", fontSize = 11.sp) }
                                    OutlinedButton(
                                        onClick = {
                                            manager.authEngine.ownerDenyRequest(req.requestId)
                                            manager.refreshStateFlows()
                                            statusMessage = "Denied ${req.toolId} for ${req.requesterAgentId}"
                                        },
                                    ) { Text("Deny", fontSize = 11.sp) }
                                }
                            }
                        }
                    }

                    val activeGrants = grants.filter { it.isActive() }
                    item {
                        Spacer(Modifier.height(6.dp))
                        Text("Active Scoped Grants (${activeGrants.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    items(activeGrants, key = { it.grantId }) { grant ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${grant.agentId} · ${grant.toolId ?: "*"} (${grant.grantedLevel.label})", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text("By: ${grant.grantedBy} · Scope: ${grant.scopeType.name}", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = {
                                    manager.authEngine.revokeGrant(grant.grantId, "Revoked by Owner in Team MCP Settings", "owner")
                                    manager.refreshStateFlows()
                                    statusMessage = "Revoked grant ${grant.grantId}"
                                }) {
                                    Text("Revoke", color = Color(0xFFDC2626), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                2 -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (auditLog.isEmpty()) {
                        item {
                            Text("No MCP permission decisions recorded yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        items(auditLog.take(80), key = { it.eventId }) { rec ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("[${rec.decision.name}] ${rec.requestingAgentId} -> ${rec.toolId}", fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                                    Text("By ${rec.supervisorId}: ${rec.decisionReason}", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
