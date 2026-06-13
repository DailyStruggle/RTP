package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.BiomeColorSource;
import io.github.dailystruggle.mapsapi.model.RegionBiomesRgb;
import io.github.dailystruggle.mapsapi.render.RegionBiomesRgbRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolver for {@link ChartSpec.Kind#REGION_BIOMES}.
 *
 * <p>Produces a {@link RegionBiomesRgb} model carrying per-pixel 24-bit RGB
 * plus a parallel mask byte; {@link RegionBiomesRgbRenderer} routes sampled
 * cells through {@code MapCanvas#setPixelRgb} (Bukkit-family:
 * {@code MapPalette.matchColor} over the ~144-entry vanilla palette) and
 * unsampled / outside-disk cells through {@code setPixel(PaletteIndex.BLACK)}.
 *
 * <p><b>Data source</b>
 *
 * <p>Per-pixel biome data comes exclusively from the region's saved biome
 * data via {@link MemoryShape#biomeAt(int, int)} — i.e. the persisted,
 * in-memory {@code biomeKeysCache} / {@code biomePrefixSumsCache} accumulated
 * by the scan pipeline. No on-disk {@code .mca} palettes are read. The chart
 * answers "what biomes has the scan recorded for this region"; pixels with no
 * recorded biome render as unsampled (BLACK).
 *
 * <p>Per-biome RGB resolution still flows through
 * {@link BiomeColorSource#resolve(String)} — the Bukkit-family
 * {@code BukkitBiomeColorSource} installs dimension-aware overrides for the
 * well-known nether / end biomes so {@code NETHER_WASTES},
 * {@code CRIMSON_FOREST}, etc. each get distinct hues regardless of how
 * Mojang's {@code Biome#getMapColor()} quantises them.
 *
 * <p><b>Per-pixel classification</b>
 *
 * <ul>
 *   <li>{@link RegionBiomesRgb#MASK_OUTSIDE} — pixel outside the shape's
 *       spatial domain ({@link MemoryShape#contains(int, int)} returned
 *       {@code false}).</li>
 *   <li>{@link RegionBiomesRgb#MASK_RGB} with the biome's RGB — pixel inside
 *       the shape AND the region has a saved biome recorded at that
 *       coordinate.</li>
 *   <li>{@link RegionBiomesRgb#MASK_UNSAMPLED} — pixel inside the shape but
 *       the scan has not yet recorded a biome there
 *       ({@link MemoryShape#biomeAt(int, int)} returned {@code null}). The
 *       renderer paints these BLACK.</li>
 * </ul>
 *
 * <p><b>Threading</b>
 *
 * <p>Classification reads only the in-memory saved biome cache via
 * {@link MemoryShape#biomeAt(int, int)} (S-005-clean: no chunk I/O, no
 * {@code .mca} reads). It is a sibling of the bad-locations resolver,
 * dispatched from {@code VisualizationDispatch} on the async scheduler arm,
 * and sidesteps the {@code REQ-RTP-MAP-006} prohibition on resolver-side
 * {@code CompletableFuture#get / #join}.
 *
 * <p><b>Failure modes</b>
 *
 * <ul>
 *   <li>Spec is null / wrong kind / region missing -&gt;
 *       {@link UnresolvableChartSpecException}.</li>
 *   <li>Region's shape is not a {@link MemoryShape} (no
 *       {@code contains(x, z)} surface) -&gt;
 *       {@link UnresolvableChartSpecException}.</li>
 *   <li>Region has no saved biome data yet -&gt; every in-disk pixel
 *       classifies as {@code MASK_UNSAMPLED} and renders BLACK.</li>
 * </ul>
 */
public final class RegionBiomesResolver implements ChartSpecResolver {

    /** Cartography map dimensions (vanilla). Matches the renderer's fast path. */
    private static final int BUFFER_WIDTH = 128;
    private static final int BUFFER_HEIGHT = 128;


    @Override
    public Resolution resolve(ChartSpec spec) throws UnresolvableChartSpecException {
        RTP.log(java.util.logging.Level.FINE,
                "[viz/biomes] resolver entry: spec="
                        + (spec == null ? "null"
                                : (spec.kind() + " region=" + spec.regionName())));
        if (spec == null) {
            throw new UnresolvableChartSpecException("spec shall not be null");
        }
        if (spec.kind() != ChartSpec.Kind.REGION_BIOMES) {
            throw new UnresolvableChartSpecException(
                    "RegionBiomesResolver only handles REGION_BIOMES, got " + spec.kind());
        }

        Region region;
        try {
            region = RTP.selectionAPI.getRegionOrDefault(spec.regionName());
        } catch (RuntimeException e) {
            throw new UnresolvableChartSpecException(
                    "no region resolved for '" + spec.regionName() + "'", e);
        }
        if (region == null) {
            throw new UnresolvableChartSpecException(
                    "no region resolved for '" + spec.regionName() + "'");
        }
        if (!(region.shape instanceof MemoryShape<?> memoryShape)) {
            throw new UnresolvableChartSpecException(
                    "region '" + region.name
                            + "' shape is not a MemoryShape; no contains(x, z) surface");
        }
        long range;
        try {
            range = memoryShape.getRange();
        } catch (RuntimeException e) {
            throw new UnresolvableChartSpecException(
                    "region '" + region.name + "' shape getRange() failed", e);
        }
        if (range <= 0L) {
            throw new UnresolvableChartSpecException(
                    "region '" + region.name + "' shape has non-positive range " + range);
        }

        // ---- Pass 1: leap-sample bbox discovery (mirrors bad-locations / prior biomes). ----
        long pixels = (long) BUFFER_WIDTH * BUFFER_HEIGHT;
        long step = Math.max(1L, range / pixels);
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int samples = 0;
        for (long i = 0L; i < range; i += step) {
            int[] xz = memoryShape.locationToXZ(i);
            if (xz == null || xz.length < 2) continue;
            int bx = xz[0];
            int bz = xz[1];
            if (bx < minX) minX = bx;
            if (bx > maxX) maxX = bx;
            if (bz < minZ) minZ = bz;
            if (bz > maxZ) maxZ = bz;
            samples++;
        }
        if (samples == 0) {
            throw new UnresolvableChartSpecException(
                    "region '" + region.name
                            + "' shape range=" + range
                            + " but locationToXZ produced no valid samples");
        }
        int extentX = maxX - minX;
        int extentZ = maxZ - minZ;
        int pad = Math.max(1, Math.max(extentX, extentZ) / 10);
        minX -= pad; maxX += pad;
        minZ -= pad; maxZ += pad;
        long boundW = (long) maxX - minX;
        long boundH = (long) maxZ - minZ;
        if (boundW <= 0L) boundW = 1L;
        if (boundH <= 0L) boundH = 1L;

        // ---- Pass 2: per-pixel classification against the region's saved
        // biome data. No chunk I/O - biomeAt() reads only the in-memory,
        // persisted biome-location cache.
        int pixels2 = BUFFER_WIDTH * BUFFER_HEIGHT;
        int[] rgbOut = new int[pixels2];
        byte[] maskOut = new byte[pixels2];
        Map<String, Integer> biomeRgb = new HashMap<>();
        Map<String, Integer> biomeCounts = new HashMap<>();
        int insideCount = 0;
        int paintedPixels = 0;
        int unsampledPixels = 0;
        for (int py = 0; py < BUFFER_HEIGHT; py++) {
            int bz = (int) (minZ + py * boundH / (BUFFER_HEIGHT - 1));
            int row = py * BUFFER_WIDTH;
            for (int px = 0; px < BUFFER_WIDTH; px++) {
                int bx = (int) (minX + px * boundW / (BUFFER_WIDTH - 1));
                int idx = row + px;
                if (!memoryShape.contains(bx, bz)) {
                    maskOut[idx] = RegionBiomesRgb.MASK_OUTSIDE;
                    continue;
                }
                insideCount++;
                String biomeName = memoryShape.biomeAt(bx, bz);
                if (biomeName == null) {
                    maskOut[idx] = RegionBiomesRgb.MASK_UNSAMPLED;
                    unsampledPixels++;
                    continue;
                }
                Integer rgb = biomeRgb.get(biomeName);
                if (rgb == null) {
                    rgb = BiomeColorSource.resolve(biomeName) & 0xFFFFFF;
                    biomeRgb.put(biomeName, rgb);
                }
                rgbOut[idx] = rgb;
                maskOut[idx] = RegionBiomesRgb.MASK_RGB;
                biomeCounts.merge(biomeName, 1, Integer::sum);
                paintedPixels++;
            }
        }

        // Build a tiny "top biomes by pixel count" summary for the diag log.
        List<String> topBiomes = topBiomesByFrequency(biomeCounts, 5);
        RTP.log(java.util.logging.Level.FINE,
                "[viz/biomes] saved-data-paint: region=" + region.name
                        + " bbox=(" + minX + "," + minZ + ")..(" + maxX + "," + maxZ + ")"
                        + " inside-disk-pixels=" + insideCount
                        + " unique-biomes=" + biomeRgb.size()
                        + " painted-pixels=" + paintedPixels
                        + " unsampled-pixels=" + unsampledPixels
                        + " top-biomes=" + topBiomes);

        RegionBiomesRgb model = new RegionBiomesRgb(
                region.name, BUFFER_WIDTH, BUFFER_HEIGHT, rgbOut, maskOut);
        return Resolution.of(RegionBiomesRgbRenderer.INSTANCE, model);
    }

    /**
     * Compact "top biomes by pixel count" summary for diagnostics. Returns
     * at most {@code limit} entries, each as {@code "<NAME>:<count>"}.
     */
    private static List<String> topBiomesByFrequency(Map<String, Integer> counts, int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>(Math.min(limit, entries.size()));
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            Map.Entry<String, Integer> e = entries.get(i);
            out.add(e.getKey() + ":" + e.getValue());
        }
        return out;
    }
}
