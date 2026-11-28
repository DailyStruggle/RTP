# anvil-api-ADR-007: Zero-Copy Off-Heap Mode A I/O, Vectored Gather Writes, and Prioritized Dynamic Dictionaries (ICD v3)

- **Status:** Accepted
- **Date:** 2026-11-27
- **Applies To:** `:anvil-api` (`io.github.dailystruggle.anvil`, release `1.0.1`+, ICD `0x0003` / v3)
- **Governed By / Related:** [`anvil-api-ADR-003`](anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md), [`anvil-api-ADR-004`](anvil-api-ADR-004-overlaid-eikonal-light-columnar-residuals-and-adaptive-rank-exclusion.md), [`anvil-api-ADR-006`](anvil-api-ADR-006-factored-layer2-pure-id-palettes-and-local-gray-bitplanes.md), [`ADR-115`](../../../docs/adr/ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md)
- **Requirements:** `REQ-RTP-S-002`, `REQ-RTP-S-004`, `REQ-RTP-S-005`

---

## Context

In `.lca` Format Version `0x0003` (ICD v3, `anvil-api-ADR-003` / `anvil-api-ADR-006`), uncompressed Mode A (`codec1 == 0`) is the primary active disk representation for real-time Minecraft servers (100% of active terrain chunks on GoldLeaf live benchmarks; zero Zstd decompression occurs on Mode A). Because Mode A section payloads (Delta-Roaring and FlatBitPack) use the exact same binary encoding on disk, in active off-heap memory, and on the wire, moving Mode A sections between disk and memory should require **zero intermediate `byte[]` copies** for clean sections.

Previously, `:anvil-api` violated this invariant in two ways:
1. **Read path (`Disk -> Memory`):** `LcaRegionFileStorage#getOrPromoteReadAheadRegionBytes` read an entire `.lca` file into a heap `byte[fileSize]`, `LcaRegionReader#readDeferredChunkSlabFromBytes` copied every chunk via `Arrays.copyOfRange`, and `LcaModeABlockSection#sliceValidatedPayload` copied every non-uniform section into a third heap `byte[]`. Callers then re-scanned section offsets and re-resolved dictionary palettes a second time.
2. **Write path (`Memory -> Disk`):** `LcaRegionFileStorage#writeFreshChunkDirectModeA` only accepted materialized heap `LcaModeABlockSection` instances (forcing off-heap GPU/disk sections to copy back into heap `byte[]`), resolved palettes via `String[][]` lookups inside `synchronized (regionLock(key))`, copied every chunk into `frameBuf` -> `pendingPayloads` -> `batchBuf`, and relied on JDK heap-to-direct bounce buffers during `FileChannel.write`.

This ADR establishes permanent architectural invariants for symmetric zero-copy off-heap Mode A I/O in `:anvil-api` without altering the ICD v3 (`0x0003`) binary specification.

---

## Decision

### 1. Straight-to-Memory Off-Heap Bind Path (`Disk -> Active DirectByteBuffer`, Zero Decode)
- **Native Format Thesis:** `.lca` Mode A (Delta-Roaring and FlatBitPack) is the **native active in-memory, GPU PCIe, and disk format** — clean Mode A block payloads are **never decoded or unpacked** to vanilla arrays on reload, and clean sections are **never re-encoded** on save (only sparse dirty overlays compact on save).
- **Direct Read-Ahead Slabs (Read-Only & Open Write Archives):** `LcaRegionFileStorage` shall load read-only `.lca` regions (up to `readAheadMaxBytes`, default `32 MiB`) into a single direct off-heap `ByteBuffer` (`ByteBuffer.allocateDirect`) via one `FileChannel.read(directBuf, 0)` pass (avoiding JDK heap-to-direct bounce copies and avoiding `MappedByteBuffer` Windows unmap-lock and `SIGBUS` truncation hazards). Open write-cached `LcaLiveArchive` instances shall likewise serve `readDirectOffHeapChunkView(...)` from a CoW-safe `DirectReadAheadSnapshot` (invalidated on flush/compaction, with hazard-pin + compaction-epoch retry) rather than falling back to heap `readDeferredChunkSlab` after writes. Oversized regions fail closed to positioned direct chunk reads.
- **Inline CPU-Cache-Resident ECC & Single-Pass Bind:**
  - `LcaRegionReader.CachedFileHeader#pointer` enforces inline Extended Hamming `(72, 64)` SEC-DED pointer verification and 1-bit healing directly over the `9 KiB` L1-resident pointer directory + syndrome table.
  - `LcaRegionReader.DirectOffHeapChunkView#bind()` binds chunk headers, section directory offsets, micro-palettes, and zero-copy `LcaModeABlockSection.OffHeapModeASlice` views directly over the off-heap `DirectByteBuffer` via `LcaSectionCodec#bindChunkMetadataDirectFromBuffer` in **one sequential L1-cache-resident pass** with **zero full-chunk heap `byte[]` staging copy** (`DirectOffHeapChunkView.totalHeapChunkStagingCopies() == 0`).
  - `LcaModeABlockSection#sliceOffHeapFromBufferOrThrow` / `LcaModeABlockSection#trySliceOffHeapFromBuffer` enforces inline `CBIT T5` (VarInt/bounds), `CBIT T6` (container cursor bounds), and `CBIT T6b` (`octreeOccupancyMask64` syndrome verification in a single `long` register using `2.5 KiB` L1-resident O(octants) LUTs across all 4 curves and per-tag container validators) while accumulating exact single-pass `int[] paletteVoxelCounts` (`<= 1 KiB`, L1-resident) so downstream `LevelChunkSection` construction initializes all counters in O(paletteSize) without re-scanning voxels.
  - v3 columnar `block_ticks`, `fluid_ticks`, `sparsePostProcessing`, `EXT_FLAG_DICT_STRUCTURES`, and `EXT_FLAG_COLUMNAR_BLOCK_ENTITIES` bind natively onto `LcaDecodedChunkMeta` (`ColumnarTicks`, `short[][]`, `decodeDictStructures(DirectNbtBuilder)`, `decodeColumnarBlockEntities(DirectNbtBuilder)`) without synthesizing vanilla NBT bytes or invoking `FastNBTParser`.
  - `LcaSectionCodec#bindChunkMetadataDirectFromBuffer` is decomposed into small C2-inlinable helpers (`parseDirectTailIntoArena`, `decodeDirectHeightmapsFromTail`, `buildDirectInlineSectionMeta`, `decodeDirectLightChannelFromBuffer`) using zero-allocation `ScratchArena` primitive fields (`63,023 ch/s` on 12 threads / `0.055 ms/ch` warmed across `8,222` live world chunks).
