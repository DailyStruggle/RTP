package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * Abstract base class for memory-backed shapes storing bad and biome locations.
 * Uses Archimedean spiral 1D mapping for efficient spatial lookups.
 *
 * @param <E> enum type for configuration values
 */
public abstract class MemoryShape<E extends Enum<E>> extends Shape<E> {
  /**
   * Coalescing distance applied when new marks are folded into the learned bad-run array.
   *
   * <p>Sourced from the owning region's {@code spatialResolution} setting: it describes how
   * compactly that region stores its learned state, not the shape's geometry - it has no effect on
   * {@link #xzToLocation(long, long)}, {@link #locationToXZ(long)} or {@link #getRange()}. It is
   * therefore deliberately absent from the region cache key (see
   * {@code RegionCacheKey}): a run array coalesced at a coarser resolution stays a valid, merely
   * conservative, superset when read back under a finer one, so changing the setting does not
   * invalidate persisted data.
   *
   * <p>Encapsulated rather than public because the value has exactly one source (region config)
   * and several consumers; write it through {@link #setSpatialResolution(long)} so the assignment
   * sites stay auditable.
   */
  private volatile long spatialResolution = 1L;

  protected volatile long[] badKeysCache = new long[0];
  protected volatile long[] badPrefixSumsCache = new long[0];
  /**
   * Per-run rejection cause, aligned 1:1 with {@link #badKeysCache} (one
   * {@link LocationGenerator.FailTypes} ordinal, stored as a byte, per coalesced
   * bad-location run). When adjacent runs coalesce during rebuild the first run's
   * cause is kept (first-cause-wins; small-scale information loss is acceptable).
   * Legacy {@code .bin} files and untagged callers default to {@code misc}.
   */
  protected volatile byte[] badCauseCache = new byte[0];
  /**
   * Per-run expiration timestamp in unix epoch seconds, aligned 1:1 with {@link #badKeysCache}.
   * Values <= 0 indicate static / permanent retention (infinite TTL).
   */
  protected volatile long[] badExpiryCache = new long[0];

  /**
   * Sorted dual-arrays for the probationary tier (ADR-079). Holds expired segments
   * where {@code 1 * TTL <= now < 2 * TTL}. Bypasses active candidate avoidance,
   * but restored in O(log M) if candidate verification re-rejects them.
   */
  protected volatile long[] probationKeysCache = new long[0];
  protected volatile long[] probationPrefixSumsCache = new long[0];
  protected volatile byte[] probationCauseCache = new byte[0];
  protected volatile long[] probationExpiryCache = new long[0];

  /** {@code FailTypes.misc} ordinal as a byte: the default / unknown cause. */
  protected static final byte MISC_CAUSE = (byte) LocationGenerator.FailTypes.misc.ordinal();

  /**
   * First int of a {@code .bin} payload written with the cause-tagged format.
   * Distinguishable from a legacy file whose first int is the (small, positive)
   * world-name byte length. Spells "RTP1" big-endian.
   */
  private static final int BIN_MAGIC = 0x52545031;
  /**
   * Current cause-tagged {@code .bin} format version (1 == legacy, 2 == cause-tagged, 3 ==
   * ttl/epoch-tagged, 4 == unified biome run stream).
   *
   * <p>Version 4 replaces the per-biome biome sections - one {@code key} + {@code width delta} pair
   * per run under a repeated name header - with a single name table plus one ascending run stream
   * of {@code (key delta, width, biome id)}, each LEB128. That is the on-disk form of the blocked
   * union held in memory, so a load builds {@link #biomeUnion} directly and no per-biome run table
   * has to exist to receive it. Deltas shrink with run spacing rather than with the key domain, so
   * the encoding does not degrade as the world radius grows.
   */
  private static final int BIN_VERSION = 4;
  /**
   * Cross-biome union of the recorded biome runs, blocked so that a run costs 10 bytes resident
   * instead of the 24 a flat {@code long} key + {@code long} prefix sum + {@code short} id would.
   *
   * <p>Published as a single immutable holder: the columns are only consistent with each other,
   * so six independent volatiles would let a reader mix generations.
   *
   * @see BiomeUnionTable
   */
  protected volatile BiomeUnionTable biomeUnion = BiomeUnionTable.EMPTY;


  /**
   * Immutable blocked run table: the cross-biome union of recorded biome runs.
   *
   * <p>Runs are grouped into blocks of at most {@link #BLOCK_SIZE}. Each block keeps one absolute
   * {@code long} key and one absolute {@code long} prefix sum; every run inside it is an
   * {@code int} offset from those bases. The absolute magnitude that forces a {@code long} is
   * therefore paid once per block instead of once per run: 10 bytes per run against 24, with the
   * bases amortizing to 0.02 bytes per run.
   *
   * <p>Overflow is structurally impossible rather than bounded by a radius assumption: a block is
   * closed early whenever the next run's offset from either base would exceed
   * {@link Integer#MAX_VALUE}, so no world border or {@code spatialResolution} can make it wrap.
   * A side effect is that no column is ever a G1 humongous allocation - each block array is at
   * most 4 KiB - which a flat {@code long[]} past 131,072 runs was on every rebuild.
   *
   * <p>Lookup stays {@code log2(n)}: one search over the block bases plus one inside the located
   * block ({@code log2(n / BLOCK_SIZE) + log2(BLOCK_SIZE)}).
   */
  public static final class BiomeUnionTable {
    /** Runs per block. Keeps each block's {@code int[]} at 4 KiB. */
    private static final int BLOCK_SIZE = 1024;

    /** The empty table, published before the first rebuild. */
    static final BiomeUnionTable EMPTY =
        new BiomeUnionTable(
            new long[0], new long[0], new int[0][], new int[0][], new short[0][], new int[0], 0,
            new String[0]);

    /** Absolute run key of each block's first run; ascending. */
    private final long[] blockBaseKey;

    /** Absolute prefix sum of each block's first run; ascending. */
    private final long[] blockBaseSum;

    private final int[][] keyOffsets;
    private final int[][] sumOffsets;
    private final short[][] ids;

    /** Global run index of each block's first run. */
    private final int[] blockStart;

    private final int runCount;

    /** Canonical biome names, indexed by the values in {@link #ids}. */
    private final String[] names;

    /** Blocks this build had to allocate; the rest were shared with the previous table. */
    private int freshBlocks;

    /** Lazily built per-biome index views, one slot per id in {@link #names}. */
    private volatile BiomeView[] views;

    private BiomeUnionTable(
        long[] blockBaseKey,
        long[] blockBaseSum,
        int[][] keyOffsets,
        int[][] sumOffsets,
        short[][] ids,
        int[] blockStart,
        int runCount,
        String[] names) {
      this.blockBaseKey = blockBaseKey;
      this.blockBaseSum = blockBaseSum;
      this.keyOffsets = keyOffsets;
      this.sumOffsets = sumOffsets;
      this.ids = ids;
      this.blockStart = blockStart;
      this.runCount = runCount;
      this.names = names;
    }

    /**
     * Builds a table from the merge's key/width/id columns.
     *
     * @param keys run start keys, ascending
     * @param widths run widths, parallel to {@code keys}
     * @param runIds biome slot of each run, parallel to {@code keys}
     * @param count number of live entries in the columns
     * @param names canonical biome names indexed by {@code runIds}
     * @param prev previously published table to share unchanged blocks with, or {@code null}
     * @return an immutable blocked table
     */
    static BiomeUnionTable build(
        long[] keys, long[] widths, short[] runIds, int count, String[] names,
        BiomeUnionTable prev) {
      if (count <= 0) return EMPTY;

      // An early close on offset overflow can produce more blocks than count / BLOCK_SIZE, so the
      // block-indexed arrays grow rather than being sized from the run count alone.
      int maxBlocks = count / BLOCK_SIZE + 2;
      long[] baseKeys = new long[maxBlocks];
      long[] baseSums = new long[maxBlocks];
      int[] starts = new int[maxBlocks + 1];
      int[][] keyCols = new int[maxBlocks][];
      int[][] sumCols = new int[maxBlocks][];
      short[][] idCols = new short[maxBlocks][];

      int[] keyScratch = new int[BLOCK_SIZE];
      int[] sumScratch = new int[BLOCK_SIZE];
      short[] idScratch = new short[BLOCK_SIZE];

      int blocks = 0;
      int inBlock = 0;
      int fresh = 0;
      long runningSum = 0L;
      long baseKey = 0L;
      long baseSum = 0L;

      for (int i = 0; i < count; i++) {
        runningSum += widths[i];
        if (inBlock > 0) {
          // Close the block early rather than let an offset wrap. A pathological gap costs one
          // extra block, never a wrong key.
          boolean overflow =
              inBlock == BLOCK_SIZE
                  || keys[i] - baseKey > Integer.MAX_VALUE
                  || runningSum - baseSum > Integer.MAX_VALUE;
          if (overflow) {
            if (blocks == baseKeys.length) {
              int grown = blocks << 1;
              baseKeys = Arrays.copyOf(baseKeys, grown);
              baseSums = Arrays.copyOf(baseSums, grown);
              starts = Arrays.copyOf(starts, grown + 1);
              keyCols = Arrays.copyOf(keyCols, grown);
              sumCols = Arrays.copyOf(sumCols, grown);
              idCols = Arrays.copyOf(idCols, grown);
            }
            boolean shared =
                unchanged(
                    prev, blocks, starts[blocks], inBlock, baseKey, baseSum, keyScratch, sumScratch,
                    idScratch);
            if (shared) {
              keyCols[blocks] = prev.keyOffsets[blocks];
              sumCols[blocks] = prev.sumOffsets[blocks];
              idCols[blocks] = prev.ids[blocks];
            } else {
              keyCols[blocks] = Arrays.copyOf(keyScratch, inBlock);
              sumCols[blocks] = Arrays.copyOf(sumScratch, inBlock);
              idCols[blocks] = Arrays.copyOf(idScratch, inBlock);
              fresh++;
            }
            baseKeys[blocks] = baseKey;
            baseSums[blocks] = baseSum;
            blocks++;
            inBlock = 0;
          }
        }
        if (inBlock == 0) {
          baseKey = keys[i];
          baseSum = runningSum;
          starts[blocks] = i;
        }
        keyScratch[inBlock] = (int) (keys[i] - baseKey);
        sumScratch[inBlock] = (int) (runningSum - baseSum);
        idScratch[inBlock] = runIds[i];
        inBlock++;
      }
      if (blocks == baseKeys.length) {
        int grown = blocks + 1;
        baseKeys = Arrays.copyOf(baseKeys, grown);
        baseSums = Arrays.copyOf(baseSums, grown);
        starts = Arrays.copyOf(starts, grown + 1);
        keyCols = Arrays.copyOf(keyCols, grown);
        sumCols = Arrays.copyOf(sumCols, grown);
        idCols = Arrays.copyOf(idCols, grown);
      }
      if (unchanged(
          prev, blocks, starts[blocks], inBlock, baseKey, baseSum, keyScratch, sumScratch,
          idScratch)) {
        keyCols[blocks] = prev.keyOffsets[blocks];
        sumCols[blocks] = prev.sumOffsets[blocks];
        idCols[blocks] = prev.ids[blocks];
      } else {
        keyCols[blocks] = Arrays.copyOf(keyScratch, inBlock);
        sumCols[blocks] = Arrays.copyOf(sumScratch, inBlock);
        idCols[blocks] = Arrays.copyOf(idScratch, inBlock);
        fresh++;
      }
      baseKeys[blocks] = baseKey;
      baseSums[blocks] = baseSum;
      blocks++;
      starts[blocks] = count;

      BiomeUnionTable built =
          new BiomeUnionTable(
              Arrays.copyOf(baseKeys, blocks),
              Arrays.copyOf(baseSums, blocks),
              Arrays.copyOf(keyCols, blocks),
              Arrays.copyOf(sumCols, blocks),
              Arrays.copyOf(idCols, blocks),
              Arrays.copyOf(starts, blocks + 1),
              count,
              (prev != null && Arrays.equals(prev.names, names)) ? prev.names : names);
      built.freshBlocks = fresh;
      return built;
    }

    /**
     * Whether a block being closed is byte-identical to the same block of the previous table.
     *
     * <p>This is what makes the rebuild copy-on-write per block: closed blocks are immutable and
     * already published, so an unchanged one is shared by reference instead of reallocated. Under
     * the radius-append growth of ADR-001 only the tail block changes, so a rebuild allocates a
     * bounded number of columns rather than one pair per recorded run.
     *
     * @param prev previously published table, or {@code null}
     * @param block block index being closed
     * @param start global run index of the block's first run
     * @param len runs in the block
     * @param baseKey the block's absolute base key
     * @param baseSum the block's absolute base prefix sum
     * @param keyScratch staged key offsets
     * @param sumScratch staged prefix-sum offsets
     * @param idScratch staged biome ids
     * @return {@code true} when the previous block's arrays may be reused verbatim
     */
    private static boolean unchanged(
        BiomeUnionTable prev,
        int block,
        int start,
        int len,
        long baseKey,
        long baseSum,
        int[] keyScratch,
        int[] sumScratch,
        short[] idScratch) {
      if (prev == null || block >= prev.blockBaseKey.length) return false;
      if (prev.blockStart[block] != start || prev.blockStart[block + 1] - start != len) return false;
      if (prev.blockBaseKey[block] != baseKey || prev.blockBaseSum[block] != baseSum) return false;
      return Arrays.equals(prev.keyOffsets[block], 0, len, keyScratch, 0, len)
          && Arrays.equals(prev.sumOffsets[block], 0, len, sumScratch, 0, len)
          && Arrays.equals(prev.ids[block], 0, len, idScratch, 0, len);
    }

    /**
     * @return blocks allocated by the build that produced this table; the remainder are shared by
     *     reference with the table it superseded
     */
    int freshBlockCount() {
      return freshBlocks;
    }

    /**
     * @return number of blocks in the table
     */
    int blockCount() {
      return blockBaseKey.length;
    }

    /**
     * @param block block index
     * @return the block's key-offset column, by reference, for identity assertions
     */
    int[] keyBlock(int block) {
      return keyOffsets[block];
    }

    /**
     * @return number of union runs
     */
    int runCount() {
      return runCount;
    }

    /**
     * @param canonical canonical biome name
     * @return the biome's id, or {@code -1} when the table holds no run for it
     */
    int idOf(String canonical) {
      for (int i = 0; i < names.length; i++) {
        if (names[i].equals(canonical)) return i;
      }
      return -1;
    }

    /**
     * Per-biome index view, built on first request and cached for this table's lifetime.
     *
     * <p>This is what lets the union serve per-biome extents, so a second un-clipped per-biome run
     * table is not needed to answer {@code biomeWidth} / {@code biomeDensity}. A view is
     * {@code int[]} run indices plus that biome's own cumulative widths - 12 bytes per run, and
     * only for biomes actually queried - against 16 bytes per run held eagerly for every biome.
     *
     * <p>Extents are those of the union, i.e. after the merge's last-observation-wins clipping, so
     * a cell claimed by two biomes counts towards exactly the one {@link #biomeAt} reports.
     */
    public static final class BiomeView {
      private final BiomeUnionTable table;
      private final int[] runs;
      private final long[] cum;

      private BiomeView(BiomeUnionTable table, int[] runs, long[] cum) {
        this.table = table;
        this.runs = runs;
        this.cum = cum;
      }

      /**
       * @return runs attributed to the biome
       */
      public int length() {
        return runs.length;
      }

      /**
       * @param k index within the view
       * @return absolute run start key
       */
      public long keyAt(int k) {
        return table.keyAt(runs[k]);
      }

      /**
       * @param k index within the view
       * @return cumulative width of the biome's runs through {@code k}
       */
      public long sumAt(int k) {
        return cum[k];
      }

      /**
       * @param k index within the view
       * @return width of the run at {@code k}
       */
      public long widthAt(int k) {
        return cum[k] - ((k > 0) ? cum[k - 1] : 0L);
      }

      /**
       * @return total width attributed to the biome, in cells
       */
      public long totalWidth() {
        return (runs.length == 0) ? 0L : cum[runs.length - 1];
      }

      /**
       * @param location exclusive upper bound in the 1D domain
       * @return attributed width at 1D indices strictly below {@code location}
       */
      public long widthBefore(long location) {
        if (location <= 0L || runs.length == 0) return 0L;
        int floor = floorRun(location);
        if (floor < 0) return 0L;
        long before = (floor > 0) ? cum[floor - 1] : 0L;
        // Partial overlap: count only the head of the run that straddles the bound.
        long overlap = Math.min(widthAt(floor), location - keyAt(floor));
        return before + Math.max(0L, overlap);
      }

