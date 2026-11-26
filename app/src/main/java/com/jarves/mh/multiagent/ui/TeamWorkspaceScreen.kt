package com.jarves.mh.multiagent.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import com.jarves.mh.R
import com.jarves.mh.model.Project
import com.jarves.mh.multiagent.chat.GroupChatMessage
import com.jarves.mh.multiagent.chat.GroupMessageType
import com.jarves.mh.multiagent.locks.FileLock
import com.jarves.mh.multiagent.model.*
import com.jarves.mh.multiagent.supervisor.ContinuousExecutionConfig
import com.jarves.mh.multiagent.tasks.ProjectPart
import com.jarves.mh.multiagent.tasks.TeamTask
import com.jarves.mh.network.GitHubRepository
import com.jarves.mh.provider.core.EstimatedUsage
import com.jarves.mh.provider.core.LocalUsage
import com.jarves.mh.provider.core.OfficialQuota
import com.jarves.mh.provider.core.ProviderConfig
import com.jarves.mh.runtime.AntigravityAuthState
import com.jarves.mh.ui.FileTypeIcon
import com.jarves.mh.ui.GitHubAuthStatus
import com.jarves.mh.ui.theme.PocketOrange
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class TeamWorkspaceTab(val icon: ImageVector) {
    CHAT(Icons.AutoMirrored.Filled.Chat),
    FILES(Icons.Default.Folder),
    TERMINAL(Icons.Default.Terminal),
    PREVIEW(Icons.Default.Preview),
    SETTINGS(Icons.Default.Settings),
}

