# Checklist — Generalized menu framework + `/rtp config` first consumer

**Effective Issue:** Ship the first generalized menu (ADR-035 amended + ADR-044) with `/rtp config` as the first consumer. GUIs supply options and sub-GUIs based on the subcommands and parameters under the current command node; hover text comes from YAML block comments (parameters) and `description()` (commands). Cross-server is **not** an extra surface — the live `commands-api` tree (updated at runtime by RTP from DB state) is the single source of truth, so menus simply re-reflect on every open.

**Mode:** `[CODE]` — multi-session, D-005 (approved).

**Governing ADRs:** ADR-035 (Accepted, 2026-05-15 amendment in place), ADR-044 (Proposed), ADR-042 (block-comment preservation in YAML substrate).

**Approved scope answers (locked):**
- A. Inaccessible commands are **fully hidden**, not greyed out (filter via `CommandsAPICommand#permission()` + caller's `Predicate<String>`).
- B. Leaf parameter editors emit `MenuAction.SuggestInput("<prefix>")`. Enum/boolean enumeration is a follow-up if it proves useful in practice.
- C. Hover-text source: parameters → YAML block comment (via in-house substrate `RtpYamlSection#getComment`), with declared-type+bounds fallback; commands → `CommandsAPICommand#description()` (resolved from `messages.yml`).
- D. New ADR (ADR-044) lives under the project-wide `docs/adr/` sequence.
- E. ADR-035 amended **in place** (status: Proposed → Accepted, `SharedMenuTokenRegistry` scope struck).

---

## Stage 1 — `rtp-api` menu foundations (THIS SESSION)

Goal: land the platform-neutral menu surface so downstream stages can be reviewed against a frozen contract. No behavior, no consumers, no renderers.

- [x] 1.1 `MenuAction` sealed interface + four permitted records: `RunRtpCommand(String[] args)`, `ChangePage(int pageIndex)`, `SuggestInput(String prefix)`, `OpenExternalUrl(java.net.URI uri)`. — `rtp-api/.../menu/MenuAction.java`
- [x] 1.2 `MenuFragment` record `(String text, String hover /* nullable */, MenuAction action /* nullable */)`. Plain-text only on the API surface. — `rtp-api/.../menu/MenuFragment.java`
- [x] 1.3 `MenuLine` record — immutable, defensively copied via `List.copyOf`. — `rtp-api/.../menu/MenuLine.java`
- [x] 1.4 `MenuPage` record — immutable, defensively copied. — `rtp-api/.../menu/MenuPage.java`
- [x] 1.5 `MenuModel` record — immutable, defensively copied, requires ≥1 page. — `rtp-api/.../menu/MenuModel.java`
- [x] 1.6 `MenuRenderer` interface — `void render(UUID, MenuModel)`; S-006 contract documented. — `rtp-api/.../menu/MenuRenderer.java`
- [x] 1.7 `MenuTokenRegistry` interface — `mint` / `consume` / `outstandingFor`; atomic-consume + S-006 contracts documented. — `rtp-api/.../menu/MenuTokenRegistry.java`
- [x] 1.8 `MenuConsumerProfile` interface with `defaultProfile()` factory (uses `YamlCommentLookup.EMPTY`). — `rtp-api/.../menu/MenuConsumerProfile.java`
- [x] 1.9 `YamlCommentLookup` functional interface + `EMPTY` constant. — `rtp-api/.../menu/YamlCommentLookup.java`
- [x] 1.10 Package `io.github.dailystruggle.rtp.api.menu` with `package-info.java` cross-referencing ADR-035 / ADR-044 / ADR-042 and the inherited S-004 / S-005 / S-006 / S-007 / F-013 requirements.
- [x] 1.11 `MenuModelSurfaceTest` — 12 cases, all green (sealed-shape reflection, defensive-copy on `RunRtpCommand`/`MenuLine`/`MenuModel`, null-arg rejection, exhaustive pattern-matching switch over `MenuAction`, default profile prefix, `YamlCommentLookup.EMPTY`).
- [x] 1.12 `CHANGELOG.md` — added bullet under `[3.0.0-beta.3] - Unreleased ### Added` (line 22) summarising the surface, the deferred `rtp-core` / renderer follow-ups, and the `MenuModelSurfaceTest` coverage.

---

## Stage 2 — `rtp-core` registry, redeem, reflector, profile (THIS SESSION)

- [x] 2.1 `LocalMenuTokenRegistry` — `ConcurrentHashMap<UUID, ConcurrentHashMap<String, Entry>>` + per-player FIFO `Deque<String>` for oldest-evict-first cap (default 256). 96-bit base32 tokens, CAS consume via `ConcurrentHashMap.remove(key, value)`, TTL sweep on `RTP.scheduler.runTaskTimerAsynchronously`. — `rtp-core/.../commands/menu/LocalMenuTokenRegistry.java`
- [x] 2.2 `MenuRedeemSubcommand` (`BaseRTPCmdImpl` subclass, `name()="menu"`): resolves token via `parameterValues`, atomic-consumes through the registry (foreign-UUID rejection is implicit in the registry's owner-keyed `consume`), dispatches `RunRtpCommand` against the live `/rtp` root, rejects non-Run actions as a protocol error. Every rejection logs `RTP.log(WARNING, ...)` and sends a configurable `messages.yml → menuInvalid` / `menuExpired` / `menuUnknownPlayer` message (REQ-RTP-S-004 / S-007 / F-013). — `rtp-core/.../commands/menu/MenuRedeemSubcommand.java`
- [x] 2.3 `CommandTreeMenuBuilder`: walks `TreeCommand.getCommandLookup()` + `getParameterLookup()` filtered by `permission()` (hidden, not greyed, per scope answer A); subcommand fragments carry `RunRtpCommand`, parameter fragments carry `SuggestInput`; mints a token per clickable fragment so the renderer can materialise the `menu:<token>` click payload. Hover resolver: YAML comment → declared type (+ small curated-value list) via `menuHoverFallbackType` / `menuHoverFallbackBounds` → null. — `rtp-core/.../commands/menu/CommandTreeMenuBuilder.java`
- [x] 2.4 `ConfigMenuConsumerProfile`: takes a caller-supplied `Function<String, RtpYamlSection>` resolver (Stage 3 will wire it to the live `Configs` registry); `suggestPrefix` produces `"/rtp config <file> <key>:"` matching `CONFIG_COMMAND_SPEC §2.4`; `commentLookup` reads `RtpYamlSection#getComment` and strips leading `#` markers. — `rtp-core/.../commands/menu/ConfigMenuConsumerProfile.java`
- [x] 2.5 `MenuStageTwoTest` — 12 cases, all green: registry CAS / TTL sweep / oldest-evict cap / arg validation; redeem foreign-UUID rejection / missing token / non-Run protocol error; reflector hides permission-denied subcommands and parameters; reflector emits Run-for-subcommand / Suggest-for-parameter; hover three-step fallback (YAML → type → null); `ConfigMenuConsumerProfile` prefix shape + resolver-exception isolation. — `rtp-core/src/test/java/.../commands/menu/MenuStageTwoTest.java`
- [x] 2.6 Five new `MessagesKeys` entries (`menuInvalid`, `menuExpired`, `menuUnknownPlayer`, `menuHoverFallbackType`, `menuHoverFallbackBounds`) so Stage 2 can reference stable keys; the `messages.yml` `menu:` section and `TRACEABILITY.md` REQ-RTP-F-013 row remain Stage 3 (3.3).

---

## Stage 3 — `/rtp menu` subcommand registration in `rtp-plugin` (NEXT SESSION)

- [x] 3.1 Register `/rtp menu` (and the `menu:<token>` redeem) on the root `/rtp` command in `rtp-plugin`'s Bukkit-family wire-up. — `rtp-plugin/.../RTPCmdBukkit.java` now calls `addSubCommand(new MenuRedeemSubcommand(this, new LocalMenuTokenRegistry(), uuid -> perm -> player.hasPermission(perm)))` after the existing `BukkitTestCmd` line. Verified: `MenuStageTwoTest` 12/12 still green.
- [x] 3.2 `/rtp menu` (no args) opens the root reflected page; `/rtp menu <subtree>` opens the matching `TreeCommand` sub-node; permissions gate visibility per Stage 2. — Implemented in 4.2.a (`MenuRedeemSubcommand.openPage`) and wired in 4.2.d (`RTPCmdBukkit.selectMenuRenderer`). Verified by `openPageDispatchesToRendererWhenNoTokenAndRendererWired` (root page contains `config` subcommand fragment) + `openPageFallsBackToMenuInvalidWhenNoRendererWired` + `openPageRejectsWithMenuInvalidWhenRendererThrows`.
- [x] 3.3 `messages.yml` `menu:` section: REQ-RTP-F-013 row in `TRACEABILITY.md`. — Section 8b added to `rtp-plugin/src/main/resources/messages.yml` with flat keys matching the existing `MessagesKeys` enum naming (`menuInvalid`, `menuExpired`, `menuUnknownPlayer`, `menuHoverFallbackType`, `menuHoverFallbackBounds`); `title` / `empty` / per-subtree titles deferred alongside 3.2 since they're renderer-facing. `TRACEABILITY.md` REQ-RTP-F-013 row expanded with the menu keys and `MenuStageTwoTest` coverage; ADR-035 / ADR-044 cross-linked.

---

## Stage 4 — Paper + Folia `BookMenuRenderer` (THIS SESSION)

**Approved scope amendment (2026-05-15):** no `auto` value on `menu.renderer`. The config holds an **ordered list** of renderer ids; the framework picks the first, and on exception (renderer throws, required API missing, etc.) falls back to the next in the list. Each renderer will be implemented on every platform before release, so the list expresses operator preference, not platform capability detection.

- [x] 4.1 `BookMenuRenderer` in `rtp-paper-common` (shared by Folia — Adventure `Book` is identical). — `rtp-paper/rtp-paper-common/.../menu/BookMenuRenderer.java` translates `MenuModel` → Adventure `Book` with one `Component` per `MenuLine`, one `Book` page per `MenuPage`; `RunRtpCommand` mints a fresh token via injected `MenuTokenRegistry` and emits `ClickEvent.runCommand("/rtp menu:<token>")`; `ChangePage(i)` → `ClickEvent.changePage(i+1)`; `SuggestInput(p)` → `ClickEvent.suggestCommand(p)`; `OpenExternalUrl(u)` → `ClickEvent.openUrl(u.toString())`; non-null hover → `HoverEvent.showText`; null action → no click. S-006 throw on offline player verified by test. Folia entity-scheduler hop deferred to Stage 5 — callers from the command pipeline already run on the correct region thread.
- [x] 4.2 Wire-up in `rtp-plugin` + open-page support in `MenuRedeemSubcommand` (full scope approved 2026-05-15). Sub-checklist:
  - [x] 4.2.a `MenuRedeemSubcommand` gained a 5-arg ctor with optional `MenuRenderer` + `BiFunction<TreeCommand, UUID, MenuModel>` page builder; no-token dispatch routes through new private `openPage(senderId, nextCommand, sink)` which resolves `rtpRoot` (or `nextCommand` if a `TreeCommand`), invokes the builder, then `renderer.render(...)`. Builder / renderer exceptions both log WARN + reject with `menuInvalid` (S-004). Null renderer/builder keeps the legacy `menuInvalid` + WARN fallback (existing 3-arg ctor unchanged).
  - [x] 4.2.b Three new tests appended to `MenuStageTwoTest` (file is the natural home for menu-redeem coverage): `openPageDispatchesToRendererWhenNoTokenAndRendererWired`, `openPageFallsBackToMenuInvalidWhenNoRendererWired`, `openPageRejectsWithMenuInvalidWhenRendererThrows`. Renderer is captured via `AtomicReference`; page builder closes over `CommandTreeMenuBuilder`. All assert on `menuInvalid` rejection path messaging.
  - [x] 4.2.c `menu.renderer: [book]` added to `rtp-plugin/src/main/resources/config.yml` and `rtp-plugin/src/lite/resources/config.yml`. `ConfigKeys` enum gained a `menu` entry so `ConfigParser<ConfigKeys>` recognises the block.
  - [x] 4.2.d `RTPCmdBukkit` now holds a single `LocalMenuTokenRegistry`, a `menuPermissionProbe` `Function<UUID, Predicate<String>>`, and dispatches to a new `selectMenuRenderer(registry)` helper. The helper reads `ConfigKeys.menu → "renderer"` (tolerates `List<?>` or a bare `String`), walks the ordered preference list, instantiates the first matching id (`book` → `BookMenuRenderer(registry)`), and logs `Level.WARNING` for unknown ids, init exceptions, and exhausted lists — returning `null` so the redeem path stays backward-compatible. Page-builder closure binds `CommandTreeMenuBuilder(registry)` + `menuPermissionProbe.apply(viewer)` + a default `ConfigMenuConsumerProfile`.
  - [x] 4.2.e `MenuStageTwoTest` 15/15 green and `BookMenuRendererTest` 10/10 green via `run_test`; `RTPCmdBukkit.java`, `MenuRedeemSubcommand.java`, `BookMenuRenderer.java`, `MenuStageTwoTest.java`, `BookMenuRendererTest.java` all lint-clean.
  - [x] 4.2.f Item 3.2 re-opened and ticked above; 4.2 ticked here.
- [x] 4.3 Tests in `rtp-paper-common/src/test/java/.../menu/BookMenuRendererTest` — **10/10 green** via `run_test`. Cases: page count = `model.pages().size()`; `RunRtpCommand` → `RUN_COMMAND` click with `/rtp menu:tok-0` payload bound to viewer UUID; `ChangePage(2)` → `CHANGE_PAGE` click with value `"3"` (1-based Adventure); `SuggestInput` → `SUGGEST_COMMAND`; `OpenExternalUrl` → `OPEN_URL`; null action → no click and no mint; non-null hover → `SHOW_TEXT` hover; mint count = number of `RunRtpCommand` fragments only; render with `playerLookup → null` throws `IllegalStateException` mentioning the UUID (S-006); non-positive TTL constructor rejects with `IllegalArgumentException`. Pure unit test — no MockBukkit harness required.

---

## Stage 5 — Follow-ups deferred from this session (still beta.3 cycle)

- [ ] 5.1 Spigot per-version `BookMenuRenderer` (`rtp-bukkit-v*` branches for the 1.20.5 / 1.21 component/data-component shift).
- [ ] 5.2 Fabric `ChatMenuRenderer` (deobf carrier + obf carrier via `FabricVersionAdapter#installEffectsWiring`-style dispatch).
- [ ] 5.3 `ChatMenuRenderer` on Paper/Folia/Spigot as the `menu.renderer: chat` fallback. **D-005 approved (2026-05-15):** `page:<n>` commands-api parameter (1-indexed); `pageBuilder` widened via new `MenuOpenRequest(UUID viewer, int pageIndex)` record + `BiFunction<TreeCommand, MenuOpenRequest, MenuModel>`; Spigot uses BungeeCord-Chat (`net.md_5.bungee.api.chat.*`). Sub-checklist:
  - [x] 5.3.a `MenuOpenRequest` record in `rtp-api/.../menu/`. — `MenuOpenRequest.java`: immutable `(UUID viewer, int pageIndex)`, non-null viewer, non-negative `pageIndex`, `firstPage(uuid)` factory. No package-info change (file doesn't enumerate types).
  - [x] 5.3.b `MenuRedeemSubcommand`: register `page` `CommandParameter`, parse 1-indexed int (default 1 → idx 0), switch `pageBuilder` field/ctor to new signature, plumb into `openPage`. — `PARAM_PAGE` constant + `CommandParameter` registered alongside `PARAM_TOKEN` (predicate accepts positive ints only). `MenuPageBuilder` SAM widened: third arg becomes `MenuOpenRequest` (replacing bare `UUID viewer`); `extractPageIndex` translates wire 1-indexed → 0-indexed (missing/non-numeric/<1 collapse to 0 as defensive backstop). `openPage`/`renderAt` plumb the index; `dispatchOpen` (token-bearing OpenMenu) currently passes 0 — to be widened in 5.3.c when `MenuAction.OpenMenu` itself gets a pageIndex slot. All 5 lambda call sites in `MenuStageTwoTest`, `MenuNavigationStageATest`, `MenuParamPickerStageA2Test` updated (`(node, viewer, ...)` → `(node, open, ...)`, with `open.viewer()` extraction where the UUID is needed). New test `openPagePlumbsOneIndexedPageParameterAsZeroBasedToBuilder` covers happy-path / missing / non-numeric / zero. 31/31 menu tests green via `run_test`.
  - [ ] 5.3.c `CommandTreeMenuBuilder.build(...)` — overload accepting `int pageIndex`; existing single-page output stays the default page-0 case.
  - [ ] 5.3.d `ChatMenuRenderer` in `rtp-paper-common` (Adventure; shared by Folia).
  - [ ] 5.3.e `ChatMenuRenderer` in `rtp-bukkit-common` (BungeeCord-Chat).
  - [ ] 5.3.f `RTPCmdBukkit.selectMenuRenderer`: map `"chat"` to platform-appropriate renderer.
  - [ ] 5.3.g Tests: `ChatMenuRendererTest` in both modules (10 cases mirror Stage 4); `MenuStageTwoTest` adds `page` parameter coverage + `MenuOpenRequest` plumbing.
  - [ ] 5.3.h `CHANGELOG.md` bullet under `[3.0.0-beta.3] - Unreleased ### Added`.
- [ ] 5.4 Optional value-enumeration prompt for `EnumParameter` / `BooleanParameter` (Question B follow-up).
- [ ] 5.5 Region picker / `/rtp biomes` / `/rtpadmin` wizard consumers — each plugs into the same reflector with a new `MenuConsumerProfile`. Per-consumer ADRs as needed (D-005).
- [ ] 5.6 Delete this scratch checklist after Stage 6 ships (deferred from "after Stage 4" — module-extraction work in Stage 6 below keeps the checklist live).

---

## Stage 6 — Escalation to top-level `menu-api` module (NEXT-NEXT SESSION, post-Stage 5.3)

**Approved scope (2026-05-15):** Escalate the generalized menu to a top-level sibling module of `commands-api`, `effects-api`, and `maps-api`. Governed by the rewritten [ADR-044](../../adr/ADR-044-command-tree-menu-reflector.md) ("`menu-api` Module for Generalized Interactive Menus"). User clarified the same day that `rtp-api` is allowed to depend on sibling `*-api` modules, which is what lets `menu-api` host the reflector (which is intrinsically coupled to `commands-api`) without forcing the code to live in `rtp-core`.

**Gating conditions (must all be true before Stage 6 starts):**

- [ ] G1. Stage 5.3 (paginated `ChatMenuRenderer` on Paper / Folia / Spigot) complete and green. Minimises mid-stage import churn.
- [ ] G2. Stage 5.2 (Fabric `ChatMenuRenderer`) attempted at least to the point where the **need** for an NM-typed obf/unobf carrier is settled — go/no-go signal for whether `menu-api-fabric-unobf/` is created in the same change or deferred.
- [ ] G3. D-005 proposal authored, posted, and **explicitly approved** by the user before any file moves. This is a multi-class, multi-module change by definition.

**Sub-checklist (single dedicated change — no behavioural edits mixed in):**

- [ ] 6.1 Create `menu-api/` skeleton: `menu-api/src/main/java/io/github/dailystruggle/rtp/menuapi/`, `menu-api/src/test/java/...`, `menu-api/build.gradle` mirroring `commands-api/build.gradle` (no platform deps; `api` scope on `commands-api` only).
- [ ] 6.2 Add `menu-api` to `settings.gradle`. Verify `.\gradlew :menu-api:build` succeeds with an empty source set before any moves.
- [ ] 6.3 Move the 10 surface files from `rtp-api/src/main/java/io/github/dailystruggle/rtp/api/menu/` → `menu-api/src/main/java/io/github/dailystruggle/rtp/menuapi/`. Package rename: `io.github.dailystruggle.rtp.api.menu` → `io.github.dailystruggle.rtp.menuapi`. Use the IDE `rename_element` flow per package, not `search_replace`. Files: `MenuAction.java`, `MenuConsumerProfile.java`, `MenuFragment.java`, `MenuLine.java`, `MenuModel.java`, `MenuOpenRequest.java`, `MenuPage.java`, `MenuRenderer.java`, `MenuTokenRegistry.java`, `YamlCommentLookup.java`, `package-info.java`.
- [ ] 6.4 Move the matching test mirror: `rtp-api/src/test/java/.../menu/MenuModelSurfaceTest.java` → `menu-api/src/test/java/.../menuapi/MenuModelSurfaceTest.java`. Confirm 12/12 still green via `run_test`.
- [ ] 6.5 Move `LocalMenuTokenRegistry.java` and `CommandTreeMenuBuilder.java` from `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/commands/menu/` → `menu-api/src/main/java/io/github/dailystruggle/rtp/menuapi/token/` and `.../menuapi/build/` respectively. Leave `MenuRedeemSubcommand.java` and `ConfigMenuConsumerProfile.java` in `rtp-core` (they bind to the live `/rtp` root and the YAML substrate, both `rtp-core` concerns). Update `MenuStageTwoTest` imports accordingly; confirm 15/15 still green.
- [ ] 6.6 Add `menu-api` as an `api`-scope dependency in `rtp-api/build.gradle` so all RTP consumers get it transitively. Add explicit `implementation`/`compileOnly` `menu-api` dependency in `rtp-core/build.gradle` and `rtp-paper/rtp-paper-common/build.gradle` (and any Spigot/Folia/Fabric module that produces a renderer post-Stage 5) for IDE-navigation clarity, even though it would also resolve transitively.
- [ ] 6.7 Rewrite imports across `rtp-core`, `rtp-plugin`, `rtp-paper/rtp-paper-common`, every Spigot / Folia / Fabric module that touched `BookMenuRenderer` or `ChatMenuRenderer` during Stages 4–5, and their test mirrors. Verify `BookMenuRendererTest` 10/10 still green; verify every Stage 5 renderer test still green.
- [ ] 6.8 **Fabric carrier decision (driven by G2):**
  - If G2 confirmed an NM-typed surface is unavoidable: create `menu-api/menu-api-fabric-unobf/` (Mojmap-built, no `mappings` line, Java 25 toolchain — mirror `effects-api/effects-api-fabric-unobf/`). Add per-version NM-typed obf carriers under `rtp-fabric/rtp-fabric-common/.../menu/`. Wire dispatch through `FabricVersionAdapter#installEffectsWiring`-style entry point. Per-version `rtp-fabric-v*` carriers depend on the unobf module.
  - If G2 confirmed pure Adventure / non-NM-typed is sufficient: skip `menu-api-fabric-unobf/`. Record the decision in the Stage 6 submit summary and in ADR-044's "Migration / Rollout" §; revisit only when a future renderer needs NM-typed types.
- [ ] 6.9 Add `REQ-RTP-MENU-001..004` rows to `docs/dev/REQUIREMENTS.md` (text per ADR-044 §*Requirements*). Add matching `TRACEABILITY.md` rows pointing at `MenuStageTwoTest`, `BookMenuRendererTest`, and any Stage 5 renderer tests in scope.
- [ ] 6.10 Update ADR cross-references: ADR-035 gets a one-line back-reference to ADR-044 noting "menu surface now lives in `menu-api/` per ADR-044 §*Decision*"; ADR-044 stays at its global slot (no `menu-api-ADR-NNN` renumbering this pass — see ADR-044 *Consequences*). If `menu-api/docs/adr/` is created (only if a *new* menu-api-scoped decision is being recorded simultaneously), add a row to `docs/adr/README.md`'s *Subproject ADRs* table.
- [ ] 6.11 `CHANGELOG.md` bullet under `[3.0.0-beta.3] - Unreleased ### Changed`: `Menu surface escalated to new \`menu-api\` sibling module; package renamed io.github.dailystruggle.rtp.api.menu → io.github.dailystruggle.rtp.menuapi`. Add a `### Breaking (compile-only) Changes` row noting the package rename so addon authors recompile cleanly. Apply CHANGELOG hygiene rule (diff against `v3.0.0-beta.1`, not intermediate working-tree state).
- [ ] 6.12 `.\gradlew build` (full multi-module). All green is the gate to submit. Cite headline in submit summary per *Final Full Build*.
- [ ] 6.13 Delete this scratch checklist (5.6 retargeted here).

---

## Notes / open questions

- The runtime command-tree mutation from DB updates (the SSOT mechanism for cross-server menu content) is **not yet a code symbol** as of session start. The reflector reads whatever the live `commands-api` graph holds at mint time, which is already the right behavior; the DB→tree update is a separate axis and not blocking this work.
- Hover text built at mint time (not redeem time) means a config edit between mint and redeem cannot desync the hover *display*; the redeem still dispatches through the live command pipeline, so the *applied* value is always current. Documented in ADR-044.
- Per-player token cap interaction with deep menu pages: pages exceeding the cap paginate (existing `ChangePage` action). Confirmed in Stage 1 by the model shape; enforced in Stage 2 by the registry.
