# Anvil Biome Resolution Plan (Phase 2)

**Status:** Superseded 2026-04-19 by
`docs/dev/BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md`. Steps 1–3 (Anvil
biome-palette decode, `AnvilRegionScanner`, in-place getter amendment)
landed 2026-04-19 and are consumed as building blocks by the successor
plan. Steps 4–6 (Iris addon shrink, ADR-016 amendment, `FRONT_PAGE`
bullet) did **not** land under this plan and have migrated to the
successor's §8 landing order. The sections below are retained for
historical reference; do not extend them.

**Supersedes:** none. **Extends:** ADR-016 "Anvil subsystem" (block-palette
pre-filter). **Minimum supported format:** Minecraft 1.20.1 region files
(REQ-RTP-SYS-002).

Phase 1 (already landed) teaches the Anvil subsystem to read the **block**
palette off disk and use it as an advisory safety input. Phase 2 teaches
the same subsystem to read the **biome** palette off disk and use it as an
accuracy-preferred biome-naming input for populated chunks.

The motivating insight is recorded in ADR-016 Context and repeated here
for load-bearing importance: **for a populated chunk the `.mca` palette
is strictly more accurate than the live Bukkit API.** Bukkit's
`World#getBiome(Location)` returns `org.bukkit.block.Biome`, a closed enum
that silently collapses modded and generator-native biomes
(`iris:volcanic_ash_plains`, `terra:alpine_shelf`, namespaced datapack
biomes) to their nearest vanilla neighbour. The on-disk biome palette
preserves the namespaced identifier verbatim. For any operator running
Iris / Terra / a datapack generator, the disk read is not a degraded
approximation — it is the only non-lossy source available to a
platform-neutral module.

---

## 1. Motivation

Two user-visible problems converge on the same fix:

1. **Iris-biome naming without `IrisToolbelt`.** Today the
   `RTP_Iris_integration` addon is the only way to surface
   `iris:volcanic_ash_plains` to `/rtp biome:...`. Operators who run Iris
   but who have not installed the addon see the biome collapsed to
   `PLAINS` / `BADLANDS` / etc. via `world.getBiome(loc).name()`.
2. **Modded-biome naming on non-Iris forks.** Server forks that ship
   custom biome registries (Mohist/Arclight with Fabric biome mods, Pufferfish
   with datapack biomes) have no stable Bukkit-facing API to list or look
   up those biomes. `world.getBiome(loc)` returns `CUSTOM` (or worse,
   whichever vanilla biome the data-fixer mapped it to).

Both problems are solved if `rtp-anvil` can read the biome palette of a
populated chunk and return the namespaced identifier directly. The Iris
addon shrinks to enumeration-only (see §7); modded forks gain biome
naming for free.

---

## 2. Scope and non-scope

**In scope:**

- Biome-palette decode for 1.20.1+ region files (NBT path
  `sections[].biomes`, packed palette identical in shape to the
  block-palette decoder we already ship).
- A new `AnvilChunkView#getBiomeAt(x, y, z) -> String` returning the
  raw namespaced palette identifier (e.g., `minecraft:plains`,
  `iris:volcanic_ash_plains`).
- A new `AnvilChunkView#getBiomesPresent() -> Set<String>` returning the
  union of biome identifiers across the chunk's sections — needed to
  implement pregen-scan-based enumeration (§4).
- An **in-place amendment** of the biome getter that the Bukkit-family
  platform adapters already register via `RTPServerAccessor#setBiomeGetter`:
  the existing getter body gains an `.mca`-first pre-step and then falls
  through to the **pre-existing resolution chain** (today:
  `world.getBiome(loc).name()`; with the Iris addon installed: the
  addon's engine-backed override, because the addon registers *last*
  and its getter is what `setBiomeGetter` stores). No new
  `setBiomeGetter` / `setBiomesGetter` registration is introduced by
  this phase, and the addon load order is unchanged.
- `PaletteIdentifierNormalizer` reuse — the same reconciler that
  normalises block identifiers (`minecraft:lava` → `LAVA`) applies to
  biome identifiers unchanged.

**Out of scope:**

