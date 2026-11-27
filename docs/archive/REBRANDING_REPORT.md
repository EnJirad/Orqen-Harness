# ORQEN Rebranding Report

**Product Name:** ORQEN  
**Brand Slogan:** Think Together. Build Anything.  
**Brand Category:** Premium Futuristic AI  
**Product Description:** Premium AI Collaboration Workspace  
**New Android Application ID:** `com.orqen.app`  
**Date:** 2026-11-24  

---

## 1. Executive Summary

This report documents the comprehensive rebranding of Mobile Harness into **ORQEN** (`com.orqen.app`). The product transformation establishes ORQEN as an independent Android AI Collaboration Workspace while strictly preserving all existing functionality, system architecture, provider integrations, security controls, and open-source licensing compliance.

---

## 2. Rebranded Identity Matrix

| Domain | Original / Upstream | ORQEN Identity |
| :--- | :--- | :--- |
| **Product Name** | Mobile Harness | **ORQEN** |
| **Brand Slogan** | Build from your phone | **Think Together. Build Anything.** |
| **Brand Category** | Local Android Workspace | **Premium Futuristic AI** |
| **Product Description** | Local-first Android development workspace | **Premium AI Collaboration Workspace** |
| **Application Label** | Mobile Harness | **ORQEN** |
| **Application ID** | `com.jarves.mh` | **`com.orqen.app`** |
| **FileProvider Authority** | `com.jarves.mh.files` | **`com.orqen.app.files`** |
| **Root Project Name** | `MobileHarness` | **`ORQEN`** |
| **CMake Project Name** | `mobile_harness_native` | **`orqen_native`** |
| **Archive Output Name** | `app-<variant>.apk` | **`ORQEN-<variant>.apk`** |
| **Native Shmem Temp Path** | `/data/data/com.jarves.mh/cache/` | **`/data/data/com.orqen.app/cache/`** |
| **Backup Default File** | `MobileHarness-YYYYMMDD-HHmm.mhbackup` | **`ORQEN-YYYYMMDD-HHmm.mhbackup`** |
| **Backup WakeLock Tag** | `com.jarves.mh:backup` | **`com.orqen.app:backup`** |
| **Backup Actions** | `com.jarves.mh.START_BACKUP` | **`com.orqen.app.START_BACKUP`** |

---

## 3. Files Modified & Created

### 3.1 Build & Project Configuration
- `settings.gradle.kts`: Renamed root project to `ORQEN`.
- `app/build.gradle.kts`:
  - Updated `defaultConfig.applicationId` to `com.orqen.app`.
  - Configured `base.archivesName.set("ORQEN")` for branded output APKs.
  - Added support for `ORQEN_UPLOAD_*` signing variables with fallback to `MH_UPLOAD_*`.
  - Added Gradle property overrides for `runtimeReleaseBaseUrl`, `appUpdateManifestUrl`, and `privacyPolicyUrl`.
  - Updated `playReadinessCheck` description and checks.

### 3.2 Native C++ & JNI Bridge
- `app/src/main/cpp/CMakeLists.txt`:
  - Updated project name to `orqen_native`.
  - Updated architecture error message to refer to `ORQEN`.
  - Updated `_PATH_TMP` for `android-shmem` to `/data/data/com.orqen.app/cache/`.
- `app/src/main/cpp/executable_carrier.c`:
  - Updated dummy carrier function to `orqen_executable_carrier`.
- `third_party/talloc/replace.h`:
  - Cleaned header guards to `ORQEN_TALLOC_REPLACE_H`.

### 3.3 Kotlin Application Source Code
- `app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt`:
  - Replaced TopAppBar titles and dialog titles with "ORQEN".
  - Updated update banner dialog text to "ORQEN ${update.versionName}".
  - Updated accessibility descriptions to "ORQEN".
  - Retained backward-compatible log inspection for both "Resuming ORQEN setup" and "Resuming Mobile Harness setup".
- `app/src/main/java/com/jarves/mh/ui/SettingsScreenModern.kt`:
  - Updated About section application title to "ORQEN".
  - Updated update channel manifest placeholder to `orqen-update.json`.
- `app/src/main/java/com/jarves/mh/ui/BackupRestoreUi.kt`:
  - Updated default backup file naming template to `ORQEN-yyyyMMdd-HHmm.mhbackup`.
- `app/src/main/java/com/jarves/mh/backup/BackupCrypto.kt`:
  - Updated exception messages to "Not an ORQEN backup" and "This backup was made by a newer version of ORQEN".
