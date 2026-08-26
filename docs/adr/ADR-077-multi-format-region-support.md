# ADR-077 - Multi-Format Region Support: Linear (ZSTD) and Pluggable Region Readers

**Status:** Accepted
**Date:** 2026-08-26
**Extends:** [ADR-016](ADR-016-anvil-subsystem.md) (Anvil Read-Only Subsystem)
**Related:** [ADR-028](ADR-028-l3-backlog-cache.md) (L3 Backlog Cache), [ADR-067](ADR-067-adaptive-scan-rate-and-mca-header-generation-check.md) (Adaptive Scan Rate and Header Generation Check)

## Context

High-performance server forks (such as Leaves and Gale) and modded environments (Fabric/NeoForge running the Linear Region Format mod) frequently utilize the **Linear region format** (`.linear`). Linear replaces Mojang's 4 KiB sector-aligned Anvil (`.mca`) allocation scheme with continuous ZStandard (`zstd`) compressed streams to eliminate sector quantization padding, reduce world disk space by 30-60%, and speed up sequential I/O.

Under ADR-016, RTP's off-tick region pre-filter subsystem (`api/anvil-api`) hardcodes file resolution to `r.X.Z.mca` and expects an 8 KiB Anvil sector header table. On servers with `.linear` region storage:
1. `AnvilPrefilter` reports `UNKNOWN:no-region-file(r.X.Z.mca)` because the files on disk are named `r.X.Z.linear`.
2. Even if renamed or targeted, standard Anvil sector calculations fail, producing corrupted sector offsets.
3. As a result, all location probes return `Verdict.UNKNOWN` and fall through to live chunk loading. While S-004 safe, this disables off-tick pre-filtering and pre-scan optimizations on Linear-backed worlds.

## Decision

1. **Pluggable Region Reader SPI**: Generalize `api/anvil-api` to parse multiple on-disk region formats behind a unified `RegionFileReader` SPI:
   ```java
   public interface RegionFileReader {
       byte[] readChunkNbt(byte[] regionBytes, int rx, int rz) throws IOException;
       boolean isChunkGenerated(byte[] regionBytes, int rx, int rz);
   }
   ```
2. **Format Resolution and Auto-Detection**:
   - `RegionFileResolver` shall probe for `r.X.Z.linear` first, falling back to `r.X.Z.mca`.
   - File format verification shall validate magic header bytes (`0xC370ACDE22013702` for Linear v1/v2 vs. Anvil sector structures).
3. **Linear Decoder Implementation (`LinearRegionReader`)**:
   - Integrate `com.github.luben:zstd-jni` into `anvil-api` to decompress Linear chunk frames.
   - Read Linear v1/v2 headers, parse chunk entry index tables, and decompress the requested chunk's NBT payload.
   - Reuse existing zero-dependency `Nbt.readRootCompound` to construct standard `AnvilChunkView` objects.
4. **Safety and Fallback Guarantees (S-004 and S-005)**:
   - All decompression and file reads shall remain asynchronous on `ForkJoinPool.commonPool()`.
   - If native `zstd-jni` libraries fail to link (e.g. strict security sandboxes), or if a region file is malformed, the probe shall catch the error, emit diagnostic logging, and return `Verdict.UNKNOWN` to safely fall through to runtime chunk loading.
5. **Cache Compatibility**:
   - `AnvilRegionByteCache` shall be renamed/aliased to `RegionByteCache` to pool raw byte buffers for both `.mca` and `.linear` files.

## Alternatives Considered

| Alternative | Why Rejected |
| :--- | :--- |
| Option A: Require operators to use `.mca` | Breaks compatibility with Leaves/Gale servers and forced conversion negates disk-saving benefits for server operators. |
| Option B: Disable pre-filter on `.linear` worlds | Causes 100% fallback to live chunk loads, increasing server tick pressure and losing pre-scan acceleration. |
| Option C: Inline Linear parsing into `AnvilReader` | Violates single-responsibility principle; mixing Anvil 4 KiB sector arithmetic with ZSTD stream decoding creates tight coupling and testing complexity. |
| Option D: Pluggable SPI with dedicated `LinearRegionReader` (Selected) | Clean separation of concerns, enables unit testing with mock format fixtures, and allows future additions (e.g. Slime Region Format) without breaking Anvil code. |

## Consequences

- **Positive:**
  - Leaves, Gale, and modded servers using `.linear` retain full off-tick biome and safety pre-filtering.
  - `/rtp scan` and L3 Backlog Cache (ADR-028) can inspect `.linear` files without triggering server chunk loads.
  - Clean modular SPI architecture for future region formats.
- **Negative / Trade-offs:**
  - Adds `zstd-jni` dependency (~1-5 MB native binary jar bundle) to `anvil-api`.
  - Linear frame decompression may allocate slightly larger temporary byte buffers during initial decode compared to individual 4 KiB sector slices (buffered and bounded by `RegionByteCache`).

## References

- [ADR-016](ADR-016-anvil-subsystem.md) - Anvil Read-Only Subsystem
- [ADR-067](ADR-067-adaptive-scan-rate-and-mca-header-generation-check.md) - Adaptive Scan Rate and Generation Checks
- REQ-RTP-S-004 (No silently discarded failures)
- REQ-RTP-S-005 (No chunk loading on main thread)