      /** Last view run whose key is {@code <= location}, or {@code -1}. */
      private int floorRun(long location) {
        int lo = 0;
        int hi = runs.length - 1;
        int res = -1;
        while (lo <= hi) {
          int mid = (lo + hi) >>> 1;
          if (keyAt(mid) <= location) {
            res = mid;
            lo = mid + 1;
          } else {
            hi = mid - 1;
          }
        }
        return res;
      }
    }

    /**
     * View for one biome, built once per table and then cached. The table is immutable, so a lost
     * race only rebuilds an identical view.
     *
     * @param id biome id, as returned by {@link #idOf(String)}
     * @return the view, or {@code null} for an unknown id
     */
    BiomeView viewOf(int id) {
      if (id < 0 || id >= names.length) return null;
      BiomeView[] local = views;
      if (local != null && local[id] != null) return local[id];
      synchronized (this) {
        local = views;
        if (local == null) local = new BiomeView[names.length];
        if (local[id] == null) local[id] = buildView(id);
        views = local;
        return local[id];
      }
    }

    /** One pass over the union, collecting the runs of a single biome. */
    private BiomeView buildView(int id) {
      int n = 0;
      for (int i = 0; i < runCount; i++) {
        if (idAt(i) == id) n++;
      }
      int[] viewRuns = new int[n];
      long[] cum = new long[n];
      long acc = 0L;
      int at = 0;
      for (int i = 0; i < runCount; i++) {
        if (idAt(i) != id) continue;
        acc += sumAt(i) - ((i > 0) ? sumAt(i - 1) : 0L);
        viewRuns[at] = i;
        cum[at] = acc;
        at++;
      }
      return new BiomeView(this, viewRuns, cum);
    }

    /**
     * @return canonical biome names indexed by {@link #idAt(int)}
     */
    String[] names() {
      return names;
    }

    /**
     * @param run global run index
     * @return absolute run start key
     */
    long keyAt(int run) {
      int b = blockOf(run);
      return blockBaseKey[b] + keyOffsets[b][run - blockStart[b]];
    }

    /**
     * @param run global run index
     * @return absolute prefix sum of run widths through {@code run}
     */
    long sumAt(int run) {
      int b = blockOf(run);
      return blockBaseSum[b] + sumOffsets[b][run - blockStart[b]];
    }

    /**
     * @param run global run index
     * @return biome slot owning the run
     */
    int idAt(int run) {
      int b = blockOf(run);
      return ids[b][run - blockStart[b]];
    }

    /**
     * @return total recorded width, in cells
     */
    long totalWidth() {
      return runCount == 0 ? 0L : sumAt(runCount - 1);
    }

    /**
     * Index of the run containing {@code location}, or {@code -1} for a gap or out-of-range value.
     *
     * @param location the 1D index to locate
     * @return containing run index, or {@code -1}
     */
    int runContaining(long location) {
      if (runCount == 0) return -1;
      int b = floorBlock(location);
      if (b < 0) return -1;
      int[] offsets = keyOffsets[b];
      long diff = location - blockBaseKey[b];
      int local =
          (diff > Integer.MAX_VALUE) ? offsets.length - 1 : floorOffset(offsets, (int) diff);
      if (local < 0) return -1;
      int run = blockStart[b] + local;
      long key = keyAt(run);
      if (key == location) return run;
      long width = sumAt(run) - (run > 0 ? sumAt(run - 1) : 0L);
      return (location < key + width) ? run : -1;
    }

    /** Block owning a global run index; blocks are variable-length, so this is a search. */
    private int blockOf(int run) {
      int lo = 0;
      int hi = blockStart.length - 2;
      while (lo < hi) {
        int mid = (lo + hi + 1) >>> 1;
        if (blockStart[mid] <= run) lo = mid;
        else hi = mid - 1;
      }
      return lo;
    }

    /** Last block whose base key is {@code <= location}, or {@code -1}. */
    private int floorBlock(long location) {
      int idx = Arrays.binarySearch(blockBaseKey, location);
      if (idx >= 0) return idx;
      return -(idx + 1) - 1;
    }

    /** Last offset {@code <= target} within a block, or {@code -1}. */
    private static int floorOffset(int[] offsets, int target) {
      int idx = Arrays.binarySearch(offsets, target);
      if (idx >= 0) return idx;
      return -(idx + 1) - 1;
    }
  }

  /**
   * Monotone counter bumped every time {@link #biomeUnion} is replaced. Lets a caller that gathers
   * per-biome run views (the selection-path draw in {@code PregenTask}) hold them across attempts
   * and re-gather only when they actually changed, instead of re-reading and re-deriving them once
   * per attempt.
   *
   * <p>Not final: {@link #clone} hands the copy a fresh counter, because the clone starts with
   * empty tables and must not appear unchanged to a cache gathered against the original.
   */
  private AtomicLong biomeTableVersion = new AtomicLong();

  protected volatile boolean badLocationsDirty = true;
  protected volatile boolean biomeLocationsDirty = true;
  protected final java.util.concurrent.atomic.AtomicBoolean isRebuilding =
      new java.util.concurrent.atomic.AtomicBoolean(false);
  protected final java.util.concurrent.locks.ReentrantLock writeLock = new ReentrantLock();
  protected final AtomicLong scanStride = new AtomicLong(-1L);
  private volatile CompletableFuture<Void> loadFuture = CompletableFuture.completedFuture(null);
  private final AtomicLong totalBadCount = new AtomicLong(0L);
  private final AtomicLong totalBiomeCount = new AtomicLong(0L);

  protected volatile ConcurrentHashMap<Long, Long> rebuildingBadLocations = null;

  protected final java.util.concurrent.atomic.AtomicReference<
          java.util.concurrent.ConcurrentHashMap<Long, Long>>
      pendingBadLocations =
          new java.util.concurrent.atomic.AtomicReference<>(
              new java.util.concurrent.ConcurrentHashMap<>());
  protected final java.util.concurrent.atomic.AtomicReference<
          java.util.concurrent.ConcurrentHashMap<
              String, java.util.concurrent.ConcurrentHashMap<Long, Long>>>
      pendingBiomeLocations =
          new java.util.concurrent.atomic.AtomicReference<>(
              new java.util.concurrent.ConcurrentHashMap<>());

  protected final java.util.concurrent.atomic.AtomicReference<
          java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<Long, Boolean>>>
      pendingBiomeRemovals =
          new java.util.concurrent.atomic.AtomicReference<>(
              new java.util.concurrent.ConcurrentHashMap<>());

  /**
   * RNG source used by {@link #rand()} and all subclass overrides.
   *
   * <p>Defaults to {@link ThreadLocalRandom#current()} at call time, giving production behaviour
   * identical to before this field was introduced. Tests can inject a seeded {@link Random} via
   * {@link #setRng(Random)} to make location selection fully deterministic.
   */
  protected Random rng = null;

  /**
   * Returns the RNG to use for this shape. Falls back to {@link ThreadLocalRandom#current()} when
   * no explicit RNG has been set so that production code is unaffected.
   *
   * @return the active {@link Random} instance; never {@code null}
   */
  protected final Random rng() {
    return rng != null ? rng : ThreadLocalRandom.current();
  }

  /**
   * Injects a deterministic RNG. Intended for unit tests only.
   *
   * @param rng the {@link Random} instance to use; pass {@code null} to restore
   *     {@link ThreadLocalRandom} behaviour.
   */
  public final void setRng(Random rng) {
    this.rng = rng;
  }

  /**
   * Constructor for MemoryShape
   *
   * @param eClass - enum class to use
   * @param name - unique name of shape
   * @param data - default data
   * @throws IllegalArgumentException - in case of invalid inputs for expected types
   */
  public MemoryShape(Class<E> eClass, String name, EnumMap<E, Object> data)
      throws IllegalArgumentException {
    super(eClass, name, data);
  }

  /**
   * @return the active coalescing distance for learned bad runs; always the value last applied
   *     from the owning region's configuration, defaulting to {@code 1}
   */
  public long spatialResolution() {
    return spatialResolution;
  }

  /**
   * Apply the owning region's configured resolution.
   *
   * <p>Does not rewrite already-coalesced runs: a lower value only makes subsequent marks merge
   * less aggressively, and a higher one only makes them merge more. Operators wanting the finer
   * precision retroactively have to rescan.
   *
   * @param spatialResolution the configured value; clamped to at least {@code 1}
   */
  public void setSpatialResolution(long spatialResolution) {
    this.spatialResolution = Math.max(1L, spatialResolution);
  }


  /**
   * Get the range of the shape
   *
   * @return the range
   */
  public abstract long getRange();

  /**
   * Convert xz coordinates to a location value
   *
   * @param x the x coordinate
   * @param z the z coordinate
   * @return the location value
   */
  public abstract long xzToLocation(long x, long z);

  /**
   * Convert xz coordinates to a location value
   *
   * @param coords the coordinates
   * @return the location value
   */
  public abstract long xzToLocation(MutableRTPCoords coords);

  /**
   * Convert a location value to xz coordinates
   *
   * @param location the location value
   * @return an array containing x and z coordinates
   */
  public abstract int[] locationToXZ(long location);



  /**
   * Convert a location value to xz coordinates and store in the output object
   *
   * @param location the location value
   * @param output the output object
   */
  public abstract void locationToXZ(long location, MutableRTPCoords output);

  /**
   * Check if a location is known to be bad (e.g. invalid teleport target)
   *
   * @param x the x coordinate
   * @param z the z coordinate
   * @return true if known bad, false otherwise
   */
  public boolean isKnownBad(int x, int z) {
    return isKnownBad((long) xzToLocation(x, z));
  }

  /**
   * Check if a location is known to be bad
   *
   * @param coords the coordinates
   * @return true if known bad, false otherwise
   */
  public boolean isKnownBad(MutableRTPCoords coords) {
    return isKnownBad((long) xzToLocation(coords));
  }

  /**
   * Check if a location is known to be bad
   *
   * @param location the location value
   * @return true if known bad, false otherwise
   */
  public boolean isKnownBad(long location) {
    if (pendingBadLocations.get().containsKey(location)) return true;

    ConcurrentHashMap<Long, Long> rebuilding = rebuildingBadLocations;
    if (rebuilding != null && rebuilding.containsKey(location)) return true;

    long[] sums = badPrefixSumsCache;
    long[] keys = badKeysCache;
    if (keys.length == 0) return false;

    int floorIdx = floorRunIndex(keys, location);

    if (floorIdx >= 0 && floorIdx < sums.length) {
      long key = keys[floorIdx];
      if (key == location) return true;
      long sum = sums[floorIdx];
      long prevSum = (floorIdx > 0) ? sums[floorIdx - 1] : 0L;
      return location < (key + (sum - prevSum));
    }
    return false;
  }

  /**
   * Returns rejection cause for bad-location run containing {@code (x, z)}, or {@code -1}.
   * Non-negative return is a {@link LocationGenerator.FailTypes} ordinal.
   *
   * @param x the x coordinate
   * @param z the z coordinate
   * @return {@link LocationGenerator.FailTypes} ordinal, or {@code -1} if not known bad
   */
  public int causeAt(int x, int z) {
    return causeAt((long) xzToLocation(x, z));
  }

  /**
   * Returns rejection cause for bad-location run containing {@code location}, or {@code -1}.
   * Non-negative return is a {@link LocationGenerator.FailTypes} ordinal. Pending/rebuilding
   * entries with no resolved run cause read as {@code misc}.
   *
   * @param location the location value
   * @return {@link LocationGenerator.FailTypes} ordinal, or {@code -1} if not known bad
   */
  public int causeAt(long location) {
    if (pendingBadLocations.get().containsKey(location)) return MISC_CAUSE & 0xFF;

    ConcurrentHashMap<Long, Long> rebuilding = rebuildingBadLocations;
    if (rebuilding != null && rebuilding.containsKey(location)) return MISC_CAUSE & 0xFF;

    long[] sums = badPrefixSumsCache;
    long[] keys = badKeysCache;
    byte[] causes = badCauseCache;
    if (keys.length == 0) return -1;

    int floorIdx = floorRunIndex(keys, location);

    if (floorIdx >= 0 && floorIdx < sums.length) {
      long key = keys[floorIdx];
      long sum = sums[floorIdx];
      long prevSum = (floorIdx > 0) ? sums[floorIdx - 1] : 0L;
      boolean inRun = (key == location) || (location < (key + (sum - prevSum)));
      if (!inRun) return -1;
      return (floorIdx < causes.length) ? (causes[floorIdx] & 0xFF) : (MISC_CAUSE & 0xFF);
    }
    return -1;
  }

  /**
   * Index of the last run whose start key is {@code <= location}, or {@code -1} when every run
   * starts after it.
   *
   * <p>{@code badKeysCache} is produced sorted by the merge in {@link #flushAndRebuild}, so this
   * is a binary search. It used to be a forward linear scan, which made every {@link #isKnownBad}
   * and {@link #causeAt} call O(runs) - and {@link #addBadChunkRadius} calls {@code isKnownBad}
   * once per probed cell on the selection path.
   *
   * @param keys sorted run start keys (snapshot)
   * @param location the 1D index to locate
   * @return floor index, or {@code -1}
   */
  private static int floorRunIndex(long[] keys, long location) {
    int idx = Arrays.binarySearch(keys, location);
    if (idx >= 0) return idx;
    return -(idx + 1) - 1;
  }

  /**
   * Returns an immutable snapshot of current bad-location key array (sorted packed XZ keys).
   * Used by {@code BadPointsHeatmapResolver}. Volatile read without locking.
   *
   * @return fresh array copy; never {@code null}
   */
  public long[] badKeysSnapshot() {
    long[] keys = badKeysCache;
    return Arrays.copyOf(keys, keys.length);
  }

  /**
   * Returns an immutable snapshot of the per-run rejection-cause array, aligned
   * 1:1 with {@link #badKeysSnapshot()} (each element is a
   * {@link LocationGenerator.FailTypes} ordinal stored as a byte). Runs with no
   * recorded cause read as {@code misc}.
   *
   * @return a fresh array copy; never {@code null}, may be zero-length
   */
  public byte[] badCausesSnapshot() {
    byte[] causes = badCauseCache;
    return Arrays.copyOf(causes, causes.length);
  }

  public long[] badExpiriesSnapshot() {
    long[] expiries = badExpiryCache;
    return Arrays.copyOf(expiries, expiries.length);
  }

  public long[] probationKeysSnapshot() {
    long[] keys = probationKeysCache;
    return Arrays.copyOf(keys, keys.length);
  }

  public long[] probationPrefixSumsSnapshot() {
    long[] sums = probationPrefixSumsCache;
    return Arrays.copyOf(sums, sums.length);
  }

  public byte[] probationCausesSnapshot() {
    byte[] causes = probationCauseCache;
    return Arrays.copyOf(causes, causes.length);
  }

  public long[] probationExpiriesSnapshot() {
    long[] expiries = probationExpiryCache;
    return Arrays.copyOf(expiries, expiries.length);
  }

