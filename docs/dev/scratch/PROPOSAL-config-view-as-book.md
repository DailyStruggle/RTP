# PROPOSAL - `/rtp config <file> view` as an interactive book

**Status:** Design complete (v3.7, 2026-05-18). Pre-D-005, awaiting user review before checklist promotion. All Q1-Q13 + v3.6 refinements (3.2 / 3.4.ii / 3.5) + v3.7 implementation defaults (permission, locale keys, TTL, empty-file, shape/vert value source) answered. No open `(?)` items remain.

**Final design summary (head of file, read this first):**
- 3-page curated config subtree (selector -> per-file key list -> per-key picker), rooted on the `/rtp menu` front page (later: under an admin panel).
- Reuses `buildParamPicker` verbatim. No refactor. List keys inherit via existing `ListCmd`.
- 3 new `MenuAction` variants: `OpenConfigSelector`, `OpenConfigFile`, `OpenConfigKey`.
- Shape/vert sub-parameters: two-step page (pick type -> sub-param page), **stateless writes** (rely on parser-stored type as discriminator, matching `SubConfigCmd:122` defaulting).
- Write grammar: direct `parameterValues` on `SubConfigCmd` (no `set` verb); list edits via `ListCmd`.
- Raw YAML view preserved as opt-in `ViewRawSubConfigCmd`.
- Concurrent stale-view problem ignored (~5s natural refresh).
- Command-tree path-walker demoted to a "Browse all commands" fallback row.

**Decisions log (chronological, for traceability):**
- **First pass (Q1-Q6):** Q1 reuse `buildParamPicker`; Q2 synthesize through normal command path; Q3 list keys get click-to-remove + anvil-add; Q4 raw YAML opt-in; Q5 ignore stale views; Q6 dedicated config subtree, path-walker as fallback.
- **Second pass (Q7-Q11):** Q7 separate `MenuAction` variants (revised to 3 in v3); Q8 dropped (no `buildParamPicker` refactor needed); Q9 list edits already routed through `ListCmd`; Q10 shape/vert sub-params surface via factory `getData()`; Q11 entry point is `/rtp menu` root.
- **Third pass (Q12-Q13):** Q12 two-step page for shape/vert (v3.2a); Q13 stateless sub-param writes (relies on parser-stored type discriminator).

**Decisions from user (2026-05-18, first pass):**
- **Q1 -> reuse `buildParamPicker`.** The existing param-picker (allowed-values rows + a `PromptAnvilInput` "type a value" tail row) is the click target for each key, not a bare anvil prefill. This is a richer flow than draft v1 assumed and means a config key click descends *into* a picker page rather than opening anvil directly.
- **Q2 -> synthesize `/rtp config <file> set <key> <value>`.** Output is already logged server-side; no need for a direct write path.
- **Q3 -> list/map keys get a sub-page with click-to-remove rows + an `add` row that opens the anvil.** I.e. list keys are not skipped; they get their own multi-row editor.
- **Q4 -> raw-YAML view stays as an opt-in subcommand** (`view raw` or similar).
- **Q5 -> ignore stale-view problem.** Users re-open within the natural 5-second window.
- **Q6 -> reject route (a), adopt route (b).** Path-walking through the reflected command tree was tried in Stage A and produced suboptimal UX. Instead: dedicated menu pages (config-file selector -> per-file view) with command path-walking demoted to a **fallback row** for anything not surfaced on the front page or admin panel.

This v2 supersedes draft v1 at sections 3.1, 3.2, 3.3, 3.4 and the affected-modules table in 4.

**Effective issue (user, 2026-05-18 10:58):**
> working on the menu system, I want to reduce config updates to the view page as a book page and clickable (click event) anvil input view with suggested values for each clicked subparameter then return to book view with updated value

**User clarification (2026-05-18):**
> currently the `view` option prints to chat and closes the book and does not make any lines clickable

So the goal is to **replace** the current chat-dump behavior of `view` with a book page whose value lines are clickable, each click opening the existing anvil-prefill flow, and returning to the book afterward.

---

## 1. Current state (what is already built)

The anvil-prefill round-trip the user wants is **not new** - it already exists for the param-picker pages of the generalized menu (ADR-045). The pieces are:

- `MenuAction.PromptAnvilInput(parentPath, paramName, prefill)` in `rtp-api` - the sealed token type for "open an anvil GUI, prefilled, write the typed value back as the parameter".
- `CommandTreeMenuBuilder.buildParamPicker(...)` in `rtp-core` (line ~489) - emits a `PromptAnvilInput` row as the "type a value" entry on every param-picker page.
- `BookMenuRenderer` in `rtp-paper-common` - renders a `MenuModel` as a written book; serializes `PromptAnvilInput` (and the other server-resolved actions) as `/rtp menu token:<t>` click events. Covered by `BookMenuRendererTest`.
- `MenuRedeemSubcommand.dispatchPromptAnvilInput` in `rtp-core` - server-side handler that opens the anvil GUI on Paper/Folia, accepts the entered text, applies it as a config write, and (per ADR-045) returns the player to the originating page.
- Token mint/TTL is governed by `MenuTokenRegistry`; book renderer guarantees exactly one mint per `PromptAnvilInput` fragment.

**Implication:** the user's flow ("book page -> click value -> anvil with prefill -> return to book with new value") is **already implemented end-to-end** for menu-driven `set` operations. The only thing wired the old way is `view`, which bypasses the menu pipeline entirely and writes to chat.

## 2. The gap (`ViewSubConfigCmd` today)

`rtp-core/.../commands/config/ViewSubConfigCmd.java` (104 lines):

