# Current handoff

> Replace this file at the end of each task. Keep only the current state; do not append an endless history.

- **Status:** complete
- **Last updated:** 2026-11-10 14:27 +07:00
- **Updated by:** Antigravity / Gemini 3.8 Flash (High)
- **Task:** Resolve Android Scoped Storage EACCES read failure on active SAF streams.
- **Branch:** dev; upstream origin/dev.

## Results

- [verified] Active SAF Stream Protection against Scoped Storage EACCES:
  - Added `StorageSources.canReadDirectly(Fi fi)` and `canReadDirectly(String path)`: rigorously verifies that a physical file can actually be opened by Java `FileInputStream` rather than merely checking `File.exists()`.
  - On Android 11+, raw POSIX access to `/storage/emulated/0/...` is restricted by Scoped Storage (`EACCES Permission denied`). `resolveFi` now verifies direct readability before returning a POSIX `Fi`.
  - In `PackLoaderDialog.scanAsync`: active SAF streams (`AndroidLauncher$1$2`) are preserved intact when the POSIX file cannot be read directly, allowing the stream to be materialized via `ContentResolver.openInputStream(uri)` without triggering `"Failed to materialize ZIP from SAF: Error reading file (absolute)"`.
- [verified] Safe Recent Sources Reselection on Android Mobile:
  - When clicking a Recent Source card that was selected via SAF and cannot be read via POSIX due to Scoped Storage, PackLoader now prompts the user with a friendly re-select confirmation dialog rather than failing with a raw error or deleting the item from history.
  - Added localized strings for `packloader.recent.saf-reselect.title` and `packloader.recent.saf-reselect.prompt` in both English and Vietnamese.
- [verified] Automated Test Suite & Packaging:
  - Total 250 tests passing via `./gradlew test`.
  - Packaging verified via `./gradlew jar verifyJar`.
  - Android universal JAR deployed to `/sdcard/Download/MindustryPackLoader.jar` via `./gradlew deploy`.

## Remaining

- None.

## Next steps

- Test live in Mindustry on Android device using the deployed `/sdcard/Download/MindustryPackLoader.jar`.
- Merge `fix/android-saf-recent-sources` into `dev` when approved.

## Verification

- `./gradlew test` -> 249 tests completed, 0 failures.
- `./gradlew jar verifyJar` -> BUILD SUCCESSFUL; JAR verification passed.
- `./gradlew deploy` -> BUILD SUCCESSFUL; `classes.dex` verified for Android.
