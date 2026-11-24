package com.jarves.mh.multiagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.provider.core.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderManagementScreen(
    configs: List<ProviderConfig>,
    onSaveConfig: (ProviderConfig, String?) -> Unit,
    onDeleteConfig: (String) -> Unit,
    onTestConnection: suspend (String) -> ConnectionTestResult,
    onDiscoverModels: suspend (String) -> List<ModelInfo>,
    getSavedApiKey: (String) -> String,
    onBack: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedForTest by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
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
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Provider")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(configs) { config ->
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
                            if (config.type.supportsOAuth) {
                                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF1976D2).copy(alpha = 0.15f)) {
                                    Text("OAuth", color = Color(0xFF1976D2), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                }
                            }
                        }

                        if (config.baseUrl.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("URL: ${config.baseUrl}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        if (config.selectedModelId.isNotBlank()) {
                            Text("Model: ${config.selectedModelId}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    selectedForTest = config.id
                                    isTesting = true
                                    testResult = null
                                    scope.launch {
                                        val res = onTestConnection(config.id)
                                        testResult = res
                                        isTesting = false
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.provider_connection_test_btn), fontSize = 12.sp)
                            }

                            if (!config.id.startsWith("preset-")) {
                                IconButton(onClick = { onDeleteConfig(config.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
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
            },
        )
    }
}

@Composable
fun AddProviderDialog(
    onDismiss: () -> Unit,
    onSave: (ProviderConfig, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ProviderType.CUSTOM_API) }
    var baseUrl by remember { mutableStateOf("https://api.openai.com/v1") }
    var apiKey by remember { mutableStateOf("") }
    var selectedProtocol by remember { mutableStateOf(ApiProtocol.OPENAI_CHAT) }
    var modelId by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.provider_add_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Provider Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Model ID") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        val config = ProviderConfig(
                            name = name,
                            type = selectedType,
                            baseUrl = baseUrl,
                            protocol = selectedProtocol,
                            selectedModelId = modelId,
                        )
                        onSave(config, apiKey)
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
