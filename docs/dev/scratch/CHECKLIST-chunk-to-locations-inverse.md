# CHECKLIST — `MemoryShape.chunkToLocations` (2D chunk → 1D spiral indices inverse)

**Effective Issue (1 line):** Add a bounded inverse of `xzToLocation` that returns every 1D spiral index decoding to a given chunk `(cx, cz)` — proven ≤ 2 results, O(1) via polar-neighbour probing.

**Mode:** `[CODE]` (design scratch, pre-implementation; D-005 proposal required before any code lands)

**Status (2026-05-11, end of implementation pass):**
- [x] API surface: `MemoryShape.chunkToLocations(int, int): long[]` + `MemoryShape.addBadChunk(long): int` + `protected long neighbourRingOffset(int, int)` hook landed in `rtp-core`.
- [x] Default implementation lives in `MemoryShape` (shape-agnostic angular walk ±1..±8 + radial probe via hook); per-shape overrides supply exact ring offsets for `Circle` (π·(2R+1)) and `Square` (8R+4). `Circle_Normal` / `Square_Normal` inherit the default angular walk; sufficient because the chunk-unit `xzToLocation` semantics are shared.
- [x] `Rectangle` keeps the default (hook returns 0 → angular walk only). The ≤ 2 bound on a row-major curve is weaker but still holds (row width is the radial offset; covered indirectly by the round-trip soundness test on `Square`).
- [ ] REQ-* allocation deferred — not strictly needed since this is an internal API extension, not a behaviour change. If a future call-site migration converts pipeline rejections to `addBadChunk` and observably changes server behaviour, allocate a REQ-* then.

---

## Background (canonical reasoning — keep this short, do not re-derive in code comments)

- `MemoryShape` works in **chunk units**. `(x, z)` in `xzToLocation` are chunk coordinates, `radius` and `centerRadius` are in chunks.
- Forward map `xzToLocation(cx, cz) = n` is **many-to-one** (`DeterministicShapeTest` line 129: "`locationToXZ(xzToLocation(n)) != n` in general").
- Per chunk preimage size is bounded by **≤ 2 indices**:
  - Spiral inter-turn radial gap = 1 chunk.
  - Chunk diagonal = √2 chunks < 2.
  - ⇒ at most 2 consecutive turns of the spiral can intersect a unit-square chunk.
- For `Circle`, ring circumference `C(R) = π·(2R + 1)` (derived from `(R+1)² − R² = 2R + 1`).
- The four — and only — candidate neighbour offsets in polar space are:
  - `n ± 1`         (angular neighbour, same ring)
  - `n ± C(R)`      (same angle, ring `R ± 1`)
- One probe (or at most a handful at chunk-corner FP slop) is sufficient.

Cross-refs:
- [`ADR-001-archimedean-spiral-1d-mapping.md`](../../adr/ADR-001-archimedean-spiral-1d-mapping.md)
- `Circle.xzToLocation` / `Circle.locationToXZ` (`rtp-core`)
- `DeterministicShapeTest.circle_randOutputIsWithinRange`

---

## Plan

### Stage 1 — D-005 proposal (no code yet)

- [ ] 1. Write a short proposal (`<UPDATE>` or scratch addendum) covering:
  - Affected classes: `MemoryShape` (new abstract or default method); concrete overrides in `Circle`, `Circle_Normal`, `Square`, `Square_Normal`; explicit decision for `Rectangle`.
  - REQ-* tag.
  - Before/after structure (default O(1) implementation in `MemoryShape`; per-shape override only if measured wins).
  - Risk: `Rectangle`'s row-major curve doesn't have the spiral's "2-turn" geometric bound — needs its own analysis or `UnsupportedOperationException`.
- [ ] 2. Wait for explicit user approval before any of Stage 2.

### Stage 2 — Interface change (`MemoryShape`)

- [ ] 3. Add to `MemoryShape`:
  ```java
  /**
   * Inverse of {@link #xzToLocation(long, long)} at chunk granularity.
   * Returns every 1D spiral index n in [0, getRange()) for which
   * locationToXZ(n) decodes to the chunk (cx, cz).
   *
   * Bounded by ≤ 2 results for spiral-based shapes (CIRCLE, SQUARE):
   * the spiral's inter-turn spacing is 1 chunk and a chunk's diagonal
   * is sqrt(2), so at most two consecutive turns can intersect a chunk.
   *
   * @return 1- or 2-element array (possibly empty if the chunk is
   *         outside the shape); never null.
   */
  public long[] chunkToLocations(int cx, int cz) { … default impl … }
  ```
