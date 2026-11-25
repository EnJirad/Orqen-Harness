package com.jarves.mh.multiagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
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
import com.jarves.mh.provider.core.ModelInfo
import com.jarves.mh.provider.core.ProviderConfig
import com.jarves.mh.provider.core.ProviderType
import com.jarves.mh.runtime.AntigravityAuthState
import com.jarves.mh.runtime.AntigravityAuthStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDetailScreen(
    agentState: AgentState,
    allAgents: List<AgentState> = emptyList(),
    fileLocks: List<FileLock>,
    providerConfigs: List<ProviderConfig> = emptyList(),
    availableModels: List<String> = listOf("gemini-3.8-flash-high", "gemini-3.8-flash", "claude-sonnet-4-6", "claude-haiku-3-5", "gpt-4o", "gpt-4o-mini", "deepseek-coder"),
    antigravityCliInstalled: Boolean = true,
    antigravityInstalling: Boolean = false,
    antigravityAuth: AntigravityAuthState = AntigravityAuthState(),
    canRemoveAgent: Boolean = false,
    onDiscoverProviderModels: suspend (String) -> List<ModelInfo> = { emptyList() },
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
    var isEnabled by remember(agentState) { mutableStateOf(agentState.config.isEnabled) }
    var selectedProvider by remember(agentState) { mutableStateOf(agentState.config.providerId) }
    var selectedModel by remember(agentState) { mutableStateOf(agentState.config.modelId) }
    var systemPrompt by remember(agentState) { mutableStateOf(agentState.config.customPrompt.ifBlank { agentState.config.role.systemInstructions }) }
    var temperature by remember(agentState) { mutableFloatStateOf(agentState.config.temperature) }
    var maxTokens by remember(agentState) { mutableIntStateOf(agentState.config.maxTokens) }
    var oauthCode by remember { mutableStateOf("") }

    var roleMenuExpanded by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var discoveringModels by remember { mutableStateOf(false) }
    var liveDiscoveredModels by remember(selectedProvider) { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val selectedProviderConfig = remember(providerConfigs, selectedProvider) {
        providerConfigs.firstOrNull { it.id == selectedProvider }
    }
    val isAntigravityProvider = selectedProvider == "preset-antigravity" || selectedProviderConfig?.type == ProviderType.ANTIGRAVITY

    val peersSharingSameProvider = remember(allAgents, selectedProvider, agentState.config.slot) {
        allAgents.filter { it.config.slot != agentState.config.slot && it.config.providerId == selectedProvider }
            .map { it.config.name }
    }

    val modelChoices = remember(selectedProviderConfig, liveDiscoveredModels, availableModels) {
        val fromLive = liveDiscoveredModels
        val fromProvider = selectedProviderConfig?.availableModels.orEmpty()
        (fromLive + fromProvider + availableModels).filter { it.isNotBlank() }.distinct()
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
                                            roleMenuExpanded = false
                                        },
                                    )
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
                                    if (!antigravityCliInstalled) {
                                        Text(
                                            text = stringResource(R.string.agent_antigravity_cli_needed),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                        Button(
                                            onClick = onInstallAntigravityCli,
                                            enabled = !antigravityInstalling,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp),
                                        ) {
                                            if (antigravityInstalling) {
                                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                                Spacer(Modifier.width(8.dp))
                                            }
                                            Text(stringResource(R.string.agent_antigravity_install_cli))
                                        }
                                    } else if (antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    text = stringResource(R.string.agent_antigravity_connected),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF2E7D32),
                                                )
                                                antigravityAuth.accountEmail?.let { email ->
                                                    Text(email, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            TextButton(onClick = onLogoutAntigravity) {
                                                Text("Sign Out", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                            }
                                        }
                                    } else {
                                        Button(
                                            onClick = onStartAntigravityLogin,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp),
                                        ) {
                                            Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(16.dp))
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

                        // Model Selector Dropdown + Live Discover Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = selectedModel,
                                    onValueChange = { selectedModel = it },
                                    label = { Text(stringResource(R.string.provider_select_model_dropdown, modelChoices.size)) },
                                    trailingIcon = {
                                        IconButton(onClick = { modelMenuExpanded = true }) {
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    singleLine = true,
                                )
                                DropdownMenu(
                                    expanded = modelMenuExpanded,
                                    onDismissRequest = { modelMenuExpanded = false },
                                ) {
                                    modelChoices.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model) },
                                            onClick = {
                                                selectedModel = model
                                                modelMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                            FilledTonalIconButton(
                                onClick = {
                                    discoveringModels = true
                                    scope.launch {
                                        val discovered = onDiscoverProviderModels(selectedProvider)
                                        if (discovered.isNotEmpty()) {
                                            liveDiscoveredModels = discovered.map { it.id }
                                        }
                                        discoveringModels = false
                                        modelMenuExpanded = true
                                    }
                                },
                                enabled = !discoveringModels,
                            ) {
                                if (discoveringModels) {
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
