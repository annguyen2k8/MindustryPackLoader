# MindustryPackLoader — Milestone P3 Closure Audit Report

**Date:** 2026-11-07  
**Scope:** Read-only audit and verification reconciliation of Roadmap Milestones P0 through P3.  
**Branch:** `master`  
**Latest Commit:** `a30275b feat(ci): configure v160 and v160.7 matrix and runtime compatibility`  
**Test Suite Status:** 203 tests passing, 0 failures, across 20 test classes.

---

## 1. Executive Summary

This audit evaluates the implementation, verification evidence, and release readiness of MindustryPackLoader through milestone **P3 (Expansion & Compatibility)**.
All core milestones (P0, P1, P2) and in-scope P3 features are validated with automated regression suites, static jar packaging checks (`verifyJar`), bundle parity checks (EN/VI), and desktop runtime smoke tests on Mindustry v160.7.

One architectural platform boundary is formally identified:
- **Android Share/Open-with (`ACTION_SEND` / `ACTION_VIEW`)** cannot be registered dynamically by a mod JAR due to Android OS intent dispatch requirements (`AndroidManifest.xml` static filters in the host APK). The ingestion logic and streaming safeguards are implemented and unit-tested in `AndroidShareHandler`, but system-wide chooser registration is **Blocked** without modifying Mindustry's host application.

---

## 2. Roadmap Reconciliation (P0 – P3)

| ID | Milestone Item | Status | Primary Commit | Verification Evidence |
|---|---|---|---|---|
| **P0** | **Data Correctness & Integrity** | **Verified** | | |
| H1 | Native map import file naming & conflict policy | Verified | `deb12fc` | `PackImporterTest` |
| H2 | Schematic import false-positive SUCCESS check | Verified | `deb12fc` | `PackImporterTest` |
| H4 | Validation rejects malformed `.msav` and `.msch` | Verified | `92797f3`, `8ae7e21` | `PackFormatTest` |
| H5 | Overwrite data loss prevention | Verified | `deb12fc`, `c2cc30c` | `PackImporterRollbackTest`, `UndoManagerTest` |
| M1 | Scan race condition & cancellation | Verified | `9122e0e`, `ba1c91b` | `TaskGenerationTest`, `PackScannerTest` |
| M2 | Filter state isolation across sessions | Verified | `c4c3cb0`, `65dd3f1` | `EntryFilterTest` |
| M3 | Exclude hidden items from import execution | Verified | `c4c3cb0`, `e27a407` | `EntryFilterTest`, `QuickImportAcceptanceTest` |
| M4 | Corrupt ZIP categorization | Verified | `33d3d7c` | `PackScannerTest` |
| M5 | SAF ZIP cache leak prevention | Verified | `c3baed2` | `StorageSourcesTest`, `AndroidShareHandlerTest` |
| M6 | Result dialog detail label layout (12x12 squeeze fix) | Verified | `ec35555`, `b65e674` | Runtime layout & UI tests |
| M8 | Open Maps screen destination fix | Verified | `c4c3cb0` | Code audit & runtime check |
| - | Regression test suite (malformed/overwrite/native) | Verified | Multiple | 203 passing tests |
| - | Clean working tree & baseline stability | Verified | Multiple | Clean git status |
| **P1** | **Performance, UX & Stability** | **Verified** | | |
| H3 | Background import, progress bar & cancellation | Verified | `ba1c91b` | `TaskGenerationTest`, `PackImporterTest` |
| M7 | Adaptive responsive result dialog | Verified | `c4c3cb0`, `ec35555` | UI layout inspection & scaling |
| - | Smart selection (all, none, invert, conflict-only) | Verified | `e27a407` | `QuickImportAcceptanceTest` |
| - | Search & multi-parameter filter | Verified | `e27a407` | `EntryFilterTest` |
| - | Recent sources with typed tags & individual delete | Verified | `e27a407`, `860a5a2` | `RecentSourcesTest`, live runtime |
| - | Partial scan failure reporting & permission toasts | Verified | `33d3d7c`, `166a5c7` | `PackScannerTest`, bundle validation |
| M9 | ZIP archive resource limits (entry count, uncompressed size) | Verified | `33d3d7c` | `PackScannerTest` |
| M10 | Directory cycle prevention & max recursion depth | Verified | `33d3d7c` | `PackScannerTest` |
| M11 | Content-based identification for extensionless entries | Verified | `92797f3`, `33d3d7c` | `PackFormatTest`, `PackScannerTest` |
| - | Storage labels localization parity | Verified | `166a5c7` | `BundleTest`, `LocalizationTest` |
| - | Small-screen UI responsiveness | Verified | `ec35555`, `c1df29a` | `CheckboxRowInteractionTest`, UI tests |
| **P2** | **Friction Reduction** | **Verified** | | |
| - | One-tap Quick Import fast-path | Verified | `e4762d1`, `7e0f722` | `QuickImportAcceptanceTest`, runtime UI |
| - | Multi-source queue scanning & aggregation | Verified | `e27a407` | `MultiSourceQueueTest` |
| - | Per-type conflict policy presets (Maps vs Schematics) | Verified | `833cb67` | `PackImporterTest`, settings persistence |
| - | Dry-run conflict preview dialog & plan breakdown | Verified | `37aa1c0` | `DryRunPlanTest`, `DryRunDialog` |
| - | Safe in-place Undo with pre-write backups | Verified | `c2cc30c` | `UndoManagerTest` (7 tests) |
| - | Content-hash duplicate detection (SHA-256) | Verified | `653d9a4` | `ContentHashTest`, runtime UI |
| **P3** | **Expansion & Compatibility** | **Mixed** | | |
| P3.1a | Desktop Drag-and-Drop | **Verified** | `7d68e72` | `FileDropHandlerTest` (7 tests), `ApplicationListener` |
| P3.1b | Android Share / Open-with | **Blocked** | `7d68e72` | Platform constraint (requires host APK manifest) |
| P3.2 | Thumbnail preview & metadata for Maps and Schematics | **Verified** | `015c308` | `PreviewMetadataTest` (10 tests), `MapInfoDialog` |
| P3.3 | Export import report (JSON & RFC 4180 CSV) | **Verified** | `ab18e89` | `ReportExporterTest` (8 tests), `ExportReportDialog` |
| P3.4a | CI Matrix workflow (`v160` and `v160.7`) | **Verified** | `a30275b` | `commitTest.yml`, multi-version Gradle build |
| P3.4b | Mindustry v160.7 Runtime Smoke Test | **Verified** | `a30275b` | Desktop live game execution & screenshot evidence |
| P3.4c | Mindustry v160.0 Runtime Smoke Test | **Partially Verified** | `a30275b` | 203 automated tests pass on v160; standalone GUI unverified |