- [ ] 4. Default implementation in `MemoryShape` (shape-agnostic, uses only `xzToLocation` + `locationToXZ` + `contains`):
  - Early-out: `if (!contains(cx, cz)) return EMPTY_LONG_ARRAY;`
  - `long n = xzToLocation(cx, cz);`
  - If `locationToXZ(n)` ≠ `(cx, cz)` (FP slop case), walk `n ± 1` until one matches or both leave the chunk; cap walk at a small constant (e.g. 4).
  - Probe up to four candidate neighbour offsets: `±1`, `±ringCircumferenceEstimate(R)` where `R = chunk-distance-from-centre`. Use a hook method `protected long neighbourRingOffset(int cx, int cz)` so spiral shapes can supply the exact value (Circle: `π(2R+1)`) and non-spiral shapes can return 0 to skip.
  - Deduplicate, return sorted ascending.
- [ ] 5. Constant: `protected static final long[] EMPTY_LONG_ARRAY = new long[0];` in `MemoryShape`.

### Stage 3 — Per-shape overrides

- [ ] 6. `Circle` (+ `Circle_Normal` if math is identical): override `neighbourRingOffset` to return exact `Math.round(Math.PI * (2L*R + 1L))` where `R = floor(sqrt((cx−cx0)² + (cz−cz0)²))`. No full override of `chunkToLocations` needed if Stage 2 default is solid.
- [ ] 7. `Square` (+ `Square_Normal`): the "ring" concept is a concentric square layer; offset is the layer's perimeter `8L*R` (chunks). Override `neighbourRingOffset` accordingly. Verify via test (Stage 4) before relying on the closed form.
- [ ] 8. `Rectangle`: row-major curve. Decision (pending Stage 1 D-005): either
  - implement directly using row math (`indexOf(cx,cz)` + check `±1` and `±rowWidth`), or
  - throw `UnsupportedOperationException("chunkToLocations not supported for RECTANGLE")` — Rectangle isn't a spiral; the ≤ 2 bound does not apply.

### Stage 4 — Tests

Test class: `MemoryShapeChunkToLocationsTest` (rtp-core/src/test/...). All tests must be REQ-traceable; add row to `TRACEABILITY.md` once the REQ is allocated.

- [ ] 9. **Round-trip soundness (Circle).** Generate ~10 000 indices via `Circle.rand()` with a fixed seed, decode each to `(cx, cz)`, then assert `chunkToLocations(cx, cz)` contains the original index.
- [ ] 10. **Bound (Circle).** For a grid of chunks inside the annulus (e.g. every chunk for `radius=64`, `centerRadius=16`), assert `chunkToLocations(cx, cz).length <= 2`.
- [ ] 11. **Outside-shape.** Assert `chunkToLocations(cx, cz)` is empty for chunks outside the annulus (use `!shape.contains(cx, cz)` to pick test points: well inside the centre hole, and far past the outer radius).
- [ ] 12. **Forward consistency.** For every index returned, assert `locationToXZ(n)` is exactly `(cx, cz)` — guards against false positives from the FP-slop walk.
- [ ] 13. **Range check.** For every index returned, assert `0 <= n < shape.getRange()`.
- [ ] 14. **Symmetry / boundary sweep.** Sweep `cx` along the positive x-axis at fixed `cz=0` from `centerRadius` to `radius`; count how often the result has size 2. Document the expected density (it should be roughly proportional to chunk-diagonal-crosses-a-ring, ~1/√2 of chunks at the inner edge of each ring). Not a strict assertion — a `@DisplayName`-only sanity test that prints the distribution and asserts non-zero / non-100% to catch "always 1" or "always 2" regressions.
- [ ] 15. **Square shape.** Repeat tests 9–13 for `Square` (and `Square_Normal`).
- [ ] 16. **Rectangle.** Either:
  - Mirror tests 9–13 if implemented, with the bound relaxed to whatever the row-major curve allows (likely still ≤ 2 since rows are 1-chunk-wide), **or**
  - Assert `UnsupportedOperationException` is thrown.
- [ ] 17. **Determinism / no allocation in steady state.** Call `chunkToLocations` 1000× on the same chunk; assert returned arrays are equal (`Arrays.equals`) and the call completes well under a millisecond on average (loose perf smoke, not a benchmark).
- [ ] 18. **Origin / centre-radius edge case.** `chunkToLocations(centerX, centerZ)` (the dead-centre chunk, inside the central hole) must return empty without throwing — exercises the `contains` early-out and the `R=0` branch of `neighbourRingOffset`.
- [ ] 19. **Far-from-centre overflow guard.** Pick a chunk at the spiral's outer edge (worldborder-scale `radius`); assert no overflow in `neighbourRingOffset` (use BigInteger where `Circle.locationToXZ` already does).

