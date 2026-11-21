# anvil-api-ADR-006 - Factored `0x0003` Layer 2 Dictionary, Pure-ID Layer 3 Micro-Palettes, Legacy `v2` Quarantine & Dominant-Pinned Local Gray Bitplanes

**Status:** Accepted & Implemented (Java `:anvil-api` & `:resource:LeafAnvil:leafanvil-common` `0x0003` + `ICD.md` `LCA-ICD-3.0`; Rust `lca-format` lockstep tracked with `anvil-api-ADR-004`)
**Date:** 2026-11-21
**Extends:** [anvil-api-ADR-004](anvil-api-ADR-004-overlaid-eikonal-light-columnar-residuals-and-adaptive-rank-exclusion.md) (Format Version `0x0003` (`ICD v3`)), [anvil-api-ADR-005](anvil-api-ADR-005-performance-gated-entropy-and-prediction-carryovers.md) (Performance-Gated Entropy & Prediction Carryovers)
**Related:** [ICD.md](../ICD.md) (`LCA-ICD-3.0`), [anvil-api-ADR-003](anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md) (`.lca` Binary Specification), [leafanvil-ADR-003](../../../../resource/LeafAnvil/docs/adr/leafanvil-ADR-003-negotiated-lca-chunk-network-streaming-and-delta-sync.md) (`LcaWireStreamProtocol`), [leafanvil-ADR-005](../../../../resource/LeafAnvil/docs/adr/leafanvil-ADR-005-dual-mode-javaagent-direct-section-bridge-and-netty-wire-pipeline.md) (`RawChunkFrameSnapshot`), [ADR-115](../../../../docs/adr/ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md)

## Context

In `.lca` `formatVersion = 0x0002` (`LCA-ICD-1.0`), Layer 3 Slab-Local Micro-Palettes (`ICD.md` §6.5) and `ChunkPatchTail` (§8.3) embedded three version-pinned vanilla lookup tables (`STATIC_CORE_STATES[160]`, `STATIC_CORE_BIOMES[65]`, `STATIC_CORE_PROP_SUFFIXES[128]`) directly into the binary wire format. Although open-ended fallback tags (`0x40`, `0x00`) preserved lossless storage for modded and future vanilla blocks, coupling the wire format to hardcoded vanilla indices created three architectural problems:

1. **Two-Tier Size & Latency Bias:** Vanilla Overworld blocks received 1-byte static tokens and pre-baked static NBT byte-slice caches (`STATIC_CORE_*_NBT_BYTES*`), while future vanilla additions and heavily modded namespaces paid inline UTF-8 fallback overhead and missed static decode caches.
2. **Arbitrary Hardcoded Ordering & Cross-Language Lockstep Tax:** Static indices could never be reordered or pruned without breaking older files, and every table tweak required permanent lockstep maintenance across Java and Rust implementations.
3. **`8,192`-State Micro-Palette Cliff:** Both `LcaRegionWriter` and `LcaStreamingNbtEncoder` disabled `hasSlabLocalPalette` once `totalDictionaryStates >= 8192`, forcing heavily modded regions to lose chunk-level `BiomeTemplate` deduplication and compact `1-byte` local section palette indices.

At the same time, local voxel Gray coding over block palette ordinals had previously been tested only on unordered global/frequency indices (`+30.3%` larger in `anvil-api-ADR-003`). Because Layer 4 `SectionFrame`s operate on section-local permutations over opaque palette IDs, ordering a section's minority palette by local 3D spatial adjacency and encoding Reflected Binary Gray bitplanes works identically across any namespace, Minecraft version, or global dictionary assignment order.

## Decision

Refactor `.lca` **`formatVersion = 0x0003` (`LCA-ICD-3.0`)** (with no version bump, as `0x0003` is not yet locked in Rust `lca-format`) to eliminate all `STATIC_CORE_*` dependencies from the `0x0003` wire format while preserving transparent read compatibility and atomic upgrades for `0x0002` archives:

### 1. Quarantine Legacy `0x0002` `STATIC_CORE_*` in `LcaV2LegacyCodec`
- All `STATIC_CORE_*` arrays, static NBT byte-slice caches, static `v3` light caches, and legacy `0x0002` Slab-Local Micro-Palette / `ChunkPatchTail` codecs **shall** reside exclusively in package-private `LcaV2LegacyCodec`.
- `LcaRegionWriter`, `LcaStreamingNbtEncoder`, `LcaSectionCodec`, and `LcaV3Codec` **shall not** emit or consult any `STATIC_CORE_*` table on any `formatVersion == 0x0003` write or read path.

