package com.jarves.mh.multiagent.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.multiagent.locks.FileLock
import com.jarves.mh.multiagent.model.*
import com.jarves.mh.multiagent.tasks.ProjectPart
import com.jarves.mh.multiagent.tasks.TeamTask
import com.jarves.mh.provider.core.EstimatedUsage
import com.jarves.mh.provider.core.LocalUsage
import com.jarves.mh.provider.core.OfficialQuota

@Composable
fun TeamDashboardScreen(
    teamName: String,
    workspacePath: String,
    agents: List<AgentState>,
    tasks: List<TeamTask>,
    parts: List<ProjectPart>,
    currentPartNumber: Int,
    fileLocks: List<FileLock>,
    officialQuota: OfficialQuota?,
    estimatedUsage: EstimatedUsage?,
    localUsage: LocalUsage,
    isTeamRunning: Boolean,
    onStartTeam: () -> Unit,
    onPauseTeam: () -> Unit,
    onResumeTeam: () -> Unit,
    onStopTeam: () -> Unit,
    onOpenAgentChat: (AgentSlot) -> Unit,
    onOpenAgentDetail: (AgentSlot) -> Unit = {},
    onToggleAgentEnabled: (AgentSlot) -> Unit = {},
    onOpenQuotaDashboard: () -> Unit = {},
    onOpenActivityFeed: () -> Unit = {},
    onReleaseFileLock: (String, AgentSlot) -> Unit = { _, _ -> },
    onOpenGroupChat: () -> Unit,
    onOpenProviderSettings: () -> Unit,
    onNextPart: () -> Unit,
) {
    var showContinuousOptions by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Navigation Bar / Quick Switcher
        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.nav_tab_overview), fontWeight = FontWeight.Bold) },
                        leadingIcon = { Icon(Icons.Default.Dashboard, contentDescription = null, Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
                item {
                    AssistChip(
                        onClick = onOpenQuotaDashboard,
                        label = { Text(stringResource(R.string.nav_tab_quota)) },
                        leadingIcon = { Icon(Icons.Default.Paid, contentDescription = null, Modifier.size(16.dp)) },
                    )
                }
                item {
                    AssistChip(
                        onClick = onOpenActivityFeed,
                        label = { Text(stringResource(R.string.nav_tab_feed)) },
                        leadingIcon = { Icon(Icons.Default.DynamicFeed, contentDescription = null, Modifier.size(16.dp)) },
                    )
                }
                item {
                    AssistChip(
                        onClick = onOpenGroupChat,
                        label = { Text(stringResource(R.string.nav_tab_chat)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, Modifier.size(16.dp)) },
                    )
                }
                item {
                    AssistChip(
                        onClick = onOpenProviderSettings,
                        label = { Text("Providers") },
                        leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null, Modifier.size(16.dp)) },
                    )
                }
            }
        }

        // 1. Team Header Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = teamName.ifBlank { stringResource(R.string.team_workspace_title) },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = workspacePath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isTeamRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outlineVariant,
                        ) {
                            Text(
                                text = if (isTeamRunning) "ACTIVE" else "IDLE",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Team Stats Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        StatItem(
                            label = stringResource(R.string.team_current_part, currentPartNumber),
                            value = "Part 0$currentPartNumber",
                            icon = Icons.Default.Timeline,
                        )
                        StatItem(
                            label = stringResource(R.string.team_agents_count, agents.size),
                            value = "${agents.count { it.isWorking }} Working",
                            icon = Icons.Default.SmartToy,
                        )
                        StatItem(
                            label = stringResource(R.string.team_task_queue),
                            value = "${tasks.count { it.status == com.jarves.mh.multiagent.tasks.TaskStatus.COMPLETED }}/${tasks.size} Done",
                            icon = Icons.Default.CheckCircle,
                        )
                    }
                }
            }
        }

        // 2. Control Panel
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!isTeamRunning) {
                    Button(
                        onClick = onStartTeam,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.team_start))
                    }
                } else {
                    OutlinedButton(
                        onClick = onPauseTeam,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.team_pause))
                    }
                    Button(
                        onClick = onStopTeam,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.team_stop))
                    }
                }

                IconButton(
                    onClick = onOpenGroupChat,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Group Chat")
                }

                IconButton(
                    onClick = onOpenProviderSettings,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                ) {
                    Icon(Icons.Default.Dns, contentDescription = "Provider Management")
                }
            }
        }

        // 3. Four Agent Cards Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "AI AGENTS (${agents.size} / ${AgentSlot.MAX_AGENTS})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Tap card to configure",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 4. Agent Cards List (Up to 4 Agents)
        items(agents) { agent ->
            AgentCard(
                agent = agent,
                onCardClick = { onOpenAgentDetail(agent.config.slot) },
                onChatClick = { onOpenAgentChat(agent.config.slot) },
                onToggleEnabled = { onToggleAgentEnabled(agent.config.slot) },
            )
        }

        // 5. Quota & Usage Monitor Card
        item {
            QuotaMonitorCard(
                officialQuota = officialQuota,
                estimatedUsage = estimatedUsage,
                localUsage = localUsage,
                onCardClick = onOpenQuotaDashboard,
            )
        }

        // 6. File Locks & Ownership Card
        item {
            FileLocksCard(
                fileLocks = fileLocks,
                onReleaseLock = onReleaseFileLock,
            )
        }

        // 7. Continuous Execution Options
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showContinuousOptions = !showContinuousOptions },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Autorenew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.continuous_execution_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Icon(
                            if (showContinuousOptions) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                        )
                    }

                    AnimatedVisibility(visible = showContinuousOptions) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            ContinuousOptionRow(
                                title = stringResource(R.string.continuous_auto_resume),
                                description = stringResource(R.string.continuous_auto_resume_desc),
                                isChecked = true,
                                onCheckedChange = {},
                            )
                            Spacer(Modifier.height(8.dp))
                            ContinuousOptionRow(
                                title = stringResource(R.string.continuous_auto_retry),
                                description = stringResource(R.string.continuous_auto_retry_desc),
                                isChecked = true,
                                onCheckedChange = {},
                            )
                            Spacer(Modifier.height(8.dp))
                            ContinuousOptionRow(
                                title = stringResource(R.string.continuous_checkpoints),
                                description = stringResource(R.string.continuous_checkpoints_desc),
                                isChecked = true,
                                onCheckedChange = {},
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AgentCard(
    agent: AgentState,
    onCardClick: () -> Unit,
    onChatClick: () -> Unit,
    onToggleEnabled: () -> Unit,
) {
    val activity = agent.activityState.activity
    val activityColor = ActivityVisuals.colorFor(activity)
    val activityIcon = ActivityVisuals.iconFor(activity)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(onClick = onCardClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            // Header: Name, Slot & Role
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(activityColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = activityIcon,
                            contentDescription = null,
                            tint = activityColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = agent.config.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (!agent.config.isEnabled) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                ) {
                                    Text(
                                        text = "DISABLED",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = agent.config.role.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Chat & Configure actions
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onChatClick, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onCardClick, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = "Configure", modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Activity & Lifecycle Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(activityColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(activityColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(activity.labelRes),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = activityColor,
                )
                if (agent.activityState.currentTask.isNotBlank()) {
                    Text(
                        text = " · ${agent.activityState.currentTask}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Current File & Last Action
            if (agent.activityState.currentFile.isNotBlank() || agent.activityState.lastAction.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (agent.activityState.currentFile.isNotBlank()) {
                        Text(
                            text = "File: ${agent.activityState.currentFile.takeLast(24)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = agent.activityState.lastAction,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun QuotaMonitorCard(
    officialQuota: OfficialQuota?,
    estimatedUsage: EstimatedUsage?,
    localUsage: LocalUsage,
    onCardClick: () -> Unit = {},
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(onClick = onCardClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "REAL PROVIDER QUOTA & USAGE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Details", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(12.dp))

            // A. Official Quota
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.quota_official_title),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    val health = if (officialQuota?.isAvailable == true) {
                        if ((officialQuota.remainingPercent ?: 1f) > 0.2f) Color(0xFF2E7D32) else Color(0xFFC62828)
                    } else Color.Gray

                    Surface(shape = RoundedCornerShape(10.dp), color = health.copy(alpha = 0.15f)) {
                        Text(
                            text = if (officialQuota?.isAvailable == true) "VERIFIED SOURCE" else "UNAVAILABLE",
                            color = health,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (officialQuota?.isAvailable == true) {
                    Text(
                        text = "Remaining: ${(officialQuota.remainingPercent?.times(100))?.toInt() ?: 100}% · Source: ${officialQuota.source}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text(
                        text = officialQuota?.unavailableReason ?: stringResource(R.string.quota_unavailable),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // B. Estimated Usage & Local Usage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Estimated
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.quota_estimated_title),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Tokens: ${estimatedUsage?.estimatedTokens ?: 0}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Cost: ฿${String.format("%.2f", estimatedUsage?.estimatedCostThb ?: 0.0)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Local
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.quota_local_title),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Requests: ${localUsage.totalRequests}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Tasks: ${localUsage.completedTasks} done",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun FileLocksCard(
    fileLocks: List<FileLock>,
    onReleaseLock: (String, AgentSlot) -> Unit = { _, _ -> },
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "SHARED WORKSPACE FILE LOCKS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${fileLocks.size} Active",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))

            if (fileLocks.isEmpty()) {
                Text(
                    text = stringResource(R.string.team_no_file_locks),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (lock in fileLocks) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = lock.filePath,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = lock.agentSlot.defaultName,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        TextButton(
                            onClick = { onReleaseLock(lock.filePath, lock.agentSlot) },
                        ) {
                            Text("Release", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContinuousOptionRow(
    title: String,
    description: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = isChecked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun StatItem(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
