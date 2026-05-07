# RTP TODO — Remaining Feature Work

**Scope:** A focused, scannable checklist of the remaining feature streams the maintainer wants to land. This file is a *task tracker*, not a design doc — for rationale, see the linked ADRs and plan documents. For full release planning (tiers, blockers, polish), see [`ROADMAP.md`](ROADMAP.md).

> Keep items independently verifiable. Tick a box only when the work is merged and traceable (commit / ADR / test). Use `- [x]` and append `— <ref>` when closing.

---

## 1. Fabric Support

Active frontier per [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md). Tracked in detail under [`MULTI_PLATFORM_PLAN.md`](MULTI_PLATFORM_PLAN.md); items below are the must-finish set to call Fabric "stable".

- [ ] **Resolve Loom dependency.** `rtp-fabric` build currently does not resolve cleanly across all `v1_20_R1` / `v1_21_R1` / `v26_1_R1` submodules. Document the toolchain (Loom version, Yarn / Mojmap choice) in an ADR before merging build changes.
- [ ] **Eliminate the S-005 violation in `FabricWorld.getChunkAt`.** Replace the synchronous load with the async chunk abstraction used on Paper/Folia. Cover with a `ReqRtpS005*` test in the Fabric module.
- [ ] **Implement `FabricServerAccessor.getLocationGenerator`.** Currently a null stub — addons calling the API on Fabric will hit S-006 territory. Wire to the real `LocationGenerator` and add a contract test mirroring the Paper one.
- [ ] **Brigadier bridge wiring.** Confirm `BrigadierCommandAdapter` + `BrigadierBridgeContext` ([commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md)) bind cleanly under Fabric's `CommandRegistrationCallback`. Add an integration test that registers `/rtp` and asserts tab-completion parity with Paper.
- [ ] **Effects-API parity.** Audit `effects-api` consumers (`FireworkEffect`, sound, particle) for Fabric-side equivalents; stub or implement so loading the jar on a vanilla Fabric server does not warn.
- [ ] **Platform smoke test.** Add a Fabric entry to whatever `rtp test full` analogue exists (or create one) so a CI / manual run produces the same pass/fail matrix as Paper and Folia.
- [ ] **Promote out of "unstable" in `MULTI_PLATFORM_PLAN.md` and `AGENTS.md` *Current Development Focus*** once the above are green.

Out of scope here (deliberately): Forge / NeoForge — gated until Fabric stabilises (Phase 4).

---

## 2. Third Queue Layer — L3 Backlog (Binned) Cache

Proposed in [ADR-028](../adr/ADR-028-l3-backlog-cache.md). Per the *Domain Analogies & Aliases* table in [`AGENTS.md`](../../.junie/AGENTS.md), this is the **"backlog cache" / "L3" / "binned cache"** — an unverified buffer upstream of `unkeptLocations`, verified one bin (32×32 chunks = one `.mca`) per `Region.execute()` pulse while the region file is cached.

- [ ] **Create `BacklogLocationBuffer`.** New class alongside `LockFreeLocationBuffer`. Order-preserving FIFO, per-entry `verified` flag, head-blocking promotion semantics. Unit-test FIFO ordering, head-blocking, and the verified-flag transition.
- [ ] **Wire `RegionQueueManager.backlogLocations`.** Add the field, plumb it into the existing kept→unkept fallback chain so `keptLocations` empty → `unkeptLocations` empty → promote verified head from `backlogLocations`.
- [ ] **Bin-grouped verification pulse.** In `Region.execute()`, verify candidates one `.mca` bin at a time using the anvil prefilter ([ADR-016](../adr/ADR-016-anvil-subsystem.md)) so the OS page cache stays hot for the whole bin. Document the chosen pulse budget in the ADR.
- [ ] **Config key `backlogCacheCap`.** Default `1000`; lite-jar default `0` (per [ADR-024](../adr/ADR-024-rtp-lite-assembly-variant.md)). Wire through the existing config loader and document under the queue tuning section.
- [ ] **Persistence policy.** Confirm and enforce: backlog is **not** persisted to the DB. Add a guard test to prevent regression.
- [ ] **MemoryTracker accounting.** Decide whether unverified backlog entries count against tracked allocations; document in the ADR and add a `MemoryTracker` test.
- [ ] **Traceability.** Add a REQ-* row (or extend an existing queue REQ) and a `ReqRtp*L3*` test class; update [`TRACEABILITY.md`](TRACEABILITY.md) and [`GLOSSARY.md`](GLOSSARY.md) to canonicalise the symbol once it exists.
- [ ] **Aliases table follow-up.** Once `backlogLocations` is real, update the *Domain Analogies & Aliases* row in `AGENTS.md` to drop the "Proposed" qualifier.

