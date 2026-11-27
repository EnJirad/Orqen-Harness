# PROJECT ANALYSIS: Mobile Harness to ORQEN Multi-Agent AI Workspace

## 1. Executive Summary

This document provides a comprehensive code analysis of **Mobile Harness**, identifying the baseline architecture, existing runtime capabilities, execution boundaries, authentication flows, and the technical changes required to evolve it into **ORQEN** — a professional Multi-Agent AI Development Workspace supporting up to 4 collaborative agents, provider-independent multi-model routing, genuine provider quota monitoring, continuous autonomous execution, a shared project workspace with file lock coordination, a central group chat, and GitHub-centered workflows.

---

## 2. Baseline Architecture & Technology Stack

- **Platform**: Android Native Application.
- **Language**: Kotlin 2.1.10 (Target JVM 17).
- **UI Toolkit**: Jetpack Compose (BOM 2025.02.00, Material 3, Material Icons Extended).
- **Architecture Pattern**: Unidirectional Data Flow (MVI / MVVM), StateFlow, Coroutines.
- **Native / Linux Execution**:
  - Embedded PRoot engine (`libproot.so`, NDK 28 for 16 KiB page size support on Android 15/16).
  - Private guest Linux rootfs filesystem unpacked in internal storage (`/data/user/0/com.orqen.app/files/rootfs`).
  - Terminal emulation & native CLI execution (`NativeSpawnProcess`, `executable_carrier.c`).
- **Installed Agent Engines**:
  - `antigravity` (`agy` CLI guest binary).
  - `claude-code` (Node.js-based Anthropic coding agent CLI).
  - `deepseek-harness` (`dsh` Python/pip-based agent CLI).
- **Background Processing**:
  - `RuntimeExecutionService` (Android Foreground Service with `specialUse` type and `WAKE_LOCK`).
  - `RuntimeSetupService` (Foreground service for downloading/unpacking rootfs & agent bundles).
- **Authentication & Secrets**:
  - `ApiKeyVault`: Android KeyStore-backed AES-256 GCM encryption (`KeyGenParameterSpec`, alias `pocket-provider-key`).
  - `AntigravityAuthController`: Local loopback OAuth receiver and token management for Google / Antigravity login.
  - `GitHubClient`: GitHub Device Flow OAuth (`login/device/code`).
- **Persistence**:
  - SharedPreferences (`pocket_preferences`, `pocket_secrets`).
  - Disk checkpoints and snapshots (`WorkspaceCheckpoints` in `files/checkpoints/<projectId>/`).

---

## 3. Analysis of Existing Limitations & Root Causes

### 3.1 The "1-Hour Session Timeout" Root Cause
Investigation of `AntigravityRuntimeBridge.kt` revealed:
- Line 701 explicitly sets: `addAll(listOf("--print-timeout", "60m"))`.
- When the `agy` CLI process reaches this 60-minute limit, it gracefully or abruptly shuts down, returning a finished status.
- Furthermore, the UI (`MainViewModel.kt`) lacked an autonomous supervisor loop; when a session finished, it posted a completion state to the user and required a manual "Continue" click.
- Claude Code and other CLIs also have conversational turn limits or subagent timeouts.
- Solution: Make execution timeouts dynamic and supervised; implement an autonomous resumption loop that inspects task completion criteria, checkpoints progress, and re-invokes the agent with resumed session/trajectory state.

### 3.2 Single-Agent vs. Multi-Agent Team
- Currently, the application only runs a single active agent (`agentKind` in `AppPreferences`, single `activeSessionId` in bridges).
- There is no concept of agent roles, peer communication, file locking, or task delegation among up to 4 agents.
- Solution: Build an `AgentOrchestrator` and `TeamManager` supporting up to 4 distinct agent slots (`Agent 01` to `Agent 04`), each possessing distinct configurations, roles, provider connections, and system prompts.

### 3.3 Provider Independence & Custom API Endpoints
- While `ProviderApiClient` had endpoints for Anthropic, OpenRouter, and NVIDIA NIM, it lacked full protocol-normalized adapters for arbitrary OpenAI-compatible endpoints (`/v1/chat/completions`), Anthropic Messages (`/v1/messages`), and Gemini Developer API (`generateContent`).
- Solution: Implement a modular `ProviderAdapter` system with OpenAI-compatible, Anthropic, Gemini, Antigravity OAuth, and Custom API adapters, supporting custom base URLs, protocols, custom headers, and live connection testing.

### 3.4 Quota & Usage Classification
- Previous versions lacked real-time provider quota inspection.
- Solution: Disambiguate into 3 clear categories:
  1. **Official Quota**: Real numbers returned by provider APIs (e.g. Antigravity/Gemini rate limits, OpenRouter key credits). If unavailable, explicitly display "Unavailable".
  2. **Estimated Usage**: Calculated locally based on token counts and published pricing.
  3. **Local Usage**: Application runtime metrics (requests, sessions, tasks, local errors).

### 3.5 Shared Workspace & File Conflict Prevention
- All agents in a team must operate in the same underlying workspace folder (`/workspace/<projectSlug>`).
- Without concurrency controls, simultaneous file edits lead to race conditions and corrupt source trees.
- Solution: Implement `FileLockManager` with file ownership, lock timeouts, conflict detection, and event broadcasting.

### 3.6 GitHub Collaboration Hub
- Need a structured `.agent/` directory schema within repositories for asynchronous task management, instructions, and communication history between agents and Git checkpoints.

---

## 4. Preservation of Existing Capabilities

The following proven features will be strictly preserved:
1. Low-level PRoot runtime execution and page-size compatibility.
2. Offline runtime bundles and fast cached builds.
3. Android Keystore AES-GCM encryption for credentials.
4. Git clone, commit, diff, and change management.
5. Android project template generation and APK compilation pipeline.
6. Existing Antigravity Google OAuth authentication.
