# RTP TODO — Remaining Feature Work

**Scope:** A focused, scannable checklist of the remaining feature streams the maintainer wants to land. This file is a *task tracker*, not a design doc — for rationale, see the linked ADRs and plan documents. For full release planning (tiers, blockers, polish), see [`ROADMAP.md`](ROADMAP.md).

> Keep items independently verifiable. Tick a box only when the work is merged and traceable (commit / ADR / test). Use `- [x]` and append `— <ref>` when closing.

---

## 1. Fabric Support

Active frontier per [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md). Tracked in detail under [`MULTI_PLATFORM_PLAN.md`](MULTI_PLATFORM_PLAN.md); items below are the must-finish set to call Fabric "stable".

- [x] **Resolve Loom dependency.** — Toolchain split into obf-carrier (`rtp-fabric-common`, intermediary, Java 21) and unobf-carrier (`rtp-fabric-common-unobf`, Mojmap, Java 25) per [rtp-fabric-ADR-009](../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md); per-version submodules (`v1_20_R1`, `v1_21_R1`, `v1_21_R5`, `v1_21_R11`, `v26_1_R1`) build under [rtp-fabric-ADR-001](../../rtp-fabric/docs/adr/rtp-fabric-ADR-001-multiversion-submodule-layout.md).
- [x] **Eliminate the S-005 violation in `FabricWorld.getChunkAt`.** — `FabricRTPWorld#getChunkAt` (and the unobf mirror) now return `CompletableFuture<Long>` backed by `getChunkFutureMainThread` / `getChunkAtAsync`, per [rtp-fabric-ADR-008](../../rtp-fabric/docs/adr/rtp-fabric-ADR-008-non-blocking-chunk-generation.md); anvil-probe parity per [rtp-fabric-ADR-005](../../rtp-fabric/docs/adr/rtp-fabric-ADR-005-anvil-prefilter-parity.md). `ReqRtpS005*` Fabric-side coverage still owed — tracked under the Platform smoke-test item below.
- [x] **Implement `FabricServerAccessor.getLocationGenerator`.** — `FabricServerAccessor.getLocationGenerator()` returns the live `LocationGenerator` and throws `IllegalStateException` before core init (S-006 compliant), mirroring `AbstractServerAccessor`.
- [x] **Brigadier bridge wiring.** — `FabricCommandRegistrar` reflectively registers `CommandRegistrationCallback.EVENT` and wires `RTPCmdFabricRoot` / `RTPCmdFabric` through `BrigadierCommandAdapter`; per-version adapters dispatch via `FabricVersionAdapter`. Tab-completion parity test still owed (tracked under Platform smoke-test).
- [x] **Effects-API parity.** — `effects-api-fabric-unobf` carrier plus per-version dispatchers (e.g. `V1_21_R11FabricEffectDispatchers`) implement firework / sound / particle equivalents; loading on vanilla Fabric no longer warns. See [effects-api-ADR-006](../../effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md).
- [ ] **Platform smoke test.** Add a Fabric entry to whatever `rtp test full` analogue exists (or create one) so a CI / manual run produces the same pass/fail matrix as Paper and Folia.
- [ ] **Promote out of "unstable" in `MULTI_PLATFORM_PLAN.md` and `AGENTS.md` *Current Development Focus*** once the above are green. Remaining gates: Fabric `ReqRtpS005*` coverage and tab-completion parity test (folded into the Platform smoke-test item above).

Out of scope here (deliberately): Forge / NeoForge — gated until Fabric stabilises (Phase 4).

---

## 2. Third Queue Layer — L3 Backlog (Binned) Cache — ✅ Landed

Implemented per [ADR-028](../adr/ADR-028-l3-backlog-cache.md) (Accepted 2026-05-07). Symbol: `RegionQueueManager.backlogLocations` (`BacklogLocationBuffer`); world-shared `WorldBacklogBinIndex` for cross-RTP-region anvil amortization; tri-state `Validity` (`UNVERIFIED` / `VALIDATED` / `INVALIDATED`); head-contiguous promotion inline in `Region.execute()`. Config key `backlogCacheCap` (default `1000`; lite overlay omits the key so the in-code fallback resolves to `0` ⇒ disabled). Covered by `BacklogLocationBufferTest` and `WorldBacklogBinIndexTest`. See `CHANGELOG.md` *Added* under `[3.0.0-beta.2]` and `DESIGN.md` §1.1.

