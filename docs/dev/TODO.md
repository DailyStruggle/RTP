# RTP TODO — Remaining Feature Work

**Scope:** A focused, scannable checklist of the remaining feature streams the maintainer wants to land. This file is a *task tracker*, not a design doc — for rationale, see the linked ADRs and plan documents. For full release planning (tiers, blockers, polish), see [`ROADMAP.md`](ROADMAP.md).

> Keep items independently verifiable. Tick a box only when the work is merged and traceable (commit / ADR / test). Use `- [x]` and append `— <ref>` when closing.
>
> **Pruning policy:** completed (`[x]`) items are removed from this file once verified — the canonical record lives in `CHANGELOG.md`, the referenced ADRs, and `TRACEABILITY.md`. Only outstanding (`[ ]`) work remains below.

---

## 1. Fabric Support

Active frontier per [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md). Tracked in detail under [`MULTI_PLATFORM_PLAN.md`](MULTI_PLATFORM_PLAN.md); items below are the must-finish set to call Fabric "stable". Loom resolution, the S-005 fix in `FabricWorld.getChunkAt`, `FabricServerAccessor.getLocationGenerator`, Brigadier bridge wiring, and effects-API parity have landed — see CHANGELOG `[3.0.0-beta.2]` and the linked rtp-fabric / effects-api ADRs.

- [ ] **Platform smoke test.** Add a Fabric entry to whatever `rtp test full` analogue exists (or create one) so a CI / manual run produces the same pass/fail matrix as Paper and Folia. Covers the still-owed Fabric `ReqRtpS005*` coverage and tab-completion parity test. **Implementation plan:** [`scratch/CHECKLIST-fabric-rtp-test-full.md`](scratch/CHECKLIST-fabric-rtp-test-full.md) (five phases — core SPI lift → leaf migration → Fabric registration → offline tab-completion parity test → docs).
- [ ] **Promote out of "unstable" in `MULTI_PLATFORM_PLAN.md` and `AGENTS.md` *Current Development Focus*** once the smoke-test item is green.

Out of scope here (deliberately): Forge / NeoForge — gated until Fabric stabilises (Phase 4).

---

## 2. Third Queue Layer — L3 Backlog (Binned) Cache — ✅ Landed

Implemented per [ADR-028](../adr/ADR-028-l3-backlog-cache.md) (Accepted 2026-05-07). Symbol: `RegionQueueManager.backlogLocations` (`BacklogLocationBuffer`); world-shared `WorldBacklogBinIndex`; tri-state `Validity`; head-contiguous promotion inline in `Region.execute()`. Config key `backlogCacheCap` (default `1000`; lite resolves to `0` ⇒ disabled). Covered by `BacklogLocationBufferTest` and `WorldBacklogBinIndexTest`. See `CHANGELOG.md` *Added* under `[3.0.0-beta.2]` and `DESIGN.md` §1.1.

Follow-up items deferred out of v1 scope (roadmap, not bugs):

- [ ] **Telemetry.** Surface `backlog-bin-hits` / `backlog-bin-rejects` under `AnvilPrefilterMetrics` and through `/rtp info` (per ADR-028 *Follow-ups*).
- [ ] **Auto-sizing shorthand.** `backlogCacheCapMode = MULTIPLIER × cacheCap` (ADR-028 *Follow-ups*).
- [ ] **Fabric availability.** Re-evaluate once the Fabric anvil pre-filter parity item under §1 closes.

---

## 3. Join Cache — Login Reserve

Per [ADR-023](../adr/ADR-023-login-reserve-cache.md). Symbol: `RegionQueueManager.loginLocations` ("login cache" / "login reserve" / "join cache" in the aliases table). Fabric port has landed (see `FabricEventBridge.initLoginReserveCache` / `FabricOnEventTeleports.onJoin`, covered by `ReqFabricAdr023HasPlayedBeforeTest`).

- [ ] **Audit current implementation surface.** Confirm `loginLocations` is allocated only for the **default region** and only when `rtp.onevent.firstjoin` or `rtp.onevent.join` is enabled. (Current Bukkit impl in `RTPBukkitPlugin.initLoginReserveCache` picks "first region attached to the default world" — verify that resolves to the configured default region, and if not, fix it. Mirror in `FabricEventBridge.initLoginReserveCache`.) Add a test if missing.
- [ ] **First-join vs. subsequent-join semantics.** Verify both event paths (`firstjoin`, `join`) consume from the reserve correctly and fall back cleanly when empty. Negative-path test required.
- [ ] **Refill policy under load.** Document and test the refill cadence (does the scan task top it up? On a timer? On consume?). Capture answer in the ADR if not already there.
- [ ] **Cross-server interaction.** When a player arrives via the proxy "network wait queue" (see [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md)), decide whether the login reserve still applies or is bypassed in favour of a reservation token. Record the decision in the ADR and `MULTI_SERVER_PLAN.md`.
- [ ] **Lite-jar default.** Confirm default in lite assembly ([ADR-024](../adr/ADR-024-rtp-lite-assembly-variant.md)) — likely `0` to keep the trim small. Document.
- [ ] **Operator docs.** Add a short section to `docs/admin/` describing when to enable / size this cache and the trade-off with `keptLocations` warmth.

