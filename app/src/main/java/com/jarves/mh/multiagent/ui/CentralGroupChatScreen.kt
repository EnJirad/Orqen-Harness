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
    pendingDirectivesCount: Int = 0,
    listState: LazyListState = rememberLazyListState(),
    initialScrollDone: Boolean = false,
    onInitialScrollDone: () -> Unit = {},
    onClearChat: (() -> Unit)? = null,
    onOpenFile: ((String) -> Unit)? = null,
    onSendMessageWithImages: ((text: String, type: GroupMessageType, targetSlot: AgentSlot?, imageUris: List<Uri>) -> Unit)? = null,
    onSendMessage: (text: String, type: GroupMessageType, targetSlot: AgentSlot?) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(GroupMessageType.NORMAL) }
    var targetSlot by remember { mutableStateOf<AgentSlot?>(null) }
    var selectedImageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var pendingUserSendScroll by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
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

    val workingAgents = remember(agents, typingAgentSlot) {
        agents.filter { it.isWorking && it.config.slot != typingAgentSlot }
    }
    val totalItems = messages.size + workingAgents.size + if (typingAgentName != null) 1 else 0
    var prevTotalCount by remember { mutableIntStateOf(totalItems) }

    // Smart Scroll Memory:
    // - First visit to this project's chat: jump INSTANTLY to bottom (no top-to-bottom animation)
    // - Returning from another tab: preserve exact scroll position (e.g. message #3)
    // - New message arrives: scroll to bottom ONLY if user is already near bottom or just sent a message
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
        if (totalItems > prevTotalCount) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val isNearBottom = lastVisible >= (prevTotalCount - 2).coerceAtLeast(0)
            if (pendingUserSendScroll || isNearBottom) {
                listState.scrollToItem(totalItems - 1)
                pendingUserSendScroll = false
            }
        }
        prevTotalCount = totalItems
    }

    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && totalItems > 0) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (lastVisible >= (totalItems - 2).coerceAtLeast(0)) {
                listState.scrollToItem(totalItems - 1)
            }
        }
    }

    if (showClearConfirm && onClearChat != null) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.chat_clear_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.chat_clear_confirm_desc), fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        onClearChat()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                ) {
                    Text(stringResource(R.string.chat_clear_confirm_btn), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
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
                    if (onClearChat != null && messages.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.chat_clear_all),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
            // Compact 3-color AI Team Status Banner (expandable dropdown when Warning/Disconnected to show which AI & exact error!)
            val showBanner = isTeamRunning || teamBannerHealth != TeamBannerHealth.RUNNING
            if (showBanner) {
                var bannerExpanded by remember(teamBannerHealth, teamBannerDetail) { mutableStateOf(false) }
                val hasIssueDetail = teamBannerHealth != TeamBannerHealth.RUNNING && !teamBannerDetail.isNullOrBlank()
                val (bannerBg, bannerFg, bannerText) = when (teamBannerHealth) {
                    TeamBannerHealth.DISCONNECTED -> Triple(
                        Color(0xFFDC2626).copy(alpha = 0.14f),
                        Color(0xFFDC2626),
                        teamBannerMessage?.takeIf { it.isNotBlank() } ?: stringResource(R.string.team_status_disconnected_agent),
                    )
                    TeamBannerHealth.WARNING -> Triple(
                        Color(0xFFF59E0B).copy(alpha = 0.16f),
                        Color(0xFFD97706),
                        teamBannerMessage?.takeIf { it.isNotBlank() } ?: stringResource(R.string.team_status_warning_network),
                    )
                    TeamBannerHealth.RUNNING -> Triple(
                        Color(0xFF22C55E).copy(alpha = 0.14f),
                        Color(0xFF16A34A),
                        stringResource(R.string.team_status_running_short) +
                            if (pendingDirectivesCount > 0) " ($pendingDirectivesCount)" else "",
                    )
                }
                Surface(
                    color = bannerBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable(enabled = hasIssueDetail) { bannerExpanded = !bannerExpanded },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(bannerFg))
                            Text(
                                text = bannerText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = bannerFg,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (hasIssueDetail) {
                                Icon(
                                    imageVector = if (bannerExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Details",
                                    tint = bannerFg,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        AnimatedVisibility(visible = bannerExpanded && hasIssueDetail) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
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
                items(messages, key = { it.id }) { msg ->
                    GroupChatBubble(
                        message = msg,
                        onOpenFile = onOpenFile,
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
                if (!typingAgentName.isNullOrBlank()) {
                    item(key = "typing-wave-indicator") {
                        AiTypingWaveBubble(agentName = typingAgentName)
                    }
                }
            }

            // Quick Mention Bar (Shows ONLY actual agents in this team with their custom names!)
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
                        SuggestionChip(
                            onClick = {
                                targetSlot = if (isSelected) null else slot
                                if (!inputText.contains("@$agentName")) {
                                    inputText = "@$agentName $inputText"
                                }
                            },
                            label = { Text("@$agentName", fontSize = 11.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
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
                    // Mode selector (Chat = talk freely with idle AI; Task/Bug/Review = dispatch work to team!)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TypeSelectorChip("Chat", GroupMessageType.NORMAL, selectedType) { selectedType = it }
                        TypeSelectorChip("Task", GroupMessageType.TASK_ASSIGNMENT, selectedType) { selectedType = it }
                        TypeSelectorChip("Progress", GroupMessageType.PROGRESS_UPDATE, selectedType) { selectedType = it }
                        TypeSelectorChip("Bug", GroupMessageType.BUG_REPORT, selectedType) { selectedType = it }
                        TypeSelectorChip("Review", GroupMessageType.REVIEW_REQUEST, selectedType) { selectedType = it }
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { imagePicker.launch("image/*") },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(OrqenMint.copy(alpha = 0.14f)),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = "Attach Image",
                                tint = OrqenMint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            if (inputText.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.chat_send_to_team),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                            BasicTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        val canSend = inputText.isNotBlank() || selectedImageUris.isNotEmpty()
                        IconButton(
                            onClick = {
                                if (canSend) {
                                    pendingUserSendScroll = true
                                    val finalPrompt = inputText.ifBlank { "ช่วยวิเคราะห์รูปภาพที่แนบมาและออกแบบ/สร้างตามภาพนี้" }
                                    if (selectedImageUris.isNotEmpty() && onSendMessageWithImages != null) {
                                        onSendMessageWithImages(finalPrompt, selectedType, targetSlot, selectedImageUris)
                                    } else {
                                        onSendMessage(finalPrompt, selectedType, targetSlot)
                                    }
                                    inputText = ""
                                    selectedImageUris = emptyList()
                                    selectedType = GroupMessageType.NORMAL
                                    targetSlot = null
                                }
                            },
                            enabled = canSend,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
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

@Composable
private fun AiTypingWaveBubble(agentName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, OrqenMint.copy(alpha = 0.45f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_orqen_mark),
                    contentDescription = "ORQEN",
                    tint = OrqenMint,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = agentName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                BouncingWaveDots(
                    color = OrqenMint,
                    dotSize = 6.dp,
                    bounceHeight = -6f,
                    modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                )
            }
        }
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(3.dp))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, actColor.copy(alpha = 0.5f)),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 11.dp, vertical = 7.dp)) {
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
    }
}

@Composable
fun GroupChatBubble(
    message: GroupChatMessage,
    onOpenFile: ((String) -> Unit)? = null,
    onSendAsTask: ((String) -> Unit)? = null,
) {
    val isUser = message.isUser
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val borderColor = if (isUser) OrqenMint.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    val clipboard = LocalClipboardManager.current
    var copied by remember(message.id) { mutableStateOf(false) }
    var detailsExpanded by remember(message.id) { mutableStateOf(false) }
    val mentionedFiles = remember(message.id, message.text, message.relatedFilePath, message.workDetails) {
        extractMentionedFiles(message)
    }
    val formattedText = remember(message.id, message.text, isUser) {
        buildOrqenFormattedMessage(
            raw = message.text,
            mintColor = OrqenMint,
            isUser = isUser,
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
                Icon(
                    painter = painterResource(id = R.drawable.ic_orqen_mark),
                    contentDescription = null,
                    tint = OrqenMint,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = message.senderName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isUser) OrqenMint else MaterialTheme.colorScheme.onSurface,
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
                .clip(RoundedCornerShape(15.dp))
                .background(bg)
                .border(1.dp, borderColor, RoundedCornerShape(15.dp))
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

            SelectionContainer {
                Text(
                    text = formattedText,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }

            // 1-tap "Send as Task" button when user sends a build/edit instruction in Chat mode
            if (!isUser && !message.suggestedTaskPrompt.isNullOrBlank() && onSendAsTask != null) {
                Button(
                    onClick = { onSendAsTask(message.suggestedTaskPrompt) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OrqenMint,
                        contentColor = Color(0xFF031F1B),
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

private fun buildOrqenFormattedMessage(
    raw: String,
    mintColor: Color,
    isUser: Boolean,
): AnnotatedString = buildAnnotatedString {
    val cleaned = raw.replace("**", "")
    append(cleaned)

    // Highlight bold markdown spans from original text (**...**)
    var searchStart = 0
    val boldRegex = Regex("""\*\*([^*]+)\*\*""")
    boldRegex.findAll(raw).forEach { match ->
        val inner = match.groupValues[1]
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

    // Highlight inline code (`...`) in Mint-Cyan monospace
    val codeRegex = Regex("""`([^`]+)`""")
    codeRegex.findAll(cleaned).forEach { match ->
        addStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = mintColor,
                background = mintColor.copy(alpha = 0.12f),
            ),
            match.range.first,
            match.range.last + 1,
        )
    }

    // Highlight file paths & filenames in Mint-Cyan bold underline
    FILE_PATH_REGEX.findAll(cleaned).forEach { match ->
        addStyle(
            SpanStyle(
                color = mintColor,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                textDecoration = TextDecoration.Underline,
            ),
            match.range.first,
            match.range.last + 1,
        )
    }

    // Highlight @mentions (e.g., @Agent 01, @Agent 02, @เจ้าของบริษัท) in glowing Mint-Cyan pill style
    val mentionRegex = Regex("""@(?:เจ้าของบริษัท|Agent\s*\d+|[^\s,:;()]+)""")
    mentionRegex.findAll(cleaned).forEach { match ->
        addStyle(
            SpanStyle(
                color = mintColor,
                fontWeight = FontWeight.ExtraBold,
                background = mintColor.copy(alpha = 0.14f),
            ),
            match.range.first,
            match.range.last + 1,
        )
    }

    // Highlight key status markers (PASS:, FAIL:, 📋 heading)
    val keyMarkers = listOf("PASS:", "FAIL:", "📋 รายงานสรุปงานเสร็จสิ้นถึง")
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
