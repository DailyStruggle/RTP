# ADR-058 — Region-Specific Schematic (`.schem`) Paste at the Arrival Location

**Status:** Accepted (amended 2026-05-30, see Amendments 1-2)
**Date:** 2026-05-30

## Amendment 2 (2026-05-30): file presence is the knob (no per-region config key); core wiring landed

The per-region `schematic` config key proposed in section 3 is withdrawn. **The presence of a file is the entire knob.** Core resolves `<pluginDir>/schematics/<region>.schem` (with a `.schematic` fallback) by file name == region name: drop a file in and that region's teleports paste it; remove the file and behavior reverts to the default emergency platform. This removes a config key from locale-parity upkeep and makes the feature self-documenting (the directory listing *is* the configuration).

Landed in this amendment:

- `io.github.dailystruggle.rtp.common.tasks.teleport.RegionSchematicService#resolveSource(regionName)` performs the file-presence resolution in `rtp-core` (path policy stays platform-neutral; the paster only consumes the resolved `SchematicSource`).
- Core reaches the active paster through a new **instance** accessor `RTPWorld#schematicPaster()` (default `NoOpSchematicPaster`, S-006), overridden on `BukkitRTPWorld` / `FoliaRTPWorld` / `FabricRTPWorld` to return their existing swappable static holder (section 2). This lets `rtp-core` invoke the paster polymorphically without a platform import.
- `TeleportPipelineTask` implements the section 4 split: `runLoad` (off the region thread) resolves + decodes via `paster.load(...)`; `runTeleport` (on the region thread) pastes at `PasteAnchor.BOTTOM_CENTER` (player stands on top-center) **in place of** the emergency `RTPWorld#platform(...)`, falling back to the platform whenever nothing is pasted. Every non-`PASTED` outcome is audited and never aborts the teleport (S-004).
- Verified end-to-end against the committed `skyblock_island.schem` by `RegionSchematicServiceTest` (`rtp-core`).
- **Footprint claim check (S-003) landed.** Before `runTeleport` invokes `paster.paste(...)`, `TeleportPipelineTask#schematicFootprintClear` walks the schematic's horizontal footprint (the same `BOTTOM_CENTER`/`CENTER`/`ORIGIN` anchor math as `SchematicPlacementPlanner`) and runs every cell through `GlobalRegionVerifiers` (the sanctioned claim/protection registry the bundled claim integrations register into per [ADR-019](ADR-019-claim-plugin-integrations-folded-into-plugin.md) - never an inline claim-plugin call). If any cell intersects a claim the paste is suppressed (audited, S-004) and the default emergency platform path runs, so the paste never overwrites protected land. A failure of the check itself fails safe (treated as protected). Pinned by `ReqRtpS003SchematicFootprintClaimTest` (`rtp-core`).
- **Bundled-schematic prefab plumbing.** The `Skyblock` prefab ships an island baked into the jar at `/schematics/skyblock.schem` (`rtp-plugin` resources). On `/rtp admin prefab` confirm, `PrefabSchematicInstaller` extracts it once, copying the bundled resource named by the overlay's `schematic` value to `<pluginDir>/schematics/<regionId>.schem` keyed by the **region the overlay targets** (so the Skyblock prefab, which overlays `default`, writes `schematics/default.schem`). This matches `RegionSchematicService.resolveSource` (which keys off region name, not the `schematic` value), so the paste actually fires after applying the prefab. Existing files are never overwritten; a missing bundled resource is audited (S-004), never fatal. The round-trip (install Skyblock prefab -> `resolveSource("default")` resolves the island) is pinned by `RegionSchematicServiceTest`.

Still open from sections 4-6: the footprint claim check (S-003) ahead of the paste; the Folia and Fabric native pasters (only `BukkitSchematicPaster` ships, so Folia/Fabric currently fall back to the platform); block-entity NBT reconstruction; and the `docs/admin/` page + traceability rows. Where section 3 below says "per-region config knob", read it as superseded by this amendment.

## Amendment 1 (2026-05-30): single cross-platform `.schem` format, decoded in-house

