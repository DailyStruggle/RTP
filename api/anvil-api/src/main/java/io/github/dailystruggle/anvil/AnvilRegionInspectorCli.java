package io.github.dailystruggle.anvil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Headless region-file inspector for {@code .mca}, {@code .lca}, {@code .linear}, and
 * {@code .b_linear} archives backed by {@link RegionFormatRegistry} and {@link AnvilChunkView}.
 *
 * <p>Decodes populated chunks across a single {@code r.X.Z.<ext>} archive or a world/region
 * directory tree and reports populated chunk counts, biome distributions, block palette entries,
 * and exact 3D voxel counts for optional block filters (such as {@code log,leaves}).
 *
 * @governedBy ADR-016
 * @governedBy ADR-077
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @trace REQ-RTP-S-005
 */
public final class AnvilRegionInspectorCli {

    private static final Pattern REGION_FILE_PATTERN =
            Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.([A-Za-z0-9_]+)");

    private AnvilRegionInspectorCli() {}

    /** Summary of a single decoded chunk inside a region archive. */
    public record ChunkSummary(
            int chunkX,
            int chunkZ,
            int blockX,
            int blockZ,
            int dataVersion,
            int sectionCount,
            int surfaceYCenter,
            Set<String> biomes,
            Map<String, Integer> matchedBlockCounts) {}

    /** Summary of a single {@code r.X.Z.<ext>} region archive. */
    public record RegionSummary(
            String path,
            String format,
            int regionX,
            int regionZ,
            long sizeBytes,
            int populatedChunks,
            Set<String> biomes,
            Map<String, Long> matchedBlockTotals,
            List<ChunkSummary> sampleChunks) {}

    /**
     * Inspects a single region file or a directory containing region files.
     *
     * @param targetPath     path to an {@code r.X.Z.<ext>} file or directory
     * @param blockFilters   comma-separated substrings to count in block palettes/voxels (default: {@code log,leaves,stem,hyphae,shroomlight})
     * @param biomeFilters   optional comma-separated biome substrings to filter sample chunks
     * @param maxChunkSamples maximum number of per-chunk samples to include per region
     * @return list of inspected region summaries
     */
    public static List<RegionSummary> inspectPath(
            Path targetPath,
            List<String> blockFilters,
            List<String> biomeFilters,
            int maxChunkSamples) throws IOException {
        if (targetPath == null || !Files.exists(targetPath)) {
            return Collections.emptyList();
        }
        RegionFormatRegistry.reset();
        List<Path> files = collectRegionFiles(targetPath);
        return files.parallelStream()
                .map(file -> inspectRegionFile(file, blockFilters, biomeFilters, maxChunkSamples))
                .filter(Objects::nonNull)
                .toList();
    }

