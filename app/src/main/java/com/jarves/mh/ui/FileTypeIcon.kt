package com.jarves.mh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.IntegrationInstructions
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarves.mh.ui.theme.OrqenMint

data class FileIconSpec(
    val icon: ImageVector,
    val tint: Color,
    val badgeBg: Color,
)

fun resolveFileIconSpec(fileName: String, isDirectory: Boolean, isExpanded: Boolean = false): FileIconSpec {
    if (isDirectory) {
        val gold = Color(0xFFF59E0B)
        return FileIconSpec(
            icon = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
            tint = gold,
            badgeBg = gold.copy(alpha = 0.14f),
        )
    }
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "html", "htm" -> Color(0xFFF97316).let { FileIconSpec(Icons.Default.Language, it, it.copy(alpha = 0.14f)) }
        "css", "scss", "sass", "less" -> Color(0xFF38BDF8).let { FileIconSpec(Icons.Default.Palette, it, it.copy(alpha = 0.14f)) }
        "js", "mjs", "cjs", "jsx" -> Color(0xFFFACC15).let { FileIconSpec(Icons.Default.Code, it, it.copy(alpha = 0.14f)) }
        "ts", "tsx" -> Color(0xFF3B82F6).let { FileIconSpec(Icons.Default.Code, it, it.copy(alpha = 0.14f)) }
        "kt", "kts" -> Color(0xFFA855F7).let { FileIconSpec(Icons.Default.Code, it, it.copy(alpha = 0.14f)) }
        "java" -> Color(0xFFEA580C).let { FileIconSpec(Icons.Default.Code, it, it.copy(alpha = 0.14f)) }
        "json", "yaml", "yml", "toml", "properties", "env" -> OrqenMint.let { FileIconSpec(Icons.Default.DataObject, it, it.copy(alpha = 0.14f)) }
        "xml", "gradle" -> Color(0xFF2DD4BF).let { FileIconSpec(Icons.Default.IntegrationInstructions, it, it.copy(alpha = 0.14f)) }
        "py" -> Color(0xFF10B981).let { FileIconSpec(Icons.Default.Terminal, it, it.copy(alpha = 0.14f)) }
        "sh", "bash", "zsh", "bat", "ps1" -> Color(0xFF84CC16).let { FileIconSpec(Icons.Default.Terminal, it, it.copy(alpha = 0.14f)) }
        "md", "txt", "log", "csv" -> Color(0xFF94A3B8).let { FileIconSpec(Icons.Default.Article, it, it.copy(alpha = 0.14f)) }
        "png", "jpg", "jpeg", "webp", "svg", "gif", "ico", "bmp" -> Color(0xFFEC4899).let { FileIconSpec(Icons.Default.Image, it, it.copy(alpha = 0.14f)) }
        "ttf", "otf", "woff", "woff2" -> Color(0xFF818CF8).let { FileIconSpec(Icons.Default.TextFields, it, it.copy(alpha = 0.14f)) }
        "apk", "aab" -> Color(0xFF22C55E).let { FileIconSpec(Icons.Default.Android, it, it.copy(alpha = 0.14f)) }
        "zip", "tar", "gz", "tgz", "rar", "7z", "zst" -> Color(0xFFD97706).let { FileIconSpec(Icons.Default.FolderZip, it, it.copy(alpha = 0.14f)) }
        "sql", "db", "sqlite" -> Color(0xFF06B6D4).let { FileIconSpec(Icons.Default.Storage, it, it.copy(alpha = 0.14f)) }
        else -> OrqenMint.let { FileIconSpec(Icons.Default.Description, it, it.copy(alpha = 0.12f)) }
    }
}

@Composable
fun FileTypeIcon(
    fileName: String,
    isDirectory: Boolean,
    isExpanded: Boolean = false,
    size: Dp = 20.dp,
    showBadge: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val spec = resolveFileIconSpec(fileName, isDirectory, isExpanded)
    if (showBadge) {
        Box(
            modifier = modifier
                .size(size + 10.dp)
                .background(spec.badgeBg, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                tint = spec.tint,
                modifier = Modifier.size(size),
            )
        }
    } else {
        Icon(
            imageVector = spec.icon,
            contentDescription = null,
            tint = spec.tint,
            modifier = modifier.size(size),
        )
    }
}

/**
 * IDE-style vertical tree connector guide lines (│ and ├─) for nested folders & files.
 */
@Composable
fun FileTreeIndentGuides(
    depth: Int,
    rowHeight: Dp = 44.dp,
    stepWidth: Dp = 18.dp,
    lineColor: Color = OrqenMint.copy(alpha = 0.38f),
    branchColor: Color = OrqenMint.copy(alpha = 0.65f),
) {
    if (depth <= 0) return
    Row {
        repeat(depth) { level ->
            val isLastLevel = level == depth - 1
            Box(
                modifier = Modifier
                    .width(stepWidth)
                    .height(rowHeight)
                    .drawBehind {
                        val centerX = size.width * 0.45f
                        val midY = size.height * 0.5f
                        val stroke = 1.4.dp.toPx()
                        // Vertical guide line
                        drawLine(
                            color = lineColor,
                            start = Offset(centerX, 0f),
                            end = Offset(centerX, size.height),
                            strokeWidth = stroke,
                        )
                        // Horizontal branch connector pointing to the child folder/file
                        if (isLastLevel) {
                            drawLine(
                                color = branchColor,
                                start = Offset(centerX, midY),
                                end = Offset(size.width, midY),
                                strokeWidth = stroke,
                            )
                        }
                    },
            )
        }
    }
}