- Pre-1.18 biome format (`Level.Biomes` flat int array of vanilla
  biome IDs). We do not target MC versions below 1.20.1 per
  REQ-RTP-SYS-002, so the 1.18+ packed palette is the only format.
- Biome **writing**. The Anvil subsystem is read-only (ADR-016 §8).
- Biome **predicate evaluation** (e.g., "is `iris:volcanic_ash_plains`
  in the operator's `biomes:` allow-list?"). That logic lives in
  `LocationGenerator` / the existing biome filter; Phase 2 only supplies
  the identifier string the filter receives.
- Removing the Iris addon. See §7 — the addon shrinks but retains a
  residual responsibility.

---

## 3. Data source and format (1.20.1+ only)

For each chunk section at level `Level.sections[i]` the biome container
lives at `Level.sections[i].biomes` and has the same shape as the
block-palette container we already decode:

- `palette: List<String>` — the namespaced biome identifiers referenced
  by this section. Single-entry palettes are common (whole section is
  one biome); multi-entry palettes are stored as packed long indices.
- `data: LongArray` (optional) — packed 4×4×4 biome-cell indices into
  `palette`. Absent when the palette has exactly one entry (whole
  section is that biome). Bit-width is `ceil(log2(palette.size))` with
  a minimum of 1; cells do not cross long boundaries (same padding rule
  as blocks in 1.18+).

The spatial resolution is **4×4×4 blocks per biome cell** (not 1×1×1
like blocks). A lookup for world coordinate `(x, y, z)` inside a loaded
section at section-Y `sy` resolves to cell index:

    cellX = (x & 15) >> 2        // 0..3
    cellY = (y - sy*16) >> 2     // 0..3
    cellZ = (z & 15) >> 2        // 0..3
    idx   = (cellY << 4) | (cellZ << 2) | cellX  // 0..63

If `data` is absent, the biome is `palette[0]`.

This is the same bit-packed format `PackedPaletteDecoder` already
handles for blocks. Phase 2's decoder is ~40 LOC of delta on top of the
existing code path, not a second parser.

---

## 4. Public API shape in `rtp-anvil`

Two additions to `AnvilChunkView`, one addition to `AnvilPrefilter`:

```java
public final class AnvilChunkView {
    // existing: isAir, isSafe, getSkyLight, getSurfaceHeight, ...

    /**
     * Returns the namespaced biome identifier at world coordinate (x, y, z),
     * or {@code null} if y falls outside the decoded Y window or the
     * section's biome container is missing/unparseable.
     *
     * <p>The returned identifier is raw (e.g. {@code "minecraft:plains"},
     * {@code "iris:volcanic_ash_plains"}). Callers that need the
     * RTP-configuration-comparable form must run it through
     * {@link PaletteIdentifierNormalizer#reconcile(String)}.</p>
     */
    public String getBiomeAt(int x, int y, int z);

    /**
     * Returns the union of every distinct namespaced biome identifier that
     * appears in any decoded section of this chunk. Intended for pregen
     * biome-enumeration scans (see §6). Does not include biomes from
     * sections that failed to decode.
     */
    public Set<String> getBiomesPresent();
}
```

`AnvilPrefilter.probeDetailed(...)` is unchanged — biome reads are
available through the view that `ACCEPT` / `REJECT` already publishes.
The advisory-vs-authoritative distinction (§5) governs how the platform
adapter *uses* the biome read, not whether the probe returns.

---

## 5. Trust model

Biome reads follow the **same advisory-but-accuracy-preferred** trust
model that ADR-016 establishes for block reads, with one clarification
specific to biome naming:

- **For a populated chunk (live or disk), the `.mca` palette identifier
  is the authoritative name.** No downstream Bukkit-API re-check is
  performed — there is nothing to re-check against, because the live
  Bukkit API is strictly less precise than the disk read. This is the
  whole point of Phase 2.
- **For an unpopulated chunk (Anvil verdict `UNKNOWN` or section's
  biome container absent), the platform adapter falls through to the
  existing `world.getBiome(loc).name()` path.** This is a lossy
  fallback for Iris/Terra users, but it is the only source available
  before the generator runs. It matches the pre-Phase-2 baseline
  exactly — no regression.
- **Biome reads never gate safety.** Unlike block reads, which feed
  `chunk.isSafe(...)`, biome reads feed only the biome-allow-list
  filter in `LocationGenerator`. A wrong biome name cannot produce an
  unsafe teleport; it can only produce a "this candidate doesn't match
  operator config, reroll" outcome. The existing bounded-retry
  machinery absorbs this without change.

### 5.1 Iris painter-pass caveat (revisited)

Phase 1 noted that Iris's post-population "painter" passes can
overwrite surface *blocks* after the chunk is first saved. Painter
passes **do not rewrite biome assignments** in Iris's current engine —
biome decisions are made during terrain generation and persisted
immediately. So the disk biome read is stable even under Iris; this is
stricter than the Phase-1 block contract.

If a future Iris release changes this (e.g., biome repainting on
neighbour-chunk load), the trust-model escape hatch is: demote
`getBiomeAt` from "authoritative" to "advisory + live corroboration"
for worlds with `world.getGenerator() != null`. That is a 10-line
change gated on a future defect report; no plumbing is needed now.

---

## 6. Platform-adapter wiring

**Design correction (2026-04-19):** earlier drafts of this plan
proposed a new composite `setBiomeGetter` registration in the
platform adapter. That was wrong. `setBiomeGetter` is a *single-slot*
registration point; introducing a second caller would either (a)
race against the Iris addon for last-writer-wins ordering, or (b)
force the adapter to re-implement the addon's override chain. Neither
is acceptable.

Instead, Phase 2 **amends the existing biome getter in place**. The
adapter's `BukkitRTPWorld.getBiome` / `FoliaRTPWorld.getBiome` default
(the one installed by `AbstractServerAccessor.setBiomeGetter` at
plugin-enable if no addon overrides it) gains an Anvil pre-step:

```
// Inside the existing default biome getter body (adapter-side).
Function<Location, String> existingGetter = /* today's impl: world.getBiome(loc).name() */;

Function<Location, String> amended = loc -> {
    // 1. Anvil-first: consult the already-decoded view cache, then
    //    an opportunistic off-tick disk probe. Zero I/O on the hot
    //    path when the view is cached (Phase-1 AnvilProbeSupport).
    String fromAnvil = tryAnvilBiomeAt(loc);   // null if no view / outside Y window / decode miss
    if (fromAnvil != null) return fromAnvil;

    // 2. Fallback: the pre-existing resolution logic, unchanged.
    //    On vanilla this is world.getBiome(loc).name(). When the
    //    Iris addon is installed, the addon's enable handler has
    //    already replaced this function via setBiomeGetter with its
    //    engine-backed override, so "existing" means "whatever the
    //    last registered setter installed" — Anvil-first is layered
    //    onto that same function.
    return existingGetter.apply(loc);
};
```

Key points:

- **No new `setBiomeGetter` call is introduced by this phase.** The
  Anvil-first step is woven into the getter's body that the adapter
  already installs. The public API surface of `RTPServerAccessor`
  does not grow.
- **Addon precedence is preserved by construction.** Because the
  Iris addon still calls `setBiomeGetter(engineOverride)` during its
  own enable handler, and because that call runs *after* the adapter's
  default install, the addon's override remains authoritative when
  installed. Operators who want "Iris engine beats disk" keep that
  behaviour for free.
- **When the addon is absent**, the amended default getter runs:
  Anvil-first, then `world.getBiome(loc).name()`. This is the pure
  Phase-2 win case — Iris/Terra/modded biomes on disk are surfaced
  verbatim without any addon installed.
- Folia inherits the same amendment on `FoliaRTPWorld.getBiome` via
  `AbstractFoliaServerAccessor`; `AnvilProbeSupport` is platform-neutral.
- `rtp-anvil` exposes only `getBiomeAt` on the view. The fallback
  decision — "what to do when Anvil returns `null`" — stays on the
  platform side because the fallback may be addon-supplied.

### 6.1 Enumeration (biomes-present)

Enumeration is inherently a pregen-dependent operation: the disk can
only report biomes that have actually been written to disk. Following
the same "amend in place" correction as §6:

- `rtp-anvil` ships `AnvilRegionScanner.scanBiomes(worldFolder,
  dimSubpath)` — already landed in Step 2 — which walks every `.mca`
  file in the dimension, decodes each chunk's biome palette lazily,
  and returns the union on `ForkJoinPool.commonPool()` with a
  `(regionFolder, max-mtime)` cache.
- The adapter's **existing** biomes-getter (the one installed via
  `setBiomesGetter` — default today: the vanilla-biome-enum union on
  the world) is amended to union the Anvil scanner's result with the
  pre-existing result. Anvil-union-first, pre-existing-second; both
  are merged so the caller sees a superset.