---

## 4. API Improvements (`rtp-api`)

The April 2026 gap analysis confirmed `rtp-api` abstractions are sufficient for Fabric — gaps are implementation-side, not interface-side. The items below are the **non-Fabric** API hardening work.

- [ ] **S-006 sweep.** Audit every public `rtp-api` entry point for the "called before core loads" path. All such methods must throw `IllegalStateException` (never null-return, never silent no-op). Add `ReqRtpS006*` tests where missing.
- [ ] **External-hook surface lock.** Per [ADR-026](../adr/ADR-026-external-hook-api-surface.md) and [`EXTERNAL_HOOKS.md`](EXTERNAL_HOOKS.md): finalise the `RTPHooks` registry shape, deprecate any ad-hoc reflection paths, and document the supported hook contract for claim plugins, economy, PAPI, world border, and anvil prefilter consumers.
- [ ] **Addon-facing javadoc pass.** `rtp-api` is the public surface for addon devs; every public type and method needs a one-line purpose, a thread-safety note, and a "called before core loaded?" note where relevant.
- [ ] **Versioning policy.** Decide and document the `rtp-api` semver contract for `3.x` (binary vs. source compat for addons). One short ADR.
- [ ] **`StatePredicate` / `SafetyTokenParser` ergonomics.** These were touched recently (see VCS status) — consolidate any new helpers into the public API only after a D-005 proposal; meanwhile, add javadoc clarifying which builders are public vs. internal.
- [ ] **`ChunkSet` boundary review.** Confirm `ChunkSet` (recently modified) does not leak platform types and that its async contract is documented.
- [ ] **Example addon coverage.** Update `addons/RTP_ExampleAddon` to exercise any newly public API — it doubles as an integration test for addon developers.
- [ ] **Translation guide cross-link.** Any new user-facing string introduced by API changes must be configurable per REQ-RTP-F-013; cross-reference [`TRANSLATION_GUIDE.md`](TRANSLATION_GUIDE.md) in the ADR.

---

## 5. Harden `/rtp config` Command and Save Mechanics

Promoted from "debug `/rtp config`" to a full hardening stream. **Specification, decision, and implementation strategy are now in place:**

- **Target behavior** (normative spec): [`CONFIG_COMMAND_SPEC.md`](CONFIG_COMMAND_SPEC.md). Read before changing anything in `rtp-core/.../commands/config/` or the YAML save path.
- **Decision** (eight contracts, Accepted 2026-05-14): [ADR-037](../adr/ADR-037-harden-rtp-config-commands.md).
- **Implementation strategy** (class layout, package, migration sequencing): [ADR-041](../adr/ADR-041-config-command-and-save-implementation.md).
- **Write-path data flow** (Mermaid): [`../architecture/11-configuration-write-and-persist.md`](../architecture/11-configuration-write-and-persist.md).

The boxes below mirror ADR-041's *Migration sequencing* in order. Each step lands its own commit / PR; later steps assume earlier ones are in. Tick a box only when the PR is merged **and** the named test class is green.