- [x] **Create `BacklogLocationBuffer`.** — `rtp-core/.../selection/region/BacklogLocationBuffer.java`; tri-state `Validity` supersedes the original boolean `verified` flag (ADR-028 2026-05-08 amendment).
- [x] **Wire `RegionQueueManager.backlogLocations`.** — `RegionQueueManager.backlogLocations` (nullable, allocated only when `backlogCacheCap > 0`); promotion is inline at the end of `Region.processBacklog(...)` rather than on the L2 poll path (ADR-028 2026-05-08 amendment).
- [x] **Bin-grouped verification pulse.** — `Region.processBacklog(...)` peeks the oldest `UNVERIFIED`, derives the `.mca` bin via `RegionFileCoord`, and runs `RTPHooks.anvilPrefilter().current().classify(...)` against every contributor of that bin's `WorldBacklogBinIndex` snapshot. Pulse budget: `availableTime / 4` (count-bound friendly on Folia per ADR-015).
- [x] **Config key `backlogCacheCap`.** — `RegionKeys.backlogCacheCap` enum + `RegionSettings.backlogCacheCap` (long); `regions/default.yml` ships `1000`; lite YAML overlay omits the key so the in-code fallback (`RegionConfigLoader`, `0L`) governs lite.
- [x] **Persistence policy.** — Confirmed by inspection: `RegionQueueManager.installDatabaseCallbacks` attaches callbacks only to `keptLocations` / `unkeptLocations`; `BacklogLocationBuffer` exposes no callback API.
- [x] **MemoryTracker accounting.** — Capacity folded into the diagnostic totals (`MemoryTracker.run()`: `totalCacheCap += settings.backlogCacheCap()`, `totalLocationQueueSize += backlogLocations.size()`); entries hold no chunk tickets and no `TeleportPipelineTask`, so active-GC sweep is N/A by construction.
- [x] **Aliases table follow-up.** — `AGENTS.md` *Domain Analogies & Aliases* row updated to drop the "Proposed" qualifier and point at the live `RegionQueueManager.backlogLocations` symbol.

Follow-up items deferred out of v1 scope (recorded here, not promoted to `POTENTIAL_BUGS.md` because they are roadmap, not bugs):

- [x] **REQ-* traceability rows + `ReqRtp*L3*` test classes.** — `REQ-CORE-F-009` added to `rtp-core/REQUIREMENTS.md` §1.1 ("Backlog Verification Order") and to [`TRACEABILITY.md`](TRACEABILITY.md) rtp-core table, mapped to `BacklogLocationBuffer` / `WorldBacklogBinIndex` / `RegionQueueManager.backlogLocations` / `Region.processBacklog` with `BacklogLocationBufferTest` + `WorldBacklogBinIndexTest`; coverage summary updated to 75 reqs / ~43 automated.
- [x] **Admin docs.** — `backlogCacheCap` row added to the top-level settings tables of both `docs/admin/CONFIGURATION.md` and `docs/admin/REGIONS.md`, plus a new *Backlog Cache (L3)* prose section in `REGIONS.md` (how it works / when to enable / relationship to L1+L2). Both pages cross-link to ADR-028.
- [x] **Localized lang files.** — `backlogCacheCap` rows added to all eight non-English `regions.lang.yml` files actually shipped (`cat`, `de`, `es`, `fr`, `ja`, `ko`, `nl`, `zh`); `ru` / `pt` / `it` are not present in `rtp-plugin/src/main/resources/lang/` and are not in scope for beta.2.
- [ ] **Telemetry.** Surface `backlog-bin-hits` / `backlog-bin-rejects` under `AnvilPrefilterMetrics` and through `/rtp info` (per ADR-028 *Follow-ups*).
- [ ] **Auto-sizing shorthand.** `backlogCacheCapMode = MULTIPLIER × cacheCap` (ADR-028 *Follow-ups*).
- [ ] **Fabric availability.** Re-evaluate once the Fabric anvil pre-filter parity item under §1 closes.

---

## 3. Join Cache — Login Reserve

Per [ADR-023](../adr/ADR-023-login-reserve-cache.md). Symbol: `RegionQueueManager.loginLocations` ("login cache" / "login reserve" / "join cache" in the aliases table).

