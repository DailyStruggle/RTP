# anvil-api

Platform-neutral, off-tick Minecraft region-file decoder and read-only surface/biome pre-filter (`io.github.dailystruggle.anvil`). Pure Java 17 layered only on `:bit-api` (no Bukkit, Paper, Folia, Fabric, NeoForge, or `rtp-core` dependency), allowing any standalone world tool, structure generator, or claim scanner (e.g. `TreeGen`, `WorldGuardLite`) to inspect unloaded chunks from disk without triggering main-thread chunk loads (S-005).

## Coordinates

- **Maven Central:** `io.github.dailystruggle:anvil-api:<version>`
- **JitPack:** `com.github.DailyStruggle.RTP:anvil-api:<tag>`

```gradle
dependencies {
    implementation 'io.github.dailystruggle:anvil-api:3.3.1'
    // or via JitPack:
    // implementation 'com.github.DailyStruggle.RTP:anvil-api:3.3.1'
}
```

## Surface

- `AnvilPrefilter` - off-tick region-file probe entry point. Provides `probeDetailed(...)`, `probeSync(...)`, `probeSyncDetailed(...)`, batched `probeBatchSyncDetailed(...)`, `openSession(...)` (`RegionProbeSession`), and single-column `probeColumn(...)` / `probeColumnAsync(...)` / `squareJobAsync(...)` coalesced on `AnvilIoPool`.
- `RegionFileReader` - format-neutral SPI (`readChunk(byte[], int, int)`, `isChunkGenerated(byte[], int, int)`) that unpacks raw region bytes into an `AnvilReader.ChunkEntry`. Built-in `AnvilReader.INSTANCE` supports compression modes 1 (GZIP), 2 (Zlib/Deflate), 3 (uncompressed), 4 (in-house `Lz4BlockDecoder`), and 5 (`DynamicZstdBinding`).
- `RegionFormatRegistry` & `RegionFormatDetector` - thread-safe registry mapping file extensions (`.mca` and `.lca` built in; `.linear` registered dynamically via `LinearRegionReader.registerIfAvailable()`, and custom formats via `register(extension, reader)` or `ServiceLoader<RegionFileReaderProvider>`) to their `RegionFileReader`, paired with cached directory format profiling (`WorldFormatProfile`: `MCA`, `LINEAR`, `LCA`, `EMPTY`).
- `LcaRegionReader` / `LcaRegionWriter` / `LcaRegionFileStorage` - `.lca` (LeafCraft Anvil `v2` `0x0002`, `anvil-api-ADR-003`) reader, encoder, `.mca`/`.linear`/`.b_linear` and `.lca` `v1` -> `v2` transcoder, pure-Java Continuous Atomic Layer 2 Region Dictionary (`LCD2`, `0x4C44`) Mode B codec, header-only (`10,304 B` / `~10.1 KiB` on-disk header prefix, `~25.6 KiB` total handle residency) `256-byte` Copy-on-Write (`CoW`) sub-slab allocator (`LcaLiveArchive`), 4-tier Extended Hamming `(72, 64)` SEC-DED bit-rot healer (`healRegionBitRot`, including Layer 2 dictionary segment and uncompressed `ChunkFrame` header SEC-DED healing in `Mode B`), and multi-folder (`region/`, `entities/`, `poi/`) world storage session manager (`LcaWorldStorageSession`).
- `ColumnProbe` - low-allocation center-column (`lx=8, lz=8`) and section view over a bounded `[minY, maxY]` window (`blockAt(worldY)`, `blockAt(localX, localZ, worldY)`, `biomeAt(worldY)`, `heightmapTopY()`, `hasHeightmap()`), skipping entities, block entities, and light arrays during NBT decode.
- `ChunkCoord` - value record `(int x, int z)` with `regionX()`, `regionZ()`, and packed 64-bit `regionKey()` for grouping chunk probes by region file.
- `Nbt` - zero-dependency, depth- and allocation-bounded (`MAX_DEPTH = 512`, `MAX_ARRAY_LENGTH = 16M`) binary NBT reader/writer (`readNamedRoot`, `readRootCompound`, `readRootCompoundSelective`, `NbtList`, `NamedTag`).

## Usage

```java
import io.github.dailystruggle.anvil.AnvilChunkView;
import io.github.dailystruggle.anvil.AnvilPrefilter;
import io.github.dailystruggle.anvil.ChunkCoord;
import io.github.dailystruggle.anvil.ColumnProbe;
import io.github.dailystruggle.anvil.Verdict;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Standalone plugin (e.g. TreeGen) scanning unloaded chunks off-thread before placement:
Path worldFolder = Path.of("world");
Set<String> unsafeBlocks = Set.of("LAVA", "WATER", "MAGMA_BLOCK");

// 1. Coalesced async center-column probe over Y = [60, 160]
AnvilPrefilter.probeColumnAsync(worldFolder, "", 12, -8, 60, 160)
        .thenAccept(probe -> {
            if (probe != null && probe.hasHeightmap()) {
                int surfaceY = probe.heightmapTopY() - 1;
                String groundId = probe.blockAt(surfaceY);
                String biomeId = probe.biomeAt(surfaceY + 1);
            }
        });

// 2. Batched detailed probe sharing a single channel open per r.X.Z.mca file
List<ChunkCoord> batch = List.of(new ChunkCoord(12, -8), new ChunkCoord(13, -8));
Map<ChunkCoord, AnvilPrefilter.ProbeResult> results = AnvilPrefilter.probeBatchSyncDetailed(
        worldFolder, "", batch, unsafeBlocks, AnvilPrefilter.DEFAULT_RECONCILER);

AnvilPrefilter.ProbeResult res = results.get(new ChunkCoord(12, -8));
if (res != null && res.verdict() == Verdict.ACCEPT) {
    AnvilChunkView view = res.view();
    Set<String> biomes = view.getBiomesPresent();
}
```

## Build

```
./gradlew :anvil-api:build
```

## Docs

- `.lca` Binary Format Interface Control Document (`LCA-ICD-1.0`): [`docs/ICD.md`](docs/ICD.md)
- ADR-016: [`docs/adr/anvil-api-ADR-001-anvil-subsystem.md`](docs/adr/anvil-api-ADR-001-anvil-subsystem.md)
- ADR-077: [`docs/adr/anvil-api-ADR-002-multi-format-region-support.md`](docs/adr/anvil-api-ADR-002-multi-format-region-support.md)
- anvil-api-ADR-003: [`docs/adr/anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md`](docs/adr/anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md)
