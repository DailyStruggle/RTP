# CHECKLIST - `/rtp config <file> view` as an interactive book

**Effective Issue (one line):** Replace `ViewSubConfigCmd`'s chat-dump with a clickable book page that reuses the existing `PromptAnvilInput` round-trip, so config values can be edited in-game via the menu pipeline.

**Mode:** `[CODE]` (multi-class, multi-module — Rule D-005 approval granted by user 2026-05-18).

**Design source of truth:** [`PROPOSAL-config-view-as-book.md`](PROPOSAL-config-view-as-book.md) v3.7.
- Decisions log: see proposal head (Q1-Q13 + v3.6.1-3 + v3.7.1-5, all resolved).
- Implementation order: v3.5 §"Revised implementation order" (mirrored below, with v3.6 / v3.7 amendments folded in).

**Scope (modules touched):**
- `rtp-api` — three new `MenuAction` sealed variants.
- `rtp-core` — `CommandTreeMenuBuilder` (new builders), `MenuRedeemSubcommand` (three dispatch arms), `ViewSubConfigCmd` (rewrite), new `ViewRawSubConfigCmd` (extraction), `SubConfigCmd` (registration of raw view).
- `rtp-paper-common` — test only (existing `BookMenuRenderer` already handles arbitrary `MenuAction`s).
- `rtp-plugin` — `messages.yml` baseline + full locale TSV pipeline pass.

**Blocking decisions awaiting user approval at submit time:** none. All design questions resolved in proposal v3.7.

**Session-resumption rule:** on resume, re-read this file first; re-verify the last `[x]` item still holds (file exists, test still green, build still clean); then continue from the first `[ ]`.

---

## Steps

- [x] 1. **Pre-flight verification of v3.7 defaults still apply.** Done 2026-05-18: proposal head reads `Status: Design complete (v3.7, 2026-05-18)`; no v3.8+ amendment present.
- [x] 2. **Add three new `MenuAction` variants in `rtp-api`.** Done 2026-05-18. Added `OpenConfigSelector` (no fields), `OpenConfigFile(String fileName)`, `OpenConfigKey(String fileName, String paramName)` to `rtp-api/.../menu/MenuAction.java` (sealed `permits` list extended to 10 variants). Updated the two existing exhaustive sites so they keep compiling/passing: `MenuModelSurfaceTest.menuActionSealedShape` (7 -> 10) and `menuActionSwitchExhaustive` (three new arms); also extended `BookMenuRenderer.toClickEvent` in `rtp-paper-common` with three server-resolved `/rtp menu token:<token>` arms (mint-token redeem-path, same shape as `OpenMenu`/`OpenParamPicker`/`PromptAnvilInput`). Evidence: `run_test` of `MenuModelSurfaceTest` -> 15/15 passed.
- [ ] 3. **`CommandTreeMenuBuilder.buildConfigSelector` + `buildConfigFile`.** Thin layers over `buildParamPicker`. Selector page rows = file names from `RTP.configs.knownFiles()` (or equivalent), each row emitting `OpenConfigFile(name)`. Per-file page rows = parser keys, each row emitting `OpenConfigKey(file, key)` whose redeem hands off to `buildParamPicker` over the typed `CommandParameter`. Empty-file handling per v3.7.4 (header + `configFileEmptyHint` + back). Evidence: new tests against a fake `ConfigParser` cover (a) selector lists all known files, (b) per-file page lists all keys with current values, (c) blank-file degrades to empty hint, (d) permission gate hides the selector row when sender lacks `rtp.config.view`.
- [ ] 4. **Shape/vert two-step sub-parameter expansion in `buildParamPicker`.** Page 3a (type picker) reuses `ShapeParameter` / `VertParameter`'s existing tab-complete value source (per v3.7.5 — no new enumeration code). Page 3b (sub-param list) walks the activated factory's `getData()` and emits one `buildParamPicker` row per sub-parameter. Writes are **stateless** per Q13 (parser-stored type discriminates). Evidence: tests for `RegionKeys.shape` and `RegionKeys.vert` covering type-pick → sub-param page → stateless write.
- [ ] 5. **`MenuRedeemSubcommand` — three new dispatch arms.** `dispatchOpenConfigSelector`, `dispatchOpenConfigFile`, `dispatchOpenConfigKey`. All three: (a) enforce `rtp.config.view` and silently no-op (with `RTP.log(WARNING, ...)` per S-004) if missing; (b) after any write performed via the redeem path, **rebuild the originating page fresh** from live `ConfigParser` state and resend (v3.6.3 contract). Mint TTL = 1 hour per v3.7.3. Evidence: `MenuConfigSubtreeTest.rebuildAfterWrite_reflectsNewValue` passes; permission-denied path tested.
- [ ] 6. **`ViewSubConfigCmd` rewrite + `ViewRawSubConfigCmd` extraction.** Per v3.6.1: `ViewSubConfigCmd.onCommand` directly invokes `CommandTreeMenuBuilder.buildConfigFile(...)` and hands the result to the active renderer (book on Paper/Folia, chat fallback elsewhere). The current 1000-line raw-YAML dump moves verbatim into a new `ViewRawSubConfigCmd` registered as a sibling subcommand (opt-in per Q4). Update `SubConfigCmd:373` to register both. Evidence: invoking `/rtp config <file>` (or `... view`) opens the book; `/rtp config <file> view raw` (or whatever verb the new sibling uses) preserves the old chat output.
- [ ] 7. **`OpenConfigSelector` row added to the `/rtp menu` root page.** Per v3.7.1, hide for senders lacking `rtp.config.view`. Evidence: root page test asserts row presence/absence by permission.
- [ ] 8. **Locale TSV pipeline pass for new `messages.yml` keys.** Add the v3.7.2 key surface to baseline `rtp-plugin/src/main/resources/messages.yml`: `configSelectorTitle`, `configSelectorBackRow`, `configFileTitle`, `configFileBackRow`, `configFileEmptyHint`, `configKeyRowFormat`, `configKeyHoverFormat`, `configTypePickerTitle`, `configSubParamPageTitle`, `configViewRawHint`, `configValueUnsetPlaceholder`. Then run, in order:
  ```powershell
  .\scripts\locale-files-to-csv.ps1
  .\scripts\reconcile-locale-csvs.ps1
  ```
  Translate seeded rows in `scripts/out/locale-*.tsv` (key, value, preceding_comment columns) per the TSV pipeline contract in AGENTS.md. Then:
  ```powershell
  .\scripts\locale-files-from-csv.ps1
  ```
  Evidence: regenerated `lang/<locale>/messages.yml` and `lang/messages.lang.yml` reflect all new keys; no hand-edits to `lang/<locale>/*.yml` or `*.lang.yml`.
