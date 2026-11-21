# `.lca` (LeafCraft Anvil) Binary Format Interface Control Document (ICD)

- **Document ID:** `LCA-ICD-3.0` (superseding `LCA-ICD-1.0` `0x0002`, backward-compatible read)
- **Format Version:** `0x0003` (`LCA_V001`, Major `3`, Minor `0`; readers accept `{0x0002, 0x0003}`)
- **File Naming:** `r.<regionX>.<regionZ>.lca` (under `region/`, `entities/`, or `poi/`)
- **Governing Specifications:** [`anvil-api-ADR-004`](adr/anvil-api-ADR-004-overlaid-eikonal-light-columnar-residuals-and-adaptive-rank-exclusion.md), [`anvil-api-ADR-005`](adr/anvil-api-ADR-005-performance-gated-entropy-and-prediction-carryovers.md), [`anvil-api-ADR-003`](adr/anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md), [`anvil-api-ADR-002`](adr/anvil-api-ADR-002-multi-format-region-support.md), `leafanvil-ADR-001` (`resource/LeafAnvil`)
- **Reference Implementation:** `io.github.dailystruggle.anvil.LcaRegionReader`, `LcaRegionWriter`, `LcaSectionCodec`, `LcaV3Codec` ([`api/anvil-api`](../README.md), MIT License)

---

## Document & Data Structure Index

### A. Quick Data-Header & Offset Map (On-Disk Binary Structures -> Spec Section)

