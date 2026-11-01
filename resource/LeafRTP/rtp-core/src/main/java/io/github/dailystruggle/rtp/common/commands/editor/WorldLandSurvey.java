package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.api.world.RTPWorld;

import java.util.*;

/**
 * Progressive, world-wide, coarse-first chunk-biome survey feeding {@link WorldBiomeStore} (ADR-104 §4.6).
 *
 * <p>Covers every region file the world has on disk, not the configured regions: the data exists
 * to decide where a region should go. Files come from {@link RTPWorld#listRegionFiles()}, nearest
 * region file (0, 0) first; when the adapter cannot list, coordinates within
 * {@link #FALLBACK_RADIUS_REGIONS} are probed instead. Three passes cover the whole world at
 * increasing detail ({@link BiomeBinCodec#sampleIndices}: 1, 64, then 1,024 chunks per file), so the
 * map fills quickly and sharpens over time. Bins already at a level with an unchanged {@code .mca}
 * modification time (for example from the persisted store) are skipped without a read. Viewport
 * requests ({@link #prioritize}) take their bins to full detail first.
 *
 * <p>The per-batch budget counts chunk decodes, not files (rule F-002: count-bound). Reads go through
 * {@link RTPWorld#sampleBiomesInRegionFile} (S-005: disk only, off the tick thread). Not thread-safe;
 * callers serialise access.
 */
public final class WorldLandSurvey {

    /** Sampled read of one region file: chunk key {@code ((long) cx << 32) | (cz & 0xFFFFFFFFL)} to biome. */
    @FunctionalInterface
    public interface RegionBiomeSampler {
        Map<Long, String> sample(int rcx, int rcz, int[] localIndices);
    }

    /** {@code .mca} modification time, or -1 when unknown. */
    @FunctionalInterface
    public interface RegionMtime {
        long of(int rcx, int rcz);
    }

    /** Work done by one {@link #nextBatch} call. */
    public record Batch(String world, int y, int binsChanged, int decodes, int visits, boolean done) {}

    /** Probe radius when the adapter cannot list files: 64 region files = 32,768 blocks. */
    static final int FALLBACK_RADIUS_REGIONS = 64;
    /** Region files surveyed per world and sample Y. */
    static final int MAX_TILES_PER_WORLD = 16_384;
    /** Chunk decodes per batch: the cost the old 2-files-per-tick budget allowed. */
    static final int DECODES_PER_TICK = 2_048;
    /** Bin visits per batch (mtime stat + skip check, or a header read). */
    static final int VISITS_PER_TICK = 4_096;
    /** Bins a viewport request may queue; wider views are served by the coarse passes. */
    static final int MAX_PRIORITY_BINS = 1_024;

    private final String world;
    private final int y;
    private final WorldBiomeStore store;
    private final RegionBiomeSampler sampler;
    private final RegionMtime mtime;
    private final boolean listed;
    private final List<int[]> files;
    private final Set<Long> fileKeys = new HashSet<>();
    private final Set<Long> missing = new HashSet<>();
    private final boolean truncated;
    private final Deque<int[]> priority = new ArrayDeque<>();
    private int level;
    private int cursor;
    private long decodes;
    private int filesRead;

    /**
     * @param regionFiles listed region-file coordinates, or {@code null} to probe around the origin
     */
    WorldLandSurvey(String world, int y, WorldBiomeStore store, RegionBiomeSampler sampler, RegionMtime mtime,
                    List<int[]> regionFiles, int fallbackRadius, int maxTiles) {
        this.world = Objects.requireNonNull(world, "world");
        this.y = y;
        this.store = Objects.requireNonNull(store, "store");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.mtime = Objects.requireNonNull(mtime, "mtime");
        this.listed = regionFiles != null;
        List<int[]> order = listed ? nearestFirst(regionFiles) : discAroundOrigin(fallbackRadius);
        this.truncated = order.size() > Math.max(0, maxTiles);
        if (truncated) order = order.subList(0, Math.max(0, maxTiles));
        this.files = List.copyOf(order);
        for (int[] f : files) fileKeys.add(BiomeBinCodec.binKey(f[0], f[1]));
    }