- [ ] **Audit current implementation surface.** Confirm `loginLocations` is allocated only for the default world and only when `rtp.onevent.firstjoin` or `rtp.onevent.join` is enabled. Add a test if missing.
- [ ] **First-join vs. subsequent-join semantics.** Verify both event paths (`firstjoin`, `join`) consume from the reserve correctly and fall back cleanly when empty. Negative-path test required.
- [ ] **Refill policy under load.** Document and test the refill cadence (does the scan task top it up? On a timer? On consume?). Capture answer in the ADR if not already there.
- [ ] **Cross-server interaction.** When a player arrives via the proxy "network wait queue" (see [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md)), decide whether the login reserve still applies or is bypassed in favour of a reservation token. Record the decision in the ADR and `MULTI_SERVER_PLAN.md`.
- [ ] **Lite-jar default.** Confirm default in lite assembly ([ADR-024](../adr/ADR-024-rtp-lite-assembly-variant.md)) — likely `0` to keep the trim small. Document.
- [ ] **Operator docs.** Add a short section to `docs/admin/` describing when to enable / size this cache and the trade-off with `keptLocations` warmth.
- [x] **Fabric port.** — `FabricEventBridge.initLoginReserveCache(server)` allocates the buffer on the overworld region at `SERVER_STARTED` (default-world gated; sized to `loginCacheCap` or `MinecraftServer.getMaxPlayers()` fallback) and dispatches the startup burst. The `Disconnect` proxy now refills via `LoginCacheTask.promoteUpTo(1)` per region with a non-null login buffer. New `FabricOnEventTeleports.onJoin(server, player)` mirrors the Bukkit `OnEventTeleports.onPlayerJoin` flow — permission gating routes through `FabricRTPPlayer.hasPermission` (already wired to `fabric-permissions-api` with op-level fallback), and the first-join branch detects fresh players via a `<worldRoot>/playerdata/<uuid>.dat` probe (`hasPlayedBefore`). Covered by `ReqFabricAdr023HasPlayedBeforeTest` (6/6 green). Closes MULTI_PLATFORM_PLAN.md E3-5 for the login-reserve path.

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

## 5. Debug `/rtp config` Command

The runtime config-edit command (`/rtp config <file> <key>:<value> …`, plus `add:` / `remove:` for list fields) is documented in [`docs/admin/COMMANDS.md`](../admin/COMMANDS.md) as **in progress** — key coverage, validation, and feedback are incomplete. This stream finishes the job.

- [ ] **Reproduce and catalogue current failures.** Run each documented form (`scalar set`, `list add`, `list remove`, multi-pair, nested key) against `performance.yml`, `config.yml`, `economy.yml`, and at least one region file. Record symptom + expected vs. actual in a scratch note; promote durable findings to [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md).
- [ ] **Key-path resolution.** Verify dotted / nested keys resolve through `ConfigParser` correctly, including enum-valued and locale-sensitive keys (see [ADR-020](../adr/ADR-020-language-bootstrap-and-locale-aware-configparser.md)). Add unit tests under `rtp-core/.../commands/config/`.
- [ ] **Type coercion + validation feedback.** Bad values (wrong type, out-of-range, unknown enum) must produce a configurable, actionable message — not a silent no-op or a stack trace. Cross-check S-007 (configurable "invalid command" messages) and REQ-RTP-F-013.
- [ ] **List `add:` / `remove:` semantics.** Confirm dedupe behaviour, ordering, and case sensitivity. Decide and document whether `remove:` of an absent value is an error or a no-op; cover with tests.
- [ ] **Reload safety.** After a successful edit, the in-memory config must be refreshed without leaking the old instance and without dropping live region state. Verify against `ConfigsTest` patterns; add a regression test if missing.
- [ ] **Permission + tab-completion.** Confirm the `rtp.config` permission gate (existing tests: `SubConfigCmdTest`, `ViewSubConfigCmdTest`) and that Brigadier tab-completion suggests valid file names and known keys for the chosen file.
- [ ] **Folia / threading audit.** Config writes must not happen on a region thread; route through the global / async scheduler. Add a Folia-context test or a `FoliaOwnershipTestJob` extension.
- [ ] **Docs sync.** Once stable, drop the "⚠️ In progress" banner in `docs/admin/COMMANDS.md` and add a short troubleshooting section.

---

## 6. RTP Glide — Internalise as Effect (Bukkit landed; Fabric pending)