  public void save(String fileName, String worldName) {
    if (!fileName.endsWith(".bin")) fileName = fileName + ".bin";

    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    String dirPath =
        pluginDir.getAbsolutePath() + File.separator + "database" + File.separator + "regionData";
    String filePath = dirPath + File.separator + fileName;

    // Snapshot under write lock to avoid concurrent modifications
    long[] sBadKeys;
    long[] sBadSums;
    byte[] sBadCauses;
    long[] sBadExpiries;
    long[] sProbKeys;
    long[] sProbSums;
    byte[] sProbCauses;
    long[] sProbExpiries;
    int totalRuns;
    long[] allKeys;
    long[] allDeltas;
    byte[] allCauses;
    long[] allExpiries;
    // The union is immutable and published by a single volatile write, so it needs no copy and no
    // lock: whichever generation is read is internally consistent.
    BiomeUnionTable sUnion = biomeUnion;

    writeLock.lock();
    try {
      sBadKeys = Arrays.copyOf(badKeysCache, badKeysCache.length);
      sBadSums = Arrays.copyOf(badPrefixSumsCache, badPrefixSumsCache.length);
      sBadCauses = Arrays.copyOf(badCauseCache, badCauseCache.length);
      sBadExpiries = Arrays.copyOf(badExpiryCache, badExpiryCache.length);

      sProbKeys = Arrays.copyOf(probationKeysCache, probationKeysCache.length);
      sProbSums = Arrays.copyOf(probationPrefixSumsCache, probationPrefixSumsCache.length);
      sProbCauses = Arrays.copyOf(probationCauseCache, probationCauseCache.length);
      sProbExpiries = Arrays.copyOf(probationExpiryCache, probationExpiryCache.length);

      // Merge active and probation non-overlapping runs into a single sorted stream for disk
      totalRuns = sBadKeys.length + sProbKeys.length;
      allKeys = new long[totalRuns];
      allDeltas = new long[totalRuns];
      allCauses = new byte[totalRuns];
      allExpiries = new long[totalRuns];
      int runIdx = 0;
      int aIdx = 0, pIdx = 0;
      long aPrev = 0L, pPrev = 0L;
      while (aIdx < sBadKeys.length || pIdx < sProbKeys.length) {
        if (aIdx < sBadKeys.length && pIdx < sProbKeys.length) {
          if (sBadKeys[aIdx] <= sProbKeys[pIdx]) {
            allKeys[runIdx] = sBadKeys[aIdx];
            allDeltas[runIdx] = sBadSums[aIdx] - aPrev;
            allCauses[runIdx] = (aIdx < sBadCauses.length) ? sBadCauses[aIdx] : MISC_CAUSE;
            allExpiries[runIdx] = (aIdx < sBadExpiries.length) ? sBadExpiries[aIdx] : 0L;
            aPrev = sBadSums[aIdx];
            aIdx++;
          } else {
            allKeys[runIdx] = sProbKeys[pIdx];
            allDeltas[runIdx] = sProbSums[pIdx] - pPrev;
            allCauses[runIdx] = (pIdx < sProbCauses.length) ? sProbCauses[pIdx] : MISC_CAUSE;
            allExpiries[runIdx] = (pIdx < sProbExpiries.length) ? sProbExpiries[pIdx] : 0L;
            pPrev = sProbSums[pIdx];
            pIdx++;
          }
        } else if (aIdx < sBadKeys.length) {
          allKeys[runIdx] = sBadKeys[aIdx];
          allDeltas[runIdx] = sBadSums[aIdx] - aPrev;
          allCauses[runIdx] = (aIdx < sBadCauses.length) ? sBadCauses[aIdx] : MISC_CAUSE;
          allExpiries[runIdx] = (aIdx < sBadExpiries.length) ? sBadExpiries[aIdx] : 0L;
          aPrev = sBadSums[aIdx];
          aIdx++;
        } else {
          allKeys[runIdx] = sProbKeys[pIdx];
          allDeltas[runIdx] = sProbSums[pIdx] - pPrev;
          allCauses[runIdx] = (pIdx < sProbCauses.length) ? sProbCauses[pIdx] : MISC_CAUSE;
          allExpiries[runIdx] = (pIdx < sProbExpiries.length) ? sProbExpiries[pIdx] : 0L;
          pPrev = sProbSums[pIdx];
          pIdx++;
        }
        runIdx++;
      }

    } finally {
      writeLock.unlock();
    }

    // Build a binary payload (big-endian) without any synchronous disk I/O here.
    // BIN_VERSION 4: magic(4) + version(4) + world(4+len) + stride(8) + badSize(4) +
    // entries * 25 bytes (key 8 + delta 8 + cause 1 + expiresAt 8), then the biome section:
    // nameCount(4) + [nameLen(4) + bytes] * nameCount + runCount(4) +
    // [varint keyDelta + varint width + varint biomeId] * runCount.
    byte[] worldBytes = worldName.getBytes(StandardCharsets.UTF_8);
    String[] unionNames = sUnion.names();
    int unionRuns = sUnion.runCount();
    int size = 0;
    size += 8; // BIN_MAGIC + BIN_VERSION
    size += 4 + worldBytes.length; // world name length + bytes
    size += 8; // scanStride
    size += 4; // bad array length
    size += totalRuns * 25; // key + delta + cause + expiresAt per entry
    size += 4; // biome name table size
    for (String name : unionNames) {
      size += 4 + name.getBytes(StandardCharsets.UTF_8).length;
    }
    size += 4; // biome run count
    size += unionRuns * 30; // worst case: three 10-byte LEB128 values per run

    ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
    buf.putInt(BIN_MAGIC);
    buf.putInt(BIN_VERSION);
    buf.putInt(worldBytes.length).put(worldBytes);
    buf.putLong(scanStride.get());

    buf.putInt(totalRuns);
    for (int i = 0; i < totalRuns; i++) {
      buf.putLong(allKeys[i]);
      buf.putLong(allDeltas[i]);
      buf.put(allCauses[i]);
      buf.putLong(allExpiries[i]);
    }

    buf.putInt(unionNames.length);
    for (String name : unionNames) {
      byte[] bName = name.getBytes(StandardCharsets.UTF_8);
      buf.putInt(bName.length).put(bName);
    }
    buf.putInt(unionRuns);
    long prevKey = 0L;
    long prevSum = 0L;
    for (int i = 0; i < unionRuns; i++) {
      long key = sUnion.keyAt(i);
      long sum = sUnion.sumAt(i);
      // Keys ascend and widths are non-negative, so no value here needs zigzagging.
      putVarLong(buf, key - prevKey);
      putVarLong(buf, sum - prevSum);
      putVarLong(buf, sUnion.idAt(i));
      prevKey = key;
      prevSum = sum;
    }

    // Write directly to disk (async-safe: called from async scan/shutdown threads)
    try {
      java.nio.file.Path p = java.nio.file.Paths.get(filePath);
      java.nio.file.Files.createDirectories(p.getParent());
      // The buffer is sized for worst-case varints, so only the written prefix is emitted.
      java.nio.file.Files.write(p, Arrays.copyOf(buf.array(), buf.position()));
    } catch (Exception e) {
      RTP.log(Level.WARNING, "[MemoryShape] Failed to write binary file: " + filePath + " - " + e.getMessage(), e);
    }
  }

  /**
   * Writes {@code value} as unsigned LEB128 (seven bits per byte, high bit as continuation).
   *
   * <p>Used for the biome run stream, where every value is a non-negative delta between adjacent
   * runs. Delta magnitude tracks run spacing rather than the key domain, so the encoding does not
   * widen as the world radius grows - which a fixed-width {@code int} field would both fail to do
   * and cap.
   *
   * @param buf destination, positioned at the write point
   * @param value non-negative value to encode
   */
  private static void putVarLong(ByteBuffer buf, long value) {
    long v = value;
    while ((v & ~0x7FL) != 0L) {
      buf.put((byte) ((v & 0x7FL) | 0x80L));
      v >>>= 7;
    }
    buf.put((byte) v);
  }

  /**
   * Reads one unsigned LEB128 value written by {@link #putVarLong}.
   *
   * @param buf source, positioned at the value
   * @return the decoded value
   * @throws java.nio.BufferUnderflowException when the payload is truncated
   */
  private static long getVarLong(ByteBuffer buf) {
    long result = 0L;
    int shift = 0;
    while (true) {
      byte b = buf.get();
      result |= ((long) (b & 0x7F)) << shift;
      if ((b & 0x80) == 0) return result;
      shift += 7;
      if (shift > 63) throw new java.nio.BufferUnderflowException();
    }
  }

  /**
   * Reads a BIN_VERSION 4 biome section: name table plus one ascending run stream.
   *
   * <p>The stream is the union's own published order, already identity-merged and clipped, so it
   * is handed to the builder verbatim rather than re-merged - a load therefore reproduces the
   * saved table exactly.
   *
   * @param buf payload positioned at the biome section
   * @return the table, or {@code null} when the section is malformed
   */
  private static BiomeUnionTable readUnionSection(ByteBuffer buf) {
    int nameCount = buf.getInt();
    if (nameCount < 0 || nameCount > buf.remaining()) return null;
    String[] names = new String[nameCount];
    for (int i = 0; i < nameCount; i++) {
      int nLen = buf.getInt();
      if (nLen < 0 || nLen > buf.remaining()) return null;
      byte[] nb = new byte[nLen];
      buf.get(nb);
      names[i] = new String(nb, StandardCharsets.UTF_8);
    }
    int runCount = buf.getInt();
    // One byte per varint is the floor, so three per run bounds a sane run count.
    if (runCount < 0 || runCount > buf.remaining() / 3) return null;
    long[] keys = new long[runCount];
    long[] widths = new long[runCount];
    short[] ids = new short[runCount];
    long key = 0L;
    for (int i = 0; i < runCount; i++) {
      key += getVarLong(buf);
      long width = getVarLong(buf);
      long id = getVarLong(buf);
      keys[i] = key;
      widths[i] = width;
      ids[i] = (short) id;
    }
    return BiomeUnionTable.build(keys, widths, ids, runCount, names, null);
  }

  /**
   * Reads the pre-version-4 biome sections - one {@code key} + {@code width delta} table per biome
   * name - and folds them into a union.
   *
   * <p>Those sections are exactly the builder's input once interned, so this is an ingest rather
   * than a migration: the runs are staged, sorted key-ascending, and passed through the same
   * identity-merge-with-clipping used by a rebuild, which is what makes the resulting table a
   * partition.
   *
   * @param buf payload positioned at the biome section
   * @return the table, or {@code null} when the section is malformed
   */
  private BiomeUnionTable readLegacyBiomeSections(ByteBuffer buf) {
    int biomeSize = buf.getInt();
    if (biomeSize < 0) return null;
    String[] names = new String[biomeSize];
    long[] stagedKeys = new long[0];
    long[] stagedWidths = new long[0];
    short[] stagedIds = new short[0];
    int staged = 0;
    for (int i = 0; i < biomeSize; i++) {
      int nLen = buf.getInt();
      if (nLen < 0 || nLen > buf.remaining()) return null;
      byte[] nb = new byte[nLen];
      buf.get(nb);
      names[i] = new String(nb, StandardCharsets.UTF_8);
      int inner = buf.getInt();
      if (inner < 0 || inner > (buf.remaining() / 16)) return null;
      if (staged + inner > stagedKeys.length) {
        int grown = Math.max(16, Math.max(staged + inner, stagedKeys.length << 1));
        stagedKeys = Arrays.copyOf(stagedKeys, grown);
        stagedWidths = Arrays.copyOf(stagedWidths, grown);
        stagedIds = Arrays.copyOf(stagedIds, grown);
      }
      for (int j = 0; j < inner; j++) {
        long k = buf.getLong();
        long d = buf.getLong();
        stagedKeys[staged] = k;
        stagedWidths[staged] = d;
        stagedIds[staged] = (short) i;
        staged++;
      }
    }
    if (staged == 0) return BiomeUnionTable.EMPTY;

    // Sort key-ascending, equal keys resolved in favour of the later biome slot, matching the
    // rebuild's tie-break so a load and a rebuild of the same observations agree.
    Integer[] order = new Integer[staged];
    for (int i = 0; i < staged; i++) order[i] = i;
    final long[] sk = stagedKeys;
    final short[] si = stagedIds;
    Arrays.sort(
        order,
        (a, b) -> (sk[a] != sk[b]) ? Long.compare(sk[a], sk[b]) : Short.compare(si[b], si[a]));
    long[] sortedKeys = new long[staged];
    long[] sortedWidths = new long[staged];
    short[] sortedIds = new short[staged];
    for (int i = 0; i < staged; i++) {
      int at = order[i];
      sortedKeys[i] = stagedKeys[at];
      sortedWidths[i] = stagedWidths[at];
      sortedIds[i] = stagedIds[at];
    }

    long[] outKeys = new long[staged];
    long[] outWidths = new long[staged];
    short[] outIds = new short[staged];
    int count =
        coalesceRuns(
            sortedKeys, sortedWidths, sortedIds, staged, spatialResolution, outKeys, outWidths,
            outIds);
    return BiomeUnionTable.build(outKeys, outWidths, outIds, count, names, null);
  }

  /**
   * Folds a key-ascending run stream into the union's partition form.
   *
   * <p>Two rules, and they are the only place either is expressed: runs of the <em>same</em> biome
   * coalesce across a gap of up to {@code spatialResolution}; runs of <em>different</em> biomes
   * never merge, and an incoming run overlapping an already-placed one is clipped past it
   * (dropped when fully covered). Clipping rather than tagging is what keeps every cell in exactly
   * one run, so {@code getEffectiveGoodCount()} cannot double-count a cell claimed by two biomes.
   *
   * @param inKeys run start keys, ascending
   * @param inWidths run widths, parallel to {@code inKeys}
   * @param inIds biome id of each run, parallel to {@code inKeys}
   * @param count live entries in the input columns
   * @param spatialResolution same-biome bridging gap, in cells
   * @param outKeys destination keys; capacity {@code >= count}
   * @param outWidths destination widths; capacity {@code >= count}
   * @param outIds destination ids; capacity {@code >= count}
   * @return number of runs written
   */
  private static int coalesceRuns(
      long[] inKeys,
      long[] inWidths,
      short[] inIds,
      int count,
      long spatialResolution,
      long[] outKeys,
      long[] outWidths,
      short[] outIds) {
    int out = 0;
    long curStart = -1L;
    long curLength = -1L;
    short curId = -1;
    for (int i = 0; i < count; i++) {
      long nextKey = inKeys[i];
      long nextLength = inWidths[i];
      short nextId = inIds[i];
      if (nextKey < 0L) continue;

      if (curStart == -1L) {
        curStart = nextKey;
        curLength = nextLength;
        curId = nextId;
        continue;
      }

      long curEnd = curStart + curLength;
      if (nextId == curId) {
        if (nextKey <= curEnd + spatialResolution) {
          curLength = Math.max(curLength, nextKey + nextLength - curStart);
          continue;
        }
      } else {
        if (nextKey + nextLength <= curEnd) continue;
        if (nextKey < curEnd) {
          nextLength = nextKey + nextLength - curEnd;
          nextKey = curEnd;
        }
      }

      outKeys[out] = curStart;
      outWidths[out] = curLength;
      outIds[out] = curId;
      out++;
      curStart = nextKey;
      curLength = nextLength;
      curId = nextId;
    }
    if (curStart != -1L) {
      outKeys[out] = curStart;
      outWidths[out] = curLength;
      outIds[out] = curId;
      out++;
    }
    return out;
  }

  public void exportDebugJson(String fileName, String worldName) {
    if (!fileName.endsWith(".json")) fileName = fileName + ".json";

    // 1. Snapshot under write lock to avoid concurrent modifications
    long[] sBadKeys;
    long[] sBadSums;
    byte[] sBadCauses;
    BiomeUnionTable sUnion = biomeUnion;

    writeLock.lock();
    try {
      sBadKeys = java.util.Arrays.copyOf(badKeysCache, badKeysCache.length);
      sBadSums = java.util.Arrays.copyOf(badPrefixSumsCache, badPrefixSumsCache.length);
      sBadCauses = java.util.Arrays.copyOf(badCauseCache, badCauseCache.length);
    } finally {
      writeLock.unlock();
    }

    // 2. Convert Bad Location prefix sums back to discrete lengths, tagging each
    //    run with its rejection cause (one cause per run; `misc` when unknown).
    LocationGenerator.FailTypes[] failTypes = LocationGenerator.FailTypes.values();
    java.util.List<java.util.Map<String, Object>> badList = new java.util.ArrayList<>();
    long prev = 0L;
    for (int i = 0; i < sBadKeys.length; i++) {
      java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
      entry.put("start", sBadKeys[i]);
      entry.put("length", sBadSums[i] - prev);
      int causeOrd = (i < sBadCauses.length) ? (sBadCauses[i] & 0xFF) : (MISC_CAUSE & 0xFF);
      String causeName = (causeOrd >= 0 && causeOrd < failTypes.length)
          ? failTypes[causeOrd].name()
          : LocationGenerator.FailTypes.misc.name();
      entry.put("cause", causeName);
      badList.add(entry);
      prev = sBadSums[i];
    }

    // 3. Emit the union's runs grouped by owning biome. Extents are attributed, so a cell claimed
    //    by two biomes appears under exactly the one biomeAt() reports.
    java.util.Map<String, java.util.List<java.util.Map<String, Long>>> biomeMap = new java.util.LinkedHashMap<>();
    String[] unionNames = sUnion.names();
    for (int id = 0; id < unionNames.length; id++) {
      BiomeUnionTable.BiomeView view = sUnion.viewOf(id);
      if (view == null) continue;
      java.util.List<java.util.Map<String, Long>> bList = new java.util.ArrayList<>();
      for (int i = 0; i < view.length(); i++) {
        java.util.Map<String, Long> entry = new java.util.LinkedHashMap<>();
        entry.put("start", view.keyAt(i));
        entry.put("length", view.widthAt(i));
        bList.add(entry);
      }
      biomeMap.put(unionNames[id], bList);
    }

    // 4. Construct Root JSON Object
    java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
    root.put("world", worldName);
    root.put("scanStride", scanStride.get());
    root.put("spatialResolution", spatialResolution);
    root.put("badLocations", badList);
    root.put("biomeLocations", biomeMap);

    // 5. Write to File
    java.io.File pluginDir = io.github.dailystruggle.rtp.common.RTP.serverAccessor.getPluginDirectory();
    java.io.File outDir = new java.io.File(pluginDir, "database" + java.io.File.separator + "regionData" + java.io.File.separator + "debug");
    if (!outDir.exists()) outDir.mkdirs();
    java.io.File outFile = new java.io.File(outDir, fileName);

    try (java.io.FileWriter writer = new java.io.FileWriter(outFile)) {
      com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
      gson.toJson(root, writer);
    } catch (java.io.IOException ex) {
      io.github.dailystruggle.rtp.common.RTP.log(java.util.logging.Level.WARNING, "Failed to write debug JSON: " + ex.getMessage(), ex);
    }
  }