### 2. Factored `0x0003` Layer 2 Region Shared Dictionary (`0x2040`)
- Each `0x0003` Layer 2 dictionary segment (`Root` or `Continuation`) stores four factored, append-only tables:
  - `BaseBlock[0..B-1]` (`VarInt utf8ByteLen` + full namespaced UTF-8)
  - `PropTuple[0..P-1]` (`VarInt utf8ByteLen` + canonical `"[k1=v1,...]"`; must start with `'['` and end with `']'` under `CBIT T5`)
  - `BlockState[0..S-1]` (`VarInt baseBlockId`, `VarInt propTupleIdPlusOne`, `uint32 materialPacked`; causal bounds `0 <= baseBlockId < totalBaseBlocksSoFar` and `0 <= propTupleIdPlusOne <= totalPropTuplesSoFar` enforced under `CBIT T5`)
  - `Biome[0..M-1]` (`VarInt utf8ByteLen` + full namespaced UTF-8 + `uint32 tint`)
- Segment Word `0` (`+0x00..+0x07`) stores `uint16 segmentStateCount`, `uint16 segmentBiomeCount`, `uint16 segmentBaseBlockCount`, and `uint16 usedPayloadBytes` (preserving `usedPayloadBytes` at `+0x06..+0x07` so two-step Word-0-first `verifyAndHealDictSegmentSecDed` remains 100% identical to `0x0002`). Fixed `uint16 segmentPropTupleCount` occupies `+0x08..+0x09` (`usedPayloadBytes == 0 || usedPayloadBytes >= 2`, `CBIT T5`).
- `LcaRegionWriter.LcaLiveArchive#registerNewBlockStateId` reserves combined slack across `BaseBlock`, `PropTuple`, and `BlockState` before mutating `DictSegmentMeta`, and `rollbackDictionaryToBookmark` rolls back all four tables atomically on write failure (`REQ-RTP-S-004`).

### 3. Pure-ID Layer 3 Micro-Palette, `ChunkPatchTail` & Dynamic Per-Region Caches
- When `hasSlabLocalPalette == 1`, `0x0003` chunks encode pure Layer 2 `VarInt` ID lists (`localStateCount`, `localStateGlobalIds[]`, `localBiomeCount`, `localBiomeGlobalIds[]`, `biomeTemplateCount`, `BiomeTemplate[]`), omitting Part B property bindings and inline UTF-8 strings. `0x0003` `ChunkPatchTail` appends pure Layer 2 `VarInt` state IDs (`appendedStateCount`, `appendedGlobalStateIds[]`).
- `hasSlabLocalPalette == 1` remains available in `0x0003` even when a region exceeds `8,192` Layer 2 states.
- `LcaSectionCodec.ParsedHeaderAndDict` lazily pre-bakes per-region NBT byte slices for `BaseBlock` (`Name`/`id`) and `PropTuple` (`Properties`/`properties`) separately and concatenates them per `BlockState` (`stateNbtBytesAt`), plus `biomeStringNbtBytes`, `singleBiomeCompoundNbtBytes`, `statePackedLightV1`, and dynamic `airStateId`. Every vanilla, future, and modded block shares the identical zero-allocation decode fast path, and `readChunkView` / terrain probes resolve `baseBlockTable[stateBaseBlockIds[id]]` in $O(1)$ without string parsing.

### 4. Dominant-Pinned Adjacency-Ordered Local Gray Bitplanes (`SEC_EXT_GRAY_BITPLANES = 0x02`)
- `LcaV3Codec` allocates `SEC_EXT_GRAY_BITPLANES = 0x02` inside the `v3` `paletteLen == 0` section extension escape (mutually exclusive with `SEC_EXT_RANK_EXCLUDED = 0x01`; bits `7..2` reserved under `CBIT T5`) and `FEATURE_GRAY_BITPLANES = 1 << 4`.
- Dominant `palette[0]` stays pinned so `octreeOccupancyMask64` empty-octant skipping and Tier-3 dominant-stratum recovery remain intact; minority states `1 .. |P|-1` (`|P| <= 64`) are greedily chained by 3D curve adjacency transition frequency using scratch arrays in `V3Scratch` (`0` per-section heap allocations).
- Active-domain voxels are mapped through Reflected Binary Gray (`g = p ^ (p >>> 1)`) and encoded as `b = ceil(log2(|P|))` tiered bitplanes (`00=Const0`, `01=TieredSubHdr/RLE/CrossPlaneXOR`, `11=RawPackedBits`, `10=Forbidden -> CBIT T5`) preceded by `VarInt payloadByteLen` so `LcaSectionCodec#skipSectionFrame` skips `0x02` sections in $O(1)$ (`REQ-RTP-S-005`).
- `LcaV3Codec#decodeGrayBitplaneBlocks` enforces `0 <= invGray(g) < paletteLen` (`CBIT T6`) and mandatory `decodedOctreeMask64 == expectedOctreeMask64` (`CBIT T6b`, quarantining to `palette[0]` under Tier-3 recovery).
- Candidate selection in `LcaSectionCodec#encodeSection` is strictly gated on complete framed byte size and, when `modeBLitLen != null`, `LcaV3Codec#estimateHuffmanBitCost` against `Delta-Roaring`, `RankExcl`, and `FlatBitPack`.

