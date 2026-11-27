package com.jarves.mh.multiagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.provider.core.*
import com.jarves.mh.runtime.AntigravityAuthState
import com.jarves.mh.runtime.AntigravityAuthStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderManagementScreen(
    configs: List<ProviderConfig>,
    antigravityCliInstalled: Boolean = true,
    antigravityInstalling: Boolean = false,
    antigravityInstallProgress: Float = 0f,
    antigravityInstallMessage: String? = null,
    antigravityInstallError: String? = null,
    antigravityDownloadedBytes: Long? = null,
    antigravityTotalBytes: Long? = null,
    antigravityAuth: AntigravityAuthState = AntigravityAuthState(),
    onInstallAntigravityCli: () -> Unit = {},
    onStartAntigravityLogin: () -> Unit = {},
    onSubmitAntigravityCode: (String) -> Unit = {},
    onLogoutAntigravity: () -> Unit = {},
    onSaveConfig: (ProviderConfig, String?) -> Unit,
    onDeleteConfig: (String) -> Unit,
    onTestConnection: suspend (String) -> ConnectionTestResult,
    onDiscoverModels: suspend (String) -> List<ModelInfo>,
    getSavedApiKey: (String) -> String,
    embedded: Boolean = false,
    onBack: () -> Unit,
) {
    if (!embedded) BackHandler(onBack = onBack)

    var showAddDialog by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<ProviderConfig?>(null) }
    var oauthCode by remember { mutableStateOf("") }
    var selectedForTest by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var discoveringConfigId by remember { mutableStateOf<String?>(null) }
    var discoveredMessage by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (!embedded) {
                TopAppBar(
                    modifier = Modifier.padding(top = 4.dp),
                    title = {
                        Column {
                            Text(stringResource(R.string.provider_mgmt_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.provider_mgmt_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.provider_add_new)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.provider_add_another_id_hint),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            items(configs, key = { it.id }) { config ->
                var modelDropdownExpanded by remember(config.id) { mutableStateOf(false) }
                val hasKey = remember(config.id, editingConfig) { getSavedApiKey(config.id).isNotBlank() }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(config.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(config.type.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (config.type.supportsOAuth) {
                                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF1976D2).copy(alpha = 0.15f)) {
                                        Text("OAuth", color = Color(0xFF1976D2), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (hasKey) Color(0xFF2E7D32).copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        Text(
                                            text = if (hasKey) "KEY SET" else "NO KEY",
                                            color = if (hasKey) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                if (!config.id.startsWith("preset-")) {
                                    IconButton(onClick = { onDeleteConfig(config.id) }, modifier = Modifier.size(30.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        if (config.baseUrl.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("URL: ${config.baseUrl}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        // Antigravity inline CLI & OAuth flow
                        if (config.type == ProviderType.ANTIGRAVITY) {
                            Spacer(Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!antigravityCliInstalled) {
                                        if (antigravityInstalling) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = antigravityInstallMessage ?: stringResource(R.string.agent_installing_binary),
                                                    fontSize = 11.sp,
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
                                                Text(mbLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text("${(antigravityInstallProgress * 100).toInt()}%", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        } else {
                                            Text(
                                                text = stringResource(R.string.agent_antigravity_cli_needed),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.SemiBold,
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
                                                Text(stringResource(R.string.agent_antigravity_install_cli), fontSize = 12.sp)
                                            }
                                        }
                                    } else if (antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = "${stringResource(R.string.agent_antigravity_connected)} ${antigravityAuth.accountEmail?.let { "($it)" } ?: ""}",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF16A34A),
                                                )
                                            }
                                            TextButton(onClick = onLogoutAntigravity) {
                                                Text("Sign Out", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    } else if (antigravityAuth.status == AntigravityAuthStatus.STARTING || antigravityAuth.status == AntigravityAuthStatus.COMPLETING) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.padding(vertical = 4.dp),
                                        ) {
                                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFD97706))
                                            Text(
                                                text = antigravityAuth.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.conn_status_connecting),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    } else {
                                        if (antigravityAuth.status == AntigravityAuthStatus.ERROR && !antigravityAuth.message.isNullOrBlank()) {
                                            Text(antigravityAuth.message, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                        }
                                        Button(
                                            onClick = onStartAntigravityLogin,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp),
                                        ) {
                                            Text(stringResource(R.string.agent_antigravity_connect), fontSize = 12.sp)
                                        }
                                        if (antigravityAuth.status == AntigravityAuthStatus.AWAITING_CODE || antigravityAuth.authorizationUrl != null) {
                                            OutlinedTextField(
                                                value = oauthCode,
                                                onValueChange = { oauthCode = it },
                                                label = { Text("OAuth Code") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Button(
                                                onClick = {
                                                    if (oauthCode.isNotBlank()) {
                                                        onSubmitAntigravityCode(oauthCode.trim())
                                                        oauthCode = ""
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Text("Submit Code")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Live Available Models Dropdown Selector
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = config.selectedModelId.ifBlank { config.availableModels.firstOrNull().orEmpty() },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.provider_select_model_dropdown, config.availableModels.size)) },
                                trailingIcon = {
                                    IconButton(onClick = { modelDropdownExpanded = true }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Model")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true,
                            )
                            DropdownMenu(
                                expanded = modelDropdownExpanded,
                                onDismissRequest = { modelDropdownExpanded = false },
                            ) {
                                val modelsToShow = config.availableModels.ifEmpty { listOf(config.selectedModelId).filter { it.isNotBlank() } }
                                modelsToShow.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m) },
                                        onClick = {
                                            onSaveConfig(config.copy(selectedModelId = m), null)
                                            modelDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Action Buttons: Configure Key / Test & Auto-Discover / Discover Models
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!config.type.supportsOAuth) {
                                OutlinedButton(
                                    onClick = { editingConfig = config },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.Key, contentDescription = null, Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.provider_edit_key_btn), fontSize = 11.sp, maxLines = 1)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    selectedForTest = config.id
                                    isTesting = true
                                    testResult = null
                                    scope.launch {
                                        val res = onTestConnection(config.id)
                                        testResult = res
                                        isTesting = false
                                        if (res.isSuccess) {
                                            discoveringConfigId = config.id
                                            val discovered = onDiscoverModels(config.id)
                                            discoveredMessage = config.id to "Loaded ${discovered.size} models"
                                            discoveringConfigId = null
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = null, Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.provider_connection_test_btn), fontSize = 11.sp, maxLines = 1)
                            }

                            FilledTonalButton(
                                onClick = {
                                    discoveringConfigId = config.id
                                    scope.launch {
                                        val discovered = onDiscoverModels(config.id)
                                        discoveredMessage = config.id to "Loaded ${discovered.size} models"
                                        discoveringConfigId = null
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.provider_discover_models_short), fontSize = 11.sp, maxLines = 1)
                            }
                        }

                        val currentDiscovered = discoveredMessage
                        if (discoveringConfigId == config.id) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else if (currentDiscovered != null && currentDiscovered.first == config.id) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = currentDiscovered.second,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        // Test result feedback
                        if (selectedForTest == config.id) {
                            Spacer(Modifier.height(8.dp))
                            if (isTesting) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else testResult?.let { res ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (res.isSuccess) Color(0xFF2E7D32).copy(alpha = 0.1f) else MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = if (res.isSuccess) res.message else "Failed: ${res.message}",
                                        fontSize = 11.sp,
                                        color = if (res.isSuccess) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
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

    if (showAddDialog) {
        AddProviderDialog(
            onDismiss = { showAddDialog = false },
            onSave = { config, key ->
                onSaveConfig(config, key)
                showAddDialog = false
                scope.launch {
                    onDiscoverModels(config.id)
                }
            },
        )
    }

    editingConfig?.let { target ->
        EditProviderKeyDialog(
            config = target,
            initialKey = getSavedApiKey(target.id),
            onDismiss = { editingConfig = null },
            onSave = { updatedConfig, newKey ->
                onSaveConfig(updatedConfig, newKey)
                editingConfig = null
                scope.launch {
                    discoveringConfigId = updatedConfig.id
                    val discovered = onDiscoverModels(updatedConfig.id)
                    discoveredMessage = updatedConfig.id to "Saved key & loaded ${discovered.size} models"
                    discoveringConfigId = null
                }
            },
        )
    }
}

@Composable
private fun EditProviderKeyDialog(
    config: ProviderConfig,
    initialKey: String,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig, String) -> Unit,
) {
    var name by remember(config) { mutableStateOf(config.name) }
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config, initialKey) { mutableStateOf(initialKey) }
    var showApiKey by remember(config) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${stringResource(R.string.provider_edit_key_btn)} — ${config.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account / ID Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showApiKey) "Hide API Key" else "Show API Key",
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        config.copy(name = name.trim().ifBlank { config.name }, baseUrl = baseUrl.trim()),
                        apiKey.trim(),
                    )
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
fun AddProviderDialog(
    onDismiss: () -> Unit,
    onSave: (ProviderConfig, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ProviderType.OPENAI_COMPATIBLE) }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf("https://api.openai.com/v1") }
    var apiKey by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var selectedProtocol by remember { mutableStateOf(ApiProtocol.OPENAI_CHAT) }
    var modelId by remember { mutableStateOf("") }

    val quickPresets = remember {
        listOf(
            Triple("OpenCode Go", ProviderType.OPENAI_COMPATIBLE, "https://opencode.ai/zen/go/v1"),
            Triple("OpenAI", ProviderType.OPENAI_COMPATIBLE, "https://api.openai.com/v1"),
            Triple("OpenRouter", ProviderType.OPENROUTER, "https://openrouter.ai/api/v1"),
            Triple("DeepSeek", ProviderType.DEEPSEEK, "https://api.deepseek.com/v1"),
            Triple("Gemini", ProviderType.GEMINI, "https://generativelanguage.googleapis.com"),
            Triple("Anthropic", ProviderType.ANTHROPIC, "https://api.anthropic.com"),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.provider_add_new)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Quick Presets", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(quickPresets) { (presetLabel, presetType, presetUrl) ->
                        FilterChip(
                            selected = baseUrl.equals(presetUrl, ignoreCase = true),
                            onClick = {
                                name = presetLabel
                                selectedType = presetType
                                baseUrl = presetUrl
                                selectedProtocol = when (presetType) {
                                    ProviderType.ANTHROPIC -> ApiProtocol.ANTHROPIC_MESSAGES
                                    ProviderType.GEMINI -> ApiProtocol.GEMINI_DEV_API
                                    else -> ApiProtocol.OPENAI_CHAT
                                }
                            },
                            label = { Text(presetLabel, fontSize = 11.sp) },
                        )
                    }
                }
                // Provider Type Selector
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedType.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.provider_type_select)) },
                        trailingIcon = {
                            IconButton(onClick = { typeMenuExpanded = true }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false },
                    ) {
                        ProviderType.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.displayName) },
                                onClick = {
                                    selectedType = t
                                    typeMenuExpanded = false
                                    when (t) {
                                        ProviderType.OPENAI_COMPATIBLE -> {
                                            baseUrl = "https://api.openai.com/v1"
                                            selectedProtocol = ApiProtocol.OPENAI_CHAT
                                        }
                                        ProviderType.ANTHROPIC -> {
                                            baseUrl = "https://api.anthropic.com"
                                            selectedProtocol = ApiProtocol.ANTHROPIC_MESSAGES
                                        }
                                        ProviderType.GEMINI -> {
                                            baseUrl = "https://generativelanguage.googleapis.com"
                                            selectedProtocol = ApiProtocol.GEMINI_DEV_API
                                        }
                                        ProviderType.DEEPSEEK -> {
                                            baseUrl = "https://api.deepseek.com/v1"
                                            selectedProtocol = ApiProtocol.OPENAI_CHAT
                                        }
                                        ProviderType.OPENROUTER -> {
                                            baseUrl = "https://openrouter.ai/api/v1"
                                            selectedProtocol = ApiProtocol.OPENAI_CHAT
                                        }
                                        ProviderType.ANTIGRAVITY -> {
                                            baseUrl = ""
                                            selectedProtocol = ApiProtocol.ANTIGRAVITY_OAUTH
                                        }
                                        ProviderType.CUSTOM_API -> {
                                            selectedProtocol = ApiProtocol.OPENAI_CHAT
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account / ID Name (e.g. OpenAI #2)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showApiKey) "Hide API Key" else "Show API Key",
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Default Model ID (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        val config = ProviderConfig(
                            name = name.trim(),
                            type = selectedType,
                            baseUrl = baseUrl.trim(),
                            protocol = selectedProtocol,
                            selectedModelId = modelId.trim(),
                            availableModels = listOfNotNull(modelId.trim().takeIf { it.isNotBlank() }),
                        )
                        onSave(config, apiKey.trim())
                    }
                },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
