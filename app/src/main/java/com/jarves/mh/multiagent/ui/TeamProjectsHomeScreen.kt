package com.jarves.mh.multiagent.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.jarves.mh.network.GitHubRepository
import com.jarves.mh.ui.GitHubAuthStatus
import com.jarves.mh.ui.theme.PocketOrange

/**
 * Level 1 of the AI Team tab: Folder/Project list for isolated AI Team workspaces (/team_workspaces).
 * Users can create new folders, clone from Solo Studio, import ZIP, clone Git/GitHub,
 * and tap any folder to enter its dedicated AI Team workspace & group chat.
 * Only ONE AI Team project may actively run at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamProjectsHomeScreen(
    teamProjects: List<Project>,
    soloProjects: List<Project>,
    runningTeamProjectId: String?,
    githubAuthStatus: GitHubAuthStatus,
    githubLogin: String?,
    githubUserCode: String?,
    githubMessage: String?,
    githubRepositories: List<GitHubRepository>,
    githubRepositoriesLoading: Boolean,
    gitCloneRunning: Boolean,
    projectImporting: Boolean,
    onOpenTeamProject: (Project) -> Unit,
    onCreateQuickTeamProject: () -> Unit = {},
    onCreateTeamProject: (String) -> Unit,
    onCreateAndroidTeamProject: (String, com.jarves.mh.model.AndroidTemplate) -> Unit = { name, _ -> onCreateTeamProject(name) },
    onRenameTeamProject: (String, String) -> Unit = { _, _ -> },
    onUpdateTeamProjectIdentity: (projectId: String, type: com.jarves.mh.model.ProjectType, iconKey: String) -> Unit = { _, _, _ -> },
    onCloneFromSolo: (Project) -> Unit,
    onDeleteTeamProject: (Project) -> Unit,
    onImportZip: (Uri) -> Unit,
    onCloneGit: (String) -> Unit,
    onStartGitHubLogin: () -> Unit,
    onGenerateNewGitHubCode: () -> Unit,
    onRefreshGitHub: () -> Unit,
    onDisconnectGitHub: () -> Unit,
    onCloneGitHub: (GitHubRepository) -> Unit,
) {
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showCloneSoloDialog by remember { mutableStateOf(false) }
    var showGitCloneDialog by remember { mutableStateOf(false) }
    var showGitHubDialog by remember { mutableStateOf(false) }
    var folderNameInput by remember { mutableStateOf("") }
    var selectedAndroidTemplate by remember { mutableStateOf<com.jarves.mh.model.AndroidTemplate?>(null) }
    var gitUrlInput by remember { mutableStateOf("") }
    var repositorySearch by remember { mutableStateOf("") }

    val importZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImportZip(uri)
    }

    val runningProject = remember(teamProjects, runningTeamProjectId) {
        teamProjects.firstOrNull { it.id == runningTeamProjectId }
    }

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            icon = { Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = PocketOrange) },
            title = { Text(stringResource(R.string.an_create_project)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = folderNameInput,
                        onValueChange = { folderNameInput = it },
                        label = { Text(stringResource(R.string.ws_project_name)) },
                        placeholder = { Text(stringResource(R.string.project_auto_name_hint), fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.project_type), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(
                            onClick = { selectedAndroidTemplate = null },
                            label = { Text(stringResource(R.string.project_type_general), fontSize = 11.sp) },
                            leadingIcon = { if (selectedAndroidTemplate == null) Icon(Icons.Default.Check, null, Modifier.size(15.dp)) },
                        )
                        AssistChip(
                            onClick = { selectedAndroidTemplate = com.jarves.mh.model.AndroidTemplate.COMPOSE },
                            label = { Text(stringResource(R.string.project_type_compose), fontSize = 11.sp) },
                            leadingIcon = { if (selectedAndroidTemplate == com.jarves.mh.model.AndroidTemplate.COMPOSE) Icon(Icons.Default.Check, null, Modifier.size(15.dp)) },
                        )
                        AssistChip(
                            onClick = { selectedAndroidTemplate = com.jarves.mh.model.AndroidTemplate.XML },
                            label = { Text(stringResource(R.string.project_type_xml), fontSize = 11.sp) },
                            leadingIcon = { if (selectedAndroidTemplate == com.jarves.mh.model.AndroidTemplate.XML) Icon(Icons.Default.Check, null, Modifier.size(15.dp)) },
                        )
                    }
                    Text(
                        stringResource(
                            when (selectedAndroidTemplate) {
                                com.jarves.mh.model.AndroidTemplate.COMPOSE -> R.string.project_type_compose_desc
                                com.jarves.mh.model.AndroidTemplate.XML -> R.string.project_type_xml_desc
                                null -> R.string.project_type_general_desc
                            },
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selectedAndroidTemplate != null) {
                        Text(
                            stringResource(R.string.an_template_info, 28, 36),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val clean = folderNameInput.trim()
                    val tmpl = selectedAndroidTemplate
                    if (tmpl != null) {
                        onCreateAndroidTeamProject(clean, tmpl)
                    } else {
                        onCreateTeamProject(clean)
                    }
                    folderNameInput = ""
                    selectedAndroidTemplate = null
                    showNewFolderDialog = false
                }) { Text(stringResource(R.string.ws_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text(stringResource(R.string.action_cancel)) }
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
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Folder, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(solo.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("/workspaces/${solo.slug}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }) { Text(stringResource(R.string.ws_clone_project)) }
            },
            dismissButton = {
                TextButton(onClick = { showGitCloneDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (showGitHubDialog) {
        val clipboard = LocalClipboardManager.current
        val filteredRepositories = githubRepositories.filter { repository ->
            repositorySearch.isBlank() || repository.fullName.contains(repositorySearch, ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { if (!gitCloneRunning) showGitHubDialog = false },
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
                        OutlinedButton(onClick = onGenerateNewGitHubCode, modifier = Modifier.fillMaxWidth()) {
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
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(filteredRepositories, key = { it.fullName }) { repository ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = !gitCloneRunning) {
                                        showGitHubDialog = false
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
                    TextButton(onClick = { showGitHubDialog = false }) { Text(stringResource(R.string.action_close)) }
                }
            },
            dismissButton = {
                if (githubAuthStatus == GitHubAuthStatus.CONNECTED) {
                    TextButton(onClick = { onDisconnectGitHub(); showGitHubDialog = false }) { Text(stringResource(R.string.ws_disconnect)) }
                } else if (githubAuthStatus != GitHubAuthStatus.STARTING) {
                    TextButton(onClick = { showGitHubDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                }
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
                            text = stringResource(R.string.team_folders_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.team_folders_subtitle, teamProjects.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Active Single-Project Running Banner
            if (runningProject != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF22C55E).copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenTeamProject(runningProject) },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF22C55E)))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.team_active_running_banner, runningProject.name),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF16A34A),
                                )
                                Text(
                                    text = stringResource(R.string.team_single_project_rule),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF16A34A))
                        }
                    }
                }
            }

            // Clean Modern Action Bar: 1 Primary CTA + Quick Start + Compact Import Menu (Zero Clutter)
            item {
                var importMenuExpanded by remember { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { showNewFolderDialog = true },
                            enabled = !projectImporting && !gitCloneRunning,
                            modifier = Modifier.weight(1.35f).height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.projects_new_project),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FilledTonalButton(
                            onClick = onCreateQuickTeamProject,
                            enabled = !projectImporting && !gitCloneRunning,
                            modifier = Modifier.weight(0.95f).height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.projects_quick_project),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box {
                            OutlinedButton(
                                onClick = { importMenuExpanded = true },
                                enabled = !projectImporting && !gitCloneRunning,
                                modifier = Modifier.height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("นำเข้า", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            DropdownMenu(
                                expanded = importMenuExpanded,
                                onDismissRequest = { importMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("นำเข้าไฟล์ ZIP") },
                                    leadingIcon = { Icon(Icons.Default.FolderZip, null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        importMenuExpanded = false
                                        importZipLauncher.launch("*/*")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("โคลนจาก Git URL") },
                                    leadingIcon = { Icon(Icons.Default.Code, null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        importMenuExpanded = false
                                        showGitCloneDialog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(githubLogin?.let { "GitHub ($it)" } ?: "เชื่อมต่อ GitHub") },
                                    leadingIcon = { Icon(Icons.Default.CloudSync, null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        importMenuExpanded = false
                                        showGitHubDialog = true
                                        if (githubAuthStatus == GitHubAuthStatus.CONNECTED && githubRepositories.isEmpty()) {
                                            onRefreshGitHub()
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.team_clone_from_solo)) },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        importMenuExpanded = false
                                        showCloneSoloDialog = true
                                    },
                                )
                            }
                        }
                    }
                    if (projectImporting || gitCloneRunning) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                        )
                    }
                }
            }

            // Folder List
            if (teamProjects.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.FolderShared,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                            Text(
                                text = "เริ่มต้นสร้างโปรเจกต์แรกของคุณ",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "1. กดสร้างโปรเจกต์ใหม่  →  2. คุยออกแบบกับทีม AI ในโหมด Chat  →  3. สลับเป็นโหมด Task เพื่อให้ทีมลงมือสร้างและบิ้ว APK อัตโนมัติ",
                                textAlign = TextAlign.Center,
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(teamProjects, key = { it.id }) { project ->
                    val isRunningThis = runningTeamProjectId == project.id
                    var menuOpen by remember(project.id) { mutableStateOf(false) }
                    var showRename by remember(project.id) { mutableStateOf(false) }
                    var showIdentityDialog by remember(project.id) { mutableStateOf(false) }
                    var showDelete by remember(project.id) { mutableStateOf(false) }
                    var renameText by remember(project.id) { mutableStateOf(project.name) }
                    var editType by remember(project.id) { mutableStateOf(project.type) }
                    var editIconKey by remember(project.id) { mutableStateOf(project.iconKey) }

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (isRunningThis) 1.5.dp else 1.dp,
                                color = if (isRunningThis) OrqenSemanticColors.Success else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(16.dp),
                            )
                            .clickable { onOpenTeamProject(project) },
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isRunningThis) OrqenSemanticColors.Success.copy(alpha = 0.14f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = com.jarves.mh.ui.ProjectIconCatalog.resolveIcon(project),
                                    contentDescription = project.type.displayName,
                                    tint = if (isRunningThis) OrqenSemanticColors.Success else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = project.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        Text(
                                            text = project.type.displayName,
                                            fontSize = 9.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    if (isRunningThis) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = OrqenSemanticColors.Success.copy(alpha = 0.16f),
                                        ) {
                                            Text(
                                                text = stringResource(R.string.team_folder_running_badge),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = OrqenSemanticColors.Success,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "/team_workspaces/${project.slug} · ${project.language}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.ws_project_options),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Change Type & Icon") },
                                        leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            editType = project.type
                                            editIconKey = project.iconKey
                                            showIdentityDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.ws_rename_project)) },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            renameText = project.name
                                            showRename = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.ws_delete_project),
                                                color = if (isRunningThis) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = null,
                                                tint = if (isRunningThis) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        enabled = !isRunningThis,
                                        onClick = {
                                            menuOpen = false
                                            showDelete = true
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (showIdentityDialog) {
                        AlertDialog(
                            onDismissRequest = { showIdentityDialog = false },
                            title = { Text("Project Type & Icon") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Project Type", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(com.jarves.mh.model.ProjectType.entries) { pType ->
                                            FilterChip(
                                                selected = editType == pType,
                                                onClick = { editType = pType },
                                                label = { Text(pType.displayName, fontSize = 11.sp) },
                                            )
                                        }
                                    }
                                    Text("Project Icon", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(com.jarves.mh.ui.ProjectIconCatalog.builtInIcons, key = { it.key }) { opt ->
                                            val activeKey = editIconKey.ifBlank { com.jarves.mh.ui.ProjectIconCatalog.defaultIconKeyFor(editType) }
                                            FilterChip(
                                                selected = activeKey == opt.key,
                                                onClick = { editIconKey = opt.key },
                                                leadingIcon = { Icon(opt.icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                                label = { Text(opt.label, fontSize = 10.5.sp) },
                                            )
                                        }
                                    }
                                    TextButton(
                                        onClick = { editIconKey = "" },
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                    ) {
                                        Text("Reset to default icon for ${editType.displayName}", fontSize = 11.sp)
                                    }
                                }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    onUpdateTeamProjectIdentity(project.id, editType, editIconKey)
                                    showIdentityDialog = false
                                }) { Text(stringResource(R.string.action_save)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showIdentityDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                            },
                        )
                    }

                    if (showRename) {
                        AlertDialog(
                            onDismissRequest = { showRename = false },
                            title = { Text(stringResource(R.string.ws_rename_project)) },
                            text = {
                                OutlinedTextField(
                                    value = renameText,
                                    onValueChange = { renameText = it },
                                    label = { Text(stringResource(R.string.team_folder_name_hint)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        onRenameTeamProject(project.id, renameText.trim())
                                        showRename = false
                                    },
                                    enabled = renameText.isNotBlank(),
                                ) { Text(stringResource(R.string.action_save)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.action_cancel)) }
                            },
                        )
                    }

                    if (showDelete) {
                        com.jarves.mh.ui.SafeDeleteProjectDialog(
                            projectName = project.name,
                            onConfirmDelete = {
                                showDelete = false
                                onDeleteTeamProject(project)
                            },
                            onDismiss = { showDelete = false },
                        )
                    }
                }
            }
        }
    }
}
