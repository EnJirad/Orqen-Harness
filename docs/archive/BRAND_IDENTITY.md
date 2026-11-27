# ORQEN — Brand Identity Guide

## 1. Product Overview

- **Product Name:** ORQEN
- **Application Display Name:** ORQEN
- **Main Branding Text:** ORQEN
- **Brand Slogan:** Think Together. Build Anything.
- **Brand Category:** Premium Futuristic AI
- **Product Description:** Premium AI Collaboration Workspace
- **Project Type:** Android AI Development Workspace

---

## 2. Brand Positioning & Core Mission

ORQEN is designed as a premium, cutting-edge AI workspace built for engineers, creators, and builders who demand powerful, autonomous development capabilities directly on mobile devices.

### Mission
To bridge high-performance local computing with frontier AI reasoning, empowering developers to think collaboratively and build software anywhere.

### Category
**Premium Futuristic AI:** ORQEN emphasizes precision, autonomy, local sovereignty, and frictionless multi-agent orchestration without visual clutter or superficial gimmicks.

---

## 3. Brand Personality & Tone of Voice

- **Architectural & Rigorous:** Deeply technical, reliable, and transparent about capabilities and system limits.
- **Futuristic & Elevating:** Forward-looking, inspiring creation with confidence ("Think Together. Build Anything.").
- **Respectful & Sovereign:** Local-first, privacy-respecting, zero-telemetry, and hardware-backed security.
- **Clear & Uncompromising:** Avoids marketing hyperbole; promises only verified capabilities.

---

## 4. Naming Conventions

| Context | Approved Term | Disallowed / Legacy Terms |
| :--- | :--- | :--- |
| **Product Name** | ORQEN | Mobile Harness, MobileHarness, MH |
| **Full Slogan** | Think Together. Build Anything. | Build from your phone |
| **Category** | Premium Futuristic AI | — |
| **Descriptor** | Premium AI Collaboration Workspace | Android terminal app, hacky termux wrapper |
| **Android Display Name** | ORQEN | Mobile Harness |
| **Android Application ID** | `com.orqen.app` | `com.jarves.mh` |
| **Backup Archive Name** | `ORQEN-YYYYMMDD-HHmm.mhbackup` | `MobileHarness-YYYYMMDD-HHmm.mhbackup` |
| **Binary Output Artifact** | `ORQEN-<variant>.apk` | `app-<variant>.apk`, `mobile-harness-*.apk` |

---

## 5. Android Application Identity

- **Application ID:** `com.orqen.app`
- **Application Label (`@string/app_name`):** `ORQEN`
- **FileProvider Authority:** `com.orqen.app.files` (dynamically `${applicationId}.files`)
- **Internal Technical Namespace:** `com.jarves.mh` (retained internally for binary compatibility with C++ JNI process launcher `pocketspawn` and native loader)
- **Primary Native Libraries:** `libpocketspawn.so`, `libproot.so`, `libprootloader.so`, `libtalloc.so`, `libandroid-shmem.so`
- **Shared Memory Temp Path:** `/data/data/com.orqen.app/cache/`
- **Target Architecture:** ARM64 (`arm64-v8a`)

---

## 6. Visual Brand Assets Boundary Notice

As per product phase guidelines:
- The existing logo and asset geometries are retained for this rebranding release.
- No AI-generated or arbitrary visual redesigns are introduced.
- Future brand asset updates will be integrated when the approved visual asset package is delivered.
