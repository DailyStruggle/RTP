# ADR-088 - Configurable Downsampling Stride Filter for Spatial Candidate Selection

**Status:** Accepted (amended 2026-10-06)
**Date:** 2026-09-08 (amended 2026-10-06: full-bin stride bound replaces the half-bin bound; REROLL cell-slot spacing, section 10)

## Context

Under legacy polar Archimedean spiral mapping (`Circle`, [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md)), continuous radial dispersion $r(\theta) = a + b\theta$ skipped approximately two-thirds (~68.2%) of discrete integer chunk coordinates in outer rings. While mathematically unintended—creating coverage voids, radial spoke gaps, and kaleidoscope coordinate aliasing—server operators observed an accidental operational benefit in live production:
1. **Implicit Inter-Player Separation:** Consecutive `/rtp` executions rarely landed on immediately adjacent chunk boundaries, naturally dispersing arrivals across the region.
2. **Accelerated Safety Scanning:** Background verification and pre-scan tasks (`ScanTask`, [ADR-067](ADR-067-adaptive-scan-rate-and-mca-header-generation-check.md)) traversed fewer chunk candidates per square kilometre of world border.
3. **Reduced Horizon Clumping:** On heavily populated servers, players teleporting in sequence did not crowd each other's view-distance horizons.

With the introduction of the dual-layer spatial memory architecture ([ADR-085](ADR-085-spiral-addressed-hilbert-key-space.md)), 100.0% of geometric coordinates within the circular domain become mathematically addressable via continuous space-filling Hilbert curves nested within a discrete Chebyshev square macro-grid. While this achieves exact geometric fidelity and eliminates boundary gaps, server operators with large world borders ($R \ge 10{,}000$ blocks) or high concurrent teleport rates intentionally desire coarse spatial sparsification to guarantee that random selections do not cluster within a player's immediate view distance.

Hardcoding a coordinate skip or reverting to polar coordinate skipping is inadmissible:
- Polar dispersion is non-uniform: dense near the origin, sparser at the rim, producing extreme clumping at small radii ($r < 64$) and severe radial gaps at large radii ($r > 256$).
- Polar coordinate skipping breaks run table bijection and violates the uniform candidate probability guarantee.
- Runtime rejection sampling based on inter-entity distance checks requires $O(P)$ distance calculations per candidate attempt against active entities/players, introducing pipeline latency and thread synchronization bottlenecks.

## Decision

We introduce an **Adaptive Dyadic Downsampling Stride Filter with Keyed Pseudorandom Permutation** (`selectionStride` / `downsampleStride`, default adaptive) configured as a first-class selection mechanism on spatial memory shapes (`SquareOptimizedDualLayer`, `CircleOptimizedDualLayer`):

### 1. Mathematical Formulation & Hilbert Manifold Stride

Let the discrete spatial domain contain $N$ addressable chunk keys $k \in [0, N - 1]$ mapped via the space-filling Hilbert curve $\mathcal{H}: \mathbb{Z} \to \mathbb{Z}^2$.
When downsampling is enabled with integer stride $S \ge 1$:

$$\text{Effective Key Index } k_s = k \cdot S \quad \text{for } k \in \left[0, \left\lfloor \frac{N - 1}{S} \right\rfloor \right]$$

Because the 2D Hilbert curve preserves strict metric locality (the Hölder condition with exponent $1/2$):
$$\| \mathcal{H}(k_1) - \mathcal{H}(k_2) \|_2 \le C \sqrt{|k_1 - k_2|}$$
striding by $S$ along the 1D Hilbert index guarantees that any two consecutively sampled indices $k$ and $k + 1$ map to physical coordinates separated by a deterministic distance bounded by:
$$d_{\text{min}} \approx \sqrt{S} \text{ chunks} \quad (16\sqrt{S} \text{ blocks})$$

