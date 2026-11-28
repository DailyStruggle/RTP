# anvil-api-ADR-004 - Format Version `0x0003` (ICD v3): Overlaid Eikonal Light Residuals, Columnar Tick/BlockEntity Tails & Self-Describing Adaptive Rank-Exclusion

**Status:** Implemented (Java `:anvil-api` `0x0003` + `ICD.md` `LCA-ICD-3.0`; Rust `lca-format` lockstep pending)
**Date:** 2026-11-20 *(Originally authored 2026-11-19)*
**Extends:** [anvil-api-ADR-003](anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md) (`.lca` Binary Specification: 3D Hilbert, Adaptive Delta-Roaring, Gray-Code Light & Octree Streaming)
**Amended By:** [anvil-api-ADR-006](anvil-api-ADR-006-factored-layer2-pure-id-palettes-and-local-gray-bitplanes.md) (Factored `0x0003` Layer 2 Dictionary, Pure-ID Layer 3 Micro-Palettes, Legacy `v2` Quarantine & `SEC_EXT_GRAY_BITPLANES = 0x02`)
**Related:** [ICD.md](../ICD.md) (`LCA-ICD-1.0` -> `LCA-ICD-3.0`), [anvil-api-ADR-001](anvil-api-ADR-001-anvil-subsystem.md) (Anvil Read-Only Subsystem), [anvil-api-ADR-002](anvil-api-ADR-002-multi-format-region-support.md) (Multi-Format Region Support), [leafanvil-ADR-001](../../../../resource/LeafAnvil/docs/adr/leafanvil-ADR-001-Multi-Loader-lca-world-storage-and-bytecode-interceptor.md) (`LeafAnvil` Storage Carrier), [leafanvil-ADR-002](../../../../resource/LeafAnvil/docs/adr/leafanvil-ADR-002-in-memory-lca-chunk-representation-and-dirty-overlay-compaction.md) (In-Memory `LcaSectionBacking`), [leafanvil-ADR-003](../../../../resource/LeafAnvil/docs/adr/leafanvil-ADR-003-negotiated-lca-chunk-network-streaming-and-delta-sync.md) (`LCA_NET_V1` / `LcaOrderlessDictTable`), [leafanvil-ADR-004](../../../../resource/LeafAnvil/docs/adr/leafanvil-ADR-004-pumpkinmc-rust-lca-direct-section-bridge-and-golden-fixtures.md) (PumpkinMC Rust `lca-format` & Five-Axis Contract), [leafanvil-ADR-005](../../../../resource/LeafAnvil/docs/adr/leafanvil-ADR-005-dual-mode-javaagent-direct-section-bridge-and-netty-wire-pipeline.md) (`LcaStreamingNbtEncoder`), [leafmap-ADR-001](../../../../resource/LeafMap/docs/adr/leafmap-ADR-001-zero-mesh-hca-3d-web-map-and-block-feed.md) (`MAP_TILE` WebGL2/WebGPU Streaming), [ADR-115](../../../../docs/adr/ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md)

## Context

Empirical byte-anatomy and serialized-roundtrip (`L_inf = 0`) studies in `LcaLayeredPropertyEntropyProofTest` (`Zero Zstd` everywhere) evaluated candidate overlaid `.lca` compression layers across three distinct corpora:
1. **Natural World Regions (`26.3` Overworld):** Uniformly strided multi-region sweeps (`12` to `24` regions across `~385` overworld `.lca` files, `48.5M+` voxels).
2. **Structure Templates (`datapacks/leaf-worldgen/**/*.nbt`):** All `17` structure `.nbt` files (`758,147` non-void voxels across `japanese_temple.nbt`, `cinderfall_cone.nbt`, and `15` procedural canopy tree templates).
3. **Schematic Builds (`scripts/schematics/*.{schem,litematic}`):** All `14` architectural, storage, and redstone farm builds (`main-storage-hall.litematic`, `trade-hall-large.litematic`, `super-smelter-factory.litematic`, `autoloading-super-smelter.schem`, `iron-farm-quad.schem`, etc.).

The empirical audit exposed three structural bottlenecks in `LCA-ICD-1.0` (`formatVersion = 0x0002`)—and proved why adding them safely requires a **`formatVersion = 0x0003` (`ICD v3`)** wire specification update rather than retrofitting `0x0002`:

1. **Standalone `SkyLight` & `BlockLight` Gray Bitplanes Re-Encode Deterministic Block Consequences:**
   - In `SectionFrame`, `BlockStates` are decoded strictly before `SkyLight` and `BlockLight`, so the decoder already possesses every voxel's light opacity and emission class. Encoding raw `4-bit` light nibbles as standalone Gray bitplanes re-transmits deterministic 6-neighbor Manhattan (`eikonal`) light decay in every lit section.
   - **`0x0002` compatibility constraint:** Under `LCA-ICD-1.0` Section 7.4, `planeModes8` mode `10` (`2`) is a valid `4|4` Delta-Run stream actively parsed by both `LcaSectionCodec#decodeGrayPlanes` / `LcaSectionCodec#skipLightChannel` and Rust `lca-format`. Reclaiming `mode 10` as a block-conditioned light sentinel requires `formatVersion >= 0x0003`.
2. **Uncompressed `block_ticks`, `fluid_ticks` & Repetitive `block_entities` Dominate `Mode B` Residual Tails (`55.52%` of Uncompressed `ChunkFrame` Bytes):**
   - `LcaStreamingNbtEncoder#buildCompactResidualTail` already cross-delta-encodes secondary `Heightmaps` against `MOTION_BLOCKING_NO_LEAVES`, **but** disables `DEFLATE` on `residualNbtBytes` whenever `Mode B` (`LCD2`) is active (`allowDeflateResidual = !effectiveModeB`, `ICD.md` §6.2.2).
   - Across `24` uniformly sampled `26.3` overworld regions (`680` chunks), pending `block_ticks`/`fluid_ticks` (`present in 46.62%` of chunks, particularly in pre-generated regions) and `block_entities` (`present in 12.94%` of world chunks and dominating storage/redstone schematics at `350,791 B` raw NBT vs. `49,207 B` of voxels across `14` schematics) pass verbatim as Mojang `CompoundTag` lists into `LCD2`, where a `1 KiB` static region dictionary cannot match long runs of varying coordinates and tick timestamps.
   - **`boilerplateFlags16` v3 extension constraint:** `ICD.md` Section 8.2 already allocates bits `0..14` (`0x0001..0x4000`), leaving only **`Bit 15` (`0x8000`)** free. Furthermore, `LcaSectionCodec#locateAndDecodeChunkPatchTail` skips bits `13, 5..9` to reach `Bit 14` (`ChunkPatchTail`) in $O(1)$ without touching NBT, while `Bit 10` (`residualNbtSlice`) is unprefixed to `residualRelEnd`.