The original decision (below) used WorldEdit/FAWE to decode and paste `.schem` on Bukkit-family and **vanilla structure `.nbt`** on Fabric (section 3, section 5, section 6). That divergence is withdrawn. The format is now **`.schem` (Sponge schematic v2/v3) on every platform, decoded by an in-repo, dependency-free Sponge reader** (`io.github.dailystruggle.rtp.api.schematic.SpongeSchematicDecoder`), with each adapter pasting via its **native block-state-from-string API**. Rationale:

- **One format everywhere.** Operators reuse the same `plugins/RTP/schematics/<name>.schem` / `config/rtp/schematics/<name>.schem` file on Bukkit, Paper, Folia, and Fabric. No `.schem`->`.nbt` conversion, no divergent docs.
- **No WorldEdit hard-dependency on Fabric.** WorldEdit's Fabric mod has no build for the deobf MC 26.x runtime family (Mojmap / Java 25) that `rtp-fabric` targets via the obf/unobf carrier split ([rtp-fabric-ADR-009](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md)); a WorldEdit-only Fabric path would silently `SKIPPED_UNSUPPORTED` on a supported runtime. Decoding in-house removes that gap and the operator burden of installing a second mod.
- **The decode is platform-neutral.** A Sponge `.schem` is GZIP-NBT with a fixed schema (`Schematic.Blocks{Palette, Data (varint-packed), BlockEntities}`, plus `Width/Height/Length/Offset`). Reading it needs no world and no platform types, so `SpongeSchematicDecoder` lives in `rtp-api` next to the SPI and is reusable by every adapter (all depend on `rtp-api`). Its correctness is pinned by `SkyblockIslandFixtureTest` against the committed `skyblock_island.schem` fixture.
- **Native paste is the only platform-specific step.** The palette entries are full block-state strings (`minecraft:oak_log[axis=y]`). Each adapter parses them with the platform's own parser (`Bukkit.createBlockData(String)` on Bukkit-family; `BlockArgumentParser` through the carrier on Fabric) and writes blocks on the region thread. WorldEdit/FAWE remains an **optional accelerator** on Bukkit-family for very large schematics, never a requirement.
- **Shared block-state grammar (extracted).** The `namespace:id[k=v,...]` palette grammar is the same one `SafetyTokenParser` (ADR-017) already tokenizes for `safety.yml`. The structural split (head + bracketed `key=value` body) is extracted into a shared `io.github.dailystruggle.rtp.api.block.BlockStateString` so both the safety parser and the schematic decoder share one tested tokenizer rather than maintaining two.

Where the prose below says "Fabric: vanilla structure NBT" or "WorldEdit clipboard API", read it as superseded by this amendment. The SPI shape (section 1, section 2), the config knob (section 3 minus the per-platform file extension), the load-async/paste-on-region-thread split (section 4), the claim/S-004 contracts, and the test plan (section 6) are unchanged.

## Context

The roadmap (Tier 2, [`docs/dev/ROADMAP.md`](../dev/ROADMAP.md)) carries an entry for *Region-specific schematic (`.schem`) support*: per-region arrival structures (a small platform, a lobby pad, an arrival shrine) pasted at the chosen `RTPLocation` once a teleport is confirmed. Operators want a teleporting player to land on a known, safe, decorated footprint rather than on raw generated terrain, and they want that footprint to differ per region (a hub region's pad vs. a wilderness region's stone disc).

Rule D-005 requires a proposal before implementation for any change that crosses module boundaries; this touches `rtp-api` (new SPI), `rtp-core` (config knob + paste invocation on the confirmed-location path), every backend adapter (`rtp-bukkit`/`rtp-paper`/`rtp-folia` via WorldEdit/FAWE soft-depend, `rtp-fabric` via vanilla structure NBT), and the resource/config tree. Hence this ADR.

Several hard constraints shape the design:

