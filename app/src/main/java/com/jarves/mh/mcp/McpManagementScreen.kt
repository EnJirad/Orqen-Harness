package com.jarves.mh.mcp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.multiagent.model.AgentState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val McpEmerald = Color(0xFF10B981)
private val McpAmber = Color(0xFFF59E0B)
private val McpRed = Color(0xFFDC2626)
private val McpBlue = Color(0xFF38BDF8)

/**
 * Complete ORQEN MCP Management & SPV Authorization UI (Parts 5, 6, 16.10).
 * - Tab 0: MCP Servers (Summary stats, Quick Presets [Neon/Git/GitHub/...], Server Cards, Add/Edit Paste-First/Manual/JSON, Details & Diagnostics)
 * - Tab 1: Discovered MCP Tools (Real discovered tools, Input Schema Inspector, Allow/Ask/Deny policy)
 * - Tab 2: SPV Permissions (Per-agent base permissions, Pending/Escalated Requests with Owner Approve/Deny, Active Scoped Grants with Revoke, Audit Log)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpManagementScreen(
    manager: McpConnectionManager,
    agents: List<AgentState>,
    activeWorkspaceId: String?,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val servers by manager.servers.collectAsState()
    val runtimeStates by manager.runtimeStates.collectAsState()
    val tools by manager.tools.collectAsState()
    val diagnostics by manager.diagnostics.collectAsState()
    val requests by manager.permissionRequests.collectAsState()
    val grants by manager.permissionGrants.collectAsState()
    val auditLog by manager.permissionAuditLog.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0=Servers, 1=Tools, 2=Permissions
    var showAddDialog by remember { mutableStateOf(false) }
    var editingDraft by remember { mutableStateOf<ParsedMcpDraft?>(null) }
    var detailServerId by remember { mutableStateOf<String?>(null) }
    var inspectTool by remember { mutableStateOf<McpToolDefinition?>(null) }

    val connectedCount = servers.count { runtimeStates[it.serverId]?.status == McpConnectionStatus.CONNECTED }
    val errorCount = servers.count { runtimeStates[it.serverId]?.status == McpConnectionStatus.ERROR }
    val disconnectedCount = (servers.size - connectedCount - errorCount).coerceAtLeast(0)
    val availableToolsCount = tools.count { it.available }

    if (showAddDialog) {
        AddOrEditMcpServerDialog(
            initialDraft = editingDraft,
            activeWorkspaceId = activeWorkspaceId,
            onDismiss = {
                showAddDialog = false
                editingDraft = null
            },
            onSave = { drafts ->
                drafts.forEach { draft ->
                    val saved = manager.saveServer(
                        config = draft.config,
                        secretEnv = draft.extractedSecretEnv,
                        secretHeaders = draft.extractedSecretHeaders,
                    )
                    if (saved.enabled) {
                        scope.launch { manager.connectServer(saved.serverId) }
                    }
                }
                showAddDialog = false
                editingDraft = null
            },
        )
    }

    detailServerId?.let { sid ->
        val srv = servers.firstOrNull { it.serverId == sid }
        if (srv != null) {
            McpServerDetailsDialog(
                server = srv,
                runtime = runtimeStates[sid] ?: McpServerRuntimeState(sid),
                serverTools = tools.filter { it.serverId == sid },
                serverDiagnostics = diagnostics.filter { it.serverId == sid },
                driverMatrix = manager.getDriverCapabilityMatrix(),
                onConnect = { scope.launch { manager.connectServer(sid) } },
                onDisconnect = { scope.launch { manager.disconnectServer(sid) } },
                onRefreshTools = { scope.launch { manager.refreshTools(sid) } },
                onEdit = {
                    detailServerId = null
                    editingDraft = ParsedMcpDraft(config = srv)
                    showAddDialog = true
                },
                onDelete = {
                    detailServerId = null
                    scope.launch { manager.deleteServer(sid) }
                },
                onDismiss = { detailServerId = null },
            )
        }
    }

    inspectTool?.let { t ->
        AlertDialog(
            onDismissRequest = { inspectTool = null },
            title = { Text(t.namespacedToolId, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.description.ifBlank { "No description provided by server." }, fontSize = 12.sp)
                    Text(
                        text = "Server: ${t.serverName} · Level: ${t.requiredPermissionLevel.label}${if (t.isHighRisk) " · HIGH RISK (Owner Approval)" else ""}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (t.isHighRisk) McpRed else McpBlue,
                    )
                    Text("Input Schema (JSON):", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    ) {
                        Text(
                            text = t.inputSchemaJson,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { inspectTool = null }) { Text("Close") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(top = 6.dp),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text("MCP Servers & Tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = "$connectedCount connected · $availableToolsCount tools · SPV Auth active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editingDraft = null
                        showAddDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add MCP Server")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Servers (${servers.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Tools ($availableToolsCount)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        val pendingCount = requests.count { it.status == McpRequestStatus.PENDING || it.status == McpRequestStatus.ESCALATED_TO_OWNER }
                        Text(
                            text = if (pendingCount > 0) "SPV Auth ($pendingCount)" else "SPV Auth",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                )
            }

            when (selectedTab) {
                0 -> McpServersTab(
                    servers = servers,
                    runtimeStates = runtimeStates,
                    connectedCount = connectedCount,
                    disconnectedCount = disconnectedCount,
                    errorCount = errorCount,
                    totalTools = availableToolsCount,
                    onAddManual = {
                        editingDraft = null
                        showAddDialog = true
                    },
                    onAddTemplate = { tmpl ->
                        editingDraft = ParsedMcpDraft(config = tmpl.copy(serverId = "mcp-${java.util.UUID.randomUUID().toString().take(8)}"))
                        showAddDialog = true
                    },
                    onToggleEnabled = { srv, enabled ->
                        manager.saveServer(srv.copy(enabled = enabled))
                    },
                    onConnect = { srv -> scope.launch { manager.connectServer(srv.serverId) } },
                    onDisconnect = { srv -> scope.launch { manager.disconnectServer(srv.serverId) } },
                    onOpenDetails = { srv -> detailServerId = srv.serverId },
                    onDelete = { srv -> scope.launch { manager.deleteServer(srv.serverId) } },
                )
                1 -> McpToolsTab(
                    tools = tools,
                    onInspect = { inspectTool = it },
                    onChangePolicy = { tool, policy ->
                        manager.toolRegistry.updateToolPolicy(tool.namespacedToolId, policy)
                        manager.refreshStateFlows()
                    },
                )
                2 -> McpPermissionsTab(
                    agents = agents,
                    basePermissions = manager.authEngine.getBasePermissions(),
                    requests = requests,
                    grants = grants,
                    auditLog = auditLog,
                    onSetAgentBaseLevel = { agentId, level ->
                        manager.authEngine.setBasePermission(
                            McpAgentBasePermission(agentId = agentId, serverId = "*", maxLevel = level),
                        )
                        manager.refreshStateFlows()
                    },
                    onOwnerApprove = { reqId ->
                        manager.authEngine.ownerApproveRequest(reqId)
                        manager.refreshStateFlows()
                    },
                    onOwnerDeny = { reqId ->
                        manager.authEngine.ownerDenyRequest(reqId)
                        manager.refreshStateFlows()
                    },
                    onRevokeGrant = { grantId ->
                        manager.authEngine.revokeGrant(grantId, "Revoked by Owner from SPV Dashboard", "owner")
                        manager.refreshStateFlows()
                    },
                )
            }
        }
    }
}

@Composable
private fun McpServersTab(
    servers: List<McpServerConfig>,
    runtimeStates: Map<String, McpServerRuntimeState>,
    connectedCount: Int,
    disconnectedCount: Int,
    errorCount: Int,
    totalTools: Int,
    onAddManual: () -> Unit,
    onAddTemplate: (McpServerConfig) -> Unit,
    onToggleEnabled: (McpServerConfig, Boolean) -> Unit,
    onConnect: (McpServerConfig) -> Unit,
    onDisconnect: (McpServerConfig) -> Unit,
    onOpenDetails: (McpServerConfig) -> Unit,
    onDelete: (McpServerConfig) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Summary Stats Card
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatBadge("Total", "${servers.size}", MaterialTheme.colorScheme.onSurface)
                    StatBadge("Connected", "$connectedCount", McpEmerald)
                    StatBadge("Offline", "$disconnectedCount", MaterialTheme.colorScheme.onSurfaceVariant)
                    StatBadge("Error", "$errorCount", McpRed)
                    StatBadge("Tools", "$totalTools", McpBlue)
                }
            }
        }

        // Quick Add Presets (Neon, Git, GitHub, Filesystem, Postgres, Fetch)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("เพิ่มด่วน (Quick Presets)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onAddManual, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Icon(Icons.Default.ContentPaste, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Paste JSON / Add", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(McpConfigParser.quickTemplates, key = { it.first }) { (label, tmpl) ->
                        AssistChip(
                            onClick = { onAddTemplate(tmpl) },
                            label = { Text("+ $label", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (tmpl.transportType == McpTransportType.STDIO) Icons.Default.Terminal else Icons.Default.Cloud,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                            },
                        )
                    }
                }
            }
        }

        if (servers.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Hub, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("ยังไม่มี MCP Server", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            text = "แตะปุ่ม Quick Preset (เช่น Neon, Git, GitHub) หรือกด Paste JSON เพื่อเชื่อมต่อเครื่องมือให้ทีม AI ใช้งานจริง",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onAddManual) {
                            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("เพิ่ม MCP Server")
                        }
                    }
                }
            }
        } else {
            items(servers, key = { it.serverId }) { srv ->
                val rt = runtimeStates[srv.serverId] ?: McpServerRuntimeState(srv.serverId)
                val statusColor = when (rt.status) {
                    McpConnectionStatus.CONNECTED -> McpEmerald
                    McpConnectionStatus.CONNECTING, McpConnectionStatus.RECONNECTING -> McpAmber
                    McpConnectionStatus.ERROR -> McpRed
                    McpConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenDetails(srv) },
                ) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(statusColor))
                            Column(Modifier.weight(1f)) {
                                Text(srv.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = "${srv.transportType.displayName} · ${srv.scope.displayName} · ${rt.discoveredToolCount} tools",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = srv.enabled, onCheckedChange = { onToggleEnabled(srv, it) })
                        }
                        if (srv.description.isNotBlank()) {
                            Text(srv.description, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (!rt.lastError.isNullOrBlank()) {
                            Text(
                                text = "Error: ${rt.lastError}",
                                fontSize = 11.sp,
                                color = McpRed,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(6.dp), color = statusColor.copy(alpha = 0.14f)) {
                                Text(
                                    text = rt.status.displayName,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (rt.status == McpConnectionStatus.CONNECTED) {
                                OutlinedButton(onClick = { onDisconnect(srv) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                    Text("Disconnect", fontSize = 11.sp)
                                }
                            } else {
                                Button(
                                    onClick = { onConnect(srv) },
                                    enabled = srv.enabled && rt.status != McpConnectionStatus.CONNECTING,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(if (rt.status == McpConnectionStatus.CONNECTING) "Connecting…" else "Connect", fontSize = 11.sp)
                                }
                            }
                            IconButton(onClick = { onOpenDetails(srv) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Settings, "Details", Modifier.size(17.dp))
                            }
                            IconButton(onClick = { onDelete(srv) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.DeleteOutline, "Delete", Modifier.size(17.dp), tint = McpRed)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBadge(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = color)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun McpToolsTab(
    tools: List<McpToolDefinition>,
    onInspect: (McpToolDefinition) -> Unit,
    onChangePolicy: (McpToolDefinition, McpToolPolicy) -> Unit,
) {
    if (tools.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "ยังไม่มี MCP Tools ที่ค้นพบ — เชื่อมต่อ MCP Server เพื่อดึงรายการเครื่องมือจริง",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tools, key = { it.namespacedToolId }) { tool ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().clickable { onInspect(tool) },
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (tool.available) McpEmerald else McpRed))
                        Text(
                            text = tool.namespacedToolId,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Surface(shape = RoundedCornerShape(6.dp), color = (if (tool.isHighRisk) McpRed else McpBlue).copy(alpha = 0.14f)) {
                            Text(
                                text = tool.requiredPermissionLevel.label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (tool.isHighRisk) McpRed else McpBlue,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (tool.description.isNotBlank()) {
                        Text(tool.description, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        McpToolPolicy.entries.forEach { policy ->
                            FilterChip(
                                selected = tool.policy == policy,
                                onClick = { onChangePolicy(tool, policy) },
                                label = { Text(policy.name, fontSize = 10.sp) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpPermissionsTab(
    agents: List<AgentState>,
    basePermissions: List<McpAgentBasePermission>,
    requests: List<McpPermissionRequest>,
    grants: List<McpScopedPermissionGrant>,
    auditLog: List<McpPermissionAuditRecord>,
    onSetAgentBaseLevel: (String, McpPermissionLevel) -> Unit,
    onOwnerApprove: (String) -> Unit,
    onOwnerDeny: (String) -> Unit,
    onRevokeGrant: (String) -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("สิทธิ์พื้นฐานของ AI แต่ละตัว (Agent Base Permissions)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        items(agents, key = { it.config.slot.id }) { agent ->
            val currentLevel = basePermissions.firstOrNull { it.agentId == agent.config.slot.id && it.serverId == "*" }?.maxLevel
                ?: McpPermissionLevel.READ
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${agent.config.name} (${agent.config.role.title})", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        McpPermissionLevel.entries.forEach { lvl ->
                            FilterChip(
                                selected = currentLevel == lvl,
                                onClick = { onSetAgentBaseLevel(agent.config.slot.id, lvl) },
                                label = { Text(lvl.label, fontSize = 10.sp) },
                            )
                        }
                    }
                }
            }
        }

        val pendingOrEscalated = requests.filter {
            it.status == McpRequestStatus.PENDING || it.status == McpRequestStatus.ESCALATED_TO_OWNER
        }
        if (pendingOrEscalated.isNotEmpty()) {
            item {
                Text("คำขอสิทธิ์ที่รอการอนุมัติ (${pendingOrEscalated.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = McpAmber)
            }
            items(pendingOrEscalated, key = { it.requestId }) { req ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = McpAmber.copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, McpAmber.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${req.requesterAgentId} ขอสิทธิ์ ${req.requestedPermissionLevel.label} → ${req.toolId}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(req.decisionReason ?: req.justification, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOwnerApprove(req.requestId) }, modifier = Modifier.weight(1f)) {
                                Text("Approve", fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = { onOwnerDeny(req.requestId) }, modifier = Modifier.weight(1f)) {
                                Text("Deny", fontSize = 11.sp, color = McpRed)
                            }
                        }
                    }
                }
            }
        }

        val activeGrants = grants.filter { it.isActive() }
        if (activeGrants.isNotEmpty()) {
            item {
                Text("สิทธิ์ชั่วคราวที่กำลังใช้งาน (${activeGrants.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            items(activeGrants, key = { it.grantId }) { g ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, McpEmerald.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${g.agentId} · ${g.grantedLevel.label} · ${g.toolId ?: g.mcpServerId}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                text = "By: ${g.grantedBy} · Scope: ${g.scopeType.name} · Expires: ${timeFmt.format(Date(g.expiresAt))}",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onRevokeGrant(g.grantId) }) {
                            Text("Revoke", fontSize = 11.sp, color = McpRed, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            Text("บันทึกการตรวจสอบสิทธิ์ (Immutable Audit Log · ${auditLog.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        if (auditLog.isEmpty()) {
            item {
                Text("ยังไม่มีประวัติการขอสิทธิ์", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(auditLog.take(30), key = { it.eventId }) { rec ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "[${rec.decision.name}] ${rec.requestingAgentId} → ${rec.toolId} (${rec.grantedPermission.label})",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(rec.decisionReason, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("SPV: ${rec.supervisorId} · ${timeFmt.format(Date(rec.timestampMs))}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddOrEditMcpServerDialog(
    initialDraft: ParsedMcpDraft?,
    activeWorkspaceId: String?,
    onDismiss: () -> Unit,
    onSave: (List<ParsedMcpDraft>) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var modeTab by remember { mutableIntStateOf(if (initialDraft != null) 1 else 0) } // 0=Paste, 1=Manual, 2=Advanced JSON
    var pasteText by remember { mutableStateOf("") }
    var parseResult by remember { mutableStateOf<McpParseResult?>(null) }

    val base = initialDraft?.config
    var name by remember(initialDraft) { mutableStateOf(base?.name.orEmpty()) }
    var description by remember(initialDraft) { mutableStateOf(base?.description.orEmpty()) }
    var transport by remember(initialDraft) { mutableStateOf(base?.transportType ?: McpTransportType.STREAMABLE_HTTP) }
    var scope by remember(initialDraft) { mutableStateOf(base?.scope ?: McpScope.GLOBAL) }
    var command by remember(initialDraft) { mutableStateOf(base?.command.orEmpty()) }
    var argsText by remember(initialDraft) { mutableStateOf(base?.args?.joinToString(" ").orEmpty()) }
    var remoteUrl by remember(initialDraft) { mutableStateOf(base?.remoteUrl.orEmpty()) }
    var secretKeyName by remember(initialDraft) {
        mutableStateOf(
            base?.secretHeaderKeys?.firstOrNull()
                ?: base?.secretEnvKeys?.firstOrNull()
                ?: if (base?.transportType == McpTransportType.STDIO) "API_KEY" else "Authorization",
        )
    }
    var secretValue by remember(initialDraft) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialDraft != null) "Configure MCP Server" else "Add MCP Server", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TabRow(selectedTabIndex = modeTab) {
                    Tab(selected = modeTab == 0, onClick = { modeTab = 0 }, text = { Text("Paste", fontSize = 11.sp) })
                    Tab(selected = modeTab == 1, onClick = { modeTab = 1 }, text = { Text("Manual", fontSize = 11.sp) })
                    Tab(selected = modeTab == 2, onClick = { modeTab = 2 }, text = { Text("JSON", fontSize = 11.sp) })
                }

                if (modeTab == 0 || modeTab == 2) {
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it },
                        label = { Text(if (modeTab == 0) "Paste JSON / `claude mcp add` / URL" else "Advanced MCP JSON") },
                        placeholder = { Text("{\"mcpServers\":{\"github\":{...}}}", fontSize = 11.sp) },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            clipboard.getText()?.text?.let { pasteText = it }
                        }) { Text("Paste", fontSize = 11.sp) }
                        Button(onClick = {
                            parseResult = McpConfigParser.parse(pasteText, scope, activeWorkspaceId)
                        }) { Text("Parse & Preview", fontSize = 11.sp) }
                    }
                    parseResult?.let { res ->
                        if (res.error != null) {
                            Text(res.error, fontSize = 11.sp, color = McpRed)
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = McpEmerald.copy(alpha = 0.12f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Parsed ${res.drafts.size} server(s):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = McpEmerald)
                                    res.drafts.forEach { d ->
                                        Text("• ${d.config.name} (${d.config.transportType.displayName})", fontSize = 11.sp)
                                        d.warnings.forEach { w -> Text("  ⚠ $w", fontSize = 10.sp, color = McpAmber) }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Server Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        McpTransportType.entries.forEach { t ->
                            FilterChip(
                                selected = transport == t,
                                onClick = { transport = t },
                                label = { Text(if (t == McpTransportType.STDIO) "STDIO" else if (t == McpTransportType.STREAMABLE_HTTP) "HTTP" else "SSE", fontSize = 10.sp) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        McpScope.entries.forEach { sc ->
                            FilterChip(
                                selected = scope == sc,
                                onClick = { scope = sc },
                                label = { Text(sc.displayName, fontSize = 10.sp) },
                            )
                        }
                    }
                    if (transport == McpTransportType.STDIO) {
                        OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Command (e.g. npx, python3)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = argsText, onValueChange = { argsText = it }, label = { Text("Arguments") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    } else {
                        OutlinedTextField(value = remoteUrl, onValueChange = { remoteUrl = it }, label = { Text("Remote MCP URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(value = secretKeyName, onValueChange = { secretKeyName = it }, label = { Text(if (transport == McpTransportType.STDIO) "Secret Env Key" else "Auth Header Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = secretValue,
                        onValueChange = { secretValue = it },
                        label = { Text("API Key / Token (stored encrypted in Keystore)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (modeTab == 0 || modeTab == 2) {
                        val res = parseResult ?: McpConfigParser.parse(pasteText, scope, activeWorkspaceId)
                        if (res.isSuccess) {
                            onSave(res.drafts)
                        } else {
                            parseResult = res
                        }
                    } else {
                        val cleanName = name.trim()
                        if (cleanName.isBlank()) return@Button
                        val cleanSecretKey = secretKeyName.trim()
                        val cleanSecretVal = secretValue.trim()
                        val isStdio = transport == McpTransportType.STDIO
                        val cfg = (base ?: McpServerConfig(name = cleanName, transportType = transport)).copy(
                            name = cleanName,
                            description = description.trim(),
                            transportType = transport,
                            command = if (isStdio) command.trim() else "",
                            args = if (isStdio) McpConfigParser.tokenizeShellArgs(argsText) else emptyList(),
                            remoteUrl = if (!isStdio) remoteUrl.trim() else "",
                            scope = scope,
                            workspaceId = if (scope == McpScope.WORKSPACE) activeWorkspaceId else null,
                            authType = if (!isStdio && cleanSecretVal.isNotBlank()) McpAuthType.BEARER_TOKEN else base?.authType ?: McpAuthType.NONE,
                            secretEnvKeys = if (isStdio && cleanSecretKey.isNotBlank() && cleanSecretVal.isNotBlank()) (base?.secretEnvKeys.orEmpty() + cleanSecretKey) else base?.secretEnvKeys.orEmpty(),
                            secretHeaderKeys = if (!isStdio && cleanSecretKey.isNotBlank() && cleanSecretVal.isNotBlank()) (base?.secretHeaderKeys.orEmpty() + cleanSecretKey) else base?.secretHeaderKeys.orEmpty(),
                        )
                        val draft = ParsedMcpDraft(
                            config = cfg,
                            extractedSecretEnv = if (isStdio && cleanSecretKey.isNotBlank() && cleanSecretVal.isNotBlank()) mapOf(cleanSecretKey to cleanSecretVal) else emptyMap(),
                            extractedSecretHeaders = if (!isStdio && cleanSecretKey.isNotBlank() && cleanSecretVal.isNotBlank()) mapOf(cleanSecretKey to cleanSecretVal) else emptyMap(),
                        )
                        onSave(listOf(draft))
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun McpServerDetailsDialog(
    server: McpServerConfig,
    runtime: McpServerRuntimeState,
    serverTools: List<McpToolDefinition>,
    serverDiagnostics: List<McpDiagnosticEvent>,
    driverMatrix: List<McpDriverCapability>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefreshTools: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${server.name} · Diagnostics", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text("Status: ${runtime.status.displayName} · Transport: ${server.transportType.displayName}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (server.transportType == McpTransportType.STDIO) {
                        Text("Command: ${server.command} ${server.args.joinToString(" ")}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    } else {
                        Text("Endpoint: ${server.remoteUrl}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    runtime.protocolVersion?.let { Text("Protocol: $it · Session: ${runtime.sessionId ?: "local"}", fontSize = 11.sp) }
                    runtime.lastError?.let { Text("Last Error: $it", fontSize = 11.sp, color = McpRed) }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (runtime.status == McpConnectionStatus.CONNECTED) {
                            OutlinedButton(onClick = onDisconnect) { Text("Disconnect", fontSize = 11.sp) }
                            Button(onClick = onRefreshTools) { Text("Refresh Tools", fontSize = 11.sp) }
                        } else {
                            Button(onClick = onConnect) { Text("Connect / Test", fontSize = 11.sp) }
                        }
                        OutlinedButton(onClick = onEdit) { Text("Edit", fontSize = 11.sp) }
                    }
                }
                item {
                    Text("Discovered Tools (${serverTools.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    if (serverTools.isEmpty()) {
                        Text("No tools discovered yet.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        serverTools.take(10).forEach { t ->
                            Text("• ${t.namespacedToolId} [${t.requiredPermissionLevel.label}]", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                item {
                    Text("Connection Diagnostics (${serverDiagnostics.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    serverDiagnostics.take(8).forEach { ev ->
                        Text("[${ev.level}/${ev.category}] ${ev.message}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (ev.level == "ERROR") McpRed else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (runtime.recentLogs.isNotEmpty()) {
                    item {
                        Text("Runtime Logs", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        runtime.recentLogs.takeLast(8).forEach { log ->
                            Text(log, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                item {
                    Text("Driver Capability Matrix", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    driverMatrix.forEach { d ->
                        Text("• ${d.driverName}: Discover=${d.supportsToolDiscovery}, Invoke=${d.supportsToolInvocation}, Continue=${d.supportsToolResultContinuation}", fontSize = 10.sp)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDelete) { Text("Delete", color = McpRed) }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
