package com.jarves.mh.multiagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.jarves.mh.multiagent.events.TeamEventType
import com.jarves.mh.multiagent.model.EventPriority
import com.jarves.mh.multiagent.model.PrioritizedCollaborationEvent
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityFeedScreen(
    events: List<PrioritizedCollaborationEvent>,
    onClearEvents: () -> Unit = {},
    onBack: () -> Unit,
) {
    var selectedFilter by remember { mutableStateOf("ALL") }

    val filteredEvents = remember(events, selectedFilter) {
        when (selectedFilter) {
            "CRITICAL" -> events.filter { it.priority == EventPriority.CRITICAL }
            "LOCKS" -> events.filter { it.event.type == TeamEventType.FILE_LOCKED || it.event.type == TeamEventType.FILE_UNLOCKED }
            "COMMITS" -> events.filter { it.event.type == TeamEventType.COMMIT_CREATED || it.event.type == TeamEventType.CHECKPOINT_CREATED }
            "REVIEWS" -> events.filter { it.event.type == TeamEventType.REVIEW_REQUESTED || it.event.type == TeamEventType.PART_APPROVED }
            "TESTS" -> events.filter { it.event.type == TeamEventType.BUG_FOUND || it.event.message.contains("test", ignoreCase = true) }
            else -> events
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.feed_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${filteredEvents.size} Real-time collaboration events",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onClearEvents) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Feed")
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
            // Filter Chips Row
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = selectedFilter == "ALL",
                        onClick = { selectedFilter = "ALL" },
                        label = { Text(stringResource(R.string.feed_filter_all)) },
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "CRITICAL",
                        onClick = { selectedFilter = "CRITICAL" },
                        label = { Text(stringResource(R.string.feed_filter_critical)) },
                        leadingIcon = {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        },
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "LOCKS",
                        onClick = { selectedFilter = "LOCKS" },
                        label = { Text(stringResource(R.string.feed_filter_locks)) },
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "COMMITS",
                        onClick = { selectedFilter = "COMMITS" },
                        label = { Text(stringResource(R.string.feed_filter_commits)) },
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "REVIEWS",
                        onClick = { selectedFilter = "REVIEWS" },
                        label = { Text(stringResource(R.string.feed_filter_reviews)) },
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "TESTS",
                        onClick = { selectedFilter = "TESTS" },
                        label = { Text(stringResource(R.string.feed_filter_tests)) },
                    )
                }
            }

            if (filteredEvents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.DynamicFeed, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.feed_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredEvents) { item ->
                        ActivityEventItem(
                            item = item,
                            formattedTime = timeFormat.format(Date(item.event.timestamp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityEventItem(
    item: PrioritizedCollaborationEvent,
    formattedTime: String,
) {
    val priorityColor = when (item.priority) {
        EventPriority.CRITICAL -> Color(0xFFD32F2F)
        EventPriority.HIGH -> Color(0xFFF57C00)
        EventPriority.NORMAL -> MaterialTheme.colorScheme.primary
        EventPriority.LOW -> Color.Gray
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = priorityColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            text = item.priority.name,
                            color = priorityColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = item.event.senderSlot?.defaultName ?: "System",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = formattedTime,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = item.event.message.ifBlank { "Event: ${item.event.type.name}" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (item.event.filePath != null || item.event.commitSha != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item.event.filePath?.let { path ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = "File: ${path.takeLast(28)}",
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    item.event.commitSha?.let { sha ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = "Git: ${sha.take(7)}",
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
