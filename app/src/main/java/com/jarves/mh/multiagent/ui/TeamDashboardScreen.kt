package com.jarves.mh.multiagent.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.jarves.mh.model.Project
import com.jarves.mh.multiagent.locks.FileLock
import com.jarves.mh.multiagent.model.*
import com.jarves.mh.multiagent.supervisor.ContinuousExecutionConfig
import com.jarves.mh.multiagent.tasks.ProjectPart
import com.jarves.mh.multiagent.tasks.TeamTask
import com.jarves.mh.network.GitHubRepository
import com.jarves.mh.ui.GitHubAuthStatus
import com.jarves.mh.provider.core.EstimatedUsage
import com.jarves.mh.provider.core.LocalUsage
import com.jarves.mh.provider.core.OfficialQuota
import com.jarves.mh.provider.core.ProviderConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamDashboardScreen(
    teamName: String,
    workspacePath: String,
    projects: List<Project> = emptyList(),
    activeProject: Project? = null,
    githubAuthStatus: GitHubAuthStatus = GitHubAuthStatus.DISCONNECTED,
    githubLogin: String? = null,
    githubRepositories: List<GitHubRepository> = emptyList(),
    gitCloneRunning: Boolean = false,
    projectImporting: Boolean = false,
    agents: List<AgentState>,
    tasks: List<TeamTask>,
    parts: List<ProjectPart>,
    currentPartNumber: Int,
    fileLocks: List<FileLock>,
    officialQuota: OfficialQuota?,
    estimatedUsage: EstimatedUsage?,
    localUsage: LocalUsage,
    providerConfigs: List<ProviderConfig> = emptyList(),
    allProviderQuotas: Map<String, OfficialQuota> = emptyMap(),
    allProviderEstimated: Map<String, EstimatedUsage> = emptyMap(),
    continuousConfig: ContinuousExecutionConfig = ContinuousExecutionConfig(),
    isTeamRunning: Boolean,
    onSelectProject: (Project) -> Unit = {},
    onCreateProject: (String) -> Unit = {},
    onImportZip: (Uri) -> Unit = {},
    onCloneGit: (String) -> Unit = {},
    onStartGitHubLogin: () -> Unit = {},
    onRefreshGitHub: () -> Unit = {},
    onCloneGitHub: (GitHubRepository) -> Unit = {},
    onAddAgent: () -> Unit = {},
    onRemoveAgent: (AgentSlot) -> Unit = {},
    onUpdateContinuousConfig: (ContinuousExecutionConfig) -> Unit = {},
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
    var showWorkspaceGitPanel by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showGitCloneDialog by remember { mutableStateOf(false) }
    var showGitHubRepoDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var gitUrlInput by remember { mutableStateOf("") }

    val importZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImportZip(uri)
    }

    if (showNewProjectDialog) {
        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            title = { Text(stringResource(R.string.projects_new_project)) },
            text = {
                OutlinedTextField(
                    value = newProjectName,
                    onValueChange = { newProjectName = it },
                    label = { Text("Project / Folder Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newProjectName.isNotBlank()) {
                        onCreateProject(newProjectName.trim())
                        newProjectName = ""
                        showNewProjectDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showGitCloneDialog) {
        AlertDialog(
            onDismissRequest = { showGitCloneDialog = false },
            title = { Text(stringResource(R.string.projects_git_url)) },
            text = {
                OutlinedTextField(
                    value = gitUrlInput,
                    onValueChange = { gitUrlInput = it },
                    label = { Text("https://github.com/user/repo.git") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (gitUrlInput.isNotBlank()) {
                        onCloneGit(gitUrlInput.trim())
                        gitUrlInput = ""
                        showGitCloneDialog = false
                    }
                }) { Text("Clone") }
            },
            dismissButton = {
                TextButton(onClick = { showGitCloneDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showGitHubRepoDialog) {
        AlertDialog(
            onDismissRequest = { showGitHubRepoDialog = false },
            title = { Text(githubLogin?.let { "GitHub (@$it)" } ?: stringResource(R.string.projects_connect_github)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (githubAuthStatus != GitHubAuthStatus.CONNECTED) {
                        Button(onClick = onStartGitHubLogin, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.projects_connect_github))
                        }
                    } else {
                        OutlinedButton(onClick = onRefreshGitHub, modifier = Modifier.fillMaxWidth()) {
                            Text("Refresh Repositories")
                        }
                        if (githubRepositories.isEmpty()) {
                            Text("No repositories loaded yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(githubRepositories) { repo ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onCloneGitHub(repo)
                                                showGitHubRepoDialog = false
                                            },
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text(repo.fullName, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            if (repo.description.isNotBlank()) {
                                                Text(repo.description, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGitHubRepoDialog = false }) { Text("Close") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(top = 8.dp),
                title = {
                    Column {
                        Text(
                            text = teamName.ifBlank { stringResource(R.string.team_workspace_title) },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = workspacePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isTeamRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Text(
                            text = if (isTeamRunning) "ACTIVE" else "IDLE",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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

            // 1. Team Stats Summary Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
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

            // 1.5 Workspace & Git Repository Card (Same Git/Folder/ZIP/GitHub powers as Projects tab!)
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showWorkspaceGitPanel = !showWorkspaceGitPanel },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.FolderShared, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.team_workspace_git_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = activeProject?.name ?: stringResource(R.string.team_workspace_git_desc),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Icon(
                                if (showWorkspaceGitPanel) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                            )
                        }

                        AnimatedVisibility(visible = showWorkspaceGitPanel) {
                            Column(
                                modifier = Modifier.padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (projects.isNotEmpty()) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(projects) { project ->
                                            FilterChip(
                                                selected = activeProject?.id == project.id,
                                                onClick = { onSelectProject(project) },
                                                label = { Text(project.name, fontSize = 11.sp) },
                                            )
                                        }
                                    }
                                }

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { showNewProjectDialog = true },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                    ) {
                                        Icon(Icons.Default.CreateNewFolder, contentDescription = null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.projects_new_project), fontSize = 11.sp, maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = { importZipLauncher.launch("*/*") },
                                        enabled = !projectImporting && !gitCloneRunning,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.projects_zip_file), fontSize = 11.sp, maxLines = 1)
                                    }
                                }

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { showGitCloneDialog = true },
                                        enabled = !gitCloneRunning,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                    ) {
                                        Icon(Icons.Default.Code, contentDescription = null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.projects_git_url), fontSize = 11.sp, maxLines = 1)
                                    }
                                    Button(
                                        onClick = {
                                            showGitHubRepoDialog = true
                                            if (githubAuthStatus == GitHubAuthStatus.CONNECTED && githubRepositories.isEmpty()) {
                                                onRefreshGitHub()
                                            }
                                        },
                                        enabled = !gitCloneRunning,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                    ) {
                                        Icon(Icons.Default.CloudSync, contentDescription = null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(githubLogin ?: "GitHub", fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
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

            // 3. Agents Header with + Add Agent button (2 default, up to 4 max)
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
                    if (agents.size < AgentSlot.MAX_AGENTS) {
                        FilledTonalButton(
                            onClick = onAddAgent,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.team_add_agent), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text(
                            text = "Max 4 / 4",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 4. Agent Cards List (2 to 4 Agents)
            items(agents, key = { it.config.slot.id }) { agent ->
                AgentCard(
                    agent = agent,
                    canRemove = agents.size > 1,
                    onCardClick = { onOpenAgentDetail(agent.config.slot) },
                    onChatClick = { onOpenAgentChat(agent.config.slot) },
                    onToggleEnabled = { onToggleAgentEnabled(agent.config.slot) },
                    onRemoveClick = { onRemoveAgent(agent.config.slot) },
                )
            }

            // 5. All-Provider Quota & Usage Monitor Card
            item {
                QuotaMonitorCard(
                    officialQuota = officialQuota,
                    estimatedUsage = estimatedUsage,
                    localUsage = localUsage,
                    providerConfigs = providerConfigs,
                    allProviderQuotas = allProviderQuotas,
                    allProviderEstimated = allProviderEstimated,
                    onCardClick = onOpenQuotaDashboard,
                )
            }

            // 6. File Locks & Ownership Card
            item {
                FileLocksCard(
                    fileLocks = fileLocks,
                    agents = agents,
                    onReleaseLock = onReleaseFileLock,
                )
            }

            // 7. Continuous Execution Options (Wired to real ViewModel state)
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
                                    isChecked = continuousConfig.autoResume,
                                    onCheckedChange = { onUpdateContinuousConfig(continuousConfig.copy(autoResume = it)) },
                                )
                                Spacer(Modifier.height(8.dp))
                                ContinuousOptionRow(
                                    title = stringResource(R.string.continuous_auto_retry),
                                    description = stringResource(R.string.continuous_auto_retry_desc),
                                    isChecked = continuousConfig.autoRetryRateLimit,
                                    onCheckedChange = { onUpdateContinuousConfig(continuousConfig.copy(autoRetryRateLimit = it)) },
                                )
                                Spacer(Modifier.height(8.dp))
                                ContinuousOptionRow(
                                    title = stringResource(R.string.continuous_checkpoints),
                                    description = stringResource(R.string.continuous_checkpoints_desc),
                                    isChecked = continuousConfig.autoCheckpoint,
                                    onCheckedChange = { onUpdateContinuousConfig(continuousConfig.copy(autoCheckpoint = it)) },
                                )
                            }
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
    canRemove: Boolean = false,
    onCardClick: () -> Unit,
    onChatClick: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRemoveClick: () -> Unit = {},
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
                            text = "${agent.config.role.title} · ${agent.config.modelId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onChatClick, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onCardClick, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = "Configure", modifier = Modifier.size(18.dp))
                    }
                    if (canRemove && agent.config.slot.slotNumber > 2) {
                        IconButton(onClick = onRemoveClick, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

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
    providerConfigs: List<ProviderConfig> = emptyList(),
    allProviderQuotas: Map<String, OfficialQuota> = emptyMap(),
    allProviderEstimated: Map<String, EstimatedUsage> = emptyMap(),
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
                    text = stringResource(R.string.team_all_providers_quota).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Details", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(10.dp))

            if (providerConfigs.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    providerConfigs.forEach { cfg ->
                        val q = allProviderQuotas[cfg.id]
                        val e = allProviderEstimated[cfg.id]
                        val isAvail = q?.isAvailable == true
                        val badgeColor = if (isAvail) {
                            if ((q?.remainingPercent ?: 1f) > 0.2f) Color(0xFF2E7D32) else Color(0xFFC62828)
                        } else MaterialTheme.colorScheme.onSurfaceVariant

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(cfg.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = "Tokens: ${e?.estimatedTokens ?: 0} · ฿${String.format("%.2f", e?.estimatedCostThb ?: 0.0)}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Surface(shape = RoundedCornerShape(8.dp), color = badgeColor.copy(alpha = 0.14f)) {
                                Text(
                                    text = if (isAvail) "${((q?.remainingPercent ?: 1f) * 100).toInt()}%" else "EST",
                                    color = badgeColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = officialQuota?.unavailableReason ?: stringResource(R.string.quota_unavailable),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun FileLocksCard(
    fileLocks: List<FileLock>,
    agents: List<AgentState> = emptyList(),
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
                    val ownerName = agents.firstOrNull { it.config.slot == lock.agentSlot }?.config?.name ?: lock.agentSlot.defaultName
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
                                    text = ownerName,
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