/**
 * Full 5-tab AI Team Project Workspace (mirrors Solo Workspace UX):
 * 1. Chat (Unified CEO + Agents Group Chat with live owner steering)
 * 2. Files (Browse & edit real files in /team_workspaces/<projectId>)
 * 3. Terminal (Run real Linux PRoot commands inside /team_workspaces/<projectId>)
 * 4. Preview (Dual-mode: Web Preview + Android App UI Preview)
 * 5. Settings (Configure 2-4 AI agents, roles, models, isolated accounts, quota, feed)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamWorkspaceScreen(
    project: Project,
    projects: List<Project>,
    soloProjects: List<Project>,
    agents: List<AgentState>,
    tasks: List<TeamTask>,
    parts: List<ProjectPart>,
    currentPartNumber: Int,
    fileLocks: List<FileLock>,
    messages: List<GroupChatMessage>,
    agentLiveLogs: Map<AgentSlot, List<String>> = emptyMap(),
    teamTerminalOutput: String,
    teamTerminalRunning: Boolean,
    isTeamRunning: Boolean,
    teamBannerHealth: TeamBannerHealth = TeamBannerHealth.RUNNING,
    teamBannerMessage: String? = null,
    teamBannerDetail: String? = null,
    ownerName: String = "",
    typingAgentName: String? = null,
    runningTeamProjectId: String?,
    runningTeamProjectName: String?,
    activeSupervisedTaskId: String? = null,
    supervisorSnapshots: Map<String, com.jarves.mh.multiagent.supervisor.SupervisorUiSnapshot> = emptyMap(),
    pendingDirectivesCount: Int,
    officialQuota: OfficialQuota?,
    estimatedUsage: EstimatedUsage?,
    localUsage: LocalUsage,
    providerConfigs: List<ProviderConfig>,
    teamAgentAntigravityAuth: Map<AgentSlot, AntigravityAuthState>,
    defaultAntigravityAuth: AntigravityAuthState,
    hasProviderApiKey: (String) -> Boolean,
    allProviderQuotas: Map<String, OfficialQuota>,
    allProviderEstimated: Map<String, EstimatedUsage>,
    continuousConfig: ContinuousExecutionConfig,
    githubAuthStatus: GitHubAuthStatus,
    githubLogin: String?,
    githubUserCode: String?,
    githubMessage: String?,
    githubRepositories: List<GitHubRepository>,
    githubRepositoriesLoading: Boolean,
    gitCloneRunning: Boolean,
    projectImporting: Boolean,
    onBackToFolders: () -> Unit,
    onSendGroupMessage: (text: String, type: GroupMessageType, targetSlot: AgentSlot?) -> Unit,
    onSendGroupMessageWithImages: ((text: String, type: GroupMessageType, targetSlot: AgentSlot?, imageUris: List<Uri>) -> Unit)? = null,
    onClearGroupChat: () -> Unit = {},
    onRunTeamTerminalCommand: (String) -> Unit,
    onClearTeamTerminal: () -> Unit,
    onJumpToRunningProject: () -> Unit,
    onSelectProject: (Project) -> Unit,
    onCreateProject: (String) -> Unit,
    onCloneFromSolo: (Project) -> Unit,
    onDeleteTeamProject: (Project) -> Unit,
    onImportZip: (Uri) -> Unit,
    onCloneGit: (String) -> Unit,
    onStartGitHubLogin: () -> Unit,
    onGenerateNewGitHubCode: () -> Unit,
    onRefreshGitHub: () -> Unit,
    onDisconnectGitHub: () -> Unit,
    onCloneGitHub: (GitHubRepository) -> Unit,
    onAddAgent: () -> Unit,
    onRemoveAgent: (AgentSlot) -> Unit,
    onUpdateContinuousConfig: (ContinuousExecutionConfig) -> Unit,
    onStartTeam: () -> Unit,
    onPauseTeam: () -> Unit,
    onResumeTeam: () -> Unit,
    onStopTeam: () -> Unit,
    onOpenAgentDetail: (AgentSlot) -> Unit,
    onToggleAgentEnabled: (AgentSlot) -> Unit,
    onOpenQuotaDashboard: () -> Unit,
    onOpenActivityFeed: () -> Unit,
    onReleaseFileLock: (String, AgentSlot) -> Unit,
    onNextPart: () -> Unit,
) {
    var activeTab by rememberSaveable(project.id) { mutableStateOf(TeamWorkspaceTab.CHAT) }
    val chatListState = rememberSaveable(project.id, saver = LazyListState.Saver) { LazyListState() }
    var chatInitialScrollDone by rememberSaveable(project.id) { mutableStateOf(false) }
    var pendingFileToOpen by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val workspaceDir = remember(project.id) { File(context.filesDir, "team_workspaces/${project.id}").apply { mkdirs() } }

    BackHandler {
        if (activeTab != TeamWorkspaceTab.CHAT) {
            activeTab = TeamWorkspaceTab.CHAT
        } else {
            onBackToFolders()
        }
    }

    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!keyboardVisible) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                TeamWorkspaceTab.entries.forEach { tab ->
                    val labelRes = when (tab) {
                        TeamWorkspaceTab.CHAT -> R.string.tab_chat
                        TeamWorkspaceTab.FILES -> R.string.tab_files
                        TeamWorkspaceTab.TERMINAL -> R.string.tab_terminal
                        TeamWorkspaceTab.PREVIEW -> R.string.tab_preview
                        TeamWorkspaceTab.SETTINGS -> R.string.tab_settings
                    }
                    NavigationBarItem(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        icon = { Icon(tab.icon, contentDescription = stringResource(labelRes)) },
                        label = { Text(stringResource(labelRes), fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PocketOrange,
                            selectedTextColor = PocketOrange,
                            indicatorColor = PocketOrange.copy(alpha = 0.16f),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            var workspaceBounds by remember { mutableStateOf(IntSize.Zero) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { workspaceBounds = it },
            ) {
            when (activeTab) {
                TeamWorkspaceTab.CHAT -> {
                    val hasDisconnectedAgent = remember(agents, teamAgentAntigravityAuth, defaultAntigravityAuth, providerConfigs) {
                        agents.filter { it.config.isEnabled }.any { agent ->
                            val cfg = providerConfigs.firstOrNull { it.id == agent.config.providerId }
                            val isAgy = agent.config.providerId == "preset-antigravity" || cfg?.type?.name == "ANTIGRAVITY"
                            if (isAgy) {
                                val own = teamAgentAntigravityAuth[agent.config.slot]
                                val eff = if (own != null && own.status != com.jarves.mh.runtime.AntigravityAuthStatus.SIGNED_OUT) own else defaultAntigravityAuth
                                eff.status == com.jarves.mh.runtime.AntigravityAuthStatus.SIGNED_OUT || eff.status == com.jarves.mh.runtime.AntigravityAuthStatus.ERROR
                            } else {
                                !hasProviderApiKey(agent.config.providerId)
                            }
                        }
                    }
                    val effectiveHealth = when {
                        teamBannerHealth != TeamBannerHealth.RUNNING -> teamBannerHealth
                        hasDisconnectedAgent -> TeamBannerHealth.DISCONNECTED
                        else -> TeamBannerHealth.RUNNING
                    }
                    CentralGroupChatScreen(
                        teamName = project.name,
                        messages = messages,
                        agents = agents,
                        agentLiveLogs = agentLiveLogs,
                        isTeamRunning = isTeamRunning && runningTeamProjectId == project.id,
                        teamBannerHealth = effectiveHealth,
                        teamBannerMessage = teamBannerMessage,
                        teamBannerDetail = teamBannerDetail,
                        ownerName = ownerName,
                        typingAgentName = typingAgentName,
                        pendingDirectivesCount = pendingDirectivesCount,
                        listState = chatListState,
                        initialScrollDone = chatInitialScrollDone,
                        onInitialScrollDone = { chatInitialScrollDone = true },
                        onClearChat = onClearGroupChat,
                        onOpenFile = { path ->
                            pendingFileToOpen = path
                            activeTab = TeamWorkspaceTab.FILES
                        },
                        onSendMessageWithImages = onSendGroupMessageWithImages,
                        onSendMessage = onSendGroupMessage,
                        onBack = onBackToFolders,
                    )
                }

                TeamWorkspaceTab.FILES -> TeamFilesTab(
                    projectName = project.name,
                    workspaceDir = workspaceDir,
                    initialFilePathToOpen = pendingFileToOpen,
                    onConsumedInitialFile = { pendingFileToOpen = null },
                    onBack = onBackToFolders,
                )

                TeamWorkspaceTab.TERMINAL -> TeamTerminalTab(
                    projectName = project.name,
                    projectSlug = project.slug,
                    output = teamTerminalOutput,
                    isRunning = teamTerminalRunning,
                    onRunCommand = onRunTeamTerminalCommand,
                    onClear = onClearTeamTerminal,
                    onBack = onBackToFolders,
                )

                TeamWorkspaceTab.PREVIEW -> TeamDualPreviewTab(
                    projectName = project.name,
                    workspaceDir = workspaceDir,
                    onRunInTerminal = { cmd ->
                        onRunTeamTerminalCommand(cmd)
                        activeTab = TeamWorkspaceTab.TERMINAL
                    },
                    onBack = onBackToFolders,
                )

                TeamWorkspaceTab.SETTINGS -> TeamDashboardScreen(
                    teamName = project.name,
                    workspacePath = "/team_workspaces/${project.slug}",
                    projects = projects,
                    soloProjects = soloProjects,
                    activeProject = project,
                    githubAuthStatus = githubAuthStatus,
                    githubLogin = githubLogin,
                    githubUserCode = githubUserCode,
                    githubMessage = githubMessage,
                    githubRepositories = githubRepositories,
                    githubRepositoriesLoading = githubRepositoriesLoading,
                    gitCloneRunning = gitCloneRunning,
                    projectImporting = projectImporting,
                    agents = agents,
                    tasks = tasks,
                    parts = parts,
                    currentPartNumber = currentPartNumber,
                    fileLocks = fileLocks,
                    officialQuota = officialQuota,
                    estimatedUsage = estimatedUsage,
                    localUsage = localUsage,
                    providerConfigs = providerConfigs,
                    teamAgentAntigravityAuth = teamAgentAntigravityAuth,
                    defaultAntigravityAuth = defaultAntigravityAuth,
                    hasProviderApiKey = hasProviderApiKey,
                    allProviderQuotas = allProviderQuotas,
                    allProviderEstimated = allProviderEstimated,
                    continuousConfig = continuousConfig,
                    isTeamRunning = isTeamRunning,
                    runningTeamProjectId = runningTeamProjectId,
                    runningTeamProjectName = runningTeamProjectName,
                    activeSupervisedTaskId = activeSupervisedTaskId,
                    supervisorSnapshots = supervisorSnapshots,
                    onBackToProjects = onBackToFolders,
                    onJumpToRunningProject = onJumpToRunningProject,
                    onSelectProject = onSelectProject,
                    onCreateProject = onCreateProject,
                    onCloneFromSolo = onCloneFromSolo,
                    onDeleteTeamProject = onDeleteTeamProject,
                    onImportZip = onImportZip,
                    onCloneGit = onCloneGit,
                    onStartGitHubLogin = onStartGitHubLogin,
                    onGenerateNewGitHubCode = onGenerateNewGitHubCode,
                    onRefreshGitHub = onRefreshGitHub,
                    onDisconnectGitHub = onDisconnectGitHub,
                    onCloneGitHub = onCloneGitHub,
                    onAddAgent = onAddAgent,
                    onRemoveAgent = onRemoveAgent,
                    onUpdateContinuousConfig = onUpdateContinuousConfig,
                    onStartTeam = onStartTeam,
                    onPauseTeam = onPauseTeam,
                    onResumeTeam = onResumeTeam,
                    onStopTeam = onStopTeam,
                    onOpenAgentChat = { activeTab = TeamWorkspaceTab.CHAT },
                    onOpenAgentDetail = onOpenAgentDetail,
                    onToggleAgentEnabled = onToggleAgentEnabled,
                    onOpenQuotaDashboard = onOpenQuotaDashboard,
                    onOpenActivityFeed = onOpenActivityFeed,
                    onReleaseFileLock = onReleaseFileLock,
                    onOpenGroupChat = { activeTab = TeamWorkspaceTab.CHAT },
                    onOpenProviderSettings = {},
                    onNextPart = onNextPart,
                )
            }

            // Draggable Floating Per-Agent Status Overlay (can be dragged freely anywhere on screen!)
            if (agents.isNotEmpty()) {
                TeamTopRightStatusOverlay(
                    agents = agents,
                    isTeamRunning = isTeamRunning && runningTeamProjectId == project.id,
                    parentBounds = workspaceBounds,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
            }
        }
    }
}

@Composable
private fun TeamTopRightStatusOverlay(
    agents: List<AgentState>,
    isTeamRunning: Boolean,
    parentBounds: IntSize,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val initialTopPx = with(density) { 66.dp.toPx() }
    val initialEndPx = with(density) { 10.dp.toPx() }

    val workingCount = agents.count { it.isWorking }
    val pausedCount = agents.count { it.activityState.activity == ActivityType.PAUSED || !it.config.isEnabled }

    // Clamp offset inside parent screen bounds so it can never be dragged off-screen
    val minX = if (parentBounds.width > 0 && overlaySize.width > 0) {
        -(parentBounds.width - overlaySize.width - initialEndPx).coerceAtLeast(0f)
    } else -1000f
    val maxX = initialEndPx
    val minY = -initialTopPx
    val maxY = if (parentBounds.height > 0 && overlaySize.height > 0) {
        (parentBounds.height - overlaySize.height - initialTopPx).coerceAtLeast(0f)
    } else 1600f

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 6.dp,
        border = BorderStroke(
            width = 1.dp,
            color = if (workingCount > 0) Color(0xFF16A34A).copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
        modifier = modifier
            .padding(top = 66.dp, end = 10.dp)
            .offset {
                IntOffset(
                    x = offsetX.coerceIn(minX, maxX).roundToInt(),
                    y = offsetY.coerceIn(minY, maxY).roundToInt(),
                )
            }
            .onSizeChanged { overlaySize = it }
            .pointerInput(parentBounds, overlaySize) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX = (offsetX + dragAmount.x).coerceIn(minX, maxX)
                    offsetY = (offsetY + dragAmount.y).coerceIn(minY, maxY)
                }
            }
            .widthIn(max = 280.dp),
    ) {
        Column(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .clickable { expanded = !expanded }
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.DragIndicator,
                    contentDescription = "Drag",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(13.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    agents.forEach { agent ->
                        val dotColor = when {
                            !agent.config.isEnabled || agent.activityState.activity == ActivityType.PAUSED -> Color(0xFFF59E0B)
                            agent.activityState.activity == ActivityType.ERROR -> Color(0xFFDC2626)
                            agent.isWorking -> Color(0xFF16A34A)
                            agent.activityState.activity == ActivityType.COMPLETED -> Color(0xFF10B981)
                            else -> Color(0xFF3B82F6)
                        }
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                    }
                }
                Text(
                    text = when {
                        workingCount > 0 -> stringResource(R.string.team_overlay_working_count, workingCount, agents.size)
                        isTeamRunning -> stringResource(R.string.team_overlay_standby)
                        pausedCount == agents.size -> stringResource(R.string.team_overlay_paused)
                        else -> "${agents.size} AI"
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (workingCount > 0) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    agents.forEach { agent ->
                        val act = agent.activityState.activity
                        val statusColor = when {
                            !agent.config.isEnabled || act == ActivityType.PAUSED -> Color(0xFFF59E0B)
                            act == ActivityType.ERROR -> Color(0xFFDC2626)
                            agent.isWorking -> Color(0xFF16A34A)
                            act == ActivityType.COMPLETED -> Color(0xFF10B981)
                            else -> Color(0xFF3B82F6)
                        }
                        val statusText = when {
                            !agent.config.isEnabled -> stringResource(R.string.team_overlay_paused)
                            else -> stringResource(act.labelRes)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (agent.isWorking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.6.dp,
                                    color = statusColor,
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(statusColor),
                                )
                            }
                            Text(
                                text = agent.config.name,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp),
                            )
                            Text(
                                text = "· $statusText",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = statusColor,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamFilesTab(
    projectName: String,
    workspaceDir: File,
    initialFilePathToOpen: String? = null,
    onConsumedInitialFile: () -> Unit = {},
    onBack: () -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var fileContent by remember { mutableStateOf("") }
    var savedBanner by remember { mutableStateOf(false) }
    var expandedDirs by rememberSaveable(workspaceDir.absolutePath) { mutableStateOf(emptyList<String>()) }
    var highlightedFilePath by remember { mutableStateOf<String?>(null) }
    val filesListState = androidx.compose.foundation.lazy.rememberLazyListState()

    // When clicking a file chip in Chat: expand ONLY that file's parent folders & highlight it in the tree!
    LaunchedEffect(initialFilePathToOpen, workspaceDir) {
        val requested = initialFilePathToOpen?.trim()?.removePrefix("/") ?: return@LaunchedEffect
        if (requested.isBlank()) return@LaunchedEffect
        val direct = File(workspaceDir, requested)
        val target = when {
            direct.isFile -> direct
            else -> workspaceDir.walkTopDown()
                .maxDepth(6)
                .filter { it.isFile && !it.path.contains("/.git/") && !it.path.contains("/node_modules/") && !it.path.contains("/build/") }
                .firstOrNull { f ->
                    val rel = f.relativeTo(workspaceDir).path
                    rel.equals(requested, ignoreCase = true) ||
                        rel.endsWith("/$requested", ignoreCase = true) ||
                        f.name.equals(requested.substringAfterLast('/'), ignoreCase = true)
                }
        }
        if (target != null && target.isFile) {
            val relPath = target.relativeTo(workspaceDir).path
            val segments = relPath.split('/')
            val parentFolders = (1 until segments.size).map { depth -> segments.take(depth).joinToString("/") }
            // Expand ONLY the ancestor folders along the path to this file
            expandedDirs = parentFolders
            highlightedFilePath = relPath
            selectedFile = null
            refreshTick++
        }
        onConsumedInitialFile()
    }

    LaunchedEffect(workspaceDir, selectedFile) {
        if (selectedFile != null) return@LaunchedEffect
        var lastFp = withContext(Dispatchers.IO) { computeWorkspaceFingerprint(workspaceDir) }
        while (true) {
            delay(2000L)
            val nextFp = withContext(Dispatchers.IO) { computeWorkspaceFingerprint(workspaceDir) }
            if (nextFp != lastFp) {
                lastFp = nextFp
                refreshTick++
            }
        }
    }

    val expandedSet = remember(expandedDirs) { expandedDirs.toSet() }

    data class TeamFileNode(
        val file: File,
        val relPath: String,
        val name: String,
        val isDirectory: Boolean,
        val depth: Int,
        val childCount: Int,
    )

    val visibleNodes = remember(workspaceDir, refreshTick, expandedDirs) {
        val ignored = setOf(".git", "node_modules", "build", ".gradle")
        val result = mutableListOf<TeamFileNode>()
        fun collectChildren(dir: File, depth: Int) {
            if (depth > 8 || result.size >= 400) return
            val children = dir.listFiles()
                ?.filter { it.name !in ignored }
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                .orEmpty()
            for (child in children) {
                val rel = child.relativeTo(workspaceDir).path
                val isDir = child.isDirectory
                val count = if (isDir) child.listFiles()?.count { it.name !in ignored } ?: 0 else 0
                result += TeamFileNode(
                    file = child,
                    relPath = rel,
                    name = child.name,
                    isDirectory = isDir,
                    depth = depth,
                    childCount = count,
                )
                if (isDir && rel in expandedSet) {
                    collectChildren(child, depth + 1)
                }
            }
        }
        collectChildren(workspaceDir, 0)
        result.toList()
    }
    val totalVisibleCount = visibleNodes.size

    if (selectedFile != null) {
        val currentFile = selectedFile!!
        val isImageFile = currentFile.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        Scaffold(
            topBar = {
                TopAppBar(
                    modifier = Modifier.padding(top = 6.dp),
                    navigationIcon = {
                        IconButton(onClick = { selectedFile = null; savedBanner = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Text(currentFile.relativeTo(workspaceDir).path, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    actions = {
                        if (!isImageFile) {
                            TextButton(onClick = {
                                runCatching { currentFile.writeText(fileContent) }
                                savedBanner = true
                            }) {
                                Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                )
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
                if (isImageFile) {
                    val bmp = remember(currentFile.absolutePath, currentFile.lastModified()) {
                        runCatching { android.graphics.BitmapFactory.decodeFile(currentFile.absolutePath) }.getOrNull()
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (bmp != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = currentFile.name,
                                modifier = Modifier.fillMaxSize().padding(8.dp),
                            )
                        } else {
                            Text("Unable to decode image", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    if (savedBanner) {
                        Text("Saved", color = Color(0xFF16A34A), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    OutlinedTextField(
                        value = fileContent,
                        onValueChange = { fileContent = it; savedBanner = false },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        return
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
                        Text("$projectName · ${stringResource(R.string.tab_files)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("$totalVisibleCount items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (expandedDirs.isNotEmpty()) {
                        IconButton(onClick = { expandedDirs = emptyList() }) {
                            Icon(Icons.Default.UnfoldLess, contentDescription = "Collapse All")
                        }
                    }
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { pad ->
        if (visibleNodes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.team_files_empty), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                }
            }
        } else {
            LaunchedEffect(highlightedFilePath, visibleNodes) {
                val target = highlightedFilePath ?: return@LaunchedEffect
                val idx = visibleNodes.indexOfFirst { it.relPath == target }
                if (idx >= 0) {
                    filesListState.animateScrollToItem(idx)
                }
            }
            LazyColumn(
                state = filesListState,
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(visibleNodes, key = { it.relPath }) { node ->
                    val isExpanded = node.isDirectory && node.relPath in expandedSet
                    val isHighlighted = !node.isDirectory && node.relPath == highlightedFilePath
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            isHighlighted -> PocketOrange.copy(alpha = 0.22f)
                            isExpanded -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            else -> Color.Transparent
                        },
                        border = if (isHighlighted) BorderStroke(1.5.dp, PocketOrange) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (node.isDirectory) {
                                    expandedDirs = if (isExpanded) {
                                        expandedDirs.filterNot { it == node.relPath || it.startsWith("${node.relPath}/") }
                                    } else {
                                        expandedDirs + node.relPath
                                    }
                                } else {
                                    highlightedFilePath = node.relPath
                                    val isImg = node.file.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
                                    fileContent = if (isImg) "" else runCatching { node.file.readText().take(200_000) }.getOrDefault("")
                                    selectedFile = node.file
                                    savedBanner = false
                                }
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (node.depth * 18 + 6).dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (node.isDirectory) {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(2.dp))
                            } else {
                                Spacer(Modifier.width(6.dp))
                            }
                            FileTypeIcon(
                                fileName = node.name,
                                isDirectory = node.isDirectory,
                                isExpanded = isExpanded,
                                size = 17.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(
                                    text = node.name,
                                    fontSize = 13.sp,
                                    fontWeight = if (node.isDirectory) FontWeight.Bold else FontWeight.Medium,
                                    color = if (node.isDirectory) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (node.isDirectory) "${node.childCount} items" else "${node.file.length()} bytes",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!node.isDirectory) {
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamTerminalTab(
    projectName: String,
    projectSlug: String,
    output: String,
    isRunning: Boolean,
    onRunCommand: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var cmdInput by remember { mutableStateOf("") }
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
                        Text("$projectName · ${stringResource(R.string.tab_terminal)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("/workspace/$projectSlug", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0D1117),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text(
                        text = output.ifBlank { "$ /workspace/$projectSlug ready…" },
                        color = Color(0xFFE6EDF3),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
            if (isRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cmdInput,
                    onValueChange = { cmdInput = it },
                    placeholder = { Text("ls -la, npm run dev, python3 -m http.server 8080…", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                )
                IconButton(
                    onClick = {
                        if (cmdInput.isNotBlank() && !isRunning) {
                            onRunCommand(cmdInput.trim())
                            cmdInput = ""
                        }
                    },
                    enabled = cmdInput.isNotBlank() && !isRunning,
                    modifier = Modifier.background(PocketOrange, CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run", tint = Color.White)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamDualPreviewTab(
    projectName: String,
    workspaceDir: File,
    onRunInTerminal: (String) -> Unit,
    onBack: () -> Unit,
) {
    var previewSubTab by rememberSaveable { mutableIntStateOf(0) } // 0 = Web Preview, 1 = App Preview
    var webUrlInput by rememberSaveable { mutableStateOf("") }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var workspaceFingerprint by remember { mutableLongStateOf(0L) }
    var liveReloadEnabled by rememberSaveable { mutableStateOf(true) }
    var lastSyncedTime by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var selectedScreenPath by rememberSaveable { mutableStateOf<String?>(null) }

    // Real-time file watcher (600ms) for instant hot-reloading of BOTH Web Preview & App Preview as AI writes code
    LaunchedEffect(workspaceDir, liveReloadEnabled) {
        if (!liveReloadEnabled) return@LaunchedEffect
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        var lastFp = withContext(Dispatchers.IO) { computeWorkspaceFingerprint(workspaceDir) }
        workspaceFingerprint = lastFp
        while (true) {
            delay(600L)
            val nextFp = withContext(Dispatchers.IO) { computeWorkspaceFingerprint(workspaceDir) }
            if (nextFp != lastFp) {
                lastFp = nextFp
                workspaceFingerprint = nextFp
                lastSyncedTime = timeFmt.format(Date())
                reloadNonce++
                val url = webUrlInput.trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    webViewRef?.reload()
                }
            }
        }
    }

    val livePulse = rememberInfiniteTransition(label = "livePreviewPulse")
    val liveDotAlpha by livePulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveDotAlpha",
    )

    val indexHtmlFile = remember(workspaceDir, reloadNonce, workspaceFingerprint) {
        listOf(
            File(workspaceDir, "index.html"),
            File(workspaceDir, "public/index.html"),
            File(workspaceDir, "dist/index.html"),
            File(workspaceDir, "build/index.html"),
            File(workspaceDir, "web/index.html"),
            File(workspaceDir, "src/index.html"),
        ).firstOrNull { it.isFile }
            ?: workspaceDir.walkTopDown()
                .maxDepth(4)
                .onEnter { dir -> dir.name !in setOf(".git", "node_modules", ".gradle") }
                .firstOrNull { it.isFile && it.extension.equals("html", ignoreCase = true) }
    }

    val detectedAppScreens = remember(workspaceDir, reloadNonce) {
        workspaceDir.walkTopDown()
            .maxDepth(7)
            .filter {
                it.isFile &&
                    !it.path.contains("/.git/") &&
                    !it.path.contains("/build/") &&
                    (it.name.endsWith("Screen.kt") ||
                        it.name.endsWith("Activity.kt") ||
                        it.name.endsWith("App.kt") ||
                        (it.path.contains("/res/layout/") && it.name.endsWith(".xml")))
            }
            .map { it.relativeTo(workspaceDir).path }
            .take(40)
            .toList()
    }

    LaunchedEffect(detectedAppScreens) {
        if (detectedAppScreens.isNotEmpty() && (selectedScreenPath == null || selectedScreenPath !in detectedAppScreens)) {
            selectedScreenPath = detectedAppScreens.first()
        } else if (detectedAppScreens.isEmpty()) {
            selectedScreenPath = null
        }
    }

    val parsedWidgets = remember(workspaceDir, selectedScreenPath, reloadNonce) {
        val rel = selectedScreenPath ?: return@remember emptyList()
        val file = File(workspaceDir, rel)
        if (!file.isFile) emptyList() else parseAppScreenWidgets(file)
    }

    val builtApkFile = remember(workspaceDir, reloadNonce) {
        workspaceDir.walkTopDown()
            .maxDepth(6)
            .firstOrNull { it.isFile && it.name.endsWith(".apk") }
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
                title = { Text("$projectName · ${stringResource(R.string.tab_preview)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        reloadNonce++
                        lastSyncedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        val url = webUrlInput.trim()
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            webViewRef?.reload()
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Dual-Mode Switcher: Web Preview vs App Preview
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        0 to stringResource(R.string.team_preview_web_tab),
                        1 to stringResource(R.string.team_preview_app_tab),
                    ).forEach { (idx, label) ->
                        val selected = previewSubTab == idx
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = if (selected) PocketOrange.copy(alpha = 0.16f) else Color.Transparent,
                            modifier = Modifier.weight(1f).clickable { previewSubTab = idx },
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            // Real-Time Live Hot-Reload Status Bar
            Surface(
                color = if (liveReloadEnabled) Color(0xFF16A34A).copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    1.dp,
                    if (liveReloadEnabled) Color(0xFF16A34A).copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (liveReloadEnabled) Color(0xFF16A34A).copy(alpha = liveDotAlpha)
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            ),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (liveReloadEnabled) stringResource(R.string.team_preview_live_on) else stringResource(R.string.team_preview_live_paused),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (liveReloadEnabled) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (lastSyncedTime != null) {
                                stringResource(R.string.team_preview_last_synced, lastSyncedTime!!)
                            } else {
                                stringResource(R.string.team_preview_watching_files)
                            },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FilterChip(
                        selected = liveReloadEnabled,
                        onClick = { liveReloadEnabled = !liveReloadEnabled },
                        label = {
                            Text(
                                text = if (liveReloadEnabled) stringResource(R.string.team_preview_live_toggle_pause) else stringResource(R.string.team_preview_live_toggle_resume),
                                fontSize = 11.sp,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (liveReloadEnabled) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        modifier = Modifier.height(28.dp),
                    )
                }
            }

            if (previewSubTab == 0) {
                // WEB PREVIEW (Real-time auto-reloading WebView)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = webUrlInput,
                        onValueChange = { webUrlInput = it },
                        placeholder = { Text(if (indexHtmlFile != null) "Auto: ${indexHtmlFile.name} (or http://127.0.0.1:3000)" else "http://127.0.0.1:3000", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    FilledTonalIconButton(onClick = {
                        reloadNonce++
                        lastSyncedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                }
                if (webUrlInput.isBlank() && indexHtmlFile == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(24.dp)) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(44.dp), tint = PocketOrange)
                            Text(stringResource(R.string.team_preview_web_empty), textAlign = TextAlign.Center, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    val targetUrl = webUrlInput.trim()
                    val renderKey = "web|$reloadNonce|$workspaceFingerprint|$targetUrl|${indexHtmlFile?.absolutePath}|${indexHtmlFile?.lastModified()}"
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { ctx ->
                            createOrqenPreviewWebView(ctx).also { webViewRef = it }
                        },
                        update = { webView ->
                            webViewRef = webView
                            // Track renderKey on the WebView instance itself (tag) so switching Web -> App -> Web NEVER leaves a blank white screen!
                            if (webView.tag != renderKey) {
                                webView.tag = renderKey
                                loadOrqenWebPreview(webView, targetUrl, indexHtmlFile)
                            }
                        },
                    )
                }
            } else {
                // APP PREVIEW (Real-time Interactive Phone-Frame Preview: Live WebApp Mobile View OR Android Native UI + APK)
                val targetUrl = webUrlInput.trim()
                val hasWebPreviewSource = targetUrl.startsWith("http://") || targetUrl.startsWith("https://") || indexHtmlFile != null
                val hasNativeScreens = detectedAppScreens.isNotEmpty()
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (hasWebPreviewSource && !hasNativeScreens) {
                        item {
                            // Real Interactive Mobile Phone-Frame Live Preview (shows real fonts, real CSS/JS, live 600ms hot-reload!)
                            val phoneRenderKey = "phone|$reloadNonce|$workspaceFingerprint|$targetUrl|${indexHtmlFile?.absolutePath}|${indexHtmlFile?.lastModified()}"
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Surface(
                                    shape = RoundedCornerShape(30.dp),
                                    color = Color(0xFF070A0D),
                                    border = BorderStroke(2.5.dp, PocketOrange.copy(alpha = 0.65f)),
                                    shadowElevation = 8.dp,
                                    modifier = Modifier
                                        .widthIn(max = 360.dp)
                                        .fillMaxWidth()
                                        .height(580.dp),
                                ) {
                                    Column(Modifier.fillMaxSize()) {
                                        // Realistic Phone Status Bar & Notch
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFF0B1016))
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFEAF4F4),
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .width(64.dp)
                                                    .height(10.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF161F29)),
                                            )
                                            Text(
                                                text = "LIVE",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = PocketOrange,
                                            )
                                        }
                                        AndroidView(
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                            factory = { ctx -> createOrqenPreviewWebView(ctx) },
                                            update = { webView ->
                                                if (webView.tag != phoneRenderKey) {
                                                    webView.tag = phoneRenderKey
                                                    loadOrqenWebPreview(webView, targetUrl, indexHtmlFile)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        // Phone-Frame Live App Preview Card
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Phone Header & Build Status
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = PocketOrange)
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.team_preview_app_title), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(
                                            text = if (builtApkFile != null) "APK Ready: ${builtApkFile.name}" else stringResource(R.string.team_preview_app_screens_count, detectedAppScreens.size),
                                            fontSize = 11.sp,
                                            color = if (builtApkFile != null) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                // Live Phone-Frame Wireframe Surface (auto-updates when AI edits Kotlin/XML UI)
                                if (selectedScreenPath != null) {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            // Simulated Phone Top Notch + Screen Title
                                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                                Box(
                                                    Modifier
                                                        .width(64.dp)
                                                        .height(6.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
                                                )
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(
                                                    text = stringResource(R.string.team_preview_app_live_wireframe),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = PocketOrange,
                                                )
                                                Text(
                                                    text = "· ${selectedScreenPath!!.substringAfterLast('/')}",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                            if (parsedWidgets.isEmpty()) {
                                                Text(
                                                    text = stringResource(R.string.team_preview_app_no_elements),
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                                )
                                            } else {
                                                parsedWidgets.take(14).forEach { widget ->
                                                    when (widget.kind) {
                                                        ParsedWidgetKind.TOP_BAR -> {
                                                            Surface(
                                                                shape = RoundedCornerShape(8.dp),
                                                                color = PocketOrange.copy(alpha = 0.14f),
                                                                modifier = Modifier.fillMaxWidth(),
                                                            ) {
                                                                Text(
                                                                    text = widget.label,
                                                                    fontSize = 13.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = MaterialTheme.colorScheme.onSurface,
                                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                                )
                                                            }
                                                        }
                                                        ParsedWidgetKind.INPUT -> {
                                                            Surface(
                                                                shape = RoundedCornerShape(8.dp),
                                                                color = MaterialTheme.colorScheme.surface,
                                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                                                modifier = Modifier.fillMaxWidth(),
                                                            ) {
                                                                Text(
                                                                    text = widget.label,
                                                                    fontSize = 11.sp,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                                )
                                                            }
                                                        }
                                                        ParsedWidgetKind.BUTTON -> {
                                                            Surface(
                                                                shape = RoundedCornerShape(8.dp),
                                                                color = PocketOrange,
                                                                modifier = Modifier.fillMaxWidth(),
                                                            ) {
                                                                Text(
                                                                    text = widget.label,
                                                                    fontSize = 12.sp,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    color = Color.White,
                                                                    textAlign = TextAlign.Center,
                                                                    modifier = Modifier.padding(vertical = 7.dp),
                                                                )
                                                            }
                                                        }
                                                        ParsedWidgetKind.CARD -> {
                                                            Surface(
                                                                shape = RoundedCornerShape(10.dp),
                                                                color = MaterialTheme.colorScheme.surface,
                                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                                                modifier = Modifier.fillMaxWidth(),
                                                            ) {
                                                                Text(
                                                                    text = widget.label,
                                                                    fontSize = 12.sp,
                                                                    modifier = Modifier.padding(10.dp),
                                                                )
                                                            }
                                                        }
                                                        ParsedWidgetKind.TEXT -> {
                                                            Text(
                                                                text = widget.label,
                                                                fontSize = 12.sp,
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.padding(horizontal = 4.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Quick Screen Switcher Chips
                                if (detectedAppScreens.size > 1) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        detectedAppScreens.take(10).forEach { path ->
                                            val name = path.substringAfterLast('/')
                                            FilterChip(
                                                selected = selectedScreenPath == path,
                                                onClick = { selectedScreenPath = path },
                                                label = { Text(name, fontSize = 11.sp) },
                                            )
                                        }
                                    }
                                }

                                Button(
                                    onClick = { onRunInTerminal("gradle assembleDebug --no-daemon") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.team_preview_build_apk_btn), fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    if (detectedAppScreens.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.team_preview_app_empty),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                            )
                        }
                    } else {
                        items(detectedAppScreens) { screenPath ->
                            val isSelected = selectedScreenPath == screenPath
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) PocketOrange.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = if (isSelected) BorderStroke(1.dp, PocketOrange.copy(alpha = 0.5f)) else null,
                                modifier = Modifier.fillMaxWidth().clickable { selectedScreenPath = screenPath },
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Smartphone, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = screenPath,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) PocketOrange else MaterialTheme.colorScheme.onSurface,
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

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private fun createOrqenPreviewWebView(ctx: android.content.Context): WebView {
    return WebView(ctx).apply {
        // Use standard browser WHITE canvas so pages without explicit CSS background remain 100% readable!
        setBackgroundColor(android.graphics.Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webChromeClient = android.webkit.WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }
    }
}

private fun loadOrqenWebPreview(
    webView: WebView,
    targetUrl: String,
    indexHtmlFile: File?,
) {
    webView.clearCache(true)
    if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) {
        webView.loadUrl(targetUrl)
    } else if (indexHtmlFile != null && indexHtmlFile.isFile) {
        val baseDirUrl = "file://${indexHtmlFile.parentFile?.absolutePath ?: ""}/"
        val rawHtml = runCatching { indexHtmlFile.readText() }.getOrDefault("")
        val htmlWithBase = if (rawHtml.contains("<base ", ignoreCase = true)) {
            rawHtml
        } else if (rawHtml.contains("<head>", ignoreCase = true)) {
            rawHtml.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head><base href=\"$baseDirUrl\">")
        } else {
            "<base href=\"$baseDirUrl\">$rawHtml"
        }
        webView.loadDataWithBaseURL(
            baseDirUrl,
            htmlWithBase,
            "text/html",
            "UTF-8",
            null,
        )
    }
}

private fun computeWorkspaceFingerprint(workspaceDir: File): Long {
    if (!workspaceDir.exists()) return 0L
    var hash = 1125899906842597L
    var count = 0
    val seq = workspaceDir.walkTopDown()
        .maxDepth(7)
        .onEnter { dir ->
            val name = dir.name
            name != ".git" && name != "node_modules" && name != "build" && name != ".gradle"
        }
    for (file in seq) {
        if (!file.isFile) continue
        hash = 31L * hash + file.relativeTo(workspaceDir).path.hashCode()
        hash = 31L * hash + file.length()
        hash = 31L * hash + file.lastModified()
        count++
        if (count >= 400) break
    }
    return hash xor count.toLong()
}

private enum class ParsedWidgetKind { TOP_BAR, TEXT, BUTTON, INPUT, CARD }

private data class ParsedUiWidget(
    val kind: ParsedWidgetKind,
    val label: String,
)

private fun parseAppScreenWidgets(file: File): List<ParsedUiWidget> {
    val content = runCatching { file.readText().take(80_000) }.getOrDefault("")
    if (content.isBlank()) return emptyList()
    val result = mutableListOf<ParsedUiWidget>()

    if (file.name.endsWith(".xml", ignoreCase = true)) {
        val tagRegex = Regex("<([A-Za-z0-9_.]+)[^>]*android:text=\"([^\"]+)\"")
        tagRegex.findAll(content).forEach { m ->
            val tag = m.groupValues[1].substringAfterLast('.')
            val text = m.groupValues[2]
            val kind = when {
                tag.contains("Button", ignoreCase = true) -> ParsedWidgetKind.BUTTON
                tag.contains("EditText", ignoreCase = true) -> ParsedWidgetKind.INPUT
                tag.contains("Toolbar", ignoreCase = true) -> ParsedWidgetKind.TOP_BAR
                else -> ParsedWidgetKind.TEXT
            }
            result += ParsedUiWidget(kind, text)
        }
        return result
    }

    // Jetpack Compose / Kotlin UI quick live parser
    val lines = content.lines()
    val quotedRegex = Regex("\"([^\"]{1,80})\"")
    var pendingKind: ParsedWidgetKind? = null
    for (rawLine in lines) {
        val line = rawLine.trim()
        if (line.startsWith("//") || line.startsWith("import ") || line.startsWith("package ")) continue

        when {
            line.contains("TopAppBar(") -> pendingKind = ParsedWidgetKind.TOP_BAR
            line.contains("OutlinedTextField(") || line.contains("TextField(") -> pendingKind = ParsedWidgetKind.INPUT
            line.contains("Button(") || line.contains("FilledTonalButton(") || line.contains("OutlinedButton(") -> pendingKind = ParsedWidgetKind.BUTTON
            line.contains("Card(") || line.contains("ElevatedCard(") -> pendingKind = ParsedWidgetKind.CARD
        }

        val quoteMatch = quotedRegex.find(line)
        if (quoteMatch != null) {
            val text = quoteMatch.groupValues[1].trim()
            if (text.isNotEmpty() && !text.startsWith("http") && !text.contains("contentDescription")) {
                val kind = when {
                    line.contains("Text(") && pendingKind != null -> {
                        val k = pendingKind
                        pendingKind = null
                        k
                    }
                    line.contains("Text(") -> ParsedWidgetKind.TEXT
                    pendingKind == ParsedWidgetKind.INPUT -> {
                        pendingKind = null
                        ParsedWidgetKind.INPUT
                    }
                    pendingKind == ParsedWidgetKind.TOP_BAR -> {
                        pendingKind = null
                        ParsedWidgetKind.TOP_BAR
                    }
                    pendingKind == ParsedWidgetKind.BUTTON -> {
                        pendingKind = null
                        ParsedWidgetKind.BUTTON
                    }
                    else -> null
                }
                if (kind != null) {
                    result += ParsedUiWidget(kind, text)
                }
            }
        }
        if (result.size >= 20) break
    }
    return result
}
