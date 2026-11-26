# ORQEN-Harness — AI Handoff

## Current Project State
- **Branch**: `main`
- **Previous Verified Commit**: `cbafbf7e85db7909e0ace08ab7975437b451ad15` (Part A.5)
- **Current Milestone**: **PART A.5.1 Completed** — Autonomous persistent project queue continuation (`superviseProjectQueue`), parallel independent subtask execution within `team.maxConcurrentAgents`, persistent image/attachment handoff (`TaskAttachmentCodec` -> `RuntimeExecutionRequest.imageFiles`) surviving queuing & process recreation, and end-to-end continuity tests (`PartA51TeamContinuityTest`).
- **Build & Test Status**: `42 suites / 284 tests / 284 PASSED / 0 FAILED / 0 SKIPPED`; `ORQEN-debug.apk` (`70M`) `BUILD SUCCESSFUL`.

## Active Issues & Next Recommended Action
1. Install `ORQEN-debug.apk` on a physical Android device with real provider credentials to complete `ANDROID_RUNTIME_TESTED` and `REAL_PROVIDER_TESTED` verification.
2. After on-device verification passes, remove the unused private `runTeamCycle()` helper from `MainViewModel.kt`.

## Standing Rules
- Never use plain `gradle build`; use the cached offline debug build command in `.ai/office/QUICK_START.md`.
- User standing rule: commit and `git push origin main` after completing, testing, and building each round of work (never commit `ORQEN-debug.apk`).