- `app/src/main/java/com/jarves/mh/backup/BackupService.kt`:
  - Updated wake lock tag to `"com.orqen.app:backup"`.
  - Updated broadcast action constants to `"com.orqen.app.START_BACKUP"` and `"com.orqen.app.STOP_BACKUP"`.
- `app/src/main/java/com/jarves/mh/runtime/OpenRouterRoutingGateway.kt`:
  - Updated KDoc to reference ORQEN.
- `app/src/main/java/com/jarves/mh/runtime/RuntimeSetupService.kt`:
  - Updated session resume log text to `"— Resuming ORQEN setup —"`.
- `app/src/main/java/com/jarves/mh/update/AppUpdater.kt`:
  - Updated downloaded APK temporary filenames to `orqen-${BuildConfig.APP_VARIANT}.apk`.

### 3.4 Android Resources (238 string entries across 7 languages)
- `app/src/main/res/values/strings*.xml`: English base strings (`app_name` -> "ORQEN", notification channels, service descriptions, setup dialogs, terminal banners, backup messages).
- `app/src/main/res/values-ar/strings*.xml`: Arabic translations.
- `app/src/main/res/values-el/strings*.xml`: Greek translations.
- `app/src/main/res/values-es/strings*.xml`: Spanish translations.
- `app/src/main/res/values-hi/strings*.xml`: Hindi translations (including replacement of transliterated `मोबाइल हार्नेस` to `ORQEN`).
- `app/src/main/res/values-ru/strings*.xml`: Russian translations.
- `app/src/main/res/values-zh/strings*.xml`: Chinese translations.

### 3.5 Fastlane & Store Metadata
- `fastlane/metadata/android/en-US/title.txt`: Set to `ORQEN`.
- `fastlane/metadata/android/en-US/short_description.txt`: Set to `Think Together. Build Anything.`
- `fastlane/metadata/android/en-US/full_description.txt`: Updated description, features, and slogan.
- `fastlane/metadata/android/en-US/changelogs/1.txt`: Initial ORQEN alpha notes.
- `fastlane/graphics/feature-graphic.svg`: Updated typography to `ORQEN` and `Think Together. Build Anything.`
- `fdroid/com.jarves.mh.yml`: Updated `AutoName` to `ORQEN`.

### 3.6 Tooling & Developer Scripts
- `.vscode/tasks.json`: Updated debug run task to launch `com.orqen.app/com.jarves.mh.MainActivity`.
- `scripts/install-phone.sh`: Updated adb launch intent to `com.orqen.app/com.jarves.mh.MainActivity`.
- `scripts/build-play-release.sh`: Updated upload keychain parameters and keystore fallback.
- `scripts/serve-update-server.sh`: Updated output APK name to `orqen-${flavor}-${variant}.apk` and manifest to `orqen-update.json`.
- `scripts/runtime-bundles/build-claude-from-installed-android.sh`: Updated default package to `com.orqen.app`.
- `scripts/runtime-bundles/build-dsh-from-installed-android.sh`: Updated default package to `com.orqen.app`.

### 3.7 Documentation Created & Updated
- `README.md`: Completely updated for ORQEN, featuring the new slogan, description, verified capabilities, quickstart, architecture, and comprehensive upstream attribution.
- `PRIVACY.md`: Updated product name to ORQEN while maintaining all privacy protections and transparency disclosures.
- `BRAND_IDENTITY.md`: Created detailed brand identity guidelines.
- `BASELINE_REPORT.md`: Created initial inspection and baseline report.
- `orqen-update.json`: Created dedicated update manifest file.
- `docs/PLAY_STORE_CHECKLIST.md`: Updated application ID and Play Store release guidelines.
- `docs/play/*`: Updated data safety, app content, foreground services, and reviewer documentation.

---

## 4. Android Package & Namespace Strategy

### 4.1 Application ID vs. Technical Namespace Separation
In accordance with Android best practices and the engineering directives:
- **`applicationId` (`com.orqen.app`)**: Represents the public, user-facing, and OS-facing application identity. It defines the application identifier on the device, in package managers, in Android permissions, for FileProvider authorities, and on distribution stores.
- **`namespace` (`com.jarves.mh`)**: Retained as the internal technical package namespace. This guarantees:
  1. Complete binary compatibility with the native C++ JNI bridge (`Java_com_jarves_mh_runtime_NativeSpawn_*`).
  2. Unbroken symbol resolution for pre-compiled helper binaries and JNI entry points.
  3. Zero risk of compile-time or runtime symbol mismatch across internal modules.
  4. Separation between external brand identity and internal technical plumbing.