- **S-005 (no main-thread / region-thread chunk I/O).** Reading a `.schem`/NBT file off disk and decoding it is blocking I/O and must never run on a tick thread. Writing blocks into the world, by contrast, *must* run on the thread that owns the target region (Folia region thread; Bukkit/Paper main thread).
- **S-003 (no teleport into / modification of claim-protected land).** A schematic paste mutates blocks. Pasting a structure that overwrites claimed land is a worse S-003 violation than merely teleporting there. Paste must be claim-aware and suppressed (skip, do not partially paste) when the destination footprint intersects a claim, per [ADR-019](ADR-019-claim-plugin-integrations-folded-into-plugin.md).
- **S-004 (no silently discarded failures).** A failed or skipped paste (missing file, decode error, claim intersection, unsupported platform) must be audited via `RTP.log`, never swallowed. A paste failure must **not** abort the teleport — the player still arrives; the structure is simply absent and the reason is logged.
- **Swappable getter hook.** The issue asks that the platform paster be replaceable the same way the biome getter is. Today each adapter exposes a static, replaceable strategy field — e.g. `BukkitRTPWorld.setBiomeGetter(Function<Location,String>)` / `FoliaRTPWorld.setBiomeGetter(...)` — so an addon can override how a value is resolved without forking the adapter. The schematic paster shall follow this exact idiom.
- **Primarily additive.** No existing teleport path may change behavior when the feature is unconfigured. A region with no schematic knob behaves exactly as today.

## Decision

Introduce a `SchematicPaster` SPI in `rtp-api`, a per-region config knob naming a file, a `rtp-core` invocation point on the confirmed-arrival path with a strict **load-async then paste-on-region-thread** split, per-platform implementations registered behind a swappable static getter on each adapter (mirroring `setBiomeGetter`), and claim-aware suppression. The default behavior with no configuration is a no-op.

### 1. `SchematicPaster` SPI (`rtp-api`)

A new platform-neutral interface in `io.github.dailystruggle.rtp.api.substitutions` (or a new `...api.schematic` package), depending only on existing `rtp-api` types (`RTPLocation`, `RTPWorld`). No `org.bukkit.*`, no WorldEdit, no Minecraft types leak into `rtp-api`.

```java
public interface SchematicPaster {
    /**
     * Load + decode a schematic source off-thread. MUST NOT touch the world or
     * load chunks. Returns a platform-opaque handle wrapped in a future so the
     * blocking file read happens on an I/O thread (S-005).
     */
    CompletableFuture<LoadedSchematic> load(SchematicSource source);

    /**
     * Paste a previously-loaded schematic at the arrival location. MUST be
     * invoked on the thread that owns the target region (caller's contract);
     * the implementation performs only the block writes, no file I/O.
     * Returns the outcome so the caller can audit per S-004.
     */
    PasteResult paste(LoadedSchematic schematic, RTPLocation at, PasteOptions options);

    /** Whether this paster can service the given source on the running platform. */
    boolean supports(SchematicSource source);
}
```

Supporting types (all in `rtp-api`):

- `SchematicSource` — value object: file path (resolved by core, see section 3), a format hint, and an anchor/offset policy.
- `LoadedSchematic` — opaque handle to the decoded payload plus its bounding-box dimensions (so core can compute the footprint for the claim check **before** pasting). Carries no live world references.
- `PasteOptions` — anchor (center-on / bottom-on the arrival block), air-handling (paste air vs. skip air), and a `claimAware` flag.
- `PasteResult` — `PASTED` / `SKIPPED_CLAIM` / `SKIPPED_UNSUPPORTED` / `MISSING_SOURCE` / `DECODE_ERROR` / `PASTE_ERROR`, plus an optional message for the S-004 audit line.

Per [ADR-051](ADR-051-two-tier-api-extension-model.md) / [ADR-026](ADR-026-external-hook-api-surface.md), this is a Tier-style extension SPI: the default core wiring supplies a binding; addons may replace it.

### 2. Swappable getter hook (mirrors `setBiomeGetter`)

Each backend adapter that participates exposes a static, replaceable holder for the active paster, exactly mirroring the biome-getter idiom:

