package com.jarves.mh.multiagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun detectFileLanguageBadge(fileName: String): String {
    val lower = fileName.lowercase()
    return when {
        lower.endsWith(".kt") || lower.endsWith(".kts") -> "KOTLIN"
        lower.endsWith(".java") -> "JAVA"
        lower.endsWith(".py") -> "PYTHON"
        lower.endsWith(".ts") || lower.endsWith(".tsx") -> "TYPESCRIPT"
        lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs") -> "JAVASCRIPT"
        lower.endsWith(".html") || lower.endsWith(".htm") -> "HTML"
        lower.endsWith(".css") || lower.endsWith(".scss") -> "CSS"
        lower.endsWith(".json") -> "JSON"
        lower.endsWith(".xml") -> "XML"
        lower.endsWith(".md") || lower.endsWith(".markdown") -> "MARKDOWN"
        lower.endsWith(".sh") || lower.endsWith(".bash") -> "SHELL"
        lower.endsWith(".gradle") -> "GRADLE"
        lower.endsWith(".yml") || lower.endsWith(".yaml") -> "YAML"
        lower.endsWith(".sql") -> "SQL"
        lower.endsWith(".c") || lower.endsWith(".h") -> "C"
        lower.endsWith(".cpp") || lower.endsWith(".hpp") -> "C++"
        lower.endsWith(".go") -> "GO"
        lower.endsWith(".rs") -> "RUST"
        lower.endsWith(".properties") || lower.endsWith(".toml") -> "CONFIG"
        else -> "TEXT"
    }
}

fun isMarkdownFileName(fileName: String): Boolean {
    val lower = fileName.lowercase()
    return lower.endsWith(".md") || lower.endsWith(".markdown")
}

private val CODE_BG = Color(0xFF0A0F1C)
private val CODE_GUTTER_BG = Color(0xFF0D1426)
private val CODE_TEXT = Color(0xFFE2E8F0)
private val CODE_KEYWORD = Color(0xFFC084FC)
private val CODE_STRING = Color(0xFF34D399)
private val CODE_COMMENT = Color(0xFF64748B)
private val CODE_NUMBER = Color(0xFFFBBF24)
private val CODE_TAG_ANNO = Color(0xFF38BDF8)
private val CODE_TYPE = Color(0xFF60A5FA)

private val COMMON_KEYWORDS = setOf(
    "package", "import", "class", "interface", "object", "fun", "val", "var", "return", "if", "else",
    "when", "for", "while", "do", "try", "catch", "finally", "throw", "private", "public", "protected",
    "internal", "override", "open", "abstract", "sealed", "data", "enum", "companion", "suspend",
    "inline", "lateinit", "by", "true", "false", "null", "this", "super", "in", "is", "as",
    "def", "from", "elif", "pass", "break", "continue", "lambda", "with", "yield", "async", "await",
    "None", "True", "False", "and", "or", "not", "self",
    "function", "const", "let", "export", "default", "new", "typeof", "void", "switch", "case",
    "static", "final", "extends", "implements", "throws", "boolean", "int", "long", "float", "double",
    "select", "where", "insert", "update", "delete", "create", "table", "into", "values",
)

private val TOKEN_REGEX = Regex(
    """(//.*$|#.*$|/\*.*?\*/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|@[A-Za-z_][A-Za-z0-9_]*|</?[A-Za-z_][A-Za-z0-9_.:-]*|\b\d+(?:\.\d+)?[fFlL]?\b|\b[A-Z][A-Za-z0-9_]*\b|\b[a-z_][A-Za-z0-9_]*\b)""",
)