- [ ] **Step 1 — Codes + messages.** Land `ConfigReasonCode` enum + `messages.yml → config.error.*` + `config.dryRun.*` keys. Green: `ReqRtpF013ConfigMessageCoverageTest` (bijection check).
- [ ] **Step 2 — Validators (scalar) + audit-first intermediate state.** Land `ConfigParameterValidator` + range/type/enum implementations. Wire as additive pre-check in `SubConfigCmd#onCommand` that emits a `WARNING` audit record without blocking the legacy path. Closes spec gap A1 (partial).
- [ ] **Step 3 — Atomic writer + startup cleanup.** Land `AtomicConfigWriter` (temp+fsync+rename) and `cleanupStaleTempFiles` startup hook. Swap `ConfigParser` save path through the writer. Green: `AtomicConfigWriterRenameAtomicityTest`. Closes spec gaps A9 + A11.
- [ ] **Step 4 — Transaction + audit + executor.** Land `ConfigTransaction`, `ConfigAuditRecord`, `ConfigAuditFormatter`, `ConfigCommandExecutor`. Collapse `SubConfigCmd#onCommand` to delegate. Green: `ReqRtpS004ConfigCommandAuditTest`, `ConfigTransactionAtomicRollbackTest`. Closes spec gaps A1 + A2 + A4.
- [ ] **Step 5 — Dry-run.** Add `--dry-run` to grammar + executor; render diff via `config.dryRun.*`. Green: `ConfigDryRunDiffTest`. Closes spec gap A3.
- [ ] **Step 6 — Scoped permissions.** Add `rtp.config.set.<section>` resolution (umbrella still valid). Green: `ConfigPermissionScopeTest`. Closes spec gap A6.
- [ ] **Step 7 — Grammar unification.** Replace hand-written `onTabComplete` branches with `ConfigParameterGrammar`. Green: `ConfigParameterGrammarParseCompleteParityTest`. Closes spec gap A8.
- [ ] **Step 8 — Schema-checked reload.** Wire `ConfigParameterValidator.validateAll` into `ReloadCmd` / `SubReloadCmd`. Green: `ReloadCmdSchemaValidationTest`. Closes spec gap A7.
- [ ] **Step 9 — `LanguageCmd` hardening.** Same lifecycle plus `reInitializeAllParsers` in §3.7. Closes spec gap A10.
- [ ] **Step 10 — Composite invariants.** Register Polygon (`expand=false`, vertex validity), Rectangle / Ellipse extent positivity (ADR-034) as composite invariants run on every write **and** every reload.
- [ ] **Admin docs final pass.** When all ten steps are green, remove the "⚠️ Hardening in 3.0.0-beta.3" banners from [`docs/admin/COMMANDS.md`](../admin/COMMANDS.md) §`/rtp config` and [`docs/admin/QUICK_START.md`](../admin/QUICK_START.md), and empty Appendix A of [`CONFIG_COMMAND_SPEC.md`](CONFIG_COMMAND_SPEC.md). When the appendix is empty, mark ADR-037 **Implemented** in [`docs/adr/README.md`](../adr/README.md).
- [ ] **Traceability rows.** Each test class above gets a row in [`TRACEABILITY.md`](TRACEABILITY.md) keyed to REQ-RTP-S-004 / REQ-RTP-S-007 / REQ-RTP-F-013 / REQ-RTP-S-006 as appropriate.

Deferred (not blocking beta.3, tracked here for visibility):

- [ ] **YAML comment-preservation gap (spec Appendix A12).** Required for the `view` hover-text feature to render YAML comments faithfully. Needs either an upgrade of the current SnakeYAML wrapper ([ADR-025](../adr/ADR-025-replace-simpleyaml-with-internal-snakeyaml-wrapper.md)) or an in-house round-trip-preserving parser. Open a follow-up ADR before implementation.

---

## 6. RTP Glide — Internalise as Effect (Bukkit landed; Fabric pending)

Originally shipped as the standalone `addons/RTP_Glide` addon. Per [effects-api-ADR-001](../../effects-api/docs/adr/effects-api-ADR-001-glide-effect.md) (Accepted & Implemented 2026-05-05) the decomposition collapsed to a **single `GlideEffect` in `effects-api`**; the drop-height knob (`STARTHEIGHT`) is exposed inside the effect's positional config. The Bukkit-family implementation, config wiring, and the standalone addon removal have all landed (see CHANGELOG `[3.0.0-beta.2]`); Fabric parity and test/doc coverage remain outstanding.

- [ ] **Platform parity.** Spigot / Paper / Folia covered via the Bukkit-family `GlideSafetyListener` (Folia: Entity Scheduler for the watchdog per effects-api-ADR-001 §"Behavioural contract"). **Fabric is explicitly deferred** to Phase 2 of [effects-api-ADR-003](../../effects-api/docs/adr/effects-api-ADR-003-platform-split-bukkit-fabric.md) — `FabricGlideEffect` needs elytra-equip + glide-state plumbing without a clean Fabric primitive. Cross-tracked with the Fabric effects-api parity item in §1.
- [ ] **Safety interaction.** Confirm the start-height jump and the shutdown emergency-platform path (`shutdownPlatformMaterial`, AIR-only replacement) still terminate on S-001-safe blocks; add a regression test covering void-world misconfiguration and a chunk-unload-mid-glide race (the listener already logs the unload case, but there is no `ReqRtp*` test asserting attribution).
- [ ] **Tests + traceability.** Add `ReqRtp*Glide*` (or `EffectsApiGlide*`) coverage for: timeout watchdog, firework-suppression listener, shutdown platform synthesis, and the `PlayerGlideEvent` / `PlayerLandEvent` lifecycle. Update [`TRACEABILITY.md`](TRACEABILITY.md). No `effects-api` tests reference these classes yet (`search_project ReqRtp` in `effects-api/` → no hits).
- [ ] **Docs.** Add a `docs/admin/` section describing the `GLIDE` effect and its keys — `docs/admin/` currently has zero hits for "Glide". `CHANGELOG.md` already records the landing under `[3.0.0-beta.2]`.

