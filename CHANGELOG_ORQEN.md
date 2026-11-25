# Changelog — ORQEN Multi-Agent AI Workspace

## [1.0.0-orqen-multiagent] — 2026-11-25

### Highlights
Transformed Mobile Harness into **ORQEN Multi-Agent AI Workspace** — a full-featured, Thai-localized, autonomous 4-agent collaborative Android engineering platform.

### Added
- **Brand & Localization (Part 00)**:
  - Extracted and applied full ORQEN brand assets (adaptive squircle icons, notification marks, store graphics).
  - Added complete Thai (`values-th`) localization across all screens and multi-agent dialogs.
- **Hub & Architecture (Parts 01–02)**:
  - Established `.agent/` GitHub coordination hub (`communication/`, `instructions/`, `management/`, `reports/`, `testing/`, `state/`).
  - Implemented `AgentLifecycleStateMachine` (11 validated states), `IFileLockCoordinator`, `IQuotaEvaluator` (USD & THB @ 35.0), `IGitHubSyncCoordinator`, and `PrioritizedCollaborationEvent`.
- **Multi-Agent Frontend & UX (Part 03)**:
  - Built `AgentDetailScreen` (custom name, role, model, prompt templates, temperature/tokens, held file locks).
  - Built `QuotaManagementScreen` (3-tier quota tracking, USD/THB toggle, Budget Guard & auto-pause).
  - Built `ActivityFeedScreen` (priority filters & real-time collaboration audit log).
  - Enhanced `TeamDashboardScreen` with 7 navigation modes and inline controls.
- **Autonomous Backend & User UX Upgrades (Part 04)**:
  - Enhanced `ContinuousSupervisor` (`Retry-After` parsing + Exponential Backoff + 45-min PRoot auto-checkpointing) shared across **Solo Projects** and **AI Team**.
  - Added Solo Continuous Execution panel directly in `ProjectsScreen`.
  - Fixed AI Team top inset/camera cutout spacing (`Scaffold` + `TopAppBar`), set default 2 agents with `+ Add Agent` up to 4 max, persistent custom agent names shared across prompts/chat/locks, and full Git/Folder/ZIP/GitHub project controls inside AI Team.
  - Added inline Antigravity CLI check & Google OAuth login directly inside AI Team, all-provider quota display, Solo connected-provider quota card in `AgentScreen`, and `BackHandler` across all tabs and sub-screens.
- **Shared/Multi-ID Provider Orchestration (Part 05)**:
  - Built `ProviderOrchestrator` supporting BOTH **Shared Provider ID across multiple agents with different models** AND **independent separate IDs per agent**, plus Smart Model Fallback.
  - Upgraded `ProviderManagementScreen` and `AgentDetailScreen` with API Key editor, auto model discovery, and live Model Dropdown selectors.
  - Built `GitHubSyncCoordinator` implementing `IGitHubSyncCoordinator`.
- **Verification, Hardening & Security Audit (Parts 06–08)**:
  - Added `@Synchronized` lock safety in `FileLockManager` verified under 4-thread concurrency stress testing (`Part06ConcurrencyAndE2ETest`).
  - Eliminated all Kotlin compiler warnings in multi-agent & provider modules, capped state machine history to 100 entries, and added force quota cache invalidation (`Part07HardeningTest`).
  - Completed repository-wide secret scan (`0` leaks), hardened path traversal protection in `FileLockManager` and `HubExchangeService`, and verified max 4-agent invariant (`Part08SecurityAuditTest`).
