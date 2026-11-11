# anvil-api-ADR-003 - `.lca` Binary Specification: 3D Hilbert, Adaptive Delta-Roaring, Gray-Code Light & Octree Streaming

**Status:** Accepted
**Date:** 2026-10-30
**Extends:** [anvil-api-ADR-001](anvil-api-ADR-001-anvil-subsystem.md) (Anvil Read-Only Subsystem), [anvil-api-ADR-002](anvil-api-ADR-002-multi-format-region-support.md) (Multi-Format Region Support)
**Related:** [leafrtp-ADR-065](../../../../resource/LeafRTP/docs/adr/leafrtp-ADR-065-layered-spiral-hilbert-rle-index.md) (Layered Spiral-Hilbert RLE Index), [leafrtp-ADR-072](../../../../resource/LeafRTP/docs/adr/leafrtp-ADR-072-hybrid-bit-packed-and-16-bit-run-container-hazard-table.md) (Hybrid Bit-Packed and 16-Bit Run Container Hazard Table), [leafconfig-ADR-001](../../../../resource/LeafConfig/docs/adr/leafconfig-ADR-001-universal-server-config-atlas-and-web-ide.md) (Universal Server Config Atlas & 3D World Map Workspace)

## Context

Existing Minecraft region storage and web-map tile formats trade off disk size, CPU overhead, and random-access granularity:

1. **Vanilla Anvil (`r.<x>.<z>.mca`):**
   - Stores up to $1,024$ independent `Zlib` (`DEFLATE`) compressed chunk NBT streams aligned to $4\text{ KiB}$ disk sectors (`6.23 MB` on `r.0.0.mca`, `6,080 B/chunk`).
   - **Per-Chunk Dictionaries:** Repeats NBT keys (`"block_states"`, `"palette"`, `"Name":"minecraft:deepslate"`) and palette strings across up to $1,024$ chunks per region.
   - **$4\text{ KiB}$ Sector Padding and Spill Fragmentation:** Pads each chunk stream to a $4,096\text{-byte}$ boundary (`31.4%` or `1.95 MB` trailing zeros on `r.0.0.mca`), and grows from `4.20 MB` to `18.69 MB` (`1,768` EOF spills) across $10,000$ chunk saves.
   - **`Y -> Z -> X` Scanline Packing and Palette Bit-Width Step:** Each $16 \times 16 \times 16$ (`4,096`-voxel) section packs palette indices in `YZX` order using $b = \max(4, \lceil \log_2 |P| \rceil)$ bits per voxel. Crossing from $16$ to $17$ palette entries increases all $4,096$ indices from $4\text{ bits}$ (`long[256]`) to $5\text{ bits}$ (`long[342]`), adding `+724 B` uncompressed (`+257 B` / `+23.4%` after `Zlib`).
   - **4-Bit Lighting Nibbles (`35%--45%` of `.mca` bytes):** Each active $16^3$ section stores $2,048\text{ B}$ of `SkyLight` and $2,048\text{ B}$ of `BlockLight` nibbles. Because light decays by Manhattan distance ($15 \to 14 \to \dots \to 0$), standard binary nibbles flip multiple bits at step boundaries (e.g. $8 = 1000_2 \to 7 = 0111_2$) and require full `Zlib` decompression to read.
2. **Linear (`r.<x>.<z>.linear`, xymb / Leaves / LinearPaper):**
   - Removes $4\text{ KiB}$ sector padding and compresses all $1,024$ per-chunk NBT payloads in a single whole-region `Zstd` stream (`4.34 MB` at `Zstd-1`, `3.65 MB` at `Zstd-6`).
   - **Whole-Region Read and Write Amplification:** Reading a single uncached chunk requires decompressing all $1,024$ chunks (`26.4 ms`), and saving a single modified chunk requires re-compressing the entire region (`49--206 ms`, `405x` write amplification).
3. **Pre-Baked Triangle Meshes (`.prbm` `hires` + 2D `lowres` Tiles):**
   - Decodes `.mca` chunks and runs a CPU greedy mesher to emit `Float32` vertex positions, normals, UVs, ambient occlusion, and material tables into `.prbm` tile files (`~58 MB/region` raw, `~12--18 MB` compressed), duplicating world geometry on disk alongside `.mca`.
   - **Single-Layer `lowres` Horizon:** Distant LOD (`lowres`) uses a single-layer 2D heightmap mesh that omits overhangs, floating islands, arches, caves, and Nether ceilings.
   - **Static Geometry:** Because lighting and hidden-surface removal are baked into static triangle meshes, dynamic $Y$-cutaways, relighting, and single-section incremental updates require re-meshing on the server CPU.

## Decision

Specify the `.lca` (LeafCraft Anvil) binary format for `api/anvil-api`, server persistence (`region/`, `entities/`, `poi/`), and 3D octree and multi-span LOD streaming.

All multi-byte integers in header and directory structures are big-endian (`BE`) for `VarHandle` (`MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN)`) and Mojang `CompoundTag` sidecar compatibility.

### 1. File Layout Overview (`r.<rx>.<rz>.lca`)

```
+-----------------------------------------------------------------------+ Offset 0x0000
| 1. Superblock Header (64 bytes)                                       |
+-----------------------------------------------------------------------+ Offset 0x0040
| 2. Atomic Chunk Pointer Directory: uint64[1024] (8,192 bytes)         |
+-----------------------------------------------------------------------+ Offset 0x2040
| 3. Region Shared BlockState & Biome String Dictionary (Variable)      |
+-----------------------------------------------------------------------+ Offset dictEnd
| 4. Extended Hamming (72, 64) SEC-DED Pointer Syndrome Table (1,024 B) |
+-----------------------------------------------------------------------+ Offset slabBase (256B-aligned)
| 5. 256-Byte Sub-Slab Pool: Self-Describing CoW Chunk Frames           |
+-----------------------------------------------------------------------+ Offset fileLength - 8
| 6. Footer Canary Magic: uint64 (0x4C4341315F454E44 = "LCA1_END")      |
+-----------------------------------------------------------------------+ Offset fileLength
```

### 2. Layer 1: Superblock (`64 Bytes`) & Atomic Chunk Pointer Directory (`8,192 Bytes`)

#### 2.1 Superblock Header (`0x0000 .. 0x003F`, 64 Bytes)

| Byte Offset | Type | Field Name | Description / Invariant |
| ---: | :--- | :--- | :--- |
| `0x00..0x07` | `uint64` | `magic` | `0x4C43415F56303031L` (`"LCA_V001"` ASCII) |
| `0x08..0x09` | `uint16` | `formatVersion` | `0x0002` (Major `2`, Minor `0`; `0x0001` supported for read & auto-upgrade) |
| `0x0A` | `uint8` | `storageKind` | `0 = REGION_VOXEL`, `1 = ENTITIES`, `2 = POI`, `3 = MAP_TILE` |
| `0x0B` | `uint8` | `flags` | Bit `0`: `HAS_SHARED_DICT`; Bit `1`: `HAS_LOD_HEADER`; Bit `2`: `HAS_TAIL_DELTA_RING`; Bit `3`: `DEFAULT_MODE_B`; Bit `4`: `HAS_SECDED_PARITY` |
| `0x0C..0x0F` | `int32` | `regionX` | Signed region $X$ coordinate |
| `0x10..0x13` | `int32` | `regionZ` | Signed region $Z$ coordinate |
| `0x14..0x17` | `int32` | `minSectionY` | World minimum section $Y$ (e.g. `-4` for $Y = -64$) |
| `0x18..0x19` | `uint16` | `sectionCount` | Vertical sections per chunk (e.g. `24` for $Y = -64..319$) |
| `0x1A..0x1B` | `uint16` | `slabShift` | Sub-slab alignment shift: `8` ($2^8 = 256\text{ bytes}$) |
| `0x1C..0x1F` | `int32` | `defaultDataVersion` | Mojang `DataVersion` (e.g. `3953` for 1.21, `4671` for 26.1) |
| `0x20..0x27` | `uint64` | `dictOffsetAndLen` | High 32 bits: root `dictCapacityBytes` (`256B`-aligned slack arena); Low 32 bits: `dictByteOffset` (`0x2040`) |
| `0x28..0x2F` | `uint64` | `lodHeaderOffsetAndLen`| High 32 bits: `secDedAndLodByteLength` (`>= 1,024 B` Extended Hamming `(72, 64)` SEC-DED pointer syndrome table); Low 32 bits: `secDedTableByteOffset` |
| `0x30..0x37` | `uint64` | `slabPoolBaseOffset` | 256-byte aligned start offset of the chunk sub-slab pool |
| `0x38..0x3F` | `uint64` | `headerChecksum` | XXH64 / CRC64 over bytes `0x00..0x37` |