    private static List<Path> collectRegionFiles(Path targetPath) throws IOException {
        if (Files.isRegularFile(targetPath)) {
            return List.of(targetPath);
        }
        List<Path> blockRegions = new ArrayList<>();
        List<Path> otherRegions = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(targetPath, 6)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                Matcher m = REGION_FILE_PATTERN.matcher(p.getFileName().toString());
                if (!m.matches()) return;
                String ext = "." + m.group(3).toLowerCase(Locale.ROOT);
                if (!RegionFormatRegistry.isRegistered(ext)) return;
                String norm = p.toString().replace('\\', '/');
                if (norm.contains("/entities/") || norm.contains("/poi/")) {
                    otherRegions.add(p);
                } else {
                    blockRegions.add(p);
                }
            });
        }
        List<Path> chosen = blockRegions.isEmpty() ? otherRegions : blockRegions;
        chosen.sort(Comparator.comparing(Path::toString));
        return chosen;
    }

    private static RegionSummary inspectRegionFile(
            Path file,
            List<String> blockFilters,
            List<String> biomeFilters,
            int maxChunkSamples) {
        Matcher m = REGION_FILE_PATTERN.matcher(file.getFileName().toString());
        if (!m.matches()) {
            return null;
        }
        int rx = Integer.parseInt(m.group(1));
        int rz = Integer.parseInt(m.group(2));
        String ext = "." + m.group(3).toLowerCase(Locale.ROOT);
        RegionFileReader reader = RegionFormatRegistry.getReader(ext);
        if (reader == null) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException ex) {
            return null;
        }

        List<String> normBlockFilters = (blockFilters == null || blockFilters.isEmpty())
                ? List.of("log", "leaves", "stem", "hyphae", "shroomlight", "sapling")
                : blockFilters.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        List<String> normBiomeFilters = (biomeFilters == null)
                ? List.of()
                : biomeFilters.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();

        int populated = 0;
        Set<String> regionBiomes = new TreeSet<>();
        Map<String, Long> regionBlockTotals = new TreeMap<>();
        List<ChunkSummary> samples = new ArrayList<>();

        for (int localZ = 0; localZ < 32; localZ++) {
            for (int localX = 0; localX < 32; localX++) {
                AnvilReader.ChunkEntry entry;
                try {
                    entry = reader.readChunk(bytes, localX, localZ);
                } catch (IOException | RuntimeException ignored) {
                    continue;
                }
                if (entry == null || entry.root == null) {
                    continue;
                }
                populated++;
                AnvilChunkView view = AnvilReader.toView(entry.root);
                Set<String> chunkBiomes = new TreeSet<>(view.getBiomesPresent());
                regionBiomes.addAll(chunkBiomes);

                if (!normBiomeFilters.isEmpty()) {
                    boolean matchesBiome = false;
                    for (String b : chunkBiomes) {
                        String bl = b.toLowerCase(Locale.ROOT);
                        for (String bf : normBiomeFilters) {
                            if (bl.contains(bf)) {
                                matchesBiome = true;
                                break;
                            }
                        }
                        if (matchesBiome) break;
                    }
                    if (!matchesBiome) {
                        continue;
                    }
                }

                Map<String, Integer> chunkBlocks = countMatchingVoxels(view, normBlockFilters);
                for (Map.Entry<String, Integer> e : chunkBlocks.entrySet()) {
                    regionBlockTotals.merge(e.getKey(), e.getValue().longValue(), Long::sum);
                }

                int absCx = (rx << 5) + localX;
                int absCz = (rz << 5) + localZ;
                int surfaceY = view.getHeightmapTopY(8, 8);
                samples.add(new ChunkSummary(
                        absCx,
                        absCz,
                        absCx << 4,
                        absCz << 4,
                        view.dataVersion(),
                        view.sections().size(),
                        surfaceY,
                        chunkBiomes,
                        chunkBlocks));
            }
        }

        samples.sort(
                Comparator.<ChunkSummary>comparingInt(
                                c -> c.matchedBlockCounts().values().stream().mapToInt(Integer::intValue).sum())
                        .reversed()
                        .thenComparingInt(c -> c.surfaceYCenter() != Integer.MIN_VALUE ? 0 : 1)
                        .thenComparingInt(ChunkSummary::chunkZ)
                        .thenComparingInt(ChunkSummary::chunkX));
        List<ChunkSummary> topSamples =
                samples.size() > maxChunkSamples ? new ArrayList<>(samples.subList(0, maxChunkSamples)) : samples;

        return new RegionSummary(
                file.toString(),
                ext,
                rx,
                rz,
                bytes.length,
                populated,
                regionBiomes,
                regionBlockTotals,
                topSamples);
    }

    private static Map<String, Integer> countMatchingVoxels(AnvilChunkView view, List<String> filters) {
        Map<String, Integer> counts = new TreeMap<>();
        for (PaletteSection sec : view.sections()) {
            List<String> pal = sec.palette();
            boolean anyMatch = false;
            boolean[] idxMatches = new boolean[pal.size()];
            for (int i = 0; i < pal.size(); i++) {
                String name = pal.get(i);
                if (name == null) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                for (String f : filters) {
                    if (lower.contains(f)) {
                        idxMatches[i] = true;
                        anyMatch = true;
                        break;
                    }
                }
            }
            if (!anyMatch) continue;
            if (pal.size() == 1) {
                if (idxMatches[0]) {
                    counts.merge(pal.get(0), 4096, Integer::sum);
                }
                continue;
            }
            long[] data = sec.data();
            if (data == null || data.length == 0) {
                continue;
            }
            int palSize = pal.size();
            int[] palCounts = new int[palSize];
            for (int idx = 0; idx < 4096; idx++) {
                int pIdx = PackedPaletteDecoder.decode(data, palSize, idx);
                if (pIdx >= 0 && pIdx < palSize && idxMatches[pIdx]) {
                    palCounts[pIdx]++;
                }
            }
            for (int i = 0; i < palSize; i++) {
                if (palCounts[i] > 0) {
                    counts.merge(pal.get(i), palCounts[i], Integer::sum);
                }
            }
        }
        return counts;
    }

    /**
     * CLI entry point printing JSON output for MCP / script consumption.
     *
     * @param args {@code <path> [--block-filter a,b] [--biome-filter c,d] [--max-samples N]}
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: AnvilRegionInspectorCli <path> [--block-filter a,b] [--biome-filter c,d] [--max-samples N]");
            System.exit(2);
        }
        Path target = Path.of(args[0]);
        List<String> blockFilters = new ArrayList<>();
        List<String> biomeFilters = new ArrayList<>();
        int maxSamples = 12;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--block-filter" -> {
                    if (i + 1 < args.length) {
                        for (String p : args[++i].split(",")) {
                            if (!p.isBlank()) blockFilters.add(p.trim());
                        }
                    }
                }
                case "--biome-filter" -> {
                    if (i + 1 < args.length) {
                        for (String p : args[++i].split(",")) {
                            if (!p.isBlank()) biomeFilters.add(p.trim());
                        }
                    }
                }
                case "--max-samples" -> {
                    if (i + 1 < args.length) {
                        maxSamples = Integer.parseInt(args[++i]);
                    }
                }
                default -> {
                }
            }
        }

        List<RegionSummary> regions = inspectPath(target, blockFilters, biomeFilters, maxSamples);
        System.out.println(toJson(target.toString(), regions));
    }

    private static String toJson(String target, List<RegionSummary> regions) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n");
        sb.append("  \"target\": ").append(quote(target)).append(",\n");
        sb.append("  \"region_count\": ").append(regions.size()).append(",\n");
        int totalChunks = regions.stream().mapToInt(RegionSummary::populatedChunks).sum();
        long totalBytes = regions.stream().mapToLong(RegionSummary::sizeBytes).sum();
        Set<String> allBiomes = new TreeSet<>();
        Map<String, Long> allBlocks = new TreeMap<>();
        Set<String> formats = new LinkedHashSet<>();
        for (RegionSummary r : regions) {
            formats.add(r.format());
            allBiomes.addAll(r.biomes());
            r.matchedBlockTotals().forEach((k, v) -> allBlocks.merge(k, v, Long::sum));
        }
        sb.append("  \"formats\": ").append(stringListJson(formats)).append(",\n");
        sb.append("  \"total_populated_chunks\": ").append(totalChunks).append(",\n");
        sb.append("  \"total_size_bytes\": ").append(totalBytes).append(",\n");
        sb.append("  \"biomes\": ").append(stringListJson(allBiomes)).append(",\n");
        sb.append("  \"matched_block_totals\": ").append(longMapJson(allBlocks)).append(",\n");
        sb.append("  \"regions\": [\n");
        for (int i = 0; i < regions.size(); i++) {
            RegionSummary r = regions.get(i);
            sb.append("    {\n");
            sb.append("      \"path\": ").append(quote(r.path())).append(",\n");
            sb.append("      \"format\": ").append(quote(r.format())).append(",\n");
            sb.append("      \"regionX\": ").append(r.regionX()).append(",\n");
            sb.append("      \"regionZ\": ").append(r.regionZ()).append(",\n");
            sb.append("      \"size_bytes\": ").append(r.sizeBytes()).append(",\n");
            sb.append("      \"populated_chunks\": ").append(r.populatedChunks()).append(",\n");
            sb.append("      \"biomes\": ").append(stringListJson(r.biomes())).append(",\n");
            sb.append("      \"matched_block_totals\": ").append(longMapJson(r.matchedBlockTotals())).append(",\n");
            sb.append("      \"sample_chunks\": [\n");
            for (int j = 0; j < r.sampleChunks().size(); j++) {
                ChunkSummary c = r.sampleChunks().get(j);
                sb.append("        {")
                        .append("\"chunkX\": ").append(c.chunkX()).append(", ")
                        .append("\"chunkZ\": ").append(c.chunkZ()).append(", ")
                        .append("\"blockX\": ").append(c.blockX()).append(", ")
                        .append("\"blockZ\": ").append(c.blockZ()).append(", ")
                        .append("\"dataVersion\": ").append(c.dataVersion()).append(", ")
                        .append("\"sections\": ").append(c.sectionCount()).append(", ")
                        .append("\"surfaceYCenter\": ").append(c.surfaceYCenter()).append(", ")
                        .append("\"biomes\": ").append(stringListJson(c.biomes())).append(", ")
                        .append("\"matched_blocks\": ").append(intMapJson(c.matchedBlockCounts()))
                        .append("}");
                if (j + 1 < r.sampleChunks().size()) sb.append(",");
                sb.append("\n");
            }
            sb.append("      ]\n");
            sb.append("    }");
            if (i + 1 < regions.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String stringListJson( Iterable<String> items) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String item : items) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(quote(item));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String longMapJson(Map<String, Long> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(quote(e.getKey())).append(": ").append(e.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    private static String intMapJson(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(quote(e.getKey())).append(": ").append(e.getValue());
        }
        sb.append("}");
        return sb.toString();
    }
}
