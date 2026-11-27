# ORQEN Rebranding Baseline Report

**Date:** 2026-11-24  
**Project:** Rebrand Mobile Harness into ORQEN  
**Product Name:** ORQEN  
**Brand Slogan:** Think Together. Build Anything.  
**Brand Category:** Premium Futuristic AI  
**Proposed Android Application ID:** `com.orqen.app`  
**Product Description:** Premium AI Collaboration Workspace  

---

## 1. Upstream & Repository Baseline

- **Original Repository:** `https://github.com/techjarves/Mobile-Harness.git`
- **Upstream Commit SHA:** `367b207ab7b048b9510f594ca4b730419debb901`
- **Active Branch:** `main`
- **Git Status at Start:** Clean working tree, upstream fetched and tracked
- **Upstream Git Remote:** Kept as `upstream` (`https://github.com/techjarves/Mobile-Harness.git`)
- **Origin Git Remote:** `https://github.com/techjarves/Mobile-Harness.git` (Pushing to upstream repository strictly prohibited)

---

## 2. Architecture & Identity Baseline

### 2.1 Android Identity
- **Root Project Name:** `MobileHarness` (in `settings.gradle.kts`)
- **Application ID:** `com.jarves.mh` (in `app/build.gradle.kts`)
- **Namespace:** `com.jarves.mh`
- **Kotlin Package Declarations:** `com.jarves.mh.*`
- **FileProvider Authority:** `${applicationId}.files` (evaluates dynamically at runtime to `context.packageName + ".files"`)
- **Target SDK:** 28 (direct APK PRoot compatibility) / 36 (Play build)
- **Min SDK:** 28
- **Compile SDK:** 36
- **Version Code:** 11
- **Version Name:** `1.0.10`
- **Flavors:** `online`, `offline` (dimension: `runtimeDelivery`)

### 2.2 Native Code & JNI Bridge
- **CMake Project:** `project(mobile_harness_native C ASM)` in `app/src/main/cpp/CMakeLists.txt`
- **Native Libraries:**
  - `libpocketspawn.so` (JNI methods: `Java_com_jarves_mh_runtime_NativeSpawn_spawn`, `waitFor`, `kill`)
  - `libproot.so` & `libprootloader.so` (PRoot user-space emulator and loader)
  - `libtalloc.so`
  - `libandroid-shmem.so` (configured with `_PATH_TMP="/data/data/com.jarves.mh/cache/"`)
- **Carrier Executable:** `executable_carrier.c` (`mobile_harness_executable_carrier`)

### 2.3 Storage & Preferences
- **SharedPreferences:**
  - `pocket_preferences`: Stores project lists, chat configurations, theme, model preferences.
  - `pocket_secrets`: Stores encrypted API keys.
  - KeyStore Alias: `pocket-provider-key` (AES-256-GCM via AndroidKeyStore).
  - `pocket_installer`: Package installation tracking.
- **Backup System:**
  - Container format: `.mhbackup`
  - Magic bytes: `MHBK` (`0x4D, 0x48, 0x42, 0x4B`)
  - Default filename template: `MobileHarness-yyyyMMdd-HHmm.mhbackup`
  - Error messages: Currently reference "Mobile Harness"

### 2.4 Update & Release Infrastructure
- **Runtime Release Base URL:** `https://github.com/techjarves/Mobile-Harness/releases/download/runtime-2026.09.4`
- **In-App Update Manifest URL:** `https://github.com/techjarves/Mobile-Harness/releases/latest/download/mobile-harness-update.json`
- **Update File:** `mobile-harness-update.json`
- **Local Testing Server:** `scripts/serve-update-server.sh`
- **Play Release Script:** `scripts/build-play-release.sh`

---

## 3. Scope of Branding Occurrences

A search across the repository identified 807 occurrences of "Mobile Harness", "mobile-harness", "techjarves", and "com.jarves.mh".

### 3.1 Distribution
1. **User Interface Strings (`app/src/main/res/values*/`)**:
   - English (`values/`) and 6 localized variants (`values-ar/`, `values-el/`, `values-es/`, `values-hi/`, `values-ru/`, `values-zh/`).
   - `app_name`: currently `"Mobile Harness"` -> target: `"ORQEN"`.
   - Setup, runtime, notification, backup, and workspace strings.
2. **Kotlin Source Files (`app/src/main/java/`)**:
   - `PocketDevApp.kt`: TopAppBar titles, dialogs, about screen, update prompt, accessibility descriptions.
   - `SettingsScreenModern.kt`: About section title and update channel placeholder.
   - `BackupCrypto.kt`: Backup exception messages.
   - `BackupRestoreUi.kt`: Default backup filename.
   - `RuntimeSetupService.kt`: Log header message.
   - `AppUpdater.kt`: Temporary APK filename.
3. **Build Configurations**:
   - `settings.gradle.kts`: `rootProject.name = "MobileHarness"` -> `rootProject.name = "ORQEN"`.
   - `app/build.gradle.kts`: `applicationId = "com.jarves.mh"` -> `applicationId = "com.orqen.app"`.
   - `app/build.gradle.kts`: task descriptions, default properties.
4. **Native Code (`app/src/main/cpp/`)**:
   - `CMakeLists.txt`: project name and `_PATH_TMP` path (`/data/data/com.orqen.app/cache/`).
   - `executable_carrier.c`: carrier function name.
5. **Documentation & Release Metadata**:
   - `README.md`
   - `PRIVACY.md`
   - `fastlane/metadata/android/en-US/title.txt`, `full_description.txt`, `short_description.txt`
   - `fdroid/com.jarves.mh.yml`
   - `docs/PLAY_STORE_CHECKLIST.md`, `docs/play/*`
   - `mobile-harness-update.json`
6. **Scripts**:
   - `scripts/build-play-release.sh`
   - `scripts/install-phone.sh`
   - `scripts/serve-update-server.sh`
   - `scripts/runtime-bundles/build-claude-from-installed-android.sh`
   - `scripts/runtime-bundles/build-dsh-from-installed-android.sh`

---

## 4. Preservation & Technical Safety Boundaries

1. **Internal Technical Package Names:**
   - As mandated by the engineering requirements, internal Kotlin package structure `com.jarves.mh.*` is retained while the public `applicationId` is updated to `com.orqen.app`. This preserves native JNI function bindings (`Java_com_jarves_mh_runtime_NativeSpawn_*`) and avoids risky mass package migration.
2. **Third-Party Agent Integrations:**
   - "DeepSeek Harness" (`dsh`), "Claude Code", and "Antigravity CLI" are third-party integrations and tools, not the product itself. Their identifiers, protocols, and binaries are strictly preserved.
3. **Open-Source Compliance:**
   - The original MIT `LICENSE` and copyright notices are preserved.
   - Upstream attribution to the original project (`https://github.com/techjarves/Mobile-Harness`) is explicitly documented.
4. **Update Fallbacks:**
   - Existing upstream download endpoints will remain active fallbacks until an ORQEN-specific server is designated by the user, while build configuration is parameterized for easy future switching.