For power-of-two strides $S \in \{1, 4, 16, 64, 256\}$:
- $S = 1$: Native 1-chunk resolution ($100\%$ candidate density, $d_{\text{step}} = 1$ chunk = 16 blocks).
- $S = 4$: Downsampled by $4\times$, minimum physical candidate step $d_{\text{step}} \ge 2$ chunks (32 blocks).
- $S = 16$: Downsampled by $16\times$, candidate arrivals separated by $\ge 4$ chunks (64 blocks).
- $S = 64$: Downsampled by $64\times$, candidate arrivals separated by $\ge 8$ chunks (128 blocks, matching typical server view distances).
- $S = 256$: Downsampled by $256\times$, candidate arrivals separated by $\ge 16$ chunks (256 blocks, macro-horizon spacing).

### 2. Dyadic Bit-Reversal Bisection Subsets (Quadtree Anti-Resonance)

Because the dual-layer Hilbert manifold is built upon recursive base-4 quadtrees ($32 \times 32$ chunks = $1{,}024$ chunks), linear phase stepping ($\phi \to \phi + 1$) causes quadtree resonance: indices lock into identical quadrant corners, creating dense bands and leaving adjacent blocks unvisited for epochs.

To break quadtree resonance and maximize physical dispersion between successive phase offsets, phase shifts step through the **Dyadic Bit-Reversal Bisection Sequence**:
$$\phi \in \left[0, \frac{S}{2}, \frac{S}{4}, \frac{3S}{4}, \frac{S}{8}, \frac{5S}{8}, \dots \right]$$
For $S = 256$: offsets advance as `0, 128, 64, 192, 32, 160, 96, 224, 16, 144...`  
For $S = 64$: offsets advance as `0, 32, 16, 48, 8, 40, 24, 56, 4, 36...`

In binary integer arithmetic, this is computed in $O(1)$ without branches or memory:
$$\phi = \text{reverse}(t \pmod S) \ggg (32 - \log_2(S))$$
Successive phase offsets systematically alternate between opposite halves, quarters, and eighths of the spatial manifold, multiplying inter-arrival physical jump distance by $> 5\times$ compared to linear stepping.

### 3. Keyed Pseudorandom Permutation (Zero Duplicates & Unpredictability)

Memoryless pseudo-random candidate selection (`rand.nextLong()`) is subject to the Birthday Paradox: over 24,000 teleports on an $R = 256$ world ($202{,}000$ chunks), random sampling with replacement generates $\sim 1{,}360$ duplicate chunk landings ($5.7\%$). Conversely, linear coprime stepping produces zero duplicates but is trivially predictable by players observing consecutive landings.

To achieve **zero duplicate selections**, **zero player predictability**, and **$O(1)$ memory**, candidate indices within each dyadic subset are drawn via a **4-Round Feistel Network with Cycle-Walking** (Format-Preserving Encryption):
$$k_{\text{permuted}} = \text{FeistelPermute}(k_{\text{counter}}, \text{subsetSize}, \text{secretServerSeed} \oplus (\phi \cdot \gamma))$$
where $\gamma = \text{0x9E3779B97F4A7C15L}$ (the golden ratio fractional constant).

Properties:
1. **Strict Bijection (Zero Collisions):** The Feistel network is mathematically reversible, guaranteeing that every candidate key in the subset domain is visited exactly once before any can repeat ($0.0\%$ duplicates across the entire subset capacity).
2. **Cryptographic Hardening via ARX PRF:** The round function $F(R, K)$ uses a balanced Add-Rotate-XOR (ARX) sequence (SipRound style) with domain half-mask isolation. Mixing modular addition ($\boxplus$), variable bit rotations ($\lll$), and XORs ($\oplus$) directly with the 64-bit round key blows up the algebraic degree and destroys linear/differential relations, rendering the cipher secure against automated SAT/SMT (Z3) solver key extraction on small domains.
3. **True Cycle-Walking over Bit-Space:** Candidates are initialized directly on the bit-bounded space without premature modulo reductions, cycling out-of-bounds ciphertexts in $\le 3$ iterations back into the exact target domain size.
4. **$O(1)$ Auxiliary RAM:** The entire state is an advancing 64-bit integer counter ($t \gets t + 1$), requiring $< 64$ bytes of memory with zero heap tracking.

