# IMPLEMENTATION PLAN: Transform Mobile Harness into ORQEN Multi-Agent Workspace

## Phase Breakdown & Execution Sequence

### Phase 1: Source & Brand Asset Integration
- [x] Inspect existing Mobile Harness source code, dependencies, build scripts, and assets.
- [x] Extract `.ai/orqen-brand-assets.zip`.
- [x] Integrate ORQEN brand assets into `app/src/main/res/` (adaptive icons, density mipmaps, launcher background, vector marks).
- [x] Replace old launcher vectors in `mipmap-anydpi` and `drawable/`.
- [x] Create core analysis and architecture documentation (`PROJECT_ANALYSIS.md`, `ARCHITECTURE.md`).

### Phase 2: Localization & Thai Language Addition (ภาษาไทย)
- [ ] Add `THAI("th", "Thai", "ไทย")` to `AppLanguage.kt`.
- [ ] Add `<locale android:name="th"/>` to `locales_config.xml`.
- [ ] Create `app/src/main/res/values-th/` with complete, professional Thai translations:
  - `strings.xml`
  - `strings_backup.xml`
  - `strings_messages.xml`
  - `strings_runtime.xml`
  - `strings_settings.xml`
  - `strings_workspace.xml`
- [ ] Add multi-agent, quota, and team string resources in all language files.

### Phase 3: Multi-Provider AI Architecture & Custom API Integration
- [ ] Build `com.jarves.mh.provider.core`:
  - `ProviderAdapter` interface
  - `ProviderProtocol` extensions: `OPENAI_CHAT`, `ANTHROPIC_MESSAGES`, `GEMINI_DEV_API`, `ANTIGRAVITY_OAUTH`
  - `ConnectionTestResult`, `ModelDiscoveryResult`
  - `OfficialQuota`, `EstimatedUsage`, `LocalUsage`
- [ ] Implement `OpenAiCompatibleAdapter`, `AnthropicAdapter`, `GeminiDevAdapter`, `AntigravityAdapter`, and `CustomProviderAdapter`.
- [ ] Implement live connection testing with secure key masking and Android KeyStore persistence.
- [ ] Build model discovery and manual model entry mechanisms.

### Phase 4: Multi-Agent AI Core & Shared Workspace (Max 4 Agents)
- [ ] Build `com.jarves.mh.multiagent`:
  - `AgentSlot` (1 to 4 limit enforcement)
  - `AgentRole` (Leader/Architect, Engineer, Specialist, Tester, Custom)
  - `AgentConfig` (Provider, Account, Model, Tools, Instructions)
  - `AgentActivity` & real-time activity tracking with iconography
  - `TeamGroup` model & team configuration wizard
- [ ] Build `FileLockManager`:
  - File ownership tracking
  - Conflict detection & prevention
  - Lock expiration & auto-release
- [ ] Build `TeamEventBus`:
  - Inter-agent messaging (`TASK_ASSIGNED`, `FILE_LOCKED`, `FILE_UPDATED`, `REVIEW_REQUESTED`, `BUG_FOUND`)
- [ ] Build `SequentialPartPlanner` (Parts 01 to 10 with Acceptance Criteria).
- [ ] Build `GitCoordinator` & `.agent/` directory generator.

### Phase 5: Continuous Autonomous Execution & Supervisor
- [ ] Address the 1-hour session limit:
  - Configure dynamic runtime execution parameters in `AntigravityRuntimeBridge.kt`.
  - Create `ContinuousSupervisor` to oversee agent tasks.
  - Implement auto-resume loop with task completion verification and exponential backoff.
  - Implement task checkpoints and process crash recovery.
  - Enhance `RuntimeExecutionService` with multi-agent progress notification and wake lock coordination.

### Phase 6: Dashboard UI, Team Workspace, & Group Chat
- [ ] Build `TeamDashboardScreen`:
  - Team overview header (Status, Part, Quota status, GitHub sync).
  - 4 distinct Agent Cards with real-time activity icons, current task, elapsed time, and actions.
  - File Locks & Ownership monitor.
- [ ] Build `CentralGroupChatScreen`:
  - Collaborative group feed featuring User, Agent 01, Agent 02, Agent 03, Agent 04.
  - Formatted messages (Task Assignment, Progress Update, Review Request, Bug Report, Approval).
- [ ] Build `PrivateAgentChatScreen`:
  - Direct 1-on-1 agent inspection, tool calls, and command logs.
- [ ] Build `ProviderManagementScreen`:
  - Multi-account management, custom endpoints, connection test UI, and model selector.
- [ ] Support both Light and Dark mode with clean, modern, minimal styling.

### Phase 7: Verification, Testing & Build
- [ ] Run comprehensive unit tests for Multi-Agent slots, Provider Adapters, Quota Tracker, and File Lock Manager.
- [ ] Verify Android compilation: assemble Debug APK using PocketDev-prescribed command.
- [ ] Validate APK generation.

### Phase 8: Git Commit & Remote Push
- [ ] Create clean git commit with descriptive summary.
- [ ] Configure remote `https://github.com/EnJirad/Orqen-Harness.git` and push using the authorized user token.