### 5. Atomic `0x0002 -> 0x0003` Upgrade & Zero-Copy `LeafAnvil` Wire Integration
- Read-only `LcaRegionReader` methods read `0x0002` archives losslessly via `LcaV2LegacyCodec` without mutating files on disk.
- Writable open (`LcaRegionWriter#openLiveArchive`, `LcaRegionFileStorage#getOrOpenRegion`) and world conversion/healing (`LeafAnvilWorldConverter`) invoke `LcaRegionWriter.upgradeLegacyArchiveAtomically` (sibling `.v3upgrade.tmp` + `force(true)` + `ATOMIC_MOVE` + cache invalidation, `ICD.md` §10.4, `REQ-RTP-S-004`).
- `RawChunkFrameSnapshot` carries `formatVersion`, resolves `0x0003` pure-ID micro-palette runtime IDs in $O(1)$ directly from `dictIdToRuntimeId` with zero string lookups, rewrites `paletteLen == 0` (`secExtFlags` `0x01`/`0x02`) section headers without unpacking voxels, and provides `isV3ExtendedSectionFrame` / `downgradeV3BlockSectionFrameToV2`.
- `LcaWireStreamProtocol` allocates `CAP_LCA_V3_SECTIONS = 1 << 6`, forwarding `0x01`/`0x02`/Eikonal slices zero-copy to `v3` clients while automatically downgrading `secExtFlags` block slices (and failing closed on undecodable Eikonal slices) for legacy clients.

## Empirical Evidence & Feature-Gate Decision

Measured via `LcaV3FormatAndBenchmarkTest` (`overworld_mountain`, 64 real chunks) and `LcaAdr005EntropyPerfTradeoffTest` (`evaluateGrayBitplanesModeAModeBTradeoff`, 64 real `1_21_R1` Overworld chunks + 16 stratified multi-material modded structure chunks, `L_inf = 0` verified across all chunks):

### A. Real `overworld_mountain` (64 Chunks, Warmed `LcaV3FormatAndBenchmarkTest`)

| Variant | Archive Size (B) | vs `v2` | Save (`us/chunk`) | RawNBT Rd (`us/chunk`) | View Rd (`us/chunk`) |
| :--- | ---: | ---: | ---: | ---: | ---: |
| `v2 Mode A (STATIC_CORE)` | `290,824` | `baseline` | `78.56` | `36.32` | `15.49` |
| `v3 Default Mode A (Factored + Pure-ID)` | `288,776` | **`-0.70%`** | **`75.34`** | **`33.03`** | **`12.87`** |
| `v2 Mode B (LCD2 + STATIC_CORE)` | `180,984` | `baseline` | `351.25` | `64.08` | `49.94` |
| `v3 Default Mode B (Factored + Pure-ID)` | `172,792` | **`-4.53%`** | `424.99` | **`56.34`** | **`47.00`** |

Removing `STATIC_CORE_*` completely and switching to factored Layer 2 + pure-ID Layer 3 micro-palettes **improves both archive size and read latency** in both Mode A and Mode B (`-0.70%` / `-4.53%` smaller; `RawNBT Rd` `-9.1%` / `-12.1%` faster; `View Rd` `-16.9%` / `-5.9%` faster).

### B. `FEATURE_GRAY_BITPLANES` (`SEC_EXT_GRAY_BITPLANES = 0x02`) A/B Trade-Off (`LcaAdr005EntropyPerfTradeoffTest`)