- As with §6, when the Iris addon is installed its `setBiomesGetter`
  call runs later and replaces the function wholesale with the
  engine-backed roster (`IrisToolbelt.access(world).getEngine()
  .getAllBiomes()`). The addon's roster is a strict superset of the
  Anvil scan, so the replacement is an upgrade, not a regression. No
  new `setBiomesGetter` registration is introduced by this phase.

Scan cost budget: ~5 ms per region file on commodity disk, dominated
by sequential read. A 10-region pregen zone scans in under a second
cold and is free warm (mtime unchanged). This is acceptable for a
one-shot startup scan; the cache key means tab-completion never
re-scans.

---

## 7. Impact on `RTP_Iris_integration` addon

**The addon does not shrink in this phase.** Under the corrected
design (§6), the adapter's default getter gains an Anvil-first
pre-step; it does **not** try to absorb the addon's responsibilities.
The addon continues to register both `setBiomeGetter` and
`setBiomesGetter` exactly as today, and its enable handler still
runs after the adapter's default install, so its engine-backed
overrides win whenever Iris is present.

What operators observe after Phase 2 lands:

- **Iris installed + addon installed:** unchanged. The addon's
  engine-backed getter is authoritative; the Anvil pre-step inside
  the adapter default is never consulted because the adapter default
  is no longer the registered getter.