    /** Survey of a live world at sample {@code y}. Lists the region directory: call off-tick. */
    public static WorldLandSurvey of(RTPWorld<?> rtpWorld, int y) {
        Objects.requireNonNull(rtpWorld, "rtpWorld");
        return new WorldLandSurvey(rtpWorld.name(), y, WorldBiomeStore.of(rtpWorld.name()),
                (rx, rz, idx) -> rtpWorld.sampleBiomesInRegionFile(rx, rz, y, idx),
                rtpWorld::regionFileModifiedMillis,
                rtpWorld.listRegionFiles(), FALLBACK_RADIUS_REGIONS, MAX_TILES_PER_WORLD);
    }

    /** Distinct coordinates sorted by squared distance to region file (0, 0), then x, then z. */
    static List<int[]> nearestFirst(List<int[]> coords) {
        Map<Long, int[]> unique = new LinkedHashMap<>();
        for (int[] c : coords) {
            if (c == null || c.length < 2) continue;
            unique.putIfAbsent(((long) c[0] << 32) | (c[1] & 0xFFFFFFFFL), new int[]{c[0], c[1]});
        }
        List<int[]> out = new ArrayList<>(unique.values());
        out.sort(Comparator.<int[]>comparingLong(c -> (long) c[0] * c[0] + (long) c[1] * c[1])
                .thenComparingInt(c -> c[0]).thenComparingInt(c -> c[1]));
        return out;
    }

    static List<int[]> discAroundOrigin(int radiusRegions) {
        List<int[]> disc = new ArrayList<>();
        long r2 = (long) radiusRegions * radiusRegions;
        for (int dz = -radiusRegions; dz <= radiusRegions; dz++) {
            for (int dx = -radiusRegions; dx <= radiusRegions; dx++) {
                if ((long) dx * dx + (long) dz * dz <= r2) disc.add(new int[]{dx, dz});
            }
        }
        return nearestFirst(disc);
    }

    /** Adapters return {@code PLAINS} / {@code IRIS:ASH}; the editor keys colours by namespaced lowercase ids. */
    static String namespacedBiome(String canonical) {
        String lower = canonical.toLowerCase(Locale.ROOT);
        return lower.indexOf(':') >= 0 ? lower : "minecraft:" + lower;
    }

    public String world() {
        return world;
    }

    public int y() {
        return y;
    }

    public WorldBiomeStore store() {
        return store;
    }

    /** {@code true} when coordinates came from a directory listing rather than blind probing. */
    public boolean listed() {
        return listed;
    }

    /** Region files (or probe coordinates) the survey covers. */
    public int fileTotal() {
        return files.size();
    }

    /** Current pass ({@code 0..FINEST_LEVEL}); {@code LEVELS} once every pass finished. */
    public int level() {
        return level;
    }

    /** Files visited in the current pass. */
    public int cursor() {
        return cursor;
    }

    /** Sampled reads that returned land. */
    public int filesRead() {
        return filesRead;
    }

    public long decodes() {
        return decodes;
    }

    /** {@code true} when the tile cap left region files out. */
    public boolean truncated() {
        return truncated;
    }

    /** Overall progress in [0, 1] across the three passes. */
    public double progress() {
        if (files.isEmpty() || level >= BiomeBinCodec.LEVELS) return 1.0;
        return (level + cursor / (double) files.size()) / BiomeBinCodec.LEVELS;
    }

    public boolean isDone() {
        return level >= BiomeBinCodec.LEVELS && priority.isEmpty();
    }

