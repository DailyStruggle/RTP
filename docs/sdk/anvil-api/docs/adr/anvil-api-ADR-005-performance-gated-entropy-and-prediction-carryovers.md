# anvil-api-ADR-005 - Performance-Gated Evaluation of Adaptive Entropy, 2D Prediction & Non-Mutating Cost Estimation Carryovers for `.lca`

**Status:** Accepted (Empirical Verdicts Recorded: **Accept `B1`, `D`, & `E`**; **Reject `A1`, `A2`, `B2`, `C1`, `C2`, `C3`**)
**Date:** 2026-11-20
**Extends:** [anvil-api-ADR-003](anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md) (`.lca` Binary Specification), [anvil-api-ADR-004](anvil-api-ADR-004-overlaid-eikonal-light-columnar-residuals-and-adaptive-rank-exclusion.md) (`formatVersion = 0x0003` / `ICD v3`)
**Related:** [ICD.md](../ICD.md), [leafanvil-ADR-004](../../../../resource/LeafAnvil/docs/adr/leafanvil-ADR-004-pumpkinmc-rust-lca-direct-section-bridge-and-golden-fixtures.md) (Five-Axis Performance Contract), [leaf-video-ADR-005](../../../../leaf-video/docs/adr/leaf-video-ADR-005-declarative-consolidation-color-invariant-atlases-and-modulated-matrix-noise-panes.md) (`Engine-A2` / `Engine-C3` Entropy & Prediction Primitives), [ADR-115](../../../../docs/adr/ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md)

---

## Context

`leaf-video-ADR-005` (`resource/LeafVideo/native/lcv_adr005_engines.h`) implements several lightweight, `Zero Zstd` lossless entropy and 2D prediction primitives—specifically a carryless 32-bit adaptive binary range coder (`lcv_a5_rc_enc_bit` / `lcv_a5_rc_dec_bit`), a 12-context signed-integer coder (`lcv_a5_rc_enc_signed12`), and causal 2D `MED-2D` prediction—alongside non-mutating mode-selection ideas.

While lossy video coding tools (`FDCT8`/`IDCT8`, RDOQ, QPel motion vectors, `YCoCg-R`, in-loop deblocking, and post-decode cosmetic noise synthesis) are strictly incompatible with authoritative `.lca` chunk archives (`L_inf = 0` symbolic fidelity invariant), four lossless carryovers were identified as candidates for `.lca` (`formatVersion = 0x0003`, `anvil-api-ADR-004`):

1. **Candidate A (`Heightmaps` `MED-2D` Prediction + Entropy):** Replacing or supplementing the current 1D raster delta + zero-run + ZigZag encoding on `16x16` `MOTION_BLOCKING_NO_LEAVES` heightmaps (`ICD.md` §8.1) with causal 2D `MED-2D` prediction paired with either (A1) byte-aligned zero-run + ZigZag or (A2) 12-context adaptive binary range coding (`signed12`).
2. **Candidate B (Sparse Eikonal Light Residuals):** Replacing or supplementing the 4-bitplane `GrayPlanes` encoding of `anvil-api-ADR-004` §1 Eikonal `SkyLight` / `BlockLight` residuals (`r(v) = (L(v) - hatL(v)) & 0x0F`) with `64-bit` octant-masked sparse residual coding paired with either (B1) byte-aligned zero-run + 4-bit packed non-zero residuals or (B2) `signed12` adaptive binary range coding on non-zero residuals.
3. **Candidate C (`Mode B` `LCD2` Entropy Stage):** Replacing or supplementing the region-frozen `384 B` 12-bit Canonical Huffman tables (`LcaSectionCodec#decodeHuffmanStream12`, 3x-unrolled 4,096-entry LUT) with (C1) bitfield-context adaptive binary range coding on `cmdStream` only, (C2) adaptive binary range coding on all three streams (`litStream`, `cmdStream`, `offStream`), or (C3) per-chunk/adaptive 12-bit Canonical Huffman (still decoded via the 4,096-entry LUT).
4. **Candidate D (Non-Mutating `Mode B` Cost Estimator — Zero Wire Change):** Using `AtomicDictState`'s existing Huffman bit-length tables (`litLen[256]`) or fast byte-entropy scoring to gate per-section `RankExcl` selection in `Mode B` (`anvil-api-ADR-004` §3.3) without running full double-`LCD2` trial encoding on live `CoW` saves.

