# Current handoff

> Replace this file at the end of each task. Keep only the current state; do not append an endless history.

- **Status:** complete
- **Last updated:** 2026-11-02 09:22 +07:00
- **Updated by:** OpenCode / DeepSeek V4.1 Flash Free
- **Task:** Implement the user-level Shared Context & Memory system for Antigravity CLI and OpenCode.
- **Branch:** master; implementation commit `217b5b9`.

## Results

- [verified] Audited the native instruction mechanisms from official docs and the installed CLIs (`agy 1.4.12`, `opencode v2.2.0`).
- [verified] Created canonical user memory at `~/.agent-memory/` with global instructions, preferences, cross-project decisions, a guide, and project templates.
- [verified] Wired Agy through `~/.gemini/config/AGENTS.md` using native `@[label](path)` includes.
- [verified] Wired OpenCode through `~/.config/opencode/opencode.json` using the native `instructions` array.
- [verified] Created project context at `AGENTS.md`, the handoff at `.agents/handoff.md`, and the project bridge at `opencode.json`.
- [verified] Both CLIs can write files in a trusted workspace; byte-exact write probes passed.
- [verified] Project files are included in the implementation commit.

## Remaining

- None for this task.
- Optional: adopt the workflow in other repositories using `~/.agent-memory/README.md` and the templates.

## Next steps

1. Read `~/.agent-memory/README.md` before adding the system to another repository.
2. For an existing project, merge the `## Shared context` section into `AGENTS.md` and merge the `instructions` array into `opencode.json`; do not replace existing content.
3. If OpenCode is already running when a project bridge is added, run `opencode reload` or start a fresh session.
4. Replace this handoff at the end of the next task.

## Open questions

- None. Multi-machine synchronization and Termux/Android verification are out of scope for this task.

## Verification

- `agy -p` global preferences, first decision, project handoff, and subdirectory handoff — pass.
- `opencode run` global preferences, project handoff, and subdirectory handoff — pass.
- `agy -p` and `opencode run --auto` byte-exact write probes in a trusted workspace — pass.
- `opencode debug config` shows the global and project configuration documents with their `instructions` arrays.
- `.\gradlew.bat test --rerun-tasks` — BUILD SUCCESSFUL; 102 tests, 0 failures, 0 errors, 0 skipped.
- `.\gradlew.bat verifyJar --rerun-tasks` — BUILD SUCCESSFUL; desktop jar bundles verified.
- Not verified: Termux/Android, macOS/Linux, multi-user setups.
