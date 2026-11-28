package com.jarves.mh.ui

import android.app.Application
import android.app.ActivityManager
import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.os.SystemClock
import android.os.Build
import android.os.StatFs
import android.os.Debug
import android.system.Os
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.jarves.mh.BuildConfig
import com.jarves.mh.R
import com.jarves.mh.data.ApiKeyVault
import com.jarves.mh.data.ApiKeyInfo
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChatAttachment
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProjectType
import com.jarves.mh.model.AndroidTemplate
import com.jarves.mh.model.ProjectChat
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.WorkspaceEntry
import com.jarves.mh.model.projectSlug
import com.jarves.mh.model.generateQuickChatIdentity
import com.jarves.mh.model.providerProtocolForAgent
import com.jarves.mh.network.ConnectionValidation
import com.jarves.mh.network.ModelDiscoveryResult
import com.jarves.mh.multiagent.model.*
import com.jarves.mh.multiagent.locks.*
import com.jarves.mh.multiagent.events.*
import com.jarves.mh.multiagent.tasks.*
import com.jarves.mh.multiagent.chat.*
import com.jarves.mh.multiagent.git.*
import com.jarves.mh.multiagent.supervisor.*
import com.jarves.mh.provider.core.*
import com.jarves.mh.provider.manager.ProviderManager
import com.jarves.mh.network.ProviderApiClient
import com.jarves.mh.network.GitHubRepository
import com.jarves.mh.runtime.ClaudeRuntimeBridge
import com.jarves.mh.runtime.DshRuntimeBridge
import com.jarves.mh.runtime.AgentRegistry
import com.jarves.mh.runtime.AgentUpdateInfo
import com.jarves.mh.runtime.AntigravityAuthController
import com.jarves.mh.runtime.AntigravityAuthState
import com.jarves.mh.runtime.AntigravityAuthStatus
import com.jarves.mh.runtime.AntigravityRuntimeBridge
import com.jarves.mh.runtime.NativeSpawnProcess
import com.jarves.mh.runtime.RuntimeInstallProgress
import com.jarves.mh.runtime.RuntimeInstaller
import com.jarves.mh.runtime.RuntimeSetupController
import com.jarves.mh.runtime.DevStackInstallController
import com.jarves.mh.runtime.RuntimeSetupService
import com.jarves.mh.runtime.ToolInstallPermissionReceiver
import com.jarves.mh.runtime.RuntimeSetupSnapshot
import com.jarves.mh.runtime.RuntimeSetupStatus
import com.jarves.mh.runtime.readHeadBytes
import com.jarves.mh.runtime.readTailText
import com.jarves.mh.runtime.supportsArm64Runtime
import com.jarves.mh.runtime.AndroidAppInstaller
import com.jarves.mh.runtime.AndroidBuildPhase
import com.jarves.mh.runtime.AndroidBuildRecord
import com.jarves.mh.runtime.AndroidAction
import com.jarves.mh.runtime.AndroidApkInfo
import com.jarves.mh.runtime.AndroidBuildIssue
import com.jarves.mh.runtime.AndroidBuildStage
import com.jarves.mh.runtime.AndroidHealthCheck
import com.jarves.mh.runtime.AndroidHealthFix
import com.jarves.mh.runtime.AndroidHealthStatus
import com.jarves.mh.runtime.AndroidLogLevel
import com.jarves.mh.runtime.AndroidLogLine
import com.jarves.mh.runtime.AndroidLogcatState
import com.jarves.mh.runtime.AndroidProjectTemplateGenerator
import com.jarves.mh.runtime.RuntimeExecutionService
import com.jarves.mh.runtime.RuntimeTaskController
import com.jarves.mh.runtime.androidGradleCommand
import com.jarves.mh.runtime.androidGradleShellCommand
import com.jarves.mh.runtime.isMissingCachedDependencyError
import com.jarves.mh.runtime.publishCanonicalDebugApk
import com.jarves.mh.runtime.resolveDebugAssembleTask
import com.jarves.mh.runtime.diagnoseAndroidBuildFailure
import com.jarves.mh.runtime.findAndroidProjectRoot
import com.jarves.mh.runtime.findDebugApk
import com.jarves.mh.runtime.findReusableDebugApk
import com.jarves.mh.runtime.inferAndroidBuildStage
import com.jarves.mh.runtime.isDebugApkStale
import com.jarves.mh.runtime.parseAndroidBuildIssues
import com.jarves.mh.update.AppUpdateInfo
import com.jarves.mh.update.AppUpdater
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.net.UnknownHostException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class StartupStage { CHECKING, SETUP_REQUIRED, INSTALLING, MODEL_SETUP, INITIALIZING, READY, ERROR }

enum class ApiPingStatus { IDLE, PINGING, OK, FAILED }
enum class AppUpdateStatus { AVAILABLE, PERMISSION_REQUIRED, DOWNLOADING, INSTALLING, ERROR }
enum class GitHubAuthStatus { DISCONNECTED, STARTING, AWAITING_USER, CONNECTED, ERROR }

data class ExportSelection(
    val defaultIncluded: Boolean = true,
    val overrides: Map<String, Boolean> = emptyMap(),
) {
    fun includes(path: String): Boolean {
        val normalized = path.trim('/')
        return overrides.entries
            .asSequence()
            .filter { (rule, _) -> normalized == rule || normalized.startsWith("$rule/") }
            .maxByOrNull { it.key.length }
            ?.value ?: defaultIncluded
    }

    fun canContainIncluded(path: String): Boolean {
        val normalized = path.trim('/')
        return includes(normalized) || overrides.any { (rule, included) ->
            included && rule.startsWith("$normalized/")
        }
    }

    fun toggled(path: String, directory: Boolean): ExportSelection {
        val normalized = path.trim('/')
        val nextValue = !includes(normalized)
        val next = overrides.toMutableMap()
        if (directory) next.keys.filter { it.startsWith("$normalized/") }.forEach(next::remove)
        next[normalized] = nextValue
        return copy(overrides = next)
    }
}

data class DeveloperDiagnostics(
    val loading: Boolean = false,
    val appPssBytes: Long = 0L,
    val javaHeapBytes: Long = 0L,
    val javaHeapMaxBytes: Long = 0L,
    val nativeHeapBytes: Long = 0L,
    val graphicsMemoryBytes: Long = 0L,
    val activeThreadCount: Int = 0,
    val runningProotProcesses: Int = 0,
    val responseCacheBytes: Long = 0L,
    val gcCount: Long = 0L,
    val memoryPressureLevel: String = "NORMAL",
    val adaptiveConcurrencyLimit: Int = 4,
    val activeProcesses: List<String> = emptyList(),
    val messageCount: Int = 0,
    val liveActivityCount: Int = 0,
    val terminalBufferBytes: Long = 0L,
    val buildLogBytes: Long = 0L,
    val logcatLines: Int = 0,
    val fileEntries: Int = 0,
    val runtimeCacheBytes: Long = 0L,
    val recentExit: String? = null,
    val refreshedAtMillis: Long? = null,
)

/** Counts UTF-8 storage without allocating a second byte array for large logs. */
internal fun utf8SizeInBytes(value: String): Long {
    var bytes = 0L
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char.code < 0x80 -> bytes += 1
            char.code < 0x800 -> bytes += 2
            char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                bytes += 4
                index += 1
            }
            else -> bytes += 3
        }
        index += 1
    }
    return bytes
}

enum class FileReadOnlyReason { TOO_LARGE, BINARY, INVALID_UTF8, UNSAFE, READ_ERROR }

data class OpenedFileState(
    val path: String,
    val content: String? = null,
    val draft: String = "",
    val loading: Boolean = true,
    val editing: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val conflict: Boolean = false,
    val readOnlyReason: FileReadOnlyReason? = null,
    val originalFingerprint: String? = null,
    val lineEnding: String = "\n",
    val hadTrailingNewline: Boolean = false,
    val targetLine: Int? = null,
    val targetColumn: Int? = null,
)

data class TerminalOutputLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val command: String,
    val output: String,
    val exitCode: Int = 0,
)

private val ANSI_TERMINAL_SEQUENCE = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")

internal fun sanitizeTerminalOutput(text: String): String = text
    .replace(ANSI_TERMINAL_SEQUENCE, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }

private val ANTIGRAVITY_MODEL_EFFORT = Regex("^(.*)-(low|medium|high)$")

private fun antigravityEffortFromModel(model: String): String? =
    ANTIGRAVITY_MODEL_EFFORT.matchEntire(model)?.groupValues?.get(2)

private fun antigravityModelWithEffort(model: String, effort: String): String? {
    val match = ANTIGRAVITY_MODEL_EFFORT.matchEntire(model) ?: return null
    return "${match.groupValues[1]}-$effort"
}

private data class ProjectTerminalSnapshot(
    val lines: List<TerminalOutputLine> = emptyList(),
    val cwd: String = "/workspace",
)

private data class ProjectTerminalResult(
    val output: String,
    val exitCode: Int,
    val cwd: String,
)

private data class RuntimeRetryRequest(
    val runtime: com.jarves.mh.runtime.RuntimeBridge,
    val project: Project,
    val prompt: String,
    val history: List<ChatMessage>,
    val provider: ProviderProfile,
)

data class QueuedFollowUp(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val prompt: String,
    val attachments: List<ChatAttachment>,
)

private data class TranscriptWrite(
    val projectId: String,
    val chatId: String,
    val messages: List<ChatMessage>,
)

private data class ImportedZipProject(
    val project: Project,
    val sourceAttachment: ChatAttachment,
)

enum class TeamScreenMode {
    PROJECT_LIST,
    DASHBOARD,
    AGENT_DETAIL,
    QUOTA_DASHBOARD,
    ACTIVITY_FEED,
    GROUP_CHAT,
    PRIVATE_CHAT,
    PROVIDER_SETTINGS,
}

data class AppUiState(
    val startupStage: StartupStage = StartupStage.CHECKING,
    val startupProgress: Float = 0f,
    val startupMessage: String = "Checking this device…",
    val startupBytes: Pair<Long, Long>? = null,
    val startupLogs: List<String> = emptyList(),
    val startupIndeterminate: Boolean = false,
    val startupError: String? = null,
    val startupErrorIsOffline: Boolean = false,
    val showDetailedSetupProgress: Boolean = false,
    val onboardingComplete: Boolean = false,
    val backgroundSetupComplete: Boolean = false,
    val initialLanguageSelected: Boolean = false,
    val provider: ProviderProfile = ProviderProfile(ProviderKind.ANTHROPIC),
    val activeApiKeyName: String? = null,
    val themeMode: com.jarves.mh.ui.theme.AppThemeMode = com.jarves.mh.ui.theme.AppThemeMode.DARK,
    val languageCode: String = "system",
    val ownerDisplayName: String = "",
    val teamTypingAgentName: String? = null,
    val teamTypingAgentSlot: AgentSlot? = null,
    val teamTypingAgents: Map<AgentSlot, String> = emptyMap(),
    val teamStreamingAgentName: String? = null,
    val teamStreamingAgentSlot: AgentSlot? = null,
    val teamStreamingText: String = "",
    val teamAwaitingStartConfirmation: Boolean = false,
    val teamGitPushRunning: Boolean = false,
    val teamActiveProjectHasGitRemote: Boolean = false,
    val teamAutoPushGitEnabled: Boolean = false,
    val apiPingStatus: ApiPingStatus = ApiPingStatus.IDLE,
    val apiPingMessage: String? = null,
    val projects: List<Project> = emptyList(),
    val teamProjects: List<Project> = emptyList(),
    val activeTeamProject: Project? = null,
    val runningTeamProjectId: String? = null,
    val teamPriorityDirectivesCount: Int = 0,
    val teamAgentLiveLogs: Map<AgentSlot, List<String>> = emptyMap(),
    val teamBannerHealth: com.jarves.mh.multiagent.ui.TeamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.RUNNING,
    val teamBannerMessage: String? = null,
    val teamBannerDetail: String? = null,
    val activeSupervisedTaskId: String? = null,
    val supervisorSnapshots: Map<String, com.jarves.mh.multiagent.supervisor.SupervisorUiSnapshot> = emptyMap(),
    val teamTerminalOutput: String = "",
    val teamTerminalRunning: Boolean = false,
    val projectImporting: Boolean = false,
    val projectImportMessage: String? = null,
    val gitCloneRunning: Boolean = false,
    val gitCloneMessage: String? = null,
    val githubAuthStatus: GitHubAuthStatus = GitHubAuthStatus.DISCONNECTED,
    val githubLogin: String? = null,
    val githubUserCode: String? = null,
    val githubVerificationUri: String? = null,
    val githubMessage: String? = null,
    val githubRepositories: List<GitHubRepository> = emptyList(),
    val githubRepositoriesLoading: Boolean = false,
    val activeProject: Project? = null,
    val workspaceVisible: Boolean = false,
    val readOnlyProject: Project? = null,
    val readOnlyProjectChats: List<ProjectChat> = emptyList(),
    val readOnlyChatId: String? = null,
    val readOnlyMessages: List<ChatMessage> = emptyList(),
    val projectChats: List<ProjectChat> = emptyList(),
    val activeChatId: String? = null,
    val workspaceFiles: List<WorkspaceEntry> = emptyList(),
    val androidProjectDetected: Boolean = false,
    val filesLoading: Boolean = false,
    val diagnostics: DeveloperDiagnostics = DeveloperDiagnostics(),
    val openedFile: OpenedFileState? = null,
    val projectExportRunning: Boolean = false,
    val projectExportSucceededAtMillis: Long? = null,
    val messages: List<ChatMessage> = listOf(
        ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change."),
    ),
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val pendingApproval: ToolRequest? = null,
    val changes: List<ChangeItem> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
    val liveProcess: List<ActivityItem> = emptyList(),
    val liveThinking: Boolean = false,
    val activeThinkingBlockId: Long? = null,
    val taskStartedAtMillis: Long? = null,
    val taskFinishedAtMillis: Long? = null,
    val workSegmentStartedAtMillis: Long? = null,
    val currentTaskRequest: String? = null,
    val previewReady: Boolean = false,
    val previewUrl: String? = null,
    val isRunning: Boolean = false,
    val queuedFollowUps: List<QueuedFollowUp> = emptyList(),
    val activeSessionId: String? = null,
    val toastMessage: String? = null,
    val projectTerminalLines: List<TerminalOutputLine> = emptyList(),
    val projectTerminalLiveOutput: String = "",
    val projectTerminalRunning: Boolean = false,
    val projectTerminalCwd: String = "/workspace",
    val projectTerminalCommand: String? = null,
    val projectTerminalDraft: String? = null,
    val pendingTerminalCommand: String? = null,
    val suggestedProjectRoot: String? = null,
    val selectedDevStacks: Set<DevStack> = emptySet(),
    val installedDevStacks: Set<DevStack> = emptySet(),
    val devStackInstalling: DevStack? = null,
    val devStackRemoving: Boolean = false,
    val devStackMessage: String? = null,
    val devStackProgress: Float = 0f,
    val devStackBytes: Pair<Long, Long>? = null,
    val devStackBytesPerSecond: Long? = null,
    val autoInstallMissingTools: Boolean = false,
    val keepAliveInBackground: Boolean = true,
    val agentKind: AgentKind = AgentKind.CLAUDE_CODE,
    val primaryAgentKind: AgentKind = AgentKind.CLAUDE_CODE,
    val installedAgentVersions: Map<AgentKind, String> = emptyMap(),
    val agentInstalling: AgentKind? = null,
    val agentMessage: String? = null,
    val agentInstallError: String? = null,
    val agentInstallErrorKind: AgentKind? = null,
    val agentProgress: Float = 0f,
    val agentDownloadedBytes: Long? = null,
    val agentTotalBytes: Long? = null,
    val agentBytesPerSecond: Long? = null,
    val agentUpdates: Map<AgentKind, AgentUpdateInfo> = emptyMap(),
    val agentUpdatesChecking: Boolean = false,
    val agentUpdating: AgentKind? = null,
    val agentUpdateMessage: String? = null,
    val agentUpdateProgress: Float = 0f,
    val agentUpdateIndeterminate: Boolean = false,
    val agentUpdateDownloadedBytes: Long? = null,
    val agentUpdateTotalBytes: Long? = null,
    val agentUpdateBytesPerSecond: Long? = null,
    val antigravityAuth: AntigravityAuthState = AntigravityAuthState(),
    val teamAgentAntigravityAuth: Map<AgentSlot, AntigravityAuthState> = emptyMap(),
    val antigravityModel: String = "",
    val antigravityEffort: String = "high",
    val antigravityModels: List<String> = emptyList(),
    val antigravityModelsLoading: Boolean = false,
    val androidBuildRunning: Boolean = false,
    val androidBuildMessage: String? = null,
    val androidBuildPhase: AndroidBuildPhase = AndroidBuildPhase.IDLE,
    val androidBuildAction: AndroidAction = AndroidAction.NONE,
    val androidBuildStage: AndroidBuildStage = AndroidBuildStage.IDLE,
    val androidBuildIssues: List<AndroidBuildIssue> = emptyList(),
    val androidBuildLog: String = "",
    val androidBuildStartedAtMillis: Long? = null,
    val androidBuildFinishedAtMillis: Long? = null,
    val androidBuildApkPath: String? = null,
    val androidBuildApkSizeBytes: Long? = null,
    val androidBuildRecentDurationsMillis: List<Long> = emptyList(),
    val androidApkInfo: AndroidApkInfo? = null,
    val androidHealthChecks: List<AndroidHealthCheck> = emptyList(),
    val androidHealthRunning: Boolean = false,
    val androidLogcat: AndroidLogcatState = AndroidLogcatState(),
    val appUpdate: AppUpdateInfo? = null,
    val appUpdateStatus: AppUpdateStatus? = null,
    val appUpdateDownloadedBytes: Long = 0L,
    val appUpdateTotalBytes: Long = -1L,
    val appUpdateError: String? = null,
    val showWhatsNew: Boolean = false,
    val backupFeatureSeen: Boolean = true,
    val teamWorkspaceVisible: Boolean = false,
    val teamScreenMode: TeamScreenMode = TeamScreenMode.PROJECT_LIST,
    val teamAgents: List<AgentState> = emptyList(),
    val teamTasks: List<TeamTask> = emptyList(),
    val teamParts: List<ProjectPart> = emptyList(),
    val teamCurrentPartNumber: Int = 1,
    val teamFileLocks: List<FileLock> = emptyList(),
    val teamOfficialQuota: OfficialQuota? = null,
    val teamEstimatedUsage: EstimatedUsage? = null,
    val teamLocalUsage: LocalUsage = LocalUsage(),
    val teamIsRunning: Boolean = false,
    val teamIsPaused: Boolean = false,
    val teamGroupMessages: List<GroupChatMessage> = emptyList(),
    val providerConfigs: List<ProviderConfig> = emptyList(),
    val activePrivateSlot: AgentSlot? = null,
    val budgetLimitUsd: Double = 10.0,
    val budgetAutoPauseEnabled: Boolean = true,
    val teamCollaborationEvents: List<PrioritizedCollaborationEvent> = emptyList(),
    val soloContinuousConfig: ContinuousExecutionConfig = ContinuousExecutionConfig(),
    val teamContinuousConfig: ContinuousExecutionConfig = ContinuousExecutionConfig(),
    val teamAllProviderQuotas: Map<String, OfficialQuota> = emptyMap(),
    val teamAllProviderEstimated: Map<String, EstimatedUsage> = emptyMap(),
    val teamAllProviderLocal: Map<String, LocalUsage> = emptyMap(),
    val soloOfficialQuota: OfficialQuota? = null,
    val soloEstimatedUsage: EstimatedUsage? = null,
    val soloLocalUsage: LocalUsage = LocalUsage(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    /** Resolves a user-visible string in the language selected in the app. */
    private fun str(@StringRes id: Int, vararg args: Any): String = AppStrings.get(getApplication(), id, *args)

    private fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        AppStrings.context(getApplication()).resources.getQuantityString(id, count, *args)
    private data class FolderMetadata(val modifiedAtMillis: Long, val childCount: Int)

    private val vault = ApiKeyVault(application)
    private val preferences = AppPreferences(application)
    private val claudeRuntime = ClaudeRuntimeBridge(application) { profile -> vault.get(profile.kind.name) }
    private val dshRuntime = DshRuntimeBridge(application) { profile -> vault.get(profile.kind.name) }
    private val installer = RuntimeInstaller(application)
    private val antigravityRuntime = AntigravityRuntimeBridge(
        application,
        model = { _state.value.antigravityModel },
        effort = { _state.value.antigravityEffort },
        conversationId = { projectId ->
            _state.value.activeChatId?.let { preferences.loadAgentConversation(AgentKind.ANTIGRAVITY, projectId, it) }
        },
        saveConversationId = { projectId, id ->
            _state.value.activeChatId?.let { preferences.saveAgentConversation(AgentKind.ANTIGRAVITY, projectId, it, id) }
        },
    )
    private val agentRegistry = AgentRegistry.builtIns(claudeRuntime, dshRuntime, antigravityRuntime)
    private fun activeRuntime(): com.jarves.mh.runtime.RuntimeBridge = agentRegistry.require(_state.value.agentKind).runtime
    private val providerApi = ProviderApiClient(application)
    val providerManager = ProviderManager(application, vault, preferences)
    val fileLockManager = FileLockManager()
    val teamEventBus = TeamEventBus()
    private val teamGitPrefs by lazy {
        getApplication<Application>().getSharedPreferences("orqen_team_git_prefs", android.content.Context.MODE_PRIVATE)
    }
    private val pendingTeamImageFiles = java.util.concurrent.ConcurrentLinkedQueue<File>()
    val taskManager by lazy {
        val defaultWs = File(getApplication<Application>().filesDir, "team_workspaces/${TaskManager.DEFAULT_PROJECT_ID}")
            .apply { mkdirs() }
            .canonicalFile
            .absolutePath
        TaskManager(durableStore = orqenTaskStore, defaultWorkspacePath = defaultWs)
    }
    val continuousSupervisor = ContinuousSupervisor()
    val soloSupervisor = ContinuousSupervisor()
    val autonomousEngine = AutonomousExecutionEngine(fileLockManager)
    private val teamPrefs = application.getSharedPreferences("orqen_team_agents", Context.MODE_PRIVATE)
    private val teamChatManager = TeamChatManager(application, "default_team")
    val gitCoordinator = GitCoordinator(File(application.filesDir, "workspaces"))
    private fun appUpdater(): AppUpdater = AppUpdater(
        getApplication(),
        if (BuildConfig.DEBUG) preferences.debugUpdateManifestUrl else "",
    )
    @Volatile private var projectTerminalProcess: Process? = null
    @Volatile private var terminalProcess: Process? = null
    @Volatile private var projectTerminalProjectId: String? = null
    @Volatile private var projectTerminalStopRequested: Boolean = false
    @Volatile private var androidBuildProcess: Process? = null
    @Volatile private var androidBuildStopRequested: Boolean = false
    @Volatile private var androidLogcatProcess: Process? = null
    private var androidLogcatJob: kotlinx.coroutines.Job? = null
    private var diagnosticsJob: kotlinx.coroutines.Job? = null
    private var lastDiagnosticsRefreshAtElapsedMillis = 0L
    private var cachedRecentExitSummary: String? = null
    private var recentExitLoadedAtElapsedMillis = 0L
    private var pendingAndroidInstallId: String? = null
    private var pendingAndroidInstallLaunch = false
    private var androidLaunchAtMillis: Long? = null
    @Volatile private var setupCompletionHandled: Boolean = false
    @Volatile private var githubAuthProcess: Process? = null
    private var githubAuthJob: kotlinx.coroutines.Job? = null
    private var teamCycleJob: kotlinx.coroutines.Job? = null
    private val activeTeamChatJobs = java.util.concurrent.ConcurrentHashMap<AgentSlot, kotlinx.coroutines.Job>()
    private val pendingTeamDirectives = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val teamProjectChats = mutableMapOf<String, MutableList<GroupChatMessage>>()
    private val teamProjectTerminals = mutableMapOf<String, String>()

    private fun projectChatFile(projectId: String): File =
        File(File(getApplication<Application>().filesDir, "team_workspaces/$projectId").apply { mkdirs() }, ".orqen_chat.json")

    private fun loadProjectTeamChat(projectId: String): MutableList<GroupChatMessage> {
        teamProjectChats[projectId]?.let { return it }
        val loaded = mutableListOf<GroupChatMessage>()
        val file = projectChatFile(projectId)
        if (file.isFile) {
            runCatching {
                val arr = org.json.JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val slotId = obj.optString("senderSlot").takeIf { it.isNotBlank() }
                    val typeName = obj.optString("messageType", GroupMessageType.NORMAL.name)
                    val msgType = runCatching { GroupMessageType.valueOf(typeName) }.getOrDefault(GroupMessageType.NORMAL)
                    val details = mutableListOf<String>()
                    obj.optJSONArray("workDetails")?.let { dArr ->
                        for (j in 0 until dArr.length()) details += dArr.optString(j)
                    }
                    val imgs = mutableListOf<String>()
                    obj.optJSONArray("imagePaths")?.let { iArr ->
                        for (j in 0 until iArr.length()) imgs += iArr.optString(j)
                    }
                    loaded += GroupChatMessage(
                        id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                        senderSlot = slotId?.let { AgentSlot.fromId(it) },
                        senderName = obj.optString("senderName"),
                        text = obj.optString("text"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        messageType = msgType,
                        relatedFilePath = obj.optString("relatedFilePath").takeIf { it.isNotBlank() },
                        workStatus = obj.optString("workStatus").takeIf { it.isNotBlank() },
                        workDetails = details,
                        imagePaths = imgs,
                        suggestedTaskPrompt = obj.optString("suggestedTaskPrompt").takeIf { it.isNotBlank() },
                        apkFilePath = obj.optString("apkFilePath").takeIf { it.isNotBlank() },
                    )
                }
            }
        }
        teamProjectChats[projectId] = loaded
        return loaded
    }

    private fun persistProjectTeamChat(projectId: String, messages: List<GroupChatMessage>) {
        val snapshot = messages.takeLast(200)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val arr = org.json.JSONArray()
                snapshot.forEach { msg ->
                    arr.put(
                        org.json.JSONObject().apply {
                            put("id", msg.id)
                            put("senderSlot", msg.senderSlot?.id.orEmpty())
                            put("senderName", msg.senderName)
                            put("text", msg.text)
                            put("timestamp", msg.timestamp)
                            put("messageType", msg.messageType.name)
                            put("relatedFilePath", msg.relatedFilePath.orEmpty())
                            put("workStatus", msg.workStatus.orEmpty())
                            put("workDetails", org.json.JSONArray(msg.workDetails))
                            put("imagePaths", org.json.JSONArray(msg.imagePaths))
                            put("suggestedTaskPrompt", msg.suggestedTaskPrompt.orEmpty())
                            put("apkFilePath", msg.apkFilePath.orEmpty())
                        },
                    )
                }
                projectChatFile(projectId).writeText(arr.toString())
            }
        }
    }
    val chatDraftStore by lazy {
        com.jarves.mh.data.ChatDraftStore(
            storageFile = File(getApplication<Application>().filesDir, "orqen_chat_drafts.json"),
            taskStore = orqenTaskStore,
        )
    }

    fun loadSingleChatDraft(projectId: String?, chatId: String?): String {
        if (projectId.isNullOrBlank()) return ""
        return chatDraftStore.getDraftText(chatDraftStore.singleProjectKey(projectId, chatId.orEmpty()))
    }

    fun saveSingleChatDraft(projectId: String?, chatId: String?, text: String, cursor: Int = text.length) {
        if (projectId.isNullOrBlank()) return
        chatDraftStore.saveDraft(
            conversationKey = chatDraftStore.singleProjectKey(projectId, chatId.orEmpty()),
            text = text,
            cursorPosition = cursor,
            projectId = projectId,
            conversationId = chatId.orEmpty().ifBlank { "default" },
            mode = com.jarves.mh.data.DraftChatMode.SOLO,
        )
    }

    fun clearSingleChatDraft(projectId: String?, chatId: String?) {
        if (projectId.isNullOrBlank()) return
        chatDraftStore.clearDraft(chatDraftStore.singleProjectKey(projectId, chatId.orEmpty()))
    }

    fun loadTeamChatDraft(projectId: String?, conversationId: String = "group"): String {
        if (projectId.isNullOrBlank()) return ""
        return chatDraftStore.getDraftText(chatDraftStore.teamProjectKey(projectId, conversationId))
    }

    fun saveTeamChatDraft(projectId: String?, text: String, cursor: Int = text.length, conversationId: String = "group") {
        if (projectId.isNullOrBlank()) return
        chatDraftStore.saveDraft(
            conversationKey = chatDraftStore.teamProjectKey(projectId, conversationId),
            text = text,
            cursorPosition = cursor,
            projectId = projectId,
            conversationId = conversationId,
            mode = com.jarves.mh.data.DraftChatMode.TEAM_CHAT,
        )
    }

    fun clearTeamChatDraft(projectId: String?, conversationId: String = "group") {
        if (projectId.isNullOrBlank()) return
        chatDraftStore.clearDraft(chatDraftStore.teamProjectKey(projectId, conversationId))
    }

    fun updateTeamProjectIdentity(
        projectId: String,
        type: ProjectType,
        iconKey: String,
        customIconPath: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val updated = _state.value.teamProjects.map { p ->
            if (p.id == projectId) p.copy(
                type = type,
                iconKey = iconKey,
                customIconPath = customIconPath,
                updatedAtMillis = now,
            ) else p
        }
        val activeUpdated = _state.value.activeTeamProject?.let { cur ->
            if (cur.id == projectId) cur.copy(type = type, iconKey = iconKey, customIconPath = customIconPath, updatedAtMillis = now) else cur
        }
        _state.update { it.copy(teamProjects = updated, activeTeamProject = activeUpdated, toastMessage = "Updated project type & icon") }
        preferences.saveTeamProjects(updated)
    }

    fun updateSingleProjectIdentity(
        projectId: String,
        type: ProjectType,
        iconKey: String,
        customIconPath: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val updated = _state.value.projects.map { p ->
            if (p.id == projectId) p.copy(
                type = type,
                iconKey = iconKey,
                customIconPath = customIconPath,
                updatedAtMillis = now,
            ) else p
        }
        val activeUpdated = _state.value.activeProject?.let { cur ->
            if (cur.id == projectId) cur.copy(type = type, iconKey = iconKey, customIconPath = customIconPath, updatedAtMillis = now) else cur
        }
        _state.update { it.copy(projects = updated, activeProject = activeUpdated, toastMessage = "Updated project type & icon") }
        preferences.saveProjects(updated)
    }

    val skillManager by lazy {
        com.jarves.mh.skills.SkillManager(getApplication())
    }
    private val teamAgentRunner by lazy {
        com.jarves.mh.runtime.TeamAgentRunner(getApplication(), installer, providerManager, skillManager = skillManager)
    }
    private val orqenDbHelper by lazy {
        com.jarves.mh.multiagent.persistence.OrqenTaskDatabaseHelper(getApplication())
    }
    private val orqenTaskStore by lazy {
        com.jarves.mh.multiagent.persistence.SqliteOrqenTaskStore(orqenDbHelper)
    }
    private val checkpointManager by lazy {
        com.jarves.mh.multiagent.engine.CheckpointManager(orqenTaskStore)
    }
    private val recoverySupervisor by lazy {
        com.jarves.mh.multiagent.engine.ExecutionRecoverySupervisor(
            store = orqenTaskStore,
            checkpointManager = checkpointManager,
            fileLockManager = fileLockManager,
        )
    }
    private val collaborationStore by lazy {
        com.jarves.mh.multiagent.collab.SqliteCollaborationStore(orqenDbHelper)
    }
    private val persistentLivenessMonitor by lazy {
        com.jarves.mh.multiagent.recovery.ExecutionLivenessMonitor(orqenTaskStore)
    }
    private val teamRuntimeAdapter by lazy {
        com.jarves.mh.multiagent.engine.TeamAgentRunnerRuntimeAdapter(
            runner = teamAgentRunner,
            allowSandboxedCliTools = true,
            fileLockManager = fileLockManager,
            mcpToolInvoker = { agentId, workspaceId, taskId, toolId, argsJson ->
                val res = mcpConnectionManager.executeToolForAgent(
                    agentId = agentId,
                    agentRole = "agent",
                    namespacedToolId = toolId,
                    argumentsJson = argsJson,
                    workspaceId = workspaceId,
                    taskId = taskId,
                    activeTaskAgentIds = setOf(agentId),
                    autoRequestFromSpv = true,
                )
                res.success to res.contentText
            },
        )
    }
    val mcpConnectionManager by lazy {
        com.jarves.mh.mcp.McpConnectionManager(
            store = com.jarves.mh.mcp.SqliteMcpStore(orqenDbHelper),
            secretStore = com.jarves.mh.mcp.VaultMcpSecretStore(vault),
            transportFactory = { cfg ->
                when (cfg.transportType) {
                    com.jarves.mh.mcp.McpTransportType.STDIO ->
                        com.jarves.mh.mcp.StdioMcpTransportClient(
                            serverId = cfg.serverId,
                            installer = installer,
                            workspaceResolver = { wsId ->
                                val teamWs = _state.value.teamProjects.firstOrNull { it.id == wsId }?.rootPath
                                val projWs = _state.value.projects.firstOrNull { it.id == wsId }?.rootPath
                                val activeWs = _state.value.activeTeamProject?.rootPath ?: _state.value.activeProject?.rootPath
                                val path = teamWs?.takeIf { it.isNotBlank() }
                                    ?: projWs?.takeIf { it.isNotBlank() }
                                    ?: activeWs?.takeIf { it.isNotBlank() }
                                if (path != null) File(path) else getApplication<Application>().filesDir
                            },
                        )
                    com.jarves.mh.mcp.McpTransportType.STREAMABLE_HTTP ->
                        com.jarves.mh.mcp.StreamableHttpMcpTransportClient(cfg.serverId)
                    com.jarves.mh.mcp.McpTransportType.LEGACY_HTTP_SSE ->
                        com.jarves.mh.mcp.LegacySseMcpTransportClient(cfg.serverId)
                }
            },
        )
    }
    val resourceManager = com.jarves.mh.multiagent.runtime.AgentResourceManager()

    val autonomousSupervisorEngine: com.jarves.mh.multiagent.supervisor.AutonomousSupervisorEngine by lazy {
        com.jarves.mh.multiagent.supervisor.AutonomousSupervisorEngine(
            taskStore = orqenTaskStore,
            collabStore = collaborationStore,
            runtime = teamRuntimeAdapter,
            checkpointManager = checkpointManager,
            fileLockManager = fileLockManager,
            livenessMonitor = persistentLivenessMonitor,
            limits = com.jarves.mh.multiagent.supervisor.RecoveryLimitsConfig(
                autoContinueAfterCheckpoint = true,
            ),
            mcpManager = mcpConnectionManager,
            resourceManager = resourceManager,
            modelPoolProvider = {
                val isAgySignedIn = _state.value.antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN ||
                    _state.value.teamAgentAntigravityAuth.values.any { it.status == AntigravityAuthStatus.SIGNED_IN }
                val pool = com.jarves.mh.multiagent.supervisor.TeamModelIntelligence.buildModelPool(
                    providerConfigs = providerManager.configs.value,
                    hasApiKey = { id -> getProviderApiKey(id).isNotBlank() },
                    isAntigravitySignedIn = isAgySignedIn,
                    knownAgyModels = _state.value.antigravityModels,
                )
                com.jarves.mh.multiagent.supervisor.TeamModelIntelligence.toModelCandidates(pool)
            },
            providerConfigResolver = { providerId ->
                providerManager.configs.value.firstOrNull { it.id == providerId }
            },
            chatProjectionSink = com.jarves.mh.multiagent.collab.TeamChatManagerBridge.createSink(teamChatManager),
            onStepLifecycleUpdate = { upd ->
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val slot = AgentSlot.fromId(upd.agentId) ?: AgentSlot.AGENT_1
                    val displayName = agentNameFor(slot).ifBlank { upd.agentDisplayName }
                    when (upd.phase) {
                        "PLANNED" -> {
                            val supName = agentNameFor(AgentSlot.AGENT_1)
                            // If SPV already delivered a natural AI-authored planning briefing for this submission,
                            // keep the structured DAG summary in internal live logs instead of posting a robotic duplicate bubble.
                            if (spvAiBriefedCurrentSubmission) {
                                appendAgentLiveLog(AgentSlot.AGENT_1, upd.detail)
                                spvAiBriefedCurrentSubmission = false
                            } else {
                                appendProjectTeamMessage(
                                    projectId = upd.projectId,
                                    senderSlot = AgentSlot.AGENT_1,
                                    senderName = supName,
                                    text = upd.detail,
                                    messageType = GroupMessageType.TASK_ASSIGNMENT,
                                )
                            }
                            _state.update { it.copy(teamTypingAgentName = null, teamTypingAgentSlot = null) }
                            val anyWorkerExecuting = _state.value.teamAgents.any { agent ->
                                agent.config.slot != AgentSlot.AGENT_1 && agent.activityState.activity in setOf(
                                    ActivityType.WRITING_CODE,
                                    ActivityType.REVIEWING,
                                    ActivityType.FIXING_BUG,
                                    ActivityType.ANALYZING,
                                )
                            }
                            if (!anyWorkerExecuting) {
                                updateAgentActivity(AgentSlot.AGENT_1, ActivityType.PLANNING, upd.stepTitle, "แผนพร้อมแล้ว กำลังส่งต่องาน")
                            }
                        }
                        "REMEDIATION" -> {
                            val supName = agentNameFor(AgentSlot.AGENT_1)
                            appendAgentLiveLog(AgentSlot.AGENT_1, upd.detail)
                            appendProjectTeamMessage(
                                projectId = upd.projectId,
                                senderSlot = AgentSlot.AGENT_1,
                                senderName = supName,
                                text = upd.detail,
                                messageType = GroupMessageType.PROGRESS_UPDATE,
                                workStatus = "Working",
                            )
                        }
                        "STARTED" -> {
                            val act = when {
                                upd.stepTitle.contains("verify", ignoreCase = true) || upd.stepTitle.contains("review", ignoreCase = true) -> ActivityType.REVIEWING
                                upd.stepTitle.contains("inspect", ignoreCase = true) || upd.stepTitle.contains("plan", ignoreCase = true) -> ActivityType.ANALYZING
                                upd.stepTitle.contains("fix", ignoreCase = true) -> ActivityType.FIXING_BUG
                                else -> ActivityType.WRITING_CODE
                            }
                            clearAgentLiveLog(slot)
                            val supName = agentNameFor(AgentSlot.AGENT_1)
                            // P0: SPV is the sole Owner-facing supervisor! Internal worker steps are reported
                            // to the Owner concisely by SPV while raw worker activity stays in internal logs.
                            val spvUpdateText = com.jarves.mh.multiagent.collab.SpvConversationRouter.formatSpvStatusReport(
                                phase = "STARTED",
                                workerName = displayName,
                                stepNumber = upd.stepNumber,
                                totalSteps = upd.totalSteps,
                                stepTitle = upd.stepTitle,
                                detail = upd.detail,
                            )
                            appendAgentLiveLog(slot, upd.detail.ifBlank { "Started '${upd.stepTitle}'" })
                            appendProjectTeamMessage(
                                projectId = upd.projectId,
                                senderSlot = AgentSlot.AGENT_1,
                                senderName = supName,
                                text = spvUpdateText,
                                messageType = GroupMessageType.PROGRESS_UPDATE,
                                workStatus = "Working",
                            )
                            _state.update { it.copy(teamTypingAgentName = null, teamTypingAgentSlot = null) }
                            val isThai = isThaiUiLanguage()
                            if (slot != AgentSlot.AGENT_1) {
                                val monitorText = if (isThai) "กำลังติดตามงานของ $displayName" else "Monitoring $displayName"
                                updateAgentActivity(AgentSlot.AGENT_1, ActivityType.WAITING, "", monitorText)
                            }
                            val actLabel = when (act) {
                                ActivityType.REVIEWING -> if (isThai) "กำลังตรวจสอบ/รีวิว" else "Reviewing & QA"
                                ActivityType.ANALYZING -> if (isThai) "กำลังวิเคราะห์" else "Analyzing"
                                ActivityType.FIXING_BUG -> if (isThai) "กำลังแก้บั๊ก" else "Fixing bug"
                                else -> if (isThai) "กำลังเขียนโค้ด" else "Writing code"
                            }
                            updateAgentActivity(slot, act, upd.stepTitle, "$actLabel: ${upd.stepTitle.take(60)}")
                            _state.value.teamAgents.firstOrNull { it.config.slot == slot }?.let { ag ->
                                autonomousSupervisorEngine.markAgentConnected(slot.id, ag.config.providerId, ag.config.modelId)
                            }
                            val stepPrefix = if (upd.stepNumber > 0 && upd.totalSteps > 0) {
                                if (isThai) "ขั้น ${upd.stepNumber}/${upd.totalSteps} · " else "Step ${upd.stepNumber}/${upd.totalSteps} · "
                            } else ""
                            updateTeamForegroundNotification(
                                _state.value.activeTeamProject?.name ?: "ORQEN",
                                "$stepPrefix$displayName ($actLabel): ${upd.stepTitle.take(60)}",
                            )
                        }
                        "PROGRESS" -> {
                            if (upd.detail.isNotBlank()) {
                                val isThai = isThaiUiLanguage()
                                val (humanProgress, activeFile) = formatLiveProgressForUser(upd.detail, upd.stepTitle, isThai)
                                appendAgentLiveLog(slot, humanProgress)
                                val currentAct = _state.value.teamAgents
                                    .firstOrNull { it.config.slot == slot }
                                    ?.activityState?.activity
                                    ?.takeIf { it != ActivityType.WAITING && it != ActivityType.COMPLETED && it != ActivityType.ERROR }
                                    ?: if (upd.stepTitle.contains("review", ignoreCase = true) || upd.stepTitle.contains("verify", ignoreCase = true)) {
                                        ActivityType.REVIEWING
                                    } else ActivityType.WRITING_CODE
                                updateAgentActivity(slot, currentAct, upd.stepTitle, humanProgress, activeFile)
                                // Clear any stale PROVIDER_ERROR/DISCONNECTED banner once the agent recovers and emits real progress!
                                _state.value.teamAgents.firstOrNull { it.config.slot == slot }?.let { ag ->
                                    autonomousSupervisorEngine.markAgentConnected(slot.id, ag.config.providerId, ag.config.modelId)
                                }
                                val now = System.currentTimeMillis()
                                if (now - lastNotifProgressUpdateMs >= 3_500L) {
                                    lastNotifProgressUpdateMs = now
                                    val stepPrefix = if (upd.stepNumber > 0 && upd.totalSteps > 0) {
                                        if (isThai) "ขั้น ${upd.stepNumber}/${upd.totalSteps} · " else "Step ${upd.stepNumber}/${upd.totalSteps} · "
                                    } else ""
                                    updateTeamForegroundNotification(
                                        _state.value.activeTeamProject?.name ?: "ORQEN",
                                        "$stepPrefix$displayName: ${humanProgress.take(75)}",
                                    )
                                }
                            }
                        }
                        "COMPLETED" -> {
                            val isThai = isThaiUiLanguage()
                            updateAgentActivity(slot, ActivityType.COMPLETED, upd.stepTitle, if (isThai) "เสร็จสิ้น" else "Completed")
                            _state.update { it.copy(teamTypingAgentName = null, teamTypingAgentSlot = null) }
                            val supName = agentNameFor(AgentSlot.AGENT_1)
                            val stepPrefix = if (upd.stepNumber > 0 && upd.totalSteps > 0) {
                                if (isThai) "เสร็จขั้น ${upd.stepNumber}/${upd.totalSteps} · " else "Done Step ${upd.stepNumber}/${upd.totalSteps} · "
                            } else {
                                if (isThai) "เสร็จสิ้น · " else "Completed · "
                            }
                            updateTeamForegroundNotification(
                                _state.value.activeTeamProject?.name ?: "ORQEN",
                                "$stepPrefix$displayName: ${upd.stepTitle.take(55)}",
                            )
                            // Keep raw worker output in internal live logs; SPV reports concise verified progress to Owner!
                            appendAgentLiveLog(slot, upd.detail.take(400))
                            val spvSummary = com.jarves.mh.multiagent.collab.SpvConversationRouter.formatSpvStatusReport(
                                phase = "COMPLETED",
                                workerName = displayName,
                                stepNumber = upd.stepNumber,
                                totalSteps = upd.totalSteps,
                                stepTitle = upd.stepTitle,
                                modifiedFiles = upd.modifiedFiles,
                                detail = upd.detail,
                            )
                            appendProjectTeamMessage(
                                projectId = upd.projectId,
                                senderSlot = AgentSlot.AGENT_1,
                                senderName = supName,
                                text = spvSummary,
                                messageType = GroupMessageType.COMPLETION_REPORT,
                                workStatus = "Done",
                                workDetails = upd.modifiedFiles.map { "Modified: $it" },
                            )
                        }
                        "RECOVERING", "FAILED" -> {
                            updateAgentActivity(slot, ActivityType.ERROR, upd.stepTitle, upd.detail.take(80))
                            _state.update { it.copy(teamTypingAgentName = null, teamTypingAgentSlot = null) }
                            val supName = agentNameFor(AgentSlot.AGENT_1)
                            appendAgentLiveLog(slot, "[${upd.phase}] ${upd.detail.take(300)}")
                            val spvRecoveryMsg = com.jarves.mh.multiagent.collab.SpvConversationRouter.formatSpvStatusReport(
                                phase = upd.phase,
                                workerName = displayName,
                                stepNumber = upd.stepNumber,
                                totalSteps = upd.totalSteps,
                                stepTitle = upd.stepTitle,
                                detail = upd.detail,
                            )
                            appendProjectTeamMessage(
                                projectId = upd.projectId,
                                senderSlot = AgentSlot.AGENT_1,
                                senderName = supName,
                                text = spvRecoveryMsg,
                                messageType = GroupMessageType.BUG_REPORT,
                            )
                        }
                    }
                }
            },
        )
    }
    private var activeSupervisedTaskId: String? = null
    @Volatile private var spvAiBriefedCurrentSubmission: Boolean = false
    private var lastNotifProgressUpdateMs: Long = 0L
    private var supervisorSnapshotJob: kotlinx.coroutines.Job? = null
    private val multiSessionController by lazy {
        com.jarves.mh.multiagent.session.MultiSessionChatController(orqenTaskStore)
    }
    private val hierarchicalMemory by lazy {
        com.jarves.mh.multiagent.session.HierarchicalMemoryManager(orqenTaskStore)
    }
    @Volatile private var lastOpenedAntigravityAuthUrl: String? = null
    private var activeRuntimeRequest: RuntimeRetryRequest? = null
    private val pendingFollowUps = java.util.ArrayDeque<QueuedFollowUp>()
    @Volatile private var steeringToFollowUp: Boolean = false
    private val failedApiKeyIds = mutableSetOf<String>()
    private val folderMetadataCache = java.util.concurrent.ConcurrentHashMap<String, FolderMetadata>()
    private val folderCountsInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Writes are drained into a per-chat batch below so rapid activity frames do not
    // serialize hundreds of obsolete snapshots.
    private val transcriptWrites = Channel<TranscriptWrite>(Channel.UNLIMITED)
    private val initialAgentKind = AgentKind.fromStored(preferences.agentKind)
    private val initialPrimaryAgentKind = preferences.primaryAgentKind
        .takeIf(String::isNotBlank)
        ?.let(AgentKind::fromStored)
        ?: initialAgentKind
    private val antigravityAuthController = AntigravityAuthController(
        application,
        preferences.antigravitySignedIn,
        preferences.antigravityAccountEmail,
    ) { signedIn, email ->
        preferences.antigravitySignedIn = signedIn
        preferences.antigravityAccountEmail = email.orEmpty()
        if (!signedIn) preferences.clearAgentConversations(AgentKind.ANTIGRAVITY)
    }
    private val teamAntigravityControllers: Map<AgentSlot, AntigravityAuthController> = AgentSlot.entries.associateWith { slot ->
        val slotKey = slot.name.lowercase()
        AntigravityAuthController(
            context = application,
            initiallySignedIn = preferences.isTeamAgentAntigravitySignedIn(slotKey),
            initialAccountEmail = preferences.getTeamAgentAntigravityEmail(slotKey),
            profileId = slotKey,
        ) { signedIn, email ->
            preferences.setTeamAgentAntigravitySignedIn(slotKey, signedIn)
            preferences.setTeamAgentAntigravityEmail(slotKey, email.orEmpty())
        }
    }
    private val _state = MutableStateFlow(
        AppUiState(
            startupMessage = str(R.string.vm_checking_device),
            onboardingComplete = preferences.onboardingComplete,
            backgroundSetupComplete = preferences.backgroundSetupComplete,
            initialLanguageSelected = preferences.initialLanguageSelected,
            agentKind = initialAgentKind,
            primaryAgentKind = initialPrimaryAgentKind,
            provider = preferences.loadProvider(vault, initialAgentKind),
            activeApiKeyName = vault.list(preferences.loadProvider(vault, initialAgentKind).kind.name)
                .firstOrNull(ApiKeyInfo::isActive)?.name,
            antigravityAuth = AntigravityAuthState(
                status = if (preferences.antigravitySignedIn) AntigravityAuthStatus.SIGNED_IN else AntigravityAuthStatus.SIGNED_OUT,
                message = preferences.antigravityAccountEmail.takeIf(String::isNotBlank)?.let { str(R.string.vm_connected_as, it) },
                accountEmail = preferences.antigravityAccountEmail.takeIf(String::isNotBlank),
            ),
            teamAgentAntigravityAuth = AgentSlot.entries.associateWith { slot ->
                val slotKey = slot.name.lowercase()
                val signedIn = preferences.isTeamAgentAntigravitySignedIn(slotKey)
                val email = preferences.getTeamAgentAntigravityEmail(slotKey)
                AntigravityAuthState(
                    status = if (signedIn) AntigravityAuthStatus.SIGNED_IN else AntigravityAuthStatus.SIGNED_OUT,
                    message = email.takeIf(String::isNotBlank)?.let { str(R.string.vm_connected_as, it) },
                    accountEmail = email.takeIf(String::isNotBlank),
                )
            },
            antigravityModel = preferences.antigravityModel,
            antigravityEffort = preferences.antigravityEffort,
            themeMode = runCatching { com.jarves.mh.ui.theme.AppThemeMode.valueOf(preferences.themeMode.uppercase()) }
                .getOrDefault(com.jarves.mh.ui.theme.AppThemeMode.DARK),
            languageCode = preferences.languageCode,
            ownerDisplayName = preferences.ownerDisplayName,
            projects = preferences.loadProjects(),
            teamProjects = preferences.loadTeamProjects(),
            activeTeamProject = preferences.loadTeamProjects().let { list ->
                val savedId = preferences.activeTeamProjectId
                list.firstOrNull { it.id == savedId } ?: list.firstOrNull()
            },
            githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
            githubLogin = preferences.githubLogin.takeIf(String::isNotBlank),
            selectedDevStacks = preferences.selectedDevStacks.mapNotNull { name ->
                runCatching { DevStack.valueOf(name) }.getOrNull()
            }.toSet() + DevStack.WEB,
            autoInstallMissingTools = preferences.autoInstallMissingTools,
            keepAliveInBackground = preferences.keepAliveInBackground,
            // Only people updating from an earlier version get the dialog; a new install has nothing "new".
            showWhatsNew = preferences.onboardingComplete && preferences.whatsNewSeenVersionCode < BuildConfig.VERSION_CODE,
            backupFeatureSeen = preferences.backupFeatureSeen,
        ),
    ).also {
        if (!preferences.onboardingComplete) preferences.whatsNewSeenVersionCode = BuildConfig.VERSION_CODE
    }

    fun dismissWhatsNew() {
        preferences.whatsNewSeenVersionCode = BuildConfig.VERSION_CODE
        _state.update { it.copy(showWhatsNew = false) }
    }

    fun markBackupFeatureSeen() {
        if (_state.value.backupFeatureSeen) return
        preferences.backupFeatureSeen = true
        _state.update { it.copy(backupFeatureSeen = true) }
    }

    init {
        viewModelScope.launch { initMultiAgentTeam() }
        viewModelScope.launch {
            var lastCompletedCounter = DevStackInstallController.snapshot.value.completedCounter
            DevStackInstallController.snapshot.collect { snap ->
                val justFinished = snap.completedCounter != lastCompletedCounter
                if (justFinished) lastCompletedCounter = snap.completedCounter
                val refreshedStacks = if (justFinished) {
                    runCatching { withContext(Dispatchers.IO) { installer.installedStacks() } }.getOrNull()
                } else null
                _state.update { current ->
                    current.copy(
                        devStackInstalling = snap.activeStack,
                        devStackRemoving = snap.removing,
                        devStackProgress = snap.progress,
                        devStackMessage = snap.message ?: snap.lastOutcomeMessage ?: current.devStackMessage,
                        devStackBytes = if (snap.downloadedBytes != null && snap.totalBytes != null) {
                            snap.downloadedBytes to snap.totalBytes
                        } else null,
                        devStackBytesPerSecond = snap.bytesPerSecond,
                        installedDevStacks = refreshedStacks ?: current.installedDevStacks,
                        toastMessage = if (justFinished && !snap.lastOutcomeMessage.isNullOrBlank()) {
                            snap.lastOutcomeMessage
                        } else current.toastMessage,
                        androidHealthRunning = if (justFinished && pendingAndroidHealthRefreshAfterStack) false else current.androidHealthRunning,
                    )
                }
                if (justFinished && pendingAndroidHealthRefreshAfterStack) {
                    pendingAndroidHealthRefreshAfterStack = false
                    refreshAndroidHealth()
                }
            }
        }
        viewModelScope.launch {
            AndroidAppInstaller.events.collect { event ->
                if (event.operationId != pendingAndroidInstallId) return@collect
                val project = _state.value.activeProject
                val launchRequested = pendingAndroidInstallLaunch
                pendingAndroidInstallId = null
                pendingAndroidInstallLaunch = false
                if (event.success) {
                    if (event.launched) androidLaunchAtMillis = System.currentTimeMillis()
                    val message = when {
                        event.launched -> str(R.string.an_app_installed_launched)
                        launchRequested -> str(R.string.an_app_installed_no_launcher)
                        else -> str(R.string.an_apk_installed)
                    }
                    _state.update {
                        it.copy(
                            androidBuildRunning = false,
                            androidBuildPhase = AndroidBuildPhase.SUCCEEDED,
                            androidBuildStage = AndroidBuildStage.COMPLETE,
                            androidBuildMessage = message,
                            androidBuildFinishedAtMillis = System.currentTimeMillis(),
                            toastMessage = message,
                        )
                    }
                    project?.let { saveAndroidBuildRecord(it.id) }
                    refreshAndroidApkInfo()
                } else {
                    val message = event.message ?: str(R.string.an_apk_install_failed)
                    _state.update {
                        it.copy(
                            androidBuildRunning = false,
                            androidBuildPhase = AndroidBuildPhase.FAILED,
                            androidBuildStage = AndroidBuildStage.FAILED,
                            androidBuildMessage = message,
                            androidBuildIssues = listOf(AndroidBuildIssue(title = str(R.string.rt_apk_install_failed), detail = message)),
                            androidBuildFinishedAtMillis = System.currentTimeMillis(),
                            toastMessage = message,
                        )
                    }
                    project?.let { saveAndroidBuildRecord(it.id) }
                }
            }
        }
        // GitHub's official CLI owns its OAuth credential. Remove credentials from
        // the retired custom OAuth implementation and discover the real CLI status.
        vault.remove(LEGACY_GITHUB_TOKEN_KEY)
        viewModelScope.launch { refreshGitHubConnection() }
        RuntimeSetupController.restore(application)
        viewModelScope.launch(Dispatchers.IO) {
            for (pending in transcriptWrites) {
                delay(TRANSCRIPT_WRITE_DEBOUNCE_MS)
                val latestByChat = linkedMapOf((pending.projectId to pending.chatId) to pending)
                while (true) {
                    val next = transcriptWrites.tryReceive().getOrNull() ?: break
                    latestByChat[next.projectId to next.chatId] = next
                }
                latestByChat.values.forEach { latest ->
                    preferences.saveMessages(latest.projectId, latest.chatId, latest.messages)
                }
            }
        }
        viewModelScope.launch { dshRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { antigravityRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch {
            antigravityAuthController.state.collect { auth ->
                _state.update { it.copy(antigravityAuth = auth) }
                auth.authorizationUrl?.takeIf { it != lastOpenedAntigravityAuthUrl }?.let { url ->
                    lastOpenedAntigravityAuthUrl = url
                    runCatching {
                        getApplication<Application>().startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        _state.update { state -> state.copy(toastMessage = str(R.string.vm_browser_open_failed_signin)) }
                    }
                }
            }
        }
        teamAntigravityControllers.forEach { (slot, controller) ->
            viewModelScope.launch {
                controller.state.collect { auth ->
                    _state.update { current ->
                        current.copy(teamAgentAntigravityAuth = current.teamAgentAntigravityAuth + (slot to auth))
                    }
                    auth.authorizationUrl?.takeIf { it != lastOpenedAntigravityAuthUrl }?.let { url ->
                        lastOpenedAntigravityAuthUrl = url
                        runCatching {
                            getApplication<Application>().startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            _state.update { state -> state.copy(toastMessage = str(R.string.vm_browser_open_failed_signin)) }
                        }
                    }
                }
            }
        }
        if (antigravityAuthController.hasOfficialCredential() &&
            (!preferences.antigravitySignedIn || preferences.antigravityAccountEmail.isBlank())
        ) {
            viewModelScope.launch { antigravityAuthController.beginLogin() }
        }
        if (!preferences.legacySeededCredentialRemoved) {
            vault.remove(ProviderKind.CUSTOM.name)
            preferences.legacySeededCredentialRemoved = true
            _state.update { current ->
                if (current.provider.kind == ProviderKind.CUSTOM) {
                    current.copy(provider = current.provider.copy(hasSecret = false))
                } else current
            }
        }
        if (
            BuildConfig.TEST_OPENROUTER_API_KEY.isNotBlank() &&
            preferences.testProviderDefaultsVersion < TEST_PROVIDER_DEFAULTS_VERSION
        ) {
            val testProvider = ProviderProfile(
                kind = ProviderKind.CUSTOM,
                baseUrl = TEST_OPENROUTER_BASE_URL,
                model = TEST_OPENROUTER_MODEL,
                hasSecret = true,
            )
            vault.put(ProviderKind.CUSTOM.name, BuildConfig.TEST_OPENROUTER_API_KEY)
            preferences.saveProvider(testProvider, _state.value.agentKind)
            preferences.testProviderDefaultsVersion = TEST_PROVIDER_DEFAULTS_VERSION
            _state.update { it.copy(provider = testProvider) }
        }

        val loadedProjects = preferences.loadProjects()
        val cleanedProjects = loadedProjects.filter { project ->
            if (project.kind == ProjectKind.QUICK_PROJECT) {
                val chats = preferences.loadProjectChats(project.id)
                val userMessages = chats.sumOf { preferences.loadMessages(project.id, it.id).count { m -> m.fromUser } }
                val workspaceDir = File(application.filesDir, "workspaces/${project.id}")
                val userFiles = if (workspaceDir.isDirectory) {
                    workspaceDir.walkTopDown().filter { file ->
                        file.isFile && !file.name.startsWith(".claude") && file.name != ".pocket-dev-stacks.json"
                    }.count()
                } else 0
                val keep = userMessages > 0 || userFiles > 0
                if (!keep) {
                    workspaceDir.deleteRecursively()
                    terminalHistoryFile(project.id).delete()
                    preferences.deleteProjectChats(project.id)
                }
                keep
            } else true
        }
        if (cleanedProjects.size != loadedProjects.size) {
            preferences.saveProjects(cleanedProjects)
            _state.update { it.copy(projects = cleanedProjects) }
        }
    }

    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val _terminalLines = MutableStateFlow<List<TerminalOutputLine>>(emptyList())
    val terminalLines: StateFlow<List<TerminalOutputLine>> = _terminalLines.asStateFlow()

    private val _isTerminalRunning = MutableStateFlow(false)
    val isTerminalRunning: StateFlow<Boolean> = _isTerminalRunning.asStateFlow()

    private val _terminalLiveOutput = MutableStateFlow("")
    val terminalLiveOutput: StateFlow<String> = _terminalLiveOutput.asStateFlow()

    private val _terminalCurrentCommand = MutableStateFlow<String?>(null)
    val terminalCurrentCommand: StateFlow<String?> = _terminalCurrentCommand.asStateFlow()

    fun runTerminalCommand(cmd: String) {
        val command = cmd.trim()
        if (command.isBlank() || _isTerminalRunning.value) return
        if (command == "clear") {
            _terminalLines.value = emptyList()
            return
        }
        _isTerminalRunning.value = true
        _terminalCurrentCommand.value = command
        _terminalLiveOutput.value = ""
        viewModelScope.launch {
            val (output, exitCode) = withContext(Dispatchers.IO) {
                runCatching {
                    if (!installer.isInstalled()) {
                        return@runCatching str(R.string.vm_linux_not_ready) to 1
                    }
                    val runtime = installer.installedRuntime()
                    val workspace = File(getApplication<Application>().filesDir, "workspaces/terminal").apply { mkdirs() }
                    val preparedCommand = prepareInteractiveShellCommand(command)
                    val proc = installer.process(
                        proot = runtime.proot,
                        rootfs = runtime.rootfs,
                        workspace = workspace,
                        environment = emptyMap(),
                        guestCommand = listOf("/usr/bin/bash", "-c", preparedCommand),
                    )
                    terminalProcess = proc
                    val native = proc as? NativeSpawnProcess
                    var offset = 0L
                    val streamed = StringBuilder()
                    var autoConfirmed = false
                    while (proc.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                        val file = native?.outputFile
                        val available = (file?.length() ?: 0L) - offset
                        if (file == null || available <= 0) {
                            Thread.sleep(50)
                            continue
                        }
                        val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                        val count = RandomAccessFile(file, "r").use { input ->
                            input.seek(offset)
                            input.read(bytes)
                        }
                        if (count > 0) {
                            offset += count
                            streamed.append(bytes.decodeToString(0, count))
                            _terminalLiveOutput.value = sanitizeTerminalOutput(streamed.toString())
                                .trimEnd()
                                .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
                            if (!autoConfirmed && shouldAutoConfirmPackageCommand(command, streamed.toString())) {
                                proc.outputStream.write("y\n".toByteArray())
                                proc.outputStream.flush()
                                autoConfirmed = true
                            }
                        }
                    }
                    val exit = proc.waitFor()
                    runCatching { proc.outputStream.close() }
                    val out = sanitizeTerminalOutput(streamed.toString()).trim()
                    val finalOut = if (out.isNotEmpty() || exit == 0) out else str(R.string.vm_process_exited_code, exit)
                    finalOut to exit
                }.getOrElse { str(R.string.vm_error_with_message, it.message.orEmpty()) to 1 }
            }
            _terminalLines.update { it + TerminalOutputLine(command = command, output = output, exitCode = exitCode) }
            _terminalLiveOutput.value = ""
            _terminalCurrentCommand.value = null
            _isTerminalRunning.value = false
            terminalProcess = null
        }
    }

    fun sendTerminalInput(text: String) {
        sendProcessInput(terminalProcess, text)
    }

    fun interruptTerminalCommand() {
        interruptProcess(terminalProcess)
    }

    fun clearTerminal() {
        _terminalLines.value = emptyList()
    }

    fun requestProjectTerminalCommand(command: String) {
        val normalized = command.trim()
        if (normalized.isBlank() || _state.value.projectTerminalRunning || _state.value.androidBuildRunning || projectTerminalProcess?.isAlive == true) return
        if (requiresAndroidToolchain(normalized) && !installer.isStackInstalled(DevStack.ANDROID)) {
            _state.update {
                it.copy(toastMessage = str(R.string.vm_android_tools_missing))
            }
            return
        }
        if (isDestructiveTerminalCommand(normalized)) {
            _state.update { it.copy(pendingTerminalCommand = normalized) }
        } else {
            runProjectTerminalCommand(normalized)
        }
    }

    fun prepareProjectTerminalCommand(command: String) {
        val project = _state.value.activeProject ?: return
        if (command.isBlank() || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        _state.update {
            it.copy(
                projectTerminalCwd = projectGuestRoot(project),
                projectTerminalDraft = command.trim(),
            )
        }
    }

    fun consumeProjectTerminalDraft() {
        _state.update { it.copy(projectTerminalDraft = null) }
    }

    fun openProjectTerminal() {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        _state.update { it.copy(projectTerminalCwd = projectGuestRoot(project)) }
    }

    fun confirmProjectTerminalCommand() {
        val command = _state.value.pendingTerminalCommand ?: return
        _state.update { it.copy(pendingTerminalCommand = null) }
        runProjectTerminalCommand(command)
    }

    fun cancelProjectTerminalCommand() {
        _state.update { it.copy(pendingTerminalCommand = null) }
    }

    private fun runProjectTerminalCommand(command: String) {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        val startingCwd = _state.value.projectTerminalCwd
        val existingLines = _state.value.projectTerminalLines
        projectTerminalStopRequested = false
        val requestedPreviewUrl = detectServerUrl(command)
        _state.update {
            it.copy(
                projectTerminalRunning = true,
                projectTerminalLiveOutput = "",
                projectTerminalCommand = command,
                pendingTerminalCommand = null,
                previewReady = it.previewReady || requestedPreviewUrl != null,
                previewUrl = requestedPreviewUrl ?: it.previewUrl,
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { runProjectTerminalProcess(project.id, command, startingCwd) }
                    .getOrElse { error ->
                        ProjectTerminalResult(
                            output = str(R.string.vm_terminal_error, error.message ?: error::class.java.simpleName),
                            exitCode = 1,
                            cwd = startingCwd,
                        )
                    }
            }
            val completedLine = TerminalOutputLine(
                command = command,
                output = result.output.ifBlank {
                    if (result.exitCode == 0) "" else str(R.string.vm_process_exited_code, result.exitCode)
                },
                exitCode = result.exitCode,
            )
            val updatedLines = (existingLines + completedLine).takeLast(MAX_PROJECT_TERMINAL_HISTORY)
            saveProjectTerminal(project.id, result.cwd, updatedLines)
            if (_state.value.activeProject?.id == project.id) {
                _state.update {
                    it.copy(
                        projectTerminalLines = updatedLines,
                        projectTerminalLiveOutput = "",
                        projectTerminalRunning = false,
                        projectTerminalCwd = result.cwd,
                        projectTerminalCommand = null,
                    )
                }
                refreshProjectFiles()
            }
            projectTerminalProcess = null
            projectTerminalProjectId = null
            projectTerminalStopRequested = false
        }
    }

    fun stopProjectTerminalCommand() {
        if (!_state.value.projectTerminalRunning) return
        projectTerminalStopRequested = true
        viewModelScope.launch(Dispatchers.IO) {
            projectTerminalProcess?.destroy()
            delay(400)
            if (projectTerminalProcess?.isAlive == true) projectTerminalProcess?.destroyForcibly()
        }
    }

    fun sendProjectTerminalInput(text: String) {
        sendProcessInput(projectTerminalProcess, text)
    }

    fun interruptProjectTerminalCommand() {
        interruptProcess(projectTerminalProcess)
    }

    fun clearProjectTerminal() {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning) return
        _state.update { it.copy(projectTerminalLines = emptyList(), projectTerminalLiveOutput = "") }
        saveProjectTerminal(project.id, _state.value.projectTerminalCwd, emptyList())
    }

    fun refreshDeveloperDiagnostics() {
        if (diagnosticsJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagnosticsRefreshAtElapsedMillis < DIAGNOSTICS_REFRESH_COOLDOWN_MS) return
        _state.update { it.copy(diagnostics = it.diagnostics.copy(loading = true)) }
        diagnosticsJob = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { collectDeveloperDiagnostics() }
                lastDiagnosticsRefreshAtElapsedMillis = SystemClock.elapsedRealtime()
                _state.update { it.copy(diagnostics = snapshot) }
            } finally {
                diagnosticsJob = null
            }
        }
    }

    fun stopInactiveDeveloperProcesses() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _state.value
            if (!current.isRunning) {
                runCatching { claudeRuntime.stopActiveSession() }
                runCatching { dshRuntime.stopActiveSession() }
                runCatching { antigravityRuntime.stopActiveSession() }
            }
            if (!current.projectTerminalRunning) {
                projectTerminalProcess?.takeIf(Process::isAlive)?.destroyForcibly()
                projectTerminalProcess = null
            }
            if (!current.androidBuildRunning) {
                androidBuildProcess?.takeIf(Process::isAlive)?.destroyForcibly()
                androidBuildProcess = null
            }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(toastMessage = str(R.string.diagnostics_inactive_stopped)) }
                lastDiagnosticsRefreshAtElapsedMillis = 0L
                refreshDeveloperDiagnostics()
            }
        }
    }

    fun clearDeveloperRuntimeCache() {
        val current = _state.value
        if (current.isRunning || current.projectTerminalRunning || current.androidBuildRunning) {
            _state.update { it.copy(toastMessage = str(R.string.diagnostics_cache_busy)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var freedBytes = 0L
            getApplication<Application>().cacheDir.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith("runtime-output-") }
                .forEach { file ->
                    freedBytes += runCatching { file.length() }.getOrDefault(0L)
                    runCatching { file.delete() }
                }
            freedBytes += runCatching { installer.cleanCachesAndTempFiles() }.getOrDefault(0L)
            val freedMb = freedBytes / (1024.0 * 1024.0)
            val toast = if (freedMb >= 0.5) {
                "${str(R.string.diagnostics_cache_cleared)} (${"%.1f".format(freedMb)} MB)"
            } else {
                str(R.string.diagnostics_cache_cleared)
            }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(toastMessage = toast) }
                lastDiagnosticsRefreshAtElapsedMillis = 0L
                refreshDeveloperDiagnostics()
            }
        }
    }

    fun onMemoryTrim(level: Int) {
        resourceManager.onTrimMemory(level)
        releaseMemoryCaches()
    }

    /** Drops only rebuildable or already-persisted data when Android reports memory pressure. */
    fun releaseMemoryCaches() {
        folderMetadataCache.clear()
        cachedRecentExitSummary = null
        val activeTeamProjId = _state.value.activeTeamProject?.id
        // Evict inactive project chats & terminals from RAM (all history remains persisted on disk).
        val inactiveChatKeys = teamProjectChats.keys.filter { it != activeTeamProjId }
        inactiveChatKeys.forEach { teamProjectChats.remove(it) }
        if (activeTeamProjId != null) {
            teamProjectChats[activeTeamProjId]?.let { msgs ->
                if (msgs.size > 120) {
                    val trimmed = msgs.takeLast(120).toMutableList()
                    teamProjectChats[activeTeamProjId] = trimmed
                }
            }
        }
        val inactiveTermKeys = teamProjectTerminals.keys.filter { it != activeTeamProjId }
        inactiveTermKeys.forEach { teamProjectTerminals.remove(it) }
        // Trim AI response cache and inactive project memory caches.
        val pressure = resourceManager.currentPressureLevel()
        if (pressure == com.jarves.mh.multiagent.runtime.MemoryPressureLevel.CRITICAL) {
            teamAgentRunner.clearResponseCache()
        } else {
            teamAgentRunner.trimCacheToBytes(512L * 1024L)
        }
        teamAgentRunner.reapDeadProcesses()
        runCatching { autonomousSupervisorEngine.memoryManager.trimInMemoryCache(keepProjectId = activeTeamProjId) }
        _state.update { current ->
            current.copy(
                projectTerminalLines = current.projectTerminalLines.takeLast(MEMORY_PRESSURE_TERMINAL_LINES),
                projectTerminalLiveOutput = current.projectTerminalLiveOutput.takeLast(MEMORY_PRESSURE_TEXT_CHARS),
                androidBuildLog = current.androidBuildLog.takeLast(MEMORY_PRESSURE_TEXT_CHARS),
                androidLogcat = current.androidLogcat.copy(
                    lines = current.androidLogcat.lines.takeLast(MEMORY_PRESSURE_LOGCAT_LINES),
                ),
            )
        }
    }

    private fun collectDeveloperDiagnostics(): DeveloperDiagnostics {
        val current = _state.value
        val snap = resourceManager.captureSnapshot(
            configuredMaxAgents = current.teamAgents.count { it.config.isEnabled }.coerceAtLeast(1),
            runningProotProcesses = teamAgentRunner.activeProcessCount(),
            responseCacheBytes = teamAgentRunner.cacheBytes(),
        )
        val active = buildList {
            if (current.isRunning) add(current.agentKind.title)
            if (current.projectTerminalRunning || projectTerminalProcess?.isAlive == true) add(str(R.string.an_process_project_terminal))
            if (terminalProcess?.isAlive == true) add(str(R.string.an_process_quick_terminal))
            if (current.androidBuildRunning || androidBuildProcess?.isAlive == true) add(str(R.string.an_android_build))
            if (current.androidLogcat.running || androidLogcatProcess?.isAlive == true) add(str(R.string.android_logcat_tab))
        }
        val runtimeCache = getApplication<Application>().cacheDir.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith("runtime-output-") }
            .sumOf(File::length)
        return DeveloperDiagnostics(
            appPssBytes = snap.pssBytes,
            javaHeapBytes = snap.javaHeapUsedBytes,
            javaHeapMaxBytes = snap.javaHeapMaxBytes,
            nativeHeapBytes = snap.nativeHeapBytes,
            graphicsMemoryBytes = snap.graphicsMemoryBytes,
            activeThreadCount = snap.activeThreadCount,
            runningProotProcesses = snap.runningProotProcesses,
            responseCacheBytes = snap.responseCacheBytes,
            gcCount = snap.gcCount,
            memoryPressureLevel = snap.pressureLevel.name,
            adaptiveConcurrencyLimit = snap.adaptiveConcurrencyLimit,
            activeProcesses = active,
            messageCount = current.messages.size,
            liveActivityCount = current.liveProcess.size,
            terminalBufferBytes = utf8SizeInBytes(current.projectTerminalLiveOutput) +
                current.projectTerminalLines.sumOf { utf8SizeInBytes(it.output) },
            buildLogBytes = utf8SizeInBytes(current.androidBuildLog),
            logcatLines = current.androidLogcat.lines.size,
            fileEntries = current.workspaceFiles.size,
            runtimeCacheBytes = runtimeCache,
            recentExit = recentAppExitSummary(),
            refreshedAtMillis = snap.capturedAtMs,
        )
    }

    private fun recentAppExitSummary(): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - recentExitLoadedAtElapsedMillis < RECENT_EXIT_CACHE_MS) return cachedRecentExitSummary
        recentExitLoadedAtElapsedMillis = now
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = getApplication<Application>().getSystemService(ActivityManager::class.java)
        val exit = manager.getHistoricalProcessExitReasons(getApplication<Application>().packageName, 0, 8)
            .firstOrNull { info ->
                info.reason == android.app.ApplicationExitInfo.REASON_ANR ||
                    info.reason == android.app.ApplicationExitInfo.REASON_CRASH ||
                    info.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    info.reason == android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
                    info.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY
            } ?: run {
                cachedRecentExitSummary = null
                return null
            }
        val reason = when (exit.reason) {
            android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> str(R.string.an_exit_low_memory)
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> str(R.string.an_exit_excessive_resources)
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> str(R.string.an_exit_native_crash)
            else -> str(R.string.an_exit_crash)
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(exit.timestamp))
        return ("$reason · $time" + exit.description?.takeIf(String::isNotBlank)?.let { " · ${it.take(160)}" }.orEmpty())
            .also { cachedRecentExitSummary = it }
    }

    private fun runProjectTerminalProcess(projectId: String, command: String, cwd: String): ProjectTerminalResult {
        if (!installer.isInstalled()) return ProjectTerminalResult(str(R.string.vm_linux_not_ready), 1, cwd)
        val installed = installer.installedRuntime()
        val project = _state.value.projects.firstOrNull { it.id == projectId }
            ?: _state.value.activeProject?.takeIf { it.id == projectId }
            ?: return ProjectTerminalResult(str(R.string.vm_project_unavailable), 1, cwd)
        val workspace = projectWorkspaceRoot(project)
        val guestWorkspacePath = projectGuestRoot(project)
        val marker = "__POCKETDEV_CWD_${UUID.randomUUID()}__"
        val preparedCommand = prepareInteractiveShellCommand(command)
        val script = """
            cd -- ${shellQuote(cwd)} || exit 1
            $preparedCommand
            pocket_status=${'$'}?
            printf '\n$marker%s\n' "${'$'}PWD"
            exit ${'$'}pocket_status
        """.trimIndent()
        val process = installer.process(
            proot = installed.proot,
            rootfs = installed.rootfs,
            workspace = workspace,
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/bash", "-lc", script),
            guestWorkspacePath = guestWorkspacePath,
        )
        projectTerminalProcess = process
        projectTerminalProjectId = projectId
        if (projectTerminalStopRequested) process.destroy()
        val native = process as? NativeSpawnProcess
            ?: return ProjectTerminalResult(str(R.string.vm_unsupported_terminal_process), 1, cwd)
        var offset = 0L
        val output = StringBuilder()
        var autoConfirmed = false
        while (process.isAlive || native.outputFile.length() > offset) {
            val available = native.outputFile.length() - offset
            if (available <= 0) {
                Thread.sleep(50)
                continue
            }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = RandomAccessFile(native.outputFile, "r").use { file ->
                file.seek(offset)
                file.read(bytes)
            }
            if (count > 0) {
                offset += count
                output.append(bytes.decodeToString(0, count))
                val visible = sanitizeTerminalOutput(output.toString().substringBefore(marker))
                    .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
                if (!autoConfirmed && shouldAutoConfirmPackageCommand(command, visible)) {
                    process.outputStream.write("y\n".toByteArray())
                    process.outputStream.flush()
                    autoConfirmed = true
                }
                val detectedPreviewUrl = detectPreviewUrl(visible)
                _state.update { current ->
                    if (current.activeProject?.id == projectId) {
                        current.copy(
                            projectTerminalLiveOutput = visible,
                            previewReady = current.previewReady || detectedPreviewUrl != null,
                            previewUrl = detectedPreviewUrl ?: current.previewUrl,
                        )
                    } else current
                }
            }
        }
        val exitCode = process.waitFor()
        runCatching { process.outputStream.close() }
        val raw = output.toString()
        val cwdAfter = raw.substringAfter(marker, "")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.takeIf { it == guestWorkspacePath || it.startsWith("$guestWorkspacePath/") }
            ?: cwd
        val cleanOutput = sanitizeTerminalOutput(raw.substringBefore(marker))
            .trim()
            .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
        return ProjectTerminalResult(cleanOutput, exitCode, cwdAfter)
    }

    private fun sendProcessInput(process: Process?, text: String) {
        if (process?.isAlive != true || text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                process.outputStream.write((text + "\n").toByteArray())
                process.outputStream.flush()
            }.onFailure {
                _state.update { current -> current.copy(toastMessage = str(R.string.vm_process_not_accepting_input)) }
            }
        }
    }

    private fun interruptProcess(process: Process?) {
        if (process?.isAlive != true) return
        viewModelScope.launch(Dispatchers.IO) {
            (process as? NativeSpawnProcess)?.interrupt() ?: process.destroy()
        }
    }

    /**
     * Package management must never block on Y/N, locale, timezone, service-restart,
     * or config-file dialogs in the phone UI. Other commands remain interactive and
     * can receive input through [sendProcessInput].
     */
    private fun prepareInteractiveShellCommand(command: String): String {
        val normalizedApt = command
            .replace(Regex("(?<![\\w-])sudo\\s+apt(?:-get)?\\s+"), "apt-get ")
            .replace(Regex("(?<![\\w-])apt\\s+"), "apt-get ")
            .replace(
                Regex("(?<![\\w-])apt-get\\s+(install|upgrade|full-upgrade|dist-upgrade|remove|autoremove|fix-broken)\\b"),
                "apt-get -y -o Dpkg::Options::=--force-confold $1",
            )
        return "export DEBIAN_FRONTEND=noninteractive APT_LISTCHANGES_FRONTEND=none UCF_FORCE_CONFFOLD=1 NEEDRESTART_MODE=a TZ=Etc/UTC LC_ALL=C.UTF-8; $normalizedApt"
    }

    private fun shouldAutoConfirmPackageCommand(command: String, output: String): Boolean {
        val packageCommand = Regex("(?i)(^|[;&|]\\s*)(sudo\\s+)?(apt|apt-get|dpkg)\\b").containsMatchIn(command)
        if (!packageCommand) return false
        val tail = output.takeLast(500)
        return Regex("(?i)(do you want to continue|continue\\?)\\s*\\[[Yy]/[Nn]\\]").containsMatchIn(tail)
    }

    private fun isDestructiveTerminalCommand(command: String): Boolean {
        val normalized = command.lowercase().replace(Regex("\\s+"), " ")
        return listOf(
            "rm -rf", "rm -fr", "git reset --hard", "git clean -f", "git push --force",
            "mkfs", "dd if=", "chmod -r 777", "shutdown", "reboot", ":(){", "kill \$(pgrep", "pkill -f",
        ).any(normalized::contains) || Regex("(curl|wget).*(\\||>)\\s*(sh|bash)").containsMatchIn(normalized)
    }

    private fun detectPreviewUrl(output: String): String? {
        val match = Regex("https?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0):(\\d{2,5})(?:/[^\\s]*)?")
            .findAll(output)
            .lastOrNull()
            ?: return null
        val port = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return "http://127.0.0.1:$port/"
    }

    private fun detectServerUrl(command: String): String? {
        val match = Regex("""python(?:3)?\s+-m\s+http\.server(?:\s+(\d{2,5}))?""")
            .find(command)
            ?: return null
        val port = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 8000
        return port.takeIf { it in 1..65535 }?.let { "http://127.0.0.1:$it/" }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun terminalHistoryFile(projectId: String): File =
        File(getApplication<Application>().filesDir, "terminal-history/$projectId.json")

    private fun loadProjectTerminal(project: Project): ProjectTerminalSnapshot {
        val file = terminalHistoryFile(project.id)
        val guestRoot = projectGuestRoot(project)
        if (!file.isFile) return ProjectTerminalSnapshot(cwd = guestRoot)
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                TerminalOutputLine(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    command = item.optString("command"),
                    output = item.optString("output"),
                    exitCode = item.optInt("exitCode"),
                )
            }
            ProjectTerminalSnapshot(
                lines = lines.takeLast(MAX_PROJECT_TERMINAL_HISTORY),
                cwd = root.optString("cwd", guestRoot).takeIf {
                    it == guestRoot || it.startsWith("$guestRoot/")
                } ?: guestRoot,
            )
        }.getOrDefault(ProjectTerminalSnapshot(cwd = guestRoot))
    }

    private fun saveProjectTerminal(projectId: String, cwd: String, lines: List<TerminalOutputLine>) {
        runCatching {
            val file = terminalHistoryFile(projectId)
            file.parentFile?.mkdirs()
            val array = JSONArray()
            lines.takeLast(MAX_PROJECT_TERMINAL_HISTORY).forEach { line ->
                array.put(
                    JSONObject()
                        .put("id", line.id)
                        .put("command", line.command)
                        .put("output", line.output.takeLast(MAX_PROJECT_TERMINAL_OUTPUT))
                        .put("exitCode", line.exitCode),
                )
            }
            file.writeText(JSONObject().put("cwd", cwd).put("lines", array).toString())
        }
    }

    private fun projectGuestRoot(project: Project): String = "/workspace/${project.slug}"

    private fun projectWorkspaceRoot(project: Project): File {
        val base = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
            .apply { mkdirs() }
            .canonicalFile
        if (project.rootPath.isBlank()) return base
        val selected = File(base, project.rootPath).canonicalFile
        require(selected.toPath().startsWith(base.toPath())) { str(R.string.vm_unsafe_project_root) }
        return selected.apply { mkdirs() }
    }

    fun buildAndRunAndroidApp() {
        performAndroidAction(AndroidAction.BUILD_AND_RUN)
    }

    fun performAndroidAction(action: AndroidAction) {
        when (action) {
            AndroidAction.BUILD -> {
                if (!completeFromReusableApk(action, launch = false)) startAndroidGradleTask("assembleDebug", action)
            }
            AndroidAction.FORCE_BUILD -> startAndroidGradleTask("assembleDebug", action)
            AndroidAction.INSTALL -> installLatestAndroidApk(launch = false, action = action)
            AndroidAction.RUN -> runLatestAndroidApk()
            AndroidAction.BUILD_AND_RUN -> {
                if (!completeFromReusableApk(action, launch = true)) startAndroidGradleTask("assembleDebug", action)
            }
            AndroidAction.CLEAN -> startAndroidGradleTask("clean", action)
            AndroidAction.NONE -> Unit
        }
    }

    private fun completeFromReusableApk(action: AndroidAction, launch: Boolean): Boolean {
        val current = _state.value
        val project = current.activeProject ?: return false
        if (current.isRunning || current.projectTerminalRunning || current.androidBuildRunning) return false
        val workspace = findAndroidProjectRoot(projectWorkspaceRoot(project)) ?: return false
        val apk = findReusableDebugApk(workspace) ?: return false
        if (launch) {
            if (AndroidAppInstaller.openIfAlreadyInstalled(getApplication(), apk)) {
                androidLaunchAtMillis = System.currentTimeMillis()
            } else {
                beginAndroidInstall(apk, launch = true, action = action)
                return true
            }
        }
        val now = System.currentTimeMillis()
        val message = if (launch) str(R.string.an_no_changes_opened_app) else str(R.string.an_apk_already_current)
        _state.update {
            it.copy(
                androidBuildRunning = false,
                androidBuildPhase = AndroidBuildPhase.SUCCEEDED,
                androidBuildAction = action,
                androidBuildStage = AndroidBuildStage.COMPLETE,
                androidBuildIssues = emptyList(),
                androidBuildMessage = message,
                androidBuildLog = "Gradle skipped: project files have not changed since the last successful build.\n",
                androidBuildStartedAtMillis = now,
                androidBuildFinishedAtMillis = now,
                androidBuildApkPath = apk.absolutePath,
                androidBuildApkSizeBytes = apk.length(),
                toastMessage = message,
            )
        }
        saveAndroidBuildRecord(project.id)
        refreshAndroidApkInfo()
        return true
    }

    fun cleanAndroidProject() {
        performAndroidAction(AndroidAction.CLEAN)
    }

    private fun runLatestAndroidApk() {
        val project = _state.value.activeProject ?: return
        val root = findAndroidProjectRoot(projectWorkspaceRoot(project))
        val apk = root?.let(::findDebugApk)
        if (apk == null) {
            _state.update { it.copy(toastMessage = str(R.string.an_build_apk_before_run)) }
            return
        }
        if (AndroidAppInstaller.openIfAlreadyInstalled(getApplication(), apk)) {
            androidLaunchAtMillis = System.currentTimeMillis()
            val now = System.currentTimeMillis()
            _state.update {
                it.copy(
                    androidBuildAction = AndroidAction.RUN,
                    androidBuildStage = AndroidBuildStage.COMPLETE,
                    androidBuildPhase = AndroidBuildPhase.SUCCEEDED,
                    androidBuildMessage = str(R.string.an_app_launched_without_rebuild),
                    androidBuildStartedAtMillis = now,
                    androidBuildFinishedAtMillis = now,
                    toastMessage = str(R.string.an_app_launched),
                )
            }
        } else {
            beginAndroidInstall(apk, launch = true, action = AndroidAction.RUN)
        }
    }

    private fun installLatestAndroidApk(launch: Boolean, action: AndroidAction) {
        val project = _state.value.activeProject ?: return
        val root = findAndroidProjectRoot(projectWorkspaceRoot(project))
        val apk = root?.let(::findDebugApk)
        if (apk == null) {
            _state.update { it.copy(toastMessage = str(R.string.an_build_apk_before_install)) }
            return
        }
        beginAndroidInstall(apk, launch, action)
    }

    private fun beginAndroidInstall(apk: File, launch: Boolean, action: AndroidAction) {
        if (_state.value.androidBuildRunning && pendingAndroidInstallId != null) return
        val operationId = UUID.randomUUID().toString()
        pendingAndroidInstallId = operationId
        pendingAndroidInstallLaunch = launch
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                androidBuildRunning = true,
                androidBuildAction = action,
                androidBuildStage = AndroidBuildStage.INSTALLING,
                androidBuildPhase = AndroidBuildPhase.BUILDING,
                androidBuildMessage = if (launch) str(R.string.an_installing_apk_before_launch) else str(R.string.an_installing_apk),
                androidBuildIssues = emptyList(),
                androidBuildStartedAtMillis = it.androidBuildStartedAtMillis ?: now,
                androidBuildFinishedAtMillis = null,
                androidBuildApkPath = apk.absolutePath,
                androidBuildApkSizeBytes = apk.length(),
            )
        }
        runCatching {
            AndroidAppInstaller.install(getApplication(), apk, launchAfterInstall = launch, operationId = operationId)
        }.onFailure { error ->
            pendingAndroidInstallId = null
            pendingAndroidInstallLaunch = false
            _state.update {
                it.copy(
                    androidBuildRunning = false,
                    androidBuildPhase = AndroidBuildPhase.FAILED,
                    androidBuildStage = AndroidBuildStage.FAILED,
                    androidBuildMessage = error.message ?: str(R.string.an_could_not_start_install),
                    androidBuildIssues = listOf(AndroidBuildIssue(title = str(R.string.rt_apk_install_failed), detail = error.message ?: str(R.string.an_could_not_start_installer))),
                )
            }
        }
    }

    fun cancelAndroidBuild() {
        if (!_state.value.androidBuildRunning) return
        androidBuildStopRequested = true
        _state.update {
            it.copy(
                androidBuildPhase = AndroidBuildPhase.CANCELLING,
                androidBuildStage = AndroidBuildStage.CANCELLING,
                androidBuildMessage = str(R.string.an_stopping_gradle),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            androidBuildProcess?.destroy()
            delay(500)
            if (androidBuildProcess?.isAlive == true) androidBuildProcess?.destroyForcibly()
        }
    }

    private fun startAndroidGradleTask(task: String, action: AndroidAction) {
        val project = _state.value.activeProject ?: return
        if (_state.value.androidBuildRunning) return
        if (!installer.isStackInstalled(DevStack.ANDROID)) {
            val autoStarted = ToolInstallPermissionReceiver.requestOrAutoInstallTool(
                context = getApplication(),
                stack = DevStack.ANDROID,
            )
            _state.update {
                it.copy(
                    toastMessage = if (autoStarted) {
                        "Installing Android toolchain in background..."
                    } else {
                        str(R.string.vm_android_tools_missing)
                    },
                )
            }
            return
        }
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = str(R.string.vm_wait_agent_before_build)) }
            return
        }
        if (_state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = str(R.string.vm_wait_terminal_before_build)) }
            return
        }
        val startedAt = System.currentTimeMillis()
        androidBuildStopRequested = false
        _state.update {
            it.copy(
                androidBuildRunning = true,
                androidBuildPhase = AndroidBuildPhase.PREPARING,
                androidBuildAction = action,
                androidBuildStage = AndroidBuildStage.PREPARING,
                androidBuildIssues = emptyList(),
                androidBuildMessage = if (task == "clean") str(R.string.an_preparing_clean) else str(R.string.an_preparing_build),
                androidBuildLog = "",
                androidBuildStartedAtMillis = startedAt,
                androidBuildFinishedAtMillis = null,
                androidBuildApkPath = null,
                androidBuildApkSizeBytes = null,
                toastMessage = null,
            )
        }
        saveAndroidBuildRecord(project.id)
        startAndroidBuildForegroundService(project, task)
        viewModelScope.launch(Dispatchers.IO) {
            var buildWorkspace: File? = null
            var finalOutput = ""
            var finalExitCode = -1
            runCatching {
                val installed = installer.installedRuntime()
                installer.ensureArm64NdkAndCmakeShims(installed.rootfs)
                val workspace = findAndroidProjectRoot(projectWorkspaceRoot(project))
                    ?: error(str(R.string.vm_no_android_project))
                buildWorkspace = workspace
                if (task != "clean" &&
                    ToolInstallPermissionReceiver.projectNeedsNativeNdk(workspace) &&
                    !installer.isStackInstalled(DevStack.ANDROID_NDK)
                ) {
                    val autoStarted = ToolInstallPermissionReceiver.requestOrAutoInstallTool(
                        context = getApplication(),
                        stack = DevStack.ANDROID_NDK,
                    )
                    error(
                        if (autoStarted) {
                            "Android NDK & CMake is installing in the background. Retry build once ready."
                        } else {
                            "Project requires Android NDK & CMake. Tap Allow on the Android notification to install in background."
                        },
                    )
                }
                val resolvedTask = if (task == "assembleDebug") resolveDebugAssembleTask(workspace) else task
                val tryOfflineFirst = task != "clean" && findDebugApk(workspace) != null

                suspend fun runSingleGradleAttempt(offline: Boolean): Pair<Int, String> {
                    val command = androidGradleShellCommand(
                        root = workspace,
                        task = resolvedTask,
                        offline = offline,
                        timeoutMinutes = if (offline) 8 else 15,
                    )
                    _state.update { current ->
                        val header = if (offline || current.androidBuildLog.isBlank()) {
                            "$ $command\n"
                        } else {
                            "${current.androidBuildLog}\n--- Offline cache miss, retrying online ---\n$ $command\n"
                        }
                        current.copy(
                            androidBuildPhase = AndroidBuildPhase.BUILDING,
                            androidBuildStage = AndroidBuildStage.RESOLVING,
                            androidBuildMessage = if (task == "clean") str(R.string.an_cleaning_project) else str(R.string.an_running_assemble),
                            androidBuildLog = header.takeLast(MAX_ANDROID_BUILD_LOG),
                        )
                    }
                    val process = installer.process(
                        installed.proot, installed.rootfs, workspace, emptyMap(),
                        listOf("/usr/bin/bash", "-lc", command),
                        projectGuestRoot(project),
                    )
                    androidBuildProcess = process
                    RuntimeTaskController.stopAction = ::cancelAndroidBuild
                    val native = process as? NativeSpawnProcess ?: error(str(R.string.an_unsupported_build_process))
                    var offset = 0L
                    var pendingChunk = StringBuilder()
                    var lastUiFlushMs = 0L
                    while (process.isAlive || native.outputFile.length() > offset) {
                        val available = native.outputFile.length() - offset
                        if (available <= 0L) {
                            if (pendingChunk.isNotEmpty()) {
                                val flushText = pendingChunk.toString()
                                pendingChunk.setLength(0)
                                _state.update { current ->
                                    val nextStage = inferAndroidBuildStage(flushText, current.androidBuildStage)
                                    current.copy(
                                        androidBuildLog = (current.androidBuildLog + flushText).takeLast(MAX_ANDROID_BUILD_LOG),
                                        androidBuildStage = nextStage,
                                        androidBuildMessage = androidStageMessage(nextStage),
                                    )
                                }
                            }
                            Thread.sleep(100)
                            continue
                        }
                        val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                        val count = RandomAccessFile(native.outputFile, "r").use { file ->
                            file.seek(offset)
                            file.read(bytes)
                        }
                        if (count > 0) {
                            offset += count
                            pendingChunk.append(sanitizeTerminalOutput(bytes.decodeToString(0, count)))
                            val now = System.currentTimeMillis()
                            if (now - lastUiFlushMs >= 150L) {
                                lastUiFlushMs = now
                                val flushText = pendingChunk.toString()
                                pendingChunk.setLength(0)
                                _state.update { current ->
                                    val nextStage = inferAndroidBuildStage(flushText, current.androidBuildStage)
                                    current.copy(
                                        androidBuildLog = (current.androidBuildLog + flushText).takeLast(MAX_ANDROID_BUILD_LOG),
                                        androidBuildStage = nextStage,
                                        androidBuildMessage = androidStageMessage(nextStage),
                                    )
                                }
                            }
                        }
                    }
                    if (pendingChunk.isNotEmpty()) {
                        val flushText = pendingChunk.toString()
                        _state.update { current ->
                            val nextStage = inferAndroidBuildStage(flushText, current.androidBuildStage)
                            current.copy(
                                androidBuildLog = (current.androidBuildLog + flushText).takeLast(MAX_ANDROID_BUILD_LOG),
                                androidBuildStage = nextStage,
                                androidBuildMessage = androidStageMessage(nextStage),
                            )
                        }
                    }
                    val code = process.waitFor()
                    val output = sanitizeTerminalOutput(native.outputFile.readTailText(MAX_PROCESS_OUTPUT_BYTES))
                    return code to output
                }

                var (exitCode, buildOutput) = runSingleGradleAttempt(offline = tryOfflineFirst)
                if (exitCode != 0 && !androidBuildStopRequested && tryOfflineFirst && isMissingCachedDependencyError(buildOutput)) {
                    val retryResult = runSingleGradleAttempt(offline = false)
                    exitCode = retryResult.first
                    buildOutput = retryResult.second
                }
                finalExitCode = exitCode
                finalOutput = buildOutput
                if (androidBuildStopRequested) throw java.util.concurrent.CancellationException("Build cancelled")
                check(exitCode == 0) {
                    diagnoseAndroidBuildFailure(buildOutput, exitCode)
                }
                if (task == "clean") null else {
                    _state.update { it.copy(androidBuildStage = AndroidBuildStage.FINDING_APK, androidBuildMessage = str(R.string.an_finding_apk)) }
                    val builtApk = findDebugApk(workspace) ?: error(str(R.string.an_no_debug_apk_found))
                    publishCanonicalDebugApk(workspace, builtApk)
                }
            }.onSuccess { apk ->
                val shouldInstallAndRun = apk != null && action == AndroidAction.BUILD_AND_RUN
                val message = if (apk == null) str(R.string.an_project_cleaned) else str(R.string.an_debug_apk_built)
                val buildCompletedAt = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    _state.update { current ->
                        val duration = (buildCompletedAt - startedAt).coerceAtLeast(1L)
                        val recentDurations = if (apk != null) {
                            (current.androidBuildRecentDurationsMillis + duration).takeLast(5)
                        } else current.androidBuildRecentDurationsMillis
                        current.copy(
                            androidBuildRunning = shouldInstallAndRun,
                            androidBuildPhase = AndroidBuildPhase.SUCCEEDED,
                            androidBuildStage = if (shouldInstallAndRun) AndroidBuildStage.INSTALLING else AndroidBuildStage.COMPLETE,
                            androidBuildMessage = message,
                            androidBuildIssues = emptyList(),
                            androidBuildFinishedAtMillis = buildCompletedAt,
                            androidBuildApkPath = apk?.absolutePath,
                            androidBuildApkSizeBytes = apk?.length(),
                            androidBuildRecentDurationsMillis = recentDurations,
                            toastMessage = message,
                        )
                    }
                    saveAndroidBuildRecord(project.id)
                    refreshAndroidApkInfo()
                }
                finishAndroidBuildForegroundService(project, message, failed = false)
                if (shouldInstallAndRun) beginAndroidInstall(apk!!, launch = true, action = action)
            }.onFailure { error ->
                val cancelled = error is java.util.concurrent.CancellationException || androidBuildStopRequested
                val message = if (cancelled) str(R.string.an_build_cancelled) else error.message?.let { localizedAndroidBuildText(AppStrings.context(getApplication()), it) } ?: str(R.string.an_could_not_build_apk)
                val issues = if (cancelled) emptyList() else buildWorkspace?.let {
                    parseAndroidBuildIssues(finalOutput.ifBlank { message }, it, finalExitCode)
                }.orEmpty().ifEmpty { listOf(AndroidBuildIssue(title = str(R.string.android_build_failed_title), detail = message)) }
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            androidBuildRunning = false,
                            androidBuildPhase = if (cancelled) AndroidBuildPhase.CANCELLED else AndroidBuildPhase.FAILED,
                            androidBuildStage = if (cancelled) AndroidBuildStage.CANCELLED else AndroidBuildStage.FAILED,
                            androidBuildMessage = message,
                            androidBuildIssues = issues,
                            androidBuildFinishedAtMillis = System.currentTimeMillis(),
                            toastMessage = message,
                        )
                    }
                    saveAndroidBuildRecord(project.id)
                }
                finishAndroidBuildForegroundService(project, message, failed = !cancelled, cancelled = cancelled)
            }.also {
                androidBuildProcess = null
                androidBuildStopRequested = false
                RuntimeTaskController.stopAction = null
                steeringToFollowUp = false
                if (pendingFollowUps.isNotEmpty()) {
                    viewModelScope.launch {
                        delay(150)
                        if (!_state.value.isRunning && !_state.value.androidBuildRunning && !_state.value.projectTerminalRunning) {
                            startNextFollowUp()
                        }
                    }
                }
            }
        }
    }

    private fun requiresAndroidToolchain(command: String): Boolean =
        Regex("(?m)(^|[;&|]\\s*)(?:\\./)?gradle(?:w)?(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(command)

    private fun androidStageMessage(stage: AndroidBuildStage): String = when (stage) {
        AndroidBuildStage.PREPARING -> str(R.string.an_preparing_build)
        AndroidBuildStage.RESOLVING -> str(R.string.an_resolving_dependencies)
        AndroidBuildStage.COMPILING -> str(R.string.an_compiling_source)
        AndroidBuildStage.RESOURCES -> str(R.string.an_processing_resources)
        AndroidBuildStage.PACKAGING -> str(R.string.an_packaging_apk)
        AndroidBuildStage.FINDING_APK -> str(R.string.an_finding_apk)
        AndroidBuildStage.INSTALLING -> str(R.string.an_installing_apk)
        AndroidBuildStage.LAUNCHING -> str(R.string.an_launching_app)
        AndroidBuildStage.COMPLETE -> str(R.string.android_stage_complete)
        AndroidBuildStage.CANCELLING -> str(R.string.an_stopping_gradle)
        AndroidBuildStage.CANCELLED -> str(R.string.an_build_cancelled)
        AndroidBuildStage.FAILED -> str(R.string.an_operation_failed)
        AndroidBuildStage.IDLE -> str(R.string.android_stage_idle)
    }

    private fun startAndroidBuildForegroundService(project: Project, task: String) {
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                .putExtra(RuntimeExecutionService.EXTRA_TITLE, if (task == "clean") str(R.string.an_notif_cleaning_title) else str(R.string.an_notif_building_title))
                .putExtra(RuntimeExecutionService.EXTRA_DETAIL, if (task == "clean") str(R.string.an_notif_cleaning_detail) else str(R.string.an_notif_building_detail))
                .putExtra(RuntimeExecutionService.EXTRA_CAN_STOP, true),
        )
    }

    private fun finishAndroidBuildForegroundService(
        project: Project,
        detail: String,
        failed: Boolean,
        cancelled: Boolean = false,
    ) {
        runCatching {
            getApplication<Application>().startService(
                Intent(getApplication(), RuntimeExecutionService::class.java)
                    .setAction(
                        when {
                            cancelled -> RuntimeExecutionService.ACTION_CANCELLED
                            failed -> RuntimeExecutionService.ACTION_FAILED
                            else -> RuntimeExecutionService.ACTION_COMPLETE
                        },
                    )
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }
    }

    private fun androidBuildRecordFile(projectId: String): File =
        File(getApplication<Application>().filesDir, "android-builds/$projectId.json")

    private fun saveAndroidBuildRecord(projectId: String) {
        val state = _state.value
        runCatching {
            androidBuildRecordFile(projectId).apply {
                parentFile?.mkdirs()
                writeText(
                    JSONObject()
                        .put("phase", state.androidBuildPhase.name)
                        .put("message", state.androidBuildMessage)
                        .put("log", state.androidBuildLog.takeLast(MAX_ANDROID_BUILD_LOG))
                        .put("startedAtMillis", state.androidBuildStartedAtMillis)
                        .put("finishedAtMillis", state.androidBuildFinishedAtMillis)
                        .put("apkPath", state.androidBuildApkPath)
                        .put("apkSizeBytes", state.androidBuildApkSizeBytes)
                        .put("action", state.androidBuildAction.name)
                        .put("stage", state.androidBuildStage.name)
                        .put("recentDurationsMillis", JSONArray(state.androidBuildRecentDurationsMillis))
                        .put("issues", JSONArray().apply {
                            state.androidBuildIssues.forEach { issue ->
                                put(JSONObject()
                                    .put("severity", issue.severity.name)
                                    .put("title", issue.title)
                                    .put("detail", issue.detail)
                                    .put("suggestion", issue.suggestion)
                                    .put("filePath", issue.filePath)
                                    .put("line", issue.line)
                                    .put("column", issue.column))
                            }
                        })
                        .toString(),
                )
            }
        }
    }

    private fun loadAndroidBuildRecord(projectId: String): AndroidBuildRecord {
        val file = androidBuildRecordFile(projectId)
        if (!file.isFile) return AndroidBuildRecord()
        return runCatching {
            val json = JSONObject(file.readText())
            val phase = runCatching { AndroidBuildPhase.valueOf(json.optString("phase")) }
                .getOrDefault(AndroidBuildPhase.IDLE)
                .let { if (it in setOf(AndroidBuildPhase.PREPARING, AndroidBuildPhase.BUILDING, AndroidBuildPhase.CANCELLING)) AndroidBuildPhase.CANCELLED else it }
            AndroidBuildRecord(
                phase = phase,
                message = if (phase == AndroidBuildPhase.CANCELLED) str(R.string.an_previous_build_interrupted) else json.optString("message").takeIf(String::isNotBlank),
                log = json.optString("log"),
                startedAtMillis = json.optLong("startedAtMillis").takeIf { it > 0L },
                finishedAtMillis = json.optLong("finishedAtMillis").takeIf { it > 0L },
                apkPath = json.optString("apkPath").takeIf { it.isNotBlank() && File(it).isFile },
                apkSizeBytes = json.optLong("apkSizeBytes").takeIf { it > 0L },
                action = runCatching { AndroidAction.valueOf(json.optString("action")) }.getOrDefault(AndroidAction.NONE),
                recentDurationsMillis = json.optJSONArray("recentDurationsMillis")?.let { array ->
                    (0 until array.length()).mapNotNull { index -> array.optLong(index).takeIf { it > 0L } }
                }.orEmpty(),
                stage = runCatching { AndroidBuildStage.valueOf(json.optString("stage")) }.getOrDefault(
                    when (phase) {
                        AndroidBuildPhase.SUCCEEDED -> AndroidBuildStage.COMPLETE
                        AndroidBuildPhase.FAILED -> AndroidBuildStage.FAILED
                        AndroidBuildPhase.CANCELLED -> AndroidBuildStage.CANCELLED
                        else -> AndroidBuildStage.IDLE
                    },
                ),
                issues = json.optJSONArray("issues")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optJSONObject(index)?.let { item ->
                            AndroidBuildIssue(
                                severity = runCatching { com.jarves.mh.runtime.AndroidIssueSeverity.valueOf(item.optString("severity")) }
                                    .getOrDefault(com.jarves.mh.runtime.AndroidIssueSeverity.ERROR),
                                title = item.optString("title"),
                                detail = item.optString("detail"),
                                suggestion = item.optString("suggestion").takeIf(String::isNotBlank),
                                filePath = item.optString("filePath").takeIf(String::isNotBlank),
                                line = item.optInt("line").takeIf { it > 0 },
                                column = item.optInt("column").takeIf { it > 0 },
                            )
                        }
                    }
                }.orEmpty(),
            )
        }.getOrDefault(AndroidBuildRecord())
    }

    fun refreshAndroidDashboard() {
        refreshAndroidApkInfo()
        refreshAndroidHealth()
    }

    fun refreshAndroidApkInfo() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val info = runCatching {
                val projectRoot = findAndroidProjectRoot(projectWorkspaceRoot(project)) ?: return@runCatching null
                val apk = findDebugApk(projectRoot) ?: return@runCatching null
                val app = getApplication<Application>()
                @Suppress("DEPRECATION")
                val archive = app.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                val packageName = archive?.packageName ?: AndroidAppInstaller.packageName(app, apk)
                AndroidApkInfo(
                    path = apk.absolutePath,
                    fileName = apk.name,
                    sizeBytes = apk.length(),
                    builtAtMillis = apk.lastModified(),
                    packageName = packageName,
                    versionName = archive?.versionName,
                    versionCode = archive?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
                    },
                    minSdk = archive?.applicationInfo?.minSdkVersion,
                    targetSdk = archive?.applicationInfo?.targetSdkVersion,
                    stale = isDebugApkStale(projectRoot, apk),
                    installed = packageName?.let { AndroidAppInstaller.isInstalled(app, it) } == true,
                    installedMatches = AndroidAppInstaller.installedMatches(app, apk),
                )
            }.getOrNull()
            _state.update { it.copy(androidApkInfo = info) }
        }
    }

    fun shareAndroidApk() {
        val info = _state.value.androidApkInfo ?: return
        val file = File(info.path).takeIf(File::isFile) ?: return
        runCatching {
            val app = getApplication<Application>()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }, str(R.string.an_share_apk)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: str(R.string.an_could_not_share_apk)) } }
    }

    fun saveAndroidApk(uri: Uri) {
        val info = _state.value.androidApkInfo ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val source = File(info.path).takeIf(File::isFile) ?: error(str(R.string.an_apk_unavailable))
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().buffered().use { it.copyTo(output) }
                } ?: error(str(R.string.an_location_unavailable))
            }
            _state.update { it.copy(toastMessage = if (result.isSuccess) str(R.string.an_apk_saved) else result.exceptionOrNull()?.message ?: str(R.string.an_could_not_save_apk)) }
        }
    }

    fun openInstalledAndroidAppDetails() {
        val packageName = _state.value.androidApkInfo?.packageName ?: return
        getApplication<Application>().startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun uninstallAndroidApp() {
        val packageName = _state.value.androidApkInfo?.packageName ?: return
        getApplication<Application>().startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openAndroidIssue(issue: AndroidBuildIssue) {
        val project = _state.value.activeProject ?: return
        val path = issue.filePath ?: return
        val root = projectWorkspaceRoot(project)
        val candidate = File(root, path)
        val resolved = when {
            candidate.isFile -> candidate
            else -> root.walkTopDown().firstOrNull { it.isFile && it.name == File(path).name }
        } ?: return
        val relative = resolved.relativeTo(root).invariantSeparatorsPath
        _state.update { it.copy(openedFile = OpenedFileState(path = relative, targetLine = issue.line, targetColumn = issue.column)) }
        loadOpenedFile(project, relative, issue.line, issue.column)
    }

    fun refreshAndroidHealth() {
        val project = _state.value.activeProject ?: return
        if (_state.value.androidHealthRunning) return
        _state.update { it.copy(androidHealthRunning = true, androidHealthChecks = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val checks = mutableListOf<AndroidHealthCheck>()
            val toolsInstalled = installer.isStackInstalled(DevStack.ANDROID)
            checks += AndroidHealthCheck(
                "tools", str(R.string.an_health_tools_title),
                if (toolsInstalled) str(R.string.an_health_tools_ok) else str(R.string.an_health_tools_missing),
                if (toolsInstalled) AndroidHealthStatus.PASSED else AndroidHealthStatus.FAILED,
                if (toolsInstalled) AndroidHealthFix.NONE else AndroidHealthFix.INSTALL_TOOLS,
            )
            val root = findAndroidProjectRoot(projectWorkspaceRoot(project))
            checks += AndroidHealthCheck(
                "project", str(R.string.an_health_project_title),
                root?.let { str(R.string.an_health_project_ok, it.name) } ?: str(R.string.an_health_project_missing),
                if (root != null) AndroidHealthStatus.PASSED else AndroidHealthStatus.FAILED,
            )
            val launcher = root?.let { File(it, "gradlew").isFile || File(it, "build.gradle").isFile || File(it, "build.gradle.kts").isFile } == true
            checks += AndroidHealthCheck(
                "gradle", str(R.string.an_health_gradle_title),
                if (launcher) str(R.string.an_health_gradle_ok) else str(R.string.an_health_gradle_missing),
                if (launcher) AndroidHealthStatus.PASSED else AndroidHealthStatus.FAILED,
            )
            if (toolsInstalled && root != null) {
                val (toolchainExit, output) = runAndroidGuestCommand(
                    project,
                    "java -version 2>&1 | grep -E '\"17\\.|version 17' >/dev/null && " +
                        "gradle --version >/dev/null && " +
                        "/root/android-sdk/build-tools/35.0.0/aapt2 version >/dev/null && " +
                        "printf POCKETDEV_TOOLCHAIN_OK",
                )
                val ok = toolchainExit == 0 && "POCKETDEV_TOOLCHAIN_OK" in output
                checks += AndroidHealthCheck(
                    "toolchain", str(R.string.an_health_toolchain_title),
                    if (ok) str(R.string.an_health_toolchain_ok) else str(R.string.an_health_toolchain_failed),
                    if (ok) AndroidHealthStatus.PASSED else AndroidHealthStatus.FAILED,
                    if (ok) AndroidHealthFix.NONE else AndroidHealthFix.INSTALL_TOOLS,
                )
                val compileSdk = root.walkTopDown().maxDepth(3).filter { it.isFile && it.name in setOf("build.gradle", "build.gradle.kts") }
                    .mapNotNull { Regex("compileSdk(?:Version)?\\s*(?:=|\\s)\\s*(\\d+)").find(it.readText())?.groupValues?.get(1)?.toIntOrNull() }
                    .maxOrNull()
                val platformAvailable = compileSdk == null || File(installer.installedRuntime().rootfs, "root/android-sdk/platforms/android-$compileSdk/android.jar").isFile
                checks += AndroidHealthCheck(
                    "sdk", str(R.string.an_health_sdk_title),
                    when { compileSdk == null -> str(R.string.an_health_sdk_unknown); platformAvailable -> str(R.string.an_health_sdk_ok, compileSdk); else -> str(R.string.an_health_sdk_missing, compileSdk) },
                    when { compileSdk == null -> AndroidHealthStatus.WARNING; platformAvailable -> AndroidHealthStatus.PASSED; else -> AndroidHealthStatus.FAILED },
                    if (!platformAvailable) AndroidHealthFix.INSTALL_TOOLS else AndroidHealthFix.NONE,
                )
            }
            val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
            val online = network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            checks += AndroidHealthCheck(
                "network", str(R.string.an_health_network_title),
                if (online) str(R.string.an_health_network_ok) else str(R.string.an_health_network_offline),
                if (online) AndroidHealthStatus.PASSED else AndroidHealthStatus.WARNING,
                if (online) AndroidHealthFix.NONE else AndroidHealthFix.OPEN_NETWORK_SETTINGS,
            )
            val freeBytes = StatFs(app.filesDir.absolutePath).availableBytes
            val enoughStorage = freeBytes >= 1_500L * 1024L * 1024L
            checks += AndroidHealthCheck(
                "storage", str(R.string.an_health_storage_title),
                str(R.string.an_health_storage_detail, freeBytes / 1024 / 1024),
                if (enoughStorage) AndroidHealthStatus.PASSED else AndroidHealthStatus.WARNING,
                if (enoughStorage) AndroidHealthFix.NONE else AndroidHealthFix.OPEN_STORAGE_SETTINGS,
            )
            val installAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || app.packageManager.canRequestPackageInstalls()
            checks += AndroidHealthCheck(
                "install", str(R.string.an_health_install_title),
                if (installAllowed) str(R.string.an_health_install_ok) else str(R.string.an_health_install_missing),
                if (installAllowed) AndroidHealthStatus.PASSED else AndroidHealthStatus.FAILED,
                if (installAllowed) AndroidHealthFix.NONE else AndroidHealthFix.OPEN_INSTALL_SETTINGS,
            )
            val apk = root?.let(::findDebugApk)
            checks += AndroidHealthCheck(
                "apk", str(R.string.an_health_apk_title),
                when { apk == null -> str(R.string.an_health_apk_missing); isDebugApkStale(root, apk) -> str(R.string.an_health_apk_stale); else -> str(R.string.an_health_apk_ok) },
                when { apk == null -> AndroidHealthStatus.WARNING; isDebugApkStale(root, apk) -> AndroidHealthStatus.WARNING; else -> AndroidHealthStatus.PASSED },
            )
            _state.update { it.copy(androidHealthRunning = false, androidHealthChecks = checks) }
        }
    }

    fun performAndroidHealthFix(fix: AndroidHealthFix) {
        val app = getApplication<Application>()
        when (fix) {
            AndroidHealthFix.INSTALL_TOOLS -> {
                _state.update { current ->
                    current.copy(
                        androidHealthRunning = true,
                        androidHealthChecks = current.androidHealthChecks.map { check ->
                            if (check.fix == AndroidHealthFix.INSTALL_TOOLS) {
                                check.copy(
                                    detail = str(R.string.an_repairing_tools),
                                    status = AndroidHealthStatus.RUNNING,
                                    fix = AndroidHealthFix.NONE,
                                )
                            } else check
                        },
                    )
                }
                installDevStack(DevStack.ANDROID, refreshAndroidHealthAfter = true)
            }
            AndroidHealthFix.OPEN_INSTALL_SETTINGS -> app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AndroidHealthFix.OPEN_NETWORK_SETTINGS -> app.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AndroidHealthFix.OPEN_STORAGE_SETTINGS -> app.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AndroidHealthFix.CLEAN -> cleanAndroidProject()
            AndroidHealthFix.REFRESH -> refreshAndroidHealth()
            AndroidHealthFix.COPY_LOGCAT_COMMAND, AndroidHealthFix.NONE -> Unit
        }
    }

    private fun runAndroidGuestCommand(project: Project, command: String): Pair<Int, String> {
        return runCatching {
            val runtime = installer.installedRuntime()
            val workspace = projectWorkspaceRoot(project)
            val outputFile = File(getApplication<Application>().cacheDir, "android-health-${System.nanoTime()}.log")
            try {
                val process = installer.process(
                    runtime.proot, runtime.rootfs, workspace, emptyMap(),
                    listOf("/usr/bin/bash", "-lc", command), projectGuestRoot(project), outputFile = outputFile,
                )
                val exit = process.waitFor()
                exit to sanitizeTerminalOutput(outputFile.readTailText(64 * 1024)).trim()
            } finally { outputFile.delete() }
        }.getOrElse { -1 to (it.message ?: "Command failed") }
    }

    private fun hasLogcatAccess(): Boolean {
        val app = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) return true
        return runCatching {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val finished = process.waitFor(800, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0
        }.getOrDefault(false)
    }

    fun startAndroidLogcat() {
        if (androidLogcatJob?.isActive == true) return
        val info = _state.value.androidApkInfo
        val packageName = info?.packageName
        if (packageName.isNullOrBlank() || info.installed.not()) {
            _state.update { it.copy(androidLogcat = it.androidLogcat.copy(available = false, message = str(R.string.an_install_apk_before_logcat))) }
            return
        }
        if (!hasLogcatAccess()) {
            _state.update { it.copy(androidLogcat = it.androidLogcat.copy(available = false, message = LOGCAT_GRANT_COMMAND, packageName = packageName)) }
            return
        }
        val app = getApplication<Application>()
        val uid = runCatching { app.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
        if (uid == null) {
            _state.update { it.copy(androidLogcat = it.androidLogcat.copy(available = false, message = str(R.string.an_installed_app_not_found))) }
            return
        }
        androidLogcatJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val root = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED
                val since = androidLaunchAtMillis?.let {
                    SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(it))
                } ?: "1"
                val logCommand = "/system/bin/logcat --uid=$uid -v threadtime -T '$since'"
                val process = if (root) ProcessBuilder("su", "-c", logCommand).redirectErrorStream(true).start()
                    else ProcessBuilder("/system/bin/logcat", "--uid=$uid", "-v", "threadtime", "-T", since).redirectErrorStream(true).start()
                androidLogcatProcess = process
                _state.update { it.copy(androidLogcat = it.androidLogcat.copy(available = true, running = true, paused = false, message = null, packageName = packageName)) }
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { raw ->
                        if (!_state.value.androidLogcat.paused) appendAndroidLogLine(raw)
                    }
                }
            }.onFailure { error ->
                _state.update { it.copy(androidLogcat = it.androidLogcat.copy(running = false, message = error.message ?: str(R.string.an_logcat_stopped))) }
            }
            androidLogcatProcess = null
            _state.update { it.copy(androidLogcat = it.androidLogcat.copy(running = false)) }
        }
    }

    private fun appendAndroidLogLine(raw: String) {
        val levelChar = Regex("\\s([VDIWEF])\\s").find(raw)?.groupValues?.getOrNull(1)
        val level = when (levelChar) {
            "D" -> AndroidLogLevel.DEBUG; "I" -> AndroidLogLevel.INFO; "W" -> AndroidLogLevel.WARNING
            "E" -> AndroidLogLevel.ERROR; "F" -> AndroidLogLevel.FATAL; else -> AndroidLogLevel.VERBOSE
        }
        val location = Regex("([A-Za-z0-9_]+\\.(?:kt|java)):(\\d+)").find(raw)
        _state.update { current ->
            val line = AndroidLogLine(
                id = System.nanoTime(), text = raw, level = level,
                filePath = location?.groupValues?.getOrNull(1), line = location?.groupValues?.getOrNull(2)?.toIntOrNull(),
            )
            current.copy(androidLogcat = current.androidLogcat.copy(lines = (current.androidLogcat.lines + line).takeLast(MAX_LOGCAT_LINES)))
        }
    }

    fun stopAndroidLogcat() {
        androidLogcatProcess?.destroy()
        androidLogcatJob?.cancel()
        androidLogcatJob = null
        _state.update { it.copy(androidLogcat = it.androidLogcat.copy(running = false)) }
    }

    fun toggleAndroidLogcatPause() = _state.update { it.copy(androidLogcat = it.androidLogcat.copy(paused = !it.androidLogcat.paused)) }
    fun clearAndroidLogcat() = _state.update { it.copy(androidLogcat = it.androidLogcat.copy(lines = emptyList())) }
    fun setAndroidLogcatQuery(value: String) = _state.update { it.copy(androidLogcat = it.androidLogcat.copy(query = value)) }
    fun setAndroidLogcatLevel(value: AndroidLogLevel) = _state.update { it.copy(androidLogcat = it.androidLogcat.copy(minimumLevel = value)) }

    fun openAndroidLogLine(line: AndroidLogLine) {
        val fileName = line.filePath ?: return
        openAndroidIssue(AndroidBuildIssue(title = "Logcat", detail = line.text, filePath = fileName, line = line.line))
    }


    fun toggleTheme() {
        val next = if (_state.value.themeMode == com.jarves.mh.ui.theme.AppThemeMode.DARK) {
            com.jarves.mh.ui.theme.AppThemeMode.LIGHT
        } else {
            com.jarves.mh.ui.theme.AppThemeMode.DARK
        }
        setThemeMode(next)
    }

    fun setThemeMode(mode: com.jarves.mh.ui.theme.AppThemeMode) {
        preferences.themeMode = mode.name.lowercase()
        _state.update { it.copy(themeMode = mode) }
    }

    fun setLanguage(code: String) {
        preferences.languageCode = code
        _state.update { it.copy(languageCode = code) }
    }

    fun setOwnerDisplayName(name: String) {
        val cleaned = name.trim()
        preferences.ownerDisplayName = cleaned
        _state.update { it.copy(ownerDisplayName = cleaned) }
    }

    private fun effectiveOwnerName(): String =
        _state.value.ownerDisplayName.trim().ifBlank { "เจ้าของบริษัท" }

    fun finishInitialLanguageSetup() {
        preferences.initialLanguageSelected = true
        _state.update { it.copy(initialLanguageSelected = true) }
    }

    fun reopenInitialLanguageSetup() {
        preferences.initialLanguageSelected = false
        _state.update { it.copy(initialLanguageSelected = false) }
    }

    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()

    fun getSavedApiKeys(kind: ProviderKind): List<ApiKeyInfo> = vault.list(kind.name)

    fun addApiKey(kind: ProviderKind, name: String, secret: String): List<ApiKeyInfo> {
        vault.add(kind.name, name, secret)
        val keys = vault.list(kind.name)
        refreshActiveApiKey(kind)
        return keys
    }

    fun activateApiKey(kind: ProviderKind, keyId: String): List<ApiKeyInfo> {
        vault.activate(kind.name, keyId)
        refreshActiveApiKey(kind)
        return vault.list(kind.name)
    }

    fun removeApiKey(kind: ProviderKind, keyId: String): List<ApiKeyInfo> {
        vault.remove(kind.name, keyId)
        refreshActiveApiKey(kind)
        return vault.list(kind.name)
    }

    private fun refreshActiveApiKey(kind: ProviderKind) {
        if (_state.value.provider.kind != kind) return
        val keys = vault.list(kind.name)
        _state.update { current ->
            current.copy(
                activeApiKeyName = keys.firstOrNull(ApiKeyInfo::isActive)?.name,
                provider = current.provider.copy(hasSecret = keys.isNotEmpty()),
            )
        }
    }

    /** Keeps both agent bridges mapped to the same workspace root; the active one is used. */
    private fun configureBridgeRoots(projectId: String, rootPath: String) {
        claudeRuntime.configureProjectRoot(projectId, rootPath)
        dshRuntime.configureProjectRoot(projectId, rootPath)
        antigravityRuntime.configureProjectRoot(projectId, rootPath)
    }

    init {
        viewModelScope.launch { RuntimeSetupController.snapshot.collect(::onSetupSnapshot) }
        viewModelScope.launch { claudeRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        if (!supportsArm64Runtime(android.os.Build.SUPPORTED_ABIS, System.getProperty("os.arch"))) {
            _state.update {
                it.copy(
                    startupStage = StartupStage.SETUP_REQUIRED,
                    startupMessage = str(R.string.vm_arm64_required),
                    startupError = null,
                    startupErrorIsOffline = false,
                )
            }
            return
        }
        val setupSnapshot = RuntimeSetupController.snapshot.value
        if (setupSnapshot.status == RuntimeSetupStatus.RUNNING) {
            onSetupSnapshot(setupSnapshot)
            resumeRuntimeSetupService()
            return
        }
        val installed = withContext(Dispatchers.IO) {
            // Upgrades from the old single-bundle layout keep every already-installed tool.
            installer.migrateLegacyToolMarkers()
            installer.isInstalled().also { ready ->
                if (ready) {
                    installer.cleanupLegacyWorkspaceScaffolding()
                    runCatching { com.jarves.mh.backup.BackupManager.applyPendingHome(getApplication<Application>().filesDir) }
                }
            }
        }
        _state.update { current ->
            current.copy(
                installedDevStacks = if (installed) installer.installedStacks() else current.installedDevStacks,
                installedAgentVersions = if (installed) installer.installedAgentVersions() else emptyMap(),
            )
        }
        when {
            !installed && setupSnapshot.status == RuntimeSetupStatus.ERROR -> onSetupSnapshot(setupSnapshot)
            !installed -> _state.update { it.copy(startupStage = StartupStage.SETUP_REQUIRED, startupProgress = 0f) }
            !preferences.onboardingComplete -> {
                preferences.runtimeSetupComplete = true
                _state.update { it.copy(startupStage = StartupStage.MODEL_SETUP, startupProgress = 1f) }
            }
            else -> initializeRuntime()
        }
    }

    fun startRuntimeSetup() {
        if (state.value.startupStage == StartupStage.INSTALLING) return
        setupCompletionHandled = false
        _state.update {
            it.copy(
                startupStage = StartupStage.INSTALLING,
                startupProgress = 0.01f,
                startupMessage = str(R.string.vm_preparing_workspace),
                startupBytes = null,
                startupLogs = listOf("\$ " + str(R.string.vm_preparing_workspace)),
                startupIndeterminate = false,
                startupError = null,
                startupErrorIsOffline = false,
                showDetailedSetupProgress = true,
            )
        }
        resumeRuntimeSetupService()
    }

    fun retryStartup() {
        if (installer.isInstalled()) viewModelScope.launch { initializeRuntime() } else {
            _state.update { it.copy(startupStage = StartupStage.SETUP_REQUIRED, startupError = null) }
            startRuntimeSetup()
        }
    }

    private fun resumeRuntimeSetupService() {
        val stacks = _state.value.selectedDevStacks.joinToString(",") { it.name }
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_START)
                .putExtra(RuntimeSetupService.EXTRA_STACKS, stacks)
                .putExtra(RuntimeSetupService.EXTRA_AGENT, _state.value.agentKind.name),
        )
    }

    private fun onSetupSnapshot(snapshot: RuntimeSetupSnapshot) {
        when (snapshot.status) {
            RuntimeSetupStatus.RUNNING -> _state.update {
                it.copy(
                    startupStage = StartupStage.INSTALLING,
                    startupProgress = snapshot.progress,
                    startupMessage = snapshot.message,
                    startupBytes = snapshot.totalBytes?.let { total -> (snapshot.downloadedBytes ?: 0L) to total },
                    startupLogs = snapshot.logs,
                    startupIndeterminate = snapshot.indeterminate,
                    startupError = null,
                    startupErrorIsOffline = false,
                )
            }
            RuntimeSetupStatus.COMPLETE -> {
                if (setupCompletionHandled) return
                setupCompletionHandled = true
                preferences.runtimeSetupComplete = true
                viewModelScope.launch {
                    val (installedStacks, installedAgents) = withContext(Dispatchers.IO) {
                        installer.installedStacks() to installer.installedAgentVersions()
                    }
                    _state.update {
                        it.copy(
                            installedDevStacks = installedStacks,
                            installedAgentVersions = installedAgents,
                        )
                    }
                    if (preferences.onboardingComplete) {
                        initializeRuntime()
                    } else {
                        _state.update {
                            it.copy(
                                startupStage = StartupStage.MODEL_SETUP,
                                startupProgress = 1f,
                                startupBytes = null,
                                startupIndeterminate = false,
                            )
                        }
                    }
                }
            }
            RuntimeSetupStatus.ERROR -> _state.update {
                it.copy(
                    startupStage = StartupStage.ERROR,
                    startupMessage = snapshot.message,
                    startupProgress = snapshot.progress,
                    startupLogs = snapshot.logs,
                    startupIndeterminate = false,
                    startupError = snapshot.errorMessage,
                    startupErrorIsOffline = snapshot.offline,
                )
            }
            RuntimeSetupStatus.CANCELLED -> _state.update {
                it.copy(
                    startupStage = StartupStage.SETUP_REQUIRED,
                    startupMessage = str(R.string.vm_setup_paused),
                    startupProgress = snapshot.progress,
                    startupLogs = snapshot.logs,
                    startupIndeterminate = false,
                )
            }
            RuntimeSetupStatus.IDLE -> Unit
        }
    }

    private suspend fun initializeRuntime() {
        val startedAt = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                startupStage = StartupStage.INITIALIZING,
                startupProgress = 0.05f,
                startupMessage = str(R.string.vm_opening_workspace),
                startupBytes = null,
                startupLogs = listOf("\$ " + str(R.string.vm_opening_workspace)),
                startupIndeterminate = false,
                startupError = null,
                startupErrorIsOffline = false,
            )
        }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                installer.initializeExisting { progress ->
                    _state.update { current ->
                        current.copy(
                            startupProgress = 0.05f + progress.fraction * 0.95f,
                            startupMessage = progress.message,
                            startupBytes = null,
                            startupLogs = mergeStartupLog(current.startupLogs, progress),
                        )
                    }
                }
            }
        }
        if (result.isSuccess) {
            // The real version probe can finish in a fraction of a second on fast phones.
            // Keep the successful loading state visible long enough to be understandable.
            val remaining = MINIMUM_INITIALIZATION_SCREEN_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (remaining > 0) delay(remaining)
            _state.update {
                it.copy(
                    startupStage = StartupStage.READY,
                    startupProgress = 1f,
                    installedDevStacks = installer.installedStacks(),
                    installedAgentVersions = installer.installedAgentVersions(),
                )
            }
            autoPingApi()
            checkForAppUpdate()
        } else {
            showStartupError(result.exceptionOrNull() ?: IllegalStateException(str(R.string.vm_initialization_failed)))
        }
    }

    fun checkForAppUpdate(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - preferences.lastAppUpdateCheckMillis < 24L * 60L * 60L * 1000L) return
        viewModelScope.launch(Dispatchers.IO) {
            val update = runCatching { appUpdater().check() }.getOrNull()
            preferences.lastAppUpdateCheckMillis = System.currentTimeMillis()
            if (update != null) {
                _state.update {
                    it.copy(appUpdate = update, appUpdateStatus = AppUpdateStatus.AVAILABLE, appUpdateError = null)
                }
            }
        }
    }

    /** Debug builds only: persist a manifest URL override and re-check immediately. */
    fun setDebugUpdateManifestUrl(url: String) {
        if (!BuildConfig.DEBUG) return
        preferences.debugUpdateManifestUrl = url.trim()
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Debug builds only: clear the manifest URL override and re-check the default channel. */
    fun clearDebugUpdateManifestUrl() {
        if (!BuildConfig.DEBUG) return
        preferences.debugUpdateManifestUrl = ""
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Debug builds only: the currently-active manifest URL override (empty = default). */
    fun debugUpdateManifestUrl(): String = if (BuildConfig.DEBUG) preferences.debugUpdateManifestUrl else ""

    fun installAppUpdate() {
        val info = _state.value.appUpdate ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getApplication<Application>().packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(appUpdateStatus = AppUpdateStatus.PERMISSION_REQUIRED) }
            return
        }
        if (_state.value.appUpdateStatus == AppUpdateStatus.DOWNLOADING) return
        _state.update {
            it.copy(appUpdateStatus = AppUpdateStatus.DOWNLOADING, appUpdateDownloadedBytes = 0L, appUpdateTotalBytes = info.sizeBytes, appUpdateError = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appUpdater().download(info) { downloaded, total ->
                    _state.update { current -> current.copy(appUpdateDownloadedBytes = downloaded, appUpdateTotalBytes = total) }
                }
            }.onSuccess { apk ->
                _state.update { it.copy(appUpdateStatus = AppUpdateStatus.INSTALLING) }
                runCatching { AndroidAppInstaller.install(getApplication(), apk) }.onFailure { error ->
                    _state.update { it.copy(appUpdateStatus = AppUpdateStatus.ERROR, appUpdateError = error.message ?: str(R.string.vm_installer_start_failed)) }
                }
            }.onFailure { error ->
                _state.update { it.copy(appUpdateStatus = AppUpdateStatus.ERROR, appUpdateError = error.message ?: str(R.string.vm_update_download_failed)) }
            }
        }
    }

    fun dismissAppUpdateError() {
        _state.update { it.copy(appUpdateStatus = AppUpdateStatus.AVAILABLE, appUpdateError = null) }
    }

    private fun mergeStartupLog(
        existing: List<String>,
        progress: RuntimeInstallProgress,
    ): List<String> {
        val prefix = "\$ ${progress.message}"
        val bytes = progress.totalBytes?.let { total ->
            val downloaded = progress.downloadedBytes ?: 0L
            " — %.1f / %.1f MB".format(downloaded / 1_048_576.0, total / 1_048_576.0)
        }.orEmpty()
        val nextLine = prefix + bytes
        val updated = if (existing.lastOrNull()?.startsWith(prefix) == true) {
            existing.dropLast(1) + nextLine
        } else {
            existing + nextLine
        }
        return updated.takeLast(80)
    }

    private fun showStartupError(error: Throwable) {
        val isOffline = generateSequence(error as Throwable?) { it.cause }
            .any { cause ->
                cause is UnknownHostException ||
                    cause.message.orEmpty().contains("unable to resolve host", ignoreCase = true) ||
                    cause.message.orEmpty().contains("no address associated with hostname", ignoreCase = true)
            }
        val message = if (isOffline) {
            str(R.string.vm_setup_offline)
        } else {
            error.message?.take(300) ?: str(R.string.vm_setup_generic_error)
        }
        _state.update {
            it.copy(
                startupStage = StartupStage.ERROR,
                startupError = message,
                startupErrorIsOffline = isOffline,
            )
        }
    }

    fun finishOnboarding(profile: ProviderProfile, secret: String) {
        vault.put(profile.kind.name, secret)
        val saved = profile.copy(
            hasSecret = secret.isNotBlank() || vault.contains(profile.kind.name),
        )
        preferences.saveProvider(saved, _state.value.agentKind)
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, provider = saved, startupStage = StartupStage.READY) }
        refreshActiveApiKey(profile.kind)
        autoPingApi()
    }

    fun finishAntigravityOnboarding() {
        check(_state.value.antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN) {
            str(R.string.vm_antigravity_sign_in_first)
        }
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, startupStage = StartupStage.READY) }
    }

    /** Finishes first run without a provider so it can be configured later from Settings. */
    fun skipOnboarding() {
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, startupStage = StartupStage.READY) }
    }

    /** Lets first-run users escape a provider/login failure without losing saved credentials. */
    fun chooseOnboardingAgent(kind: AgentKind) {
        selectAgent(kind)
        _state.update {
            it.copy(
                startupStage = if (installer.isAgentInstalled(kind)) {
                    StartupStage.MODEL_SETUP
                } else {
                    StartupStage.SETUP_REQUIRED
                },
                startupError = null,
                startupErrorIsOffline = false,
            )
        }
    }

    fun updateProvider(profile: ProviderProfile, secret: String) = finishOnboarding(profile, secret)

    fun finishBackgroundSetup() {
        preferences.backgroundSetupComplete = true
        _state.update { it.copy(backgroundSetupComplete = true) }
    }

    /** Called from the first-launch setup screen; persists the agent choice for setup and Settings. */
    fun selectAgent(kind: AgentKind) {
        if (_state.value.agentKind == kind) return
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = str(R.string.vm_stop_agent_before_switching)) }
            return
        }
        val selectingInitialAgent = !preferences.runtimeSetupComplete
        preferences.agentKind = kind.stableId
        if (selectingInitialAgent) preferences.primaryAgentKind = kind.stableId
        _state.update { current ->
            preferences.saveProvider(current.provider, current.agentKind)
            val provider = preferences.loadProvider(vault, kind)
            current.copy(
                agentKind = kind,
                primaryAgentKind = if (selectingInitialAgent) kind else current.primaryAgentKind,
                provider = provider,
                activeApiKeyName = vault.list(provider.kind.name).firstOrNull(ApiKeyInfo::isActive)?.name,
                // Ping results belong to the previous agent; never leak them across.
                apiPingStatus = ApiPingStatus.IDLE,
                apiPingMessage = null,
            )
        }
    }

    /** Installs the other agent on demand (Settings) with live progress, then switches to it. */
    fun installAgent(kind: AgentKind) {
        if (_state.value.agentInstalling != null) return
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = str(R.string.vm_stop_agent_before_switching)) }
            return
        }
        if (installer.isAgentInstalled(kind)) {
            selectAgent(kind)
            return
        }
        _state.update {
            it.copy(
                agentInstalling = kind,
                agentMessage = str(R.string.vm_preparing_named, kind.localizedTitle(getApplication())),
                agentInstallError = null,
                agentInstallErrorKind = null,
                agentProgress = 0f,
                agentDownloadedBytes = null,
                agentTotalBytes = null,
                agentBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            var sampleBytes = 0L
            var sampleAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    agentRegistry.require(kind).install(installer) { progress ->
                        val now = SystemClock.elapsedRealtime()
                        val bytes = progress.downloadedBytes
                        val elapsed = now - sampleAt
                        val speed = if (bytes != null && elapsed >= 500L) {
                            ((bytes - sampleBytes).coerceAtLeast(0L) * 1_000L / elapsed.coerceAtLeast(1L)).also {
                                sampleBytes = bytes
                                sampleAt = now
                            }
                        } else _state.value.agentBytesPerSecond
                        _state.update { current ->
                            current.copy(
                                agentMessage = progress.message,
                                agentProgress = progress.fraction.coerceIn(0f, 1f),
                                agentDownloadedBytes = bytes ?: current.agentDownloadedBytes,
                                agentTotalBytes = progress.totalBytes ?: current.agentTotalBytes,
                                agentBytesPerSecond = speed,
                            )
                        }
                    }
                }
            }
            result.onSuccess {
                if (kind == AgentKind.DEEPSEEK_HARNESS) preferences.dshVersion = installer.dshVersion
                selectAgent(kind)
            }
            val failureMessage = result.exceptionOrNull()?.let { err ->
                err.message?.take(200)?.takeIf { it.isNotBlank() }
                    ?: str(R.string.vm_install_failed_named, kind.localizedTitle(getApplication()))
            }
            _state.update { current ->
                current.copy(
                    installedAgentVersions = installer.installedAgentVersions(),
                    agentInstalling = null,
                    agentProgress = 0f,
                    agentDownloadedBytes = null,
                    agentTotalBytes = null,
                    agentBytesPerSecond = null,
                    agentInstallError = failureMessage,
                    agentInstallErrorKind = if (failureMessage != null) kind else null,
                    toastMessage = failureMessage ?: current.toastMessage,
                    agentMessage = result.fold(
                        onSuccess = { str(R.string.vm_agent_ready, kind.localizedTitle(getApplication())) },
                        onFailure = { failureMessage },
                    ),
                )
            }
        }
    }

    fun checkAgentUpdates() {
        if (_state.value.agentUpdatesChecking || _state.value.agentUpdating != null || _state.value.isRunning) return
        _state.update { it.copy(agentUpdatesChecking = true, agentUpdateMessage = str(R.string.vm_checking_agent_releases)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { installer.checkAgentUpdates() } }
            _state.update {
                it.copy(
                    agentUpdates = result.getOrDefault(emptyMap()),
                    agentUpdatesChecking = false,
                    agentUpdateMessage = result.fold(
                        onSuccess = { updates -> if (updates.isEmpty()) str(R.string.vm_agents_up_to_date) else plural(R.plurals.vm_agent_updates_available, updates.size, updates.size) },
                        onFailure = { error -> error.message?.take(200) ?: str(R.string.vm_agent_update_check_failed) },
                    ),
                )
            }
        }
    }

    fun updateAgent(kind: AgentKind) {
        val update = _state.value.agentUpdates[kind] ?: return
        if (_state.value.agentUpdating != null || _state.value.agentInstalling != null || _state.value.isRunning) return
        _state.update {
            it.copy(
                agentUpdating = kind,
                agentUpdateMessage = str(R.string.vm_preparing_version, kind.localizedTitle(getApplication()), update.latestVersion),
                agentUpdateProgress = 0f,
                agentUpdateIndeterminate = false,
                agentUpdateDownloadedBytes = null,
                agentUpdateTotalBytes = null,
                agentUpdateBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            var sampleBytes = 0L
            var sampleAt = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    installer.updateAgent(kind, update.latestVersion) { progress ->
                        val now = SystemClock.elapsedRealtime()
                        val bytes = progress.downloadedBytes
                        val elapsed = now - sampleAt
                        val speed = if (bytes != null && elapsed >= 500L) {
                            ((bytes - sampleBytes).coerceAtLeast(0L) * 1_000L / elapsed.coerceAtLeast(1L)).also {
                                sampleBytes = bytes
                                sampleAt = now
                            }
                        } else _state.value.agentUpdateBytesPerSecond
                        _state.update {
                            it.copy(
                                agentUpdateMessage = progress.message,
                                agentUpdateProgress = progress.fraction.coerceIn(0f, 1f),
                                agentUpdateIndeterminate = progress.indeterminate,
                                agentUpdateDownloadedBytes = bytes ?: it.agentUpdateDownloadedBytes,
                                agentUpdateTotalBytes = progress.totalBytes ?: it.agentUpdateTotalBytes,
                                agentUpdateBytesPerSecond = speed,
                            )
                        }
                    }
                }
            }
            _state.update { current ->
                current.copy(
                    installedAgentVersions = installer.installedAgentVersions(),
                    agentUpdates = if (result.isSuccess) current.agentUpdates - kind else current.agentUpdates,
                    agentUpdating = null,
                    agentUpdateMessage = result.fold(
                        onSuccess = { str(R.string.vm_agent_updated_to, kind.localizedTitle(getApplication()), update.latestVersion) },
                        onFailure = { error -> error.message?.take(220) ?: str(R.string.vm_update_failed_named, kind.localizedTitle(getApplication())) },
                    ),
                    agentUpdateProgress = if (result.isSuccess) 1f else 0f,
                    agentUpdateIndeterminate = false,
                    agentUpdateDownloadedBytes = null,
                    agentUpdateTotalBytes = null,
                    agentUpdateBytesPerSecond = null,
                )
            }
        }
    }

    fun startAntigravityLogin() {
        if (_state.value.agentInstalling != null || _state.value.isRunning) return
        lastOpenedAntigravityAuthUrl = null
        viewModelScope.launch { antigravityAuthController.beginLogin() }
    }

    fun submitAntigravityCode(code: String) {
        runCatching { antigravityAuthController.submitCode(code) }
            .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: str(R.string.vm_submit_code_failed)) } }
    }

    fun logoutAntigravity() {
        viewModelScope.launch {
            runCatching { antigravityAuthController.logout() }
                .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: str(R.string.vm_sign_out_failed)) } }
        }
    }

    fun startTeamAgentAntigravityLogin(slot: AgentSlot) {
        if (_state.value.agentInstalling != null) return
        val controller = teamAntigravityControllers[slot] ?: return
        lastOpenedAntigravityAuthUrl = null
        viewModelScope.launch { controller.beginLogin() }
    }

    fun submitTeamAgentAntigravityCode(slot: AgentSlot, code: String) {
        val controller = teamAntigravityControllers[slot] ?: return
        runCatching { controller.submitCode(code) }
            .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: str(R.string.vm_submit_code_failed)) } }
    }

    fun logoutTeamAgentAntigravity(slot: AgentSlot) {
        val controller = teamAntigravityControllers[slot] ?: return
        viewModelScope.launch {
            runCatching { controller.logout() }
                .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: str(R.string.vm_sign_out_failed)) } }
        }
    }

    fun setAntigravityModel(model: String) {
        preferences.antigravityModel = model
        val modelEffort = antigravityEffortFromModel(model)
        if (modelEffort != null) preferences.antigravityEffort = modelEffort
        _state.update {
            it.copy(
                antigravityModel = model,
                antigravityEffort = modelEffort ?: it.antigravityEffort,
            )
        }
    }

    fun setAntigravityEffort(effort: String) {
        if (effort !in setOf("low", "medium", "high")) return
        val current = _state.value
        val matchingModel = antigravityModelWithEffort(current.antigravityModel, effort)
            ?.takeIf { candidate -> current.antigravityModels.isEmpty() || candidate in current.antigravityModels }
        if (current.antigravityModel.isNotBlank() &&
            antigravityEffortFromModel(current.antigravityModel) != null &&
            matchingModel == null
        ) {
            _state.update { it.copy(toastMessage = str(
                R.string.vm_model_no_effort,
                str(
                    when (effort) {
                        "low" -> R.string.vm_effort_low
                        "medium" -> R.string.vm_effort_medium
                        else -> R.string.vm_effort_high
                    },
                ),
            )) }
            return
        }
        preferences.antigravityEffort = effort
        matchingModel?.let { preferences.antigravityModel = it }
        _state.update {
            it.copy(
                antigravityEffort = effort,
                antigravityModel = matchingModel ?: it.antigravityModel,
            )
        }
    }

    fun refreshAntigravityModels() {
        if (_state.value.antigravityModelsLoading || !installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) return
        _state.update { it.copy(antigravityModelsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val runtime = installer.installedRuntime()
                val workspace = File(getApplication<Application>().filesDir, "workspaces/antigravity-models").apply { mkdirs() }
                val process = installer.process(
                    runtime.proot,
                    runtime.rootfs,
                    workspace,
                    emptyMap(),
                    listOf(com.jarves.mh.runtime.RuntimeInstaller.AGY_GUEST_PATH, "models"),
                    guestWorkspacePath = "/workspace/antigravity-models",
                    emulateHardLinks = false,
                )
                while (process.isAlive) delay(50)
                check(process.waitFor() == 0) { str(R.string.vm_antigravity_list_models_failed) }
                val output = (process as? NativeSpawnProcess)?.outputFile?.readTailText(MAX_PROCESS_OUTPUT_BYTES).orEmpty()
                output.lineSequence()
                    .map { sanitizeTerminalOutput(it).trim() }
                    .mapNotNull { line -> line.split(Regex("\\s+"), limit = 2).firstOrNull() }
                    .filter { it.matches(Regex("[a-z0-9][a-z0-9._-]+")) }
                    .distinct()
                    .toList()
                    .also { check(it.isNotEmpty()) { str(R.string.vm_antigravity_no_models) } }
            }
            withContext(Dispatchers.Main) {
                _state.update { current ->
                    result.fold(
                        onSuccess = { models ->
                            val preferred = antigravityModelWithEffort(
                                current.antigravityModel,
                                current.antigravityEffort,
                            )?.takeIf(models::contains)
                            val selected = preferred
                                ?: current.antigravityModel.takeIf(models::contains)
                                ?: models.first()
                            val selectedEffort = antigravityEffortFromModel(selected) ?: current.antigravityEffort
                            preferences.antigravityModel = selected
                            preferences.antigravityEffort = selectedEffort
                            current.copy(
                                antigravityModelsLoading = false,
                                antigravityModels = models,
                                antigravityModel = selected,
                                antigravityEffort = selectedEffort,
                            )
                        },
                        onFailure = { error -> current.copy(
                            antigravityModelsLoading = false,
                            toastMessage = error.message ?: str(R.string.vm_antigravity_models_load_failed),
                        ) },
                    )
                }
            }
        }
    }

    /** Called from the first-launch tool picker; persists the choice for setup and Settings. */
    fun toggleDevStack(stack: DevStack) {
        if (stack == DevStack.WEB) return
        val updated = _state.value.selectedDevStacks.toMutableSet().apply {
            if (!add(stack)) remove(stack)
        }
        preferences.selectedDevStacks = updated.map { it.name }.toSet()
        _state.update { it.copy(selectedDevStacks = updated) }
    }

    private var pendingAndroidHealthRefreshAfterStack: Boolean = false

    fun setAutoInstallMissingTools(enabled: Boolean) {
        preferences.autoInstallMissingTools = enabled
        _state.update { it.copy(autoInstallMissingTools = enabled) }
    }

    fun setKeepAliveInBackground(enabled: Boolean) {
        preferences.keepAliveInBackground = enabled
        _state.update { it.copy(keepAliveInBackground = enabled) }
        if (enabled) {
            RuntimeExecutionService.ensureKeepAliveIfEnabled(getApplication())
        } else {
            RuntimeExecutionService.stopKeepAlive(getApplication())
        }
    }

    /** Installs one development stack on demand in a background Foreground Service (survives leaving the app). */
    fun installDevStack(stack: DevStack, refreshAndroidHealthAfter: Boolean = false) {
        if (_state.value.devStackInstalling != null || DevStackInstallController.snapshot.value.activeStack != null) return
        pendingAndroidHealthRefreshAfterStack = refreshAndroidHealthAfter
        _state.update {
            it.copy(
                devStackInstalling = stack,
                devStackRemoving = false,
                devStackMessage = str(R.string.vm_preparing_named, stack.localizedLabel(getApplication())),
                devStackProgress = 0.01f,
                devStackBytes = null,
                devStackBytesPerSecond = null,
            )
        }
        RuntimeSetupService.startInstallStack(getApplication(), stack)
    }

    /** Removes an optional toolchain in a background Foreground Service after the Settings confirmation dialog. */
    fun removeDevStack(stack: DevStack) {
        if (_state.value.devStackInstalling != null || DevStackInstallController.snapshot.value.activeStack != null || stack == DevStack.WEB) return
        if (_state.value.isRunning || _state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = str(R.string.vm_stop_before_removing_tools)) }
            return
        }
        pendingAndroidHealthRefreshAfterStack = false
        val selected = _state.value.selectedDevStacks - stack
        preferences.selectedDevStacks = selected.map { it.name }.toSet()
        _state.update {
            it.copy(
                selectedDevStacks = selected,
                devStackInstalling = stack,
                devStackRemoving = true,
                devStackMessage = str(R.string.vm_removing_named, stack.localizedLabel(getApplication())),
                devStackProgress = 0.1f,
                devStackBytes = null,
                devStackBytesPerSecond = null,
            )
        }
        RuntimeSetupService.startRemoveStack(getApplication(), stack)
    }

    suspend fun discoverModels(profile: ProviderProfile, secret: String): ModelDiscoveryResult {
        val key = secret.ifBlank { vault.get(profile.kind.name).orEmpty() }
        return providerApi.discoverModels(profile.baseUrl, key, providerProtocolForAgent(profile, _state.value.agentKind))
    }

    suspend fun validateProvider(
        profile: ProviderProfile,
        secret: String,
        models: List<com.jarves.mh.network.DiscoveredModel>,
    ): ConnectionValidation {
        val key = secret.ifBlank { vault.get(profile.kind.name).orEmpty() }
        return providerApi.validate(
            profile.baseUrl,
            profile.model,
            key,
            providerProtocolForAgent(profile, _state.value.agentKind),
            models,
            profile.openRouterProviderOrder,
            profile.openRouterAllowFallbacks,
        )
    }

    /**
     * Background check at startup/after setup. Skips Claude subscriptions: their test boots Claude
     * Code (about a minute of heavy work on older phones), so it only runs when the user taps Test.
     */
    private fun autoPingApi() {
        val profile = _state.value.provider
        if (_state.value.agentKind == AgentKind.CLAUDE_CODE && profile.kind == ProviderKind.CLAUDE) return
        pingApi()
    }

    fun pingApi() {
        if (_state.value.agentKind == AgentKind.ANTIGRAVITY) {
            testAntigravityConnection()
            return
        }
        val profile = _state.value.provider
        if (_state.value.agentKind == AgentKind.CLAUDE_CODE && profile.kind == ProviderKind.CLAUDE) {
            testClaudeSubscription(profile)
            return
        }
        if (profile.baseUrl.isBlank() || profile.model.isBlank()) return
        if (_state.value.apiPingStatus == ApiPingStatus.PINGING) return
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = str(R.string.vm_ping_sending)) }
        viewModelScope.launch {
            val key = vault.get(profile.kind.name).orEmpty()
            val result = providerApi.validate(
                profile.baseUrl,
                profile.model,
                key,
                providerProtocolForAgent(profile, _state.value.agentKind),
                emptyList(),
                profile.openRouterProviderOrder,
                profile.openRouterAllowFallbacks,
            )
            when (result) {
                is ConnectionValidation.Success -> _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.OK, apiPingMessage = str(R.string.vm_ping_success))
                }
                is ConnectionValidation.Failure -> _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.FAILED, apiPingMessage = result.message)
                }
            }
        }
    }

    /** Claude subscriptions have no HTTP endpoint to ping, so ask Claude Code itself for a one-word reply. */
    private fun testClaudeSubscription(profile: ProviderProfile) {
        if (_state.value.apiPingStatus == ApiPingStatus.PINGING) return
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = str(R.string.vm_claude_hello)) }
        viewModelScope.launch {
            runCatching { claudeRuntime.hello(profile) }
                .onSuccess {
                    _state.update { it.copy(apiPingStatus = ApiPingStatus.OK, apiPingMessage = str(R.string.vm_claude_working)) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            apiPingStatus = ApiPingStatus.FAILED,
                            apiPingMessage = error.message?.replace(Regex("\\s+"), " ")?.trim()?.take(240)
                                ?: str(R.string.vm_unknown_error),
                        )
                    }
                }
        }
    }

    /** Sends a tiny hello to the agy CLI to prove it actually answers. Silent timeout inside. */
    fun testAntigravityConnection() {
        if (_state.value.agentKind != AgentKind.ANTIGRAVITY) return
        if (_state.value.apiPingStatus == ApiPingStatus.PINGING) return
        if (_state.value.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN) {
            _state.update {
                it.copy(
                    apiPingStatus = ApiPingStatus.FAILED,
                    apiPingMessage = str(R.string.vm_antigravity_needs_signin),
                )
            }
            return
        }
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = str(R.string.vm_antigravity_hello)) }
        viewModelScope.launch {
            val result = runCatching { antigravityRuntime.hello() }
            result.onSuccess {
                _state.update {
                    it.copy(
                        apiPingStatus = ApiPingStatus.OK,
                        apiPingMessage = str(R.string.vm_antigravity_working),
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.FAILED, apiPingMessage = helloFailureMessage(error.message.orEmpty()))
                }
            }
        }
    }

    private fun helloFailureMessage(raw: String): String {
        val value = raw.replace(Regex("\\s+"), " ").trim()
        return when {
            value.contains("not installed", true) -> str(R.string.vm_antigravity_cli_missing)
            value.contains("sign-in", true) || value.contains("not signed in", true) ||
                value.contains("authentication", true) -> str(R.string.vm_antigravity_reconnect)
            value.contains("did not answer", true) -> str(R.string.vm_antigravity_no_answer)
            value.isBlank() -> str(R.string.vm_antigravity_no_answer)
            else -> value.take(200)
        }
    }

    fun openProject(project: Project) {
        val current = _state.value
        if (current.activeProject?.id == project.id) {
            _state.update {
                it.copy(
                    workspaceVisible = true,
                    readOnlyProject = null,
                    readOnlyProjectChats = emptyList(),
                    readOnlyChatId = null,
                    readOnlyMessages = emptyList(),
                )
            }
            return
        }
        if (current.isRunning || current.projectTerminalRunning || current.androidBuildRunning) {
            val chats = preferences.loadProjectChats(project.id).ifEmpty {
                listOf(ProjectChat(title = str(R.string.vm_main_chat)))
            }
            val chat = chats.first()
            _state.update {
                it.copy(
                    readOnlyProject = project,
                    readOnlyProjectChats = chats,
                    readOnlyChatId = chat.id,
                    readOnlyMessages = preferences.loadMessages(project.id, chat.id),
                )
            }
            return
        }
        configureBridgeRoots(project.id, project.rootPath)
        val terminal = loadProjectTerminal(project)
        val suggestedRoot = if (project.rootPath.isBlank()) detectNestedProjectRoot(project) else null
        val chats = preferences.loadProjectChats(project.id).ifEmpty {
            listOf(ProjectChat(title = str(R.string.vm_main_chat))).also { preferences.saveProjectChats(project.id, it) }
        }
        val activeChat = chats.first()
        val saved = preferences.loadMessages(project.id, activeChat.id)
        val msgs = saved.ifEmpty { listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")) }
        val androidBuild = loadAndroidBuildRecord(project.id)
        _state.update {
            it.copy(
                activeProject = project,
                workspaceVisible = true,
                readOnlyProject = null,
                readOnlyProjectChats = emptyList(),
                readOnlyChatId = null,
                readOnlyMessages = emptyList(),
                projectChats = chats,
                activeChatId = activeChat.id,
                messages = msgs,
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = true,
                projectTerminalLines = terminal.lines,
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = terminal.cwd,
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = suggestedRoot,
                previewReady = false,
                previewUrl = null,
                pendingAttachments = emptyList(),
                androidBuildRunning = false,
                androidBuildPhase = androidBuild.phase,
                androidBuildAction = androidBuild.action,
                androidBuildStage = androidBuild.stage,
                androidBuildIssues = androidBuild.issues,
                androidBuildMessage = androidBuild.message,
                androidBuildLog = androidBuild.log,
                androidBuildStartedAtMillis = androidBuild.startedAtMillis,
                androidBuildFinishedAtMillis = androidBuild.finishedAtMillis,
                androidBuildApkPath = androidBuild.apkPath,
                androidBuildApkSizeBytes = androidBuild.apkSizeBytes,
                androidBuildRecentDurationsMillis = androidBuild.recentDurationsMillis,
            )
        }
        refreshProjectFiles()
        viewModelScope.launch {
            val pending = activeRuntime().loadPendingChanges(project.id)
            if (_state.value.activeProject?.id == project.id) _state.update { it.copy(changes = pending) }
        }
    }

    fun closeProject() {
        val active = _state.value.activeProject
        persistMessages()
        if (_state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) {
            _state.update {
                it.copy(
                    workspaceVisible = false,
                    toastMessage = when {
                        it.isRunning -> str(R.string.vm_task_continues_background)
                        it.androidBuildRunning -> str(R.string.an_build_continues_background)
                        projectTerminalStopRequested -> null
                        else -> str(R.string.vm_terminal_continues_background)
                    },
                )
            }
            return
        }

        if (active != null) {
            val chats = preferences.loadProjectChats(active.id)
            val userMessages = chats.sumOf { preferences.loadMessages(active.id, it.id).count { m -> m.fromUser } }
            val workspaceDir = File(getApplication<Application>().filesDir, "workspaces/${active.id}")
            val userFiles = if (workspaceDir.isDirectory) {
                workspaceDir.walkTopDown().filter { file ->
                    file.isFile && !file.name.startsWith(".claude") && file.name != ".pocket-dev-stacks.json"
                }.count()
            } else 0

            if (userMessages == 0 && userFiles == 0 && !_state.value.isRunning && !_state.value.projectTerminalRunning) {
                // Unused empty project; delete immediately so it does not clutter the project list.
                _state.update { current -> current.copy(projects = current.projects.filterNot { it.id == active.id }) }
                preferences.saveProjects(_state.value.projects)
                viewModelScope.launch(Dispatchers.IO) {
                    workspaceDir.deleteRecursively()
                    terminalHistoryFile(active.id).delete()
                    preferences.deleteProjectChats(active.id)
                }
            }
        }

        _state.update {
            it.copy(
                activeProject = null,
                workspaceVisible = false,
                projectChats = emptyList(),
                activeChatId = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = false,
                isRunning = false,
                activeSessionId = null,
                pendingApproval = null,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = "/workspace",
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = null,
                previewReady = false,
                previewUrl = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun closeReadOnlyProject() {
        _state.update {
            it.copy(
                readOnlyProject = null,
                readOnlyProjectChats = emptyList(),
                readOnlyChatId = null,
                readOnlyMessages = emptyList(),
            )
        }
    }

    fun switchReadOnlyChat(chatId: String) {
        val project = _state.value.readOnlyProject ?: return
        if (_state.value.readOnlyProjectChats.none { it.id == chatId }) return
        _state.update {
            it.copy(
                readOnlyChatId = chatId,
                readOnlyMessages = preferences.loadMessages(project.id, chatId),
            )
        }
    }

    fun activateReadOnlyProject() {
        if (_state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        val project = _state.value.readOnlyProject ?: return
        closeReadOnlyProject()
        openProject(project)
    }

    fun consumeToast() = _state.update { it.copy(toastMessage = null) }

    fun createProject(name: String) {
        val resolvedName = name.trim().ifBlank {
            generateAutoProjectName(_state.value.projects.map { it.name })
        }
        if (_state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) {
            _state.update { it.copy(toastMessage = str(R.string.vm_stop_before_new_project)) }
            return
        }
        val baseSlug = projectSlug(resolvedName)
        val usedSlugs = _state.value.projects.mapTo(mutableSetOf()) { it.slug }
        val slug = generateSequence(1) { it + 1 }
            .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
            .first { it !in usedSlugs }
        val project = Project(
            name = resolvedName,
            description = str(R.string.vm_starter_web_project),
            language = "TypeScript",
            slug = slug,
        )
        configureBridgeRoots(project.id, project.rootPath)
        val guestRoot = projectGuestRoot(project)
        _state.update {
            it.copy(
                projects = listOf(project) + it.projects,
                activeProject = project,
                workspaceVisible = true,
                messages = listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")),
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = true,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = guestRoot,
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = null,
                previewReady = false,
                previewUrl = null,
            )
        }
        preferences.saveProjects(_state.value.projects)
        File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
        val firstChat = ProjectChat(title = str(R.string.vm_new_chat))
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projectChats = listOf(firstChat), activeChatId = firstChat.id) }
        refreshProjectFiles()
    }

    fun createAndroidProject(name: String, template: AndroidTemplate) {
        if (name.isBlank()) return
        if (_state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) {
            _state.update { it.copy(toastMessage = str(R.string.an_stop_before_new_project)) }
            return
        }
        val cleanName = name.replace(Regex("\\s+"), " ").trim().take(60)
        val baseSlug = projectSlug(cleanName)
        val usedSlugs = _state.value.projects.mapTo(mutableSetOf()) { it.slug }
        val slug = generateSequence(1) { it + 1 }
            .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
            .first { it !in usedSlugs }
        val project = Project(
            name = cleanName,
            description = if (template == AndroidTemplate.COMPOSE) "Android · Jetpack Compose" else "Android · Kotlin + XML",
            language = "Kotlin",
            slug = slug,
            type = ProjectType.ANDROID,
            androidTemplate = template,
        )
        val workspace = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
        runCatching { AndroidProjectTemplateGenerator.generate(workspace, cleanName, template) }
            .onFailure { error ->
                _state.update { it.copy(toastMessage = str(R.string.an_could_not_create_android_project)) }
                return
            }
        val firstChat = ProjectChat(title = str(R.string.vm_new_chat))
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projects = listOf(project) + it.projects) }
        preferences.saveProjects(_state.value.projects)
        openProject(project)
        if (!installer.isStackInstalled(DevStack.ANDROID)) {
            _state.update { it.copy(toastMessage = str(R.string.an_android_project_created_install_tools)) }
        }
    }

    fun createQuickProject() {
        if (_state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) {
            _state.update { it.copy(toastMessage = str(R.string.vm_stop_before_new_project)) }
            return
        }
        val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
        val project = Project(
            name = identity.displayName,
            description = "Quick project workspace",
            language = str(R.string.vm_language_general),
            slug = identity.slug,
            kind = ProjectKind.QUICK_PROJECT,
        )
        val firstChat = ProjectChat(title = str(R.string.vm_new_chat))
        File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projects = listOf(project) + it.projects) }
        preferences.saveProjects(_state.value.projects)
        openProject(project)
    }

    fun selectTeamProject(project: Project) {
        // Save outgoing project's terminal output so each project keeps its own isolated state
        _state.value.activeTeamProject?.id?.let { prevId ->
            if (prevId != project.id) {
                teamProjectTerminals[prevId] = _state.value.teamTerminalOutput
            }
        }
        preferences.activeTeamProjectId = project.id
        val projectHistory = loadProjectTeamChat(project.id)
        val projectTerminal = teamProjectTerminals[project.id].orEmpty()
        val projectRunning = _state.value.runningTeamProjectId == project.id && _state.value.teamIsRunning
        val projectAwaitingConfirm = pendingStartConfirmation?.projectId == project.id
        val projectActiveTaskId = if (projectRunning) activeSupervisedTaskId else null
        val hasRemote = detectTeamProjectGitRemote(project.id)
        _state.update {
            it.copy(
                activeTeamProject = project,
                teamGroupMessages = projectHistory.toList(),
                teamTerminalOutput = projectTerminal,
                teamAgentLiveLogs = if (projectRunning) it.teamAgentLiveLogs else emptyMap(),
                teamTypingAgentName = if (projectRunning) it.teamTypingAgentName else null,
                teamTypingAgentSlot = if (projectRunning) it.teamTypingAgentSlot else null,
                teamStreamingAgentName = if (projectRunning) it.teamStreamingAgentName else null,
                teamStreamingAgentSlot = if (projectRunning) it.teamStreamingAgentSlot else null,
                teamStreamingText = if (projectRunning) it.teamStreamingText else "",
                teamAwaitingStartConfirmation = projectAwaitingConfirm,
                teamActiveProjectHasGitRemote = hasRemote,
                activeSupervisedTaskId = projectActiveTaskId,
            )
        }
    }

    fun openTeamProject(project: Project) {
        selectTeamProject(project)
        _state.update { it.copy(teamScreenMode = TeamScreenMode.DASHBOARD) }
    }

    fun createTeamProject(name: String) {
        val resolvedName = name.trim().ifBlank {
            generateAutoProjectName(_state.value.teamProjects.map { it.name })
        }
        val baseSlug = projectSlug(resolvedName)
        val usedSlugs = _state.value.teamProjects.mapTo(mutableSetOf()) { it.slug }
        val slug = generateSequence(1) { it + 1 }
            .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
            .first { it !in usedSlugs }
        val project = Project(
            name = resolvedName,
            description = "AI Squad Isolated Workspace",
            language = "General",
            slug = slug,
        )
        // Save previous project's terminal output before switching to brand-new isolated workspace
        _state.value.activeTeamProject?.id?.let { prevId ->
            teamProjectTerminals[prevId] = _state.value.teamTerminalOutput
        }
        File(getApplication<Application>().filesDir, "team_workspaces/${project.id}").mkdirs()
        teamProjectChats[project.id] = mutableListOf()
        teamProjectTerminals[project.id] = ""
        val updated = listOf(project) + _state.value.teamProjects
        preferences.saveTeamProjects(updated)
        preferences.activeTeamProjectId = project.id
        _state.update {
            it.copy(
                teamProjects = updated,
                activeTeamProject = project,
                teamGroupMessages = emptyList(),
                teamTerminalOutput = "",
                teamAgentLiveLogs = emptyMap(),
                teamTypingAgentName = null,
                teamTypingAgentSlot = null,
                teamStreamingAgentName = null,
                teamStreamingAgentSlot = null,
                teamStreamingText = "",
                teamAwaitingStartConfirmation = false,
                activeSupervisedTaskId = null,
                toastMessage = "Created AI Squad workspace: ${project.name}",
            )
        }
    }

    fun createQuickTeamProject() {
        val identity = generateQuickChatIdentity(_state.value.teamProjects.mapTo(mutableSetOf()) { it.slug })
        val project = Project(
            name = identity.displayName,
            description = "Quick AI Team workspace",
            language = str(R.string.vm_language_general),
            slug = identity.slug,
            kind = ProjectKind.QUICK_PROJECT,
        )
        _state.value.activeTeamProject?.id?.let { prevId ->
            teamProjectTerminals[prevId] = _state.value.teamTerminalOutput
        }
        File(getApplication<Application>().filesDir, "team_workspaces/${project.id}").mkdirs()
        teamProjectChats[project.id] = mutableListOf()
        teamProjectTerminals[project.id] = ""
        val updated = listOf(project) + _state.value.teamProjects
        preferences.saveTeamProjects(updated)
        preferences.activeTeamProjectId = project.id
        _state.update {
            it.copy(
                teamProjects = updated,
                activeTeamProject = project,
                teamGroupMessages = emptyList(),
                teamTerminalOutput = "",
                teamAgentLiveLogs = emptyMap(),
                teamTypingAgentName = null,
                teamTypingAgentSlot = null,
                teamStreamingAgentName = null,
                teamStreamingAgentSlot = null,
                teamStreamingText = "",
                teamAwaitingStartConfirmation = false,
                activeSupervisedTaskId = null,
                toastMessage = "Created Quick AI Team project: ${project.name}",
            )
        }
    }

    fun createAndroidTeamProject(name: String, template: AndroidTemplate) {
        val resolvedName = name.replace(Regex("\\s+"), " ").trim().take(60).ifBlank {
            generateAutoProjectName(_state.value.teamProjects.map { it.name })
        }
        val baseSlug = projectSlug(resolvedName)
        val usedSlugs = _state.value.teamProjects.mapTo(mutableSetOf()) { it.slug }
        val slug = generateSequence(1) { it + 1 }
            .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
            .first { it !in usedSlugs }
        val project = Project(
            name = resolvedName,
            description = if (template == AndroidTemplate.COMPOSE) "Android · Jetpack Compose (AI Team)" else "Android · Kotlin + XML (AI Team)",
            language = "Kotlin",
            slug = slug,
            type = ProjectType.ANDROID,
            androidTemplate = template,
        )
        val workspace = File(getApplication<Application>().filesDir, "team_workspaces/${project.id}")
        runCatching { AndroidProjectTemplateGenerator.generate(workspace, resolvedName, template) }
            .onFailure {
                _state.update { it.copy(toastMessage = str(R.string.an_could_not_create_android_project)) }
                return
            }
        _state.value.activeTeamProject?.id?.let { prevId ->
            teamProjectTerminals[prevId] = _state.value.teamTerminalOutput
        }
        teamProjectChats[project.id] = mutableListOf()
        teamProjectTerminals[project.id] = ""
        val updated = listOf(project) + _state.value.teamProjects
        preferences.saveTeamProjects(updated)
        preferences.activeTeamProjectId = project.id
        _state.update {
            it.copy(
                teamProjects = updated,
                activeTeamProject = project,
                teamGroupMessages = emptyList(),
                teamTerminalOutput = "",
                teamAgentLiveLogs = emptyMap(),
                teamTypingAgentName = null,
                teamTypingAgentSlot = null,
                teamStreamingAgentName = null,
                teamStreamingAgentSlot = null,
                teamStreamingText = "",
                teamAwaitingStartConfirmation = false,
                activeSupervisedTaskId = null,
                toastMessage = if (!installer.isStackInstalled(DevStack.ANDROID)) {
                    str(R.string.an_android_project_created_install_tools)
                } else {
                    "Created Android AI Team project: ${project.name}"
                },
            )
        }
    }

    fun renameTeamProject(projectId: String, newName: String) {
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return
        val updated = _state.value.teamProjects.map { proj ->
            if (proj.id == projectId) proj.copy(name = cleanName) else proj
        }
        preferences.saveTeamProjects(updated)
        _state.update { state ->
            state.copy(
                teamProjects = updated,
                activeTeamProject = if (state.activeTeamProject?.id == projectId) {
                    state.activeTeamProject.copy(name = cleanName)
                } else state.activeTeamProject,
            )
        }
    }

    fun cloneSoloProjectToTeam(soloProject: Project) {
        if (_state.value.projectImporting || _state.value.gitCloneRunning) return
        _state.update { it.copy(projectImporting = true, projectImportMessage = "Cloning ${soloProject.name} to AI Squad…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val app = getApplication<Application>()
                    val sourceDir = File(app.filesDir, "workspaces/${soloProject.id}")
                    val baseSlug = projectSlug("${soloProject.slug}-squad")
                    val usedSlugs = _state.value.teamProjects.mapTo(mutableSetOf()) { it.slug }
                    val slug = generateSequence(1) { it + 1 }
                        .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
                        .first { it !in usedSlugs }
                    val cloned = Project(
                        name = "${soloProject.name} (Squad)",
                        description = "Cloned from Solo · ${soloProject.description}",
                        language = soloProject.language,
                        slug = slug,
                        rootPath = soloProject.rootPath,
                        kind = soloProject.kind,
                        type = soloProject.type,
                        androidTemplate = soloProject.androidTemplate,
                    )
                    val targetDir = File(app.filesDir, "team_workspaces/${cloned.id}").apply { mkdirs() }
                    if (sourceDir.isDirectory) {
                        sourceDir.copyRecursively(targetDir, overwrite = true)
                    }
                    cloned
                }
            }
            result.onSuccess { cloned ->
                val updated = listOf(cloned) + _state.value.teamProjects
                preferences.saveTeamProjects(updated)
                preferences.activeTeamProjectId = cloned.id
                _state.update {
                    it.copy(
                        teamProjects = updated,
                        activeTeamProject = cloned,
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "Cloned '${soloProject.name}' to AI Squad workspace",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = error.message?.take(180) ?: "Failed to clone Solo project",
                    )
                }
            }
        }
    }

    fun deleteTeamProject(project: Project) {
        viewModelScope.launch(Dispatchers.IO) {
            File(getApplication<Application>().filesDir, "team_workspaces/${project.id}").deleteRecursively()
        }
        val updated = _state.value.teamProjects.filterNot { it.id == project.id }
        val nextActive = if (_state.value.activeTeamProject?.id == project.id) updated.firstOrNull() else _state.value.activeTeamProject
        preferences.saveTeamProjects(updated)
        preferences.activeTeamProjectId = nextActive?.id
        _state.update { it.copy(teamProjects = updated, activeTeamProject = nextActive) }
    }

    fun importZipToTeamProject(uri: Uri) {
        if (_state.value.projectImporting || _state.value.gitCloneRunning) return
        _state.update { it.copy(projectImporting = true, projectImportMessage = str(R.string.vm_reading_archive)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    extractImportedProject(
                        uri = uri,
                        workspaceRootDir = "team_workspaces",
                        usedSlugs = _state.value.teamProjects.mapTo(mutableSetOf()) { it.slug },
                    )
                }
            }
            result.onSuccess { imported ->
                val project = imported.project
                val updated = listOf(project) + _state.value.teamProjects
                preferences.saveTeamProjects(updated)
                preferences.activeTeamProjectId = project.id
                _state.update {
                    it.copy(
                        teamProjects = updated,
                        activeTeamProject = project,
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = str(R.string.vm_project_imported, project.name),
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = str(R.string.vm_import_failed, error.message?.take(180) ?: str(R.string.vm_invalid_zip)),
                    )
                }
            }
        }
    }

    fun clonePublicGitToTeam(url: String) {
        cloneGitRepository(url = url, repositoryName = null, branch = null, useGitHubCli = false, forTeam = true)
    }

    fun cloneGitHubToTeam(repository: GitHubRepository) {
        if (_state.value.githubAuthStatus != GitHubAuthStatus.CONNECTED) {
            _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.DISCONNECTED, githubMessage = str(R.string.vm_github_connect_again)) }
            return
        }
        cloneGitRepository(repository.cloneUrl, repository.fullName, repository.defaultBranch, useGitHubCli = true, forTeam = true)
    }

    fun importZipProject(uri: Uri) {
        if (_state.value.projectImporting || _state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        _state.update { it.copy(projectImporting = true, projectImportMessage = str(R.string.vm_reading_archive)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { extractImportedProject(uri) } }
            result.onSuccess { imported ->
                val project = imported.project
                val firstChat = ProjectChat(title = str(R.string.vm_new_chat))
                preferences.saveProjectChats(project.id, listOf(firstChat))
                _state.update { current ->
                    current.copy(
                        projects = listOf(project) + current.projects,
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = str(R.string.vm_project_imported, project.name),
                    )
                }
                preferences.saveProjects(_state.value.projects)
                openProject(project)
                _state.update { it.copy(pendingAttachments = listOf(imported.sourceAttachment)) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = str(R.string.vm_import_failed, error.message?.take(180) ?: str(R.string.vm_invalid_zip)),
                    )
                }
            }
        }
    }

    private fun extractImportedProject(
        uri: Uri,
        workspaceRootDir: String = "workspaces",
        usedSlugs: MutableSet<String> = _state.value.projects.mapTo(mutableSetOf()) { it.slug },
    ): ImportedZipProject {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        var archiveName = "Imported project.zip"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                    archiveName = cursor.getString(index) ?: archiveName
                }
            }
        }
        val identity = generateQuickChatIdentity(usedSlugs)
        val projectId = UUID.randomUUID().toString()
        val destination = File(app.filesDir, "$workspaceRootDir/$projectId")
        destination.mkdirs()
        val destinationPath = destination.canonicalFile.toPath()
        val availableLimit = (destination.usableSpace * 8L / 10L).coerceAtMost(MAX_IMPORTED_PROJECT_BYTES)
        var extractedBytes = 0L
        var entries = 0
        try {
            val source = resolver.openInputStream(uri) ?: error(str(R.string.vm_zip_open_failed))
            source.buffered().use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= MAX_IMPORTED_ZIP_ENTRIES) { str(R.string.vm_zip_too_many_files) }
                        val entryName = entry.name.replace('\\', '/').trimStart('/')
                        require(entryName.isNotBlank() && '\u0000' !in entryName) { str(R.string.vm_zip_invalid_path) }
                        if (entryName.startsWith("__MACOSX/") || entryName.endsWith("/.DS_Store") || entryName == ".DS_Store") {
                            zip.closeEntry()
                            continue
                        }
                        val target = File(destination, entryName).canonicalFile
                        require(target.toPath().startsWith(destinationPath)) { str(R.string.vm_zip_unsafe_path) }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    extractedBytes += count
                                    require(extractedBytes <= availableLimit) { str(R.string.vm_zip_too_large) }
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (entry.time > 0) target.setLastModified(entry.time)
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(entries > 0 && destination.walkTopDown().any { it.isFile }) { str(R.string.vm_zip_no_files) }
            val preliminary = Project(
                id = projectId,
                name = identity.displayName,
                description = "Imported project workspace",
                language = str(R.string.vm_language_general),
                slug = identity.slug,
                kind = ProjectKind.QUICK_PROJECT,
            )
            val nestedRoot = detectNestedProjectRoot(preliminary)
            val projectRoot = nestedRoot?.let { File(destination, it) } ?: destination
            val metadata = detectImportedProjectMetadata(projectRoot)
            val safeArchiveName = sanitizeAttachmentName(archiveName).let { name ->
                if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
            }
            val archiveFolder = File(projectRoot, ".pocketdev/imports").apply { mkdirs() }
            val archivedSource = File(archiveFolder, safeArchiveName)
            var sourceBytes = 0L
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                archivedSource.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sourceBytes += count
                        require(extractedBytes + sourceBytes <= availableLimit) { str(R.string.vm_import_too_large) }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error(str(R.string.vm_zip_preserve_failed))
            val project = preliminary.copy(
                description = metadata.first,
                language = metadata.second,
                rootPath = nestedRoot.orEmpty(),
            )
            return ImportedZipProject(
                project = project,
                sourceAttachment = ChatAttachment(
                    displayName = archiveName.take(120),
                    relativePath = archivedSource.relativeTo(projectRoot).invariantSeparatorsPath,
                    mimeType = "application/zip",
                    sizeBytes = sourceBytes,
                ),
            )
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw error
        }
    }

    private fun detectImportedProjectMetadata(root: File): Pair<String, String> {
        val names = root.walkTopDown().maxDepth(3).filter(File::isFile).map { it.name.lowercase() }.toSet()
        return when {
            names.any { it == "settings.gradle.kts" || it == "build.gradle.kts" } -> "Imported Gradle project" to "Kotlin"
            names.any { it == "settings.gradle" || it == "build.gradle" } -> "Imported Gradle project" to "Java"
            "package.json" in names && names.any { it == "tsconfig.json" || it.endsWith(".ts") || it.endsWith(".tsx") } -> "Imported web project" to "TypeScript"
            "package.json" in names -> "Imported web project" to "JavaScript"
            names.any { it == "pyproject.toml" || it == "requirements.txt" || it.endsWith(".py") } -> "Imported Python project" to "Python"
            names.any { it == "cargo.toml" || it.endsWith(".rs") } -> "Imported Rust project" to "Rust"
            names.any { it == "go.mod" || it.endsWith(".go") } -> "Imported Go project" to "Go"
            else -> "Imported ZIP project" to str(R.string.vm_language_general)
        }
    }

    fun clonePublicGitRepository(url: String) {
        cloneGitRepository(url = url, repositoryName = null, branch = null, useGitHubCli = false)
    }

    fun cloneGitHubRepository(repository: GitHubRepository) {
        if (_state.value.githubAuthStatus != GitHubAuthStatus.CONNECTED) {
            _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.DISCONNECTED, githubMessage = str(R.string.vm_github_connect_again)) }
            return
        }
        cloneGitRepository(repository.cloneUrl, repository.fullName, repository.defaultBranch, useGitHubCli = true)
    }

    private fun cloneGitRepository(
        url: String,
        repositoryName: String?,
        branch: String?,
        useGitHubCli: Boolean,
        forTeam: Boolean = false,
    ) {
        if (_state.value.gitCloneRunning || _state.value.projectImporting || _state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        val normalized = runCatching { validateGitUrl(url) }.getOrElse { error ->
            _state.update { it.copy(toastMessage = error.message ?: str(R.string.vm_invalid_git_url)) }
            return
        }
        _state.update { it.copy(gitCloneRunning = true, gitCloneMessage = str(R.string.vm_connecting_git)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val existingSlugs = (if (forTeam) _state.value.teamProjects else _state.value.projects).mapTo(mutableSetOf()) { it.slug }
                    val identity = generateQuickChatIdentity(existingSlugs)
                    val projectId = UUID.randomUUID().toString()
                    val rootFolder = if (forTeam) "team_workspaces" else "workspaces"
                    val workspace = File(getApplication<Application>().filesDir, "$rootFolder/$projectId").apply { mkdirs() }
                    val output = File(getApplication<Application>().cacheDir, "git-clone-${System.nanoTime()}.log")
                    try {
                        val environment = mutableMapOf(
                            "GIT_TERMINAL_PROMPT" to "0",
                            "GIT_LFS_SKIP_SMUDGE" to "1",
                            "GH_PROMPT_DISABLED" to "1",
                            "GH_NO_UPDATE_NOTIFIER" to "1",
                        )
                        val installed = installer.installedRuntime()
                        val command = if (useGitHubCli && repositoryName != null) {
                            buildList {
                                addAll(listOf(RuntimeInstaller.GITHUB_CLI_GUEST_PATH, "repo", "clone", repositoryName, ".", "--", "--progress", "--single-branch"))
                                branch?.takeIf(String::isNotBlank)?.let { addAll(listOf("--branch", it)) }
                            }
                        } else {
                            buildList {
                                addAll(listOf("git", "clone", "--progress", "--single-branch"))
                                branch?.takeIf(String::isNotBlank)?.let { addAll(listOf("--branch", it)) }
                                add(normalized)
                                add(".")
                            }
                        }
                        _state.update { it.copy(gitCloneMessage = str(R.string.vm_cloning_named, repositoryName ?: normalized.substringAfterLast('/').removeSuffix(".git"))) }
                        val process = installer.process(
                            installed.proot,
                            installed.rootfs,
                            workspace,
                            environment,
                            command,
                            guestWorkspacePath = "/workspace/${identity.slug}",
                            outputFile = output,
                        )
                        val exit = process.waitFor()
                        val details = output.readTailText(MAX_PROCESS_OUTPUT_BYTES).trim()
                        check(exit == 0) { details.takeLast(600).ifBlank { str(R.string.vm_git_clone_failed_exit, exit) } }
                        val metadata = detectImportedProjectMetadata(workspace)
                        // Name Git-cloned projects after their real repository name (e.g. GitHub repo name),
                        // while keeping the storage directory keyed by a unique UUID (projectId) so duplicate
                        // display names are safely allowed without workspace path collisions!
                        val repoDisplayName = repositoryName?.substringAfterLast('/')?.trim()?.takeIf { it.isNotBlank() }
                            ?: normalized.trimEnd('/').substringAfterLast('/').removeSuffix(".git").trim().takeIf { it.isNotBlank() }
                            ?: identity.displayName
                        Project(
                            id = projectId,
                            name = repoDisplayName,
                            description = repositoryName?.let { "GitHub · $it" } ?: "Imported Git repository",
                            language = metadata.second,
                            slug = identity.slug,
                            kind = ProjectKind.QUICK_PROJECT,
                        )
                    } catch (error: Throwable) {
                        workspace.deleteRecursively()
                        throw error
                    } finally {
                        output.delete()
                    }
                }
            }
            result.onSuccess { project ->
                if (forTeam) {
                    val updated = listOf(project) + _state.value.teamProjects
                    preferences.saveTeamProjects(updated)
                    preferences.activeTeamProjectId = project.id
                    _state.update {
                        it.copy(
                            teamProjects = updated,
                            activeTeamProject = project,
                            gitCloneRunning = false,
                            gitCloneMessage = null,
                            toastMessage = str(R.string.vm_repository_cloned),
                        )
                    }
                } else {
                    val chat = ProjectChat(title = str(R.string.vm_new_chat))
                    preferences.saveProjectChats(project.id, listOf(chat))
                    _state.update { current -> current.copy(projects = listOf(project) + current.projects) }
                    preferences.saveProjects(_state.value.projects)
                    openProject(project)
                    _state.update { it.copy(gitCloneRunning = false, gitCloneMessage = null, toastMessage = str(R.string.vm_repository_cloned)) }
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        gitCloneRunning = false,
                        gitCloneMessage = null,
                        toastMessage = str(R.string.vm_clone_failed, error.message?.lineSequence()?.lastOrNull()?.take(180) ?: str(R.string.vm_unknown_error)),
                    )
                }
            }
        }
    }

    private fun validateGitUrl(value: String): String {
        val clean = value.trim()
        val uri = URI(clean)
        require(uri.scheme.equals("https", ignoreCase = true)) { str(R.string.vm_git_https_only) }
        require(uri.userInfo == null && uri.fragment == null && uri.host?.isNotBlank() == true) { str(R.string.vm_git_no_credentials) }
        require(uri.host != "localhost" && uri.host != "127.0.0.1" && uri.host != "::1") { str(R.string.vm_git_local_unsupported) }
        require(uri.path.count { it == '/' } >= 2) { str(R.string.vm_git_url_not_repo) }
        return uri.toASCIIString()
    }

    fun startGitHubLogin() {
        if (_state.value.githubAuthStatus == GitHubAuthStatus.STARTING || _state.value.githubAuthStatus == GitHubAuthStatus.AWAITING_USER) return
        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = str(R.string.vm_github_preparing_signin)) }
        startGitHubForegroundOperation()
        githubAuthJob = viewModelScope.launch {
            try {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    installer.ensureGitHubCliInstalled { progress ->
                        _state.update { it.copy(githubMessage = progress.message) }
                    }
                    val runtime = installer.installedRuntime()
                    val outputFile = File(getApplication<Application>().cacheDir, "github-auth-${System.nanoTime()}.log")
                    val workspace = File(getApplication<Application>().filesDir, "workspaces/github-auth").apply { mkdirs() }
                    val process = installer.process(
                        runtime.proot,
                        runtime.rootfs,
                        workspace,
                        githubCliEnvironment(),
                        listOf(
                            RuntimeInstaller.GITHUB_CLI_GUEST_PATH,
                            "auth", "login",
                            "--hostname", "github.com",
                            "--git-protocol", "https",
                            "--web",
                            "--insecure-storage",
                        ),
                        guestWorkspacePath = "/workspace/github-auth",
                        outputFile = outputFile,
                    )
                    githubAuthProcess = process
                    var offset = 0L
                    val captured = StringBuilder()
                    var browserOpened = false
                    try {
                        while (process.isAlive || outputFile.length() > offset) {
                            if (outputFile.length() > offset) {
                                val count = (outputFile.length() - offset).coerceAtMost(16L * 1024).toInt()
                                val bytes = ByteArray(count)
                                RandomAccessFile(outputFile, "r").use { file -> file.seek(offset); file.readFully(bytes) }
                                offset += count
                                captured.append(bytes.toString(Charsets.UTF_8))
                                val clean = sanitizeTerminalOutput(captured.toString()).takeLast(20_000)
                                val code = GITHUB_DEVICE_CODE.find(clean)?.value
                                if (code != null && !browserOpened) {
                                    browserOpened = true
                                    _state.update {
                                        it.copy(
                                            githubAuthStatus = GitHubAuthStatus.AWAITING_USER,
                                            githubUserCode = code,
                                            githubVerificationUri = GITHUB_DEVICE_URL,
                                            githubMessage = str(R.string.vm_github_enter_code),
                                            toastMessage = str(R.string.vm_github_code_copied, code),
                                        )
                                    }
                                    // The browser covers the dialog, so put the code on the clipboard
                                    // and give the user a moment to see it before GitHub asks for it.
                                    copyToClipboard("GitHub code", code)
                                    delay(1_500)
                                    openExternalUrl(GITHUB_DEVICE_URL)
                                }
                            } else {
                                delay(100)
                            }
                        }
                        val exit = process.waitFor()
                        check(exit == 0) {
                            sanitizeTerminalOutput(captured.toString()).lineSequence().lastOrNull { it.isNotBlank() }
                                ?: str(R.string.vm_github_signin_failed_exit, exit)
                        }
                    } finally {
                        githubAuthProcess = null
                        outputFile.delete()
                    }
                    githubAccountLogin() ?: error(str(R.string.vm_github_account_unknown))
                }
            }
            result.onSuccess { login ->
                preferences.githubLogin = login
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.CONNECTED,
                        githubLogin = login,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubMessage = str(R.string.vm_github_connected_as, login),
                    )
                }
                refreshGitHubRepositories()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.ERROR,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubMessage = error.message?.take(240) ?: str(R.string.vm_github_signin_failed),
                    )
                }
            }
            } finally {
                stopGitHubForegroundOperation()
                githubAuthJob = null
            }
        }
    }

    fun generateNewGitHubCode() {
        githubAuthProcess?.destroy()
        githubAuthJob?.cancel()
        githubAuthProcess = null
        githubAuthJob = null
        stopGitHubForegroundOperation()
        _state.update {
            it.copy(
                githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
                githubUserCode = null,
                githubVerificationUri = null,
                githubMessage = str(R.string.vm_github_new_code),
            )
        }
        startGitHubLogin()
    }

    fun refreshGitHubRepositories() {
        if (_state.value.githubAuthStatus != GitHubAuthStatus.CONNECTED) return
        if (_state.value.githubRepositoriesLoading) return
        _state.update { it.copy(githubRepositoriesLoading = true, githubMessage = str(R.string.vm_loading_repositories)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { githubRepositoriesFromCli() } }
            result.onSuccess { repositories ->
                _state.update {
                    it.copy(
                        githubRepositories = repositories,
                        githubRepositoriesLoading = false,
                        githubMessage = plural(R.plurals.vm_repositories_available, repositories.size, repositories.size),
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        githubRepositoriesLoading = false,
                        githubMessage = error.message ?: str(R.string.vm_github_repos_failed),
                    )
                }
            }
        }
    }

    fun disconnectGitHub() {
        if (_state.value.githubAuthStatus == GitHubAuthStatus.STARTING) return
        val login = _state.value.githubLogin
        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = str(R.string.vm_github_signing_out)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val command = buildList {
                        addAll(listOf("auth", "logout", "--hostname", "github.com"))
                        login?.takeIf(String::isNotBlank)?.let { addAll(listOf("--user", it)) }
                    }
                    val output = runGitHubCli(command)
                    check(output.first == 0) { output.second.lineSequence().lastOrNull { it.isNotBlank() } ?: str(R.string.vm_github_logout_failed) }
                }
            }
            result.onSuccess {
                preferences.githubLogin = ""
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
                        githubLogin = null,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubRepositories = emptyList(),
                        githubMessage = str(R.string.vm_signed_out),
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.ERROR, githubMessage = error.message ?: str(R.string.vm_sign_out_failed)) }
            }
        }
    }

    private suspend fun refreshGitHubConnection() = withContext(Dispatchers.IO) {
        if (!installer.isGitHubCliInstalled()) return@withContext
        val login = runCatching { githubAccountLogin() }.getOrNull()
        if (login.isNullOrBlank()) {
            preferences.githubLogin = ""
            _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.DISCONNECTED, githubLogin = null) }
        } else {
            preferences.githubLogin = login
            _state.update {
                it.copy(
                    githubAuthStatus = GitHubAuthStatus.CONNECTED,
                    githubLogin = login,
                    githubMessage = str(R.string.vm_github_connected_as, login),
                )
            }
        }
    }

    private fun githubCliEnvironment(): Map<String, String> = mapOf(
        "GH_PROMPT_DISABLED" to "1",
        "GH_NO_UPDATE_NOTIFIER" to "1",
        // Android PRoot has no Secret Service. This keeps the official gh-owned
        // credential in PocketDev's private Linux home instead of exporting it.
        "BROWSER" to "/bin/false",
    )

    private fun runGitHubCli(arguments: List<String>): Pair<Int, String> {
        check(installer.isGitHubCliInstalled()) { str(R.string.vm_github_cli_missing) }
        val runtime = installer.installedRuntime()
        val outputFile = File(getApplication<Application>().cacheDir, "github-cli-${System.nanoTime()}.log")
        val workspace = File(getApplication<Application>().filesDir, "workspaces/github-auth").apply { mkdirs() }
        return try {
            val process = installer.process(
                runtime.proot,
                runtime.rootfs,
                workspace,
                githubCliEnvironment(),
                listOf(RuntimeInstaller.GITHUB_CLI_GUEST_PATH) + arguments,
                guestWorkspacePath = "/workspace/github-auth",
                outputFile = outputFile,
            )
            val exit = process.waitFor()
            exit to sanitizeTerminalOutput(outputFile.readTailText(MAX_PROCESS_OUTPUT_BYTES)).trim()
        } finally {
            outputFile.delete()
        }
    }

    private fun githubAccountLogin(): String? {
        val (exit, output) = runGitHubCli(listOf("api", "user", "--jq", ".login"))
        return output.lineSequence().lastOrNull { it.isNotBlank() }?.trim().takeIf { exit == 0 && !it.isNullOrBlank() }
    }

    private fun githubRepositoriesFromCli(): List<GitHubRepository> {
        val endpoint = "user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100"
        val (exit, output) = runGitHubCli(listOf("api", "--paginate", "--slurp", endpoint))
        check(exit == 0) { output.lineSequence().lastOrNull { it.isNotBlank() } ?: str(R.string.vm_github_repos_failed) }
        val pages = JSONArray(output)
        val repositories = LinkedHashMap<String, GitHubRepository>()
        for (pageIndex in 0 until pages.length()) {
            val page = pages.optJSONArray(pageIndex) ?: continue
            for (index in 0 until page.length()) {
                val item = page.optJSONObject(index) ?: continue
                val fullName = item.optString("full_name").takeIf(String::isNotBlank) ?: continue
                repositories[fullName] = GitHubRepository(
                    fullName = fullName,
                    cloneUrl = item.optString("clone_url", "https://github.com/$fullName.git"),
                    private = item.optBoolean("private"),
                    defaultBranch = item.optString("default_branch", "main"),
                    description = item.optString("description"),
                    updatedAt = item.optString("updated_at"),
                )
            }
        }
        return repositories.values.toList()
    }

    private fun copyToClipboard(label: String, text: String) {
        runCatching {
            val clipboard = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        }
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            getApplication<Application>().startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            _state.update { state -> state.copy(toastMessage = str(R.string.vm_browser_open_failed_url)) }
        }
    }

    private fun startGitHubForegroundOperation() {
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), com.jarves.mh.runtime.RuntimeExecutionService::class.java)
                .setAction(com.jarves.mh.runtime.RuntimeExecutionService.ACTION_START)
                .putExtra(com.jarves.mh.runtime.RuntimeExecutionService.EXTRA_PROJECT_NAME, str(R.string.vm_notif_github_signin))
                .putExtra(com.jarves.mh.runtime.RuntimeExecutionService.EXTRA_TITLE, str(R.string.vm_notif_connecting_github))
                .putExtra(com.jarves.mh.runtime.RuntimeExecutionService.EXTRA_CAN_STOP, false),
        )
    }

    private fun stopGitHubForegroundOperation() {
        runCatching {
            getApplication<Application>().startService(
                Intent(getApplication(), com.jarves.mh.runtime.RuntimeExecutionService::class.java)
                    .setAction(com.jarves.mh.runtime.RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure {
            getApplication<Application>().stopService(
                Intent(getApplication(), com.jarves.mh.runtime.RuntimeExecutionService::class.java),
            )
        }
    }

    fun renameProject(projectId: String, newName: String) {
        val clean = newName.replace(Regex("\\s+"), " ").trim().take(60)
        if (clean.isBlank()) return
        _state.update { current ->
            val projects = current.projects.map { project ->
                if (project.id == projectId) project.copy(name = clean) else project
            }
            val active = current.activeProject?.let { project ->
                if (project.id == projectId) project.copy(name = clean) else project
            }
            current.copy(projects = projects, activeProject = active)
        }
        preferences.saveProjects(_state.value.projects)
    }

    fun deleteProject(projectId: String) {
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        if (_state.value.activeProject?.id == projectId || _state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        _state.update { current -> current.copy(projects = current.projects.filterNot { it.id == projectId }) }
        preferences.saveProjects(_state.value.projects)
        folderMetadataCache.keys.removeAll { it.startsWith("$projectId:") }
        viewModelScope.launch(Dispatchers.IO) {
            val filesDir = getApplication<Application>().filesDir
            File(filesDir, "workspaces/${project.id}").deleteRecursively()
            terminalHistoryFile(project.id).delete()
            androidBuildRecordFile(project.id).delete()
            preferences.deleteProjectChats(project.id)
        }
    }

    private fun detectNestedProjectRoot(project: Project): String? {
        val base = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
        if (!base.isDirectory) return null
        val visible = base.listFiles().orEmpty().filterNot { file ->
            file.name == ".claude" || file.name == ".claude.json"
        }
        val onlyDirectory = visible.singleOrNull()?.takeIf(File::isDirectory) ?: return null
        val containsProjectFiles = onlyDirectory.walkTopDown()
            .maxDepth(2)
            .any { it.isFile && it.name !in setOf(".DS_Store", ".claude.json") }
        return onlyDirectory.name.takeIf { containsProjectFiles && !it.contains("..") }
    }

    fun useSuggestedProjectRoot() {
        val current = _state.value
        val project = current.activeProject ?: return
        val root = current.suggestedProjectRoot ?: return
        if (current.isRunning || current.projectTerminalRunning) return
        val updated = project.copy(rootPath = root)
        configureBridgeRoots(updated.id, updated.rootPath)
        val projects = current.projects.map { if (it.id == updated.id) updated else it }
        val guestRoot = projectGuestRoot(updated)
        preferences.saveProjects(projects)
        saveProjectTerminal(updated.id, guestRoot, current.projectTerminalLines)
        _state.update {
            it.copy(
                projects = projects,
                activeProject = updated,
                suggestedProjectRoot = null,
                projectTerminalCwd = guestRoot,
                changes = emptyList(),
                toastMessage = str(R.string.vm_project_root_set, root),
            )
        }
        refreshProjectFiles()
    }

    fun exportActiveProject(uri: Uri, selection: ExportSelection) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.projectTerminalRunning || current.androidBuildRunning ||
            current.projectExportRunning || current.openedFile?.saving == true
        ) {
            _state.update { it.copy(toastMessage = str(R.string.export_busy)) }
            return
        }
        _state.update { it.copy(projectExportRunning = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = projectWorkspaceRoot(project)
                    val rootPath = root.canonicalFile.toPath()
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error(str(R.string.vm_export_location_failed))
                    output.buffered().use { stream ->
                        ZipOutputStream(stream).use { zip ->
                            zip.putNextEntry(ZipEntry("${project.slug}/"))
                            zip.closeEntry()
                            root.walkTopDown()
                                .onEnter { directory ->
                                    if (directory == root) {
                                        true
                                    } else {
                                        val relative = directory.relativeTo(root).invariantSeparatorsPath
                                        selection.canContainIncluded(relative) &&
                                            !isClaudeRuntimeMetadata(relative) &&
                                            !Files.isSymbolicLink(directory.toPath()) &&
                                            runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                                    }
                                }
                                .drop(1)
                                .filter { file ->
                                    !Files.isSymbolicLink(file.toPath()) &&
                                        runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false) &&
                                        !isClaudeRuntimeMetadata(file.relativeTo(root).invariantSeparatorsPath) &&
                                        if (file.isDirectory) {
                                            selection.canContainIncluded(file.relativeTo(root).invariantSeparatorsPath)
                                        } else {
                                            selection.includes(file.relativeTo(root).invariantSeparatorsPath)
                                        }
                                }
                                .forEach { file ->
                                    val relative = file.relativeTo(root).invariantSeparatorsPath
                                    val entryName = "${project.slug}/$relative" + if (file.isDirectory) "/" else ""
                                    zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                                    if (file.isFile) file.inputStream().buffered().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                        }
                    }
                }
            }
            _state.update {
                it.copy(
                    projectExportRunning = false,
                    projectExportSucceededAtMillis = result.getOrNull()?.let { System.currentTimeMillis() },
                    toastMessage = result.fold(
                        onSuccess = { str(R.string.export_success, "${project.slug}.zip") },
                        onFailure = { error -> str(R.string.export_failed, error.message ?: str(R.string.unknown_error)) },
                    ),
                )
            }
        }
    }

    fun createChat() {
        val project = _state.value.activeProject ?: return
        if (_state.value.isRunning) return
        persistMessages()
        val chat = ProjectChat(title = str(R.string.vm_new_chat))
        val chats = listOf(chat) + _state.value.projectChats
        preferences.saveProjectChats(project.id, chats)
        _state.update {
            it.copy(
                projectChats = chats,
                activeChatId = chat.id,
                messages = listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")),
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                pendingApproval = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun switchChat(chatId: String) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.activeChatId == chatId) return
        val chat = current.projectChats.firstOrNull { it.id == chatId } ?: return
        persistMessages()
        val saved = preferences.loadMessages(project.id, chat.id)
        _state.update {
            it.copy(
                activeChatId = chat.id,
                messages = saved.ifEmpty { listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")) },
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                pendingApproval = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun refreshProjectFiles() {
        val project = _state.value.activeProject ?: return
        _state.update { it.copy(filesLoading = true) }
        viewModelScope.launch {
            val (entries, suggestedRoot, androidProjectDetected) = withContext(Dispatchers.IO) {
                Triple(
                    readWorkspaceDirectory(project, ""),
                    if (project.rootPath.isBlank()) detectNestedProjectRoot(project) else null,
                    findAndroidProjectRoot(projectWorkspaceRoot(project)) != null,
                )
            }
            if (_state.value.activeProject?.id == project.id) {
                _state.update {
                    it.copy(
                        workspaceFiles = entries,
                        filesLoading = false,
                        suggestedProjectRoot = suggestedRoot,
                        androidProjectDetected = androidProjectDetected,
                    )
                }
                loadMissingFolderCounts(project, entries)
            }
        }
    }

    fun loadProjectDirectory(relativePath: String) {
        val project = _state.value.activeProject ?: return
        val existingEntries = _state.value.workspaceFiles
        viewModelScope.launch {
            val (children, updatedEntries) = withContext(Dispatchers.IO) {
                val loadedChildren = readWorkspaceDirectory(project, relativePath)
                val prefix = relativePath.trim('/').let { if (it.isBlank()) "" else "$it/" }
                val retained = existingEntries.filterNot { entry ->
                    entry.path.startsWith(prefix) && entry.path.removePrefix(prefix).let { !it.contains('/') }
                }
                val sorted = sortWorkspaceEntries((retained + loadedChildren).distinctBy { it.path }).map { entry ->
                    if (entry.path == relativePath) entry.copy(childCount = loadedChildren.size) else entry
                }
                loadedChildren to sorted
            }
            if (_state.value.activeProject?.id != project.id) return@launch
            _state.update { current -> current.copy(workspaceFiles = updatedEntries) }
            updateFolderMetadata(project, relativePath, children.size)
            loadMissingFolderCounts(project, children)
        }
    }

    fun openFile(entry: WorkspaceEntry) {
        if (entry.isDirectory) return
        val project = _state.value.activeProject ?: return
        _state.update { it.copy(openedFile = OpenedFileState(path = entry.path)) }
        loadOpenedFile(project, entry.path)
    }

    private fun loadOpenedFile(project: Project, path: String, targetLine: Int? = null, targetColumn: Int? = null) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val file = safeWorkspaceFile(project, path)
                    ?: return@withContext OpenedFileState(path = path, loading = false, readOnlyReason = FileReadOnlyReason.UNSAFE)
                runCatching {
                    val tooLarge = file.length() > MAX_EDITABLE_FILE_BYTES
                    val bytes = if (tooLarge) file.readHeadBytes(MAX_EDITABLE_FILE_BYTES.toInt()) else file.readBytes()
                    if (bytes.any { it == 0.toByte() }) {
                        OpenedFileState(path = path, loading = false, readOnlyReason = FileReadOnlyReason.BINARY)
                    } else {
                        val decoder = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                        val decoded = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
                        if (decoded == null) {
                            OpenedFileState(path = path, loading = false, readOnlyReason = FileReadOnlyReason.INVALID_UTF8)
                        } else {
                            val content = if (tooLarge) decoded + "\n\n…" else decoded
                            OpenedFileState(
                                path = path,
                                content = content,
                                draft = content,
                                loading = false,
                                readOnlyReason = FileReadOnlyReason.TOO_LARGE.takeIf { tooLarge },
                                originalFingerprint = if (tooLarge) null else fingerprint(file),
                                lineEnding = if (decoded.contains("\r\n")) "\r\n" else "\n",
                                hadTrailingNewline = decoded.endsWith("\n") || decoded.endsWith("\r"),
                            )
                        }
                    }
                }.getOrElse {
                    OpenedFileState(path = path, loading = false, readOnlyReason = FileReadOnlyReason.READ_ERROR)
                }
            }
            if (_state.value.openedFile?.path == path) {
                _state.update { it.copy(openedFile = loaded.copy(targetLine = targetLine, targetColumn = targetColumn)) }
            }
        }
    }

    fun closeFile() {
        _state.update { it.copy(openedFile = null) }
    }

    fun beginFileEdit() {
        val current = _state.value
        val opened = current.openedFile ?: return
        if (opened.loading || opened.readOnlyReason != null || opened.content == null ||
            current.isRunning || current.projectTerminalRunning || current.androidBuildRunning
        ) return
        _state.update { it.copy(openedFile = opened.copy(editing = true, draft = opened.content, dirty = false, conflict = false)) }
    }

    fun updateFileDraft(value: String) {
        val opened = _state.value.openedFile ?: return
        if (!opened.editing || opened.saving) return
        _state.update { it.copy(openedFile = opened.copy(draft = value, dirty = value != opened.content, conflict = false)) }
    }

    fun cancelFileEdit() {
        val opened = _state.value.openedFile ?: return
        _state.update { it.copy(openedFile = opened.copy(editing = false, draft = opened.content.orEmpty(), dirty = false, conflict = false)) }
    }

    fun reloadOpenedFile() {
        val project = _state.value.activeProject ?: return
        val path = _state.value.openedFile?.path ?: return
        _state.update { it.copy(openedFile = OpenedFileState(path = path)) }
        loadOpenedFile(project, path)
    }

    fun dismissFileConflict() {
        val opened = _state.value.openedFile ?: return
        _state.update { it.copy(openedFile = opened.copy(conflict = false, saving = false)) }
    }

    fun saveOpenedFile() {
        val current = _state.value
        val project = current.activeProject ?: return
        val opened = current.openedFile ?: return
        if (!opened.editing || opened.saving || !opened.dirty || current.isRunning ||
            current.projectTerminalRunning || current.androidBuildRunning
        ) return
        _state.update { it.copy(openedFile = opened.copy(saving = true, conflict = false)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = safeWorkspaceFile(project, opened.path) ?: error("unsafe_path")
                    if (opened.originalFingerprint == null || fingerprint(file) != opened.originalFingerprint) {
                        return@runCatching false
                    }
                    var normalized = opened.draft.replace("\r\n", "\n").replace('\r', '\n')
                    normalized = normalized.trimEnd('\n') + if (opened.hadTrailingNewline) "\n" else ""
                    if (opened.lineEnding == "\r\n") normalized = normalized.replace("\n", "\r\n")
                    val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
                    try {
                        temporary.writeText(normalized, Charsets.UTF_8)
                        if (file.canExecute()) temporary.setExecutable(true, false)
                        runCatching {
                            Files.move(
                                temporary.toPath(), file.toPath(),
                                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                            )
                        }.getOrElse {
                            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    } finally {
                        temporary.delete()
                    }
                    true
                }
            }
            when {
                result.getOrNull() == false -> _state.update {
                    it.copy(openedFile = it.openedFile?.copy(saving = false, conflict = true))
                }
                result.isSuccess -> {
                    _state.update { it.copy(toastMessage = str(R.string.file_saved)) }
                    invalidateFolderMetadata(project.id, listOf(opened.path))
                    refreshProjectFiles()
                    loadOpenedFile(project, opened.path)
                }
                else -> _state.update {
                    it.copy(
                        openedFile = it.openedFile?.copy(saving = false),
                        toastMessage = str(R.string.file_save_failed),
                    )
                }
            }
        }
    }


    private fun readWorkspaceDirectory(project: Project, relativePath: String): List<WorkspaceEntry> {
        val root = projectWorkspaceRoot(project)
        if (!root.isDirectory) return emptyList()
        val rootPath = root.canonicalFile.toPath()
        val directory = if (relativePath.isBlank()) root else File(root, relativePath)
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath()) ||
            !runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
        ) return emptyList()
        fun isVisibleSafeEntry(file: File): Boolean {
            val relative = file.relativeTo(root).invariantSeparatorsPath
            return !isClaudeRuntimeMetadata(relative) &&
                !Files.isSymbolicLink(file.toPath()) &&
                runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
        }
        return directory.listFiles().orEmpty()
            .asSequence()
            .filter(::isVisibleSafeEntry)
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                WorkspaceEntry(
                    path = relative,
                    name = file.name,
                    isDirectory = file.isDirectory,
                    depth = relative.count { it == '/' },
                    sizeBytes = if (file.isFile) file.length() else 0,
                    childCount = if (file.isDirectory) {
                        folderMetadataCache[folderMetadataKey(project.id, relative)]
                            ?.takeIf { it.modifiedAtMillis == file.lastModified() }
                            ?.childCount
                    } else {
                        null
                    },
                )
            }
            .sortedWith(compareByDescending<WorkspaceEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()
    }

    private fun loadMissingFolderCounts(project: Project, entries: List<WorkspaceEntry>) {
        entries.asSequence()
            .filter { it.isDirectory && it.childCount == null && !isDeferredMetadataPath(it.path) }
            .forEach { entry ->
                val key = folderMetadataKey(project.id, entry.path)
                if (!folderCountsInFlight.add(key)) return@forEach
                viewModelScope.launch {
                    val metadata = withContext(Dispatchers.IO) {
                        val directory = safeWorkspaceDirectory(project, entry.path)
                        directory?.let {
                            FolderMetadata(
                                modifiedAtMillis = it.lastModified(),
                                childCount = countVisibleChildren(project, it),
                            )
                        }
                    }
                    folderCountsInFlight.remove(key)
                    if (metadata == null || _state.value.activeProject?.id != project.id) return@launch
                    folderMetadataCache[key] = metadata
                    _state.update { current ->
                        current.copy(
                            workspaceFiles = current.workspaceFiles.map { item ->
                                if (item.path == entry.path) item.copy(childCount = metadata.childCount) else item
                            },
                        )
                    }
                }
            }
    }

    private fun updateFolderMetadata(project: Project, relativePath: String, count: Int) {
        val directory = safeWorkspaceDirectory(project, relativePath) ?: return
        folderMetadataCache[folderMetadataKey(project.id, relativePath)] = FolderMetadata(
            modifiedAtMillis = directory.lastModified(),
            childCount = count,
        )
    }

    private fun countVisibleChildren(project: Project, directory: File): Int {
        val root = projectWorkspaceRoot(project).canonicalFile.toPath()
        return directory.listFiles().orEmpty().count { file ->
            val relative = runCatching { file.relativeTo(projectWorkspaceRoot(project)).invariantSeparatorsPath }
                .getOrNull() ?: return@count false
            !isClaudeRuntimeMetadata(relative) &&
                !Files.isSymbolicLink(file.toPath()) &&
                runCatching { file.canonicalFile.toPath().startsWith(root) }.getOrDefault(false)
        }
    }

    private fun safeWorkspaceDirectory(project: Project, relativePath: String): File? {
        val root = projectWorkspaceRoot(project).canonicalFile
        if (relativePath.isBlank()) return root.takeIf(File::isDirectory)
        return safeWorkspacePath(project, relativePath)?.takeIf(File::isDirectory)
    }

    private fun folderMetadataKey(projectId: String, path: String): String = "$projectId:${path.trim('/')}"

    private fun invalidateFolderMetadata(projectId: String, paths: List<String>) {
        paths.forEach { changedPath ->
            val normalized = changedPath.trim('/')
            val parent = normalized.substringBeforeLast('/', "")
            folderMetadataCache.remove(folderMetadataKey(projectId, parent))
            folderMetadataCache.remove(folderMetadataKey(projectId, normalized))
        }
    }

    private fun isDeferredMetadataPath(path: String): Boolean {
        val segments = path.split('/')
        return segments.any { it in DEFERRED_FOLDER_METADATA_NAMES }
    }

    private fun sortWorkspaceEntries(entries: List<WorkspaceEntry>): List<WorkspaceEntry> {
        val childrenByParent = entries.groupBy { entry -> entry.path.substringBeforeLast('/', "") }
        val siblingOrder = compareByDescending<WorkspaceEntry> { it.isDirectory }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.name }
        return buildList(entries.size) {
            fun appendChildren(parentPath: String) {
                childrenByParent[parentPath].orEmpty().sortedWith(siblingOrder).forEach { entry ->
                    add(entry)
                    appendChildren(entry.path)
                }
            }
            appendChildren("")
        }
    }

    private fun isClaudeRuntimeMetadata(relativePath: String): Boolean {
        return relativePath == ".claude" ||
            relativePath == ".claude.json" ||
            relativePath.startsWith(".claude/")
    }

    private fun safeWorkspaceFile(project: Project, relativePath: String): File? {
        if (relativePath.isBlank() || isClaudeRuntimeMetadata(relativePath)) return null
        val root = projectWorkspaceRoot(project).canonicalFile
        var cursor = root
        for (segment in relativePath.replace('\\', '/').split('/')) {
            if (segment.isBlank() || segment == "." || segment == "..") return null
            cursor = File(cursor, segment)
            if (Files.isSymbolicLink(cursor.toPath())) return null
        }
        val canonical = runCatching { cursor.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { it.toPath().startsWith(root.toPath()) && it.isFile }
    }

    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- Manual file management (Files tab) ----

    /** Resolves a workspace path (file or folder) inside the project root, rejecting symlinks and traversal. */
    private fun safeWorkspacePath(project: Project, relativePath: String): File? {
        val root = projectWorkspaceRoot(project).canonicalFile
        val clean = relativePath.replace('\\', '/').trim('/')
        if (clean.isBlank()) return root
        if (isClaudeRuntimeMetadata(clean)) return null
        var cursor = root
        for (segment in clean.split('/')) {
            if (segment.isBlank() || segment == "." || segment == "..") return null
            cursor = File(cursor, segment)
            if (Files.isSymbolicLink(cursor.toPath())) return null
        }
        val canonical = runCatching { cursor.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { it.toPath().startsWith(root.toPath()) }
    }

    private fun isValidEntryName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && name.length <= 255 &&
            name.none { it == '/' || it == '\\' || it == '\u0000' } &&
            !isClaudeRuntimeMetadata(name)

    private fun fileOpsBlocked(): Boolean {
        val current = _state.value
        val busy = current.isRunning || current.projectTerminalRunning || current.androidBuildRunning ||
            current.projectExportRunning
        if (busy) _state.update { it.copy(toastMessage = str(R.string.fm_busy)) }
        return busy
    }

    private fun parentPathOf(path: String): String = path.trim('/').substringBeforeLast('/', "")

    private fun childPath(parent: String, name: String): String =
        parent.trim('/').let { if (it.isBlank()) name else "$it/$name" }

    /** Reloads one folder after a change; drops stale entries under a removed path first. */
    private fun reloadWorkspaceFolder(project: Project, parent: String, removedPath: String? = null) {
        invalidateFolderMetadata(project.id, listOfNotNull(parent, removedPath))
        if (removedPath != null) {
            _state.update { current ->
                current.copy(
                    workspaceFiles = current.workspaceFiles.filterNot {
                        it.path == removedPath || it.path.startsWith("$removedPath/")
                    },
                )
            }
        }
        loadProjectDirectory(parent)
        if (parent.isNotBlank()) {
            val grandParent = parentPathOf(parent)
            invalidateFolderMetadata(project.id, listOf(grandParent))
            loadProjectDirectory(grandParent)
        }
    }

    private fun runFileOperation(
        project: Project,
        reloadParent: String,
        removedPath: String? = null,
        operation: () -> String,
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(operation) }
            if (_state.value.activeProject?.id != project.id) return@launch
            result
                .onSuccess { message -> _state.update { it.copy(toastMessage = message) } }
                .onFailure { error ->
                    _state.update {
                        it.copy(toastMessage = str(R.string.fm_failed, error.message ?: str(R.string.vm_unknown_error)))
                    }
                }
            reloadWorkspaceFolder(project, reloadParent, removedPath.takeIf { result.isSuccess })
        }
    }

    fun createWorkspaceEntry(parentPath: String, name: String, isDirectory: Boolean) {
        val project = _state.value.activeProject ?: return
        val trimmed = name.trim()
        if (!isValidEntryName(trimmed)) {
            _state.update { it.copy(toastMessage = str(R.string.fm_invalid_name)) }
            return
        }
        if (fileOpsBlocked()) return
        runFileOperation(project, parentPath) {
            val parent = safeWorkspacePath(project, parentPath)?.takeIf(File::isDirectory)
                ?: error(str(R.string.fm_protected))
            val target = File(parent, trimmed)
            check(!target.exists()) { str(R.string.fm_exists, trimmed) }
            val created = if (isDirectory) target.mkdir() else target.createNewFile()
            check(created) { str(R.string.fm_protected) }
            str(R.string.fm_created, trimmed)
        }
    }

    fun renameWorkspaceEntry(path: String, newName: String) {
        val project = _state.value.activeProject ?: return
        val trimmed = newName.trim()
        if (!isValidEntryName(trimmed)) {
            _state.update { it.copy(toastMessage = str(R.string.fm_invalid_name)) }
            return
        }
        if (trimmed == path.substringAfterLast('/')) return
        if (fileOpsBlocked()) return
        val parentPath = parentPathOf(path)
        runFileOperation(project, parentPath, removedPath = path) {
            val source = safeWorkspacePath(project, path)?.takeIf { it.exists() && path.isNotBlank() }
                ?: error(str(R.string.fm_protected))
            val target = File(source.parentFile, trimmed)
            // Allow case-only renames on case-insensitive storage.
            check(!target.exists() || target.canonicalPath.equals(source.canonicalPath, ignoreCase = true)) {
                str(R.string.fm_exists, trimmed)
            }
            Files.move(source.toPath(), target.toPath())
            str(R.string.fm_renamed, trimmed)
        }
    }

    fun deleteWorkspaceEntry(path: String) {
        val project = _state.value.activeProject ?: return
        if (path.isBlank() || fileOpsBlocked()) return
        val name = path.substringAfterLast('/')
        runFileOperation(project, parentPathOf(path), removedPath = path) {
            val target = safeWorkspacePath(project, path)?.takeIf(File::exists) ?: error(str(R.string.fm_protected))
            check(target != projectWorkspaceRoot(project).canonicalFile) { str(R.string.fm_protected) }
            // deleteRecursively does not follow symlinks out of the folder; it deletes the link itself.
            check(target.deleteRecursively()) { str(R.string.fm_protected) }
            str(R.string.fm_deleted, name)
        }
    }

    fun uploadToWorkspace(parentPath: String, uris: List<Uri>) {
        val project = _state.value.activeProject ?: return
        if (uris.isEmpty() || fileOpsBlocked()) return
        val resolver = getApplication<Application>().contentResolver
        runFileOperation(project, parentPath) {
            val parent = safeWorkspacePath(project, parentPath)?.takeIf(File::isDirectory)
                ?: error(str(R.string.fm_protected))
            var uploaded = 0
            uris.forEach { uri ->
                var displayName = "upload"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                            ?.let { displayName = cursor.getString(it) ?: displayName }
                    }
                }
                val safeName = sanitizeAttachmentName(displayName)
                val stem = safeName.substringBeforeLast('.', safeName)
                val extension = safeName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
                var destination = File(parent, safeName)
                var suffix = 2
                while (destination.exists()) destination = File(parent, "$stem-${suffix++}$extension")
                var copied = 0L
                // Uploads stream straight to disk, so the only real limit is free storage.
                val uploadLimit = (parent.usableSpace * 8L / 10L).coerceAtMost(MAX_UPLOAD_BYTES)
                val uploadLimitText = android.text.format.Formatter.formatShortFileSize(getApplication(), uploadLimit)
                try {
                    resolver.openInputStream(uri)?.buffered()?.use { input ->
                        destination.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                check(copied <= uploadLimit) { str(R.string.fm_too_large, displayName, uploadLimitText) }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error(str(R.string.vm_read_named_failed, displayName))
                } catch (error: Throwable) {
                    destination.delete()
                    throw error
                }
                uploaded++
            }
            AppStrings.context(getApplication()).resources.getQuantityString(R.plurals.fm_uploaded, uploaded, uploaded)
        }
    }

    fun shareWorkspaceFile(path: String) {
        val project = _state.value.activeProject ?: return
        runCatching {
            val file = safeWorkspaceFile(project, path) ?: error(str(R.string.fm_protected))
            val app = getApplication<Application>()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
            app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }, str(R.string.fm_share_title, file.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { error ->
            _state.update { it.copy(toastMessage = str(R.string.fm_failed, error.message ?: str(R.string.vm_unknown_error))) }
        }
    }

    fun saveWorkspaceFileCopy(path: String, destination: Uri) {
        val project = _state.value.activeProject ?: return
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = safeWorkspaceFile(project, path) ?: error(str(R.string.fm_protected))
                    resolver.openOutputStream(destination, "wt")?.use { output ->
                        file.inputStream().buffered().use { it.copyTo(output) }
                    } ?: error(str(R.string.fm_protected))
                    file.name
                }
            }
            _state.update {
                it.copy(
                    toastMessage = result.fold(
                        onSuccess = { name -> str(R.string.fm_saved_copy, name) },
                        onFailure = { error -> str(R.string.fm_failed, error.message ?: str(R.string.vm_unknown_error)) },
                    ),
                )
            }
        }
    }

    fun addChatAttachments(uris: List<Uri>) {
        val current = _state.value
        val project = current.activeProject ?: return
        val chatId = current.activeChatId ?: return
        if (uris.isEmpty()) return
        val remaining = (MAX_ATTACHMENTS_PER_MESSAGE - current.pendingAttachments.size).coerceAtLeast(0)
        if (remaining == 0) {
            _state.update { it.copy(toastMessage = str(R.string.vm_max_attachments, MAX_ATTACHMENTS_PER_MESSAGE)) }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val added = mutableListOf<ChatAttachment>()
                val errors = mutableListOf<String>()
                uris.take(remaining).forEach { uri ->
                    runCatching { copyChatAttachment(project, chatId, uri) }
                        .onSuccess(added::add)
                        .onFailure { errors += (it.message ?: str(R.string.vm_attach_failed)) }
                }
                added to errors
            }
            val (added, errors) = result
            _state.update { state ->
                state.copy(
                    pendingAttachments = state.pendingAttachments + added,
                    toastMessage = errors.firstOrNull() ?: if (uris.size > remaining) plural(R.plurals.vm_more_files_can_be_added, remaining, remaining) else null,
                )
            }
            if (added.isNotEmpty()) refreshProjectFiles()
        }
    }

    fun removePendingAttachment(attachmentId: String) {
        val current = _state.value
        val project = current.activeProject ?: return
        val attachment = current.pendingAttachments.firstOrNull { it.id == attachmentId } ?: return
        _state.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { item -> item.id == attachmentId }) }
        viewModelScope.launch(Dispatchers.IO) {
            val root = projectWorkspaceRoot(project)
            val file = File(root, attachment.relativePath).canonicalFile
            if (file.toPath().startsWith(root.canonicalFile.toPath())) file.delete()
        }
    }

    fun openChatAttachment(attachment: ChatAttachment) {
        val project = _state.value.activeProject ?: return
        runCatching {
            val root = projectWorkspaceRoot(project).canonicalFile
            val file = File(root, attachment.relativePath).canonicalFile
            require(file.isFile && file.toPath().startsWith(root.toPath())) { str(R.string.vm_attachment_unavailable) }
            val app = getApplication<Application>()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, attachment.mimeType.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }.onFailure { error ->
            _state.update { it.copy(toastMessage = error.message ?: str(R.string.vm_no_app_for_attachment)) }
        }
    }

    private fun copyChatAttachment(project: Project, chatId: String, uri: Uri): ChatAttachment {
        val resolver = getApplication<Application>().contentResolver
        var displayName = "attachment"
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { displayName = cursor.getString(it) ?: displayName }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { declaredSize = cursor.getLong(it) }
            }
        }
        val mimeType = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val supportedTextExtensions = setOf(
            "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml", "log",
            "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm",
            "css", "scss", "sass", "less", "c", "cc", "cpp", "h", "hpp", "sh", "bash", "zsh",
            "gradle", "properties", "toml", "ini", "conf", "sql",
        )
        val supported = mimeType.startsWith("image/") ||
            mimeType.startsWith("text/") || mimeType == "application/json" || mimeType == "application/xml" ||
            mimeType.endsWith("+json") || mimeType.endsWith("+xml") || extension in supportedTextExtensions
        require(supported) { str(R.string.vm_attachment_unsupported_type) }
        require(declaredSize <= MAX_ATTACHMENT_BYTES || declaredSize < 0) { str(R.string.vm_attachment_too_large, displayName) }
        val safeName = sanitizeAttachmentName(displayName)
        val root = projectWorkspaceRoot(project).canonicalFile
        val folder = File(root, "attachments/$chatId").apply { mkdirs() }.canonicalFile
        require(folder.toPath().startsWith(root.toPath())) { str(R.string.vm_unsafe_attachment_folder) }
        val stem = safeName.substringBeforeLast('.', safeName)
        val safeExtension = safeName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var destination = File(folder, safeName)
        var suffix = 2
        while (destination.exists()) destination = File(folder, "$stem-${suffix++}$safeExtension")
        var copied = 0L
        try {
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_ATTACHMENT_BYTES) { str(R.string.vm_attachment_too_large, displayName) }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error(str(R.string.vm_read_named_failed, displayName))
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
        return ChatAttachment(
            displayName = displayName.take(120),
            relativePath = destination.relativeTo(root).invariantSeparatorsPath,
            mimeType = mimeType,
            sizeBytes = copied,
        )
    }

    private fun sanitizeAttachmentName(name: String): String {
        val clean = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trim('.').take(100)
        return clean.ifBlank { "attachment-${UUID.randomUUID().toString().take(8)}" }
    }

    fun sendPrompt(prompt: String) {
        val project = state.value.activeProject ?: return
        if (_state.value.agentKind == AgentKind.ANTIGRAVITY &&
            _state.value.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN) {
            _state.update { it.copy(toastMessage = str(R.string.vm_antigravity_signin_before_task)) }
            return
        }
        if (_state.value.agentKind == AgentKind.DEEPSEEK_HARNESS && _state.value.provider.kind == ProviderKind.CLAUDE) {
            _state.update { it.copy(toastMessage = str(R.string.vm_dsh_claude_unsupported)) }
            return
        }
        val attachments = state.value.pendingAttachments
        if (prompt.isBlank() && attachments.isEmpty()) return
        if (state.value.isRunning || state.value.androidBuildRunning) {
            queueFollowUp(project, prompt, attachments)
            return
        }
        if (state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = str(R.string.an_wait_terminal_then_send)) }
            return
        }
        startPrompt(project, prompt, attachments)
    }

    private fun queueFollowUp(project: Project, prompt: String, attachments: List<ChatAttachment>) {
        val requestText = prompt.trim().ifBlank { "Please review the attached files." }
        pendingFollowUps.addLast(QueuedFollowUp(projectId = project.id, prompt = requestText, attachments = attachments))
        _state.update {
            it.copy(
                pendingAttachments = emptyList(),
                queuedFollowUps = pendingFollowUps.toList(),
                toastMessage = str(R.string.an_follow_up_queued),
            )
        }
    }

    fun steerQueuedFollowUp(id: String) {
        if (steeringToFollowUp) return
        val selected = pendingFollowUps.firstOrNull { it.id == id } ?: return
        pendingFollowUps.remove(selected)
        pendingFollowUps.addFirst(selected)
        _state.update {
            it.copy(
                queuedFollowUps = pendingFollowUps.toList(),
                toastMessage = str(R.string.an_steering_to_follow_up),
            )
        }
        when {
            _state.value.isRunning -> {
                steeringToFollowUp = true
                viewModelScope.launch { activeRuntime().stopActiveSession() }
            }
            _state.value.androidBuildRunning -> {
                steeringToFollowUp = true
                cancelAndroidBuild()
            }
            else -> startNextFollowUp()
        }
    }

    fun removeQueuedFollowUp(id: String) {
        val selected = pendingFollowUps.firstOrNull { it.id == id } ?: return
        pendingFollowUps.remove(selected)
        _state.update { it.copy(queuedFollowUps = pendingFollowUps.toList()) }
    }

    private fun startPrompt(project: Project, prompt: String, attachments: List<ChatAttachment>) {
        // requestText is sent to the agent, so it stays English; the chat title is user-facing.
        val requestText = prompt.trim().ifBlank { "Please review the attached files." }
        updateActiveChatTitle(prompt.trim().ifBlank { str(R.string.md_chat_title_attachments) })
        _state.update {
            val startedAt = System.currentTimeMillis()
            it.copy(
                messages = it.messages + ChatMessage(fromUser = true, text = prompt.trim(), attachments = attachments),
                pendingAttachments = emptyList(),
                isRunning = true,
                activity = listOf(ActivityItem(str(R.string.vm_understanding_request), str(R.string.vm_preparing_safe_plan), false)) + it.activity,
                liveProcess = listOf(ActivityItem("Think", requestPlanningSummary(requestText, it.agentKind), false)),
                liveThinking = true,
                activeThinkingBlockId = null,
                taskStartedAtMillis = startedAt,
                taskFinishedAtMillis = null,
                workSegmentStartedAtMillis = startedAt,
                currentTaskRequest = requestText,
            )
        }
        touchProject(project.id)
        persistMessages()
        val history = state.value.messages // includes all messages up to now
        val ownerTag = _state.value.ownerDisplayName.trim().takeIf { it.isNotBlank() }
            ?.let { "[User Profile: Address the user as \"$it\" in your replies.]\n" }
            .orEmpty()
        val soloWorkspaceDir = File(getApplication<Application>().filesDir, "projects/${project.id}")
        val soloSkillsTag = skillManager.buildSkillsPromptSection(soloWorkspaceDir, requestText)
            .takeIf { it.isNotBlank() }
            ?.let { "$it\n" }
            .orEmpty()
        val runtimePrompt = ownerTag + soloSkillsTag + if (attachments.isEmpty()) requestText else buildString {
            appendLine(requestText)
            appendLine()
            appendLine("<attached_files>")
            attachments.forEach { attachment ->
                appendLine("- ${attachment.displayName}: ${projectGuestRoot(project)}/${attachment.relativePath} (${attachment.mimeType})")
            }
            appendLine("These files were explicitly attached by the user. Inspect them only as needed for the request.")
            appendLine("</attached_files>")
        }
        failedApiKeyIds.clear()
        activeRuntimeRequest = RuntimeRetryRequest(
            runtime = activeRuntime(),
            project = project,
            prompt = runtimePrompt,
            history = history,
            provider = state.value.provider,
        )
        viewModelScope.launch {
            activeRuntimeRequest?.let { request ->
                request.runtime.startSession(
                    request.project.id,
                    request.project.slug,
                    request.project.kind,
                    request.prompt,
                    request.history,
                    request.provider,
                )
            }
        }
    }

    fun answerApproval(approved: Boolean) {
        val request = state.value.pendingApproval ?: return
        viewModelScope.launch { activeRuntime().respondToApproval(request, approved) }
    }

    fun stopTask() {
        if (!_state.value.isRunning) return
        steeringToFollowUp = false
        pendingFollowUps.clear()
        _state.update { it.copy(queuedFollowUps = emptyList()) }
        viewModelScope.launch { activeRuntime().stopActiveSession() }
    }

    private fun startNextFollowUp() {
        val next = if (pendingFollowUps.isEmpty()) null else pendingFollowUps.removeFirst()
        if (next == null) {
            _state.update { it.copy(queuedFollowUps = emptyList()) }
            return
        }
        val project = _state.value.activeProject
        if (project == null || project.id != next.projectId) {
            pendingFollowUps.clear()
            _state.update { it.copy(queuedFollowUps = emptyList()) }
            return
        }
        _state.update { it.copy(queuedFollowUps = pendingFollowUps.toList()) }
        startPrompt(project, next.prompt, next.attachments)
    }

    fun undoLastChanges() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val restored = activeRuntime().undoLastChanges(project.id)
            _state.update { current ->
                current.copy(
                    changes = if (restored) emptyList() else current.changes,
                    activity = listOf(
                        ActivityItem(
                            if (restored) str(R.string.vm_changes_undone) else str(R.string.vm_undo_unavailable),
                            if (restored) str(R.string.vm_files_restored) else str(R.string.vm_no_checkpoint),
                        ),
                    ) + current.activity,
                )
            }
            if (restored) refreshProjectFiles()
        }
    }

    fun keepLastChanges() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            activeRuntime().acceptLastChanges(project.id)
            _state.update {
                it.copy(
                    changes = emptyList(),
                    activity = listOf(ActivityItem(str(R.string.vm_changes_kept), str(R.string.vm_changes_kept_detail))) + it.activity,
                )
            }
        }
    }

    fun undoFileChange(path: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            if (activeRuntime().undoFileChange(project.id, path)) {
                _state.update { current -> current.copy(changes = current.changes.filterNot { it.path == path }) }
                refreshProjectFiles()
            }
        }
    }

    fun keepFileChange(path: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            if (activeRuntime().acceptFileChange(project.id, path)) {
                _state.update { current -> current.copy(changes = current.changes.filterNot { it.path == path }) }
            }
        }
    }

    private fun isNoisyRuntimeItem(item: ActivityItem): Boolean {
        // Never allocate or scan an entire command/result just to classify the row.
        val combined = "${item.title} ${item.detail.take(NOISY_ITEM_SCAN_CHARS)}"
        return combined.contains("Starting Claude Code", true) ||
            combined.contains("Agent process started", true) ||
            combined.contains("Claude Code connected", true) ||
            combined.contains("Runtime warning", true) ||
            combined.contains("unrecognized_model", true) ||
            combined.contains("Writing response", true) ||
            combined.contains("Claude Code finished", true) ||
            combined.contains("Task completed", true) ||
            combined.contains(str(R.string.vm_task_completed), true)
    }

    private fun toolPlanSummary(toolName: String, detail: String): String {
        val clean = detail.replace(Regex("\\s+"), " ").trim()
        val short = clean.take(90).ifBlank { str(R.string.vm_plan_current_project) }
        return when (toolName) {
            "Write" -> str(R.string.vm_plan_create, clean.substringAfterLast('/').ifBlank { str(R.string.vm_plan_a_project_file) })
            "Edit", "NotebookEdit" -> str(R.string.vm_plan_update, clean.substringAfterLast('/').ifBlank { str(R.string.vm_plan_a_project_file) })
            "Read" -> str(R.string.vm_plan_inspect, clean.substringAfterLast('/').ifBlank { str(R.string.vm_plan_a_project_file) })
            "Glob" -> str(R.string.vm_plan_find)
            "Grep" -> str(R.string.vm_plan_search, short)
            "Bash" -> if (clean.contains("cat ", true) || clean.contains("printf ", true) || clean.contains(" >")) {
                str(R.string.vm_plan_bash_files)
            } else {
                str(R.string.vm_plan_run, short)
            }
            else -> str(R.string.vm_plan_use_tool, toolName)
        }
    }

    private fun requestPlanningSummary(
        request: String,
        agentKind: AgentKind,
        toolName: String? = null,
        detail: String = "",
    ): String {
        val agentName = agentKind.localizedTitle(getApplication())
        val cleanRequest = request.replace(Regex("\\s+"), " ").trim().take(110)
        val requestPart = if (cleanRequest.isBlank()) {
            str(R.string.vm_plan_agent_reviewing, agentName)
        } else {
            str(R.string.vm_plan_user_asking, cleanRequest)
        }
        return if (toolName == null) {
            str(R.string.vm_plan_deciding, requestPart, agentName)
        } else {
            str(R.string.vm_plan_with_tool, requestPart, toolPlanSummary(toolName, detail))
        }
    }

    private fun finishWorkSegment(current: AppUiState, finishedAt: Long = System.currentTimeMillis()): AppUiState {
        val meaningfulItems = current.liveProcess.filterNot(::isNoisyRuntimeItem)
            .map(::boundedActivityItem)
            .map { if (it.isComplete) it else it.copy(isComplete = true) }
            .takeLast(MAX_VISIBLE_WORK_ITEMS)
        if (!current.liveThinking && meaningfulItems.isEmpty()) {
            return current.copy(liveProcess = emptyList(), workSegmentStartedAtMillis = null)
        }
        val startedAt = current.workSegmentStartedAtMillis ?: current.taskStartedAtMillis ?: finishedAt
        val block = ChatMessage(
            fromUser = false,
            text = "",
            workItems = meaningfulItems,
            workedMillis = (finishedAt - startedAt).coerceAtLeast(0L),
        )
        return current.copy(
            messages = current.messages + block,
            liveProcess = emptyList(),
            liveThinking = false,
            activeThinkingBlockId = null,
            workSegmentStartedAtMillis = null,
        )
    }

    /** Adds the complete request duration to the response produced after the latest user message. */
    private fun attachTaskDuration(current: AppUiState, finishedAt: Long): AppUiState {
        val startedAt = current.taskStartedAtMillis ?: return current
        val lastUserIndex = current.messages.indexOfLast { it.fromUser }
        val responseIndex = current.messages.indices.lastOrNull { index ->
            index > lastUserIndex && !current.messages[index].fromUser && current.messages[index].text.isNotBlank()
        } ?: return current
        val updated = current.messages.toMutableList()
        updated[responseIndex] = updated[responseIndex].copy(
            workedMillis = (finishedAt - startedAt).coerceAtLeast(1L),
        )
        return current.copy(messages = updated)
    }

    private fun appendWorkItem(current: AppUiState, item: ActivityItem): AppUiState {
        val boundedItem = boundedActivityItem(item)
        if (isNoisyRuntimeItem(boundedItem)) return current
        return current.copy(
            liveProcess = (current.liveProcess
                .map { if (!it.isComplete) it.copy(isComplete = true) else it } + boundedItem)
                .takeLast(MAX_VISIBLE_WORK_ITEMS),
            liveThinking = false,
            workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
        )
    }

    private fun onRuntimeEvent(event: RuntimeEvent) {
        val terminalEventForActiveSession =
            (event is RuntimeEvent.SessionCompleted || event is RuntimeEvent.SessionFailed) &&
                _state.value.isRunning &&
                (_state.value.activeSessionId == null || _state.value.activeSessionId == event.sessionId)
        if (event is RuntimeEvent.SessionFailed && _state.value.agentKind == AgentKind.ANTIGRAVITY &&
            (event.reason.contains("sign-in", true) || event.reason.contains("authentication", true))) {
            antigravityAuthController.invalidateSession(event.reason)
        }
        if (event is RuntimeEvent.SessionFailed && retryWithNextApiKey(event)) return
        _state.update { current ->
            if (!current.isRunning) {
                current
            } else if (current.activeSessionId != null && current.activeSessionId != event.sessionId) {
                current
            } else when (event) {
                is RuntimeEvent.SessionStarted -> current.copy(
                    activeSessionId = event.sessionId,
                    activity = current.activity.mapIndexed { index, item -> if (index == 0) item.copy(isComplete = true) else item },
                )
                is RuntimeEvent.AssistantDelta -> {
                    val timeline = if (current.liveThinking || current.liveProcess.any { !isNoisyRuntimeItem(it) }) {
                        finishWorkSegment(current)
                    } else {
                        current
                    }
                    val lastMessage = timeline.messages.lastOrNull()
                    if (lastMessage != null && !lastMessage.fromUser && lastMessage.workItems.isEmpty() && lastMessage.workedMillis == 0L) {
                        timeline.copy(messages = timeline.messages.dropLast(1) + lastMessage.copy(text = lastMessage.text + event.text))
                    } else {
                        timeline.copy(messages = timeline.messages + ChatMessage(fromUser = false, text = event.text))
                    }
                }
                is RuntimeEvent.ReasoningProgress -> {
                    val existingIndex = current.liveProcess.indexOfLast { it.title == "Think" }
                    // The request-level Think summary is seeded once in sendPrompt.
                    // After that segment has been committed to the timeline, later
                    // agent turns must not repeat the same request summary.
                    if (existingIndex < 0) return@update current
                    val reasoning = ActivityItem(
                        title = "Think",
                        detail = current.liveProcess.getOrNull(existingIndex)?.detail
                            ?: requestPlanningSummary(current.currentTaskRequest.orEmpty(), current.agentKind),
                        isComplete = false,
                    )
                    val process = if (existingIndex >= 0) {
                        current.liveProcess.toMutableList().also { it[existingIndex] = reasoning }
                    } else {
                        current.liveProcess + reasoning
                    }
                    current.copy(
                        liveProcess = process,
                        liveThinking = true,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.ReasoningSummary -> {
                    val summary = event.summary.trim()
                    val process = current.liveProcess.toMutableList()
                    val existingIndex = process.indexOfLast { !it.isComplete && it.title == "Think" }
                    if (event.startsNewBlock) {
                        process.indices.forEach { index ->
                            if (!process[index].isComplete) process[index] = process[index].copy(isComplete = true)
                        }
                        val initial = summary.ifBlank { str(R.string.vm_thinking) }
                        val replaceFallback = current.activeThinkingBlockId == null &&
                            process.size == 1 && process.first().title == "Think"
                        if (replaceFallback) {
                            process[0] = ActivityItem("Think", initial, event.isFinal)
                        } else {
                            process += ActivityItem("Think", initial, event.isFinal)
                        }
                    } else if (current.activeThinkingBlockId == event.blockId && existingIndex >= 0 && summary.isNotBlank()) {
                        process[existingIndex] = process[existingIndex].copy(
                            detail = summary,
                            isComplete = event.isFinal,
                        )
                    } else {
                        return@update current
                    }
                    current.copy(
                        liveProcess = process,
                        liveThinking = !event.isFinal,
                        activeThinkingBlockId = if (event.isFinal) null else event.blockId,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.ToolStarted -> {
                    val planned = current.copy(
                        liveProcess = current.liveProcess.map { item ->
                            if (!item.isComplete) item.copy(isComplete = true) else item
                        },
                        liveThinking = false,
                        activeThinkingBlockId = null,
                        activity = (listOf(
                            ActivityItem("Running ${event.toolName}", event.detail, false, isCommand = event.toolName == "Bash"),
                        ) + current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it })
                            .take(MAX_VISIBLE_WORK_ITEMS),
                    )
                    appendWorkItem(
                        planned,
                        ActivityItem("Running ${event.toolName}", event.detail, false, isCommand = event.toolName == "Bash"),
                    )
                }
                is RuntimeEvent.ToolProgress -> {
                    val runningIndex = current.liveProcess.indexOfLast {
                        !it.isComplete && it.title == "Running ${event.toolName}"
                    }
                    if (runningIndex < 0) current else current.copy(
                        liveProcess = current.liveProcess.toMutableList().also { items ->
                            items[runningIndex] = boundedActivityItem(
                                items[runningIndex].copy(detail = event.detail),
                            )
                        },
                    )
                }
                is RuntimeEvent.RuntimeLog -> appendWorkItem(
                    current.copy(activity = listOf(ActivityItem(event.title, event.detail)) + current.activity),
                    ActivityItem(event.title, event.detail),
                )
                is RuntimeEvent.ToolRequested -> appendWorkItem(current.copy(
                    pendingApproval = event.request,
                    activity = listOf(ActivityItem(str(R.string.vm_waiting_approval), event.request.explanation, false)) + current.activity,
                ), ActivityItem(str(R.string.vm_waiting_approval), event.request.explanation, false))
                is RuntimeEvent.ToolApproved -> appendWorkItem(current.copy(
                    pendingApproval = null,
                    activity = listOf(ActivityItem(str(R.string.vm_applying_approved), str(R.string.vm_editing_project_files), false)) + current.activity,
                ), ActivityItem(str(R.string.vm_action_approved), str(R.string.vm_agent_continuing, current.agentKind.localizedTitle(getApplication())), false))
                is RuntimeEvent.ToolRejected -> appendWorkItem(current.copy(
                    pendingApproval = null,
                ), ActivityItem(str(R.string.vm_action_rejected), str(R.string.vm_agent_continue_without, current.agentKind.localizedTitle(getApplication()))))
                is RuntimeEvent.ToolCompleted -> {
                    // Bridges fill empty tool results with English placeholders; translate for display.
                    val summary = when (event.summary) {
                        "Tool failed" -> str(R.string.md_tool_failed)
                        "Completed successfully" -> str(R.string.md_tool_completed_successfully)
                        else -> event.summary
                    }
                    val runningIndex = current.liveProcess.indexOfLast {
                        !it.isComplete && it.title == "Running ${event.toolName}"
                    }
                    val process = if (runningIndex >= 0) {
                        current.liveProcess.toMutableList().also { items ->
                            val runningItem = items[runningIndex]
                            items[runningIndex] = ActivityItem(
                                "${event.toolName} completed",
                                runningItem.detail.ifBlank { summary },
                                isCommand = event.toolName == "Bash",
                            ).let(::boundedActivityItem)
                        }
                    } else {
                        current.liveProcess + boundedActivityItem(ActivityItem(
                            "${event.toolName} completed",
                            summary,
                            isCommand = event.toolName == "Bash",
                        ))
                    }
                    current.copy(
                        activity = (listOf(ActivityItem(summary, event.toolName)) + current.activity)
                            .take(MAX_VISIBLE_WORK_ITEMS),
                        liveProcess = process.takeLast(MAX_VISIBLE_WORK_ITEMS),
                        liveThinking = false,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.FilesChanged -> current.copy(
                    changes = event.changes,
                    liveThinking = false,
                    liveProcess = if (event.paths.isEmpty()) current.liveProcess else current.liveProcess +
                        ActivityItem(
                            str(R.string.vm_files_changed),
                            event.paths.take(4).joinToString(", ") + if (event.paths.size > 4) " " + str(R.string.vm_files_more, event.paths.size - 4) else "",
                        ),
                    workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                )
                is RuntimeEvent.PreviewStarted -> current.copy(
                    previewReady = true,
                    previewUrl = event.url,
                    activity = listOf(ActivityItem(str(R.string.vm_preview_ready), event.url)) + current.activity,
                    liveProcess = current.liveProcess + ActivityItem(str(R.string.vm_preview_ready), event.url),
                    liveThinking = false,
                    workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                )
                is RuntimeEvent.SessionCompleted -> {
                    val finishedAt = System.currentTimeMillis()
                    attachTaskDuration(finishWorkSegment(current, finishedAt), finishedAt).copy(
                        isRunning = false,
                        activeSessionId = null,
                        activity = listOf(ActivityItem(str(R.string.vm_task_completed), str(R.string.vm_agent_finished, current.agentKind.localizedTitle(getApplication())))) +
                            current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                        taskFinishedAtMillis = finishedAt,
                        currentTaskRequest = null,
                    )
                }
                is RuntimeEvent.SessionFailed -> {
                    val finishedAt = System.currentTimeMillis()
                    val displayReason = localizedFailureReason(event.reason)
                    attachTaskDuration(
                        finishWorkSegment(
                            appendWorkItem(current, ActivityItem("Task stopped", displayReason)),
                            finishedAt,
                        ),
                        finishedAt,
                    ).copy(
                        isRunning = false,
                        activeSessionId = null,
                        pendingApproval = null,
                        toastMessage = displayReason.takeIf {
                            event.reason.contains("user not found", true) ||
                                event.reason.contains("API key", true) ||
                                event.reason.contains("authentication", true)
                        },
                        activity = listOf(ActivityItem("Task stopped", displayReason)) + current.activity,
                        taskFinishedAtMillis = finishedAt,
                        currentTaskRequest = null,
                    )
                }
            }
        }
        if (terminalEventForActiveSession) {
            activeRuntimeRequest = null
            failedApiKeyIds.clear()
            steeringToFollowUp = false
            if (pendingFollowUps.isNotEmpty()) {
                viewModelScope.launch {
                    delay(150)
                    if (!_state.value.isRunning) startNextFollowUp()
                }
            }
        }
        if (event is RuntimeEvent.FilesChanged || event is RuntimeEvent.SessionCompleted) {
            _state.value.activeProject?.id?.let { projectId ->
                if (event is RuntimeEvent.FilesChanged) invalidateFolderMetadata(projectId, event.paths)
                touchProject(projectId)
            }
            refreshProjectFiles()
        }
        // Save every visible reasoning/tool transition, not only assistant text and
        // final results. If Android kills the process, the last displayed timeline
        // is restored as an interrupted work block rather than disappearing.
        persistMessages(includeLiveProcess = true)
    }

    private fun retryWithNextApiKey(event: RuntimeEvent.SessionFailed): Boolean {
        val current = _state.value
        if (current.agentKind == AgentKind.ANTIGRAVITY) return false
        if (!current.isRunning || current.activeSessionId != event.sessionId) return false
        if (!isApiKeyFailure(event.reason)) return false
        val request = activeRuntimeRequest ?: return false
        val credentials = vault.credentials(request.provider.kind.name)
        val active = credentials.firstOrNull { it.isActive } ?: return false
        failedApiKeyIds += active.id
        val next = credentials.firstOrNull { it.id !in failedApiKeyIds } ?: return false
        if (!vault.activate(request.provider.kind.name, next.id)) return false
        _state.update {
            it.copy(
                activeSessionId = null,
                activeApiKeyName = next.name,
                toastMessage = str(R.string.vm_key_switched_toast, active.name, next.name),
                liveProcess = it.liveProcess + ActivityItem(str(R.string.vm_api_key_switched), str(R.string.vm_using_key, next.name), true),
            )
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            request.runtime.startSession(
                request.project.id,
                request.project.slug,
                request.project.kind,
                request.prompt,
                request.history,
                request.provider,
            )
        }
        return true
    }

    /**
     * Runtime bridges report failures in English (matched by [isApiKeyFailure] and the toast
     * check), so known reasons are translated only for display.
     */
    private fun localizedFailureReason(reason: String): String {
        val trimmed = reason.trim()
        Regex("^No API key is saved for (.+)\\.$").matchEntire(trimmed)?.let { match ->
            val name = match.groupValues[1]
            val kind = ProviderKind.entries.firstOrNull { it.title == name }
            return str(R.string.md_reason_no_api_key, kind?.localizedTitle(getApplication()) ?: name)
        }
        Regex("^(Claude Code|DeepSeek Harness) stopped with exit code (-?\\d+)$").matchEntire(trimmed)?.let { match ->
            return str(R.string.md_reason_agent_exit_code, match.groupValues[1], match.groupValues[2].toInt())
        }
        Regex("^Antigravity exited with code (-?\\d+)$").matchEntire(trimmed)?.let { match ->
            return str(R.string.md_reason_antigravity_exit_code, match.groupValues[1].toInt())
        }
        return when (trimmed) {
            "Stopped by user" -> str(R.string.md_reason_stopped_by_user)
            "User not found. Check the API key and provider account." -> str(R.string.md_reason_user_not_found)
            "The provider rejected the saved API key." -> str(R.string.md_reason_key_rejected)
            "Runtime verification failed. Nothing unverified was executed." -> str(R.string.md_reason_verification_failed)
            "The real Claude Code runtime could not start." -> str(R.string.md_reason_claude_could_not_start)
            "Claude Code reported an error" -> str(R.string.md_reason_claude_reported_error)
            "Antigravity CLI is not installed." -> str(R.string.md_reason_antigravity_cli_missing_short)
            "Antigravity needs Google sign-in. Open Settings → Coding agent." -> str(R.string.md_reason_antigravity_signin)
            "Your Antigravity account is out of credits. Check the account plan or wait for credits to reset." -> str(R.string.md_reason_antigravity_no_credits)
            "Antigravity reached the 60-minute task limit. Your files were kept." -> str(R.string.md_reason_antigravity_time_limit)
            "The selected Antigravity model is unavailable. Refresh models in Settings." -> str(R.string.md_reason_antigravity_model_unavailable)
            "Antigravity could not complete the task." -> str(R.string.md_reason_antigravity_failed)
            "Claude subscription login is not supported by DeepSeek Harness. Pick a key-based provider in Settings." -> str(R.string.vm_dsh_claude_unsupported)
            "DeepSeek Harness stopped before processing the prompt" -> str(R.string.md_reason_dsh_stopped_before_prompt)
            "No API key reached DeepSeek Harness. Re-save the provider key in Settings." -> str(R.string.md_reason_dsh_no_key)
            "DeepSeek Harness could not start." -> str(R.string.md_reason_dsh_could_not_start)
            "DeepSeek Harness reported an unspecified error" -> str(R.string.md_reason_dsh_unspecified)
            "DeepSeek Harness turn failed" -> str(R.string.md_reason_dsh_turn_failed)
            "DeepSeek Harness was blocked from completing the task" -> str(R.string.md_reason_dsh_blocked)
            "No Claude subscription token is saved. Add one from Agent → AI provider." -> str(R.string.md_reason_no_claude_token)
            "DeepSeek Harness is not installed. Open Settings → Coding agent to install it." -> str(R.string.md_reason_dsh_not_installed)
            "Antigravity CLI is not installed. Open Settings → Coding agent to install it." -> str(R.string.md_reason_antigravity_not_installed)
            "Antigravity did not answer. Try again." -> str(R.string.vm_antigravity_no_answer)
            else -> reason
        }
    }

    private fun isApiKeyFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "api key" in value || "authentication" in value || "user not found" in value ||
            "http 401" in value || "http 403" in value || "http 429" in value ||
            "expired" in value || "quota" in value || "rate limit" in value
    }

    private fun touchProject(projectId: String) {
        val now = System.currentTimeMillis()
        _state.update { current ->
            val updatedProjects = current.projects.map { p ->
                if (p.id == projectId) p.copy(updatedAtMillis = now) else p
            }
            val active = if (current.activeProject?.id == projectId) current.activeProject?.copy(updatedAtMillis = now) else current.activeProject
            current.copy(projects = updatedProjects, activeProject = active)
        }
        preferences.saveProjects(_state.value.projects)
    }

    private fun persistMessages(includeLiveProcess: Boolean = true) {
        val current = _state.value
        val project = current.activeProject ?: return
        val chatId = current.activeChatId ?: return
        val liveItems = if (includeLiveProcess) {
            current.liveProcess.takeLast(MAX_VISIBLE_WORK_ITEMS)
                .map(::boundedActivityItem)
                .filterNot(::isNoisyRuntimeItem)
        } else {
            emptyList()
        }
        val messages = if (liveItems.isEmpty() && !current.liveThinking) {
            current.messages
        } else {
            val startedAt = current.workSegmentStartedAtMillis ?: current.taskStartedAtMillis ?: System.currentTimeMillis()
            current.messages + ChatMessage(
                id = "interrupted-${current.activeSessionId ?: chatId}",
                fromUser = false,
                text = "",
                workItems = liveItems.map { it.copy(isComplete = true) } + ActivityItem(
                    str(R.string.vm_task_interrupted),
                    str(R.string.vm_task_interrupted_detail),
                ),
                workedMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
            )
        }
        transcriptWrites.trySend(TranscriptWrite(project.id, chatId, messages))
    }

    private fun boundedActivityItem(item: ActivityItem): ActivityItem {
        if (item.detail.length <= MAX_ACTIVITY_DETAIL_CHARS) return item
        return item.copy(
            detail = "… Earlier output omitted …\n" + item.detail.takeLast(MAX_ACTIVITY_DETAIL_CHARS),
        )
    }

    private fun updateActiveChatTitle(prompt: String) {
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId ?: return
        val now = System.currentTimeMillis()
        val newChatTitle = str(R.string.vm_new_chat)
        val title = prompt.replace(Regex("\\s+"), " ").trim().let {
            if (it.length <= 42) it else it.take(39).trimEnd() + "…"
        }
        _state.update { current ->
            val chats = current.projectChats.map { chat ->
                if (chat.id == chatId) {
                    chat.copy(
                        title = if (chat.title == "New chat" || chat.title == newChatTitle) title else chat.title,
                        updatedAtMillis = now,
                    )
                } else chat
            }.sortedByDescending { it.updatedAtMillis }
            current.copy(projectChats = chats)
        }
        preferences.saveProjectChats(project.id, _state.value.projectChats)
    }

    // --- ORQEN Multi-Agent AI Workspace & Team Coordination ---

    private fun defaultAgentTemplateForSlot(slot: AgentSlot): AgentState = when (slot) {
        AgentSlot.AGENT_1 -> AgentState(
            config = AgentConfig(
                slot = AgentSlot.AGENT_1,
                name = "Agent 01 (Lead)",
                role = AgentRole(
                    type = AgentRoleType.TEAM_LEADER,
                    title = "Team Leader / Architect / QA",
                    systemInstructions = "Analyze project overview, plan development steps, assign tasks to agents, verify commits, test features thoroughly, and approve pull requests.",
                    responsibilities = listOf(
                        "Analyze codebase overview and plan sequential parts",
                        "Assign tasks and define acceptance criteria",
                        "Verify commit SHAs and independent test results",
                        "Perform architectural review and approve merges"
                    ),
                ),
                providerId = "preset-antigravity",
                modelId = "gemini-3.8-flash-high",
            ),
            activityState = AgentActivityState(
                activity = ActivityType.PLANNING,
                currentTask = "Ready to lead & coordinate",
                lastAction = "Waiting for team kickoff",
            ),
        )
        AgentSlot.AGENT_2 -> AgentState(
            config = AgentConfig(
                slot = AgentSlot.AGENT_2,
                name = "Agent 02 (Dev)",
                role = AgentRole(
                    type = AgentRoleType.SOFTWARE_ENGINEER,
                    title = "Software Engineer",
                    systemInstructions = "Receive tasks from Lead, analyze codebase, write clean code, fix bugs, commit and push changes with clear commit messages.",
                    responsibilities = listOf(
                        "Implement assigned feature tasks",
                        "Edit source files under file locks",
                        "Create Git commits with part prefixes"
                    ),
                ),
                providerId = "preset-anthropic",
                modelId = "claude-sonnet-4-6",
            ),
            activityState = AgentActivityState(
                activity = ActivityType.WAITING,
                currentTask = "Ready for task assignment",
                lastAction = "Idle",
            ),
        )
        AgentSlot.AGENT_3 -> AgentState(
            config = AgentConfig(
                slot = AgentSlot.AGENT_3,
                name = "Agent 03 (Specialist)",
                role = AgentRole(
                    type = AgentRoleType.SPECIALIST,
                    title = "Specialist / Backend Developer",
                    systemInstructions = "Handle specialized tasks, complex algorithms, backend logic, APIs, and assist teammates on heavy modules.",
                    responsibilities = listOf(
                        "Develop complex backend APIs and data logic",
                        "Optimize algorithms and performance",
                        "Coordinate interface contracts with teammates"
                    ),
                ),
                providerId = "preset-openai",
                modelId = "gpt-4o",
            ),
            activityState = AgentActivityState(
                activity = ActivityType.WAITING,
                currentTask = "Ready for specialized tasks",
                lastAction = "Idle",
            ),
        )
        AgentSlot.AGENT_4 -> AgentState(
            config = AgentConfig(
                slot = AgentSlot.AGENT_4,
                name = "Agent 04 (Tester)",
                role = AgentRole(
                    type = AgentRoleType.TESTER,
                    title = "Independent Tester / Reviewer",
                    systemInstructions = "Independently review code, test edge cases, verify regressions, inspect security, check performance, and report bug findings.",
                    responsibilities = listOf(
                        "Run independent test suites",
                        "Inspect code security and regressions",
                        "Create bug reports for Team Lead"
                    ),
                ),
                providerId = "preset-gemini",
                modelId = "gemini-2.5-flash",
            ),
            activityState = AgentActivityState(
                activity = ActivityType.WAITING,
                currentTask = "Ready to test commits",
                lastAction = "Idle",
            ),
        )
    }

    private fun loadSavedTeamAgents(): List<AgentState> {
        val raw = teamPrefs.getString("agents_json", null)
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching {
                val arr = org.json.JSONArray(raw)
                val list = mutableListOf<AgentState>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val slot = AgentSlot.fromId(obj.getString("slot")) ?: continue
                    val base = defaultAgentTemplateForSlot(slot)
                    val roleType = runCatching { AgentRoleType.valueOf(obj.optString("roleType", base.config.role.type.name)) }
                        .getOrDefault(base.config.role.type)
                    val customPrompt = obj.optString("customPrompt", base.config.role.systemInstructions)
                    list.add(
                        base.copy(
                            config = base.config.copy(
                                name = obj.optString("name", base.config.name).ifBlank { base.config.name },
                                role = base.config.role.copy(
                                    type = roleType,
                                    title = roleType.defaultTitle,
                                    systemInstructions = customPrompt,
                                ),
                                providerId = obj.optString("providerId", base.config.providerId),
                                modelId = obj.optString("modelId", base.config.modelId),
                                isEnabled = obj.optBoolean("isEnabled", true),
                                temperature = obj.optDouble("temperature", 0.7).toFloat(),
                                maxTokens = obj.optInt("maxTokens", 8192),
                                customPrompt = customPrompt,
                            )
                        )
                    )
                }
                list.take(AgentSlot.MAX_AGENTS)
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        // Default: 2 AI agents (User can add up to 4 agents)
        return listOf(
            defaultAgentTemplateForSlot(AgentSlot.AGENT_1),
            defaultAgentTemplateForSlot(AgentSlot.AGENT_2),
        )
    }

    private fun saveTeamAgents(agents: List<AgentState>) {
        val arr = org.json.JSONArray()
        agents.take(AgentSlot.MAX_AGENTS).forEach { agent ->
            arr.put(
                org.json.JSONObject().apply {
                    put("slot", agent.config.slot.id)
                    put("name", agent.config.name)
                    put("roleType", agent.config.role.type.name)
                    put("providerId", agent.config.providerId)
                    put("modelId", agent.config.modelId)
                    put("isEnabled", agent.config.isEnabled)
                    put("temperature", agent.config.temperature.toDouble())
                    put("maxTokens", agent.config.maxTokens)
                    put("customPrompt", agent.config.customPrompt.ifBlank { agent.config.role.systemInstructions })
                }
            )
        }
        teamPrefs.edit().putString("agents_json", arr.toString()).apply()
    }

    private fun initMultiAgentTeam() {
        val initialAgents = loadSavedTeamAgents()

        val defaultTasks = listOf(
            TeamTask(
                partNumber = 1,
                title = "Inspect codebase and define architecture",
                description = "Analyze the shared workspace files, verify Gradle and source dependencies.",
                assignedSlot = AgentSlot.AGENT_1,
                status = TaskStatus.READY,
                acceptanceCriteria = listOf("Project builds cleanly", "No unresolved dependencies"),
            ),
            TeamTask(
                partNumber = 1,
                title = "Implement core UI and feature components",
                description = "Develop interface and screen logic in the shared project tree.",
                assignedSlot = AgentSlot.AGENT_2,
                status = TaskStatus.PLANNED,
                acceptanceCriteria = listOf("Components pass layout verification"),
            ),
            TeamTask(
                partNumber = 1,
                title = "Build backend services & data contracts",
                description = "Implement provider adapters and storage managers.",
                assignedSlot = AgentSlot.AGENT_3,
                status = TaskStatus.PLANNED,
                acceptanceCriteria = listOf("Data layer conforms to specifications"),
            ),
            TeamTask(
                partNumber = 1,
                title = "Execute test suite & regression checks",
                description = "Verify multi-agent concurrency, locks, and build output.",
                assignedSlot = AgentSlot.AGENT_4,
                status = TaskStatus.PLANNED,
                acceptanceCriteria = listOf("All unit and integration tests pass"),
            ),
        )
        defaultTasks.forEach { taskManager.addTask(it) }

        val activeTeamId = _state.value.activeTeamProject?.id
        val initialTeamMessages = if (activeTeamId != null) loadProjectTeamChat(activeTeamId).toList() else emptyList()
        val initialHasGitRemote = activeTeamId?.let { detectTeamProjectGitRemote(it) } ?: false
        val initialAutoPushGit = teamGitPrefs.getBoolean("auto_push_git_enabled", false)
        _state.update {
            it.copy(
                teamAgents = initialAgents,
                teamParts = taskManager.parts.value,
                teamTasks = taskManager.tasks.value,
                teamCurrentPartNumber = taskManager.currentPartNumber.value,
                providerConfigs = providerManager.configs.value,
                teamGroupMessages = initialTeamMessages,
                teamActiveProjectHasGitRemote = initialHasGitRemote,
                teamAutoPushGitEnabled = initialAutoPushGit,
            )
        }

        viewModelScope.launch {
            taskManager.tasks.collect { tasks -> _state.update { it.copy(teamTasks = tasks) } }
        }
        viewModelScope.launch {
            taskManager.parts.collect { parts -> _state.update { it.copy(teamParts = parts) } }
        }
        viewModelScope.launch {
            taskManager.currentPartNumber.collect { num -> _state.update { it.copy(teamCurrentPartNumber = num) } }
        }
        viewModelScope.launch {
            providerManager.configs.collect { configs -> _state.update { it.copy(providerConfigs = configs) } }
        }
        viewModelScope.launch {
            // Immediate + periodic quota & file lock refresh across ALL providers and Solo agent
            while (true) {
                refreshAllQuotas()
                delay(15_000L)
            }
        }
    }

    fun refreshAllQuotas(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (forceRefresh) {
                providerManager.invalidateQuotaCache()
            }
            fileLockManager.cleanExpiredLocks()
            val locks = fileLockManager.getActiveLocks()
            val configs = _state.value.providerConfigs
            val allQuotas = mutableMapOf<String, OfficialQuota>()
            val allEstimated = mutableMapOf<String, EstimatedUsage>()
            val allLocal = mutableMapOf<String, LocalUsage>()

            for (cfg in configs) {
                runCatching {
                    allQuotas[cfg.id] = providerManager.getOfficialQuota(cfg.id)
                    allEstimated[cfg.id] = providerManager.getEstimatedUsage(cfg.id)
                    allLocal[cfg.id] = providerManager.getLocalUsage(cfg.id)
                }
            }

            val primaryConfigId = configs.firstOrNull()?.id ?: "preset-antigravity"
            val soloProviderId = when {
                _state.value.agentKind == AgentKind.ANTIGRAVITY -> "preset-antigravity"
                _state.value.provider.kind == ProviderKind.ANTHROPIC -> "preset-anthropic"
                _state.value.provider.kind == ProviderKind.DEEPSEEK -> "preset-deepseek"
                _state.value.provider.kind == ProviderKind.LLM_ROUTER -> "preset-openrouter"
                else -> primaryConfigId
            }

            _state.update {
                it.copy(
                    teamFileLocks = locks,
                    teamOfficialQuota = allQuotas[primaryConfigId],
                    teamEstimatedUsage = allEstimated[primaryConfigId],
                    teamLocalUsage = allLocal[primaryConfigId] ?: LocalUsage(),
                    teamAllProviderQuotas = allQuotas,
                    teamAllProviderEstimated = allEstimated,
                    teamAllProviderLocal = allLocal,
                    soloOfficialQuota = allQuotas[soloProviderId] ?: allQuotas[primaryConfigId],
                    soloEstimatedUsage = allEstimated[soloProviderId] ?: allEstimated[primaryConfigId],
                    soloLocalUsage = allLocal[soloProviderId] ?: LocalUsage(),
                )
            }
        }
    }

    fun addTeamAgent() {
        val current = _state.value.teamAgents.toMutableList()
        if (current.size >= AgentSlot.MAX_AGENTS) return
        val usedSlots = current.map { it.config.slot }.toSet()
        val nextSlot = AgentSlot.entries.firstOrNull { it !in usedSlots } ?: return
        val newAgent = defaultAgentTemplateForSlot(nextSlot)
        current.add(newAgent)
        saveTeamAgents(current)
        _state.update { it.copy(teamAgents = current) }
        recordCollaborationEvent(
            type = TeamEventType.TASK_CREATED,
            senderSlot = nextSlot,
            message = "${newAgent.config.name} joined the AI Team (${current.size}/${AgentSlot.MAX_AGENTS})",
            priority = EventPriority.NORMAL,
        )
    }

    fun removeTeamAgent(slot: AgentSlot) {
        val current = _state.value.teamAgents.toMutableList()
        if (current.size <= 1) return
        val removed = current.firstOrNull { it.config.slot == slot } ?: return
        current.removeAll { it.config.slot == slot }
        fileLockManager.releaseAllForAgent(slot)
        saveTeamAgents(current)
        _state.update {
            it.copy(
                teamAgents = current,
                teamFileLocks = fileLockManager.getActiveLocks(),
                activePrivateSlot = if (it.activePrivateSlot == slot) current.firstOrNull()?.config?.slot else it.activePrivateSlot,
            )
        }
        recordCollaborationEvent(
            type = TeamEventType.FILE_UNLOCKED,
            senderSlot = slot,
            message = "${removed.config.name} removed from AI Team (${current.size}/${AgentSlot.MAX_AGENTS})",
            priority = EventPriority.NORMAL,
        )
    }

    fun updateSoloContinuousConfig(config: ContinuousExecutionConfig) {
        soloSupervisor.updateConfig(config)
        _state.update { it.copy(soloContinuousConfig = config) }
    }

    fun updateTeamContinuousConfig(config: ContinuousExecutionConfig) {
        continuousSupervisor.updateConfig(config)
        _state.update { it.copy(teamContinuousConfig = config) }
    }

    fun setTeamScreenMode(mode: TeamScreenMode) {
        _state.update { it.copy(teamScreenMode = mode) }
    }

    fun setActivePrivateSlot(slot: AgentSlot?) {
        _state.update { it.copy(activePrivateSlot = slot) }
    }

    private data class PendingStartConfirmation(
        val projectId: String,
        val proactiveObjective: String,
        val hasRecoverableTasks: Boolean,
    )
    private var pendingStartConfirmation: PendingStartConfirmation? = null

    fun startTeamWork() {
        val activeProj = _state.value.activeTeamProject
        if (activeProj == null) {
            _state.update { it.copy(toastMessage = str(R.string.team_no_projects_yet)) }
            return
        }
        val runningId = _state.value.runningTeamProjectId
        if (runningId != null && runningId != activeProj.id) {
            val runningName = _state.value.teamProjects.firstOrNull { it.id == runningId }?.name ?: "another project"
            _state.update { it.copy(toastMessage = str(R.string.team_locked_by_other_project, runningName)) }
            return
        }
        // Prevent duplicate simultaneous Start button presses for the same active project
        if (_state.value.teamIsRunning && runningId == activeProj.id && teamCycleJob?.isActive == true) {
            return
        }
        if (_state.value.teamAgents.none { it.config.isEnabled }) {
            _state.update {
                it.copy(
                    toastMessage = "Enable at least one AI agent before starting the team.",
                    teamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.WARNING,
                    teamBannerMessage = "No enabled AI agents configured",
                )
            }
            return
        }

        val nextDirective = pendingTeamDirectives.poll()
        _state.update { it.copy(teamPriorityDirectivesCount = pendingTeamDirectives.size) }
        if (!nextDirective.isNullOrBlank()) {
            pendingStartConfirmation = null
            _state.update { it.copy(teamAwaitingStartConfirmation = false) }
            submitAutonomousTeamObjective(nextDirective)
            return
        }

        // Supervisor Check-and-Confirm Flow:
        // When Owner presses Start, SPV inspects pending/unfinished tasks + workspace files,
        // reports them in Group Chat, and asks Owner to confirm ("โอเค / ทำต่อ") before delegating to the team!
        teamCycleJob?.cancel()
        teamCycleJob = viewModelScope.launch {
            val supName = agentNameFor(AgentSlot.AGENT_1)
            val ownerName = effectiveOwnerName()
            _state.update {
                it.copy(
                    teamTypingAgentName = supName,
                    teamTypingAgentSlot = AgentSlot.AGENT_1,
                )
            }
            try {
                val (pendingTasks, workspaceFiles) = withContext(Dispatchers.IO) {
                    val allTasks = runCatching { orqenTaskStore.listTasksForProject(activeProj.id) }.getOrDefault(emptyList())
                    val unfinished = allTasks.filter {
                        it.status != com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED &&
                            it.status != com.jarves.mh.multiagent.persistence.PersistentTaskStatus.CANCELLED
                    }
                    val workspaceDir = File(getApplication<Application>().filesDir, "team_workspaces/${activeProj.id}").apply { mkdirs() }
                    val realFiles = workspaceDir.walkTopDown()
                        .maxDepth(4)
                        .filter { f ->
                            f.isFile &&
                                f.name != ".orqen_chat.json" &&
                                !f.path.contains("/.git/") &&
                                !f.path.contains("/.orqen_uploads/") &&
                                !f.path.contains("/node_modules/") &&
                                !f.path.contains("/build/") &&
                                !f.path.contains("/.gradle/")
                        }
                        .map { it.relativeTo(workspaceDir).path }
                        .take(60)
                        .toList()
                    unfinished to realFiles
                }
                delay(280L)

                // Case 1: Brand-new EMPTY workspace (no pending tasks AND no real project files)
                if (pendingTasks.isEmpty() && workspaceFiles.isEmpty()) {
                    pendingStartConfirmation = null
                    val emptyPrompt = if (isThaiUiLanguage()) {
                        "สวัสดีครับ $ownerName! ผม ($supName) ตรวจสอบโปรเจกต์ '${activeProj.name}' แล้ว ตอนนี้โฟลเดอร์โปรเจกต์ยังว่างเปล่าอยู่ (ยังไม่มีไฟล์และยังไม่มีงานค้างครับ)\n\n" +
                            "คุณ$ownerName ต้องการให้ผมและทีมเริ่มสร้างอะไรดีครับ? พิมพ์รายละเอียดหรือสั่งงานในแชทนี้ได้เลยครับ"
                    } else {
                        "Hello $ownerName! I ($supName) checked '${activeProj.name}' — this workspace is completely empty (no files or pending tasks yet).\n\n" +
                            "What would you like me and the team to build? Just describe your idea or task right here in chat!"
                    }
                    _state.update {
                        it.copy(
                            teamAwaitingStartConfirmation = false,
                            teamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.RUNNING,
                            teamBannerMessage = if (isThaiUiLanguage()) {
                                "โปรเจกต์ยังว่างเปล่า — พิมพ์สั่งงานที่ต้องการให้ทีมสร้างได้เลยครับ"
                            } else {
                                "Empty workspace — tell the team what to build in chat"
                            },
                        )
                    }
                    appendProjectTeamMessage(
                        projectId = activeProj.id,
                        senderSlot = AgentSlot.AGENT_1,
                        senderName = supName,
                        text = emptyPrompt,
                        messageType = GroupMessageType.NORMAL,
                    )
                    return@launch
                }

                // Case 2 & 3: Either has pending tasks OR existing project files -> report & ask Owner to confirm!
                val proactiveObjective = "วิเคราะห์โปรเจกต์ '${activeProj.name}' จากไฟล์ทั้งหมดใน workspace ปัจจุบัน ทำความเข้าใจว่ากำลังพัฒนาอะไร ตรวจสอบส่วนที่ยังไม่สมบูรณ์หรือยังไม่ได้ทดสอบ จากนั้นวางแผนและพัฒนาต่อจนโปรเจกต์สมบูรณ์และผ่านการตรวจสอบ"
                val reportText = buildString {
                    appendLine("สวัสดีครับ $ownerName! ผม ($supName) ตรวจสอบโปรเจกต์ '${activeProj.name}' เรียบร้อยแล้วครับ:")
                    if (pendingTasks.isNotEmpty()) {
                        appendLine("พบงานค้างที่ยังไม่เสร็จ ${pendingTasks.size} รายการ:")
                        pendingTasks.take(5).forEachIndexed { idx, t ->
                            appendLine("${idx + 1}. ${t.title} [${t.status.name}]")
                        }
                    } else {
                        appendLine("ยังไม่มีคิวงานค้างในระบบ แต่พบไฟล์ในโปรเจกต์ปัจจุบัน (${workspaceFiles.size} ไฟล์):")
                        appendLine(workspaceFiles.take(15).joinToString("\n") { "• $it" })
                        appendLine("ผมสามารถวิเคราะห์โครงสร้างโปรเจกต์ วางแผน และให้ทีมพัฒนาต่อจนสมบูรณ์ได้ครับ")
                    }
                    appendLine()
                    append("ต้องการให้ผมและทีมดำเนินการทำงานต่อเลยไหมครับ? พิมพ์ \"โอเค\" หรือ \"ทำต่อ\" (หรือกดปุ่มด้านล่าง) เพื่อให้ผมสั่งงานทีมต่อทันทีครับ")
                }
                pendingStartConfirmation = PendingStartConfirmation(
                    projectId = activeProj.id,
                    proactiveObjective = proactiveObjective,
                    hasRecoverableTasks = pendingTasks.isNotEmpty(),
                )
                _state.update {
                    it.copy(
                        teamAwaitingStartConfirmation = true,
                        teamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.RUNNING,
                        teamBannerMessage = "รอเจ้าของบริษัทยืนยันการทำงานต่อในแชทกลุ่ม",
                    )
                }
                appendProjectTeamMessage(
                    projectId = activeProj.id,
                    senderSlot = AgentSlot.AGENT_1,
                    senderName = supName,
                    text = reportText,
                    messageType = GroupMessageType.NORMAL,
                    suggestedTaskPrompt = "โอเค ทำต่อเลย",
                )
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (err: Throwable) {
                android.util.Log.e("OrqenMainViewModel", "Start AI Team inspection failed for project ${activeProj.id}", err)
                val detail = err.message?.take(200) ?: err.javaClass.simpleName
                _state.update {
                    it.copy(
                        teamAwaitingStartConfirmation = false,
                        teamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.WARNING,
                        teamBannerMessage = "Start AI Team failed",
                        teamBannerDetail = detail,
                        toastMessage = "Start AI Team failed: $detail",
                    )
                }
            } finally {
                _state.update { it.copy(teamTypingAgentName = null, teamTypingAgentSlot = null) }
            }
        }
    }

    private fun confirmAndStartPendingTeamWork(projectId: String) {
        val pending = pendingStartConfirmation ?: return
        if (pending.projectId != projectId) return
        val activeProj = _state.value.teamProjects.firstOrNull { it.id == projectId } ?: _state.value.activeTeamProject ?: return
        pendingStartConfirmation = null
        _state.update { it.copy(teamAwaitingStartConfirmation = false) }

        teamCycleJob?.cancel()
        teamCycleJob = viewModelScope.launch {
            var serviceStarted = false
            val supName = agentNameFor(AgentSlot.AGENT_1)
            val ownerName = effectiveOwnerName()
            try {
                _state.update {
                    it.copy(
                        teamTypingAgentName = supName,
                        teamTypingAgentSlot = AgentSlot.AGENT_1,
                    )
                }
                delay(240L)
                appendProjectTeamMessage(
                    projectId = activeProj.id,
                    senderSlot = AgentSlot.AGENT_1,
                    senderName = supName,
                    text = "รับทราบครับ $ownerName! ผมจะสั่งการและกระจายงานให้ทีมดำเนินการต่อทันทีครับ",
                    messageType = GroupMessageType.TASK_ASSIGNMENT,
                )
                _state.update { it.copy(teamTypingAgentName = null, teamTypingAgentSlot = null) }

                ensureSupervisorSnapshotCollector()
                val team = withContext(Dispatchers.IO) {
                    syncTeamProfilesForProject(activeProj)
                }
                _state.update {
                    it.copy(
                        teamIsRunning = true,
                        runningTeamProjectId = activeProj.id,
                        teamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.RUNNING,
                        teamBannerMessage = null,
                        teamBannerDetail = null,
                    )
                }
                continuousSupervisor.startContinuous()
                startTeamForegroundService(activeProj.name, "${activeProj.name} · Autonomous Supervisor active")
                serviceStarted = true
                runCatching {
                    com.jarves.mh.runtime.TaskRecoveryJobService.scheduleReconciliation(
                        context = getApplication(),
                        taskId = activeProj.id,
                    )
                }.onFailure { err ->
                    android.util.Log.w("OrqenMainViewModel", "Failed to schedule TaskRecoveryJobService", err)
                }
                val reconciled = withContext(Dispatchers.IO) {
                    autonomousSupervisorEngine.reconcileActiveTasksOnce(team)
                }
                if (reconciled.isEmpty()) {
                    val result = withContext(Dispatchers.IO) {
                        autonomousSupervisorEngine.submitAndSupervise(
                            projectId = activeProj.id,
                            team = team,
                            objective = pending.proactiveObjective,
                            onTaskCreated = { created ->
                                activeSupervisedTaskId = created.taskId
                                _state.update { it.copy(activeSupervisedTaskId = created.taskId) }
                            },
                        )
                    }
                    if (result != null) {
                        activeSupervisedTaskId = result.taskId
                        _state.update { it.copy(activeSupervisedTaskId = result.taskId) }
                        appendProjectTeamMessage(
                            projectId = activeProj.id,
                            senderSlot = AgentSlot.AGENT_1,
                            senderName = supName,
                            text = formatTeamTaskCompletionMessage(activeProj.name, result.title, result.taskId, result.status, result.lastError),
                            messageType = if (result.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED) {
                                GroupMessageType.COMPLETION_REPORT
                            } else {
                                GroupMessageType.PROGRESS_UPDATE
                            },
                        )
                        if (result.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED) {
                            notifyTeamTaskCompleted(activeProj.name, formatTeamTaskCompletionNotifDetail(activeProj.name, result.title))
                        }
                    }
                    withContext(Dispatchers.IO) {
                        autonomousSupervisorEngine.superviseProjectQueue(
                            projectId = activeProj.id,
                            team = team,
                            onTaskStarted = { queued ->
                                activeSupervisedTaskId = queued.taskId
                                _state.update { it.copy(activeSupervisedTaskId = queued.taskId) }
                                updateTeamForegroundNotification(activeProj.name, "Continuing queued task: ${queued.title.take(50)}")
                            },
                            onTaskFinished = { finished ->
                                appendProjectTeamMessage(
                                    projectId = activeProj.id,
                                    senderSlot = AgentSlot.AGENT_1,
                                    senderName = supName,
                                    text = formatTeamTaskCompletionMessage(activeProj.name, finished.title, finished.taskId, finished.status, finished.lastError),
                                    messageType = if (finished.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED) {
                                        GroupMessageType.COMPLETION_REPORT
                                    } else {
                                        GroupMessageType.PROGRESS_UPDATE
                                    },
                                )
                                if (finished.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED) {
                                    notifyTeamTaskCompleted(activeProj.name, formatTeamTaskCompletionNotifDetail(activeProj.name, finished.title))
                                }
                            },
                        )
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (err: Throwable) {
                android.util.Log.e("OrqenMainViewModel", "Confirmed AI Team execution failed for project ${activeProj.id}", err)
                val detail = err.message?.take(200) ?: err.javaClass.simpleName
                _state.update {
                    it.copy(
                        teamIsRunning = false,
                        runningTeamProjectId = null,
                        teamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.WARNING,
                        teamBannerMessage = "Start AI Team failed",
                        teamBannerDetail = detail,
                        toastMessage = "Start AI Team failed: $detail",
                    )
                }
            } finally {
                _state.update { it.copy(teamIsRunning = false, runningTeamProjectId = null, teamTypingAgentName = null, teamTypingAgentSlot = null) }
                updateAgentActivities(ActivityType.WAITING)
                if (serviceStarted) {
                    stopTeamForegroundService()
                }
            }
        }
    }

    private fun isThaiUiLanguage(): Boolean {
        val code = _state.value.languageCode.lowercase()
        return code == "th" || (code == "system" && java.util.Locale.getDefault().language.equals("th", ignoreCase = true))
    }

    private fun formatTeamTaskCompletionMessage(
        projectName: String,
        taskTitle: String,
        taskId: String,
        status: com.jarves.mh.multiagent.persistence.PersistentTaskStatus,
        lastError: String?,
    ): String = if (status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED) {
        if (isThaiUiLanguage()) {
            "งานเสร็จสมบูรณ์แล้ว! โปรเจกต์ '$projectName' ดำเนินการ '${taskTitle}' เสร็จเรียบร้อยครับ ($taskId)"
        } else {
            "Task Completed! Project '$projectName' finished '${taskTitle}' ($taskId)"
        }
    } else {
        if (isThaiUiLanguage()) {
            "สถานะงานโปรเจกต์ '$projectName' [${status.name}] ($taskId): ${lastError ?: taskTitle}"
        } else {
            "Project '$projectName' status [${status.name}] ($taskId): ${lastError ?: taskTitle}"
        }
    }

    private fun formatTeamTaskCompletionNotifDetail(projectName: String, taskTitle: String): String =
        if (isThaiUiLanguage()) {
            "โปรเจกต์ '$projectName' ทำงานเสร็จสมบูรณ์แล้ว: $taskTitle"
        } else {
            "Project '$projectName' completed: $taskTitle"
        }

    fun pauseTeamWork() {
        teamCycleJob?.cancel()
        teamCycleJob = null
        pendingStartConfirmation = null
        activeSupervisedTaskId?.let { tid ->
            orqenTaskStore.getTask(tid)?.let { task ->
                orqenTaskStore.upsertTask(
                    task.copy(
                        pauseRequested = true,
                        status = com.jarves.mh.multiagent.persistence.PersistentTaskStatus.PAUSED,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        _state.update { it.copy(teamIsRunning = false, teamIsPaused = true, teamAwaitingStartConfirmation = false) }
        continuousSupervisor.pauseContinuous("User paused team")
        stopTeamForegroundService()
        updateAgentActivities(ActivityType.PAUSED)
    }

    fun resumeTeamWork() {
        val activeProj = _state.value.activeTeamProject
        val tid = activeSupervisedTaskId ?: activeProj?.let { proj ->
            orqenTaskStore.listTasksForProject(proj.id)
                .firstOrNull { it.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.PAUSED && !it.ownerStopRequested }
                ?.taskId
        }
        if (tid != null && activeProj != null) {
            activeSupervisedTaskId = tid
            teamCycleJob?.cancel()
            teamCycleJob = viewModelScope.launch {
                var serviceStarted = false
                try {
                    val task = withContext(Dispatchers.IO) { orqenTaskStore.getTask(tid) }
                    if (task != null && !task.ownerStopRequested && task.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.PAUSED) {
                        val team = withContext(Dispatchers.IO) {
                            orqenTaskStore.upsertTask(
                                task.copy(
                                    pauseRequested = false,
                                    status = com.jarves.mh.multiagent.persistence.PersistentTaskStatus.QUEUED,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                            syncTeamProfilesForProject(activeProj)
                        }
                        _state.update { it.copy(teamIsRunning = true, teamIsPaused = false, runningTeamProjectId = activeProj.id) }
                        startTeamForegroundService(activeProj.name, "Resuming autonomous task $tid")
                        serviceStarted = true
                        withContext(Dispatchers.IO) {
                            autonomousSupervisorEngine.superviseTask(tid, team)
                        }
                    } else {
                        _state.update { it.copy(teamIsPaused = false) }
                        startTeamWork()
                    }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (err: Throwable) {
                    android.util.Log.e("OrqenMainViewModel", "Resume AI Team failed for task $tid", err)
                    val detail = err.message?.take(200) ?: err.javaClass.simpleName
                    _state.update {
                        it.copy(
                            teamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.WARNING,
                            teamBannerMessage = "Resume AI Team failed",
                            teamBannerDetail = detail,
                            toastMessage = "Resume AI Team failed: $detail",
                        )
                    }
                } finally {
                    _state.update { it.copy(teamIsRunning = false, runningTeamProjectId = null) }
                    if (serviceStarted) {
                        stopTeamForegroundService()
                    }
                }
            }
            return
        }
        _state.update { it.copy(teamIsPaused = false) }
        startTeamWork()
    }

    fun stopTeamWork() {
        teamCycleJob?.cancel()
        teamCycleJob = null
        pendingStartConfirmation = null
        pendingTeamDirectives.clear()
        activeSupervisedTaskId?.let { tid ->
            orqenTaskStore.getTask(tid)?.let { task ->
                orqenTaskStore.upsertTask(
                    task.copy(
                        ownerStopRequested = true,
                        pauseRequested = false,
                        status = com.jarves.mh.multiagent.persistence.PersistentTaskStatus.CANCELLED,
                        executionLeaseOwner = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            com.jarves.mh.runtime.TaskRecoveryJobService.cancelTaskRecovery(getApplication(), tid)
            mcpConnectionManager.authEngine.revokeGrantsForTask(
                taskId = tid,
                reason = "Task stopped by owner",
            )
            mcpConnectionManager.refreshStateFlows()
        }
        activeSupervisedTaskId = null
        _state.value.activeTeamProject?.id?.let { projId ->
            com.jarves.mh.runtime.TaskRecoveryJobService.cancelTaskRecovery(getApplication(), projId)
        }
        _state.update {
            it.copy(
                teamIsRunning = false,
                teamIsPaused = false,
                teamAwaitingStartConfirmation = false,
                runningTeamProjectId = null,
                teamPriorityDirectivesCount = 0,
            )
        }
        continuousSupervisor.stopContinuous("User stopped team")
        stopTeamForegroundService()
        AgentSlot.entries.forEach { fileLockManager.releaseAllForAgent(it) }
        updateAgentActivities(ActivityType.WAITING)
        _state.update { it.copy(teamFileLocks = fileLockManager.getActiveLocks()) }
    }

    private fun ensureSupervisorSnapshotCollector() {
        if (supervisorSnapshotJob?.isActive == true) return
        supervisorSnapshotJob = viewModelScope.launch {
            autonomousSupervisorEngine.snapshots.collect { snaps ->
                _state.update { it.copy(supervisorSnapshots = snaps) }
            }
        }
    }

    private fun syncTeamProfilesForProject(project: Project): com.jarves.mh.multiagent.collab.AgentTeamEntity {
        val wsDir = File(File(getApplication<Application>().filesDir, "team_workspaces"), project.id)
            .apply { mkdirs() }
            .canonicalFile
        orqenTaskStore.upsertProject(
            com.jarves.mh.multiagent.persistence.ProjectEntity(
                projectId = project.id,
                projectName = project.name,
                workspacePath = wsDir.absolutePath,
            ),
        )
        // Self-heal any legacy "default-team-project" row with empty workspacePath (which caused PRoot to bind-mount "/")
        val defaultProjId = com.jarves.mh.multiagent.tasks.TaskManager.DEFAULT_PROJECT_ID
        orqenTaskStore.getProject(defaultProjId)?.let { defProj ->
            if (defProj.workspacePath.isBlank() || defProj.workspacePath == "/") {
                orqenTaskStore.upsertProject(defProj.copy(workspacePath = wsDir.absolutePath))
            }
        }
        val registered = _state.value.teamAgents.map { state ->
            autonomousSupervisorEngine.registry.register(
                com.jarves.mh.multiagent.collab.AgentProfileMapper.fromAgentConfig(state.config),
            )
        }
        val enabledCount = registered.count { it.isEnabled }
        return autonomousSupervisorEngine.teamManager.createTeam(
            projectId = project.id,
            teamName = project.name,
            memberAgentIds = registered.map { it.agentId },
            requireIndependentReview = true,
            maxConcurrentAgents = enabledCount.coerceIn(1, 4), // Safe parallel execution across multiple coding agents via FileLockManager
        )
    }

    /**
     * Authoritative Part A.4 production entry point:
     * User submits an objective -> MainViewModel -> AutonomousSupervisorEngine -> Persistent SQLite Task
     * -> Real Provider/CLI Execution -> Watchdog -> Recovery/Failover -> Verification -> Completion.
     */
    fun submitAutonomousTeamObjective(
        objective: String,
        requiredDeliverableFiles: List<String> = emptyList(),
        attachmentPaths: List<String> = emptyList(),
    ) {
        val activeProj = _state.value.activeTeamProject ?: return
        val cleanObjective = objective.trim()
        if (cleanObjective.isBlank()) return
        if (_state.value.teamAgents.none { it.config.isEnabled }) {
            _state.update {
                it.copy(
                    toastMessage = "Enable at least one AI agent before submitting a team task.",
                    teamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.WARNING,
                    teamBannerMessage = "No enabled AI agents configured",
                )
            }
            return
        }

        teamCycleJob?.cancel()
        teamCycleJob = viewModelScope.launch {
            var serviceStarted = false
            try {
                ensureSupervisorSnapshotCollector()
                val team = withContext(Dispatchers.IO) {
                    syncTeamProfilesForProject(activeProj)
                }
                _state.update {
                    it.copy(
                        teamIsRunning = true,
                        runningTeamProjectId = activeProj.id,
                        teamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.RUNNING,
                        teamBannerMessage = null,
                        teamBannerDetail = null,
                    )
                }
                startTeamForegroundService(activeProj.name, "Autonomous Supervisor: ${cleanObjective.take(60)}")
                serviceStarted = true
                runCatching {
                    com.jarves.mh.runtime.TaskRecoveryJobService.scheduleReconciliation(
                        context = getApplication(),
                        taskId = activeProj.id,
                    )
                }.onFailure { err ->
                    android.util.Log.w("OrqenMainViewModel", "Failed to schedule TaskRecoveryJobService", err)
                }
                val wsDirForBrief = File(getApplication<Application>().filesDir, "team_workspaces/${activeProj.id}").apply { mkdirs() }
                val existingTasksForBrief = withContext(Dispatchers.IO) {
                    orqenTaskStore.listTasksForProject(activeProj.id)
                }
                val lowerObj = cleanObjective.lowercase()
                val isResumeDirective = listOf(
                    "ทำงานต่อ", "ทำต่อ", "ต่อจากเดิม", "ต่อเลย", "ลุยต่อ",
                    "continue", "resume", "keep going", "start building this based on our finalized discussion",
                ).any { lowerObj.contains(it) }
                val resumableTask = if (isResumeDirective) {
                    existingTasksForBrief.firstOrNull { t ->
                        t.status in setOf(
                            com.jarves.mh.multiagent.persistence.PersistentTaskStatus.PAUSED,
                            com.jarves.mh.multiagent.persistence.PersistentTaskStatus.BLOCKED,
                            com.jarves.mh.multiagent.persistence.PersistentTaskStatus.READY,
                            com.jarves.mh.multiagent.persistence.PersistentTaskStatus.QUEUED,
                        ) && withContext(Dispatchers.IO) { orqenTaskStore.listStepsForTask(t.taskId).isNotEmpty() }
                    }
                } else null

                // Real AI-authored SPV Workspace Inspection & Planning Briefing (never a robotic template!)
                val spvBriefingText = generateSpvAiBriefing(
                    project = activeProj,
                    workspaceDir = wsDirForBrief,
                    phase = "START_AND_PLAN",
                    objective = cleanObjective,
                    existingTask = resumableTask ?: existingTasksForBrief.firstOrNull(),
                )
                if (!spvBriefingText.isNullOrBlank()) {
                    spvAiBriefedCurrentSubmission = true
                    appendProjectTeamMessage(
                        projectId = activeProj.id,
                        senderSlot = AgentSlot.AGENT_1,
                        senderName = agentNameFor(AgentSlot.AGENT_1),
                        text = spvBriefingText,
                        messageType = GroupMessageType.TASK_ASSIGNMENT,
                        workStatus = "Working",
                    )
                }

                val result = withContext(Dispatchers.IO) {
                    if (resumableTask != null) {
                        // Resume existing task directly without creating a duplicate plan!
                        orqenTaskStore.runInTransaction {
                            orqenTaskStore.listStepsForTask(resumableTask.taskId).forEach { st ->
                                if (st.status == com.jarves.mh.multiagent.persistence.StepStatus.BLOCKED ||
                                    st.status == com.jarves.mh.multiagent.persistence.StepStatus.FAILED ||
                                    st.status == com.jarves.mh.multiagent.persistence.StepStatus.RUNNING
                                ) {
                                    orqenTaskStore.upsertStep(
                                        st.copy(
                                            status = com.jarves.mh.multiagent.persistence.StepStatus.READY,
                                            retryCount = 0,
                                        ),
                                    )
                                }
                            }
                            orqenTaskStore.upsertTask(
                                resumableTask.copy(
                                    status = com.jarves.mh.multiagent.persistence.PersistentTaskStatus.READY,
                                    pauseRequested = false,
                                    ownerStopRequested = false,
                                    lastError = null,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        activeSupervisedTaskId = resumableTask.taskId
                        _state.update { it.copy(activeSupervisedTaskId = resumableTask.taskId) }
                        if (spvBriefingText.isNullOrBlank()) {
                            appendProjectTeamMessage(
                                projectId = activeProj.id,
                                senderSlot = AgentSlot.AGENT_1,
                                senderName = agentNameFor(AgentSlot.AGENT_1),
                                text = "รับทราบครับ กำลังทำงานต่อจากจุดที่ค้างไว้ (${resumableTask.taskId}: ${resumableTask.title}) ทันทีครับ",
                                messageType = GroupMessageType.PROGRESS_UPDATE,
                                workStatus = "Working",
                            )
                        }
                        autonomousSupervisorEngine.superviseTask(
                            taskId = resumableTask.taskId,
                            team = team,
                            requiredDeliverableFiles = requiredDeliverableFiles,
                        )
                    } else {
                        // Feed SPV's reorganized, synthesized engineering plan directly to the execution DAG
                        // so Dev receives a clean structured plan rather than raw unorganized chat transcripts!
                        val structuredObjective = if (!spvBriefingText.isNullOrBlank()) {
                            "$cleanObjective\n\n--- SPV SYNTHESIZED PLAN ---\n${spvBriefingText.take(2_400)}"
                        } else cleanObjective
                        autonomousSupervisorEngine.submitAndSupervise(
                            projectId = activeProj.id,
                            team = team,
                            objective = structuredObjective,
                            requiredDeliverableFiles = requiredDeliverableFiles,
                            attachmentPaths = attachmentPaths,
                            onTaskCreated = { created ->
                                activeSupervisedTaskId = created.taskId
                                _state.update { it.copy(activeSupervisedTaskId = created.taskId) }
                            },
                        )
                    }
                }
                spvAiBriefedCurrentSubmission = false
                if (result != null) {
                    activeSupervisedTaskId = result.taskId
                    _state.update { it.copy(activeSupervisedTaskId = result.taskId) }
                    val wsDir = File(getApplication<Application>().filesDir, "team_workspaces/${activeProj.id}")
                    val apkBuildResult = if (result.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED) {
                        buildAndLocateTeamAndroidApk(activeProj)
                    } else TeamApkBuildResult()
                    val builtApk = apkBuildResult.apkFile
                    val webEntryRel = if (result.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED && builtApk == null) {
                        listOf("index.html", "dist/index.html", "build/index.html", "public/index.html", "src/index.html")
                            .firstOrNull { File(wsDir, it).isFile }
                    } else null
                    val taskStepsForSummary = withContext(Dispatchers.IO) {
                        orqenTaskStore.listStepsForTask(result.taskId)
                    }
                    val checkpointModifiedFiles = withContext(Dispatchers.IO) {
                        orqenTaskStore.listCheckpointsForTask(result.taskId)
                            .flatMap { it.modifiedFiles }
                            .filter { it.isNotBlank() && !it.startsWith(".orqen/") }
                            .distinct()
                    }
                    val deliveredFiles = if (result.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED) {
                        val recentWsFiles = wsDir.walkTopDown()
                            .maxDepth(5)
                            .filter { f ->
                                f.isFile &&
                                    !f.path.contains("/.git/") &&
                                    !f.path.contains("/.gradle/") &&
                                    !f.path.contains("/build/") &&
                                    !f.path.contains("/.orqen/") &&
                                    f.name != ".orqen_chat.json"
                            }
                            .sortedByDescending { it.lastModified() }
                            .take(6)
                            .mapNotNull { runCatching { it.relativeTo(wsDir).path }.getOrNull() }
                            .toList()
                        (checkpointModifiedFiles + recentWsFiles).distinct().take(8)
                    } else emptyList()

                    val aiFinalSummary = if (result.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED) {
                        generateSpvAiBriefing(
                            project = activeProj,
                            workspaceDir = wsDir,
                            phase = "FINAL_DELIVERY",
                            objective = result.title,
                            existingTask = result,
                            deliveredFiles = deliveredFiles,
                            builtApkName = builtApk?.name,
                            builtApkRelPath = apkBuildResult.relativePath,
                            apkBuildError = apkBuildResult.buildError,
                            webEntryRel = webEntryRel,
                        )
                    } else null
                    val deterministicSummaryDetails = buildString {
                        val completedSteps = taskStepsForSummary.filter {
                            it.status == com.jarves.mh.multiagent.persistence.StepStatus.COMPLETED
                        }
                        if (completedSteps.isNotEmpty()) {
                            appendLine("\nสรุปสิ่งที่ดำเนินการและแก้ไข:")
                            completedSteps.take(6).forEach { st ->
                                val cleanNote = st.outputSummary
                                    .replace("REVIEW_APPROVED", "")
                                    .replace("TESTS_PASSED", "")
                                    .trim()
                                    .replace(Regex("\\s+"), " ")
                                    .take(180)
                                appendLine("- ${st.title}${if (cleanNote.isNotBlank()) ": $cleanNote" else ""}")
                            }
                        }
                        if (deliveredFiles.isNotEmpty()) {
                            appendLine("\nไฟล์ที่แก้ไข/อัปเดต:")
                            deliveredFiles.take(6).forEach { f -> appendLine("- `$f`") }
                        }
                        if (builtApk != null) {
                            val displayApkRel = apkBuildResult.relativePath ?: "app/build/outputs/apk/debug/app-debug.apk"
                            appendLine("\nไฟล์ APK พร้อมติดตั้ง: `$displayApkRel` (กดปุ่มติดตั้ง APK ด้านล่างได้เลย)")
                        } else if (!apkBuildResult.buildError.isNullOrBlank()) {
                            appendLine("\nหมายเหตุการบิลด์ APK: ไม่สามารถสร้าง APK อัตโนมัติได้ (${apkBuildResult.buildError.take(240)})")
                        }
                    }.trim()
                    val completionMsg = aiFinalSummary?.let { aiText ->
                        val needsApkNote = builtApk != null && !aiText.contains("apk", ignoreCase = true)
                        val needsErrNote = builtApk == null && !apkBuildResult.buildError.isNullOrBlank() && !aiText.contains("apk", ignoreCase = true)
                        buildString {
                            append(aiText.trim())
                            if (needsApkNote) {
                                append("\n\nไฟล์ APK พร้อมติดตั้ง: `${apkBuildResult.relativePath ?: "app/build/outputs/apk/debug/app-debug.apk"}` (กดปุ่มติดตั้งด้านล่างได้เลยครับ)")
                            } else if (needsErrNote) {
                                append("\n\nหมายเหตุการบิลด์ APK: บิลด์ไม่สำเร็จ (${apkBuildResult.buildError!!.take(220)})")
                            }
                        }
                    } ?: when {
                        result.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED && builtApk != null ->
                            "งานในโปรเจกต์ '${activeProj.name}' เสร็จสมบูรณ์ ตรวจสอบผ่าน และบิลด์ Debug APK เรียบร้อยแล้วครับ!\n$deterministicSummaryDetails"
                        result.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED && webEntryRel != null ->
                            "เว็บโปรเจกต์ '${activeProj.name}' ดำเนินการและตรวจสอบเสร็จสมบูรณ์แล้วครับ! กดเปิดดู Preview ด้านล่างหรือแตะไฟล์ `$webEntryRel` ได้เลย\n$deterministicSummaryDetails"
                        result.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED ->
                            "โปรเจกต์ '${activeProj.name}' ดำเนินการและตรวจสอบเสร็จสมบูรณ์แล้วครับ!\n$deterministicSummaryDetails"
                        else -> formatTeamTaskCompletionMessage(activeProj.name, result.title, result.taskId, result.status, result.lastError)
                    }
                    val completionWorkDetails = buildList {
                        if (webEntryRel != null) add("PREVIEW_READY")
                        if (builtApk != null) add("APK: ${apkBuildResult.relativePath ?: "apk/${builtApk.name}"}")
                        deliveredFiles.forEach { add("File: $it") }
                    }
                    appendProjectTeamMessage(
                        projectId = activeProj.id,
                        senderSlot = AgentSlot.AGENT_1,
                        senderName = agentNameFor(AgentSlot.AGENT_1),
                        text = completionMsg,
                        messageType = if (result.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED) {
                            GroupMessageType.COMPLETION_REPORT
                        } else {
                            GroupMessageType.PROGRESS_UPDATE
                        },
                        workStatus = if (result.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED) "Done" else null,
                        workDetails = completionWorkDetails,
                        apkFilePath = builtApk?.absolutePath,
                    )
                    if (result.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED) {
                        // Archive any older stale QUEUED/BLOCKED/READY tasks for this project so they
                        // never re-run and spam [BLOCKED] banners right after the project completes!
                        withContext(Dispatchers.IO) {
                            orqenTaskStore.listTasksForProject(activeProj.id)
                                .filter { old ->
                                    old.taskId != result.taskId && old.status in setOf(
                                        com.jarves.mh.multiagent.persistence.PersistentTaskStatus.QUEUED,
                                        com.jarves.mh.multiagent.persistence.PersistentTaskStatus.READY,
                                        com.jarves.mh.multiagent.persistence.PersistentTaskStatus.BLOCKED,
                                    )
                                }
                                .forEach { old ->
                                    orqenTaskStore.upsertTask(
                                        old.copy(
                                            status = com.jarves.mh.multiagent.persistence.PersistentTaskStatus.CANCELLED,
                                            ownerStopRequested = true,
                                            lastError = "Superseded by completed task ${result.taskId}",
                                            updatedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                        }
                        val notifSummary = when {
                            builtApk != null -> "สร้างแอป '${activeProj.name}' เสร็จสมบูรณ์แล้ว พร้อมติดตั้ง APK ได้ทันที"
                            webEntryRel != null -> "สร้างเว็บ '${activeProj.name}' เสร็จสมบูรณ์แล้ว กดเปิดดู Preview ได้เลย"
                            else -> formatTeamTaskCompletionNotifDetail(activeProj.name, result.title)
                        }
                        notifyTeamTaskCompleted(activeProj.name, notifSummary)
                        if (_state.value.teamAutoPushGitEnabled) {
                            pushTeamProjectToGit(isAuto = true)
                        }
                    }
                }
                // Phase 1 (Part A.5.1): Automatically continue with any remaining queued tasks for this project!
                withContext(Dispatchers.IO) {
                    autonomousSupervisorEngine.superviseProjectQueue(
                        projectId = activeProj.id,
                        team = team,
                        onTaskStarted = { queued ->
                            activeSupervisedTaskId = queued.taskId
                            _state.update { it.copy(activeSupervisedTaskId = queued.taskId) }
                            updateTeamForegroundNotification(activeProj.name, "Continuing queued task: ${queued.title.take(50)}")
                        },
                        onTaskFinished = { finished ->
                            appendProjectTeamMessage(
                                projectId = activeProj.id,
                                senderSlot = AgentSlot.AGENT_1,
                                senderName = agentNameFor(AgentSlot.AGENT_1),
                                text = formatTeamTaskCompletionMessage(activeProj.name, finished.title, finished.taskId, finished.status, finished.lastError),
                                messageType = if (finished.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED) {
                                    GroupMessageType.COMPLETION_REPORT
                                } else {
                                    GroupMessageType.PROGRESS_UPDATE
                                },
                            )
                            if (finished.status == com.jarves.mh.multiagent.persistence.PersistentTaskStatus.COMPLETED) {
                                notifyTeamTaskCompleted(activeProj.name, formatTeamTaskCompletionNotifDetail(activeProj.name, finished.title))
                            }
                        },
                    )
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (err: Throwable) {
                android.util.Log.e("OrqenMainViewModel", "Autonomous Supervisor execution failed", err)
                val detail = err.message?.take(200) ?: err.javaClass.simpleName
                _state.update {
                    it.copy(
                        teamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.WARNING,
                        teamBannerMessage = "AI Team execution failed",
                        teamBannerDetail = detail,
                        toastMessage = "AI Team error: $detail",
                    )
                }
            } finally {
                _state.update { it.copy(teamIsRunning = false, runningTeamProjectId = null, teamTypingAgentName = null, teamTypingAgentSlot = null) }
                updateAgentActivities(ActivityType.WAITING)
                if (serviceStarted) {
                    stopTeamForegroundService()
                }
            }
        }
    }

    fun clearTeamChat() {
        val projectId = _state.value.activeTeamProject?.id
        if (projectId != null) {
            teamProjectChats[projectId]?.clear()
            persistProjectTeamChat(projectId, emptyList())
        }
        teamChatManager.clearHistory()
        _state.update { it.copy(teamGroupMessages = emptyList()) }
    }

    @Volatile
    private var teamForegroundServiceActive: Boolean = false

    private fun buildCombinedTeamNotificationStatus(overrideDetail: String? = null): String {
        val state = _state.value
        val isThai = isThaiUiLanguage()
        val lines = mutableListOf<String>()

        // 1. Agents currently replying in normal Team Chat
        val typingNames = state.teamTypingAgents.values.distinct()
        if (typingNames.isNotEmpty()) {
            val joined = typingNames.joinToString(", ")
            lines += if (isThai) "$joined กำลังตอบแชท..." else "$joined replying in chat..."
        }

        // 2. Agents currently coding / reviewing / building in Team Task Mode
        if (state.teamIsRunning) {
            val workingAgents = state.teamAgents.filter { ag ->
                ag.config.isEnabled &&
                    ag.activityState.activity != ActivityType.WAITING &&
                    ag.activityState.activity != ActivityType.COMPLETED &&
                    ag.activityState.activity != ActivityType.ERROR
            }
            workingAgents.forEach { ag ->
                val actLabel = when (ag.activityState.activity) {
                    ActivityType.WRITING_CODE -> if (isThai) "กำลังเขียนโค้ด" else "Writing code"
                    ActivityType.REVIEWING -> if (isThai) "กำลังตรวจโค้ด/QA" else "Reviewing/QA"
                    ActivityType.BUILDING_APK -> if (isThai) "กำลังบิลด์ APK" else "Building APK"
                    ActivityType.FIXING_BUG -> if (isThai) "กำลังแก้บั๊ก" else "Fixing bug"
                    ActivityType.ANALYZING -> if (isThai) "กำลังวิเคราะห์" else "Analyzing"
                    ActivityType.PLANNING -> if (isThai) "กำลังวางแผน" else "Planning"
                    ActivityType.TESTING -> if (isThai) "กำลังทดสอบ" else "Testing"
                    else -> if (isThai) "กำลังทำงาน" else "Working"
                }
                val taskHint = ag.activityState.currentTask.ifBlank { ag.activityState.lastAction }.take(55)
                lines += if (taskHint.isNotBlank()) {
                    "${ag.config.name} ($actLabel): $taskHint"
                } else {
                    "${ag.config.name}: $actLabel"
                }
            }
        }

        if (!overrideDetail.isNullOrBlank()) {
            val trimmed = overrideDetail.trim()
            if (lines.none { it.contains(trimmed.take(28), ignoreCase = true) }) {
                lines += trimmed
            }
        }
        return lines.distinct().take(4).joinToString("\n").ifBlank {
            if (isThai) "ORQEN AI กำลังทำงานในพื้นหลัง..." else "ORQEN AI working in background..."
        }
    }

    private fun syncTeamForegroundNotification(projectName: String? = null, overrideDetail: String? = null) {
        val state = _state.value
        val hasActiveChat = state.teamTypingAgents.isNotEmpty() || activeTeamChatJobs.isNotEmpty()
        val hasActiveWork = state.teamIsRunning
        val projName = projectName ?: state.activeTeamProject?.name ?: "ORQEN"
        if (!hasActiveChat && !hasActiveWork) {
            if (teamForegroundServiceActive) {
                teamForegroundServiceActive = false
                val app = getApplication<Application>()
                runCatching {
                    app.startService(
                        Intent(app, RuntimeExecutionService::class.java)
                            .setAction(RuntimeExecutionService.ACTION_CANCELLED),
                    )
                }
            }
            return
        }
        val detail = buildCombinedTeamNotificationStatus(overrideDetail)
        if (!teamForegroundServiceActive) {
            startTeamForegroundService(projName, detail)
        } else {
            val app = getApplication<Application>()
            runCatching {
                app.startService(
                    Intent(app, RuntimeExecutionService::class.java)
                        .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                        .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projName)
                        .putExtra(RuntimeExecutionService.EXTRA_TITLE, str(R.string.app_name))
                        .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail)
                        .putExtra(RuntimeExecutionService.EXTRA_CAN_STOP, true),
                )
            }
        }
    }

    private fun startTeamForegroundService(projectName: String, statusDetail: String) {
        val app = getApplication<Application>()
        teamForegroundServiceActive = true
        com.jarves.mh.runtime.RuntimeTaskController.stopAction = {
            activeTeamChatJobs.keys.toList().forEach { cancelTeamChatAgent(it) }
            if (_state.value.teamIsRunning) stopTeamWork()
        }
        com.jarves.mh.runtime.RuntimeTaskController.pauseAction = { pauseTeamWork() }
        com.jarves.mh.runtime.RuntimeTaskController.resumeAction = { resumeTeamWork() }
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                app,
                Intent(app, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_START)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_TITLE, str(R.string.app_name))
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, statusDetail)
                    .putExtra(RuntimeExecutionService.EXTRA_CAN_STOP, true),
            )
        }.onFailure { err ->
            teamForegroundServiceActive = false
            android.util.Log.e("OrqenMainViewModel", "Failed to start RuntimeExecutionService foreground service", err)
        }
    }

    private fun updateTeamForegroundNotification(projectName: String, statusDetail: String) {
        syncTeamForegroundNotification(projectName, overrideDetail = statusDetail)
    }

    /**
     * Asks SPV's real AI model to think and author a natural, project-aware briefing
     * (either inspecting pending work + explaining the plan at Start, or summarizing verified
     * deliverables on Final Delivery) instead of posting static hardcoded templates.
     */
    private suspend fun generateSpvAiBriefing(
        project: Project,
        workspaceDir: File,
        phase: String,
        objective: String,
        existingTask: com.jarves.mh.multiagent.persistence.TaskEntity? = null,
        deliveredFiles: List<String> = emptyList(),
        builtApkName: String? = null,
        builtApkRelPath: String? = null,
        apkBuildError: String? = null,
        webEntryRel: String? = null,
    ): String? {
        val enabledAgents = _state.value.teamAgents.filter { it.config.isEnabled }
        val spv = com.jarves.mh.multiagent.collab.SpvConversationRouter.selectSoleOwnerFacingSupervisor(enabledAgents)
            ?: return null
        val devName = enabledAgents.firstOrNull { it.config.slot != spv.config.slot }?.config?.name ?: "Dev"
        val ownerName = effectiveOwnerName()
        val providerCfg = providerManager.configs.value.firstOrNull { it.id == spv.config.providerId }
        val langHint = if (isThaiUiLanguage()) "Thai" else "English"

        val workspaceFiles = withContext(Dispatchers.IO) {
            workspaceDir.walkTopDown()
                .maxDepth(4)
                .filter { f ->
                    f.isFile &&
                        !f.path.contains("/.git/") &&
                        !f.path.contains("/.gradle/") &&
                        !f.path.contains("/build/") &&
                        f.name != ".orqen_chat.json"
                }
                .take(25)
                .mapNotNull { runCatching { it.relativeTo(workspaceDir).path }.getOrNull() }
                .toList()
        }
        val taskSteps = existingTask?.let { t ->
            withContext(Dispatchers.IO) { orqenTaskStore.listStepsForTask(t.taskId) }
        }.orEmpty()
        val recentChat = _state.value.teamGroupMessages.takeLast(20).joinToString("\n") { m ->
            val who = if (m.senderSlot == null) ownerName else m.senderName
            "- $who: ${m.text.trim().take(320)}"
        }

        val prompt = buildString {
            appendLine("You are ${spv.config.name} (SPV — Supervisor, Planner & QA) leading this AI Team for project '${project.name}'.")
            appendLine("Owner name: $ownerName | Developer teammate: $devName")
            appendLine("Reply naturally, concisely, and thoughtfully in $langHint (NO robotic boilerplate templates, NO raw JSON/tool dumps).")
            appendLine()
            if (phase == "START_AND_PLAN") {
                appendLine("TASK: The Owner just clicked Start / submitted a task directive: \"$objective\"")
                if (workspaceFiles.isNotEmpty()) {
                    appendLine("Existing files already in this project workspace (${workspaceFiles.size} shown): ${workspaceFiles.joinToString(", ")}")
                } else {
                    appendLine("Workspace is currently fresh (no source files yet).")
                }
                if (taskSteps.isNotEmpty()) {
                    val done = taskSteps.filter { it.status == com.jarves.mh.multiagent.persistence.StepStatus.COMPLETED }
                    val pending = taskSteps.filter { it.status != com.jarves.mh.multiagent.persistence.StepStatus.COMPLETED }
                    appendLine("Previous task (${existingTask?.taskId}): ${done.size}/${taskSteps.size} steps done.")
                    if (pending.isNotEmpty()) {
                        appendLine("Unfinished/pending items: ${pending.joinToString("; ") { "${it.title} (${it.status})" }}")
                    }
                }
                if (recentChat.isNotBlank()) {
                    appendLine("Recent discussion:\n$recentChat")
                }
                appendLine()
                appendLine("Write a clear, natural, well-structured message directly to $ownerName:")
                appendLine("1. Briefly report what already exists in the workspace / what work is still pending (if any).")
                appendLine("2. Synthesize and reorganize EVERYTHING discussed with $ownerName into a clean, actionable engineering plan (goals, features, files/architecture, verification criteria) — do NOT just echo raw chat lines.")
                appendLine("3. Confirm you are now handing off this structured plan to $devName to implement and will independently test & verify afterward.")
                appendLine("Do NOT ask the Owner for permission to start — execution is starting immediately.")
            } else {
                appendLine("TASK: All work and verification for project '${project.name}' (objective: \"$objective\") has COMPLETED successfully with zero remaining blockers!")
                if (taskSteps.isNotEmpty()) {
                    appendLine("Completed steps and real changes:")
                    taskSteps.forEach { st ->
                        val summary = st.outputSummary
                            .replace("REVIEW_APPROVED", "")
                            .replace("TESTS_PASSED", "")
                            .trim()
                            .replace(Regex("\\s+"), " ")
                            .take(260)
                        appendLine("- #${st.sequenceNumber} ${st.title} (${st.status}): $summary")
                    }
                }
                if (deliveredFiles.isNotEmpty()) {
                    appendLine("Verified modified/deliverable files: ${deliveredFiles.joinToString(", ")}")
                }
                if (builtApkName != null) {
                    appendLine("Android Debug APK built and ready to install right below this message: ${builtApkRelPath ?: "app/build/outputs/apk/debug/app-debug.apk"} (apk/$builtApkName)")
                } else if (!apkBuildError.isNullOrBlank()) {
                    appendLine("Android APK build failed with error: ${apkBuildError.take(280)}")
                }
                if (webEntryRel != null) {
                    appendLine("Web entry file ready for live Preview right below this message: $webEntryRel")
                }
                appendLine()
                appendLine("Write a clear, natural, detailed final delivery summary to $ownerName confirming:")
                appendLine("1. Specifically what was fixed/implemented and where (mention exact changes and modified files).")
                appendLine("2. Verification & QA results.")
                appendLine("3. If APK was built, invite $ownerName to tap the Install APK button right below this message (or mention the exact APK build error if it failed).")
            }
        }

        _state.update {
            it.copy(
                teamTypingAgentName = spv.config.name,
                teamTypingAgentSlot = spv.config.slot,
            )
        }
        if (phase == "START_AND_PLAN") {
            updateAgentActivity(AgentSlot.AGENT_1, ActivityType.ANALYZING, project.name, "กำลังวิเคราะห์โปรเจกต์และวางแผนงาน")
        }
        return try {
            val useReadOnlyInspection = (phase == "START_AND_PLAN")
            val res = kotlinx.coroutines.withTimeout(if (useReadOnlyInspection) 90_000L else 30_000L) {
                teamAgentRunner.executeTurn(
                    agent = spv,
                    providerConfig = providerCfg,
                    workspaceDir = workspaceDir,
                    projectSlug = project.slug,
                    prompt = prompt,
                    useIsolatedAgyHome = useIsolatedAgyHomeFor(spv.config.slot),
                    enableCache = false,
                    knownAgyModels = _state.value.antigravityModels,
                    enableWorkspaceTools = useReadOnlyInspection,
                    readOnlyTools = true,
                    onProgress = { chunk ->
                        val cleaned = chunk.trim()
                        if (cleaned.isNotBlank()) {
                            appendAgentLiveLog(spv.config.slot, cleaned)
                        }
                    },
                )
            }
            if (!res.isError && res.responseText.isNotBlank()) {
                autonomousSupervisorEngine.markAgentConnected(
                    agentId = spv.config.slot.id,
                    providerId = spv.config.providerId,
                    modelId = spv.config.modelId,
                )
                cleanChatSummary(res.responseText)
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            _state.update { it.copy(teamTypingAgentName = null, teamTypingAgentSlot = null) }
        }
    }

    /**
     * Converts raw tool/stream progress strings into concise, human-readable localized status text
     * (and extracts the active file path when applicable) for both the in-app status strip and
     * Android top status bar notifications.
     */
    private fun formatLiveProgressForUser(
        rawProgress: String,
        stepTitle: String,
        isThai: Boolean,
    ): Pair<String, String> {
        val trimmed = rawProgress.trim()
        if (trimmed.startsWith("TOOL_STARTED:")) {
            val parts = trimmed.removePrefix("TOOL_STARTED:").split(":", limit = 2)
            val tool = parts[0].trim().lowercase()
            val detail = parts.getOrElse(1) { "" }.trim()
            val shortFile = detail.substringAfterLast('/').take(48)
            return when (tool) {
                "write_file" -> (if (isThai) "กำลังสร้าง/เขียนไฟล์ $shortFile" else "Writing file $shortFile") to detail
                "edit_file" -> (if (isThai) "กำลังแก้ไขไฟล์ $shortFile" else "Editing file $shortFile") to detail
                "delete_file" -> (if (isThai) "กำลังลบไฟล์ $shortFile" else "Deleting file $shortFile") to detail
                "read_file" -> (if (isThai) "กำลังอ่านและตรวจสอบไฟล์ $shortFile" else "Reading & inspecting $shortFile") to detail
                "search_files" -> (if (isThai) "กำลังค้นหาโค้ดในโปรเจกต์ (${detail.take(32)})" else "Searching codebase (${detail.take(32)})") to ""
                "list_dir" -> (if (isThai) "กำลังสำรวจโครงสร้างโฟลเดอร์โปรเจกต์" else "Inspecting project directory structure") to ""
                "run_command" -> (if (isThai) "กำลังรันคำสั่งทดสอบ/บิลด์ (${detail.take(40)})" else "Running command (${detail.take(40)})") to ""
                "verify_apk" -> (if (isThai) "กำลังตรวจสอบไฟล์ APK ที่บิลด์เสร็จ" else "Verifying built APK artifact") to ""
                else -> (if (isThai) "กำลังใช้เครื่องมือ $tool ${detail.take(36)}".trim() else "Running $tool ${detail.take(36)}".trim()) to ""
            }
        }
        if (trimmed.startsWith("TOOL_COMPLETED:")) {
            val tool = trimmed.removePrefix("TOOL_COMPLETED:").trim()
            return (if (isThai) "ดำเนินการ $tool เสร็จแล้ว กำลังประมวลผลต่อ..." else "Completed $tool, continuing...") to ""
        }
        if (trimmed.startsWith("Updating workspace files", ignoreCase = true)) {
            return (if (isThai) "กำลังอัปเดตและบันทึกไฟล์โค้ดในโปรเจกต์..." else "Updating project workspace files...") to ""
        }
        if (trimmed.startsWith("Calling ", ignoreCase = true)) {
            return (if (isThai) "กำลังคิดและประมวลผลแผน/โค้ด..." else trimmed) to ""
        }
        return trimmed.take(90) to ""
    }

    private fun notifyTeamTaskCompleted(projectName: String, summary: String) {
        val app = getApplication<Application>()
        val title = if (isThaiUiLanguage()) {
            "งานเสร็จสมบูรณ์: $projectName"
        } else {
            "Task Completed: $projectName"
        }
        // Post directly via NotificationManager on a high-importance channel so the top Android
        // status bar notification ALWAYS appears reliably even if the app is backgrounded!
        runCatching {
            val nm = app.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "orqen_team_completion_v1"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val ch = android.app.NotificationChannel(
                    channelId,
                    "ORQEN AI Team Completions",
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Notifications when AI Team finishes building and verifying a project"
                    enableVibration(true)
                }
                nm.createNotificationChannel(ch)
            }
            val launchIntent = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = launchIntent?.let {
                android.app.PendingIntent.getActivity(
                    app,
                    projectName.hashCode(),
                    it,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val notif = androidx.core.app.NotificationCompat.Builder(app, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(summary))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setDefaults(android.app.Notification.DEFAULT_ALL)
                .setAutoCancel(true)
                .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
                .build()
            nm.notify(7100 + (projectName.hashCode() and 0x7FF), notif)
        }
        runCatching {
            app.startService(
                Intent(app, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_NOTIFY_RESULT)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_TITLE, title)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, summary),
            )
        }
    }

    private fun stopTeamForegroundService() {
        // Keep the foreground service alive if a normal Team Chat reply is still running!
        if (_state.value.teamTypingAgents.isNotEmpty() || activeTeamChatJobs.isNotEmpty()) {
            syncTeamForegroundNotification()
            return
        }
        teamForegroundServiceActive = false
        val app = getApplication<Application>()
        runCatching {
            app.startService(
                Intent(app, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED),
            )
        }
    }

    fun advanceTeamPart() {
        val current = _state.value.teamCurrentPartNumber
        taskManager.approvePart(current)
        teamChatManager.postMessage(
            senderSlot = AgentSlot.AGENT_1,
            senderName = agentNameFor(AgentSlot.AGENT_1),
            text = "Part 0$current approved. Advancing team to Part 0${current + 1}.",
            messageType = GroupMessageType.APPROVAL,
        )
    }

    private fun appendProjectTeamMessage(
        projectId: String?,
        senderSlot: AgentSlot?,
        senderName: String,
        text: String,
        messageType: GroupMessageType,
        relatedFilePath: String? = null,
        workStatus: String? = null,
        workDetails: List<String> = emptyList(),
        imagePaths: List<String> = emptyList(),
        suggestedTaskPrompt: String? = null,
        apkFilePath: String? = null,
    ) {
        val msg = GroupChatMessage(
            senderSlot = senderSlot,
            senderName = senderName,
            text = text,
            messageType = messageType,
            relatedFilePath = relatedFilePath,
            workStatus = workStatus,
            workDetails = workDetails,
            imagePaths = imagePaths,
            suggestedTaskPrompt = suggestedTaskPrompt,
            apkFilePath = apkFilePath,
        )
        if (projectId != null) {
            val list = loadProjectTeamChat(projectId)
            list.add(msg)
            persistProjectTeamChat(projectId, list)
            if (_state.value.activeTeamProject?.id == projectId) {
                _state.update { it.copy(teamGroupMessages = list.toList()) }
            }
        }
    }

    private fun detectTeamProjectGitRemote(projectId: String): Boolean {
        if (teamGitPrefs.getString("git_url_$projectId", "").orEmpty().isNotBlank()) return true
        val gitConfig = File(getApplication<Application>().filesDir, "team_workspaces/$projectId/.git/config")
        if (!gitConfig.isFile) return false
        return runCatching { gitConfig.readText().contains("[remote \"origin\"]") }.getOrDefault(false)
    }

    fun setTeamAutoPushGitEnabled(enabled: Boolean) {
        teamGitPrefs.edit().putBoolean("auto_push_git_enabled", enabled).apply()
        _state.update {
            it.copy(
                teamAutoPushGitEnabled = enabled,
                toastMessage = if (enabled) "เปิด Push ขึ้น Git อัตโนมัติแล้ว" else "ปิด Push ขึ้น Git อัตโนมัติแล้ว",
            )
        }
    }

    fun pushTeamProjectToGit(gitUrl: String? = null, apiKey: String? = null, isAuto: Boolean = false) {
        val project = _state.value.activeTeamProject ?: return
        if (_state.value.teamGitPushRunning) return

        val savedUrl = gitUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: teamGitPrefs.getString("git_url_${project.id}", "")?.trim()?.takeIf { it.isNotBlank() }
        val savedKey = apiKey?.trim()?.takeIf { it.isNotBlank() }
            ?: teamGitPrefs.getString("git_key_${project.id}", "")?.trim()?.takeIf { it.isNotBlank() }

        if (!gitUrl.isNullOrBlank()) {
            teamGitPrefs.edit().putString("git_url_${project.id}", gitUrl.trim()).apply()
        }
        if (!apiKey.isNullOrBlank()) {
            teamGitPrefs.edit().putString("git_key_${project.id}", apiKey.trim()).apply()
        }

        val hasLocalRemote = detectTeamProjectGitRemote(project.id)
        if (!hasLocalRemote && savedUrl.isNullOrBlank()) {
            if (!isAuto) {
                _state.update { it.copy(toastMessage = "กรุณากรอก Git URL ก่อนทำการ Push") }
            }
            return
        }

        _state.update {
            it.copy(
                teamGitPushRunning = true,
                teamActiveProjectHasGitRemote = true,
                toastMessage = if (isAuto) "กำลัง Auto-Push โปรเจกต์ ${project.name} ขึ้น Git…" else "กำลัง Push โค้ดขึ้น Git…",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val installed = installer.installedRuntime()
                val workspace = File(getApplication<Application>().filesDir, "team_workspaces/${project.id}")
                    .apply { mkdirs() }
                    .canonicalFile
                val safeSlug = project.slug.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').ifBlank { "team-project" }

                // Inject API key / PAT safely into https URL only inside the push command (never logged)
                val authRemoteUrl = if (!savedUrl.isNullOrBlank() && !savedKey.isNullOrBlank() && savedUrl.startsWith("https://") && !savedUrl.contains("@")) {
                    val encodedKey = java.net.URLEncoder.encode(savedKey, "UTF-8")
                    savedUrl.replaceFirst("https://", "https://x-access-token:$encodedKey@")
                } else {
                    savedUrl
                }

                val script = buildString {
                    appendLine("set -e")
                    appendLine("if [ ! -d .git ]; then git init -b main || git init; fi")
                    appendLine("git config user.name \"ORQEN AI Team\" || true")
                    appendLine("git config user.email \"orqen@local\" || true")
                    if (!authRemoteUrl.isNullOrBlank()) {
                        val esc = authRemoteUrl.replace("'", "'\"'\"'")
                        appendLine("if git remote get-url origin >/dev/null 2>&1; then git remote set-url origin '$esc'; else git remote add origin '$esc'; fi")
                    }
                    appendLine("git add -A")
                    appendLine("git diff --cached --quiet || git commit -m 'chore: sync from ORQEN AI Team'")
                    appendLine("BRANCH=\$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)")
                    appendLine("git push -u origin \"\$BRANCH\"")
                }
                val outFile = File(getApplication<Application>().cacheDir, "team-git-push-${System.nanoTime()}.log")
                val proc = installer.process(
                    proot = installed.proot,
                    rootfs = installed.rootfs,
                    workspace = workspace,
                    environment = emptyMap(),
                    guestCommand = listOf("/bin/sh", "-lc", script),
                    guestWorkspacePath = "/workspace/$safeSlug",
                    emulateHardLinks = false,
                    outputFile = outFile,
                )
                val exit = proc.waitFor()
                val rawLog = runCatching { outFile.readText() }.getOrDefault("")
                runCatching { outFile.delete() }
                val sanitizedLog = if (!savedKey.isNullOrBlank()) rawLog.replace(savedKey, "***") else rawLog

                withContext(Dispatchers.Main) {
                    if (exit == 0) {
                        _state.update {
                            it.copy(
                                teamGitPushRunning = false,
                                teamActiveProjectHasGitRemote = true,
                                toastMessage = "Push โปรเจกต์ '${project.name}' ขึ้น Git เรียบร้อยแล้ว",
                            )
                        }
                        appendProjectTeamMessage(
                            projectId = project.id,
                            senderSlot = AgentSlot.AGENT_1,
                            senderName = agentNameFor(AgentSlot.AGENT_1),
                            text = "Push โค้ดโปรเจกต์ '${project.name}' ขึ้น Git เรียบร้อยแล้วครับ",
                            messageType = GroupMessageType.SYSTEM,
                        )
                    } else {
                        val errSnippet = sanitizedLog.lines().lastOrNull { it.isNotBlank() }?.take(140) ?: "exit code $exit"
                        _state.update {
                            it.copy(
                                teamGitPushRunning = false,
                                toastMessage = "Push ขึ้น Git ไม่สำเร็จ: $errSnippet",
                            )
                        }
                    }
                }
            } catch (err: Throwable) {
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            teamGitPushRunning = false,
                            toastMessage = "Push ขึ้น Git ไม่สำเร็จ: ${err.message?.take(120) ?: err.javaClass.simpleName}",
                        )
                    }
                }
            }
        }
    }

    fun installTeamApk(apkPath: String) {
        val apkFile = File(apkPath)
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            _state.update { it.copy(toastMessage = "ไม่พบไฟล์ APK: ${apkFile.name}") }
            return
        }
        runCatching {
            com.jarves.mh.runtime.AndroidAppInstaller.install(
                context = getApplication(),
                apk = apkFile,
                launchAfterInstall = true,
            )
            _state.update { it.copy(toastMessage = "กำลังเปิดหน้าติดตั้ง ${apkFile.name}…") }
        }.onFailure { err ->
            android.util.Log.e("OrqenMainViewModel", "Failed to install team APK: $apkPath", err)
            _state.update { it.copy(toastMessage = "ไม่สามารถเปิดติดตั้ง APK ได้: ${err.message?.take(120) ?: err.javaClass.simpleName}") }
        }
    }

    // ==================== PHASE F: TEAM WORKSPACE FILE MANAGEMENT ====================

    private fun teamWorkspaceRoot(project: Project): File =
        File(getApplication<Application>().filesDir, "team_workspaces/${project.id}")
            .apply { mkdirs() }
            .canonicalFile

    private fun safeTeamWorkspaceFile(project: Project, relPath: String): File? {
        val root = teamWorkspaceRoot(project)
        val clean = relPath.trim().removePrefix("/")
        if (clean.isBlank()) return root
        if (!com.jarves.mh.multiagent.persistence.WorkspacePathValidator.isPathInsideRoot(root, clean)) return null
        return File(root, clean).canonicalFile
    }

    fun uploadFilesToTeamWorkspace(parentRelPath: String, uris: List<Uri>, onDone: () -> Unit = {}) {
        val project = _state.value.activeTeamProject ?: return
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val parent = safeTeamWorkspaceFile(project, parentRelPath)?.takeIf { it.isDirectory }
                    ?: error("Invalid destination folder")
                var uploaded = 0
                val uploadedNames = mutableListOf<String>()
                uris.forEach { uri ->
                    var displayName = "upload_${System.currentTimeMillis()}"
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                                ?.let { displayName = cursor.getString(it) ?: displayName }
                        }
                    }
                    val safeName = sanitizeAttachmentName(displayName)
                    val stem = safeName.substringBeforeLast('.', safeName)
                    val ext = safeName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
                    var dest = File(parent, safeName)
                    var suffix = 2
                    while (dest.exists()) dest = File(parent, "$stem-${suffix++}$ext")
                    val uploadLimit = (parent.usableSpace * 8L / 10L).coerceAtMost(MAX_UPLOAD_BYTES)
                    var copied = 0L
                    try {
                        resolver.openInputStream(uri)?.buffered()?.use { input ->
                            dest.outputStream().buffered().use { output ->
                                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = input.read(buf)
                                    if (count < 0) break
                                    copied += count
                                    check(copied <= uploadLimit) { "File '$displayName' exceeds upload size limit" }
                                    output.write(buf, 0, count)
                                }
                            }
                        } ?: error("Cannot read '$displayName'")
                    } catch (e: Throwable) {
                        dest.delete()
                        throw e
                    }
                    uploaded++
                    uploadedNames += dest.name
                }
                uploaded to uploadedNames
            }
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        toastMessage = result.fold(
                            onSuccess = { (count, _) -> "Uploaded $count file(s) to ${project.name}" },
                            onFailure = { err -> "Upload failed: ${err.message ?: "Unknown error"}" },
                        ),
                    )
                }
                onDone()
            }
        }
    }

    fun createTeamWorkspaceFolder(parentRelPath: String, folderName: String, onDone: () -> Unit = {}) {
        val project = _state.value.activeTeamProject ?: return
        val cleanName = folderName.trim().replace('/', '_').replace('\\', '_')
        if (cleanName.isBlank() || cleanName == "." || cleanName == "..") return
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching {
                val parent = safeTeamWorkspaceFile(project, parentRelPath)?.takeIf { it.isDirectory }
                    ?: error("Invalid parent directory")
                val target = File(parent, cleanName)
                check(com.jarves.mh.multiagent.persistence.WorkspacePathValidator.isPathInsideRoot(teamWorkspaceRoot(project), target.absolutePath)) {
                    "Invalid folder path"
                }
                check(target.exists() || target.mkdirs()) { "Unable to create folder '$cleanName'" }
                cleanName
            }
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        toastMessage = res.fold(
                            onSuccess = { "Created folder '$it'" },
                            onFailure = { err -> "Create folder failed: ${err.message}" },
                        ),
                    )
                }
                onDone()
            }
        }
    }

    fun renameTeamWorkspaceEntry(relPath: String, newName: String, onDone: () -> Unit = {}) {
        val project = _state.value.activeTeamProject ?: return
        val cleanName = newName.trim().replace('/', '_').replace('\\', '_')
        if (relPath.isBlank() || cleanName.isBlank() || cleanName == "." || cleanName == "..") return
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching {
                val root = teamWorkspaceRoot(project)
                val source = safeTeamWorkspaceFile(project, relPath)?.takeIf { it.exists() }
                    ?: error("Path not found or protected")
                check(source != root) { "Cannot rename workspace root" }
                val dest = File(source.parentFile, cleanName).canonicalFile
                check(com.jarves.mh.multiagent.persistence.WorkspacePathValidator.isPathInsideRoot(root, dest.absolutePath)) {
                    "Invalid destination path"
                }
                check(!dest.exists()) { "'$cleanName' already exists" }
                check(source.renameTo(dest)) { "Unable to rename '${source.name}'" }
                cleanName
            }
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        toastMessage = res.fold(
                            onSuccess = { "Renamed to '$it'" },
                            onFailure = { err -> "Rename failed: ${err.message}" },
                        ),
                    )
                }
                onDone()
            }
        }
    }

    fun deleteTeamWorkspaceEntry(relPath: String, onDone: () -> Unit = {}) {
        val project = _state.value.activeTeamProject ?: return
        if (relPath.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching {
                val root = teamWorkspaceRoot(project)
                val target = safeTeamWorkspaceFile(project, relPath)?.takeIf { it.exists() }
                    ?: error("Path not found or protected")
                check(target != root) { "Cannot delete workspace root" }
                val name = target.name
                check(target.deleteRecursively()) { "Failed to delete '$name'" }
                name
            }
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        toastMessage = res.fold(
                            onSuccess = { "Deleted '$it'" },
                            onFailure = { err -> "Delete failed: ${err.message}" },
                        ),
                    )
                }
                onDone()
            }
        }
    }

    fun saveTeamWorkspaceFileCopy(relPath: String, destination: Uri) {
        val project = _state.value.activeTeamProject ?: return
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching {
                val file = safeTeamWorkspaceFile(project, relPath)?.takeIf { it.isFile }
                    ?: error("File not found or protected")
                resolver.openOutputStream(destination, "wt")?.use { out ->
                    file.inputStream().buffered().use { it.copyTo(out) }
                } ?: error("Cannot open destination")
                file.name
            }
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        toastMessage = res.fold(
                            onSuccess = { "Downloaded '$it'" },
                            onFailure = { err -> "Download failed: ${err.message}" },
                        ),
                    )
                }
            }
        }
    }

    fun shareTeamWorkspaceFile(relPath: String) {
        val project = _state.value.activeTeamProject ?: return
        runCatching {
            val file = safeTeamWorkspaceFile(project, relPath)?.takeIf { it.isFile }
                ?: error("File not found")
            val app = getApplication<Application>()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
            app.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    "Share ${file.name}",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { err ->
            _state.update { it.copy(toastMessage = "Share failed: ${err.message}") }
        }
    }

    fun downloadTeamWorkspaceZip(relPaths: List<String>, destination: Uri) {
        val project = _state.value.activeTeamProject ?: return
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching {
                val root = teamWorkspaceRoot(project)
                val pathsToExport = if (relPaths.isEmpty()) listOf("") else relPaths
                val exportResult = resolver.openOutputStream(destination, "wt")?.buffered()?.use { rawOut ->
                    com.jarves.mh.runtime.WorkspaceZipExporter.exportToStream(
                        workspaceRoot = root,
                        relativePaths = pathsToExport,
                        outputStream = rawOut,
                    )
                } ?: error("Cannot write ZIP destination")
                check(exportResult.success && exportResult.entryCount > 0) {
                    exportResult.errorMessage ?: "No files selected to archive"
                }
                exportResult.entryCount
            }
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        toastMessage = res.fold(
                            onSuccess = { count -> "Exported ZIP archive ($count file(s))" },
                            onFailure = { err -> "ZIP export failed: ${err.message}" },
                        ),
                    )
                }
            }
        }
    }

    // ==================== PHASE H: SPV MODEL INTELLIGENCE & MANAGEMENT COMMANDS ====================

    fun applySpvModelOptimization(
        goal: com.jarves.mh.multiagent.supervisor.TeamOptimizationGoal,
    ): List<com.jarves.mh.multiagent.supervisor.AgentModelRecommendation> {
        val isAgySignedIn = _state.value.antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN ||
            _state.value.teamAgentAntigravityAuth.values.any { it.status == AntigravityAuthStatus.SIGNED_IN }
        val pool = com.jarves.mh.multiagent.supervisor.TeamModelIntelligence.buildModelPool(
            providerConfigs = providerManager.configs.value,
            hasApiKey = { id -> getProviderApiKey(id).isNotBlank() },
            isAntigravitySignedIn = isAgySignedIn,
            knownAgyModels = _state.value.antigravityModels,
        )
        val recs = com.jarves.mh.multiagent.supervisor.TeamModelIntelligence.recommendModelAssignments(
            agents = _state.value.teamAgents,
            pool = pool,
            goal = goal,
        )
        recs.filter { it.changed }.forEach { rec ->
            val current = _state.value.teamAgents.firstOrNull { it.config.slot == rec.slot }?.config ?: return@forEach
            updateAgentConfig(
                rec.slot,
                current.copy(
                    providerId = rec.recommendedProviderId,
                    modelId = rec.recommendedModelId,
                ),
            )
        }
        return recs
    }

    private fun handleSpvManagementCommand(
        projectId: String,
        cmd: com.jarves.mh.multiagent.supervisor.SpvManagementCommand,
    ) {
        val supName = agentNameFor(AgentSlot.AGENT_1)
        val report = when (cmd.kind) {
            com.jarves.mh.multiagent.supervisor.SpvManagementCommandKind.CONFIGURE_ANDROID_TEAM -> {
                val modelRecs = applySpvModelOptimization(com.jarves.mh.multiagent.supervisor.TeamOptimizationGoal.ANDROID_DEVELOPMENT)
                val mcpChanges = mcpConnectionManager.applyRecommendedTeamPermissions(projectId, _state.value.teamAgents)
                buildString {
                    appendLine("Configured AI Team for Android Development:")
                    if (modelRecs.isEmpty()) {
                        appendLine("- Models: No available providers with valid credentials found. Configure a provider in Providers tab.")
                    } else {
                        modelRecs.forEach { r ->
                            appendLine("- ${r.agentName} (${r.roleType.name}): ${r.recommendedModelId} (${r.rationale})")
                        }
                    }
                    if (mcpChanges.isNotEmpty()) {
                        appendLine("MCP Permissions Applied (least-privilege; high-risk tools still require Owner approval):")
                        mcpChanges.take(8).forEach { appendLine("- $it") }
                    }
                }
            }
            com.jarves.mh.multiagent.supervisor.SpvManagementCommandKind.CHOOSE_BEST_MODELS,
            com.jarves.mh.multiagent.supervisor.SpvManagementCommandKind.RECONFIGURE_TEAM,
            -> {
                val recs = applySpvModelOptimization(com.jarves.mh.multiagent.supervisor.TeamOptimizationGoal.BALANCED)
                if (recs.isEmpty()) {
                    "No eligible models found in configured providers. Please connect Antigravity or add an API key first."
                } else {
                    buildString {
                        appendLine("Manager updated team model assignments (Balanced quality & cost):")
                        recs.forEach { r ->
                            val tag = if (r.changed) "UPDATED" else "KEPT"
                            appendLine("- [$tag] ${r.agentName}: ${r.recommendedModelId} — ${r.rationale}")
                        }
                    }
                }
            }
            com.jarves.mh.multiagent.supervisor.SpvManagementCommandKind.OPTIMIZE_LOW_COST -> {
                val recs = applySpvModelOptimization(com.jarves.mh.multiagent.supervisor.TeamOptimizationGoal.LOW_COST)
                if (recs.isEmpty()) {
                    "No eligible low-cost models found in configured providers."
                } else {
                    buildString {
                        appendLine("Manager optimized team for low API costs:")
                        recs.forEach { r ->
                            appendLine("- ${r.agentName}: ${r.recommendedModelId} — ${r.rationale}")
                        }
                    }
                }
            }
            com.jarves.mh.multiagent.supervisor.SpvManagementCommandKind.CHECK_AGENT_TOOLS -> {
                val hasRuntime = runCatching { installer.installedRuntime(); true }.getOrDefault(false)
                val servers = mcpConnectionManager.servers.value
                val states = mcpConnectionManager.runtimeStates.value
                val connected = servers.count { states[it.serverId]?.status == com.jarves.mh.mcp.McpConnectionStatus.CONNECTED && mcpConnectionManager.isServerEnabledForTeam(projectId, it.serverId) }
                buildString {
                    appendLine("Agent Tool & Capability Audit:")
                    appendLine("- Workspace File & Code Tools: ENABLED for all agents (read/write/edit/list/search)")
                    appendLine("- Terminal / Gradle / Test Runner (PRoot): ${if (hasRuntime) "READY" else "NOT INSTALLED (install Linux runtime in Settings)"}")
                    appendLine("- Connected Team MCP Servers: $connected / ${servers.size}")
                    _state.value.teamAgents.filter { it.config.isEnabled }.forEach { a ->
                        val perm = mcpConnectionManager.authEngine.getBasePermissions(a.config.slot.id).firstOrNull()?.maxLevel?.label ?: "READ (default)"
                        appendLine("- ${a.config.name} (${a.config.role.title}): Model=${a.config.modelId.ifBlank { "default" }}, MCP=$perm")
                    }
                }
            }
            com.jarves.mh.multiagent.supervisor.SpvManagementCommandKind.FIND_FAILING_AGENT -> {
                val health = autonomousSupervisorEngine.healthMonitor.snapshotAll()
                val failing = health.values.filter { it.consecutiveFailures > 0 || it.recentProviderErrors.isNotEmpty() }
                    .sortedByDescending { it.consecutiveFailures }
                if (failing.isEmpty()) {
                    "No agents have recorded repeated execution failures in the current session."
                } else {
                    buildString {
                        appendLine("Failure Diagnostics Report:")
                        failing.forEach { rec ->
                            val lastErr = rec.recentProviderErrors.lastOrNull() ?: "none"
                            appendLine("- ${rec.agentId} (${rec.healthState.name}): consecutiveFailures=${rec.consecutiveFailures}, recentErrors=${rec.recentProviderErrors.size}, lastError=$lastErr")
                        }
                    }
                }
            }
        }
        appendProjectTeamMessage(
            projectId = projectId,
            senderSlot = AgentSlot.AGENT_1,
            senderName = supName,
            text = report.trim(),
            messageType = GroupMessageType.PROGRESS_UPDATE,
        )
    }

    private data class TeamApkBuildResult(
        val apkFile: File? = null,
        val relativePath: String? = null,
        val buildError: String? = null,
    )

    /**
     * Checks if [project] is an Android project; if so, finds an already-built debug APK or builds
     * one automatically inside PRoot with live Manager status updates so Owner sees real progress
     * and receives an installable APK button (`app/build/outputs/apk/debug/app-debug.apk`) directly in Chat!
     */
    private suspend fun buildAndLocateTeamAndroidApk(project: Project): TeamApkBuildResult = withContext(Dispatchers.IO) {
        val workspace = File(getApplication<Application>().filesDir, "team_workspaces/${project.id}")
            .apply { mkdirs() }
            .canonicalFile
        val appGradleKts = File(workspace, "app/build.gradle.kts")
        val appGradle = File(workspace, "app/build.gradle")
        val isAndroidProj = project.type == ProjectType.ANDROID ||
            appGradle.isFile ||
            appGradleKts.isFile ||
            File(workspace, "settings.gradle").isFile ||
            File(workspace, "settings.gradle.kts").isFile
        if (!isAndroidProj) return@withContext TeamApkBuildResult()

        fun findNewestApk(): File? {
            val apkRoot = File(workspace, "app/build/outputs/apk")
            if (!apkRoot.isDirectory) return null
            return apkRoot.walkTopDown()
                .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) && it.length() > 0L }
                .maxByOrNull { it.lastModified() }
        }

        fun publishCanonicalApk(built: File): TeamApkBuildResult {
            val safeSlug = project.slug.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').ifBlank { "team-project" }
            val standardDebugApk = File(workspace, "app/build/outputs/apk/debug/app-debug.apk")
            if (built.canonicalPath != standardDebugApk.canonicalPath) {
                runCatching {
                    standardDebugApk.parentFile?.mkdirs()
                    built.copyTo(standardDebugApk, overwrite = true)
                }
            }
            val visibleApk = File(workspace, "apk/$safeSlug-debug.apk").apply { parentFile?.mkdirs() }
            runCatching { built.copyTo(visibleApk, overwrite = true) }
            val finalFile = when {
                standardDebugApk.isFile && standardDebugApk.length() > 0L -> standardDebugApk
                visibleApk.isFile && visibleApk.length() > 0L -> visibleApk
                else -> built
            }
            val rel = runCatching { finalFile.relativeTo(workspace).path }.getOrDefault("app/build/outputs/apk/debug/app-debug.apk")
            return TeamApkBuildResult(apkFile = finalFile, relativePath = rel)
        }

        val existingApk = findNewestApk()
        val latestSrcMod = workspace.walkTopDown()
            .maxDepth(6)
            .filter {
                it.isFile &&
                    !it.path.contains("/build/") &&
                    !it.path.contains("/.git/") &&
                    !it.path.contains("/.gradle/") &&
                    !it.path.contains("/.orqen/") &&
                    !it.path.contains("/apk/") &&
                    it.name != ".orqen_chat.json"
            }
            .maxOfOrNull { it.lastModified() } ?: 0L

        // If an APK already exists and is newer than all source files, return it immediately!
        if (existingApk != null && existingApk.lastModified() >= latestSrcMod) {
            return@withContext publishCanonicalApk(existingApk)
        }

        val isThai = isThaiUiLanguage()
        val managerName = agentNameFor(AgentSlot.AGENT_1)
        val buildStatusText = if (isThai) "กำลังบิลด์ Debug APK ให้พร้อมติดตั้ง..." else "Building Debug APK..."
        withContext(Dispatchers.Main.immediate) {
            updateAgentActivity(AgentSlot.AGENT_1, ActivityType.BUILDING_APK, project.name, buildStatusText)
            _state.update { it.copy(teamTypingAgentName = managerName, teamTypingAgentSlot = AgentSlot.AGENT_1) }
            appendAgentLiveLog(AgentSlot.AGENT_1, buildStatusText)
            updateTeamForegroundNotification(project.name, buildStatusText)
        }

        if (!installer.isStackInstalled(DevStack.ANDROID)) {
            val autoStarted = ToolInstallPermissionReceiver.requestOrAutoInstallTool(
                context = getApplication(),
                stack = DevStack.ANDROID,
            )
            return@withContext TeamApkBuildResult(
                buildError = if (autoStarted) {
                    "Android SDK is installing in the background. Retry build once complete."
                } else {
                    "Android SDK is missing. Tap Allow on the Android notification to install in background."
                },
            )
        }
        if (ToolInstallPermissionReceiver.projectNeedsNativeNdk(workspace) && !installer.isStackInstalled(DevStack.ANDROID_NDK)) {
            val autoStarted = ToolInstallPermissionReceiver.requestOrAutoInstallTool(
                context = getApplication(),
                stack = DevStack.ANDROID_NDK,
            )
            return@withContext TeamApkBuildResult(
                buildError = if (autoStarted) {
                    "Android NDK & CMake is installing in the background. Retry build once complete."
                } else {
                    "Project requires Android NDK & CMake. Tap Allow on the Android notification to install in background."
                },
            )
        }

        val assembleTask = resolveDebugAssembleTask(workspace)

        val safeSlug = project.slug.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').ifBlank { "team-project" }
        var lastBuildError: String? = null

        suspend fun runGradleApkBuild(offline: Boolean): Boolean {
            val buildCmd = androidGradleShellCommand(
                root = workspace,
                task = assembleTask,
                offline = offline,
                timeoutMinutes = if (offline) 8 else 15,
            )
            val outFile = File(getApplication<Application>().cacheDir, "team-apk-build-${System.nanoTime()}.log")
            return runCatching {
                val installed = installer.installedRuntime()
                installer.ensureArm64NdkAndCmakeShims(installed.rootfs)
                val proc = installer.process(
                    proot = installed.proot,
                    rootfs = installed.rootfs,
                    workspace = workspace,
                    environment = emptyMap(),
                    guestCommand = listOf("/bin/sh", "-lc", buildCmd),
                    guestWorkspacePath = "/workspace/$safeSlug",
                    emulateHardLinks = false,
                    outputFile = outFile,
                )
                try {
                    runCatching { proc.outputStream.close() }
                    val exitCode = proc.waitFor()
                    val tail = runCatching { outFile.readTailText(4096).trim() }.getOrDefault("")
                    if (exitCode == 0) {
                        true
                    } else {
                        val errLine = tail.lineSequence()
                            .filter { it.startsWith("e: ") || it.contains("FAILURE:") || it.contains("What went wrong:") || it.contains("Execution failed") }
                            .take(4)
                            .joinToString(" | ")
                            .ifBlank { tail.takeLast(240) }
                        lastBuildError = "Gradle $assembleTask exited with code $exitCode: $errLine"
                        false
                    }
                } finally {
                    runCatching { proc.destroy() }
                    runCatching { outFile.delete() }
                }
            }.getOrElse { err ->
                lastBuildError = err.message ?: err.javaClass.simpleName
                false
            }
        }

        val tryOfflineFirst = existingApk != null
        var ok = runGradleApkBuild(offline = tryOfflineFirst)
        if (!ok && tryOfflineFirst && (lastBuildError?.contains("offline", ignoreCase = true) == true || lastBuildError?.contains("Could not resolve", ignoreCase = true) == true)) {
            withContext(Dispatchers.Main.immediate) {
                appendAgentLiveLog(AgentSlot.AGENT_1, "Offline cache miss — retrying Gradle $assembleTask online...")
            }
            ok = runGradleApkBuild(offline = false)
        }

        withContext(Dispatchers.Main.immediate) {
            _state.update { it.copy(teamTypingAgentName = null, teamTypingAgentSlot = null) }
        }

        val built = findNewestApk()
        if (ok && built != null && built.isFile && built.length() > 0L) {
            publishCanonicalApk(built)
        } else {
            TeamApkBuildResult(apkFile = null, buildError = lastBuildError ?: "Debug APK not found after $assembleTask")
        }
    }

    fun clearTeamTerminal() {
        _state.update { it.copy(teamTerminalOutput = "") }
    }

    fun runTeamTerminalCommand(command: String) {
        val project = _state.value.activeTeamProject ?: return
        if (command.isBlank() || _state.value.teamTerminalRunning) return
        _state.update {
            it.copy(
                teamTerminalRunning = true,
                teamTerminalOutput = (it.teamTerminalOutput + "\n$ $command\n").takeLast(120_000),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val out = runCatching {
                val installed = installer.installedRuntime()
                val workspace = File(getApplication<Application>().filesDir, "team_workspaces/${project.id}").apply { mkdirs() }
                val outputFile = File(getApplication<Application>().cacheDir, "team-term-${System.nanoTime()}.log")
                val proc = installer.process(
                    installed.proot,
                    installed.rootfs,
                    workspace,
                    emptyMap(),
                    listOf("/usr/bin/bash", "-lc", command),
                    guestWorkspacePath = "/workspace/${project.slug}",
                    outputFile = outputFile,
                )
                try {
                    proc.outputStream.close()
                    val exit = proc.waitFor()
                    val text = outputFile.readTailText(64 * 1024).trim()
                    if (text.isBlank()) "[exit $exit]\n" else "$text\n[exit $exit]\n"
                } finally {
                    runCatching { proc.destroy() }
                    outputFile.delete()
                }
            }.getOrElse { err ->
                "Error: ${err.message}\n"
            }
            _state.update {
                it.copy(
                    teamTerminalRunning = false,
                    teamTerminalOutput = (it.teamTerminalOutput + out).takeLast(120_000),
                )
            }
        }
    }

    // pendingTeamImageFiles declared before init {} above

    fun postTeamChatMessageWithImages(
        text: String,
        type: GroupMessageType = GroupMessageType.NORMAL,
        targetSlot: AgentSlot? = null,
        imageUris: List<Uri>,
    ) {
        val projectId = _state.value.activeTeamProject?.id
        if (projectId == null || imageUris.isEmpty()) {
            postTeamChatMessage(text, type, targetSlot)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val uploadsDir = File(app.filesDir, "team_workspaces/$projectId/.orqen_uploads").apply { mkdirs() }
            val savedFiles = imageUris.take(3).mapIndexedNotNull { idx, uri ->
                runCatching {
                    val outFile = File(uploadsDir, "img_${System.currentTimeMillis()}_$idx.jpg")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        val bmp = android.graphics.BitmapFactory.decodeStream(input)
                        if (bmp != null) {
                            val maxDim = 1280
                            val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height).coerceAtLeast(1))
                            val resized = if (scale < 1f) {
                                android.graphics.Bitmap.createScaledBitmap(
                                    bmp,
                                    (bmp.width * scale).toInt().coerceAtLeast(1),
                                    (bmp.height * scale).toInt().coerceAtLeast(1),
                                    true,
                                )
                            } else bmp
                            outFile.outputStream().use { out ->
                                resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                            }
                            if (resized !== bmp) resized.recycle()
                            bmp.recycle()
                            outFile
                        } else null
                    }
                }.getOrNull()
            }
            withContext(Dispatchers.Main) {
                val savedPaths = savedFiles.map { it.absolutePath }
                // STRICT CHAT MODE ISOLATION: attaching images in Chat Mode (NORMAL) stays in Chat Mode!
                if (type == GroupMessageType.NORMAL) {
                    appendProjectTeamMessage(
                        projectId = projectId,
                        senderSlot = null,
                        senderName = effectiveOwnerName(),
                        text = text,
                        messageType = GroupMessageType.NORMAL,
                        imagePaths = savedPaths,
                    )
                    clearTeamChatDraft(projectId)
                    handleIdleAgentChatReply(projectId, text, targetSlot)
                    return@withContext
                }
                appendProjectTeamMessage(
                    projectId = projectId,
                    senderSlot = null,
                    senderName = effectiveOwnerName(),
                    text = text,
                    messageType = type,
                    imagePaths = savedPaths,
                )
                clearTeamChatDraft(projectId)
                savedFiles.forEach { pendingTeamImageFiles.add(it) }
                val finalizedObjective = com.jarves.mh.multiagent.collab.SpvModePolicy.buildObjectiveFromDiscussion(
                    recentMessages = _state.value.teamGroupMessages,
                    explicitInstruction = text,
                    attachmentPaths = savedPaths,
                ).ifBlank { text }
                pendingTeamDirectives.add(finalizedObjective)
                _state.update { it.copy(teamPriorityDirectivesCount = pendingTeamDirectives.size) }
                recordCollaborationEvent(
                    type = TeamEventType.TASK_CREATED,
                    senderSlot = targetSlot,
                    message = "Owner directive (+${savedFiles.size} image): \"$text\"",
                    priority = EventPriority.CRITICAL,
                )
                val runningId = _state.value.runningTeamProjectId
                val canAutoStart = !_state.value.teamIsRunning && (runningId == null || runningId == projectId)
                if (canAutoStart) {
                    submitAutonomousTeamObjective(finalizedObjective, attachmentPaths = savedPaths)
                } else {
                    _state.value.activeTeamProject?.let { proj ->
                        val team = syncTeamProfilesForProject(proj)
                        autonomousSupervisorEngine.preparePlan(proj.id, team, finalizedObjective, attachmentPaths = savedPaths)
                    }
                }
            }
        }
    }

    private fun isActionableTaskIntent(text: String): Boolean {
        val lower = text.lowercase()
        val keywords = listOf(
            "สร้าง", "แก้", "เพิ่ม", "ทำ", "ปรับ", "ลบ", "เปลี่ยน", "เขียน", "อัปเดต", "ออกแบบ",
            "build", "create", "fix", "add", "implement", "update", "change", "refactor", "delete", "make ",
        )
        return keywords.any { lower.contains(it) }
    }

    private fun tryHandleLiveRoleHandover(
        projectId: String,
        userMessage: String,
        targetSlot: AgentSlot?,
        enabledAgents: List<AgentState>,
    ): Boolean {
        val handover = com.jarves.mh.multiagent.collab.TeamGroupChatRouter.detectLiveHandoverRequest(
            text = userMessage,
            explicitTarget = targetSlot,
            enabledAgents = enabledAgents,
        ) ?: return false

        val ownerName = effectiveOwnerName()
        val takeover = handover.takeoverAgent
        val relieved = handover.relievedAgent
        viewModelScope.launch {
            val reassignedSteps = withContext(Dispatchers.IO) {
                com.jarves.mh.multiagent.collab.TeamGroupChatRouter.applyLiveRoleHandover(
                    store = orqenTaskStore,
                    projectId = projectId,
                    takeoverAgentId = takeover.config.slot.id,
                    relievedAgentId = relieved.config.slot.id,
                )
            }
            // Free the relieved agent to chat with the Owner while the takeover agent steps in
            val updatedAgents = _state.value.teamAgents.map { agent ->
                when (agent.config.slot) {
                    relieved.config.slot -> agent.copy(
                        isWorking = false,
                        activityState = agent.activityState.copy(
                            activity = ActivityType.WAITING,
                            lastAction = "ส่งต่องานให้ ${takeover.config.name} แล้ว — พร้อมคุยกับ $ownerName",
                        ),
                    )
                    takeover.config.slot -> agent.copy(
                        isWorking = true,
                        activityState = agent.activityState.copy(
                            activity = ActivityType.ANALYZING,
                            lastAction = "รับช่วงต่อจาก ${relieved.config.name}: ${userMessage.take(60)}",
                        ),
                    )
                    else -> agent
                }
            }
            _state.update { it.copy(teamAgents = updatedAgents) }

            val stepInfo = if (reassignedSteps > 0) " ($reassignedSteps ขั้นตอนที่ค้างอยู่ถูกโอนมาเรียบร้อย)" else ""
            appendProjectTeamMessage(
                projectId = projectId,
                senderSlot = takeover.config.slot,
                senderName = takeover.config.name,
                text = "รับทราบครับ $ownerName! ผมรับช่วงต่อจาก ${relieved.config.name} ทันที$stepInfo จะลุยทำต่อจากจุดเดิมให้เลยครับ ให้ ${relieved.config.name} พักมาคุยกับ $ownerName ได้เต็มที่ครับ",
                messageType = GroupMessageType.PROGRESS_UPDATE,
            )
            appendProjectTeamMessage(
                projectId = projectId,
                senderSlot = relieved.config.slot,
                senderName = relieved.config.name,
                text = "ส่งมอบงานต่อให้ ${takeover.config.name} เรียบร้อยครับ $ownerName ตอนนี้ผมว่างพร้อมคุยกับ $ownerName แล้วครับ มีเรื่องไหนให้ช่วยบอกได้เลย!",
                messageType = GroupMessageType.NORMAL,
            )
            recordCollaborationEvent(
                type = TeamEventType.TASK_ASSIGNED,
                senderSlot = takeover.config.slot,
                message = "Live Handover: ${relieved.config.name} -> ${takeover.config.name} ($reassignedSteps steps)",
                priority = EventPriority.HIGH,
            )

            // Ensure execution continues seamlessly under the takeover agent!
            val activeTasks = withContext(Dispatchers.IO) {
                orqenTaskStore.listTasksForProject(projectId).filter {
                    it.status in setOf(
                        com.jarves.mh.multiagent.persistence.PersistentTaskStatus.RUNNING,
                        com.jarves.mh.multiagent.persistence.PersistentTaskStatus.PAUSED,
                        com.jarves.mh.multiagent.persistence.PersistentTaskStatus.QUEUED,
                        com.jarves.mh.multiagent.persistence.PersistentTaskStatus.PLANNING,
                        com.jarves.mh.multiagent.persistence.PersistentTaskStatus.READY,
                        com.jarves.mh.multiagent.persistence.PersistentTaskStatus.RECOVERING,
                        com.jarves.mh.multiagent.persistence.PersistentTaskStatus.BLOCKED,
                    )
                }
            }
            if (activeTasks.isNotEmpty()) {
                resumeTeamWork()
            } else if (userMessage.length > 10) {
                // No active task yet — dispatch the takeover agent's task directly
                submitAutonomousTeamObjective(
                    "[Handover to ${takeover.config.name} (${takeover.config.slot.id})] $userMessage",
                )
            }
        }
        return true
    }

    private fun handleIdleAgentChatReply(projectId: String, userMessage: String, targetSlot: AgentSlot?) {
        val project = _state.value.teamProjects.firstOrNull { it.id == projectId } ?: _state.value.activeTeamProject ?: return
        val enabledAgents = _state.value.teamAgents.filter { it.config.isEnabled }
        if (enabledAgents.isEmpty()) return

        // 1. Dynamic Live Role Handover via @mention (e.g. "@QA ช่วยมาเทสระบบและบิ้ว apk แทน SPV หน่อย ...")
        if (tryHandleLiveRoleHandover(projectId, userMessage, targetSlot, enabledAgents)) {
            return
        }

        // 2. Resolve ALL explicit @mentions (supports concurrent multi-agent chat & AI-to-AI relay)
        val allMentioned = com.jarves.mh.multiagent.collab.TeamGroupChatRouter.resolveAllMentionedSlots(
            userMessage,
            targetSlot,
            enabledAgents,
        )
        val explicitMention = allMentioned.firstOrNull()
        val responder = if (explicitMention != null) {
            enabledAgents.firstOrNull { it.config.slot == explicitMention }
        } else {
            com.jarves.mh.multiagent.collab.SpvConversationRouter.selectSoleOwnerFacingSupervisor(enabledAgents)
        } ?: return

        // Secondary teammate for AI-to-AI chat relay (e.g. "@SPV ช่วยถาม @Dev หน่อย...")
        val relayTeammate = allMentioned.drop(1)
            .firstOrNull { it != responder.config.slot }
            ?.let { slot -> enabledAgents.firstOrNull { it.config.slot == slot } }

        // Launch responder concurrently in its own per-agent coroutine!
        // Because Chat Mode is read-only (`readOnlyTools = true`), we NEVER cancel SPV or tear down
        // a working agent's active coding step (`teamCycleJob`) — busy agents answer concurrently and keep working!
        launchConcurrentAgentChatTurn(
            project = project,
            responder = responder,
            enabledAgents = enabledAgents,
            userMessage = userMessage,
            relayContextFromAgent = null,
            onReplyPosted = { spvReply ->
                if (relayTeammate != null) {
                    launchConcurrentAgentChatTurn(
                        project = project,
                        responder = relayTeammate,
                        enabledAgents = enabledAgents,
                        userMessage = userMessage,
                        relayContextFromAgent = "${responder.config.name}: $spvReply",
                        onReplyPosted = null,
                    )
                }
            },
        )
    }

    private val activeTeamChatRequestIds = java.util.concurrent.ConcurrentHashMap<AgentSlot, String>()

    fun cancelTeamChatAgent(slot: AgentSlot) {
        activeTeamChatJobs.remove(slot)?.cancel()
        activeTeamChatRequestIds.remove(slot)?.let { reqId ->
            teamAgentRunner.cancelRequest(reqId)
        }
        _state.update { cur ->
            val remainingTyping = cur.teamTypingAgents - slot
            val nextTyping = remainingTyping.entries.lastOrNull()
            val clearStream = cur.teamStreamingAgentSlot == slot
            cur.copy(
                teamTypingAgents = remainingTyping,
                teamTypingAgentName = nextTyping?.value,
                teamTypingAgentSlot = nextTyping?.key,
                teamStreamingAgentName = if (clearStream) null else cur.teamStreamingAgentName,
                teamStreamingAgentSlot = if (clearStream) null else cur.teamStreamingAgentSlot,
                teamStreamingText = if (clearStream) "" else cur.teamStreamingText,
            )
        }
        syncTeamForegroundNotification()
    }

    private fun launchConcurrentAgentChatTurn(
        project: Project,
        responder: AgentState,
        enabledAgents: List<AgentState>,
        userMessage: String,
        relayContextFromAgent: String?,
        onReplyPosted: ((String) -> Unit)?,
    ) {
        val ownerName = effectiveOwnerName()
        val isBusyWorker = responder.isWorking && _state.value.teamIsRunning
        val job = viewModelScope.launch {
            _state.update {
                val updatedTyping = it.teamTypingAgents + (responder.config.slot to responder.config.name)
                it.copy(
                    teamTypingAgentName = responder.config.name,
                    teamTypingAgentSlot = responder.config.slot,
                    teamTypingAgents = updatedTyping,
                )
            }
            syncTeamForegroundNotification(project.name)
            try {
                val workspaceDir = File(getApplication<Application>().filesDir, "team_workspaces/${project.id}").apply { mkdirs() }
                val teamStatusSummary = enabledAgents.joinToString("\n") { a ->
                    "- ${a.config.name} (${a.config.role.title}): ${if (a.isWorking) "กำลังทำงาน (${a.activityState.lastAction})" else "พักรออยู่"}"
                }
                val recentDiscussion = _state.value.teamGroupMessages
                    .takeLast(8)
                    .joinToString("\n") { m ->
                        val who = if (m.senderSlot == null) ownerName else m.senderName
                        "- $who: ${m.text.trim().take(280)}"
                    }
                val chatPrompt = buildString {
                    appendLine("You are ${responder.config.name} (${responder.config.role.title}) on this AI Team.")
                    appendLine("INTERACTION MODE: CHAT_CONSULTATION (full read-only inspection & diagnostics).")
                    appendLine("You CAN use ALL inspection and diagnostic tools (list_dir, read_file, search_files, run_command for read-only checks/tests/diagnostics) to thoroughly inspect the project and answer the Owner accurately.")
                    appendLine("The ONLY restriction in Chat Mode is that you MUST NOT create, edit, or delete source files; code modifications begin when the Owner switches to Task Mode or clicks Start.")
                    appendLine("Owner's name (address the user as): $ownerName")
                    appendLine("Your employee personality & tone: ${responder.config.effectivePersonality()}")
                    appendLine("STRICT RULES IN CHAT MODE:")
                    appendLine("- DO NOT create tasks, DO NOT delegate work, DO NOT modify/create/delete any files, and DO NOT claim you started building.")
                    appendLine("- NEVER use emojis in your replies. Keep formatting clean and professional.")
                    appendLine("- Explain in clear, plain, easy-to-understand language. When mentioning files, refer to them by short filename only (e.g. `MainActivity.kt` instead of long nested paths like `app/src/main/java/.../MainActivity.kt`) unless the Owner explicitly asks for full paths.")
                    appendLine("- Act as an intelligent technical partner: understand the Owner's idea, ask useful clarifying questions, analyze requirements, suggest architecture, compare options, and help refine a complete specification.")
                    appendLine("- Remember and build upon the recent conversation context below.")
                    if (isBusyWorker) {
                        appendLine("NOTE: You are currently working on an active background task (${responder.activityState.lastAction}). Answer $ownerName clearly and concisely while your active work continues uninterrupted.")
                    }
                    if (!relayContextFromAgent.isNullOrBlank()) {
                        appendLine("NOTE: A teammate just responded to $ownerName and looped you in for your perspective:")
                        appendLine(relayContextFromAgent.take(500))
                        appendLine("Give your direct, concise technical input as ${responder.config.name} (${responder.config.role.title}) so both $ownerName and your teammate see your answer.")
                    }
                    if (recentDiscussion.isNotBlank()) {
                        appendLine("Recent discussion context:")
                        appendLine(recentDiscussion)
                    }
                    appendLine("Current team status:")
                    appendLine(teamStatusSummary)
                    appendLine(
                        com.jarves.mh.skills.AppSettingsActionDispatcher.buildReadOnlySettingsContext(
                            themeMode = _state.value.themeMode,
                            languageCode = _state.value.languageCode,
                            ownerName = ownerName,
                            autoSkillsEnabled = skillManager.autoSelectEnabled.value,
                            skillCount = skillManager.skills.value.size,
                            autoPushGitEnabled = _state.value.teamAutoPushGitEnabled,
                            agents = _state.value.teamAgents,
                        ),
                    )
                    appendLine("Current workspace files:")
                    appendLine(workspaceFileListingSummary(workspaceDir))
                    appendLine()
                    appendLine("$ownerName said: \"$userMessage\"")
                    appendLine("Reply naturally in the same language as $ownerName, addressing them as $ownerName, clearly helping refine their idea or answering their question concisely.")
                }
                val providerCfg = _state.value.providerConfigs.firstOrNull { it.id == responder.config.providerId }
                val chatReqId = "chat-${responder.config.slot.id}-${System.nanoTime()}"
                activeTeamChatRequestIds[responder.config.slot] = chatReqId
                val res = teamAgentRunner.executeTurn(
                    agent = responder,
                    providerConfig = providerCfg,
                    workspaceDir = workspaceDir,
                    projectSlug = project.slug,
                    prompt = chatPrompt,
                    useIsolatedAgyHome = useIsolatedAgyHomeFor(responder.config.slot),
                    enableCache = false,
                    knownAgyModels = _state.value.antigravityModels,
                    enableWorkspaceTools = true,
                    readOnlyTools = true,
                    requestId = chatReqId,
                    onProgress = { chunk ->
                        val cleanedChunk = chunk.trim()
                        if (cleanedChunk.isNotBlank() && !cleanedChunk.startsWith("Cache hit")) {
                            appendAgentLiveLog(responder.config.slot, cleanedChunk)
                        }
                    },
                )
                val replyBody = if (res.isError) {
                    if (isBusyWorker) {
                        "รับทราบครับ $ownerName ตอนนี้ผมกำลังลุยงาน (${responder.activityState.lastAction}) ในโปรเจกต์ ${project.name} อยู่ครับ และยังทำงานต่อให้ไม่สะดุดครับ"
                    } else {
                        "ตอนนี้ทีมกำลังดูแลโปรเจกต์ ${project.name} อยู่ครับ $ownerName (สถานะทีม: ${enabledAgents.count { it.isWorking }}/${enabledAgents.size} กำลังทำงาน)"
                    }
                } else {
                    autonomousSupervisorEngine.markAgentConnected(
                        agentId = responder.config.slot.id,
                        providerId = responder.config.providerId,
                        modelId = responder.config.modelId,
                    )
                    cleanChatSummary(res.responseText)
                }
                val parsedActions = com.jarves.mh.skills.AppSettingsActionDispatcher.parseAndStripActions(replyBody)
                val appliedNotes = mutableListOf<String>()
                parsedActions.safeActions.forEach { act ->
                    if (applySafeAppControlAction(act)) {
                        appliedNotes.add(com.jarves.mh.skills.AppSettingsActionDispatcher.describeActionForUser(act))
                    }
                }
                val approvalNotes = parsedActions.approvalActions.map { act ->
                    com.jarves.mh.skills.AppSettingsActionDispatcher.describeActionForUser(act)
                }
                val finalReply = buildString {
                    append(parsedActions.cleanReplyText.ifBlank { replyBody })
                    if (appliedNotes.isNotEmpty()) {
                        appendLine()
                        appendLine()
                        append("ปรับตั้งค่าแอปให้เรียบร้อยแล้ว: ${appliedNotes.joinToString(", ")}")
                    }
                    if (approvalNotes.isNotEmpty()) {
                        appendLine()
                        appendLine()
                        append("รายการนี้มีผลกระทบสำคัญ กรุณายืนยันก่อนดำเนินการ: ${approvalNotes.joinToString(", ")}")
                    }
                }
                // Avoid O(N^2) intermediate string copies and 10x full AppUiState recompositions:
                // post the completed reply directly in a single atomic state update.
                _state.update {
                    if (it.teamStreamingAgentSlot == responder.config.slot) {
                        it.copy(
                            teamStreamingAgentName = null,
                            teamStreamingAgentSlot = null,
                            teamStreamingText = "",
                        )
                    } else it
                }
                appendProjectTeamMessage(
                    projectId = project.id,
                    senderSlot = responder.config.slot,
                    senderName = responder.config.name,
                    text = finalReply,
                    messageType = GroupMessageType.NORMAL,
                    suggestedTaskPrompt = null,
                )
                onReplyPosted?.invoke(finalReply)
            } finally {
                activeTeamChatJobs.remove(responder.config.slot)
                activeTeamChatRequestIds.remove(responder.config.slot)
                _state.update {
                    val remainingTyping = it.teamTypingAgents - responder.config.slot
                    val nextTyping = remainingTyping.entries.lastOrNull()
                    val clearStream = it.teamStreamingAgentSlot == responder.config.slot
                    it.copy(
                        teamTypingAgents = remainingTyping,
                        teamTypingAgentName = nextTyping?.value,
                        teamTypingAgentSlot = nextTyping?.key,
                        teamStreamingAgentName = if (clearStream) null else it.teamStreamingAgentName,
                        teamStreamingAgentSlot = if (clearStream) null else it.teamStreamingAgentSlot,
                        teamStreamingText = if (clearStream) "" else it.teamStreamingText,
                    )
                }
                syncTeamForegroundNotification(project.name)
            }
        }
        activeTeamChatJobs[responder.config.slot] = job
    }

    fun postTeamChatMessage(text: String, type: GroupMessageType = GroupMessageType.NORMAL, targetSlot: AgentSlot? = null) {
        val projectId = _state.value.activeTeamProject?.id
        val ownerName = effectiveOwnerName()

        // STRICT MODE SEPARATION (Phase 3):
        // The selected mode (`type`) is authoritative.
        // - GroupMessageType.NORMAL = CHAT MODE (consultation-only: NEVER creates tasks, NEVER runs
        //   keyword-based task detection, NEVER invokes Developer/Reviewer/QA/tools/terminal/MCP).
        // - Any explicit Task Mode type (`TASK_ASSIGNMENT`, `BUG_REPORT`, `REVIEW_REQUEST`, etc.) =
        //   TASK MODE (synthesizes full conversation decisions and launches autonomous SPV execution).
        val enabledAgentsForRoute = _state.value.teamAgents.filter { it.config.isEnabled }
        val ownerRoute = com.jarves.mh.multiagent.collab.SpvConversationRouter.routeOwnerMessage(
            text = text,
            messageType = type,
            enabledAgents = enabledAgentsForRoute,
        )
        if (type == GroupMessageType.NORMAL || ownerRoute == null || !ownerRoute.shouldCreateTask) {
            appendProjectTeamMessage(
                projectId = projectId,
                senderSlot = null,
                senderName = ownerName,
                text = text,
                messageType = GroupMessageType.NORMAL,
            )
            if (projectId != null) {
                clearTeamChatDraft(projectId)
                handleIdleAgentChatReply(projectId, text, targetSlot)
            }
            return
        }

        // Fast-path for direct APK build requests ("บิ้ว APK ให้หน่อย" / "build apk") when idle:
        // Directly builds & verifies the Debug APK without re-planning or re-running old completed tasks!
        val lowerTextForApk = text.lowercase()
        val isPureApkBuildRequest = (lowerTextForApk.contains("บิ้ว apk") || lowerTextForApk.contains("บิว apk") ||
            lowerTextForApk.contains("build apk") || lowerTextForApk.contains("สร้าง apk")) &&
            text.length <= 90 && !_state.value.teamIsRunning && projectId != null
        if (isPureApkBuildRequest) {
            val activeProj = _state.value.activeTeamProject
            if (activeProj != null) {
                appendProjectTeamMessage(
                    projectId = projectId,
                    senderSlot = null,
                    senderName = ownerName,
                    text = text,
                    messageType = type,
                )
                clearTeamChatDraft(projectId)
                val spvName = agentNameFor(AgentSlot.AGENT_1)
                appendProjectTeamMessage(
                    projectId = projectId,
                    senderSlot = AgentSlot.AGENT_1,
                    senderName = spvName,
                    text = "รับทราบครับ $ownerName กำลังเริ่มบิ้วและตรวจสอบ Debug APK จากโค้ดล่าสุดให้ทันทีครับ (ไม่ต้องรันแผนเดิมซ้ำ)",
                    messageType = GroupMessageType.PROGRESS_UPDATE,
                )
                viewModelScope.launch {
                    updateAgentActivity(AgentSlot.AGENT_1, ActivityType.BUILDING_APK, "Build Debug APK", "กำลังบิ้ว Debug APK...")
                    val apkRes = withContext(Dispatchers.IO) { buildAndLocateTeamAndroidApk(activeProj) }
                    val builtApk = apkRes.apkFile
                    updateAgentActivity(AgentSlot.AGENT_1, ActivityType.WAITING, "", "พร้อมใช้งาน")
                    if (builtApk != null && builtApk.isFile) {
                        val rel = apkRes.relativePath ?: "app/build/outputs/apk/debug/app-debug.apk"
                        appendProjectTeamMessage(
                            projectId = projectId,
                            senderSlot = AgentSlot.AGENT_1,
                            senderName = spvName,
                            text = "บิ้ว Debug APK ของโปรเจกต์ '${activeProj.name}' เสร็จสมบูรณ์แล้วครับ $ownerName (`$rel`) สามารถกดปุ่มติดตั้ง APK ด้านล่างได้ทันที",
                            messageType = GroupMessageType.COMPLETION_REPORT,
                            apkFilePath = builtApk.absolutePath,
                            workStatus = "APK Ready",
                        )
                    } else {
                        val errDetail = apkRes.buildError?.take(260)?.let { "\nรายละเอียด: $it" } ?: ""
                        appendProjectTeamMessage(
                            projectId = projectId,
                            senderSlot = AgentSlot.AGENT_1,
                            senderName = spvName,
                            text = "ไม่พบไฟล์ APK ที่บิ้วสำเร็จในโปรเจกต์นี้ครับ $ownerName (อาจไม่ใช่โปรเจกต์ Android หรือยังมีจุดคอมไพล์ผิดพลาด ตรวจสอบได้ในแท็บ Terminal)$errDetail",
                            messageType = GroupMessageType.PROGRESS_UPDATE,
                        )
                    }
                }
                return
            }
        }

        // Explicit TASK_EXECUTION transition: synthesize finalized requirements from prior Chat consultation
        // so the Owner never has to repeat previously discussed specifications!
        val finalizedObjective = com.jarves.mh.multiagent.collab.SpvModePolicy.buildObjectiveFromDiscussion(
            recentMessages = _state.value.teamGroupMessages,
            explicitInstruction = text,
        ).ifBlank { text }

        // Explicit new task cancels any pending Start confirmation prompt
        pendingStartConfirmation = null
        _state.update { it.copy(teamAwaitingStartConfirmation = false) }

        // Dynamic Live Role Handover also works in Task Mode (e.g. "@QA ช่วยมาเทสระบบและบิ้ว apk แทน SPV หน่อย...")
        if (projectId != null && com.jarves.mh.multiagent.collab.TeamGroupChatRouter.detectLiveHandoverRequest(text, targetSlot, enabledAgentsForRoute) != null) {
            appendProjectTeamMessage(
                projectId = projectId,
                senderSlot = null,
                senderName = ownerName,
                text = text,
                messageType = type,
            )
            clearTeamChatDraft(projectId)
            tryHandleLiveRoleHandover(projectId, text, targetSlot, enabledAgentsForRoute)
            return
        }

        // TASK / BUG / REVIEW / PROGRESS mode: dispatch real work to the AI Team!
        appendProjectTeamMessage(
            projectId = projectId,
            senderSlot = null,
            senderName = ownerName,
            text = text,
            messageType = if (type == GroupMessageType.NORMAL) GroupMessageType.TASK_ASSIGNMENT else type,
        )
        if (projectId != null) clearTeamChatDraft(projectId)
        pendingTeamDirectives.add(finalizedObjective)
        _state.update { it.copy(teamPriorityDirectivesCount = pendingTeamDirectives.size) }
        recordCollaborationEvent(
            type = TeamEventType.TASK_CREATED,
            senderSlot = targetSlot,
            message = "Owner directive: \"${text.take(140)}\"",
            priority = EventPriority.CRITICAL,
        )

        val runningId = _state.value.runningTeamProjectId
        val canAutoStart = !_state.value.teamIsRunning && (runningId == null || runningId == projectId) && projectId != null
        if (canAutoStart) {
            submitAutonomousTeamObjective(finalizedObjective)
        } else if (_state.value.teamIsRunning && runningId == projectId) {
            _state.value.activeTeamProject?.let { proj ->
                val team = syncTeamProfilesForProject(proj)
                autonomousSupervisorEngine.preparePlan(proj.id, team, finalizedObjective)
            }
            val ceoName = agentNameFor(AgentSlot.AGENT_1)
            val coderName = _state.value.teamAgents.filter { it.config.isEnabled }.getOrNull(1)?.config?.name ?: ceoName
            appendProjectTeamMessage(
                projectId = projectId,
                senderSlot = AgentSlot.AGENT_1,
                senderName = ceoName,
                text = "รับทราบครับ $ownerName! ผมรับคำสั่งใหม่แล้วและกำลังอัปเดตแผนงานส่งต่อให้ $coderName ดำเนินการต่อทันที",
                messageType = GroupMessageType.TASK_ASSIGNMENT,
            )
        } else if (!_state.value.teamIsRunning) {
            viewModelScope.launch {
                delay(300L)
                val responder = targetSlot ?: AgentSlot.AGENT_1
                appendProjectTeamMessage(
                    projectId = projectId,
                    senderSlot = responder,
                    senderName = agentNameFor(responder),
                    text = "รับทราบคำสั่งด่วน: \"$text\" — จัดคิวเป็นอันดับ 1 แล้ว (รอโปรเจกต์ที่กำลังรันเสร็จหรือกดหยุดก่อน)",
                    messageType = GroupMessageType.PROGRESS_UPDATE,
                )
            }
        }
    }

    fun saveProviderConfig(config: ProviderConfig, apiKey: String?) {
        providerManager.saveConfig(config, apiKey)
    }

    fun deleteProviderConfig(configId: String) {
        providerManager.deleteConfig(configId)
    }

    suspend fun testProviderConnection(configId: String): ConnectionTestResult {
        return providerManager.testConnection(configId)
    }

    suspend fun discoverProviderModels(configId: String): List<ModelInfo> {
        return providerManager.discoverModels(configId)
    }

    fun getProviderApiKey(configId: String): String {
        return providerManager.getApiKey(configId)
    }

    private fun agentNameFor(slot: AgentSlot): String {
        return _state.value.teamAgents.firstOrNull { it.config.slot == slot }?.config?.name ?: slot.defaultName
    }

    fun updateAgentConfig(slot: AgentSlot, config: AgentConfig) {
        val current = _state.value.teamAgents.toMutableList()
        val index = current.indexOfFirst { it.config.slot == slot }
        if (index >= 0) {
            val old = current[index]
            current[index] = old.copy(config = config)
            saveTeamAgents(current)
            _state.update {
                it.copy(
                    teamAgents = current,
                    toastMessage = "อัปเดต ${config.name} (${config.modelId.ifBlank { config.providerId }}) พร้อมใช้งานในแชทนี้ทันที",
                )
            }
            // Instant hot-swap: re-register updated profile in AutonomousSupervisorEngine & clear response cache
            // so the new model, provider, or agent is used immediately on the very next turn in the SAME chat!
            runCatching {
                autonomousSupervisorEngine.registry.register(
                    com.jarves.mh.multiagent.collab.AgentProfileMapper.fromAgentConfig(config),
                )
            }
            teamAgentRunner.clearResponseCache()
            recordCollaborationEvent(
                type = TeamEventType.TASK_ASSIGNED,
                senderSlot = slot,
                message = "Hot-swapped ${config.name} -> ${config.providerId}/${config.modelId} (${config.role.title})",
                priority = EventPriority.NORMAL,
            )
        }
    }

    fun toggleAgentEnabled(slot: AgentSlot) {
        val current = _state.value.teamAgents.toMutableList()
        val index = current.indexOfFirst { it.config.slot == slot }
        if (index >= 0) {
            val old = current[index]
            val updatedConfig = old.config.copy(isEnabled = !old.config.isEnabled)
            current[index] = old.copy(config = updatedConfig)
            saveTeamAgents(current)
            _state.update { it.copy(teamAgents = current) }
            runCatching {
                autonomousSupervisorEngine.registry.register(
                    com.jarves.mh.multiagent.collab.AgentProfileMapper.fromAgentConfig(updatedConfig),
                )
            }
            teamAgentRunner.clearResponseCache()
            recordCollaborationEvent(
                type = TeamEventType.TASK_ASSIGNED,
                senderSlot = slot,
                message = "${old.config.name} ${if (updatedConfig.isEnabled) "Enabled" else "Disabled"}",
                priority = EventPriority.NORMAL,
            )
        }
    }

    fun releaseFileLock(filePath: String, slot: AgentSlot) {
        fileLockManager.release(filePath, slot)
        _state.update { it.copy(teamFileLocks = fileLockManager.getActiveLocks()) }
        recordCollaborationEvent(
            type = TeamEventType.FILE_UNLOCKED,
            senderSlot = slot,
            filePath = filePath,
            message = "Lock released for $filePath by ${agentNameFor(slot)}",
            priority = EventPriority.NORMAL,
        )
    }

    fun updateBudget(limitUsd: Double, autoPause: Boolean) {
        _state.update {
            it.copy(budgetLimitUsd = limitUsd, budgetAutoPauseEnabled = autoPause)
        }
    }

    fun clearTeamCollaborationEvents() {
        _state.update { it.copy(teamCollaborationEvents = emptyList()) }
    }

    fun recordCollaborationEvent(
        type: TeamEventType,
        senderSlot: AgentSlot?,
        message: String,
        filePath: String? = null,
        commitSha: String? = null,
        priority: EventPriority = EventPriority.NORMAL,
    ) {
        val rawEvent = TeamCollaborationEvent(
            type = type,
            senderSlot = senderSlot,
            message = message,
            filePath = filePath,
            commitSha = commitSha,
        )
        val prioritized = PrioritizedCollaborationEvent(event = rawEvent, priority = priority)
        val current = _state.value.teamCollaborationEvents.toMutableList()
        current.add(0, prioritized)
        _state.update { it.copy(teamCollaborationEvents = current.take(100)) }
    }

    private fun useIsolatedAgyHomeFor(slot: AgentSlot): Boolean {
        val slotAuth = _state.value.teamAgentAntigravityAuth[slot]
        return slotAuth?.status == AntigravityAuthStatus.SIGNED_IN
    }

    private fun workspaceFileListingSummary(workspaceDir: File): String {
        val files = workspaceDir.walkTopDown()
            .maxDepth(4)
            .filter { f ->
                f.isFile &&
                    f.name != ".orqen_chat.json" &&
                    !f.path.contains("/.git/") &&
                    !f.path.contains("/.orqen_uploads/") &&
                    !f.path.contains("/node_modules/") &&
                    !f.path.contains("/build/") &&
                    !f.path.contains("/.gradle/")
            }
            .map { it.relativeTo(workspaceDir).path }
            .take(60)
            .toList()
        return if (files.isEmpty()) "(empty workspace)" else files.joinToString("\n")
    }

    private fun appendAgentLiveLog(slot: AgentSlot, line: String) {
        if (line.isBlank()) return
        val currentMap = _state.value.teamAgentLiveLogs
        val updatedList = ((currentMap[slot] ?: emptyList()) + line.trim()).takeLast(40)
        _state.update { it.copy(teamAgentLiveLogs = currentMap + (slot to updatedList)) }
    }

    private fun clearAgentLiveLog(slot: AgentSlot) {
        _state.update { it.copy(teamAgentLiveLogs = it.teamAgentLiveLogs + (slot to emptyList())) }
    }

    private fun applySafeAppControlAction(action: com.jarves.mh.skills.AppControlAction): Boolean {
        return when (action.action) {
            "set_theme" -> {
                val mode = when (action.value.uppercase()) {
                    "DARK", "มืด" -> com.jarves.mh.ui.theme.AppThemeMode.DARK
                    "LIGHT", "สว่าง" -> com.jarves.mh.ui.theme.AppThemeMode.LIGHT
                    else -> com.jarves.mh.ui.theme.AppThemeMode.SYSTEM
                }
                setThemeMode(mode)
                true
            }
            "set_language" -> {
                val code = when (action.value.lowercase()) {
                    "th", "thai", "ไทย" -> "th"
                    "en", "english", "อังกฤษ" -> "en"
                    else -> "system"
                }
                setLanguage(code)
                true
            }
            "set_owner_name" -> {
                if (action.value.isNotBlank()) {
                    setOwnerDisplayName(action.value.trim())
                    true
                } else false
            }
            "set_auto_skills" -> {
                val enabled = action.value.equals("true", true) || action.value == "1" || action.value.equals("on", true)
                skillManager.setAutoSelectEnabled(enabled)
                true
            }
            "toggle_skill" -> {
                if (action.target.isNotBlank()) {
                    val enabled = action.value.equals("true", true) || action.value == "1" || action.value.equals("on", true)
                    skillManager.toggleSkill(action.target, enabled)
                    true
                } else false
            }
            "set_auto_push_git" -> {
                val enabled = action.value.equals("true", true) || action.value == "1" || action.value.equals("on", true)
                setTeamAutoPushGitEnabled(enabled)
                true
            }
            "toggle_agent" -> {
                val slot = AgentSlot.fromId(action.target) ?: return false
                val targetAgent = _state.value.teamAgents.firstOrNull { it.config.slot == slot }
                if (targetAgent != null) {
                    val enabled = action.value.equals("true", true) || action.value == "1" || action.value.equals("on", true)
                    updateAgentConfig(slot, targetAgent.config.copy(isEnabled = enabled))
                    true
                } else false
            }
            "set_agent_model" -> {
                val slot = AgentSlot.fromId(action.target) ?: return false
                val targetAgent = _state.value.teamAgents.firstOrNull { it.config.slot == slot }
                if (targetAgent != null && action.value.isNotBlank()) {
                    updateAgentConfig(slot, targetAgent.config.copy(modelId = action.value.trim()))
                    true
                } else false
            }
            else -> false
        }
    }

    private fun cleanChatSummary(rawText: String, writtenFiles: List<String> = emptyList()): String {
        val withoutCodeBlocks = rawText
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        val base = when {
            withoutCodeBlocks.isNotBlank() -> withoutCodeBlocks.take(24_000)
            writtenFiles.isNotEmpty() -> "อัปเดตไฟล์เรียบร้อยแล้ว (${writtenFiles.size} ไฟล์)"
            else -> rawText.trim().take(12_000)
        }
        return if (writtenFiles.isNotEmpty()) {
            "$base\n\nไฟล์ที่อัปเดต: ${writtenFiles.joinToString(", ")}"
        } else {
            base
        }
    }

    private fun isQuotaOrRateLimitError(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("429") ||
            lower.contains("rate limit") ||
            lower.contains("quota") ||
            lower.contains("resource_exhausted") ||
            lower.contains("limit exceeded") ||
            lower.contains("too many requests")
    }

    private fun updateTeamBannerHealthFromError(errorText: String?, agentName: String? = null) {
        if (errorText.isNullOrBlank()) {
            _state.update {
                it.copy(
                    teamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.RUNNING,
                    teamBannerMessage = null,
                    teamBannerDetail = null,
                )
            }
            return
        }
        val lower = errorText.lowercase()
        val detail = buildString {
            if (!agentName.isNullOrBlank()) appendLine("AI ที่มีปัญหา: $agentName")
            append("สาเหตุ: ${errorText.take(320)}")
        }
        val isDisconnected = lower.contains("401") ||
            lower.contains("403") ||
            lower.contains("unauthorized") ||
            lower.contains("api key") ||
            lower.contains("not installed") ||
            lower.contains("sign in") ||
            lower.contains("login") ||
            lower.contains("auth")
        if (isDisconnected) {
            _state.update {
                it.copy(
                    teamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.DISCONNECTED,
                    teamBannerMessage = if (!agentName.isNullOrBlank()) "$agentName หลุดการเชื่อมต่อ · กรุณาเชื่อมต่อใหม่" else str(R.string.team_status_disconnected_agent),
                    teamBannerDetail = detail,
                )
            }
        } else {
            _state.update {
                it.copy(
                    teamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.WARNING,
                    teamBannerMessage = if (!agentName.isNullOrBlank()) "$agentName พบปัญหาชั่วคราว (กดดูรายละเอียด)" else str(R.string.team_status_warning_network),
                    teamBannerDetail = detail,
                )
            }
        }
    }

    private fun maybeAutoSwitchAgentModel(
        projectId: String,
        agent: AgentState,
        forceOnQuotaError: Boolean,
    ): AgentState {
        val contCfg = _state.value.teamContinuousConfig
        if (!contCfg.autoSwitchOnLowQuota) return agent

        val allQuotas = _state.value.teamAllProviderQuotas
        val quota = allQuotas[agent.config.providerId] ?: _state.value.teamOfficialQuota
        val threshold = (contCfg.quotaSwitchThresholdPercent.coerceIn(5, 50)) / 100f
        val isLowQuota = quota?.isAvailable == true && (quota.remainingPercent ?: 1f) <= threshold
        if (!forceOnQuotaError && !isLowQuota) return agent

        // Check if current provider itself is exhausted -> try switching to another connected provider with remaining quota!
        val currentModel = agent.config.modelId
        val providerExhausted = quota?.isAvailable == true && (quota.remainingPercent ?: 1f) <= threshold
        var targetProviderId = agent.config.providerId
        if (providerExhausted) {
            val altProvider = _state.value.providerConfigs.firstOrNull { cfg ->
                if (cfg.id == agent.config.providerId) return@firstOrNull false
                val hasCreds = cfg.type == com.jarves.mh.provider.core.ProviderType.ANTIGRAVITY ||
                    getProviderApiKey(cfg.id).isNotBlank()
                if (!hasCreds) return@firstOrNull false
                val q = allQuotas[cfg.id]
                // Verify candidate backup provider actually has quota remaining!
                q == null || !q.isAvailable || (q.remainingPercent ?: 1f) > 0.15f
            }
            if (altProvider != null) {
                targetProviderId = altProvider.id
            }
        }

        val knownAgy = _state.value.antigravityModels
        val isTargetAgy = targetProviderId == "preset-antigravity"
        val targetCfg = _state.value.providerConfigs.firstOrNull { it.id == targetProviderId }
        val codingFallbackPool = if (isTargetAgy) {
            // Strictly use ONLY real models available in the user's actual Antigravity plan!
            knownAgy.filter { it.isNotBlank() }
        } else {
            targetCfg?.availableModels?.filter { it.isNotBlank() }.orEmpty()
        }
        val chosenFallback = contCfg.fallbackModelId
            .takeIf { it.isNotBlank() && !it.equals(currentModel, ignoreCase = true) && (codingFallbackPool.isEmpty() || it in codingFallbackPool) }
            ?: codingFallbackPool.firstOrNull { !it.equals(currentModel, ignoreCase = true) }
            ?: return agent

        val updatedConfig = agent.config.copy(providerId = targetProviderId, modelId = chosenFallback)
        updateAgentConfig(agent.config.slot, updatedConfig)
        appendProjectTeamMessage(
            projectId = projectId,
            senderSlot = agent.config.slot,
            senderName = agent.config.name,
            text = "สลับโมเดลอัตโนมัติทันที: $currentModel → $chosenFallback (ตรวจสอบแล้วโควต้าสำรองพร้อมใช้งาน)",
            messageType = GroupMessageType.SYSTEM,
            workStatus = "สลับโมเดลสำรองอัตโนมัติ",
            workDetails = listOf("เดิม: $currentModel", "ใหม่: $chosenFallback ($targetProviderId)"),
        )
        return agent.copy(config = updatedConfig)
    }

    private fun recordRealtimeTeamTurnUsage(providerId: String, prompt: String, response: String) {
        val estTokens = ((prompt.length + response.length) / 4L).coerceAtLeast(120L)
        val estCostUsd = (estTokens / 1_000_000.0) * 1.50
        val currentEstMap = _state.value.teamAllProviderEstimated.toMutableMap()
        val prevEst = currentEstMap[providerId]
        val nextTokens = (prevEst?.estimatedTokens ?: 0L) + estTokens
        val nextUsd = (prevEst?.estimatedCostUsd ?: 0.0) + estCostUsd
        currentEstMap[providerId] = EstimatedUsage(
            estimatedTokens = nextTokens,
            estimatedCostUsd = nextUsd,
            estimatedCostThb = nextUsd * 35.0,
        )

        val currentLocalMap = _state.value.teamAllProviderLocal.toMutableMap()
        val prevLocal = currentLocalMap[providerId] ?: LocalUsage()
        currentLocalMap[providerId] = prevLocal.copy(
            totalRequests = prevLocal.totalRequests + 1,
            sessionTimeMillis = prevLocal.sessionTimeMillis + 15_000L,
            lastActiveMillis = System.currentTimeMillis(),
        )

        val totalTokens = currentEstMap.values.sumOf { it.estimatedTokens }
        val totalUsd = currentEstMap.values.sumOf { it.estimatedCostUsd }
        val totalReqs = currentLocalMap.values.sumOf { it.totalRequests }
        _state.update {
            it.copy(
                teamAllProviderEstimated = currentEstMap,
                teamAllProviderLocal = currentLocalMap,
                teamEstimatedUsage = EstimatedUsage(
                    estimatedTokens = totalTokens,
                    estimatedCostUsd = totalUsd,
                    estimatedCostThb = totalUsd * 35.0,
                ),
                teamLocalUsage = it.teamLocalUsage.copy(
                    totalRequests = totalReqs,
                    lastActiveMillis = System.currentTimeMillis(),
                ),
            )
        }
        refreshAllQuotas(forceRefresh = false)
    }

    private suspend fun executeTeamTurnWithAutoSwitch(
        projectId: String,
        projectName: String,
        agent: AgentState,
        workspaceDir: File,
        projectSlug: String,
        prompt: String,
        imageFiles: List<File> = emptyList(),
        onProgress: suspend (String) -> Unit,
    ): com.jarves.mh.runtime.TeamAgentExecutionResult {
        var activeAgent = maybeAutoSwitchAgentModel(projectId, agent, forceOnQuotaError = false)
        val accumulatedFiles = linkedSetOf<String>()
        var currentPrompt = prompt
        val maxSelfHealRetries = if (_state.value.teamContinuousConfig.autoResume) 3 else 0
        var attempt = 0
        var lastResult = com.jarves.mh.runtime.TeamAgentExecutionResult(responseText = "Cancelled", isError = true)

        while (_state.value.teamIsRunning) {
            val providerCfg = _state.value.providerConfigs.firstOrNull { it.id == activeAgent.config.providerId }
            val result = teamAgentRunner.executeTurn(
                agent = activeAgent,
                providerConfig = providerCfg,
                workspaceDir = workspaceDir,
                projectSlug = projectSlug,
                prompt = currentPrompt,
                useIsolatedAgyHome = useIsolatedAgyHomeFor(activeAgent.config.slot),
                enableCache = _state.value.teamContinuousConfig.tokenSaverCacheEnabled,
                imageFiles = imageFiles,
                knownAgyModels = _state.value.antigravityModels,
                onProgress = onProgress,
            )
            accumulatedFiles.addAll(result.writtenFiles)
            lastResult = result.copy(writtenFiles = accumulatedFiles.toList())

            if (!result.isError) {
                updateTeamBannerHealthFromError(null)
                recordRealtimeTeamTurnUsage(activeAgent.config.providerId, currentPrompt, result.responseText)
                return lastResult
            }

            // Decide if we can autonomously self-heal & resume!
            val isQuotaErr = isQuotaOrRateLimitError(result.responseText)
            val isTimeoutErr = result.responseText.contains("timed out", ignoreCase = true) ||
                result.responseText.contains("timeout", ignoreCase = true)
            val canRecover = (result.isRecoverable || isQuotaErr || isTimeoutErr) && attempt < maxSelfHealRetries && _state.value.teamIsRunning

            if (!canRecover) {
                updateTeamBannerHealthFromError(result.responseText, activeAgent.config.name)
                return lastResult
            }

            // Instant switch on quota/rate-limit error without long delay!
            if (isQuotaErr && _state.value.teamContinuousConfig.autoSwitchOnLowQuota) {
                val switched = maybeAutoSwitchAgentModel(projectId, activeAgent, forceOnQuotaError = true)
                if (switched.config.modelId != activeAgent.config.modelId || switched.config.providerId != activeAgent.config.providerId) {
                    activeAgent = switched
                    attempt++
                    onProgress("Switched immediately to ${activeAgent.config.modelId}")
                    continue
                }
            }

            attempt++
            val backoffMs = (2_000L * (1 shl (attempt - 1))).coerceAtMost(10_000L)
            val healMsg = "${activeAgent.config.name} พบปัญหาชั่วคราว · กำลังทำต่ออัตโนมัติ (ครั้งที่ $attempt/$maxSelfHealRetries)"
            val healDetail = "AI ที่มีปัญหา: ${activeAgent.config.name} (${activeAgent.config.modelId})\nสาเหตุ: ${result.responseText.take(320)}"
            _state.update {
                it.copy(
                    teamBannerHealth = com.jarves.mh.multiagent.ui.TeamBannerHealth.WARNING,
                    teamBannerMessage = healMsg,
                    teamBannerDetail = healDetail,
                )
            }
            updateTeamForegroundNotification(projectName, "${activeAgent.config.name}: กำลังกู้คืนและทำต่ออัตโนมัติ ($attempt/$maxSelfHealRetries)…")
            onProgress("Auto-recovery ($attempt/$maxSelfHealRetries): ${result.responseText.take(100)}")

            // Auto-switch to backup coding model on quota error or repeated timeout
            if ((isQuotaErr || (isTimeoutErr && attempt >= 2)) && _state.value.teamContinuousConfig.autoSwitchOnLowQuota) {
                val switched = maybeAutoSwitchAgentModel(projectId, activeAgent, forceOnQuotaError = true)
                if (switched.config.modelId != activeAgent.config.modelId) {
                    activeAgent = switched
                    onProgress("Switched to backup model: ${activeAgent.config.modelId}")
                }
            }

            delay(backoffMs)

            // Build autonomous self-healing continuation prompt with live workspace snapshot
            currentPrompt = buildString {
                appendLine("AUTONOMOUS SELF-RECOVERY (Attempt $attempt/$maxSelfHealRetries):")
                appendLine("The previous attempt was interrupted by: ${result.responseText.take(220)}")
                if (accumulatedFiles.isNotEmpty()) {
                    appendLine("Files already created/updated before interruption: ${accumulatedFiles.joinToString(", ")}")
                }
                appendLine("Current actual workspace files:")
                appendLine(workspaceFileListingSummary(workspaceDir))
                appendLine()
                appendLine("Think autonomously: inspect what is already done, DO NOT restart from scratch if files already exist, break the remaining work into concise fast steps so it finishes cleanly without timing out, and complete the original request below:")
                appendLine("---")
                appendLine(prompt)
            }
        }

        return lastResult
    }

    private suspend fun runTeamCycle() {
        val projectId = _state.value.runningTeamProjectId ?: _state.value.activeTeamProject?.id
        val project = _state.value.teamProjects.firstOrNull { it.id == projectId } ?: _state.value.activeTeamProject
        if (project == null) {
            stopTeamWork()
            return
        }
        val workspaceDir = File(getApplication<Application>().filesDir, "team_workspaces/${project.id}").apply { mkdirs() }
        var initialGreetingSent = false

        try {
            // Persistent always-on standby loop: stays alive waiting for Owner commands until Pause/Stop!
            while (_state.value.teamIsRunning) {
                val activeAgents = _state.value.teamAgents.filter { it.config.isEnabled }
                if (activeAgents.isEmpty()) {
                    pauseTeamWork()
                    return
                }
                val ceo = activeAgents.first()
                val coder = activeAgents.getOrNull(1) ?: ceo
                val reviewer = if (activeAgents.size >= 3) activeAgents.last() else ceo

                // Wait for Owner directive if queue is empty (do NOT re-spam welcome message after tasks/errors!)
                val initialRequest = pendingTeamDirectives.poll()
                if (initialRequest == null) {
                    if (!initialGreetingSent) {
                        initialGreetingSent = true
                        updateAgentActivities(ActivityType.WAITING)
                        appendProjectTeamMessage(
                            projectId = project.id,
                            senderSlot = ceo.config.slot,
                            senderName = ceo.config.name,
                            text = "ทีม AI พร้อมทำงานแล้ว — กำลังรอรับคำสั่งจาก ${effectiveOwnerName()} ครับ (พิมพ์สั่งในแชทนี้ได้เลย)",
                            messageType = GroupMessageType.PROGRESS_UPDATE,
                        )
                    }
                    delay(350L)
                    continue
                }

                initialGreetingSent = true
                _state.update { it.copy(teamPriorityDirectivesCount = pendingTeamDirectives.size) }

                val currentSpend = _state.value.teamEstimatedUsage?.estimatedCostUsd ?: 0.0
                if (autonomousEngine.isBudgetExceeded(currentSpend, _state.value.budgetLimitUsd, _state.value.budgetAutoPauseEnabled)) {
                    pauseTeamWork()
                    recordCollaborationEvent(
                        type = TeamEventType.TASK_BLOCKED,
                        senderSlot = ceo.config.slot,
                        message = "Budget Guard triggered: paused team at $${String.format("%.2f", currentSpend)}",
                        priority = EventPriority.CRITICAL,
                    )
                    return
                }

                val accumulatedRequirements = mutableListOf(initialRequest)
                val attachedImages = mutableListOf<File>()
                while (true) {
                    val img = pendingTeamImageFiles.poll() ?: break
                    attachedImages.add(img)
                }
                val allWrittenFiles = linkedSetOf<String>()
                val completedStepsSummary = mutableListOf<String>()
                var taskHadFatalError = false

                // STEP 1: CEO (Agent 1) analyzes Owner's request & creates the master plan
                updateAgentActivities(ActivityType.WAITING)
                clearAgentLiveLog(ceo.config.slot)
                appendAgentLiveLog(ceo.config.slot, "Analyzing Owner request: $initialRequest")
                updateAgentActivity(ceo.config.slot, ActivityType.PLANNING, "CEO Planning", "กำลังวิเคราะห์และวางแผน")
                updateTeamForegroundNotification(project.name, "${ceo.config.name} • กำลังวางแผน")
                val ownerName = effectiveOwnerName()
                val ceoPlanPrompt = buildString {
                    appendLine("You are ${ceo.config.name}, the CEO / Lead Architect of this AI Team.")
                    appendLine("Owner's name (address the user as): $ownerName")
                    appendLine("Your employee personality & tone: ${ceo.config.effectivePersonality()}")
                    appendLine("$ownerName's request: \"$initialRequest\"")
                    appendLine("Current workspace files (/workspace/${project.slug}):")
                    appendLine(workspaceFileListingSummary(workspaceDir))
                    appendLine()
                    appendLine("Create a concise, concrete implementation plan and delegate the exact tasks to @${coder.config.name} (Developer).")
                    appendLine("Reply in the same language as the Owner's request. Keep your chat summary concise and clean without dumping raw shell logs.")
                }
                val ceoResult = executeTeamTurnWithAutoSwitch(
                    projectId = project.id,
                    projectName = project.name,
                    agent = ceo,
                    workspaceDir = workspaceDir,
                    projectSlug = project.slug,
                    prompt = ceoPlanPrompt,
                    imageFiles = attachedImages,
                    onProgress = { step ->
                        appendAgentLiveLog(ceo.config.slot, step)
                        updateAgentActivity(ceo.config.slot, ActivityType.PLANNING, "CEO Planning", step)
                    },
                )
                appendAgentLiveLog(ceo.config.slot, "Plan completed and handed off to @${coder.config.name}")
                // CEO finishes planning -> immediately switch CEO to WAITING (resting) before Coder starts!
                updateAgentActivity(
                    ceo.config.slot,
                    ActivityType.WAITING,
                    "วางแผนเรียบร้อย",
                    "พักรอ ${coder.config.name} ทำงาน",
                )
                val ceoWorkDetails = _state.value.teamAgentLiveLogs[ceo.config.slot].orEmpty()
                val ceoChatText = if (ceoResult.isError) {
                    "[Warning] ${ceoResult.responseText}"
                } else {
                    val summary = cleanChatSummary(ceoResult.responseText)
                    if (ceo.config.slot != coder.config.slot && !summary.contains("@${coder.config.name}")) {
                        "@${coder.config.name} วางแผนเรียบร้อยแล้วครับ รับงานไปเขียนโค้ดต่อได้เลย:\n\n$summary"
                    } else summary
                }
                appendProjectTeamMessage(
                    projectId = project.id,
                    senderSlot = ceo.config.slot,
                    senderName = ceo.config.name,
                    text = ceoChatText,
                    messageType = GroupMessageType.TASK_ASSIGNMENT,
                    workStatus = if (ceoResult.isError) "วางแผนขัดข้อง" else "วางแผนเรียบร้อย · ส่งต่อ @${coder.config.name}",
                    workDetails = ceoWorkDetails,
                )
                if (ceoResult.isError) {
                    updateAgentActivities(ActivityType.WAITING)
                    updateTeamForegroundNotification(project.name, "พบปัญหาขณะวางแผน · กำลังรอคำสั่งถัดไป")
                    continue
                }
                completedStepsSummary.add("${ceo.config.name}: วางแผนงานและส่งต่อให้ ${coder.config.name}")

                // STEP 2 & 3: Coder Build + QA/CEO Verification & Fix Loop (up to 4 rounds, absorbing live Owner steering!)
                var lastFeedback: String? = null
                var round = 1
                val maxRounds = 4
                while (round <= maxRounds && _state.value.teamIsRunning) {
                    while (true) {
                        val extra = pendingTeamDirectives.poll() ?: break
                        accumulatedRequirements.add(extra)
                    }
                    _state.update { it.copy(teamPriorityDirectivesCount = pendingTeamDirectives.size) }

                    // Developer (Agent 2) codes / fixes real files in workspaceDir while CEO rests
                    clearAgentLiveLog(coder.config.slot)
                    appendAgentLiveLog(coder.config.slot, "Round $round started in /team_workspaces/${project.slug}")
                    updateAgentActivity(
                        coder.config.slot,
                        if (lastFeedback == null) ActivityType.WRITING_CODE else ActivityType.FIXING_BUG,
                        if (lastFeedback == null) "Building (Round $round)" else "Fixing bugs (Round $round)",
                        if (lastFeedback == null) "กำลังเขียนโค้ดและสร้างไฟล์" else "กำลังแก้ไขตามที่ตรวจสอบ",
                    )
                    updateTeamForegroundNotification(
                        project.name,
                        "${coder.config.name} • กำลัง${if (lastFeedback == null) "เขียนโค้ด" else "แก้ไขบั๊ก"}",
                    )
                    val tokenSaver = _state.value.teamContinuousConfig.tokenSaverCacheEnabled
                    val planCap = if (tokenSaver) 1200 else 3000
                    val feedbackCap = if (tokenSaver) 1200 else 2500
                    val coderPrompt = buildString {
                        appendLine("You are ${coder.config.name}, the Developer on this AI Team.")
                        appendLine("Owner's name (address the user as): $ownerName")
                        appendLine("Your employee personality & tone: ${coder.config.effectivePersonality()}")
                        appendLine("CEO (${ceo.config.name}) plan:")
                        appendLine(ceoResult.responseText.take(planCap))
                        appendLine()
                        appendLine("All Owner requirements to satisfy (include every single one):")
                        accumulatedRequirements.forEachIndexed { i, req -> appendLine("${i + 1}. $req") }
                        if (lastFeedback != null) {
                            appendLine()
                            appendLine("QA/Reviewer found issues in the previous round — fix ALL of them:")
                            appendLine(lastFeedback!!.take(feedbackCap))
                        }
                        appendLine()
                        appendLine("Current workspace files:")
                        appendLine(workspaceFileListingSummary(workspaceDir))
                        appendLine()
                        appendLine("Implement ONLY the files that need creating/editing directly in the workspace (do not repeat unchanged files). Keep your summary concise to save tokens.")
                    }
                    val coderResult = executeTeamTurnWithAutoSwitch(
                        projectId = project.id,
                        projectName = project.name,
                        agent = coder,
                        workspaceDir = workspaceDir,
                        projectSlug = project.slug,
                        prompt = coderPrompt,
                        imageFiles = attachedImages,
                        onProgress = { step ->
                            appendAgentLiveLog(coder.config.slot, step)
                            updateAgentActivity(coder.config.slot, ActivityType.WRITING_CODE, "Round $round", step)
                        },
                    )
                    allWrittenFiles.addAll(coderResult.writtenFiles)
                    appendAgentLiveLog(coder.config.slot, "Finished round $round. Handed off to @${reviewer.config.name}")
                    // Coder finishes coding -> immediately switch Coder to WAITING (resting) while Reviewer tests!
                    updateAgentActivity(
                        coder.config.slot,
                        ActivityType.WAITING,
                        "เขียนโค้ดเสร็จแล้ว",
                        "พักรอ ${reviewer.config.name} เทสระบบ",
                    )
                    val coderWorkDetails = _state.value.teamAgentLiveLogs[coder.config.slot].orEmpty() +
                        coderResult.writtenFiles.map { "Updated file: $it" }
                    val coderChatText = if (coderResult.isError) {
                        "[Warning] ${coderResult.responseText}"
                    } else {
                        val summary = cleanChatSummary(coderResult.responseText, coderResult.writtenFiles)
                        if (coder.config.slot != reviewer.config.slot && !summary.contains("@${reviewer.config.name}")) {
                            "@${reviewer.config.name} งานเสร็จเรียบร้อยแล้วครับ รบกวนช่วยตรวจสอบและเทสระบบได้เลย:\n\n$summary"
                        } else summary
                    }
                    appendProjectTeamMessage(
                        projectId = project.id,
                        senderSlot = coder.config.slot,
                        senderName = coder.config.name,
                        text = coderChatText,
                        messageType = GroupMessageType.COMPLETION_REPORT,
                        workStatus = if (coderResult.isError) "เขียนโค้ดขัดข้อง" else "เขียนโค้ดเสร็จแล้ว (${coderResult.writtenFiles.size} ไฟล์) · ส่งต่อ @${reviewer.config.name}",
                        workDetails = coderWorkDetails,
                    )
                    if (coderResult.isError) {
                        taskHadFatalError = true
                        break
                    }
                    completedStepsSummary.add("${coder.config.name} (รอบที่ $round): ${if (lastFeedback == null) "พัฒนาและเขียนโค้ด" else "แก้ไขจุดที่พบ"} (${coderResult.writtenFiles.size} ไฟล์)")

                    // If Owner added new instructions while Coder was busy -> loop immediately to add them!
                    if (pendingTeamDirectives.isNotEmpty()) {
                        while (true) {
                            val extra = pendingTeamDirectives.poll() ?: break
                            accumulatedRequirements.add(extra)
                        }
                        _state.update { it.copy(teamPriorityDirectivesCount = 0) }
                        lastFeedback = "Owner added new requirements above — please integrate them with your current work."
                        round++
                        continue
                    }

                    // QA / CEO Verification Step
                    clearAgentLiveLog(reviewer.config.slot)
                    appendAgentLiveLog(reviewer.config.slot, "Testing & verifying round $round output…")
                    updateAgentActivity(reviewer.config.slot, ActivityType.TESTING, "Testing & Verifying (Round $round)", "กำลังตรวจสอบและเทสระบบ")
                    updateTeamForegroundNotification(project.name, "${reviewer.config.name} • กำลังตรวจสอบระบบ")
                    val reviewPrompt = buildString {
                        appendLine("You are ${reviewer.config.name} verifying ${coder.config.name}'s work for $ownerName.")
                        appendLine("Owner's name (address the user as): $ownerName")
                        appendLine("Your employee personality & tone: ${reviewer.config.effectivePersonality()}")
                        appendLine("Owner requirements:")
                        accumulatedRequirements.forEachIndexed { i, req -> appendLine("${i + 1}. $req") }
                        appendLine()
                        appendLine("Developer report:")
                        appendLine(coderResult.responseText.take(1000))
                        appendLine()
                        appendLine("Actual files now in workspace:")
                        appendLine(workspaceFileListingSummary(workspaceDir))
                        appendLine()
                        appendLine("Inspect and test the files. Start your response with 'PASS:' if everything works and meets all requirements, OR start with 'FAIL:' followed by the exact bugs, root causes, and what ${coder.config.name} must fix.")
                    }
                    val reviewResult = executeTeamTurnWithAutoSwitch(
                        projectId = project.id,
                        projectName = project.name,
                        agent = reviewer,
                        workspaceDir = workspaceDir,
                        projectSlug = project.slug,
                        prompt = reviewPrompt,
                        imageFiles = attachedImages,
                        onProgress = { step ->
                            appendAgentLiveLog(reviewer.config.slot, step)
                            updateAgentActivity(reviewer.config.slot, ActivityType.TESTING, "Testing Round $round", step)
                        },
                    )
                    val passed = !reviewResult.isError &&
                        reviewResult.responseText.trimStart().startsWith("PASS", ignoreCase = true)
                    appendAgentLiveLog(reviewer.config.slot, if (passed) "Verification PASSED" else "Verification FAILED — sent back to @${coder.config.name}")
                    // Reviewer finishes testing -> update status cleanly
                    updateAgentActivity(
                        reviewer.config.slot,
                        if (passed) ActivityType.COMPLETED else ActivityType.WAITING,
                        if (passed) "ตรวจสอบผ่านเรียบร้อย" else "ส่งกลับให้แก้ไข",
                        if (passed) "พร้อมรายงาน $ownerName" else "พักรอ ${coder.config.name} แก้ไข",
                    )
                    val reviewerWorkDetails = _state.value.teamAgentLiveLogs[reviewer.config.slot].orEmpty()
                    val reviewChatText = if (reviewResult.isError) {
                        "[Warning] ${reviewResult.responseText}"
                    } else {
                        val summary = cleanChatSummary(reviewResult.responseText)
                        when {
                            passed -> "@${coder.config.name} ตรวจสอบและเทสระบบผ่านเรียบร้อยครับ!\n\n$summary"
                            coder.config.slot != reviewer.config.slot -> "@${coder.config.name} เทสระบบแล้วพบจุดที่ต้องแก้ไขครับ ช่วยแก้แล้วส่งกลับมาเทสใหม่ได้เลย:\n\n$summary"
                            else -> summary
                        }
                    }
                    appendProjectTeamMessage(
                        projectId = project.id,
                        senderSlot = reviewer.config.slot,
                        senderName = reviewer.config.name,
                        text = reviewChatText,
                        messageType = if (passed) GroupMessageType.APPROVAL else GroupMessageType.BUG_REPORT,
                        workStatus = when {
                            reviewResult.isError -> "ตรวจสอบขัดข้อง"
                            passed -> "เทสระบบผ่านเรียบร้อย"
                            else -> "พบจุดต้องแก้ไข · ส่งกลับ @${coder.config.name}"
                        },
                        workDetails = reviewerWorkDetails,
                    )

                    if (passed && pendingTeamDirectives.isEmpty()) {
                        completedStepsSummary.add("${reviewer.config.name}: ตรวจสอบและเทสระบบผ่านเรียบร้อย")
                        updateAgentActivity(ceo.config.slot, ActivityType.COMPLETED, "Approved & Ready", "เสร็จสิ้นพร้อมส่งมอบ")
                        break
                    } else {
                        completedStepsSummary.add("${reviewer.config.name} (รอบที่ $round): พบจุดต้องปรับปรุงและส่งกลับให้ ${coder.config.name}")
                        lastFeedback = reviewResult.responseText
                        round++
                    }
                }

                // Automatic Owner Completion Summary Report mentioning @$ownerName after every finished task!
                if (!taskHadFatalError && _state.value.teamIsRunning) {
                    val ownerSummary = buildString {
                        appendLine("@$ownerName ทีม AI ทำงานและตรวจสอบระบบเสร็จสมบูรณ์แล้วครับ!")
                        appendLine("รายงานสรุปงานเสร็จสิ้นถึง $ownerName")
                        appendLine("• คำสั่ง: ${accumulatedRequirements.joinToString(" / ").take(180)}")
                        if (allWrittenFiles.isNotEmpty()) {
                            appendLine("• ไฟล์ที่สร้าง/แก้ไข (${allWrittenFiles.size} ไฟล์): ${allWrittenFiles.joinToString(", ")}")
                        } else {
                            appendLine("• สถานะไฟล์: ตรวจสอบและดำเนินการตามแผนเรียบร้อย")
                        }
                        appendLine("• สรุปการทำงาน: ${completedStepsSummary.joinToString(" → ")}")
                    }.trim()
                    appendProjectTeamMessage(
                        projectId = project.id,
                        senderSlot = ceo.config.slot,
                        senderName = ceo.config.name,
                        text = ownerSummary,
                        messageType = GroupMessageType.COMPLETION_REPORT,
                        workStatus = "รายงานสรุปงานเสร็จสิ้นถึง @$ownerName (${allWrittenFiles.size} ไฟล์)",
                        workDetails = completedStepsSummary + allWrittenFiles.map { "ไฟล์: $it" },
                    )
                    notifyTeamTaskCompleted(
                        projectName = project.name,
                        summary = "${project.name}: งานเสร็จสิ้นแล้ว (${allWrittenFiles.size} ไฟล์) — ${initialRequest.take(80)}",
                    )
                }

                // Task done — return to WAITING standby for the next Owner command!
                updateAgentActivities(ActivityType.WAITING)
                updateTeamForegroundNotification(project.name, "${project.name} · เสร็จสิ้นแล้ว — รอรับคำสั่งถัดไป")
            }
        } finally {
            updateAgentActivities(ActivityType.WAITING)
            if (!_state.value.teamIsRunning) {
                stopTeamForegroundService()
                _state.update { it.copy(runningTeamProjectId = null) }
            }
        }
    }

    private fun updateAgentActivity(slot: AgentSlot, activity: ActivityType, task: String = "", lastAction: String = "", file: String = "") {
        val current = _state.value.teamAgents.toMutableList()
        val index = current.indexOfFirst { it.config.slot == slot }
        if (index >= 0) {
            val old = current[index]
            current[index] = old.copy(
                isWorking = (activity != ActivityType.WAITING && activity != ActivityType.PAUSED && activity != ActivityType.COMPLETED),
                activityState = AgentActivityState(
                    activity = activity,
                    currentTask = task,
                    currentFile = file,
                    lastAction = lastAction,
                    startedAtElapsedMs = SystemClock.elapsedRealtime(),
                ),
            )
            _state.update { it.copy(teamAgents = current) }
        }
    }

    private fun updateAgentActivities(activity: ActivityType) {
        val current = _state.value.teamAgents.map {
            it.copy(
                isWorking = (activity != ActivityType.WAITING && activity != ActivityType.PAUSED && activity != ActivityType.COMPLETED),
                activityState = it.activityState.copy(activity = activity),
            )
        }
        _state.update { it.copy(teamAgents = current) }
    }

    override fun onCleared() {
        stopAndroidLogcat()
        super.onCleared()
    }

    companion object {
        /** A long-running agent can poll a managed command hundreds of times. Keep the chat
         * responsive by retaining the latest useful steps instead of composing an unbounded list. */
        private const val MAX_VISIBLE_WORK_ITEMS = 32
        private val DEFERRED_FOLDER_METADATA_NAMES = setOf(".git", ".gradle", "build", "node_modules")
        private const val MAX_ACTIVITY_DETAIL_CHARS = 8_000
        private const val NOISY_ITEM_SCAN_CHARS = 320
        private const val TRANSCRIPT_WRITE_DEBOUNCE_MS = 500L
        private const val DIAGNOSTICS_REFRESH_COOLDOWN_MS = 1_000L
        private const val RECENT_EXIT_CACHE_MS = 60_000L
        private const val MINIMUM_INITIALIZATION_SCREEN_MS = 3_000L
        private const val MAX_EDITABLE_FILE_BYTES = 512_000L
        private const val MAX_PROJECT_TERMINAL_HISTORY = 100
        private const val MAX_PROJECT_TERMINAL_OUTPUT = 200_000
        private const val MAX_ANDROID_BUILD_LOG = 300_000
        private const val MAX_LOGCAT_LINES = 5_000
        private const val MEMORY_PRESSURE_TERMINAL_LINES = 20
        private const val MEMORY_PRESSURE_LOGCAT_LINES = 500
        private const val MEMORY_PRESSURE_TEXT_CHARS = 64 * 1024
        const val LOGCAT_GRANT_COMMAND = "adb shell pm grant com.jarves.mh android.permission.READ_LOGS"
        private const val MAX_ATTACHMENTS_PER_MESSAGE = 5
        private const val MAX_PROCESS_OUTPUT_BYTES = 512 * 1024
        private const val MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L
        private const val MAX_UPLOAD_BYTES = 8L * 1024L * 1024L * 1024L
        private const val MAX_IMPORTED_PROJECT_BYTES = 8L * 1024L * 1024L * 1024L
        private const val MAX_IMPORTED_ZIP_ENTRIES = 100_000
        private const val LEGACY_GITHUB_TOKEN_KEY = "GITHUB_APP"
        private const val GITHUB_DEVICE_URL = "https://github.com/login/device"
        private val GITHUB_DEVICE_CODE = Regex("\\b[A-Z0-9]{4}-[A-Z0-9]{4}\\b")
        private const val TEST_PROVIDER_DEFAULTS_VERSION = 1
        private const val TEST_OPENROUTER_BASE_URL = "https://openrouter.ai/api"
        private const val TEST_OPENROUTER_MODEL = "stealth/ox-alpha"
    }
}