### 4. Virtual Good Domain Accumulate Integration (Immunity to Range Shrinkage)

When bad/hazardous chunks are registered in `SegmentedKeyRunTable`, punching holes into physical coordinates would fracture stride moduli and break the permutation cycle.

To ensure seamless range contraction:
1. In `MODE_ACCUMULATE`, striding and the Feistel permutation operate on the **Virtual Good Domain $[0, \text{totalGood} - 1]$**:
   $$\text{totalGood} = \text{range} - \text{runTable.totalCovered()}$$
2. Subset sizes and permutation bounds are evaluated against $\text{totalGood}$:
   $$\text{subsetSize} = (\text{totalGood} - 1 - \phi) / S + 1$$
   $$\text{virtualGoodIndex} = k_{\text{permuted}} \cdot S + \phi$$
3. The virtual index is translated to a verified safe physical chunk via `SegmentedKeyRunTable.resolveAccumulate(virtualGoodIndex)`.

As new bad locations are discovered, $\text{totalGood}$ contracts smoothly. Because selection evaluates modulo $\text{totalGood}$, every generated index remains strictly in-bounds, skipping 100% of bad chunks in $O(\log \text{Runs})$ without re-rolls or broken permutations.

### 5. Loopless Deterministic Selection Runtime ($1.56\,\mu\text{s}$)

In `SquareOptimizedDualLayer`, every virtual good index maps bijectively to a valid safe chunk. Legacy retry loops (`for (int attempts = 0; attempts < 100; attempts++)`) have been eliminated entirely. Candidate coordinate generation executes in a **single-pass, branchless formula**:
- Latency drops from $12.6\,\mu\text{s} \to 1.56\,\mu\text{s}$ per selection ($> 600{,}000$ coordinates/second).
- 100% loop-free and retry-free.

### 6. Adaptive Stride Scaling (`deriveAdaptiveStride`) & Full-Bin Stride Limit

A static stride $S = 256$ on small shapes ($R = 16$, $1{,}024$ chunks) causes subset starvation (only 4 candidates per subset). Stride $S$ scales adaptively based on domain size:
$$S = \begin{cases} 1 & \text{for } \text{domain} < 64 \\ 4 & \text{for } \text{domain} < 256 \quad (R \le 8) \\ 16 & \text{for } \text{domain} < 1024 \quad (R \le 16) \\ 64 & \text{for } \text{domain} < 8192 \quad (R \le 64) \\ 256 & \text{for } \text{domain} \ge 8192 \quad (R \ge 128) \end{cases}$$
This guarantees that every subset maintains $\ge 64$ candidates while keeping inter-player physical spacing proportional across all world sizes.

#### Hard Limit: Full-Bin Stride Rule ($S \le \text{binArea}$)
Stride $S$ is bounded by one key per bin:
$$S \le \max\left(1, \text{binArea} \right) \quad \text{where } \text{binArea} = P^2 = \text{pointEdgeChunks}^2$$

- **Rule Rationale:** At $S = P^2$ a phase lane holds the same intra-bin Hilbert offset in every bin. Bins of equal orientation translate that offset by multiples of $P$, so same-lane keys sit at least $P$ chunks apart. The floor breaks only where the spiral re-orients a bin at a ring corner; that share is about $1/\text{rings}$, and derived $P \le 2R/64$ keeps $\text{rings} \ge 32$, so at least ~95% of keys keep the floor (measured 5.1% / 2.6% / 1.35% seam keys at 32 / 64 / 128 rings; on the shipped 16,384-block circle no same-lane pair is closer than 22.6 chunks). Below $P^2$ a lane takes several keys per bin, and the Hilbert sub-quadrants they land in are transposed or reflected relative to each other, so no spacing floor holds: $S = P^2/2$ leaves 78-92% and $S = P^2/4$ leaves 97-99% of keys within $\sqrt{S}$ of a same-lane key inside a bin, independent of radius (`ConsecutiveLandingSpacingSimTest`). Being a power of four is not sufficient; the stride must cover the whole bin.
- **Predictability:** A lane narrows the next window of 16-64 landings to $N / S$ keys sharing one intra-bin offset (about 3,200 chunks on the shipped circle). The order within the lane, the next lane and the window length stay keyed (C7), so this does not let a player trap a landing.
- **Enforced Caps:**
  - For $P = 32$ ($\text{binArea} = 1{,}024\text{ chunks}$): $S \le 1{,}024$.
  - For $P = 16$ ($\text{binArea} = 256\text{ chunks}$): $S \le 256$.
  - Stride is additionally bounded by $S \le \frac{\text{domainSize}}{4}$.