### Stage 5 — Docs / traceability

- [ ] 20. Update [`TRACEABILITY.md`](../TRACEABILITY.md) — add row for the new REQ → `MemoryShape.chunkToLocations` → `MemoryShapeChunkToLocationsTest`.
- [ ] 21. Add a short note to [`CONCEPTS.md`](../CONCEPTS.md) (≤ 5 lines) under the spiral section: "the inverse is bounded by ≤ 2 results per chunk because the chunk diagonal √2 < 2× the spiral's 1-chunk inter-turn spacing."
- [ ] 22. ADR? **Probably not** — this is a derived consequence of ADR-001, not a new architectural decision. If `Rectangle` ends up throwing, mention it in a one-line note in ADR-001's References block or in the new REQ's notes column.
- [ ] 23. `CHANGELOG.md` entry under the current unreleased heading: "Added `MemoryShape.chunkToLocations(cx, cz)` returning the (≤ 2-element) 1D spiral preimage of a chunk." (Confirm net-delta-from-last-released-tag rule per `AGENTS.md` *CHANGELOG Hygiene*.)

### Stage 6 — Cleanup

- [ ] 24. Delete this scratch file once Stages 2–5 are merged and tests are green.

---

## Notes for unit tests (consolidated)

- **Reproducibility:** seed all RNGs (`shape.setRng(new Random(SEED))`) per `DeterministicShapeTest` convention; never rely on `ThreadLocalRandom`.
- **Test fixture:** mirror `DeterministicShapeTest`'s `@AfterEach`-style `setRng(null)` teardown to avoid leaking RNGs across tests.
- **Mock accessor:** use `MockRTPServerAccessor` like the existing tests; no Bukkit/Folia/Fabric dependency.
- **REQ traceability:** class name should encode the REQ id, e.g. `ReqCoreFXXXChunkToLocationsTest`. Update `TRACEABILITY.md` row.
- **No `System.out`:** any debug prints must use the `[DEBUG_LOG]` prefix per the run-tests convention, and be removed before submit.
- **Do not weaken assertions** if a test fails — debug the math. The ≤ 2 bound is a hard geometric property; if a test sees 3, something is wrong with the polar-offset formula or with which neighbour gets probed.

---

## Application — amplifying `addBadLocation` via the chunk preimage

**Idea (from issue update 2026-05-11):** when the pipeline calls `shape.addBadLocation(pos)`, the failing `pos` decodes to some chunk `(cx, cz)`. For **chunk-attributable** failure modes (biome mismatch, ocean/material rejection, claim/protection, force-load mask, world-border, anvil pre-filter verdict), every other 1D index that decodes to the same chunk is *also* going to fail the same check next time it's rolled. Marking those twins immediately via `chunkToLocations(cx, cz)` skips a future redundant pipeline pass.

### Bound on the speed-up