| Corpus | Variant | Archive Size (B) | vs `v2` | CoW Save (`us/chunk`) | RawNBT Rd (`us/chunk`) | View Rd (`us/chunk`) |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: |
| **Synthetic Stratified Structure** (16 chunks, 16 materials incl. `create:*`) | `v2 Mode A (STATIC_CORE)` | `100,616` | `baseline` | `204.24` | `46.50` | `16.43` |
| | `v3 Base Mode A (Factored+PureID)` | `96,520` | `-4.07%` | `197.49` | `45.32` | `15.56` |
| | **`v3+Gray Mode A (SEC_EXT_0x02)`** | **`59,656`** | **`-40.71%`** | `248.10` | `86.44` | `40.48` |
| | `v2 Mode B (STATIC_CORE)` | `28,680` | `baseline` | `789.84` | `124.22` | `60.20` |
| | `v3 Base Mode B (Factored+PureID)` | `28,680` | `+0.00%` | `412.22` | `135.43` | `51.65` |
| | `v3+Gray Mode B (SEC_EXT_0x02)` | `28,680` | `+0.00%` | `415.72` | `69.92` | `46.07` |
| **Real Overworld `1_21_R1`** (64 chunks) | `v2 Mode A` | `1,303,304` | `baseline` | `238.15` | `155.81` | `116.57` |
| | `v3 Base Mode A` | `1,297,672` | `-0.43%` | `207.28` | `163.53` | `129.01` |
| | `v3+Gray Mode A` | `1,288,712` | `-1.12%` | `308.60` | `189.26` | `130.44` |
| | `v2 Mode B` | `723,208` | `baseline` | `1216.92` | `253.83` | `200.62` |
| | `v3 Base Mode B` | `674,824` | `-6.69%` | `1162.87` | `234.48` | `215.51` |
| | `v3+Gray Mode B` | `677,640` | `-6.30%` | `1187.23` | `242.49` | `191.50` |

**Default Enablement Ruling (`anvil-api-ADR-005` Policy):**
- `FEATURE_GRAY_BITPLANES` achieves a massive **`-40.71%`** Mode A reduction (`-38.2%` vs `v3 Base`) on smoothly stratified multi-material structures/schematics and `-1.12%` on natural Mode A terrain, **but** adds `+48.9%` trial-encode overhead on natural Mode A terrain and `+0.42%` in natural Mode B (`677,640 B` vs `674,824 B`).
- Therefore, **`FEATURE_GRAY_BITPLANES` remains opt-in** (not enabled in `DEFAULT_V3_FEATURES`, matching `FEATURE_RANK_EXCL` in `anvil-api-ADR-004`/`005`) for archival/structure/schematic Mode A compression, while all `0x0003` decoders support `SEC_EXT_GRAY_BITPLANES = 0x02` unconditionally.

## Consequences

- **Positive:**
  - `.lca` `0x0003` is 100% content-agnostic: vanilla, future Minecraft versions, and arbitrary modded packs share identical `1-byte` `VarInt` local IDs, $O(1)$ base-block lookups, and dynamic pre-baked NBT caches.
  - Zero `STATIC_CORE_*` references remain in any `0x0003` encoder or decoder; legacy `0x0002` support is cleanly quarantined in `LcaV2LegacyCodec`.
  - Existing `0x0002` worlds read seamlessly without mutation and upgrade atomically on writable open or world conversion/healing.
  - Stratified builds and schematics gain an optional `-38%..41%` Mode A local Gray-bitplane voxel mode that is strictly independent of global dictionary ordering.
- **Negative / Trade-Offs:**
  - Rust `lca-format` parity for the `0x0003` factored Layer 2 dictionary, pure-ID micro-palette, and `SEC_EXT_GRAY_BITPLANES = 0x02` remains part of the pending `0x0003` Rust lockstep track noted in `anvil-api-ADR-004`.
  - Pre-refactor `0x0003` development scratch files written before this ADR fail closed (`CBIT T5`) when parsed as factored Layer 2 (production `.lca` archives are `0x0002`, which read and upgrade cleanly).

## Verification

- `:anvil-api`: `LcaRegionCodecTest` (factored Layer 2 continuation segments, `SEC-DED` healing, `DED`/`CBIT T5` rejection, 4-table bookmark rollback, modded dynamic NBT cache equivalence for legacy & modern `DataVersion >= 5006`, `> 8,192`-state micro-palettes + pure-ID `ChunkPatchTail`, non-mutating `0x0002` read compatibility), `LcaV3FormatAndBenchmarkTest` (`SEC_EXT_GRAY_BITPLANES` 64-case direct round-trip across palettes `2..64` & 4 curves, $O(1)$ `skipSectionFrame`, Tier-3 recovery to `palette[0]`, `CBIT T5/T6/T6b` faults), `LcaAdr005EntropyPerfTradeoffTest` (Mode A/Mode B A/B benchmark), `LcaRegionFileStorageTest` (atomic `0x0002 -> 0x0003` upgrade, `RawChunkFrameSnapshot` $O(1)$ pure-ID runtime resolution & `secExtFlags` header rewriting).
- `:resource:LeafAnvil:leafanvil-common`: `LeafAnvilInterceptorAndConverterTest` (`LeafAnvilWorldConverter` atomic `0x0002 -> 0x0003` upgrade in `convertWorldToLca` and `healAndVerifyWorld`, `LcaWireStreamProtocol` `CAP_LCA_V3_SECTIONS` negotiation and automatic `v2` downgrade for legacy clients).