- **Resolution of Domain Saturation:** When benchmarking or operating at large strides ($S = 256$), the world radius must provide sufficient domain capacity ($R \ge 1{,}024\text{ chunks}$ for concurrent bursts $N \ge 500$) so that macro-tile occupancy remains below the packing threshold ($\le 10\%$), preventing seam boundary overlap between adjacent occupied bins.

#### Superseded: Half-Bin "Nyquist" Rule ($S \le \frac{\text{binArea}}{2}$)

The original rule capped $S$ at half the bin, citing the sampling theorem ($f_s \ge 2 f_{\max}$) against aliasing across bin boundaries. The key space is a bijection rather than a sampled signal, so no aliasing applies. The cap clamped the resolution-derived $S = P^2$ to $P^2/2$, which put two keys of each lane in mirrored halves of every bin, as close as 1.4 chunks.

### 7. Strict Separation of Ground Truth vs. Candidate Sampling

Safety ground truth shall never be degraded:
1. **Full-Resolution Run Table Storage:** `SegmentedKeyRunTable` stores and verifies terrain at full 1-chunk resolution ($S = 1$). Safety verdicts are registered at exact chunk coordinates.
2. **Downsampling at the Selection Tier:** Stride and dyadic subsets apply strictly inside `MemoryShape.rand()`.
3. **Optional Scan Task Optimization:** Operators can optionally enable strided scanning in `ScanTask` (`scan.useSelectionStride: true`) to accelerate background pre-scans on massive worlds ($R \ge 10{,}000$).

### 8. Dynamic Expansion Epoch Ratchet (Stability Under Range Expansion)

While Section 4 defines range contraction under `MODE_ACCUMULATE`, `MemoryShape` also supports dynamic radial expansion (`expand: true`, `adjustRange`), which increases `totalGood` and domain range mid-lifecycle.

Altering the permutation domain modulus mid-sequence changes the bijective cycle. To maintain deterministic uniqueness and prevent broken permutations:
1. **Epoch Counter Tracking:** `MemoryShape` maintains an atomic sequence counter `expansionEpoch` (initialized to `0`).
2. **Modulus & Key Ratchet on `adjustRange`:** When `adjustRange` increments the active range under `expand: true`:
   - `expansionEpoch` increments atomically ($\text{expansionEpoch} \gets \text{expansionEpoch} + 1$).
   - The Feistel round key is ratcheted with the new epoch:
     $$K_{\text{epoch}} = \text{secretServerSeed} \oplus (\text{expansionEpoch} \cdot \text{0x517CC1B727220A95L}) \oplus (\phi \cdot \gamma)$$
   - Candidate offset counters ($t$) reset to `0`.
3. **Invariants:**
   - Strict **$0.0\%$ collisions** are preserved within each expansion epoch.
   - Cross-boundary duplicate probability across epochs is mathematically bounded by domain-wide uniform dispersion ($\le \text{visited} / \text{totalGood}_{\text{new}}$), eliminating clustering or persistent repeat landings.

### 9. Configuration & Command Interface

Configured in `regions.yml`:

```yaml
regions:
  default:
    world: "world"
    shape:
      name: "square_optimized_duallayer"
      radius: 5000
      centerRadius: 256
      # Selection stride (0 / adaptive = auto-derived from domain; >0 = explicit stride e.g. 16, 64, 256)
      selectionStride: 0
      # Rotate sampling phase offset after N consumed teleports (0 = advance per candidate; >0 = batch window)
      strideRotateAfter: 0
```