private fun highlightCodeLine(line: String, lang: String): AnnotatedString = buildAnnotatedString {
    if (line.isEmpty()) return@buildAnnotatedString
    var cursor = 0
    val isHashCommentLang = lang in setOf("PYTHON", "SHELL", "YAML", "CONFIG")
    for (m in TOKEN_REGEX.findAll(line)) {
        val start = m.range.first
        val end = m.range.last + 1
        if (start > cursor) {
            withStyle(SpanStyle(color = CODE_TEXT)) { append(line.substring(cursor, start)) }
        }
        val tok = m.value
        val style = when {
            tok.startsWith("//") || tok.startsWith("/*") || (isHashCommentLang && tok.startsWith("#")) ->
                SpanStyle(color = CODE_COMMENT, fontStyle = FontStyle.Italic)
            tok.startsWith("\"") || tok.startsWith("'") -> {
                val after = line.substring(end).trimStart()
                if (lang == "JSON" && after.startsWith(":")) {
                    SpanStyle(color = CODE_TAG_ANNO, fontWeight = FontWeight.Medium)
                } else {
                    SpanStyle(color = CODE_STRING)
                }
            }
            tok.startsWith("@") || tok.startsWith("<") ->
                SpanStyle(color = CODE_TAG_ANNO, fontWeight = FontWeight.Medium)
            tok[0].isDigit() ->
                SpanStyle(color = CODE_NUMBER)
            tok in COMMON_KEYWORDS ->
                SpanStyle(color = CODE_KEYWORD, fontWeight = FontWeight.SemiBold)
            tok[0].isUpperCase() ->
                SpanStyle(color = CODE_TYPE)
            else ->
                SpanStyle(color = CODE_TEXT)
        }
        withStyle(style) { append(tok) }
        cursor = end
    }
    if (cursor < line.length) {
        withStyle(SpanStyle(color = CODE_TEXT)) { append(line.substring(cursor)) }
    }
}

@Composable
fun SyntaxHighlightedCodeView(
    code: String,
    fileName: String,
    modifier: Modifier = Modifier,
) {
    val lang = remember(fileName) { detectFileLanguageBadge(fileName) }
    val highlightedLines by produceState<List<AnnotatedString>>(
        initialValue = emptyList(),
        key1 = code,
        key2 = lang,
    ) {
        value = withContext(Dispatchers.IO) {
            val rawLines = code.split('\n')
            val maxHighlight = minOf(rawLines.size, 1500)
            List(rawLines.size) { idx ->
                val ln = rawLines[idx]
                if (idx < maxHighlight) highlightCodeLine(ln, lang)
                else AnnotatedString(ln, SpanStyle(color = CODE_TEXT))
            }
        }
    }

    val hScroll = rememberScrollState()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CODE_BG,
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxSize()) {
            // Subtle language & line count header strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CODE_GUTTER_BG)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1E293B),
                ) {
                    Text(
                        text = lang,
                        color = CODE_TAG_ANNO,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Text(
                    text = "${highlightedLines.size} บรรทัด",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                )
            }
            HorizontalDivider(color = Color(0xFF1E293B))
            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(hScroll),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    itemsIndexed(highlightedLines) { idx, annotated ->
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = (idx + 1).toString().padStart(4, ' '),
                                color = Color(0xFF475569),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(end = 10.dp),
                            )
                            Text(
                                text = annotated,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Bullet(val prefix: String, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class CodeBlock(val lang: String, val code: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data object Divider : MdBlock()
}

private fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = raw.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> i++
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim().uppercase().ifBlank { "CODE" }
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(lines[i])
                    i++
                }
                if (i < lines.size) i++
                blocks += MdBlock.CodeBlock(lang, sb.toString())
            }
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                blocks += MdBlock.Divider
                i++
            }
            trimmed.startsWith("### ") -> {
                blocks += MdBlock.Heading(3, trimmed.removePrefix("### ").trim())
                i++
            }
            trimmed.startsWith("## ") -> {
                blocks += MdBlock.Heading(2, trimmed.removePrefix("## ").trim())
                i++
            }
            trimmed.startsWith("# ") -> {
                blocks += MdBlock.Heading(1, trimmed.removePrefix("# ").trim())
                i++
            }
            trimmed.startsWith("> ") -> {
                blocks += MdBlock.Quote(trimmed.removePrefix("> ").trim())
                i++
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                blocks += MdBlock.Bullet("•", trimmed.drop(2).trim())
                i++
            }
            Regex("^\\d+\\.\\s+.*").matches(trimmed) -> {
                val dotIdx = trimmed.indexOf('.')
                val numPrefix = trimmed.substring(0, dotIdx + 1)
                val body = trimmed.substring(dotIdx + 1).trim()
                blocks += MdBlock.Bullet(numPrefix, body)
                i++
            }
            else -> {
                blocks += MdBlock.Paragraph(trimmed)
                i++
            }
        }
    }
    return blocks
}