#### 2.2 8 KiB Atomic Chunk Pointer Directory (`0x0040 .. 0x203F`, `uint64[1024]`)

Indexed by `chunkIdx = (rx & 31) | ((rz & 31) << 5)`. Each 64-bit word is read and updated via `VarHandle.getVolatile` / `compareAndSet`:

$$\underbrace{\mathtt{slabOffset35}}_{\text{bits } 63..29} \;\big|\; \underbrace{\mathtt{codec1}}_{\text{bit } 28} \;\big|\; \underbrace{\mathtt{exactByteLen20}}_{\text{bits } 27..8} \;\big|\; \underbrace{\mathtt{generation8}}_{\text{bits } 7..0}$$

- **`slabOffset35` (bits `63..29`, 35 bits):** Starting file offset in `256-byte` sub-slabs (`actualByteOffset = slabOffset35 << 8`), addressing up to $8\text{ TiB}$ per region file. `0` indicates an ungenerated/absent chunk.
- **`codec1` (bit `28`, 1 bit):** `0 = Mode A (Uncompressed Section Payload)`, `1 = Mode B (Uncompressed ChunkFrame Header + SEC-DED Parity + Continuous Atomic Region Dictionary LCD2 0x4C44 Body)`.
- **`exactByteLen20` (bits `27..8`, 20 bits):** Exact payload byte length ($1\text{ B} .. 1,048,575\text{ B}$). Allocated slab capacity is `allocatedSlabs = (exactByteLen20 + 255) >>> 8`. Uncompressed chunk materialization is bounded by `MAX_CHUNK_UNCOMPRESSED_BYTES = 16 MiB` (`CBIT T4`).
- **`generation8` (bits `7..0`, 8 bits):** Monotonically incrementing wrapping save counter (`0..255`). Serves as an ABA concurrency guard for lock-free readers and an $O(1)$ cache-invalidation token for `LeafRTP` and map readers.

#### 2.3 In-Memory `256B` Free-Slab Allocator, Header-Only Live Archive Residency & Copy-on-Write (`CoW`) Live-Save Invariant
On region open (`LcaRegionWriter.openLiveArchive(Path)`), `LcaLiveArchive` reads only the `10,304 B` (`~10.1 KiB`, `0x0000..0x283F`) default on-disk region header prefix (`64 B` `Superblock`, `8,192 B` `1,024` pointer words, `1,024 B` default `Root Shared Dictionary`, and `1,024 B` `SEC-DED` parity table) plus the 8-byte footer canary into heap memory (`headerOnlyChannelMode = true`), scanning the $1,024$ pointer words in `< 15 us` to populate the size-indexed free-slab `TreeMap` while serving chunk reads and CoW slab writes positionally over its open `FileChannel`. This bounds per-region heap residency to `~10.1 KiB` of raw header bytes (`~5.15 MiB` across `512` open regions) and `~25.6 KiB` total per-handle footprint (`~12.8 MiB` across `512` open regions) once `prevChunkPointers[1024]`, per-chunk cache arrays, and decoded dictionary/allocator lookup structures are included, updating `LcaRegionReader.FILE_HEADER_CACHE` pointers in-place (`updateCachedPointer`) on save without evicting cached region headers. On chunk save:
- **Copy-on-Write (`CoW`) Slab Rotation and Per-Thread Hazard Pointers:** Live saves never mutate an in-use chunk slab in-place while concurrent readers may be reading it. Before reclaiming the oldest slot of the 4-epoch CoW grace ring, the writer checks the 64-slot lock-free per-thread hazard pointer array (`PINNED_SLAB_STARTS` in `LcaRegionReader`): if a reader thread pins `[oldSlabOffset, oldSlabOffset + oldSlabs)`, retirement of that slab range is deferred until the reader unpins its slot. The writer pops the best-fit unpinned free interval $\ge \mathtt{newSlabs}$ from the `TreeMap` (or appends at EOF), writes the new chunk frame, issues a `VarHandle.releaseFence()`, updates the 1-byte Extended Hamming `(72, 64)` SEC-DED syndrome, CAS-swaps the 64-bit directory word (`newSlabOffset35 | codec1 | exactByteLen20 | ((generation8 + 1) & 0xFF)`), and records the previous pointer in `prevChunkPointers[chunkIdx]` for rollback. Unpinned old slabs return to the `TreeMap` pool for reuse (`95.0%--100.0%` slot reuse across $10,000$ saves).
- **Exclusive Offline / Single-Writer Compaction:** Only when opened in exclusive offline maintenance mode with zero concurrent readers may a writer overwrite an existing slab in-place when `newSlabs <= currentSlabs`.

### 3. Layer 2: Region Shared Dictionary & Front-Loaded LOD Header

#### 3.1 Chained Region Shared `BlockState` & `Biome` Dictionary (Slack + Relative Continuation Pointer)
```
// Root Dictionary Segment at 0x2040 (or Continuation Segment at segmentOffset):
uint32  segmentStateCount          // Number of BlockState entries in this segment
uint16  segmentBiomeCount          // Number of Biome entries in this segment
uint16  usedPayloadBytes           // Exact active entry bytes written in this segment
Entry[segmentStateCount]:
  VarInt  utf8ByteLen
  byte[]  canonicalStateString     // e.g. "minecraft:oak_stairs[facing=north,half=bottom,waterlogged=false]"
  uint32  materialPacked           // RGBA8 average color + 4-bit roughness/emissive/foliage/water flags for map LODs
BiomeEntry[segmentBiomeCount]:
  VarInt  utf8ByteLen
  byte[]  biomeIdString            // e.g. "minecraft:lush_caves"
  uint32  foliageAndWaterTint      // Biome tint colors for map rendering
byte[]  slackPadding               // Zero-filled slack up to (segmentCapacityBytes - 8)
uint64  nextSegmentLinkTrailer     // ALWAYS at the last 8 bytes of the segment (offset + segmentCapacityBytes - 8)
```
- **Pre-Allocated Slack and Tail Relative Continuation Pointer (`nextSegmentLinkTrailer`):**
  - The root dictionary at `0x2040` is pre-allocated with `256B`-aligned slack capacity (default `1,024 B` = 4 sub-slabs of `256 B`, configurable up to `4,096 B+`) so newly encountered `BlockState` or `Biome` strings append into `slackPadding` in $O(1)$ without shifting `lodHeaderOffset` or `slabPoolBaseOffset`.
  - The final 8 bytes (`uint64`) of every dictionary segment store a relative continuation link trailer:
    $$\underbrace{\mathtt{nextSegmentRelSlabs32}}_{\text{bits } 63..32\text{ (int32)}} \;\big|\; \underbrace{\mathtt{nextSegmentCapacitySlabs24}}_{\text{bits } 31..8\text{ (uint24)}} \;\big|\; \underbrace{\mathtt{segmentFlags8}}_{\text{bits } 7..0}$$
    where `nextSegmentRelSlabs32` is a relative offset in `256-byte` sub-slabs from the start of the current segment to the next dictionary segment (`0` = terminal segment), and `nextSegmentCapacitySlabs24` is the capacity of that next segment in `256B` sub-slabs.
  - When a segment's slack is exhausted (`usedPayloadBytes + newEntryBytes > segmentCapacityBytes - 8`), the writer allocates a free slab range from the `TreeMap` (e.g., `256 B .. 4 KiB`), formats it as a continuation segment (with `nextSegmentLinkTrailer = 0`), and atomically writes the relative pointer into the current segment's trailer. This allows the shared dictionary to grow across chained segments while preserving monotonic global dictionary IDs (`0 .. totalStateCount - 1`); offline compaction can flatten chained segments into a single contiguous root arena.
