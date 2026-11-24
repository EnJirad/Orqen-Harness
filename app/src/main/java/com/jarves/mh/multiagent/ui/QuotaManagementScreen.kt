package com.jarves.mh.multiagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.provider.core.EstimatedUsage
import com.jarves.mh.provider.core.LocalUsage
import com.jarves.mh.provider.core.OfficialQuota

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaManagementScreen(
    officialQuota: OfficialQuota?,
    estimatedUsage: EstimatedUsage?,
    localUsage: LocalUsage,
    agents: List<AgentState>,
    budgetLimitUsd: Double = 10.0,
    isAutoPauseEnabled: Boolean = true,
    onUpdateBudget: (Double, Boolean) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    var showThbFirst by remember { mutableStateOf(true) }
    var autoPause by remember { mutableStateOf(isAutoPauseEnabled) }
    var budgetLimit by remember { mutableDoubleStateOf(budgetLimitUsd) }

    val totalCostUsd = estimatedUsage?.estimatedCostUsd ?: 0.0
    val totalCostThb = estimatedUsage?.estimatedCostThb ?: (totalCostUsd * 35.0)
    val totalTokens = estimatedUsage?.estimatedTokens ?: 0L
    val budgetProgress = (totalCostUsd / budgetLimit.coerceAtLeast(0.01)).toFloat().coerceIn(0f, 1f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.quota_mgmt_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "3-Tier Quota Tracking · Real-time Valuation",
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
                    TextButton(onClick = { showThbFirst = !showThbFirst }) {
                        Text(
                            text = if (showThbFirst) "฿ THB" else "$ USD",
                            fontWeight = FontWeight.Bold,
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
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Cost Overview Banner Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            text = "TOTAL ESTIMATED EXPENSE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (showThbFirst) "฿${String.format("%.2f", totalCostThb)}" else "$${String.format("%.4f", totalCostUsd)}",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = if (showThbFirst) "(~ $${String.format("%.4f", totalCostUsd)} USD)" else "(~ ฿${String.format("%.2f", totalCostThb)} THB)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text("Tokens Consumed", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                Text("$totalTokens tokens", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column {
                                Text("Requests", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                Text("${localUsage.totalRequests} API calls", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column {
                                Text("Tasks Completed", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                Text("${localUsage.completedTasks} done", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
            }

            // 2. Budget Guard & Auto-Pause Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.quota_budget_guard_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = stringResource(R.string.quota_budget_limit, budgetLimit, budgetLimit * 35.0),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = autoPause,
                                onCheckedChange = {
                                    autoPause = it
                                    onUpdateBudget(budgetLimit, it)
                                },
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        LinearProgressIndicator(
                            progress = { budgetProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (budgetProgress > 0.8f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )

                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Spent: $${String.format("%.2f", totalCostUsd)} (${(budgetProgress * 100).toInt()}%)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Limit: $${String.format("%.2f", budgetLimit)}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // 3. Tier 1 — Official Provider Quotas Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "TIER 1 — OFFICIAL PROVIDER QUOTA",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (officialQuota?.isAvailable == true) Color(0xFF2E7D32).copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f),
                            ) {
                                Text(
                                    text = if (officialQuota?.isAvailable == true) "VERIFIED SOURCE" else "UNAVAILABLE",
                                    color = if (officialQuota?.isAvailable == true) Color(0xFF2E7D32) else Color.Gray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        if (officialQuota?.isAvailable == true) {
                            Text(
                                text = "Source: ${officialQuota.source}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Remaining Quota: ${(officialQuota.remainingPercent?.times(100))?.toInt() ?: 100}%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32),
                            )
                            officialQuota.resetTime?.let {
                                Text("Window Resets: $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            // 4. Tier 2 — Model Rate Card (per 1M Tokens)
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.quota_pricing_table_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(10.dp))

                        RateCardRow(model = "Gemini 2.5/3.8 Flash", inputPrice = "$0.075 / ฿2.63", outputPrice = "$0.30 / ฿10.50")
                        RateCardRow(model = "Gemini 2.5 Pro", inputPrice = "$1.250 / ฿43.75", outputPrice = "$5.00 / ฿175.00")
                        RateCardRow(model = "Claude Sonnet 3.5 / 4.6", inputPrice = "$3.000 / ฿105.00", outputPrice = "$15.00 / ฿525.00")
                        RateCardRow(model = "Claude Haiku 3.5", inputPrice = "$0.800 / ฿28.00", outputPrice = "$4.00 / ฿140.00")
                        RateCardRow(model = "GPT-4o", inputPrice = "$2.500 / ฿87.50", outputPrice = "$10.00 / ฿350.00")
                        RateCardRow(model = "GPT-4o-mini", inputPrice = "$0.150 / ฿5.25", outputPrice = "$0.60 / ฿21.00")
                    }
                }
            }

            // 5. Tier 3 — Per-Agent Cost Allocation Header
            item {
                Text(
                    text = stringResource(R.string.quota_per_agent_breakdown),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // 6. Agent Cost Cards
            items(agents) { agent ->
                // Approximate 25% share per active agent as baseline
                val agentTokens = (totalTokens / agents.size.coerceAtLeast(1))
                val agentUsd = (totalCostUsd / agents.size.coerceAtLeast(1))
                val agentThb = (totalCostThb / agents.size.coerceAtLeast(1))

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = agent.config.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "${agent.config.role.title} · ${agent.config.modelId}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "~ $agentTokens tokens",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (showThbFirst) "฿${String.format("%.2f", agentThb)}" else "$${String.format("%.4f", agentUsd)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (showThbFirst) "$${String.format("%.4f", agentUsd)}" else "฿${String.format("%.2f", agentThb)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RateCardRow(model: String, inputPrice: String, outputPrice: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = model, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.2f))
        Text(text = "In: $inputPrice", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.2f))
        Text(text = "Out: $outputPrice", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.2f))
    }
}
