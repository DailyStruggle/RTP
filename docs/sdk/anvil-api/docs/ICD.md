# `.lca` (LeafCraft Anvil) Binary Format Interface Control Document (ICD)

- **Document ID:** `LCA-ICD-1.0`
- **Format Version:** `0x0001` (`LCA_V001`, Major `1`, Minor `0`)
- **File Naming:** `r.<regionX>.<regionZ>.lca` (under `region/`, `entities/`, or `poi/`)
- **Governing Specifications:** [`anvil-api-ADR-003`](adr/anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md), [`anvil-api-ADR-002`](adr/anvil-api-ADR-002-multi-format-region-support.md), `leafanvil-ADR-001` (`resource/LeafAnvil`)
- **Reference Implementation:** `io.github.dailystruggle.anvil.LcaRegionReader`, `LcaRegionWriter`, `LcaSectionCodec` ([`api/anvil-api`](../README.md), MIT License)

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
| **Extended Hamming `(72, 64)` SEC-DED Syndrome Table** | `secDedTableByteOffset` (`0x2440` default) | `1,024 B` (`1 B/chunk`) | [Section 4.4 Extended Hamming `(72, 64)` SEC-DED Table](#44-extended-hamming-72-64-sec-ded-pointer-syndrome-table-1024-bytes) |
| **`ChunkFrame` Uncompressed Header & Canary** | `slabOffset35 << 8` (`+0x00 .. +headerLen-1`) | `24 + 2 * sectionCount B` | [Section 6.1 Uncompressed `ChunkFrame` Header](#61-uncompressed-chunkframe-header-headerlen--24--2--sectioncount-bytes) |
| **`Mode B` Header SEC-DED + `Zstd-1` Frame Body** | `(slabOffset35 << 8) + headerLen` | `headerWords B + Zstd` | [Section 6.2 Mode B (`codec1 = 1`) Wrapper](#62-mode-b-codec1--1-header-sec-ded-syndromes--zstd-1-body-decompression) |
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
6. [Layer 3: Self-Describing `ChunkFrame` Header, Mode B Wrapper & Section Directory](#6-layer-3-self-describing-chunkframe-header-mode-b-wrapper--section-directory)
   - [6.1 Uncompressed `ChunkFrame` Header (`headerLen = 24 + 2 * sectionCount` Bytes)](#61-uncompressed-chunkframe-header-headerlen--24--2--sectioncount-bytes)
   - [6.2 Mode B (`codec1 = 1`) Header SEC-DED Syndromes & `Zstd-1` Body Decompression](#62-mode-b-codec1--1-header-sec-ded-syndromes--zstd-1-body-decompression)
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

> **Open Format & Interoperability License (`MIT`):** While the `LeafAnvil` runtime carrier plugin/mod (`net.leaf.anvil.*`, `resource/LeafAnvil`) is distributed under the **LeafCraft Commercial Software License** (`LicenseRef-LeafCraft-Commercial`), this `.lca` binary specification (`LCA-ICD-1.0`) and the `io.github.dailystruggle.anvil` codec library (`api/anvil-api`) are openly licensed under the **MIT License**. Third-party plugins, world generators, server forks, map renderers, and backup utilities may freely implement or reverse-engineer `.lca` readers and writers without restriction.

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
| `0x08..0x09` | `uint16` | `formatVersion` | `0x0001` (Major `1`, Minor `0`) |
| `0x0A` | `uint8` | `storageKind` | `0` = `REGION_VOXEL` (`region/`), `1` = `ENTITIES` (`entities/`), `2` = `POI` (`poi/`), `3` = `MAP_TILE` |
| `0x0B` | `uint8` | `flags` | Bitmask:<br>- Bit `0` (`0x01`): `HAS_SHARED_DICT` (always `1`)<br>- Bit `1` (`0x02`): `HAS_LOD_HEADER` (`0` in standard region files; `.dlsh` sidecars used)<br>- Bit `2` (`0x04`): `HAS_TAIL_DELTA_RING` (`0`)<br>- Bit `3` (`0x08`): `DEFAULT_ZSTD1` (`1` if region defaults to Mode B `Zstd-1`)<br>- Bit `4` (`0x10`): `HAS_SECDED_PARITY` (`1` when `1,024 B` SEC-DED table is present) |
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
| `28` | `1 bit` | `codec1` | `(int) ((ptr64 >>> 28) & 1L)`.<br>- `0` = **Mode A**: Uncompressed `ChunkFrame` (`0-LZ` direct random access).<br>- `1` = **Mode B**: Uncompressed `ChunkFrame` header + `headerWords` SEC-DED bytes + `Zstd-1` compressed body (Section 6.2). |
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

```
Offset +0x00: uint32  segmentStateCount      // Number of BlockState entries in this segment
Offset +0x04: uint16  segmentBiomeCount      // Number of Biome entries in this segment
Offset +0x06: uint16  usedPayloadBytes       // Active entry bytes following the 8B header
Offset +0x08: StateEntry[segmentStateCount]:
                VarInt  utf8ByteLen
                byte[]  canonicalStateUtf8   // e.g. "minecraft:oak_stairs[facing=north,half=bottom,waterlogged=false]"
                uint32  materialPacked       // RGBA8 color + material flags for map LODs
              BiomeEntry[segmentBiomeCount]:
                VarInt  utf8ByteLen
                byte[]  biomeIdUtf8          // e.g. "minecraft:plains"
                uint32  foliageAndWaterTint  // Packed tint metadata
              byte[]  slackPadding           // Zero-filled slack up to (segmentCapacityBytes - 8)
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
- **`segmentFlags8` (`bits 7..0`, `uint8`):** Reserved (`0`).
- Global `BlockState` IDs (`0 .. totalRegionStates - 1`) and global `Biome` IDs (`0 .. totalRegionBiomes - 1`) are numbered sequentially in segment traversal order (`<= 64` chained segments maximum, `CBIT T5`).

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

### 6.2 Mode B (`codec1 = 1`) Header SEC-DED Syndromes & `Zstd-1` Body Decompression

When the chunk pointer has `codec1 == 1` (**Mode B**):
1. Bytes `[0 .. headerLen)` of the on-disk slab contain the **uncompressed** `ChunkFrame` header above.
2. Bytes `[headerLen .. headerLen + headerWords)` (where `headerWords = (headerLen + 7) >>> 3`, e.g. `9 bytes` when `headerLen = 72`) store one Extended Hamming `(72, 64)` SEC-DED syndrome byte per 64-bit big-endian word of the uncompressed header (the final partial word, if `headerLen % 8 != 0`, is zero-padded in the low bytes when computing its syndrome).
3. Bytes `[headerLen + headerWords .. exactByteLen20)` contain a single **standard `Zstd` (`RFC 8878`) compressed frame** representing the `ChunkFrame` body `[sectionDirEnd .. uncompressedFrameLen)`.
4. To decode a Mode B chunk: verify/heal the `headerWords` header words via `SEC-DED`, decompress the `Zstd` slice `[headerLen + headerWords .. exactByteLen20)`, and concatenate `[0 .. headerLen)` + `decompressedBody` to obtain the canonical uncompressed `ChunkFrame` of length `uncompressedFrameLen = headerLen + decompressedBody.length`.

### 6.3 `chunkStatusOrdinal` Mapping Table (`+0x14`)

| Ordinal (`uint8`) | Mojang NBT `Status` String |
| ---: | :--- |
| `0xFF` (`255`) | `"minecraft:full"` |
| `0xFE` (`254`) | *(Status tag absent; e.g. `entities/` or `poi/` chunks)* |
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

### 6.5 Slab-Local Chunk Micro-Palette (`Part A` + `Part B`)

When `hasSlabLocalPalette == 1` (`chunkFlags` bit `1`), the initial Slab-Local Micro-Palette starts immediately at `sectionDirEnd` (`24 + 2 * sectionCount`) and ends at $\min_k(\mathtt{relStart}_k)$ across all non-uniform sections $k$ (or `residualRelStart` if no non-uniform section precedes `residualRelStart`), with zero tail padding when `scaleOffsets == 0`. Incremental chunk patches (`boilerplateFlags16` bit `14` = `0x4000`, `HAS_CHUNK_PATCH_TAIL`, Section 8.3) may append additional local entries (`localStateIdx = localStateCount .. localStateCount + appendedStateCount - 1`) without shifting or re-sorting indices `0 .. localStateCount - 1`.

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

Initialize all $4,096$ voxels of the section to `palette[0]` (index `0`). Then, for each minority state $p = 1 \dots |P|-1$ in order, read a big-endian `uint16 word16`:
- Let `tag = (word16 >>> 14) & 3` and `meta14 = word16 & 0x3FFF`.

| `tag` | Container Name | Decoding Algorithm (`h` = traversal index in `0..4095`, `yzx = CURVE_TO_YZX[curveId][h]`) |
| :--- | :--- | :--- |
| `0` (`00`) | `TAG_SINGLETON` | Single voxel at `h = meta14 & 0x0FFF`. Set `voxel[CURVE_TO_YZX[curveId][h]] = p`. (`2 B` total). |
| `1` (`01`) | `TAG_DELTA_RUN` | - Let `use62Split = (meta14 & 0x2000) != 0` and `runCount = meta14 & 0x1FFF`.<br>- Set `cursor = 0`. Loop `r = 0 .. runCount - 1`:<br>  - Read `uint8 packed`.<br>  - **If `use62Split` (`6\|2` split):**<br>    `adjGap = (packed >>> 2) & 0x3F`; `lenMinus1 = packed & 0x03`.<br>    If `adjGap == 63`, add `readBoundedGap240()` (`1..2 B`).<br>    If `lenMinus1 == 3`, add `readVarInt()`.<br>  - **Else (`4\|4` split):**<br>    `adjGap = (packed >>> 4) & 0x0F`; `lenMinus1 = packed & 0x0F`.<br>    If `adjGap == 15`, add `readBoundedGap240()` (`1..2 B`).<br>    If `lenMinus1 == 15`, add `readVarInt()`.<br>  - Let `gap = (r == 0) ? adjGap : (adjGap + 1)` and `len = lenMinus1 + 1`.<br>  - Advance `cursor += gap`. For `k = 0 .. len - 1`, set `voxel[CURVE_TO_YZX[curveId][cursor++]] = p`. (`CBIT T6`: `cursor <= 4096`). |
| `2` (`10`) | `TAG_DELTA_ARRAY` | - `runCount = meta14`. Set `cursor = 0`.<br>- Loop `r = 0 .. runCount - 1`:<br>  - Read `adjGap = readBoundedGap240()` (`1..2 B`).<br>  - Let `gap = (r == 0) ? adjGap : (adjGap + 1)`.<br>  - Advance `cursor += gap`, set `voxel[CURVE_TO_YZX[curveId][cursor++]] = p`. |
| `3` (`11`) | `TAG_BITMAP` | Read `512` bytes (`4,096` bits). For `h = 0 .. 4095`, if `((buf[pos + (h >>> 3)] >>> (h & 7)) & 1) != 0`, set `voxel[CURVE_TO_YZX[curveId][h]] = p`. |

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
  - `mode == 1` (`01` - All-`1` plane): `0` bytes follow; bit `plane` is `1` for all $4,096$ voxels.
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
| `10` | `0x0400` | Remaining bytes `[pos .. residualRelEnd)` contain a Mojang binary root `TAG_Compound` (containing non-empty `block_entities`, `structures`, custom tags, or `Entities`/`Sections` for `entities/` and `poi/` archives) |
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
  1. Appended `BaseBlockEntry[M]` and `PropBinding` items extend the chunk's Slab-Local Micro-Palette from size `K` to `K + M`.
  2. Any `SectionPatch` with `hasReplacementSectionOffset` (`0x02`) updates `entries[sectionIndex]` (and `relStarts[sectionIndex]`).
  3. Inline-uniform sections (`(entry16 & 0x8000) != 0`) and non-uniform `SectionFrame`s can therefore reference appended local palette indices `K .. K + M - 1` directly.
  4. After decoding base section `i` (whether originally void `0x0000`, inline-uniform `0x8000`, or a non-uniform `SectionFrame` validated by `CBIT T6b`), any `hasVoxelDeltas`, `hasSkyLightPatch`, or `hasBlockLightPatch` entries for `sectionIndex == i` are applied on top.

---

## 9. Static Core Lookup Tables (`STATIC_CORE_*`)

To decode Slab-Local Micro-Palettes (`Part A` and `Part B`, Section 6.5), readers must use these exact 0-indexed arrays from `LcaSectionCodec.java`:

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
   - Check `fileLength >= 0x2048`, `readUint64BE(0x00) == 0x4C43415F56303031L` (`"LCA_V001"`), `readUint64BE(fileLength - 8) == 0x4C4341315F454E44L` (`"LCA1_END"`), `readUint16BE(0x08) == 1`, `readUint16BE(0x1A) == 8`, and `computeSuperblockChecksum64(buf, 0) == readUint64BE(0x38)`.
   - Read `minSectionY = readInt32BE(0x14)` and `sectionCount = readUint16BE(0x18)`.
2. **Read & Verify 64-Bit Chunk Pointer (`CBIT T3`):**
   - Compute `chunkIdx = (chunkX & 31) | ((chunkZ & 31) << 5)`.
   - Read `ptr64 = readUint64BE(0x0040 + (chunkIdx << 3))`. If `(superblock[0x0B] & 0x10) != 0`, verify/heal `ptr64` with `secDedByte = buf[secDedTableOffset + chunkIdx] & 0xFF` (Section 4.4).
   - Extract `slabOffset35 = (ptr64 >>> 29) & 0x7_FFFF_FFFFL`, `codec1 = (int) ((ptr64 >>> 28) & 1L)`, `exactByteLen20 = (int) ((ptr64 >>> 8) & 0xFFFFFL)`. If `slabOffset35 == 0` or `exactByteLen20 == 0`, the chunk is ungenerated (`null`).
3. **Locate & Unpack `ChunkFrame` (`CBIT T3b`, `T3c`):**
   - Seek to `fOff = slabOffset35 << 8`. Verify `(readInt32BE(fOff) & 0xFC00_0000) == 0x5800_0000` and `((readInt32BE(fOff) >>> 16) & 0x03FF) == chunkIdx`.
   - If `codec1 == 1` (`Mode B`), heal the `headerWords = (24 + 2 * sectionCount + 7) >>> 3` header words using the SEC-DED bytes at `fOff + headerLen` (Section 6.2), and `Zstd`-decompress `[fOff + headerLen + headerWords .. fOff + exactByteLen20)` to form the uncompressed `ChunkFrame`.
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
     - Probe whether the slab at `bytePos` is `Mode B` (`slabModeB`: Zstd frame magic `0x28, 0xB5, 0x2F, 0xFD` at `bytePos + headerLen + headerWords` or valid inline `headerWords` SEC-DED syndromes on a zero-body header). Read `dvWord = readInt32BE(bytePos)`. If `slabModeB` is `true`, evaluate Tier 1 header SEC-DED healing over a scratch copy of `[bytePos .. bytePos + headerLen)` using `[bytePos + headerLen .. bytePos + headerLen + headerWords)`.
     - If `(dvWord & 0xFC00_0000) == 0x5800_0000`, extract `slabChunkIdx = (dvWord >>> 16) & 0x03FF`.
     - If `needsSlabScan[slabChunkIdx]` is `true`, commit any healed `Mode B` header words and compute the frame's byte length `exactLen` using `slabModeB`:
       - **In Mode A (`slabModeB == false`, `codec1 == 0`):** Read `chunkFlags = buf[bytePos + 0x15] & 0xFF`, `unitShift = ((chunkFlags & 4) != 0) ? 2 : 0`, and `residualRel = readUint16BE(bytePos + 0x16)`. Let `residualStart = bytePos + sectionDirEnd + (residualRel << unitShift)`. If `(chunkFlags & 1) == 0`, `exactLen = sectionDirEnd + (residualRel << unitShift)`. Otherwise read `bpFlags = readUint16BE(residualStart)` and advance past each active `boilerplateFlags16` field (`bit 13` `VarInt mbnlLen` + `mbnlStream`, `bit 5..9` fields, `bit 14` (`0x4000`) `VarInt patchTailByteLen` + `chunkPatchTail`, and `bit 10` residual NBT or any appended post-residual `SectionFrame`s up to the next `0x5800_0000` slab header or `"LCA1_END"` footer).
       - **In Mode B (`slabModeB == true`, `codec1 == 1`):** At `compStart = bytePos + sectionDirEnd + headerWords`, verify the 4-byte `Zstd` magic `0x28, 0xB5, 0x2F, 0xFD`, skip the `Zstd` frame header descriptor (`FHD`), and walk the 3-byte `Zstd` block headers (`lastBlock = blockHeader & 1`, `blockType = (blockHeader >>> 1) & 3`, `blockSize = blockHeader >>> 3`) until `lastBlock == 1` (plus 4-byte checksum if `FHD` bit `2` is set) to obtain `exactLen = zstdEnd - bytePos`.
     - Reconstruct `ptr64 = ((long) slab << 29) | ((slabModeB ? 1L : 0L) << 28) | ((exactLen & 0xFFFFFL) << 8) | 1L`, write `ptr64` to `0x0040 + (slabChunkIdx << 3)`, and write `computeHammingSecDed8(ptr64)` to `secDedTableByteOffset + slabChunkIdx`.
3. **Tier 3 - Section-Granular `octreeOccupancyMask64` Quarantine (`CBIT T6`, `T6b`):**
   - If decoding a non-uniform `SectionFrame` fails `cursor <= 4096` (`CBIT T6`) or `decodedOctreeMask64 == octreeOccupancyMask64` (`CBIT T6b`), quarantine only that $16 \times 16 \times 16$ section and fill its $4,096$ voxels with its dominant block state `palette[0]` (`dictIds[0]` from Section 7 item 3) while decoding all other vertical sections of the chunk normally.
4. **Tier 4 - Copy-on-Write (`CoW`) Prior-Generation Pointer Rollback:**
   - In live writer sessions (`LcaLiveArchive`), if an active chunk pointer fails validation after an interrupted write, restore `prevChunkPointers[chunkIdx]` from the 4-epoch CoW grace ring.
