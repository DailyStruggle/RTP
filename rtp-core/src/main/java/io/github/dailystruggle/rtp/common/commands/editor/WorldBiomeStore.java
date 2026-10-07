package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.RTP;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * World-wide chunk biome store in {@link BiomeBinCodec} form (ADR-104 §4.6).
 *
 * <p>One bin per region file; each bin holds one layer per sample Y, tagged with the detail level
 * reached and the {@code .mca} modification time it was read at. Lookups take the nearest layer
 * within {@link #Y_TOLERANCE} blocks. The same run bytes are persisted, embedded in exports, sent
 * in live tiles and kept by the page. Persisted per world as {@code <plugin>/cache/biomes/<world>.rbs}
 * (gzip); a corrupt or foreign file is logged and ignored (S-004). Methods are thread-safe.
 */
public final class WorldBiomeStore {

    public static final int DEFAULT_Y = 64;
    public static final int Y_TOLERANCE = 16;
    /** Bound per world: 65,536 bins (~6-20 MB of runs) keeps memory and file size fixed. */
    static final int MAX_BINS = 65_536;
    /** Distinct sample Y layers per world. */
    static final int MAX_LAYERS = 4;
    static final String CACHE_DIR = "cache/biomes";
    private static final int MAGIC = 0x52425331; // "RBS1"
    private static final int FORMAT = 1;
    private static final Pattern UNSAFE_FILE_CHARS = Pattern.compile("[^A-Za-z0-9_.\\-]");

    /** One sample-Y layer of a bin: {@code level} is -1 when stale or unread. */
    public record Layer(int y, int level, byte[] runs) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Layer other)) return false;
            return y == other.y && level == other.level && Arrays.equals(runs, other.runs);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(y, level) + Arrays.hashCode(runs);
        }

        @Override
        public String toString() {
            return "Layer[y=" + y + ", level=" + level + ", runs=" + Arrays.toString(runs) + "]";
        }
    }

    /** Snapshot of one bin. */
    public record BinView(int rx, int rz, long mtime, long version, List<Layer> layers) {}

    private static final class Bin {
        final int rx;
        final int rz;
        long mtime = -1L;
        long version;
        final TreeMap<Integer, Layer> layers = new TreeMap<>();

        Bin(int rx, int rz) {
            this.rx = rx;
            this.rz = rz;
        }
    }

    private static final Map<String, WorldBiomeStore> STORES = new ConcurrentHashMap<>();

    private final String world;
    private final Path file;
    private final List<String> palette = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> paletteIndex = new ConcurrentHashMap<>();
    private final Map<Long, Bin> bins = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();
    private volatile long savedVersion;

    WorldBiomeStore(String world, Path file) {
        this.world = Objects.requireNonNull(world, "world");
        this.file = file;
    }

    /** Store of {@code world}, loaded from the cache file on first use. Disk read: call off-tick. */
    public static WorldBiomeStore of(String world) {
        return STORES.computeIfAbsent(world, w -> {
            WorldBiomeStore s = new WorldBiomeStore(w, cacheFile(w));
            s.load();
            return s;
        });
    }

    /** Stores created so far, by world name. */
    public static Map<String, WorldBiomeStore> loaded() {
        return Collections.unmodifiableMap(STORES);
    }

    /** Persists every changed store; logs failures (S-004). Disk write: call off-tick. */
    public static void saveAll() {
        for (WorldBiomeStore s : STORES.values()) s.saveIfChanged();
    }

    static void clearForTests() {
        STORES.clear();
    }

    static Path cacheFile(String world) {
        File dir = (RTP.serverAccessor != null) ? RTP.serverAccessor.getPluginDirectory() : null;
        if (dir == null) return null;
        String safe = UNSAFE_FILE_CHARS.matcher(world).replaceAll("_");
        return dir.toPath().resolve(CACHE_DIR).resolve(safe + ".rbs");
    }

    public String world() {
        return world;
    }

    public long version() {
        return version.get();
    }

    /** Palette snapshot; layer cell value {@code v > 0} is {@code palette.get(v - 1)}. */
    public List<String> palette() {
        return List.copyOf(palette);
    }

    /** Palette index of a namespaced lowercase biome id, adding it when new. */
    public synchronized int paletteIndex(String biome) {
        Integer idx = paletteIndex.get(biome);
        if (idx != null) return idx;
        palette.add(biome);
        int i = palette.size() - 1;
        paletteIndex.put(biome, i);
        return i;
    }

    public int binCount() {
        return bins.size();
    }

    /** Recorded {@code .mca} modification time of the bin, or -1. */
    public long mtime(int rx, int rz) {
        Bin b = bins.get(BiomeBinCodec.binKey(rx, rz));
        if (b == null) return -1L;
        synchronized (b) {
            return b.mtime;
        }
    }

    /** Detail level of the exact {@code y} layer, or -1 when absent or stale. */
    public int levelAt(int rx, int rz, int y) {
        Bin b = bins.get(BiomeBinCodec.binKey(rx, rz));
        if (b == null) return -1;
        synchronized (b) {
            Layer l = b.layers.get(y);
            return (l == null) ? -1 : l.level();
        }
    }

    /** Nearest layer to {@code y} within {@link #Y_TOLERANCE}, or {@code null}. */
    public Layer layer(int rx, int rz, int y) {
        Bin b = bins.get(BiomeBinCodec.binKey(rx, rz));
        if (b == null) return null;
        synchronized (b) {
            return nearest(b.layers, y);
        }
    }

    static Layer nearest(NavigableMap<Integer, Layer> layers, int y) {
        Map.Entry<Integer, Layer> lo = layers.floorEntry(y);
        Map.Entry<Integer, Layer> hi = layers.ceilingEntry(y);
        Map.Entry<Integer, Layer> best = lo;
        if (hi != null && (best == null || hi.getKey() - y < y - best.getKey())) best = hi;
        if (best == null || Math.abs(best.getKey() - y) > Y_TOLERANCE) return null;
        return best.getValue();
    }

    /** Stored sample Y nearest to {@code y} within {@link #Y_TOLERANCE} across all bins, or {@code null}. */
    public Integer nearestStoredY(int y) {
        Integer best = null;
        for (Integer stored : storedYs()) {
            if (Math.abs(stored - y) > Y_TOLERANCE) continue;
            if (best == null || Math.abs(stored - y) < Math.abs(best - y)) best = stored;
        }
        return best;
    }

    /** Distinct sample Ys present in the store. */
    public SortedSet<Integer> storedYs() {
        SortedSet<Integer> ys = new TreeSet<>();
        for (Bin b : bins.values()) {
            synchronized (b) {
                ys.addAll(b.layers.keySet());
            }
        }
        return ys;
    }

    /**
     * Records a sampled read of region file {@code (rx, rz)} at {@code y}.
     *
     * @param level   detail level of {@code indices} ({@link BiomeBinCodec#sampleIndices})
     * @param indices local chunk indices requested ({@code lz * 32 + lx})
     * @param biomes  local index to namespaced lowercase biome; missing = chunk not generated
     * @param mtime   {@code .mca} modification time read before sampling, or -1
     * @return {@code true} when the layer bytes changed
     */
    public boolean apply(int rx, int rz, int y, int level, int[] indices, Map<Integer, String> biomes, long mtime) {
        long key = BiomeBinCodec.binKey(rx, rz);
        Bin b = bins.get(key);
        if (b == null) {
            if (bins.size() >= MAX_BINS) return false;
            b = bins.computeIfAbsent(key, k -> new Bin(rx, rz));
        }
        int[] values = new int[indices.length];
        for (int i = 0; i < indices.length; i++) {
            String biome = biomes.get(indices[i]);
            values[i] = (biome == null) ? 0 : paletteIndex(biome) + 1;
        }
        synchronized (b) {
            if (!b.layers.containsKey(y) && b.layers.size() >= MAX_LAYERS) return false;
            Layer old = b.layers.get(y);
            int[] cells = (old == null) ? new int[BiomeBinCodec.CELLS] : BiomeBinCodec.decode(old.runs());
            for (int i = 0; i < indices.length; i++) {
                int lx = indices[i] & 31;
                int lz = indices[i] >> 5;
                if (values[i] != 0) {
                    BiomeBinCodec.fillStretch(cells, level, lx, lz, values[i]);
                } else if (level == BiomeBinCodec.FINEST_LEVEL) {
                    // An ungenerated chunk is only known as such at full detail
                    cells[BiomeBinCodec.xz2d(lx, lz)] = 0;
                }
            }
            byte[] runs = BiomeBinCodec.encode(cells);
            int newLevel = Math.max(level, old == null ? -1 : old.level());
            boolean changed = old == null || !Arrays.equals(old.runs(), runs);
            b.layers.put(y, new Layer(y, newLevel, runs));
            if (mtime >= 0) b.mtime = mtime;
            if (changed || old.level() != newLevel) b.version = version.incrementAndGet();
            return changed;
        }
    }

    /**
     * Notes the current {@code .mca} modification time. When it differs from the recorded one, every
     * layer keeps its bytes (still drawn) but drops to at most level {@code FINEST - 1}, so the
     * finest pass re-reads the file without coarse passes overwriting detail.
     *
     * @return {@code true} when the bin was marked stale
     */
    public boolean noteMtime(int rx, int rz, long mtime) {
        if (mtime < 0) return false;
        Bin b = bins.get(BiomeBinCodec.binKey(rx, rz));
        if (b == null) return false;
        synchronized (b) {
            if (b.mtime == mtime) return false;
            boolean stale = b.mtime >= 0;
            b.mtime = mtime;
            if (!stale) return false;
            for (Map.Entry<Integer, Layer> e : b.layers.entrySet()) {
                Layer l = e.getValue();
                if (l.level() >= BiomeBinCodec.FINEST_LEVEL) {
                    e.setValue(new Layer(l.y(), BiomeBinCodec.FINEST_LEVEL - 1, l.runs()));
                }
            }
            return true;
        }
    }

    /** Snapshots of bins changed after {@code sinceVersion}. */
    public List<BinView> changedSince(long sinceVersion) {
        List<BinView> out = new ArrayList<>();
        for (Bin b : bins.values()) {
            synchronized (b) {
                if (b.version > sinceVersion) out.add(view(b));
            }
        }
        return out;
    }

    /** Snapshot of one bin, or {@code null}. */
    public BinView bin(int rx, int rz) {
        Bin b = bins.get(BiomeBinCodec.binKey(rx, rz));
        if (b == null) return null;
        synchronized (b) {
            return view(b);
        }
    }

    /** Every bin, nearest region file (0, 0) first. */
    public List<BinView> binsNearestFirst() {
        List<Long> keys = new ArrayList<>(bins.keySet());
        Collections.sort(keys);
        List<BinView> out = new ArrayList<>(keys.size());
        for (Long k : keys) {
            Bin b = bins.get(k);
            if (b == null) continue;
            synchronized (b) {
                out.add(view(b));
            }
        }
        return out;
    }

    private static BinView view(Bin b) {
        return new BinView(b.rx, b.rz, b.mtime, b.version, List.copyOf(b.layers.values()));
    }

    /** Collapses {@code runs} to one run of its most frequent known value (level-0 view). */
    public static byte[] coarsen(byte[] runs) {
        int[] counts = BiomeBinCodec.histogram(runs, 1);
        int best = 0;
        int bestCount = 0;
        for (int v = 1; v < counts.length; v++) {
            if (counts[v] > bestCount) {
                best = v;
                bestCount = counts[v];
            }
        }
        int[] cells = new int[BiomeBinCodec.CELLS];
        Arrays.fill(cells, best);
        return BiomeBinCodec.encode(cells);
    }

    void saveIfChanged() {
        long v = version.get();
        if (file == null || v == savedVersion) return;
        try {
            save(file);
            savedVersion = v;
        } catch (IOException e) {
            RTP.log(Level.WARNING, "[editor] failed to save biome store of world '" + world + "' to " + file + ": " + e.getMessage(), e);
        }
    }

    void save(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        List<String> pal = palette();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeUTF(world);
            out.writeInt(pal.size());
            for (String p : pal) out.writeUTF(p);
            List<BinView> all = binsNearestFirst();
            out.writeInt(all.size());
            for (BinView b : all) {
                out.writeInt(b.rx());
                out.writeInt(b.rz());
                out.writeLong(b.mtime());
                out.writeByte(b.layers().size());
                for (Layer l : b.layers()) {
                    out.writeInt(l.y());
                    out.writeByte(l.level());
                    out.writeShort(l.runs().length);
                    out.write(l.runs());
                }
            }
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            load(file);
            savedVersion = version.get();
        } catch (IOException | RuntimeException e) {
            RTP.log(Level.WARNING, "[editor] ignoring unreadable biome store " + file + " (world '" + world + "'): " + e.getMessage(), e);
            bins.clear();
        }
    }

    void load(Path source) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(source))))) {
            if (in.readInt() != MAGIC) throw new IOException("not a biome store");
            int format = in.readInt();
            if (format != FORMAT) throw new IOException("unsupported biome store format " + format);
            String storedWorld = in.readUTF();
            if (!world.equals(storedWorld)) throw new IOException("store belongs to world '" + storedWorld + "'");
            int palSize = in.readInt();
            if (palSize < 0 || palSize > 65_535) throw new IOException("bad palette size " + palSize);
            int[] remap = new int[palSize + 1];
            for (int i = 0; i < palSize; i++) remap[i + 1] = paletteIndex(in.readUTF()) + 1;
            int count = in.readInt();
            if (count < 0 || count > MAX_BINS) throw new IOException("bad bin count " + count);
            for (int i = 0; i < count; i++) {
                int rx = in.readInt();
                int rz = in.readInt();
                long mtime = in.readLong();
                int layerCount = in.readUnsignedByte();
                Bin b = new Bin(rx, rz);
                b.mtime = mtime;
                for (int j = 0; j < layerCount; j++) {
                    int y = in.readInt();
                    int level = in.readByte();
                    byte[] runs = new byte[in.readUnsignedShort()];
                    in.readFully(runs);
                    int[] cells = BiomeBinCodec.decode(runs);
                    for (int c = 0; c < cells.length; c++) {
                        int v = cells[c];
                        if (v < 0 || v >= remap.length) throw new IOException("palette index out of range");
                        cells[c] = remap[v];
                    }
                    if (b.layers.size() < MAX_LAYERS) {
                        b.layers.put(y, new Layer(y, Math.min(level, BiomeBinCodec.FINEST_LEVEL), BiomeBinCodec.encode(cells)));
                    }
                }
                b.version = version.incrementAndGet();
                bins.put(BiomeBinCodec.binKey(rx, rz), b);
            }
        }
    }
}
