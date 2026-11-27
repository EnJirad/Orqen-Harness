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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.jarves.mh.ui.theme.PocketOrange
import com.jarves.mh.provider.core.EstimatedUsage
import com.jarves.mh.provider.core.LocalUsage
import com.jarves.mh.provider.core.OfficialQuota
import com.jarves.mh.provider.core.ProviderConfig
import com.jarves.mh.provider.core.ProviderType
import com.jarves.mh.runtime.AntigravityAuthState
import com.jarves.mh.runtime.AntigravityAuthStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamDashboardScreen(
    teamName: String,
    workspacePath: String,
    projects: List<Project> = emptyList(),
    soloProjects: List<Project> = emptyList(),
    activeProject: Project? = null,
    githubAuthStatus: GitHubAuthStatus = GitHubAuthStatus.DISCONNECTED,
    githubLogin: String? = null,
    githubUserCode: String? = null,
    githubMessage: String? = null,
    githubRepositories: List<GitHubRepository> = emptyList(),
    githubRepositoriesLoading: Boolean = false,
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
    teamAgentAntigravityAuth: Map<AgentSlot, AntigravityAuthState> = emptyMap(),
    defaultAntigravityAuth: AntigravityAuthState = AntigravityAuthState(),
    hasProviderApiKey: (String) -> Boolean = { false },
    allProviderQuotas: Map<String, OfficialQuota> = emptyMap(),
    allProviderEstimated: Map<String, EstimatedUsage> = emptyMap(),
    continuousConfig: ContinuousExecutionConfig = ContinuousExecutionConfig(),
    autoPushGitEnabled: Boolean = false,
    onToggleAutoPushGit: (Boolean) -> Unit = {},
    isTeamRunning: Boolean,
    runningTeamProjectId: String? = null,
    runningTeamProjectName: String? = null,
    activeSupervisedTaskId: String? = null,
    supervisorSnapshots: Map<String, com.jarves.mh.multiagent.supervisor.SupervisorUiSnapshot> = emptyMap(),
    onBackToProjects: () -> Unit = {},
    onJumpToRunningProject: () -> Unit = {},
    onSelectProject: (Project) -> Unit = {},
    onCreateProject: (String) -> Unit = {},
    onCloneFromSolo: (Project) -> Unit = {},
    onDeleteTeamProject: (Project) -> Unit = {},
    onImportZip: (Uri) -> Unit = {},
    onCloneGit: (String) -> Unit = {},
    onStartGitHubLogin: () -> Unit = {},
    onGenerateNewGitHubCode: () -> Unit = {},
    onRefreshGitHub: () -> Unit = {},
    onDisconnectGitHub: () -> Unit = {},
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
    onOpenTeamMcpSettings: () -> Unit = {},
    onAutoOptimizeModels: () -> Unit = {},
    onReleaseFileLock: (String, AgentSlot) -> Unit = { _, _ -> },
    onOpenGroupChat: () -> Unit,
    onOpenProviderSettings: () -> Unit,
    onNextPart: () -> Unit,
) {
    var showContinuousOptions by remember { mutableStateOf(false) }
    var showWorkspaceGitPanel by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showCloneSoloDialog by remember { mutableStateOf(false) }
    var showGitCloneDialog by remember { mutableStateOf(false) }
    var showGitHubRepoDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var gitUrlInput by remember { mutableStateOf("") }
    var repositorySearch by remember { mutableStateOf("") }

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

    if (showCloneSoloDialog) {
        AlertDialog(
            onDismissRequest = { showCloneSoloDialog = false },
            icon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = PocketOrange) },
            title = { Text(stringResource(R.string.team_clone_from_solo_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.team_clone_from_solo_desc),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (soloProjects.isEmpty()) {
                        Text(
                            text = stringResource(R.string.team_clone_from_solo_empty),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(soloProjects, key = { it.id }) { solo ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !projectImporting && !gitCloneRunning) {
                                            showCloneSoloDialog = false
                                            onCloneFromSolo(solo)
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.Folder, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(solo.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("/workspaces/${solo.slug} · ${solo.language}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCloneSoloDialog = false }) { Text(stringResource(R.string.action_close)) }
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
        val clipboard = LocalClipboardManager.current
        val filteredRepositories = githubRepositories.filter { repository ->
            repositorySearch.isBlank() || repository.fullName.contains(repositorySearch, ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { if (!gitCloneRunning) showGitHubRepoDialog = false },
            icon = { Icon(Icons.Default.Code, null, tint = PocketOrange) },
            title = { Text(githubLogin?.let { stringResource(R.string.projects_github_account, it) } ?: stringResource(R.string.projects_connect_github)) },
            text = {
                when (githubAuthStatus) {
                    GitHubAuthStatus.DISCONNECTED, GitHubAuthStatus.ERROR -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            githubMessage ?: stringResource(R.string.ws_github_signin_desc),
                            color = if (githubAuthStatus == GitHubAuthStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                        Button(onClick = onStartGitHubLogin, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.ws_github_signin))
                        }
                    }
                    GitHubAuthStatus.STARTING -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(githubMessage ?: stringResource(R.string.ws_github_starting))
                    }
                    GitHubAuthStatus.AWAITING_USER -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.ws_github_enter_code), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { githubUserCode?.let { clipboard.setText(AnnotatedString(it)) } },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                githubUserCode.orEmpty(),
                                modifier = Modifier.padding(16.dp),
                                textAlign = TextAlign.Center,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                letterSpacing = 2.sp,
                            )
                        }
                        Text(stringResource(R.string.ws_github_tap_code), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            onClick = onGenerateNewGitHubCode,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.ws_github_new_code))
                        }
                    }
                    GitHubAuthStatus.CONNECTED -> Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(githubMessage ?: stringResource(R.string.ws_github_select_repo), modifier = Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = onRefreshGitHub, enabled = !githubRepositoriesLoading) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.ws_github_refresh_repos))
                            }
                        }
                        OutlinedTextField(
                            value = repositorySearch,
                            onValueChange = { repositorySearch = it },
                            placeholder = { Text(stringResource(R.string.ws_github_search_repos)) },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (githubRepositoriesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(filteredRepositories, key = { it.fullName }) { repository ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = !gitCloneRunning) {
                                        showGitHubRepoDialog = false
                                        onCloneGitHub(repository)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                ) {
                                    Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (repository.private) Icons.Default.Key else Icons.Default.Code, null, modifier = Modifier.size(17.dp), tint = PocketOrange)
                                        Spacer(Modifier.width(9.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(repository.fullName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${if (repository.private) stringResource(R.string.ws_repo_private) else stringResource(R.string.ws_repo_public)} · ${repository.defaultBranch}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (githubAuthStatus == GitHubAuthStatus.CONNECTED) {
                    TextButton(onClick = { showGitHubRepoDialog = false }) { Text(stringResource(R.string.action_close)) }
                }
            },
            dismissButton = {
                if (githubAuthStatus == GitHubAuthStatus.CONNECTED) {
                    TextButton(onClick = { onDisconnectGitHub(); showGitHubRepoDialog = false }) { Text(stringResource(R.string.ws_disconnect)) }
                } else if (githubAuthStatus != GitHubAuthStatus.STARTING) {
                    TextButton(onClick = { showGitHubRepoDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }

    val isLockedByOtherProject = runningTeamProjectId != null && runningTeamProjectId != activeProject?.id

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(top = 8.dp),
                navigationIcon = {
                    IconButton(onClick = onBackToProjects) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to folders")
                    }
                },
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
                            onClick = onOpenTeamMcpSettings,
                            label = { Text("Team MCP") },
                            leadingIcon = { Icon(Icons.Default.Hub, contentDescription = null, Modifier.size(16.dp)) },
                        )
                    }
                    item {
                        AssistChip(
                            onClick = onAutoOptimizeModels,
                            label = { Text("Auto-Models") },
                            leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null, Modifier.size(16.dp)) },
                        )
                    }
                }
            }

            // Autonomous Supervisor panel has been moved to its own dedicated Supervisor tab!

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

            // 1.5 Auto-Push to Git Toggle Card (Duplicate Workspace & Git Panel removed from Team Settings)
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Push ขึ้น Git อัตโนมัติเมื่องานเสร็จ",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = if (autoPushGitEnabled) "เปิดใช้งาน — ระบบจะ commit & push ให้อัตโนมัติ" else "ปิดใช้งาน — กดปุ่ม Push ที่มุมขวาบนแชทเอง",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = autoPushGitEnabled,
                            onCheckedChange = onToggleAutoPushGit,
                        )
                    }
                }
            }

            // 2. Control Panel (with Single-Project Lock + Prominent Group Chat Command Button)
            if (isLockedByOtherProject) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFF59E0B).copy(alpha = 0.14f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.team_locked_by_other_project, runningTeamProjectName ?: ""),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFD97706),
                            )
                            OutlinedButton(
                                onClick = onJumpToRunningProject,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(stringResource(R.string.team_jump_running_project), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isTeamRunning && !isLockedByOtherProject) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
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
                    } else if (!isLockedByOtherProject) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "พิมพ์สั่งงานในแท็บแชทด้านล่างเพื่อเริ่มการทำงานของทีม AI (ปุ่มพัก/หยุดจะแสดงเมื่อทีมเริ่มทำงาน)",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
                val providerCfg = providerConfigs.firstOrNull { it.id == agent.config.providerId }
                val isAgy = agent.config.providerId == "preset-antigravity" || providerCfg?.type == ProviderType.ANTIGRAVITY
                val slotAuth = teamAgentAntigravityAuth[agent.config.slot] ?: AntigravityAuthState()
                val effectiveAuth = if (slotAuth.status != AntigravityAuthStatus.SIGNED_OUT) slotAuth else defaultAntigravityAuth
                val connStatus = when {
                    agent.activityState.activity == ActivityType.ERROR -> 1 // Yellow: waiting/reconnecting
                    isAgy -> when (effectiveAuth.status) {
                        AntigravityAuthStatus.SIGNED_IN -> 2 // Green: connected
                        AntigravityAuthStatus.STARTING, AntigravityAuthStatus.COMPLETING, AntigravityAuthStatus.AWAITING_CODE -> 1 // Yellow: connecting/waiting
                        AntigravityAuthStatus.SIGNED_OUT, AntigravityAuthStatus.ERROR -> 0 // Red: disconnected
                    }
                    hasProviderApiKey(agent.config.providerId) -> 2 // Green: API key configured
                    else -> 0 // Red: disconnected / missing key
                }
                val connDetail = when {
                    isAgy && effectiveAuth.status == AntigravityAuthStatus.SIGNED_IN -> effectiveAuth.accountEmail
                    !isAgy -> providerCfg?.name
                    else -> null
                }
                val connLoading = isAgy && (effectiveAuth.status == AntigravityAuthStatus.STARTING || effectiveAuth.status == AntigravityAuthStatus.COMPLETING)
                AgentCard(
                    agent = agent,
                    connectionLevel = connStatus,
                    connectionDetail = connDetail,
                    connectionLoading = connLoading,
                    canRemove = agents.size > 1,
                    onCardClick = { onOpenAgentDetail(agent.config.slot) },
                    onChatClick = { onOpenAgentChat(agent.config.slot) },
                    onToggleEnabled = { onToggleAgentEnabled(agent.config.slot) },
                    onRemoveClick = { onRemoveAgent(agent.config.slot) },
                )
            }

            // 5. Active Project Models Live Quota & Usage Monitor Card
            item {
                QuotaMonitorCard(
                    officialQuota = officialQuota,
                    estimatedUsage = estimatedUsage,
                    localUsage = localUsage,
                    agents = agents,
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
                                Spacer(Modifier.height(8.dp))
                                ContinuousOptionRow(
                                    title = stringResource(R.string.token_saver_cache_title),
                                    description = stringResource(R.string.token_saver_cache_desc),
                                    isChecked = continuousConfig.tokenSaverCacheEnabled,
                                    onCheckedChange = { onUpdateContinuousConfig(continuousConfig.copy(tokenSaverCacheEnabled = it)) },
                                )
                                Spacer(Modifier.height(8.dp))
                                ContinuousOptionRow(
                                    title = stringResource(R.string.quota_auto_switch_title),
                                    description = stringResource(R.string.quota_auto_switch_desc, continuousConfig.quotaSwitchThresholdPercent),
                                    isChecked = continuousConfig.autoSwitchOnLowQuota,
                                    onCheckedChange = { onUpdateContinuousConfig(continuousConfig.copy(autoSwitchOnLowQuota = it)) },
                                )
                                if (continuousConfig.autoSwitchOnLowQuota) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.quota_fallback_model_label),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    val realPlanModels = remember(agents, providerConfigs) {
                                        val usedIds = agents.map { it.config.providerId }.toSet()
                                        providerConfigs
                                            .filter { usedIds.isEmpty() || it.id in usedIds }
                                            .flatMap { it.availableModels }
                                            .filter { it.isNotBlank() }
                                            .distinct()
                                            .take(8)
                                    }
                                    val fallbackOptions = listOf("" to stringResource(R.string.quota_fallback_auto_coding)) +
                                        realPlanModels.map { it to it }
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(fallbackOptions) { (modelId, label) ->
                                            FilterChip(
                                                selected = continuousConfig.fallbackModelId == modelId,
                                                onClick = { onUpdateContinuousConfig(continuousConfig.copy(fallbackModelId = modelId)) },
                                                label = { Text(label, fontSize = 11.sp) },
                                            )
                                        }
                                    }
                                }
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
    connectionLevel: Int = 2,
    connectionDetail: String? = null,
    connectionLoading: Boolean = false,
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
                        Spacer(Modifier.height(4.dp))
                        val (connColor, connText) = when (connectionLevel) {
                            2 -> Color(0xFF22C55E) to (connectionDetail?.let { "${stringResource(R.string.conn_status_connected)} · $it" } ?: stringResource(R.string.conn_status_connected))
                            1 -> Color(0xFFF59E0B) to if (connectionLoading) stringResource(R.string.conn_status_connecting) else stringResource(R.string.conn_status_waiting_network)
                            else -> Color(0xFFEF4444) to stringResource(R.string.conn_status_disconnected)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (connectionLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.6.dp,
                                    color = connColor,
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(connColor),
                                )
                            }
                            Text(
                                text = connText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = connColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
    agents: List<AgentState> = emptyList(),
    providerConfigs: List<ProviderConfig> = emptyList(),
    allProviderQuotas: Map<String, OfficialQuota> = emptyMap(),
    allProviderEstimated: Map<String, EstimatedUsage> = emptyMap(),
    onCardClick: () -> Unit = {},
) {
    // Show ONLY the models/providers actively used by agents in this project
    val activeModels = remember(agents, providerConfigs) {
        val enabled = agents.filter { it.config.isEnabled }
        if (enabled.isNotEmpty()) {
            enabled.distinctBy { "${it.config.providerId}:${it.config.modelId}" }
        } else {
            agents.distinctBy { "${it.config.providerId}:${it.config.modelId}" }
        }
    }

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
                    text = stringResource(R.string.quota_project_models_title).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Details", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(10.dp))

            if (activeModels.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeModels.forEach { agent ->
                        val providerId = agent.config.providerId
                        val cfg = providerConfigs.firstOrNull { it.id == providerId }
                        val q = allProviderQuotas[providerId] ?: officialQuota
                        val e = allProviderEstimated[providerId] ?: estimatedUsage
                        val isAvail = q?.isAvailable == true
                        val remaining = (q?.remainingPercent ?: 1f).coerceIn(0f, 1f)
                        val usedPct = ((1f - remaining) * 100).toInt()
                        val badgeColor = when {
                            !isAvail -> MaterialTheme.colorScheme.onSurfaceVariant
                            remaining > 0.25f -> Color(0xFF16A34A)
                            remaining > 0.12f -> Color(0xFFD97706)
                            else -> Color(0xFFDC2626)
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = agent.config.modelId.ifBlank { cfg?.selectedModelId ?: "Default Model" },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${agent.config.name} · ${cfg?.name ?: providerId} · ${e?.estimatedTokens ?: 0} tok",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Surface(shape = RoundedCornerShape(8.dp), color = badgeColor.copy(alpha = 0.14f)) {
                                    Text(
                                        text = if (isAvail) "${(remaining * 100).toInt()}% (-$usedPct%)" else "LIVE ${e?.estimatedTokens ?: 0}",
                                        color = badgeColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (isAvail) {
                                LinearProgressIndicator(
                                    progress = { remaining },
                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                                    color = badgeColor,
                                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
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