```java
// e.g. on BukkitRTPWorld / FoliaRTPWorld (and the Fabric world peer)
private static @NotNull SchematicPaster schematicPaster = NoOpSchematicPaster.INSTANCE;

public static void setSchematicPaster(@NotNull SchematicPaster paster) {
    <Adapter>.schematicPaster = paster;
}

public static @NotNull SchematicPaster getSchematicPaster() {
    return schematicPaster;
}
```

- The adapter installs its native paster at bootstrap (WorldEdit/FAWE-backed on Bukkit/Paper/Folia, vanilla-NBT-backed on Fabric). If the soft-depend is absent the holder stays `NoOpSchematicPaster` (every call returns `SKIPPED_UNSUPPORTED`, audited once).
- An addon wanting a custom paster (a different schematic format, a procedural generator) calls `setSchematicPaster(...)` during its own load, identically to how an addon today calls `setBiomeGetter(...)`. This is the "hook to change the platform getter similarly to changing the biome getter" the issue asks for.
- The default holder being a no-op (not `null`) satisfies S-006: API entry points never NPE and never silently no-op without an audit.

### 3. Per-region config knob + file layout

- New per-region key (region config, e.g. `regions.yml` region block): `schematic: <name>` (default empty / unset). Empty means "no paste" — the additive default.
- File resolution is owned by **core** (not the paster), so the claim/footprint logic and the path policy are platform-neutral:
  - Bukkit/Paper/Folia: `plugins/RTP/schematics/<name>.schem` (and `<name>.schematic` legacy fallback).
  - Fabric: `config/rtp/schematics/<name>.nbt` (vanilla structure NBT).
  - Core builds the `SchematicSource` from the configured name + the platform's schematics directory (surfaced via `RTPServerAccessor`), so a region may also use `schem/<region>.schem` by convention when `<name>` is left to default to the region name.
- New key must be mirrored into every shipped locale comment set via the locale TSV pipeline if it carries a user-facing comment (see Locale Parity rules); the value itself is a filename, not translated.

### 4. `rtp-core` invocation: load-async, paste-on-region-thread (S-005)

The paste hooks the **confirmed-arrival** path — after a candidate `RTPLocation` is selected and the teleport is about to commit, not during queue pre-generation (pre-generated locations are not yet claimed by a player and may never be used; pasting then would mutate the world speculatively).

Sequence:

1. On teleport-commit, if the region's `schematic` knob is set, core calls `paster.load(source)` — this runs on the async/I/O path (the file read + decode). `load` is cached per `(region, name, fileMtime)` so repeated teleports into the same region decode once.
2. When the future completes, core computes the paste footprint bounding box from `LoadedSchematic` dimensions anchored at the arrival location.
3. **Claim check (S-003):** core runs the existing claim-intersection check (the folded-in claim integration, ADR-019) over the footprint, not just the single arrival block. If any block intersects a claim, core skips the paste, emits an S-004 audit (`SKIPPED_CLAIM`), and proceeds with the teleport unmodified.
4. Core schedules `paster.paste(...)` on the region-owning thread via `RTP.scheduler` — `runTask(RTPLocation, ...)` on Folia (entity/region scheduler keyed to the destination), `runTask(...)` on Bukkit/Paper. The paster performs block writes only; no I/O.
5. The `PasteResult` is audited (success or any skip/error reason) via `RTP.log`. A non-`PASTED` result never fails the teleport.

This split keeps file I/O off tick threads (S-005) and block writes on the correct region thread, and never blocks the pipeline on a `.get()`.

### 5. Per-platform implementations

- **Bukkit / Paper / Folia:** WorldEdit/FAWE soft-depend. `load` reads + decodes the `.schem` via the WorldEdit clipboard API on the async path; `paste` applies the clipboard with an `EditSession` on the region thread. Catalog the soft-depend in [`EXTERNAL_HOOKS.md`](../dev/EXTERNAL_HOOKS.md) with a row per [ADR-026](ADR-026-external-hook-api-surface.md). Folia note: the `EditSession` runs inside the scheduled region task so it never touches a foreign region.
- **Fabric:** vanilla structure NBT (`StructureTemplate`) load on the I/O path, placement routed through the obf/unobf carrier per [rtp-fabric-ADR-009](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md) (the NM-typed placement call lives in the carrier, dispatched by `FabricVersionAdapter`). No WorldEdit dependency on Fabric.

