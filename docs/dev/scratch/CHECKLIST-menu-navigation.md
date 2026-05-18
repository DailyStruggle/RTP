# CHECKLIST — Menu Navigation (Stage A + Stage B)

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

---

# Stage B — Quick Actions front page (D-005 proposal — APPROVED 2026-05-16)

**Approved redlines (2026-05-16):**
1. Admin-view gate = new perm `rtp.menu.admin` (default op). `rtp.admin` must include `rtp.menu.admin` as a child (auto-grant).
2. Reload row: **no** confirm-guard. Admins already hold `rtp.reload`; ship with `RunRtpCommand("rtp reload")` and a warning hover. Stage A.2 not a blocker.
3. Row cap OK (player ≤ 7, admin ≤ 9, paginate on overflow).
4. Rows referencing unregistered subtrees silently drop rather than render broken `OpenMenu` actions.

**Second-round redlines (2026-05-16, after command-graph audit):**
- DROP: `↩ Back` (provided by EssentialsX), `🎒 Personal queue` (background feature, `/rtp` decides), `🧰 External hooks` (not necessary), `📊 Server status` as separate row (use `/rtp info`).
- REVISE: `🌍 Pick a region` and `🌳 Pick a biome` are **not** subtree opens; they are **paginated parameter-value picker sub-pages** sourced from `CommandParameter.relevantValues(UUID)` (the existing tab-complete source). This requires a new **generic paginated parameter-value picker** primitive (Stage B.0) that is reusable for any parameter, not just region/biome.
- ADD: `📋 Info` (admin) → `RunRtpCommand("rtp info")` covers status.

**Final row catalogues (post-audit):**

Player view (4 rows):
1. `🎲 Teleport me now` → `RunRtpCommand("rtp")` — visibility: `rtp.use`.
2. `🌍 Pick a region` → `OpenParamPicker("region")` — visibility: any value in `regionParameter.relevantValues(viewer)` is non-empty.
3. `🌳 Pick a biome` → `OpenParamPicker("biome")` — visibility: any value in `biomeParameter.relevantValues(viewer)` is non-empty.
4. `❓ Help` → `RunRtpCommand("rtp help")`.

Admin view (6 rows):
1. `🎲 Teleport me now` → `RunRtpCommand("rtp")`.
2. `📋 Info` → `RunRtpCommand("rtp info")`.
3. `⚙ Config editor` → `OpenMenu(["config"])`.
4. `🔄 Scan control` → `OpenMenu(["scan"])`.
5. `🔍 Diagnostics (full)` → `RunRtpCommand("rtp test full")` (warning hover).
6. `⚠ Reload` → `RunRtpCommand("rtp reload")` (warning hover; perm gate is sufficient).

**Stage B.0 (NEW — generic paginated param-value picker):**
Reuses Stage A.2's value-picker concept but explicitly paginated and driven by `CommandParameter.relevantValues(UUID)`. The page builder produces one `MenuFragment` per value (action = `SuggestInput("/rtp <param>:<value> ")`), batched into `MenuPage`s of N rows (configurable, default 7), with `ChangePage` prev/next rows using the existing `menuPagePrev` / `menuPageNext` keys (Stage A.6). Entry point: a new `MenuAction.OpenParamPicker(String paramName)` variant OR a new `OpenMenu`-style path encoding (e.g. `OpenMenu([":param:region"])`); decision deferred to B.0 implementation but **leaning toward OpenMenu encoding** to avoid widening `MenuAction` again.

**Effective issue:** `/rtp menu` opens the reflected command tree (alphabetised flat list of `TreeCommand` children). Players and admins benefit from a curated landing screen with permission-gated, status-aware rows — see the design memo in chat history dated 2026-05-16.

**Mode:** `[CODE]`, multi-session, multi-class — D-005 proposal below requires explicit user approval before any code changes.

## Stage B proposal (per Rule D-005)

### Affected classes / modules

