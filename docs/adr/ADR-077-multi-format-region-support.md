# ADR-077 - Multi-Format Region Support: Linear (ZSTD) and Pluggable Region Readers

**Status:** Accepted (revised 2026-09-23 - see Revision)
**Date:** 2026-08-26
**Extends:** [ADR-016](ADR-016-anvil-subsystem.md) (Anvil Read-Only Subsystem)
**Related:** [ADR-028](ADR-028-l3-backlog-cache.md) (L3 Backlog Cache), [ADR-067](ADR-067-adaptive-scan-rate-and-mca-header-generation-check.md) (Adaptive Scan Rate and Header Generation Check)

## Revision (2026-09-23) - Linear folded into `anvil-api` via a pure-Java decoder

The original decision (Option E) packaged the Linear decoder as a standalone
`LeafRTPLinearAddon` specifically to keep the native `com.github.luben:zstd-jni`
library (~6.3 MiB of multi-platform native binaries) out of the core jar. That
size concern no longer applies: RTP only ever *decompresses* Linear frames
(read-only, off-tick), so the native library was replaced with the pure-Java
`io.airlift:aircompressor:2.0.3` ZStandard decoder (Java-8/21-compatible
maintenance line, no native binaries). The shaded decoder footprint dropped from
~6.3 MiB to ~0.26 MiB.

Given that footprint, `LinearRegionReader` was moved into `api/anvil-api`
(package `io.github.dailystruggle.rtp.anvil`) and `.linear` is now registered by
default in `RegionFormatRegistry` alongside `.mca`. The standalone
`addons/LeafRTPLinearAddon` module was retired. `.linear` off-tick pre-filtering
is therefore built in for both the Lite and Pro editions with no operator action
and no native-binary bloat. `zstd-jni` is retained only as a `testImplementation`
in `anvil-api` to author synthetic `.linear` fixtures (cross-validated against the
pure-Java decode path). The pluggable `RegionFileReader` / `RegionFileReaderProvider`
SPI is unchanged and still available for third-party formats. This supersedes
Option E below and the addon-specific wording in the Decision/Consequences.

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
3. **Linear Decoder Implementation** (revised - see Revision 2026-09-23):
   - Built into `api/anvil-api` (`LinearRegionReader`), using the pure-Java `io.airlift:aircompressor` ZStandard decoder to decompress Linear chunk frames with zero native binaries.
   - Registered for `.linear` by default in `RegionFormatRegistry` (the `RegionFileReaderProvider` SPI remains available for third-party formats).
   - Reads Linear v1/v2 headers, parses chunk entry index tables, and decompresses the requested chunk's NBT payload.
   - Reuses existing zero-dependency `Nbt.readRootCompound` to construct standard `AnvilChunkView` objects.
4. **Safety and Fallback Guarantees (S-004 and S-005)**:
   - All decompression and file reads shall remain asynchronous on `ForkJoinPool.commonPool()`.
   - If the ZStandard decoder classes are unavailable, or if a region file is malformed, the probe shall catch the error, emit diagnostic logging, and return `Verdict.UNKNOWN` to safely fall through to runtime chunk loading. (With the pure-Java decoder there is no native linkage to fail, but the guard is retained defensively.)
5. **Cache Compatibility**:
   - `AnvilRegionByteCache` shall be renamed/aliased to `RegionByteCache` to pool raw byte buffers for both `.mca` and `.linear` files.

## Alternatives Considered

| Alternative | Why Rejected |
| :--- | :--- |
| Option A: Require operators to use `.mca` | Breaks compatibility with Leaves/Gale servers and forced conversion negates disk-saving benefits for server operators. |
| Option B: Disable pre-filter on `.linear` worlds | Causes 100% fallback to live chunk loads, increasing server tick pressure and losing pre-scan acceleration. |
| Option C: Inline Linear parsing into `AnvilReader` | Violates single-responsibility principle; mixing Anvil 4 KiB sector arithmetic with ZSTD stream decoding creates tight coupling and testing complexity. |
| Option D: Bundle zstd-jni into anvil-api directly | Bundles multi-platform *native* binaries into the core jar, increasing overall jar size by ~6.3 MiB for all users even though 98%+ use standard .mca Anvil. |
| Option E: Pluggable SPI with dedicated `LeafRTPLinearAddon` (originally selected; superseded 2026-09-23) | Kept the native zstd-jni out of core, but required operators on `.linear` worlds to install a separate addon jar. Superseded once the native dependency was replaced by the tiny pure-Java aircompressor decoder (see Revision). |
| Option F: Build Linear into `anvil-api` using a pure-Java ZStandard decoder (Selected, 2026-09-23) | RTP only decompresses (read-only, off-tick), so a pure-Java decoder (`io.airlift:aircompressor`, ~0.26 MiB shaded, no native binaries) delivers built-in `.linear` support for both editions with negligible size cost and no per-platform native packaging or linkage-failure risk. |

## Consequences

- **Positive:**
  - Leaves, Gale, and modded servers using `.linear` retain full off-tick biome and safety pre-filtering out of the box, in both the Lite and Pro editions, with no operator action.
  - Core plugin JAR remains lightweight (the pure-Java decoder adds ~0.26 MiB) with zero native binary bloat for any server.
  - `/rtp scan` and the Backlog cache (ADR-028) can inspect `.linear` files without triggering server chunk loads.
  - Clean modular SPI architecture (`RegionFormatRegistry` / `RegionFileReaderProvider`) remains available for future region formats.
- **Negative / Trade-offs:**
  - The pure-Java ZStandard decoder is somewhat slower than the native zstd-jni path, but decode runs off-tick on `ForkJoinPool.commonPool()` for a prefilter, so it is not tick-critical.
  - Linear frame decompression may allocate slightly larger temporary byte buffers during initial decode compared to individual 4 KiB sector slices (buffered and bounded by `RegionByteCache`).

## References

- [ADR-016](ADR-016-anvil-subsystem.md) - Anvil Read-Only Subsystem
- [ADR-067](ADR-067-adaptive-scan-rate-and-mca-header-generation-check.md) - Adaptive Scan Rate and Generation Checks
- REQ-RTP-S-004 (No silently discarded failures)
- REQ-RTP-S-005 (No chunk loading on main thread)