- **Material Metadata (`materialPacked`):** Embedding the 32-bit `materialPacked` word (`RGBA8` albedo + translucency/emissive/water flags) in the region dictionary once per state allows map renderers to shade voxel or octree LOD cells in $O(1)$ from the `VarInt` state ID without parsing blockstate JSONs or textures during LOD streaming.

#### 3.2 Multi-Span Analytical Index Integration (`.dlsh` / Optional Layer 2 Extension)
To keep `.lca` region headers compact (`10,304 B` / `~10.1 KiB` on-disk header prefix, `~25.6 KiB` total `LcaLiveArchive` handle residency per open region), `lodHeaderOffsetAndLen` (`0x28..0x2F`) in standard server region archives points directly to the `1,024-byte` Extended Hamming `(72, 64)` SEC-DED pointer syndrome table (`flags` bit `4` = `HAS_SECDED_PARITY`), while the $512 \times 512$ (`262,144`-column) 2D `DualLayerSpiralHilbertCurve` (`H_2`) multi-span horizon index is maintained in standalone `.dlsh` sidecar caches or optional `HAS_LOD_HEADER` (`flags` bit `1`) segments:
1. **Region Occupancy and Safety Bitmaps:** Roaring containers for `generated_chunk_mask` (`1,024` bits), `rtp_safe_ground_mask` (`262,144` bits), and `water_column_mask`.
2. **Progressive Multi-Span Surface and Cavity Columns (`LOD-3 -> LOD-0`):**
   - Stores up to $K=4$ vertical `(topY, bottomY, stateId, skyLight4 | blockLight4)` spans per $(x, z)$ column along `H_2` (retaining overhangs, bridges, floating islands, and cave roofs).
   - Elevations are encoded as 8 Reflected Binary Gray-code bitplanes (`g7..g0` where $G = Y \oplus (Y \gg 1)$) inside Roaring containers (`~6 KB` for a $512 \times 512$ region at `LOD-3` `S=64`, and `~433 B/chunk` at `LOD-0`).

### 4. Layer 3: Self-Describing Chunk Frame, Slab-Local Micro-Palette & `uint16[N]` Vertical Section Directory

At `byteOffset = slabOffset35 << 8`:

```
+-----------------------------------------------------------------------+
| int32   selfTagAndDataVersion (4 bytes: 0x5800_0000 | (chunkIdx << 16)|
|                                         | (chunkDataVersion & 0xFFFF))|
| int64   lastUpdateTick        (8 bytes; world gameTime)               |
| int64   inhabitedTimeTicks    (8 bytes)                               |
| uint8   chunkStatusOrdinal    (1 byte; 0xFF = minecraft:full)         |
| uint8   chunkFlags            (1 byte; bit 0: hasResidualNbt,         |
|                                        bit 1: hasSlabLocalPalette,    |
|                                        bit 2: scaleOffsets [0=1B,1=4B]|
|                                        bits 7..3: defaultBiomeCode5)  |
| uint16  residualNbtRelOffset  (2 bytes; offset in 1B/4B units to tail)|
| uint16  sectionOffsets[sectionCount] (48 bytes for 24 sections)       |
+-----------------------------------------------------------------------+
| [If codec1 == 1 (Mode B): uint8 headerSecDed[headerWords] (9 bytes)]  |
|   Extended Hamming (72, 64) SEC-DED syndromes over the uncompressed   |
|   ChunkFrame header words; body below is pure-Java LCD2 (0x4C44)      |
+-----------------------------------------------------------------------+
| Slab-Local Chunk Micro-Palette (Part A: 1-byte Static Core Tokens +   |
| Base Block/Biome Names; Part B: Sparse '[...]' Property Suffixes)     |
+-----------------------------------------------------------------------+
| Section Frame 0 .. Section Frame (sectionCount - 1)                   |
+-----------------------------------------------------------------------+
| Residual Tail (uint16 boilerplateFlags16 + optional VarInt mbnlLen    |
| + mbnlDeltaStream + boilerplate fields + optional Mojang TAG_Compound)|
+-----------------------------------------------------------------------+
```

