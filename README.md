# CIFI Assistant - Android Game Companion & Optical Dossier Engine

<p align="center">
  <b>A lightweight, privacy-focused, zero-polling Android companion app for <i>Cell: Idle Factory Incremental (CIFI)</i>.</b><br>
  Extracts multi-page account progression, gem upgrade ranks, and inventory stats into standardized Dossier text and JSON for tools like <b>cifi-toolkit</b>, Discord, and Reddit.
</p>

---

## ⚡ Key Highlights

- **0.0% Idle CPU Utilization**: Captures a single frame only when you tap the scanner orb—never records or polls continuously in the background.
- **Draggable Floating Orb**: Pops up a compact circular scanner orb with a live `[X/9]` page counter badge, tactile animations, and edge-snapping.
- **Notification Quick-Controls**: Hide or show the floating orb (`Hide Orb` / `Show Orb`), copy your compiled Dossier (`Copy Dossier`), or shut down the assistant (`Turn Off`) right from the Android notification shade without losing your screen capture session.
- **Instant On-Demand OCR + High-Contrast Filtering**: Captures a single frame buffer via `MediaProjection`, immediately tears down resources to maintain zero idle RAM overhead, and runs Google ML Kit on-device Text Recognition with resolution-independent coordinates and background-suppressed high-contrast crop passes for 100% offline accuracy.
- **Dual-Speed Classification**:
  1. *Fast Path (1-2ms)*: Direct pixel analysis of the cyan navigation tab bar (`Y = 35.5%`) to distinguish Profile Inventory vs Profile Stats.
  2. *Banner OCR*: Neural text classification on the gem title crop (`X: 18%..86%, Y: 20.5%..27%`) to detect any of the 7 Gem screens.
- **Master v1.1.6 Gem Upgrade Hierarchy**: Synchronizes all 52 unique Gem Upgrades across the 7 gems, unlocking upgrades dynamically based on the current gem quality level and filtering locked items from export.
- **One-Tap Multi-Page Dossier**: Compiles all captured screens into an organized dossier string or structured JSON copied straight to your clipboard.

---

## 📱 Supported Pages (9-Slot Memory Bank)

| Slot | Page Name | Short | Icon | Unlock Rules |
| :--- | :--- | :--- | :---: | :--- |
| 1 | **Player Profile (Inventory)** | Inventory | 🎒 | Primary, Special, Academy Materials, Hunter Loot, Dial Nodes |
| 2 | **Player Profile (Stats)** | Stats | 📊 | Ouroboros Stats, Run Cones, 20 All-Time Highest Stats |
| 3 | **The Exodus Gem** | Exodus | 🌌 | Max Quality V (7 Upgrades) |
| 4 | **Gem of Temporality** | Temporality | ⏳ | Max Quality IV (6 Upgrades) |
| 5 | **Gem of Innovation** | Innovation | 💡 | Max Quality III (9 Upgrades) |
| 6 | **Gem of Power** | Power | ⚡ | Max Quality III (9 Upgrades) |
| 7 | **Gem of Attraction** | Attraction | 🧲 | Max Quality IV (6 Upgrades) |
| 8 | **Gem of Creation** | Creation | 🪐 | Max Quality IV (11 Upgrades) |
| 9 | **The Evolution Gem** | Evolution | 🧬 | Max Quality I (4 Upgrades) |

---

## 🏗️ Architecture & Project Structure

