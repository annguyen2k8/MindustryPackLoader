![Logo](icon.png)
# Mindustry PackLoader
![Mindustry](https://img.shields.io/badge/Mindustry-v160+-EE82EE?style=for-the-badge)
[![Latest Release](https://img.shields.io/github/v/release/annguyen2k8/MindustryPackLoader?style=for-the-badge&logo=github&color=32CD32)](https://github.com/annguyen2k8/MindustryPackLoader/releases/latest)
[![Total Downloads](https://img.shields.io/github/downloads/annguyen2k8/MindustryPackLoader/total?style=for-the-badge&logo=github&color=2E7D32)](https://github.com/annguyen2k8/MindustryPackLoader/releases)

A Mindustry Java mod for bulk importing maps (`.msav`) and schematics (`.msch`) directly from ZIP archives or folders on Android and Desktop.


## Features

- **Bulk Import:** Import dozens of maps and schematics from `.zip` packs or folders in one operation.
- **Smart Conflict Handling:** Choose how to resolve duplicate files:
  - **Skip:** Keep existing game files intact.
  - **Overwrite:** Replace existing files with imported versions.
  - **Rename:** Automatically append numbers (`_1`, `_2`) to keep both.
- **Instant Hot-Reload:** Automatically reloads game maps and schematics immediately without restarting Mindustry.
- **Safe & Validated:** Built-in Zip Slip protection, file header verification for `.msch` and `.msav` streams, and atomic writes via temporary files.
- **Mobile-Optimized:** Designed for Android with touch-friendly controls, multi-selection checkboxes, search filtering, and System File Picker (SAF) support.
- **Seamless In-Game Integration:** Direct action buttons located on the **Main Menu**, **Schematics**, **Custom Game**, and **Map Editor** screens.


## Installation

1. Download the latest `MindustryPackLoader.jar` from Releases (or build locally).
2. Launch Mindustry, navigate to **Mods** > **Import Mod** > **Import from File**.
3. Select `MindustryPackLoader.jar` and enable the mod.


## Usage

### Opening PackLoader
- **Main Menu:** Click the **Pack Loader** button to open the loader dialog.
- **Schematics / Maps Menus:** Click **Import .zip** or **Scan Folder** directly inside the respective dialogs.

### Import Workflow
1. **Choose Source:** Select a `.zip` pack via the file picker or browse a device folder.
2. **Review & Select:** Check the items you want to import, or use **Select All** / **Deselect All**.
3. **Select Conflict Policy:** Pick **Skip**, **Overwrite**, or **Rename**.
4. **Import:** Tap **Import Selected**. Review the import summary and tap to open your updated Schematics or Maps library.


## Building & Testing

### Requirements
- **JDK 17**
- **Android SDK** (API 21+, `d8` in `PATH` and `ANDROID_HOME` configured for Android builds)
- Target: **Mindustry v160+**

### Commands

- **Run Unit Tests (45 tests):**
  ```bash
  ./gradlew test
  ```
- **Build Desktop Mod (`MindustryPackLoaderDesktop.jar`):**
  ```bash
  ./gradlew jar
  ```
  Output: `build/libs/MindustryPackLoaderDesktop.jar`

- **Build Universal Mod with Android Support (`classes.dex`):**
  ```bash
  ./gradlew deploy
  ```
  Output: `build/libs/MindustryPackLoader.jar`

- **Verify Localization & JAR Packaging:**
  ```bash
  ./gradlew verifyJar
  ```
