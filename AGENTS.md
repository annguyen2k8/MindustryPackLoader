# Mindustry PackLoader

## Overview

A Mindustry Java mod that bulk-imports maps (`.msav`) and schematics (`.msch`) from ZIP archives or folders on Android and desktop. It validates entries, applies a duplicate policy (skip, overwrite, or rename), writes atomically, and hot-reloads game maps and schematics.

## Stack and commands

- **Language/runtime:** Java 17; Gradle Kotlin DSL (`build.gradle.kts`); Mindustry v160 for compile and tests.
- **Test framework:** JUnit 5.
- **Desktop jar:** `./gradlew jar` (Windows: `.\gradlew.bat jar`) -> `build/libs/MindustryPackLoaderDesktop.jar`.
- **Universal jar:** `./gradlew deploy` -> `build/libs/MindustryPackLoader.jar`; requires an Android SDK and `d8` in `PATH`.
- **Tests:** `./gradlew test` (Windows: `.\gradlew.bat test`).
- **Packaging and localization check:** `./gradlew verifyJar`.

## Architecture

- `src/packloader/PackLoaderMod.java` — mod entry point, UI button registration, content load.
- `src/packloader/core/PackFormat.java` — `.msav`/`.msch` stream validation and parsing, map rename, format limits.
- `src/packloader/core/PackScanner.java` — ZIP/folder scanning, pack type detection, Zip Slip checks, archive limits.
- `src/packloader/core/PackImporter.java` — import pipeline, duplicate policies, staging/atomic writes, rollback, native map/schematic registration, hot reload.
- `src/packloader/model/` — entries, reports, policies, filters.
- `src/packloader/ui/` — import, result, and storage-browser dialogs.
- `src/packloader/util/` — localization, main-thread dispatch, storage sources (SAF/desktop), task generation.
- `test/` — JUnit tests with a fake game harness.
- `assets/bundles/` — `bundle.properties` and `bundle_vi.properties` localization.
- `mod.json` and `mod.hjson` — mod metadata packaged into the jar.

## Conventions

- Java 17, 4-space indentation. Match the surrounding style: braces on the same line; use explicit imports where practical.
- Use Mindustry `arc`/`Fi` abstractions for cross-platform file access; keep Android and desktop behavior compatible.
- Treat ZIP input as untrusted. Validate entry names, sizes, and format headers; preserve Zip Slip protection and decompression limits.
- Preserve atomic staging and rollback in `PackImporter`; do not write directly to live game directories.
- Add or update tests under `test/` for core behavior; keep UI code thin.
- Add localization keys to both `bundle.properties` and `bundle_vi.properties`; `verifyJar` checks the packaged bundles.
- Update `mod.json` and `mod.hjson` versions together. Keep `minGameVersion` at 160 unless intentionally targeting another release.
- Do not commit local/untracked artifacts such as `session.json`; stage files explicitly.

## Project decisions

- 2026-11-02: Keep import writes staged and atomic, with rollback on failure. Native registration and fallback extraction are both supported.
- 2026-11-02: Target Mindustry v160 and Java 17, matching `build.gradle.kts`.
- 2026-11-03: Implement durable pre-write backups, SHA-256 integrity verification, and safe in-place undo for maps and schematics.

## Shared context

Global user memory is loaded from `~/.agent-memory/` by both Antigravity and OpenCode. Keep this file project-specific.

Read `.agents/handoff.md` from disk at the start of each task before changing code. Antigravity also inlines it here:

@[Current handoff](.agents/handoff.md)

OpenCode loads the same handoff through `opencode.json`.
