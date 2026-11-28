package com.jarves.mh.runtime

import org.json.JSONObject
import java.io.File

enum class AndroidBuildPhase { IDLE, PREPARING, BUILDING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }
enum class AndroidAction { NONE, BUILD, FORCE_BUILD, INSTALL, RUN, BUILD_AND_RUN, CLEAN }
enum class AndroidBuildStage {
    IDLE, PREPARING, RESOLVING, COMPILING, RESOURCES, PACKAGING, FINDING_APK,
    INSTALLING, LAUNCHING, COMPLETE, FAILED, CANCELLING, CANCELLED,
}
enum class AndroidIssueSeverity { INFO, WARNING, ERROR }

data class AndroidBuildIssue(
    val severity: AndroidIssueSeverity = AndroidIssueSeverity.ERROR,
    val title: String,
    val detail: String,
    val suggestion: String? = null,
    val filePath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

data class AndroidApkInfo(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val builtAtMillis: Long,
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val stale: Boolean = false,
    val installed: Boolean = false,
    val installedMatches: Boolean = false,
)

enum class AndroidHealthStatus { RUNNING, PASSED, WARNING, FAILED }
enum class AndroidHealthFix { NONE, INSTALL_TOOLS, OPEN_INSTALL_SETTINGS, OPEN_NETWORK_SETTINGS, OPEN_STORAGE_SETTINGS, CLEAN, COPY_LOGCAT_COMMAND, REFRESH }

data class AndroidHealthCheck(
    val id: String,
    val title: String,
    val detail: String,
    val status: AndroidHealthStatus,
    val fix: AndroidHealthFix = AndroidHealthFix.NONE,
)

enum class AndroidLogLevel { VERBOSE, DEBUG, INFO, WARNING, ERROR, FATAL }
data class AndroidLogLine(
    val id: Long,
    val text: String,
    val level: AndroidLogLevel,
    val filePath: String? = null,
    val line: Int? = null,
)

data class AndroidLogcatState(
    val available: Boolean = false,
    val running: Boolean = false,
    val paused: Boolean = false,
    val message: String? = null,
    val packageName: String? = null,
    val lines: List<AndroidLogLine> = emptyList(),
    val minimumLevel: AndroidLogLevel = AndroidLogLevel.VERBOSE,
    val query: String = "",
)

data class AndroidBuildRecord(
    val phase: AndroidBuildPhase = AndroidBuildPhase.IDLE,
    val message: String? = null,
    val log: String = "",
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val apkPath: String? = null,
    val apkSizeBytes: Long? = null,
    val action: AndroidAction = AndroidAction.NONE,
    val stage: AndroidBuildStage = AndroidBuildStage.IDLE,
    val issues: List<AndroidBuildIssue> = emptyList(),
    val recentDurationsMillis: List<Long> = emptyList(),
)

internal fun resolveDebugAssembleTask(projectRoot: File): String {
    val appGradleKts = File(projectRoot, "app/build.gradle.kts")
    val appGradle = File(projectRoot, "app/build.gradle")
    val scriptText = runCatching {
        when {
            appGradleKts.isFile -> appGradleKts.readText()
            appGradle.isFile -> appGradle.readText()
            else -> ""
        }
    }.getOrDefault("")
    val hasOnlineFlavor = scriptText.contains("productFlavors") &&
        (scriptText.contains("\"online\"") || scriptText.contains("'online'"))
    return if (hasOnlineFlavor) "assembleOnlineDebug" else "assembleDebug"
}

internal fun isMissingCachedDependencyError(output: String): Boolean {
    val lower = output.lowercase()
    return ("offline mode" in lower || "no cached version" in lower || "could not resolve" in lower || "could not get resource" in lower) &&
        "outofmemory" !in lower &&
        "no space left" !in lower
}

internal fun androidGradleCommand(root: File, task: String, offline: Boolean = false): String {
    val launcher = File(root, "gradlew").takeIf(File::isFile)?.let { "bash ./gradlew" } ?: "gradle"
    val resolvedTask = if (task == "assembleDebug") resolveDebugAssembleTask(root) else task
    val offlineFlag = if (offline) "--offline " else ""
    return "$launcher -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 $offlineFlag" +
        "--init-script /root/.gradle/init.d/pocketdev-android.gradle " +
        "-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 " +
        // Gradle daemons can remain attached to deleted PRoot paths after an
        // interrupted build. A bounded, no-daemon invocation is more reliable
        // on Android and avoids leaving a second busy daemon behind.
        "$resolvedTask --console=plain --stacktrace"
}

/**
 * Full PocketDev shell command used by Solo Mode, AI Team Mode, and Terminal Build APK buttons:
 * 1. Runs the idempotent ARM64 NDK/CMake shim script so x86_64 NDK/CMake binaries work natively on ARM64.
 * 2. Uses pre-installed `gradle` directly (avoiding gradlew network downloads in offline mode).
 * 3. Applies memory-safe `--no-daemon --max-workers=2`, local Maven init-script, ARM64 aapt2 override,
 *    and flavor-aware `assembleOnlineDebug` vs `assembleDebug`.
 */
internal fun androidGradleShellCommand(
    root: File,
    task: String,
    offline: Boolean = false,
    timeoutMinutes: Int = if (offline) 8 else 15,
): String {
    val resolvedTask = if (task == "assembleDebug") resolveDebugAssembleTask(root) else task
    val offlineFlag = if (offline) "--offline " else ""
    val shimPrefix = "[ -x /root/.gradle/init.d/pocketdev-arm64-ndk-shim.sh ] && /root/.gradle/init.d/pocketdev-arm64-ndk-shim.sh; "
    return "${shimPrefix}timeout ${timeoutMinutes}m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 $offlineFlag" +
        "--init-script /root/.gradle/init.d/pocketdev-android.gradle " +
        "-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 " +
        "$resolvedTask --console=plain --stacktrace"
}

internal fun publishCanonicalDebugApk(projectRoot: File, builtApk: File): File {
    val canonicalDir = File(projectRoot, "app/build/outputs/apk/debug").apply { mkdirs() }
    val canonicalApk = File(canonicalDir, "app-debug.apk")
    return runCatching {
        if (builtApk.canonicalFile != canonicalApk.canonicalFile) {
            builtApk.copyTo(canonicalApk, overwrite = true)
        }
        canonicalApk.takeIf { it.isFile && it.length() > 0L } ?: builtApk
    }.getOrDefault(builtApk)
}

internal fun findAndroidProjectRoot(workspace: File): File? {
    val settingsNames = setOf("settings.gradle", "settings.gradle.kts", "settings.gradle.dcl")
    return workspace.walkTopDown().maxDepth(4)
        .filter { it.isFile && it.name in settingsNames }
        .mapNotNull(File::getParentFile)
        .sortedBy { it.absolutePath.length }
        .firstOrNull { root ->
            root.walkTopDown().maxDepth(5)
                .any { it.isFile && it.invariantSeparatorsPath.endsWith("src/main/AndroidManifest.xml") }
        }
}

internal fun findDebugApk(projectRoot: File): File? {
    val metadata = projectRoot.walkTopDown().maxDepth(8)
        .filter { it.isFile && it.name == "output-metadata.json" && "/outputs/apk/" in it.invariantSeparatorsPath }
        .maxByOrNull(File::lastModified)
    if (metadata != null) {
        runCatching {
            val json = JSONObject(metadata.readText())
            val elements = json.optJSONArray("elements") ?: return@runCatching null
            (0 until elements.length()).asSequence()
                .mapNotNull { elements.optJSONObject(it)?.optString("outputFile") }
                .map { File(metadata.parentFile, it) }
                .firstOrNull { it.isFile && it.length() > 0L }
        }.getOrNull()?.let { return it }
    }
    return projectRoot.walkTopDown().maxDepth(10)
        .filter {
            it.isFile &&
                it.extension.equals("apk", true) &&
                "/outputs/apk/" in it.invariantSeparatorsPath &&
                "/debug/" in it.invariantSeparatorsPath
        }
        .maxByOrNull(File::lastModified)
}

/** Returns the last debug APK only when every build input is older than it. */
internal fun findReusableDebugApk(projectRoot: File): File? {
    val apk = findDebugApk(projectRoot) ?: return null
    val ignoredDirectories = setOf("build", ".gradle", ".idea", ".git")
    val buildInputExtensions = setOf(
        "kt", "java", "xml", "gradle", "kts", "properties", "toml", "pro", "json", "jar", "aar",
    )
    val changedAfterApk = projectRoot.walkTopDown()
        .onEnter { directory -> directory == projectRoot || directory.name !in ignoredDirectories }
        .filter { file ->
            if (!file.isFile) return@filter false
            val relative = file.relativeTo(projectRoot).invariantSeparatorsPath
            val isAndroidSource = relative.startsWith("src/") || "/src/" in relative
            isAndroidSource || file.extension.lowercase() in buildInputExtensions ||
                file.name in setOf("gradlew", "gradlew.bat")
        }
        .any { it.lastModified() > apk.lastModified() }
    return apk.takeUnless { changedAfterApk }
}

internal fun isDebugApkStale(projectRoot: File, apk: File): Boolean = findReusableDebugApk(projectRoot) != apk

internal fun inferAndroidBuildStage(output: String, current: AndroidBuildStage): AndroidBuildStage {
    val text = output.lowercase()
    return when {
        "packagedebug" in text || "assembledebug" in text && "> task" in text -> AndroidBuildStage.PACKAGING
        "merge" in text && "resource" in text || "processdebugresources" in text || "aapt2" in text -> AndroidBuildStage.RESOURCES
        "compiledebugkotlin" in text || "compiledebugjava" in text || "kotlinc" in text || "javac" in text -> AndroidBuildStage.COMPILING
        "download" in text || "resolve" in text || "dependency" in text -> AndroidBuildStage.RESOLVING
        else -> current
    }
}

internal fun parseAndroidBuildIssues(output: String, projectRoot: File, exitCode: Int): List<AndroidBuildIssue> {
    val issues = mutableListOf<AndroidBuildIssue>()
    val location = Regex("(?m)^(.+?\\.(?:kt|java|xml|gradle|kts)):(\\d+)(?::(\\d+))?[: ]+(?:error: )?(.+)$", RegexOption.IGNORE_CASE)
    location.findAll(output).take(12).forEach { match ->
        val rawPath = match.groupValues[1].trim()
        val file = File(rawPath)
        val relative = when {
            file.isAbsolute && runCatching { file.canonicalFile.toPath().startsWith(projectRoot.canonicalFile.toPath()) }.getOrDefault(false) ->
                file.canonicalFile.relativeTo(projectRoot.canonicalFile).invariantSeparatorsPath
            rawPath.startsWith("/workspace/") -> rawPath.substringAfter("/workspace/").substringAfter('/', "")
            else -> rawPath.takeIf { File(projectRoot, it).isFile }
        }
        issues += AndroidBuildIssue(
            title = "Source error",
            detail = match.groupValues[4].trim().take(400),
            suggestion = "Open the file and correct the reported line.",
            filePath = relative,
            line = match.groupValues[2].toIntOrNull(),
            column = match.groupValues[3].toIntOrNull(),
        )
    }
    if (issues.isNotEmpty()) return issues.distinctBy { Triple(it.filePath, it.line, it.detail) }
    val summary = diagnoseAndroidBuildFailure(output, exitCode)
    val lower = output.lowercase()
    val suggestion = when {
        "no space left" in lower -> "Free device storage, then retry the build."
        "could not resolve" in lower || "could not get resource" in lower -> "Check the network connection or use a cached dependency version."
        "manifest merger failed" in lower -> "Review duplicate or conflicting manifest declarations."
        "aapt2" in lower || "resource linking failed" in lower -> "Review resource names and XML diagnostics in the build output."
        "cmake" in lower || "ndk" in lower || "exec format error" in lower -> "Ensure ARM64 clang/lld/cmake/ninja are installed and run /root/.gradle/init.d/pocketdev-arm64-ndk-shim.sh before Gradle."
        "outofmemory" in lower || "heap space" in lower -> "Close other apps and retry."
        else -> "Open the build details for the complete Gradle output."
    }
    return listOf(AndroidBuildIssue(title = "Build failed", detail = summary, suggestion = suggestion))
}

internal fun diagnoseAndroidBuildFailure(output: String, exitCode: Int): String {
    val text = output.lowercase()
    return when {
        "no space left on device" in text -> "The device does not have enough free storage for this build."
        "outofmemoryerror" in text || "java heap space" in text -> "Gradle ran out of memory. Close other apps and retry."
        "could not resolve" in text || "could not get resource" in text -> "A project dependency is unavailable. Connect to the internet or use an offline-cached version."
        "manifest merger failed" in text -> "Android manifest merge failed. Open the build log for the conflicting declaration."
        "aapt2" in text || "android resource linking failed" in text -> "Android resources could not be compiled. Open the build log for the file and line."
        "exec format error" in text || ("cmake" in text && "failed" in text) || ("ndk" in text && "not configured" in text) ->
            "Native CMake/NDK build failed on ARM64. Verify ARM64 cmake, ninja, and clang-18 shims in /root/android-sdk."
        "compilation error" in text || "compilation failed" in text || "unresolved reference" in text -> "Kotlin or Java compilation failed. Open the build log for source errors."
        "minimum supported gradle version" in text || "maximum supported gradle" in text -> "This project's Gradle and Android plugin versions are incompatible."
        else -> "Gradle build failed (exit code $exitCode)."
    }
}