---

## 3. CI Matrix Audit (`commitTest.yml`)

### 3.1 Structure & Configuration
- **Matrix Strategy:** Defined with `matrix.mindustryVersion: ['v160', 'v160.7']` and `fail-fast: false`, ensuring failures in one version do not terminate evaluation of the other.
- **Workflow Steps:**
  1. `actions/checkout@v4`
  2. Android build-tools PATH registration (`${ANDROID_HOME}/build-tools/34.0.0`)
  3. `actions/setup-java@v4` with Temurin JDK 17
  4. Execution permission grant (`chmod +x ./gradlew`)
  5. Test suite execution: `./gradlew test -PmindustryVersion=${{ matrix.mindustryVersion }}`
  6. Desktop JAR build & verification: `./gradlew jar check verifyJar -PmindustryVersion=${{ matrix.mindustryVersion }}`
  7. Universal Android/Desktop build: `./gradlew deploy -PmindustryVersion=${{ matrix.mindustryVersion }}`
  8. Artifact publishing: `actions/upload-artifact@v4` with namespaced artifact IDs (`MindustryPackLoader-v160` and `MindustryPackLoader-v160.7`).

### 3.2 Artifact Publishing & Remote Compatibility
- Ivy repository layout in `build.gradle.kts` maps directly to GitHub releases:
  `https://github.com/Anuken/Mindustry/releases/download/[revision]/dependencies.jar`
- Both `v160` and `v160.7` release dependencies are available remotely (HTTP 200).
- Local Gradle execution on both versions passed:
  - `.\gradlew.bat test "-PmindustryVersion=v160"` (BUILD SUCCESSFUL)
  - `.\gradlew.bat test "-PmindustryVersion=v160.7"` (BUILD SUCCESSFUL)
  - `.\gradlew.bat jar check verifyJar "-PmindustryVersion=v160"` (BUILD SUCCESSFUL)
  - `.\gradlew.bat jar check verifyJar "-PmindustryVersion=v160.7"` (BUILD SUCCESSFUL)

