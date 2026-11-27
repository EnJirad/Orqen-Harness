package com.jarves.mh.multiagent.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.jarves.mh.multiagent.model.ActivityType
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.multiagent.chat.GroupChatMessage
import com.jarves.mh.multiagent.chat.GroupMessageType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.ui.theme.OrqenMint
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay

enum class TeamBannerHealth {
    RUNNING,
    WARNING,
    DISCONNECTED,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CentralGroupChatScreen(
    teamName: String,
    messages: List<GroupChatMessage>,
    agents: List<AgentState> = emptyList(),
    agentLiveLogs: Map<AgentSlot, List<String>> = emptyMap(),
    isTeamRunning: Boolean = false,
    teamBannerHealth: TeamBannerHealth = TeamBannerHealth.RUNNING,
    teamBannerMessage: String? = null,
    teamBannerDetail: String? = null,
    ownerName: String = "",
    typingAgentName: String? = null,
    typingAgentSlot: AgentSlot? = null,
    typingAgents: Map<AgentSlot, String> = emptyMap(),
    streamingAgentName: String? = null,
    streamingAgentSlot: AgentSlot? = null,
    streamingText: String = "",
    awaitingStartConfirmation: Boolean = false,
    pendingDirectivesCount: Int = 0,
    listState: LazyListState = rememberLazyListState(),
    initialScrollDone: Boolean = false,
    onInitialScrollDone: () -> Unit = {},
    onClearChat: (() -> Unit)? = null,
    hasGitRemote: Boolean = false,
    gitPushRunning: Boolean = false,
    onPushToGit: ((gitUrl: String?, apiKey: String?) -> Unit)? = null,
    onInstallApk: ((String) -> Unit)? = null,
    onOpenPreview: (() -> Unit)? = null,
    onOpenFile: ((String) -> Unit)? = null,
    draftConversationKey: String = "",
    initialDraftText: String = "",
    onDraftTextChanged: (String) -> Unit = {},
    onStartImplementation: (() -> Unit)? = null,
    onSendMessageWithImages: ((text: String, type: GroupMessageType, targetSlot: AgentSlot?, imageUris: List<Uri>) -> Unit)? = null,
    onSendMessage: (text: String, type: GroupMessageType, targetSlot: AgentSlot?) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var inputValue by remember(draftConversationKey) {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = initialDraftText,
                selection = androidx.compose.ui.text.TextRange(initialDraftText.length),
            ),
        )
    }
    var selectedType by remember { mutableStateOf(GroupMessageType.NORMAL) }
    var targetSlot by remember { mutableStateOf<AgentSlot?>(null) }
    var selectedImageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // Phase 9: Debounced autosave + immediate lifecycle flush (ON_PAUSE/ON_STOP + onDispose)!
    val latestInputText by rememberUpdatedState(inputValue.text)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(draftConversationKey, inputValue.text) {
        delay(220L)
        onDraftTextChanged(inputValue.text)
    }
    DisposableEffect(draftConversationKey, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE ||
                event == androidx.lifecycle.Lifecycle.Event.ON_STOP
            ) {
                onDraftTextChanged(latestInputText)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onDraftTextChanged(latestInputText)
        }
    }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showUploadSheet by remember { mutableStateOf(false) }
    var uploadTabIndex by remember { mutableIntStateOf(0) }
    var showGitPushDialog by remember { mutableStateOf(false) }
    var gitPushUrlInput by remember { mutableStateOf("") }
    var gitPushApiKeyInput by remember { mutableStateOf("") }
    var pendingUserSendScroll by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            selectedImageUris = (selectedImageUris + uris).distinct().take(3)
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            selectedImageUris = (selectedImageUris + uris).distinct().take(3)
        }
    }
    val genericFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            selectedImageUris = (selectedImageUris + uris).distinct().take(3)
        }
    }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val displayOwner = ownerName.trim().ifBlank { "You" }
    val participantSummary = remember(agents, displayOwner) {
        if (agents.isEmpty()) "$displayOwner · Agent 01 · Agent 02"
        else "$displayOwner · " + agents.joinToString(" · ") { it.config.name }
    }

    val effectiveTypingAgents = remember(typingAgents, typingAgentSlot, typingAgentName) {
        if (typingAgents.isNotEmpty()) {
            typingAgents
        } else if (typingAgentSlot != null && !typingAgentName.isNullOrBlank()) {
            mapOf(typingAgentSlot to typingAgentName)
        } else {
            emptyMap()
        }
    }
    val workingAgents = remember(agents, effectiveTypingAgents) {
        agents.filter { it.isWorking && !effectiveTypingAgents.containsKey(it.config.slot) }
    }
    val hasStreaming = !streamingAgentName.isNullOrBlank() && streamingText.isNotBlank()
    val visibleThinkingAgents = remember(effectiveTypingAgents, hasStreaming, streamingAgentSlot) {
        effectiveTypingAgents.entries.filter { (slot, _) ->
            !(hasStreaming && slot == streamingAgentSlot)
        }
    }
    val totalItems = messages.size + workingAgents.size + (if (hasStreaming) 1 else 0) + visibleThinkingAgents.size
    var prevTotalCount by remember { mutableIntStateOf(totalItems) }

    val isPinnedToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastIdx = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val count = info.totalItemsCount
            count == 0 || lastIdx >= (count - 2).coerceAtLeast(0) || !listState.canScrollForward
        }
    }

    // Smart Auto-Scroll & Push-Up:
    // - First visit to this project's chat: jump INSTANTLY to bottom
    // - New message or new streaming line arrives: push chat up smoothly ONLY if user is pinned near bottom or just sent a message
    // - User can freely scroll up at any time to read earlier messages without being yanked back!
    LaunchedEffect(totalItems) {
        if (totalItems == 0) {
            prevTotalCount = 0
            return@LaunchedEffect
        }
        if (!initialScrollDone) {
            listState.scrollToItem(totalItems - 1)
            onInitialScrollDone()
            prevTotalCount = totalItems
            return@LaunchedEffect
        }
        if (totalItems > prevTotalCount && (pendingUserSendScroll || isPinnedToBottom)) {
            listState.scrollToItem(totalItems - 1)
            pendingUserSendScroll = false
        }
        prevTotalCount = totalItems
    }

    // Push chat up smoothly as each new line of AI response appears!
    LaunchedEffect(streamingText.length) {
        if (hasStreaming && totalItems > 0 && (isPinnedToBottom || pendingUserSendScroll)) {
            listState.scrollToItem(totalItems - 1, scrollOffset = 10_000)
        }
    }

    // Push chat up whenever keyboard opens so messages stay visible above the input bar!
    var prevImeBottom by remember { mutableIntStateOf(0) }
    LaunchedEffect(imeBottom) {
        if (imeBottom > prevImeBottom && totalItems > 0) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (lastVisible >= (totalItems - 4).coerceAtLeast(0) || !listState.canScrollForward) {
                listState.scrollToItem(totalItems - 1, scrollOffset = 10_000)
            }
        }
        prevImeBottom = imeBottom
    }

    if (showClearConfirm && onClearChat != null) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.chat_clear_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.chat_clear_confirm_desc), fontSize = 13.sp) },
            confirmButton = {
                // Confirm button on the LEFT, Cancel button on the RIGHT
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            showClearConfirm = false
                            onClearChat()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.chat_clear_confirm_btn), color = Color.White)
                    }
                    OutlinedButton(
                        onClick = { showClearConfirm = false },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    if (showGitPushDialog && onPushToGit != null) {
        AlertDialog(
            onDismissRequest = { if (!gitPushRunning) showGitPushDialog = false },
            icon = { Icon(Icons.Default.CloudUpload, contentDescription = null, tint = OrqenMint) },
            title = { Text("เชื่อมต่อและ Push ขึ้น Git", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "โปรเจกต์นี้ยังไม่ได้เชื่อมต่อกับ Git Repository กรุณากรอก URL ของ Git และ API Key / Access Token เพื่อ Push โค้ดขึ้นทันที",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = gitPushUrlInput,
                        onValueChange = { gitPushUrlInput = it },
                        label = { Text("Git Repository URL") },
                        placeholder = { Text("https://github.com/user/repo.git", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = gitPushApiKeyInput,
                        onValueChange = { gitPushApiKeyInput = it },
                        label = { Text("API Key / Personal Access Token") },
                        placeholder = { Text("ghp_xxxxxxxxxxxx", fontSize = 12.sp) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            val cleanUrl = gitPushUrlInput.trim()
                            val cleanKey = gitPushApiKeyInput.trim().takeIf { it.isNotBlank() }
                            if (cleanUrl.isNotBlank()) {
                                showGitPushDialog = false
                                onPushToGit(cleanUrl, cleanKey)
                            }
                        },
                        enabled = gitPushUrlInput.isNotBlank() && !gitPushRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("บันทึก & Push")
                    }
                    OutlinedButton(
                        onClick = { showGitPushDialog = false },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(top = 6.dp),
                title = {
                    Column {
                        Text("$teamName · ${stringResource(R.string.team_group_chat)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(participantSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Trash button shifted to the left of the Git Push button
                    if (onClearChat != null && messages.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.chat_clear_all),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Git Push button on the far right!
                    if (onPushToGit != null) {
                        IconButton(
                            onClick = {
                                if (hasGitRemote) {
                                    onPushToGit(null, null)
                                } else {
                                    showGitPushDialog = true
                                }
                            },
                            enabled = !gitPushRunning,
                        ) {
                            if (gitPushRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = OrqenMint,
                                )
                            } else {
                                Icon(
                                    Icons.Default.CloudUpload,
                                    contentDescription = "Push to Git",
                                    tint = if (hasGitRemote) OrqenMint else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // Unified Expandable AI Team Status Bar (replaces floating pill! Tap down-arrow to inspect every agent & what they're doing)
            val showBanner = agents.isNotEmpty() || isTeamRunning || teamBannerHealth != TeamBannerHealth.RUNNING
            if (showBanner) {
                var bannerExpanded by remember { mutableStateOf(false) }
                val activeWorkingAgents = agents.filter { it.isWorking && it.config.slot != typingAgentSlot }
                val isAnyTyping = typingAgentSlot != null && agents.any { it.config.slot == typingAgentSlot }
                val pausedAgentsCount = agents.count { !it.config.isEnabled || it.activityState.activity == ActivityType.PAUSED }
                val errorAgentsCount = agents.count { it.activityState.activity == ActivityType.ERROR }
                val hasIssueDetail = teamBannerHealth != TeamBannerHealth.RUNNING && !teamBannerDetail.isNullOrBlank()
                val (bannerBg, bannerFg, bannerText) = when {
                    teamBannerHealth == TeamBannerHealth.DISCONNECTED -> Triple(
                        OrqenSemanticColors.Error.copy(alpha = 0.14f),
                        OrqenSemanticColors.Error,
                        teamBannerMessage?.takeIf { it.isNotBlank() } ?: stringResource(R.string.team_status_disconnected_agent),
                    )
                    teamBannerHealth == TeamBannerHealth.WARNING -> Triple(
                        OrqenSemanticColors.Warning.copy(alpha = 0.16f),
                        OrqenSemanticColors.Warning,
                        teamBannerMessage?.takeIf { it.isNotBlank() } ?: stringResource(R.string.team_status_warning_network),
                    )
                    isAnyTyping && activeWorkingAgents.isEmpty() -> Triple(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        MaterialTheme.colorScheme.primary,
                        "${typingAgentName ?: "AI"} กำลังคิด...",
                    )
                    activeWorkingAgents.isNotEmpty() -> {
                        val lead = activeWorkingAgents.first()
                        val actLabel = stringResource(lead.activityState.activity.labelRes)
                        Triple(
                            OrqenSemanticColors.Success.copy(alpha = 0.14f),
                            OrqenSemanticColors.Success,
                            "กำลังทำงาน ${activeWorkingAgents.size}/${agents.size} · ${lead.config.name}: $actLabel" +
                                if (pendingDirectivesCount > 0) " (+$pendingDirectivesCount)" else "",
                        )
                    }
                    awaitingStartConfirmation -> Triple(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        MaterialTheme.colorScheme.primary,
                        "SPV รอยืนยันการทำงานต่อจากเจ้าของบริษัท",
                    )
                    errorAgentsCount > 0 -> Triple(
                        OrqenSemanticColors.Error.copy(alpha = 0.14f),
                        OrqenSemanticColors.Error,
                        "พบข้อผิดพลาด ($errorAgentsCount ตัว) · แตะเพื่อดูรายละเอียด",
                    )
                    pausedAgentsCount > 0 && !isTeamRunning -> Triple(
                        OrqenSemanticColors.Warning.copy(alpha = 0.14f),
                        OrqenSemanticColors.Warning,
                        "พักงาน (${pausedAgentsCount}/${agents.size} ตัว)",
                    )
                    isTeamRunning -> Triple(
                        OrqenSemanticColors.Info.copy(alpha = 0.14f),
                        OrqenSemanticColors.Info,
                        stringResource(R.string.team_status_running_short) +
                            if (pendingDirectivesCount > 0) " ($pendingDirectivesCount)" else "",
                    )
                    else -> Triple(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        "ทีมพร้อมรับคำสั่ง (${agents.count { it.config.isEnabled }}/${agents.size} AI)",
                    )
                }
                Surface(
                    color = bannerBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { bannerExpanded = !bannerExpanded },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            when {
                                isAnyTyping && activeWorkingAgents.isEmpty() -> {
                                    ThinkingPulseSpark(color = bannerFg, size = 14.dp)
                                }
                                activeWorkingAgents.isNotEmpty() -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.8.dp,
                                        color = bannerFg,
                                    )
                                }
                                else -> {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(bannerFg))
                                }
                            }
                            Text(
                                text = bannerText,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = bannerFg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (bannerExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle AI Status",
                                tint = bannerFg,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        AnimatedVisibility(visible = bannerExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                HorizontalDivider(color = bannerFg.copy(alpha = 0.22f))
                                agents.forEach { agent ->
                                    val slot = agent.config.slot
                                    val accent = agentAccentColor(slot)
                                    val isTypingThis = effectiveTypingAgents.containsKey(slot)
                                    val isWorkingThis = agent.isWorking && !isTypingThis
                                    val act = agent.activityState.activity
                                    val statusLabel = com.jarves.mh.skills.PlayfulAgentStatus.format(
                                        activity = act,
                                        isWorking = isWorkingThis,
                                        isTyping = isTypingThis,
                                        isEnabled = agent.config.isEnabled,
                                        slot = slot,
                                    )
                                    val currentTaskDesc = agent.activityState.currentTask.ifBlank { agent.activityState.lastAction }
                                        .takeIf { it.isNotBlank() && !it.equals("Idle", ignoreCase = true) }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        when {
                                            isTypingThis -> ThinkingPulseSpark(color = accent, size = 13.dp)
                                            isWorkingThis -> CircularProgressIndicator(
                                                modifier = Modifier.size(11.dp),
                                                strokeWidth = 1.6.dp,
                                                color = accent,
                                            )
                                            else -> Icon(
                                                imageVector = AgentIconCatalog.iconFor(agent.config),
                                                contentDescription = null,
                                                tint = if (!agent.config.isEnabled || act == ActivityType.PAUSED) Color(0xFFF59E0B) else accent,
                                                modifier = Modifier.size(13.dp),
                                            )
                                        }
                                        Text(
                                            text = agent.config.name,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = accent,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "· $statusLabel" + if (currentTaskDesc != null) " — $currentTaskDesc" else "",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                                if (hasIssueDetail) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        SelectionContainer {
                                            Text(
                                                text = teamBannerDetail.orEmpty(),
                                                fontSize = 10.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(8.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Chat message list + inline live working bubbles right inside the chat stream
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty() && workingAgents.isEmpty() && !hasStreaming && typingAgentName.isNullOrBlank()) {
                    item(key = "chat-welcome-guide") {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "พร้อมช่วยคุณแล้ว · $teamName",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "• โหมด คุยปรึกษา (Chat): ถาม-ตอบ วางแผน และตรวจวิเคราะห์โค้ด (ยังไม่แก้ไฟล์)\n• โหมด สั่งงาน (Task): ทีม AI ลงมือเขียนโค้ด ทดสอบ และบิ้ว APK ให้ครบจบ\n• พิมพ์ @ชื่อเอเจนต์ เพื่อคุยข้ามสายงานหรือโอนหน้าที่ได้ทันที",
                                    fontSize = 12.5.sp,
                                    lineHeight = 19.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    AssistChip(
                                        onClick = {
                                            pendingUserSendScroll = true
                                            onSendMessage("ช่วยวิเคราะห์โปรเจกต์และวางแผนการทำงานให้หน่อยครับ", GroupMessageType.NORMAL, null)
                                        },
                                        label = { Text("วางแผนโปรเจกต์", fontSize = 11.5.sp) },
                                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(15.dp)) },
                                    )
                                    AssistChip(
                                        onClick = {
                                            pendingUserSendScroll = true
                                            onSendMessage("ช่วยตรวจสอบโครงสร้างไฟล์ที่มีอยู่ตอนนี้ให้หน่อยครับ", GroupMessageType.NORMAL, null)
                                        },
                                        label = { Text("ตรวจไฟล์ในโปรเจกต์", fontSize = 11.5.sp) },
                                        leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(15.dp)) },
                                    )
                                }
                            }
                        }
                    }
                }
                items(messages, key = { it.id }) { msg ->
                    val senderAgent = msg.senderSlot?.let { slot -> agents.firstOrNull { it.config.slot == slot } }
                    GroupChatBubble(
                        message = msg,
                        agents = agents,
                        senderIcon = senderAgent?.let { AgentIconCatalog.iconFor(it.config) },
                        onOpenFile = onOpenFile,
                        onInstallApk = onInstallApk,
                        onOpenPreview = onOpenPreview,
                        onSendAsTask = { taskPrompt ->
                            pendingUserSendScroll = true
                            onSendMessage(taskPrompt, GroupMessageType.TASK_ASSIGNMENT, null)
                        },
                    )
                }
                items(workingAgents, key = { "live-${it.config.slot.id}" }) { agent ->
                    LiveAgentWorkingBubble(
                        agent = agent,
                        logs = agentLiveLogs[agent.config.slot].orEmpty(),
                    )
                }
                if (hasStreaming) {
                    item(key = "streaming-live-bubble") {
                        val streamAgent = streamingAgentSlot?.let { slot -> agents.firstOrNull { it.config.slot == slot } }
                        AiStreamingBubble(
                            agentName = streamingAgentName!!,
                            agentSlot = streamingAgentSlot,
                            agentIcon = streamAgent?.let { AgentIconCatalog.iconFor(it.config) },
                            text = streamingText,
                        )
                    }
                }
                items(visibleThinkingAgents, key = { "typing-${it.key.id}" }) { (thinkSlot, thinkName) ->
                    val thinkAgent = agents.firstOrNull { it.config.slot == thinkSlot }
                    AiTypingWaveBubble(
                        agentName = thinkName,
                        agentSlot = thinkSlot,
                        agentIcon = thinkAgent?.let { AgentIconCatalog.iconFor(it.config) },
                    )
                }
            }

            // Quick Mention Bar (Shows ONLY actual agents in this team with their custom names & distinct colors!)
            if (agents.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(agents, key = { it.config.slot.id }) { agent ->
                        val slot = agent.config.slot
                        val agentName = agent.config.name
                        val isSelected = targetSlot == slot
                        val slotColor = agentAccentColor(slot)
                        SuggestionChip(
                            onClick = {
                                targetSlot = if (isSelected) null else slot
                                val mentionPrefix = "@$agentName "
                                val curText = inputValue.text
                                if (!curText.contains("@$agentName")) {
                                    val newText = if (curText.isBlank()) mentionPrefix else "$mentionPrefix$curText"
                                    // Place cursor '|' immediately AFTER "@AgentName " so user types naturally!
                                    inputValue = androidx.compose.ui.text.input.TextFieldValue(
                                        text = newText,
                                        selection = androidx.compose.ui.text.TextRange(mentionPrefix.length),
                                    )
                                } else {
                                    val idx = curText.indexOf("@$agentName") + agentName.length + 1
                                    val cursorPos = if (idx < curText.length && curText[idx] == ' ') idx + 1 else idx
                                    inputValue = inputValue.copy(selection = androidx.compose.ui.text.TextRange(cursorPos.coerceAtMost(curText.length)))
                                }
                            },
                            icon = {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(slotColor),
                                )
                            },
                            label = {
                                Text(
                                    text = "@$agentName",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSelected) slotColor else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            border = BorderStroke(
                                width = if (isSelected) 1.4.dp else 1.dp,
                                color = slotColor.copy(alpha = if (isSelected) 0.85f else 0.4f),
                            ),
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = if (isSelected) slotColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            ),
                        )
                    }
                }
            }

            // Input Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    // Mode helper text placed ABOVE mode buttons so it never gets in the way of the text input box!
                    Text(
                        text = when (selectedType) {
                            GroupMessageType.NORMAL -> "โหมดคุยปรึกษา — ตอบคำถาม/วิเคราะห์โค้ดอย่างเดียว ยังไม่แก้ไฟล์"
                            GroupMessageType.TASK_ASSIGNMENT -> "โหมดสั่งทำงาน — ทีม AI จะวางแผน แก้โค้ด ทดสอบ และบิ้ว APK ให้ทันที"
                            GroupMessageType.BUG_REPORT -> "โหมดแจ้งบั๊ก — ทีม AI จะไล่หาสาเหตุและแก้บั๊กพร้อมทดสอบซ้ำ"
                            GroupMessageType.REVIEW_REQUEST -> "โหมดรีวิว — ทีม AI จะตรวจสอบคุณภาพและความปลอดภัยของโค้ด"
                            else -> ""
                        },
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                    // Clean, Beginner-Friendly Mode Selector: Chat (Consult) vs Task (Build) + More menu
                    var moreModeMenuOpen by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TypeSelectorChip("คุยปรึกษา", GroupMessageType.NORMAL, selectedType) { selectedType = it }
                        TypeSelectorChip("สั่งทำงาน", GroupMessageType.TASK_ASSIGNMENT, selectedType) { selectedType = it }
                        Box {
                            val extraSelected = selectedType == GroupMessageType.BUG_REPORT || selectedType == GroupMessageType.REVIEW_REQUEST
                            val extraLabel = when (selectedType) {
                                GroupMessageType.BUG_REPORT -> "แจ้งบั๊ก"
                                GroupMessageType.REVIEW_REQUEST -> "รีวิว"
                                else -> "อื่นๆ ▾"
                            }
                            Surface(
                                onClick = { moreModeMenuOpen = true },
                                shape = RoundedCornerShape(10.dp),
                                color = if (extraSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                border = if (extraSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null,
                            ) {
                                Text(
                                    text = extraLabel,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (extraSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (extraSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = moreModeMenuOpen,
                                onDismissRequest = { moreModeMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("แจ้งบั๊ก (Bug Report)") },
                                    leadingIcon = { Icon(Icons.Default.BugReport, null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        selectedType = GroupMessageType.BUG_REPORT
                                        moreModeMenuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("ขอรีวิวโค้ด (Review)") },
                                    leadingIcon = { Icon(Icons.Default.RateReview, null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        selectedType = GroupMessageType.REVIEW_REQUEST
                                        moreModeMenuOpen = false
                                    },
                                )
                            }
                        }
                        if (onStartImplementation != null && !isTeamRunning && messages.isNotEmpty() && selectedType == GroupMessageType.NORMAL) {
                            Spacer(Modifier.weight(1f))
                            FilledTonalButton(
                                onClick = onStartImplementation,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("เริ่มสร้างเลย", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Attached image thumbnails preview before sending
                    if (selectedImageUris.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(selectedImageUris) { uri ->
                                val thumb = remember(uri) {
                                    runCatching {
                                        context.contentResolver.openInputStream(uri)?.use { stream ->
                                            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                            BitmapFactory.decodeStream(stream, null, opts)?.asImageBitmap()
                                        }
                                    }.getOrNull()
                                }
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(1.dp, OrqenMint.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                ) {
                                    if (thumb != null) {
                                        Image(
                                            bitmap = thumb,
                                            contentDescription = "Attached image",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Icon(Icons.Default.Image, null, tint = OrqenMint, modifier = Modifier.align(Alignment.Center))
                                    }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(2.dp)
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.7f))
                                            .clickable { selectedImageUris = selectedImageUris.filterNot { it == uri } },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }

                    // 2-Tab Upload Picker Dialog (Tab 0: Photos & Videos | Tab 1: Files)
                    if (showUploadSheet) {
                        AlertDialog(
                            onDismissRequest = { showUploadSheet = false },
                            icon = { Icon(Icons.Default.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            title = { Text("อัปโหลดให้ทีม AI ดู", fontWeight = FontWeight.SemiBold) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    TabRow(selectedTabIndex = uploadTabIndex) {
                                        Tab(
                                            selected = uploadTabIndex == 0,
                                            onClick = { uploadTabIndex = 0 },
                                            text = { Text("รูปและวิดีโอ", fontSize = 12.5.sp) },
                                            icon = { Icon(Icons.Default.PermMedia, null, modifier = Modifier.size(18.dp)) },
                                        )
                                        Tab(
                                            selected = uploadTabIndex == 1,
                                            onClick = { uploadTabIndex = 1 },
                                            text = { Text("ไฟล์เอกสาร/โค้ด", fontSize = 12.5.sp) },
                                            icon = { Icon(Icons.Default.InsertDriveFile, null, modifier = Modifier.size(18.dp)) },
                                        )
                                    }
                                    if (uploadTabIndex == 0) {
                                        Text(
                                            text = "เลือกรูปภาพหน้าจอหรือคลิปวิดีโอเพื่อให้ทีม AI วิเคราะห์และออกแบบตามได้ทันที",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            Button(
                                                onClick = {
                                                    showUploadSheet = false
                                                    imagePicker.launch("image/*")
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(10.dp),
                                            ) {
                                                Icon(Icons.Default.Image, null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("เลือกรูปภาพ", fontSize = 12.sp)
                                            }
                                            FilledTonalButton(
                                                onClick = {
                                                    showUploadSheet = false
                                                    videoPicker.launch("video/*")
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(10.dp),
                                            ) {
                                                Icon(Icons.Default.Videocam, null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("เลือกวิดีโอ", fontSize = 12.sp)
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = "เลือกไฟล์โค้ด, JSON, PDF, TXT หรือเอกสารจากเครื่องเพื่อให้ AI อ่าน",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Button(
                                            onClick = {
                                                showUploadSheet = false
                                                genericFilePicker.launch("*/*")
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp),
                                        ) {
                                            Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("เลือกไฟล์จากเครื่อง", fontSize = 12.5.sp)
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showUploadSheet = false }) { Text("ปิด") }
                            },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { showUploadSheet = true },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = "Upload Media or Files",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        val mentionVisualTransformation = remember(agents) {
                            androidx.compose.ui.text.input.VisualTransformation { text ->
                                val highlighted = buildAnnotatedString {
                                    append(text.text)
                                    val rawStr = text.text
                                    val lowerStr = rawStr.lowercase()
                                    val sortedAgents = agents.sortedByDescending { it.config.name.length }
                                    sortedAgents.forEach { ag ->
                                        val color = agentAccentColor(ag.config.slot)
                                        val tokens = listOf("@${ag.config.name}", "@${ag.config.slot.defaultName}", "@${ag.config.slot.id}")
                                            .distinct()
                                        tokens.forEach { tok ->
                                            val lowerTok = tok.lowercase()
                                            var idx = lowerStr.indexOf(lowerTok)
                                            while (idx >= 0) {
                                                addStyle(
                                                    SpanStyle(
                                                        color = color,
                                                        fontWeight = FontWeight.Bold,
                                                        background = color.copy(alpha = 0.14f),
                                                    ),
                                                    idx,
                                                    idx + tok.length,
                                                )
                                                idx = lowerStr.indexOf(lowerTok, startIndex = idx + tok.length)
                                            }
                                        }
                                    }
                                }
                                androidx.compose.ui.text.input.TransformedText(
                                    highlighted,
                                    androidx.compose.ui.text.input.OffsetMapping.Identity,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                        ) {
                            if (inputValue.text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.chat_send_to_team),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                )
                            }
                            BasicTextField(
                                value = inputValue,
                                onValueChange = { inputValue = it },
                                visualTransformation = mentionVisualTransformation,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        val canSend = inputValue.text.isNotBlank() || selectedImageUris.isNotEmpty()
                        IconButton(
                            onClick = {
                                if (canSend) {
                                    pendingUserSendScroll = true
                                    val finalPrompt = inputValue.text.ifBlank { "ช่วยวิเคราะห์รูปภาพ/ไฟล์ที่แนบมาและออกแบบ/สร้างตามนี้" }
                                    val sendOk = runCatching {
                                        if (selectedImageUris.isNotEmpty() && onSendMessageWithImages != null) {
                                            onSendMessageWithImages(finalPrompt, selectedType, targetSlot, selectedImageUris)
                                        } else {
                                            onSendMessage(finalPrompt, selectedType, targetSlot)
                                        }
                                        true
                                    }.getOrDefault(false)
                                    if (sendOk) {
                                        inputValue = androidx.compose.ui.text.input.TextFieldValue("")
                                        onDraftTextChanged("")
                                        selectedImageUris = emptyList()
                                        selectedType = GroupMessageType.NORMAL
                                        targetSlot = null
                                    }
                                }
                            },
                            enabled = canSend,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BouncingWaveDots(
    color: Color,
    dotSize: Dp = 6.dp,
    bounceHeight: Float = -5.5f,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bouncingWaveDots")
    val dotOffsets = listOf(0, 160, 320).map { delayMs ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = bounceHeight,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 900
                    0f at delayMs
                    bounceHeight at (delayMs + 200).coerceAtMost(850)
                    0f at (delayMs + 420).coerceAtMost(890)
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "dotOffset$delayMs",
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        dotOffsets.forEach { offsetState ->
            Box(
                modifier = Modifier
                    .offset(y = offsetState.value.dp)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * Distinct high-contrast color per AI agent slot so Owner can tell at a glance
 * which AI agent is speaking, typing, or working in Group Chat.
 */
internal fun agentAccentColor(slot: AgentSlot?): Color = when (slot) {
    AgentSlot.AGENT_1 -> Color(0xFF10B981) // Emerald Mint (Supervisor)
    AgentSlot.AGENT_2 -> Color(0xFF6B80A8) // Muted Slate Blue
    AgentSlot.AGENT_3 -> Color(0xFFA78BFA) // Violet Purple
    AgentSlot.AGENT_4 -> Color(0xFFF59E0B) // Amber Gold
    null -> OrqenMint
}

/**
 * Sleek pulsing sparkle indicator used while AI is thinking/preparing its reply
 * (replaces bouncing wave dots for a cleaner, calmer visual).
 */
@Composable
internal fun ThinkingPulseSpark(
    color: Color,
    size: Dp = 15.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinkingSpark")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sparkAlpha",
    )
    Icon(
        imageVector = Icons.Default.AutoAwesome,
        contentDescription = "Thinking",
        tint = color.copy(alpha = alpha),
        modifier = Modifier.size(size),
    )
}

@Composable
private fun AiStreamingBubble(
    agentName: String,
    agentSlot: AgentSlot? = null,
    agentIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    text: String,
) {
    val accent = agentAccentColor(agentSlot)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (agentIcon != null) {
                Icon(
                    imageVector = agentIcon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.ic_orqen_mark),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = agentName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            ThinkingPulseSpark(color = accent, size = 12.dp)
        }
        Spacer(Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .widthIn(max = 315.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .background(accent.copy(alpha = 0.10f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = text,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AiTypingWaveBubble(
    agentName: String,
    agentSlot: AgentSlot? = null,
    agentIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val accent = agentAccentColor(agentSlot)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier
                    .background(accent.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (agentIcon != null) {
                    Icon(
                        imageVector = agentIcon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(15.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_orqen_mark),
                        contentDescription = "ORQEN",
                        tint = accent,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Text(
                    text = agentName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                ThinkingPulseSpark(color = accent, size = 14.dp)
                Text(
                    text = "กำลังคิด...",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent.copy(alpha = 0.9f),
                )
            }
        }
        Text(
            text = com.jarves.mh.skills.TaskEtaEstimator.estimateChatReplyLabel(isBusyWithCode = false),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
}

@Composable
private fun LiveAgentWorkingBubble(
    agent: AgentState,
    logs: List<String>,
) {
    var expanded by remember(agent.config.slot) { mutableStateOf(false) }
    val act = agent.activityState.activity
    val actColor = ActivityVisuals.colorFor(act)
    val agentColor = agentAccentColor(agent.config.slot)
    val actIcon = ActivityVisuals.iconFor(act)
    val shortStatus = stringResource(act.labelRes)
        .removePrefix("กำลัง")
        .trim()
        .ifBlank { stringResource(act.labelRes) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = agent.config.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = agentColor,
        )

        Spacer(Modifier.height(3.dp))

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(
                Modifier
                    .background(agentColor.copy(alpha = 0.09f))
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                // Clean pill format: [Status Icon] วิเคราะห์ ••• | ▼
                Row(
                    modifier = Modifier.clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(
                        imageVector = actIcon,
                        contentDescription = null,
                        tint = actColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = shortStatus,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.8.dp,
                        color = actColor,
                    )
                    Text(
                        text = "|",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = actColor,
                        modifier = Modifier.size(18.dp),
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            if (logs.isEmpty()) {
                                Text(
                                    text = agent.activityState.lastAction.ifBlank { shortStatus },
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                logs.takeLast(10).forEach { logLine ->
                                    Text(
                                        text = "• $logLine",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        val etaLabel = com.jarves.mh.skills.TaskEtaEstimator.estimateWorkingAgentEta(
            activity = act,
            pendingTasksCount = 0,
            elapsedMs = if (agent.activityState.startedAtElapsedMs > 0L) {
                (android.os.SystemClock.elapsedRealtime() - agent.activityState.startedAtElapsedMs).coerceAtLeast(0L)
            } else 0L,
            taskTitle = agent.activityState.currentTask,
        )
        Text(
            text = etaLabel,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
}

@Composable
fun GroupChatBubble(
    message: GroupChatMessage,
    agents: List<AgentState> = emptyList(),
    senderIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onOpenFile: ((String) -> Unit)? = null,
    onInstallApk: ((String) -> Unit)? = null,
    onOpenPreview: (() -> Unit)? = null,
    onSendAsTask: ((String) -> Unit)? = null,
) {
    val isUser = message.isUser
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val agentColor = if (isUser) OrqenMint else agentAccentColor(message.senderSlot)
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val tintOverlay = if (isUser) Color.Transparent else agentColor.copy(alpha = 0.08f)
    val borderColor = if (isUser) OrqenMint.copy(alpha = 0.4f) else agentColor.copy(alpha = 0.72f)
    val clipboard = LocalClipboardManager.current
    var copied by remember(message.id) { mutableStateOf(false) }
    var detailsExpanded by remember(message.id) { mutableStateOf(false) }
    val mentionedFiles = remember(message.id, message.text, message.relatedFilePath, message.workDetails) {
        extractMentionedFiles(message)
    }
    val formattedText = remember(message.id, message.text, isUser, agentColor, agents) {
        buildOrqenFormattedMessage(
            raw = message.text,
            mintColor = agentColor,
            isUser = isUser,
            agents = agents,
        )
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500L)
            copied = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!isUser) {
                if (senderIcon != null) {
                    Icon(
                        imageVector = senderIcon,
                        contentDescription = null,
                        tint = agentColor,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_orqen_mark),
                        contentDescription = null,
                        tint = agentColor,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Text(
                text = message.senderName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = agentColor,
            )
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
            if (message.messageType != GroupMessageType.NORMAL) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = getMessageTypeColor(message.messageType).copy(alpha = 0.2f),
                ) {
                    Text(
                        text = message.messageType.name,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = getMessageTypeColor(message.messageType),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            // Copy button on EVERY chat message
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        val fullCopy = buildString {
                            append(message.text)
                            if (message.workDetails.isNotEmpty()) {
                                append("\n\n---\n")
                                append(message.workDetails.joinToString("\n"))
                            }
                        }
                        clipboard.setText(AnnotatedString(fullCopy))
                        copied = true
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.chat_copy_message),
                    tint = if (copied) OrqenMint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(12.dp),
                )
                if (copied) {
                    Text(
                        text = stringResource(R.string.chat_copied_badge),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = OrqenMint,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .widthIn(max = 315.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .background(tintOverlay)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // Inline collapsible work status & command dropdown (collapsed by default!)
            if (!isUser && (message.workDetails.isNotEmpty() || !message.workStatus.isNullOrBlank())) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, OrqenMint.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { detailsExpanded = !detailsExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_orqen_mark),
                                contentDescription = null,
                                tint = OrqenMint,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = message.workStatus?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.chat_work_details_expand, message.workDetails.size),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OrqenMint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (detailsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = OrqenMint.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        AnimatedVisibility(visible = detailsExpanded && message.workDetails.isNotEmpty()) {
                            Column(
                                modifier = Modifier.padding(top = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                HorizontalDivider(color = OrqenMint.copy(alpha = 0.2f))
                                Spacer(Modifier.height(2.dp))
                                message.workDetails.forEach { step ->
                                    SelectionContainer {
                                        Text(
                                            text = "• $step",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Attached image thumbnails inside the chat bubble
            if (message.imagePaths.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(message.imagePaths) { imgPath ->
                        val bmp = remember(imgPath) {
                            runCatching {
                                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                                BitmapFactory.decodeFile(imgPath, opts)?.asImageBitmap()
                            }.getOrNull()
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = "Uploaded reference image",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, OrqenMint.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                            )
                        }
                    }
                }
            }

            androidx.compose.foundation.text.ClickableText(
                text = formattedText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 20.sp,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                ),
                onClick = { offset ->
                    val fileAnn = formattedText.getStringAnnotations(tag = "FILE_LINK", start = offset, end = offset).firstOrNull()
                    if (fileAnn != null && onOpenFile != null) {
                        onOpenFile(fileAnn.item)
                    }
                },
            )

            // 1-tap "Send as Task" button when user sends a build/edit instruction in Chat mode
            if (!isUser && !message.suggestedTaskPrompt.isNullOrBlank() && onSendAsTask != null) {
                Button(
                    onClick = { onSendAsTask(message.suggestedTaskPrompt) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.chat_send_as_task_btn),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Direct Installable APK Card right inside Group Chat!
            val apkPath = message.apkFilePath?.takeIf { it.isNotBlank() }
            if (apkPath != null && onInstallApk != null) {
                val apkFile = remember(apkPath) { java.io.File(apkPath) }
                val apkSizeMb = remember(apkPath) {
                    val bytes = runCatching { apkFile.length() }.getOrDefault(0L)
                    if (bytes > 0L) String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0)) else "APK"
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF10B981).copy(alpha = 0.14f),
                    border = BorderStroke(1.2.dp, Color(0xFF10B981).copy(alpha = 0.65f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(11.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Android,
                                contentDescription = "APK",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(22.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = apkFile.name,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "$apkSizeMb · พร้อมติดตั้งลงเครื่องทันที",
                                    fontSize = 10.5.sp,
                                    color = Color(0xFF10B981),
                                )
                            }
                        }
                        Button(
                            onClick = { onInstallApk(apkPath) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text("ดาวน์โหลดและติดตั้ง APK", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }

            // Direct Web Preview Card right inside Group Chat when a Web project finishes!
            if (message.workDetails.any { it == "PREVIEW_READY" } && onOpenPreview != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = OrqenMint.copy(alpha = 0.14f),
                    border = BorderStroke(1.2.dp, OrqenMint.copy(alpha = 0.65f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Button(
                        onClick = onOpenPreview,
                        colors = ButtonDefaults.buttonColors(containerColor = OrqenMint),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                        Spacer(Modifier.width(6.dp))
                        Text("เปิดดู Preview เว็บไซต์", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }

            // Clickable Mint-Cyan File Chips — tap any file to jump straight to that file in Files tab!
            if (mentionedFiles.isNotEmpty() && onOpenFile != null) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(mentionedFiles) { filePath ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = OrqenMint.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, OrqenMint.copy(alpha = 0.45f)),
                            modifier = Modifier.clickable { onOpenFile(filePath) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                com.jarves.mh.ui.FileTypeIcon(
                                    fileName = filePath,
                                    isDirectory = false,
                                    size = 13.dp,
                                    showBadge = false,
                                )
                                Text(
                                    text = filePath,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = OrqenMint,
                                    maxLines = 1,
                                )
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = null,
                                    tint = OrqenMint.copy(alpha = 0.8f),
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val FILE_PATH_REGEX = Regex(
    """(?<![A-Za-z0-9_])([A-Za-z0-9_\-./]+\.(?:kt|java|xml|html|css|js|ts|tsx|jsx|json|py|md|gradle|kts|yml|yaml|txt|sh|sql|vue|svelte|dart|swift|c|cpp|h|go|rs))(?![A-Za-z0-9_])""",
    RegexOption.IGNORE_CASE,
)

private fun extractMentionedFiles(message: GroupChatMessage): List<String> {
    val found = linkedSetOf<String>()
    message.relatedFilePath?.takeIf { it.isNotBlank() }?.let { found.add(it.trim()) }
    FILE_PATH_REGEX.findAll(message.text).forEach { m ->
        val path = m.groupValues[1].trim().removePrefix("./")
        if (path.length in 3..120 && !path.startsWith("http")) {
            found.add(path)
        }
    }
    message.workDetails.forEach { detail ->
        FILE_PATH_REGEX.findAll(detail).forEach { m ->
            val path = m.groupValues[1].trim().removePrefix("./")
            if (path.length in 3..120 && !path.startsWith("http")) {
                found.add(path)
            }
        }
    }
    return found.take(8)
}

private fun stripMarkdownAndLinksForHumans(raw: String): Pair<String, Map<String, String>> {
    // Track display basename -> real relative path so tapping ANY highlighted filename opens the exact file!
    val fileTargetMap = linkedMapOf<String, String>()

    // 1. Replace markdown file links like [swift-build.gradle.kts](file:///workspace/swift-build.gradle.kts)
    //    or bare (file:///workspace/...) with just the clean basename!
    val mdFileLinkRegex = Regex("""\[([^\]]+)\]\(file:///[^)]+\)""")
    var text = mdFileLinkRegex.replace(raw) { match ->
        val fullMatch = match.value
        val pathPart = fullMatch.substringAfter("file:///").substringBefore(")").substringBefore("#")
        val cleanRel = pathPart.removePrefix("workspace/").substringAfter("/", pathPart)
        val display = match.groupValues[1].substringAfterLast('/').trim()
        if (display.isNotBlank()) fileTargetMap[display] = cleanRel
        display
    }
    val bareFileUriRegex = Regex("""\(?file:///([^\s)]+)\)?""")
    text = bareFileUriRegex.replace(text) { match ->
        val rawPath = match.groupValues[1].substringBefore("#")
        val cleanRel = rawPath.removePrefix("workspace/").substringAfter("/", rawPath)
        val base = rawPath.substringAfterLast('/').trim()
        if (base.isNotBlank()) fileTargetMap[base] = cleanRel
        base
    }

    // 2. Simplify verbose nested paths like ".ai/automation-reports/plugin-ecosystem-audit.md" -> "plugin-ecosystem-audit.md"
    val deepPathRegex = Regex("""(?<![:/\w])((?:\.?[A-Za-z0-9_.-]+/){2,}([A-Za-z0-9_.-]+\.[A-Za-z0-9]{1,8}))""")
    text = deepPathRegex.replace(text) { match ->
        val full = match.groupValues[1]
        val base = match.groupValues[2]
        fileTargetMap.putIfAbsent(base, full)
        base
    }

    // 3. Remove distracting raw markdown symbols (###, ##, #, ***, **, backticks, horizontal rules ---)
    val cleanedLines = text.lines().map { line ->
        val trimmed = line.trim()
        when {
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> ""
            trimmed.startsWith("#") -> trimmed.trimStart('#').trim()
            else -> line
        }
    }
    val cleaned = cleanedLines.joinToString("\n")
        .replace("***", "")
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    return cleaned to fileTargetMap
}

private fun buildOrqenFormattedMessage(
    raw: String,
    mintColor: Color,
    isUser: Boolean,
    agents: List<AgentState> = emptyList(),
): AnnotatedString = buildAnnotatedString {
    val (cleaned, fileTargets) = if (isUser) {
        raw.replace("**", "").replace("`", "") to emptyMap()
    } else {
        stripMarkdownAndLinksForHumans(raw)
    }
    append(cleaned)

    // Highlight headings (lines that originally started with #) & bold spans (**...**) cleanly without raw symbols
    var searchStart = 0
    val boldRegex = Regex("""\*\*([^*]+)\*\*""")
    boldRegex.findAll(raw).forEach { match ->
        val inner = match.groupValues[1].replace("`", "").trim()
        if (inner.isNotBlank()) {
            val idx = cleaned.indexOf(inner, startIndex = searchStart)
            if (idx >= 0) {
                addStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = if (isUser) Color.Unspecified else mintColor,
                    ),
                    idx,
                    idx + inner.length,
                )
                searchStart = idx + inner.length
            }
        }
    }
    raw.lines().filter { it.trim().startsWith("#") }.forEach { headingLine ->
        val cleanHeading = headingLine.trim().trimStart('#').trim().replace("**", "").replace("`", "")
        if (cleanHeading.isNotBlank()) {
            val idx = cleaned.indexOf(cleanHeading)
            if (idx >= 0) {
                addStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.5.sp,
                        color = if (isUser) Color.Unspecified else mintColor,
                    ),
                    idx,
                    idx + cleanHeading.length,
                )
            }
        }
    }

    // Highlight & make ALL filenames clickable to open directly in the file viewer!
    FILE_PATH_REGEX.findAll(cleaned).forEach { match ->
        val token = match.value
        val targetPath = fileTargets[token] ?: token
        addStyle(
            SpanStyle(
                color = mintColor,
                fontWeight = FontWeight.SemiBold,
                background = mintColor.copy(alpha = 0.12f),
                textDecoration = TextDecoration.Underline,
            ),
            match.range.first,
            match.range.last + 1,
        )
        addStringAnnotation(
            tag = "FILE_LINK",
            annotation = targetPath,
            start = match.range.first,
            end = match.range.last + 1,
        )
    }
    // Also annotate any mapped basenames from markdown links
    fileTargets.forEach { (displayBase, targetPath) ->
        var idx = cleaned.indexOf(displayBase)
        while (idx >= 0) {
            addStyle(
                SpanStyle(
                    color = mintColor,
                    fontWeight = FontWeight.SemiBold,
                    background = mintColor.copy(alpha = 0.12f),
                    textDecoration = TextDecoration.Underline,
                ),
                idx,
                idx + displayBase.length,
            )
            addStringAnnotation(
                tag = "FILE_LINK",
                annotation = targetPath,
                start = idx,
                end = idx + displayBase.length,
            )
            idx = cleaned.indexOf(displayBase, startIndex = idx + displayBase.length)
        }
    }

    // Highlight @mentions with EACH mentioned agent's own distinct accent color!
    val lowerCleaned = cleaned.lowercase()
    val mentionRegex = Regex("""@(?:เจ้าของบริษัท|Agent\s*\d+|[^\s,:;()]+)""")
    mentionRegex.findAll(cleaned).forEach { match ->
        val matchedText = match.value.lowercase()
        val matchedAgent = agents.firstOrNull { ag ->
            val nameTok = "@${ag.config.name.lowercase()}"
            val defaultTok = "@${ag.config.slot.defaultName.lowercase()}"
            val idTok = "@${ag.config.slot.id.lowercase()}"
            matchedText == nameTok || matchedText == defaultTok || matchedText == idTok
        }
        val color = matchedAgent?.let { agentAccentColor(it.config.slot) } ?: mintColor
        addStyle(
            SpanStyle(
                color = color,
                fontWeight = FontWeight.ExtraBold,
                background = color.copy(alpha = 0.14f),
            ),
            match.range.first,
            match.range.last + 1,
        )
    }
    // Also match multi-word custom agent names (e.g. "@Agent 01", "@QA Engineer")
    agents.forEach { ag ->
        val token = "@${ag.config.name}".lowercase()
        val color = agentAccentColor(ag.config.slot)
        var idx = lowerCleaned.indexOf(token)
        while (idx >= 0) {
            addStyle(
                SpanStyle(
                    color = color,
                    fontWeight = FontWeight.ExtraBold,
                    background = color.copy(alpha = 0.14f),
                ),
                idx,
                idx + token.length,
            )
            idx = lowerCleaned.indexOf(token, startIndex = idx + token.length)
        }
    }

    // Highlight key status markers (PASS:, FAIL:, completion summary heading)
    val keyMarkers = listOf("PASS:", "FAIL:", "รายงานสรุปงานเสร็จสิ้นถึง")
    keyMarkers.forEach { marker ->
        val idx = cleaned.indexOf(marker)
        if (idx >= 0) {
            val color = when {
                marker.startsWith("FAIL") -> Color(0xFFF87171)
                else -> mintColor
            }
            addStyle(
                SpanStyle(fontWeight = FontWeight.ExtraBold, color = color),
                idx,
                idx + marker.length,
            )
        }
    }
}

@Composable
fun TypeSelectorChip(
    label: String,
    type: GroupMessageType,
    selected: GroupMessageType,
    onSelect: (GroupMessageType) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (type == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.clickable { onSelect(type) },
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = if (type == selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

fun getMessageTypeColor(type: GroupMessageType): Color = when (type) {
    GroupMessageType.TASK_ASSIGNMENT -> Color(0xFF1976D2) // Blue
    GroupMessageType.PROGRESS_UPDATE -> Color(0xFF388E3C) // Green
    GroupMessageType.COMPLETION_REPORT -> Color(0xFF2E7D32)
    GroupMessageType.BUG_REPORT -> Color(0xFFD32F2F) // Red
    GroupMessageType.REVIEW_REQUEST -> Color(0xFFF57C00) // Orange
    GroupMessageType.APPROVAL -> Color(0xFF7B1FA2) // Purple
    GroupMessageType.SYSTEM -> Color(0xFF616161)
    GroupMessageType.NORMAL -> Color(0xFF757575)
}
