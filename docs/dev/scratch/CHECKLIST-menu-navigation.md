# CHECKLIST — Menu Navigation (Stage A)

**Effective issue:** Book menu rows for `TreeCommand` subcommands (e.g. `CONFIG`) just dispatch the subcommand as a text command instead of descending into a sub-menu. Players have no way to navigate forward, navigate back, or execute an assembled command from inside the menu.

**Mode:** `[CODE]`. D-005 proposal approved by user 2026-05-15 (Stage A scoped; Stage B "Quick Actions launcher" and `/rtp me` deferred — `/rtp me` captured in `docs/dev/TODO.md` §7).

**Scope (Stage A.1 — minimum viable navigation; user-approved cut 2026-05-15).**

- Reflected-tree navigation: clicking a subcommand row with navigable content re-opens the menu at that subtree.
- Back row at the top of every non-root page.
- Execute row on pages where the assembled path is runnable.
- All new user-facing strings configurable via `messages.yml` (REQ-RTP-F-013 / S-007).
- S-004 audit log preserved on every reject.

**Deferred to Stage A.2 (still tracked here; additive — no re-architecture).**

- Enumerable parameters (boolean / enum / small bounded `values()`) render their allowed values as a sub-page.
- Confirm-guard for destructive commands (`reload` by default; list configurable).

**Out of scope (Stage B+ — separate task).** Quick Actions launcher; `/rtp me`; chat renderer; inventory renderer.

---

## Steps

- [x] 1. Capture `/rtp me` follow-up in `docs/dev/TODO.md` §7 *Deferred follow-ups* — done (TODO.md line 120).
- [x] 2. New `MessagesKeys` `menuBack` + `menuExecute` added (rtp-api) with defaults in `rtp-plugin/.../messages.yml` and `lang/es/messages.yml`. Confirm-prompt keys deferred to Stage A.2.
- [-] 3. Deferred to Stage A.2 (no confirm-guard in A.1 scope).
- [x] 4. `CommandTreeMenuBuilder.build(...)` overhaul:
  - [x] 4a. New 5-arg overload accepts `List<String> assembledPath`; old 4-arg form is a delegating shim with empty path (back-compat).
  - [x] 4b. Subcommand rows: TreeCommand children with navigable content emit `MenuAction.OpenMenu(assembledPath + name)`; pure-leaf subs (no nested subs after `help`/`menu` exclusion, no params) keep `MenuAction.RunRtpCommand`.
  - [x] 4c. Both `help` and `menu` excluded from subcommand rows.
  - [x] 4d. Back row prepended on non-root pages: `MenuAction.OpenMenu(<assembledPath minus last>)`. Architectural note: navigation rides a new `MenuAction.OpenMenu` sealed variant (server-resolved path walk) rather than re-entering commands-api via `/rtp menu <args>` — see `MenuAction` javadoc and `MenuRedeemSubcommand.dispatchOpen`.
  - [x] 4e. Execute row prepended on non-root pages: `MenuAction.RunRtpCommand(<assembledPath>)`. Confirm-guard deferred to Stage A.2.
  - [-] 4f. Deferred to Stage A.2 (enumerable-param sub-pages). Free-form parameters keep existing `SuggestInput`.
- [x] 5. `MenuRedeemSubcommand` path-tracking:
  - [x] 5a. New `dispatchOpen` arm handles `MenuAction.OpenMenu` tokens by walking the path against `rtpRoot.getCommandLookup()`; unknown segments collapse to `menuInvalid` + WARN (S-004).
  - [x] 5b. `pageBuilder` SAM widened to `MenuPageBuilder` (`(node, viewer, assembledPath) -> MenuModel`); `RTPCmdBukkit` closure updated.
- [x] 6. Tests in `rtp-core/src/test/.../menu/MenuNavigationStageATest.java` (8 tests, all green):
  - [x] 6a. Sub with navigable content → `OpenMenu(path)`; pure-leaf sub → `RunRtpCommand`.
  - [x] 6b. Back row present on non-root, absent on root.
  - [x] 6c. Execute row present on non-root, absent on root.
  - [-] 6d. Deferred (Stage A.2).
  - [-] 6e. Deferred (Stage A.2).
  - [x] 6+ Surface tests updated: `MenuModelSurfaceTest` covers new `OpenMenu` variant (defensive copy + equality + sealed-shape + exhaustive switch).
- [x] 7a. Targeted module tests green (`MenuStageTwoTest` 15/15, `MenuNavigationStageATest` 8/8, `MenuModelSurfaceTest` 13/13, `BookMenuRendererTest` 10/10).
- [x] 7b. Full `.\gradlew build` pass — BUILD SUCCESSFUL (1m 15s) after one round of `spotlessApply` reformat.
- [x] 8. Submit summary cites the checklist.

## Notes / decisions

- "Navigable content" = `getCommandLookup()` (after excluding `help` / `menu`) is non-empty **or** `getParameterLookup()` is non-empty. A `TreeCommand` with neither is treated as a pure-leaf direct-execute row.
- "Runnable" = the node is not the `/rtp` root. We rely on `rtpRoot.onCommand(...)` to surface "not actually executable" via its own help/error path; the Execute row is just a convenience.
- The token-mint count per page rises with these additions. `MenuTokenRegistry` is per-player bounded; if we hit the cap on a busy page, the renderer must surface that — record as a potential bug if it becomes an issue rather than fixing speculatively.