### Guiding Principle: Performance-First Compression Discipline

`.lca`'s primary value proposition under `leafanvil-ADR-004` (Five-Axis Contract) is **sub-20 µs batch chunk decoding and `>= 1.2x` faster-than-`.mca` Java read latency** alongside strong `Zero Zstd` storage density. **A small compression gain is not worth a large CPU or latency regression.** Every candidate in this ADR is therefore subjected to a hard, quantitative **ratio-vs-latency acceptance gate** before any production wire-format or codec change is considered.

---

## Quantitative Acceptance / Rejection Gate

Each candidate **shall** be evaluated empirically in an isolated test harness (`LcaAdr005EntropyPerfTradeoffTest`) across uniformly strided live `26.3` `.lca` overworld regions (`100.00%` `L_inf = 0` lossless roundtrip verified on every sample, warmed JVM steady-state `µs/chunk` timings) against the following hard gates:

1. **Bit-Exact & Skip Invariant (`L_inf = 0`, `REQ-RTP-S-005`):** Must achieve `100.00%` lossless roundtrip and preserve $O(1)$ skip framing (`VarInt` length prefixing) with `Zero Zstd`.
2. **Decode CPU Ceiling (Hard Gate):**
   - Whole-chunk decode overhead **shall not exceed `+5.0%` (or `+2.0 µs/chunk`)** of baseline whole-chunk decode latency.
   - Any candidate that increases its own stage decode latency by **`> 2.0x`** while delivering **`< 3.0%` net whole-chunk `Mode B` byte savings** **shall be rejected**.
3. **Whole-Chunk Net Compression Floor (Hard Gate):**
   - Wire-format candidates **shall** deliver either **`>= 2.0%` net whole-chunk `Mode B` (`LCD2`) byte reduction**, OR **`>= 0.5%` net whole-chunk reduction** with **`<= +0.5 µs/chunk`** decode overhead and minimal complexity (no new cross-language entropy state machine).
   - Pure encoder-side optimizations (Candidate D, zero wire change, `0 µs` decode impact) **shall** deliver **`>= 15%` encode latency reduction** on gated chunks while preserving **`>= 95%`** of the exact double-`LCD2` byte savings.
4. **Cross-Language Complexity Tax (`leafanvil-ADR-004`):** Any new wire entropy primitive must be implemented identically in both Java (`:anvil-api`) and Rust (`resource/LeafAnvil/rust/lca-format`) and verified by golden fixtures. Marginal gains (`< 1%` whole-chunk) do not justify maintaining a second stateful binary range coder across two languages.

---

## Decision

Based on empirical measurements in `LcaAdr005EntropyPerfTradeoffTest` across `12` uniformly strided live `26.3` `.lca` overworld regions (`265` chunks, `237` heightmaps, `1,244` non-constant lit sections, `100.00%` `L_inf = 0` bit-exact roundtrip, warmed JVM steady-state timings):

1. **REJECT all adaptive binary range coder (`lcv_a5_rc_*`) carryovers (`A2`, `B2`, `C1`, `C2`), `MED-2D` heightmap encoding (`A1`), and per-chunk re-trained 12-bit Huffman (`C3`):**
   - **Range coding fails the performance-first gate across every `.lca` stage.** On `LCD2` (`Mode B`), full 3-stream binary range coding (`C2`) slows entropy decode by **`13.92x`** (`18.95 -> 263.90 µs/chunk`, **`+244.95 µs/chunk`**) for only **`-4.46%`** whole-chunk bytes, blowing past `leafanvil-ADR-004` decode budgets by an order of magnitude. Restricting RC to `cmdStream` (`C1`) still adds **`+13.91 µs/chunk`** (`1.73x` slower) for just **`-0.79%`** whole-chunk bytes.
   - **Per-chunk re-trained 12-bit Huffman (`C3`) is rejected:** `LcaSectionCodec`'s existing region-frozen `384 B` 12-bit Canonical Huffman (3x-unrolled 4,096-entry LUT) is already within **`0.86%`** (`-0.84%` whole-chunk) of per-chunk optimal Huffman while avoiding `+15.75 µs/chunk` of decode + 4K LUT rebuild overhead. **Keep the region-frozen `384 B` 12-bit Huffman tables unchanged.**
   - **2D `MED-2D` heightmaps (`A1`, `A2`) are rejected:** Baseline 1D delta + RLE decodes in **`0.77 µs/chunk`**. `MED-2D + ByteRLE` (`A1`) slows heightmap decode `3.5x` (`+1.96 µs/chunk`) for `-0.08%` whole-chunk bytes (`-3.4 B/chunk`); `MED-2D + 12-Ctx RC` (`A2`) slows decode `5.7x` (`+3.64 µs/chunk`) for `-0.45%` whole-chunk bytes (`-18.6 B/chunk`).
   - **Eikonal `Octant + signed12 RC` (`B2`) is rejected:** In `Mode B`, `B2` saves only **`0.18%`** whole-chunk (`7.5 B/chunk`) over byte-aligned `B1` while running **`15.6x` slower** to decode (`7.34 µs/sec` vs. `0.47 µs/sec`, `+32.27 µs/chunk` vs. `B1`).
