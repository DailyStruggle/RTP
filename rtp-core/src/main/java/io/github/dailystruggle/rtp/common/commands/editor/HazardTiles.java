package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.util.*;

/**
 * Live hazard layer of one region in {@link BiomeBinCodec} bins (ADR-104 §4.6).
 *
 * <p>Cell value = {@code FailTypes} ordinal + 1 of the region's learned bad-location memory
 * ({@link MemoryShape#causeAt(int, int)}, chunk coordinates), {@code 0} = not known bad. Bins
 * covering the shape are rescanned round-robin, nearest the shape centre first, and only bins whose
 * bytes changed are reported, so the layer follows scans and teleports as they learn. Pure memory
 * reads; no world access. Not thread-safe; callers serialise access.
 */
public final class HazardTiles {

    /** Bins (32 x 32 chunks) covered per region. */
    static final int MAX_BINS_PER_REGION = 4_096;

    private final MemoryShape<?> shape;
    private final List<int[]> order;
    private final Map<Long, byte[]> current = new HashMap<>();
    private int cursor;
    private boolean firstPassDone;

    HazardTiles(MemoryShape<?> shape, int maxBins) {
        this.shape = Objects.requireNonNull(shape, "shape");
        List<int[]> o = WalkPathTiles.tileOrder(shape);
        this.order = (o.size() > maxBins) ? List.copyOf(o.subList(0, Math.max(0, maxBins))) : List.copyOf(o);
    }

    public static HazardTiles of(MemoryShape<?> shape) {
        return new HazardTiles(shape, MAX_BINS_PER_REGION);
    }

    public int binTotal() {
        return order.size();
    }

    /** {@code true} once every bin was scanned at least once. */
    public boolean firstPassDone() {
        return firstPassDone;
    }

    /** Bytes of bin {@code (rx, rz)}, or {@code null} when it holds no hazard. */
    public byte[] runs(int rx, int rz) {
        return current.get(BiomeBinCodec.binKey(rx, rz));
    }

    /**
     * Rescans up to {@code maxBins} bins.
     *
     * @return {@code [rx, rz]} of bins whose bytes changed (including bins that became empty)
     */
    public List<int[]> nextBatch(int maxBins) {
        if (order.isEmpty()) {
            firstPassDone = true;
            return List.of();
        }
        List<int[]> changed = new ArrayList<>();
        for (int i = 0; i < Math.max(1, maxBins) && i < order.size(); i++) {
            int[] t = order.get(cursor);
            cursor++;
            if (cursor >= order.size()) {
                cursor = 0;
                firstPassDone = true;
            }
            byte[] runs = scan(shape, t[0], t[1]);
            long key = BiomeBinCodec.binKey(t[0], t[1]);
            byte[] old = current.get(key);
            if (runs == null) {
                if (old != null) {
                    current.remove(key);
                    changed.add(t);
                }
            } else if (old == null || !Arrays.equals(old, runs)) {
                current.put(key, runs);
                changed.add(t);
            }
        }
        return changed;
    }

    /** @return the bin's runs, or {@code null} when no chunk in it is known bad */
    static byte[] scan(MemoryShape<?> shape, int rx, int rz) {
        int[] cells = null;
        for (int d = 0; d < BiomeBinCodec.CELLS; d++) {
            int cx = (rx << 5) + BiomeBinCodec.d2x(d);
            int cz = (rz << 5) + BiomeBinCodec.d2z(d);
            int cause;
            try {
                cause = shape.causeAt(cx, cz);
            } catch (RuntimeException e) {
                cause = -1;
            }
            if (cause < 0) continue;
            if (cells == null) cells = new int[BiomeBinCodec.CELLS];
            cells[d] = cause + 1;
        }
        return (cells == null) ? null : BiomeBinCodec.encode(cells);
    }
}
