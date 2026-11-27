package com.jarves.mh.skills

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillManagementScreen(
    skillManager: SkillManager,
    activeWorkspaceDir: File?,
    activeProjectName: String?,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val autoEnabled by skillManager.autoSelectEnabled.collectAsStateWithLifecycle()
    val skills by skillManager.skills.collectAsStateWithLifecycle()

    var scanVersion by remember { mutableIntStateOf(0) }
    val resolvedPreview = remember(skills, autoEnabled, activeWorkspaceDir, scanVersion) {
        skillManager.resolveSkillsForWorkspace(activeWorkspaceDir)
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<AgentSkill?>(null) }
    var inspectingSkill by remember { mutableStateOf<AgentSkill?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val importSkillLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (text.isNotBlank()) {
                    val imported = skillManager.importSkillFromMarkdown(text, "Imported Skill")
                    statusMessage = "นำเข้าสกิล '${imported.name}' เรียบร้อยแล้ว"
                }
            }.onFailure {
                statusMessage = "ไม่สามารถอ่านไฟล์สกิลได้: ${it.message}"
            }
        }
    }

    if (showCreateDialog || editingSkill != null) {
        SkillEditorDialog(
            initial = editingSkill,
            onDismiss = {
                showCreateDialog = false
                editingSkill = null
            },
            onSave = { name, desc, cat, tags, instructions ->
                val saved = skillManager.saveCustomSkill(
                    name = name,
                    description = desc,
                    category = cat,
                    tags = tags,
                    instructions = instructions,
                    existingId = editingSkill?.id,
                )
                statusMessage = "บันทึกสกิล '${saved.name}' แล้ว"
                showCreateDialog = false
                editingSkill = null
            },
        )
    }

    inspectingSkill?.let { skill ->
        AlertDialog(
            onDismissRequest = { inspectingSkill = null },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(skill.name, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${skill.category.label} · ${if (skill.isBuiltIn) "Built-in" else "Custom"}",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    if (skill.description.isNotBlank()) {
                        Text(skill.description, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                    ) {
                        Text(
                            text = skill.instructions,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { inspectingSkill = null }) { Text("ปิด") }
            },
            dismissButton = {
                if (!skill.isBuiltIn) {
                    TextButton(onClick = {
                        editingSkill = skill
                        inspectingSkill = null
                    }) { Text("แก้ไข") }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Skills", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = "ใช้ร่วมกันทั้ง AI Team และ Solo Studio",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (statusMessage != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(statusMessage.orEmpty(), fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { statusMessage = null }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // Auto-Skill Mode Card
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        if (autoEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("เลือกสกิลอัตโนมัติ (Auto-Skill)", fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
                                Text(
                                    text = if (autoEnabled) {
                                        "AI จะตรวจสอบไฟล์ในโปรเจกต์แล้วเลือกสกิลที่เหมาะสมให้เองอัตโนมัติ"
                                    } else {
                                        "โหมดเลือกเอง — AI จะใช้เฉพาะสกิลที่คุณเปิดสวิตช์ไว้ด้านล่าง"
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = autoEnabled,
                                onCheckedChange = { skillManager.setAutoSelectEnabled(it) },
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "โปรเจกต์ปัจจุบัน: ${activeProjectName ?: "ยังไม่ได้เปิดโปรเจกต์"}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = if (resolvedPreview.skills.isEmpty()) {
                                        "ยังไม่มีสกิลที่เปิดใช้งาน"
                                    } else {
                                        "สกิลที่จะถูกดึงไปใช้: " + resolvedPreview.skills.joinToString(", ") { it.name }
                                    },
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { scanVersion++ }) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("สแกนใหม่", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Add / Import Skill Action Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("สร้างสกิลใหม่", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { importSkillLauncher.launch("*/*") },
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("นำเข้า SKILL.md", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            item {
                Text(
                    text = "คลังสกิลทั้งหมด (${skills.size})",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            items(skills, key = { it.id }) { skill ->
                val isSelectedByAuto = autoEnabled && resolvedPreview.skills.any { it.id == skill.id }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        if (isSelectedByAuto) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { inspectingSkill = skill },
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = skill.name,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Text(
                                        text = skill.category.label,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                if (isSelectedByAuto) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                    ) {
                                        Text(
                                            text = "Auto",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = skill.description,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!skill.isBuiltIn) {
                            IconButton(onClick = { skillManager.deleteCustomSkill(skill.id) }) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Delete Skill",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Switch(
                            checked = skill.isEnabled,
                            onCheckedChange = { skillManager.toggleSkill(skill.id, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillEditorDialog(
    initial: AgentSkill?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, category: SkillCategory, tags: List<String>, instructions: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var category by remember { mutableStateOf(initial?.category ?: SkillCategory.GENERAL) }
    var tagsText by remember { mutableStateOf(initial?.tags?.joinToString(", ").orEmpty()) }
    var instructions by remember { mutableStateOf(initial?.instructions.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(if (initial == null) "สร้างสกิลใหม่" else "แก้ไขสกิล", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("ชื่อสกิล") },
                    placeholder = { Text("เช่น Flutter Clean Architecture") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("คำอธิบายสั้นๆ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(SkillCategory.entries) { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.label, fontSize = 11.sp) },
                        )
                    }
                }
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("คีย์เวิร์ดสำหรับ Auto-Select (คั่นด้วยจุลภาค)") },
                    placeholder = { Text("flutter, dart, mobile") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("คำสั่ง / แนวทางของสกิล (Instructions)") },
                    placeholder = { Text("ระบุกฎ แนวทางการเขียนโค้ด หรือขั้นตอนที่ต้องการให้ AI ทำตาม...") },
                    minLines = 4,
                    maxLines = 7,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedTags = tagsText.split(',').map { it.trim() }.filter { it.isNotBlank() }
                    onSave(name, description, category, parsedTags, instructions)
                },
                enabled = name.isNotBlank() && instructions.isNotBlank(),
            ) { Text("บันทึก") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ยกเลิก") }
        },
    )
}
