package com.jarves.mh.skills

import java.io.File

enum class SkillCategory(val label: String) {
    ANDROID("Android"),
    WEB("Web / Frontend"),
    BACKEND("Backend / API"),
    UI_UX("UI / UX"),
    QA_TESTING("QA & Testing"),
    DEVOPS("DevOps / Git"),
    GENERAL("General"),
}

data class AgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val category: SkillCategory = SkillCategory.GENERAL,
    val instructions: String,
    val tags: List<String> = emptyList(),
    val isBuiltIn: Boolean = false,
    val isEnabled: Boolean = true,
)

data class ResolvedSkillsResult(
    val skills: List<AgentSkill>,
    val detectedSignals: List<String>,
    val isAutoMode: Boolean,
)

object BuiltInSkillCatalog {
    val defaults: List<AgentSkill> = listOf(
        AgentSkill(
            id = "skill-android-compose",
            name = "Android & Jetpack Compose",
            description = "Best practices for Kotlin, Jetpack Compose, Material 3, Gradle builds, and verified Debug APK packaging.",
            category = SkillCategory.ANDROID,
            tags = listOf("android", "kotlin", "compose", "gradle", "apk", "manifest", "material3"),
            instructions = """
                - Follow clean Jetpack Compose & Material 3 architecture with unidirectional state flow.
                - Keep Gradle configuration stable; verify compilation and build a working Debug APK whenever Android code changes.
                - Avoid blocking the Main thread; run file/network/shell work on Dispatchers.IO.
                - Register any new Activity, Service, or FileProvider paths properly in AndroidManifest.xml and res/xml/.
            """.trimIndent(),
            isBuiltIn = true,
            isEnabled = true,
        ),
        AgentSkill(
            id = "skill-web-modern",
            name = "Modern Web & Frontend",
            description = "Responsive HTML5, CSS3, TypeScript, React, and Vite best practices with clean component structure.",
            category = SkillCategory.WEB,
            tags = listOf("web", "html", "css", "js", "javascript", "typescript", "react", "vite", "next", "vue", "frontend"),
            instructions = """
                - Build responsive layouts that look crisp on both mobile and desktop viewports.
                - Keep HTML semantic, CSS modular, and JavaScript/TypeScript strictly typed where applicable.
                - Verify the production build or browser entry point (`index.html`) loads cleanly with zero console errors.
            """.trimIndent(),
            isBuiltIn = true,
            isEnabled = true,
        ),
        AgentSkill(
            id = "skill-python-backend",
            name = "Python & Backend Services",
            description = "Clean Python architecture, FastAPI/Flask APIs, virtual environments, and pytest verification.",
            category = SkillCategory.BACKEND,
            tags = listOf("python", "fastapi", "flask", "django", "pytest", "api", "backend", "py"),
            instructions = """
                - Write clean, typed Python 3 code with clear separation of routes, services, and data models.
                - Handle input validation, error status codes, and timeouts explicitly.
                - Run automated tests (pytest or unittest) to verify all endpoints and edge cases.
            """.trimIndent(),
            isBuiltIn = true,
            isEnabled = true,
        ),
        AgentSkill(
            id = "skill-node-api",
            name = "Node.js & REST/GraphQL APIs",
            description = "Structured Node.js/TypeScript services, safe async error handling, and package script verification.",
            category = SkillCategory.BACKEND,
            tags = listOf("node", "express", "fastify", "nest", "npm", "pnpm", "api", "server"),
            instructions = """
                - Structure Express/Fastify/Node routes cleanly with centralized error middleware and input validation.
                - Avoid unhandled promise rejections and hardcoded secrets; read configuration from environment variables.
                - Run project lint/build/test scripts via package.json before marking work complete.
            """.trimIndent(),
            isBuiltIn = true,
            isEnabled = true,
        ),
        AgentSkill(
            id = "skill-ui-ux-pro",
            name = "Professional UI/UX & Vector Icons",
            description = "Minimal, high-clarity visual hierarchy, consistent spacing, and strict vector icons (zero emojis).",
            category = SkillCategory.UI_UX,
            tags = listOf("ui", "ux", "design", "screen", "layout", "theme", "component", "css", "compose"),
            instructions = """
                - Prioritize visual clarity, generous spacing, and clear hierarchy (1 primary CTA per screen section).
                - NEVER use emojis in UI labels, notifications, or status badges; always use crisp vector/Material icons.
                - Ensure new users can understand every screen immediately with concise helper copy and clean empty states.
            """.trimIndent(),
            isBuiltIn = true,
            isEnabled = true,
        ),
        AgentSkill(
            id = "skill-qa-testing",
            name = "Automated QA & Verification",
            description = "Real test execution, regression coverage, and evidence-backed completion checks.",
            category = SkillCategory.QA_TESTING,
            tags = listOf("test", "qa", "junit", "pytest", "jest", "verify", "bug", "fix", "regression"),
            instructions = """
                - Write targeted regression tests for every bug fix or new feature.
                - Run real build and test commands and inspect actual exit codes and logs before reporting completion.
                - Never claim tests passed without executing them.
            """.trimIndent(),
            isBuiltIn = true,
            isEnabled = true,
        ),
        AgentSkill(
            id = "skill-security-data",
            name = "Security & Reliable Persistence",
            description = "Safe credential storage, SQL/file integrity, input sanitization, and non-destructive updates.",
            category = SkillCategory.GENERAL,
            tags = listOf("security", "auth", "token", "sqlite", "database", "sql", "key", "encryption", "provider"),
            instructions = """
                - Never log or hardcode API keys, tokens, or passwords; mask sensitive values in UI with visibility toggles.
                - Avoid destructive SQLite CONFLICT_REPLACE on parent rows with ON DELETE CASCADE; use safe upsert logic.
                - Validate all file paths to prevent directory traversal outside the workspace.
            """.trimIndent(),
            isBuiltIn = true,
            isEnabled = true,
        ),
        AgentSkill(
            id = "skill-modular-git",
            name = "Clean Architecture & Git Hygiene",
            description = "Modular file layout, dead-code removal, and focused commits.",
            category = SkillCategory.DEVOPS,
            tags = listOf("git", "refactor", "clean", "architecture", "docker", "ci"),
            instructions = """
                - Keep files modular and focused; avoid duplicating logic across screens or services.
                - Remove dead code and temporary build artifacts instead of leaving clutter in the repository.
                - Preserve existing tests and contracts during refactoring.
            """.trimIndent(),
            isBuiltIn = true,
            isEnabled = true,
        ),
    )
}