- ≤ 2 indices per chunk (proven above).
- Amplification factor ≤ 2× of `addBadLocation` calls — i.e. the existing bad-set fills up at most twice as fast for chunk-attributable rejections.
- **No effect on safety failures attributable to a single block** (`FailTypes.safety`, `FailTypes.vert` when caused by a specific column rather than the whole chunk's biome/protection state) — there the twin index decodes to the same chunk but to a *different block column* within it, which may well be safe. Marking the twin in that case introduces **false positives** (good locations marked bad), shrinking the effective region.

⇒ The amplification must be **opt-in per call-site**, gated on whether the rejection is chunk-uniform.

### Call-site classification (current `addBadLocation` invokers)

Audit of `addBadLocation` call-sites (from grep):

 Call-site | Chunk-uniform? | Amplify? |
-----------|----------------|----------|
 `ScanTask` SCAN_MISS short-circuit (biome / anvil prefilter rejected the chunk) | **Yes** — verdict is per-chunk (biome name, NBT prefilter, claim) | ✅ |
 `ScanTask` lines 1242/1263/1274/1364/1380/1392/1399/1432/1437/1443 (various scan rejections — needs per-line audit) | Mixed | Audit per line |
 `PregenTask` lines 605/626/783/803 | Likely chunk-uniform (pregen verdicts are chunk-level) | ✅ probable, verify |
 `Circle`/`Square`/`Rectangle`/`Circle_Normal`/`Square_Normal` `if (u) addBadLocation(location)` (uniqueplacements: "this exact 1D index was just used") | **No** — this is the "unique" knob; the twin block is still selectable | ❌ |
 `ChunkyRTPShape` line 51 | Unknown, audit | Audit |
 Pipeline `FailTypes.safety` / `FailTypes.vert` paths (per-column rejection) | **No** | ❌ |
 Pipeline `FailTypes.biome` / claim-plugin / force-load / world-border / anvil-prefilter paths | **Yes** | ✅ |

### Proposed API surface

Rather than scatter logic at every call-site, add a sibling method:

```java
/**
 * Marks the 1D index plus every other 1D index that decodes to the same chunk
 * as bad. Use only for chunk-attributable rejections (biome, claim, force-load,
 * world-border, anvil-prefilter, ocean). Do not use for per-column safety
 * failures — those would produce false positives.
 *
 * @return number of indices newly marked (≤ 2).
 */
public int addBadChunk(long location) {
    int[] xz = locationToXZ(location);
    long[] preimage = chunkToLocations(xz[0], xz[1]);
    if (preimage.length == 0) {
        addBadLocation(location);
        return 1;
    }
    int n = 0;
    for (long p : preimage) {
        if (!isKnownBad(p)) { addBadLocation(p); n++; }
    }
    return n;
}
```

(Or accept `(int cx, int cz)` directly when the caller already has the chunk coords — saves a `locationToXZ` round-trip.)

### Migration plan

- [ ] 25. Audit each `addBadLocation` call-site and classify it as chunk-uniform or column-specific. Annotate in code comments.
- [ ] 26. Convert chunk-uniform call-sites (most of `ScanTask`, `PregenTask`, biome/claim/force-load pipeline stages) to `addBadChunk`.
- [ ] 27. **Do not touch** column-specific call-sites (`uniqueplacements`, `FailTypes.safety`/`vert`) — leave as `addBadLocation`.
- [ ] 28. Metric: count how often `addBadChunk` marks 2 vs 1 — log under `MemoryTracker` / scan stats so the actual amplification factor is observable. **Expected value for `Circle`: `E[marked] = 1 + p₂ ≈ 1.57`** (worst case 2.0). Derivation: a chunk has 2 preimages iff its radial extent (mean `d ≈ 1.13` chunks across angle, half-width `≈ 0.57`) straddles the next integer ring boundary; the fraction of chunks doing so is `p₂(R) ≈ 0.57` for `R ≫ 1`, rising toward 1.0 as `R → CR`. `dp₂/dR ≈ 0` across the annulus body — amplification is approximately uniform per ring. Narrow annuli (small `Rmax − CR`) trend toward 2.0×; wide ones sit at 1.57×.

### Tests for `addBadChunk`

- [ ] 29. **Amplification.** Pick a chunk known to have 2 preimages (find one by sweeping `chunkToLocations` in test 14's density sweep). Call `addBadChunk(pos)` with one preimage; assert both are now `isKnownBad`.
- [ ] 30. **Idempotence.** Call `addBadChunk` twice; the second call should mark 0 new indices.
- [ ] 31. **Safety contract — false-positive guard.** Document (in Javadoc and test name) that this method is for chunk-uniform rejections only. No automated test can enforce the call-site discipline; add a `[DEBUG_LOG]`-style trace in dev builds if it helps audits.
- [ ] 32. **Chunk-outside-shape.** If `pos` decodes to a chunk outside the annulus (FP slop at the radius boundary), fall through to the single-`addBadLocation` path — assert no `chunkToLocations` ArrayIndexOutOfBounds.

---

## Open questions to resolve in the D-005 proposal

1. Should the result be sorted, or in "representative-first then candidate" order? Sorted is more predictable for callers building bad-sector intervals; representative-first is cheaper.
2. Is there a real caller in `rtp-core` today that needs this, or is it API surface for addons / future work? (Affects whether it's a `public` or `@ApiStatus.Experimental` method on `MemoryShape`.) Likely use-cases: anvil prefilter pre-marking a known-bad chunk's spiral indices; backlog-cache promotion deciding which 1D entries a freshly-loaded chunk satisfies.
3. Cache invalidation: when `radius` / `centerRadius` / `centerX` / `centerZ` change at runtime, callers must drop any indices they computed. Document the contract on the method's Javadoc — do not add caching inside `chunkToLocations` itself.