- **Iris installed, addon *not* installed:** this is the new win.
  The adapter default is the registered getter, so its Anvil-first
  pre-step reads `iris:volcanic_ash_plains` directly from disk for
  every populated chunk. Unpopulated chunks still collapse to
  `world.getBiome(loc).name()` — no worse than today.
- **Vanilla / datapack biomes, no Iris:** Anvil-first surfaces
  namespaced datapack biome identifiers for populated chunks; the
  fallback to `world.getBiome(loc).name()` is unchanged for
  unpopulated chunks.

A follow-up pass may revisit whether the addon's `setBiomeGetter`
override can be simplified (since the adapter default now covers the
populated-chunk case on disk), but that is deferred out of this plan.
Any such simplification must be driven by its own proposal; this
phase is strictly additive to the adapter default.

---

## 8. Threading and REQ compliance

- **REQ-RTP-S-005 (no main-thread chunk I/O).** `getBiomeAt` reads from
  an already-decoded `AnvilChunkView` — zero I/O at the call site. The
  opportunistic "probe on demand" branch in §6 runs on
  `ForkJoinPool.commonPool()` via `AnvilProbeSupport`. The existing
  Phase-1 invariants carry over unchanged.
- **REQ-RTP-S-004 (no silently discarded failures).** Decode failures
  on a per-section basis return `null` from `getBiomeAt` and fall
  through to the vanilla biome getter. This is not a failure mode the
  teleport pipeline cares about (biome naming ≠ safety); no WARN-level
  log is required. A `Level.FINE` breadcrumb in `AnvilChunkView` is
  sufficient and matches existing subsystem conventions.
- **Folia region-thread affinity.** The composite getter in §6 never
  touches `Chunk#getBiome` unless the view lookup returns `null`. The
  `world.getBiome(loc).name()` fallback *does* run on the caller's
  context and must be invoked from a region-thread-aware site — but
  that was already true pre-Phase-2 (the existing getter is the same
  call). `FoliaThreadAffinityArchTest` covers this contract.

---

## 9. Traceability

New or extended tests required before Phase 2 can land:

| Test | Module | Requirement / contract covered |
|------|--------|-------------------------------|
| `AnvilBiomeDecoderTest` | `rtp-anvil` | §3 packed-palette decode, §4 `getBiomeAt`, single-entry and multi-entry palette paths, out-of-window null return. |
| `AnvilBiomesPresentTest` | `rtp-anvil` | §4 `getBiomesPresent` union across sections. |
| `AnvilRegionScannerTest` | `rtp-anvil` | §6.1 full-region enumeration, mtime-based caching. |
| `PaletteNormalizerBiomeTest` | `rtp-spigot-common` | §4 namespaced biome reconciliation parity with block reconciliation. |
| Extended `FoliaThreadAffinityArchTest` | `rtp-folia-common` | §8 no biome read escapes to a region thread. |

Traceability matrix row additions in `docs/dev/TRACEABILITY.md` to be
authored at implementation time.

---

## 10. Landing order

1. **[Landed 2026-04-19]** Biome-palette decoder in `rtp-anvil` (behind `AnvilChunkView#getBiomeAt`),
   plus `AnvilBiomeDecoderTest`. No platform wiring yet.
2. **[Landed 2026-04-19]** `AnvilChunkView#getBiomesPresent` + `AnvilRegionScanner` + cache.
3. **[Landed 2026-04-19]** Amend the adapter-default biome getter in place on Spigot
   (`BukkitRTPWorld.getBiome` / `BukkitRTPWorld.getBiomes`) and Folia
   (`FoliaRTPWorld.getBiome` / `FoliaRTPWorld.getBiomes`) to run an
   Anvil-first pre-step before delegating to the pre-existing
   resolution logic. Paper inherits through the Spigot class
   hierarchy. **No new `setBiomeGetter` registration is introduced.**
   The instance `getBiome(x,y,z)` consults `AnvilProbeSupport.takeCached(key)`
   (zero-I/O hot path) and calls `AnvilChunkView#getBiomeAt`; the static
   `getBiomes(RTPWorld)` unions `AnvilRegionScanner.scanBiomes` with the
   pre-existing registered `getBiomes` function.
4. (Deferred, out of this plan) Any further simplification of the
   `RTP_Iris_integration` addon. The addon's current
   `setBiomeGetter` / `setBiomesGetter` registrations remain
   authoritative when Iris is installed; see §7.
5. ADR-016 amendment: §11 / §Consequences updated to reflect Phase 2
   semantics; Alternatives `A` / `B` revised if needed. No new ADR
   unless the trust model in §5 is later challenged.
6. `docs/FRONT_PAGE.bbcode` bullet: "Iris / Terra / modded-biome
   operators: namespaced biome names are now honoured in
   `biomes:` allow-lists without the Iris addon, for any pregenerated
   chunk."

Each step is an independently shippable slice. Steps 1–2 can land
before any platform work; step 3 depends on 1; step 4 depends on 3.

---

## 11. Risks and mitigations

| Risk | Mitigation |
|------|-----------|
| Mojang changes the biome NBT layout in a future MC version. | The 1.20.1 floor is declarative, not speculative. Future versions are handled the same way Phase 1 handles block-palette drift: extend `DataVersionSupport` branch-by-branch under test fixtures. |
| Iris introduces biome repainting on neighbour-load. | Trust-model escape hatch documented in §5.1: demote to "advisory + live corroboration" for `world.getGenerator() != null`. |
| Addon operators regress on upgrade (expected Iris override silently replaced by disk reads). | Addon shrink keeps the engine override path; §6 precedence order guarantees engine wins when installed. Documented in MIGRATION.md at ship time. |
| `AnvilRegionScanner` cost on worlds with huge pregen footprints. | Per-region-file laziness; mtime cache; bounded concurrency via `ForkJoinPool.commonPool()`. Worst-case is a one-time startup delay, not a hot path. |

---

## 12. References

- ADR-016 "Anvil subsystem" — Phase 1 block-palette foundation; Phase 2
  extends the same view type, applicability gate, and `AnvilProbeSupport`
  cache.
- `docs/dev/ANVIL_PREFILTER_PLAN.md` — Phase 1 design.
- `docs/dev/ANVIL_SHARED_MODULE_PLAN.md` — module layout Phase 2
  inherits without change.
- REQ-RTP-SYS-002 (Spigot support; 1.20.1 floor).
- REQ-RTP-S-004, REQ-RTP-S-005 — unchanged semantics carry over.
- `addons/RTP_Iris_integration/` — shrink target, see §7.