---

## 3. Join Cache — Login Reserve

Per [ADR-023](../adr/ADR-023-login-reserve-cache.md). Symbol: `RegionQueueManager.loginLocations` ("login cache" / "login reserve" / "join cache" in the aliases table).

- [ ] **Audit current implementation surface.** Confirm `loginLocations` is allocated only for the default world and only when `rtp.onevent.firstjoin` or `rtp.onevent.join` is enabled. Add a test if missing.
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

## 6. RTP Glide — Internalise as Vertical Adjustor + Effect

Currently shipped as the standalone `addons/RTP_Glide` addon. The behaviour ("drop the player from height, glide them down safely") decomposes cleanly into two existing extension points already present in core: a **vertical adjustor** (to lift the destination Y to drop-height) and an **effect** (to apply slow-fall / elytra / damage-immunity during descent). Folding it in removes the addon's reflection surface and gives all platforms first-class glide.

- [ ] **Decompose the addon.** Read `addons/RTP_Glide` end-to-end and map each behaviour to either (a) a `VerticalAdjustor` implementation or (b) an `effects-api` effect. Record the mapping in a short design note before code (Rule D-005 if it crosses modules).
- [ ] **`GlideVerticalAdjustor` in `rtp-core`.** New adjustor that raises the resolved Y to a configured drop-height (absolute or `+offset` above terrain), respecting world max-Y and the existing safety pipeline. Unit-test against the spiral output and a stubbed world height.
- [ ] **`GlideEffect` in `effects-api`.** Applies slow-falling / fall-damage immunity (and optionally elytra equip + firework boost parity with the addon) for the descent window. Must declare its thread-safety contract and clean up on early disconnect / death.
- [ ] **Config wiring.** Expose under the existing region/effects config (e.g. `verticalAdjustor: GLIDE` + `effects: [GLIDE]`) with keys for drop-height, descent effect duration, and fall-damage policy. Document defaults; lite-jar default off ([ADR-024](../adr/ADR-024-rtp-lite-assembly-variant.md)).
- [ ] **Platform parity.** Verify behaviour on Spigot, Paper, Folia (Entity Scheduler for the effect application — see *Folia Threading*), and Fabric (effects-api parity item in §1). No region-thread blocking; no synchronous chunk loads (S-005).
- [ ] **Safety interaction.** Confirm the adjustor's raised Y still goes through standard safety checks before teleport (S-001) — gliding does **not** bypass unsafe-block rejection, only relocates the landing search.
- [ ] **Deprecate the addon.** Once parity is verified, mark `addons/RTP_Glide` deprecated with a one-release migration note pointing to the built-in config keys; remove after the deprecation window. Update [`EXTERNAL_HOOKS.md`](EXTERNAL_HOOKS.md) if the addon registered any hook.
- [ ] **Tests + traceability.** Add `ReqRtp*Glide*` coverage (adjustor math, effect lifecycle, S-001 interaction); update [`TRACEABILITY.md`](TRACEABILITY.md). New ADR if the decomposition introduces any new public API on `rtp-api` / `effects-api`.
- [ ] **Docs.** Add a `docs/admin/` section describing the feature and config; update `CHANGELOG.md` on landing.

---

## Closing Out This File

When **all six sections** are fully ticked, fold the residue into [`ROADMAP.md`](ROADMAP.md) (or `CHANGELOG.md` for shipped items) and delete this file. Until then, prefer ticking boxes here over rewriting the structure.
