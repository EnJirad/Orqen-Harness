# CHANGELOG: ORQEN Multi-Agent Workspace

All notable changes to the ORQEN project are documented here.

## [2.0.0] - 2026-11-24

### Brand & Identity
- Rebranded application from Mobile Harness to **ORQEN**.
- Replaced all application icons and visual identity assets with official ORQEN Squircle brand pack from `.ai/orqen-brand-assets.zip`.
- Integrated adaptive icons across `mipmap-hdpi`, `mipmap-mdpi`, `mipmap-xhdpi`, `mipmap-xxhdpi`, `mipmap-xxxhdpi`, `mipmap-anydpi-v26`, and `values/ic_launcher_background.xml`.
- Added high-resolution Play Store icon and vector artwork (`ic_orqen_mark`).

### Internationalization (i18n)
- Added full **Thai language (ภาษาไทย)** support:
  - Added `THAI` enum entry to `AppLanguage.kt`.
  - Added `<locale android:name="th"/>` to `locales_config.xml`.
  - Created complete Thai localization resources under `values-th/`:
    - `strings.xml`
    - `strings_backup.xml`
    - `strings_messages.xml`
    - `strings_runtime.xml`
    - `strings_settings.xml`
    - `strings_workspace.xml`

### Multi-Agent System (Up to 4 Agents)
- Created `com.jarves.mh.multiagent` package with support for up to 4 concurrent agents:
  - Agent 01: Team Leader / Architect / QA.
  - Agent 02: Engineer / Full-Stack Developer.
  - Agent 03: Specialist / Backend Developer / Researcher.
  - Agent 04: Independent Tester / Security & Code Reviewer.
- Enforced hard architectural cap of maximum 4 agents per team.
- Implemented independent state, provider credentials, system prompts, tool permissions, and session tracking per agent.

### Shared Project Workspace & Concurrency Control
- Implemented `FileLockManager` providing file ownership, conflict prevention, lock timeouts, and event notifications.
- Created `TeamEventBus` for real-time inter-agent communication.
- Implemented `SequentialPartPlanner` dividing projects into structured execution parts (Part 01 to Part 10).
- Implemented `GitCoordinator` with structured commits and `.agent/` GitHub coordination hub.

### Multi-Provider AI Integration
- Created modular `ProviderAdapter` architecture supporting:
  - Antigravity OAuth (Google login with `agy` CLI bridge).
  - OpenAI-Compatible API (`/v1/chat/completions`).
  - Anthropic API (`/v1/messages`).
  - Google Gemini Developer API (`generateContent`).
  - Custom API endpoints with arbitrary base URLs, custom headers, and protocol selection.
- Implemented live authenticated connection testing.
- Created Model Registry with discovery and manual entry.
- Secured API keys with Android KeyStore AES-256 GCM encryption.

### Real Provider Quota & Usage Monitor
- Implemented 3-tier quota tracking:
  - **Official Quota**: Real numbers directly from provider APIs.
  - **Estimated Usage**: Calculated from input/output tokens and model pricing tables.
  - **Local Usage**: On-device request, task, and error statistics.
- Added color-coded quota indicators (Green, Yellow, Red, Gray) and alerting.

### Continuous Autonomous Execution
- Eliminated hardcoded 1-hour session halts (`--print-timeout 60m`) with supervised execution.
- Implemented `ContinuousSupervisor` for autonomous task resumption, exponential backoff, and recovery.
- Enhanced `RuntimeExecutionService` foreground service with persistent notifications and wake lock management.

### Dashboard UI & Experience
- Added modern, mobile-first Team Dashboard featuring real-time Agent Cards and Activity Icons.
- Added Central Group Chat for user and agent team collaboration.
- Added Private Agent Chat for dedicated 1-on-1 agent inspection.
- Added Provider Management and Custom Endpoint configuration UI.
- Supported seamless Light and Dark modes.