- **Mode B / Legacy Fail-Closed:** Mode B (`codec1 == 1`), v3 escape sections, or uncorrectable `CBIT T5/T6/T6b` faults fail closed cleanly (`null` / `CorruptRegionEntryException`) to the safe recovery path (`S-004`).

### 2. Zero-Copy Ordered Gather Write Path (`Active Off-Heap Memory -> Disk`)
- **Clean Section Pass-Through (`DirectModeASectionRef`):** `LcaModeABlockSection.DirectModeASectionRef` carries `(curveId, blockMode, flatBits, octreeMask64, ByteBuffer payloadBuf, int payloadOffset, int payloadLength, int paletteLength)` for clean off-heap Mode A sections (`dirtyCount == 0`).
- **Dirty-Cache Compaction Boundary:** Runtime block mutations land in a caller's sparse dirty overlay. Only sections with `dirtyCount > 0` undergo compaction/re-encoding into Mode A **before** entering the archive lock; clean sections never materialize to heap `byte[]`.
- **Vectored Ordered Gather Write (`FileChannel.write(ByteBuffer[])`):**
  - `LcaRegionFileStorage#writeFreshChunkDirectOffHeapModeA` / `LcaLiveArchive#saveFreshChunkDirectOffHeapModeA` assemble tiny framing headers (chunk header, micro-palette, per-section headers/palettes/biome/light, residual tail, 256 B slab padding) into compact header buffers and interleave **zero-copy `ByteBuffer.slice(off, len)` references** to clean active off-heap Mode A payloads in exact ICD v3 wire order.
  - `LcaLiveArchive#flushPendingWrites` writes batched gather chunks directly via `channel.position(batchStartOffset)` + looped `channel.write(ByteBuffer[] srcs, int offset, int len)` inside `synchronized (this)` (safe alongside concurrent lock-free positioned `FileChannel.read(bb, pos)` readers, which neither read nor mutate channel position).
- **Mandatory Pin Release (`S-002`):** Every `Runnable onFlushComplete` slab-pin release callback registered with a pending off-heap gather chunk shall execute in a `finally` block on flush completion, chunk coalescing overwrite, archive `close()`, or I/O error — never leaking GPU or arena slab leases.

### 3. Prioritized In-Memory Dictionaries & Dynamically Sized On-Disk Dictionary Segments
- **Prioritized Pre-Resolved Integer IDs:** `LcaRegionFileStorage#bindPrioritizedDictionary(String[] blockStates, String[] biomes)` seeds dense priority-ordered global dictionary IDs (`boundWorldgenDictIds` and `boundBiomeDictIds`) so callers pass `int[][]` pre-resolved IDs directly with O(1) array lookup — eliminating per-chunk `String[]` allocations and `HashMap` lookups inside `regionLock`.
- **Dynamic On-Disk Growth & Crash-Safe Write Ordering:** Novel block states or biomes not present in the prioritized seed continue to append dynamically to `.lca` ICD v3 `dictSegments` on disk. Write ordering is strictly enforced:
  1. Append and flush dirty `dictSegments` (if any) + chunk data slabs + footer canary.
  2. Atomically publish 64-bit pointer directory entries and SEC-DED syndromes after `VarHandle.releaseFence()`.

### 4. ICD Version & Tier 1 Boundary Preservation
- All changes remain **100% binary compatible with ICD v3 (`0x0003`)** (`LCA_V001`); no format version bump is introduced.
- `:anvil-api` retains strict `ADR-115` Tier 1 purity (zero `net.minecraft.*`, `org.bukkit.*`, `io.papermc.*`, or RTP imports; enforced by `AnvilPackageBoundaryArchTest`).

---

## Anti-Regression Invariants (Mechanically Enforced)

Any change to `:anvil-api` read/write paths **shall not**:
1. Re-introduce `Arrays.copyOfRange` or heap `byte[]` copying of Mode A chunk/section payloads on `readDirectOffHeapChunkView` or `OffHeapModeASlice`.
2. Re-introduce heap `byte[]` materialization of clean `DirectModeASectionRef` payloads on `writeFreshChunkDirectOffHeapModeA` / `flushPendingWrites`.
3. Drop or skip `onFlushComplete` slab-pin release callbacks on error, coalesce, or close (`S-002`).
4. Commit pointer-directory updates before flushing dirty `dictSegments` and data slabs.

Enforced by `LcaZeroCopyOffHeapIoTest` in `:anvil-api` and `ZeroCopyGpuDmaActivationTest` in GoldLeaf.
