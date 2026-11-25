package com.jarves.mh.multiagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.multiagent.chat.GroupChatMessage
import com.jarves.mh.multiagent.chat.GroupMessageType
import com.jarves.mh.multiagent.model.AgentSlot
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CentralGroupChatScreen(
    teamName: String,
    messages: List<GroupChatMessage>,
    onSendMessage: (text: String, type: GroupMessageType, targetSlot: AgentSlot?) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var inputText by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(GroupMessageType.NORMAL) }
    var targetSlot by remember { mutableStateOf<AgentSlot?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.team_group_chat), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("User · Agent 01 · Agent 02 · Agent 03 · Agent 04", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .background(MaterialTheme.colorScheme.background),
        ) {
            // Chat message list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages) { msg ->
                    GroupChatBubble(message = msg)
                }
            }

            // Quick Mention Bar
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(AgentSlot.entries) { slot ->
                    val isSelected = targetSlot == slot
                    SuggestionChip(
                        onClick = {
                            targetSlot = if (isSelected) null else slot
                            if (!inputText.contains("@${slot.defaultName}")) {
                                inputText = "@${slot.defaultName} $inputText"
                            }
                        },
                        label = { Text("@${slot.defaultName}", fontSize = 11.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                }
            }

            // Input Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    // Type selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TypeSelectorChip("Normal", GroupMessageType.NORMAL, selectedType) { selectedType = it }
                        TypeSelectorChip("Task", GroupMessageType.TASK_ASSIGNMENT, selectedType) { selectedType = it }
                        TypeSelectorChip("Progress", GroupMessageType.PROGRESS_UPDATE, selectedType) { selectedType = it }
                        TypeSelectorChip("Bug", GroupMessageType.BUG_REPORT, selectedType) { selectedType = it }
                        TypeSelectorChip("Review", GroupMessageType.REVIEW_REQUEST, selectedType) { selectedType = it }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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

                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    onSendMessage(inputText, selectedType, targetSlot)
                                    inputText = ""
                                    selectedType = GroupMessageType.NORMAL
                                    targetSlot = null
                                }
                            },
                            enabled = inputText.isNotBlank(),
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
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
fun GroupChatBubble(message: GroupChatMessage) {
    val isUser = message.isUser
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message.senderName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            if (message.messageType != GroupMessageType.NORMAL) {
                Spacer(Modifier.width(6.dp))
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
        }

        Spacer(Modifier.height(3.dp))

        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
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