  public CompletableFuture<Void> getLoadFuture() {
    return loadFuture;
  }

  public CompletableFuture<Void> load(String fileName, String worldName) {
    if (!fileName.endsWith(".bin")) fileName = fileName + ".bin";

    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    String filePath =
        pluginDir.getAbsolutePath()
            + File.separator
            + "database"
            + File.separator
            + "regionData"
            + File.separator
            + fileName;

    loadFuture = CompletableFuture.runAsync(() -> {
      java.nio.file.Path p = java.nio.file.Paths.get(filePath);
      if (!java.nio.file.Files.exists(p)) return;
      byte[] data;
      try {
        data = java.nio.file.Files.readAllBytes(p);
      } catch (Exception e) {
        RTP.log(Level.WARNING, "[MemoryShape] Failed to read binary file: " + filePath + " - " + e.getMessage(), e);
        return;
      }
      if (data.length < 4) {
        // Empty or truncated binary - nothing to load. Most often produced
        // by a save() call that hit an error before writing the world-name
        // length header, or by a zero-byte file from a prior crash.
        return;
      }
      try {
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                // Format detection: a cause-tagged payload (BIN_VERSION >= 2)
                // begins with BIN_MAGIC; a legacy payload begins directly with the
                // (small, positive) world-name byte length.
                int first = buf.getInt();
                int version;
                int wLen;
                if (first == BIN_MAGIC) {
                  version = buf.getInt();
                  wLen = buf.getInt();
                } else {
                  version = 1;
                  wLen = first;
                }
                if (wLen < 0 || wLen > buf.remaining()) return;
                byte[] w = new byte[wLen];
                buf.get(w);
                String readWorld = new String(w, StandardCharsets.UTF_8);
                if (!readWorld.equals(worldName)) return;

                if (buf.remaining() >= 8) {
                  scanStride.set(buf.getLong());
                } else {
                  scanStride.set(-1L);
                }

                // Per-bad-run on-disk width: legacy = key(8) + delta(8); v2 adds a
                // trailing cause byte; v3 adds expiresAt epoch seconds (8 bytes).
                int badEntryWidth = (version >= 3) ? 25 : ((version >= 2) ? 17 : 16);
                int badSize = buf.getInt();
                if (badSize < 0 || badSize > (buf.remaining() / badEntryWidth)) return;

                long now = java.time.Instant.now().getEpochSecond();
                long[] rawKeys = new long[badSize];
                long[] rawDeltas = new long[badSize];
                byte[] rawCauses = new byte[badSize];
                long[] rawExpiries = new long[badSize];

                int activeCount = 0;
                int probCount = 0;

                for (int i = 0; i < badSize; i++) {
                  long k = buf.getLong();
                  long d = buf.getLong();
                  byte cause = (version >= 2) ? buf.get() : MISC_CAUSE;
                  long exp = (version >= 3) ? buf.getLong() : 0L;
                  rawKeys[i] = k;
                  rawDeltas[i] = d;
                  rawCauses[i] = cause;
                  rawExpiries[i] = exp;

                  if (exp <= 0L || now < exp) {
                    activeCount++;
                  } else {
                    // Check if within probation window (1x TTL to 2x TTL)
                    LocationGenerator.FailTypes[] values = LocationGenerator.FailTypes.values();
                    LocationGenerator.FailTypes ft = (cause >= 0 && cause < values.length)
                        ? values[cause] : LocationGenerator.FailTypes.misc;
                    long ttl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig.resolveTtlSeconds(ft, null);
                    long window = (ttl > 0L) ? ttl : 14L * 86400L;
                    if (now < exp + window) {
                      probCount++;
                    }
                  }
                }

                long[] newBadKeys = new long[activeCount];
                long[] newBadSums = new long[activeCount];
                byte[] newBadCauses = new byte[activeCount];
                long[] newBadExpiries = new long[activeCount];

                long[] newProbKeys = new long[probCount];
                long[] newProbSums = new long[probCount];
                byte[] newProbCauses = new byte[probCount];
                long[] newProbExpiries = new long[probCount];

                int aIdx = 0, pIdx = 0;
                long aRunning = 0L, pRunning = 0L;

                for (int i = 0; i < badSize; i++) {
                  long k = rawKeys[i];
                  long d = rawDeltas[i];
                  byte cause = rawCauses[i];
                  long exp = rawExpiries[i];

                  if (exp <= 0L || now < exp) {
                    newBadKeys[aIdx] = k;
                    aRunning += d;
                    newBadSums[aIdx] = aRunning;
                    newBadCauses[aIdx] = cause;
                    newBadExpiries[aIdx] = exp;
                    aIdx++;
                  } else {
                    LocationGenerator.FailTypes[] values = LocationGenerator.FailTypes.values();
                    LocationGenerator.FailTypes ft = (cause >= 0 && cause < values.length)
                        ? values[cause] : LocationGenerator.FailTypes.misc;
                    long ttl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig.resolveTtlSeconds(ft, null);
                    long window = (ttl > 0L) ? ttl : 14L * 86400L;
                    if (now < exp + window) {
                      newProbKeys[pIdx] = k;
                      pRunning += d;
                      newProbSums[pIdx] = pRunning;
                      newProbCauses[pIdx] = cause;
                      newProbExpiries[pIdx] = exp;
                      pIdx++;
                    }
                  }
                }

                BiomeUnionTable newUnion =
                    (version >= 4) ? readUnionSection(buf) : readLegacyBiomeSections(buf);
                if (newUnion == null) return;

                // Apply under write lock
                writeLock.lock();
                try {
                  badKeysCache = newBadKeys;
                  badPrefixSumsCache = newBadSums;
                  badCauseCache = newBadCauses;
                  badExpiryCache = newBadExpiries;
                  probationKeysCache = newProbKeys;
                  probationPrefixSumsCache = newProbSums;
                  probationCauseCache = newProbCauses;
                  probationExpiryCache = newProbExpiries;
                  // The biome table now loads into its published form directly, so no rebuild is
                  // needed before a union-backed read works. The recorded total therefore has to
                  // be set here too - previously only a rebuild ever computed it.
                  biomeUnion = newUnion;
                  totalBiomeCount.set(newUnion.totalWidth());
                  biomeTableVersion.incrementAndGet();
                  badLocationsDirty = true;
                  biomeLocationsDirty = false;
                } finally {
                  writeLock.unlock();
                }
              } catch (Throwable t) {
                RTP.log(Level.WARNING, t.getMessage(), t);
              }
            });
    return loadFuture;
  }

  public void addBadLocation(long location) {
    addBadLocation(location, LocationGenerator.FailTypes.misc);
  }

  /**
   * Marks a single 1D index bad with cause and custom retention TTL in seconds (ADR-079).
   *
   * @param location   the 1D spiral index to mark bad
   * @param cause      the rejection reason; {@code null} is treated as {@link LocationGenerator.FailTypes#misc}
   * @param ttlSeconds retention duration in seconds; {@code <= 0} indicates infinite retention
   */
  public void addBadLocation(long location, LocationGenerator.FailTypes cause, long ttlSeconds) {
    checkAndRestoreFromProbation(location);
    if (absorbIntoAdjacentRun(location)) return;
    long ord = (cause == null) ? MISC_CAUSE : cause.ordinal();
    long epochSec = (ttlSeconds <= 0) ? 0L : (java.time.Instant.now().getEpochSecond() + ttlSeconds);
    // Combine cause (low 8 bits) and epochSec (shifted 8 bits) into pending value
    long pendingVal = (ord & 0xFFL) | (epochSec << 8);
    pendingBadLocations.get().put(location, pendingVal);
    badLocationsDirty = true;
  }

  /**
   * Checks if {@code location} falls within an existing probationary run. If so, immediately
   * re-promotes that entire run back into {@code pendingBadLocations} with refreshed TTL.
   *
   * @param location candidate spiral index
   * @return true if a probationary run was restored
   */
  public boolean checkAndRestoreFromProbation(long location) {
    long[] pKeys = probationKeysCache;
    long[] pSums = probationPrefixSumsCache;
    byte[] pCauses = probationCauseCache;
    int m = Math.min(pKeys.length, pSums.length);
    if (m == 0 || location < 0L) return false;

    int idx = floorRunIndex(pKeys, location);
    if (idx < 0 || idx >= m) return false;

    long start = pKeys[idx];
    long prevSum = (idx > 0) ? pSums[idx - 1] : 0L;
    long len = pSums[idx] - prevSum;
    if (location >= start && location < start + len) {
      // Re-promote the whole run
      byte causeByte = (idx < pCauses.length) ? pCauses[idx] : MISC_CAUSE;
      LocationGenerator.FailTypes[] values = LocationGenerator.FailTypes.values();
      LocationGenerator.FailTypes cause = (causeByte >= 0 && causeByte < values.length)
          ? values[causeByte] : LocationGenerator.FailTypes.misc;
      long ttl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig.resolveTtlSeconds(cause, null);
      long epochSec = (ttl <= 0) ? 0L : (java.time.Instant.now().getEpochSecond() + ttl);
      long pendingVal = (causeByte & 0xFFL) | (epochSec << 8);
      for (long k = start; k < start + len; k++) {
        pendingBadLocations.get().put(k, pendingVal);
      }
      badLocationsDirty = true;
      return true;
    }
    return false;
  }

  /**
   * Marks a single 1D index bad and records the rejection {@code cause}. The cause
   * is carried on the pending entry's value (its {@link LocationGenerator.FailTypes}
   * ordinal) and surfaces as the per-run cause after the next rebuild.
   *
   * @param location the 1D spiral index to mark bad
   * @param cause    the rejection reason; {@code null} is treated as {@link LocationGenerator.FailTypes#misc}
   */
  public void addBadLocation(long location, LocationGenerator.FailTypes cause) {
    long ttl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig.resolveTtlSeconds(cause, null);
    addBadLocation(location, cause, ttl);
  }

  /**
   * Extend an existing bad run in place when {@code location} sits within {@code
   * spatialResolution} of its end, instead of queueing a pending entry.
   *
   * <p>A genuinely new run has to flow through {@code pendingBadLocations} and the dirty flag,
   * because inserting a key changes the array length. But a mark that the merge in
   * {@link #flushAndRebuild} would coalesce into the preceding run only changes that run's
   * length, which is representable in place - so it costs a suffix add rather than a full
   * re-merge, and it does not dirty the state at all.
   *
   * <p>Concurrency: prefix sums are updated back-to-front, so a lock-free reader mid-update sees
   * either the old or the new value at each index and the array stays non-decreasing either way;
   * {@code keys} is not touched, so a concurrent {@link #floorRunIndex} binary search stays
   * valid. Serialized against {@link #flushAndRebuild} through {@code isRebuilding}, and skipped
   * (falling back to the pending path) whenever a rebuild holds that flag.
   *
   * @param location the 1D index being marked bad
   * @return true when the mark was absorbed and no pending entry is needed
   */
  private boolean absorbIntoAdjacentRun(long location) {
    if (location < 0L) return false;
    if (!isRebuilding.compareAndSet(false, true)) return false;
    try {
      long[] keys = badKeysCache;
      long[] sums = badPrefixSumsCache;
      int n = Math.min(keys.length, sums.length);
      if (n == 0) return false;

      int idx = floorRunIndex(keys, location);
      if (idx < 0 || idx >= n) return false;

      long key = keys[idx];
      long prevSum = (idx > 0) ? sums[idx - 1] : 0L;
      long end = key + (sums[idx] - prevSum); // exclusive

      if (location < end) return true; // already covered: nothing to record
      if (location > end + spatialResolution) return false; // genuinely new run

      long newEnd = location + 1L;
      // A mark that would also bridge to the next run changes the array length; leave that
      // coalescing to the merge.
      if (idx + 1 < keys.length && keys[idx + 1] <= newEnd + spatialResolution) return false;

      long delta = newEnd - end;
      // writeLock excludes the snapshot readers that require a coherent view (save, load,
      // learnedStateSummary); the lock-free readers are safe by the monotonic-suffix argument
      // above.
      writeLock.lock();
      try {
        for (int k = n - 1; k >= idx; k--) sums[k] += delta;
      } finally {
        writeLock.unlock();
      }
      totalBadCount.addAndGet(delta);
      return true;
    } finally {
      isRebuilding.set(false);
    }
  }

  /** Shared empty result for {@link #chunkToLocations(int, int)} when a chunk has no preimage. */
  protected static final long[] EMPTY_LONG_ARRAY = new long[0];

  /**
   * Maximum walk distance (1D steps) used by default {@link #chunkToLocations(int, int)}
   * when probing for twin spiral index decoding to the same chunk.
   */
  protected static final int CHUNK_TO_LOCATIONS_WALK_BUDGET = 8;

  /**
   * Estimated 1D index offset between adjacent spiral rings at the same angle for given chunk.
   * Override hook for subclasses with exact geometry (e.g. Circle, Square). Returns 0 by default.
   *
   * @param cx chunk x in shape chunk-units
   * @param cz chunk z in shape chunk-units
   * @return non-negative ring offset, or 0 to rely solely on angular walk
   */
  protected long neighbourRingOffset(int cx, int cz) {
    return 0L;
  }

  /**
   * Inverse of {@link #xzToLocation(long, long)} at chunk granularity. Returns every 1D index
   * in {@code [0, getRange())} where {@code locationToXZ(n)} decodes to chunk {@code (cx, cz)}.
   * Bounded by <= 2 elements for Archimedean spirals (ADR-001).
   *
   * @param cx chunk x in shape chunk-units
   * @param cz chunk z in shape chunk-units
   * @return sorted distinct array of 0, 1, or 2 1D indices; never {@code null}
   */
  public long[] chunkToLocations(int cx, int cz) {
    if (!contains(cx, cz)) return EMPTY_LONG_ARRAY;

    final long range = getRange();
    if (range <= 0L) return EMPTY_LONG_ARRAY;

    final long representative = xzToLocation(cx, cz);
    if (representative < 0L || representative >= range) return EMPTY_LONG_ARRAY;

    // Collect up to 2 distinct indices that decode back to (cx, cz).
    long first = -1L;
    long second = -1L;

    int[] decoded = locationToXZ(representative);
    if (decoded[0] == cx && decoded[1] == cz) {
      first = representative;
    }

    // Angular walk: ±1 .. ±CHUNK_TO_LOCATIONS_WALK_BUDGET. We stop in each
    // direction as soon as the decoded coordinate leaves the chunk - the
    // representative is on the curve so the chunk's intersection with the
    // curve is contiguous in either direction.
    for (int delta = 1; delta <= CHUNK_TO_LOCATIONS_WALK_BUDGET; delta++) {
      long up = representative + delta;
      if (up < range) {
        decoded = locationToXZ(up);
        if (decoded[0] == cx && decoded[1] == cz) {
          if (first < 0L) first = up;
          else if (second < 0L && up != first) { second = up; break; }
        } else if (first >= 0L) {
          // Left the chunk on the +delta side; don't probe further up.
          break;
        }
      }
    }
    if (second < 0L) {
      for (int delta = 1; delta <= CHUNK_TO_LOCATIONS_WALK_BUDGET; delta++) {
        long down = representative - delta;
        if (down >= 0L) {
          decoded = locationToXZ(down);
          if (decoded[0] == cx && decoded[1] == cz) {
            if (first < 0L) first = down;
            else if (down != first) { second = down; break; }
          } else if (first >= 0L) {
            break;
          }
        }
      }
    }

    // Radial probe (twin on adjacent ring at roughly the same angle). Skip
    // when subclass has no exact ring offset, or when we already have 2 hits.
    // The candidate must be at least one ring away from any already-found
    // index - otherwise the angular walk would already have found it and we
    // would be double-counting a chunk that touches a single arc of the curve.
    if (second < 0L) {
      long ringOffset = neighbourRingOffset(cx, cz);
      if (ringOffset > (long) CHUNK_TO_LOCATIONS_WALK_BUDGET) {
        long base = (first >= 0L) ? first : representative;
        long[] candidates = new long[] { base + ringOffset, base - ringOffset };
        for (long cand : candidates) {
          if (cand < 0L || cand >= range) continue;
          // Reject candidates that fall within the angular-walk window of an
          // already-found index - they aren't on the adjacent ring.
          if (first >= 0L
              && Math.abs(cand - first) <= (long) CHUNK_TO_LOCATIONS_WALK_BUDGET) {
            continue;
          }
          decoded = locationToXZ(cand);
          if (decoded[0] == cx && decoded[1] == cz) {
            if (first < 0L) first = cand;
            else if (cand != first) { second = cand; break; }
          }
        }
      }
    }

    if (first < 0L) return EMPTY_LONG_ARRAY;
    if (second < 0L) return new long[] { first };
    return (first <= second) ? new long[] { first, second } : new long[] { second, first };
  }

