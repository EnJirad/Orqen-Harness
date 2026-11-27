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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarves.mh.R
import com.jarves.mh.multiagent.persistence.ConversationEntity
import com.jarves.mh.multiagent.persistence.PersistentTaskStatus
import com.jarves.mh.multiagent.persistence.TaskEntity
import com.jarves.mh.ui.theme.OrqenMint

data class PersistentTaskUiSummary(
    val task: TaskEntity,
    val completedSteps: Int,
    val totalSteps: Int,
    val currentStepTitle: String?,
)

@Composable
fun PersistentSessionsAndTasksDialog(
    projectName: String,
    conversations: List<ConversationEntity>,
    activeConversationId: String?,
    tasks: List<PersistentTaskUiSummary>,
    onCreateConversation: (String) -> Unit,
    onSelectConversation: (String) -> Unit,
    onArchiveConversation: (String) -> Unit,
    onContinueTaskInChat: (String) -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onStopTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onStopAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var newChatTitle by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.84f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, OrqenMint.copy(alpha = 0.35f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.orqen_sessions_tasks_btn),
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = projectName,
                            fontSize = 12.sp,
                            color = OrqenMint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (tasks.any { it.task.status == PersistentTaskStatus.RUNNING || it.task.status == PersistentTaskStatus.QUEUED }) {
                        OutlinedButton(
                            onClick = onStopAll,
                            border = BorderStroke(1.dp, Color(0xFFF87171)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.orqen_stop_all_btn),
                                fontSize = 11.sp,
                                color = Color(0xFFF87171),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }

                // New Session bar (independent of Task lifecycle)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newChatTitle,
                        onValueChange = { newChatTitle = it },
                        placeholder = { Text(stringResource(R.string.orqen_new_session_btn), fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            onCreateConversation(newChatTitle.ifBlank { "Chat ${conversations.size + 1}" })
                            newChatTitle = ""
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.orqen_sessions_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(conversations, key = { it.conversationId }) { conv ->
                        val isActive = conv.conversationId == activeConversationId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isActive) OrqenMint.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                if (isActive) OrqenMint else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectConversation(conv.conversationId) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.Default.Forum,
                                    contentDescription = null,
                                    tint = if (isActive) OrqenMint else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = conv.title,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (conv.linkedTaskIds.isNotEmpty()) {
                                        Text(
                                            text = "Tasks: ${conv.linkedTaskIds.joinToString(", ")}",
                                            fontSize = 10.sp,
                                            color = OrqenMint,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { onArchiveConversation(conv.conversationId) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.orqen_persistent_tasks_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    items(tasks, key = { it.task.taskId }) { item ->
                        val t = item.task
                        val statusColor = when (t.status) {
                            PersistentTaskStatus.RUNNING, PersistentTaskStatus.RECOVERING -> OrqenMint
                            PersistentTaskStatus.COMPLETED -> Color(0xFF4ADE80)
                            PersistentTaskStatus.PAUSED, PersistentTaskStatus.PAUSE_REQUESTED -> Color(0xFFFBBF24)
                            PersistentTaskStatus.BLOCKED, PersistentTaskStatus.FAILED -> Color(0xFFF87171)
                            PersistentTaskStatus.CANCELLED -> Color(0xFF94A3B8)
                            PersistentTaskStatus.DRAFT, PersistentTaskStatus.QUEUED, PersistentTaskStatus.PLANNING, PersistentTaskStatus.READY -> Color(0xFF60A5FA)
                        }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "${t.taskId} · ${t.title}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = statusColor.copy(alpha = 0.16f),
                                    ) {
                                        Text(
                                            text = t.status.name,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = statusColor,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }

                                // Honest progress display — NO fake percentages!
                                Text(
                                    text = buildString {
                                        append(stringResource(R.string.orqen_steps_completed_fmt, item.completedSteps))
                                        if (!item.currentStepTitle.isNullOrBlank()) {
                                            append(" · ${item.currentStepTitle}")
                                        }
                                        if (t.actualCostUsd > 0.0) {
                                            append(" · $${String.format("%.4f", t.actualCostUsd)}")
                                        }
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                if (!t.lastError.isNullOrBlank()) {
                                    Text(
                                        text = t.lastError,
                                        fontSize = 11.sp,
                                        color = Color(0xFFF87171),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = { onContinueTaskInChat(t.taskId) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.orqen_continue_existing_task),
                                            fontSize = 10.sp,
                                        )
                                    }
                                    if (t.status == PersistentTaskStatus.RUNNING) {
                                        OutlinedButton(
                                            onClick = { onPauseTask(t.taskId) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text(stringResource(R.string.rt_exec_pause_task), fontSize = 10.sp)
                                        }
                                    } else if (t.status == PersistentTaskStatus.PAUSED || t.status == PersistentTaskStatus.QUEUED) {
                                        OutlinedButton(
                                            onClick = { onResumeTask(t.taskId) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text(stringResource(R.string.rt_exec_resume_task), fontSize = 10.sp, color = OrqenMint)
                                        }
                                    } else if (t.status == PersistentTaskStatus.BLOCKED || t.status == PersistentTaskStatus.FAILED) {
                                        OutlinedButton(
                                            onClick = { onRetryTask(t.taskId) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text(stringResource(R.string.orqen_retry_step_btn), fontSize = 10.sp)
                                        }
                                    }
                                    if (t.status != PersistentTaskStatus.COMPLETED && t.status != PersistentTaskStatus.CANCELLED) {
                                        OutlinedButton(
                                            onClick = { onStopTask(t.taskId) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text(stringResource(R.string.rt_exec_stop_task), fontSize = 10.sp, color = Color(0xFFF87171))
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
