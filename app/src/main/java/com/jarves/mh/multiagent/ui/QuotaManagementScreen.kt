package com.jarves.mh.multiagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.jarves.mh.multiagent.supervisor.ContinuousExecutionConfig
import com.jarves.mh.provider.core.EstimatedUsage
import com.jarves.mh.provider.core.LocalUsage
import com.jarves.mh.provider.core.OfficialQuota
import com.jarves.mh.provider.core.ProviderConfig
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaManagementScreen(
    officialQuota: OfficialQuota?,
    estimatedUsage: EstimatedUsage?,
    localUsage: LocalUsage,
    agents: List<AgentState>,
    providerConfigs: List<ProviderConfig> = emptyList(),
    allProviderQuotas: Map<String, OfficialQuota> = emptyMap(),
    allProviderEstimated: Map<String, EstimatedUsage> = emptyMap(),
    allProviderLocal: Map<String, LocalUsage> = emptyMap(),
    budgetLimitUsd: Double = 10.0,
    isAutoPauseEnabled: Boolean = true,
    continuousConfig: ContinuousExecutionConfig = ContinuousExecutionConfig(),
    onUpdateContinuousConfig: (ContinuousExecutionConfig) -> Unit = {},
    onRefreshQuotas: () -> Unit = {},
    onUpdateBudget: (Double, Boolean) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    // Real-time auto-refresh while Quota screen is open
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            onRefreshQuotas()
        }
    }

    var showThbFirst by remember { mutableStateOf(true) }
    var autoPause by remember { mutableStateOf(isAutoPauseEnabled) }
    var budgetLimit by remember { mutableDoubleStateOf(budgetLimitUsd) }

    // Filter strictly to providers & models actually used by active agents in this project
    val usedProviderIds = remember(agents) {
        agents.filter { it.config.isEnabled }.map { it.config.providerId }.toSet()
            .ifEmpty { agents.map { it.config.providerId }.toSet() }
    }
    val projectProviderConfigs = remember(providerConfigs, usedProviderIds) {
        if (usedProviderIds.isEmpty()) providerConfigs
        else providerConfigs.filter { it.id in usedProviderIds }
    }
    val projectEstimatedMap = remember(allProviderEstimated, usedProviderIds) {
        if (usedProviderIds.isEmpty()) allProviderEstimated
        else allProviderEstimated.filterKeys { it in usedProviderIds }
    }
    val projectLocalMap = remember(allProviderLocal, usedProviderIds) {
        if (usedProviderIds.isEmpty()) allProviderLocal
        else allProviderLocal.filterKeys { it in usedProviderIds }
    }

    val totalCostUsd = if (projectEstimatedMap.isNotEmpty()) {
        projectEstimatedMap.values.sumOf { it.estimatedCostUsd }
    } else {
        estimatedUsage?.estimatedCostUsd ?: 0.0
    }
    val totalCostThb = totalCostUsd * 35.0
    val totalTokens = if (projectEstimatedMap.isNotEmpty()) {
        projectEstimatedMap.values.sumOf { it.estimatedTokens }
    } else {
        estimatedUsage?.estimatedTokens ?: 0L
    }
    val totalRequests = if (projectLocalMap.isNotEmpty()) {
        projectLocalMap.values.sumOf { it.totalRequests }
    } else {
        localUsage.totalRequests
    }
    val budgetProgress = (totalCostUsd / budgetLimit.coerceAtLeast(0.01)).toFloat().coerceIn(0f, 1f)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.quota_mgmt_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "All Providers · 3-Tier Quota Tracking",
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
                    IconButton(onClick = onRefreshQuotas) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Quotas")
                    }
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
                            text = "TOTAL ESTIMATED EXPENSE (ALL PROVIDERS)",
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
                                Text("$totalRequests API calls", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column {
                                Text("Providers", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                Text("${providerConfigs.size.coerceAtLeast(1)} active", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
            }

            // 1.5 Per-Agent / Per-Model Real Quota Breakdown (shows real Antigravity & Provider quota per model)
            if (agents.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(16.dp)),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "โควต้าจริงแยกตามโมเดลของทีม AI",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "${agents.count { it.config.isEnabled }}/${agents.size} ใช้งาน",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            agents.forEach { agent ->
                                val cfg = agent.config
                                val q = allProviderQuotas[cfg.providerId]
                                val est = allProviderEstimated[cfg.providerId]
                                val loc = allProviderLocal[cfg.providerId]
                                val rem = q?.remainingPercent?.coerceIn(0f, 1f)
                                val accent = agentAccentColor(cfg.slot)
                                val barColor = when {
                                    rem == null -> accent
                                    rem > 0.25f -> Color(0xFF16A34A)
                                    rem > 0.12f -> Color(0xFFD97706)
                                    else -> Color(0xFFDC2626)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            imageVector = AgentIconCatalog.iconFor(cfg),
                                            contentDescription = null,
                                            tint = accent,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Text(
                                            text = "${cfg.name} · ${cfg.modelId.ifBlank { "default" }}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = if (rem != null) "เหลือ ${(rem * 100).toInt()}%" else "${loc?.totalRequests ?: 0} req",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = barColor,
                                        )
                                    }
                                    if (rem != null) {
                                        LinearProgressIndicator(
                                            progress = { rem },
                                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                                            color = barColor,
                                            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                        )
                                    }
                                    val subInfo = buildList {
                                        q?.resetTime?.takeIf { it.isNotBlank() }?.let { add("รีเซ็ต: $it") }
                                        est?.let { add("${it.estimatedTokens} tokens · ฿${String.format("%.2f", it.estimatedCostUsd * 35.0)}") }
                                        loc?.let { add("${it.totalRequests} ครั้ง") }
                                    }.joinToString(" · ")
                                    if (subInfo.isNotBlank()) {
                                        Text(
                                            text = subInfo,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
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
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
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

            // 2.5 Auto-Switch Model Before Quota Ends Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.quota_auto_switch_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = stringResource(R.string.quota_auto_switch_desc, continuousConfig.quotaSwitchThresholdPercent),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = continuousConfig.autoSwitchOnLowQuota,
                                onCheckedChange = { onUpdateContinuousConfig(continuousConfig.copy(autoSwitchOnLowQuota = it)) },
                            )
                        }

                        if (continuousConfig.autoSwitchOnLowQuota) {
                            Text(
                                text = stringResource(R.string.quota_fallback_model_label),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            val realPlanModels = remember(projectProviderConfigs) {
                                projectProviderConfigs
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

            // 3. Active Project Models Real-Time Quota Breakdown (ONLY models used in this project!)
            item {
                Text(
                    text = stringResource(R.string.quota_project_models_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            items(projectProviderConfigs) { cfg ->
                val pQuota = allProviderQuotas[cfg.id]
                val pEst = allProviderEstimated[cfg.id]
                val pLocal = allProviderLocal[cfg.id] ?: LocalUsage()
                val usedModelsForProvider = agents.filter { it.config.providerId == cfg.id }
                    .map { it.config.modelId.ifBlank { cfg.selectedModelId } }
                    .distinct()
                    .joinToString(", ")
                    .ifBlank { cfg.selectedModelId.ifBlank { "Default" } }
                val isAvail = pQuota?.isAvailable == true
                val remaining = (pQuota?.remainingPercent ?: 1f).coerceIn(0f, 1f)
                val usedPct = ((1f - remaining) * 100).toInt()
                val healthColor = when {
                    !isAvail -> Color.Gray
                    remaining > 0.25f -> Color(0xFF16A34A)
                    remaining > 0.12f -> Color(0xFFD97706)
                    else -> Color(0xFFDC2626)
                }

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(cfg.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    "${cfg.type.displayName} · $usedModelsForProvider",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Surface(shape = RoundedCornerShape(10.dp), color = healthColor.copy(alpha = 0.15f)) {
                                Text(
                                    text = if (isAvail) "${(remaining * 100).toInt()}% LEFT (-$usedPct%)" else "LIVE TRACKING",
                                    color = healthColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }

                        if (isAvail) {
                            LinearProgressIndicator(
                                progress = { remaining },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = healthColor,
                                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                        }

                        if (pQuota != null && pQuota.isAvailable) {
                            Text(
                                text = "Source: ${pQuota.source}${pQuota.resetTime?.let { " · Reset: $it" } ?: ""}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                text = pQuota?.unavailableReason ?: stringResource(R.string.quota_unavailable),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Tokens: ${pEst?.estimatedTokens ?: 0}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            Text(
                                text = if (showThbFirst) "฿${String.format("%.2f", pEst?.estimatedCostThb ?: 0.0)}" else "$${String.format("%.4f", pEst?.estimatedCostUsd ?: 0.0)}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("Requests: ${pLocal.totalRequests}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            // 5. Tier 3 — Per-Agent Cost Allocation
            item {
                Text(
                    text = stringResource(R.string.quota_per_agent_breakdown),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            items(agents) { agent ->
                val pEst = allProviderEstimated[agent.config.providerId]
                val agentTokens = pEst?.estimatedTokens ?: (totalTokens / agents.size.coerceAtLeast(1))
                val agentUsd = pEst?.estimatedCostUsd ?: (totalCostUsd / agents.size.coerceAtLeast(1))
                val agentThb = agentUsd * 35.0

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
                        Column(Modifier.weight(1f)) {
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