  /**
   * Marks given 1D index and every twin index in the same chunk as bad.
   * Use only for chunk-attributable failures (biome, claims, borders, anvil).
   *
   * @param location 1D index decoding to target chunk
   * @return count of newly marked indices (0, 1, or 2)
   */
  public int addBadChunk(long location) {
    return addBadChunk(location, LocationGenerator.FailTypes.misc);
  }

  /**
   * Cause-tagged variant of {@link #addBadChunk(long)}.
   *
   * @param location 1D index decoding to target chunk
   * @param cause rejection cause attributed to marked indices
   * @return count of newly marked indices (0, 1, or 2)
   */
  public int addBadChunk(long location, LocationGenerator.FailTypes cause) {
    long ttl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig.resolveTtlSeconds(cause, null);
    return addBadChunk(location, cause, ttl);
  }

  /**
   * Cause- and TTL-tagged variant of {@link #addBadChunk(long)} (ADR-079).
   *
   * @param location   1D index decoding to target chunk
   * @param cause      rejection cause attributed to marked indices
   * @param ttlSeconds retention duration in seconds; {@code <= 0} indicates infinite retention
   * @return count of newly marked indices (0, 1, or 2)
   */
  public int addBadChunk(long location, LocationGenerator.FailTypes cause, long ttlSeconds) {
    int[] xz = locationToXZ(location);
    long[] preimage = chunkToLocations(xz[0], xz[1]);
    if (preimage.length == 0) {
      if (!isKnownBad(location)) {
        addBadLocation(location, cause, ttlSeconds);
        return 1;
      }
      return 0;
    }
    int marked = 0;
    for (long p : preimage) {
      if (!isKnownBad(p)) {
        addBadLocation(p, cause, ttlSeconds);
        marked++;
      }
    }
    return marked;
  }

