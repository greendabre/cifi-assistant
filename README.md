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
│       │   │   └── cifi_profile_analyzer.html # Original optical web analyzer
│       │   ├── res/
│       │   │   ├── drawable/                # Cyber-themed pill & vector icons
│       │   │   ├── layout/
│       │   │   │   ├── activity_main.xml    # Permission onboarding & Memory Bank manager
│       │   │   │   ├── overlay_floating_pill.xml # Floating pill with Scan & Dossier buttons
│       │   │   │   └── dialog_dossier_preview.xml # Live in-app Dossier viewer
│       │   │   ├── values/                  # Cyber scifi color palette, strings, themes
│       │   │   └── xml/                     # Accessibility config & security rules
│       │   └── java/com/cifi/assistant/
│       │       ├── MainActivity.kt          # Permission onboarding & live status
│       │       ├── model/
│       │       │   ├── PageId.kt            # 9-page enum & metadata
│       │       │   ├── GemUpgradeData.kt    # 52 unique GU definitions (v1.1.6)
│       │       │   └── DossierModels.kt     # Inventory, Stats, & Gem data models
│       │       ├── repository/
│       │       │   └── DossierRepository.kt # 9-slot singleton store & dossier compilers
│       │       ├── engine/
│       │       │   ├── PixelAnalyzer.kt     # Fast RGB tab detector (Y = 35.5%)
│       │       │   └── OpticalAnalysisEngine.kt # ML Kit OCR & metric parser
│       │       ├── service/
│       │       │   ├── CifiAccessibilityService.kt # Window event listener for CIFI
│       │       │   └── CaptureService.kt    # Foreground service & single-frame capture
│       │       ├── overlay/
│       │       │   └── OverlayController.kt # Draggable WindowManager overlay
│       │       └── util/
│       │           ├── NotificationHelper.kt # Foreground service notifications
│       │           └── ClipboardHelper.kt   # System clipboard integration
│       └── test/java/com/cifi/assistant/
│           └── DossierTest.kt               # Unit tests for unlock logic & formatting
├── build.gradle.kts                         # Root Gradle config
├── settings.gradle.kts                      # Module settings
└── gradle.properties                        # JVM & AndroidX settings
```

---

## 🚀 How to Build & Run

### 1. Requirements
- **Android Studio** (Koala / Ladybug or newer recommended)
- **Android SDK** API 35 (compileSdk & targetSdk 35, minSdk 26)
- **Java 17** (standard in modern Android Studio)

### 2. Opening in Android Studio
1. Open Android Studio.
2. Select **Open** and choose `G:\My Drive\Coding\CIFI Assistant` (or the repository root folder).
3. Allow Gradle to sync.
4. Click **Run** (`Shift + F10`) to deploy to your physical Android device or emulator.

---

## 🔒 Permission Setup in App

When you launch **CIFI Assistant** for the first time, tap the three permission buttons in order:
1. **Display Over Other Apps**: Enables Android `SYSTEM_ALERT_WINDOW` so the floating pill can appear above CIFI.
2. **Accessibility Service**: Directs you to Android Accessibility settings. Toggle on **CIFI Assistant** so the app can wake up automatically when CIFI launches and hide when you minimize it.
3. **Screen Capture Token**: Prompts the standard Android "Start recording or casting with CIFI Assistant?" dialog. Grant this once per session to enable instant on-demand single-frame scans.

---

## 🎮 In-Game Workflow

1. Open **Cell: Idle Factory Incremental (CIFI)**.
2. The **CIFI Assistant** pill will automatically slide into view:
   ```
   [ ::: | ⚡ Scan [0/9] | 📋 Dossier | ✕ ]
   ```
3. Navigate to any page you want to capture (e.g. *Inventory*, *Stats*, or any of the *7 Gem* screens).
4. Tap **`⚡ Scan`**.
   - A single frame is snapped and analyzed in under 50ms.
   - The badge updates: `⚡ Scan [1/9]`, `[2/9]`, etc.
   - Rescanning a page safely overwrites it with the newest values.
5. Once your screens are captured, tap **`📋 Dossier`**.
   - Your full, formatted multi-page report is instantly copied to your clipboard!
   - Paste directly into Discord, Reddit, or import into **cifi-toolkit**.