Originally shipped as the standalone `addons/RTP_Glide` addon. Per [effects-api-ADR-001](../../effects-api/docs/adr/effects-api-ADR-001-glide-effect.md) (Accepted & Implemented 2026-05-05) the decomposition collapsed to a **single `GlideEffect` in `effects-api`** rather than the originally-planned "vertical adjustor + effect" pair — the drop-height knob (`STARTHEIGHT`) is exposed inside the effect's positional config (effects-api-ADR-002 type-driven reading order), so no `rtp-core` `VerticalAdjustor` was needed. The Bukkit-family implementation is live; Fabric parity remains outstanding. The standalone `addons/RTP_Glide` subproject has been removed from the build (settings.gradle, IDE module files, and addon-directory references all dropped); the `GLIDE` effect is the only supported path.

- [x] **Decompose the addon.** — Mapped end-to-end in [effects-api-ADR-001](../../effects-api/docs/adr/effects-api-ADR-001-glide-effect.md) §"Decision"; the entire addon folds into one `Effect` (no separate adjustor), with `PlayerGlideEvent` / `PlayerLandEvent` migrated into `effects-api/.../bukkit/events/`.
- [x] ~~**`GlideVerticalAdjustor` in `rtp-core`.**~~ Superseded by effects-api-ADR-001: drop-height is the `STARTHEIGHT` positional key on `GlideEffect`, applied at effect-start rather than as a pre-teleport Y adjustor. No `rtp-core` change was required (Architecture Boundaries §3 — `effects-api` is the correct home).
- [x] **`GlideEffect` in `effects-api`.** — `effects-api/.../bukkit/LocalEffects/GlideEffect.java` + `enums/GlideTypeNames.java` + `SpigotListeners/GlideSafetyListener.java`; registered in `EffectFactory` as `"GLIDE"` (server-version ≥ 9) and wired through `EffectsAPI.init` / `EffectsAPI.disable` with shutdown-time safe placement (effects-api-ADR-001 §"Shutdown handling").
- [x] **Config wiring.** — Per-effect keys `landingTimeout`, `allowFireworks`, `placeOnShutdown`, `shutdownPlatformMaterial`, `startHeight`, world filter; positional parsing via effects-api-ADR-002. Lite-jar behaviour follows the standard effects-api wiring (no separate lite override is required because the effect is off-by-default unless configured).
- [ ] **Platform parity.** Spigot / Paper / Folia covered via the Bukkit-family `GlideSafetyListener` (Folia: Entity Scheduler for the watchdog per effects-api-ADR-001 §"Behavioural contract"). **Fabric is explicitly deferred** to Phase 2 of [effects-api-ADR-003](../../effects-api/docs/adr/effects-api-ADR-003-platform-split-bukkit-fabric.md) — `FabricGlideEffect` needs elytra-equip + glide-state plumbing without a clean Fabric primitive. Cross-tracked with the Fabric effects-api parity item in §1.
- [ ] **Safety interaction.** Confirm the start-height jump and the shutdown emergency-platform path (`shutdownPlatformMaterial`, AIR-only replacement) still terminate on S-001-safe blocks; add a regression test covering void-world misconfiguration and a chunk-unload-mid-glide race (the listener already logs the unload case, but there is no `ReqRtp*` test asserting attribution).
- [x] **Deprecate the addon.** — `addons/RTP_Glide` subproject deleted; `settings.gradle` `include 'addons:RTP_Glide'` line removed; stale `.idea/modules/addons/RTP_Glide/` IDE descriptors and `.idea/gradle.xml` / `.idea/modules.xml` entries cleaned. `addons/REQUIREMENTS.md` and `helpers/PeriodicWorldSaver/README.md` references updated. The `GLIDE` effect in `effects-api` is now the only supported path. `EXTERNAL_HOOKS.md` unaffected (the addon registered no hook — events were addon-local). Historical references retained in CHANGELOG / ADRs per change-history immutability.
- [ ] **Tests + traceability.** Add `ReqRtp*Glide*` (or `EffectsApiGlide*`) coverage for: timeout watchdog, firework-suppression listener, shutdown platform synthesis, and the `PlayerGlideEvent` / `PlayerLandEvent` lifecycle. Update [`TRACEABILITY.md`](TRACEABILITY.md). No `effects-api` tests reference these classes yet (`search_project ReqRtp` in `effects-api/` → no hits).
- [ ] **Docs.** Add a `docs/admin/` section describing the `GLIDE` effect and its keys — `docs/admin/` currently has zero hits for "Glide". `CHANGELOG.md` already records the landing under `[3.0.0-beta.2]`.

---

## Closing Out This File

When **all six sections** are fully ticked, fold the residue into [`ROADMAP.md`](ROADMAP.md) (or `CHANGELOG.md` for shipped items) and delete this file. Until then, prefer ticking boxes here over rewriting the structure.
