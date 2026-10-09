package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.anvil.AnvilReader;
import io.github.dailystruggle.rtp.anvil.AnvilRegionByteCache;
import io.github.dailystruggle.rtp.anvil.ColumnProbe;
import io.github.dailystruggle.rtp.anvil.RegionFileReader;
import io.github.dailystruggle.rtp.anvil.RegionFileResolver;
import io.github.dailystruggle.rtp.anvil.RegionFormatRegistry;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Off-tick extractor for chunk-resolution pregenerated land and biome mapping (ADR-104 §4.3, ADR-084).
 *
 * <p>Rule S-005 compliant: Zero chunk loads on the main thread. Scans region files ({@code .mca}, or
 * any format with a reader registered in {@link RegionFormatRegistry}) within the bounding box of a
 * region and samples chunk existence and representative surface biomes.</p>
 */
public final class PregenBiomeExtractor {

    public record ChunkBiomeSample(int cx, int cz, int biomeIndex) {}

    public record ExtractionResult(
            List<String> palette,
            List<ChunkBiomeSample> chunks,
            int totalGeneratedChunks,
            int regionFilesScanned
    ) {}

    /** Receives one generated chunk sample; returning {@code false} stops the current file early. */
    @FunctionalInterface
    interface ChunkSink {
        boolean accept(int cx, int cz, String biome);
    }

    /** Chunks added around the sampled shape extent so the land backdrop frames the boundary. */
    static final int BOUNDS_MARGIN_CHUNKS = 8;

    private PregenBiomeExtractor() {}

    /** World name of a region: live world, then configured name, then {@code world}. */
    public static String resolveWorldName(Region region) {
        String worldName = null;
        if (region != null) {
            try {
                RTPWorld<?> rtpWorld = region.getWorld();
                if (rtpWorld != null) {
                    worldName = rtpWorld.name();
                }
            } catch (Exception ignored) {}
            if ((worldName == null || worldName.isEmpty())
                    && region.configuredWorldName != null && !region.configuredWorldName.isEmpty()) {
                worldName = region.configuredWorldName;
            }
        }
        return (worldName == null || worldName.isEmpty()) ? "world" : worldName;
    }