- **New, `rtp-core`** — `io.github.dailystruggle.rtp.common.commands.menu.FrontPageBuilder`. Sibling to the existing `CommandTreeMenuBuilder`. Produces a `MenuModel` from curated row descriptors rather than reflecting a `TreeCommand`. Pure data builder, no platform imports.
- **New, `rtp-core`** — `LandingMenuConsumerProfile` (alongside `ConfigMenuConsumerProfile`). Provides the `MenuConsumerProfile.suggestPrefix` / `commentLookup` for the landing surface (curated rows largely won't need the YAML comment fallback, but the interface is required by the renderer contract).
- **New, `rtp-core`** — `FrontPageRowDescriptor` (sealed interface or record family) describing a single curated row: label key, optional status-supplier, `MenuAction`, permission predicate, visibility predicate (so e.g. the cooldown row only appears when a cooldown is active). Lives next to `FrontPageBuilder`.
- **Modified, `rtp-core`** — `MenuRedeemSubcommand`: the no-token / `openPage` entry on the root node delegates to `FrontPageBuilder` instead of `CommandTreeMenuBuilder` when the resolved node is the `/rtp` root. Subtree opens (Stage A `OpenMenu(path)`) keep using `CommandTreeMenuBuilder` unchanged.
- **Modified, `rtp-api`** — `MessagesKeys`: add the row-label keys (see §"`messages.yml` keys" below). No surface contract change beyond enum entries.
- **Modified, `rtp-plugin`** — `messages.yml` (+ `lang/es/messages.yml` if present): add default strings for each new key.
- **Modified, `rtp-plugin`** — `RTPCmdBukkit.selectMenuRenderer`-adjacent wiring: pass the `FrontPageBuilder` to `MenuRedeemSubcommand` alongside the existing `CommandTreeMenuBuilder`. No new config knobs in this stage (renderer selection unchanged).
- **No change** — `commands-api` (decision §"Hidden-from-menu opt-out" below).

### Intended before/after structure

**Before (today):** `/rtp menu` → `MenuRedeemSubcommand.openPage` → `pageBuilder.apply(rtpRoot, MenuOpenRequest, …)` → `CommandTreeMenuBuilder.build(rtpRoot, viewer, assembledPath=[])` → flat list of `TreeCommand` children.

**After:** `/rtp menu` → `MenuRedeemSubcommand.openPage` → branch on `node == rtpRoot && assembledPath.isEmpty()`:
- Root + empty path → `FrontPageBuilder.build(viewer, permissionProbe, statusSnapshot)` → curated `MenuModel` with hero/conditional/footer sections.
- Anything else → existing `CommandTreeMenuBuilder.build(...)` (unchanged Stage A behavior).

`OpenMenu([])` from a back-row on a depth-1 page lands on the curated front page too (same branch). This means the back chain naturally terminates at the curated page rather than the flat reflector page.

### Row catalogue (player view — permission-gated, in order)

Each row is a `FrontPageRowDescriptor` with: `MessagesKeys labelKey`, `Predicate<UUID> visibility`, optional `Function<UUID,String> statusSupplier`, `MenuAction action`.

1. `rtp.use` → `🎲 Teleport me now` → `RunRtpCommand("rtp")`. Always-visible if `rtp.use`.
2. `rtp.back` (only if `/rtp back` is registered on the root) → `↩ Back` → `RunRtpCommand("rtp back")`. Visibility also requires a recorded prior teleport (DB lookup gated behind a fast in-memory hint).
3. `rtp.personalqueue` → `🎒 Personal queue` (status: `keptLocations` snapshot for the player's bucket) → `OpenMenu(["personalqueue"])` if that subtree exists, else `RunRtpCommand("rtp personalqueue toggle")`.
4. Cooldown / cost status row (no permission gate; visibility = cooldown active OR cost configured) → status text only, `action=null`.
5. `rtp.regions` (visibility) → `🌍 Pick a region` → `OpenMenu(["regions"])`.
6. `rtp.biomes` → `🌳 Pick a biome` → `OpenMenu(["biomes"])`.
7. Always-visible footer → `❓ Help` → `RunRtpCommand("rtp help")`.

### Row catalogue (admin view — `rtp.admin`, in order)

Admin view replaces (not appends to) the player view when `rtp.admin` is granted. If both teleport- and admin-style perms are held, admin view wins; a `RunRtpCommand("rtp")` row is still included as row 1 so admins can self-teleport.

1. `🎲 Teleport me now` → `RunRtpCommand("rtp")`.
2. `📊 Server status` (status: TPS / MSPT / L1·L2·L3 fill levels via `MemoryTracker` + `METRICS_PLAN.md` instrumentation) → `OpenMenu(["status"])` if registered, else `RunRtpCommand("rtp test")`.
3. `⚙ Config editor` → `OpenMenu(["config"])`. Already exists.
4. `🗺 Region management` → `OpenMenu(["regions"])` (admin-scope view if the subtree differentiates).
5. `🔍 Diagnostics` → `RunRtpCommand("rtp test full")`. Hover warns about cost.
6. `🧰 External hooks` (status: which `RTPHooks` entries resolved) → `OpenMenu(["hooks"])` if registered, else `action=null` status row.
7. `⚠ Reload` → `RunRtpCommand("rtp reload")` with a `menuFrontPageHoverReload` warning hover. **No confirm-guard** (approved 2026-05-16): admins already hold the `rtp.reload` perm to reach this row, so the perm is the gate.

### `messages.yml` keys

All new strings under a `menuFrontPage:` block (REQ-RTP-F-013). Keys (camelCase to match existing `MessagesKeys` naming):

- `menuFrontPageTitle`, `menuFrontPageSectionTeleport`, `menuFrontPageSectionAdmin`, `menuFrontPageSectionStatus`, `menuFrontPageRowTeleportMe`, `menuFrontPageRowTeleportMeSubtitle`, `menuFrontPageRowBack`, `menuFrontPageRowPersonalQueue`, `menuFrontPageRowCooldown`, `menuFrontPageRowCost`, `menuFrontPageRowRegions`, `menuFrontPageRowBiomes`, `menuFrontPageRowHelp`, `menuFrontPageRowServerStatus`, `menuFrontPageRowConfig`, `menuFrontPageRowRegionManagement`, `menuFrontPageRowDiagnostics`, `menuFrontPageRowExternalHooks`, `menuFrontPageRowReload`.

Plus `menuFrontPageHover*` siblings for any row whose hover is curated rather than YAML-derived.

### Hidden-from-menu opt-out

The original chat-history proposal floated `CommandsAPICommand#hiddenFromMenu()`. **Defer**: not needed for Stage B since the curated front page hand-picks rows rather than enumerating subcommands. Revisit only if/when we also want to influence the flat reflector page from Stage A. Recorded here so we don't grow `commands-api` in this change.

### Relevant REQ-* / ADRs

- ADR-035 (menu surface, Accepted), ADR-043 (personal queue), ADR-044 (menu-api / generalized menu), ADR-026 (`RTPHooks` registry), ADR-042 (YAML block-comment preservation — only marginally relevant since curated rows mostly skip YAML hover).
- S-006 (require-by-contract API entry — `FrontPageBuilder` throws `IllegalStateException` if invoked before core is loaded), S-004 (any reject in `MenuRedeemSubcommand` still WARN-logs), F-013 (all row labels via `messages.yml`).
- New `REQ-RTP-MENU-005` row in `REQUIREMENTS.md` + `TRACEABILITY.md`: "The plugin shall provide a curated landing page for `/rtp menu` with permission-gated, status-aware rows." Concrete test class: `FrontPageBuilderTest`.

### Risks and trade-offs

- **Tight coupling between curated rows and live state.** Status suppliers (`MemoryTracker` snapshot, cooldown remaining) read at mint time. If a status changes between mint and redeem the *display* is stale but the *dispatched* command runs against live state — same model as Stage 2 hover-text. Acceptable.
- **Permission probe cost.** Each `FrontPageRowDescriptor.visibility` is one `Predicate<UUID>` call. ~10 rows × 1 perm check = trivial.
- **Two-page model vs one.** The curated front page is its own `MenuPage` with potentially many lines; if it overflows, paginate via `ChangePage` (already supported). Initial cap: keep player view ≤ 7 rows, admin view ≤ 9 rows; one page only.
- **Gating on Stage A.2 confirm-guard for `⚠ Reload`.** If A.2 hasn't landed when B is ready, the cleanest fallback is to omit the reload row from B and ship A.2 + reload-row together in B.2. Recorded below as G2.
- **Localisation surface area.** ~18 new `messages.yml` keys. Defaults in `messages.yml` and `lang/es/messages.yml`.
- **Test cost.** New `FrontPageBuilderTest` (target ~10 cases): visibility gates by permission; status supplier exceptions isolated (don't break the page); admin-view vs player-view selection; back-from-depth-1 lands on curated page; cooldown row visibility logic; reload row absent until A.2.

### Gating conditions

- ~~G1. Stage A.2 confirm-guard.~~ **Lifted (2026-05-16):** reload row ships without confirm-guard.
- **G2.** User approves this proposal in full or with redlines. — ✅ **APPROVED 2026-05-16.**

### Stage B sub-checklist (post-approval)

- [x] B.1. MessagesKeys entries + en/es defaults in `messages.yml` and `lang/es/messages.yml`.
- [x] B.2. (Folded into B.3 — no separate sealed descriptor type needed; the curated catalogue lives directly in the builder.)
- [x] B.3. `FrontPageBuilder` in `rtp-core/.../commands/menu/FrontPageBuilder.java` — curated rows, permission gating, parameter-presence + suggestion gating, throwing-probe isolation with WARN log.
- [x] B.4. (Descoped — the curated front page does not consume YAML comments, so no dedicated `MenuConsumerProfile` was needed; reuse `defaultProfile()`-equivalent inline.)
- [x] B.5. Wired the front-page branch into `RTPCmdBukkit`'s `MenuPageBuilder` closure: root-and-empty path → `FrontPageBuilder`; everything else → existing `CommandTreeMenuBuilder`.
- [x] B.6. `rtp.menu.admin` permission added to `plugin.yml` (default op, listed as child of the `rtp.*` aggregate).
- [x] B.7. `FrontPageBuilderTest` — 9 cases, all green via `run_test`.
- [ ] B.8. (Deferred — REQ-RTP-MENU-* series is not yet propagated into `REQUIREMENTS.md` / `TRACEABILITY.md` for any Stage A test class either; adding only Stage B would be inconsistent. Track as a sweep that picks up Stage A + Stage B together when REQUIREMENTS.md absorbs the MENU-* family from ADR-044.)
- [x] B.9. `CHANGELOG.md` bullet under `[3.0.0-beta.3] - Unreleased ### Added`.
- [x] B.10. `run_test` over the menu test directory: 40 / 40 green (9 new + 31 prior).
- [x] B.11. `.\gradlew build` — BUILD SUCCESSFUL (1m 4s, after `spotlessApply` on the new test file).

---

## Stage B notes / open questions

- Whether `OpenMenu([])` from a depth-1 back should land on the curated front page or the flat reflector page. Recommended: curated, since that's the user-facing root. The flat reflector page becomes effectively unreachable from inside the menu and only renders if someone constructs a Stage A `OpenMenu([])` token by hand — acceptable.
- Whether `rtp.admin` alone or `rtp.admin || rtp.reload || rtp.regenerate` should select the admin view. Recommended: a single `rtp.menu.admin` permission, defaulting to op, so the admin-view gate is explicit and decoupled from any one feature perm. Adds one perm to `plugin.yml`.
- Status-supplier latency budget: each supplier must return in < 1 ms (in-memory snapshot read only). No DB / disk / chunk I/O. Documented on `FrontPageRowDescriptor`.