- Reads the raw on-disk YAML file via `Files.readAllLines(...)`.
- Streams up to `MAX_LINES = 1000` lines to the caller via `RTP.serverAccessor.sendMessage(...)` as plain chat.
- No click events, no book, no model object, no participation in `MenuTokenRegistry`, no path-tracking.
- Registered from `SubConfigCmd:373` (`addSubCommand(new ViewSubConfigCmd(this, configParser))`).

The current output is also disjoint from the structured config model: it shows raw YAML text, including comments, whitespace, and any hand-edits, rather than typed keys with current values. That means even if we made lines clickable in chat, the click target would be "edit this raw line" rather than "edit this parameter", which is the wrong abstraction for the anvil flow (which expects a `(parentPath, paramName)` pair).

## 3. Proposed design (draft, please critique)

### 3.1 Reuse, do not parallel-build

The view page **is** a menu page. Build it through the same pipeline:

- `view` no longer streams raw YAML lines. Instead it constructs a `MenuModel` whose rows are the parameters of the underlying `ConfigParser<E>` (one row per enum key in `E`), each row carrying:
  - **Label:** `<paramName>: <currentValue>` (color-coded by type, e.g. green for default-equal, yellow for overridden).
  - **Hover:** the YAML comment block for that key (already extracted by `SubConfigCmd.descriptionFromComment`).
  - **Click action:** `MenuAction.PromptAnvilInput(parentPath = ["config", "<file>"], paramName = "<key>", prefill = "<currentValue>")` - i.e. the **identical** token type the param-picker already emits.
- The renderer is the existing `BookMenuRenderer`. No new renderer.
- The redeem path is the existing `MenuRedeemSubcommand.dispatchPromptAnvilInput`. No new redeem arm.
- After anvil-submit, the user lands back on the same view page via the existing "return to originating page" branch of `dispatchPromptAnvilInput` (ADR-045). The page is rebuilt fresh, so the new value is visible immediately.

This means the **only new code** is the page-builder for "render this `ConfigParser` as a `MenuModel`", plus the wiring that swaps `ViewSubConfigCmd`'s body from chat-dump to "open menu page".

### 3.2 Where the new page-builder lives

`CommandTreeMenuBuilder` is the right home, but the natural extension point depends on whether we treat `config <file> view` as:

- **(a)** a synthetic node in the reflected `TreeCommand` tree, so `OpenMenu(["config", "<file>", "view"])` Just Works through the existing `dispatchOpen` path-walker, **or**
- **(b)** a special-cased page identified by a dedicated `MenuAction.OpenConfigView(fileName)` variant.

I lean **(a)** because:
- It piggybacks on the path-walking infrastructure Stage A already built (`MenuRedeemSubcommand.dispatchOpen`, `CHECKLIST-menu-navigation.md`).
- It avoids adding a sealed variant just for one entry point, which would also require a token-registry round-trip update in `BookMenuRenderer`.
- The reflected tree already exposes `config -> <file> -> view`, so `OpenMenu` reaching that path is natural; we just intercept the page-builder when the leaf is `view`.

**Mechanism:** in `CommandTreeMenuBuilder.build(..., assembledPath)`, when the resolved node is the `view` subcommand of a `SubConfigCmd`, dispatch to a new `buildConfigView(parentSubConfigCmd, ...)` instead of the generic leaf renderer. `SubConfigCmd` already holds a `FactoryValue<?>` (the typed config), so the page-builder can iterate keys directly without reading the file.

### 3.3 Where the new entry-point lives

Two integration points (both small):

- **`/rtp config <file> view` invoked directly (chat / Brigadier):** `ViewSubConfigCmd.onCommand` no longer reads the file; instead it dispatches an `OpenMenu(["config", "<file>", "view"])` through the same code path the menu redeem uses. Effectively: `view` becomes a one-line "open me as a book".
- **`/rtp menu` navigation:** the `view` subcommand row in the existing menu already exists (Stage A made `TreeCommand` children navigable); no change needed - clicking it descends into the new page.

### 3.4 Suggested-values source for anvil prefill

The user mentioned "suggested values for each clicked subparameter". The existing `PromptAnvilInput` carries a single `prefill` string, not a list of suggestions, because anvil GUIs only have one input slot. Two interpretations:

- **(?) Q1:** Does "suggested values" mean
  - (i) just **the current value** prefilled (which is what `PromptAnvilInput.prefill` already does), or
  - (ii) a **tab-complete-style suggestion list** shown above/before the anvil opens (e.g. as a separate intermediate page for enum-typed keys: shape, vert, biomes-with-allowed-values), with a "type custom" fallback?

If (ii), the right move is to mirror what `CommandTreeMenuBuilder.buildParamPicker` already does for enumerable params: render a value-picker sub-page first (rows = allowed values via `safeValues(...)`), with the final row being a `PromptAnvilInput` for free-form override. This is **already implemented** for command parameters; we'd reuse it for config keys by extracting a small "values for this typed key" helper.

For now this draft assumes interpretation **(i)** is the minimum viable feature and **(ii)** is a follow-up. Please confirm.

### 3.5 Write-back semantics

`dispatchPromptAnvilInput` today translates the anvil-typed string into a parameter update for the **command** parented by `parentPath`. For config keys, the equivalent is `configParser.setFromString(key, typedValue)` (or whatever the parser exposes - need to verify the exact API). The user-visible operation should be the same as `/rtp config <file> set <key> <value>` - and indeed the cleanest implementation is to have the redeem path **synthesize and run** that command, so all existing validation, audit logging, S-004 attribution, and `RTP.serverAccessor.sendMessage(updatedMsg)` confirmation in `SubConfigCmd.onCommand` Just Work.

