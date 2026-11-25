package com.jarves.mh.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.ui.theme.OrqenMint
import kotlinx.coroutines.delay

fun generateAutoProjectName(existingNames: Collection<String> = emptyList()): String {
    val prefixes = listOf("orqen", "nova", "mint", "pulse", "spark", "apex", "flux", "orbit")
    val suffixes = listOf("app", "studio", "lab", "core", "hub", "kit", "work", "pro")
    val existingLower = existingNames.mapTo(mutableSetOf()) { it.lowercase() }
    repeat(30) {
        val candidate = "${prefixes.random()}-${suffixes.random()}-${(100..999).random()}"
        if (candidate !in existingLower) return candidate
    }
    return "orqen-project-${System.currentTimeMillis() % 10000}"
}

@Composable
fun SafeDeleteProjectDialog(
    projectName: String,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var typedName by remember(projectName) { mutableStateOf("") }
    var typedDelete by remember(projectName) { mutableStateOf("") }
    var copied by remember(projectName) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500L)
            copied = false
        }
    }

    val nameMatches = typedName.trim() == projectName.trim()
    val deleteMatches = typedDelete.trim().equals("delete", ignoreCase = true)
    val canDelete = nameMatches && deleteMatches

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEF4444)) },
        title = {
            Text(
                text = stringResource(R.string.safe_delete_title),
                fontWeight = FontWeight.Bold,
                color = Color(0xFFEF4444),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.safe_delete_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Copyable exact project name box
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = OrqenMint.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, OrqenMint.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(AnnotatedString(projectName))
                            copied = true
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = projectName,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = OrqenMint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.chat_copy_message),
                            tint = OrqenMint,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(if (copied) R.string.chat_copied_badge else R.string.chat_copy_message),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = OrqenMint,
                        )
                    }
                }

                // Field 1: Paste / Type Exact Project Name
                OutlinedTextField(
                    value = typedName,
                    onValueChange = { typedName = it },
                    label = { Text(stringResource(R.string.safe_delete_name_label), fontSize = 12.sp) },
                    placeholder = { Text(projectName, fontSize = 12.sp) },
                    singleLine = true,
                    isError = typedName.isNotEmpty() && !nameMatches,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Field 2: Type "delete"
                OutlinedTextField(
                    value = typedDelete,
                    onValueChange = { typedDelete = it },
                    label = { Text(stringResource(R.string.safe_delete_word_label), fontSize = 12.sp) },
                    placeholder = { Text("delete", fontSize = 12.sp) },
                    singleLine = true,
                    isError = typedDelete.isNotEmpty() && !deleteMatches,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmDelete,
                enabled = canDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFDC2626),
                    contentColor = Color.White,
                ),
            ) {
                Text(stringResource(R.string.action_delete), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