| On-Disk Structure / Data Header | Byte Offset / Location | Size | Governing Section Link |
| :--- | :--- | :--- | :--- |
| **Superblock Header (`"LCA_V001"`)** | File `0x0000 .. 0x003F` | `64 B` | [Section 4.1 Superblock Header (`0x0000 .. 0x003F`)](#41-superblock-header-0x0000--0x003f-64-bytes) |
| **Superblock 64-Bit Checksum (`CBIT T2`)** | File `0x0038 .. 0x003F` | `8 B` | [Section 4.2 Superblock Checksum Algorithm](#42-superblock-checksum-algorithm-0x380x3f-cbit-t2) |
| **Atomic Chunk Pointer Directory (`uint64[1024]`)** | File `0x0040 .. 0x203F` | `8,192 B` | [Section 4.3 Atomic Chunk Pointer Directory](#43-atomic-chunk-pointer-directory-0x0040--0x203f-uint641024) |
| **Root Region Shared Dictionary Segment** | File `0x2040 .. 0x2040 + cap - 1` | `1,024 B+` (`256B` slack) | [Section 5.1 Dictionary Segment Layout](#51-dictionary-segment-layout-root-or-continuation) |
| **Dictionary Continuation Link Trailer** | `segmentStart + cap - 8` | `8 B` (`uint64`) | [Section 5.2 Chained Continuation Link Trailer](#52-chained-continuation-link-trailer-nextsegmentlinktrailer) |
| **`dictPatternArena` (`384 B` Huffman + LZ Patterns)** | `DictSegment` `[entriesEnd .. 8 + usedPayloadBytes)` | `384 B .. 16,384 B` | [Section 5.3 Mode B Pattern Arena & Canonical Huffman](#53-mode-b-pattern-arena-dictpatternarena--384-byte-canonical-huffman-tables) |
| **Dictionary Downward-Growing `SEC-DED` Syndromes** | `segmentStart + cap - 8 - 1 - w` | `1 B / 64-bit word` | [Section 5.4 Downward-Growing Dictionary `SEC-DED`](#54-downward-growing-dictionary-extended-hamming-72-64-sec-ded-syndromes) |
| **Extended Hamming `(72, 64)` SEC-DED Syndrome Table** | `secDedTableByteOffset` (`0x2440` default) | `1,024 B` (`1 B/chunk`) | [Section 4.4 Extended Hamming `(72, 64)` SEC-DED Table](#44-extended-hamming-72-64-sec-ded-pointer-syndrome-table-1024-bytes) |
| **`ChunkFrame` Uncompressed Header & Canary** | `slabOffset35 << 8` (`+0x00 .. +headerLen-1`) | `24 + 2 * sectionCount B` | [Section 6.1 Uncompressed `ChunkFrame` Header](#61-uncompressed-chunkframe-header-headerlen--24--2--sectioncount-bytes) |
| **`Mode B` Header SEC-DED + `LCD2` Frame Body** | `(slabOffset35 << 8) + headerLen` | `headerWords B + LCD2` | [Section 6.2 Mode B (`codec1 = 1`) Wrapper](#62-mode-b-codec1--1-header-sec-ded-syndromes--continuous-atomic-region-dictionary-lcd2-body-decompression) |
| **`chunkStatusOrdinal` Header Byte** | `ChunkFrame +0x14` | `1 B` (`uint8`) | [Section 6.3 `chunkStatusOrdinal` Mapping Table](#63-chunkstatusordinal-mapping-table-0x14) |
| **Vertical Section Offset Directory (`uint16[N]`)** | `ChunkFrame +0x18 .. +headerLen-1` | `2 * sectionCount B` | [Section 6.4 Decoding `uint16 sectionOffsets[i]`](#64-decoding-uint16-sectionoffsetsi-i--0--sectioncount---1-y--minsectiony--i) |
| **Slab-Local Chunk Micro-Palette (`Part A` & `Part B`)** | `ChunkFrame + sectionDirEnd` | Variable (unpadded) | [Section 6.5 Slab-Local Chunk Micro-Palette](#65-slab-local-chunk-micro-palette-part-a--part-b) |
| **$16 \times 16 \times 16$ Non-Uniform `SectionFrame` Header** | `ChunkFrame + relStart_i` | `1 B` (`sectionHeader`) + optional `8 B` (`octreeMask64`) | [Section 7 Layer 4: $16 \times 16 \times 16$ Non-Uniform `SectionFrame` Layout](#7-layer-4-16-times-16-times-16-non-uniform-sectionframe-layout) |
| **3D Space-Filling Curves (`YZX`, `H_3`, `Y+H_2`, `XZY`)** | Selected by `sectionHeader` bits `7..6` | `4,096` voxel permutation | [Section 7.1 3D Space-Filling Curve Permutations](#71-3d-space-filling-curve-permutations-curveid--03) |
| **Minority State Delta-Roaring Containers (`00..11`)** | `SectionFrame` BlockState Payload (`mode 01`) | `2 B .. 514 B` per state | [Section 7.2 Minority State Delta-Roaring Containers](#72-minority-state-delta-roaring-containers-blockencodingmode--01-or-11) |
| **Flat Bit-Packed Voxel Ceiling (`leafrtp-ADR-072`)** | `SectionFrame` BlockState Payload (`mode 10`) | `ceil(4096 * b / 8) B` | [Section 7.3 Flat Bit-Packed Section Ceiling](#73-flat-bit-packed-section-ceiling-blockencodingmode--10) |
| **Const Nibble & 4-Bitplane Gray-Code Lighting** | `SectionFrame` Lighting Payload | `0 B`, `1 B`, Gray, or `2,048 B` | [Section 7.4 Gray-Code Light Bitplanes](#74-gray-code-light-bitplanes-skylightmode--10-or-blocklightmode--10) |
| **Residual Tail (`boilerplateFlags16` & `MOTION_BLOCKING_NO_LEAVES`)** | `ChunkFrame + residualRelStart` | Variable to `residualRelEnd` | [Section 8 Layer 3c: Residual Tail](#8-layer-3c-residual-tail-motion_blocking_no_leaves--boilerplateflags16) |
| **Binary Heightmap Delta & Cross-Delta Streams** | `ResidualTail +0x02` (`bpFlags` bit `13` & bit `9`) | `~110 B` (`mbnl`) + secondary | [Section 8.1 Binary Heightmap Delta Streams](#81-binary-heightmap-delta-streams-mbnlstream--secondary-heightmaps) |
| **`boilerplateFlags16` Residual Bitmask & Mojang NBT** | `ResidualTail +0x00` | `2 B` flags + fields | [Section 8.2 `boilerplateFlags16` Complete Bit Reference](#82-boilerplateflags16-complete-bit-reference) |
| **`ChunkPatchTail` (`HAS_CHUNK_PATCH_TAIL = 0x4000`)** | `ResidualTail` (`bpFlags` bit `14`, before bit `10`) | `VarInt len` + patch payload | [Section 8.3 `ChunkPatchTail` Wire Layout](#83-chunkpatchtail-wire-layout-boilerplateflags16-bit-14--0x4000-has_chunk_patch_tail) |
| **Static Core Token Tables (`160` Blocks, `65` Biomes, `128` Props)** | Referenced by `1-byte` tokens (`0x80\|id`, `0x60\|id`) | Constant lookup tables | [Section 9 Static Core Lookup Tables (`STATIC_CORE_*`)](#9-static-core-lookup-tables-static_core_) |
| **Footer Canary (`"LCA1_END"`)** | `fileLength - 8 .. fileLength - 1` | `8 B` (`uint64`) | [Section 3 Top-Level File Layout](#3-top-level-file-layout-rrxrzlca) |

### B. Table of Contents

1. [Purpose & Scope](#1-purpose--scope)
2. [Primitive Data Types & Endianness Conventions](#2-primitive-data-types--endianness-conventions)
   - [2.1 `VarInt` Reference Pseudocode](#21-varint-reference-pseudocode)
   - [2.2 `Bounded240` Gap Reference Pseudocode](#22-bounded240-gap-reference-pseudocode)
3. [Top-Level File Layout (`r.<rx>.<rz>.lca`)](#3-top-level-file-layout-rrxrzlca)
4. [Layer 1: Superblock (`64 B`), Pointer Directory (`8,192 B`) & `SEC-DED` Table (`1,024 B`)](#4-layer-1-superblock-64-b-pointer-directory-8192-b--sec-ded-table-1024-b)
   - [4.1 Superblock Header (`0x0000 .. 0x003F`, 64 Bytes)](#41-superblock-header-0x0000--0x003f-64-bytes)
   - [4.2 Superblock Checksum Algorithm (`0x38..0x3F`, `CBIT T2`)](#42-superblock-checksum-algorithm-0x380x3f-cbit-t2)
   - [4.3 Atomic Chunk Pointer Directory (`0x0040 .. 0x203F`, `uint64[1024]`)](#43-atomic-chunk-pointer-directory-0x0040--0x203f-uint641024)
   - [4.4 Extended Hamming `(72, 64)` SEC-DED Pointer Syndrome Table (`1,024 Bytes`)](#44-extended-hamming-72-64-sec-ded-pointer-syndrome-table-1024-bytes)
5. [Layer 2: Chained Region Shared Dictionary (`0x2040`)](#5-layer-2-chained-region-shared-dictionary-0x2040)
   - [5.1 Dictionary Segment Layout (`Root` or `Continuation`)](#51-dictionary-segment-layout-root-or-continuation)
   - [5.2 Chained Continuation Link Trailer (`nextSegmentLinkTrailer`)](#52-chained-continuation-link-trailer-nextsegmentlinktrailer)
   - [5.3 Mode B Pattern Arena (`dictPatternArena`) & `384-Byte` Canonical Huffman Tables](#53-mode-b-pattern-arena-dictpatternarena--384-byte-canonical-huffman-tables)
   - [5.4 Downward-Growing Dictionary Extended Hamming `(72, 64)` `SEC-DED` Syndromes](#54-downward-growing-dictionary-extended-hamming-72-64-sec-ded-syndromes)
6. [Layer 3: Self-Describing `ChunkFrame` Header, Mode B Wrapper & Section Directory](#6-layer-3-self-describing-chunkframe-header-mode-b-wrapper--section-directory)
   - [6.1 Uncompressed `ChunkFrame` Header (`headerLen = 24 + 2 * sectionCount` Bytes)](#61-uncompressed-chunkframe-header-headerlen--24--2--sectioncount-bytes)
   - [6.2 Mode B (`codec1 = 1`) Header SEC-DED Syndromes & Continuous Atomic Region Dictionary (`LCD2`) Body Decompression](#62-mode-b-codec1--1-header-sec-ded-syndromes--continuous-atomic-region-dictionary-lcd2-body-decompression)
   - [6.3 `chunkStatusOrdinal` Mapping Table (`+0x14`)](#63-chunkstatusordinal-mapping-table-0x14)
   - [6.4 Decoding `uint16 sectionOffsets[i]` (`i = 0 .. sectionCount - 1`, `Y = minSectionY + i`)](#64-decoding-uint16-sectionoffsetsi-i--0--sectioncount---1-y--minsectiony--i)
   - [6.5 Slab-Local Chunk Micro-Palette (`Part A` + `Part B`)](#65-slab-local-chunk-micro-palette-part-a--part-b)
7. [Layer 4: $16 \times 16 \times 16$ Non-Uniform `SectionFrame` Layout](#7-layer-4-16-times-16-times-16-non-uniform-sectionframe-layout)
   - [7.1 3D Space-Filling Curve Permutations (`curveId = 0..3`)](#71-3d-space-filling-curve-permutations-curveid--03)
   - [7.2 Minority State Delta-Roaring Containers (`blockEncodingMode == 01`)](#72-minority-state-delta-roaring-containers-blockencodingmode--01-or-11)
   - [7.3 Flat Bit-Packed Section Ceiling (`blockEncodingMode == 10`)](#73-flat-bit-packed-section-ceiling-blockencodingmode--10)
   - [7.4 Gray-Code Light Bitplanes (`skyLightMode == 10` or `blockLightMode == 10`)](#74-gray-code-light-bitplanes-skylightmode--10-or-blocklightmode--10)
8. [Layer 3c: Residual Tail (`MOTION_BLOCKING_NO_LEAVES` & `boilerplateFlags16`)](#8-layer-3c-residual-tail-motion_blocking_no_leaves--boilerplateflags16)
   - [8.1 Binary Heightmap Delta Streams (`mbnlStream` & Secondary Heightmaps)](#81-binary-heightmap-delta-streams-mbnlstream--secondary-heightmaps)
   - [8.2 `boilerplateFlags16` Complete Bit Reference](#82-boilerplateflags16-complete-bit-reference)
   - [8.3 `ChunkPatchTail` Wire Layout (`boilerplateFlags16` Bit `14` = `0x4000`, `HAS_CHUNK_PATCH_TAIL`)](#83-chunkpatchtail-wire-layout-boilerplateflags16-bit-14--0x4000-has_chunk_patch_tail)
9. [Static Core Lookup Tables (`STATIC_CORE_*`)](#9-static-core-lookup-tables-static_core_)
   - [9.1 `STATIC_CORE_STATES` (`160` Base Block Identifiers)](#91-static_core_states-160-base-block-identifiers)
   - [9.2 `STATIC_CORE_BIOMES` (`65` Biome Identifiers)](#92-static_core_biomes-65-biome-identifiers)
   - [9.3 `STATIC_CORE_PROP_SUFFIXES` (`128` Property Suffixes)](#93-static_core_prop_suffixes-128-property-suffixes)
10. [Reader & Recovery Procedures](#10-reader--recovery-procedures)
    - [10.1 Standard Chunk Read Procedure](#101-standard-chunk-read-procedure)
    - [10.2 4-Tier Bit-Rot Recovery & Self-Describing Slab Reverse-Scan](#102-4-tier-bit-rot-recovery--self-describing-slab-reverse-scan)

---

## 1. Purpose & Scope

This Interface Control Document (ICD) defines the on-disk binary specification for **`.lca` (LeafCraft Anvil)** region archive files (`r.<rx>.<rz>.lca`). It specifies all header layouts, bitfield allocations, space-filling curve equations, variable-length integer encodings, checksum/parity formulas, and static token lookup tables required for external tools, map renderers, backup utilities, or world editors to read, validate, and write `.lca` archives.

> **Open Format & Interoperability License (`MIT`):** While the `LeafAnvil` runtime carrier plugin/mod (`net.leaf.anvil.*`, `resource/LeafAnvil`) is dual-licensed (`PolyForm-Noncommercial-1.0.0` for personal use, research, and private testing from source; `LicenseRef-LeafCraft-Commercial` for commercial/monetized servers), this `.lca` binary specification (`LCA-ICD-1.0`), `LeafAnvil-Net`, `rust/lca-format`, and the `io.github.dailystruggle.anvil` codec library (`api/anvil-api`) are openly licensed under the **MIT License**. Third-party plugins, world generators, server forks, map renderers, and backup utilities may freely implement or reverse-engineer `.lca` readers and writers without restriction.

---

## 2. Primitive Data Types & Endianness Conventions

Unless explicitly noted otherwise, all multi-byte fixed-width integers in headers, directories, and trailers are **Big-Endian (`BE`, network byte order)**:

| Type | Width | Signedness | Byte Order & Encoding |
| :--- | :--- | :--- | :--- |
| `uint8` / `byte` | `1 B` | Unsigned (`0..255`) | Single byte |
| `int8` | `1 B` | Signed (`-128..127`) | Two's complement |
| `uint16` | `2 B` | Unsigned (`0..65,535`) | Big-Endian (`(b0 << 8) \| b1`) |
| `int32` | `4 B` | Signed (`-2^31..2^31-1`) | Big-Endian (`(b0 << 24) \| (b1 << 16) \| (b2 << 8) \| b3`) |
| `uint32` | `4 B` | Unsigned (`0..2^32-1`) | Big-Endian |
| `int64` / `uint64` | `8 B` | 64-bit integer | Big-Endian (`MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN)`) |
| `VarInt` | `1..5 B` | Unsigned (`0..2^31-1`) | 7-bit little-endian continuation groups (LEB128): bits `6..0` carry payload, bit `7` (`0x80`) = `1` if more bytes follow, `0` on the final byte |
| `Bounded240` | `1..2 B` | Unsigned (`0..4,095`) | If `val < 240`: 1 byte `uint8(val)`. If `240 <= val <= 4095`: 2 bytes `[0xF0 \| ((val >>> 8) & 0x0F), val & 0xFF]` |
| `UTF-8 String` | Variable | UTF-8 bytes | In `.lca` structures: `VarInt byteLength` followed by `byte[byteLength]`. In Mojang NBT blobs: `uint16 byteLength` (BE) followed by `byte[byteLength]` |

### 2.1 `VarInt` Reference Pseudocode
```java
int readVarInt(byte[] buf, int pos) {
    int val = 0, shift = 0;
    while (true) {
        int b = buf[pos++] & 0xFF;
        val |= (b & 0x7F) << shift;
        if ((b & 0x80) == 0) return val;
        shift += 7; // Malformed if shift > 28
    }
}
```

### 2.2 `Bounded240` Gap Reference Pseudocode
```java
int readBoundedGap240(byte[] buf, int pos) {
    int b0 = buf[pos] & 0xFF;
    if (b0 < 240) return b0; // Consumes 1 byte
    return ((b0 & 0x0F) << 8) | (buf[pos + 1] & 0xFF); // Consumes 2 bytes
}
```

---

## 3. Top-Level File Layout (`r.<rx>.<rz>.lca`)

Every `.lca` file covers a $32 \times 32$-chunk ($512 \times 512$-block) region where `regionX = floor(chunkX / 32)` and `regionZ = floor(chunkZ / 32)`.

```
+-----------------------------------------------------------------------+ Offset 0x0000
| Layer 1a: Superblock Header (64 bytes, 0x0000 .. 0x003F)              |
+-----------------------------------------------------------------------+ Offset 0x0040
| Layer 1b: Atomic Chunk Pointer Directory: uint64[1024] (8,192 bytes)  |
+-----------------------------------------------------------------------+ Offset 0x2040
| Layer 2a: Root Shared BlockState & Biome String Dictionary            |
|           (Default 1,024 B slack-padded segment; ends with 8B link)   |
+-----------------------------------------------------------------------+ Offset secDedTableOffset (default 0x2440)
| Layer 2b: Extended Hamming (72, 64) SEC-DED Syndrome Table (1,024 B)  |
+-----------------------------------------------------------------------+ Offset slabPoolBaseOffset (256B-aligned, default 0x2840)
| Layer 3 & 4: 256-Byte Sub-Slab Pool                                   |
|              - Self-Describing ChunkFrames (256B-aligned)             |
|              - Chained Dictionary Continuation Segments (if any)      |
+-----------------------------------------------------------------------+ Offset fileLength - 8
| Footer Canary: uint64 (0x4C4341315F454E44 = "LCA1_END" ASCII, 8 B)    |
+-----------------------------------------------------------------------+ Offset fileLength
```

---

## 4. Layer 1: Superblock (`64 B`), Pointer Directory (`8,192 B`) & `SEC-DED` Table (`1,024 B`)

### 4.1 Superblock Header (`0x0000 .. 0x003F`, 64 Bytes)

| Byte Offset | Type | Field Name | Value / Invariant |
| ---: | :--- | :--- | :--- |
| `0x00..0x07` | `uint64` | `magic` | `0x4C43415F56303031L` (`"LCA_V001"` ASCII) |
| `0x08..0x09` | `uint16` | `formatVersion` | `0x0002` (Major `2`, Minor `0`) |
| `0x0A` | `uint8` | `storageKind` | `0` = `REGION_VOXEL` (`region/`), `1` = `ENTITIES` (`entities/`), `2` = `POI` (`poi/`), `3` = `MAP_TILE` |
| `0x0B` | `uint8` | `flags` | Bitmask:<br>- Bit `0` (`0x01`): `HAS_SHARED_DICT` (always `1`)<br>- Bit `1` (`0x02`): `HAS_LOD_HEADER` (`0` in standard region files; `.dlsh` sidecars used)<br>- Bit `2` (`0x04`): `HAS_TAIL_DELTA_RING` (`0`)<br>- Bit `3` (`0x08`): `DEFAULT_MODE_B` (`1` if region defaults to Mode B `LCD2` `0x4C44` atomic dictionary frames)<br>- Bit `4` (`0x10`): `HAS_SECDED_PARITY` (`1` when `1,024 B` SEC-DED table is present) |
| `0x0C..0x0F` | `int32` | `regionX` | Signed region $X$ coordinate ($\lfloor \text{chunkX} / 32 \rfloor$) |
| `0x10..0x13` | `int32` | `regionZ` | Signed region $Z$ coordinate ($\lfloor \text{chunkZ} / 32 \rfloor$) |
| `0x14..0x17` | `int32` | `minSectionY` | Minimum vertical section $Y$ index (e.g. `-4` for Overworld $Y = -64$, `0` for Nether/End $Y = 0$) |
| `0x18..0x19` | `uint16` | `sectionCount` | Vertical $16^3$ sections per chunk (`1..128`; e.g. `24` for Overworld $Y = -64..319$, `16` for $Y = 0..255$) |
| `0x1A..0x1B` | `uint16` | `slabShift` | Sub-slab byte alignment shift: **must be `8`** ($2^8 = 256\text{ B}$) |
| `0x1C..0x1F` | `int32` | `defaultDataVersion` | Default Mojang `DataVersion` (e.g. `3953` for 1.21, `4671` for 1.21.4 / 26.1) |
| `0x20..0x27` | `uint64` | `dictOffsetAndLen` | High 32 bits (`bits 63..32`): root `dictCapacityBytes` (`256B`-aligned, default `1,024`).<br>Low 32 bits (`bits 31..0`): `dictByteOffset` (`0x00002040`) |
| `0x28..0x2F` | `uint64` | `lodHeaderOffsetAndLen` | High 32 bits (`bits 63..32`): `secDedTableByteLength` (`>= 1,024`).<br>Low 32 bits (`bits 31..0`): `secDedTableByteOffset` (default `0x2040 + dictCapacityBytes`) |
| `0x30..0x37` | `uint64` | `slabPoolBaseOffset` | `256`-byte aligned byte offset where the sub-slab pool begins |
| `0x38..0x3F` | `uint64` | `headerChecksum` | 64-bit SplitMix/XXH64 checksum over bytes `0x00..0x37` (see Section 4.2) |

### 4.2 Superblock Checksum Algorithm (`0x38..0x3F`, `CBIT T2`)

The 64-bit `headerChecksum` at `0x38..0x3F` is computed over the seven big-endian `uint64` words at `0x00..0x37`:

```java
static long computeSuperblockChecksum64(byte[] buf, int offset) {
    long h = 0x9E3779B97F4A7C15L;
    for (int i = 0; i < 7; i++) {
        long k = readUint64BE(buf, offset + (i << 3));
        k *= 0xBF58476D1CE4E5B9L;
        k = Long.rotateLeft(k, 31);
        k *= 0x94D049BB133111EBL;
        h ^= k;
        h = Long.rotateLeft(h, 27) * 5 + 0x52DCE729L;
    }
    h ^= h >>> 33;
    h *= 0xFF51AFD7ED558CCDL;
    h ^= h >>> 33;
    h *= 0xC4CEB9FE1A85EC53L;
    h ^= h >>> 33;
    return h;
}
```

### 4.3 Atomic Chunk Pointer Directory (`0x0040 .. 0x203F`, `uint64[1024]`)

For a chunk at local region coordinates `localX = chunkX & 31` (`0..31`) and `localZ = chunkZ & 31` (`0..31`):
- **Directory Index:** `chunkIdx = localX | (localZ << 5)` (`0..1023`)
- **Directory Byte Offset:** `0x0040 + (chunkIdx << 3)`

Each 64-bit big-endian pointer word `ptr64` is packed as:

```
 63                                 29 28 27                     8 7           0
+-------------------------------------+--+------------------------+-------------+
|            slabOffset35             |c1|     exactByteLen20     | generation8 |
+-------------------------------------+--+------------------------+-------------+
```

| Bits | Width | Field | Formula & Semantics |
| :--- | :--- | :--- | :--- |
| `63..29` | `35 bits` | `slabOffset35` | `(ptr64 >>> 29) & 0x7_FFFF_FFFFL`. File byte offset of the chunk's `ChunkFrame` is `slabOffset35 << 8` (256-byte units, addressing up to `8 TiB`). **`0` = ungenerated / absent chunk** (`ptr64 == 0L`). |
| `28` | `1 bit` | `codec1` | `(int) ((ptr64 >>> 28) & 1L)`.<br>- `0` = **Mode A**: Uncompressed `ChunkFrame` (`0-LZ` direct random access).<br>- `1` = **Mode B**: Uncompressed `ChunkFrame` header + `headerWords` SEC-DED bytes + Continuous Atomic Region Dictionary (`LCD2`, `0x4C44`) compressed body (Section 6.2). |
| `27..8` | `20 bits` | `exactByteLen20` | `(int) ((ptr64 >>> 8) & 0xFFFFFL)`. Exact on-disk byte length of the `ChunkFrame` (`1 .. 1,048,575 B`). Number of 256-byte sub-slabs occupied on disk is `allocatedSlabs = (exactByteLen20 + 255) >>> 8`. |
| `7..0` | `8 bits` | `generation8` | `(int) (ptr64 & 0xFFL)`. Monotonically incrementing wrapping save counter (`0..255`) used for lock-free ABA read verification and cache invalidation. |

### 4.4 Extended Hamming `(72, 64)` SEC-DED Pointer Syndrome Table (`1,024 Bytes`)

When `superblock.flags` bit `4` (`HAS_SECDED_PARITY = 0x10`) is set, `1,024` bytes at `secDedTableByteOffset` (`superblock[0x28..0x2F]` low 32 bits) store 1 syndrome byte per `chunkIdx` (`0..1023`):

1. **Code Construction (`SECDED_DATA_MASKS[64]`):**
   Iterate `code = 3, 4, 5, ...` in ascending integer order. Whenever `Integer.bitCount(code) >= 2`, assign `SECDED_DATA_MASKS[bitIdx++] = code` until all `64` data bits (`bitIdx = 0..63`) have been assigned (this selects the first 64 integers in `[3..127]` having at least two `1`-bits).
2. **Syndrome Computation (`uint8`):**
   ```java
   int computeHammingSecDed8(long word64) {
       int syn7 = 0;
       for (int b = 0; b < 64; b++) {
           if (((word64 >>> b) & 1L) != 0L) syn7 ^= SECDED_DATA_MASKS[b];
       }
       int overallParity = (Long.bitCount(word64) ^ Integer.bitCount(syn7)) & 1;
       return (overallParity << 7) | syn7;
   }
   ```
3. **Single-Bit Healing (`SEC`) & Double-Bit Detection (`DED`):**
   Given `word64` and `storedSyndrome8`:
   - Let `diff8 = (computeHammingSecDed8(word64) ^ storedSyndrome8) & 0xFF`. If `diff8 == 0`, `word64` is error-free.
   - Let `errSyn7 = diff8 & 0x7F` and `totalParity = (Long.bitCount(word64) ^ Integer.bitCount(storedSyndrome8 & 0x7F) ^ ((storedSyndrome8 >>> 7) & 1)) & 1`.
   - If `totalParity == 1`:
     - If `Integer.bitCount(errSyn7) <= 1`, the single-bit flip occurred inside `storedSyndrome8` itself; `word64` is valid as-is.
     - Otherwise, look up `corruptedBit` where `SECDED_DATA_MASKS[corruptedBit] == errSyn7` and repair `word64 ^= (1L << corruptedBit)`.
   - If `totalParity == 0` and `diff8 != 0`, a multi-bit (`DED`) error occurred; trigger Tier 2 self-describing slab reverse-scan (Section 10.2).

---

## 5. Layer 2: Chained Region Shared Dictionary (`0x2040`)


The root dictionary segment starts at byte offset `0x2040` with total byte capacity `rootDictCapacityBytes` (`superblock[0x20..0x27]` high 32 bits, a multiple of `256 B`, default `1,024 B`).

### 5.1 Dictionary Segment Layout (`Root` or `Continuation`)

#### `formatVersion = 0x0003` (`LCA-ICD-3.0`, Factored Content-Agnostic Dictionary)
In `formatVersion >= 0x0003`, Layer 2 factors block states into region-local `BaseBlock` and `PropTuple` tables (`anvil-api-ADR-006`), preserving `usedPayloadBytes` at `+0x06..+0x07` so Word-0-first `(72, 64)` `SEC-DED` healing (Section 5.4) is byte-compatible with `0x0002`:

```
Offset +0x00: uint16  segmentStateCount      // BlockState entries in this segment (0..65535)
Offset +0x02: uint16  segmentBiomeCount      // Biome entries in this segment (0..16384)
Offset +0x04: uint16  segmentBaseBlockCount  // BaseBlock entries in this segment (0..65535)
Offset +0x06: uint16  usedPayloadBytes       // Active bytes in [+0x08 .. +0x08 + usedPayloadBytes)
                                             // (Must be 0 [empty segment] or >= 2 -> CBIT T5)
Offset +0x08: uint16  segmentPropTupleCount  // PropTuple entries in this segment (0..65535)
Offset +0x0A: BaseBlockEntry[segmentBaseBlockCount]:
                VarInt  utf8ByteLen
                byte[]  baseBlockUtf8        // Full namespaced ID (e.g. "minecraft:stone", "create:cogwheel")
              PropTupleEntry[segmentPropTupleCount]:
                VarInt  utf8ByteLen
                byte[]  propTupleUtf8        // Canonical "[k1=v1,...]" (must start '[' and end ']' -> CBIT T5)
              BlockStateEntry[segmentStateCount]:
                VarInt  baseBlockId          // Causal bound: 0 <= baseBlockId < totalBaseBlocksSoFar (CBIT T5)
                VarInt  propTupleIdPlusOne   // 0 = bare block; >= 1 -> PropTuple[id - 1] (<= totalPropTuplesSoFar)
                uint32  materialPacked       // RGBA8 color + LeafMap material flags
              BiomeEntry[segmentBiomeCount]:
                VarInt  utf8ByteLen
                byte[]  biomeIdUtf8          // Full namespaced biome ID
                uint32  foliageAndWaterTint  // Packed tint metadata
              byte[]  patternArenaSlice      // Optional forward-indexed LCD2 Huffman + cross-chunk byte patterns
              byte[]  slackPadding           // Zero-filled slack between active payload and downward-growing SEC-DED
              uint8   secDedSyndromes[W]     // Downward-growing (72, 64) SEC-DED syndromes at (cap - 8 - 1 - w)
Offset +cap-8: uint64 nextSegmentLinkTrailer // ALWAYS at the final 8 bytes of the segment
```

#### Legacy `formatVersion = 0x0002` (`LCA-ICD-1.0`, Flat Canonical Strings)
```
Offset +0x00: uint32  segmentStateCount      // Number of BlockState entries in this segment
Offset +0x04: uint16  segmentBiomeCount      // Number of Biome entries in this segment
Offset +0x06: uint16  usedPayloadBytes       // Active entry + pattern arena bytes following the 8B header
Offset +0x08: StateEntry[segmentStateCount]:
                VarInt  utf8ByteLen
                byte[]  canonicalStateUtf8   // e.g. "minecraft:oak_stairs[facing=north,half=bottom,waterlogged=false]"
                uint32  materialPacked       // RGBA8 color + material flags for map LODs
              BiomeEntry[segmentBiomeCount]:
                VarInt  utf8ByteLen
                byte[]  biomeIdUtf8          // e.g. "minecraft:plains"
                uint32  foliageAndWaterTint  // Packed tint metadata
              byte[]  patternArenaSlice      // Optional forward-indexed LCD2 Huffman + cross-chunk byte patterns
              byte[]  slackPadding           // Zero-filled slack between active payload and downward-growing SEC-DED
              uint8   secDedSyndromes[W]     // Downward-growing (72, 64) SEC-DED syndromes at (cap - 8 - 1 - w)
Offset +cap-8: uint64 nextSegmentLinkTrailer // ALWAYS at the final 8 bytes of the segment
```

### 5.2 Chained Continuation Link Trailer (`nextSegmentLinkTrailer`)

Located at `segmentStartOffset + segmentCapacityBytes - 8`:

```
 63                               32 31                       8 7              0
+-----------------------------------+--------------------------+----------------+
|    nextSegmentRelSlabs32 (int32)  | nextSegmentCapSlabs24    | segmentFlags8  |
+-----------------------------------+--------------------------+----------------+
```

- **`nextSegmentRelSlabs32` (`bits 63..32`, signed `int32`):** Relative offset in `256B` sub-slabs from `segmentStartOffset` to the next continuation segment (`nextSegmentOffset = segmentStartOffset + (nextSegmentRelSlabs32 << 8)`). **`0` indicates the terminal segment.**
- **`nextSegmentCapSlabs24` (`bits 31..8`, `uint24`):** Capacity of the next segment in `256B` sub-slabs (`nextSegmentCapacityBytes = nextSegmentCapSlabs24 << 8`).
- **`segmentFlags8` (`bits 7..0`, `uint8`):** Bit `0` (`0x01`) = continuation link active; Bit `1` (`0x02`) = downward-growing Extended Hamming `(72, 64)` `SEC-DED` syndromes (`1` byte at `segmentPayloadLimit - 1 - w` per active 64-bit word `w = 0 .. ((8 + usedPayloadBytes + 7) >>> 3) - 1`) and optional forward-indexed `LCD2` pattern arena (`dictPatternArena`) active.
- Global `BlockState` IDs (`0 .. totalRegionStates - 1`), global `Biome` IDs (`0 .. totalRegionBiomes - 1`), and `dictPatternArena` bytes (`0 .. totalPatternBytes - 1`, starting with `384 B` of 4-bit Canonical Huffman code lengths for `lit`, `cmd`, `off` followed by monotonically appended cross-chunk byte patterns) are numbered sequentially in segment traversal order (`<= 64` chained segments maximum, `CBIT T5`).

### 5.3 Mode B Pattern Arena (`dictPatternArena`) & `384-Byte` Canonical Huffman Tables

When one or more chunks in the region use **Mode B (`codec1 = 1`)**, the chained Layer 2 dictionary segments carry a forward-addressed byte arena (`dictPatternArena`) immediately following each segment's `BiomeEntry[segmentBiomeCount]` list:

1. **Multi-Segment Concatenation (`CBIT T5`):**
   - For each chained dictionary segment `segOffset` with byte capacity `segCap` (`<= 64` segments maximum):
     - Let `segPayloadLimit = segOffset + segCap - 8` and `activeEnd = segOffset + 8 + usedPayloadBytes`. Readers **must** reject the archive (`CBIT T5`) if `segStateCount > 65,536`, `segBiomeCount > 16,384`, or `activeEnd > segPayloadLimit`.
     - Advance `pos` from `segOffset + 8` past all `segmentStateCount` `StateEntry` items and `segmentBiomeCount` `BiomeEntry` items. Reject (`CBIT T5`) if `pos > activeEnd`.
     - If `pos < activeEnd`, append the slice `regionBytes[pos .. activeEnd)` to `dictPatternArena`.
   - Across chained continuation segments, `dictPatternArena` is the contiguous concatenation of these per-segment slices in traversal order (`0 .. totalPatternBytes - 1`, capped at `MAX_DICT_ARENA_BYTES = 16,384` bytes by writers).

2. **`384-Byte` Packed 4-Bit Canonical Huffman Header (`DICT_HUFF_HEADER_BYTES = 384`):**
   The first `384 bytes` (`dictPatternArena[0 .. 383]`) encode three `256`-symbol, length-limited (`1 .. 12` bits) Canonical Huffman code-length tables (`128 bytes` per stream, `2` symbols per byte):

   | `dictPatternArena` Slice | Size | Target `LCD2` Stream | Enabled by `frameFlags8` |
   | :--- | :--- | :--- | :--- |
   | `0x000 .. 0x07F` (`0 .. 127`) | `128 B` | `litStream` (`256` byte symbols `0x00 .. 0xFF`) | Bit `0` (`0x01`, `LIT_HUFFMAN`) |
   | `0x080 .. 0x0FF` (`128 .. 255`) | `128 B` | `cmdStream` (`256` sequence command tokens `0x00 .. 0xFF`) | Bit `1` (`0x02`, `CMD_HUFFMAN`) |
   | `0x100 .. 0x17F` (`256 .. 383`) | `128 B` | `offStream` (`256` offset & overflow `VarInt` bytes `0x00 .. 0xFF`) | Bit `3` (`0x08`, `OFF_HUFFMAN`) |

   - **Nibble Packing & `8-Bit` Fallback Rule:**
     For table byte `b = dictPatternArena[tableOffset + i] & 0xFF` (`i = 0 .. 127`):
     - High nibble `l0 = (b >>> 4) & 0x0F` specifies the code bit-length for even symbol `2 * i`.
     - Low nibble `l1 = b & 0x0F` specifies the code bit-length for odd symbol `2 * i + 1`.
     - Any nibble outside `[1 .. 12]` (`0` or `13 .. 15`), or an arena shorter than `tableOffset + 128` bytes, **must** fall back to length `8` (`lens[sym] = 8`). When all `256` symbols have length `8`, Canonical assignment produces the identity mapping `code(sym) == sym`.
   - **Canonical Code Construction (`1 .. 12` Bits):**
     1. Count `blCount[L]` = number of symbols in `0 .. 255` with `lens[s] == L` for `L = 1 .. 12` (`blCount[0] = 0`).
     2. Initialize `code = 0`; for `bits = 1 .. 12`, set `code = (code + blCount[bits - 1]) << 1` and `nextCode[bits] = code`.
     3. Iterate `s = 0 .. 255` in ascending symbol order and assign the `lens[s]`-bit big-endian integer `codes[s] = nextCode[lens[s]]++`.
   - **MSB-First Bitstream Packing:**
     Huffman codewords are emitted and consumed **most-significant-bit (`MSB`) first** inside each stream byte (`bit 7` down to `bit 0`). On the final byte of a compressed Huffman stream, any unused low-order tail bits (`(8 - (totalBits & 7)) & 7` bits) are padded with `0` bits; decoders stop as soon as `outLen` (`litTotal`, `seqCount`, or `offTotal`) symbols have been emitted.

3. **Forward-Addressed Cross-Chunk Pattern Slice (`dictPatternArena[384 .. totalPatternBytes)`):**
   - Bytes at indices `384 .. dictPatternArena.length - 1` store immutable, monotonically appended cross-chunk byte patterns learned from recurring section headers, palettes, Roaring containers, and heightmap/residual sequences across chunks in the region.
   - `LCD2` sequences with `matchMode == 2` reference `dictPatternArena[384 + off16BE .. 384 + off16BE + matchLen)` directly without copying per-chunk dictionary headers. Because existing offsets in `[384 .. oldUsedBytes)` are never shifted or overwritten during live `CoW` saves, older `ChunkFrame` slabs remain valid as new patterns append to dictionary slack or chained continuation segments.

### 5.4 Downward-Growing Dictionary Extended Hamming `(72, 64)` `SEC-DED` Syndromes

When `nextSegmentLinkTrailer` has `segmentFlags8` bit `1` (`0x02`) set, every active 64-bit word of `[segOffset .. segOffset + 8 + usedPayloadBytes)` is protected by a 1-byte Extended Hamming `(72, 64)` syndrome (using `computeHammingSecDed8` from Section 4.4) growing **downward** from `segPayloadLimit = segOffset + segCap - 8`:

- Let `activeBytes = 8 + usedPayloadBytes` and `activeWords = (activeBytes + 7) >>> 3` (requiring `activeBytes + activeWords <= segCap - 8`).
- Each 64-bit word `w = 0 .. activeWords - 1` is formed in big-endian byte order over `[segOffset .. segOffset + activeBytes)`, padding missing low-order bytes with `0x00` when `activeBytes` is not a multiple of `8` (`readHeaderWord64BE`, Section 6.2.5), and its syndrome byte is stored at `regionBytes[segPayloadLimit - 1 - w]`.
- **Two-Step Header-First Healing (`verifyAndHealDictSegmentSecDed`):**
  1. Verify and heal **Word `0`** (`[segOffset .. segOffset + 8)`, containing `segmentStateCount`, `segmentBiomeCount`, and `usedPayloadBytes`) first against `regionBytes[segPayloadLimit - 1]`. This guarantees a 1-bit flip inside `usedPayloadBytes` is repaired before `activeWords` is derived.
  2. Re-read `usedPayloadBytes`, recompute `activeBytes = 8 + usedPayloadBytes` and `activeWords = (activeBytes + 7) >>> 3` (rejecting if `activeEnd > segPayloadLimit`), and verify/heal remaining words `w = 1 .. activeWords - 1` against `regionBytes[segPayloadLimit - 1 - w]`. Any uncorrectable double-bit (`DED`) fault fails closed with `CorruptRegionEntryException` (`CBIT T5`).

---

## 6. Layer 3: Self-Describing `ChunkFrame` Header, Mode B Wrapper & Section Directory

At `frameByteOffset = slabOffset35 << 8` with length `exactByteLen20`:

### 6.1 Uncompressed `ChunkFrame` Header (`headerLen = 24 + 2 * sectionCount` Bytes)

Both **Mode A (`codec1 = 0`)** and **Mode B (`codec1 = 1`)** begin with the exact same uncompressed `headerLen`-byte header (`72 bytes` when `sectionCount = 24`):

| Relative Offset | Type | Field Name | Description / Formula |
| ---: | :--- | :--- | :--- |
| `+0x00..0x03` | `int32` | `selfTagAndDataVersion` | `0x5800_0000 \| ((chunkIdx & 0x03FF) << 16) \| (chunkDataVersion & 0xFFFF)`.<br>- Top 6 bits (`0xFC00_0000`) **must equal `0x5800_0000`** (`CBIT T3b` self-describing slab canary).<br>- Bits `25..16` **must equal `chunkIdx`** (`0..1023`).<br>- Bits `15..0` store the chunk's Mojang `DataVersion`. |
| `+0x04..0x0B` | `int64` | `lastUpdateTick` | Chunk `LastUpdate` world tick timestamp (`TAG_Long`) |
| `+0x0C..0x13` | `int64` | `inhabitedTimeTicks` | Chunk `InhabitedTime` cumulative ticks (`TAG_Long`) |
| `+0x14` | `uint8` | `chunkStatusOrdinal` | Encoded chunk `Status` string (see table in Section 6.3) |
| `+0x15` | `uint8` | `chunkFlags` | Bitmask:<br>- Bit `0` (`0x01`): `hasResidualNbt` (`1` if residual tail is present)<br>- Bit `1` (`0x02`): `hasSlabLocalPalette` (`1` if Slab-Local Micro-Palette is present at `sectionDirEnd`)<br>- Bit `2` (`0x04`): `scaleOffsets` (`0` = 1-byte exact offset units (`unitShift = 0`), `1` = 4-byte offset units (`unitShift = 2`))<br>- Bits `7..3` (`0xF8`): `defaultBiomeCode5` (`0` = none; `1..30` = `chunkDefaultBiomeDictId + 1`) |
| `+0x16..0x17` | `uint16` | `residualNbtRelOffset` | Relative offset in `1 << unitShift` byte units (`1 B` when `scaleOffsets == 0`, `4 B` when `scaleOffsets == 1`) from `sectionDirEnd` to the Residual Tail:<br>`residualRelStart = sectionDirEnd + (residualNbtRelOffset << unitShift)` (or `frameLen` if `hasResidualNbt == 0`) |
| `+0x18 .. +headerLen-1` | `uint16[sectionCount]` | `sectionOffsets` | Directory of `sectionCount` big-endian `uint16` section descriptors for `Y = minSectionY + i` (`i = 0 .. sectionCount - 1`) |

Let `sectionDirEnd = 24 + 2 * sectionCount`.

### 6.2 Mode B (`codec1 = 1`) Header SEC-DED Syndromes & Continuous Atomic Region Dictionary (`LCD2`) Body Decompression

When a chunk pointer entry in `0x0040 .. 0x203F` has `codec1 == 1` (**Mode B**, the default storage format when `LeafAnvil` `lcd2-level >= 1` or `superblock.flags` bit `3` `DEFAULT_MODE_B = 0x08` is active), the chunk slab at `frameByteOffset = slabOffset35 << 8` of total on-disk length `exactByteLen20` keeps the `ChunkFrame` header uncompressed for $O(1)$ metadata and section-directory inspection while protecting the header with inline `SEC-DED` parity and compressing the chunk body `[headerLen .. uncompressedChunkLen)` as a single pure-Java **Continuous Atomic Region Dictionary (`LCD2`, magic `0x4C44`)** frame.

#### 6.2.1 On-Disk Mode B Slab Envelope & Header `SEC-DED` Parity (`CBIT T3c`)

Let `headerLen = 24 + 2 * sectionCount` (`sectionDirEnd`) and `headerWords = (headerLen + 7) >>> 3` (for example, `headerLen = 72` and `headerWords = 9` when `sectionCount = 24`):

```
+-----------------------------------------------------------------------+ slabOffset = slabOffset35 << 8
| 1. Uncompressed ChunkFrame Header: byte[headerLen]                    |
|    (+0x00 .. +headerLen - 1; identical layout to Mode A Section 6.1)  |
+-----------------------------------------------------------------------+ +headerLen
| 2. Inline Header SEC-DED Syndromes: uint8[headerWords]                |
|    (+headerLen .. +headerLen + headerWords - 1; 1 B per 64-bit word)  |
+-----------------------------------------------------------------------+ +headerLen + headerWords (compStart)
| 3. LCD2 Compressed Body Frame: byte[compFrameLen]                     |
|    (0 bytes if chunk has zero body bytes; otherwise >= 9 bytes        |
|     starting with magic 0x4C44 "LD" up to +exactByteLen20)            |
+-----------------------------------------------------------------------+ +exactByteLen20
```

1. **Header `SEC-DED` Word Framing & Verification (`CBIT T3c`):**
   - Require `exactByteLen20 >= headerLen + headerWords` (else reject with `CBIT T3c` / `T4`).
   - Partition the uncompressed header `[0 .. headerLen)` into `headerWords` big-endian 64-bit words `w = 0 .. headerWords - 1`. If the final word (`w = headerWords - 1`) has fewer than `8` bytes (`rem = headerLen - 8 * w` with `1 <= rem <= 7`, which occurs whenever `sectionCount` is not a multiple of `4`), its remaining `(8 - rem)` low-order byte lanes (`bits (8 - rem) * 8 - 1 .. 0`) are zero-padded with `0x00` (`readHeaderWord64BE` in Section 6.2.5).
   - Verify each `word64` against `storedSyndrome8 = slab[headerLen + w] & 0xFF` using the Extended Hamming `(72, 64)` `SEC-DED` algorithm in Section 4.4:
     - Single-bit flips in `[0 .. headerLen)` are repaired in-place before checking `selfTagAndDataVersion` (`0x5800_0000` canary and `chunkIdx`, `CBIT T3b`).
     - Any unrecoverable double-bit error (`DED`, `totalParity == 0 && diff8 != 0`) fails closed with `CorruptRegionEntryException` (`CBIT T3c`).
2. **Empty-Body Mode B Chunks (`compLen == 0`):**
   - Let `compStart = headerLen + headerWords` and `compLen = exactByteLen20 - compStart`.
   - If `compLen == 0` (occurring when every `sectionOffsets[i]` is `0x0000` void or `0x8000 | ...` inline uniform, `hasSlabLocalPalette == 0`, and `hasResidualNbt == 0`), the unpacked `ChunkFrame` is simply the `headerLen`-byte header (`uncompBodyLen = 0`), and no `LCD2` frame bytes are present.
   - Whenever `compLen > 0`, `[compStart .. compStart + compLen)` **must** contain a valid `LCD2` frame (`compLen >= 9`).

#### 6.2.2 `LCD2` (`0x4C44` `"LD"`) `9-Byte` Frame Header & `frameFlags8` (`CBIT T4`, `T5`, `T8`)

At `compStart = headerLen + headerWords` (for `compLen > 0`):

| Offset from `compStart` | Type | Field Name | Value / Invariant |
| ---: | :--- | :--- | :--- |
| `+0x00..0x01` | `uint16` | `magic` | **Must equal `0x4C44`** (`"LD"` ASCII). Any other value fails closed (`CBIT T8` / `T5`). |
| `+0x02` | `uint8` | `frameFlags8` | Stream & passthrough bitmask:<br>- Bit `0` (`0x01`): `LIT_HUFFMAN` (`litStream` is 12-bit Canonical Huffman coded)<br>- Bit `1` (`0x02`): `CMD_HUFFMAN` (`cmdStream` is 12-bit Canonical Huffman coded)<br>- Bit `2` (`0x04`): `RAW_PASSTHROUGH` (body is stored uncompressed at `+0x09`)<br>- Bit `3` (`0x08`): `OFF_HUFFMAN` (`offStream` is 12-bit Canonical Huffman coded)<br>- Bits `7..4` (`0xF0`): Reserved (`0` in `LCA-ICD-1.0`) |
| `+0x03..0x05` | `uint24BE` | `compFrameLen` | `((b3 & 0xFF) << 16) \| ((b4 & 0xFF) << 8) \| (b5 & 0xFF)`. **Total byte length of the `LCD2` frame including this `9-byte` header** (`9 <= compFrameLen <= compLen`; typically `exactByteLen20 == compStart + compFrameLen`). |
| `+0x06..0x08` | `uint24BE` | `uncompBodyLen` | `((b6 & 0xFF) << 16) \| ((b7 & 0xFF) << 8) \| (b8 & 0xFF)`. Exact uncompressed byte length of the chunk body `[headerLen .. headerLen + uncompBodyLen)` (`0 <= uncompBodyLen <= MAX_CHUNK_UNCOMPRESSED_BYTES`, `16 MiB`, `CBIT T4`). |

- **Raw Passthrough Path (`(frameFlags8 & 0x04) != 0`):**
  - Emitted by the encoder whenever `9 + metaVarIntsLen + finalLitLen + finalCmdLen + finalOffLen >= 9 + uncompBodyLen` so incompressible payloads never expand beyond `+9 B`.
  - Readers verify `9 + uncompBodyLen <= compFrameLen` (`CBIT T5`), copy the `headerLen` uncompressed header bytes into `dst[0 .. headerLen)`, copy `src[compStart + 9 .. compStart + 9 + uncompBodyLen)` into `dst[headerLen .. headerLen + uncompBodyLen)`, and return `dst`.
- **Single-Pass Residual NBT Rule (`boilerplateFlags16` Bit `12`):**
  - In Mode B, `boilerplateFlags16` bit `12` (`0x1000`, `DEFLATE`-compressed residual NBT, Section 8.2) is **always `0`** so `LCD2` compresses the Slab-Local Micro-Palette, all non-uniform `SectionFrame`s, heightmap delta streams, `ChunkPatchTail`, and trailing Mojang NBT together in one pass without nested `Zlib` framing.

#### 6.2.3 Three-Stream Framing (`litStream`, `cmdStream`, `offStream`)

When `(frameFlags8 & 0x04) == 0`, let `pos = compStart + 9` and `limit = compStart + compFrameLen`:

1. **Stream Element Counts (`VarInt` Triple at `+0x09`):**
   - Read `seqCount = readVarInt(src, pos, limit)` (number of LZ/dictionary match sequences; `1` command byte per sequence).
   - Read `litTotal = readVarInt(src, pos, limit)` (total uncompressed literal bytes across all sequences plus any trailing literal tail).
   - Read `offTotal = readVarInt(src, pos, limit)` (total uncompressed bytes in `offBuf`, comprising **both** length-overflow `VarInt`s and `1 B` / `2 B` match offsets).
   - Reject (`CBIT T5`) if `seqCount < 0`, `litTotal < 0`, `offTotal < 0`, `litTotal > uncompBodyLen`, or `seqCount > uncompBodyLen`.
2. **Sequential Stream Unpacking (`litBuf`, `cmdBuf`, `offBuf`):**
   The three streams follow back-to-back in fixed order (`litStream`, then `cmdStream`, then `offStream`):
   - **`litStream` $\to$ `byte[litTotal] litBuf`:**
     - If `(frameFlags8 & 0x01) != 0` (`LIT_HUFFMAN`): read `VarInt compLitLen`, verify `pos + compLitLen <= limit`, decode `litTotal` symbols from `src[pos .. pos + compLitLen)` using the `lit` Canonical Huffman table (`dictPatternArena[0 .. 127]`, Section 5.3), and advance `pos += compLitLen`.
     - Else: verify `pos + litTotal <= limit`, slice `litBuf = src[pos .. pos + litTotal)`, and advance `pos += litTotal`.
   - **`cmdStream` $\to$ `byte[seqCount] cmdBuf`:**
     - If `(frameFlags8 & 0x02) != 0` (`CMD_HUFFMAN`): read `VarInt compCmdLen`, verify `pos + compCmdLen <= limit`, decode `seqCount` symbols from `src[pos .. pos + compCmdLen)` using the `cmd` Canonical Huffman table (`dictPatternArena[128 .. 255]`, Section 5.3), and advance `pos += compCmdLen`.
     - Else: verify `pos + seqCount <= limit`, slice `cmdBuf = src[pos .. pos + seqCount)`, and advance `pos += seqCount`.
   - **`offStream` $\to$ `byte[offTotal] offBuf`:**
     - If `(frameFlags8 & 0x08) != 0` (`OFF_HUFFMAN`): read `VarInt compOffLen`, verify `pos + compOffLen <= limit`, decode `offTotal` symbols from `src[pos .. pos + compOffLen)` using the `off` Canonical Huffman table (`dictPatternArena[256 .. 383]`, Section 5.3), and advance `pos += compOffLen`.
     - Else: verify `pos + offTotal <= limit`, slice `offBuf = src[pos .. pos + offTotal)`, and advance `pos += offTotal`.

#### 6.2.4 Sequence Command Byte (`cmd`) Bitfield & Match Execution State Machine

Allocate `dst = new byte[headerLen + uncompBodyLen]`, copy the healed uncompressed header into `dst[0 .. headerLen)`, and initialize cursors `dstPos = headerLen`, `litPos = 0`, `offPos = 0`, `lastOffset = 0`, `lastIsDict = false`.

Each sequence `s = 0 .. seqCount - 1` consumes one command token `cmd = cmdBuf[s] & 0xFF`:

```
  7         5 4       3 2         0
+------------+---------+-----------+
|  litCode3  |matchMode| lenCode3  |
|  (0 .. 7)  | (0 .. 3)| (0 .. 7)  |
+------------+---------+-----------+
```

Execute the following steps in **exact order** for each sequence `s` (noting that both overflow `VarInt`s reside in `offBuf` **prior to** the sequence's match-offset bytes):

1. **Decode Literal Length & Copy Literals:**
   - Let `litLen = (cmd >>> 5) & 7`.
   - If `litLen == 7`, read `extraLit = readVarInt(offBuf, offPos, offTotal)` from **`offBuf`**, advance `offPos += varIntLen(extraLit)`, and set `litLen = 7 + extraLit`.
   - If `litLen > 0`: verify `litPos + litLen <= litTotal` and `dstPos + litLen <= dst.length` (`CBIT T5`), copy `litBuf[litPos .. litPos + litLen)` to `dst[dstPos .. dstPos + litLen)`, and advance `litPos += litLen`, `dstPos += litLen`.
2. **Decode Match Mode & Match Length:**
   - Let `matchMode = (cmd >>> 3) & 3` and `lenCode3 = cmd & 7`.
   - Determine minimum match length `minMatch`:
     - `minMatch = 3` when `matchMode == 0` or `matchMode == 3`
     - `minMatch = 4` when `matchMode == 1` or `matchMode == 2`
   - Let `matchLen = lenCode3 + minMatch`.
   - If `lenCode3 == 7`, read `extraMatch = readVarInt(offBuf, offPos, offTotal)` from **`offBuf`**, advance `offPos += varIntLen(extraMatch)`, and set `matchLen += extraMatch`.
3. **Decode Match Offset from `offBuf` & Copy Match Bytes:**

   | `matchMode` | Name | Offset Bytes in `offBuf` | Effective Offset & Source Domain | `minMatch` |
   | :---: | :--- | :--- | :--- | :---: |
   | `0` (`00`) | `SHORT_INTRA` | `1 B` (`uint8 u8`) | `lastOffset = u8 + 1` (`1 .. 256`), `lastIsDict = false`<br>Backward distance in `dst`: `srcPos = dstPos - lastOffset >= headerLen` | `3` |
   | `1` (`01`) | `FAR_INTRA` | `2 B` (`uint16BE u16`) | `lastOffset = u16 + 257` (`257 .. 65,792`), `lastIsDict = false`<br>Backward distance in `dst`: `srcPos = dstPos - lastOffset >= headerLen` | `4` |
   | `2` (`10`) | `DICT_ARENA` | `2 B` (`uint16BE u16`) | `lastOffset = u16 + 384` (`384 .. 65,919`), `lastIsDict = true`<br>Forward slice in Layer 2 `dictPatternArena[lastOffset .. lastOffset + matchLen)` | `4` |
   | `3` (`11`) | `REPEAT_OFFSET` | `0 B` *(none)* | Reuses `(lastOffset, lastIsDict)` from the previous sequence (`lastOffset > 0` required; invalid on `s == 0`) | `3` |

   - Verify `dstPos + matchLen <= dst.length` (`CBIT T5`).
   - **If `lastIsDict == true`:** Verify `lastOffset >= 384` and `lastOffset + matchLen <= dictPatternArena.length` (`CBIT T5`), copy `dictPatternArena[lastOffset .. lastOffset + matchLen)` to `dst[dstPos .. dstPos + matchLen)`, and advance `dstPos += matchLen`.
   - **If `lastIsDict == false`:** Verify `lastOffset > 0` and `dstPos - lastOffset >= headerLen` (`CBIT T5`, preventing matches from reading into the uncompressed `ChunkFrame` header). Let `matchSrc = dstPos - lastOffset`. Copy `matchLen` bytes forward byte-by-byte (`for (int k = 0; k < matchLen; k++) dst[dstPos++] = dst[matchSrc + k]`) when `lastOffset < matchLen` so overlapping RLE matches replicate properly; `System.arraycopy` may be used when `lastOffset >= matchLen`.
4. **Copy Trailing Literals & Verify Exact Frame Output (`CBIT T5`):**
   - After all `seqCount` sequences complete, let `remLit = litTotal - litPos`.
   - If `remLit > 0`, verify `dstPos + remLit <= dst.length`, copy `litBuf[litPos .. litTotal)` into `dst[dstPos .. dstPos + remLit)`, and advance `dstPos += remLit`.
   - Require `dstPos == headerLen + uncompBodyLen` (else throw `CorruptRegionEntryException`, `CBIT T5`). The resulting `dst` array has the exact same layout as an uncompressed Mode A `ChunkFrame` and is decoded identically by Sections 6.4, 6.5, 7, and 8.

#### 6.2.5 Reference Mode B (`LCD2` + 12-Bit Canonical Huffman) Decoder Pseudocode

```java
static final int LCA_DICT_FRAME_MAGIC = 0x4C44; // "LD"
static final int DICT_HUFF_HEADER_BYTES = 384;  // 3 * 128B (lit, cmd, off)

static long readHeaderWord64BE(byte[] buf, int baseOffset, int byteLimitLen, int wordIdx) {
    int rel = wordIdx << 3;
    int rem = byteLimitLen - rel;
    long v = 0L;
    for (int b = 0; b < 8; b++) {
        int byteVal = (b < rem) ? (buf[baseOffset + rel + b] & 0xFF) : 0;
        v = (v << 8) | byteVal;
    }
    return v;
}

static int[] buildHuffDecTable12(byte[] dictArena, int tableOffset) {
    int[] lens = new int[256];
    if (dictArena == null || dictArena.length < tableOffset + 128) {
        Arrays.fill(lens, 8);
    } else {
        for (int i = 0; i < 128; i++) {
            int b = dictArena[tableOffset + i] & 0xFF;
            int l0 = (b >>> 4) & 0x0F;
            int l1 = b & 0x0F;
            lens[i << 1] = (l0 >= 1 && l0 <= 12) ? l0 : 8;
            lens[(i << 1) | 1] = (l1 >= 1 && l1 <= 12) ? l1 : 8;
        }
    }
    int[] blCount = new int[13];
    for (int l : lens) blCount[l]++;
    int[] nextCode = new int[13];
    int code = 0;
    for (int bits = 1; bits <= 12; bits++) {
        code = (code + blCount[bits - 1]) << 1;
        nextCode[bits] = code;
    }
    int[] decTable = new int[4096]; // 12-bit MSB peek -> (symbol << 8) | bitLen
    for (int sym = 0; sym < 256; sym++) {
        int l = lens[sym];
        int c = nextCode[l]++;
        int base = (c << (12 - l)) & 0x0FFF;
        int span = 1 << (12 - l);
        int entry = (sym << 8) | l;
        for (int k = base, end = Math.min(4096, base + span); k < end; k++) {
            decTable[k] = entry;
        }
    }
    return decTable;
}

static byte[] decodeHuffmanStream12(byte[] src, int srcOff, int compLen, int[] decTable, int outLen) {
    int bitPos = srcOff, bitLimit = srcOff + compLen;
    if (bitLimit > src.length) throw new IllegalArgumentException("CBIT T5: Truncated Huffman stream");
    byte[] dst = new byte[outLen];
    long bitBuf = 0L;
    int bitsInBuf = 0, outIdx = 0;
    while (outIdx < outLen) {
        while (bitsInBuf <= 56 && bitPos < bitLimit) {
            bitBuf |= ((long) (src[bitPos++] & 0xFF)) << (56 - bitsInBuf);
            bitsInBuf += 8;
        }
        int entry = decTable[(int) (bitBuf >>> 52)];
        int len = entry & 0xFF;
        if (len == 0 || len > bitsInBuf) {
            throw new IllegalArgumentException("CBIT T5: Truncated or invalid Huffman codeword");
        }
        dst[outIdx++] = (byte) (entry >>> 8);
        bitBuf <<= len;
        bitsInBuf -= len;
    }
    return dst;
}

static byte[] decodeModeBToFullFrame(
        byte[] slab, int headerStart, int headerLen, int compStart, int compLen, byte[] dictPatternArena
) {
    if (compLen == 0) {
        return Arrays.copyOfRange(slab, headerStart, headerStart + headerLen);
    }
    if (compLen < 9 || (slab[compStart] & 0xFF) != 0x4C || (slab[compStart + 1] & 0xFF) != 0x44) {
        throw new IllegalArgumentException("CBIT T8: Invalid or truncated LCD2 header");
    }
    int flags = slab[compStart + 2] & 0xFF;
    int compFrameLen = ((slab[compStart + 3] & 0xFF) << 16)
            | ((slab[compStart + 4] & 0xFF) << 8) | (slab[compStart + 5] & 0xFF);
    int uncompBodyLen = ((slab[compStart + 6] & 0xFF) << 16)
            | ((slab[compStart + 7] & 0xFF) << 8) | (slab[compStart + 8] & 0xFF);
    if (compFrameLen < 9 || compFrameLen > compLen || uncompBodyLen > 16 * 1024 * 1024) {
        throw new IllegalArgumentException("CBIT T4/T5: Invalid LCD2 lengths");
    }
    byte[] dst = new byte[headerLen + uncompBodyLen];
    System.arraycopy(slab, headerStart, dst, 0, headerLen);
    if ((flags & 0x04) != 0) {
        if (9 + uncompBodyLen > compFrameLen) throw new IllegalArgumentException("CBIT T5");
        System.arraycopy(slab, compStart + 9, dst, headerLen, uncompBodyLen);
        return dst;
    }
    int limit = compStart + compFrameLen, pos = compStart + 9;
    int seqCount = readVarIntBounded(slab, pos, limit); pos += varIntLen(seqCount);
    int litTotal = readVarIntBounded(slab, pos, limit); pos += varIntLen(litTotal);
    int offTotal = readVarIntBounded(slab, pos, limit); pos += varIntLen(offTotal);
    if (seqCount < 0 || litTotal < 0 || offTotal < 0
            || litTotal > uncompBodyLen || seqCount > uncompBodyLen) {
        throw new IllegalArgumentException("CBIT T5: Invalid LCD2 stream counts");
    }

    byte[] litBuf;
    if ((flags & 0x01) != 0) {
        int cLen = readVarIntBounded(slab, pos, limit); pos += varIntLen(cLen);
        if (cLen < 0 || pos + cLen > limit) throw new IllegalArgumentException("CBIT T5");
        litBuf = decodeHuffmanStream12(slab, pos, cLen, buildHuffDecTable12(dictPatternArena, 0), litTotal);
        pos += cLen;
    } else {
        if (pos + litTotal > limit) throw new IllegalArgumentException("CBIT T5");
        litBuf = Arrays.copyOfRange(slab, pos, pos + litTotal); pos += litTotal;
    }
    byte[] cmdBuf;
    if ((flags & 0x02) != 0) {
        int cLen = readVarIntBounded(slab, pos, limit); pos += varIntLen(cLen);
        if (cLen < 0 || pos + cLen > limit) throw new IllegalArgumentException("CBIT T5");
        cmdBuf = decodeHuffmanStream12(slab, pos, cLen, buildHuffDecTable12(dictPatternArena, 128), seqCount);
        pos += cLen;
    } else {
        if (pos + seqCount > limit) throw new IllegalArgumentException("CBIT T5");
        cmdBuf = Arrays.copyOfRange(slab, pos, pos + seqCount); pos += seqCount;
    }
    byte[] offBuf;
    if ((flags & 0x08) != 0) {
        int cLen = readVarIntBounded(slab, pos, limit); pos += varIntLen(cLen);
        if (cLen < 0 || pos + cLen > limit) throw new IllegalArgumentException("CBIT T5");
        offBuf = decodeHuffmanStream12(slab, pos, cLen, buildHuffDecTable12(dictPatternArena, 256), offTotal);
    } else {
        if (pos + offTotal > limit) throw new IllegalArgumentException("CBIT T5");
        offBuf = Arrays.copyOfRange(slab, pos, pos + offTotal);
    }

    int dstPos = headerLen, litPos = 0, offPos = 0, lastOffset = 0;
    boolean lastIsDict = false;
    for (int s = 0; s < seqCount; s++) {
        int cmd = cmdBuf[s] & 0xFF;
        int litLen = (cmd >>> 5) & 7;
        if (litLen == 7) {
            int extra = readVarIntBounded(offBuf, offPos, offTotal); offPos += varIntLen(extra);
            litLen += extra;
        }
        if (litPos + litLen > litTotal || dstPos + litLen > dst.length) {
            throw new IllegalArgumentException("CBIT T5: Literal copy out of bounds");
        }
        System.arraycopy(litBuf, litPos, dst, dstPos, litLen);
        litPos += litLen; dstPos += litLen;

        int matchMode = (cmd >>> 3) & 3;
        int minMatch = (matchMode == 0 || matchMode == 3) ? 3 : 4;
        int matchLen = (cmd & 7) + minMatch;
        if ((cmd & 7) == 7) {
            int extra = readVarIntBounded(offBuf, offPos, offTotal); offPos += varIntLen(extra);
            matchLen += extra;
        }
        if (matchMode == 0) {
            if (offPos >= offTotal) throw new IllegalArgumentException("CBIT T5");
            lastOffset = (offBuf[offPos++] & 0xFF) + 1; lastIsDict = false;
        } else if (matchMode == 1) {
            if (offPos + 2 > offTotal) throw new IllegalArgumentException("CBIT T5");
            lastOffset = (((offBuf[offPos] & 0xFF) << 8) | (offBuf[offPos + 1] & 0xFF)) + 257;
            offPos += 2; lastIsDict = false;
        } else if (matchMode == 2) {
            if (offPos + 2 > offTotal) throw new IllegalArgumentException("CBIT T5");
            lastOffset = (((offBuf[offPos] & 0xFF) << 8) | (offBuf[offPos + 1] & 0xFF)) + DICT_HUFF_HEADER_BYTES;
            offPos += 2; lastIsDict = true;
        } else if (lastOffset <= 0) {
            throw new IllegalArgumentException("CBIT T5: Repeat offset with no prior match");
        }
        if (dstPos + matchLen > dst.length) throw new IllegalArgumentException("CBIT T5");
        if (lastIsDict) {
            if (dictPatternArena == null || lastOffset < DICT_HUFF_HEADER_BYTES
                    || lastOffset + matchLen > dictPatternArena.length) {
                throw new IllegalArgumentException("CBIT T5: Dictionary offset out of bounds");
            }
            System.arraycopy(dictPatternArena, lastOffset, dst, dstPos, matchLen);
            dstPos += matchLen;
        } else {
            int matchSrc = dstPos - lastOffset;
            if (lastOffset <= 0 || matchSrc < headerLen) throw new IllegalArgumentException("CBIT T5");
            for (int k = 0; k < matchLen; k++) dst[dstPos++] = dst[matchSrc + k];
        }
    }
    int remLit = litTotal - litPos;
    if (remLit > 0) {
        if (dstPos + remLit > dst.length) throw new IllegalArgumentException("CBIT T5");
        System.arraycopy(litBuf, litPos, dst, dstPos, remLit);
        dstPos += remLit;
    }
    if (dstPos != dst.length) throw new IllegalArgumentException("CBIT T5: Length mismatch");
    return dst;
}
```

### 6.3 `chunkStatusOrdinal` Mapping Table (`+0x14`)

| Ordinal (`uint8`) | Mojang NBT `Status` String |
| ---: | :--- |
| `0xFF` (`255`) | `"minecraft:full"` |
| `0xFE` (`254`) | *(Status tag absent; e.g. `entities/` or `poi/` chunks)* |
| `0xFD` (`253`) | *(Unrecognized / custom `Status` string stored verbatim in Residual Tail `bit 10` `residualNbtSlice`)* |
| `0xEF` (`239`) | `"full"` (pre-namespace legacy status) |
| `1` | `"minecraft:empty"` |
| `2` | `"minecraft:structure_starts"` |
| `3` | `"minecraft:structure_references"` |
| `4` | `"minecraft:biomes"` |
| `5` | `"minecraft:noise"` |
| `6` | `"minecraft:surface"` |
| `7` | `"minecraft:carvers"` |
| `8` | `"minecraft:features"` |
| `9` | `"minecraft:initialize_light"` |
| `10` | `"minecraft:light"` |
| `11` | `"minecraft:spawn"` |
| `12` | `"minecraft:terrain"` (Minecraft `26.3+` / `DataVersion >= 5023` merged terrain stage) |

> **Unallocated Ordinals & `0xFD` Residual Escape (`CBIT T5`):** Unallocated ordinal values (`0`, `13..238`, `240..252`) are reserved and fail closed (`CorruptRegionEntryException`) on decode rather than defaulting to `"minecraft:full"`. When `chunkStatusOrdinal == 0xFD` (`253`), the chunk is treated as an incomplete `ProtoChunk` for view/column probes (`decodeWindowToViewOrProbe` returns `null`) and its exact `"Status"` `TAG_String` is read from Residual Tail `bit 10` (`residualNbtSlice`), failing closed under `CBIT T5` if absent.

### 6.4 Decoding `uint16 sectionOffsets[i]` (`i = 0 .. sectionCount - 1`, `Y = minSectionY + i`)

Each 16-bit big-endian entry `entry16 = sectionOffsets[i]` has three cases:

1. **`entry16 == 0x0000` (Unallocated / Void Section):**
   - Section is absent or pure void (`0` body bytes). If queried by coordinates, treat as uniform `"minecraft:air"` with `SkyLight = 15`, `BlockLight = 0`.
2. **Bit `15 == 1` (`(entry16 & 0x8000) != 0` - Inline Uniform Section, `0` Body Bytes):**
   - All $4,096$ voxels in section `Y` share a single block state (`|P| = 1`) and uniform lighting:
   - **Bits `14..13` (`skyLight2 = (entry16 >>> 13) & 3`):**
     - `0` (`00`): `SkyLight = null` (or all `0` if needed), `BlockLight = null`
     - `1` (`01`): `SkyLight = 2,048 bytes of 0xFF` (all nibbles `15`), `BlockLight = null`
     - `2` (`10`): `SkyLight = 2,048 bytes of 0xFF` (`15`), `BlockLight = 2,048 bytes of 0xFF` (`15`)
     - `3` (`11`): `SkyLight = 2,048 bytes of 0x00` (all nibbles `0`), `BlockLight = null`
   - **If `hasSlabLocalPalette == 1` (`chunkFlags` bit `1`):**
     - **Bits `12..11` (`biomeTmpl2 = (entry16 >>> 11) & 3`):**
       - `0..2`: Use chunk `BiomeTemplate[biomeTmpl2]` from Slab-Local Micro-Palette Part A (Section 6.5).
       - `3`: Use the chunk's `defaultBiomeCode5` (`chunkFlags >>> 3`) as a single-biome palette.
     - **Bits `10..0` (`localDictId11 = entry16 & 0x07FF`):** Index (`0..2047`) into the chunk's Slab-Local Micro-Palette block state list.
   - **If `hasSlabLocalPalette == 0`:**
     - **Bits `12..0` (`globalDictId13 = entry16 & 0x1FFF`):** Index (`0..8191`) into the Region Shared Dictionary (Layer 2), using `defaultBiomeCode5` for the section biome.
3. **Bit `15 == 0` and `entry16 > 0` (`0x0001 .. 0x7FFF` - Non-Uniform Section Offset):**
   - Let `unitShift = ((chunkFlags & 4) != 0) ? 2 : 0` (`1-byte` exact units when `scaleOffsets == 0`, `4-byte` units when `scaleOffsets == 1`).
   - The `SectionFrame` for section `i` starts at frame-relative byte offset:
     $$\mathtt{relStart}_i = \mathtt{sectionDirEnd} + \bigl((\mathtt{entry16} - 1) \ll \mathtt{unitShift}\bigr)$$
   - **`CBIT T5` Disjoint Offset Invariant:** Every non-uniform `relStart_i` must satisfy $\mathtt{sectionDirEnd} \le \mathtt{relStart}_i < \mathtt{frameLen}$, $\mathtt{relStart}_i \neq \mathtt{residualRelStart}$ (when `hasResidualNbt == 1`), and $\mathtt{relStart}_i \neq \mathtt{relStart}_j$ for all $j \neq i$ (duplicate non-zero non-uniform `entry16` offsets are rejected).
   - **Order-Independent Upper Bound `relEnd_i`:** `SectionFrame`s are not required to appear in monotonic vertical index order `i = 0 .. sectionCount - 1` (for example, a single modified section may be re-encoded and appended into the chunk's `256-byte` sub-slab slack either immediately before `residualRelStart` or after the Residual Tail). The upper bound `relEnd_i` of `SectionFrame` `i` is the smallest boundary offset strictly greater than `relStart_i`:
     $$\mathtt{relEnd}_i = \min\Bigl(\{\mathtt{relStart}_j \mid \mathtt{relStart}_j > \mathtt{relStart}_i\} \cup \{B \in \{\mathtt{residualRelStart}, \mathtt{frameLen}\} \mid B > \mathtt{relStart}_i\}\Bigr)$$

### 6.5 Slab-Local Chunk Micro-Palette (`0x0003` Pure-ID vs. Legacy `0x0002` `Part A` + `Part B`)

When `hasSlabLocalPalette == 1` (`chunkFlags` bit `1`), the initial Slab-Local Micro-Palette starts immediately at `sectionDirEnd` (`24 + 2 * sectionCount`) and ends at $\min_k(\mathtt{relStart}_k)$ across all non-uniform sections $k$ (or `residualRelStart` if no non-uniform section precedes `residualRelStart`), with zero tail padding when `scaleOffsets == 0`. Incremental chunk patches (`boilerplateFlags16` bit `14` = `0x4000`, `HAS_CHUNK_PATCH_TAIL`, Section 8.3) may append additional local entries (`localStateIdx = localStateCount .. localStateCount + appendedStateCount - 1`) without shifting or re-sorting indices `0 .. localStateCount - 1`.

#### `formatVersion = 0x0003` (`LCA-ICD-3.0`, Pure Layer 2 ID Micro-Palette)
In `formatVersion >= 0x0003`, Layer 3 micro-palettes store **pure Layer 2 `VarInt` IDs** with zero `STATIC_CORE_*` tokens, zero inline UTF-8 strings, and no Part B property-binding table (`anvil-api-ADR-006`). `hasSlabLocalPalette == 1` remains valid regardless of total Layer 2 dictionary cardinality (including `> 8,192` modded states):

```
+-----------------------------------------------------------------------+ Offset sectionDirEnd
| 1. VarInt  localStateCount                       (0 .. 4096, CBIT T5) |
| 2. VarInt  localStateGlobalIds[localStateCount]  (0 .. S-1,  CBIT T7) |
| 3. VarInt  localBiomeCount                       (0 .. 1024, CBIT T5) |
| 4. VarInt  localBiomeGlobalIds[localBiomeCount]  (0 .. M-1,  CBIT T7) |
| 5. VarInt  biomeTemplateCount                    (0 .. 64,   CBIT T5) |
| 6. BiomeTemplate[biomeTemplateCount]             (4x4x4 XZY-RLE)      |
+-----------------------------------------------------------------------+
```

#### Legacy `formatVersion = 0x0002` (`LCA-ICD-1.0`, `Part A` + `Part B`, Quarantined in `LcaV2LegacyCodec`)

```
+-----------------------------------------------------------------------+ Offset sectionDirEnd
| Part A: Base Block Names, Biomes & Deduplicated 4x4x4 BiomeTemplates  |
|   1. VarInt localStateCount                                           |
|   2. BaseBlockEntry[localStateCount]                                  |
|   3. VarInt localBiomeCount                                           |
|   4. BiomeNameEntry[localBiomeCount]                                  |
|   5. VarInt biomeTemplateCount                                        |
|   6. BiomeTemplate[biomeTemplateCount]                                |
+-----------------------------------------------------------------------+
| Part B: Sparse Property Suffix Bindings (only read for full NBT)      |
|   7. VarInt propStateCount                                            |
|   8. PropBinding[propStateCount]:                                     |
|        VarInt localStateIdx (0 .. localStateCount - 1)                |
|        uint8  propTag:                                                |
|          - If (propTag & 0x80) != 0:                                  |
|              propId = propTag & 0x7F -> STATIC_CORE_PROP_SUFFIXES     |
|          - Else (propTag == 0x40):                                    |
|              VarInt globalStateId -> Region Shared Dictionary[id]     |
+-----------------------------------------------------------------------+
```

#### Entry Encoding in `BaseBlockEntry` & `BiomeNameEntry`:
Each entry in `BaseBlockEntry[localStateCount]` or `BiomeNameEntry[localBiomeCount]` begins with a `uint8 tag`:
- **If `(tag & 0x80) != 0`:** 1-byte static core token with `coreId = tag & 0x7F` (`0..127`). Resolves to `STATIC_CORE_STATES[coreId]` (with any `[...]` stripped) for blocks, or `STATIC_CORE_BIOMES[coreId]` for biomes (`0..64`, see Section 9 for the full tables).
- **Else if `0x60 <= tag < 0x80`:** 1-byte extended static core token with `coreId = 128 + (tag & 0x1F)` (`128..159` in `STATIC_CORE_STATES`).
- **Else if `tag == 0x40`:** Stripped `"minecraft:"` namespace string: read `VarInt utfLen`, read `utfLen` UTF-8 bytes, and prepend `"minecraft:"`.
- **Else (`tag == 0x00`):** Full modded namespace string: read `VarInt utfLen` and decode `utfLen` UTF-8 bytes directly.

#### `BiomeTemplate` Encoding in Part A (`t = 0 .. biomeTemplateCount - 1`):
Each chunk-level `BiomeTemplate` encodes a deduplicated $4 \times 4 \times 4$ (`64`-cell) biome grid using the exact same format as a section biome payload (Section 7.4):
- Read `VarInt bLen` (`1..64`), followed by `bLen` `VarInt` indices into the chunk's `BiomeNameEntry[localBiomeCount]` list.
- If `bLen > 1`: read `VarInt runCount`, followed by `runCount` `XZY` column runs:
  - When `bLen <= 8`: each run is 1 byte `uint8 packed = (pIdx3 << 5) | (runLen5 - 1)` where `pIdx3 = (packed >>> 5) & 7` and `runLen = (packed & 0x1F) + 1`.
  - When `bLen > 8`: each run is `VarInt pIdx` followed by `uint8 (runLen - 1)`.
  - For each cell in the run (`cursor = 0..63`), the `XZY` traversal index maps to Mojang's `YZX` $4 \times 4 \times 4$ biome cell index via:
    $$\mathtt{yzxCell} = ((\mathtt{cursor} \ \& \ 3) \ll 4) \;\big|\; (\mathtt{cursor} \ \& \ \mathtt{0x0C}) \;\big|\; ((\mathtt{cursor} \ggg 4) \ \& \ 3)$$

---

## 7. Layer 4: $16 \times 16 \times 16$ Non-Uniform `SectionFrame` Layout

Each non-uniform section (`sectionOffsets[i]` has bit `15 == 0` and `entry16 > 0`) at `fOff + relStart_i` has the following sequential layout:

```
+-----------------------------------------------------------------------+
| 1. uint8   sectionHeader                                              |
|            bits 7..6: curveId            (00=YZX, 01=H_3, 10=Y+H_2, 11=XZY)
|            bits 5..4: blockEncodingMode  (00=Uniform [mask=0L elided],|
|                                           01=DeltaRoaring [8B mask],  |
|                                           10=FlatBitPack  [8B mask],  |
|                                           11=DeltaRoaringFullMask     |
|                                              [mask=-1L elided])       |
|            bits 3..2: skyLightMode       (00=Absent, 01=ConstNibble,  |
|                                           10=GrayPlanes, 11=Raw2048B) |
|            bits 1..0: blockLightMode     (00=Absent, 01=ConstNibble,  |
|                                           10=GrayPlanes, 11=Raw2048B) |
+-----------------------------------------------------------------------+
| 2. [If blockEncodingMode == 01 || blockEncodingMode == 10]:           |
|    uint64  octreeOccupancyMask64 (8 bytes, Big-Endian)                |
|            Bit k (0..63) == 1 iff 4x4x4 octant k contains any voxel   |
|            with palette index p > 0 (non-dominant state).             |
|            (Elided when mode == 00 -> 0L, or mode == 11 -> -1L)       |
+-----------------------------------------------------------------------+
| 3. VarInt  paletteLen (|P|, 1..4096)                                  |
|    VarInt  dictIds[|P|] (Frequency-sorted: dictIds[0] is dominant     |
|                          state palette[0], omitted from container     |
|                          stream; indices into Slab-Local or Region)   |
+-----------------------------------------------------------------------+
| 4. BlockState Payload                                                 |
|    - If blockEncodingMode == 00 (Uniform, |P| == 1): 0 bytes          |
|    - If blockEncodingMode == 01 or 11 (DeltaRoaring): |P|-1           |
|      containers for p = 1 .. |P|-1 along curveId                      |
|    - If blockEncodingMode == 10 (FlatBitPack):                        |
|      ceil(4096 * ceil(log2(|P|)) / 8) bytes in YZX order              |
+-----------------------------------------------------------------------+
| 5. Biome 4x4x4 Payload                                                |
|    - First byte b0:                                                   |
|      - If (b0 & 0x80) != 0: chunk BiomeTemplate[b0 & 0x3F] (1 byte)   |
|      - Else: rewind 1 byte, read VarInt bLen:                         |
|        - If bLen == 0: inherit chunk defaultBiomeCode5 (1 byte total) |
|        - If bLen >= 1: VarInt[bLen] biome IDs + XZY column RLE        |
+-----------------------------------------------------------------------+
| 6. Lighting Payload                                                   |
|    - If skyLightMode == 01 || blockLightMode == 01:                   |
|        uint8 constLightNibbles = (blkConst4 << 4) | skyConst4 (1 B)   |
|    - SkyLight Payload:                                                |
|        mode 00: 0 B (Absent)                                          |
|        mode 01: 0 additional bytes (uses constLightNibbles & 0x0F)    |
|        mode 10: uint8 planeModes8 + 4 Gray-code bitplanes in Y+H_2    |
|        mode 11: byte[2048] raw packed nibbles                         |
|    - BlockLight Payload:                                              |
|        mode 00: 0 B (Absent)                                          |
|        mode 01: 0 additional bytes (uses constLightNibbles >>> 4)     |
|        mode 10: uint8 planeModes8 + 4 Gray-code bitplanes in H_3      |
|        mode 11: byte[2048] raw packed nibbles                         |
+-----------------------------------------------------------------------+
```

### 7.1 3D Space-Filling Curve Permutations (`curveId = 0..3`)

For any voxel $(x, y, z) \in [0, 15]^3$, its standard Mojang `YZX` index is:
$$\mathtt{yzx} = (y \ll 8) \;\big|\; (z \ll 4) \;\big|\; x \quad \in [0, 4095]$$

The four traversal curves map $\mathtt{yzx} \leftrightarrow h \in [0, 4095]$ (`CURVE_TO_YZX[curveId][h] = yzx`):

1. **`00` (`CURVE_YZX` - Horizontal Scanline):**
   $$h_{\text{YZX}}(x, y, z) = (y \ll 8) \;\big|\; (z \ll 4) \;\big|\; x$$
2. **`01` (`CURVE_H3` - 3D Octree Hilbert Curve):**
   $$h_{\text{H3}}(x, y, z) = (\mathtt{octant6} \ll 6) \;\big|\; \mathtt{local6}$$
   where:
   - $\mathtt{octant6} = (((y \ggg 2) \ \& \ 3) \ll 4) \;\big|\; H_{2,4}((x \ggg 2) \ \& \ 3,\; (z \ggg 2) \ \& \ 3) \in [0, 63]$
   - $\mathtt{local6} = ((y \ \& \ 3) \ll 4) \;\big|\; H_{2,4}(x \ \& \ 3,\; z \ \& \ 3) \in [0, 63]$
   - **Octant Syndrome Mapping:** For every curve (`0..3`), `YZX_TO_OCTANT6[yzx]` equals $\mathtt{octant6} = h_{\text{H3}}(x, y, z) \ggg 6 \in [0, 63]$. Any voxel at `yzx` with palette index $p > 0$ sets bit `(1L << YZX_TO_OCTANT6[yzx])` in `octreeOccupancyMask64` (`CBIT T6b`).
3. **`10` (`CURVE_Y_H2` - Horizontal 2D Hilbert Slabs):**
   $$h_{\text{YH2}}(x, y, z) = (y \ll 8) \;\big|\; H_{2,16}(x, z)$$
4. **`11` (`CURVE_XZY` - Vertical Columns):**
   $$h_{\text{XZY}}(x, y, z) = (x \ll 8) \;\big|\; (z \ll 4) \;\big|\; y$$

#### Exact 2D Hilbert Index Function $H_{2,N}(x, z)$ (for $N = 16$ with $s_{\text{init}} = 8$, or $N = 4$ with $s_{\text{init}} = 2$):
```java
static int xyToHilbert2D(int x, int z, int sInit) {
    int d = 0;
    for (int s = sInit; s > 0; s >>= 1) {
        int rx = (x & s) != 0 ? 1 : 0;
        int rz = (z & s) != 0 ? 1 : 0;
        d += s * s * ((3 * rx) ^ rz);
        if (rz == 0) {
            if (rx == 1) {
                x = s - 1 - x;
                z = s - 1 - z;
            }
            int t = x;
            x = z;
            z = t;
        }
    }
    return d;
}
```

### 7.2 Minority State Delta-Roaring Containers (`blockEncodingMode == 01 or 11`)

When `blockEncodingMode == 01` (`octreeOccupancyMask64 != -1L`), the Roaring cursor domain $c \in [0, N_{\text{active}} - 1]$ is rank-compacted over `octreeOccupancyMask64`:
- For `curveId == 01` (`CURVE_H3`), only the $K = \operatorname{popcnt}(\mathtt{octreeOccupancyMask64})$ non-empty $4 \times 4 \times 4$ octants are indexed ($N_{\text{active}} = 64 K$, mapping $c \mapsto h = (\mathtt{activeOctant}[c \ggg 6] \ll 6) \mid (c \ \& \ 63)$).
- For `curveId == 00` (`CURVE_YZX`) or `10` (`CURVE_Y_H2`), only the $S \in [1, 4]$ non-empty $16 \times 4 \times 16$ horizontal slabs (`((octreeOccupancyMask64 >>> (octY * 16)) & 0xFFFF) != 0`) are indexed ($N_{\text{active}} = 1024 S$, mapping $c \mapsto h = (\mathtt{activeSlab}[c \ggg 10] \ll 10) \mid (c \ \& \ 1023)$).
- Otherwise (`blockEncodingMode == 11` or `curveId == 11`), $N_{\text{active}} = 4,096$ and $h = c$.

Initialize all $4,096$ voxels of the section to `palette[0]` (index `0`). Then, for each minority state $p = 1 \dots |P|-1$ in order, read a big-endian `uint16 word16`:
- Let `tag = (word16 >>> 14) & 3` and `meta14 = word16 & 0x3FFF`.

| `tag` | Container Name | Decoding Algorithm (`c` = compacted cursor in `0..N_active-1` $\mapsto h$, `yzx = CURVE_TO_YZX[curveId][h]`) |
| :--- | :--- | :--- |
| `0` (`00`) | `TAG_SINGLETON` | Let `subTag = (meta14 >>> 12) & 3` and `idx12 = meta14 & 0x0FFF`:<br>- `subTag == 0`: Single voxel at `c = idx12` (`2 B` total).<br>- `subTag == 1`: Single contiguous run at `c = idx12` of length `(readUint8() + 2)` in `2..257` (`3 B` total).<br>- `subTag == 2`: Single contiguous run at `c = idx12` of length `(readUint16BE() + 2)` in `258..4096` (`4 B` total).<br>- `subTag == 3`: Two contiguous runs (`len <= 16` each): read `uint8 lens` (`len0 = (lens >>> 4) + 1`, `len1 = (lens & 0x0F) + 1`) + `adjGap1 = readBoundedGap240()`, placing run 0 at `[idx12 .. idx12 + len0)` and run 1 at `[idx12 + len0 + 1 + adjGap1 .. + len1)` (`4..5 B` total). |
| `1` (`01`) | `TAG_DELTA_RUN` | - Let `subMode = (meta14 >>> 12) & 3` (`00` = `4\|4`, `01` = `5\|3`, `10` = `6\|2`, `11` = `7\|1`) and `runCount = meta14 & 0x0FFF`.<br>- Let `lBits = 4 - subMode`, `gMax = (1 << (4 + subMode)) - 1`, `lMax = (1 << lBits) - 1`.<br>- Set `cursor = 0`. Loop `r = 0 .. runCount - 1`:<br>  - Read `uint8 tok`: `adjGap = (tok >>> lBits) & gMax`; `lenMinus1 = tok & lMax`.<br>  - If `adjGap == gMax`, add `readBoundedGap240()` (`1..2 B`).<br>  - If `lenMinus1 == lMax`, add `readVarInt()`.<br>  - Let `gap = (r == 0) ? adjGap : (adjGap + 1)` and `len = lenMinus1 + 1`.<br>  - Advance `cursor += gap`. Apply run `[cursor .. cursor + len)` (`CBIT T6`: `cursor + len <= N_active`). |
| `2` (`10`) | `TAG_DELTA_ARRAY` | - `runCount = meta14`. Set `cursor = 0`.<br>- Loop `r = 0 .. runCount - 1`:<br>  - Read `adjGap = readBoundedGap240()` (`1..2 B`).<br>  - Let `gap = (r == 0) ? adjGap : (adjGap + 1)`.<br>  - Advance `cursor += gap`, set voxel at `cursor++` to `p`. |
| `3` (`11`) | `TAG_BITMAP` | - If `meta14 == 512` or `(meta14 & 0x1000) != 0`: read `byteLen = (meta14 == 512) ? 512 : (meta14 & 0x0FFF)` bytes as `byteLen / 8` big-endian `uint64` words.<br>- Else (`1 <= meta14 <= 64`, Word-Masked Bitmap): read `uint64 wordMask64` (`popcnt == meta14`) followed by `meta14` big-endian `uint64` words for each set bit `w` of `wordMask64`. |

After decoding all $p = 1 \dots |P|-1$, verify `decodedOctreeMask64 == octreeOccupancyMask64` (`CBIT T6b`).

### 7.3 Flat Bit-Packed Section Ceiling (`blockEncodingMode == 10`)

When `blockEncodingMode == 2` (`10`):
- Let `flatBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteLen - 1))` and `flatByteCount = (4096 * flatBits + 7) >>> 3`.
- Read `flatByteCount` bytes as a continuous little-endian bitstream of `4,096` `flatBits`-wide integers in `YZX` order (`yzx = 0 .. 4095`):
  ```java
  int bitPos = 0;
  for (int yzx = 0; yzx < 4096; yzx++) {
      int val = 0;
      for (int b = 0; b < flatBits; b++) {
          if ((buf[pos + (bitPos >>> 3)] & (1 << (bitPos & 7))) != 0) val |= (1 << b);
          bitPos++;
      }
      yzxPaletteIdx[yzx] = val;
  }
  ```

### 7.4 Gray-Code Light Bitplanes (`skyLightMode == 10` or `blockLightMode == 10`)

In Minecraft NBT, `SkyLight` and `BlockLight` are `byte[2048]` arrays where voxel `yzx` (`0..4095`) occupies the low nibble (`b & 0x0F`) when `yzx` is even and the high nibble (`(b >>> 4) & 0x0F`) when `yzx` is odd.

- **Curve Assignment:** `SkyLight` uses `CURVE_TO_YZX[CURVE_Y_H2]` (`10:Y+H_2`); `BlockLight` uses `CURVE_TO_YZX[CURVE_H3]` (`01:H_3`).
- **Forward Gray Transform (Encoder):** Each 4-bit light nibble $L \in [0, 15]$ at `yzx = curveToYzx[h]` is mapped to Reflected Binary Gray code:
  $$G(h) = L \oplus (L \ggg 1) \quad \in [0, 15]$$
- **Packed 1-Byte `planeModes8` Header:**
  The channel payload starts with a single byte `uint8 planeModes8 = m0 | (m1 << 2) | (m2 << 4) | (m3 << 6)`, where `mode = (planeModes8 >>> (plane * 2)) & 3` defines the encoding for Gray bitplane `plane = 0..3` (`bit = 1 << plane`):
  - `mode == 0` (`00` - All-`0` plane): `0` bytes follow; bit `plane` is `0` for all $4,096$ voxels.
  - `mode == 1` (`01` - Tiered Sub-Header `uint8 subHdr`):
    - `subHdr == 0xFF`: All-`1` plane (`1 B` total).
    - `(subHdr & 0xC0) == 0xC0`: Single contiguous `1`-run (`kind = (subHdr >>> 4) & 3`, `val12 = ((subHdr & 0x0F) << 8) | readUint8()`): `kind == 2` (`0xE0`) sets tail `[val12 .. 4096)`, `kind == 1` (`0xD0`) sets prefix `[0 .. val12]`, `kind == 0` (`0xC0`) reads `lenMinus1 = readVarInt()` and sets `[val12 .. val12 + lenMinus1]`.
    - `(subHdr & 0xC0) != 0xC0`: Adaptive `4|4` (`(subHdr & 0x20) == 0`) or `6|2` (`(subHdr & 0x20) != 0`) stream with inline 5-bit `runCount = subHdr & 0x1F` (plus `readVarInt()` if `runCount == 31`).
  - `mode == 2` (`10` - `4|4` Delta-Run Stream):
    Read `VarInt runCount`. Set `cursor = 0`. Loop `r = 0 .. runCount - 1`:
    - Read `uint8 tok`: `adjGap = (tok >>> 4) & 0x0F`, `lenMinus1 = tok & 0x0F`.
    - If `adjGap == 15`: `adjGap += readVarInt()`.
    - If `lenMinus1 == 15`: `lenMinus1 += readVarInt()`.
    - Let `gap = (r == 0) ? adjGap : (adjGap + 1)`.
    - Advance `cursor += gap`. For `k = 0 .. lenMinus1`, set `grayCurve[cursor++] |= (1 << plane)`.
  - `mode == 3` (`11` - Raw `512 B` Bitmap):
    Read `512` bytes. For `h = 0 .. 4095`, if `((buf[pos + (h >>> 3)] >>> (h & 7)) & 1) != 0`, set `grayCurve[h] |= (1 << plane)`.
- **Inverse Gray Transform (Decoder):**
  For each $h \in [0, 4095]$ with $g = \mathtt{grayCurve}[h]$:
  $$L = g \oplus (g \ggg 1) \oplus (g \ggg 2) \oplus (g \ggg 3) \quad \in [0, 15]$$
  Scatter $L$ to `yzx = curveToYzx[h]`. (Note: in direct-to-packed-2048 decoding, setting Gray bit `plane` is equivalent to XORing the output nibble with `xorMask = (1 << (plane + 1)) - 1`, i.e. `0x1, 0x3, 0x7, 0xF` for `plane = 0, 1, 2, 3`).

---

## 8. Layer 3c: Residual Tail (`MOTION_BLOCKING_NO_LEAVES` & `boilerplateFlags16`)

When `hasResidualNbt == 1` (`chunkFlags` bit `0`), the Residual Tail starts at `residualRelStart = sectionDirEnd + (residualNbtRelOffset << unitShift)` and extends to:
$$\mathtt{residualRelEnd} = \min\Bigl(\{\mathtt{relStart}_j \mid \mathtt{relStart}_j > \mathtt{residualRelStart}\} \cup \{\mathtt{frameLen}\}\Bigr)$$
Bounding the Residual Tail by `residualRelEnd` (rather than blindly reading to `frameLen`) allows out-of-order `SectionFrame`s to be appended in the `256-byte` sub-slab slack *after* the Residual Tail without being consumed by `bit 10` (`residualNbtSlice`).

```
Offset +0x00: uint16  boilerplateFlags16    // Bitmask (bits 0..14) of compacted vanilla/Starlight fields & patch tail
              [If bit 13 (0x2000): VarInt mbnlByteLen + byte[mbnlByteLen] mbnlStream (Section 8.1)]
              [If bit 5  (0x0020): uint8  ppEmptyCount]
              [If bit 6  (0x0040): VarInt starlightLightVersion]
              [If bit 7  (0x0080): uint8  lowBoundaryFlags  (section Y = minSectionY - 1)]
              [If bit 8  (0x0100): uint8  highBoundaryFlags (section Y = minSectionY + sectionCount)]
              [If bit 9  (0x0200): uint8  secHmCount + SecondaryHeightmapEntry[secHmCount]]
              [If bit 14 (0x4000): VarInt patchTailByteLen + byte[patchTailByteLen] chunkPatchTail (Section 8.3)]
              [If bit 10 (0x0400): byte[] residualNbtSlice (to residualRelEnd; DEFLATE if bit 12 == 1, else raw Mojang NBT)]
```

### 8.1 Binary Heightmap Delta Streams (`mbnlStream` & Secondary Heightmaps)

Mojang packs `256` 9-bit column heights ($16 \times 16$) into `long[37]` (`7` entries per 64-bit word at bit offsets `0, 9, 18, 27, 36, 45, 54`, masking with `0x1FF`).

1. **Standalone Heightmap Delta Stream (`mbnlStream` or fallback secondary heightmap):**
   - Byte `0`: `uint8 numLongs` (typically `37`).
   - Loop until `c == 256` columns decoded (with `prev = 0`):
     - Read `uint8 tok`:
     - **If `(tok & 0x80) != 0` (Zero-Delta Run):** `runLen = (tok & 0x7F) + 1`. Repeat `cols[c++] = prev` for `runLen` columns.
     - **Else (ZigZag Delta):**
       - Let `zz = tok`. If `(tok & 0x40) != 0`, read `extra = readVarInt()` and set `zz = (tok & 0x3F) | (extra << 6)`.
       - Decode signed delta: `delta = (zz >>> 1) ^ -(zz & 1)`.
       - `prev = (prev + delta) & 0x1FF`; `cols[c++] = prev`.
2. **Secondary Heightmap Entries (`boilerplateFlags16` bit `9` = `0x0200`):**
   - `decodedByOrder` is initialized with `[mbnlLongs]` if `mbnlByteLen > 0`, else empty.
   - Read `uint8 secHmCount`. For each entry:
     - Read `uint8 nameCode`:
       - `0` = `"MOTION_BLOCKING"`, `1` = `"OCEAN_FLOOR"`, `2` = `"WORLD_SURFACE"`, `3` = `"OCEAN_FLOOR_WG"`, `4` = `"WORLD_SURFACE_WG"`, `5` = `"MOTION_BLOCKING_NO_LEAVES"`.
       - `0xFF`: read `VarInt nameLen` + `byte[nameLen]` custom UTF-8 heightmap name.
     - Read `VarInt payLen` and `byte[payLen] pay`:
       - **If `payLen == 2 && (pay[0] & 0xFF) == 0xFE` (Exact Duplicate):** Copy `decodedByOrder.get(pay[1] & 0xFF)`.
       - **Else if `payLen >= 3 && (pay[0] & 0xFF) == 0xFD` (Cross-Heightmap Delta):**
         Let `baseLongs = decodedByOrder.get(pay[1] & 0xFF)` and `numLongs = pay[2] & 0xFF`. Decode 256 columns from `pay[3..payLen)`:
         - `(tok & 0x80) != 0`: copy `runLen = (tok & 0x7F) + 1` columns directly from `baseLongs[c]`.
         - `(tok & 0x80) == 0`: decode ZigZag `delta` as above and set `cols[c] = (baseLongs[c] + delta) & 0x1FF`.
       - **Else:** Decode `pay` as a standalone heightmap delta stream.
     - Append the decoded `long[]` to `decodedByOrder`.

### 8.2 `boilerplateFlags16` Complete Bit Reference

| Bit | Mask | Field / Reconstructed Mojang NBT Key |
| ---: | ---: | :--- |
| `0` | `0x0001` | Reconstruct `"block_entities": TAG_List<TAG_Compound>[]` (empty list) |
| `1` | `0x0002` | Reconstruct `"block_ticks": TAG_List<TAG_Compound>[]` (empty list) |
| `2` | `0x0004` | Reconstruct `"fluid_ticks": TAG_List<TAG_Compound>[]` (empty list) |
| `3` | `0x0008` | Reconstruct `"isLightOn": (byte) 1` (`TAG_Byte`) |
| `4` | `0x0010` | Reconstruct `"structures": {"References": {}, "Starts": {}}` |
| `5` | `0x0020` | Read `uint8 ppEmptyCount`; reconstruct `"PostProcessing": TAG_List` of `ppEmptyCount` empty `TAG_List<TAG_Short>` sublists |
| `6` | `0x0040` | Read `VarInt v`; reconstruct `"starlight.light_version": (int) v` (`TAG_Int`) |
| `7` | `0x0080` | Read `uint8 lowFlags`; prepend boundary lighting section at `Y = (byte) (minSectionY - 1)` (`skyMode = lowFlags & 3`, `blkMode = (lowFlags >>> 2) & 3`, where `0` = absent, `1` = `byte[2048]` of `0x00`, `2` = `byte[2048]` of `0xFF`) |
| `8` | `0x0100` | Read `uint8 highFlags`; append boundary lighting section at `Y = (byte) (minSectionY + sectionCount)` (`skyMode = highFlags & 3`, `blkMode = (highFlags >>> 2) & 3`) |
| `9` | `0x0200` | Read secondary heightmaps (`uint8 secHmCount` + entries, Section 8.1) into `"Heightmaps"` |
| `10` | `0x0400` | Remaining bytes `[pos .. residualRelEnd)` contain a Mojang binary root `TAG_Compound` (containing non-empty `block_entities`, `structures`, custom tags, unrecognized `Status` strings when `chunkStatusOrdinal == 0xFD`, or `Entities`/`Sections` for `entities/` and `poi/` archives) |
| `11` | `0x0800` | Non-voxel chunk root (`entities/` or `poi/`): do **not** synthesize voxel chunk keys (`xPos`, `yPos`, `zPos`, `LastUpdate`, `InhabitedTime`, `Status`, `sections`); return only `DataVersion` + residual `TAG_Compound` entries |
| `12` | `0x1000` | The residual `TAG_Compound` slice (`bit 10`) is `Zlib` (`DEFLATE`) compressed (used only in `Mode A` when residual NBT $\ge 256\text{ B}$; never set in `Mode B`) |
| `13` | `0x2000` | Read `VarInt mbnlByteLen` + `byte[mbnlByteLen] mbnlStream` immediately following `boilerplateFlags16` and decode `"MOTION_BLOCKING_NO_LEAVES"` into `"Heightmaps"` (Section 8.1) |
| `14` | `0x4000` | `HAS_CHUNK_PATCH_TAIL`: Read `VarInt patchTailByteLen` + `byte[patchTailByteLen] chunkPatchTail` immediately before `bit 10` (`residualNbtSlice`), containing appended Slab-Local Micro-Palette entries and per-section voxel/light/offset patches (Section 8.3) |

### 8.3 `ChunkPatchTail` Wire Layout (`boilerplateFlags16` Bit `14` = `0x4000`, `HAS_CHUNK_PATCH_TAIL`)

Placing `bit 14` (`0x4000`) with a `VarInt patchTailByteLen` prefix immediately before `bit 10` (`0x0400`, `residualNbtSlice`) allows `readChunkView`, `readColumnProbe`, and `readTerrainProbe` to jump to `residualRelStart`, skip the compact header flags (`mbnlStream`, etc.), and decode `chunkPatchTail` in $O(1)$ without parsing Mojang NBT or inflating Zlib streams, while leaving `bit 10` unambiguously delimited as `[pos .. residualRelEnd)`.

```
+-----------------------------------------------------------------------+
| VarInt  patchTailByteLen (total bytes of payload + 1B patchParity8)   |
+-----------------------------------------------------------------------+
| 1. Appended Slab-Local Micro-Palette Extension                        |
|    VarInt  appendedStateCount (M >= 0)                                |
|    [If M > 0]:                                                        |
|      BaseBlockEntry[M]  (assigned localStateIdx = K .. K + M - 1,     |
|                          using Section 6.5 Part A entry encoding)     |
|      VarInt appendedPropCount                                         |
|      PropBinding[appendedPropCount]:                                  |
|        VarInt localStateIdx (K .. K + M - 1)                          |
|        uint8  propTag:                                                |
|          - If (propTag & 0x80) != 0:                                  |
|              propId = propTag & 0x7F -> STATIC_CORE_PROP_SUFFIXES     |
|          - Else (propTag == 0x40):                                    |
|              VarInt globalStateId -> Region Shared Dictionary[id]     |
+-----------------------------------------------------------------------+
| 2. Per-Section Patch Records                                          |
|    VarInt  patchedSectionCount (S >= 0)                               |
|    SectionPatch[S]:                                                   |
|      uint8   sectionIndex (0 .. sectionCount - 1, Y = minSectionY + i)|
|      uint8   patchFlags:                                              |
|                bit 0 (0x01): hasVoxelDeltas                           |
|                bit 1 (0x02): hasReplacementSectionOffset              |
|                bit 2 (0x04): hasSkyLightPatch                         |
|                bit 3 (0x08): hasBlockLightPatch                       |
|      [If (patchFlags & 0x02) != 0 (hasReplacementSectionOffset)]:     |
|        uint16 newSectionEntry16 (overrides sectionOffsets[sectionIdx])|
|      [If (patchFlags & 0x01) != 0 (hasVoxelDeltas)]:                  |
|        VarInt deltaCount (1 .. 4096)                                  |
|        For d = 0 .. deltaCount - 1:                                   |
|          uint16 yzx12       (0 .. 4095: (y << 8) | (z << 4) | x)      |
|          VarInt dictId      (localStateIdx 0..K+M-1 if slab-local,    |
|                              else globalStateId)                      |
|      [If (patchFlags & 0x04) != 0 (hasSkyLightPatch)]:                |
|        uint8  skyPatchMode  (01=ConstNibble [uint8 c4],               |
|                              10=GrayPlanes_YH2, 11=Raw2048B)          |
|        byte[] skyPatchPayload                                         |
|      [If (patchFlags & 0x08) != 0 (hasBlockLightPatch)]:              |
|        uint8  blkPatchMode  (01=ConstNibble [uint8 c4],               |
|                              10=GrayPlanes_H3,  11=Raw2048B)          |
|        byte[] blkPatchPayload                                         |
+-----------------------------------------------------------------------+
| 3. Integrity Syndrome Trailer                                         |
|    uint8   patchParity8 (computeHammingSecDed8 over 64-bit SplitMix   |
|                          hash of the preceding patchTailByteLen - 1 B)|
+-----------------------------------------------------------------------+
```

- **Pre-Section Resolution Order:** When `hasResidualNbt == 1` and `(boilerplateFlags16 & 0x4000) != 0`, readers decode `ChunkPatchTail` *before* iterating `sectionOffsets[0 .. sectionCount - 1]`:
  1. In **`formatVersion >= 0x0003`**, item 1 of `ChunkPatchTail` stores `VarInt appendedStateCount` followed directly by `VarInt appendedGlobalStateIds[appendedStateCount]` (pure Layer 2 BlockState IDs, `0 .. S-1`, `CBIT T7`; no `STATIC_CORE_*` tokens and no `PropBinding` list). In legacy **`formatVersion <= 0x0002`**, item 1 stores `BaseBlockEntry[M]` and `PropBinding` items (`LcaV2LegacyCodec`). Both extend the chunk's Slab-Local Micro-Palette from size `K` to `K + M`.
  2. Any `SectionPatch` with `hasReplacementSectionOffset` (`0x02`) updates `entries[sectionIndex]` (and `relStarts[sectionIndex]`).
  3. Inline-uniform sections (`(entry16 & 0x8000) != 0`) and non-uniform `SectionFrame`s can therefore reference appended local palette indices `K .. K + M - 1` directly.
  4. After decoding base section `i` (whether originally void `0x0000`, inline-uniform `0x8000`, or a non-uniform `SectionFrame` validated by `CBIT T6b`), any `hasVoxelDeltas`, `hasSkyLightPatch`, or `hasBlockLightPatch` entries for `sectionIndex == i` are applied on top.

---

## 9. Legacy `0x0002` Static Core Lookup Tables (`STATIC_CORE_*`)

> **Quarantine Notice (`formatVersion = 0x0003`, `anvil-api-ADR-006`):** `STATIC_CORE_STATES`, `STATIC_CORE_BIOMES`, and `STATIC_CORE_PROP_SUFFIXES` are **retired in `formatVersion = 0x0003` (`LCA-ICD-3.0`)** and quarantined in `LcaV2LegacyCodec.java`. No `0x0003` encoder or decoder reads or emits any `STATIC_CORE_*` token. The frozen 0-indexed tables below are retained solely for backward-compatible decoding of legacy `formatVersion = 0x0002` Slab-Local Micro-Palettes (`Part A` and `Part B`, Section 6.5) and `ChunkPatchTail` (§8.3).

### 9.1 `STATIC_CORE_STATES` (`160` Base Block Identifiers)
- Index `0..127`: encoded in Part A as `uint8 (0x80 | id)`
- Index `128..159`: encoded in Part A as `uint8 (0x60 | (id - 128))`

| IDs | `0` / `+0` | `+1` | `+2` | `+3` |
| ---: | :--- | :--- | :--- | :--- |
| **`0..3`** | `minecraft:air` | `minecraft:stone` | `minecraft:deepslate` | `minecraft:dirt` |
| **`4..7`** | `minecraft:grass_block` | `minecraft:podzol` | `minecraft:coarse_dirt` | `minecraft:rooted_dirt` |
| **`8..11`** | `minecraft:mycelium` | `minecraft:dirt_path` | `minecraft:farmland` | `minecraft:mud` |
| **`12..15`** | `minecraft:packed_mud` | `minecraft:clay` | `minecraft:gravel` | `minecraft:sand` |
| **`16..19`** | `minecraft:red_sand` | `minecraft:sandstone` | `minecraft:red_sandstone` | `minecraft:bedrock` |
| **`20..23`** | `minecraft:water` | `minecraft:lava` | `minecraft:granite` | `minecraft:diorite` |
| **`24..27`** | `minecraft:andesite` | `minecraft:tuff` | `minecraft:calcite` | `minecraft:dripstone_block` |
| **`28..31`** | `minecraft:pointed_dripstone` | `minecraft:smooth_basalt` | `minecraft:basalt` | `minecraft:blackstone` |
| **`32..35`** | `minecraft:obsidian` | `minecraft:crying_obsidian` | `minecraft:cobblestone` | `minecraft:mossy_cobblestone` |
| **`36..39`** | `minecraft:coal_ore` | `minecraft:deepslate_coal_ore` | `minecraft:iron_ore` | `minecraft:deepslate_iron_ore` |
| **`40..43`** | `minecraft:copper_ore` | `minecraft:deepslate_copper_ore` | `minecraft:gold_ore` | `minecraft:deepslate_gold_ore` |
| **`44..47`** | `minecraft:redstone_ore` | `minecraft:deepslate_redstone_ore` | `minecraft:diamond_ore` | `minecraft:deepslate_diamond_ore` |
| **`48..51`** | `minecraft:emerald_ore` | `minecraft:deepslate_emerald_ore` | `minecraft:lapis_ore` | `minecraft:deepslate_lapis_ore` |
| **`52..55`** | `minecraft:raw_iron_block` | `minecraft:raw_copper_block` | `minecraft:raw_gold_block` | `minecraft:amethyst_block` |
| **`56..59`** | `minecraft:budding_amethyst` | `minecraft:amethyst_cluster` | `minecraft:snow` | `minecraft:snow_block` |
| **`60..63`** | `minecraft:powder_snow` | `minecraft:ice` | `minecraft:packed_ice` | `minecraft:blue_ice` |
| **`64..67`** | `minecraft:moss_block` | `minecraft:moss_carpet` | `minecraft:glow_lichen` | `minecraft:vine` |
| **`68..71`** | `minecraft:cave_vines` | `minecraft:cave_vines_plant` | `minecraft:spore_blossom` | `minecraft:hanging_roots` |
| **`72..75`** | `minecraft:big_dripleaf` | `minecraft:big_dripleaf_stem` | `minecraft:small_dripleaf` | `minecraft:azalea` |
| **`76..79`** | `minecraft:flowering_azalea` | `minecraft:oak_log` | `minecraft:spruce_log` | `minecraft:birch_log` |
| **`80..83`** | `minecraft:jungle_log` | `minecraft:acacia_log` | `minecraft:dark_oak_log` | `minecraft:mangrove_log` |
| **`84..87`** | `minecraft:cherry_log` | `minecraft:oak_leaves` | `minecraft:spruce_leaves` | `minecraft:birch_leaves` |
| **`88..91`** | `minecraft:jungle_leaves` | `minecraft:acacia_leaves` | `minecraft:dark_oak_leaves` | `minecraft:mangrove_leaves` |
| **`92..95`** | `minecraft:cherry_leaves` | `minecraft:azalea_leaves` | `minecraft:flowering_azalea_leaves` | `minecraft:short_grass` |
| **`96..99`** | `minecraft:grass` | `minecraft:tall_grass` | `minecraft:fern` | `minecraft:large_fern` |
| **`100..103`** | `minecraft:dandelion` | `minecraft:poppy` | `minecraft:blue_orchid` | `minecraft:allium` |
| **`104..107`** | `minecraft:azure_bluet` | `minecraft:red_tulip` | `minecraft:orange_tulip` | `minecraft:white_tulip` |
| **`108..111`** | `minecraft:pink_tulip` | `minecraft:oxeye_daisy` | `minecraft:cornflower` | `minecraft:lily_of_the_valley` |
| **`112..115`** | `minecraft:sweet_berry_bush` | `minecraft:pumpkin` | `minecraft:melon` | `minecraft:sugar_cane` |
| **`116..119`** | `minecraft:bamboo` | `minecraft:kelp` | `minecraft:kelp_plant` | `minecraft:seagrass` |
| **`120..123`** | `minecraft:tall_seagrass` | `minecraft:cave_air` | `minecraft:void_air` | `minecraft:infested_stone` |
| **`124..127`** | `minecraft:infested_deepslate` | `minecraft:sculk` | `minecraft:sculk_vein` | `minecraft:netherrack` |
| **`128..131`** | `minecraft:magma_block` | `minecraft:bubble_column` | `minecraft:firefly_bush` | `minecraft:brown_mushroom` |
| **`132..135`** | `minecraft:small_amethyst_bud` | `minecraft:medium_amethyst_bud` | `minecraft:large_amethyst_bud` | `minecraft:oak_fence` |
| **`136..139`** | `minecraft:oak_planks` | `minecraft:cobweb` | `minecraft:chest` | `minecraft:spawner` |
| **`140..143`** | `minecraft:rail` | `minecraft:wall_torch` | `minecraft:end_stone` | `minecraft:soul_sand` |
| **`144..147`** | `minecraft:soul_soil` | `minecraft:glowstone` | `minecraft:nether_quartz_ore` | `minecraft:nether_gold_ore` |
| **`148..151`** | `minecraft:crimson_nylium` | `minecraft:warped_nylium` | `minecraft:nether_wart_block` | `minecraft:warped_wart_block` |
| **`152..155`** | `minecraft:shroomlight` | `minecraft:ancient_debris` | `minecraft:bone_block` | `minecraft:stone_bricks` |
| **`156..159`** | `minecraft:mossy_stone_bricks` | `minecraft:cracked_stone_bricks` | `minecraft:torch` | `minecraft:lantern` |

### 9.2 `STATIC_CORE_BIOMES` (`65` Biome Identifiers)
- Index `0..64`: encoded in Part A as `uint8 (0x80 | id)`

| IDs | `0` / `+0` | `+1` | `+2` | `+3` |
| ---: | :--- | :--- | :--- | :--- |
| **`0..3`** | `minecraft:plains` | `minecraft:sunflower_plains` | `minecraft:snowy_plains` | `minecraft:ice_spikes` |
| **`4..7`** | `minecraft:desert` | `minecraft:swamp` | `minecraft:mangrove_swamp` | `minecraft:forest` |
| **`8..11`** | `minecraft:flower_forest` | `minecraft:birch_forest` | `minecraft:dark_forest` | `minecraft:old_growth_birch_forest` |
| **`12..15`** | `minecraft:old_growth_pine_taiga` | `minecraft:old_growth_spruce_taiga` | `minecraft:taiga` | `minecraft:snowy_taiga` |
| **`16..19`** | `minecraft:savanna` | `minecraft:savanna_plateau` | `minecraft:windswept_hills` | `minecraft:windswept_gravelly_hills` |
| **`20..23`** | `minecraft:windswept_forest` | `minecraft:windswept_savanna` | `minecraft:jungle` | `minecraft:sparse_jungle` |
| **`24..27`** | `minecraft:bamboo_jungle` | `minecraft:badlands` | `minecraft:eroded_badlands` | `minecraft:wooded_badlands` |
| **`28..31`** | `minecraft:meadow` | `minecraft:cherry_grove` | `minecraft:grove` | `minecraft:snowy_slopes` |
| **`32..35`** | `minecraft:frozen_peaks` | `minecraft:jagged_peaks` | `minecraft:stony_peaks` | `minecraft:river` |
| **`36..39`** | `minecraft:frozen_river` | `minecraft:beach` | `minecraft:snowy_beach` | `minecraft:stony_shore` |
| **`40..43`** | `minecraft:warm_ocean` | `minecraft:lukewarm_ocean` | `minecraft:deep_lukewarm_ocean` | `minecraft:ocean` |
| **`44..47`** | `minecraft:deep_ocean` | `minecraft:cold_ocean` | `minecraft:deep_cold_ocean` | `minecraft:frozen_ocean` |
| **`48..51`** | `minecraft:deep_frozen_ocean` | `minecraft:mushroom_fields` | `minecraft:dripstone_caves` | `minecraft:lush_caves` |
| **`52..55`** | `minecraft:deep_dark` | `minecraft:nether_wastes` | `minecraft:crimson_forest` | `minecraft:warped_forest` |
| **`56..59`** | `minecraft:soul_sand_valley` | `minecraft:basalt_deltas` | `minecraft:the_end` | `minecraft:the_void` |
| **`60..63`** | `minecraft:small_end_islands` | `minecraft:end_midlands` | `minecraft:end_highlands` | `minecraft:end_barrens` |
| **`64`** | `minecraft:pale_garden` | - | - | - |

### 9.3 `STATIC_CORE_PROP_SUFFIXES` (`128` Property Suffixes)
- Index `0..127`: encoded in Part B as `uint8 (0x80 | propId)`

| IDs | `0` / `+0` | `+1` | `+2` | `+3` |
| ---: | :--- | :--- | :--- | :--- |
| **`0..3`** | `[axis=y]` | `[axis=x]` | `[axis=z]` | `[lit=false]` |
| **`4..7`** | `[lit=true]` | `[snowy=false]` | `[snowy=true]` | `[waterlogged=false]` |
| **`8..11`** | `[waterlogged=true]` | `[half=lower]` | `[half=upper]` | `[half=bottom]` |
| **`12..15`** | `[half=top]` | `[drag=true]` | `[drag=false]` | `[berries=false]` |
| **`16..19`** | `[berries=true]` | `[level=0]` | `[level=1]` | `[level=2]` |
| **`20..23`** | `[level=3]` | `[level=4]` | `[level=5]` | `[level=6]` |
| **`24..27`** | `[level=7]` | `[level=8]` | `[level=9]` | `[level=10]` |
| **`28..31`** | `[level=11]` | `[level=12]` | `[level=13]` | `[level=14]` |
| **`32..35`** | `[level=15]` | `[layers=1]` | `[layers=2]` | `[layers=3]` |
| **`36..39`** | `[layers=4]` | `[layers=5]` | `[layers=6]` | `[layers=7]` |
| **`40..43`** | `[layers=8]` | `[distance=1,persistent=false,waterlogged=false]` | `[distance=2,persistent=false,waterlogged=false]` | `[distance=3,persistent=false,waterlogged=false]` |
| **`44..47`** | `[distance=4,persistent=false,waterlogged=false]` | `[distance=5,persistent=false,waterlogged=false]` | `[distance=6,persistent=false,waterlogged=false]` | `[distance=7,persistent=false,waterlogged=false]` |
| **`48..51`** | `[persistent=false,waterlogged=false,distance=1]` | `[persistent=false,waterlogged=false,distance=2]` | `[persistent=false,waterlogged=false,distance=3]` | `[persistent=false,waterlogged=false,distance=4]` |
| **`52..55`** | `[persistent=false,waterlogged=false,distance=5]` | `[persistent=false,waterlogged=false,distance=6]` | `[persistent=false,waterlogged=false,distance=7]` | `[facing=up,waterlogged=false]` |
| **`56..59`** | `[facing=down,waterlogged=false]` | `[facing=north,waterlogged=false]` | `[facing=south,waterlogged=false]` | `[facing=east,waterlogged=false]` |
| **`60..63`** | `[facing=west,waterlogged=false]` | `[facing=north]` | `[facing=south]` | `[facing=east]` |
| **`64..67`** | `[facing=west]` | `[facing=up]` | `[facing=down]` | `[age=0]` |
| **`68..71`** | `[age=1]` | `[age=2]` | `[age=3]` | `[age=4]` |
| **`72..75`** | `[age=5]` | `[age=6]` | `[age=7]` | `[age=8]` |
| **`76..79`** | `[age=9]` | `[age=10]` | `[age=11]` | `[age=12]` |
| **`80..83`** | `[age=13]` | `[age=14]` | `[age=15]` | `[age=16]` |
| **`84..87`** | `[age=17]` | `[age=18]` | `[age=19]` | `[age=20]` |
| **`88..91`** | `[age=21]` | `[age=22]` | `[age=23]` | `[age=24]` |
| **`92..95`** | `[age=25]` | `[age=20,berries=false]` | `[age=21,berries=false]` | `[age=22,berries=false]` |
| **`96..99`** | `[age=23,berries=false]` | `[age=24,berries=false]` | `[age=25,berries=false]` | `[age=23,berries=true]` |
| **`100..103`** | `[age=24,berries=true]` | `[age=25,berries=true]` | `[waterlogged=false,shape=east_west]` | `[waterlogged=false,shape=north_south]` |
| **`104..107`** | `[shape=north_south,waterlogged=false]` | `[shape=east_west,waterlogged=false]` | `[stage=0]` | `[stage=1]` |
| **`108..111`** | `[hanging=false,waterlogged=false]` | `[hanging=true,waterlogged=false]` | `[type=bottom,waterlogged=false]` | `[type=top,waterlogged=false]` |
| **`112..115`** | `[type=double,waterlogged=false]` | `[east=false,north=false,south=false,up=false,west=false]` | `[bites=0]` | `[moisture=0]` |
| **`116..119`** | `[moisture=7]` | `[distance=7,persistent=true,waterlogged=false]` | `[distance=1,persistent=true,waterlogged=false]` | `[eye=false,facing=north]` |
| **`120..123`** | `[eye=false,facing=south]` | `[eye=false,facing=east]` | `[eye=false,facing=west]` | `[pickles=1,waterlogged=true]` |
| **`124..127`** | `[candles=1,lit=false,waterlogged=false]` | `[facing=north,type=single,waterlogged=false]` | `[facing=south,type=single,waterlogged=false]` | `[facing=east,type=single,waterlogged=false]` |

*(Note: Total entries in `STATIC_CORE_PROP_SUFFIXES` is `128` (`0..127`).)*

---

## 10. Reader & Recovery Procedures

### 10.1 Standard Chunk Read Procedure

To read chunk `(chunkX, chunkZ)` from `r.<rx>.<rz>.lca`:

1. **Validate Superblock & Footer (`CBIT T1`, `T2`):**
   - Check `fileLength >= 0x2048`, `readUint64BE(0x00) == 0x4C43415F56303031L` (`"LCA_V001"`), `readUint64BE(fileLength - 8) == 0x4C4341315F454E44L` (`"LCA1_END"`), `readUint16BE(0x08) == 2`, `readUint16BE(0x1A) == 8`, and `computeSuperblockChecksum64(buf, 0) == readUint64BE(0x38)`.
   - Read `minSectionY = readInt32BE(0x14)` and `sectionCount = readUint16BE(0x18)`.
2. **Read & Verify 64-Bit Chunk Pointer (`CBIT T3`):**
   - Compute `chunkIdx = (chunkX & 31) | ((chunkZ & 31) << 5)`.
   - Read `ptr64 = readUint64BE(0x0040 + (chunkIdx << 3))`. If `(superblock[0x0B] & 0x10) != 0`, verify/heal `ptr64` with `secDedByte = buf[secDedTableOffset + chunkIdx] & 0xFF` (Section 4.4).
   - Extract `slabOffset35 = (ptr64 >>> 29) & 0x7_FFFF_FFFFL`, `codec1 = (int) ((ptr64 >>> 28) & 1L)`, `exactByteLen20 = (int) ((ptr64 >>> 8) & 0xFFFFFL)`. If `slabOffset35 == 0` or `exactByteLen20 == 0`, the chunk is ungenerated (`null`).
3. **Locate & Unpack `ChunkFrame` (`CBIT T3b`, `T3c`):**
   - Seek to `fOff = slabOffset35 << 8`. Verify `(readInt32BE(fOff) & 0xFC00_0000) == 0x5800_0000` and `((readInt32BE(fOff) >>> 16) & 0x03FF) == chunkIdx`.
   - If `codec1 == 1` (`Mode B`), heal the `headerWords = (24 + 2 * sectionCount + 7) >>> 3` header words using the SEC-DED bytes at `fOff + headerLen` (Section 6.2), and decode the `LCD2` (`0x4C44`) frame over `[fOff + headerLen + headerWords .. fOff + exactByteLen20)` against the Layer 2 `dictPatternArena` to form the uncompressed `ChunkFrame`.
4. **Decode Palette, Patch Tail & Sections:**
   - If `hasSlabLocalPalette` (`(chunkFlags & 2) != 0`), decode the initial Part A (and Part B if blockstate properties `[...]` are needed) at `[sectionDirEnd .. min_k(relStart_k))` (Section 6.5).
   - If `hasResidualNbt` (`(chunkFlags & 1) != 0`) and `(boilerplateFlags16 & 0x4000) != 0`, decode `ChunkPatchTail` (Section 8.3), appending any `BaseBlockEntry[M]` / `PropBinding` items to the Slab-Local Micro-Palette and applying any `hasReplacementSectionOffset` (`0x02`) overrides to `sectionOffsets[]`.
   - For each section `i = 0 .. sectionCount - 1` (`Y = minSectionY + i`), inspect `entry16 = sectionOffsets[i]`:
     - `0x0000`: void/air section (or synthesized base if patched by `ChunkPatchTail`).
     - `(entry16 & 0x8000) != 0`: inline uniform section (Section 6.4).
     - Otherwise: decode non-uniform `SectionFrame` over `[relStart_i .. relEnd_i)` (Section 6.4 & Section 7), and apply any `SectionPatch` voxel/light deltas for `i`.
5. **Decode Residual Tail (if `hasResidualNbt`, `(chunkFlags & 1) != 0`):**
   - At `residualRelStart = sectionDirEnd + (residualNbtRelOffset << unitShift)` up to `residualRelEnd = min({relStart_j | relStart_j > residualRelStart} U {frameLen})`, read `boilerplateFlags16` and decode `mbnlStream` (when bit `13` is set) and any flagged residual fields (Section 8).

### 10.2 4-Tier Bit-Rot Recovery & Self-Describing Slab Reverse-Scan

When `LcaRegionReader.healRegionBitRot(byte[])` or a fault-tolerant reader encounters corruption:

1. **Tier 1 - Extended Hamming `(72, 64)` SEC-DED Pointer & Mode B Header Healing:**
   - For each `chunkIdx = 0 .. 1023`, evaluate `ptr64` at `0x0040 + (chunkIdx << 3)` against `storedSyndrome8 = buf[secDedTableByteOffset + chunkIdx] & 0xFF` using the algorithm in Section 4.4.
   - If `totalParity == 1` and `Integer.bitCount(errSyn7) >= 2`, flip bit `SECDED_SYNDROME_TO_BIT[errSyn7]` in `ptr64`.
   - For `Mode B` chunks (`codec1 == 1`), also evaluate each 64-bit word `w = 0 .. headerWords - 1` of the uncompressed `ChunkFrame` header `[fOff .. fOff + headerLen)` against `buf[fOff + headerLen + w] & 0xFF` (Section 6.2).
   - Mark `needsSlabScan[chunkIdx] = true` if any of the following hold:
     - `healSingleBitFlip64` detects a multi-bit (`DED`) error (`totalParity == 0 && diff8 != 0`),
     - `(slabOffset35 << 8) < slabPoolBaseOffset` or `(slabOffset35 << 8) + exactByteLen20 > fileLength - 8` (`CBIT T3`),
     - The target slab header word `dvWord = readInt32BE(fOff)` fails `(dvWord & 0xFC00_0000) == 0x5800_0000` or `((dvWord >>> 16) & 0x03FF) != chunkIdx` (`CBIT T3b`).
2. **Tier 2 - Self-Describing `256B` Slab Pool Reverse-Scan:**
   - If any `needsSlabScan[chunkIdx]` is `true`, iterate `slab = (slabPoolBaseOffset >>> 8) .. ((fileLength - 8) >>> 8) - 1` (`bytePos = slab << 8`):
     - Probe whether the slab at `bytePos` is `Mode B` (`slabModeB`: `LCD2` frame magic `0x4C, 0x44` (`"LD"`) at `bytePos + headerLen + headerWords` or valid inline `headerWords` SEC-DED syndromes on a zero-body header). Read `dvWord = readInt32BE(bytePos)`. If `slabModeB` is `true`, evaluate Tier 1 header SEC-DED healing over a scratch copy of `[bytePos .. bytePos + headerLen)` using `[bytePos + headerLen .. bytePos + headerLen + headerWords)`.
     - If `(dvWord & 0xFC00_0000) == 0x5800_0000`, extract `slabChunkIdx = (dvWord >>> 16) & 0x03FF`.
     - If `needsSlabScan[slabChunkIdx]` is `true`, commit any healed `Mode B` header words and compute the frame's byte length `exactLen` using `slabModeB`:
       - **In Mode A (`slabModeB == false`, `codec1 == 0`):** Read `chunkFlags = buf[bytePos + 0x15] & 0xFF`, `unitShift = ((chunkFlags & 4) != 0) ? 2 : 0`, and `residualRel = readUint16BE(bytePos + 0x16)`. Let `residualStart = bytePos + sectionDirEnd + (residualRel << unitShift)`. If `(chunkFlags & 1) == 0`, `exactLen = sectionDirEnd + (residualRel << unitShift)`. Otherwise read `bpFlags = readUint16BE(residualStart)` and advance past each active `boilerplateFlags16` field (`bit 13` `VarInt mbnlLen` + `mbnlStream`, `bit 5..9` fields, `bit 14` (`0x4000`) `VarInt patchTailByteLen` + `chunkPatchTail`, and `bit 10` residual NBT or any appended post-residual `SectionFrame`s up to the next `0x5800_0000` slab header or `"LCA1_END"` footer).
       - **In Mode B (`slabModeB == true`, `codec1 == 1`):** At `compStart = bytePos + sectionDirEnd + headerWords`, verify the 2-byte `LCD2` magic `0x4C, 0x44` (`"LD"`) and read `uint24BE compressedBodyLen` at `compStart + 3..5` to obtain `exactLen = sectionDirEnd + headerWords + compressedBodyLen`.
     - Reconstruct `ptr64 = ((long) slab << 29) | ((slabModeB ? 1L : 0L) << 28) | ((exactLen & 0xFFFFFL) << 8) | 1L`, write `ptr64` to `0x0040 + (slabChunkIdx << 3)`, and write `computeHammingSecDed8(ptr64)` to `secDedTableByteOffset + slabChunkIdx`.
3. **Tier 3 - Section-Granular `octreeOccupancyMask64` Quarantine (`CBIT T6`, `T6b`):**
   - If decoding a non-uniform `SectionFrame` fails `cursor <= 4096` (`CBIT T6`) or `decodedOctreeMask64 == octreeOccupancyMask64` (`CBIT T6b`), quarantine only that $16 \times 16 \times 16$ section and fill its $4,096$ voxels with its dominant block state `palette[0]` (`dictIds[0]` from Section 7 item 3) while decoding all other vertical sections of the chunk normally.
4. **Tier 4 - Copy-on-Write (`CoW`) Prior-Generation Pointer Rollback:**
   - In live writer sessions (`LcaLiveArchive`), if an active chunk pointer fails validation after an interrupted write, restore `prevChunkPointers[chunkIdx]` from the 4-epoch CoW grace ring.

### 10.3 Format Version `0x0001` $\to$ `0x0002` Upgrade Procedure

Archives created under `formatVersion = 0x0001` (`LCA_FORMAT_VERSION_V1`) share the identical 64-byte superblock, 8 KiB pointer directory, chained shared dictionary, SEC-DED syndrome tables, `ChunkFrame` headers, and residual NBT layout as `formatVersion = 0x0002`, differing only in section-level `octreeOccupancyMask64` cursor domain compaction (Section 7 item 4) and Gray light `planeModes8` `mode 01` sub-headers (Section 7 item 6).

### 10.4 Format Version `0x0002` $\to$ `0x0003` Atomic Upgrade Procedure

1. **Non-Mutating Read Path:** Read-only `LcaRegionReader` operations decode `formatVersion = 0x0002` archives losslessly via `LcaV2LegacyCodec` without modifying bytes on disk.
2. **Atomic Writable Upgrade (`REQ-RTP-S-004`):** Opening a `0x0002` `.lca` file for live writes (`LcaRegionWriter#openLiveArchive`, `LcaRegionFileStorage#getOrOpenRegion`) or invoking world conversion/healing (`LeafAnvilWorldConverter`) invokes `LcaRegionWriter.upgradeLegacyArchiveAtomically`:
   - Reads and validates all generated chunks from the `0x0002` archive, transcodes them into a fresh `0x0003` archive (factored Layer 2 dictionary + pure-ID Layer 3 micro-palettes, preserving Mode A/Mode B or applying the requested `lcd2Level`),
   - Writes the upgraded archive to a sibling `.v3upgrade.tmp` file with `FileChannel.force(true)`, atomically replaces the target via `ATOMIC_MOVE` (+ parent directory fsync on POSIX), invalidates `LcaRegionReader` file caches, and cleans up `.tmp` on any error.

---

## 11. Format Version `0x0003` (`LCA-ICD-3.0`) Normative Extensions

Archives written with superblock `formatVersion == 0x0003` (`0x08..0x09`) retain the identical 64-byte superblock, 8 KiB pointer directory, chained `SEC-DED`-protected dictionary layout (`0x2040`, factored into `BaseBlock`/`PropTuple`/`BlockState`/`Biome` per Section 5.1), `ChunkFrame` headers, pure-ID Layer 3 micro-palettes & `ChunkPatchTail` (Sections 6.5 & 8.3), `LCD2` (`0x4C44`) `Mode B` framing, and footer canary (`"LCA1_END"`). Readers accept `formatVersion` $\in \{\mathtt{0x0002}, \mathtt{0x0003}\}$ (`CBIT T1` rejects `0x0000`, `0x0001`, and `>= 0x0004`). Any `0x0003` sentinel encountered inside a `formatVersion == 0x0002` archive fails closed (`CBIT T5`/`T7`).

### 11.1 `SectionFrame` Extension Escape (`paletteLen == 0`, `secExtFlags`)
- In `formatVersion >= 0x0003` with `blockEncodingMode` $\in \{\mathtt{01}, \mathtt{11}\}$, a leading `paletteLen == 0` (`0x00`) introduces:
  - `uint8 secExtFlags`:
    - `0x01`: `SEC_EXT_RANK_EXCLUDED` (Adaptive Successive Rank-Exclusion)
    - `0x02`: `SEC_EXT_GRAY_BITPLANES` (Dominant-Pinned Adjacency-Ordered Local Gray Bitplanes, `anvil-api-ADR-006`)
    - Bits `0` and `1` are mutually exclusive (`0x03` fails closed `CBIT T5`); bits `7..2` are reserved and must be `0` (`CBIT T5`).
  - `VarInt actualPaletteLen` (`2 .. 4096`, else `CBIT T7`), followed by `VarInt dictIds[actualPaletteLen]`.
- **Mode `0x01` (`SEC_EXT_RANK_EXCLUDED`):** Each minority container $p \in [1, \text{paletteLen}-1]$ encodes runs over the successively compacted unclaimed domain $D_p = D_{p-1} \setminus S_{p-1}$ (`LcaV3Codec#decodeRankExcludedDeltaRoaringBlocks`) and enforces mandatory `CBIT T6b` (`decodedOctreeMask64 == expectedOctreeMask64`). Opt-in for archival compaction; decoders support it unconditionally.
- **Mode `0x02` (`SEC_EXT_GRAY_BITPLANES`, `anvil-api-ADR-006`):** Dominant `dictIds[0]` is pinned (preserving `octreeOccupancyMask64` skipping and Tier-3 dominant-stratum recovery), while minority states `dictIds[1 .. actualPaletteLen - 1]` are ordered by local 3D curve adjacency. Immediately after `dictIds[actualPaletteLen]`:
  - `VarInt payloadByteLen` (`>= ceil(b / 4)` where `b = ceil(log2(actualPaletteLen))`, bounded by section limit -> `CBIT T5`), enabling $O(1)$ `LcaSectionCodec#skipSectionFrame` (`REQ-RTP-S-005`).
  - `byte[ceil(b / 4)] planeModes`: `2` bits per Gray bitplane `j = 0 .. b - 1` (`00 = Const0`, `01 = TieredSubHdr/RLE/CrossPlaneXOR`, `11 = RawPackedBits (ceil(activeVoxels / 8) B)`, `10 = Forbidden -> CBIT T5`; unused tail bits in the final byte must be `0` -> `CBIT T5`).
  - `byte[] planePayloads[0..b-1]`: Reflected Binary Gray bitplanes `g(v) = p(v) ^ (p(v) >>> 1)` over the `activeVoxels` active-octant curve domain.
  - On decode (`LcaV3Codec#decodeGrayBitplaneBlocks`), each active voxel computes `p = invGray(g)`, validates `0 <= p < actualPaletteLen` (`CBIT T6`), and enforces `decodedOctreeMask64 == expectedOctreeMask64` (`CBIT T6b`). Opt-in via `FEATURE_GRAY_BITPLANES` for stratified structures/schematics; decoders support it unconditionally.

### 11.2 Block-Conditioned Eikonal Light Residuals + `ADR-005` `B1` (`sentinelHdr`)
- In `formatVersion >= 0x0003` (`storageKind != 3`), when `skyLightMode == 10` or `blockLightMode == 10`, if `(firstByte & 0x03) == 0x02` (`sentinelHdr`), the channel encodes an Eikonal light residual rather than 4-bitplane `GrayPlanes`:
  - `sentinelHdr` bit layout:
    - Bits `1..0`: `10` (`0x02`, Eikonal sentinel)
    - Bits `3..2`: `predictorSubMode2` (`0 = BlockLight`, `1 = SkyLight open-sky`, `2 = SkyLight top-face seeded`, `3 = reserved -> CBIT T5`)
    - Bits `6..4`: `predictorTableId3` (`0 = LCA_LIGHT_TABLE_V1`; any non-zero value -> `CBIT T5`)
    - Bit `7`: `topSeedMode` (`0 = nibble RLE`, `1 = raw 128 B packed nibbles`; valid only for `subMode == 2`, else `CBIT T5`)
  - Immediately followed by `VarInt payloadByteLen` (`>= 1`, bounded by section limit -> `CBIT T5`), enabling $O(1)$ `skipLightChannel` (`REQ-RTP-S-005`).
  - Inside `payloadByteLen`: optional `topFaceSeed` (when `subMode == 2`), `uint8 residualEncodingMode` (`0x00 = B1` `64-bit` octant-bitmask + per-active-octant `64-bit` voxel mask + packed 4-bit non-zero modular residuals `(actual - hatL) & 0x0F`, `lo | (hi << 4)`).
- **`LCA_LIGHT_TABLE_V1` (`LcaV3Codec#classifyLightV1`):** Frozen static classifier mapping canonical blockstate strings to `(opacity4 << 4) | (emission4 & 0x0F)`, explicitly classifying `minecraft:tinted_glass` as opacity `15` and running bounded 6-connected fixed-point Eikonal relaxation (`int[4096]` circular ring + `long[64]` `inQueue` deduplication). Enabled by default in `Mode B` (`LCD2`) and opt-in in `Mode A` (`0-LZ`) to preserve `Mode A` live-save latency below `.mca` `Zlib`.

### 11.3 `boilerplateFlags16` Bit `15` (`0x8000`) Columnar Ticks & Template `block_entities`
- In `formatVersion >= 0x0003`, `boilerplateFlags16` bit `15` (`0x8000`, `HAS_EXTENDED_FLAGS`) appears strictly after `Bit 14` (`ChunkPatchTail`) and strictly before `Bit 10` (`residualNbtSlice`):
  - `VarInt extFlags`: `bit 0 = 0x01` (`block_ticks`), `bit 1 = 0x02` (`fluid_ticks`), `bit 2 = 0x04` (`block_entities`), bits `3..31` reserved (`0`, else `CBIT T5`). Mutual exclusion with empty-list bits `0, 1, 2` is enforced (`CBIT T5`).
  - Each active sub-stream is framed by `VarInt subStreamByteLen` + payload:
    - `ColumnarTickStream`: `VarInt count` (`1..65536`), `uint8 tickFlags` (`bit 0 = allInChunkXZ`, `bit 1 = allPriorityZero`), unique ID dictionary (`(utfLen << 1) | mcPrefixBit`), optional `idOrds`, packed `XZ` (`(lx << 4) | lz`) or `ZigZagVarLong` `dX/dZ`, `ZigZagVarLong` `deltaY`, `deltaT`, and optional `p`. Non-vanilla schemas fall back losslessly to `Bit 10`.
    - `ColumnarBlockEntityStream`: `VarInt count` (`1..16384`), `uint8 beFlags` (`bit 0 = allInChunkXYZ`), template table (`VarInt templateCount`, `[VarInt byteLen, rawNbtCompoundBodyWithTagEnd]`), and per-instance template ordinal + `x,y,z` / `Pos[3]` coordinates.
