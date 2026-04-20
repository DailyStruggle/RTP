# Anvil Biome Resolution Plan (Phase 2)

**Status:** Draft — design-only. No code in this phase.
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
- Platform-side wiring through `RTPServerAccessor#setBiomeGetter` with a
  `.mca`-first, live-fallback precedence order.
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

On each Bukkit-family platform (`rtp-spigot`, `rtp-paper`, `rtp-folia`)
the adapter registers a composite biome getter at plugin-enable time:

```
Function<Location, String> getter = loc -> {
    Chunk liveChunk = loc.getWorld().getChunkAt(loc);  // already loaded path
    if (liveChunk != null) {
        AnvilChunkView view = anvilProbeSupport.takeCached(key(liveChunk));
        if (view != null) {
            String id = view.getBiomeAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            if (id != null) return id;
        }
        // Opportunistic probe: chunk is on disk even though live handle
        // is cached; read off-tick. This is the common Phase-2 case.
        String fromDisk = tryAnvilBiomeAt(loc);
        if (fromDisk != null) return fromDisk;
    }
    // Fallback: unpopulated chunk or Anvil decode failure.
    return loc.getWorld().getBiome(loc).name();
};
RTP.serverAccessor.setBiomeGetter(getter);
```

Key points:

- The adapter owns the composite getter. `rtp-anvil` exposes only
  `getBiomeAt` on the view; the fallback decision is platform-aware.
- Registration must happen **before** the Iris addon's enable handler
  (addon load order) so that an operator running Iris + the addon still
  sees the addon override (the addon's getter is authoritative for
  Iris-engine truth when the engine is live — see §7).
- Folia uses the same `AnvilProbeSupport` cache introduced in Phase 1;
  the Folia-side biome getter is bit-for-bit identical to Spigot's
  because `AnvilProbeSupport` is platform-neutral.

### 6.1 Enumeration (`setBiomesGetter`)

Enumeration is inherently a pregen-dependent operation: the disk can
only report biomes that have actually been written to disk. The plan
is:

- `rtp-anvil` gains a `AnvilRegionScanner.scanBiomes(worldFolder,
  dimSubpath)` utility that walks every `.mca` file in the dimension,
  decodes each chunk's biome palette lazily, and returns the union.
  Runs on `ForkJoinPool.commonPool()` and is cached per (world,
  world-folder-mtime) pair.
- The platform adapter registers a `setBiomesGetter` that returns the
  cached union. For worlds where pregen covers the full RTP region,
  this is complete. For worlds with sparse pregen it is a subset — the
  addon's engine-backed enumeration remains the complete source.

Scan cost budget: ~5 ms per region file on commodity disk, dominated
by sequential read. A 10-region pregen zone scans in under a second
cold and is free warm (mtime unchanged). This is acceptable for a
one-shot startup scan; the cache key means tab-completion never
re-scans.

---

## 7. Impact on `RTP_Iris_integration` addon

The addon shrinks but does not disappear.

**Removed from the addon (absorbed by `rtp-anvil` + platform adapter):**

- `setBiomeGetter` — the core composite getter (§6) reads Iris biome
  names directly from disk for populated chunks. The addon's
  `engine.getBiome(location).getName()` override is no longer needed
  for the populated case.

**Retained in the addon:**

- `setBiomesGetter` as an **override** — when Iris is installed and
  loaded, `IrisToolbelt.access(world).getEngine().getAllBiomes()`
  returns the full Iris-pack biome roster regardless of pregen state.
  This is strictly a superset of what the Anvil region scan can see,
  and it is the correct source for tab-completion on worlds where
  pregen is partial. Registered with a higher-precedence setter call
  than the platform adapter so it wins when Iris is present.
- `setBiomeGetter` as an **override for unpopulated chunks** — when
  the Anvil probe returns `UNKNOWN` and Iris is installed, the addon's
  `engine.getBiome(location).getName()` can still answer because Iris
  computes biomes deterministically from pack configuration. This
  replaces the current "fall through to `world.getBiome(loc).name()`
  lossy vanilla collapse" for the pre-population case.

Net addon size estimate: ~60 LOC (down from ~140). The residual
responsibility is the gap that `rtp-anvil` by construction cannot fill:
"what biome *will* exist here before the generator has run."

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

1. Biome-palette decoder in `rtp-anvil` (behind `AnvilChunkView#getBiomeAt`),
   plus `AnvilBiomeDecoderTest`. No platform wiring yet.
2. `AnvilChunkView#getBiomesPresent` + `AnvilRegionScanner` + cache.
3. Platform composite getter on Spigot (`BukkitRTPWorld` enable path)
   and Folia (`FoliaRTPWorld` enable path). Paper inherits through
   Spigot class hierarchy.
4. Iris addon shrink (`setBiomeGetter` removal, `setBiomesGetter`
   kept as override).
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