private val INLINE_MD_REGEX = Regex("""(\*\*[^*]+\*\*|`[^`]+`|\*[^*]+\*)""")

private fun renderInlineMarkdown(
    text: String,
    baseColor: Color,
    codeBg: Color,
    codeFg: Color,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (m in INLINE_MD_REGEX.findAll(text)) {
        val start = m.range.first
        val end = m.range.last + 1
        if (start > cursor) {
            withStyle(SpanStyle(color = baseColor)) { append(text.substring(cursor, start)) }
        }
        val tok = m.value
        when {
            tok.startsWith("**") && tok.endsWith("**") -> {
                withStyle(SpanStyle(color = baseColor, fontWeight = FontWeight.Bold)) {
                    append(tok.removePrefix("**").removeSuffix("**"))
                }
            }
            tok.startsWith("`") && tok.endsWith("`") -> {
                withStyle(
                    SpanStyle(
                        color = codeFg,
                        background = codeBg,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                    ),
                ) {
                    append(" ${tok.removePrefix("`").removeSuffix("`")} ")
                }
            }
            tok.startsWith("*") && tok.endsWith("*") -> {
                withStyle(SpanStyle(color = baseColor, fontStyle = FontStyle.Italic)) {
                    append(tok.removePrefix("*").removeSuffix("*"))
                }
            }
            else -> withStyle(SpanStyle(color = baseColor)) { append(tok) }
        }
        cursor = end
    }
    if (cursor < text.length) {
        withStyle(SpanStyle(color = baseColor)) { append(text.substring(cursor)) }
    }
}

@Composable
fun FormattedMarkdownDocumentView(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks by produceState<List<MdBlock>>(initialValue = emptyList(), key1 = markdown) {
        value = withContext(Dispatchers.IO) { parseMarkdownBlocks(markdown) }
    }
    val textColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary
    val codeInlineBg = MaterialTheme.colorScheme.surfaceVariant
    val codeInlineFg = MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier,
    ) {
        SelectionContainer(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(blocks) { _, block ->
                    when (block) {
                        is MdBlock.Heading -> {
                            val size = when (block.level) {
                                1 -> 19.sp
                                2 -> 16.sp
                                else -> 14.sp
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = renderInlineMarkdown(block.text, if (block.level == 1) primaryColor else textColor, codeInlineBg, codeInlineFg),
                                    fontSize = size,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = size * 1.35f,
                                )
                                if (block.level == 1) {
                                    HorizontalDivider(color = primaryColor.copy(alpha = 0.3f))
                                }
                            }
                        }
                        is MdBlock.Bullet -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    text = block.prefix,
                                    color = primaryColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    text = renderInlineMarkdown(block.text, textColor, codeInlineBg, codeInlineFg),
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        is MdBlock.Quote -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = primaryColor.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.25f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = renderInlineMarkdown(block.text, textColor, codeInlineBg, codeInlineFg),
                                    fontSize = 12.5.sp,
                                    lineHeight = 19.sp,
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                        is MdBlock.CodeBlock -> {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = CODE_BG,
                                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = block.lang,
                                        color = CODE_TAG_ANNO,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    val codeScroll = rememberScrollState()
                                    Column(modifier = Modifier.horizontalScroll(codeScroll)) {
                                        block.code.split('\n').forEach { ln ->
                                            Text(
                                                text = highlightCodeLine(ln, block.lang),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.5.sp,
                                                lineHeight = 16.sp,
                                                softWrap = false,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        is MdBlock.Paragraph -> {
                            Text(
                                text = renderInlineMarkdown(block.text, textColor, codeInlineBg, codeInlineFg),
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                            )
                        }
                        MdBlock.Divider -> {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}