---

## 7. Interactive Menus (Book-First, Chat Fallback)

Per [ADR-035](../adr/ADR-035-interactive-menus-book-first.md) (Proposed, target `3.0.0-beta.4`). Book renderer is primary; `tellraw` chat renderer is the fallback. Inventory-GUI renderer is **deferred** pending a secure design.

- [ ] **Land the `rtp-api` menu model.** `MenuModel` / `MenuPage` / `MenuLine` / `MenuFragment` / sealed `MenuAction` + `MenuRenderer` + `MenuTokenRegistry` interface. No platform / Adventure / `org.bukkit.*` imports. S-006 throw-on-pre-core.
- [ ] **`rtp-core` token registry + redeem subcommand.** `LocalMenuTokenRegistry` (in-memory, TTL'd, per-player bounded) and the `rtp menu:<token>` internal subcommand wired through `commands-api`. Atomic single-consume CAS. `ReqRtpS004` audit log on every redeem-failure path.
- [ ] **`SharedMenuTokenRegistry` (cross-server).** SQL driver (reuses `AbstractSQLDatabaseAccessor`) + Redis driver (Lettuce, `SET NX PX` + Lua redeem). Atomic-consume regression test per driver (`SharedMenuTokenRegistryAtomicConsumeTest`).
- [ ] **`BookMenuRenderer` (Paper / Folia / Spigot).** Adventure `Book` on Paper / Folia; `Player#openBook(ItemStack)` on Spigot with the 1.20.5 / 1.21 component-shift handled in the per-version `rtp-spigot-v*` subprojects per [ADR-010](../adr/ADR-010-versioned-platform-adapter-submodules.md).
- [ ] **`ChatMenuRenderer` (all platforms incl. Fabric).** BungeeCord chat-api on Spigot, Adventure on Paper / Folia, native `Component` on Fabric (deobf + obf carriers per [rtp-fabric-ADR-009](../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md)).
- [ ] **Fabric `BookMenuRenderer` follow-up.** `OpenWrittenBookS2CPacket` wiring; deferred from beta.4 per ADR-035 *Decision*. Tracks Fabric parity rule.
- [ ] **First in-tree consumer: `/rtp` no-args region picker.** Page lists permitted regions with hover (distance / cooldown / queue depth); click dispatches `MenuAction.RunRtpCommand("--region", regionName)`. All strings via `messages.yml → menu:` per REQ-RTP-F-013.
- [ ] **`messages.yml → menu:` section.** Keys per ADR-035 *Concrete first consumer* + redeem-failure messages (`menu.invalid`, `menu.expired`, `menu.unknownPlayer`, `menu.unknownRegion`, `menu.storeUnavailable`). REQ-RTP-S-007 / REQ-RTP-F-013 coverage.
- [ ] **Traceability.** Add `MenuRedeemSubcommandTest`, `SharedMenuTokenRegistryAtomicConsumeTest` (one per driver), and the no-args region-picker test to [`TRACEABILITY.md`](TRACEABILITY.md) under REQ-RTP-F-013 / S-007 / NET-011 / 012 / 014.
- [ ] **Admin docs.** Add a `docs/admin/` page describing renderer choice (`menu.renderer: book | chat | auto`), token store (`menu.tokenStore: local | shared`), TTL knobs, and lite-assembly defaults per [ADR-024](../adr/ADR-024-rtp-lite-assembly-variant.md).

Deferred follow-ups (research-only, not blocking beta.4):

- [ ] **`/rtp biomes` menu.** Follow-up ADR; reuses the same primitive.
- [ ] **`/rtpadmin` setup wizards.** Highest-value consumer; deferred to a dedicated ADR ([ADR-038](../adr/ADR-038-rtpadmin-setup-wizards.md)).
- [ ] **Public `MenuRenderer` SPI for addons.** Currently internal-public; lock the addon-facing surface only after the in-tree consumer settles.
- [ ] **Inventory-backed (chest-GUI) renderer.** **Research-only**, gated by D-005 + a successor ADR. Tracked under [`docs/dev/scratch/CHECKLIST-inventory-menu-research.md`](scratch/CHECKLIST-inventory-menu-research.md) (research items R1–R10: `InventoryClickEvent` desync matrix, virtual-inventory wrapper feasibility, `MenuTokenRegistry` + `commands-api` reuse, Fabric parity strategy, lite-assembly impact, prior-art survey, D-005 gate, successor-ADR draft). No code lands until that checklist's R1–R9 are answered and R10 produces an accepted ADR.

---

## Closing Out This File

When **all seven sections** are fully ticked, fold the residue into [`ROADMAP.md`](ROADMAP.md) (or `CHANGELOG.md` for shipped items) and delete this file. Until then, prefer ticking boxes here over rewriting the structure.
