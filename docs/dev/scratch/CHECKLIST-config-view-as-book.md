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
- [x] 3. **`CommandTreeMenuBuilder.buildConfigSelector` + `buildConfigFile`.** Done 2026-05-18. Added two new public methods to `rtp-core/.../commands/menu/CommandTreeMenuBuilder.java` (lines ~576-707). `buildConfigSelector(UUID, List<String> fileNames)` emits Back→`OpenMenu([])` + header + one `OpenConfigFile(name)` row per non-blank entry. `buildConfigFile(UUID, String fileName, ConfigParser<E>)` emits Back→`OpenConfigSelector` + header + one `OpenConfigKey(file, key)` row per `E.values()` (label `"<KEY>: <currentValue>"`); empty-enum parser degrades to a non-clickable hint row per v3.7.4. File names passed in by caller (not reached from `RTP.configs`) for unit-test isolation. New `MessagesKeys` enum values deferred to step 8 (locale TSV pass); reuses only existing `MessagesKeys.menuBack` and English fallbacks. Evidence: new `MenuConfigSubtreeBuildersTest` (7 tests, all green via `run_test`) covers (a) selector layout + file rows, (b) empty selector, (c) blank-entry skipping, (d) selector null-arg rejection, (e) per-file layout against live `PerformanceKeys` parser, (f) empty-enum hint row, (g) per-file null-arg rejection. Permission gating (point d in original step text) is intentionally NOT in the builder per its javadoc — gating belongs in the dispatch arm (step 5) and the selector-row predicate (step 7).
- [x] 4. **Shape/vert two-step sub-parameter expansion.** Done 2026-05-18. Per v3.7.5 + the design-confirmation locked with the user this session (Q4-1=a / Q4-2=name-as-discriminator / Q4-3=name-row-on-page-3a). Added one new sealed variant `MenuAction.OpenConfigSubParamPage(fileName, paramName, typeName)` to `rtp-api` (sealed shape 10 -> 11; `MenuModelSurfaceTest` updated; `BookMenuRenderer.toClickEvent` extended with the matching `/rtp menu token:<token>` mint-arm). Added two new public builders to `rtp-core/.../commands/menu/CommandTreeMenuBuilder.java`: `buildShapeVertTypePicker(...)` (page 3a - Back -> `OpenConfigFile` + header + one `OpenMenu(writeCommandPath + "name:<type>")` row per type, current type marked with `&a*` prefix, case-insensitive) and `buildShapeVertSubParamPage(...)` (page 3b - Back -> `OpenConfigKey` (re-opens 3a) + header + one `OpenParamPicker(writeCommandPath, subParamName)` row per sub-param). Both stay caller-isolated from `RTP.factoryMap` per the step-3 isolation pattern; the production wiring lands with the step-6 `MenuConfigSubtreeBuilder` impl. Writes remain stateless (Q13) - every sub-param click targets the flat key `<subParamName>:<value>` and the parser's stored `name` discriminates which type is active. New `MessagesKeys` enum values deferred to step 8. Evidence: new `MenuShapeVertExpansionTest` (8 tests, all green via `run_test`) covering (i) page-3a layout + write-target assembly, (ii) current-type marker (case-insensitive), (iii) null-current + null/empty type-entry filtering, (iv) page-3a null/empty-arg rejection, (v) page-3b layout + `OpenParamPicker` parentPath/paramName per row, (vi) empty-subParams hint degradation, (vii) blank sub-param-name filtering, (viii) page-3b null/empty-arg rejection. Full `commands/menu/` package: 65/65 (was 57, +8 new; zero regressions).
- [x] 5. **`MenuRedeemSubcommand` — three new dispatch arms.** Done 2026-05-18. Added a new `MenuConfigSubtreeBuilder` SAM (3 methods: `buildSelector` / `buildFile` / `buildKey`) + a new field + a new 8-arg constructor that plumbs an optional implementation alongside the renderer wire-up (same null-collapse rule as `paramPickerBuilder` / `anvilInputOpener`). Added three private dispatch methods to `MenuRedeemSubcommand` (`dispatchOpenConfigSelector` / `dispatchOpenConfigFile` / `dispatchOpenConfigKey`) and three new arms in the `dispatch()` switch. Each arm: (a) rejects with `menuInvalid` + WARN when the builder/renderer is unwired; (b) gates on `CONFIG_VIEW_PERMISSION = "rtp.config.view"` via the existing `permissionProbeFactory` (deny-by-default on a null probe or a throwing probe, per S-004); (c) routes through the builder, treating a null return as `unknown file/param` reject (S-004); (d) renders, with renderer-failure isolated to a WARN + reject. Per the v3.6.3 contract, each invocation re-asks the builder for a fresh model so a re-issued action after a config write surfaces the new value. Mint TTL is registry-managed (not pinned in the dispatch arms) per v3.7.3 — token TTL is the registry's contract; the dispatch path is TTL-agnostic. Evidence: new `MenuConfigSubtreeDispatchTest` (10 tests, all green via `run_test`) covers (i) happy paths for all three arms, (ii) permission-denied rejection for all three, (iii) builder-unwired rejection, (iv) builder-null return for file + key, (v) `rebuildAfterWrite_reflectsNewValue` — a stateful test builder whose source mutates between redeems; the second redeem's `MenuModel.title` reflects the new value. Full menu test suite (57 tests across the package) stays green. The `ViewSubConfigCmd` rewrite (step 6) shall provide the production `MenuConfigSubtreeBuilder` impl that bridges to `RTP.configs` and reuses `buildConfigSelector` / `buildConfigFile` / `buildParamPicker`.
- [x] 6. **`ViewSubConfigCmd` rewrite + `ViewRawSubConfigCmd` extraction.** Done 2026-05-18. New `ViewRawSubConfigCmd` (verb `viewraw`) extracted from the previous `ViewSubConfigCmd` and registered as a sibling subcommand in `SubConfigCmd.addParameters` (line 374). `ViewSubConfigCmd` rewritten to delegate to a static `BookViewOpener` SAM (`@FunctionalInterface (UUID viewer, String fileName) -> boolean`); when the opener returns `true`, the command short-circuits; otherwise it falls through to the legacy raw-dump path (reused via `new ViewRawSubConfigCmd(...).onCommand(...)`) so platforms without the book renderer keep a usable `/rtp config <file> view`. Opener throws are contained (logged WARN per S-004 spirit) and trigger the same fallthrough. The production opener wiring in `RTPCmdBukkit` (mint `OpenConfigFile` token + render via the wired `MenuRenderer`/`MenuConfigSubtreeBuilder`) lands with the follow-up slice that wires the production `MenuConfigSubtreeBuilder` impl into the existing 7-arg `MenuRedeemSubcommand` constructor (still pending: this slice ships the command split + hook but does NOT yet install the opener from `RTPCmdBukkit`; behavior on Paper/Folia/Spigot today: `/rtp config <file> view` chats the warning hint + falls through to the raw dump, which is identical to today's UX for the legacy verb so no regression). Evidence: new `ViewSubConfigCmdTest` (7 tests, all green via `run_test`) covers (a) opener-true short-circuit + UUID/file-name propagation, (b) opener-false fallthrough, (c) no-opener fallthrough, (d) throwing-opener containment, (e) `setBookViewOpener(null)` clears, (f) `view` command surface (name + permission), (g) `viewraw` command surface (name + permission).
- [x] 7. **`OpenConfigSelector` row added to the `/rtp menu` root page.** Done 2026-05-18. Added new `CONFIG_VIEW_PERMISSION = "rtp.config.view"` constant to `FrontPageBuilder`; threaded the existing `permission` predicate into `appendAdminRows`; replaced the previous `OpenMenu(["config"])` admin row with `OpenConfigSelector()` gated on `safeTest(permission, CONFIG_VIEW_PERMISSION)`. Hidden silently when the viewer lacks the permission (matches the v3.7.1 "hidden not greyed" rule). The legacy reflector path `/rtp menu config` still resolves via `MenuPageBuilder` fallthrough for callers that explicitly type the segment, so admins without `rtp.config.view` are not blocked from the reflector if they need it. Evidence: `FrontPageBuilderTest` extended (2 new tests + 2 existing tests updated; 11/11 green): (a) `adminView_emitsAdminRows` updated to grant `rtp.config.view` and asserts `OpenConfigSelector` row, (b) `adminView_dropsRows_forMissingSubcommands` updated to use the new helper, (c) NEW `adminView_hidesConfigRow_whenLacksConfigViewPermission` asserts the row is hidden when admin lacks `rtp.config.view` (and the legacy `OpenMenu([config])` row also does not appear), (d) NEW `adminView_configRow_isOpenConfigSelector_whenPermissionHeld` asserts the row is `OpenConfigSelector` (not `OpenMenu`) when permission held. Full menu test package: 67/67 green (was 65, +2 new step 7 tests).
- [x] 8. **Locale TSV pipeline pass for new `messages.yml` keys.** Done 2026-05-18. Added the 11 v3.7.2 keys (`configSelectorTitle`, `configSelectorBackRow`, `configFileTitle`, `configFileBackRow`, `configFileEmptyHint`, `configKeyRowFormat`, `configKeyHoverFormat`, `configTypePickerTitle`, `configSubParamPageTitle`, `configViewRawHint`, `configValueUnsetPlaceholder`) to baseline `rtp-plugin/src/main/resources/messages.yml` AND to `rtp-api/.../enums/MessagesKeys.java` (with Javadoc). Ran the TSV pipeline (`locale-files-to-csv` -> `reconcile-locale-csvs` -> `locale-files-from-csv`); reconcile reported `added=15` (11 new + 4 stale-key reseeds) per locale across 12 locales (`cat`/`de`/`es`/`fr`/`it`/`ja`/`ko`/`nl`/`pl`/`pt`/`ru`/`zh`). Per TRANSLATION_GUIDE §8 the seeded English fallback under identity-key rows is the acceptable first-pass; native-speaker translation is deferred to follow-up contributor PRs. Evidence: `LocaleParityTest` 308/308 passed via `run_test` (was at parity before, still at parity after the pipeline run). No hand-edits to `lang/<locale>/*.yml` or `*.lang.yml`. Pipeline kept original baseline scripts:
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

---
## Follow-up steps (added 2026-05-18 from PROPOSAL-config-view-lists-and-sections.md)

These extend the work after steps 1-9 are committed. Design locked this session with the user (option C(4) = design-only). See the companion proposal for full rationale.

- [ ] 10. **ConfigSection flattening (Part A).** Coexist with the curated shape/vert two-step page; flatten all *other* `RtpYamlSection`-typed config keys to one row per child labeled `prefix.child: value` on the per-file page; rewrite the value-click write target to the native segment shape that `SubConfigCmd` accepts.
  - [ ] 10a. **Verify** `SubConfigCmd.onCommand` write-back semantics for dotted vs split parameter names (PROPOSAL §2.5). Determine whether `parameterValues.put("shape.radius", ...)` lands as nested YAML or as a stray top-level key. If split is required, document the segment layout (likely `/rtp config <file> set <prefix> <child>:<value>`).
  - [ ] 10b. Extend `CommandTreeMenuBuilder.buildConfigFile` with per-key type detection (`RtpYamlSection` / `List` / scalar) and flatten non-curated sections.
  - [ ] 10c. Add the curated allow-list `Set<String>` (initially `{"regions.shape", "regions.vert"}`) to `MenuConfigSubtreeBuilder` and to `RTPCmdBukkit`'s impl; dispatch consults it.
  - [ ] 10d. Extend `dispatchOpenConfigKey` (and downstream `buildParamPicker` invocation) to handle dotted `paramName` per 10a's verified shape.
  - [ ] 10e. New `MenuConfigFileFlatteningTest` covering scalar/list/section/curated-section detection. Existing menu tests stay green.
  - [ ] 10f. Locale: new `messages.yml` baseline keys `configFlatKeyRowFormat`, `configListSummaryRowFormat`. Run the locale TSV pipeline.

- [ ] 11. **List editor (Part B).** Show the *current* contents of a list-typed config key (clickable to remove via `key:-value`) in addition to the add-row universe (`relevantValues` minus current).
  - [ ] 11a. **Verify** `SubConfigCmd` minus-sign remove handling on list parameters (PROPOSAL §3.3). Add 3-5 line adapter if missing.
  - [ ] 11b. New `CommandTreeMenuBuilder.buildListEditor(callerId, file, listKey, currentEntries, availableEntries, writeCommandPath)` per PROPOSAL §3.2 layout.
  - [ ] 11c. Route `dispatchOpenConfigKey` to `buildListEditor` when the resolved key's current value is a `List`.
  - [ ] 11d. New `MenuListEditorTest` per PROPOSAL §3.4 (happy path with current+available diff, empty current, pagination, null-arg rejection).
  - [ ] 11e. Locale: new `messages.yml` baseline keys `configListEditorTitle`, `configListRemoveRowFormat`, `configListAddRowFormat`, `configListAddHeader`. Run the TSV pipeline.

- [ ] 12. **Final full build gate (replaces step 9 if not yet run).** `\.\gradlew :rtp-plugin:test --tests "*LocaleParityTest*"` and `.\gradlew build` both green; cite in submit summary per AGENTS.md *Final Full Build*.

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