  /**
   * Coerces uniquePlacements config value into a non-negative chunk radius.
   * Handles Boolean (true->1, false->0), Number, String. 0 disables unique placements.
   *
   * @param raw raw config object or {@code null}
   * @return non-negative chunk radius
   */
  public static int uniquePlacementsRadius(Object raw) {
    if (raw == null) return 0;
    if (raw instanceof Boolean b) return b ? 1 : 0;
    if (raw instanceof Number n) return Math.max(0, n.intValue());
    String s = String.valueOf(raw).trim();
    if (s.equalsIgnoreCase("true")) return 1;
    if (s.equalsIgnoreCase("false") || s.isEmpty()) return 0;
    try {
      return Math.max(0, Integer.parseInt(s));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * Marks chunks within Chebyshev radius {@code chunkRadius - 1} around {@code location} as bad.
   *
   * @param location center 1D index
   * @param chunkRadius Chebyshev radius (<= 0 is no-op)
   * @return newly marked index count
   */
  public int addBadChunkRadius(long location, int chunkRadius) {
    if (chunkRadius <= 0) return 0;
    if (chunkRadius == 1) {
      return addBadChunk(location, LocationGenerator.FailTypes.uniquePlacement);
    }
    int[] center = locationToXZ(location);
    int reach = chunkRadius - 1;
    int marked = 0;
    for (int dx = -reach; dx <= reach; dx++) {
      for (int dz = -reach; dz <= reach; dz++) {
        long[] preimage = chunkToLocations(center[0] + dx, center[1] + dz);
        for (long p : preimage) {
          if (p < 0L) continue;
          if (!isKnownBad(p)) {
            addBadLocation(p, LocationGenerator.FailTypes.uniquePlacement);
            marked++;
          }
        }
      }
    }
    return marked;
  }

  public void addBiomeLocation(Long location, long width, String biome) {
    // Canonicalise the biome key so that `FOREST` and `MINECRAFT:FOREST`
    // alias to the same per-biome bucket; the lookup side (PregenTask
    // biome-recall, getBiomeKeys / getBiomePrefixSums) does the same.
    String key = io.github.dailystruggle.rtp.common.selection.region.BiomeNames.canonical(biome);
    pendingBiomeLocations
        .get()
        .computeIfAbsent(key, b -> new ConcurrentHashMap<>())
        .put(location, width);
    biomeLocationsDirty = true;
  }

  public void clear() {
    scanStride.set(-1L);
    badKeysCache = new long[0];
    badPrefixSumsCache = new long[0];
    badCauseCache = new byte[0];
    badExpiryCache = new long[0];
    probationKeysCache = new long[0];
    probationPrefixSumsCache = new long[0];
    probationCauseCache = new byte[0];
    probationExpiryCache = new long[0];
    biomeTableVersion.incrementAndGet();
    biomeUnion = BiomeUnionTable.EMPTY;
    badLocationsDirty = true;
    biomeLocationsDirty = true;
  }

  /**
   * Current version of the per-biome run tables. Changes whenever the tables are rebuilt, reloaded
   * or cleared, so a cached gather of {@link #getBiomeKeys} / {@link #getBiomePrefixSums} stays
   * valid exactly while this value is unchanged.
   *
   * @return monotone table version
   */
  public long biomeTableVersion() {
    return biomeTableVersion.get();
  }

  /**
   * Owning biome of each union run, as an index into {@link #getBiomeMappedNamesCache()}.
   *
   * @return the id column, parallel to the mapped key/prefix-sum arrays; never {@code null}
   */
  public short[] getBiomeMappedIdsCache() {
    BiomeUnionTable union = biomeUnion;
    short[] out = new short[union.runCount()];
    for (int i = 0; i < out.length; i++) out[i] = (short) union.idAt(i);
    return out;
  }

  /**
   * Interning table for {@link #getBiomeMappedIdsCache()}.
   *
   * @return canonical biome names indexed by id; never {@code null}
   */
  public String[] getBiomeMappedNamesCache() {
    return biomeUnion.names().clone();
  }

  /**
   * Run start keys recorded for {@code biome}, materialized from the union's per-biome view.
   *
   * <p>Kept as a convenience for callers that want a plain array (the biome menu); the selection
   * path reads {@link #biomeRunView} instead, so nothing on it copies a run table.
   *
   * @param biome the biome name (canonicalised internally)
   * @return a fresh array, or {@code null} when nothing is recorded for {@code biome}
   */
  public long[] getBiomeKeys(String biome) {
    BiomeUnionTable.BiomeView view = biomeRunView(biome);
    if (view == null) return null;
    long[] out = new long[view.length()];
    for (int i = 0; i < out.length; i++) out[i] = view.keyAt(i);
    return out;
  }

  /**
   * Prefix sums of the run widths recorded for {@code biome}, aligned 1:1 with
   * {@link #getBiomeKeys}.
   *
   * @param biome the biome name (canonicalised internally)
   * @return a fresh array, or {@code null} when nothing is recorded for {@code biome}
   */
  public long[] getBiomePrefixSums(String biome) {
    BiomeUnionTable.BiomeView view = biomeRunView(biome);
    if (view == null) return null;
    long[] out = new long[view.length()];
    for (int i = 0; i < out.length; i++) out[i] = view.sumAt(i);
    return out;
  }

  /**
   * Returns saved biome identifier for location run containing {@code (x, z)}, or {@code null}.
   * Reads in-memory persisted cache without chunk I/O.
   *
   * @param x the x coordinate
   * @param z the z coordinate
   * @return canonical biome name, or {@code null}
   */
  public String biomeAt(int x, int z) {
    return biomeAt((long) xzToLocation(x, z));
  }

  /**
   * Returns saved biome identifier for location run containing {@code location}, or {@code null}.
   * Reads in-memory run tables without chunk I/O.
   *
   * <p>Resolved from the union table, which the identity-based merge makes a partition of the
   * recorded space: one binary search plus one id index, so the cost is independent of biome
   * count.
   *
   * @param location the location value
   * @return canonical biome name, or {@code null}
   */
  public String biomeAt(long location) {
    BiomeUnionTable union = biomeUnion;
    if (union.runCount() > 0) {
      int idx = union.runContaining(location);
      if (idx < 0) return null;
      int id = union.idAt(idx);
      String[] unionNames = union.names();
      return (id >= 0 && id < unionNames.length) ? unionNames[id] : null;
    }
    return null;
  }


  /**
   * Total recorded width for {@code biome} across the whole shape, in cells. Reads the last prefix
   * sum, so it is O(1) and triggers no scan or chunk I/O. Use as the normalizer when turning
   * recorded coverage into a per-biome weight.
   *
   * <p>Read from the union's per-biome view, so extents are <em>attributed</em>: a cell claimed by
   * two biomes counts towards exactly the one {@link #biomeAt} reports, per the merge's
   * last-observation-wins rule. Widths across all biomes therefore sum to
   * {@link #getEffectiveGoodCount()} rather than over-counting contested cells, which are an
   * artifact of coarse {@code spatialResolution} aggregation.
   *
   * @param biome the biome name (canonicalised internally)
   * @return recorded cell count, or {@code 0} when the biome has never been observed
   */
  public long biomeWidth(String biome) {
    BiomeUnionTable.BiomeView view = biomeRunView(biome);
    return (view == null) ? 0L : view.totalWidth();
  }

  /**
   * Run view for {@code biome} - start keys plus prefix sums of run width - as the single read
   * surface for the biome-recall draw and the extent queries.
   *
   * <p>Backed by the blocked union's per-biome index view, which is built on first request and
   * cached for the immutable table's lifetime, so a queried biome costs 12 bytes per run instead
   * of the 16 bytes per run a separate per-biome table would hold for every biome. The union is
   * the only stored form - a load builds it directly - so there is no fallback path.
   *
   * @param biome the biome name (canonicalised internally)
   * @return the view, or {@code null} when nothing is recorded for {@code biome}
   */
  public BiomeUnionTable.BiomeView biomeRunView(String biome) {
    String canonical =
        io.github.dailystruggle.rtp.common.selection.region.BiomeNames.canonical(biome);
    BiomeUnionTable union = biomeUnion;
    int id = union.idOf(canonical);
    return (id < 0) ? null : union.viewOf(id);
  }

  /**
   * Recorded width for {@code biome} at 1D indices strictly below {@code location}, in cells.
   * Costs one binary search plus one prefix-sum read. Attributed, as {@link #biomeWidth}.
   *
   * @param biome the biome name (canonicalised internally)
   * @param location exclusive upper bound in the 1D domain
   * @return recorded cell count below {@code location}
   */
  public long biomeWidthBefore(String biome, long location) {
    if (location <= 0L) return 0L;
    BiomeUnionTable.BiomeView view = biomeRunView(biome);
    return (view == null) ? 0L : view.widthBefore(location);
  }

  /**
   * Recorded width for {@code biome} within the half-open 1D range {@code [from, to)}, in cells.
   *
   * <p>The spiral's 1D index is monotone in radius (ADR-001), so a contiguous 1D range is an
   * annulus and this doubles as a spatial density query. Two binary searches, no scan, no chunk
   * I/O. Divide by {@code to - from} for a density fraction.
   *
   * @param biome the biome name (canonicalised internally)
   * @param from inclusive lower bound in the 1D domain
   * @param to exclusive upper bound in the 1D domain
   * @return recorded cell count in range; {@code 0} when the range is empty or inverted
   */
  public long biomeDensity(String biome, long from, long to) {
    if (to <= from) return 0L;
    return biomeWidthBefore(biome, to) - biomeWidthBefore(biome, Math.max(0L, from));
  }

  /**
   * Returns union of biome identifiers observed producing at least one candidate. Reads the
   * union's interning table, populated via Anvil observations.
   *
   * @return unmodifiable set of observed biome identifiers
   */
  public Set<String> getObservedBiomes() {
    return Collections.unmodifiableSet(
        new java.util.LinkedHashSet<>(Arrays.asList(biomeUnion.names())));
  }

  /**
   * Remove a location from biome-specific valid locations
   *
   * @param location the location value
   * @param biome the biome name
   */
  public void removeBiomeLocation(Long location, String biome) {
    String key = io.github.dailystruggle.rtp.common.selection.region.BiomeNames.canonical(biome);
    pendingBiomeRemovals
        .get()
        .computeIfAbsent(key, b -> new ConcurrentHashMap<>())
        .put(location, true);
    biomeLocationsDirty = true;
  }

  /**
   * Get the sum of all bad location segments
   *
   * @return the sum of all bad location segments
   */
  public long getEffectiveBadCount() {
    return totalBadCount.get();
  }

  /**
   * Get the sum of all good location segments
   *
   * @return the sum of all good location segments
   */
  public long getEffectiveGoodCount() {
    return totalBiomeCount.get();
  }

  /**
   * Immutable, human-oriented roll-up of this shape's persistent learned state.
   * Backs the per-region {@code [mem*]} placeholders surfaced by
   * {@code /rtp info region:<name>} (see {@code docs/admin/COMMANDS.md} and the
   * placeholder catalog in {@code PlaceholderProvider}). All figures are derived
   * from the same data {@link #exportDebugJson} writes; no chunk I/O, no scan is
   * triggered.
   *
   * @param range          total candidate cells the shape can address
   * @param badCount        cells currently flagged bad (learned rejects)
   * @param goodCount       cells with a recorded biome (learned good)
   * @param coveragePercent percentage of {@code range} that has been learned
   *                        (bad + good), {@code NaN} when {@code range == 0}
   * @param badPercent      percentage of {@code range} flagged bad, {@code NaN}
   *                        when {@code range == 0}
   * @param topCause        name of the rejection cause covering the most bad
   *                        cells, or {@code "none"} when nothing is flagged
   * @param topCausePercent that cause's share of all bad cells, {@code NaN} when
   *                        nothing is flagged
   */
  public record LearnedStateSummary(
      long range,
      long badCount,
      long goodCount,
      double coveragePercent,
      double badPercent,
      String topCause,
      double topCausePercent) {}

  /**
   * Builds a {@link LearnedStateSummary} from a single write-locked snapshot of
   * the bad-location arrays (the same snapshot strategy as {@link #save} /
   * {@link #exportDebugJson}). The per-cause tally is cell-weighted via the
   * prefix sums, so a long run counts proportionally more than a short one.
   *
   * @return a fresh summary; never {@code null}
   */
  public LearnedStateSummary learnedStateSummary() {
    long[] sBadKeys;
    long[] sBadSums;
    byte[] sBadCauses;
    writeLock.lock();
    try {
      sBadKeys = Arrays.copyOf(badKeysCache, badKeysCache.length);
      sBadSums = Arrays.copyOf(badPrefixSumsCache, badPrefixSumsCache.length);
      sBadCauses = Arrays.copyOf(badCauseCache, badCauseCache.length);
    } finally {
      writeLock.unlock();
    }

    long range = getRange();
    long badCount = getEffectiveBadCount();
    long goodCount = getEffectiveGoodCount();
    long learned = badCount + goodCount;
    double coveragePercent =
        range > 0 ? Math.min(100.0, 100.0 * learned / range) : Double.NaN;
    double badPercent = range > 0 ? 100.0 * badCount / range : Double.NaN;

    LocationGenerator.FailTypes[] failTypes = LocationGenerator.FailTypes.values();
    long[] perCause = new long[failTypes.length];
    long prev = 0L;
    long totalRunCells = 0L;
    for (int i = 0; i < sBadKeys.length; i++) {
      long len = sBadSums[i] - prev;
      prev = sBadSums[i];
      int causeOrd = (i < sBadCauses.length) ? (sBadCauses[i] & 0xFF) : (MISC_CAUSE & 0xFF);
      if (causeOrd < 0 || causeOrd >= failTypes.length) causeOrd = MISC_CAUSE & 0xFF;
      perCause[causeOrd] += len;
      totalRunCells += len;
    }

    String topCause = "none";
    double topCausePercent = Double.NaN;
    long topCells = 0L;
    for (int i = 0; i < perCause.length; i++) {
      if (perCause[i] > topCells) {
        topCells = perCause[i];
        topCause = failTypes[i].name();
      }
    }
    if (totalRunCells > 0) topCausePercent = 100.0 * topCells / totalRunCells;

    return new LearnedStateSummary(
        range, badCount, goodCount, coveragePercent, badPercent, topCause, topCausePercent);
  }

  /**
   * Sift the frontier heap entry at {@code root} down into place.
   *
   * <p>The heap holds biome slot indices ordered by each slot's current run key, i.e. the
   * multi-way merge frontier. Unboxed by design: the merge runs once per rebuild over every
   * recorded run, so a {@code PriorityQueue<Integer>} allocated an {@code Integer} per run.
   *
   * @param heap biome slot indices
   * @param size live heap length
   * @param root index to sift from
   * @param allKeys per-slot run keys, by reference
   * @param indices per-slot cursor into its key array
   */
  private static void siftDownFrontier(
      int[] heap, int size, int root, long[][] allKeys, int[] indices) {
    int node = root;
    while (true) {
      int left = (node << 1) + 1;
      if (left >= size) break;
      int child = left;
      int right = left + 1;
      if (right < size && frontierPrecedes(heap[right], heap[left], allKeys, indices)) {
        child = right;
      }
      if (!frontierPrecedes(heap[child], heap[node], allKeys, indices)) {
        break;
      }
      int swap = heap[node];
      heap[node] = heap[child];
      heap[child] = swap;
      node = child;
    }
  }

  /**
   * Reusable, thread-confined scratch for {@link #flushAndRebuild}.
   *
   * <p>The merge writes several arrays sized to {@code existing + pending} runs and then copies
   * only the live prefix into the exact-fit arrays it publishes. Those intermediates are never
   * published, so they need not be freshly allocated: at one rebuild per attempt they were the
   * bulk of the per-rebuild churn, and past 131072 {@code long} entries each one is a G1
   * humongous allocation straight into old gen.
   *
   * <p>Safe to hold per shape because {@code flushAndRebuild} is serialized by the
   * {@code isRebuilding} CAS - a second thread returns rather than entering the merge - and no
   * reader ever sees these arrays. Capacity only grows, so the tables' own growth amortizes it.
   */
  private static final class RebuildScratch {
    private long[] biomeKeys = new long[0];
    private long[] biomeLengths = new long[0];
    private long[] biomeCutKeys = new long[0];
    private long[] biomeCutLengths = new long[0];
    private long[] unionKeys = new long[0];
    private long[] unionLengths = new long[0];
    private short[] unionIds = new short[0];
    private long[] stageKeys = new long[0];
    private long[] stageLengths = new long[0];
    private short[] stageIds = new short[0];
    private long[] existingKeys = new long[0];
    private long[] existingLengths = new long[0];
    private byte[] existingCauses = new byte[0];
    private long[] existingExpiries = new long[0];
    private long[] mergedKeys = new long[0];
    private long[] mergedLengths = new long[0];
    private byte[] mergedCauses = new byte[0];
    private long[] mergedExpiries = new long[0];
  }

  /** Never published, never read concurrently - see {@link RebuildScratch}. */
  private transient RebuildScratch scratch = new RebuildScratch();

  /**
   * Grow-only capacity check. Doubles rather than exact-fits so a steadily growing table does not
   * reallocate on every rebuild.
   *
   * @param array the current buffer
   * @param capacity required length
   * @return a buffer of at least {@code capacity}; contents beyond the caller's own write index
   *     are undefined
   */
  private static long[] ensureCapacity(long[] array, int capacity) {
    if (array.length >= capacity) return array;
    return new long[Math.max(capacity, array.length << 1)];
  }

  /**
   * Grow-only capacity check for the cause column.
   *
   * @param array the current buffer
   * @param capacity required length
   * @return a buffer of at least {@code capacity}
   */
  private static byte[] ensureCapacity(byte[] array, int capacity) {
    if (array.length >= capacity) return array;
    return new byte[Math.max(capacity, array.length << 1)];
  }

  /**
   * Grow-only capacity check for the union's biome-id column.
   *
   * @param array the current buffer
   * @param capacity required length
   * @return a buffer of at least {@code capacity}
   */
  private static short[] ensureCapacity(short[] array, int capacity) {
    if (array.length >= capacity) return array;
    return new short[Math.max(capacity, array.length << 1)];
  }

  /**
   * Frontier order: ascending run key, and on an equal key the later biome slot first.
   *
   * <p>The tie-break decides which biome keeps a cell claimed by two biomes at the same key, since
   * the union clips whichever run is placed second. Slots are name-sorted, so this is stable
   * across rebuilds.
   *
   * @param a candidate biome slot
   * @param b incumbent biome slot
   * @param allKeys per-slot run keys, by reference
   * @param indices per-slot cursor into its key array
   * @return {@code true} when {@code a} should be popped before {@code b}
   */
  private static boolean frontierPrecedes(int a, int b, long[][] allKeys, int[] indices) {
    long keyA = allKeys[a][indices[a]];
    long keyB = allKeys[b][indices[b]];
    if (keyA != keyB) return keyA < keyB;
    return a > b;
  }

  public void flushAndRebuild(long spatialResolution) {
    setSpatialResolution(spatialResolution);
    if (!badLocationsDirty && !biomeLocationsDirty) return;
    if (isRebuilding.compareAndSet(false, true)) {
      try {
        long currentBadSum = 0L;
        long currentBiomeSum = 0L;
        ConcurrentHashMap<Long, Long> localPendingBad =
            pendingBadLocations.getAndSet(new ConcurrentHashMap<>());
        ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>> localPendingBiome =
            pendingBiomeLocations.getAndSet(new ConcurrentHashMap<>());
        ConcurrentHashMap<String, ConcurrentHashMap<Long, Boolean>> localPendingBiomeRemovals =
            pendingBiomeRemovals.getAndSet(new ConcurrentHashMap<>());

        this.rebuildingBadLocations = localPendingBad;

        // Biome logic (similar to bad locations but per biome).
        // The union is the only stored form of the biome table, so the merge sources come from the
        // previously published union rather than from a second per-biome copy: these maps are
        // local working state for this rebuild, not retained fields.
        if (!localPendingBiome.isEmpty() || !localPendingBiomeRemovals.isEmpty()) {
          BiomeUnionTable sourceUnion = this.biomeUnion;
          String[] sourceNames = sourceUnion.names();
          java.util.HashMap<String, long[]> newBiomeKeysCache = new java.util.HashMap<>();
          java.util.HashMap<String, long[]> newBiomePrefixSumsCache = new java.util.HashMap<>();
          for (int id = 0; id < sourceNames.length; id++) {
            BiomeUnionTable.BiomeView view = sourceUnion.viewOf(id);
            if (view == null || view.length() == 0) continue;
            long[] vKeys = new long[view.length()];
            long[] vSums = new long[view.length()];
            for (int i = 0; i < vKeys.length; i++) {
              vKeys[i] = view.keyAt(i);
              vSums[i] = view.sumAt(i);
            }
            newBiomeKeysCache.put(sourceNames[id], vKeys);
            newBiomePrefixSumsCache.put(sourceNames[id], vSums);
          }

          Set<String> affectedBiomes = new HashSet<>();
          affectedBiomes.addAll(localPendingBiome.keySet());
          affectedBiomes.addAll(localPendingBiomeRemovals.keySet());

          for (String biome : affectedBiomes) {
            long[] currentBiomeKeys = newBiomeKeysCache.getOrDefault(biome, new long[0]);
            long[] currentBiomePrefixSums =
                newBiomePrefixSumsCache.getOrDefault(biome, new long[0]);

            ConcurrentHashMap<Long, Long> additions = localPendingBiome.getOrDefault(biome, new ConcurrentHashMap<>());
            ConcurrentHashMap<Long, Boolean> removals = localPendingBiomeRemovals.getOrDefault(biome, new ConcurrentHashMap<>());

            java.util.List<java.util.Map.Entry<Long, Long>> tempBiomeEntries = new java.util.ArrayList<>();
            for (java.util.Map.Entry<Long, Long> entry : additions.entrySet()) {
                tempBiomeEntries.add(entry);
            }
            long[] pendingBiomeKeys = new long[tempBiomeEntries.size()];
            long[] pendingBiomeLengths = new long[tempBiomeEntries.size()];
            int pbIdx = 0;
            for (java.util.Map.Entry<Long, Long> entry : tempBiomeEntries) {
                pendingBiomeKeys[pbIdx] = entry.getKey();
                pendingBiomeLengths[pbIdx] = entry.getValue();
                pbIdx++;
            }

            // Sort keys while maintaining length mappings
            sortParallelArrays(pendingBiomeKeys, pendingBiomeLengths, 0, pendingBiomeKeys.length - 1);

            int biomeMergeCapacity = currentBiomeKeys.length + pendingBiomeKeys.length;
            scratch.biomeKeys = ensureCapacity(scratch.biomeKeys, biomeMergeCapacity);
            scratch.biomeLengths = ensureCapacity(scratch.biomeLengths, biomeMergeCapacity);
            long[] mKeys = scratch.biomeKeys;
            long[] mLengths = scratch.biomeLengths;
            int mIdx = 0;

            int bi = 0;
            int bj = 0;
            long cStart = -1;
            long cLength = -1;

            while (bi < currentBiomeKeys.length || bj < pendingBiomeKeys.length) {
              long nKey;
              long nLength;

              if (bi < currentBiomeKeys.length && bj < pendingBiomeKeys.length) {
                if (currentBiomeKeys[bi] <= pendingBiomeKeys[bj]) {
                  nKey = currentBiomeKeys[bi];
                  long prevSum = (bi > 0) ? currentBiomePrefixSums[bi - 1] : 0L;
                  nLength = currentBiomePrefixSums[bi] - prevSum;
                  bi++;
                } else {
                  nKey = pendingBiomeKeys[bj];
                  nLength = pendingBiomeLengths[bj];
                  bj++;
                }
              } else if (bi < currentBiomeKeys.length) {
                nKey = currentBiomeKeys[bi];
                long prevSum = (bi > 0) ? currentBiomePrefixSums[bi - 1] : 0L;
                nLength = currentBiomePrefixSums[bi] - prevSum;
                bi++;
              } else {
                nKey = pendingBiomeKeys[bj];
                nLength = pendingBiomeLengths[bj];
                bj++;
              }

              if (nKey < 0) continue;

              if (cStart == -1) {
                cStart = nKey;
                cLength = nLength;
              } else {
                if (nKey <= cStart + cLength + spatialResolution) {
                  cLength = Math.max(cLength, nKey + nLength - cStart);
                } else {
                  mKeys[mIdx] = cStart;
                  mLengths[mIdx] = cLength;
                  mIdx++;
                  cStart = nKey;
                  cLength = nLength;
                }
              }
            }
            if (cStart != -1) {
              mKeys[mIdx] = cStart;
              mLengths[mIdx] = cLength;
              mIdx++;
            }

            // Handle removals
            if (!removals.isEmpty()) {
              java.util.List<Long> tempRemovals = new java.util.ArrayList<>();
              for (Long rLoc : removals.keySet()) {
                tempRemovals.add(rLoc);
              }
              long[] sortedRemovals = new long[tempRemovals.size()];
              for (int k = 0; k < tempRemovals.size(); k++) {
                sortedRemovals[k] = tempRemovals.get(k);
              }
              Arrays.sort(sortedRemovals);

              int cutCapacity = mIdx + sortedRemovals.length;
              scratch.biomeCutKeys = ensureCapacity(scratch.biomeCutKeys, cutCapacity);
              scratch.biomeCutLengths = ensureCapacity(scratch.biomeCutLengths, cutCapacity);
              long[] postRemovalKeys = scratch.biomeCutKeys;
              long[] postRemovalLengths = scratch.biomeCutLengths;
              int prIdx = 0;

              int currentSpanIdx = 0;
              int currentRemIdx = 0;

              while (currentSpanIdx < mIdx) {
                long spanStart = mKeys[currentSpanIdx];
                long spanEnd = spanStart + mLengths[currentSpanIdx];

                long lastStart = spanStart;
                while (currentRemIdx < sortedRemovals.length && sortedRemovals[currentRemIdx] < spanEnd) {
                  long rLoc = sortedRemovals[currentRemIdx];
                  if (rLoc >= lastStart) {
                    if (rLoc > lastStart) {
                      postRemovalKeys[prIdx] = lastStart;
                      postRemovalLengths[prIdx] = rLoc - lastStart;
                      prIdx++;
                    }
                    lastStart = rLoc + 1;
                  }
                  currentRemIdx++;
                }

                if (lastStart < spanEnd) {
                  postRemovalKeys[prIdx] = lastStart;
                  postRemovalLengths[prIdx] = spanEnd - lastStart;
                  prIdx++;
                }
                currentSpanIdx++;
              }

              mKeys = postRemovalKeys;
              mIdx = prIdx;
              mLengths = postRemovalLengths;
            }

            long[] nbKeys = new long[mIdx];
            long[] nbSums = new long[mIdx];
            long bRunningSum = 0;
            for (int k = 0; k < mIdx; k++) {
                nbKeys[k] = mKeys[k];
                bRunningSum += mLengths[k];
                nbSums[k] = bRunningSum;
            }
            newBiomeKeysCache.put(biome, nbKeys);
            newBiomePrefixSumsCache.put(biome, nbSums);
          }

          // 1. Gather every biome's runs by reference. Lengths are derived from the prefix sums
          //    inline during the merge, so no per-biome length array is materialized: that alone
          //    was one O(recorded runs) allocation per rebuild.
          //    Slots are ordered by biome name so the merge's tie-break is deterministic across
          //    runs, which a ConcurrentHashMap iteration order would not be.
          String[] allNames = newBiomeKeysCache.keySet().toArray(new String[0]);
          Arrays.sort(allNames);
          long[][] allKeys = new long[allNames.length][];
          long[][] allSums = new long[allNames.length][];
          String[] slotNames = new String[allNames.length];
          int numBiomes = 0;
          int totalPotentialIntervals = 0;
          for (String biomeName : allNames) {
            long[] keys = newBiomeKeysCache.get(biomeName);
            if (keys == null || keys.length == 0) continue;
            long[] sums = newBiomePrefixSumsCache.get(biomeName);
            if (sums == null || sums.length < keys.length) continue;
            allKeys[numBiomes] = keys;
            allSums[numBiomes] = sums;
            slotNames[numBiomes] = biomeName;
            numBiomes++;
            totalPotentialIntervals += keys.length;
          }

          // 1b. Map each merge slot to a stable id. Slots are dense and name-sorted, so a newly
          //     observed biome would renumber every existing run's id and defeat per-block reuse.
          //     Ids therefore keep the previously published table's numbering and only append.
          BiomeUnionTable prevUnion = this.biomeUnion;
          String[] prevNames = prevUnion.names();
          short[] slotToId = new short[numBiomes];
          String[] unionNames = prevNames;
          int idCount = prevNames.length;
          for (int k = 0; k < numBiomes; k++) {
            int id = -1;
            for (int n = 0; n < idCount; n++) {
              if (unionNames[n].equals(slotNames[k])) {
                id = n;
                break;
              }
            }
            if (id < 0) {
              if (idCount == unionNames.length) {
                unionNames = Arrays.copyOf(unionNames, Math.max(4, idCount + numBiomes));
              }
              unionNames[idCount] = slotNames[k];
              id = idCount++;
            }
            slotToId[k] = (short) id;
          }
          if (unionNames.length != idCount) unionNames = Arrays.copyOf(unionNames, idCount);

          // 2. Perform a multi-way primitive merge. The frontier is an int-keyed binary heap
          //    rather than a PriorityQueue<Integer>, so the merge boxes nothing.
          if (numBiomes > 0) {
            int[] indices = new int[numBiomes];
            int[] heap = new int[numBiomes];
            int heapSize = numBiomes;
            for (int k = 0; k < numBiomes; k++) heap[k] = k;
            for (int k = (heapSize >> 1) - 1; k >= 0; k--) {
              siftDownFrontier(heap, heapSize, k, allKeys, indices);
            }

            scratch.unionKeys = ensureCapacity(scratch.unionKeys, totalPotentialIntervals);
            scratch.unionLengths = ensureCapacity(scratch.unionLengths, totalPotentialIntervals);
            scratch.unionIds = ensureCapacity(scratch.unionIds, totalPotentialIntervals);
            long[] mergedMappedKeys = scratch.unionKeys;
            long[] mergedMappedLengths = scratch.unionLengths;
            short[] mergedMappedIds = scratch.unionIds;
            int mappedIdx = 0;

            long currentStartMapped = -1;
            long currentLengthMapped = -1;
            int currentIdMapped = -1;

            while (heapSize > 0) {
              int bIdx = heap[0];
              int iIdx = indices[bIdx];

              long nextKey = allKeys[bIdx][iIdx];
              long[] bSums = allSums[bIdx];
              long nextLength = bSums[iIdx] - ((iIdx > 0) ? bSums[iIdx - 1] : 0L);

              indices[bIdx] = ++iIdx;
              if (iIdx >= allKeys[bIdx].length) {
                heap[0] = heap[--heapSize];
              }
              if (heapSize > 0) siftDownFrontier(heap, heapSize, 0, allKeys, indices);

              // 3. Union on biome identity, not on proximity alone. Runs of the same biome still
              //    coalesce across a gap of up to spatialResolution; runs of different biomes
              //    never merge, so every union run carries exactly one biome id.
              if (currentStartMapped == -1) {
                currentStartMapped = nextKey;
                currentLengthMapped = nextLength;
                currentIdMapped = bIdx;
                continue;
              }

              long currentEnd = currentStartMapped + currentLengthMapped;
              if (bIdx == currentIdMapped) {
                if (nextKey <= currentEnd + spatialResolution) {
                  currentLengthMapped =
                      Math.max(currentLengthMapped, nextKey + nextLength - currentStartMapped);
                  continue;
                }
              } else {
                // Overlap between different biomes: the run already placed keeps the contested
                // cells and the incoming run is clipped past them. The union therefore stays a
                // partition, so a cell claimed by two biomes is counted once and
                // getEffectiveGoodCount() is unaffected by the identity split. Placement order is
                // key-ascending, with equal keys resolved in favour of the later biome slot.
                if (nextKey + nextLength <= currentEnd) continue;
                if (nextKey < currentEnd) {
                  nextLength = nextKey + nextLength - currentEnd;
                  nextKey = currentEnd;
                }
              }

              mergedMappedKeys[mappedIdx] = currentStartMapped;
              mergedMappedLengths[mappedIdx] = currentLengthMapped;
              mergedMappedIds[mappedIdx] = slotToId[currentIdMapped];
              currentBiomeSum += currentLengthMapped;
              mappedIdx++;
              currentStartMapped = nextKey;
              currentLengthMapped = nextLength;
              currentIdMapped = bIdx;
            }

            if (currentStartMapped != -1) {
              mergedMappedKeys[mappedIdx] = currentStartMapped;
              mergedMappedLengths[mappedIdx] = currentLengthMapped;
              mergedMappedIds[mappedIdx] = slotToId[currentIdMapped];
              currentBiomeSum += currentLengthMapped;
              mappedIdx++;
            }

            // 4. Publish the union as one blocked table. Widths are turned into prefix sums by
            //    the builder, so no exact-fit long[] pair is materialized here any more.
            this.biomeUnion =
                BiomeUnionTable.build(
                    mergedMappedKeys,
                    mergedMappedLengths,
                    mergedMappedIds,
                    mappedIdx,
                    unionNames,
                    prevUnion);
          } else {
            this.biomeUnion = BiomeUnionTable.EMPTY;
          }

          this.biomeTableVersion.incrementAndGet();
        } else {
          currentBiomeSum = biomeUnion.totalWidth();
        }
        this.biomeLocationsDirty = (!pendingBiomeLocations.get().isEmpty() || !pendingBiomeRemovals.get().isEmpty());

        // 4. Read current volatile arrays
        long[] currentBadKeys = badKeysCache;
        long[] currentBadSums = badPrefixSumsCache;
        byte[] currentBadCauses = badCauseCache;
        long[] currentBadExpiries = badExpiryCache;

        long[] currentProbKeys = probationKeysCache;
        long[] currentProbSums = probationPrefixSumsCache;
        byte[] currentProbCauses = probationCauseCache;
        long[] currentProbExpiries = probationExpiryCache;

        // Merge active and probation runs into a single sorted stream of existing runs
        int existingTotal = currentBadKeys.length + currentProbKeys.length;
        scratch.existingKeys = ensureCapacity(scratch.existingKeys, existingTotal);
        scratch.existingLengths = ensureCapacity(scratch.existingLengths, existingTotal);
        scratch.existingCauses = ensureCapacity(scratch.existingCauses, existingTotal);
        scratch.existingExpiries = ensureCapacity(scratch.existingExpiries, existingTotal);
        long[] existingKeys = scratch.existingKeys;
        long[] existingLengths = scratch.existingLengths;
        byte[] existingCauses = scratch.existingCauses;
        long[] existingExpiries = scratch.existingExpiries;
        int exIdx = 0;
        int curA = 0, curP = 0;
        long prevA = 0L, prevP = 0L;
        while (curA < currentBadKeys.length || curP < currentProbKeys.length) {
          if (curA < currentBadKeys.length && curP < currentProbKeys.length) {
            if (currentBadKeys[curA] <= currentProbKeys[curP]) {
              existingKeys[exIdx] = currentBadKeys[curA];
              long s = currentBadSums[curA];
              existingLengths[exIdx] = s - prevA;
              prevA = s;
              existingCauses[exIdx] = (curA < currentBadCauses.length) ? currentBadCauses[curA] : MISC_CAUSE;
              existingExpiries[exIdx] = (curA < currentBadExpiries.length) ? currentBadExpiries[curA] : 0L;
              curA++;
            } else {
              existingKeys[exIdx] = currentProbKeys[curP];
              long s = currentProbSums[curP];
              existingLengths[exIdx] = s - prevP;
              prevP = s;
              existingCauses[exIdx] = (curP < currentProbCauses.length) ? currentProbCauses[curP] : MISC_CAUSE;
              existingExpiries[exIdx] = (curP < currentProbExpiries.length) ? currentProbExpiries[curP] : 0L;
              curP++;
            }
          } else if (curA < currentBadKeys.length) {
            existingKeys[exIdx] = currentBadKeys[curA];
            long s = currentBadSums[curA];
            existingLengths[exIdx] = s - prevA;
            prevA = s;
            existingCauses[exIdx] = (curA < currentBadCauses.length) ? currentBadCauses[curA] : MISC_CAUSE;
            existingExpiries[exIdx] = (curA < currentBadExpiries.length) ? currentBadExpiries[curA] : 0L;
            curA++;
          } else {
            existingKeys[exIdx] = currentProbKeys[curP];
            long s = currentProbSums[curP];
            existingLengths[exIdx] = s - prevP;
            prevP = s;
            existingCauses[exIdx] = (curP < currentProbCauses.length) ? currentProbCauses[curP] : MISC_CAUSE;
            existingExpiries[exIdx] = (curP < currentProbExpiries.length) ? currentProbExpiries[curP] : 0L;
            curP++;
          }
          exIdx++;
        }

        // 5. Merge values from capturedBad into local data with RLE compression.
        //    Each pending entry's map value carries: (causeByte & 0xFF) | (epochSec << 8).
        long[] pendingKeys = new long[localPendingBad.size()];
        long[] pendingCauses = new long[localPendingBad.size()];
        long[] pendingExpiries = new long[localPendingBad.size()];
        {
          int pk = 0;
          for (java.util.Map.Entry<Long, Long> e : localPendingBad.entrySet()) {
            pendingKeys[pk] = e.getKey();
            Long v = e.getValue();
            long val = (v == null) ? MISC_CAUSE : v;
            pendingCauses[pk] = val & 0xFFL;
            pendingExpiries[pk] = val >>> 8;
            pk++;
          }
        }
        // Sort keys while keeping the parallel cause and expiry columns aligned.
        sortParallelThreeArrays(pendingKeys, pendingCauses, pendingExpiries, 0, pendingKeys.length - 1);

        // Buffers are reused and therefore oversized: bound the merge by the live run count, not
        // by array length.
        int existingCount = exIdx;
        int maxMerged = existingCount + pendingKeys.length;
        scratch.mergedKeys = ensureCapacity(scratch.mergedKeys, maxMerged);
        scratch.mergedLengths = ensureCapacity(scratch.mergedLengths, maxMerged);
        scratch.mergedCauses = ensureCapacity(scratch.mergedCauses, maxMerged);
        scratch.mergedExpiries = ensureCapacity(scratch.mergedExpiries, maxMerged);
        long[] mergedKeys = scratch.mergedKeys;
        long[] mergedLengths = scratch.mergedLengths;
        byte[] mergedCauses = scratch.mergedCauses;
        long[] mergedExpiries = scratch.mergedExpiries;
        int mergeIndex = 0;

        int i = 0; // existingKeys index
        int j = 0; // pendingKeys index

        long currentStart = -1;
        long currentLength = -1;
        byte currentCause = MISC_CAUSE;
        long currentExpiry = 0L;

        while (i < existingCount || j < pendingKeys.length) {
          long nextKey;
          long nextLength;
          byte nextCause;
          long nextExpiry;

          if (i < existingCount && j < pendingKeys.length) {
            if (existingKeys[i] <= pendingKeys[j]) {
              nextKey = existingKeys[i];
              nextLength = existingLengths[i];
              nextCause = existingCauses[i];
              nextExpiry = existingExpiries[i];
              i++;
            } else {
              nextKey = pendingKeys[j];
              nextLength = 1L;
              nextCause = (byte) pendingCauses[j];
              nextExpiry = pendingExpiries[j];
              j++;
            }
          } else if (i < existingCount) {
            nextKey = existingKeys[i];
            nextLength = existingLengths[i];
            nextCause = existingCauses[i];
            nextExpiry = existingExpiries[i];
            i++;
          } else {
            nextKey = pendingKeys[j];
            nextLength = 1L;
            nextCause = (byte) pendingCauses[j];
            nextExpiry = pendingExpiries[j];
            j++;
          }

          if (nextKey < 0) continue;

          if (currentStart == -1) {
            currentStart = nextKey;
            currentLength = nextLength;
            currentCause = nextCause;
            currentExpiry = nextExpiry;
          } else {
            boolean adjacent = (nextKey <= currentStart + currentLength + spatialResolution);
            boolean bothStatic = (currentExpiry <= 0L && nextExpiry <= 0L);
            boolean bothDynamic = (currentExpiry > 0L && nextExpiry > 0L);
            boolean sameTier = bothStatic || bothDynamic;

            if (adjacent && sameTier) {
              currentLength = Math.max(currentLength, nextKey + nextLength - currentStart);
              if (bothDynamic) {
                currentExpiry = Math.max(currentExpiry, nextExpiry);
              }
              // first-cause-wins: keep currentCause when runs coalesce (ADR-052).
            } else {
              mergedKeys[mergeIndex] = currentStart;
              mergedLengths[mergeIndex] = currentLength;
              mergedCauses[mergeIndex] = currentCause;
              mergedExpiries[mergeIndex] = currentExpiry;
              mergeIndex++;
              currentStart = nextKey;
              currentLength = nextLength;
              currentCause = nextCause;
              currentExpiry = nextExpiry;
            }
          }
        }

        if (currentStart != -1) {
          mergedKeys[mergeIndex] = currentStart;
          mergedLengths[mergeIndex] = currentLength;
          mergedCauses[mergeIndex] = currentCause;
          mergedExpiries[mergeIndex] = currentExpiry;
          mergeIndex++;
        }

        // 6. Partition merged runs into Active vs Probation vs Evicted based on wall-clock time
        long now = java.time.Instant.now().getEpochSecond();
        int activeCount = 0;
        int probCount = 0;

        for (int k = 0; k < mergeIndex; k++) {
          long exp = mergedExpiries[k];
          if (exp <= 0L || now < exp) {
            activeCount++;
          } else {
            LocationGenerator.FailTypes[] values = LocationGenerator.FailTypes.values();
            byte c = mergedCauses[k];
            LocationGenerator.FailTypes ft = (c >= 0 && c < values.length) ? values[c] : LocationGenerator.FailTypes.misc;
            long ttl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig.resolveTtlSeconds(ft, null);
            long window = (ttl > 0L) ? ttl : 14L * 86400L;
            if (now < exp + window) {
              probCount++;
            }
          }
        }

        long[] newKeys = new long[activeCount];
        long[] newSums = new long[activeCount];
        byte[] newCauses = new byte[activeCount];
        long[] newExpiries = new long[activeCount];

        long[] newProbKeys = new long[probCount];
        long[] newProbSums = new long[probCount];
        byte[] newProbCauses = new byte[probCount];
        long[] newProbExpiries = new long[probCount];

        int aK = 0, pK = 0;
        long runningSum = 0L, runningProbSum = 0L;

        for (int k = 0; k < mergeIndex; k++) {
          long exp = mergedExpiries[k];
          long len = mergedLengths[k];
          byte c = mergedCauses[k];
          long start = mergedKeys[k];

          if (exp <= 0L || now < exp) {
            newKeys[aK] = start;
            runningSum += len;
            newSums[aK] = runningSum;
            newCauses[aK] = c;
            newExpiries[aK] = exp;
            aK++;
          } else {
            LocationGenerator.FailTypes[] values = LocationGenerator.FailTypes.values();
            LocationGenerator.FailTypes ft = (c >= 0 && c < values.length) ? values[c] : LocationGenerator.FailTypes.misc;
            long ttl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig.resolveTtlSeconds(ft, null);
            long window = (ttl > 0L) ? ttl : 14L * 86400L;
            if (now < exp + window) {
              newProbKeys[pK] = start;
              runningProbSum += len;
              newProbSums[pK] = runningProbSum;
              newProbCauses[pK] = c;
              newProbExpiries[pK] = exp;
              pK++;
            }
          }
        }

        currentBadSum = runningSum;

        this.badKeysCache = newKeys;
        this.badPrefixSumsCache = newSums;
        this.badCauseCache = newCauses;
        this.badExpiryCache = newExpiries;

        this.probationKeysCache = newProbKeys;
        this.probationPrefixSumsCache = newProbSums;
        this.probationCauseCache = newProbCauses;
        this.probationExpiryCache = newProbExpiries;

        this.rebuildingBadLocations = null; // Clear the reference only after the arrays update
        this.badLocationsDirty = !pendingBadLocations.get().isEmpty();

        totalBadCount.set(currentBadSum);
        totalBiomeCount.set(currentBiomeSum);
      } finally {
        isRebuilding.set(false);
      }
    }
  }

  /** Canonical {@code mode} values. {@link #mode()} upper-cases before comparing. */
  protected static final String MODE_ACCUMULATE = "ACCUMULATE";

  protected static final String MODE_NEAREST = "NEAREST";
  protected static final String MODE_REROLL = "REROLL";

  private volatile Knobs<E> knobs;
  private volatile boolean expandWarningLogged = false;

  /**
   * The four knobs the shared selection path reads, resolved to enum constants. Shapes expose
   * four different params enums, so the template cannot name constants directly - but it also
   * must not look them up by string per call: the {@link #data} {@code EnumMap} exists precisely
   * to make a knob read an ordinal array index, and a name index in front of it reinstates the
   * hashing the {@code EnumMap} was chosen to avoid. Resolved once per shape instance instead,
   * so {@link #rand()} pays only the {@code EnumMap} reads it paid before the unification.
   */
  private static final class Knobs<E extends Enum<E>> {
    private final E mode;
    private final E expand;
    private final E weight;
    private final E uniquePlacements;

    private Knobs(Class<E> myClass) {
      E m = null;
      E e = null;
      E w = null;
      E u = null;
      // One pass at resolution time. getEnumConstants() clones its array, so it is touched here
      // and nowhere on the selection path.
      for (E value : myClass.getEnumConstants()) {
        switch (value.name().toLowerCase(Locale.ROOT)) {
          case "mode" -> {
            if (m == null) m = value;
          }
          case "expand" -> {
            if (e == null) e = value;
          }
          case "weight" -> {
            if (w == null) w = value;
          }
          case "uniqueplacements" -> {
            if (u == null) u = value;
          }
          default -> {}
        }
      }
      this.mode = m;
      this.expand = e;
      this.weight = w;
      this.uniquePlacements = u;
    }
  }

  /** Benign race: construction is idempotent, and publication is via the volatile field. */
  private Knobs<E> knobs() {
    Knobs<E> cached = knobs;
    if (cached == null) {
      cached = new Knobs<>(myClass);
      knobs = cached;
    }
    return cached;
  }

  /**
   * Resolve a parameter by name rather than by enum constant. Init-time only: the selection path
   * uses {@link #knobs()}. Scans the params enum, so do not call it per selection.
   *
   * @param name parameter name, matched case-insensitively
   * @return the matching constant, or {@code null} if this shape does not expose it
   */
  protected final E keyByName(String name) {
    for (E value : myClass.getEnumConstants()) {
      if (value.name().equalsIgnoreCase(name)) return value;
    }
    return null;
  }

  /**
   * Read a parameter by resolved key.
   *
   * @param key the parameter constant, or {@code null} when the shape does not expose it
   * @param def returned when {@code key} is {@code null} or holds no value
   * @return the configured value, or {@code def}
   */
  private Object paramByKey(E key, Object def) {
    if (key == null) return def;
    EnumMap<E, Object> snapshot = data;
    synchronized (snapshot) {
      Object value = snapshot.get(key);
      return value != null ? value : def;
    }
  }

  /**
   * Read a parameter by name. Init-time convenience; see {@link #keyByName(String)}.
   *
   * @param name parameter name
   * @param def returned when the shape does not expose the parameter or holds no value
   * @return the configured value, or {@code def}
   */
  protected final Object paramByName(String name, Object def) {
    return paramByKey(keyByName(name), def);
  }

  /**
   * @return the configured selection mode, upper-cased. Case normalization is not optional: the
   *     dispatch in {@link #resolve} matches exact strings, so a lower-case {@code mode: nearest}
   *     would otherwise silently degrade to "no repair, no reroll".
   */
  protected final String mode() {
    return paramByKey(knobs().mode, MODE_ACCUMULATE).toString().toUpperCase(Locale.ROOT);
  }

  /** @return true if this shape exposes an {@code expand} knob at all. */
  protected final boolean declaresExpand() {
    return knobs().expand != null;
  }

  /** @return the configured {@code expand} flag, tolerating a string-typed YAML value. */
  protected final boolean expand() {
    Object raw = paramByKey(knobs().expand, Boolean.FALSE);
    if (raw instanceof Boolean b) return b;
    return raw != null && Boolean.parseBoolean(raw.toString());
  }

  /**
   * Whether {@code expand} is meaningful for this shape. Shapes whose 1D range bounds a larger
   * construction than the shape itself (the polygon mask, the ellipse inscribed in its bounding
   * circle) must return {@code false}: expanding past the learned bad runs pushes samples into
   * space {@link #contains(int, int)} rejects, so the knob can only hurt.
   *
   * @return true unless the shape is bounded smaller than its range
   */
  protected boolean supportsExpand() {
    return true;
  }

  /**
   * Force {@code expand} off for shapes that cannot honor it, warning once. Written back into
   * {@code data} rather than merely ignored so {@link #contains(int, int)}, which reads the same
   * knob, stays consistent with the sampling path.
   */
  private void coerceUnsupportedExpand() {
    if (supportsExpand() || !declaresExpand() || !expand()) return;
    E key = knobs().expand;
    if (key != null) set(key, Boolean.FALSE);
    if (!expandWarningLogged) {
      expandWarningLogged = true;
      RTP.log(
          Level.WARNING,
          "["
              + getClass().getSimpleName()
              + "] expand=true is not supported on "
              + getClass().getSimpleName()
              + " shapes; forcing expand=false");
    }
  }

  /**
   * Adjust the sampling range for the learned bad area.
   *
   * <p>Applied only when the shape exposes an {@code expand} knob. Shapes without one (notably
   * {@code Rectangle}) sample their raw range on a plain uniform curve, and introducing the
   * adjustment here would silently change their distribution.
   *
   * @param range the geometric range from {@link #getRange()}
   * @param badSum total learned bad area
   * @param mode the normalized selection mode
   * @return the range to sample against
   */
  protected double adjustRange(double range, long badSum, String mode) {
    if (!declaresExpand()) return range;
    boolean expand = expand();
    boolean accumulate = MODE_ACCUMULATE.equals(mode);
    if (!expand && accumulate) return range - badSum;
    if (expand && !accumulate) return range + badSum;
    return range;
  }

  /**
   * Draw a raw 1D sample in {@code [0, range)}. This is the shape's distribution model and the
   * only part of selection that is expected to differ between shapes.
   *
   * <p>The default is a power curve biased by the {@code weight} knob. Shapes that expose no
   * {@code weight} get {@code weight == 1.0}, i.e. a plain uniform draw.
   *
   * @param range the adjusted range
   * @return the sampled scalar
   */
  protected double sample(double range) {
    E weightKey = knobs().weight;
    double weight = (weightKey == null) ? 1.0 : getNumber(weightKey, 1.0).doubleValue();
    return range * Math.pow(rng().nextDouble(), weight);
  }

  /**
   * Post-selection hook, applied to the resolved location before it is returned. Lets a shape
   * reject a sample its range cannot express (e.g. a polygon mask) without re-implementing
   * {@link #rand()}.
   *
   * @param location the resolved location, or {@code -1} if already rejected
   * @return the location to return, or {@code -1} to request a re-roll
   */
  protected long postProcess(long location) {
    return location;
  }

  /**
   * Get a random location value within the shape.
   *
   * <p>Template method: the shared steps (bad-run snapshot, accumulate resolution, mode dispatch,
   * unique-placement marking) live here, and shapes contribute only {@link #getRange()},
   * {@link #adjustRange}, {@link #sample} and {@link #postProcess}. Deliberately not {@code final}
   * - {@code ChunkyRTPShape} and test doubles substitute their own selection wholesale.
   *
   * @return the random location value, or {@code -1} to request a re-roll
   */
  public long rand() {
    maybeFlushAndRebuild();
    coerceUnsupportedExpand();

    // Snapshot both arrays together to avoid races with concurrent rebuilds where
    // badKeysCache and badPrefixSumsCache may be observed at different lengths.
    long[] keys = badKeysCache;
    long[] sums = badPrefixSumsCache;
    if (keys.length != sums.length) {
      // Length mismatch indicates a concurrent rebuild was observed mid-update.
      // Clamp to the shorter common length to keep indexing safe.
      int common = Math.min(keys.length, sums.length);
      if (keys.length != common) keys = Arrays.copyOf(keys, common);
      if (sums.length != common) sums = Arrays.copyOf(sums, common);
    }
    long badSum = (sums.length > 0) ? sums[sums.length - 1] : 0L;

    String mode = mode();
    double range = adjustRange(getRange(), badSum, mode);
    double res = sample(range);
    return postProcess(resolve(res, range, mode, keys, sums));
  }

  /**
   * Upper bound on how many pending marks {@link #rand()} lets accumulate before rebuilding.
   * Caps the staleness of the learned-state snapshot on shapes that are never pulsed.
   */
  private static final int MAX_PENDING_BEFORE_REBUILD = 256;

  /**
   * Rebuild the learned-state arrays only when the pending batch is large enough to amortize the
   * cost.
   *
   * <p>{@link #flushAndRebuild} is a full merge of the whole run array, so calling it per
   * selection makes bulk selection quadratic once anything marks locations bad on the selection
   * path (see {@code uniquePlacements}). The pulse-level callers - {@code ScanTask},
   * {@code PregenTask}, {@code Region} - already rebuild on their own cadence; this only stops
   * {@code rand()} from forcing one per call.
   *
   * <p>The batch size scales with the existing run count, so a small learned state still
   * rebuilds eagerly (the merge is cheap there) while a large one amortizes the O(runs) merge
   * over proportionally many marks. Correctness does not depend on the timing:
   * {@link #isKnownBad} consults {@code pendingBadLocations} directly, so a pending mark is
   * honored immediately; only the ACCUMULATE bad-area subtraction lags, and it already reads a
   * possibly-stale snapshot by design.
   */
  private void maybeFlushAndRebuild() {
    if (!badLocationsDirty && !biomeLocationsDirty) return;

    boolean badReady = false;
    if (badLocationsDirty) {
      int pending = pendingBadLocations.get().size();
      int batch = Math.min(MAX_PENDING_BEFORE_REBUILD, Math.max(1, badKeysCache.length / 8));
      badReady = pending == 0 || pending >= batch;
    }

    boolean biomeReady = false;
    if (biomeLocationsDirty) {
      int pending = pendingBiomeMarkCount();
      int batch =
          Math.min(MAX_PENDING_BEFORE_REBUILD, Math.max(1, biomeUnion.runCount() / 8));
      biomeReady = pending == 0 || pending >= batch;
    }

    if (!badReady && !biomeReady) return;
    flushAndRebuild(spatialResolution);
  }

  /**
   * Pending biome marks awaiting a merge, additions and removals together.
   *
   * <p>Biome pendings are nested per biome name, so this sums the inner maps rather than reading
   * one size. The map is small (one entry per observed biome), so the walk is bounded by biome
   * count, not by recorded run count.
   *
   * @return total pending biome additions plus removals
   */
  private int pendingBiomeMarkCount() {
    int pending = 0;
    for (ConcurrentHashMap<Long, Long> perBiome : pendingBiomeLocations.get().values()) {
      pending += perBiome.size();
    }
    for (ConcurrentHashMap<Long, Boolean> perBiome : pendingBiomeRemovals.get().values()) {
      pending += perBiome.size();
    }
    return pending;
  }

  /**
   * Rebuild only when the pending batch justifies the O(runs) merge.
   *
   * <p>Callers on a per-attempt path want the learned-state arrays current but cannot afford a
   * full copy-on-write merge for every single observation, because the merge cost tracks the whole
   * existing table rather than the pending set. Staleness is safe: {@link #isKnownBad} consults
   * the pending maps directly, and a biome observation not yet merged only means the recall draw
   * cannot pick it yet.
   *
   * @param spatialResolution the owning region's configured resolution
   */
  public void flushAndRebuildIfNeeded(long spatialResolution) {
    setSpatialResolution(spatialResolution);
    maybeFlushAndRebuild();
  }

  /**
   * Turn a raw sample into a concrete location, applying the mode's bad-location policy.
   *
   * @param res the raw sample
   * @param range the adjusted range
   * @param mode the normalized selection mode
   * @param keys bad-run start keys (snapshot)
   * @param sums bad-run prefix sums (snapshot)
   * @return the resolved location, or {@code -1} to request a re-roll
   */
  private long resolve(double res, double range, String mode, long[] keys, long[] sums) {
    long location;
    if (MODE_ACCUMULATE.equals(mode)) {
      long target = (long) res;
      long currentBadSum = 0;

      // Iterate until the number of bad spots preceding our physical guess stabilizes.
      while (true) {
        // Search physical keys using a physical guess (target + current shift).
        int index = Arrays.binarySearch(keys, target + currentBadSum);

        if (index < 0) {
          // Point is between keys (or after all keys). Invert insertion point.
          index = -index - 1;
        } else {
          // Exact match: the coordinate sits exactly on the start of a bad interval.
          // Force the index forward to include this interval's bad sum.
          index = index + 1;
        }

        // Clamp defensively against the prefix-sums length to avoid AIOOBE if a
        // concurrent rebuild slipped a longer keys snapshot past us.
        if (index > sums.length) index = sums.length;

        long newBadSum = (index > 0) ? sums[index - 1] : 0;

        if (newBadSum == currentBadSum) break;
        currentBadSum = newBadSum;
      }
      location = target + currentBadSum;
    } else {
      location = (long) res;
    }

    if (MODE_NEAREST.equals(mode) && isKnownBad(location)) {
      location = nearestGood(location, range, keys, sums);
    }

    // NEAREST falls through to the REROLL check on purpose: if the nearest-good search
    // could not repair the sample, handing back a known-bad location wastes a full
    // pipeline attempt, so re-roll instead.
    if ((MODE_NEAREST.equals(mode) || MODE_REROLL.equals(mode)) && isKnownBad(location)) {
      return -1;
    }

    int uniqueRadius = uniquePlacementsRadius(paramByKey(knobs().uniquePlacements, 0));
    // addBadChunkRadius: chunk-uniform (uniqueplacements knob) - within a chunk the per-column
    // selection order is deterministic, so re-rolling onto the same chunk produces the
    // same effective placement. Marking the landing chunk (radius 1) prevents that chunk-level
    // re-roll; a larger radius additionally clears the surrounding chunks so placements spread out.
    if (uniqueRadius > 0) addBadChunkRadius(location, uniqueRadius);

    return location;
  }

  /**
   * Snap a known-bad location to whichever end of its bad run is nearer.
   *
   * @return the repaired location, or {@code location} unchanged when the containing run cannot
   *     be identified (the caller then re-rolls)
   */
  private long nearestGood(long location, double range, long[] keys, long[] sums) {
    int idx = Arrays.binarySearch(keys, location);
    int floorIdx = (idx >= 0) ? idx : -(idx + 1) - 1;
    if (floorIdx < 0 || floorIdx >= sums.length) return location;

    long key = keys[floorIdx];
    long prevSum = (floorIdx > 0) ? sums[floorIdx - 1] : 0L;
    long val = sums[floorIdx] - prevSum;

    long lowerGood = key - 1;
    long upperGood = key + val;

    if (lowerGood < 0) return upperGood;
    if (upperGood >= range) return lowerGood;
    return (location - lowerGood < upperGood - location) ? lowerGood : upperGood;
  }

  @Override
  public int[] select() {
    return locationToXZ(rand());
  }

  private void sortParallelArrays(long[] keys, long[] lengths, int left, int right) {
    if (left >= right) return;
    int pivotIdx = left + (right - left) / 2;
    long pivot = keys[pivotIdx];
    int i = left, j = right;
    while (i <= j) {
      while (keys[i] < pivot) i++;
      while (keys[j] > pivot) j--;
      if (i <= j) {
        long tempKey = keys[i];
        keys[i] = keys[j];
        keys[j] = tempKey;
        long tempLen = lengths[i];
        lengths[i] = lengths[j];
        lengths[j] = tempLen;
        i++;
        j--;
      }
    }
    if (left < j) sortParallelArrays(keys, lengths, left, j);
    if (i < right) sortParallelArrays(keys, lengths, i, right);
  }

  private void sortParallelThreeArrays(long[] keys, long[] b, long[] c, int left, int right) {
    if (left >= right) return;
    int pivotIdx = left + (right - left) / 2;
    long pivot = keys[pivotIdx];
    int i = left, j = right;
    while (i <= j) {
      while (keys[i] < pivot) i++;
      while (keys[j] > pivot) j--;
      if (i <= j) {
        long tempKey = keys[i];
        keys[i] = keys[j];
        keys[j] = tempKey;
        long tempB = b[i];
        b[i] = b[j];
        b[j] = tempB;
        long tempC = c[i];
        c[i] = c[j];
        c[j] = tempC;
        i++;
        j--;
      }
    }
    if (left < j) sortParallelThreeArrays(keys, b, c, left, j);
    if (i < right) sortParallelThreeArrays(keys, b, c, i, right);
  }

  @Override
  public boolean contains(int x, int z) {
    long l = xzToLocation(x, z);
    long range = (long) getRange();

    if (supportsExpand() && expand()) {
      long[] sums = badPrefixSumsCache;
      long badSum = (sums.length > 0) ? sums[sums.length - 1] : 0L;
      range += badSum;
    }

    return l >= 0 && l < range;
  }

  @Override
  public MemoryShape<E> clone() {
    MemoryShape<E> shape = (MemoryShape<E>) super.clone();
    shape.badKeysCache = new long[0];
    shape.badPrefixSumsCache = new long[0];
    shape.badCauseCache = new byte[0];
    shape.badExpiryCache = new long[0];
    shape.probationKeysCache = new long[0];
    shape.probationPrefixSumsCache = new long[0];
    shape.probationCauseCache = new byte[0];
    shape.probationExpiryCache = new long[0];
    shape.biomeTableVersion = new AtomicLong();
    shape.biomeUnion = BiomeUnionTable.EMPTY;
    // Scratch is per-instance and mutated during rebuild; sharing it with the clone would let two
    // shapes merge into the same buffers.
    shape.scratch = new RebuildScratch();
    shape.badLocationsDirty = true;
    shape.biomeLocationsDirty = true;
    return shape;
  }
}
