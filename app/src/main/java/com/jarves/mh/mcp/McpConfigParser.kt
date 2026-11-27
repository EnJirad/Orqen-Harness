package com.jarves.mh.mcp

import org.json.JSONArray
import org.json.JSONObject

data class ParsedMcpDraft(
    val config: McpServerConfig,
    /** Extracted secret env values (key -> raw value); placeholder tokens are excluded automatically. */
    val extractedSecretEnv: Map<String, String> = emptyMap(),
    /** Extracted secret header values (header -> raw value); placeholder tokens are excluded automatically. */
    val extractedSecretHeaders: Map<String, String> = emptyMap(),
    val warnings: List<String> = emptyList(),
)

data class McpParseResult(
    val drafts: List<ParsedMcpDraft> = emptyList(),
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null && drafts.isNotEmpty()
}

/**
 * Paste-First MCP Configuration Parser (Part 5).
 * Supports:
 * 1. Claude `{"mcpServers": { ... }}` JSON
 * 2. Single MCP server JSON object
 * 3. `claude mcp add ...` CLI command
 * 4. Remote MCP URL (`https://...`)
 * 5. Curated quick presets (Neon, Git, GitHub, Filesystem, Postgres, Fetch)
 */
object McpConfigParser {

    private val secretKeyPatterns = listOf(
        "token", "secret", "key", "password", "passwd", "auth", "credential", "bearer", "database_url", "connection_string",
    )

    private val placeholderMarkers = listOf(
        "your_token", "your-token", "your_api_key", "your-api-key", "<token>", "<api_key>",
        "replace_me", "changeme", "xxx", "placeholder", "sk-...", "ghp_...",
    )

    fun isLikelySecretKey(key: String): Boolean {
        val lower = key.lowercase()
        return secretKeyPatterns.any { lower.contains(it) }
    }

    fun isPlaceholderCredential(value: String): Boolean {
        val trimmed = value.trim().lowercase()
        if (trimmed.isBlank()) return true
        return placeholderMarkers.any { trimmed == it || trimmed.contains(it) } ||
            (trimmed.startsWith("<") && trimmed.endsWith(">")) ||
            (trimmed.startsWith("\${") && trimmed.endsWith("}"))
    }

    fun parse(rawInput: String, scope: McpScope = McpScope.GLOBAL, workspaceId: String? = null): McpParseResult {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return McpParseResult(error = "Configuration input is empty.")
        }

