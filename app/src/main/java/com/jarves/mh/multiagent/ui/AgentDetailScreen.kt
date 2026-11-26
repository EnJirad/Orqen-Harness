package com.jarves.mh.multiagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.Login
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
import com.jarves.mh.multiagent.locks.FileLock
import com.jarves.mh.multiagent.model.*
import com.jarves.mh.provider.core.ConnectionTestResult
import com.jarves.mh.provider.core.ModelInfo
import com.jarves.mh.provider.core.ProviderConfig
import com.jarves.mh.provider.core.ProviderType
import com.jarves.mh.runtime.AntigravityAuthState
import com.jarves.mh.runtime.AntigravityAuthStatus
import com.jarves.mh.ui.formatAntigravityModelName
import com.jarves.mh.ui.formatAntigravityModelTier
import com.jarves.mh.ui.theme.PocketOrange
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDetailScreen(
    agentState: AgentState,
    allAgents: List<AgentState> = emptyList(),
    fileLocks: List<FileLock>,
    providerConfigs: List<ProviderConfig> = emptyList(),
    allProviderQuotas: Map<String, com.jarves.mh.provider.core.OfficialQuota> = emptyMap(),
    availableModels: List<String> = listOf("gemini-3.8-flash-high", "gemini-3.8-flash", "claude-sonnet-4-6", "claude-haiku-3-5", "gpt-4o", "gpt-4o-mini", "deepseek-coder"),
    antigravityCliInstalled: Boolean = true,
    antigravityInstalling: Boolean = false,
    antigravityInstallProgress: Float = 0f,
    antigravityInstallMessage: String? = null,
    antigravityInstallError: String? = null,
    antigravityDownloadedBytes: Long? = null,
    antigravityTotalBytes: Long? = null,
    antigravityModels: List<String> = emptyList(),
    antigravityModelsLoading: Boolean = false,
    antigravityAuth: AntigravityAuthState = AntigravityAuthState(),
    canRemoveAgent: Boolean = false,
    onDiscoverProviderModels: suspend (String) -> List<ModelInfo> = { emptyList() },
    onTestProviderConnection: suspend (String) -> ConnectionTestResult = { ConnectionTestResult(false, message = "") },
    onRefreshAntigravityModels: () -> Unit = {},
    onInstallAntigravityCli: () -> Unit = {},
    onStartAntigravityLogin: () -> Unit = {},
    onSubmitAntigravityCode: (String) -> Unit = {},
    onLogoutAntigravity: () -> Unit = {},
    onSaveConfig: (AgentConfig) -> Unit,
    onRemoveAgent: (AgentSlot) -> Unit = {},
    onReleaseFileLock: (String, AgentSlot) -> Unit,
    onOpenChat: (AgentSlot) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    var name by remember(agentState) { mutableStateOf(agentState.config.name) }
    var selectedRoleType by remember(agentState) { mutableStateOf(agentState.config.role.type) }
    var iconKey by remember(agentState) { mutableStateOf(agentState.config.iconKey) }
    var isEnabled by remember(agentState) { mutableStateOf(agentState.config.isEnabled) }
    var selectedProvider by remember(agentState) { mutableStateOf(agentState.config.providerId) }
    var selectedModel by remember(agentState) { mutableStateOf(agentState.config.modelId) }
    var systemPrompt by remember(agentState) { mutableStateOf(agentState.config.customPrompt.ifBlank { agentState.config.role.systemInstructions }) }
    var employeePersonality by remember(agentState) { mutableStateOf(agentState.config.personality) }
    var temperature by remember(agentState) { mutableFloatStateOf(agentState.config.temperature) }
    var maxTokens by remember(agentState) { mutableIntStateOf(agentState.config.maxTokens) }
    var oauthCode by remember { mutableStateOf("") }

    var roleMenuExpanded by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var modelSearch by remember { mutableStateOf("") }
    var discoveringModels by remember { mutableStateOf(false) }
    var testingApi by remember(selectedProvider) { mutableStateOf(false) }
    var apiTestResult by remember(selectedProvider) { mutableStateOf<ConnectionTestResult?>(null) }
    var liveDiscoveredModels by remember(selectedProvider) { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val selectedProviderConfig = remember(providerConfigs, selectedProvider) {
        providerConfigs.firstOrNull { it.id == selectedProvider }
    }
    val isAntigravityProvider = selectedProvider == "preset-antigravity" || selectedProviderConfig?.type == ProviderType.ANTIGRAVITY

    LaunchedEffect(isAntigravityProvider, antigravityCliInstalled) {
        if (isAntigravityProvider && antigravityCliInstalled && antigravityModels.isEmpty() && !antigravityModelsLoading) {
            onRefreshAntigravityModels()
        }
    }

    val peersSharingSameProvider = remember(allAgents, selectedProvider, agentState.config.slot) {
        allAgents.filter { it.config.slot != agentState.config.slot && it.config.providerId == selectedProvider }
            .map { it.config.name }
    }

    val modelChoices = remember(isAntigravityProvider, antigravityModels, selectedProviderConfig, liveDiscoveredModels) {
        if (isAntigravityProvider) {
            // Strictly show ONLY real models available on the user's actual Antigravity plan (never stale presets!)
            antigravityModels.filter { it.isNotBlank() }.distinct()
        } else {
            (liveDiscoveredModels + selectedProviderConfig?.availableModels.orEmpty())
                .filter { it.isNotBlank() }
                .distinct()
        }
    }

    val filteredModels = remember(modelChoices, modelSearch, isAntigravityProvider) {
        val q = modelSearch.trim()
        if (q.isBlank()) modelChoices
        else modelChoices.filter { id ->
            id.contains(q, ignoreCase = true) ||
                (isAntigravityProvider && formatAntigravityModelName(id).contains(q, ignoreCase = true))
        }
    }

    if (showModelSheet) {
        val realProviderQuota = allProviderQuotas[selectedProvider]?.takeIf { it.isAvailable && it.remainingPercent != null }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showModelSheet = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = { showModelSheet = false },
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.80f)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                if (realProviderQuota != null && realProviderQuota.remainingPercent != null) {
                    val rem = realProviderQuota.remainingPercent.coerceIn(0f, 1f)
                    val qColor = when {
                        rem > 0.25f -> Color(0xFF16A34A)
                        rem > 0.12f -> Color(0xFFD97706)
                        else -> Color(0xFFDC2626)
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = qColor.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, qColor.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.model_picker_real_quota_label), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = qColor)
                                Text(
                                    "${(rem * 100).toInt()}% left${realProviderQuota.resetTime?.let { " · $it" }.orEmpty()}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = qColor,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { rem },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = qColor,
                                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = selectedProviderConfig?.name ?: "Models",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${filteredModels.size} / ${modelChoices.size} models",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            if (isAntigravityProvider) {
                                onRefreshAntigravityModels()
                            } else {
                                discoveringModels = true
                                scope.launch {
                                    val discovered = onDiscoverProviderModels(selectedProvider)
                                    if (discovered.isNotEmpty()) {
                                        liveDiscoveredModels = discovered.map { it.id }
                                    }
                                    discoveringModels = false
                                }
                            }
                        },
                        enabled = !(if (isAntigravityProvider) antigravityModelsLoading else discoveringModels),
                    ) {
                        if (if (isAntigravityProvider) antigravityModelsLoading else discoveringModels) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = PocketOrange)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.provider_discover_models_short))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = modelSearch,
                    onValueChange = { modelSearch = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.st_search_model_or_custom)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(10.dp))
                if (modelSearch.isNotBlank() && filteredModels.none { it.equals(modelSearch.trim(), ignoreCase = true) }) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PocketOrange.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedModel = modelSearch.trim()
                                modelSearch = ""
                                showModelSheet = false
                            },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(modelSearch.trim(), fontWeight = FontWeight.Bold, color = PocketOrange, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(filteredModels, key = { it }) { modelId ->
                        val isSelected = modelId == selectedModel
                        val displayName = if (isAntigravityProvider) formatAntigravityModelName(modelId) else modelId
                        val tier = if (isAntigravityProvider) formatAntigravityModelTier(modelId) else ""
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedModel = modelId
                                    showModelSheet = false
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(displayName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (tier.isNotBlank()) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = PocketOrange.copy(alpha = 0.14f),
                                            ) {
                                                Text(tier, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = PocketOrange, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                            }
                                        }
                                    }
                                    if (displayName != modelId) {
                                        Text(modelId, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
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

    val activity = agentState.activityState.activity
    val activityColor = ActivityVisuals.colorFor(activity)
    val activityIcon = ActivityVisuals.iconFor(activity)
    val heldLocks = remember(fileLocks, agentState.config.slot) {
        fileLocks.filter { it.agentSlot == agentState.config.slot }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(top = 4.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(activityColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(activityIcon, contentDescription = null, tint = activityColor, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = name.ifBlank { agentState.config.slot.defaultName },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = stringResource(R.string.agent_detail_title),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenChat(agentState.config.slot) }) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(R.string.agent_detail_chat_btn))
                    }
                    if (canRemoveAgent) {
                        IconButton(onClick = {
                            onRemoveAgent(agentState.config.slot)
                            onBack()
                        }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.team_remove_agent), tint = MaterialTheme.colorScheme.error)
                        }
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
            // 1. General & Custom Name Card
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
                            Column {
                                Text(
                                    text = agentState.config.slot.defaultName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = stringResource(R.string.agent_detail_enabled),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { isEnabled = it },
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.agent_detail_name)) },
                            supportingText = { Text("Teammates will remember & address this agent by this name", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true,
                        )

                        Spacer(Modifier.height(12.dp))

                        // Role Selector
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedRoleType.defaultTitle,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.agent_detail_role)) },
                                trailingIcon = {
                                    IconButton(onClick = { roleMenuExpanded = true }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            )
                            DropdownMenu(
                                expanded = roleMenuExpanded,
                                onDismissRequest = { roleMenuExpanded = false },
                            ) {
                                AgentRoleType.entries.forEach { role ->
                                    DropdownMenuItem(
                                        text = { Text(role.defaultTitle) },
                                        onClick = {
                                            selectedRoleType = role
                                            if (iconKey.isBlank()) {
                                                iconKey = AgentIconCatalog.defaultKeyForRole(role)
                                            }
                                            roleMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Per-Agent Icon Picker + Role-Appropriate Randomize
                        val activeIconKey = iconKey.ifBlank { AgentIconCatalog.defaultKeyForRole(selectedRoleType) }
                        val agentColor = agentAccentColor(agentState.config.slot)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "ไอคอนประจำตัว AI",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            TextButton(
                                onClick = {
                                    iconKey = AgentIconCatalog.randomKeyForRole(selectedRoleType, activeIconKey)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("สุ่มให้เหมาะกับบทบาท", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(AgentIconCatalog.allOptions, key = { it.key }) { option ->
                                val isSelected = activeIconKey == option.key
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) agentColor.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    border = BorderStroke(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) agentColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    ),
                                    modifier = Modifier.clickable { iconKey = option.key },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Icon(
                                            imageVector = option.icon,
                                            contentDescription = option.label,
                                            tint = if (isSelected) agentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = option.label,
                                            fontSize = 10.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) agentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Provider & Model Configuration + Inline Antigravity OAuth
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
                            text = stringResource(R.string.agent_detail_provider_model),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(12.dp))

                        // Provider Selector Dropdown
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedProviderConfig?.name ?: selectedProvider,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("AI Provider") },
                                trailingIcon = {
                                    IconButton(onClick = { providerMenuExpanded = true }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            )
                            DropdownMenu(
                                expanded = providerMenuExpanded,
                                onDismissRequest = { providerMenuExpanded = false },
                            ) {
                                val options = if (providerConfigs.isNotEmpty()) {
                                    providerConfigs.map { it.id to it.name }
                                } else {
                                    listOf(
                                        "preset-antigravity" to "Antigravity (Google)",
                                        "preset-anthropic" to "Anthropic API",
                                        "preset-gemini" to "Google Gemini API",
                                        "preset-openai" to "OpenAI",
                                        "preset-deepseek" to "DeepSeek API",
                                        "preset-openrouter" to "OpenRouter",
                                    )
                                }
                                options.forEach { (id, title) ->
                                    DropdownMenuItem(
                                        text = { Text(title) },
                                        onClick = {
                                            selectedProvider = id
                                            providerConfigs.firstOrNull { it.id == id }?.selectedModelId?.takeIf { it.isNotBlank() }?.let {
                                                selectedModel = it
                                            }
                                            providerMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        // Inline Antigravity CLI & OAuth Flow when Antigravity is chosen
                        if (isAntigravityProvider) {
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = stringResource(R.string.agent_antigravity_isolated_hint, name.ifBlank { agentState.config.name }),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (!antigravityCliInstalled) {
                                        if (antigravityInstalling) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = antigravityInstallMessage ?: stringResource(R.string.agent_installing_binary),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                            LinearProgressIndicator(
                                                progress = { antigravityInstallProgress.coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                val mbLabel = if (antigravityDownloadedBytes != null || antigravityTotalBytes != null) {
                                                    val dl = (antigravityDownloadedBytes ?: 0L) / (1024f * 1024f)
                                                    val tot = antigravityTotalBytes?.let { " / %.1f MB".format(it / (1024f * 1024f)) }.orEmpty()
                                                    "%.1f MB%s".format(dl, tot)
                                                } else {
                                                    stringResource(R.string.st_processing_files)
                                                }
                                                Text(mbLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text("${(antigravityInstallProgress * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        } else {
                                            Text(
                                                text = stringResource(R.string.agent_antigravity_cli_needed),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                            if (!antigravityInstallError.isNullOrBlank()) {
                                                Text(
                                                    text = antigravityInstallError,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                            Button(
                                                onClick = onInstallAntigravityCli,
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(10.dp),
                                            ) {
                                                Text(stringResource(R.string.agent_antigravity_install_cli))
                                            }
                                        }
                                    } else if (antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color(0xFF22C55E).copy(alpha = 0.14f),
                                            border = BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.45f)),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(18.dp))
                                                    Spacer(Modifier.width(8.dp))
                                                    Column {
                                                        Text(
                                                            text = stringResource(R.string.agent_antigravity_connected),
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF16A34A),
                                                        )
                                                        antigravityAuth.accountEmail?.let { email ->
                                                            Text(email, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                                        }
                                                    }
                                                }
                                                TextButton(onClick = onLogoutAntigravity) {
                                                    Text("Sign Out", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    } else if (antigravityAuth.status == AntigravityAuthStatus.STARTING || antigravityAuth.status == AntigravityAuthStatus.COMPLETING) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color(0xFFF59E0B).copy(alpha = 0.14f),
                                            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.45f)),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.2.dp, color = Color(0xFFD97706))
                                                Text(
                                                    text = antigravityAuth.message?.takeIf { it.isNotBlank() }
                                                        ?: if (antigravityAuth.status == AntigravityAuthStatus.COMPLETING) stringResource(R.string.conn_verifying_code) else stringResource(R.string.conn_status_connecting),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        }
                                    } else {
                                        if (antigravityAuth.status == AntigravityAuthStatus.ERROR && !antigravityAuth.message.isNullOrBlank()) {
                                            Text(
                                                text = antigravityAuth.message,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                        Button(
                                            onClick = onStartAntigravityLogin,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp),
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.agent_antigravity_connect))
                                        }
                                        if (antigravityAuth.status == AntigravityAuthStatus.AWAITING_CODE || antigravityAuth.authorizationUrl != null) {
                                            OutlinedTextField(
                                                value = oauthCode,
                                                onValueChange = { oauthCode = it },
                                                label = { Text("Paste OAuth Code") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(10.dp),
                                            )
                                            Button(
                                                onClick = {
                                                    if (oauthCode.isNotBlank()) {
                                                        onSubmitAntigravityCode(oauthCode.trim())
                                                        oauthCode = ""
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(10.dp),
                                            ) {
                                                Text("Verify & Connect")
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    testingApi = true
                                    apiTestResult = null
                                    scope.launch {
                                        apiTestResult = onTestProviderConnection(selectedProvider)
                                        testingApi = false
                                    }
                                },
                                enabled = !testingApi,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                if (testingApi) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.conn_status_connecting), fontSize = 12.sp)
                                } else {
                                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.conn_test_api_btn), fontSize = 12.sp)
                                }
                            }
                            apiTestResult?.let { res ->
                                Spacer(Modifier.height(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (res.isSuccess) Color(0xFF22C55E).copy(alpha = 0.14f) else MaterialTheme.colorScheme.errorContainer,
                                    border = BorderStroke(1.dp, if (res.isSuccess) Color(0xFF22C55E).copy(alpha = 0.45f) else MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (res.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            tint = if (res.isSuccess) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = if (res.isSuccess) "${stringResource(R.string.conn_status_connected)} · ${res.message}" else res.message,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (res.isSuccess) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }

                        // Shared vs Dedicated Provider ID Hint
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (peersSharingSameProvider.isNotEmpty()) Color(0xFF1976D2).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (peersSharingSameProvider.isNotEmpty()) {
                                    stringResource(R.string.provider_shared_id_hint, peersSharingSameProvider.joinToString(", "))
                                } else {
                                    stringResource(R.string.provider_unique_id_hint)
                                },
                                fontSize = 11.sp,
                                color = if (peersSharingSameProvider.isNotEmpty()) Color(0xFF1976D2) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // Real Model Selector (Modal Bottom Sheet like AI Connect) + Live Refresh Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = if (isAntigravityProvider && selectedModel.isNotBlank()) formatAntigravityModelName(selectedModel) else selectedModel,
                                onValueChange = { if (!isAntigravityProvider) selectedModel = it },
                                readOnly = isAntigravityProvider,
                                label = { Text(stringResource(R.string.provider_select_model_dropdown, modelChoices.size)) },
                                trailingIcon = {
                                    IconButton(onClick = { showModelSheet = true }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { showModelSheet = true },
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true,
                            )
                            val isRefreshingModels = if (isAntigravityProvider) antigravityModelsLoading else discoveringModels
                            FilledTonalIconButton(
                                onClick = {
                                    if (isAntigravityProvider) {
                                        onRefreshAntigravityModels()
                                        showModelSheet = true
                                    } else {
                                        discoveringModels = true
                                        scope.launch {
                                            val discovered = onDiscoverProviderModels(selectedProvider)
                                            if (discovered.isNotEmpty()) {
                                                liveDiscoveredModels = discovered.map { it.id }
                                            }
                                            discoveringModels = false
                                            showModelSheet = true
                                        }
                                    }
                                },
                                enabled = !isRefreshingModels,
                            ) {
                                if (isRefreshingModels) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.provider_discover_models_short))
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = stringResource(R.string.agent_detail_temperature, temperature),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Slider(
                            value = temperature,
                            onValueChange = { temperature = it },
                            valueRange = 0.0f..1.0f,
                            steps = 10,
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.agent_detail_max_tokens, maxTokens),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Slider(
                            value = maxTokens.toFloat(),
                            onValueChange = { maxTokens = it.toInt() },
                            valueRange = 1024f..32768f,
                            steps = 31,
                        )
                    }
                }
            }

            // 3. Lifecycle State Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.agent_detail_lifecycle_state),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = activityColor.copy(alpha = 0.2f),
                            ) {
                                Text(
                                    text = agentState.lifecycleState.name,
                                    color = activityColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(activity.labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        if (agentState.activityState.currentTask.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Current Task: ${agentState.activityState.currentTask}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 4. System Prompt & Templates
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
                            text = stringResource(R.string.agent_detail_system_prompt),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SuggestionChip(
                                onClick = {
                                    systemPrompt = "You are the Team Leader & Architect. Coordinate tasks, review specifications, and ensure code quality across all agents."
                                },
                                label = { Text("Lead", fontSize = 11.sp) },
                            )
                            SuggestionChip(
                                onClick = {
                                    systemPrompt = "You are the Core Developer. Write idiomatic Kotlin and Compose code, follow strict architecture guidelines, and implement domain logic."
                                },
                                label = { Text("Dev", fontSize = 11.sp) },
                            )
                            SuggestionChip(
                                onClick = {
                                    systemPrompt = "You are the DevOps & Test Engineer. Run Gradle test suites, verify builds, manage dependencies, and monitor PRoot execution limits."
                                },
                                label = { Text("DevOps", fontSize = 11.sp) },
                            )
                            SuggestionChip(
                                onClick = {
                                    systemPrompt = "You are the Code Reviewer & QA. Perform static analysis, detect potential security and concurrency flaws, and approve merge requests."
                                },
                                label = { Text("QA", fontSize = 11.sp) },
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = systemPrompt,
                            onValueChange = { systemPrompt = it },
                            placeholder = { Text("Enter custom system instructions for this agent…") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 220.dp),
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                }
            }

            // 4.5 Employee Personality & Character Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.agent_personality_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        val presets = listOf(
                            "" to stringResource(R.string.agent_personality_auto),
                            "สุขุม รอบคอบ คิดเป็นระบบ สั่งงานชัดเจนแบบมืออาชีพ" to "สุขุมรอบคอบ",
                            "กระตือรือร้น เป็นกันเอง เขียนโค้ดไว ชอบช่วยแก้ปัญหาตรงไปตรงมา" to "กระตือรือร้นสายลุย",
                            "เจ้าระเบียบ เป๊ะทุกจุด ตรวจบั๊กละเอียด ไม่ปล่อยงานหลุดมาตรฐาน" to "เจ้าระเบียบเป๊ะ",
                            "สร้างสรรค์ เน้นดีไซน์สวยล้ำสมัย UI/UX ลื่นไหล" to "สร้างสรรค์ดีไซน์",
                        )
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(presets.size) { idx ->
                                val (valText, label) = presets[idx]
                                FilterChip(
                                    selected = employeePersonality == valText,
                                    onClick = { employeePersonality = valText },
                                    label = { Text(label, fontSize = 11.sp) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = employeePersonality,
                            onValueChange = { employeePersonality = it },
                            placeholder = {
                                Text(
                                    text = AgentConfig.defaultEmployeePersonality(agentState.config.slot),
                                    fontSize = 11.sp,
                                )
                            },
                            supportingText = {
                                Text(stringResource(R.string.agent_personality_hint), fontSize = 10.sp)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                }
            }

            // 5. Active Held File Locks
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
                            text = stringResource(R.string.agent_detail_held_locks, heldLocks.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))

                        if (heldLocks.isEmpty()) {
                            Text(
                                text = stringResource(R.string.team_no_file_locks),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            heldLocks.forEach { lock ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = lock.filePath,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = lock.reason,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(
                                        onClick = { onReleaseFileLock(lock.filePath, agentState.config.slot) },
                                    ) {
                                        Text(stringResource(R.string.agent_detail_release_lock), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6. Save Configuration Button
            item {
                Button(
                    onClick = {
                        val updatedConfig = agentState.config.copy(
                            name = name.trim().ifBlank { agentState.config.slot.defaultName },
                            role = agentState.config.role.copy(
                                type = selectedRoleType,
                                title = selectedRoleType.defaultTitle,
                                systemInstructions = systemPrompt,
                            ),
                            modelId = selectedModel.trim().ifBlank { agentState.config.modelId },
                            providerId = selectedProvider,
                            isEnabled = isEnabled,
                            temperature = temperature,
                            maxTokens = maxTokens,
                            customPrompt = systemPrompt,
                            personality = employeePersonality.trim(),
                            iconKey = iconKey.trim(),
                        )
                        onSaveConfig(updatedConfig)
                        onBack()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.agent_detail_save_btn), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