2. **ACCEPT `B1` (`64-bit` Octant-Bitmask + Packed 4-Bit Non-Zero Residuals) as the preferred residual representation if/when `anvil-api-ADR-004` §1 Eikonal Light is implemented:**
   - `B1` uses **zero range coding**—only a `64-bit` active-octant mask, one `64-bit` voxel mask per active `4x4x4` octant, and packed 4-bit non-zero nibbles decoded via branchless `Long.numberOfTrailingZeros` bitscan.
   - Compared against `anvil-api-ADR-004`'s 4-bitplane `GrayPlanes` residual encoding, `B1` is simultaneously **`-21.67%` smaller in `Mode B`** (`-2.70%` net whole-chunk, `-111 B/chunk`) AND **`13.9x` faster to decode** (`0.47 µs/sec` vs. `6.55 µs/sec`, saving **`-28.54 µs/chunk`**).
   - *Important Caveat for `anvil-api-ADR-004` §1:* The benchmark also measured the Eikonal BFS predictor itself at **`14.01 µs/sec` (`65.76 µs/chunk` across `4.69` non-constant lit sections/chunk)**. Before promoting `anvil-api-ADR-004` §1 to `Accepted`, Five-Axis benchmarks must verify that the total Eikonal BFS + `B1` decode cost stays within `leafanvil-ADR-004` read budgets (or gate Eikonal light to offline/cold storage tiers).
3. **ACCEPT `D` (Non-Mutating `litLen[b]` Cost Estimator — Zero Wire Change, `0 µs` Decode Impact) for `anvil-api-ADR-004` §3.3:**
   - Scoring candidate `SectionFrame`/`ChunkFrame` byte streams with `AtomicDictState`'s existing `litLen[256]` Huffman bit-lengths costs **`5.86 µs/candidate`** and reduces `Mode B` A/B trial encoding from **`521.68 µs/chunk`** (double `LCD2`) to **`272.55 µs/chunk`** (**`47.8%` faster encode**, zero wire change, `0 µs` decode impact). Final `RankExcl` savings-retention parity (`>= 95%`) shall be verified in `Gate 1` when `anvil-api-ADR-004` §3.3 is implemented.