---

## 4. Cross-Version Binary Compatibility & API Audit

### 4.1 Identified Upstream API Break: `Maps.loadPreviews()`
- **v160 Signature:** `public void loadPreviews()`
- **v160.7+ Signature:** `public void loadPreviewsAsync()`
- **Adapter Solution (`PackImporter.java`):**
  ```java
  private static void invokeLoadPreviews(Object maps){
      if(maps == null) return;
      try{
          try{
              java.lang.reflect.Method m = maps.getClass().getMethod("loadPreviewsAsync");
              m.invoke(maps);
          }catch(NoSuchMethodException e){
              try{
                  java.lang.reflect.Method m = maps.getClass().getMethod("loadPreviews");
                  m.invoke(maps);
              }catch(NoSuchMethodException ignored){}
          }
      }catch(Throwable ignored){}
  }
  ```
  This eliminates runtime `NoSuchMethodError` when running a JAR compiled against one version on a game host of the other version.

### 4.2 Other Upstream APIs Inspected
- `MenuFragment.customButtons`, `buildMobile()`, `buildDesktop()`: Present with identical method/field descriptors on both v160 and v160.7 bytecode.
- `arc.ApplicationListener.fileDropped(Fi)`: Present on both versions as an Arc default interface method.
- `Schematics.getPreview(Schematic)`: Present and identical on both versions.
- `Maps.byName(String)`: Present and identical on both versions.
- `Maps.importMap(Fi)`, `Maps.removeMap(Map)`, `Maps.all()`: Present and identical on both versions.
- `Schematics.all()`, `add(Schematic)`, `remove(Schematic)`, `getLoadouts()`: Present and identical on both versions.

---

## 5. Verification Classification

### 5.1 Verified
1. **P0 Data Integrity & Bug Fixes:** All 13 items verified via unit tests and bytecode inspection.
2. **P1 Performance, UX & Polish:** All 11 items verified via unit tests and UI interaction tests.
3. **P2 Friction Reduction:** All 6 items verified with acceptance tests and settings persistence.
4. **P3.1 Desktop Drag-and-Drop:** Verified via `FileDropHandlerTest` and `ApplicationListener`.
5. **P3.2 Preview Thumbnail & Metadata:** Verified via `PreviewMetadataTest` and `MapInfoDialog`.
6. **P3.3 Export Import Report:** Verified via `ReportExporterTest` (JSON, RFC 4180 CSV, clipboard) and UI.
7. **P3.4 CI Matrix & Automated Tests (v160 + v160.7):** Verified via local parameterized Gradle runs.
8. **P3.4 Mindustry v160.7 Live Runtime Smoke Test:** Verified via mod loading, dialog display, checkbox interaction, and clean runtime logs.

### 5.2 Partially Verified
1. **Mindustry v160.0 Standalone GUI Smoke Test:**
   - Automated compile, packaging, and 203 unit tests pass cleanly against Mindustry v160 classes.
   - However, no interactive GUI session was executed on an actual v160 game executable on the host machine (only v160.7 executable is installed).

### 5.3 Not Verified
- None within the automated unit/integration test boundary.

### 5.4 Blocked
1. **Android Share / Open-with (`ACTION_SEND` / `ACTION_VIEW`):**
   - **Reason:** Android security model requires intent filters to be declared statically in `AndroidManifest.xml` within the APK package. Mindustry's mod loader loads mod classes dynamically at runtime from external storage; it cannot rewrite or inject components into the host APK manifest.
   - **Resolution Path:** Requires an upstream PR or patch to Mindustry's `android/AndroidManifest.xml` and `AndroidLauncher.java` to declare intent filters and dispatch URIs to mod event hooks.

---

## 6. Recommendations & Readiness for P4

1. **P3 Closure:**
   - Milestone P3 is substantially complete and stable for release.
   - The desktop features (Preview, Report Export, Drag-and-Drop, CI Matrix) are fully operational.
   - The single blocked feature (Android Share) is an architectural platform boundary that cannot be solved within mod scope; documenting it in the README and progress tracker is the correct engineering decision.

2. **P4 Transition Readiness:**
   - **Status:** **READY TO OPEN P4**.
   - Working tree is clean, test suite passes 100% across both target Mindustry versions (203/203), and build packaging conforms to release standards.