- Supported in command overrides: `/rtp shape:square_optimized_duallayer selectionstride:256`.

### 10. REROLL Cell-Slot Spacing (Cross-Lane Floor)

Section 6 gives a floor only among keys of one lane. Lanes are permuted under independent keys, so keys of two lanes fall at random relative to each other: a 4,096-landing REROLL stress run on the 16,384-block circle had 98 pairs within 48 blocks (3 chunks), about the uniform rate.

In `MODE_REROLL` the key domain is fixed, so the stride keys are regrouped by aligned 8x8-chunk cells:
1. **Cells:** for $P \ge 8$, every aligned 64-key block of a bin's Hilbert index is an aligned $8 \times 8$-chunk cell, and its 64 offsets are an isometric copy of the base order-3 curve under all eight bin orientations. A stride $S$ that is a multiple of 64 splits into $S/64$ cell **slots**; key $= b \cdot S + 64 \cdot \text{slot} + \ell$.
2. **Inset offsets:** $\ell$ is restricted to the 16 offsets whose chunk sits $\ge 2$ chunks from every cell edge (local coordinates $[2, 5]^2$). Any two picks in distinct cells are then $\ge 5$ chunks apart on some axis (more than 64 blocks), whichever window drew them.
3. **Shared slot order:** all windows on a slot advance one counter through one keyed permutation of the $\text{range}/S$ stride blocks ($K = \text{secretServerSeed} \oplus (\text{epoch} + \text{cycle}) \cdot \ldots \oplus \text{slot} \cdot \gamma$). No cell repeats within a **pass** of $\text{range}/64$ picks (65,472 on the shipped 16,384-block circle; a 20,000-entry backlog plus 4,096 landings at 40% rejection draws about 34,000).
4. **Windows unchanged:** a window holds one (slot, offset) pair for a Gaussian 16-64 picks, so picks inside it keep the section 6 lattice. Every $S/64$ windows visit each slot once in a keyed order, so slot passes advance evenly; the offset is keyed per window. `BINNED_AMORTIZED` uses the pure-in-$t$ form: slot $= \text{reverse}(t \bmod S/64)$, block counter $t / (S/64)$, offset keyed per draw.

**Bounds:** the floor holds within a pass; a later pass revisits cells at a fresh keyed offset, so cross-pass pairs fall back toward the uniform rate. `MODE_ACCUMULATE` keeps the per-lane path, because renumbered good indices do not stay on cells. Predictability (C7): the next pick is one of $\text{range}/S$ blocks under a keyed permutation; an observer learns at most the current slot and offset of a window.

**Coverage trade-off:** only the $4 \times 4$ core of each cell is selectable, 25% of chunks, on a lattice with an 8-chunk period. The spread by area stays even, because every cell offers the same subset (`CellSlotSpacingTest`), but land patches narrower than about 4 chunks that fall entirely in cell borders are never picked. A 16x16 cell with the same 2-chunk inset keeps the 5-chunk floor and reaches 56% of chunks, but its pass is 4x shorter (16,368 picks on the shipped circle), below the draws of a 20,000-entry backlog.

## Criteria