### 6. Tests + docs

- A regression test asserting paste is scheduled on the region-owning thread (the "paste-on-region-thread" guard), traceable to a new `REQ-RTP-*` row in [`TRACEABILITY.md`](../dev/TRACEABILITY.md).
- An S-003 test: a footprint intersecting a claim yields `SKIPPED_CLAIM` and an unmodified world.
- An S-004 test: each non-`PASTED` outcome emits a `RTP.log` audit and does not abort the teleport.
- A `MockSchematicPaster` test fixture (deterministic, no real files) for core-side scheduling/claim tests.
- A `docs/admin/` page documenting the `schematic` knob, the `schematics/` directory per platform, the WorldEdit/FAWE requirement on Bukkit-family, and the claim-suppression behavior.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Paste during queue pre-generation (when the location is cached) | Speculative world mutation: most pre-generated locations are discarded or re-used across players; pasting then mutates terrain for teleports that never happen and re-pastes on every poll. Commit-time paste mutates exactly once, for the player who arrives. |
| Bake schematic handling directly into each adapter with no `rtp-api` SPI | Violates the architecture boundary (WorldEdit/Minecraft types would have nothing platform-neutral to bind to) and gives addons no override point — the issue explicitly wants a swappable getter. |
| A single global schematic instead of per-region | Misses the stated requirement (per-region arrival structures). Per-region is a config knob, not a code fork. |
| Synchronous load+paste in one call on the teleport thread | Direct S-005 violation (file decode is blocking I/O on a tick thread). The load/paste split is mandatory. |
| `null` default paster, null-checked at call sites | Violates S-006 and invites silent no-ops; a `NoOpSchematicPaster` that audits once is safer and matches the require-by-contract style. |
| Abort the teleport on paste failure | Worse UX and a reliability regression: an operator's malformed `.schem` would strand players. Paste is best-effort decoration; the teleport is the contract. |

## Consequences

- **Positive:** Per-region arrival structures become a pure config knob; no caveat on existing behavior (unset = no-op, fully additive). Addons can swap the paster exactly like the biome getter. File I/O stays off tick threads (S-005); block writes stay on the owning region thread; claim land is protected (S-003); every skip/failure is audited (S-004). Fabric is covered without a WorldEdit dependency.
- **Negative / Trade-offs:** Adds a soft-depend surface (WorldEdit/FAWE) to catalog and version-audit on Bukkit-family. Introduces a new `rtp-api` SPI and a new config key to keep in locale parity. The Fabric NBT path adds another NM-typed surface to the obf/unobf carrier. Commit-time pasting adds a small, bounded amount of region-thread work per teleport into a schematic-configured region (one `EditSession`/placement), which operators with very large schematics should be aware of.

## References

- Roadmap entry: [`docs/dev/ROADMAP.md`](../dev/ROADMAP.md) Tier 2, *Region-specific schematic (`.schem`) support*.
- Biome-getter hook prior art: `BukkitRTPWorld#setBiomeGetter` / `FoliaRTPWorld#setBiomeGetter`.
- Scheduling contract: [`.junie/AGENTS.md`](../../.junie/AGENTS.md) *Scheduler Usage*; `RTPScheduler` (`runTask(RTPLocation, ...)`).
- S-003 / S-004 / S-005 / S-006: [`REQUIREMENTS.md section 3`](../dev/REQUIREMENTS.md); claim integration: [ADR-019](ADR-019-claim-plugin-integrations-folded-into-plugin.md).
- External hook catalog: [ADR-026](ADR-026-external-hook-api-surface.md), [`EXTERNAL_HOOKS.md`](../dev/EXTERNAL_HOOKS.md).
- Extension-tier model: [ADR-051](ADR-051-two-tier-api-extension-model.md).
- Fabric carrier dispatch: [rtp-fabric-ADR-009](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md).