- [ ] 9. **Verification gate.** Run, in order:
  ```powershell
  .\gradlew :rtp-plugin:test --tests "*LocaleParityTest*"
  .\gradlew build
  ```
  Evidence: both green. Cite the full-build outcome in the submit summary's `### Verification` section per AGENTS.md *Final Full Build* rule.

---

## Deferred / out-of-scope (do NOT do in this slice)

- ListCmd-as-its-own-menu-page polish (v2.4's `configListAddRow`/`configListRemoveHover`). `ListCmd` already renders via `buildParamPicker`; revisit only if UX feedback demands it.
- Admin-panel migration of the `config` row. Permission node `rtp.config.view` is stable across that future migration; no work needed here.
- Concurrent stale-view detection (Q5). ~5s natural refresh is acceptable.
- On-disk write batching / deferred reload. `SubConfigCmd` already writes through; v3.6.3 only commits to in-memory visibility.
- Orphan sub-param cleanup on shape/vert type change. Parser ignores them under the new type (cheapest correct behavior per v3.7.5).

---

## Submit checklist (run before `submit`)

- [ ] All nine steps above are ticked with verifiable evidence.
- [ ] `.\gradlew build` is green (cite headline in submit summary).
- [ ] `LocaleParityTest` is green.
- [ ] No hand-edits to `lang/<locale>/*.yml` or `*.lang.yml` (TSV pipeline only).
- [ ] No mojibake markers (`â€`, `Â`, `Ã`, `âœ`, `ðŸ`, `�`) in the diff per AGENTS.md *Markdown Encoding Hygiene*.
- [ ] This checklist file is deleted after submit (per AGENTS.md *Checklist-Based State Tracking*: "Delete the file once the task is submitted").
- [ ] Any unchecked item at submit time is called out under `### Notes` in the submit summary with a reason.
