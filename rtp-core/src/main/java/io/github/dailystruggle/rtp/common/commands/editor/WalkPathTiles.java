package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.util.*;

/**
 * Full-resolution walk path of one region, produced as spatial tiles over time (ADR-104 §4.6).
 *
 * <p>A tile is a 32 x 32 chunk square aligned with region files. For every chunk it holds that
 * chunk's 1D curve location, or {@code -1} when the chunk is outside the shape; the page sorts the
 * cells of the tiles it shows by location and joins neighbours, so the path stays exact at any
 * zoom while the browser only holds the tiles in view. Tiles are computed with
 * {@link MemoryShape#xzToLocation(long, long)} (pure math, no world access) nearest the shape
 * centre first. Not thread-safe; callers serialise access.
 */
public final class WalkPathTiles {

    /** Chunks per tile edge: one region file. */
    public static final int TILE_CHUNKS = 32;
    /** Disk bound: 4,096 tiles = 4.2M chunks (~37 MB of tile files) per region. */
    static final int MAX_TILES_PER_REGION = 4_096;
    /** Chunks added around the sampled curve bounds so the outermost ring is never clipped. */
    static final int BOUNDS_MARGIN_CHUNKS = 2;

    /** One tile: {@code locs[lz * 32 + lx]} is the curve location of chunk (tx*32+lx, tz*32+lz). */
    public record Tile(int tx, int tz, int cells, long[] locs) {}

    /** Tiles computed by one {@link #nextBatch} call plus progress counters. */
    public record Batch(String region, List<Tile> tiles, int tilesDone, int tilesTotal, boolean done) {}

    private final String region;
    private final MemoryShape<?> shape;
    private final Deque<int[]> pending;
    private final int tilesTotal;
    private final boolean truncated;
    private int tilesDone;

    WalkPathTiles(String region, MemoryShape<?> shape, int maxTiles) {
        this.region = Objects.requireNonNull(region, "region");
        this.shape = Objects.requireNonNull(shape, "shape");
        List<int[]> order = tileOrder(shape);
        this.truncated = order.size() > maxTiles;
        if (truncated) order = order.subList(0, Math.max(0, maxTiles));
        this.pending = new ArrayDeque<>(order);
        this.tilesTotal = pending.size();
    }

    public static WalkPathTiles of(String region, MemoryShape<?> shape) {
        return new WalkPathTiles(region, shape, MAX_TILES_PER_REGION);
    }

    /** Tiles overlapping the shape's chunk bounds, nearest the bounds centre first. */
    static List<int[]> tileOrder(MemoryShape<?> shape) {
        int[] b = PregenBiomeExtractor.shapeChunkBounds(shape, 4096);
        if (b == null) return List.of();
        int minTx = Math.floorDiv(b[0] - BOUNDS_MARGIN_CHUNKS, TILE_CHUNKS);
        int minTz = Math.floorDiv(b[1] - BOUNDS_MARGIN_CHUNKS, TILE_CHUNKS);
        int maxTx = Math.floorDiv(b[2] + BOUNDS_MARGIN_CHUNKS, TILE_CHUNKS);
        int maxTz = Math.floorDiv(b[3] + BOUNDS_MARGIN_CHUNKS, TILE_CHUNKS);
        double ctx = (b[0] + b[2]) / 2.0 / TILE_CHUNKS;
        double ctz = (b[1] + b[3]) / 2.0 / TILE_CHUNKS;
        List<int[]> out = new ArrayList<>();
        for (int tz = minTz; tz <= maxTz; tz++) {
            for (int tx = minTx; tx <= maxTx; tx++) out.add(new int[]{tx, tz});
        }
        out.sort(Comparator.<int[]>comparingDouble(t -> {
            double dx = t[0] + 0.5 - ctx;
            double dz = t[1] + 0.5 - ctz;
            return dx * dx + dz * dz;
        }).thenComparingInt(t -> t[0]).thenComparingInt(t -> t[1]));
        return out;
    }

    public String region() {
        return region;
    }

    public int tilesDone() {
        return tilesDone;
    }

    public int tilesTotal() {
        return tilesTotal;
    }

    /** {@code true} when the shape needs more than {@link #MAX_TILES_PER_REGION} tiles. */
    public boolean truncated() {
        return truncated;
    }

    public boolean isDone() {
        return pending.isEmpty();
    }

    /**
     * Scans up to {@code maxTiles} tiles; scanned tiles with no chunk on the curve yield nothing.
     *
     * @return the batch, or {@code null} once every tile was computed
     */
    public Batch nextBatch(int maxTiles) {
        if (isDone()) return null;
        long range = shape.getRange();
        List<Tile> tiles = new ArrayList<>();
        int scanned = 0;
        while (!pending.isEmpty() && scanned < Math.max(1, maxTiles)) {
            int[] t = pending.poll();
            scanned++;
            tilesDone++;
            Tile tile = computeTile(shape, range, t[0], t[1]);
            if (tile != null) tiles.add(tile);
        }
        return new Batch(region, tiles, tilesDone, tilesTotal, isDone());
    }

    /**
     * A chunk belongs to the curve when its location is in range and maps back to the chunk (or a
     * direct neighbour: area-scaled curves round, which must not punch holes in the path).
     */
    static Tile computeTile(MemoryShape<?> shape, long range, int tx, int tz) {
        long[] locs = new long[TILE_CHUNKS * TILE_CHUNKS];
        int cells = 0;
        for (int lz = 0; lz < TILE_CHUNKS; lz++) {
            for (int lx = 0; lx < TILE_CHUNKS; lx++) {
                int cx = tx * TILE_CHUNKS + lx;
                int cz = tz * TILE_CHUNKS + lz;
                long loc;
                try {
                    loc = shape.xzToLocation(cx, cz);
                } catch (RuntimeException e) {
                    loc = -1L;
                }
                if (loc >= 0 && loc < range) {
                    int[] back = shape.locationToXZ(loc);
                    if (back == null || back.length < 2
                            || Math.abs(back[0] - cx) > 1 || Math.abs(back[1] - cz) > 1) {
                        loc = -1L;
                    }
                } else {
                    loc = -1L;
                }
                locs[lz * TILE_CHUNKS + lx] = loc;
                if (loc >= 0) cells++;
            }
        }
        return (cells == 0) ? null : new Tile(tx, tz, cells, locs);
    }
}