```
CIFI Assistant/
├── app/
│   ├── build.gradle.kts                     # App dependencies (ML Kit, Coroutines, Serialization)
│   ├── proguard-rules.pro                   # R8 / Proguard rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml          # Services, Permissions, Launcher Activity
│       │   ├── assets/
│       │   │   └── cifi_profile_analyzer.html # Original optical web analyzer reference
│       │   ├── res/
│       │   │   ├── drawable/                # Cyber-themed scanner orb & vector icons
│       │   │   ├── layout/
│       │   │   │   ├── activity_main.xml    # Permission onboarding & Memory Bank manager
│       │   │   │   ├── overlay_floating_circle.xml # Draggable circular scanner orb
│       │   │   │   └── dialog_dossier_preview.xml  # Live in-app Dossier viewer
│       │   │   ├── values/                  # Cyber scifi color palette, strings, themes
│       │   │   └── xml/                     # Backup & data extraction rules
│       │   └── java/com/cifi/assistant/
│       │       ├── MainActivity.kt          # Permission onboarding, orb toggle & live status
│       │       ├── model/
│       │       │   ├── PageId.kt            # 9-page enum & metadata
│       │       │   ├── GemUpgradeData.kt    # 52 unique GU definitions (v1.1.6)
│       │       │   └── DossierModels.kt     # Inventory, Stats, & Gem data models
│       │       ├── repository/
│       │       │   └── DossierRepository.kt # 9-slot singleton store & dossier compilers
│       │       ├── engine/
│       │       │   ├── PixelAnalyzer.kt     # Fast RGB tab detector
│       │       │   └── OpticalAnalysisEngine.kt # ML Kit OCR & high-contrast crop filter
│       │       ├── service/
│       │       │   └── CaptureService.kt    # Foreground service, single-frame capture & notification controls
│       │       ├── overlay/
│       │       │   └── OverlayController.kt # Draggable WindowManager circular orb overlay
│       │       └── util/
│       │           ├── CifiNumberUtils.kt   # Scientific notation & OCR token sanitizer
│       │           ├── DebugScanStorage.kt  # Diagnostic scan & OCR output retention
│       │           ├── NotificationHelper.kt # Foreground notification with Hide/Show Orb & Turn Off actions
│       │           └── ClipboardHelper.kt   # System clipboard integration
│       ├── androidTest/java/com/cifi/assistant/
│       │   └── OcrDiagnosticTest.kt         # On-device OCR verification across all 9 pages
│       └── test/java/com/cifi/assistant/
│           └── DossierTest.kt               # Unit tests for unlock logic & formatting
├── build.gradle.kts                         # Root Gradle config
├── settings.gradle.kts                      # Module settings
└── gradle.properties                        # JVM & AndroidX settings
```

---

## 📥 Installation (Pre-built APK)

Download the latest **`CIFI-Assistant-v1.1.0.apk`** from the [**GitHub Releases page**](https://github.com/greendabre/cifi-assistant/releases/latest) and install it on your Android device (Android 8.0 / API 26 or newer).

## 🚀 How to Build from Source

### 1. Requirements
- **Android Studio** (Koala / Ladybug or newer recommended)
- **Android SDK** API 35 (`compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`)
- **Java 17 or 21**

### 2. Opening in Android Studio
1. Open Android Studio.
2. Select **Open** and choose the repository root folder (`CIFI Assistant`).
3. Allow Gradle to sync.
4. Click **Run** (`Shift + F10`) to deploy to your physical Android device.

---

## 🔒 Permission Setup in App

When you launch **CIFI Assistant**, only **2 permissions** are needed (no Accessibility Service required):
1. **Floating Overlay (`SYSTEM_ALERT_WINDOW`)**: Allows the compact circular scanner orb to display on top of CIFI.
2. **Screen Capture & Notification Controls (`MediaProjection`)**: Prompts the standard Android screen capture permission dialog once per session so the orb can snap a single frame on tap and display the quick-action controls in your notification bar.

---

## 🎮 In-Game Workflow & Notification Controls

1. Open **CIFI Assistant** and tap **Authorize** (or **Turn On Assistant**).
2. The draggable circular **Scanner Orb** (`[0/9]`) appears on screen, and a persistent control notification is added to your notification shade:
   - **`Hide Orb` / `Show Orb`**: Hide or show the floating scanner orb anytime from the notification bar without losing your active capture session.
   - **`Copy Dossier`**: Copy your compiled 9-page Dossier report directly from the notification.
   - **`Turn Off`**: Shut down the capture service, remove the floating orb, and dismiss the notification.
3. Open **Cell: Idle Factory Incremental (CIFI)** and navigate to any of the 9 supported pages (*Inventory*, *Stats*, or any of the *7 Gem* screens).
4. **Tap the Floating Orb**:
   - The orb briefly hides itself so it never appears in the screenshot, snaps a single frame, and runs on-device OCR + high-contrast crop filtering.
   - The orb pops with the detected page's icon (🎒, 📊, 🌌, ⏳, 💡, ⚡, 🧲, 🪐, or 🧬), increments the `[X/9]` badge, and **automatically copies the updated full Dossier to your clipboard**.
   - Rescanning any page safely overwrites that slot with the newest values.
5. Paste your Dossier directly into Discord, Reddit, or **cifi-toolkit** (or open **CIFI Assistant** to preview the report or copy as JSON).
