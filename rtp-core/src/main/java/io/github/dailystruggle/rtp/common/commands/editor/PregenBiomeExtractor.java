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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Off-tick extractor for chunk-resolution pregenerated land and biome mapping (ADR-104 §4.3, ADR-084).
 *
 * <p>Rule S-005 compliant: Zero chunk loads on the main thread. Scans region files (.mca / .linear)
 * within the bounding box of a region and samples chunk existence and representative surface biomes.</p>
 */
public final class PregenBiomeExtractor {

    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.(mca|linear)");

    public record ChunkBiomeSample(int cx, int cz, int biomeIndex) {}

    public record ExtractionResult(
            List<String> palette,
            List<ChunkBiomeSample> chunks,
            int totalGeneratedChunks,
            int regionFilesScanned
    ) {}

    private PregenBiomeExtractor() {}

    /**
     * Resolves the world region directory for a region or world name.
     */
    public static Path resolveRegionFolder(Region region) {
        if (region == null) return null;
        String worldName = null;
        try {
            RTPWorld<?> rtpWorld = region.getWorld();
            if (rtpWorld != null) {
                worldName = rtpWorld.name();
            }
        } catch (Exception ignored) {}
        if (worldName == null || worldName.isEmpty()) {
            if (region.configuredWorldName != null && !region.configuredWorldName.isEmpty()) {
                worldName = region.configuredWorldName;
            } else {
                worldName = "world";
            }
        }

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

        // 2. Search common server paths (e.g. ./world/region, ./world/dimensions/minecraft/overworld/region)
        List<Path> searchRoots = new ArrayList<>();
        searchRoots.add(Path.of("."));
        searchRoots.add(Path.of("world"));
        searchRoots.add(Path.of("C:\\GameServers\\Minecraft\\testServer\\RTP-Paper\\26.3"));

        for (Path root : searchRoots) {
            Path found = findRegionDirUnder(root, worldName);
            if (found != null) return found;
        }

        return null;
    }

    private static Path findRegionDirUnder(Path root, String worldName) {
        if (!Files.isDirectory(root)) return null;

        // Try direct combinations
        List<Path> candidates = List.of(
                root.resolve("region"),
                root.resolve(worldName).resolve("region"),
                root.resolve("world").resolve("region"),
                root.resolve(worldName).resolve("dimensions/minecraft/overworld/region"),
                root.resolve("world/dimensions/minecraft/overworld/region")
        );
        for (Path c : candidates) {
            if (Files.isDirectory(c)) return c;
        }

        // Shallow walk for "region" folder
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                if (Files.isDirectory(p) && "region".equalsIgnoreCase(p.getFileName().toString())) {
                    return p;
                }
                if (Files.isDirectory(p)) {
                    Path nested = p.resolve("region");
                    if (Files.isDirectory(nested)) return nested;
                }
            }
        } catch (IOException ignored) {}

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
            long range = memoryShape.getRange();
            int rBlocks = (int) Math.min(32768, Math.max(256, Math.round(Math.sqrt(range / Math.PI))));
            int rChunks = Math.max(16, (rBlocks / 16) + 8);
            minCx = -rChunks;
            maxCx = rChunks;
            minCz = -rChunks;
            maxCz = rChunks;
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

        for (int rz = minRz; rz <= maxRz; rz++) {
            for (int rx = minRx; rx <= maxRx; rx++) {
                String baseName = "r." + rx + "." + rz;
                Path mca = regionDir.resolve(baseName + ".mca");
                Path linear = regionDir.resolve(baseName + ".linear");
                Path target = Files.isRegularFile(mca) ? mca : (Files.isRegularFile(linear) ? linear : null);
                if (target == null) continue;

                filesScanned++;
                byte[] bytes = AnvilRegionByteCache.get(target);
                if (bytes == null) continue;

                String ext = target.getFileName().toString().endsWith(".linear") ? ".linear" : ".mca";
                RegionFileReader reader = RegionFormatRegistry.getReader(ext);
                if (reader == null) reader = AnvilReader.INSTANCE;

                int baseChunkX = rx << 5;
                int baseChunkZ = rz << 5;

                for (int lz = 0; lz < 32; lz++) {
                    int cz = baseChunkZ + lz;
                    if (cz < minCz || cz > maxCz) continue;

                    for (int lx = 0; lx < 32; lx++) {
                        int cx = baseChunkX + lx;
                        if (cx < minCx || cx > maxCx) continue;

                        boolean isGen;
                        try {
                            isGen = reader.isChunkGenerated(bytes, lx, lz);
                        } catch (Exception e) {
                            isGen = false;
                        }
                        if (!isGen) continue;

                        generatedCount++;

                        if (samples.size() < maxChunks) {
                            String biomeName = null;
                            // Attempt fast column probe for surface biome
                            try {
                                ColumnProbe probe = AnvilReader.readColumnProbe(bytes, lx, lz, 32, 255);
                                if (probe != null && probe.hasHeightmap()) {
                                    biomeName = probe.biomeAt(probe.heightmapTopY());
                                }
                            } catch (Exception ignored) {}

                            // Fallback to MemoryShape biome cache if column probe failed
                            if (biomeName == null && region != null && region.shape instanceof MemoryShape<?> ms) {
                                biomeName = ms.biomeAt(cx * 16 + 8, cz * 16 + 8);
                            }

                            if (biomeName == null || biomeName.isEmpty()) {
                                biomeName = "minecraft:plains";
                            }

                            int bIdx = paletteMap.computeIfAbsent(biomeName, k -> {
                                palette.add(k);
                                return palette.size() - 1;
                            });

                            samples.add(new ChunkBiomeSample(cx, cz, bIdx));
                        }
                    }
                }
            }
        }

        return new ExtractionResult(palette, samples, generatedCount, filesScanned);
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