| # | Criterion | Status |
|---|---|---|
| C1 | **Exact Coordinate Bijection:** Strided index maps bijectively to valid chunk coordinates without collisions or out-of-bounds leakage | **MET** |
| C2 | **Full Safety Ground Truth:** Run tables retain 1-chunk resolution without loss of hazard identification | **MET** |
| C3 | **Deterministic Spacing & Full-Bin Bound:** At $S = P^2$ same-lane keys satisfy $d \ge P$ chunks except at spiral ring-corner seams (share ~$1/\text{rings} \le$ ~5%); sub-bin strides carry no spacing floor; $S \le \text{binArea}$ | **MET** (raw draw order: 0 back-to-back pairs under 256 blocks per 4,096 draws. With ~40% terrain rejection under ACCUMULATE, and with backlog and live searches interleaved, back-to-back pairs stay ~95% below uniform, because the remap offset between nearby picks is only the rejections learned between them. Dense learned bad areas push that offset toward a full bin and loosen in-bin spacing) |
| C4 | **Zero Dynamic Distance Queries:** Selection operates in $O(1)$ without runtime distance re-roll loops | **MET** |
| C5 | **Full Ergodicity:** Rotating dyadic phase over $S$ offsets covers 100% of addressable chunk coordinates without coordinate starvation | **PARTIAL** (ACCUMULATE: met. REROLL with cell-slot spacing: 25% of chunks, the core of each 8x8 cell, section 10) |
| C6 | **Zero Duplicate Selections:** Keyed Feistel permutation guarantees 0 duplicate chunk landings ($0.0\%$) across the domain | **PARTIAL** (holds while the permuted domain is fixed, as in REROLL mode. Under ACCUMULATE each merged rejection renumbers good indices, and with `expand: false` landings are not marked, so used chunks repeat at about the uniform rate) |
| C7 | **Cryptographic Unpredictability:** Non-linear avalanche bit-mixing prevents players/bots from anticipating landing coordinates | **MET** |
| C8 | **Range Contraction Immunity:** Permutations operate on virtual good space, remaining valid when bad locations contract domain | **MET** |
| C9 | **Scale-Invariant Density:** Adaptive stride scaling prevents subset starvation on small arenas ($R \le 16$) | **MET** |
| C10 | **Cross-Lane Floor (REROLL):** any two picks within a pass are $\ge 5$ chunks apart on some axis, with no area marked | **MET** (section 10; 0 pairs within 48 blocks over 4,096 landings after a 20,000-draw backlog, `CellSlotSpacingTest`) |

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Legacy Polar Spiral Gaps** | Non-uniform: extreme clumping at small radii ($r < 64$) and severe radial spoke gaps at large radii ($r > 256$), with polar coordinate aliasing and 68.2% unmapped holes. |
| **Runtime Inter-Player Distance Rejection** | Requires querying active entity locations and executing re-roll rejection loops during selection, adding pipeline latency and CPU overhead. |
| **Static Stride Offset ($S > 1, \phi = 0$ permanently)** | Causes permanent coordinate starvation of $(S - 1)/S$ of the world domain, repeatedly visiting the same subset of safe landing zones over time. |
| **Linear Phase Progression ($\phi \to \phi + 1$)** | Resonates with base-4 Hilbert quadtree boundaries, locking candidates into quadrant corners and creating spatial pockets. |
| **Linear Coprime Stepping ($x_{t+1} = x_t + G$)** | Trivially cracked by players logging 2-3 landings, exposing subsequent destinations to camping or griefing. |
| **Memoryless Random with Replacement (`rand()`)** | Birthday Paradox guarantees 5-7% duplicate selections over sustained loads. |

## Consequences

- **Positive:**
  - $O(1)$ single-pass candidate generation ($1.56\,\mu\text{s}$) with zero retry loops.
  - Zero duplicate selections across sustained server loads ($0.0\%$ collisions).
  - Cryptographically unpredictable destinations with $< 64$ bytes RAM overhead.
  - Natural inter-player horizon spacing without runtime entity distance checks.
  - Dynamic range shrinkage from bad locations handled seamlessly without broken permutations.
  - Full scale invariance from $R = 16$ to $R = 1024+$ chunks.
- **Negative / Trade-offs:**
  - Requires maintaining an atomic 64-bit counter and 64-bit secret key per shape instance.

## References

- [ADR-001: Archimedean Spiral 1D Mapping](ADR-001-archimedean-spiral-1d-mapping.md)
- [ADR-028: L3 Backlog Cache](ADR-028-l3-backlog-cache.md)
- [ADR-067: Automatic PRESCAN and Adaptive Scan Rate Control](ADR-067-adaptive-scan-rate-and-mca-header-generation-check.md)
- [ADR-081: Unified Blocked Biome Run Table in MemoryShape](ADR-081-unified-blocked-biome-run-table.md)
- [ADR-085: Spiral-Addressed Hilbert Key Space for Learned State](ADR-085-spiral-addressed-hilbert-key-space.md)