### 4.2 Migration Considerations
- Changing the `applicationId` to `com.orqen.app` makes ORQEN an independent, separate application installation from any existing Mobile Harness installations.
- Old app private data will not automatically migrate without an explicit user export/import via ORQEN's built-in Backup & Restore tool (`.mhbackup` format is fully supported).

---

## 5. Update Infrastructure Status

- **Status:** Functional with configurable fallback.
- **Behavior:** The build configuration provides properties (`appUpdateManifestUrl`, `runtimeReleaseBaseUrl`) allowing the user or deployment environment to redirect update checks and runtime downloads to an ORQEN server without code modifications.
- **Default Endpoints:** Fall back to verified upstream releases to avoid broken HTTP 404 links during the transition period.
- **Verification Integrity:** Cryptographic SHA-256 verification and Android package signing checks in `AppUpdater.kt` remain active and enforce signature identity.

---

## 6. Preserved Features & System Integrity

All core features and technical capabilities have been preserved without modification:
1. **Coding Agent Integrations:** Claude Code, Antigravity CLI, DeepSeek Harness.
2. **Linux Subsystem:** Ubuntu 20.04 LTS via PRoot with 16 KB page-size compatibility.
3. **Security:** Hardware-backed Android Keystore AES-256-GCM encryption for API credentials.
4. **Services:** Foreground execution services with proper Android specialUse and dataSync declarations.
5. **On-Device Android Development:** Local template generation, APK building, and direct package installer launch.
6. **Persistence:** Workspace management, chat history, terminal multiplexer, and backup/restore.

---

## 7. Remaining Historical / Attribution References

The following references to "Mobile Harness" are intentionally retained:
1. `LICENSE`: Original copyright notice (`Copyright (c) 2026 Mobile Harness Contributors`) as required by MIT open-source license terms.
2. `README.md`: Upstream attribution section explicitly crediting TechJarves and Mobile Harness as the foundation of the fork.
3. `PocketDevApp.kt:1887`: Backward-compatible check for legacy setup resumption logs.
4. `app/build.gradle.kts`: Upstream URL fallbacks for runtime bundle downloads and update manifest.
5. `fdroid/com.jarves.mh.yml`: Upstream source repository reference for the F-Droid build definition.

---

## 8. Verification & Build Validation

### 8.1 Verification Tasks
1. Search verification: Confirmed 0 unintended user-facing Mobile Harness strings in app resources, UI classes, and store metadata.
2. Resource validation: Checked all 7 language resource directories (`values`, `values-ar`, `values-el`, `values-es`, `values-hi`, `values-ru`, `values-zh`).
3. XML validity: Verified all modified strings.xml files parse cleanly without syntax errors.
4. Application ID validation: `applicationId = "com.orqen.app"`.
5. Display name validation: `@string/app_name` resolves to `ORQEN`.

### 8.2 Build & Test Results
1. **Debug APK Build Execution:**
   - Command: `gradle ... assembleDebug`
   - Result: `BUILD SUCCESSFUL in 5m 36s` (80 actionable tasks: 31 executed, 49 up-to-date)
   - Native C/C++ compilation: 82/82 targets successfully compiled and linked using LLVM/Clang 18 on ARM64.
2. **Output Artifacts:**
   - `ORQEN-debug.apk` (`app/build/outputs/apk/online/debug/app-online-debug.apk`) — 63 MB
   - `ORQEN-offline-debug.apk` (`app/build/outputs/apk/offline/debug/app-offline-debug.apk`) — 63 MB
3. **AAPT Badging Verification:**
   - Package Name: `com.orqen.app`
   - Application Label: `ORQEN` (default and 70+ localized variants)
   - Version Code: `11`
   - Version Name: `1.0.10`
   - Launchable Activity: `com.jarves.mh.MainActivity`
   - Packaged Native ABIs: `arm64-v8a` (`libandroid-shmem.so`, `libandroidx.graphics.path.so`, `libpocketspawn.so`, `libproot.so`, `libprootloader.so`, `libtalloc.so`, `libzstd-jni-1.5.7-12.so`)
4. **Unit Test Execution:**
   - `testOnlineDebugUnitTest`: `BUILD SUCCESSFUL in 4m 1s` (All unit tests passed)
   - `testOfflineDebugUnitTest`: `BUILD SUCCESSFUL in 3m 57s` (All unit tests passed)