3. **Redundant Domain Stepping Across Minority `Delta-Roaring` Containers (With `LCD2` Alignment & `SectionFrame` Independence Constraints):**
   - `LcaSectionCodec` encodes minority palette states $p = 1 \dots |P|-1$ independently over the same active octant domain, re-stepping over voxel positions already claimed by $p' < p$.
   - Testing `Mode B` (`LCD2`) on `trade-hall-large.litematic` proved that **unconditional** rank-exclusion regresses repeated architectural schematics (`+29.63%` in `Mode B` despite `-9.29%` in `Mode A`) because shifting domain lengths when surrounding block counts vary breaks byte-aligned `LCD2` intra-chunk back-references across repeated bays.
   - **`chunkFlags` allocation constraint:** `ICD.md` Section 6.1 allocates all `8` bits of `chunkFlags` (`bits 0..2` = `hasResidualNbt`/`hasSlabLocalPalette`/`scaleOffsets`; `bits 7..3` = `defaultBiomeCode5`). Even if a chunk bit existed, a chunk-level flag breaks self-describing `SectionFrame` / `SectionWireSlice` independence required by `LcaSectionBacking` (`leafanvil-ADR-002`), `LcaOrderlessDictTable` cross-chunk deduplication (`leafanvil-ADR-003`), and single-section CoW splicing in `LcaRegionWriter`.

## Decision