    /**
     * Replaces the viewport priority with the bins of region-file rectangle
     * {@code [minRx, maxRx] x [minRz, maxRz]}, nearest its centre first. Rectangles wider than
     * {@link #MAX_PRIORITY_BINS} bins are ignored: the coarse passes already cover them.
     */
    public void prioritize(int minRx, int minRz, int maxRx, int maxRz) {
        priority.clear();
        long w = (long) maxRx - minRx + 1;
        long h = (long) maxRz - minRz + 1;
        if (w <= 0 || h <= 0 || w * h > MAX_PRIORITY_BINS) return;
        double cx = (minRx + maxRx) / 2.0;
        double cz = (minRz + maxRz) / 2.0;
        List<int[]> rect = new ArrayList<>();
        for (int rz = minRz; rz <= maxRz; rz++) {
            for (int rx = minRx; rx <= maxRx; rx++) {
                long key = BiomeBinCodec.binKey(rx, rz);
                if (listed ? !fileKeys.contains(key) : missing.contains(key)) continue;
                if (store.levelAt(rx, rz, y) >= BiomeBinCodec.FINEST_LEVEL) continue;
                rect.add(new int[]{rx, rz});
            }
        }
        rect.sort(Comparator.comparingDouble(t -> (t[0] - cx) * (t[0] - cx) + (t[1] - cz) * (t[1] - cz)));
        priority.addAll(rect);
    }

    /**
     * Advances the survey until {@link #DECODES_PER_TICK} chunk decodes or {@link #VISITS_PER_TICK}
     * bin visits were spent.
     *
     * @return the batch, or {@code null} once the survey is done
     */
    public Batch nextBatch() {
        if (isDone()) return null;
        int spent = 0;
        int visits = 0;
        int changed = 0;
        while (spent < DECODES_PER_TICK && visits < VISITS_PER_TICK && !isDone()) {
            int[] f;
            int lvl;
            boolean fromPriority = !priority.isEmpty();
            if (fromPriority) {
                f = priority.peek();
                lvl = store.levelAt(f[0], f[1], y) + 1;
                if (lvl > BiomeBinCodec.FINEST_LEVEL) {
                    priority.poll();
                    continue;
                }
            } else {
                if (cursor >= files.size()) {
                    level++;
                    cursor = 0;
                    continue;
                }
                f = files.get(cursor++);
                lvl = level;
                if (!listed && lvl > 0 && missing.contains(BiomeBinCodec.binKey(f[0], f[1]))) continue;
            }
            visits++;
            int[] result = visit(f[0], f[1], lvl);
            spent += result[0];
            changed += result[1];
            // No progress (no land, or a read that recorded nothing): drop it instead of retrying forever
            if (fromPriority && (result[2] < 0 || store.levelAt(f[0], f[1], y) < lvl)) priority.poll();
        }
        return new Batch(world, y, changed, spent, visits, isDone());
    }

    /** @return {@code [decodes, changed (0/1), levelReached or -1 when the file has no land]} */
    private int[] visit(int rx, int rz, int lvl) {
        long m = mtime.of(rx, rz);
        store.noteMtime(rx, rz, m);
        if (store.levelAt(rx, rz, y) >= lvl) return new int[]{0, 0, lvl};
        int[] idx = BiomeBinCodec.sampleIndices(lvl);
        int cost = idx.length;
        Map<Integer, String> local = sample(rx, rz, idx);
        if (local.isEmpty() && lvl == 0) {
            // Centre chunk not generated: fall through to the 64-sample level for this file
            lvl = 1;
            idx = BiomeBinCodec.sampleIndices(lvl);
            cost += idx.length;
            local = sample(rx, rz, idx);
        }
        decodes += cost;
        if (local.isEmpty()) {
            if (!listed) {
                missing.add(BiomeBinCodec.binKey(rx, rz));
                return new int[]{cost, 0, -1};
            }
        } else {
            filesRead++;
        }
        boolean changed = store.apply(rx, rz, y, lvl, idx, local, m);
        return new int[]{cost, changed ? 1 : 0, lvl};
    }

    private Map<Integer, String> sample(int rx, int rz, int[] idx) {
        Map<Long, String> raw;
        try {
            raw = sampler.sample(rx, rz, idx);
        } catch (RuntimeException e) {
            raw = null;
        }
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<Integer, String> local = new HashMap<>(raw.size() * 2);
        for (Map.Entry<Long, String> e : raw.entrySet()) {
            String biome = e.getValue();
            if (biome == null || biome.isEmpty()) continue;
            long key = e.getKey();
            int cx = (int) (key >> 32);
            int cz = (int) key;
            local.put(((cz & 31) << 5) | (cx & 31), namespacedBiome(biome));
        }
        return local;
    }
}