/**
 * Inspects a project workspace (files, build manifests, folder structure) and user prompt
 * to automatically select the most relevant skills for both AI Team and Solo Studio.
 */
object AutoSkillProjectAnalyzer {

    fun parseMarkdownSkill(rawMarkdown: String, fallbackName: String = "Custom Skill"): AgentSkill {
        val trimmed = rawMarkdown.trim()
        var name = fallbackName.removeSuffix(".md").removeSuffix(".txt").trim().ifBlank { "Custom Skill" }
        var description = "Imported custom skill"
        var body = trimmed
        var tags = emptyList<String>()

        if (trimmed.startsWith("---")) {
            val endIdx = trimmed.indexOf("\n---", startIndex = 3)
            if (endIdx > 3) {
                val frontmatter = trimmed.substring(3, endIdx)
                body = trimmed.substring(endIdx + 4).trim()
                frontmatter.lines().forEach { line ->
                    val sep = line.indexOf(':')
                    if (sep > 0) {
                        val key = line.substring(0, sep).trim().lowercase()
                        val value = line.substring(sep + 1).trim().trim('"', '\'')
                        when (key) {
                            "name", "title" -> if (value.isNotBlank()) name = value
                            "description" -> if (value.isNotBlank()) description = value
                            "tags" -> tags = value.trim('[', ']').split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
                        }
                    }
                }
            }
        }
        if (description == "Imported custom skill") {
            val firstLine = body.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
            if (!firstLine.isNullOrBlank()) description = firstLine.take(120)
        }
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "custom" }
        return AgentSkill(
            id = "custom-$slug-${System.currentTimeMillis() % 100_000}",
            name = name,
            description = description,
            category = SkillCategory.GENERAL,
            instructions = body.ifBlank { trimmed },
            tags = tags.ifEmpty { name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 } },
            isBuiltIn = false,
            isEnabled = true,
        )
    }

    /**
     * Discovers any project-local SKILL.md or markdown skill files inside [workspaceDir]/.orqen/skills.
     */
    fun discoverWorkspaceLocalSkills(workspaceDir: File?): List<AgentSkill> {
        if (workspaceDir == null || !workspaceDir.isDirectory) return emptyList()
        val found = mutableListOf<AgentSkill>()
        val candidates = mutableListOf<File>()
        File(workspaceDir, "SKILL.md").takeIf { it.isFile }?.let { candidates.add(it) }
        File(workspaceDir, ".orqen/skills").takeIf { it.isDirectory }?.listFiles()
            ?.filter { it.isFile && (it.extension.equals("md", true) || it.extension.equals("txt", true)) }
            ?.sortedBy { it.name }
            ?.let { candidates.addAll(it) }

        candidates.take(4).forEach { file ->
            runCatching {
                val content = file.readText().take(4000)
                if (content.isNotBlank()) {
                    found.add(parseMarkdownSkill(content, file.nameWithoutExtension))
                }
            }
        }
        return found
    }

    fun selectForProject(
        allSkills: List<AgentSkill>,
        workspaceDir: File?,
        userPrompt: String = "",
        maxSkills: Int = 4,
    ): ResolvedSkillsResult {
        val signals = linkedSetOf<String>()
        val lowerPrompt = userPrompt.lowercase()

        // 1. Inspect project files (shallow fast walk, max 120 files to keep latency < 15ms)
        var hasGradle = false
        var hasAndroidManifest = false
        var hasKotlin = false
        var hasPackageJson = false
        var hasHtmlCss = false
        var hasPython = false
        var hasTests = false
        var hasDockerOrCi = false
        var packageJsonContent = ""

        if (workspaceDir != null && workspaceDir.isDirectory) {
            var scanned = 0
            workspaceDir.walkTopDown()
                .maxDepth(4)
                .onEnter { dir ->
                    val n = dir.name
                    n != ".git" && n != "node_modules" && n != "build" && n != ".gradle" && n != "__pycache__"
                }
                .forEach { f ->
                    if (!f.isFile || scanned >= 120) return@forEach
                    scanned++
                    val name = f.name.lowercase()
                    val ext = f.extension.lowercase()
                    when {
                        name == "build.gradle" || name == "build.gradle.kts" || name == "settings.gradle.kts" -> hasGradle = true
                        name == "androidmanifest.xml" -> hasAndroidManifest = true
                        ext == "kt" || ext == "kts" -> hasKotlin = true
                        name == "package.json" -> {
                            hasPackageJson = true
                            packageJsonContent = runCatching { f.readText().take(3000).lowercase() }.getOrDefault("")
                        }
                        ext in setOf("html", "css", "scss", "tsx", "jsx", "vue", "svelte") -> hasHtmlCss = true
                        ext == "py" || name == "requirements.txt" || name == "pyproject.toml" -> hasPython = true
                        name.contains("test") || name.contains("spec") -> hasTests = true
                        name == "dockerfile" || name == "docker-compose.yml" -> hasDockerOrCi = true
                    }
                }
        }

        if (hasAndroidManifest || (hasGradle && hasKotlin) || lowerPrompt.contains("android") || lowerPrompt.contains("apk") || lowerPrompt.contains("compose")) {
            signals += listOf("android", "kotlin", "compose", "gradle", "ui")
        }
        if (hasHtmlCss || (hasPackageJson && (packageJsonContent.contains("react") || packageJsonContent.contains("vite") || packageJsonContent.contains("next") || packageJsonContent.contains("vue"))) || lowerPrompt.contains("web") || lowerPrompt.contains("html") || lowerPrompt.contains("react")) {
            signals += listOf("web", "frontend", "html", "css", "ui")
        }
        if (hasPackageJson && (packageJsonContent.contains("express") || packageJsonContent.contains("fastify") || packageJsonContent.contains("nestjs") || lowerPrompt.contains("node") || lowerPrompt.contains("api"))) {
            signals += listOf("node", "api", "backend")
        }
        if (hasPython || lowerPrompt.contains("python") || lowerPrompt.contains("fastapi") || lowerPrompt.contains("flask")) {
            signals += listOf("python", "backend", "api")
        }
        if (hasTests || lowerPrompt.contains("test") || lowerPrompt.contains("bug") || lowerPrompt.contains("fix") || lowerPrompt.contains("เทส") || lowerPrompt.contains("บั๊ก")) {
            signals += listOf("qa", "test", "verify")
        }
        if (lowerPrompt.contains("ui") || lowerPrompt.contains("ux") || lowerPrompt.contains("design") || lowerPrompt.contains("หน้าจอ") || lowerPrompt.contains("ออกแบบ")) {
            signals += listOf("ui", "ux", "design")
        }
        if (lowerPrompt.contains("auth") || lowerPrompt.contains("security") || lowerPrompt.contains("sqlite") || lowerPrompt.contains("database") || lowerPrompt.contains("api key")) {
            signals += listOf("security", "sqlite", "auth")
        }
        if (hasDockerOrCi || lowerPrompt.contains("git") || lowerPrompt.contains("refactor") || lowerPrompt.contains("โครงสร้าง")) {
            signals += listOf("git", "refactor", "architecture")
        }

        // Always include workspace-local SKILL.md files first
        val localSkills = discoverWorkspaceLocalSkills(workspaceDir)

        // Score all catalog & user-custom skills against detected signals + prompt tokens
        val scored = allSkills.map { skill ->
            var score = 0
            if (!skill.isBuiltIn && skill.isEnabled) {
                // User-added custom skills get a natural boost so custom domain knowledge is respected
                score += 4
            }
            for (tag in skill.tags) {
                val t = tag.lowercase()
                if (t in signals) score += 5
                if (t.length >= 3 && lowerPrompt.contains(t)) score += 3
            }
            if (skill.category == SkillCategory.QA_TESTING && signals.isNotEmpty()) score += 2
            if (skill.category == SkillCategory.UI_UX && ("ui" in signals || "android" in signals || "web" in signals)) score += 3
            skill to score
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }

        val selected = (localSkills + scored)
            .distinctBy { it.id }
            .take(maxSkills)
            .ifEmpty {
                // Sensible baseline for brand-new empty workspaces before any files exist
                allSkills.filter { it.id in setOf("skill-ui-ux-pro", "skill-qa-testing") }.take(2)
            }

        return ResolvedSkillsResult(
            skills = selected,
            detectedSignals = signals.toList(),
            isAutoMode = true,
        )
    }
}