Implement the three empirically validated lossless (`L_inf = 0`, `Zero Zstd`) overlaid layers below under **`.lca` Format Version `0x0003`**, governed by an **`api/anvil-api/docs/ICD.md` Version 3 (`LCA-ICD-3.0`)** specification update, and reject standalone `SectionFrame` block-property Huffman layering (see [Alternatives Considered](#alternatives-considered)).

### 0. Wire Format Version `0x0003` (`LCA-ICD-3.0`), Compatibility & Cross-System Contract

1. **Superblock Version Bump & ICD v3 Specification:**
   - Superblock magic (`"LCA_V001"`, `0x4C43415F56303031L`) and footer canary (`"LCA1_END"`, `0x4C4341315F454E44L`) remain unchanged.
   - Superblock `formatVersion` at `0x08..0x09` **shall** increment from `0x0002` (`LCA_FORMAT_VERSION_V2`) to **`0x0003`** (`LCA_FORMAT_VERSION_V3`, Major `3`, Minor `0`).
   - Implementing this ADR **shall** update `api/anvil-api/docs/ICD.md` (and the `resource/LeafAnvil/docs/ICD.md` pointer) to **Version 3 (`LCA-ICD-3.0`, `formatVersion = 0x0003`)**, adding Section 9.4 (`LCA_LIGHT_TABLE_V1`), Section 7.2.1 (`v3` `SectionFrame` extension escape), Section 7.4.1 (`v3` Eikonal Light Residual framing), Section 8.4 (`boilerplateFlags16` bit `15` `extFlags` & columnar ticks/`block_entities`), and Section 10.4 (`0x0002` $\to$ `0x0003` upgrade procedure).
2. **Backward-Compatible Read Path & Atomic On-Disk Upgrade:**
   - Readers (`LcaRegionReader`, `LcaSectionCodec`, Rust `lca-format`) **shall** accept `formatVersion` $\in \{\mathtt{0x0002}, \mathtt{0x0003}\}$ (retaining `0x0001` upgrade support via `LcaRegionWriter.upgradeToCurrentVersion`) and reject `0x0000` or `>= 0x0004` under `CBIT T1`.
   - When reading `formatVersion <= 0x0002`, decoders **shall** preserve exact `LCA-ICD-1.0` semantics: `planeModes8` mode `10` (`2`) decodes as a `4|4` Delta-Run stream, `paletteLen == 0` fails closed (`CBIT T7`), and `boilerplateFlags16` bit `15` (`0x8000`) fails closed (`CBIT T5`).
   - `LcaRegionWriter.upgradeToCurrentVersion`, `LcaRegionWriter.openLiveArchive`, `LcaRegionFileStorage`, and `LeafAnvilWorldConverter` **shall** support atomic `0x0002` $\to$ `0x0003` region upgrades via the sibling `.tmp` + `ATOMIC_MOVE` procedure defined in `ICD.md` Section 10.3.
3. **Network Negotiation (`LCA_NET_V1`, `leafanvil-ADR-003`) & `LeafMap` (`leafmap-ADR-001`) Guard:**
   - `LcaWireStreamProtocol` **shall** allocate capability bit **`CAP_LCA_V3_SECTIONS = 1 << 6`** (bit `6`, following existing bits `0..5`) in `HandshakePayload.capabilityFlags` to negotiate `v3` `SectionWireSlice` payloads (eikonal light residuals and per-section rank-exclusion) without bumping `PROTOCOL_VERSION`. Clients that do not advertise `CAP_LCA_V3_SECTIONS` **shall** continue receiving `v2`-compatible `SectionWireSlice` frames.
   - Archives with `storageKind == 3` (`MAP_TILE`, `leafmap-ADR-001`) **shall not** emit eikonal light residual frames (or `LeafMap` ingestion **shall** decode light residuals CPU-side before GPU upload) so WebGL2/WebGPU shaders never execute iterative 4,096-voxel BFS per section.

### 1. Block-Conditioned Eikonal `SkyLight` & `BlockLight` Residual Coding (`LcaSectionCodec`)

1. **Frozen Normative Predictor Table (`LCA_LIGHT_TABLE_V1`, `ICD.md` §9.4):**
   - Because $r(v) = (L(v) - \hat{L}(v)) \bmod 16$ is lossless (`L_inf = 0`) if and only if encoder and decoder compute the **identical** $\hat{L}(v)$, the opacity $\kappa(v) \in \{0, 1, 15\}$ and emission $e(v) \in [0..15]$ classifier is normative wire state—not an implementation heuristic.
   - `ICD.md` Section 9.4 **shall** freeze `LCA_LIGHT_TABLE_V1` (`predictorTableId3 = 0`) as an exact, cross-language deterministic lookup table over canonical block state strings (no loose `.contains(...)` wildcards that drift across Minecraft versions; explicitly classifying `minecraft:tinted_glass` as $\kappa = 15$, not `0`). Future Minecraft updates requiring a revised table **shall** allocate a new `predictorTableId3` (`1..7`) while keeping `0` immutable forever.
   - `ParsedHeaderAndDict` and `SlabLocalPalette` **shall** lazily cache a packed `byte[] statePackedLightV1` array (`uint8 = (opacity4 << 4) | emission4`, `1 B` per dictionary state), computed once per dictionary entry rather than per voxel. On the direct-to-NBT hot path (`LcaSectionCodec#encodeChunkFrameToNbtBytes`), when a section carries a `v3` eikonal light sentinel, the decoder resolves `palPackedLight[0..|P|-1]` from `statePackedLightV1[secPalIds[p]]` in $O(|P|)$ and unpacks `yzxPaletteIdx[4096]` from `packedBlockLongs` solely for that section (`0` extra work on baseline, constant, or absent light sections).
2. **Deterministic Bounded Fixed-Point BFS (Zero Queue Overflow, Zero Heap Churn):**
   - Naive FIFO relaxation can re-enqueue a voxel when a stronger light path arrives later, overflowing an unguarded `int[4096]` queue. Both `BlockLight` and `SkyLight` predictors **shall** use a circular `int[4096]` ring buffer paired with a `long[64]` `inQueue` deduplication bitset (`512 B`)—or an equivalent 16-bucket Dial level queue ($L = 15 \to 1$)—allocated inside the thread-local `AnvilIoPool` scratch arena (`byte[4096] hatL`, `int[4096] ring`, `long[64] inQueue`, `byte[4096] palPackedLight`).
   - Because each voxel can appear in the ring at most once at any instant, queue occupancy is mathematically bounded to $\le 4,096$. Because edge attenuations are non-negative ($\max(1, \kappa(u)) \ge 1$ for 6-neighbor steps; downward sky attenuation $\kappa(u) \ge 0$ along a strict DAG $y = 15 \to 0$) over the finite lattice $[0, 15]^{4096}$, relaxation converges to a **unique maximum fixed point** $\hat{L}$ independent of queue order across Java, Rust, and JS/WASM.
   - **`BlockLight` Predictor:** Seeds all voxels with $e(v) > 0$ at $\hat{L}(v) = e(v)$ and relaxes 6-connected in-section neighbors via $\hat{L}(u) = \max(\hat{L}(u), \hat{L}(v) - \max(1, \kappa(u)))$.
   - **`SkyLight` Predictor:** Evaluates two deterministic top-face (`y = 15`) hypotheses: (a) open-sky ($\hat{S}(x, 15, z) = 15$ where $\kappa = 0$, else `0`), or (b) explicit `16x16` top-face nibble seed when overhead sections cast partial shadows. Performs one top-down vertical pass (`y = 15 -> 0` with step attenuation `0` when $\kappa = 0$, else $\kappa$), then seeds voxels with $\hat{S}(v) > 1$ into the bounded `int[4096]` ring + `long[64]` `inQueue` bitset for 6-connected relaxation.
3. **Zero-Tax `v3` Wire Framing & Hard $O(1)$ Skip Invariant (`REQ-RTP-S-005`):**
   - In `formatVersion >= 0x0003`, legacy `planeModes8` `mode 10` (`2`) is retired (superseded by `mode 01` tiered `subHdr`, which already provides `4|4`, `6|2`, single-run, and cross-plane XOR). Setting `(firstByte & 0x03) == 2` on a `skyLightMode == 10` or `blockLightMode == 10` channel unambiguously identifies a `v3` Block-Conditioned Light Residual frame with **`0` extra bits on baseline `GrayPlanes` channels**:
     ```text
     uint8   sentinelHdr:
               bits 1..0 = 10  (0x02: v3 Block-Conditioned Light Residual sentinel; valid iff formatVersion >= 0x0003)
               bits 3..2 = predictorSubMode:
                             00 (0): In-section BlockLight eikonal (BlockLight channel only; CBIT T5 on SkyLight)
                             01 (1): Open-sky SkyLight eikonal (SkyLight channel only; CBIT T5 on BlockLight)
                             10 (2): Seeded top-face SkyLight eikonal (SkyLight channel only; CBIT T5 on BlockLight)
                             11 (3): Reserved (fails closed CBIT T5)
               bits 6..4 = predictorTableId3 (0 = LCA_LIGHT_TABLE_V1; 1..7 reserved -> CBIT T5)
               bit  7    = topSeedMode (when predictorSubMode == 2: 0 = 256-voxel 4-bitplane Gray-RLE top-face seed,
                                        1 = raw 128 B packed 4-bit nibbles in x + (z << 4) order;
                                        must be 0 when predictorSubMode != 2 -> CBIT T5)
     VarInt  payloadByteLen          // Exact byte length of [optional topFaceSeed + residualPlaneModes8 + residualPlanePayloads]
     [If predictorSubMode == 2]:
             topFaceSeed             // 128 B raw nibbles (topSeedMode == 1) or 256-entry Gray-RLE (topSeedMode == 0)
     uint8   residualPlaneModes8     // Standard v3 Gray planeModes8 (modes 00, 01, 11; mode 10 forbidden -> CBIT T5)
     byte[]  residualPlanePayloads   // Encoded modular nibble residual r(v) = (L(v) - L_hat(v)) & 0x0F in channel curve order
     ```
   - The encoder **shall** compare the complete framed byte length (`1 + varIntLen(payloadByteLen) + payloadByteLen`) against baseline standalone `GrayPlanes` and emit whichever is strictly smaller (`0-byte` passthrough tax on baseline sections).
   - **Hard Skip Invariant (`REQ-RTP-S-005`):** `LcaSectionCodec#skipLightChannel` and `LcaSectionCodec#skipSectionFrame` **shall** read `payloadByteLen`, validate `payloadByteLen >= 1 && pos + payloadByteLen <= limit` (`CBIT T5`), and advance `pos += payloadByteLen` in $O(1)$ without executing eikonal propagation or decoding `BlockStates`.

### 2. Columnar `block_ticks` / `fluid_ticks` & Template-Deduplicated `block_entities` in `ResidualTail` (`LcaStreamingNbtEncoder` & `LcaSectionCodec`)

1. **Exact `boilerplateFlags16` Bit `15` (`0x8000`) Placement (`ICD.md` §8.2 & §8.4):**
   - Allocate **`boilerplateFlags16` Bit `15` (`0x8000`) = `HAS_EXTENDED_FLAGS`** (valid iff `formatVersion >= 0x0003`; rejected `CBIT T5` in `<= 0x0002`).
   - Place the `extFlags` header and its length-prefixed columnar streams **strictly after `Bit 14` (`ChunkPatchTail`) and strictly before `Bit 10` (`residualNbtSlice`)**:
     ```text
     [uint16 boilerplateFlags16]
     [... bits 13, 5, 6, 7, 8, 9, 14 (ChunkPatchTail) unchanged per LCA-ICD-1.0 Section 8 ...]
     [If bit 15 (0x8000):
         VarInt extFlags:
           bit 0 (0x01): HAS_COLUMNAR_BLOCK_TICKS      -> VarInt byteLen + ColumnarTickStream
           bit 1 (0x02): HAS_COLUMNAR_FLUID_TICKS      -> VarInt byteLen + ColumnarTickStream
           bit 2 (0x04): HAS_COLUMNAR_BLOCK_ENTITIES   -> VarInt byteLen + ColumnarBlockEntitiesStream
           bit 3 (0x08): HAS_SPARSE_POST_PROCESSING    -> VarInt byteLen + SparsePostProcessingStream
           bit 4 (0x10): HAS_DICT_STRUCTURES           -> VarInt byteLen + DictStructuresStream
           bits 5..31:   Reserved (non-zero fails closed CBIT T5)
     ]
     [If bit 10 (0x0400): byte[] residualNbtSlice (unprefixed to residualRelEnd)]
     ```
   - **Invariant Preservation:** Placing `Bit 15` after `Bit 14` leaves `LcaSectionCodec#locateAndDecodeChunkPatchTail` $O(1)$ prefix-skipping untouched, keeps `Bit 10` unambiguously delimited as `[pos .. residualRelEnd)`, and enables Tier 2 bit-rot reverse-scan (`ICD.md` §10.2) to skip all five extended streams in $O(1)$ via their `VarInt byteLen` prefixes.
   - **Mutual Exclusion (`CBIT T5`):** Decoders **shall** reject frames where both an empty-list/structure bit in `boilerplateFlags16` and its corresponding `extFlags` bit are set (`bit 1` vs `extFlags bit 0`, `bit 2` vs `extFlags bit 1`, `bit 0` vs `extFlags bit 2`, `bit 5` vs `extFlags bit 3`, `bit 4` vs `extFlags bit 4`).
2. **Columnar Scheduled-Tick Stream (`block_ticks` & `fluid_ticks`) with Strict Schema Fallback:**
   - **Strict Vanilla-Schema Validation (`L_inf = 0`):** A tick list **shall** be extracted into `ColumnarTickStream` **if and only if** it is non-empty (`1 <= tickCount <= MAX_TICKS_PER_CHUNK`, cap `65,536` `CBIT T4`) and **every** element is a `TAG_Compound` containing the exact 6 vanilla keys with exact tag types `{i: TAG_String, p: TAG_Int, t: TAG_Int, x: TAG_Int, y: TAG_Int, z: TAG_Int}`. If any element carries extra modded keys or non-`TAG_Int` types (e.g., `TAG_Long`), the encoder **shall** leave that entire list in `Bit 10` (`residualNbtSlice`) untouched.
   - **Overflow-Safe 64-Bit Deltas:** All coordinate and tick deltas (`dx`, `dy`, `dz`, `dt`) **shall** compute 64-bit signed differences `(long) a - (long) b` encoded as unsigned LEB128 **`ZigZagVarLong`** (`(d << 1) ^ (d >> 63)`), eliminating 32-bit signed wraparound near `±2^30` while preserving 1-byte encoding for deltas in `[-64, +63]`.
   - **Exact Wire Layout (`ColumnarTickStream`):**
     - `VarInt tickCount`, `uint8 tickFlags` (`bit 0` = `allInChunkXZ`, `bit 1` = `allPriorityZero`, `bit 2` = `idUseSmallHuffman` [valid iff `2 <= uniqueIdCount <= 64`], `bits 7..3` = `0` -> `CBIT T5`).
     - `VarInt uniqueIdCount` (`1..tickCount`), followed by `uniqueIdCount` strings encoded as `VarInt ((utf8Len << 1) | isMinecraftPrefix)` + `byte[utf8Len]` (stripping `"minecraft:"` when `isMinecraftPrefix == 1`).
     - `idOrd[tickCount]`: omitted (`0 B`) when `uniqueIdCount == 1`; canonical small-alphabet Huffman bitstream when `idUseSmallHuffman == 1`; else `VarInt[tickCount]`.
     - `xz`: `uint8[tickCount]` `((x - (cx << 4)) << 4) | (z - (cz << 4))` when `allInChunkXZ == 1`; else interleaved `ZigZagVarLong(x - (cx << 4)), ZigZagVarLong(z - (cz << 4))`.
     - `deltaY[tickCount]`: `ZigZagVarLong(y[k] - prevY)` (`prevY = 0` initially).
     - `deltaT[tickCount]`: `ZigZagVarLong(t[k] - prevT)` (`prevT = 0` initially).
     - `priority[tickCount]`: omitted when `allPriorityZero == 1`; else `ZigZagVarInt(p[k])`.
3. **Chunk-Local Template-Deduplicated `block_entities` Stream:**
   - Extracts non-empty `block_entities` lists (`1 <= beCount <= MAX_BLOCK_ENTITIES_PER_CHUNK`, cap `16,384` `CBIT T4`) where every element is a `TAG_Compound` (falling back per-list to `Bit 10` otherwise):
     - Strips coordinate fields per element based on detected coordinate schema (`coordType2`: `0` = `TAG_Int x, y, z`, `1` = `TAG_Int_Array Pos[3]`, `2` = none / non-int coordinates left verbatim inside the template) and deduplicates the remaining raw Mojang `TAG_Compound` bytes into a chunk-local template table `templates[0..T-1]`.
     - **Exact Wire Layout (`ColumnarBlockEntitiesStream`):**
       - `VarInt beCount`, `uint8 beFlags` (`bit 0` = `allInChunkXYZ` when every element has `coordType2 == 0` and `(x, z)` inside `[cx << 4 .. (cx << 4) + 15]`, `bits 7..1` = `0` -> `CBIT T5`), `VarInt templateCount` (`1..beCount`).
       - `templates[0..T-1]`: `[VarInt templateLen + byte[templateLen] rawTemplateCompoundNbt]`.
       - Per-instance entries (`k = 0..beCount-1`):
         - If `allInChunkXYZ == 1`: `[VarInt templateOrd if T > 1]`, `uint8 packedXZ8`, `ZigZagVarLong(y[k] - prevY)` (`prevY = 0`).
         - Else: `VarInt ((templateOrd << 2) | coordType2)`; if `coordType2 != 2`, `ZigZagVarLong(x[k] - prevX)`, `ZigZagVarLong(y[k] - prevY)`, `ZigZagVarLong(z[k] - prevZ)` (`prevX = prevY = prevZ = 0`).
   - **Semantic NBT Parity & Zero-DOM Streaming Save (`leafanvil-ADR-005`):** Reconstructed `CompoundTag` entries preserve exact semantic Mojang NBT equality (identical keys, tag types, and values; NBT compound key iteration order is unordered by specification). `LcaStreamingNbtEncoder` **shall** extract `block_ticks`, `fluid_ticks`, and `block_entities` directly from its single-pass binary NBT `ByteBuffer` scanner into `AnvilIoPool` scratch buffers without inflating a `LinkedHashMap` DOM tree on chunk save.

### 3. Self-Describing Per-`SectionFrame` Adaptive Successive Rank-Exclusion `Delta-Roaring` (`LcaSectionCodec`)

1. **Self-Describing Per-`SectionFrame` Signaling (`paletteLen == 0` Escape, `0 B` Tax on Baseline):**
   - Replaces the infeasible `chunkFlags` bit with a per-`SectionFrame` extension prefix valid iff `formatVersion >= 0x0003` and `blockEncodingMode` $\in \{\mathtt{01}, \mathtt{11}\}$:
     ```text
     VarInt  paletteLenOrEscape:
               1..4096 : Baseline v2/v3 Delta-Roaring section (0 B overhead, byte-identical to v2)
               0       : v3 SectionFrame extension prefix (fails closed CBIT T7 in formatVersion <= 0x0002):
                           uint8   secExtFlags  // bit 0 (0x01) = RANK_EXCLUDED; bits 7..1 reserved (0 -> CBIT T5)
                           VarInt  paletteLen   // Actual |P| in 2..4096 (CBIT T7 if < 2 or > 4096)
     ```
   - Keeps every `SectionFrame` and `SectionWireSlice` 100% self-describing across `LcaSectionBacking` (`leafanvil-ADR-002`), `LcaOrderlessDictTable` cross-chunk deduplication (`leafanvil-ADR-003`), single-section CoW splicing in `LcaRegionWriter`, and `LCA_NET_V1` (`CAP_LCA_V3_SECTIONS`).
2. **Rank-Compacted Minority Domain & `CBIT T6b` Parity:**
   - When `(secExtFlags & 0x01) != 0` (`RANK_EXCLUDED`), minority states $p = 1 \dots |P|-1$ (ordered by descending voxel count after dominant `palette[0]`) **shall** encode `TAG_SINGLETON`, `TAG_DELTA_RUN`, `TAG_DELTA_ARRAY`, or `TAG_BITMAP` over the successively compacted unclaimed index domain $D_p = D_{p-1} \setminus S_{p-1}$ (`|D_1| = N_active`, $|D_{p+1}| = |D_p| - |S_p|$), using existing `Bounded240` gaps (`< 240` in `1 B`, `0xF0 | hi4, lo8` in `2 B` since $|D_p| \le 4,096$).
   - **Mandatory `CBIT T6b` Check:** Rank-excluded decoding **shall** accumulate `decodedOctreeMask64 |= (1L << YZX_TO_OCTANT6[yzx])` for every decoded minority voxel and verify `decodedOctreeMask64 == octreeOccupancyMask64` (`CBIT T6b`), quarantining the section to `palette[0]` under Tier 3 recovery on mismatch.
   - `LcaSectionCodec#skipSectionFrame` **shall** remain $O(|P|)$ container-header jumps (containers are identically self-delimited without reconstructing `unclaimed`).
3. **Bounded Cardinality, `AtomicDictState#rollbackTo` & `Mode B (LCD2)` Non-Regression Gate:**
   - **Cardinality Cap:** To bound $O(|P| \cdot N_{\text{active}})$ compaction cost on high-cardinality build sections, the encoder **shall** evaluate rank-exclusion only when `3 <= |P| <= 64` (skipping `|P| <= 2` where rank-exclusion is a no-op and high-cardinality sections where `FlatBitPack` or baseline dominates) and only retain a rank-excluded section candidate when `rankExclSectionBytes + 2 < baselineSectionBytes`.
   - **Mode A (`0-LZ`):** Select rank-exclusion per section monotonically.
   - **Mode B (`LCD2`) Safe A/B Gate:** Whenever at least one section in the chunk qualifies, the encoder **shall** trial-encode baseline vs. rank-excluded chunk frames using `mutateDict = false` (or bookmarking `int mark = dict.usedBytes` and calling `AtomicDictState#rollbackTo(mark)`) so the rejected candidate **never mutates or pollutes** the region's shared `AtomicDictState` LZ arena; only the strictly smaller winner commits with `mutateDict = true`.
   - **Live `CoW` Save Latency Policy (`leafanvil-ADR-004` Axis 5):** Offline region conversion/compaction (`lcd2-level >= 2`) runs the full `Mode B` A/B trial; live tick-adjacent single-chunk `CoW` saves **may** skip double-`LCD2` trial-encoding (emitting baseline `Delta-Roaring`) if Gate 1 Five-Axis benchmarks show double-`LCD2` exceeds the Axis 5 dirty-save budget.

## Alternatives Considered

| Alternative | Why Rejected |
| :--- | :--- |
| **Standalone `SectionFrame` Block-Property Huffman Layering** | Splitting `(baseBlock, propSuffix)` into base-material `Delta-Roaring` + masked property/scatter Huffman streams saved `-13.66%` inside `*_leaves` canopy sections (`5.3%` of non-uniform world sections), but diluted to `-1.24%` across all world sections in `Mode B`, `-0.02%` on `15` datapack tree `.nbt`s (uniform `persistent=true` before ticking), regressed repeated schematics when ungated, and increased section encode latency `2.4x` (`304 us` vs. `126 us`). |
| **Overloading `formatVersion = 0x0002` (`planeModes8` mode `2` & shrinking `defaultBiomeCode5`)** | Breaks backward compatibility with `LCA-ICD-1.0` Section 7.4 (where mode `2` is a valid `4|4` Delta-Run stream actively parsed in Java and Rust) and corrupts `0x0002` chunks with `defaultBiomeDictId >= 15` (`chunkFlags` has zero spare bits). |
| **Chunk-Level `chunkFlags` Bit for Rank-Exclusion** | Even if moved to another chunk header byte, a chunk-level flag violates self-describing `SectionFrame` / `SectionWireSlice` independence required by `LcaSectionBacking` (`leafanvil-ADR-002`), `LcaOrderlessDictTable` (`leafanvil-ADR-003`), and single-section CoW splicing. The `paletteLen == 0` escape (`secExtFlags`) keeps `0 B` tax on baseline sections while preserving full section independence. |
| **Re-Enabling `DEFLATE` on `residualNbtSlice` Inside `Mode B`** | Violates the single-pass `LCD2` zero-nested-Zlib invariant (`ICD.md` §6.2.2), delivers zero benefit in `Mode A` (`0-LZ`), and compresses repetitive tick/BE `CompoundTag` lists far worse than schema-aware columnar/template streams. |
| **Allowing Eikonal Light Residuals Inside `storageKind == 3` (`MAP_TILE`)** | `LeafMap` WebGL2/WebGPU shaders (`leafmap-ADR-001`) stream `SectionFrame` slices directly and cannot execute iterative 4,096-voxel BFS per section on the GPU efficiently. |

## Empirical Prototype Measurements (`LcaLayeredPropertyEntropyProofTest`, Pre-ICD-v3 Framing)

The measurements below come from the initial `LcaLayeredPropertyEntropyProofTest` proof harness (`100.00%` `L_inf = 0` roundtrip on each isolated component, `Zero Zstd`). **They are exploratory prototype numbers prior to `ICD v3` framing—not final acceptance benchmarks** (see mandatory caveats and re-benchmark gate below).

### 1. Component-Level Isolation (`26.3` Overworld, `17` `.nbt` Structures, `14` Schematics)

| Evaluated Layer & Corpus | Sample Size | Baseline `Mode A` $\to$ Revised | Baseline `Mode B (LCD2)` $\to$ Revised | Net `Mode B` Delta |
| :--- | ---: | ---: | ---: | ---: |
| **`SkyLight` Block-Conditioned Residual** (`26.3` Overworld) | `548` non-const secs | `118,783` $\to$ `32,787 B` (`-72.40%`) | `80,743` $\to$ `28,945 B` | **`-64.15%`** (`-136.3 B/chunk`) |
| **`BlockLight` Block-Conditioned Residual** (`26.3` Overworld) | `1,297` non-const secs | `223,131` $\to$ `91,218 B` (`-59.12%`) | `190,957` $\to$ `83,481 B` | **`-56.28%`** (`-282.8 B/chunk`) |
| **Columnar `Ticks` + Template `BlockEntities`** (`24` Overworld Regions) | `680` chunks | `3,523,147` $\to$ `166,196 B` (`-95.28%` ticks) | `3,261,077` $\to$ `3,087,350 B` (whole chunk) | **`-5.14%` w/ `256B` slabs** (`-255.5 B/chunk`) |
| **Adaptive `RankExcl` Block Containers** (`26.3` Overworld) | `3,043` secs (`380` chunks) | `1,224,120` $\to$ `1,129,146 B` (`-7.76%`) | `1,037,674` $\to$ `978,397 B` | **`-5.71%`** (`-156.0 B/chunk`) |
| **All `14` Schematic Builds** (`Adaptive Voxel + Template BE`) | `14` files (`99` secs) | `400,000+ B` raw $\to$ `87,058 B` | `53,329` $\to$ `37,759 B` (`-68.58%` storage hall) | **`-29.20%`** (`-76.49%` vs GZIP `.schem`) |
| **All `17` Structure Templates** (`Adaptive Voxel + Template BE`) | `17` `.nbt` files | — | `83,486` $\to$ `78,817 B` (`-11.50%` cinderfall, `-10.40%` temple) | **`-5.59%`** (`-95.80%` vs GZIP `.nbt`) |

### 2. Study 6 Whole-Chunk Prototype Estimate (`12` Regions, `252` Sampled Chunks — Subject to Re-Measurement)

| Pipeline (`Zero Zstd` Everywhere) | Prototype Total (`256B` Slabs) | `%` of `.mca` (`Ratio`) | Delta vs. `.mca` | Delta vs. Current `.lca v2` |
| :--- | ---: | ---: | ---: | ---: |
| **1. Vanilla `.mca` (`4 KiB` Sector `Zlib`, zero-hole repack)** | `2,187,264 B` | `100.00%` (`1.00x`) | `0.00%` | — |
| **2. Current `.lca v2` `0-LZ Mode A` (raw residual tail)** | `2,440,704 B` | `111.59%` (`0.90x`) | `+11.59%` | baseline `0-LZ` |
| **3. Prototype Revised `0-LZ Mode A` (zero entropy/LZ)** | `1,249,792 B` | `57.14%` (`1.75x`) | `-42.86%` | `-48.79%` vs. Cur `0-LZ Mode A` |
| **4. Current `.lca v2` `Mode B (LCD2)`** | `1,204,736 B` *(Exact: `1,046,225 B`)* | `55.08%` (`1.82x`) | `-44.92%` | baseline `Mode B` |
| **5. Prototype Revised `Mode B (LCD2)`** | `1,031,680 B` *(Exact: `872,284 B`)* | `47.17%` (`2.12x`) | `-52.83%` | `-14.36%` (`256B`) / `-16.63%` (exact) |

> **Known Prototype Accounting Caveats (Must Be Resolved Before Promotion to `Accepted`):**
> 1. Study 6 (`LcaLayeredPropertyEntropyProofTest` `L4587`) left the rejected property-Huffman layer enabled (`encodeZeroTaxLayeredSection(yzxGlobal, globalDict, true)`), slightly overstating standalone 3-layer savings by `~1.2%` on voxel bytes.
> 2. Prototype encoders omitted `ICD v3` wire framing overhead (`VarInt payloadByteLen` = `1–2 B`/lit section, `extFlags` + columnar stream `VarInt byteLen` prefixes, `+2 B` `secExtFlags` prefix on rank-excluded sections) and approximated section biomes as a flat `1 B` (`0x80`) instead of copying actual multi-biome payloads.
> 3. Study 6 evaluated `Math.min(curB, revCandB)` using two isolated `AtomicDictState` instances (`curRegDict` vs. `revRegDict`) rather than a single shared region dictionary with `AtomicDictState#rollbackTo`.
> 4. Fixed region headers (`8,256 B` / `10,432 B`) were amortized over `21` sampled chunks/region (`252 / 12`) rather than `1,024` chunks/region.
> 5. End-to-end CPU latency (`cold read`, `warm sweep`, `dirty CoW save` with eikonal BFS and `Mode B` gating) has not yet been benchmarked against the `leafanvil-ADR-004` Five-Axis Performance Contract.

## Implementation & Promotion Contract (`Proposed` $\to$ `Accepted`)

1. **Rule D-005.2 Stop-Gate:** Implementing this ADR modifies `> 1` production class (`LcaSectionCodec`, `LcaStreamingNbtEncoder`, `LcaRegionReader`, `LcaRegionWriter`), updates `api/anvil-api/docs/ICD.md` to Version 3 (`LCA-ICD-3.0`), and crosses into `resource/LeafAnvil` (`LcaWireStreamProtocol`, `rust/lca-format`). Before editing production classes, run `repo_ast_offload(action="critique_design")` and obtain approval on the 4-part proposal.
2. **Safety & Fail-Closed Invariants (`REQ-RTP-S-004`, `REQ-RTP-S-005`):**
   - Every decoded `VarInt`, `ZigZagVarLong`, `predictorTableId3`, `predictorSubMode`, `secExtFlags`, `extFlags`, `templateOrd`, `idOrd`, `payloadByteLen`, and `rankDomain` index **shall** validate bounds and reserved bits before allocation or indexing, throwing `CorruptRegionEntryException` (`CBIT T1/T4/T5/T6b/T7`) on malformed or truncated frames (`S-004`).
   - `LcaSectionCodec#skipSectionFrame` and `LcaSectionCodec#skipLightChannel` **shall** remain $O(|P|)$ / $O(1)$ pointer advances with zero BFS light propagation or NBT inflation (`S-005`).
   - Reuse thread-local `AnvilIoPool` scratch buffers (`byte[4096] hatL`, `int[4096] ring`, `long[64] inQueue`, packed LUT cache, streaming tick/BE buffers) to maintain zero per-section heap churn.
3. **Mandatory Promotion Gates (Before Changing Status to `Accepted`):**
   - **Clean `ICD v3` Re-Benchmark & Five-Axis Proof:** Re-run Study 6 using the actual `formatVersion = 0x0003` production codec (property-Huffman disabled, real biome bytes, full `v3` wire headers, single shared `AtomicDictState` with `rollbackTo`, `1,024`-chunk region amortization) and publish measured `cold read`, `warm sweep`, `dirty CoW save`, memory residency, and net region byte metrics against `leafanvil-ADR-004`.
   - **Normative `ICD.md` Version 3 (`LCA-ICD-3.0`) & Cross-Language Golden Fixtures:** Update `api/anvil-api/docs/ICD.md` with frozen `LCA_LIGHT_TABLE_V1` (§9.4), `v3` wire layouts, and `0x0002` $\to$ `0x0003` upgrade rules; extend `LcaGoldenFixtureExporter` with `v3` fixtures covering all three layers + `0x0002` backward compatibility; implement matching `v3` decoding in `resource/LeafAnvil/rust/lca-format` and verify lockstep `cargo test` + Gradle pass.
   - **Gate 1 Negative & Fuzz Suite:** Verify `0x0002` read-compat, `0x0002` $\to$ `0x0003` atomic upgrade, `tinted_glass` + multi-source BFS fixed-point parity, modded tick/BE schema fallback (`L_inf = 0`), `CBIT T6b` rank-exclusion quarantine, and malformed/truncated `sentinelHdr` / `extFlags` / `secExtFlags` fuzz rejection.

## Consequences

### Positive
- Eliminates all three `LCA-ICD-1.0` (`0x0002`) wire collisions cleanly under `formatVersion = 0x0003` (`LCA-ICD-3.0`) while preserving `0 B` header tax on baseline `v3` sections and 100% backward read compatibility with `0x0002` archives.
- Preserves self-describing `SectionFrame` / `SectionWireSlice` independence across `LcaSectionBacking`, `LcaOrderlessDictTable`, single-section CoW splicing, and negotiated `LCA_NET_V1` streaming (`CAP_LCA_V3_SECTIONS`).
- Delivers large `Zero Zstd` reductions across lighting (`~56–64%` in prototype), container-heavy schematics (`~29–69%`), `0-LZ Mode A` worlds (`~49%`), and `Mode B` overworld regions (`~12–15%` net expected after `v3` framing and removal of property-Huffman), with `0` regressions on repeated architectural builds and strict vanilla-schema fallback for modded NBT.

### Negative / Trade-Offs
- Requires a coordinated `ICD.md` Version 3 (`LCA-ICD-3.0`) update, `0x0002` $\to$ `0x0003` region upgrade path, `LCA_NET_V1` capability bit (`CAP_LCA_V3_SECTIONS`), and lockstep Rust `lca-format` + golden-fixture updates before promotion to `Accepted`.
- Decoders reading `v3` eikonal lighting frames execute a bounded 4,096-voxel integer BFS using cached dictionary opacity/emission bytes (`AnvilIoPool` scratch, zero heap churn), though `skipSectionFrame` and `skipLightChannel` remain $O(|P|)$ / $O(1)$ via `payloadByteLen` and `MAP_TILE` archives remain exempt from GPU-side BFS.
- `Mode B` rank-exclusion A/B gating adds optional second-pass `LCD2` trial cost during offline compaction unless skipped or heuristically gated on live tick-adjacent `CoW` saves.

### Production Java `:anvil-api` (`0x0003`) Implementation & Empirical Results (`LcaV3FormatAndBenchmarkTest`)

Implemented in `LcaV3Codec`, `LcaSectionCodec`, `LcaRegionReader`, `LcaRegionWriter`, `LcaStreamingNbtEncoder`, and `LcaSectionBacking` (incorporating accepted `anvil-api-ADR-005` `B1` `64-bit` octant-bitmask packed 4-bit Eikonal residuals and `D` `litLen[b]` cost estimation).

**Live `26.3` Overworld Sample (`160` chunks across `8` strided regions, `256 B` slabs, `Mode B` `LCD2`):**

| Configuration | Archive Size (B) | vs `v2` | Direct NBT Rd (`µs/chk`) | `readChunkView` (`skipLight`, `µs/chk`) | Default Policy |
| :--- | ---: | ---: | ---: | ---: | :--- |
| **`v2` (`0x0002`, baseline)** | `592,392` | `0.00%` | `250.89` | `78.22` | Legacy read-supported |
| **`v3-Columnar`** | `571,144` | **`-3.59%`** | **`218.95` (`-12.7%`)** | **`64.69` (`-17.3%`)** | Enabled in `Mode A` & `Mode B` |
| **`v3-Default` (`Columnar + Eikonal+B1`)** | `536,328` | **`-9.46%`** | `276.14` (`+10.1%`) | **`62.46` (`-20.1%`)** | **Default `Mode B` (`LCD2`)** |
| `v3-Col+RankEx` (opt-in) | `550,920` | `-7.00%` | `296.44` (`+18.2%`) | `149.15` (`+90.7%`) | Decode-supported, opt-in encode |
| `v3-Full` (`Col + Eikonal+B1 + RankEx`, opt-in) | `528,904` | `-10.72%` | `344.72` (`+37.4%`) | `134.00` (`+71.3%`) | Opt-in archival compaction |

- **Bundled `26_3_R1/r.0.0.mca` (`40,960 B` `.mca`):** `v2 Mode B = 20,488 B` (`50.02%` of `.mca`) -> `v3-Full Mode B = 18,440 B` (`45.02%` of `.mca`, `-10.00%` vs `v2`).
- **Warmed 64-Chunk Single-Pass `overworld_mountain_r.0.0.mca` (`.mca = 495,616 B`) with `ADR-005` `E` (`FastGreedy LCD2`, `lcd2Level = 1`):**
  - `Vanilla .mca (in-RAM Zlib)`: `495,616 B` (`100.00%`) | `DOM Save = 440.46 µs` | `View Rd = 122.30 µs`
  - `v3-Default Mode A (0-LZ)`: `290,824 B` (`58.68%`) | `DOM Save = 377.26 µs` | `View Rd = 43.06 µs` (`2.84x` faster than `.mca`)
  - `v3-Columnar Mode B (FastGreedy)`: `252,680 B` (`50.98%`) | `DOM Save = 400.86 µs` (**`2.41x` faster** than exhaustive `967.34 µs`, faster than `.mca` Zlib) | `View Rd = 62.48 µs` (`1.96x` faster than `.mca`)
  - `v3-Default Mode B (Eik+B1 + FastGreedy)`: `233,736 B` (`47.16%`, `2.12x` smaller) | `DOM Save = 519.83 µs` (**`1.82x` faster** than exhaustive `947.56 µs`) | `View Rd = 61.92 µs` (`1.98x` faster than `.mca`); exhaustive archival compaction remains available at `lcd2Level >= 3`.
- **Default Policy Decisions Driven by Benchmarks:**
  1. `Columnar Ticks/BE` (`boilerplateFlags16` bit `15`) is enabled by default in both `Mode A` and `Mode B` (smaller **and** faster to decode).
  2. `Eikonal + B1` light residuals are enabled by default in `Mode B` (`LCD2`) and opt-in in `Mode A` (`0-LZ`) so `Mode A` retains faster-than-`.mca` `Zlib` live save latency.
  3. `RankExcl` (`secExtFlags` bit `0`) is decoded unconditionally across all `v3` readers and kept opt-in (`FEATURE_RANK_EXCL`) for archival compaction because its `+70–90%` `readChunkView` latency penalty outweighs its extra `-1.26%` byte savings on live worlds.