**(?) Q2:** Are we comfortable having the redeem path round-trip through `/rtp config <file> set <key> <value>` (cleanest, reuses all validation/logging), or do you want a direct `configParser` write (faster, but duplicates validation)?

### 3.6 Pagination

A `ConfigParser` typically has 10-40 keys; written books hold ~14 lines per page. The existing `BookMenuRenderer` already paginates via `ChangePage`. No new pagination logic - we just split the row list across pages the same way param-picker pages do.

The current `MAX_LINES = 1000` chat cap becomes irrelevant; it can be deleted.

---

## 4. Affected classes / modules (per D-005)

| Module | File | Change |
|---|---|---|
| `rtp-core` | `commands/config/ViewSubConfigCmd.java` | Replace `onCommand` body: read file -> open menu page at `["config", "<file>", "view"]`. Delete the `MAX_LINES` chat-streaming path. |
| `rtp-core` | `commands/menu/CommandTreeMenuBuilder.java` | Add `buildConfigView(SubConfigCmd parent, UUID callerId, ..., List<String> assembledPath)`. Wire from `build(...)` when the resolved node is a `view` leaf under a `SubConfigCmd`. |
| `rtp-core` (test) | `commands/menu/MenuConfigViewTest.java` (new) | Each key in a `ConfigParser` emits exactly one row with a `PromptAnvilInput` token carrying the current value as `prefill`; pagination matches book-page capacity; permission gating mirrors `rtp.config`. |
| `rtp-api` | `menu/MenuAction.java` | **No change** if we adopt route (a) above. Add `OpenConfigView` only if user prefers route (b). |
| `rtp-plugin` | `messages.yml` + every `lang/<locale>/messages.yml` + `messages.lang.yml` | New keys: `configViewTitle`, `configViewRowFormat` (or reuse existing menu format keys). Per locale parity rule, runs through the **TSV pipeline** (see AGENTS.md), not hand-edited. |
| `rtp-paper-common` (test) | `menu/BookMenuRendererTest.java` | Add a test asserting a config-view `MenuModel` renders with the same `PromptAnvilInput` token semantics as a param-picker. |

**Out of scope of this proposal:**
- Fabric rendering of the book page. The book + anvil GUI flow is Bukkit-family only today (per `MenuAction` javadoc). Fabric falls back to the chat renderer; that path can stay unchanged.
- Multi-file "config index" book (a top-level book listing all config files with click-to-view). The user only mentioned the per-file view page; if they want an index too, separate proposal.
- Editing of structured / list-valued keys (e.g. `worlds`, biome allow-lists). The anvil flow is single-line-string by construction; complex types will need either a sub-picker page or a follow-up proposal. **(?) Q3:** how should list-valued and map-valued keys be handled - skip them with a "(complex, use `/rtp config <file> set` directly)" row, or render them as nested sub-pages?

## 5. REQ-* / ADR references

- **ADR-045** (`docs/adr/ADR-045-rtp-docs-menu-consumer.md`) - the menu consumer protocol this proposal piggybacks on. No new ADR is required if we adopt route (a); the existing dispatch semantics already cover this use case.
- **REQ-RTP-F-013** - all new user-facing strings (page title, row format, anvil prompt) must be configurable via `messages.yml` and locale-mirrored via the TSV pipeline.
- **REQ-RTP-S-004** - the redeem path already audit-logs invalid tokens; the new page-builder must not introduce any silent-discard paths. Reusing `SubConfigCmd.onCommand`'s existing write logic (route Q2 = "round-trip through `set`") satisfies this for free.
- **S-005** - the page-builder runs on whatever thread the menu redeem dispatched it on; no chunk I/O is involved, so trivially satisfied. The current `view` already runs `RTP.scheduler.runTaskAsynchronously` for file reads, which becomes irrelevant once we stop touching the file.

## 6. Risks and trade-offs

- **Loss of raw-YAML-with-comments visibility.** Today, `view` shows the literal file including comments and any hand-edits. The new view shows typed parameters and their current values, which is the more useful abstraction for editing - but it hides the comment block (except in tooltips) and any keys present in the file but not in the parser's enum (i.e. unknown / deprecated keys). **Mitigation:** keep a hidden / opt-in `view raw` subcommand that preserves today's behavior, or add a `?` row at the top of the book that opens a chat dump. **(?) Q4** - do you want raw-YAML access preserved at all?
- **Anvil only accepts ~50 characters.** Long string values (e.g. URLs, long biome lists) will not fit. For those, the anvil flow degrades; the fallback should be to surface `/rtp config <file> set <key> ...` as a `SuggestInput` row instead of `PromptAnvilInput`. The existing param-picker already has this fork; reuse it.
- **Concurrent edits.** Two players viewing the same file, one edits a key, the other's book shows stale values until they re-open. **(?) Q5** - acceptable? The redeem path could broadcast a "your view book is stale" message to other viewers, but that needs a per-file viewer registry.
- **No ADR change needed.** If route (a) holds, this is purely additive within Stage A semantics. The risk of needing a superseding ADR is low but non-zero - we should walk the proposal past ADR-045's "Open question" section once before final approval.

## 7. Open questions (consolidated)

- **Q1.** "Suggested values" = current-value prefill only, or a value-picker sub-page for enum keys with prefill-fallback?
- **Q2.** Write-back via synthesized `/rtp config <file> set <key> <value>` (reuses all validation/logging), or direct `configParser` write?
- **Q3.** List-valued / map-valued keys - skip, sub-page, or "open in chat for now" fallback?
- **Q4.** Preserve raw-YAML chat view as a `view raw` opt-in, or drop it entirely?
- **Q5.** Stale-view problem under concurrent edits - ignore, broadcast a notice, or build a viewer registry?
- **Q6.** Route (a) `OpenMenu` path-walker vs. route (b) new `MenuAction.OpenConfigView` variant - I lean (a), confirm?

