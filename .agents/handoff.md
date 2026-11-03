# Current handoff

> Replace this file at the end of each task. Keep only the current state; do not append an endless history.

- **Status:** complete
- **Last updated:** 2026-11-03 12:22 +07:00
- **Updated by:** Antigravity / Gemini 3.8 Flash (High)
- **Task:** Implement Option A ("Phương án A") to distinguish Recent Sources between Schematics and Maps: visual badge & icon colors per type, independent storage by (path, type) key, targeted deletion, and unit tests.
- **Branch:** master; working tree ready to commit.

## Results

- [verified] RecentSources Type Differentiation:
  - Deduplication in `RecentSources.add` now checks both `path` and `type` (`it.path.equalsIgnoreCase(path) && it.type == type`), allowing the same directory/archive to coexist independently as a Schematics source and a Maps source.
  - Added overloaded `RecentSources.remove(String path, PackType type)` to delete only the specified `(path, type)` pair, while maintaining `remove(String path)` for backward compatibility.
- [verified] Visual Polish & Type Badges:
  - Refactored duplicated recent source card rendering in `buildModeSelection()` and `buildSourceSelection()` into a unified helper `createRecentSourceCard(SourceItem item, PackType activeType)`.
  - Icon & color distinctions:
    - For `PackType.MAP`: card icon and badge colored `Pal.heal`, showing mini `Icon.terrain` with localized name ("Map" / "Bản đồ").
    - For `PackType.SCHEMATIC`: card icon and badge colored `Pal.accent`, showing mini `Icon.paste` with localized name ("Schematic").
  - Clean layout: badge placed on the right of the header row with `growX().minWidth(0f)` on the title label, ensuring long titles truncate cleanly with ellipsis without pushing the badge or delete button out.
- [verified] Automated Tests & Packaging:
  - Added `testDeduplicationDistinguishesType()` and `testRemoveByType()` to `RecentSourcesTest.java`.
  - `.\gradlew.bat test` — BUILD SUCCESSFUL (all unit tests passed).
  - `.\gradlew.bat check` and `.\gradlew.bat verifyJar` — BUILD SUCCESSFUL (JAR packaging verified).
  - Untracked artifacts (`session.json`) completely preserved.

## Remaining

- In-game live visual inspection with OpenGL context.
- Physical Android device testing with Storage Access Framework (SAF) file picker.

## Next steps

1. Read `.agents/handoff.md` from disk at task start.
2. Desktop In-Game Smoke Test:
   - Copy `build/libs/MindustryPackLoaderDesktop.jar` into portable Mindustry mod folder `D:\Anzuno\Mindustry\MindustryData\mods\` (or `%APPDATA%\Mindustry\mods\`).
   - Launch `D:\Anzuno\Mindustry\Mindustry.exe`.
   - Verify: Mode selection dialog, Recent Sources badges, and independent Schematics/Maps card behavior.

## Open questions

- None.

## Verification

- `.\gradlew.bat test` — BUILD SUCCESSFUL (all unit tests passed).
- `.\gradlew.bat check` — BUILD SUCCESSFUL (desktop JAR verified).
- `.\gradlew.bat verifyJar` — BUILD SUCCESSFUL (packaged bundle paths verified at `bundles/*`).