4. **ACCEPT `E` (Zero-Fill, Epoch-Tagged Fast-Greedy `LCD2` Encoder + Bounded Live Dictionary Training — Zero Wire Change, `lcd2Level <= 2`):**
   - Replaces the exhaustive 8-chain + `48 KiB` `Arrays.fill` + lazy-lookahead `LCD2` parser on default live saves (`lcd2Level <= 2`) with a 1-probe epoch-tagged `intraHash4` (`4,096` entries, zero per-chunk `Arrays.fill`) + 1-probe `dictHash4` + `repeatOffset` + boundary-only indexing + literal-run step acceleration (`1 + ((p - anchor) >>> 6)`), while preserving the exhaustive parser for explicit archival compaction (`lcd2Level >= 3`).
   - Bounds live dictionary training in `LcaRegionWriter#encodeModeBWithAtomicDict` to the first `8` chunks (`DICT_FAST_TRAIN_MAX_CHUNKS`) and `4 KiB` (`DICT_FAST_TRAIN_CAP_BYTES`) and skips `syncAtomicDictToSegments()` / `cachedParsedDict = null` invalidation when no new dictionary bytes were appended.
   - Cuts `LCD2` body encode by **`6.90x`** (`300.86 -> 43.62 µs/chunk`) AND speeds up `LCD2` decode by **`-14.3%`** (`23.26 -> 19.94 µs/chunk`) for only `+2.2%–3.2%` body delta, cutting end-to-end warmed 64-chunk single-pass `Mode B` DOM saves from **`956–967 µs`** down to **`400.86 µs`** (`v3-Columnar`, beating vanilla `.mca` Zlib's `440.46 µs`) and **`519.83 µs`** (`v3-Default` with `Eikonal+B1`, `47.16%` of `.mca`, `2.12x` smaller, `View Rd` `61.92 µs` vs. `.mca` `122.30 µs`).

---

## Empirical Evidence (`LcaAdr005EntropyPerfTradeoffTest`)

**Corpus & Reference Baseline:** `12` uniformly strided live `26.3` `.lca` overworld regions (`265` chunks, `237` heightmaps, `1,244` non-constant lit sections = `375` SkyLight + `869` BlockLight, `4.69` lit sections/chunk). Every candidate verified `100.00%` `L_inf = 0` bit-exact roundtrip with `Zero Zstd`.
- **Whole-Chunk `Mode B` (`LCD2`) Reference:** Avg **`4,115.5 B/chunk`** (`1,090,608 B` total) | `LCD2` Encode: **`260.84 µs/chunk`** | `LCD2` Decode (Huffman + LZ77): **`32.53 µs/chunk`** (Huffman stage alone: **`18.95 µs/chunk`**).

### Summary Verdict Matrix

| Candidate | Target | `Mode A` Delta (Stage) | `Mode B` Delta (Stage) | Net Whole-Chunk `Mode B` Delta | Decode Latency (Stage / Delta per Chunk) | Gate Verdict |
| :--- | :--- | ---: | ---: | ---: | ---: | :--- |
| **`A1`: `MED-2D` + Byte-RLE** | `16x16` `Heightmaps` (`n=237`) | `-15.40%` | `-6.32%` | **`-0.08%`** (`-3.4 B`) | `2.74 µs` (**`+1.96 µs`**, `3.5x` slower) | **REJECT** (tiny gain, `3.5x` slower) |
| **`A2`: `MED-2D` + `signed12` RC** | `16x16` `Heightmaps` (`n=237`) | `-49.42%` | `-34.57%` | **`-0.45%`** (`-18.6 B`) | `4.42 µs` (**`+3.64 µs`**, `5.7x` slower) | **REJECT** (fails `< 0.5%` floor & `+2 µs` ceiling) |
| **`B1`: `Octant-Bitmask` + Packed 4-Bit** | `ADR-004` Eikonal Light (`n=1,244` secs) | `-24.80%` vs `ADR-004` | `-21.67%` vs `ADR-004` | **`-2.70%`** (`-111 B`) | **`0.47 µs/sec`** (**`-28.54 µs/chunk`**, **`13.9x` faster**) | **ACCEPT** (strictly dominates `ADR-004` `GrayPlanes`) |
| **`B2`: `Octant` + `signed12` RC** | `ADR-004` Eikonal Light (`n=1,244` secs) | `-45.75%` vs `ADR-004` | `-23.14%` vs `ADR-004` | **`-2.88%`** (`-118 B`) | `7.34 µs/sec` (**`+3.73 µs/chunk`**, **`15.6x` slower** than `B1`) | **REJECT** (`+0.18%` over `B1` at `15.6x` decode cost) |
| **`C1`: `LCD2` `cmdStream` Bitfield RC** | `Mode B` `cmdStream` (`n=265`) | — | `-0.81%` | **`-0.79%`** (`-32.6 B`) | `32.87 µs` (**`+13.91 µs/chunk`**, `1.73x` slower) | **REJECT** (fails `+2 µs` ceiling & `2%` floor) |
| **`C2`: `LCD2` Full 3-Stream RC** | `Mode B` `lit`/`cmd`/`off` (`n=265`) | — | `-4.57%` | **`-4.46%`** (`-183.7 B`) | `263.90 µs` (**`+244.95 µs/chunk`**, **`13.92x` slower**) | **REJECT** (catastrophic decode regression) |
| **`C3`: `LCD2` Per-Chunk 12-Bit Huff** | `Mode B` `lit`/`cmd`/`off` (`n=265`) | — | `-0.86%` | **`-0.84%`** (`-34.4 B`) | `24.34 + 10.36 = 34.70 µs` (**`+15.75 µs/chunk`**) | **REJECT** (frozen `384 B` table is already within `0.86%`) |
| **`D`: Non-Mutating `litLen[b]` Cost Est.** | `ADR-004` §3.3 `Mode B` A/B Gate | — | Zero wire change | `0.00%` (encoder-only) | **`0.00 µs` decode**; Encode `521.68 -> 272.55 µs` (**`-47.8%`**) | **ACCEPT** (pure encode speedup, zero decode/wire cost) |
| **`E`: Fast-Greedy `LCD2` + Bounded Train** | `Mode B` (`lcd2Level <= 2`, `0x4C44`) | — | Zero wire change | `+2.2%–3.2%` vs. exhaustive (`47.2%` of `.mca`) | **`-14.3%` decode** (`23.26 -> 19.94 µs`); Encode `300.86 -> 43.62 µs` (**`6.90x` faster**) | **ACCEPT** (single-pass `Mode B` beats `.mca` Zlib save + `2.12x` smaller) |

### Detailed Study Breakdown

1. **Study A (`16x16` `MOTION_BLOCKING_NO_LEAVES` Heightmaps, `n = 237` chunks):**
   - `Base (1D Delta+RLE)`: `Mode A = 15,252 B` | `Mode B = 14,260 B` | `Enc = 1.51 µs` | `Dec = 0.77 µs`
   - `A1 (MED-2D + ByteRLE)`: `Mode A = 12,903 B (-15.40%)` | `Mode B = 13,359 B (-6.32%)` | `Enc = 2.69 µs` | `Dec = 2.74 µs`
   - `A2 (MED-2D + 12-Ctx RC)`: `Mode A = 7,715 B (-49.42%)` | `Mode B = 9,331 B (-34.57%)` | `Enc = 4.31 µs` | `Dec = 4.42 µs`
2. **Study B (`anvil-api-ADR-004` Eikonal Light Residuals, `n = 1,244` non-constant sections):**
   - `Base v2 (Standalone GrayPlanes)`: `Mode A = 358,581 B` | `Mode B = 233,596 B`
   - `ADR-004 (Eikonal + GrayPlanes)`: `Mode A = 174,068 B (-51.46%)` | `Mode B = 135,952 B (-41.80%)` | `Residual Dec = 6.55 µs/sec (30.74 µs/chunk)`
   - `B1 (Eikonal + 64-bit Octant-Bitmask + Packed 4-bit)`: `Mode A = 130,897 B (-24.80% vs ADR-004)` | `Mode B = 106,497 B (-21.67% vs ADR-004)` | `Residual Dec = 0.47 µs/sec (2.20 µs/chunk)`
   - `B2 (Eikonal + Octant + signed12 RC)`: `Mode A = 94,424 B (-45.75% vs ADR-004)` | `Mode B = 104,495 B (-23.14% vs ADR-004)` | `Residual Dec = 7.34 µs/sec (34.47 µs/chunk)`
   - `Eikonal BFS Predictor Cost`: `14.01 µs/sec` (`65.76 µs/chunk` at `4.69` lit sections/chunk).
3. **Study C (`Mode B` `LCD2` Entropy Stage, `n = 265` chunks across `12` regions):**
   - `Base (Frozen 384 B 12-Bit Huffman)`: `1,066,392 B` (`lit=888,185`, `cmd=57,599`, `off=120,608`) | `EntDec = 18.95 µs/chunk`
   - `C1 (cmdStream Bitfield RC)`: `1,057,743 B (-0.81%)` | `EntDec = 32.87 µs/chunk (+13.91 µs)`
   - `C2 (Full 3-Stream Binary-Tree RC)`: `1,017,701 B (-4.57%)` | `EntDec = 263.90 µs/chunk (+244.95 µs)`
   - `C3 (Per-Chunk 12-Bit Huffman)`: `1,057,266 B (-0.86%)` | `EntDec = 24.34 µs + TableBuild 10.36 µs = 34.70 µs/chunk (+15.75 µs)`
4. **Study D (Non-Mutating `Mode B` Cost Estimator, `n = 265` chunks):**
   - `Double-LCD2 Trial Encode`: `521.68 µs/chunk`
   - `litLen[b] Cost Estimator (5.86 µs/cand) + Single LCD2`: `272.55 µs/chunk` (**`-47.8%` encode latency**, `0 µs` decode latency).
5. **Study E (Zero-Fill Fast-Greedy `LCD2` Encoder + Bounded Live Dictionary Training, `64`-chunk `overworld_mountain_r.0.0.mca`, `.mca = 495,616 B`, `LcaV3FormatAndBenchmarkTest`):**
   - **Isolated `LCD2` Body Benchmark (`0x4C44` wire-identical, `100%` bit-exact decode):**
     - `Exhaustive LCD2 (allowTrain=true, prior live path)`: `216,704 B` (`43.72%` of `.mca`) | `Enc = 300.86 µs`
     - `Exhaustive LCD2 (allowTrain=false, lcd2Level=3)`: `216,704 B` (`43.72%` of `.mca`) | `Enc = 240.75 µs` | `Dec = 23.26 µs`
     - `FastGreedy-1 (1-probe, 0-fill, adopted for lcd2Level <= 2)`: `232,576 B` (`46.93%` of `.mca`) | `Enc = 43.62 µs` (**`6.90x` faster** vs. `allowTrain=true`, `5.52x` vs. `allowTrain=false`) | `Dec = 19.94 µs` (**`-14.3%` faster**)
     - `FastGreedy-2 (2-way, 0-fill)`: `231,552 B` (`46.72%` of `.mca`) | `Enc = 47.46 µs` (`6.34x` faster) | `Dec = 19.66 µs` (`-15.5%` faster)
     - `FastGreedy-1+H3 (1-probe + 3B short, 0-fill)`: `226,944 B` (`45.79%` of `.mca`) | `Enc = 76.42 µs` (`3.94x` faster) | `Dec = 21.20 µs` (`-8.9%` faster)
   - **End-to-End Warmed 64-Chunk Production `LcaLiveArchive` Save & Decode (`overworld_mountain_r.0.0.mca`, `.mca = 495,616 B`):**
     - `Vanilla .mca (in-RAM Zlib)`: `495,616 B` (`100.00%`) | `DOM Save = 440.46 µs` | `View Rd = 122.30 µs`
     - `v3-Default Mode A (0-LZ)`: `290,824 B` (`58.68%`) | `DOM Save = 377.26 µs` | `View Rd = 43.06 µs` (`2.84x` faster than `.mca`)
     - `v3-Columnar Mode B (FastGreedy, lcd2Level=1)`: `252,680 B` (`50.98%`) | `DOM Save = 400.86 µs` (**`2.41x` faster** than prior `967.34 µs`, **faster than `.mca` Zlib**) | `View Rd = 62.48 µs` (`1.96x` faster than `.mca`)
     - `v3-Default Mode B (Eik+B1 + FastGreedy, lcd2Level=1)`: `233,736 B` (`47.16%`, **`2.12x` smaller**) | `DOM Save = 519.83 µs` (**`1.82x` faster** than prior `947.56 µs`) | `View Rd = 61.92 µs` (`1.98x` faster than `.mca`)

---

## Consequences

### Positive
- **Protects `.lca`'s Five-Axis Decode Performance (`leafanvil-ADR-004`):** Prevents introducing a stateful bit-at-a-time binary range coder into Java and Rust `.lca` codecs, avoiding `1.7x–13.9x` decode regressions for marginal (`0.45%–4.46%`) byte gains.
- **Confirms `LCD2` 12-Bit Table Huffman Optimality:** Proves that the existing region-frozen `384 B` 12-bit Canonical Huffman table (with 3x-unrolled 4K LUT) is within `0.86%` of per-chunk optimal Huffman at zero rebuild cost.
- **Upgrades `anvil-api-ADR-004` with Two Strict Wins:** Replaces `ADR-004`'s slow `GrayPlanes` Eikonal residual stage with **`B1` (`64-bit` Octant-Bitmask + Packed 4-Bit Nibbles)** (`-21.67%` smaller AND `13.9x` faster to decode, trivial to mirror in Rust `lca-format`) and adopts **`D` (`litLen[b]` cost estimation)** to eliminate double-`LCD2` trial encoding (`-47.8%` encode time).

### Negative / Follow-Ups
- **Eikonal BFS Cost Visibility:** Measuring `14.01 µs/sec` (`65.76 µs/chunk`) for Eikonal BFS highlights that `anvil-api-ADR-004` §1 itself must pass Five-Axis cold/warm read latency gates before promotion from `Proposed` to `Accepted` (with `B1` reducing total Eikonal light decode time by `28.54 µs/chunk` vs. `ADR-004`'s original `GrayPlanes` design).
- No production code was modified by this ADR; implementing `B1` and `D` remains gated under `anvil-api-ADR-004`'s `Rule D-005.2` 4-part proposal.