    /**
     * Absolute chunk bounds {@code [minCx, minCz, maxCx, maxCz]} of a shape, found by sampling its
     * 1D curve. Shapes address chunk coordinates with their configured centre already applied, so
     * this tracks off-origin regions without per-shape parameter knowledge. {@code null} when empty.
     */
    static int[] shapeChunkBounds(MemoryShape<?> shape, int samples) {
        long range = shape.getRange();
        if (range <= 0) return null;
        long step = Math.max(1L, range / Math.max(1, samples));
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long loc = 0; loc < range; loc += step) {
            int[] xz = shape.locationToXZ(loc);
            if (xz == null || xz.length < 2) continue;
            minX = Math.min(minX, xz[0]);
            maxX = Math.max(maxX, xz[0]);
            minZ = Math.min(minZ, xz[1]);
            maxZ = Math.max(maxZ, xz[1]);
        }
        int[] last = shape.locationToXZ(range - 1);
        if (last != null && last.length >= 2) {
            minX = Math.min(minX, last[0]);
            maxX = Math.max(maxX, last[0]);
            minZ = Math.min(minZ, last[1]);
            maxZ = Math.max(maxZ, last[1]);
        }
        return (minX > maxX) ? null : new int[]{minX, minZ, maxX, maxZ};
    }

    /**
     * Resolves the world region directory for a region or world name.
     */
    public static Path resolveRegionFolder(Region region) {
        if (region == null) return null;
        String worldName = resolveWorldName(region);

        // 1. Try RTPWorld if serverAccessor is active
        if (RTP.serverAccessor != null) {
            try {
                RTPWorld<?> rtpWorld = RTP.serverAccessor.getRTPWorld(worldName);
                if (rtpWorld != null) {
                    Object nativeWorld = rtpWorld.world();
                    if (nativeWorld != null) {
                        try {
                            // Bukkit / Paper: World#getWorldFolder()
                            java.lang.reflect.Method m = nativeWorld.getClass().getMethod("getWorldFolder");
                            Object file = m.invoke(nativeWorld);
                            if (file instanceof java.io.File f) {
                                Path dir = RegionFileResolver.regionDirectoryFor(f.toPath(), "");
                                if (Files.isDirectory(dir)) return dir;
                            }
                        } catch (ReflectiveOperationException ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. Search configured world directory (e.g. ./<worldName>/region, ./<worldName>/dimensions/minecraft/overworld/region)
        Path worldDir = Path.of(worldName);
        Path found = findRegionDirUnderWorld(worldDir);
        if (found != null) return found;

        Path dotWorldDir = Path.of(".", worldName);
        found = findRegionDirUnderWorld(dotWorldDir);
        if (found != null) return found;

        return null;
    }

    private static Path findRegionDirUnderWorld(Path worldRoot) {
        if (!Files.isDirectory(worldRoot)) return null;

        List<Path> candidates = List.of(
                worldRoot.resolve("region"),
                worldRoot.resolve("dimensions/minecraft/overworld/region")
        );
        for (Path c : candidates) {
            if (Files.isDirectory(c)) return c;
        }

        return null;
    }

    /**
     * Extracts chunk generation and surface biome samples for chunks inside or bordering the given region bounds.
     *
     * @param region target region
     * @param maxChunks maximum number of chunks to return (to bound network payload)
     * @return extraction result with palette and chunk list
     */
    public static ExtractionResult extract(Region region, int maxChunks) {
        if (region == null) {
            return new ExtractionResult(List.of("minecraft:unknown"), List.of(), 0, 0);
        }

        int minCx = -128;
        int maxCx = 128;
        int minCz = -128;
        int maxCz = 128;

        if (region.shape instanceof MemoryShape<?> memoryShape) {
            int[] bounds = shapeChunkBounds(memoryShape, 4096);
            if (bounds != null) {
                minCx = bounds[0] - BOUNDS_MARGIN_CHUNKS;
                minCz = bounds[1] - BOUNDS_MARGIN_CHUNKS;
                maxCx = bounds[2] + BOUNDS_MARGIN_CHUNKS;
                maxCz = bounds[3] + BOUNDS_MARGIN_CHUNKS;
            }
        }

        Path regionDir = resolveRegionFolder(region);
        if (regionDir == null || !Files.isDirectory(regionDir)) {
            // Fallback: if region directory is unavailable, extract biomes from MemoryShape if present
            return extractFromMemoryShape(region, minCx, minCz, maxCx, maxCz, maxChunks);
        }

        return extractFromDisk(regionDir, minCx, minCz, maxCx, maxCz, maxChunks, region);
    }

    private static ExtractionResult extractFromDisk(
            Path regionDir, int minCx, int minCz, int maxCx, int maxCz, int maxChunks, Region region) {

        int minRx = minCx >> 5;
        int maxRx = maxCx >> 5;
        int minRz = minCz >> 5;
        int maxRz = maxCz >> 5;

        Map<String, Integer> paletteMap = new LinkedHashMap<>();
        List<String> palette = new ArrayList<>();
        List<ChunkBiomeSample> samples = new ArrayList<>();

        paletteMap.put("minecraft:plains", 0);
        palette.add("minecraft:plains");

        int filesScanned = 0;
        int generatedCount = 0;
        MemoryShape<?> fallback = (region != null && region.shape instanceof MemoryShape<?> ms) ? ms : null;
        ChunkSink sink = (cx, cz, biome) -> {
            if (samples.size() >= maxChunks) return true; // keep counting generated chunks
            int bIdx = paletteMap.computeIfAbsent(biome, k -> {
                palette.add(k);
                return palette.size() - 1;
            });
            samples.add(new ChunkBiomeSample(cx, cz, bIdx));
            return true;
        };

        for (int rz = minRz; rz <= maxRz; rz++) {
            for (int rx = minRx; rx <= maxRx; rx++) {
                Path target = regionFileIn(regionDir, "r." + rx + "." + rz);
                if (target == null) continue;

                filesScanned++;
                // Leased pooled buffer: valid bytes are [0, length()).
                try (AnvilRegionByteCache.Lease lease = AnvilRegionByteCache.acquire(target)) {
                    if (lease == null) continue;
                    generatedCount += sampleRegionFile(lease.buffer(), lease.length(), extensionOf(target),
                            rx, rz, minCx, minCz, maxCx, maxCz, fallback, sink);
                }
            }
        }

        return new ExtractionResult(palette, samples, generatedCount, filesScanned);
    }

    /**
     * Region file for {@code baseName}: a registered non-Anvil format first (a converted world may
     * keep stale {@code .mca} files), then {@code .mca}. Unregistered formats are ignored so their
     * bytes never reach the Anvil decoder. {@code null} when none exists.
     */
    static Path regionFileIn(Path regionDir, String baseName) {
        for (String ext : RegionFormatRegistry.getRegisteredExtensions()) {
            if (".mca".equals(ext)) continue;
            Path p = regionDir.resolve(baseName + ext);
            if (Files.isRegularFile(p)) return p;
        }
        Path mca = regionDir.resolve(baseName + ".mca");
        return Files.isRegularFile(mca) ? mca : null;
    }

    static String extensionOf(Path regionFile) {
        String name = regionFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : ".mca";
    }

    /**
     * Emits one surface-biome sample per generated chunk of region file {@code (rx, rz)} that falls
     * inside the inclusive chunk bounds. Reads only the supplied bytes (S-005: no chunk loads).
     *
     * @return number of generated chunks visited inside the bounds
     */
    static int sampleRegionFile(byte[] bytes, String ext, int rx, int rz,
                                int minCx, int minCz, int maxCx, int maxCz,
                                MemoryShape<?> fallback, ChunkSink sink) {
        return sampleRegionFile(bytes, bytes == null ? 0 : bytes.length, ext, rx, rz,
                minCx, minCz, maxCx, maxCz, fallback, sink);
    }

    /** As above over {@code bytes[0, length)}; a pooled buffer's stale tail is never read. */
    static int sampleRegionFile(byte[] bytes, int length, String ext, int rx, int rz,
                                int minCx, int minCz, int maxCx, int maxCz,
                                MemoryShape<?> fallback, ChunkSink sink) {
        RegionFileReader reader = RegionFormatRegistry.getReader(ext);
        if (reader == null) return 0; // unregistered format: never decode as Anvil

        int baseChunkX = rx << 5;
        int baseChunkZ = rz << 5;
        int generated = 0;

        for (int lz = 0; lz < 32; lz++) {
            int cz = baseChunkZ + lz;
            if (cz < minCz || cz > maxCz) continue;

            for (int lx = 0; lx < 32; lx++) {
                int cx = baseChunkX + lx;
                if (cx < minCx || cx > maxCx) continue;

                boolean isGen;
                try {
                    isGen = reader.isChunkGenerated(bytes, length, lx, lz);
                } catch (Exception e) {
                    isGen = false;
                }
                if (!isGen) continue;

                generated++;
                String biomeName = null;
                // Attempt fast column probe for surface biome
                try {
                    ColumnProbe probe = AnvilReader.readColumnProbe(bytes, length, lx, lz, 32, 255);
                    if (probe != null && probe.hasHeightmap()) {
                        biomeName = probe.biomeAt(probe.heightmapTopY());
                    }
                } catch (Exception ignored) {}

                // Fallback to MemoryShape biome cache if column probe failed
                if (biomeName == null && fallback != null) {
                    biomeName = fallback.biomeAt(cx * 16 + 8, cz * 16 + 8);
                }

                if (biomeName == null || biomeName.isEmpty()) {
                    biomeName = "minecraft:plains";
                }

                if (!sink.accept(cx, cz, biomeName)) return generated;
            }
        }
        return generated;
    }

    private static ExtractionResult extractFromMemoryShape(
            Region region, int minCx, int minCz, int maxCx, int maxCz, int maxChunks) {

        if (!(region.shape instanceof MemoryShape<?> ms)) {
            return new ExtractionResult(List.of("minecraft:plains"), List.of(), 0, 0);
        }

        Map<String, Integer> paletteMap = new LinkedHashMap<>();
        List<String> palette = new ArrayList<>();
        List<ChunkBiomeSample> samples = new ArrayList<>();

        paletteMap.put("minecraft:plains", 0);
        palette.add("minecraft:plains");

        int stride = Math.max(1, (maxCx - minCx) / 64);
        int count = 0;

        for (int cz = minCz; cz <= maxCz && samples.size() < maxChunks; cz += stride) {
            for (int cx = minCx; cx <= maxCx && samples.size() < maxChunks; cx += stride) {
                int bx = cx * 16 + 8;
                int bz = cz * 16 + 8;
                if (!ms.contains(bx, bz)) continue;

                count++;
                String b = ms.biomeAt(bx, bz);
                if (b == null || b.isEmpty()) b = "minecraft:plains";

                int bIdx = paletteMap.computeIfAbsent(b, k -> {
                    palette.add(k);
                    return palette.size() - 1;
                });
                samples.add(new ChunkBiomeSample(cx, cz, bIdx));
            }
        }

        return new ExtractionResult(palette, samples, count, 0);
    }
}