#### 4.1 Encoding of `uint16 sectionOffsets[i]` (`Y = minSectionY + i`)
Each 16-bit entry in `sectionOffsets[i]` encodes either an inline constant section ($0\text{ bytes}$ in body) or a relative byte offset:
- **`0x0000`:** Unallocated / void section (`palette = [minecraft:air]`, `SkyLight = 15`, `BlockLight = 0`), decoded with zero body bytes read.
- **Bit `15 == 1` (`0x8000 | (skyLight2 << 13) | (biomeTemplate2 << 11) | localDictId11`, or `0x8000 | (skyLight2 << 13) | dictId13`):** Inline uniform section (`|P| = 1`):
  - Sections containing a single block state (`air`, `stone`, or `deepslate`) and uniform lighting (`66.4%` or `16,326 / 24,576` of sections in `r.0.0.mca`) pack their slab-local or region dictionary ID directly into the `uint16` directory entry, omitting the section body (`0` body bytes).
  - When `chunkFlags` bit `1` (`hasSlabLocalPalette == 1`) is set, bits `12..11` encode `biomeTemplate2` (`0..3`, referencing one of the chunk's deduplicated $4 \times 4 \times 4$ `BiomeTemplate`s in `Part A`) and bits `10..0` encode `localDictId11` (`0..2,047`), allowing uniform-block sections to remain inline when a chunk spans multiple biomes.
  - **4-State `skyLight2` Encoding:** Storing uniform lighting in `skyLight2` (bits `14..13`) enables $O(1)$ single-section decoding without reading vertical neighbors or the Layer 2 header:
    - `00`: `SkyLight = 0, BlockLight = 0` (subterranean solid stone/deepslate or dark enclosed cave air)
    - `01`: `SkyLight = 15, BlockLight = 0` (sky-exposed air or sunlit uniform water)
    - `10`: `SkyLight = 15, BlockLight = 15` (sky-exposed uniform light-emitting fill, e.g. surface lava)
    - `11`: `SkyLight = 0, BlockLight = 15` (subterranean uniform light-emitting fill, e.g. Nether/deep lava sea, or top-down analytical sky derivation when `SkyLight` was stripped prior to lighting)
  - **`dictId13 >= 8192` Overflow Fallback:** If a region's chained dictionary exceeds `8,191` unique block states and a uniform section references `dictId >= 8192` (or has non-uniform light nibbles), the encoder shall not set bit `15`; instead it emits a 4-byte non-uniform `SectionFrame` (`bit 15 == 0`, `blockEncodingMode = 00 (Uniform)`, full `VarInt` dictionary ID), preventing 13-bit truncation.
- **Bit `15 == 0` (`0x0001 .. 0x7FFF`):** 1-based relative offset from `sectionDirEnd = 24 + 2 * sectionCount` to the non-uniform `SectionFrame`:
  $$\mathtt{relStart}_i = \mathtt{sectionDirEnd} + \bigl((\mathtt{entry16}_i - 1) \ll \mathtt{unitShift}\bigr), \quad \mathtt{unitShift} = \begin{cases} 0 & (\mathtt{scaleOffsets} = 0,\text{ 1B exact units up to } 32\text{ KiB}) \\ 2 & (\mathtt{scaleOffsets} = 1,\text{ 4B units up to } 128\text{ KiB}) \end{cases}$$
  - When `hasSlabLocalPalette == 1`, the initial Slab-Local Micro-Palette occupies `[sectionDirEnd .. min_k(relStart_k))` with zero tail padding when `scaleOffsets == 0`.
  - **Order-Independent Section Bounding (`CBIT T5`):** Non-uniform `SectionFrame`s may appear in any non-overlapping permutation inside `[sectionDirEnd .. frameLen)` (including out-of-order `SectionFrame`s appended in the `256-byte` sub-slab slack before `residualRelStart` or after the Residual Tail). Each non-uniform `SectionFrame` $i$ is bounded by:
    $$\mathtt{relEnd}_i = \min\Bigl(\{\mathtt{relStart}_j \mid \mathtt{relStart}_j > \mathtt{relStart}_i\} \cup \{B \in \{\mathtt{residualRelStart}, \mathtt{frameLen}\} \mid B > \mathtt{relStart}_i\}\Bigr)$$
    and the Residual Tail is bounded by $\mathtt{residualRelEnd} = \min(\{\mathtt{relStart}_j \mid \mathtt{relStart}_j > \mathtt{residualRelStart}\} \cup \{\mathtt{frameLen}\})$. Duplicate non-zero non-uniform `entry16` offsets are rejected by `CBIT T5`.

#### 4.2 Three-Tier Palette Resolution & Slab-Local Micro-Palette (`chunkFlags` bit `1`)
`.lca` resolves block states and biomes through a three-tier hierarchy designed so that sub-chunk terrain probes never parse blockstate properties while complex property strings are never duplicated across chunks:
1. **Tier 1 - Static Core Token Tables (`1 byte`, `0` heap allocations):**
   - A 160-entry Static Core Block Table (`0x80 | coreId` for `0..127`, `0x60 | (coreId - 128)` for `128..159`), a 65-entry Static Core Biome Table (`0x80 | coreBiomeId`, covering all vanilla Overworld, Nether, and End biomes), and a 128-entry Static Core Property Suffix Table (`0x80 | propId` in `STATIC_CORE_PROP_SUFFIXES`) encode common vanilla identifiers in 1 byte and resolve directly to pre-interned `String` and singleton `List<String>` constants.
2. **Tier 2 - Slab-Local Chunk Micro-Palette (`Part A` + `Part B`, placed at `sectionDirEnd` when `hasSlabLocalPalette = 1`):**
   - **Part A (Base Block Names, Biomes, and Deduplicated Chunk `BiomeTemplate`s for `readChunkView` / Probes):**
     - Encodes the chunk's unique base block names (`1B` static core token, `0x40` stripped `"minecraft:"` ASCII, or `0x00` full modded UTF-8), unique biome names, and `1--3` deduplicated $4 \times 4 \times 4$ `BiomeTemplate`s (encoded with 1-byte `XZY` column runs `(pIdx3 << 5) | (len5 - 1)`).
     - `readChunkView`, `readColumnProbe`, `readTerrainProbe`, and `readSection` stop at the end of `Part A` without reading `Part B` or the region dictionary at `0x2040`.
   - **Part B (Property Suffix Binding for Full-NBT `readChunk`):**
     - Appended immediately after `Part A` (`VarInt propStateCount` + `(VarInt localStateIdx, propToken)` pairs). Each property-bearing state emits either `0x80 | propId` (`1 byte` static suffix from `STATIC_CORE_PROP_SUFFIXES`) or `0x40 + VarInt globalStateId` referencing Tier 3.
3. **Tier 3 - Chained Region Shared Dictionary (`0x2040` + `nextSegmentLinkTrailer`):**
   - Stores exotic or modded `BlockState` strings whose property suffixes are not in `STATIC_CORE_PROP_SUFFIXES` once per region, referenced from `Part B` via `0x40 + VarInt globalStateId` (or directly by section palettes when a region exceeds `8,191` states and `hasSlabLocalPalette = 0`).

#### 4.3 `boilerplateFlags16`, Binary `MOTION_BLOCKING_NO_LEAVES` Stream, `ChunkPatchTail` & Single-Pass Residual Tail
At `residualRelStart = sectionDirEnd + (residualNbtRelOffset << unitShift)` up to `residualRelEnd = min({relStart_j | relStart_j > residualRelStart} U {frameLen})`:
```
uint16  boilerplateFlags16         // Bitmask (bits 0..14) of compacted vanilla/Starlight fields, patch tail, and residual NBT flags
[If bit 13 (0x2000): VarInt mbnlByteLen + byte[mbnlByteLen] mbnlDeltaStream]
byte[]  boilerplateFields          // Ordered fields enabled by boilerplateFlags16 bits 5..9
[If bit 14 (0x4000): VarInt patchTailByteLen + byte[patchTailByteLen] chunkPatchTail]
byte[]  residualNbtPayload         // Optional Mojang TAG_Compound up to residualRelEnd (raw in Mode B; DEFLATE if bit 12 set in Mode A)
```
- **`boilerplateFlags16` Bit Allocation:**
  - `Bit 0` (`0x0001`): Empty `block_entities` `TAG_List<TAG_Compound>` (`0 B`)
  - `Bit 1` (`0x0002`): Empty `block_ticks` `TAG_List<TAG_Compound>` (`0 B`)
  - `Bit 2` (`0x0004`): Empty `fluid_ticks` `TAG_List<TAG_Compound>` (`0 B`)
  - `Bit 3` (`0x0008`): `isLightOn == 1b` (`0 B`)
  - `Bit 4` (`0x0010`): Empty vanilla `structures` (`{References:{}, Starts:{}}`, `0 B`)
  - `Bit 5` (`0x0020`): `PostProcessing` list of `uint8 ppEmptyCount` empty `TAG_List<TAG_Short>` sublists (`1 B`)
  - `Bit 6` (`0x0040`): Paper/Starlight `"starlight.light_version"` (`VarInt`)
  - `Bit 7` (`0x0080`): Compact lower lighting boundary section at `Y = minSectionY - 1` (`1 B`: `skyMode2 | (blkMode2 << 2)`)
  - `Bit 8` (`0x0100`): Compact upper lighting boundary section at `Y = minSectionY + sectionCount` (`1 B`: `skyMode2 | (blkMode2 << 2)`)
  - `Bit 9` (`0x0200`): Secondary heightmaps (`uint8 count` + `(code8, VarInt payLen, payload)` entries using `0xFE, refIdx` for exact duplicate of an earlier heightmap or `0xFD, refIdx, numLongs` for best-reference cross-heightmap zero-run RLE)
  - `Bit 10` (`0x0400`): Trailing residual `Nbt` `TAG_Compound` slice present (`[pos .. residualRelEnd)`)
  - `Bit 11` (`0x0800`): Non-voxel root marker (`entities/` or `poi/` chunk payload)
  - `Bit 12` (`0x1000`): Residual `Nbt` slice is `DEFLATE`-compressed (applied only in uncompressed `Mode A` when `nbtBytes >= 256 B`; skipped in `Mode B` so the `LCD2` (`0x4C44`) frame compresses the entire `ChunkFrame` body in a single pass without nested compression)
  - `Bit 13` (`0x2000`): `MOTION_BLOCKING_NO_LEAVES` binary heightmap stream present (`VarInt mbnlByteLen` + `byte[mbnlByteLen] mbnlDeltaStream`).
  - `Bit 14` (`0x4000`, `HAS_CHUNK_PATCH_TAIL`): Length-prefixed `ChunkPatchTail` (`VarInt patchTailByteLen` + `byte[patchTailByteLen]`) placed immediately before `Bit 10` (`residualNbtPayload`), containing appended Slab-Local Micro-Palette entries (`Part A` + `Part B` for `localIdx >= K`), per-section voxel delta lists (`(uint16 yzx12, VarInt dictId)`), optional replacement section offset overrides, optional `SkyLight`/`BlockLight` section patches, and a 1-byte Extended Hamming `(72, 64)` `patchParity8` syndrome trailer.
- Decoded in `LcaSectionCodec.unpackResidualSectionAndNbt` and `LcaSectionCodec.locateAndDecodeChunkPatchTail` without injecting synthetic marker keys into the returned NBT map.

### 5. Layer 4: $16 \times 16 \times 16$ Section Frame Layout (`BlockStates`, `Biomes`, `Light`)

For non-uniform sections (`sectionOffsets[i]` has bit `15 == 0`), `Biome 4x4x4 Payload` is placed immediately after `BlockState Payload` (before `SkyLight` and `BlockLight`) so `readChunkView` (`skipLighting = true`) decodes `BlockStates + Biomes` without reading the 4 Gray-code light bitplanes:

```
+-----------------------------------------------------------------------+
| uint8   sectionHeader                                                 |
|         bits 7..6: blockCurveSelector (00=YZX, 01=H_3, 10=Y+H_2, 11=XZY)
|         bits 5..4: blockEncodingMode  (00=Uniform [mask=0L elided],   |
|                                        01=DeltaRoaring [8B mask],     |
|                                        10=FlatBitPack_ADR072 [8B],    |
|                                        11=DeltaRoaringFullMask        |
|                                           [mask=-1L elided])          |
|         bits 3..2: skyLightMode       (00=Absent,  01=ConstNibble,    |
|                                        10=GrayBitplanes_YH2, 11=Raw)  |
|         bits 1..0: blockLightMode     (00=Absent,  01=ConstNibble,    |
|                                        10=GrayBitplanes_H3,  11=Raw)  |
+-----------------------------------------------------------------------+
| [If blockEncodingMode == 01 || blockEncodingMode == 10]:              |
| uint64  octreeOccupancyMask64 (8 bytes; 1 bit per 4x4x4 sub-cube in   |
|         H_3 octant order: bit k=1 iff octant k contains any state != 0|
|         Elided when mode == 00 -> 0L, or mode == 11 -> -1L)           |
+-----------------------------------------------------------------------+
| VarInt  paletteLen (|P|)                                              |
| VarInt  slabOrRegionDictIds[|P|] (Frequency-sorted: palette[0] =      |
|                                dominant state, omitted from payload)  |
+-----------------------------------------------------------------------+
| BlockState Payload (Mode 01 or 11: |P|-1 Containers -> direct         |
|                     long[256] nibble scatter when |P| <= 16;          |
|                     or Mode 10 FlatBitPack)                           |
+-----------------------------------------------------------------------+
| Biome 4x4x4 Payload (0x80|tmplIdx for chunk BiomeTemplate, 0 =        |
|                      chunkDefaultBiomeCode5, else 11:XZY column RLE)  |
+-----------------------------------------------------------------------+
| [If skyLightMode == 01 || blockLightMode == 01:                       |
|   uint8 constLightNibbles ((blkConst4 << 4) | skyConst4, 1 byte)]     |
| SkyLight Payload (if skyLightMode == 10: uint8 planeModes8 + 4 Gray   |
|                   Bitplanes in Y+H_2; if 11: 2,048 B raw nibbles)     |
| BlockLight Payload (if blockLightMode == 10: uint8 planeModes8 + 4    |
|                     Gray Bitplanes in H_3; if 11: 2,048 B raw nibbles)|
+-----------------------------------------------------------------------+
```

#### 5.1 1-Pass Directional Gradient Curve Predictor
Before encoding a section, the encoder samples $4 \times 8 \times 8 = 256$ points in `yzxStates` and counts axis-aligned continuity (`sameX` at `+1`, `sameZ` at `+16`, `sameY` at `+256`):
- If `sameY > sameX && sameY > sameZ`: selects `11:XZY` (vertical columns: tree trunks, kelp, dripstone, vines).
- Else if `sameX + sameZ > 2.4 * sameY`: selects `00:YZX` (flat strata) or `10:Y+H_2` (horizontal 2D-Hilbert slabs).
- Else: selects `01:H_3` (isotropic 3D Hilbert octree curve).

#### 5.2 Per-State Delta-Roaring Containers (`p = 1 .. |P|-1`)
`palette[0]` is implicit (`90.8%` of voxels on `r.0.0.mca`) and omitted from the container stream. When `blockEncodingMode == 01` (`octreeOccupancyMask64 != -1L`), the cursor domain $c \in [0, N_{\text{active}} - 1]$ is rank-compacted over `octreeOccupancyMask64` (`64 K` active voxels across non-empty $4^3$ octants for `01:H_3`; `1024 S` active voxels across non-empty $16 \times 4 \times 16$ slabs for `00:YZX` and `10:Y+H_2`). For each minority palette index $p \in [1, |P|-1]$, a 2-bit container tag + 14-bit metadata header selects the smallest representation:
1. **`00 = TAG_SINGLETON` (`2..5 bytes` total):** `subTag` (`bits 13..12`) encodes a single voxel at `idx12` (`00`, `2 B`), a single contiguous run of length `2..257` (`01`, `3 B`) or `258..4096` (`10`, `4 B`), or two contiguous runs of length `1..16` each (`11`, `4..5 B`).
2. **`01 = TAG_DELTA_RUN` (`Adaptive 4|4, 5|3, 6|2, or 7|1 Packed Gap|Len Stream`):**
   - Bits `13..12` of the 16-bit container header select `subMode in 0..3` (`lBits = 4 - subMode`, `gMax = (1 << (4 + subMode)) - 1`, `lMax = (1 << lBits) - 1`), matching `4|4` (`gMax=15, lMax=15`), `5|3` (`gMax=31, lMax=7`), `6|2` (`gMax=63, lMax=3`), or `7|1` (`gMax=127, lMax=1`).
   - Escape values (`adjGap == gMax`) use a bounded `240` encoding (`1 byte` for `< 240`, `2 bytes` `0xF0 | hi4, lo8` for `240..4,095`).
3. **`10 = TAG_DELTA_ARRAY` (`Bounded 240 Delta Gaps`):**
   - When all occurrences of state $p$ are isolated single voxels (`totalVoxels == runCount`), stores `adjGap` via the bounded `240` encoding (`1 B` for `< 240`, `2 B` for $\ge 240$) without length bits.
4. **`11 = TAG_BITMAP` (`Compacted or Word-Masked 64-Bit Bitmap`):**
   - Stores either a contiguous bitmap over `N_active` (`512 B` or `0x1000 | byteLen`) or a Word-Masked bitmap (`8 B` `wordMask64` + `8 * popcnt(wordMask64)` bytes for only the non-zero 64-voxel words).

#### 5.3 `leafrtp-ADR-072` Flat Bit-Packed Ceiling (`blockEncodingMode = 10`)
Let $b = \max(1, \lceil \log_2 |P| \rceil)$ and `flatBytes = (4096 * b + 7) >>> 3`. If the sum of container bytes for $p = 1 .. |P|-1$ exceeds `flatBytes` (`0.085%` of natural sections, or high-entropy player builds), the encoder sets `blockEncodingMode = 10` and writes the `flatBytes` bit-packed array in `YZX` order, bounding worst-case section size to the bit-packed representation.

#### 5.4 Constant-Nibble Packing & 4-Bitplane Gray-Code Lighting (`SkyLight` & `BlockLight`)
- **Mode `00` (`LIGHT_MODE_ABSENT`):** Channel is absent from the section (`0` bytes).
- **Mode `01` (`LIGHT_MODE_CONST`):** All $4,096$ nibbles in the channel equal a single constant $c \in [0, 15]$ (most commonly $0$ in subterranean sections or $15$ in sky-exposed sections). Whenever `skyLightMode == 01 || blockLightMode == 01`, a single shared byte `uint8 constLightNibbles = (blkConst4 << 4) | skyConst4` is written immediately before the variable light payloads, encoding one or both constant channels in `1 byte` total.
- **Mode `10` (`LIGHT_MODE_GRAY_PLANES`):** Each 4-bit light level $L \in [0, 15]$ is mapped to Reflected Binary Gray code $G = L \oplus (L \gg 1)$ and decomposed into 4 bitplanes (`g0..g3` of $4,096$ bits each):
  - **`SkyLight` uses `10:Y+H_2` (horizontal 2D Hilbert planes):** Aligns traversal with horizontal sky-light sheets at fixed $Y$ (`89.0 B/chunk`, vs. `2,020 B/chunk` on `11:XZY` and `721 B/chunk` on `01:H_3`).
  - **`BlockLight` uses `01:H_3`:** Keeps point-source $\ell_1$ light shells localized within $2^k \times 2^k \times 2^k$ octants.
  - **Packed 1-Byte `planeModes8` Header:** All four 2-bit plane mode tags are packed into a single leading byte `uint8 planeModes8 = m0 | (m1 << 2) | (m2 << 4) | (m3 << 6)`, where each 2-bit tag $m_k \in \{0,1,2,3\}$ selects `00 = All-0 (0 B)`, `01 = All-1 (0 B)`, `10 = Delta-Run Stream (VarInt runCount + adjGap4|len4 runs with gap = (r == 0) ? adjGap : adjGap + 1)`, or `11 = Raw 512B Bitmap ceiling`.
- **Mode `11` (`LIGHT_MODE_RAW`):** Uncompressed `2,048-byte` nibble array ceiling when the 4 Gray bitplanes would reach or exceed `2,048 bytes`.

#### 5.5 Thread-Local Scratch Arena (`AnvilIoPool` Workers)
`LcaRegionReader` and `LcaSectionCodec` maintain a per-thread `ScratchArena` (`ThreadLocal<ScratchArena>`) holding pre-allocated `int[4096]` (`curvePaletteIdx`), `int[4096]` (`runPoolPacked` / `runPoolNext` single-pass $O(4096)$ minority run lists), and reusable `byte[16384]` output buffers (`~26 KiB` per worker thread alongside the `24 KiB` static curve lookup tables) to avoid per-call heap allocations during section reads and saves.

### 6. LOD and Sub-Chunk Streaming Architecture

| Capability / Metric | BlueMap (`.mca` $\to$ Greedy Mesher $\to$ `.prbm` + `lowres`) | `.lca` (Octree / Multi-Span Stream) |
| :--- | :--- | :--- |
| Per-Region Map Storage (`512x512` blocks) | `~58 MB` raw `.prbm` (`~12--18 MB` gzipped) stored alongside `.mca` | `0 B` when reading `.lca` directly, or `~433 KB/region` for standalone `.dlsh` multi-span cache |
| Server LOD Extraction CPU | Background greedy-meshing + AO raycasting | `LOD-2` ($4^3$) extraction in `18.6 us/chunk` (`19 ms` per 1,024-chunk region) |
| Distant Horizon (`lowres`) Representation | 2D single-layer heightmap mesh | 3D multi-span columns ($K=4$ spans) + `64-bit` octree masks (`LOD-3` $8^3$ in `11.5 us/chunk`) |
| Live Block Edit Update | Re-read `.mca`, re-mesh chunk + neighbors, rewrite `.prbm` tile (`100--1,000+ ms`) | `165-byte` single-section delta via `uint16[24]` section pointer + `gen8` token (`< 5 us`) |
| Dynamic $Y$-Cutaway and Relighting | Requires re-baking `.prbm` (interior faces culled, lighting baked into vertices) | Client-side $Y$-slice and Gray-light shader uniform (`2.38 us` per section; separate `SkyLight` / `BlockLight` bitplanes) |

#### Progressive Client Streaming Stages (WebGL2 / WebGPU)
1. **Horizon Boot (`< 50 KB` initial payload):**
   - The client fetches the `.lca` Layer 2 analytical header (`LOD-3` $\to$ `LOD-1` progressive `DualLayerSpiralHilbertCurve` multi-span runs + `materialPacked` palette).
2. **Midground Octree Streaming (`LOD-2` $4^3$ & `LOD-1` $2^3$):**
   - As the camera moves, the map reader reads the 8-byte `octreeOccupancyMask64` and dominant palette IDs from visible sections via `uint16[24]` offsets (`11.5--18.6 us/chunk`, skipping `0x0000` air and `0x8000` uniform sections in $O(1)$).
3. **Near-Camera Full Voxel and Cutaway (`LOD-0`):**
   - For chunks near the camera or intersecting a $Y$-cutaway plane, the map reader streams the `.lca` section Delta-Roaring slices (`~150--300 B/section`). The client shader walks the `gap4|len4` runs into a 3D texture or instanced octree buffer and applies `SkyLight` + `BlockLight` bitplane shading.

### 7. Built-In Testing (`BIT`), Safety Tripwires & 4-Tier Bit-Rot Self-Healing (`REQ-RTP-S-004`, `REQ-RTP-S-005`)

#### 7.1 `PBIT` & Continuous Built-In Test Tripwires (`CBIT T1..T8`)
Following `anvil-api-ADR-002`, `LcaRegionReader` implements `BuiltInTestable`:
- **`PBIT` (Power-On Built-In Test):** On initialization, encodes and decodes a static 2-section micro-canary (1 uniform inline section + 1 non-uniform `H_3` Delta-Roaring section with `Y+H_2` Gray `SkyLight`) in `< 2 ms`, verifying bit-exact voxel/light parity and `octreeOccupancyMask64` equivalence.
- **`CBIT` (Continuous Built-In Test - $O(1)$ Read Tripwires `T1..T8`):**
  - `T1`: Verify `magic == 0x4C43415F56303031L` (`"LCA_V001"`) at `0x0000`, `formatVersion` in `{0x0001, 0x0002}`, and footer canary `0x4C4341315F454E44L` (`"LCA1_END"`) at `fileLength - 8`.
  - `T2`: Verify `headerChecksum` over superblock bytes `0x00..0x37`.
  - `T3`, `T3b` & `T3c`: Verify chunk pointer bounds `(slabOffset35 << 8) + exactByteLen20 <= fileLength - 8`, verify the self-describing ChunkFrame header canary `(rawDvWord & 0xFC00_0000) == 0x5800_0000` with embedded `((rawDvWord >>> 16) & 0x03FF) == chunkIdx` to detect pointer offset variation in $O(1)$ (`T3b`), and in `Mode B` (`codec1 = 1`) verify and heal every 64-bit word of the uncompressed `ChunkFrame` header (`headerLen = 24 + 2 * sectionCount` bytes) via its `headerWords = (headerLen + 7) >>> 3` Extended Hamming `(72, 64)` SEC-DED syndromes before inspecting `sectionOffsets[]` or decoding the `LCD2` (`0x4C44`) body (`T3c`).
  - `T4`: Enforce per-chunk uncompressed allocation ceiling (`<= 16 MiB`).
  - `T5`: Verify `sectionOffsets[i]` (scaled by `1B` or `4B` per `chunkFlags` bit `2`) stays strictly within `[sectionDirEnd .. exactByteLen20)` with no duplicate non-uniform section offsets, verify Mode B body magic `0x4C44` (`"LD"`), and verify chained dictionary continuation pointers (`nextSegmentRelSlabs32`) are acyclic (`<= 64` segments) and bounded within `fileLength - 8`.
  - `T6` & `T6b`: Verify Delta-Roaring run cursor $h$ never exceeds $4,096$ (`T6`), and verify that the 64-bit `decodedOctreeMask64` accumulated from minority voxel octants matches the stored `octreeOccupancyMask64` header word (`T6b` spatial syndrome check, detecting corrupted interior run bytes even when $h \le 4,096$).
  - `T7`: Verify `VarInt` palette indices stay strictly `< stateCount`.
  - `T8`: On lock-free concurrent `FileChannel` / `mmap` reads, pin the target `slabOffset35` in `PINNED_SLAB_STARTS`, read `ptrBefore = getVolatile(chunkIdx)`, decode/copy the target slab, issue `VarHandle.loadLoadFence()`, and re-read `ptrAfter = getVolatile(chunkIdx)`. Because live writers use Copy-on-Write (`CoW`) slab allocation with hazard-pointer retirement (`Section 2.3`), `ptrBefore == ptrAfter` guarantees a tear-free snapshot across 256-save `generation8` wraps.

#### 7.2 4-Tier Bit-Rot & Offset-Variation Recovery (`healRegionBitRot` & `readChunkViewWithRecovery`)
`.lca` defines four recovery tiers for pointer and payload corruption:
1. **Tier 1 - Extended Hamming `(72, 64)` SEC-DED Pointer, Dictionary & Mode B Header Healing (`< 10 ns`):**
   - Stores a `1,024-byte` SEC-DED syndrome table (`1 byte` per 64-bit chunk pointer) at `secDedTableOffset` (`superblock.flags` bit `4`), plus downward-growing `(72, 64)` SEC-DED syndromes at the tail of each Layer 2 dictionary segment (`segmentFlags8` bit `1` = `0x02`). Each data bit $b \in [0, 63]$ of `ptr64` is mapped to a unique 7-bit mask `SECDED_DATA_MASKS[b]` with popcount $\ge 2$, plus bit `7` as overall parity.
   - `LcaSectionCodec.healSingleBitFlip64` repairs single-bit flips in `slabOffset35`, `codec1`, `exactByteLen20`, `generation8`, or Layer 2 dictionary words in $O(1)$ (`word64 ^ (1L << corruptedBit)`) and detects double-bit errors (`DED`, returning `Long.MIN_VALUE` to trigger Tier 2).
2. **Tier 2 - Self-Describing `256B` Slab Reverse-Scan (`healRegionBitRot`):**
   - Every `ChunkFrame` begins with a self-describing 32-bit word `0x5800_0000 | (chunkIdx << 16) | chunkDataVersion`.
   - If a pointer in `0x0040..0x203F` suffers multi-bit corruption (`DED`), zeroing, or offset variation (`CBIT T3b` pointing to another chunk's slab), `LcaRegionReader.healRegionBitRot` scans the `256B`-aligned sub-slab pool from `slabPoolBaseOffset` to `fileLength - 8`, matches `0x5800_0000 | (chunkIdx << 16)`, measures the frame length, and reconstructs the 64-bit pointer and SEC-DED syndrome byte.
3. **Tier 3 - Section-Granular `octreeOccupancyMask64` Spatial Syndrome and Dominant `palette[0]` Quarantine (`readChunkViewWithRecovery`):**
   - If corruption occurs in a minority Delta-Roaring container inside section $Y$ (caught by `CBIT T6` cursor overflow or `CBIT T6b` `decodedOctreeMask64 != octreeOccupancyMask64`), `LcaRegionReader.readChunkViewWithRecovery` isolates the fault to that single $16^3$ section, substitutes its frequency-sorted dominant state `palette[0]` from the section header, records the repair in `RecoveryReport`, and leaves the remaining 23 vertical sections of the chunk intact.
4. **Tier 4 - Copy-on-Write (`CoW`) Prior-Generation Pointer Rollback (`rollbackCorruptChunkToPriorCoW`):**
   - Because live saves rotate to fresh `256B` CoW slabs before swapping the directory pointer, `LcaLiveArchive` retains `prevChunkPointers[1024]`, allowing $O(1)$ rollback to the previous verified generation if an interrupted write is detected.

## Benchmarks (`1_21_R1` & `overworld_mountain_r.0.0.mca`, `LcaRegionCodecTest`)

Measured in `LcaRegionCodecTest` with bit-exact voxel, biome, heightmap, and light round-trip parity across `.mca` fixtures (`1.20.4`, `1.21.4`, `26.1`, `26.3`) and random/sequential whole-region sweeps on the 64-chunk `1_21_R1` (`532,480 B` `.mca`) and `overworld_mountain_r.0.0.mca` (`495,616 B` `.mca`) benchmarks:

### 1. `1_21_R1` 64-Chunk Benchmark (`532,480 B` Vanilla `.mca`, `299,918 B` `.linear`)

| Workload / Metric (`1_21_R1`, `64` Chunks) | Vanilla `.mca` (`4K` `Zlib`) | `.linear` (`Zstd-1` Whole-Region) | `.lca` `v2` Mode A: `0-LZ` (Slab-Local Palette) | `.lca` `v2` Mode B: `LCD2` (`0x4C44` Atomic Dict) |
| :--- | ---: | ---: | ---: | ---: |
| Region Storage Size (`64` Chunks) | `532,480 B` (`100.0%`) | `299,918 B` (`56.3%`, `1.78x`) | `272,648 B` (`51.2%` of `.mca`, `1.95x`, `-9.1%` vs `.linear`) | `207,368 B` (`38.9%` of `.mca`, `2.57x`, `-30.9%` vs `.linear`) |
| Cold Random 1-Chunk Read (`Store -> AnvilChunkView`) | `90.26 us` | `1,303.75 us` (`1.30 ms`) | `25.28 us` (`3.57x` vs `.mca`, `51.6x` vs cold `.linear`) | `70.60 us` (`1.28x` vs `.mca`, `18.5x` vs cold `.linear`) |
| Warm Random 1-Chunk Read (`Cached -> AnvilChunkView`) | `90.26 us` | `32.79 us` | `24.80 us` (`1.32x` vs warm `.linear`, `3.64x` vs `.mca`) | `48.75 us` (`1.85x` vs `.mca`) |

### 2. `overworld_mountain_r.0.0.mca` (`495,616 B` Vanilla `.mca`, `318,806 B` `.linear`)

| Workload / Metric (`overworld_mountain_r.0.0.mca`, `64` Chunks) | Vanilla `.mca` (`4K` `Zlib`) | `.linear` (`Zstd-1` Whole-Region) | `.lca` `v2` Mode A: `0-LZ` (Slab-Local Palette) | `.lca` `v2` Mode B: `LCD2` (`0x4C44` Atomic Dict) |
| :--- | ---: | ---: | ---: | ---: |
| Region Storage Size (`64` Chunks) | `495,616 B` (`100.0%`) | `318,806 B` (`64.3%`, `1.55x`) | `290,056 B` (`58.5%` of `.mca`, `1.71x`, `-9.0%` vs `.linear`) | `233,224 B` (`47.1%` of `.mca`, `2.13x`, `-26.8%` vs `.linear`) |
| Cold Random 1-Chunk Read (`Store -> AnvilChunkView`) | `163.93 us` | `2,519.12 us` (`2.52 ms`) | `54.10 us` (`3.03x` vs `.mca`, `46.6x` vs cold `.linear`) | `88.20 us` (`1.86x` vs `.mca`, `28.6x` vs cold `.linear`) |
| Warm Random 1-Chunk Read (`Cached -> AnvilChunkView`) | `163.93 us` | `64.26 us` | `52.67 us` (`1.22x` vs warm `.linear`, `3.11x` vs `.mca`) | `68.10 us` (`2.41x` vs `.mca`) |
| Cold Whole-Region Sequential Sweep (`All 64 Chunks`) | `7.75 ms` (`121.14 us/ch`) | `6.14 ms` (`95.92 us/ch`) | `3.67 ms` (`57.27 us/ch` - `1.67x` vs `.linear`, `2.12x` vs `.mca`) | `4.46 ms` (`69.67 us/ch` - `1.38x` vs `.linear`, `1.74x` vs `.mca`) |
| Warm Whole-Region Sequential Sweep (`All 64 Chunks`) | `7.75 ms` (`121.14 us/ch`) | `4.14 ms` (`64.61 us/ch`) | `3.58 ms` (`55.87 us/ch` - `1.16x` vs warm `.linear`, `2.17x` vs `.mca`) | `4.46 ms` (`69.67 us/ch`) |
| Cold Whole-Region Terrain Probe Sweep (`Y=[48..112]`) | `7.89 ms` (`123.33 us/ch`) | `5.84 ms` (`91.32 us/ch`) | `0.48 ms` (`7.47 us/ch` - `12.23x` vs `.linear`, `16.51x` vs `.mca`) | `~1.05 ms` |
| Random RTP Terrain Window Probe (`Y=[48..112]`) | `109.72 us` | `64.26 us` (warm) | `8.55 us` (`12.84x` vs `.mca`) | `~11.5 us` |
| Partial 1-Section Read (`16^3` `H_3` / Inline Uniform) | `109.72 us` | `64.26 us` (warm) | `1.39 us` (`0.93 us` uniform - `78.9x` to `118.6x` vs `.mca`) | `~3.40 us` |
| Dirty Chunk Save (CPU Latency) | `444.83 us` | `48,991--205,789 us` | `390.00 us` (`1.14x` vs `.mca`, `>120x` vs `.linear`) | `~554.0 us` (`LCD2` continuous atomic dict) |
| 10,000 Concurrent CoW Saves Slab Reuse & Torn Reads | `4.20 -> 18.69 MB` (`82.3%`) | Rewrites `43.4 GB` total | `100.00%` (`10,000 / 10,000`) reuse, `0 B` growth, `0` torn reads | `100.00%` reuse, `0` torn reads |

## Alternatives Considered

| Alternative | Why Rejected |
| :--- | :--- |
| Keep Vanilla `.mca` (`4 KiB` sector `Zlib` NBT) | Spends `45%--60%` of chunk load/save CPU time in `inflate()`/`deflate()` and sequential NBT tag parsing, leaves `31.4%` of disk sectors as $4\text{ KiB}$ padding, and grows to `18.69 MB` after $10,000$ saves. |
| Use `.linear` (whole-region `Zstd` over vanilla NBT) | Reduces disk footprint by `~30%--41%`, but requires `26.4 ms` for cold single-chunk reads and `49--206 ms` for single dirty chunk saves (`405x` write amplification). |
| Pre-bake BlueMap `.prbm` Float32 Triangle Meshes | Duplicates world storage at `~58 MB/region` raw, flattens distant horizons into a 1-layer `lowres` mesh, and cannot slice $Y$-levels or relight without CPU re-meshing. |
| Merge into Full-Column (`16 x 384 x 16`) Palettes | `air`, `stone`, and `deepslate` compete in the same column palette, reducing implicit `palette[0]` omission from `90.8%` to `68.3%`, increasing size by `+58.7%` (`4,687.5 B/chunk`), and slowing single-section partial reads from `2.38 us` to `46.77 us`. |
| Byte-Exact Offsets (`0` Slack, No Sub-Slab Free-List) | Without `256B` sub-slab rounding and free-list reuse, growing chunks spill to EOF on `24.4%` of saves, growing a `3.96 MB` region to `14.84 MB` over $10,000$ saves (`256B` sub-slabs achieve `95.0%` reuse and cap size at `6.80 MB`). |
| Palletized Block-ID Gray Bitplanes or Brute-Force 4-Curve Trial | Gray-coding arbitrary palette IDs is `+30.3%` larger than per-state Delta-Roaring (`3,847.4 B/chunk` vs. `2,952.9 B/chunk`), and brute-force 4-curve trial encoding costs `837.8 us/chunk` (`3.97x`) compared to `228.2 us/chunk` (`1.08x`) for the 1-pass gradient predictor. |
| Pure inverted per-palette Roaring bitmaps with no flat fallback | In dense sections with $50+$ distinct block states in one $16^3$ section, per-state container headers add overhead; the `leafrtp-ADR-072` hybrid crossover ceiling bounds worst-case sections at the flat bit-packed size. |

## Consequences

- **Positive:**
  - **Storage Footprint:** Uncompressed `.lca` `v2` Mode A (`272,648 B` on `1_21_R1`, `51.2%` of `.mca`; `290,056 B` on `overworld_mountain_r.0.0.mca`, `58.5%` of `.mca`) is `-9.0%` to `-9.1%` smaller than whole-region `.linear Zstd-1` (`299,918 B` / `318,806 B`) without any LZ compression overhead; Mode B (`LCD2` `0x4C44` Continuous Atomic Layer 2 Region Dictionary, `207,368 B` / `233,224 B`) is pure Java with zero Zstd dependency and reduces size by `52.9%--61.1%` vs. `.mca` (`2.13x--2.57x`) and `26.8%--30.9%` vs. `.linear`.
  - **Read, Save, and LOD Latency:** Supports `25.28--54.10 us` cold 1-chunk reads (`24.80--52.67 us` warm), `0.93--1.39 us` single-section reads, `390.0 us` single dirty chunk saves (`~554.0 us` in Mode B `LCD2`), and `18.6 us` / `11.5 us` `LOD-2` / `LOD-3` extraction.
  - **Direct Map Streaming:** Streams multi-span Hilbert columns, `octreeOccupancyMask64` octants, and `165 B` single-section deltas directly from `.lca` without separate `.prbm` mesh files.
  - **In-File Slab Reuse:** The `uint64[1024]` atomic `256B` slab pointer directory and `uint16[24]` per-chunk section directory enable lock-free reader visibility and `95.0%--100.0%` slab reuse across $10,000$ saves.
- **Negative / Trade-offs:**
  - Requires static `4,096`-entry curve permutation tables (`H_3`, `Y+H_2`, `XZY` = `24 KiB` total) and an in-memory `TreeMap` slab allocator per open region file (`~25.6 KiB` header residency), plus periodic idle compaction when section-delta tail rings are enabled.

## References

- [anvil-api-ADR-001](anvil-api-ADR-001-anvil-subsystem.md) - Anvil Read-Only Subsystem
- [anvil-api-ADR-002](anvil-api-ADR-002-multi-format-region-support.md) - Multi-Format Region Support (`.mca` & `.linear`)
- [leafrtp-ADR-065](../../../../resource/LeafRTP/docs/adr/leafrtp-ADR-065-layered-spiral-hilbert-rle-index.md) - Layered Spiral-Hilbert RLE Index
- [leafrtp-ADR-072](../../../../resource/LeafRTP/docs/adr/leafrtp-ADR-072-hybrid-bit-packed-and-16-bit-run-container-hazard-table.md) - Hybrid Bit-Packed and 16-Bit Run Container Hazard Table
- [leafconfig-ADR-001](../../../../resource/LeafConfig/docs/adr/leafconfig-ADR-001-universal-server-config-atlas-and-web-ide.md) - Universal Server Config Atlas & 3D World Map Workspace
- `io.github.dailystruggle.anvil.LcaRegionReader`, `io.github.dailystruggle.anvil.LcaRegionWriter`, `io.github.dailystruggle.anvil.LcaSectionCodec`, and `io.github.dailystruggle.anvil.LcaRegionCodecTest` (`api/anvil-api`)