---

## 8. Next steps (only after answers to the open questions)

1. Answer Q1-Q6, narrow the proposal.
2. Promote this file to `CHECKLIST-config-view-as-book.md` once approved (per AGENTS.md *Checklist-Based State Tracking*).
3. Implement the page-builder + `ViewSubConfigCmd` rewrite as a single, small `[CODE]` slice.
4. Locale parity via the TSV pipeline for any new `messages.yml` keys.
5. `LocaleParityTest` + scoped tests + `.\gradlew build` per AGENTS.md *Final Full Build*.

---

# v2 - reshaped design after user answers (2026-05-18)

The Q1/Q3/Q6 answers materially change the architecture from v1. Replaying the design with those decisions:

## v2.1 New mental model: a dedicated config-menu subtree, not a reflected-tree page

User rejected route (a) (reflected `TreeCommand` path-walking) on the basis that Stage A's experience showed it produces poor UX. The new shape is:

- A **first-class menu subtree** rooted at a curated `config` entry on the menu front page / admin panel. This is hand-authored, not derived by reflection over the command tree.
- **Page 1: config-file selector.** Rows = one per known `ConfigParser` (messages, performance, safety, economy, effects, logging, regions, worlds, ...). Each row clicks to **Page 2** for that file.
- **Page 2: per-file key list.** Rows = one per parser key, label `<paramName>: <currentValue>`. Each row clicks to **Page 3** for that key.
- **Page 3: per-key picker.** This is **`buildParamPicker` reused verbatim** (Q1). Allowed-values rows for typed/enumerable keys, plus the existing `PromptAnvilInput` "type a value" tail. List-valued keys get a variant (see v2.4 below).
- **Back rows** at every level (Stage A `MenuAction.OpenMenu` semantics still work; only the *entry point* of the subtree is curated, not the internal navigation).
- The **command path-walker (route a) survives as a fallback row** on the front page / admin panel: "Browse all commands" -> the reflected tree as today. This preserves the discoverability of obscure subcommands without forcing them onto the curated path.

## v2.2 New `MenuAction` variants (Q6 decision implies this)

Because we're *not* path-walking the reflected tree for config, we need dedicated tokens for the curated entry points so `BookMenuRenderer` / `MenuRedeemSubcommand` can identify them without parsing strings:

- `MenuAction.OpenConfigSelector` - no payload; opens the file-selector page.
- `MenuAction.OpenConfigFile(String fileName)` - opens the per-file key list for `fileName` (matches a `ConfigParser.name`).
- `MenuAction.OpenConfigKey(String fileName, String paramName)` - opens the per-key picker (delegates internally to `buildParamPicker` with a synthesized parent + key descriptor).
- `MenuAction.OpenConfigListEntry(String fileName, String paramName, int index)` - opens the click-to-remove confirm or no-op acknowledgement for list-valued keys (Q3).

Each gets a small `dispatchOpenConfig*` arm in `MenuRedeemSubcommand`. All four follow the same server-resolved-token contract as the existing `OpenMenu` / `PromptAnvilInput`, so `BookMenuRenderer` round-trips them as `/rtp menu token:<t>` click events with no new transport logic.

**(?) Q7:** Are four new variants acceptable, or would you rather collapse to a single `OpenConfigPage(fileName?, paramName?, listIndex?)` discriminated by nullness? Four is cleaner pattern-matching; one is fewer sealed-type rows to maintain. I lean four.

## v2.3 Write-back unchanged (Q2 confirmed)

Both the picker rows (for enumerable values) and the anvil "type a value" submit route synthesize `/rtp config <file> set <key> <value>` and dispatch it through the normal command path. No new write path. All existing validation, audit logging, and the `RTP.serverAccessor.sendMessage(updatedMsg)` confirmation in `SubConfigCmd.onCommand` are inherited.

The only nuance: `buildParamPicker` today writes back to a **command parameter**, not a config key. So either:
- **v2.3a.** Generalize `buildParamPicker`'s write-back binding so the same builder can target "set this command param" *or* "set this config key" depending on a `WriteBackBinding` strategy passed in. Cleanest abstraction; small surface change.
- **v2.3b.** Copy `buildParamPicker` into a sibling `buildConfigKeyPicker` that hardcodes the config-set synthesizer. More duplication, less risk of regressing the command-param path.

**(?) Q8:** v2.3a (refactor `buildParamPicker` to accept a write-back binding) vs v2.3b (sibling builder, copy-paste)? I lean v2.3a but only mildly - the duplication in v2.3b is bounded.

## v2.4 List/map key editor (Q3 expanded)

For a list-valued key (`worlds`, biome lists, allow-lists), Page 3 changes shape:

- Rows = one per current list entry, each labeled `<entry>` with a click action that submits `/rtp config <file> set <key> remove <entry>` (or equivalent - need to verify `SubConfigCmd`'s actual list-edit grammar, **(?) Q9**).
- Footer row: `[+ add]` -> `PromptAnvilInput` with empty prefill. Anvil submit synthesizes `/rtp config <file> set <key> add <typedValue>`.
- After either action the redeem path returns the player to the same Page 3, which is rebuilt fresh and shows the updated list.

For map-valued keys (rare; if any exist - **(?) Q10**: do any current config keys use map values that the user-facing UI needs to edit?): treat each entry as a `(subkey, subvalue)` pair and render two click targets per row. This is a follow-up; not blocking v2.

## v2.5 Affected modules (replaces v1 section 4)

| Module | File | Change |
|---|---|---|
| `rtp-api` | `menu/MenuAction.java` | Add four sealed variants: `OpenConfigSelector`, `OpenConfigFile`, `OpenConfigKey`, `OpenConfigListEntry` (or a single `OpenConfigPage`, pending Q7). Same defensive-copy / equals / hashCode pattern as `PromptAnvilInput`. Update `MenuModelSurfaceTest` permitted-types list. |
| `rtp-api` (test) | `menu/MenuModelSurfaceTest.java` | Permitted-types assertion + defensive-copy tests for each new variant. |
| `rtp-core` | `commands/menu/CommandTreeMenuBuilder.java` | Add `buildConfigSelector`, `buildConfigFile`, `buildConfigKey`. `buildConfigKey` either delegates to a refactored `buildParamPicker` (Q8 = v2.3a) or to a new sibling (Q8 = v2.3b). |
| `rtp-core` | `commands/menu/MenuRedeemSubcommand.java` | Four new `dispatchOpenConfig*` arms (or one, pending Q7). Each rebuilds the target page and sends it to the player as a fresh book. |
| `rtp-core` | `commands/config/ViewSubConfigCmd.java` | Rewrite `onCommand` body: open the per-file menu page directly (no token mint needed when invoked from a command - we can build the model in-process and hand it to the active renderer). The existing chat-dump becomes a `view raw` subcommand (Q4). |
| `rtp-core` | `commands/config/ViewRawSubConfigCmd.java` (new) | Holds the old file-read + chat-stream logic from `ViewSubConfigCmd`. Registered as a child of `view`. Permission gated. |
| `rtp-core` (test) | `commands/menu/MenuConfigSubtreeTest.java` (new) | (1) selector page lists every registered `ConfigParser`; (2) per-file page renders one row per key with `OpenConfigKey` token; (3) per-key page delegates correctly to picker; (4) list-keys render add-row + per-entry remove rows. |
| `rtp-paper-common` (test) | `menu/BookMenuRendererTest.java` | New tests: each `OpenConfig*` variant round-trips through `/rtp menu token:<t>`, with one mint per fragment. |
| `rtp-plugin` | `messages.yml` baseline + locale TSV pipeline | New keys: `configSelectorTitle`, `configFileRowFormat`, `configKeyRowFormat`, `configListAddRow`, `configListRemoveHover`, `configViewRawHint`. Go through the **TSV pipeline** per AGENTS.md *Locale Config TSV Pipeline*, not hand-edited. |

`SubConfigCmd:373` (registration of `ViewSubConfigCmd`) is the only registration change; the new entry point is added under the existing wiring, not replacing it.

## v2.6 Front-page / admin-panel wiring (deferred but worth flagging)

The user said "config selection then view" lives on the front page or admin panel. I don't yet know the canonical location of that front page in the menu tree - it might be the root of `/rtp menu`, or a separate `/rtp admin menu` builder. **(?) Q11:** which existing menu page should get the new `OpenConfigSelector` row? Pointing me at the builder is enough; I don't need it documented exhaustively.

## v2.7 New open questions (Q7-Q11)

- **Q7.** Four `MenuAction` variants or one discriminated `OpenConfigPage`?
- **Q8.** Refactor `buildParamPicker` to accept a `WriteBackBinding` (v2.3a), or copy-paste into `buildConfigKeyPicker` (v2.3b)?
- **Q9.** Confirm the `/rtp config <file> set <key> add/remove <value>` grammar for list-typed keys - does `SubConfigCmd` already accept these verbs, or do they need to be added?
- **Q10.** Do any current config keys have map values that the in-game UI needs to edit, or are all of them either scalar or list?
- **Q11.** Which existing menu page hosts the new `OpenConfigSelector` row - the `/rtp menu` root, an admin panel, or both?

## v2.8 Implementation order (once v2 questions are answered)

1. Q7-Q11 answered.
2. New `MenuAction` variants land first (smallest surface, easiest to review, no behavior change yet).
3. New `CommandTreeMenuBuilder` builders + tests next, still without the redeem path wired - just verify the model is correct in isolation.
4. `MenuRedeemSubcommand` dispatch arms + `ViewSubConfigCmd` rewrite + `ViewRawSubConfigCmd` extraction.
5. `BookMenuRenderer` round-trip tests.
6. Locale TSV pipeline pass for the new `messages.yml` keys.
7. `LocaleParityTest` + scoped tests + `.\gradlew build`.

Each step independently verifiable; the checklist (once promoted) will mirror this order.

---

# v3 - corrected after reading `SubConfigCmd.addParameters` (2026-05-18)

The Q9 answer pushed me to read the actual write grammar. v2's assumptions about `set <key> <value>` and "refactor `buildParamPicker`" are both wrong. Ground truth:

## v3.1 What actually exists in `SubConfigCmd`

- `SubConfigCmd.onCommand` consumes `parameterValues: Map<String, List<String>>` directly. There is **no `set` verb**. The write form is `/rtp config <file> <key>:<value> <key>:<value> ...` — every config key is a top-level commands-api parameter on the `SubConfigCmd` itself.
- `addParameters()` walks the parser's `EnumMap` and, per typed value, registers a tailored `CommandParameter` (line 366-448): `BooleanParameter`, `IntegerParameter`, `FloatParameter`, `WorldParameter`, `RegionParameter`, `ShapeParameter`, `VertParameter`, or an inline `CommandParameter` (for raw strings). Sections that aren't `shape`/`vert` are recursed via `addSectionParameters`. **Each of these already exposes `.values()` and a description**, which is exactly what `buildParamPicker` consumes.
- **List-typed keys** (`block-foo`, `biome-foo`, anything `instanceof List`) get a dedicated `ListCmd` subcommand (line 446), with its own `ListAddParameter` and `ListRemoveParameter` files at `commands/config/list/`. So Q9's `add`/`remove` semantics **already exist** — under `/rtp config <file> <listKey> add <value>` / `... remove <value>`, dispatched through `ListCmd`, not through the parent `set`/`SubConfigCmd`.
- **`shape` / `vert` sub-parameters (Q10):** when the key is `shape` or `vert`, the registered parameter is `ShapeParameter` / `VertParameter` whose `.values()` returns factory-registered type names (`SQUARE`, `CIRCLE`, `JUMP`, `LINEAR`, ...). Selecting a type name *activates* extra sub-parameters via the `factory.get(name).getData()` call in `onCommand` (line 159+). Those sub-parameters are then also valid keys in the same `parameterValues` map. So the "select a type, get new sub-parameters" UX is already encoded in the data; it just needs the renderer to ask the factory for the activated sub-parameters after a type is picked.

## v3.2 Implications

**Q8 is moot.** No `WriteBackBinding` abstraction needed. `buildParamPicker` already targets a `TreeCommand` + `CommandParameter` pair. The config-key picker is just `buildParamPicker(theSubConfigCmdInstance, <keyName>)` — verbatim, no refactor. This is a big simplification.

**Q9 grammar corrected.** List edits go through `ListCmd`. The Page 3 editor for a list key doesn't synthesize anything novel — it just opens the `ListCmd` page (which is also already a `TreeCommand` that `buildParamPicker` can render). `ListAddParameter` exposes the anvil-target free-form entry; `ListRemoveParameter` exposes the current entries as `.values()`.

**Q10 (shape/vert sub-params) needs a small extension to `buildParamPicker`.** Today the builder shows the `.values()` of a single parameter. For shape/vert, after the user picks a value the **remaining sub-parameters of that activated type** need to surface as further rows. Options:

- **v3.2a.** Two-step page: Page 3a "pick `shape` type" -> Page 3b "configure sub-parameters of this `shape` type". 3b is built by querying `RTP.factoryMap.get(RTP.factoryNames.shape).get(typeName).getData()` for the EnumMap of sub-params, and rendering each as its own row that opens its own picker. This composes cleanly with the existing 3-page subtree.
- **v3.2b.** Inline expansion: clicking `shape` in Page 2 opens a Page 3 that combines the type picker *and* the activated type's sub-parameters on the same page (multi-section book page). Denser but trickier to lay out.

I lean **v3.2a** (composes, no special-case rendering, matches user's "config selection -> view" mental model). **(?) Q12** to confirm.

**Q11 confirmed:** new `OpenConfigSelector` row lands on the current `/rtp menu` root page. Future admin-panel nesting is non-blocking — we just keep `OpenConfigSelector` as an action and re-host the row when the admin panel exists.

## v3.3 Revised affected modules (replaces v2.5)

| Module | File | Change vs v2 |
|---|---|---|
| `rtp-api` | `menu/MenuAction.java` | **Same as v2:** four new `OpenConfig*` variants (Q7 confirmed). |
| `rtp-core` | `commands/menu/CommandTreeMenuBuilder.java` | `buildConfigSelector` (file list), `buildConfigFile` (delegates to existing `buildParamPicker(theSubConfigCmd, key)` for each key). **No `buildParamPicker` refactor.** Add small extension for shape/vert: after a value pick, query the factory's `getData()` and render sub-param rows. |
| `rtp-core` | `commands/menu/MenuRedeemSubcommand.java` | Three new dispatch arms (`OpenConfigSelector`, `OpenConfigFile`, `OpenConfigKey`). `OpenConfigListEntry` is **not needed** because list editing routes through `ListCmd`'s existing parameters via `buildParamPicker`. Drop that variant from v2 (revise Q7 answer: **three** variants, not four). |
| `rtp-core` | `commands/config/ViewSubConfigCmd.java` | Body becomes "open `OpenConfigFile(this.name)`". Old chat-dump body moves to `ViewRawSubConfigCmd`. |
| `rtp-core` | `commands/config/ViewRawSubConfigCmd.java` (new) | Holds the old `Files.readAllLines` + chat-stream logic. |
| `rtp-core` (test) | `commands/menu/MenuConfigSubtreeTest.java` (new) | Selector / file / key page assertions. For shape/vert, assert the post-pick sub-parameter rows are rendered. For list keys, assert delegation to `ListCmd`'s parameter rows. |
| `rtp-api` (test) | `menu/MenuModelSurfaceTest.java` | Permitted-types + defensive-copy for the **three** new variants. |
| `rtp-paper-common` (test) | `menu/BookMenuRendererTest.java` | Token round-trip for `OpenConfigSelector`/`OpenConfigFile`/`OpenConfigKey`. |
| `rtp-plugin` | `messages.yml` + TSV pipeline | Same as v2: `configSelectorTitle`, `configFileRowFormat`, `configKeyRowFormat`, `configViewRawHint`. **Remove** v2's `configListAddRow`/`configListRemoveHover` from this slice — they belong to a separate "ListCmd as a menu page" task if one is needed, since `ListCmd` rendering is already covered by `buildParamPicker` for free. |

Net effect of v3 vs v2: **less new code**, no `buildParamPicker` refactor, one fewer `MenuAction` variant, list-key UX inherited from existing `ListCmd`. Shape/vert sub-parameter expansion is the only genuinely new builder logic, and it's a small factory-driven `getData()` walk.

## v3.4 New / surviving open questions

- **Q7 (revised).** Still "MenuAction variants are good" per user, but **three** variants now (`OpenConfigSelector`, `OpenConfigFile`, `OpenConfigKey`), not four. Confirm.
- **Q8.** **DROPPED.** Not needed.
- **Q9.** **RESOLVED.** List edits go through `ListCmd`, already wired.
- **Q10.** **PARTIALLY RESOLVED.** Shape/vert sub-parameter expansion is the work; only Q12 remains.
- **Q11.** **RESOLVED.** `/rtp menu` root.
- **Q12 (new).** Shape/vert sub-parameter rendering: **v3.2a** two-step page (pick type, then configure sub-params) vs **v3.2b** combined page. I lean v3.2a.
- **Q13 (new).** When the user clicks a `shape`/`vert` sub-parameter, the write needs to send *both* the type name *and* the sub-parameter value in the same `parameterValues` map (because the type acts as a discriminator for which sub-parameters are valid). Is it acceptable to have the menu *remember* the type selection in the menu token state and re-send it on each sub-param write, or should we treat each sub-param write as a standalone command (relying on the parser's current state to fill the type)? I lean the latter (stateless writes, matches the current `parameterValues.putIfAbsent("vert", ...)` defaulting at line 122).

## v3.5 Revised implementation order

1. Answer Q7 (revised to 3) + Q12 + Q13.
2. Add three new `MenuAction` variants (`OpenConfigSelector`, `OpenConfigFile`, `OpenConfigKey`) + tests.
3. `CommandTreeMenuBuilder.buildConfigSelector` + `buildConfigFile`, both as thin layers over `buildParamPicker`. Tests against a fake `ConfigParser`.
4. Shape/vert sub-parameter expansion in `buildParamPicker` (the only non-trivial builder change). Tests for `RegionKeys.shape` + `RegionKeys.vert`.
5. `MenuRedeemSubcommand` three new dispatch arms.
6. `ViewSubConfigCmd` rewrite + `ViewRawSubConfigCmd` extraction + registration.
7. `OpenConfigSelector` row added to the `/rtp menu` root page.
8. Locale TSV pipeline for the new `messages.yml` keys.
9. `LocaleParityTest` + scoped tests + `.\gradlew build`.

Each step independently verifiable; the resulting checklist will follow this exact ordering.

---

# v3.6 - refinements from user (2026-05-18, post-v3 review)

Three follow-up clarifications addressed against v3.5. None invalidate the v3 design; they tighten three specific points.

## v3.6.1 (re §3.2) `view` opens the book directly — not "synthetic"

v3 framed `ViewSubConfigCmd.onCommand` as "synthesize an `OpenConfigFile` dispatch". User clarifies: `/rtp config <file>` (with no further verb) already *implies* `view` in the command grammar; we can let `view` **be** the book entry point with no synthetic indirection.

**Concrete change vs v3.3 row for `ViewSubConfigCmd`:**

- `ViewSubConfigCmd.onCommand` builds the per-file page **in-process** by calling `CommandTreeMenuBuilder.buildConfigFile(parentSubConfigCmd, callerId, ...)` and hands the resulting `MenuModel` to the active renderer (book on Paper/Folia, chat fallback elsewhere). No token mint is needed for the entry transition because the player isn't clicking through a serialized token — they typed the command.
- The `OpenConfigFile` `MenuAction` variant still exists and is still used for the *click* path from the selector page (Page 1 -> Page 2). The two entry points converge on the same `buildConfigFile` builder.
- "Synthetic command synthesis" (`/rtp config <file> view` issued internally) is **not** how the book opens — that was a v1/v2 framing that v3 partially carried over. The book opens by direct builder invocation; the command path and the click path both call the same builder.

Net effect: one fewer indirection layer, same observable behavior, same tests apply.

## v3.6.2 (re §3.4 Q1) Adopt interpretation (ii) — value-picker sub-page for enum-typed keys

v1 Q1 had two readings of "suggested values":
- (i) current value as anvil prefill only;
- (ii) a value-picker sub-page (allowed values as rows) with an anvil "type a value" tail row as fallback.

User selects **(ii)**. This is already exactly what `buildParamPicker` produces (`safeValues(...)` rows + trailing `PromptAnvilInput`), so v3's "reuse `buildParamPicker` verbatim" plan is **unchanged**. Recording this as a definitive answer (rather than an assumption) so the checklist step for `buildConfigFile` doesn't drift back to a bare-anvil prefill shortcut.

**Implication for shape/vert (v3.2a two-step):** Page 3a is the type picker — its allowed values come from `ShapeParameter.values()` / `VertParameter.values()` (factory-registered type names), with an anvil tail for typos / unknown types. Page 3b is the sub-parameter list — each sub-parameter row is itself a `buildParamPicker` page, so the value-picker semantics nest naturally.

**Implication for raw-string keys (no enumerable values):** `safeValues(...)` returns empty, so the page degrades to the single `PromptAnvilInput` row with the current value prefilled. This matches v1 (i) behavior exactly for that subset — (i) is the empty-`.values()` special case of (ii), not a separate mode.

## v3.6.3 (re §3.5) Book must reflect the updated value after a write

v3 didn't pin down what the player sees after submitting an anvil edit. User: the book **must, at minimum, show the updated value after the write**, even if making the change *durable* (config reload, on-disk persistence) is deferred to an explicit later reload.

**Contract:**

1. After a successful write (any path: enum row click, anvil submit, list add/remove via `ListCmd`), the redeem handler **rebuilds the originating page fresh** from the live `ConfigParser` state and resends it to the player. The freshly built `MenuModel` reflects the new value in the row label (`<paramName>: <newValue>`).
2. "Live `ConfigParser` state" = whatever `getConfigValue(key, default)` returns immediately after `SubConfigCmd.onCommand` finishes its write. `SubConfigCmd` already mutates the in-memory parser before its async write completes (`configParser.set(...)` calls precede the file flush), so the rebuilt page reads the post-write value without waiting for I/O.
3. **Durability is separate.** If the design chooses to defer on-disk persistence to a later `/rtp reload` (which is a valid choice — `SubConfigCmd` already writes through, but a future variant could batch), the in-memory parser still reflects the change, so the rebuilt book is still correct. The user-visible commitment is "the book shows your edit"; on-disk timing is an implementation detail.
4. **Concurrent stale views (v1 Q5) remain ignored** — other players' open books still need ~5s natural refresh. v3.6.3 only governs the *acting* player's own book.

**Implementation note:** this is already the v3 redeem semantics for list keys ("rebuilt fresh after each edit", v2.4). v3.6.3 generalizes it: **every** `MenuRedeemSubcommand.dispatchOpenConfig*` arm that follows a write rebuilds the originating page from live parser state before sending. Add an explicit test:

- `MenuConfigSubtreeTest.rebuildAfterWrite_reflectsNewValue`: write a key via the redeem path, immediately rebuild the per-file page, assert the row label contains the new value (not the pre-write value).

## v3.6.4 No new open questions

All three points are answers, not new questions. v3.5's implementation order is unchanged; the test list grows by one (`rebuildAfterWrite_reflectsNewValue`) and the `ViewSubConfigCmd` rewrite description (step 6) simplifies to "direct `buildConfigFile` invocation, no synthetic command".

Design is now ready for D-005 approval -> checklist promotion. No further questions outstanding.

---

# v3.7 - implementation defaults (2026-05-18, user-answered latent items)

Five latent items surfaced after v3.6 review that were defaultable but not pinned. User answered each; recording verbatim so the checklist doesn't re-derive them.

## v3.7.1 Permission gating

- Gate the config selector row and all `OpenConfigSelector`/`OpenConfigFile`/`OpenConfigKey` redeem arms on the existing **`rtp.config.view`** command permission (the same node that already gates `/rtp config`).
- **Hide the row** for senders lacking the permission (do not render it greyed-out). Mirrors how `SubConfigCmd` itself rejects.
- When the future admin panel exists, the row migrates under it; the permission node stays the same.

## v3.7.2 `messages.yml` keys

- More configurability is preferred over fewer keys. Final key surface (replaces v3.3's row):
  - `configSelectorTitle`, `configSelectorBackRow`
  - `configFileTitle` (per-file page header, takes file name placeholder), `configFileBackRow`, `configFileEmptyHint` (shown when the file is blank, see v3.7.4)
  - `configKeyRowFormat` (label format: `<paramName>: <currentValue>`), `configKeyHoverFormat` (hover description)
  - `configTypePickerTitle` (shape/vert step 3a header), `configSubParamPageTitle` (shape/vert step 3b header)
  - `configViewRawHint` (footer row that opens `ViewRawSubConfigCmd`)
  - `configValueUnsetPlaceholder` (display when `getConfigValue(...)` returns null/default)
- All keys go through the **TSV pipeline** (see AGENTS.md *Locale Config TSV Pipeline*). Step 8 of v3.5 is updated: edit `messages.yml` baseline, run `locale-files-to-csv.ps1` -> `reconcile-locale-csvs.ps1`, translate seeded rows in `scripts/out/locale-*.tsv`, run `locale-files-from-csv.ps1`, then `LocaleParityTest`.

## v3.7.3 Token TTL

- All three new variants (`OpenConfigSelector`, `OpenConfigFile`, `OpenConfigKey`) mint tokens with a **1-hour TTL** in `MenuTokenRegistry`.
- Existing `PromptAnvilInput` tokens emitted from within these pages keep their default TTL (ADR-045) - only the `OpenConfig*` tokens get the longer lifetime, since users may park on a selector or per-file page.
- Cut back later if we observe leaked tokens or memory pressure; until then 1 hour is the policy.

## v3.7.4 Empty / unreadable config files

- Config files are dynamically detected via `RTP.configs`; the only realistic edge case is a **blank but extant** file (the parser yields no keys).
- For that case, `buildConfigFile` renders a file-accurate empty page: file-name title row + `configFileEmptyHint` row + back button. No "(unavailable)" or greyed treatment needed.
- Deleted files are regenerated by the reload pipeline before the menu is opened, so a missing file is **not** a state the menu needs to handle.

## v3.7.5 Shape / vert sub-parameter rendering source

- Q13's stateless-write decision stands: the parser's stored `shape`/`vert` type discriminates which sub-parameters are valid.
- For *rendering* the type picker (Page 3a) and the post-pick sub-parameter list (Page 3b), **reuse the existing tab-complete value-source functions** that the commands-api uses for these parameters. Specifically:
  - Type picker rows come from the same function `ShapeParameter` / `VertParameter` already exposes for tab-completion (factory-registered names).
  - Sub-parameter rows come from the activated factory's `getData()` (already used by `SubConfigCmd.onCommand`).
- This means **no new value-enumeration code** for shape/vert - the menu builder calls the same enumerators the command tab-complete already calls. Orphaned sub-params from a previous type are left in place (parser ignores them under the new type, cheapest correct behavior).

## v3.7.6 No new open questions

All five items are now defaults of record. v3.5's nine-step implementation order is unchanged; step 8 (locale TSV pipeline) is expanded to cover the larger key surface from v3.7.2, and step 4 (shape/vert expansion) is clarified to reuse existing tab-complete value-source functions per v3.7.5.

Design is fully ready for D-005 approval -> checklist promotion.