        // 1. Remote MCP URL directly pasted
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            if (trimmed.contains('\n') || trimmed.contains(' ')) {
                return McpParseResult(error = "Invalid URL: contains whitespace.")
            }
            val hostName = runCatching { java.net.URI(trimmed).host }.getOrNull().orEmpty()
                .removePrefix("www.")
                .substringBefore('.')
                .replaceFirstChar { it.uppercase() }
                .ifBlank { "Remote MCP" }
            val transport = if (trimmed.endsWith("/sse", ignoreCase = true) || trimmed.contains("/sse?", ignoreCase = true)) {
                McpTransportType.LEGACY_HTTP_SSE
            } else {
                McpTransportType.STREAMABLE_HTTP
            }
            return McpParseResult(
                drafts = listOf(
                    ParsedMcpDraft(
                        config = McpServerConfig(
                            name = hostName,
                            description = "Remote MCP server ($trimmed)",
                            transportType = transport,
                            remoteUrl = trimmed,
                            scope = scope,
                            workspaceId = if (scope == McpScope.WORKSPACE) workspaceId else null,
                        ),
                    ),
                ),
            )
        }

        // 2. `claude mcp add ...` command
        if (trimmed.startsWith("claude mcp add", ignoreCase = true) || trimmed.startsWith("mcp add", ignoreCase = true)) {
            return parseClaudeMcpAddCommand(trimmed, scope, workspaceId)
        }

        // 3. JSON: either {"mcpServers": {...}} or single server object
        if (trimmed.startsWith("{")) {
            return parseJsonConfig(trimmed, scope, workspaceId)
        }

        return McpParseResult(
            error = "Unrecognized format. Paste Claude mcpServers JSON, a single server JSON, `claude mcp add ...`, or a remote `https://` MCP URL.",
        )
    }

    private fun parseJsonConfig(jsonText: String, scope: McpScope, workspaceId: String?): McpParseResult {
        val root = runCatching { JSONObject(jsonText) }.getOrElse { err ->
            return McpParseResult(error = "Malformed JSON: ${err.message}")
        }

        val mcpServersObj = root.optJSONObject("mcpServers")
        if (mcpServersObj != null) {
            val drafts = mutableListOf<ParsedMcpDraft>()
            val keys = mcpServersObj.keys()
            while (keys.hasNext()) {
                val serverName = keys.next()
                val obj = mcpServersObj.optJSONObject(serverName)
                    ?: return McpParseResult(error = "Entry '$serverName' in mcpServers must be a JSON object.")
                val parsed = parseSingleServerObject(serverName, obj, scope, workspaceId)
                if (parsed.error != null) return parsed
                drafts += parsed.drafts
            }
            return if (drafts.isEmpty()) {
                McpParseResult(error = "No servers found inside mcpServers object.")
            } else {
                McpParseResult(drafts = drafts)
            }
        }

        val inferredName = root.optString("name").ifBlank { "MCP Server" }
        return parseSingleServerObject(inferredName, root, scope, workspaceId)
    }

    private fun parseSingleServerObject(
        defaultName: String,
        obj: JSONObject,
        scope: McpScope,
        workspaceId: String?,
    ): McpParseResult {
        val name = obj.optString("name").ifBlank { defaultName }.trim()
        if (name.isBlank()) return McpParseResult(error = "Server name is required.")

        val description = obj.optString("description").trim()
        val url = obj.optString("url").ifBlank { obj.optString("remoteUrl") }.trim()
        val command = obj.optString("command").trim()
        val typeHint = obj.optString("type").ifBlank { obj.optString("transport") }.trim().lowercase()

        val transport = when {
            typeHint == "sse" || typeHint == "legacy_http_sse" -> McpTransportType.LEGACY_HTTP_SSE
            typeHint == "http" || typeHint == "streamable_http" || typeHint == "streamable-http" -> McpTransportType.STREAMABLE_HTTP
            typeHint == "stdio" -> McpTransportType.STDIO
            url.isNotBlank() -> if (url.endsWith("/sse", ignoreCase = true)) McpTransportType.LEGACY_HTTP_SSE else McpTransportType.STREAMABLE_HTTP
            command.isNotBlank() -> McpTransportType.STDIO
            else -> return McpParseResult(error = "Server '$name' must specify either a STDIO 'command' or a remote 'url'.")
        }

        if (transport == McpTransportType.STDIO && command.isBlank()) {
            return McpParseResult(error = "STDIO server '$name' requires a 'command'.")
        }
        if (transport != McpTransportType.STDIO && url.isBlank()) {
            return McpParseResult(error = "HTTP/SSE server '$name' requires a 'url'.")
        }

        val args = mutableListOf<String>()
        val argsArr: JSONArray? = obj.optJSONArray("args")
        if (argsArr != null) {
            for (i in 0 until argsArr.length()) {
                args += argsArr.optString(i)
            }
        }

        val warnings = mutableListOf<String>()
        val plainEnv = linkedMapOf<String, String>()
        val secretEnv = linkedMapOf<String, String>()
        val secretEnvKeys = linkedSetOf<String>()
        obj.optJSONObject("env")?.let { envObj ->
            val ek = envObj.keys()
            while (ek.hasNext()) {
                val k = ek.next()
                val v = envObj.optString(k)
                if (isLikelySecretKey(k)) {
                    secretEnvKeys += k
                    if (isPlaceholderCredential(v)) {
                        warnings += "Placeholder credential detected for '$k' — please enter your real token before connecting."
                    } else {
                        secretEnv[k] = v
                    }
                } else {
                    plainEnv[k] = v
                }
            }
        }

        val plainHeaders = linkedMapOf<String, String>()
        val secretHeaders = linkedMapOf<String, String>()
        val secretHeaderKeys = linkedSetOf<String>()
        obj.optJSONObject("headers")?.let { hdrObj ->
            val hk = hdrObj.keys()
            while (hk.hasNext()) {
                val k = hk.next()
                val v = hdrObj.optString(k)
                if (isLikelySecretKey(k)) {
                    secretHeaderKeys += k
                    if (isPlaceholderCredential(v)) {
                        warnings += "Placeholder header secret detected for '$k' — please enter your real key before connecting."
                    } else {
                        secretHeaders[k] = v
                    }
                } else {
                    plainHeaders[k] = v
                }
            }
        }

        val authType = when {
            secretHeaders.keys.any { it.equals("Authorization", ignoreCase = true) } ||
                secretHeaderKeys.any { it.equals("Authorization", ignoreCase = true) } -> McpAuthType.BEARER_TOKEN
            secretHeaders.isNotEmpty() || secretHeaderKeys.isNotEmpty() -> McpAuthType.API_KEY_HEADER
            else -> McpAuthType.NONE
        }

        val cfg = McpServerConfig(
            name = name,
            description = description,
            transportType = transport,
            command = command,
            args = args,
            env = plainEnv,
            secretEnvKeys = secretEnvKeys,
            workingDirectory = obj.optString("cwd").ifBlank { obj.optString("workingDirectory") }.trim(),
            remoteUrl = url,
            headers = plainHeaders,
            secretHeaderKeys = secretHeaderKeys,
            authType = authType,
            enabled = obj.optBoolean("enabled", true),
            scope = scope,
            workspaceId = if (scope == McpScope.WORKSPACE) workspaceId else null,
        )
        return McpParseResult(
            drafts = listOf(
                ParsedMcpDraft(
                    config = cfg,
                    extractedSecretEnv = secretEnv,
                    extractedSecretHeaders = secretHeaders,
                    warnings = warnings,
                ),
            ),
        )
    }

    private fun parseClaudeMcpAddCommand(cmd: String, scope: McpScope, workspaceId: String?): McpParseResult {
        val tokens = tokenizeShellArgs(cmd)
        val addIdx = tokens.indexOfFirst { it.equals("add", ignoreCase = true) }
        if (addIdx < 0 || addIdx + 1 >= tokens.size) {
            return McpParseResult(error = "Incomplete `claude mcp add` command.")
        }

        var transportHint: McpTransportType? = null
        val plainEnv = linkedMapOf<String, String>()
        val secretEnv = linkedMapOf<String, String>()
        val secretEnvKeys = linkedSetOf<String>()
        val plainHeaders = linkedMapOf<String, String>()
        val secretHeaders = linkedMapOf<String, String>()
        val secretHeaderKeys = linkedSetOf<String>()
        val warnings = mutableListOf<String>()
        val positional = mutableListOf<String>()

        var i = addIdx + 1
        var afterDoubleDash = false
        while (i < tokens.size) {
            val tok = tokens[i]
            if (!afterDoubleDash && tok == "--") {
                afterDoubleDash = true
                i++
                continue
            }
            if (!afterDoubleDash && (tok == "--transport" || tok == "-t") && i + 1 < tokens.size) {
                val tv = tokens[i + 1].lowercase()
                transportHint = when (tv) {
                    "http", "streamable-http", "streamable_http" -> McpTransportType.STREAMABLE_HTTP
                    "sse" -> McpTransportType.LEGACY_HTTP_SSE
                    "stdio" -> McpTransportType.STDIO
                    else -> null
                }
                i += 2
                continue
            }
            if (!afterDoubleDash && (tok == "-e" || tok == "--env") && i + 1 < tokens.size) {
                val kv = tokens[i + 1]
                val eq = kv.indexOf('=')
                if (eq > 0) {
                    val k = kv.substring(0, eq).trim()
                    val v = kv.substring(eq + 1).trim()
                    if (isLikelySecretKey(k)) {
                        secretEnvKeys += k
                        if (isPlaceholderCredential(v)) {
                            warnings += "Placeholder credential for '$k' was skipped."
                        } else {
                            secretEnv[k] = v
                        }
                    } else {
                        plainEnv[k] = v
                    }
                }
                i += 2
                continue
            }
            if (!afterDoubleDash && (tok == "-H" || tok == "--header") && i + 1 < tokens.size) {
                val hv = tokens[i + 1]
                val colon = hv.indexOf(':')
                if (colon > 0) {
                    val k = hv.substring(0, colon).trim()
                    val v = hv.substring(colon + 1).trim()
                    if (isLikelySecretKey(k)) {
                        secretHeaderKeys += k
                        if (isPlaceholderCredential(v)) {
                            warnings += "Placeholder header secret for '$k' was skipped."
                        } else {
                            secretHeaders[k] = v
                        }
                    } else {
                        plainHeaders[k] = v
                    }
                }
                i += 2
                continue
            }
            if (!afterDoubleDash && (tok == "-s" || tok == "--scope") && i + 1 < tokens.size) {
                i += 2
                continue
            }
            positional += tok
            i++
        }

        if (positional.size < 2) {
            return McpParseResult(error = "Expected `<name> <command-or-url> [args...]` after `claude mcp add`.")
        }

        val name = positional[0]
        val target = positional[1]
        val extraArgs = positional.drop(2)
        val isUrl = target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)
        val transport = transportHint ?: when {
            isUrl && target.endsWith("/sse", ignoreCase = true) -> McpTransportType.LEGACY_HTTP_SSE
            isUrl -> McpTransportType.STREAMABLE_HTTP
            else -> McpTransportType.STDIO
        }

        val cfg = McpServerConfig(
            name = name,
            description = "Imported from `claude mcp add`",
            transportType = transport,
            command = if (transport == McpTransportType.STDIO) target else "",
            args = if (transport == McpTransportType.STDIO) extraArgs else emptyList(),
            env = plainEnv,
            secretEnvKeys = secretEnvKeys,
            remoteUrl = if (transport != McpTransportType.STDIO) target else "",
            headers = plainHeaders,
            secretHeaderKeys = secretHeaderKeys,
            authType = if (secretHeaderKeys.isNotEmpty()) McpAuthType.BEARER_TOKEN else McpAuthType.NONE,
            scope = scope,
            workspaceId = if (scope == McpScope.WORKSPACE) workspaceId else null,
        )
        return McpParseResult(
            drafts = listOf(
                ParsedMcpDraft(
                    config = cfg,
                    extractedSecretEnv = secretEnv,
                    extractedSecretHeaders = secretHeaders,
                    warnings = warnings,
                ),
            ),
        )
    }

    /** Safe quote-aware shell tokenizer (never uses shell eval). */
    fun tokenizeShellArgs(input: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escaped = false
        for (ch in input) {
            when {
                escaped -> {
                    cur.append(ch)
                    escaped = false
                }
                ch == '\\' && !inSingle -> escaped = true
                ch == '\'' && !inDouble -> inSingle = !inSingle
                ch == '"' && !inSingle -> inDouble = !inDouble
                ch.isWhitespace() && !inSingle && !inDouble -> {
                    if (cur.isNotEmpty()) {
                        out += cur.toString()
                        cur.clear()
                    }
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** Curated templates for one-tap setup (user fills their own real API key/token). */
    val quickTemplates: List<Pair<String, McpServerConfig>> = listOf(
        "Neon" to McpServerConfig(
            name = "Neon",
            description = "Neon Serverless Postgres MCP (manage databases, branches, SQL)",
            transportType = McpTransportType.STREAMABLE_HTTP,
            remoteUrl = "https://mcp.neon.tech/mcp",
            secretHeaderKeys = setOf("Authorization"),
            authType = McpAuthType.BEARER_TOKEN,
            authHeaderName = "Authorization",
        ),
        "Git" to McpServerConfig(
            name = "Git",
            description = "Local Git MCP server inside PRoot (status, diff, commit, log)",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-git"),
        ),
        "GitHub" to McpServerConfig(
            name = "GitHub",
            description = "GitHub MCP (issues, PRs, repositories, code search)",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-github"),
            secretEnvKeys = setOf("GITHUB_PERSONAL_ACCESS_TOKEN"),
        ),
        "Filesystem" to McpServerConfig(
            name = "Filesystem",
            description = "Local workspace filesystem MCP server",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/workspace"),
        ),
        "Postgres" to McpServerConfig(
            name = "Postgres",
            description = "PostgreSQL read/inspect MCP server",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-postgres"),
            secretEnvKeys = setOf("POSTGRES_CONNECTION_STRING"),
        ),
        "Fetch" to McpServerConfig(
            name = "Fetch",
            description = "HTTP Fetch MCP server for web documentation lookup",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-fetch"),
        ),
        "Supabase" to McpServerConfig(
            name = "Supabase",
            description = "Supabase MCP (Postgres DB, Auth, Storage, Edge Functions, migrations)",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@supabase/mcp-server-supabase@latest"),
            secretEnvKeys = setOf("SUPABASE_ACCESS_TOKEN"),
        ),
        "Vercel" to McpServerConfig(
            name = "Vercel",
            description = "Vercel Remote MCP (deployments, preview URLs, logs, domains)",
            transportType = McpTransportType.STREAMABLE_HTTP,
            remoteUrl = "https://mcp.vercel.com",
            secretHeaderKeys = setOf("Authorization"),
            authType = McpAuthType.BEARER_TOKEN,
            authHeaderName = "Authorization",
        ),
        "Render" to McpServerConfig(
            name = "Render",
            description = "Render Remote MCP (backend services, deploy logs, metrics, env vars)",
            transportType = McpTransportType.STREAMABLE_HTTP,
            remoteUrl = "https://mcp.render.com/mcp",
            secretHeaderKeys = setOf("Authorization"),
            authType = McpAuthType.BEARER_TOKEN,
            authHeaderName = "Authorization",
        ),
        "Cloudflare" to McpServerConfig(
            name = "Cloudflare",
            description = "Cloudflare Workers, R2, D1, KV, and Pages deployment MCP",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "mcp-remote", "https://bindings.mcp.cloudflare.com/sse"),
            secretEnvKeys = setOf("CLOUDFLARE_API_TOKEN"),
        ),
        "Stripe" to McpServerConfig(
            name = "Stripe",
            description = "Stripe Payments MCP (customers, PaymentIntents, invoices, webhooks)",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@stripe/mcp", "--tools=all"),
            secretEnvKeys = setOf("STRIPE_SECRET_KEY"),
        ),
        "Context7 Docs" to McpServerConfig(
            name = "Context7 Docs",
            description = "Up-to-date library & framework documentation lookup for AI agents",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@upstash/context7-mcp@latest"),
        ),
        "Playwright" to McpServerConfig(
            name = "Playwright",
            description = "Browser automation, E2E UI testing, and screenshot inspection MCP",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@playwright/mcp@latest"),
        ),
        "Brave Search" to McpServerConfig(
            name = "Brave Search",
            description = "Real-time web search & technical research MCP",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-brave-search"),
            secretEnvKeys = setOf("BRAVE_API_KEY"),
        ),
        "SQLite" to McpServerConfig(
            name = "SQLite",
            description = "Local SQLite database query, schema inspection, and migration MCP",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-sqlite"),
        ),
        "Redis" to McpServerConfig(
            name = "Redis",
            description = "Redis cache, keys, streams, and pub/sub inspection MCP",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-redis"),
            secretEnvKeys = setOf("REDIS_URL"),
        ),
        "Sentry" to McpServerConfig(
            name = "Sentry",
            description = "Sentry crash reports, stack traces, and error monitoring MCP",
            transportType = McpTransportType.STREAMABLE_HTTP,
            remoteUrl = "https://mcp.sentry.dev/mcp",
            secretHeaderKeys = setOf("Authorization"),
            authType = McpAuthType.BEARER_TOKEN,
            authHeaderName = "Authorization",
        ),
        "Linear" to McpServerConfig(
            name = "Linear",
            description = "Linear issue tracking, sprints, and project roadmap MCP",
            transportType = McpTransportType.STREAMABLE_HTTP,
            remoteUrl = "https://mcp.linear.app/mcp",
            secretHeaderKeys = setOf("Authorization"),
            authType = McpAuthType.BEARER_TOKEN,
            authHeaderName = "Authorization",
        ),
        "Notion" to McpServerConfig(
            name = "Notion",
            description = "Notion workspace docs, specs, and knowledge base MCP",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@notionhq/notion-mcp-server"),
            secretEnvKeys = setOf("OPENAPI_MCP_HEADERS"),
        ),
        "Memory" to McpServerConfig(
            name = "Memory",
            description = "Persistent knowledge-graph memory across sessions MCP",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-memory"),
        ),
    )
}
