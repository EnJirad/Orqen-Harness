package com.jarves.mh.multiagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.multiagent.persistence.PersistentTaskStatus
import com.jarves.mh.ui.theme.OrqenMint

/**
 * Responsive ORQEN AI Development Command Center (Phase 7).
 * Adapts automatically between Bottom Navigation (phones) and NavigationRail (tablets/landscape).
 */
@Composable
fun OrqenCommandCenterScreen(
    state: CommandCenterUiState,
    onSelectSection: (CommandCenterSection) -> Unit,
    onSelectProject: (String) -> Unit,
    onSelectTask: (String) -> Unit,
    onSelectFilter: (TaskFilterMode) -> Unit,
    onSelectHostPreference: (ExecutionHostPreference) -> Unit,
    onSearch: (String) -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onStopTask: (String) -> Unit,
    onResolveApproval: (String, Boolean) -> Unit,
    onTriggerRecovery: () -> Unit,
    onClose: () -> Unit,
) {
    val navItems = listOf(
        Triple(CommandCenterSection.HOME, stringResource(R.string.cc_nav_home), Icons.Default.Dashboard),
        Triple(CommandCenterSection.PROJECTS, stringResource(R.string.cc_nav_projects), Icons.Default.Folder),
        Triple(CommandCenterSection.TASKS, stringResource(R.string.cc_nav_tasks), Icons.Default.Checklist),
        Triple(CommandCenterSection.AGENTS, stringResource(R.string.cc_nav_agents), Icons.Default.Groups),
        Triple(CommandCenterSection.WORKERS, stringResource(R.string.cc_nav_workers), Icons.Default.Dns),
        Triple(CommandCenterSection.ACTIVITY, stringResource(R.string.cc_nav_activity), Icons.Default.Notifications),
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            topBar = {
                @OptIn(ExperimentalMaterial3Api::class)
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.cc_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                            state.projects.firstOrNull { it.projectId == state.selectedProjectId }?.let { p ->
                                Text(
                                    text = p.projectName,
                                    fontSize = 11.sp,
                                    color = OrqenMint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cc_close))
                        }
                    },
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        navItems.forEach { (section, label, icon) ->
                            NavigationBarItem(
                                selected = state.activeSection == section,
                                onClick = { onSelectSection(section) },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (useRail) {
                    NavigationRail {
                        navItems.forEach { (section, label, icon) ->
                            NavigationRailItem(
                                selected = state.activeSection == section,
                                onClick = { onSelectSection(section) },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label, fontSize = 10.sp) },
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = OrqenSpacing.Lg, vertical = OrqenSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(OrqenSpacing.Md),
                ) {
                    if (state.errorMessage != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = OrqenSemanticColors.Error.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, OrqenSemanticColors.Error.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = state.errorMessage,
                                color = OrqenSemanticColors.Error,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }

                    when (state.activeSection) {
                        CommandCenterSection.HOME -> CommandCenterHomePane(state, onSelectSection, onTriggerRecovery)
                        CommandCenterSection.PROJECTS -> CommandCenterProjectsPane(state, onSelectProject)
                        CommandCenterSection.TASKS -> CommandCenterTasksPane(
                            state = state,
                            onSelectTask = onSelectTask,
                            onSelectFilter = onSelectFilter,
                            onSearch = onSearch,
                            onPauseTask = onPauseTask,
                            onResumeTask = onResumeTask,
                            onStopTask = onStopTask,
                        )
                        CommandCenterSection.AGENTS -> CommandCenterAgentsPane(state)
                        CommandCenterSection.WORKERS -> CommandCenterWorkersPane(state, onSelectHostPreference)
                        CommandCenterSection.ACTIVITY, CommandCenterSection.SETTINGS ->
                            CommandCenterActivityAndApprovalsPane(state, onResolveApproval, onTriggerRecovery)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandCenterHomePane(
    state: CommandCenterUiState,
    onSelectSection: (CommandCenterSection) -> Unit,
    onTriggerRecovery: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(OrqenSpacing.Md)) {
        item {
            OrqenSectionHeader(
                title = stringResource(R.string.cc_home_overview),
                subtitle = state.usageSummary.label,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OrqenSpacing.Sm),
            ) {
                OrqenMetricPill(
                    label = stringResource(R.string.cc_metric_active_tasks),
                    value = state.tasks.count { it.status == PersistentTaskStatus.RUNNING }.toString(),
                    accent = OrqenSemanticColors.Running,
                    modifier = Modifier.weight(1f),
                )
                OrqenMetricPill(
                    label = stringResource(R.string.cc_metric_agents),
                    value = state.agents.size.toString(),
                    accent = OrqenSemanticColors.Pending,
                    modifier = Modifier.weight(1f),
                )
                OrqenMetricPill(
                    label = stringResource(R.string.cc_metric_approvals),
                    value = state.pendingApprovals.size.toString(),
                    accent = if (state.pendingApprovals.isNotEmpty()) OrqenSemanticColors.Warning else OrqenSemanticColors.Neutral,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(OrqenSpacing.Sm)) {
                Button(
                    onClick = { onSelectSection(CommandCenterSection.TASKS) },
                    colors = ButtonDefaults.buttonColors(containerColor = OrqenMint, contentColor = Color(0xFF031F1B)),
                ) {
                    Text(stringResource(R.string.cc_nav_tasks), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onTriggerRecovery) {
                    Text(stringResource(R.string.cc_reconcile_btn), fontSize = 12.sp)
                }
            }
        }
        if (state.tasks.isEmpty()) {
            item {
                OrqenEmptyStateCard(
                    title = stringResource(R.string.cc_empty_tasks_title),
                    description = stringResource(R.string.cc_empty_tasks_desc),
                )
            }
        } else {
            items(state.tasks.take(5), key = { it.taskId }) { task ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${task.taskId} · ${task.title}", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(task.executionMode.name, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OrqenStatusBadge(task.status.name, taskStatusColor(task.status))
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandCenterProjectsPane(
    state: CommandCenterUiState,
    onSelectProject: (String) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(OrqenSpacing.Sm)) {
        item { OrqenSectionHeader(stringResource(R.string.cc_nav_projects)) }
        if (state.projects.isEmpty()) {
            item {
                OrqenEmptyStateCard(
                    title = stringResource(R.string.cc_empty_projects_title),
                    description = stringResource(R.string.cc_empty_projects_desc),
                )
            }
        } else {
            items(state.projects, key = { it.projectId }) { proj ->
                val selected = proj.projectId == state.selectedProjectId
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) OrqenMint.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (selected) OrqenMint else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectProject(proj.projectId) },
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(proj.projectName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(proj.workspacePath, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandCenterTasksPane(
    state: CommandCenterUiState,
    onSelectTask: (String) -> Unit,
    onSelectFilter: (TaskFilterMode) -> Unit,
    onSearch: (String) -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onStopTask: (String) -> Unit,
) {
    val filters = TaskFilterMode.entries.map { it.name to it.name }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(OrqenSpacing.Sm)) {
        item {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearch,
                placeholder = { Text(stringResource(R.string.cc_search_hint), fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OrqenFilterChipRow(
                options = filters,
                selectedKey = state.taskFilter.name,
                onSelect = { key -> onSelectFilter(TaskFilterMode.valueOf(key)) },
            )
        }
        items(state.filteredTasks, key = { it.taskId }) { task ->
            val selected = task.taskId == state.selectedTaskId
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (selected) OrqenMint else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectTask(task.taskId) },
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${task.taskId} · ${task.title}", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        OrqenStatusBadge(task.status.name, taskStatusColor(task.status))
                    }
                    if (!task.lastError.isNullOrBlank()) {
                        Text(task.lastError, fontSize = 11.sp, color = OrqenSemanticColors.Error, maxLines = 2)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (task.status == PersistentTaskStatus.RUNNING) {
                            OutlinedButton(onClick = { onPauseTask(task.taskId) }) {
                                Text(stringResource(R.string.rt_exec_pause_task), fontSize = 11.sp)
                            }
                        } else if (task.status == PersistentTaskStatus.PAUSED || task.status == PersistentTaskStatus.QUEUED) {
                            OutlinedButton(onClick = { onResumeTask(task.taskId) }) {
                                Text(stringResource(R.string.rt_exec_resume_task), fontSize = 11.sp, color = OrqenMint)
                            }
                        }
                        if (task.status != PersistentTaskStatus.COMPLETED && task.status != PersistentTaskStatus.CANCELLED) {
                            OutlinedButton(onClick = { onStopTask(task.taskId) }) {
                                Text(stringResource(R.string.rt_exec_stop_task), fontSize = 11.sp, color = OrqenSemanticColors.Error)
                            }
                        }
                    }
                }
            }
        }
        if (state.timelineEvents.isNotEmpty()) {
            item { OrqenSectionHeader(stringResource(R.string.cc_timeline_title)) }
            items(state.timelineEvents, key = { it.eventId }) { evt ->
                Text(
                    text = "#${evt.sequenceNumber} [${evt.eventType}] ${evt.eventPayload}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CommandCenterAgentsPane(state: CommandCenterUiState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(OrqenSpacing.Sm)) {
        item {
            OrqenSectionHeader(
                title = stringResource(R.string.cc_nav_agents),
                subtitle = "${state.agents.size} configured",
            )
        }
        items(state.agents, key = { it.agentId }) { agent ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(agent.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        OrqenStatusBadge(
                            if (agent.isEnabled) "ENABLED" else "DISABLED",
                            if (agent.isEnabled) OrqenSemanticColors.Success else OrqenSemanticColors.Neutral,
                        )
                    }
                    Text("${agent.role} · ${agent.providerId} (${agent.modelId})", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CommandCenterWorkersPane(
    state: CommandCenterUiState,
    onSelectHostPreference: (ExecutionHostPreference) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(OrqenSpacing.Sm)) {
        item { OrqenSectionHeader(stringResource(R.string.cc_nav_workers)) }
        item {
            OrqenFilterChipRow(
                options = ExecutionHostPreference.entries.map { it.name to it.name.replace('_', ' ') },
                selectedKey = state.hostPreference.name,
                onSelect = { onSelectHostPreference(ExecutionHostPreference.valueOf(it)) },
            )
        }
        if (state.workers.isEmpty()) {
            item {
                OrqenEmptyStateCard(
                    title = stringResource(R.string.cc_no_remote_workers_title),
                    description = stringResource(R.string.cc_no_remote_workers_desc),
                )
            }
        } else {
            items(state.workers, key = { it.workerId }) { w ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${w.statusDetail} · Active: ${w.activeTaskCount}/${w.maxConcurrency}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OrqenStatusBadge(
                            if (w.isOnline) "ONLINE" else "OFFLINE",
                            if (w.isOnline) OrqenSemanticColors.Success else OrqenSemanticColors.Error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandCenterActivityAndApprovalsPane(
    state: CommandCenterUiState,
    onResolveApproval: (String, Boolean) -> Unit,
    onTriggerRecovery: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(OrqenSpacing.Sm)) {
        item {
            OrqenSectionHeader(
                title = stringResource(R.string.cc_approvals_title),
                trailing = {
                    OutlinedButton(onClick = onTriggerRecovery) {
                        Text(stringResource(R.string.cc_reconcile_btn), fontSize = 11.sp)
                    }
                },
            )
        }
        if (state.pendingApprovals.isEmpty()) {
            item {
                OrqenEmptyStateCard(
                    title = stringResource(R.string.cc_no_approvals_title),
                    description = stringResource(R.string.cc_no_approvals_desc),
                )
            }
        } else {
            items(state.pendingApprovals, key = { it.approvalId }) { appr ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, OrqenSemanticColors.Warning.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${appr.riskLevel.name}: ${appr.actionTitle}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(appr.impact, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onResolveApproval(appr.approvalId, true) },
                                colors = ButtonDefaults.buttonColors(containerColor = OrqenMint, contentColor = Color(0xFF031F1B)),
                            ) {
                                Text(stringResource(R.string.cc_approve_btn), fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = { onResolveApproval(appr.approvalId, false) }) {
                                Text(stringResource(R.string.cc_reject_btn), fontSize = 11.sp, color = OrqenSemanticColors.Error)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun taskStatusColor(status: PersistentTaskStatus): Color = when (status) {
    PersistentTaskStatus.RUNNING -> OrqenSemanticColors.Running
    PersistentTaskStatus.RECOVERING -> OrqenSemanticColors.Recovery
    PersistentTaskStatus.COMPLETED -> OrqenSemanticColors.Success
    PersistentTaskStatus.PAUSED, PersistentTaskStatus.PAUSE_REQUESTED -> OrqenSemanticColors.Paused
    PersistentTaskStatus.BLOCKED, PersistentTaskStatus.FAILED -> OrqenSemanticColors.Error
    PersistentTaskStatus.CANCELLED -> OrqenSemanticColors.Neutral
    PersistentTaskStatus.DRAFT, PersistentTaskStatus.QUEUED, PersistentTaskStatus.PLANNING, PersistentTaskStatus.READY -> OrqenSemanticColors.Pending
}
