package com.jarves.mh.multiagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.model.Project
import com.jarves.mh.multiagent.engine.ExecutionPartStatus
import com.jarves.mh.multiagent.engine.MultiPartExecutionPlan
import com.jarves.mh.multiagent.engine.MultiPartPlanManager
import com.jarves.mh.multiagent.engine.MultiPartSpec
import com.jarves.mh.multiagent.model.ActivityType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.multiagent.model.PrioritizedCollaborationEvent
import com.jarves.mh.multiagent.supervisor.AgentHealthState
import com.jarves.mh.multiagent.supervisor.SupervisorDecisionRecord
import com.jarves.mh.multiagent.supervisor.SupervisorDecisionType
import com.jarves.mh.multiagent.supervisor.SupervisorOperationalStatus
import com.jarves.mh.multiagent.supervisor.SupervisorUiSnapshot
import com.jarves.mh.multiagent.tasks.TeamTask
import com.jarves.mh.provider.core.ProviderConfig
import com.jarves.mh.provider.core.ProviderType
import com.jarves.mh.runtime.AntigravityAuthState
import com.jarves.mh.runtime.AntigravityAuthStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class ManagerTab {
    STATUS,
    PLANS,
    UNIVERSE,
    DECISIONS,
}

private enum class PlanGroupFilter(val label: String) {
    ALL("ทั้งหมด"),
    ACTIVE("Active"),
    UPCOMING("Upcoming"),
    QUEUED("Queued"),
    COMPLETED("Completed"),
    PAUSED("Paused"),
    FAILED("Failed"),
    ARCHIVED("Archived"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagerWorkspaceScreen(
    project: Project,
    allProjects: List<Project>,
    workspaceDir: File,
    agents: List<AgentState>,
    tasks: List<TeamTask>,
    collaborationEvents: List<PrioritizedCollaborationEvent>,
    providerConfigs: List<ProviderConfig>,
    teamAgentAntigravityAuth: Map<AgentSlot, AntigravityAuthState>,
    defaultAntigravityAuth: AntigravityAuthState,
    hasProviderApiKey: (String) -> Boolean,
    activeSupervisedTaskId: String?,
    supervisorSnapshots: Map<String, SupervisorUiSnapshot>,
    isTeamRunning: Boolean,
    awaitingStartConfirmation: Boolean,
    ownerName: String = "",
    activeChatTargetSlots: Set<AgentSlot> = emptySet(),
    onSelectProject: (Project) -> Unit = {},
    onOpenAgentDetail: (AgentSlot) -> Unit = {},
    onOpenPlanFile: (String) -> Unit = {},
    onOpenChat: () -> Unit = {},
    onBack: () -> Unit,
) {
    var selectedTab by rememberSaveable(project.id) { mutableStateOf(ManagerTab.STATUS) }
    var selectedPlanFilter by rememberSaveable(project.id) { mutableStateOf(PlanGroupFilter.ALL) }
    var inspectedPart by remember { mutableStateOf<MultiPartSpec?>(null) }
    var inspectedDecision by remember { mutableStateOf<SupervisorDecisionRecord?>(null) }

    val activeSnapshot = activeSupervisedTaskId?.let { supervisorSnapshots[it] }
        ?: supervisorSnapshots.values.lastOrNull()

    val planState by produceState<Pair<MultiPartExecutionPlan?, Set<String>>>(
        initialValue = null to emptySet(),
        key1 = workspaceDir,
        key2 = activeSnapshot?.completedSteps,
        key3 = isTeamRunning,
    ) {
        value = withContext(Dispatchers.IO) {
            val loaded = runCatching { MultiPartPlanManager.loadPlan(workspaceDir) }.getOrNull()
            val reports = loaded?.parts
                ?.mapNotNull { part ->
                    if (File(workspaceDir, part.reportRelativePath).isFile) part.reportRelativePath else null
                }
                ?.toSet()
                .orEmpty()
            loaded to reports
        }
    }
    val multiPartPlan = planState.first
    val existingReportPaths = planState.second

    val allDecisions = remember(supervisorSnapshots, activeSnapshot) {
        val combined = linkedMapOf<String, SupervisorDecisionRecord>()
        supervisorSnapshots.values.forEach { snap ->
            snap.recoveryHistory.forEach { rec -> combined[rec.decisionId] = rec }
        }
        activeSnapshot?.recoveryHistory?.forEach { rec -> combined[rec.decisionId] = rec }
        combined.values.sortedByDescending { it.timestampMs }
    }

    // Detail dialog for selected Plan Part
    inspectedPart?.let { part ->
        AlertDialog(
            onDismissRequest = { inspectedPart = null },
            title = { Text("Part ${part.partNumber}: ${part.title}", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrqenStatusBadge(part.status.name, partStatusColor(part.status))
                    Text("เป้าหมาย: ${part.objective}", fontSize = 12.5.sp)
                    Text("Agent ที่รับผิดชอบ: ${resolveAgentDisplayName(part.assignedAgentId, agents)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (part.dependencies.isNotEmpty()) {
                        Text("เงื่อนไขก่อนเริ่ม (Dependencies): ${part.dependencies.joinToString(", ")}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (part.deliverables.isNotEmpty()) {
                        Text("ผลลัพธ์ที่คาดหวัง: ${part.deliverables.joinToString(" · ")}", fontSize = 11.5.sp)
                    }
                    Text(
                        "Review: ${part.reviewerVerdict ?: "PENDING"} · Tests: ${part.testExitCode?.let { if (it == 0) "PASS" else "FAIL($it)" } ?: "PENDING"}",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!part.lastError.isNullOrBlank()) {
                        Text("ข้อผิดพลาดล่าสุด: ${part.lastError}", fontSize = 11.5.sp, color = OrqenSemanticColors.Error)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        inspectedPart = null
                        onOpenPlanFile(part.specRelativePath)
                    }) { Text("เปิด Spec") }
                    if (part.reportRelativePath in existingReportPaths) {
                        TextButton(onClick = {
                            inspectedPart = null
                            onOpenPlanFile(part.reportRelativePath)
                        }) { Text("เปิด Report") }
                    }
                    TextButton(onClick = { inspectedPart = null }) { Text(stringResource(R.string.action_close)) }
                }
            },
        )
    }

    // Detail dialog for selected Decision / Recovery
    inspectedDecision?.let { dec ->
        val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
        val (title, explanation) = formatDecisionHuman(dec)
        AlertDialog(
            onDismissRequest = { inspectedDecision = null },
            title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OrqenStatusBadge(dec.result.ifBlank { dec.decisionType.name }, decisionColor(dec.decisionType))
                    Text("เหตุผล / สาเหตุ: ${dec.reason.ifBlank { explanation }}", fontSize = 12.5.sp)
                    if (dec.actionTaken.isNotBlank()) {
                        Text("การดำเนินการ: ${dec.actionTaken}", fontSize = 12.sp)
                    }
                    if (dec.evidence.isNotBlank()) {
                        Text("ข้อมูลประกอบ: ${dec.evidence}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Task ID: ${dec.taskId}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!dec.stepId.isNullOrBlank()) {
                        Text("Step ID: ${dec.stepId}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("เวลา: ${timeFmt.format(Date(dec.timestampMs))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { inspectedDecision = null }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                TopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.manager_title_fmt, project.name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.manager_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenChat) {
                            Icon(Icons.Default.Forum, contentDescription = "Team Chat")
                        }
                    },
                )

                // Manager 4-Tab Top Navigation (Status, Plans, AI Universe, Decisions)
                val tabs = listOf(
                    ManagerTab.STATUS to stringResource(R.string.manager_tab_status),
                    ManagerTab.PLANS to stringResource(R.string.manager_tab_plans),
                    ManagerTab.UNIVERSE to stringResource(R.string.manager_tab_universe),
                    ManagerTab.DECISIONS to stringResource(R.string.manager_tab_decisions),
                )
                PrimaryTabRow(
                    selectedTabIndex = ManagerTab.entries.indexOf(selectedTab),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    tabs.forEach { (tab, label) ->
                        val isSelected = selectedTab == tab
                        Tab(
                            selected = isSelected,
                            onClick = { selectedTab = tab },
                            text = {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            when (selectedTab) {
                ManagerTab.STATUS -> ManagerStatusTab(
                    agents = agents,
                    tasks = tasks,
                    collaborationEvents = collaborationEvents,
                    providerConfigs = providerConfigs,
                    teamAgentAntigravityAuth = teamAgentAntigravityAuth,
                    defaultAntigravityAuth = defaultAntigravityAuth,
                    hasProviderApiKey = hasProviderApiKey,
                    activeSnapshot = activeSnapshot,
                    isTeamRunning = isTeamRunning,
                    awaitingStartConfirmation = awaitingStartConfirmation,
                    onOpenAgentDetail = onOpenAgentDetail,
                )

                ManagerTab.PLANS -> ManagerPlansTab(
                    workspaceDir = workspaceDir,
                    agents = agents,
                    multiPartPlan = multiPartPlan,
                    tasks = tasks,
                    selectedFilter = selectedPlanFilter,
                    onSelectFilter = { selectedPlanFilter = it },
                    onInspectPart = { inspectedPart = it },
                    onOpenPlanFile = onOpenPlanFile,
                )

                ManagerTab.UNIVERSE -> AiUniverse3DScreen(
                    activeProject = project,
                    allProjects = allProjects,
                    agents = agents,
                    tasks = tasks,
                    multiPartPlan = multiPartPlan,
                    activeSnapshot = activeSnapshot,
                    collaborationEvents = collaborationEvents,
                    providerConfigs = providerConfigs,
                    isTeamRunning = isTeamRunning,
                    ownerName = ownerName,
                    activeChatTargetSlots = activeChatTargetSlots,
                    onSelectProject = onSelectProject,
                    onOpenAgentConfig = onOpenAgentDetail,
                )

                ManagerTab.DECISIONS -> ManagerDecisionsTab(
                    decisions = allDecisions,
                    agents = agents,
                    onInspectDecision = { inspectedDecision = it },
                )
            }
        }
    }
}

// ==================== TAB 1: STATUS (สถานะ) ====================

private fun formatHumanRelativeTime(timestampMs: Long?): String {
    if (timestampMs == null || timestampMs <= 0L) return "ยังไม่มีกิจกรรม"
    val diffMs = (System.currentTimeMillis() - timestampMs).coerceAtLeast(0L)
    val secs = diffMs / 1000L
    val mins = secs / 60L
    val hours = mins / 60L
    return when {
        secs < 45L -> "เมื่อสักครู่"
        mins < 60L -> "$mins นาทีที่แล้ว"
        hours < 24L -> "$hours ชม. ที่แล้ว"
        else -> SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(timestampMs))
    }
}

@Composable
private fun ManagerStatusTab(
    agents: List<AgentState>,
    tasks: List<TeamTask>,
    collaborationEvents: List<PrioritizedCollaborationEvent>,
    providerConfigs: List<ProviderConfig>,
    teamAgentAntigravityAuth: Map<AgentSlot, AntigravityAuthState>,
    defaultAntigravityAuth: AntigravityAuthState,
    hasProviderApiKey: (String) -> Boolean,
    activeSnapshot: SupervisorUiSnapshot?,
    isTeamRunning: Boolean,
    awaitingStartConfirmation: Boolean,
    onOpenAgentDetail: (AgentSlot) -> Unit,
) {
    val totalSteps = activeSnapshot?.totalSteps ?: tasks.size
    val doneSteps = activeSnapshot?.completedSteps ?: tasks.count { it.status == com.jarves.mh.multiagent.tasks.TaskStatus.COMPLETED }
    val workingCount = agents.count { it.isWorking }
    val pendingSteps = (totalSteps - doneSteps - workingCount).coerceAtLeast(0)
    val blockedCount = tasks.count { it.status == com.jarves.mh.multiagent.tasks.TaskStatus.BLOCKED || it.status == com.jarves.mh.multiagent.tasks.TaskStatus.FAILED }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Section 1: ภาพรวมงานและสถานะทีม (Human-readable Project Overview & Progress)
        item {
            OrqenSectionHeader(
                title = "ภาพรวมโปรเจกต์และการสั่งงาน",
                subtitle = if (isTeamRunning) "ทีมกำลังทำงาน" else "พร้อมรับคำสั่ง",
            )
        }
        item {
            val (statusHeadline, statusColor) = when {
                activeSnapshot != null -> managerOperationalStatus(activeSnapshot.status)
                awaitingStartConfirmation -> "รอยืนยันเริ่มงานในแชท" to OrqenSemanticColors.Pending
                isTeamRunning -> "ทีมกำลังทำงานอยู่" to OrqenSemanticColors.Running
                else -> "พร้อมรับคำสั่งใหม่" to OrqenSemanticColors.Success
            }
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.2.dp, statusColor.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isTeamRunning && activeSnapshot?.status != SupervisorOperationalStatus.COMPLETED) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = statusColor)
                            } else {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                            }
                            Text(statusHeadline, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = statusColor)
                        }
                        if (totalSteps > 0) {
                            val pct = ((doneSteps.toFloat() / totalSteps.toFloat()).coerceIn(0f, 1f) * 100f).roundToInt()
                            OrqenStatusBadge("$doneSteps/$totalSteps ($pct%)", statusColor)
                        }
                    }

                    // Plain-language summary of what the team is currently doing
                    val currentHeadline = when {
                        !activeSnapshot?.activeStepTitle.isNullOrBlank() -> activeSnapshot!!.activeStepTitle
                        !activeSnapshot?.statusDetail.isNullOrBlank() -> activeSnapshot!!.statusDetail
                        awaitingStartConfirmation -> "Manager สรุปแผนงานไว้ในแชทแล้ว พิมพ์ยืนยันเพื่อเริ่มลงมือทำ"
                        else -> "สั่งงานในแชทกลุ่มเพื่อให้ Manager วางแผนและกระจายงานให้ทีม AI"
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = if (!activeSnapshot?.activeAgentName.isNullOrBlank()) {
                                    "กำลังดำเนินการโดย: ${activeSnapshot!!.activeAgentName}"
                                } else {
                                    "สถานะล่าสุด"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = currentHeadline,
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    if (totalSteps > 0) {
                        val ratio = (doneSteps.toFloat() / totalSteps.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { ratio },
                            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
                            color = statusColor,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OrqenMetricPill("เสร็จแล้ว", "$doneSteps", OrqenSemanticColors.Success, Modifier.weight(1f))
                        OrqenMetricPill("กำลังทำ", "$workingCount", OrqenSemanticColors.Running, Modifier.weight(1f))
                        OrqenMetricPill("รอคิว", "$pendingSteps", OrqenSemanticColors.Pending, Modifier.weight(1f))
                        if (blockedCount > 0) {
                            OrqenMetricPill("ติดปัญหา", "$blockedCount", OrqenSemanticColors.Error, Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Section 2: สถานะสมาชิกทีม AI (Clear Human-Friendly Member Cards)
        item {
            OrqenSectionHeader(
                title = "สถานะสมาชิกทีม AI",
                subtitle = "$workingCount กำลังทำงาน · ${agents.size} คน",
            )
        }
        items(agents, key = { it.config.slot.id }) { agent ->
            val accent = agentAccentColor(agent.config.slot)
            val healthRec = activeSnapshot?.agentHealth?.get(agent.config.slot.id)
            val providerCfg = providerConfigs.firstOrNull { it.id == agent.config.providerId }
            val isAgy = agent.config.providerId == "preset-antigravity" || providerCfg?.type == ProviderType.ANTIGRAVITY
            val slotAuth = teamAgentAntigravityAuth[agent.config.slot] ?: AntigravityAuthState()
            val effectiveAuth = if (slotAuth.status != AntigravityAuthStatus.SIGNED_OUT) slotAuth else defaultAntigravityAuth
            val isConnected = if (isAgy) effectiveAuth.status == AntigravityAuthStatus.SIGNED_IN else hasProviderApiKey(agent.config.providerId)

            val (statusLabel, statusColor, statusIcon) = resolveAgentRealtimeStatus(agent, healthRec?.healthState, isConnected)
            val providerLabel = providerCfg?.name ?: agent.config.providerId.ifBlank { "Antigravity" }
            val modelLabel = healthRec?.modelId?.takeIf { it.isNotBlank() } ?: agent.config.modelId.ifBlank { "Default" }

            val currentWork = agent.activityState.currentTask
                .takeIf { it.isNotBlank() && !it.equals("Idle", ignoreCase = true) }
                ?: if (agent.isWorking) stringResource(agent.activityState.activity.labelRes) else "พักรอรับคำสั่ง"

            val hasIssue = agent.activityState.activity == ActivityType.ERROR ||
                healthRec?.healthState in setOf(AgentHealthState.PROVIDER_ERROR, AgentHealthState.TIMEOUT, AgentHealthState.UNAVAILABLE) ||
                !isConnected
            val recentHealthErr = healthRec?.recentProviderErrors?.lastOrNull()?.takeIf { it.isNotBlank() }
            val issueOrLastNote = when {
                !isConnected -> "ยังไม่เชื่อมต่อบัญชีหรือ API Key (แตะเพื่อตั้งค่า)"
                recentHealthErr != null -> "ปัญหาล่าสุด: $recentHealthErr"
                agent.activityState.activity == ActivityType.ERROR -> "เกิดข้อผิดพลาดในการทำงานล่าสุด"
                agent.activityState.lastAction.isNotBlank() && !agent.activityState.lastAction.equals("Idle", ignoreCase = true) ->
                    "งานล่าสุด: ${agent.activityState.lastAction}"
                else -> "สถานะปกติ · พร้อมรับงาน"
            }
            val lastSeenLabel = formatHumanRelativeTime(healthRec?.lastActivityAtMs)

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(
                    1.dp,
                    if (hasIssue) OrqenSemanticColors.Error.copy(alpha = 0.55f) else accent.copy(alpha = 0.4f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAgentDetail(agent.config.slot) },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Header row: Avatar + Name/Role + Status badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Agent3DOrbAvatar(
                            icon = AgentIconCatalog.iconFor(agent.config),
                            accent = accent,
                            sizeDp = 38.dp,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = agent.config.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${agent.config.role.title} · $providerLabel ($modelLabel)",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = statusColor.copy(alpha = 0.14f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(13.dp))
                                Text(statusLabel, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = statusColor)
                            }
                        }
                    }

                    // Human-readable 2-row detail block: What they are doing & Any issue/last note
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("กำลังทำ:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = currentWork,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = if (hasIssue) "ปัญหา:" else "บันทึก:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasIssue) OrqenSemanticColors.Error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = issueOrLastNote,
                                    fontSize = 11.sp,
                                    color = if (hasIssue) OrqenSemanticColors.Error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    // Footer row: Connection status + Last seen relative time
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isConnected) OrqenSemanticColors.Success else OrqenSemanticColors.Error),
                            )
                            Text(
                                text = if (isConnected) "เชื่อมต่อแล้ว" else "ยังไม่เชื่อมต่อ",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isConnected) OrqenSemanticColors.Success else OrqenSemanticColors.Error,
                            )
                        }
                        Text(
                            text = "อัปเดตล่าสุด: $lastSeenLabel",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Section 3: กิจกรรมล่าสุดของทีม (Cleanly Separated Recent Team Activity)
        if (collaborationEvents.isNotEmpty()) {
            item {
                OrqenSectionHeader(
                    title = "กิจกรรมล่าสุดของทีม",
                    subtitle = "${collaborationEvents.size} รายการ",
                )
            }
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        collaborationEvents.takeLast(5).reversed().forEachIndexed { idx, ev ->
                            val senderLabel = ev.event.senderSlot?.let { slot ->
                                agents.firstOrNull { it.config.slot == slot }?.config?.name ?: slot.id
                            } ?: "Team"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                ) {
                                    Text(
                                        text = senderLabel,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        maxLines = 1,
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = ev.event.message.ifBlank { ev.event.type.name },
                                        fontSize = 11.5.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = formatHumanRelativeTime(ev.event.timestamp),
                                        fontSize = 9.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (idx < minOf(collaborationEvents.size, 5) - 1) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== TAB 2: PLANS (แผนงาน) ====================

@Composable
private fun ManagerPlansTab(
    workspaceDir: File,
    agents: List<AgentState>,
    multiPartPlan: MultiPartExecutionPlan?,
    tasks: List<TeamTask>,
    selectedFilter: PlanGroupFilter,
    onSelectFilter: (PlanGroupFilter) -> Unit,
    onInspectPart: (MultiPartSpec) -> Unit,
    onOpenPlanFile: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (multiPartPlan == null) {
            item {
                OrqenEmptyStateCard(
                    title = "ยังไม่มีแผนงาน Multi-Part ในโปรเจกต์นี้",
                    description = "เมื่อสั่งงานในแชทกลุ่ม Manager จะวิเคราะห์โครงสร้างโปรเจกต์และสร้างแผนงานจริงพร้อมแบ่ง Part อัตโนมัติที่นี่",
                    icon = Icons.Default.Assignment,
                )
            }
        } else {
            val sortedParts = multiPartPlan.parts.sortedBy { it.partNumber }
            val completedParts = sortedParts.count { it.status == ExecutionPartStatus.COMPLETED }
            val activeParts = sortedParts.filter {
                it.status in setOf(
                    ExecutionPartStatus.EXECUTING,
                    ExecutionPartStatus.VERIFYING,
                    ExecutionPartStatus.WAITING_FOR_REVIEW,
                    ExecutionPartStatus.WAITING_FOR_TESTS,
                    ExecutionPartStatus.RETRYING,
                    ExecutionPartStatus.RECOVERING,
                )
            }
            val currentPart = activeParts.firstOrNull() ?: MultiPartPlanManager.selectNextEligiblePart(multiPartPlan)
            val nextPart = sortedParts.firstOrNull { p ->
                p.partId != currentPart?.partId &&
                    p.status in setOf(ExecutionPartStatus.READY, ExecutionPartStatus.PENDING)
            }
            val queuedParts = sortedParts.filter { p ->
                p.partId != currentPart?.partId &&
                    p.partId != nextPart?.partId &&
                    p.status in setOf(ExecutionPartStatus.PENDING, ExecutionPartStatus.READY, ExecutionPartStatus.BLOCKED)
            }

            // Section A: Multi-Part Plan Summary (real dynamic count, never hardcoded)
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Multi-Part Plan (v${multiPartPlan.version}) · $completedParts/${sortedParts.size} Parts",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { onOpenPlanFile(MultiPartPlanManager.OVERVIEW_REL_PATH) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text("overview.md", fontSize = 11.sp)
                            }
                        }
                        Text(
                            text = multiPartPlan.title,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (sortedParts.isNotEmpty()) {
                            LinearProgressIndicator(
                                progress = { (completedParts.toFloat() / sortedParts.size.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = OrqenSemanticColors.Running,
                            )
                        }
                    }
                }
            }

            // Section B: แผนงานปัจจุบัน (Current Plan)
            if (currentPart != null) {
                item { OrqenSectionHeader(title = stringResource(R.string.manager_section_current_plan)) }
                item {
                    PlanPartCard(
                        part = currentPart,
                        agents = agents,
                        workspaceDir = workspaceDir,
                        highlightBorder = true,
                        onClick = { onInspectPart(currentPart) },
                        onOpenPlanFile = onOpenPlanFile,
                    )
                }
            }

            // Section C: แผนงานถัดไป (Next Plan)
            if (nextPart != null) {
                item { OrqenSectionHeader(title = stringResource(R.string.manager_section_next_plan)) }
                item {
                    PlanPartCard(
                        part = nextPart,
                        agents = agents,
                        workspaceDir = workspaceDir,
                        highlightBorder = false,
                        onClick = { onInspectPart(nextPart) },
                        onOpenPlanFile = onOpenPlanFile,
                    )
                }
            }

            // Section D: แผนงานที่รอดำเนินการ (Queued / Waiting)
            if (queuedParts.isNotEmpty()) {
                item {
                    OrqenSectionHeader(
                        title = stringResource(R.string.manager_section_queued_plans),
                        subtitle = "${queuedParts.size} รายการในคิว",
                    )
                }
                items(queuedParts, key = { "q:${it.partId}" }) { part ->
                    PlanPartCard(
                        part = part,
                        agents = agents,
                        workspaceDir = workspaceDir,
                        highlightBorder = false,
                        onClick = { onInspectPart(part) },
                        onOpenPlanFile = onOpenPlanFile,
                    )
                }
            }

            // Section E: All Plans (Grouped & Filterable)
            item {
                OrqenSectionHeader(
                    title = stringResource(R.string.manager_section_all_plans),
                    subtitle = "${sortedParts.size} Parts",
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PlanGroupFilter.entries.forEach { f ->
                        FilterChip(
                            selected = selectedFilter == f,
                            onClick = { onSelectFilter(f) },
                            label = { Text(f.label, fontSize = 11.sp) },
                        )
                    }
                }
            }

            val filteredParts = sortedParts.filter { part ->
                when (selectedFilter) {
                    PlanGroupFilter.ALL -> true
                    PlanGroupFilter.ACTIVE -> part.status in setOf(ExecutionPartStatus.EXECUTING, ExecutionPartStatus.VERIFYING, ExecutionPartStatus.WAITING_FOR_REVIEW, ExecutionPartStatus.WAITING_FOR_TESTS, ExecutionPartStatus.RETRYING, ExecutionPartStatus.RECOVERING)
                    PlanGroupFilter.UPCOMING -> part.status == ExecutionPartStatus.READY
                    PlanGroupFilter.QUEUED -> part.status == ExecutionPartStatus.PENDING
                    PlanGroupFilter.COMPLETED -> part.status == ExecutionPartStatus.COMPLETED
                    PlanGroupFilter.PAUSED -> part.status == ExecutionPartStatus.BLOCKED
                    PlanGroupFilter.FAILED -> part.status == ExecutionPartStatus.FAILED
                    PlanGroupFilter.ARCHIVED -> false
                }
            }
            if (filteredParts.isEmpty()) {
                item {
                    OrqenEmptyStateCard(
                        title = "ไม่มีแผนในหมวด ${selectedFilter.label}",
                        description = "เลือกหมวดอื่นหรือดูทั้งหมดเพื่อตรวจสอบแผนงานทั้งหมดในระบบ",
                        icon = Icons.Default.FilterList,
                    )
                }
            } else {
                items(filteredParts, key = { "all:${it.partId}" }) { part ->
                    PlanPartCard(
                        part = part,
                        agents = agents,
                        workspaceDir = workspaceDir,
                        highlightBorder = false,
                        onClick = { onInspectPart(part) },
                        onOpenPlanFile = onOpenPlanFile,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanPartCard(
    part: MultiPartSpec,
    agents: List<AgentState>,
    workspaceDir: File,
    highlightBorder: Boolean,
    onClick: () -> Unit,
    onOpenPlanFile: (String) -> Unit,
) {
    val color = partStatusColor(part.status)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (highlightBorder) 1.3.dp else 1.dp, if (highlightBorder) color.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Part ${part.partNumber}: ${part.title}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OrqenStatusBadge(part.status.name, color)
            }
            Text(
                text = part.objective,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "ผู้รับผิดชอบ: ${resolveAgentDisplayName(part.assignedAgentId, agents)}" +
                    if (part.dependencies.isNotEmpty()) " · รอ: ${part.dependencies.joinToString(", ")}" else " · รันขนาน/เริ่มได้ทันที",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onOpenPlanFile(part.specRelativePath) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) { Text("View Spec", fontSize = 10.5.sp) }
                if (part.reportRelativePath.isNotBlank()) {
                    val hasReport by produceState(initialValue = false, workspaceDir, part.reportRelativePath) {
                        value = withContext(Dispatchers.IO) { File(workspaceDir, part.reportRelativePath).isFile }
                    }
                    if (hasReport) {
                        TextButton(
                            onClick = { onOpenPlanFile(part.reportRelativePath) },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) { Text("View Report", fontSize = 10.5.sp) }
                    }
                }
            }
        }
    }
}

// ==================== TAB 4: DECISIONS (ตัดสินใจ) ====================

@Composable
private fun ManagerDecisionsTab(
    decisions: List<SupervisorDecisionRecord>,
    agents: List<AgentState>,
    onInspectDecision: (SupervisorDecisionRecord) -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val recoveryTypes = remember {
        setOf(
            SupervisorDecisionType.TIMEOUT_DETECTED,
            SupervisorDecisionType.RETRY_INITIATED,
            SupervisorDecisionType.MODEL_CHANGED,
            SupervisorDecisionType.PROVIDER_CHANGED,
            SupervisorDecisionType.AGENT_REPLACED,
            SupervisorDecisionType.SUPERVISOR_FALLBACK,
        )
    }
    val decisionHistory = remember(decisions) { decisions.filter { it.decisionType !in recoveryTypes } }
    val recoveryHistory = remember(decisions) { decisions.filter { it.decisionType in recoveryTypes } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OrqenSectionHeader(
                title = stringResource(R.string.manager_decisions_header_fmt, decisions.size),
                subtitle = "บันทึกจาก ORQEN Runtime จริง",
            )
        }

        if (decisions.isEmpty()) {
            item {
                OrqenEmptyStateCard(
                    title = stringResource(R.string.manager_decisions_empty_title),
                    description = stringResource(R.string.manager_decisions_empty_desc),
                    icon = Icons.Default.Gavel,
                )
            }
        } else {
            // Section A: Decision History
            if (decisionHistory.isNotEmpty()) {
                item {
                    Text(
                        text = "${stringResource(R.string.manager_decisions_section_decisions)} (${decisionHistory.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp,
                    )
                }
                items(decisionHistory, key = { "dec:${it.decisionId}" }) { rec ->
                    DecisionRecordCard(rec = rec, timeFmt = timeFmt, onClick = { onInspectDecision(rec) })
                }
            }

            // Section B: Recovery History
            if (recoveryHistory.isNotEmpty()) {
                item {
                    Text(
                        text = "${stringResource(R.string.manager_decisions_section_recoveries)} (${recoveryHistory.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp,
                    )
                }
                items(recoveryHistory, key = { "rec:${it.decisionId}" }) { rec ->
                    DecisionRecordCard(rec = rec, timeFmt = timeFmt, onClick = { onInspectDecision(rec) })
                }
            }
        }
    }
}

@Composable
private fun DecisionRecordCard(
    rec: SupervisorDecisionRecord,
    timeFmt: SimpleDateFormat,
    onClick: () -> Unit,
) {
    val (title, explanation) = formatDecisionHuman(rec)
    val badgeColor = decisionColor(rec.decisionType)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                OrqenStatusBadge(rec.result.ifBlank { "LOGGED" }, badgeColor)
            }
            Text(explanation, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Manager · ${rec.taskId}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(timeFmt.format(Date(rec.timestampMs)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ==================== SHARED HELPERS ====================

private fun managerOperationalStatus(status: SupervisorOperationalStatus): Pair<String, Color> = when (status) {
    SupervisorOperationalStatus.PLANNING -> "กำลังวิเคราะห์และวางแผนงาน" to OrqenSemanticColors.Info
    SupervisorOperationalStatus.SUPERVISING -> "กำลังควบคุมและสั่งงานทีม" to OrqenSemanticColors.Success
    SupervisorOperationalStatus.VERIFYING -> "กำลังตรวจสอบคุณภาพและผลทดสอบ" to OrqenSemanticColors.Info
    SupervisorOperationalStatus.RECOVERING -> "กำลังกู้คืนขั้นตอนอัตโนมัติ" to OrqenSemanticColors.Warning
    SupervisorOperationalStatus.REASSIGNING -> "กำลังสลับมอบหมายงานให้เอเจนต์สำรอง" to OrqenSemanticColors.Warning
    SupervisorOperationalStatus.COMPLETED -> "ทำงานเสร็จสมบูรณ์แล้ว" to OrqenSemanticColors.Success
    SupervisorOperationalStatus.PAUSED -> "พักการทำงานชั่วคราว" to OrqenSemanticColors.Warning
    SupervisorOperationalStatus.BLOCKED -> "รอการแก้ไขหรือคำสั่งเพิ่มเติม" to OrqenSemanticColors.Error
    SupervisorOperationalStatus.CANCELLED -> "ยกเลิกงานแล้ว" to OrqenSemanticColors.Neutral
    SupervisorOperationalStatus.IDLE -> "พร้อมรับคำสั่ง" to OrqenSemanticColors.Neutral
}

private fun resolveAgentRealtimeStatus(
    agent: AgentState,
    health: AgentHealthState?,
    isConnected: Boolean,
): Triple<String, Color, ImageVector> = when {
    !agent.config.isEnabled -> Triple("Disabled", OrqenSemanticColors.Disabled, Icons.Default.Block)
    !isConnected || health == AgentHealthState.UNAVAILABLE -> Triple("Disconnected", OrqenSemanticColors.Error, Icons.Default.LinkOff)
    health == AgentHealthState.RECOVERING -> Triple("Recovering", OrqenSemanticColors.Warning, Icons.Default.Autorenew)
    agent.activityState.activity == ActivityType.ERROR ||
        health in setOf(AgentHealthState.PROVIDER_ERROR, AgentHealthState.TIMEOUT) ->
        Triple("Error", OrqenSemanticColors.Error, Icons.Default.ErrorOutline)
    agent.isWorking || health == AgentHealthState.BUSY -> when (agent.activityState.activity) {
        ActivityType.PLANNING -> Triple("Planning", OrqenSemanticColors.Info, Icons.Default.Psychology)
        ActivityType.REVIEWING, ActivityType.TESTING -> Triple("Working", OrqenSemanticColors.Running, Icons.Default.Verified)
        else -> Triple("Working", OrqenSemanticColors.Running, Icons.Default.PlayArrow)
    }
    agent.activityState.activity == ActivityType.COMPLETED -> Triple("Completed", OrqenSemanticColors.Success, Icons.Default.CheckCircle)
    agent.activityState.activity == ActivityType.WAITING -> Triple("Waiting", OrqenSemanticColors.Pending, Icons.Default.Schedule)
    else -> Triple("Idle", OrqenSemanticColors.Neutral, Icons.Default.RadioButtonChecked)
}

private fun partStatusColor(status: ExecutionPartStatus): Color = when (status) {
    ExecutionPartStatus.COMPLETED -> OrqenSemanticColors.Success
    ExecutionPartStatus.EXECUTING,
    ExecutionPartStatus.VERIFYING,
    ExecutionPartStatus.WAITING_FOR_REVIEW,
    ExecutionPartStatus.WAITING_FOR_TESTS,
    -> OrqenSemanticColors.Running
    ExecutionPartStatus.RETRYING,
    ExecutionPartStatus.RECOVERING,
    -> OrqenSemanticColors.Warning
    ExecutionPartStatus.FAILED,
    ExecutionPartStatus.BLOCKED,
    -> OrqenSemanticColors.Error
    else -> OrqenSemanticColors.Neutral
}

private fun decisionColor(type: SupervisorDecisionType): Color = when (type) {
    SupervisorDecisionType.TASK_COMPLETED -> OrqenSemanticColors.Success
    SupervisorDecisionType.TASK_BLOCKED,
    SupervisorDecisionType.TEST_FAILED,
    -> OrqenSemanticColors.Error
    SupervisorDecisionType.TIMEOUT_DETECTED,
    SupervisorDecisionType.RETRY_INITIATED,
    SupervisorDecisionType.MODEL_CHANGED,
    SupervisorDecisionType.PROVIDER_CHANGED,
    SupervisorDecisionType.AGENT_REPLACED,
    -> OrqenSemanticColors.Warning
    else -> OrqenSemanticColors.Info
}

private fun formatDecisionHuman(dec: SupervisorDecisionRecord): Pair<String, String> = when (dec.decisionType) {
    SupervisorDecisionType.RETRY_INITIATED ->
        "ลองทำขั้นตอนเดิมซ้ำอัตโนมัติ (Task Retry)" to "ระบบพบปัญหาชั่วคราว จึงสั่งรันขั้นตอนเดิมต่อโดยไม่เสียงานที่ทำไปแล้ว (${dec.reason})"
    SupervisorDecisionType.TASK_REPLANNED ->
        "ปรับปรุงแผนงานอัตโนมัติ" to "Manager ตรวจพบส่วนที่ยังไม่ครบตามเกณฑ์ตรวจสอบ จึงเพิ่มรายการแก้ไขและตรวจสอบต่ออัตโนมัติ"
    SupervisorDecisionType.MODEL_CHANGED,
    SupervisorDecisionType.PROVIDER_CHANGED,
    -> "สลับโมเดล/ผู้ให้บริการอัตโนมัติ (Failover)" to "สลับไปใช้โมเดลสำรองเพื่อให้งานเดินต่อได้ไม่สะดุด (${dec.reason})"
    SupervisorDecisionType.AGENT_REPLACED ->
        "มอบหมายให้ Agent สำรอง (Task Reassignment)" to "ส่งต่องานให้ Agent ที่พร้อมใช้งานแทน (${dec.reason})"
    SupervisorDecisionType.TIMEOUT_DETECTED ->
        "กู้คืนจากการหมดเวลารอ (Agent Timeout Recovery)" to "Manager เข้าช่วยกู้คืนขั้นตอนปัจจุบันโดยยังคงไฟล์ที่เขียนเสร็จแล้วไว้ทั้งหมด"
    SupervisorDecisionType.TASK_COMPLETED ->
        "ยืนยันงานเสร็จสมบูรณ์" to dec.reason.ifBlank { "ตรวจสอบผลลัพธ์และเกณฑ์ทั้งหมดผ่านเรียบร้อย" }
    SupervisorDecisionType.TASK_BLOCKED ->
        "หยุดรอการตรวจสอบ (Blocked)" to dec.reason
    else -> dec.decisionType.name.replace('_', ' ') to dec.reason
}

private fun resolveAgentDisplayName(agentId: String, agents: List<AgentState>): String {
    return agents.firstOrNull { it.config.slot.id == agentId }?.config?.name ?: agentId
}
