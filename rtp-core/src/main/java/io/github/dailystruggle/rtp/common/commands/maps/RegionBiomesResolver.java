package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.BiomeColorSource;
import io.github.dailystruggle.mapsapi.model.RegionBiomesRgb;
import io.github.dailystruggle.mapsapi.render.RegionBiomesRgbRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
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
 * <h3>Data source</h3>
 *
 * <p>Per-pixel biome data comes from the platform's anvil prefilter via
 * {@link RTPWorld#readBiomesInRegionFile(int, int, int)} — i.e. directly from
 * the on-disk {@code r.X.Z.mca} palettes, NOT from {@code MemoryShape}'s
 * accumulated scan samples. This removes the prior limitation where regions
 * with only one observed biome per dimension (typical of nether / end) would
 * render as a single colour across the entire disk. The chart now answers
 * "what biomes are in this region right now" rather than "what biomes has the
 * scan accumulated so far".
 *
 * <p>Per-biome RGB resolution still flows through
 * {@link BiomeColorSource#resolve(String)} — the Bukkit-family
 * {@code BukkitBiomeColorSource} installs dimension-aware overrides for the
 * well-known nether / end biomes so {@code NETHER_WASTES},
 * {@code CRIMSON_FOREST}, etc. each get distinct hues regardless of how
 * Mojang's {@code Biome#getMapColor()} quantises them.
 *
 * <h3>Per-pixel classification</h3>
 *
 * <ul>
 *   <li>{@link RegionBiomesRgb#MASK_OUTSIDE} — pixel outside the shape's
 *       spatial domain ({@link MemoryShape#contains(int, int)} returned
 *       {@code false}).</li>
 *   <li>{@link RegionBiomesRgb#MASK_RGB} with the biome's RGB — pixel inside
 *       the shape AND its containing chunk has biome data on disk.</li>
 *   <li>{@link RegionBiomesRgb#MASK_UNSAMPLED} — pixel inside the shape but
 *       its containing chunk is ungenerated (absent from any
 *       {@code .mca}). The renderer paints these BLACK.</li>
 * </ul>
 *
 * <h3>Threading</h3>
 *
 * <p>{@code readBiomesInRegionFile} performs synchronous {@code .mca} reads
 * on the calling thread (S-005-clean: on-disk reads only, no live chunk
 * I/O). This resolver loops over distinct {@code .mca} bins serially - it's
 * already off the main thread (sibling of the bad-locations resolver,
 * dispatched from {@code VisualizationDispatch} on the async scheduler
 * arm), and per-bin work is ~1-10 ms with {@code AnvilRegionByteCache}
 * warm. Sidesteps the {@code REQ-RTP-MAP-006} prohibition on resolver-side
 * {@code CompletableFuture#get / #join}. Total work scales as
 * {@code O(distinct .mca files)} per chart, typically &lt;10 for the
 * default 128x128 buffer.
 *
 * <h3>Failure modes</h3>
 *
 * <ul>
 *   <li>Spec is null / wrong kind / region missing -&gt;
 *       {@link UnresolvableChartSpecException}.</li>
 *   <li>Region's shape is not a {@link MemoryShape} (no
 *       {@code contains(x, z)} surface) -&gt;
 *       {@link UnresolvableChartSpecException}.</li>
 *   <li>Per-bin anvil read fails -&gt; that bin's map is empty, pixels in it
 *       render as BLACK (ungenerated). Logged at FINE in the platform
 *       adapter.</li>
 * </ul>
 */
public final class RegionBiomesResolver implements ChartSpecResolver {

    /** Cartography map dimensions (vanilla). Matches the renderer's fast path. */
    private static final int BUFFER_WIDTH = 128;
    private static final int BUFFER_HEIGHT = 128;

    /**
     * World-Y at which to sample the biome from each chunk's biome palette.
     * Biomes vary by 3D position in modern MC, so this picks a single
     * sampling plane. Y=64 is a reasonable surface anchor across overworld,
     * nether (above the lava sea), and end (at island level). For caves /
     * roof biomes this will read whichever biome covers y=64 in the chunk's
     * column, which matches what a vanilla map of the region would show.
     */
    private static final int SAMPLE_Y = 64;


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
        RTPWorld<?> world = region.getWorld();
        if (world == null) {
            throw new UnresolvableChartSpecException(
                    "region '" + region.name + "' has no bound world; cannot read anvil");
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

        // ---- Pass 2: pre-compute per-pixel (bx, bz) and decide which are in-disk. ----
        int pixels2 = BUFFER_WIDTH * BUFFER_HEIGHT;
        int[] pxBlockX = new int[pixels2];
        int[] pxBlockZ = new int[pixels2];
        boolean[] pxInside = new boolean[pixels2];
        // Set of region-file coords (rcx, rcz) we need to read. Packed into
        // a long: ((long)rcx << 32) | (rcz & 0xFFFF_FFFFL).
        java.util.HashSet<Long> regionFileCoords = new java.util.HashSet<>();
        int insideCount = 0;
        for (int py = 0; py < BUFFER_HEIGHT; py++) {
            int bz = (int) (minZ + py * boundH / (BUFFER_HEIGHT - 1));
            int row = py * BUFFER_WIDTH;
            for (int px = 0; px < BUFFER_WIDTH; px++) {
                int bx = (int) (minX + px * boundW / (BUFFER_WIDTH - 1));
                int idx = row + px;
                pxBlockX[idx] = bx;
                pxBlockZ[idx] = bz;
                if (memoryShape.contains(bx, bz)) {
                    pxInside[idx] = true;
                    insideCount++;
                    int cx = bx >> 4;
                    int cz = bz >> 4;
                    int rcx = cx >> 5;
                    int rcz = cz >> 5;
                    regionFileCoords.add(((long) rcx << 32) | (rcz & 0xFFFF_FFFFL));
                }
            }
        }

        RTP.log(java.util.logging.Level.FINE,
                "[viz/biomes] resolver bbox: region=" + region.name
                        + " range=" + range + " step=" + step
                        + " samples=" + samples
                        + " bbox=(" + minX + "," + minZ + ")..(" + maxX + "," + maxZ + ")"
                        + " pad=" + pad
                        + " inside-disk-pixels=" + insideCount
                        + " region-files=" + regionFileCoords.size());

        // ---- Pass 3: read each region file serially. ----
        // Each .mca read is ~1-2 ms (warm cache) to ~10 ms (cold), 1024-chunk
        // decode; a typical 128x128 chart spans <10 distinct .mca files, so
        // total bin work is ~10-100 ms. We're already off the tick thread
        // (MapDispatch.paint is dispatched from VisualizationDispatch on an
        // async scheduler arm), so a synchronous loop is acceptable and
        // sidesteps the REQ-RTP-MAP-006 prohibition on CompletableFuture
        // blocking in resolvers.
        Map<Long, String> biomeByChunk = new HashMap<>(insideCount);
        int binsResolved = 0;
        int binsEmpty = 0;
        for (Long rkey : regionFileCoords) {
            int rcx = (int) (rkey >> 32);
            int rcz = (int) (rkey & 0xFFFF_FFFFL);
            Map<Long, String> bin;
            try {
                bin = world.readBiomesInRegionFile(rcx, rcz, SAMPLE_Y);
            } catch (RuntimeException e) {
                // Platform default returns empty; only an override throwing
                // synchronously gets us here. Treat the bin as empty - all
                // its pixels render as BLACK.
                RTP.log(java.util.logging.Level.FINE,
                        "[viz/biomes] readBiomesInRegionFile(rcx=" + rcx + ",rcz=" + rcz
                                + ") threw synchronously: "
                                + e.getClass().getSimpleName() + ": " + e.getMessage());
                bin = null;
            }
            if (bin == null || bin.isEmpty()) {
                binsEmpty++;
                continue;
            }
            biomeByChunk.putAll(bin);
            binsResolved++;
        }

        // ---- Pass 4: resolve each unique biome to RGB once via BiomeColorSource. ----
        Map<String, Integer> biomeRgb = new HashMap<>(biomeByChunk.size() / 8 + 4);
        for (String biomeName : biomeByChunk.values()) {
            if (!biomeRgb.containsKey(biomeName)) {
                biomeRgb.put(biomeName, BiomeColorSource.resolve(biomeName) & 0xFFFFFF);
            }
        }

        // ---- Pass 5: materialise the RGB + mask output. ----
        int[] rgbOut = new int[pixels2];
        byte[] maskOut = new byte[pixels2];
        int paintedPixels = 0;
        int ungeneratedPixels = 0;
        for (int i = 0; i < pixels2; i++) {
            if (!pxInside[i]) {
                maskOut[i] = RegionBiomesRgb.MASK_OUTSIDE;
                continue;
            }
            int cx = pxBlockX[i] >> 4;
            int cz = pxBlockZ[i] >> 4;
            long ckey = ((long) cx << 32) | (cz & 0xFFFF_FFFFL);
            String biomeName = biomeByChunk.get(ckey);
            if (biomeName == null) {
                maskOut[i] = RegionBiomesRgb.MASK_UNSAMPLED;
                ungeneratedPixels++;
                continue;
            }
            Integer rgb = biomeRgb.get(biomeName);
            rgbOut[i] = rgb == null ? 0 : rgb;
            maskOut[i] = RegionBiomesRgb.MASK_RGB;
            paintedPixels++;
        }

        // Build a tiny "top biomes by pixel count" summary for the diag log.
        List<String> topBiomes = topBiomesByFrequency(biomeByChunk, 5);
        RTP.log(java.util.logging.Level.FINE,
                "[viz/biomes] anvil-paint: bins-resolved=" + binsResolved
                        + " bins-empty=" + binsEmpty
                        + " chunks-with-biome=" + biomeByChunk.size()
                        + " unique-biomes=" + biomeRgb.size()
                        + " painted-pixels=" + paintedPixels
                        + " ungenerated-pixels=" + ungeneratedPixels
                        + " top-biomes=" + topBiomes);

        RegionBiomesRgb model = new RegionBiomesRgb(
                region.name, BUFFER_WIDTH, BUFFER_HEIGHT, rgbOut, maskOut);
        return Resolution.of(RegionBiomesRgbRenderer.INSTANCE, model);
    }

    /**
     * Compact "top biomes by chunk count" summary for diagnostics. Returns
     * at most {@code limit} entries, each as {@code "<NAME>:<count>"}.
     */
    private static List<String> topBiomesByFrequency(Map<Long, String> biomeByChunk, int limit) {
        Map<String, Integer> counts = new HashMap<>();
        for (String b : biomeByChunk.values()) {
            counts.merge(b, 1, Integer::sum);
        }
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
