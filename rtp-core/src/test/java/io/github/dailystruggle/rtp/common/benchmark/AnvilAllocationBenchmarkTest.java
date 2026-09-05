package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.anvil.AnvilRegionByteCache;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REQ-RTP-S-005 / ADR-016 / ADR-080 - allocation and GC-pressure cost of the sequential L3 anvil
 * screen, and the measured region-read cost distribution that prices every behavioural model in
 * this tier.
 *
 * <p><b>Opt-in.</b> Tagged {@code simulation}, which {@code test} excludes. Run with {@code
 * ./gradlew :rtp-core:simulationBenchmark}; that task pins heap and collector, without which
 * allocation and GC figures are not comparable between runs.
 *
 * <p><b>Why this exists.</b> {@code AnvilRegionByteCache} pools region-file byte arrays rather than
 * reallocating per probe. A real-server benchmark cannot isolate that: 30-60 minutes of setup and
 * analysis, and the plugin's own allocation is buried under the platform's. Allocation is a
 * property of our code alone, so it is measurable here in seconds, deterministically, against the
 * shipped class - and stays measurable as a regression signal.
 *
 * <p><b>What is honest here and what is not.</b> The pooled-versus-reallocating comparison is a
 * true measurement of shipped code: both arms exercise the same reads over the same files, and the
 * only difference is who owns the buffer. What this test deliberately does <i>not</i> claim is any
 * figure for chunk-loading allocation - {@code LevelChunk}, palettes, heightmaps and NBT decode
 * buffers are platform-owned, and allocating a stand-in array "shaped like a chunk" would be
 * arithmetic dressed as measurement. Those belong to {@code helpers/StressTestRTP}.
 *
 * <p>Assertions are self-consistency only (the probe works, the pool is used, files were read).
 * Nothing here asserts that a particular number is good; a benchmark that fails when we lose is not
 * a measurement.
 */
@Tag("simulation")
@DisplayName("ADR-080 anvil region-read allocation and GC pressure")
class AnvilAllocationBenchmarkTest {

  /** Distinct region files in the working set. Above the cache's 16-entry capacity so the LRU
   * evicts and the buffer pool actually cycles, which is the production steady state. */
  private static final int REGION_FILES =
      Integer.getInteger("rtp.simulation.anvil.regionFiles", 24);

  /** Sequential reads per measured arm. */
  private static final int READS = Integer.getInteger("rtp.simulation.anvil.reads", 240);

  /** Reads discarded before measuring, so JIT and the first page-cache fill are not counted. */
  private static final int WARMUP_READS = Integer.getInteger("rtp.simulation.anvil.warmup", 48);

  /** Bytes per synthetic region file. Real {@code r.X.Z.mca} files run ~2-8 MB; 4 MB is the
   * midpoint and matches the cache's own 4 MB minimum pooled allocation. */
  private static final int REGION_BYTES =
      Integer.getInteger("rtp.simulation.anvil.regionBytes", 4 * 1024 * 1024);

  private static final SimulationReport REPORT = new SimulationReport();

  @AfterAll
  static void writeReport() {
    REPORT.write("anvil-allocation");
  }

  /** Bytes allocated by the current thread, or -1 when the JVM does not expose it. */
  private static long allocatedBytes() {
    java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    if (bean instanceof com.sun.management.ThreadMXBean sun
        && sun.isThreadAllocatedMemorySupported()) {
      return sun.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
    return -1L;
  }

  private static long gcCount() {
    long n = 0;
    for (java.lang.management.GarbageCollectorMXBean b :
        ManagementFactory.getGarbageCollectorMXBeans()) {
      long c = b.getCollectionCount();
      if (c > 0) n += c;
    }
    return n;
  }

  private static long gcMillis() {
    long n = 0;
    for (java.lang.management.GarbageCollectorMXBean b :
        ManagementFactory.getGarbageCollectorMXBeans()) {
      long t = b.getCollectionTime();
      if (t > 0) n += t;
    }
    return n;
  }

  /** Deterministic, incompressible-ish content so the filesystem cannot cheat the read. */
  private static Path[] writeRegionFiles(Path dir) throws IOException {
    Path[] files = new Path[REGION_FILES];
    byte[] buf = new byte[REGION_BYTES];
    Random rng = new Random(20260904L);
    for (int i = 0; i < files.length; i++) {
      rng.nextBytes(buf);
      files[i] = dir.resolve("r." + i + ".0.mca");
      Files.write(files[i], buf);
    }
    return files;
  }

  /** Sum of first bytes, purely to keep the read from being optimised away. */
  private static long consume(byte[] bytes) {
    return bytes == null ? 0L : bytes[0] + bytes[bytes.length / 2];
  }

  @Test
  @DisplayName("pooled region buffers versus reallocate-per-probe: allocation and GC pressure")
  void pooledVersusReallocatingRegionReads(@TempDir Path tmp) throws IOException {
    Path[] files = writeRegionFiles(tmp);

    long probeCheck = allocatedBytes();
    boolean allocSupported = probeCheck >= 0;
    if (!allocSupported) {
      REPORT.note(
          "com.sun.management.ThreadMXBean allocation counters unavailable on this JVM; "
              + "allocation rows omitted and only timing/GC rows are reported.");
    }

    // ---- Arm A: shipped path (pooled buffers, LRU + recycle) -------------------------------
    AnvilRegionByteCache.resetAll();
    long sink = 0L;
    for (int i = 0; i < WARMUP_READS; i++) {
      sink += consume(AnvilRegionByteCache.get(files[i % files.length]));
    }
    AnvilRegionByteCache.resetStats();
    long a0alloc = allocatedBytes();
    long a0gc = gcCount();
    long a0gcMs = gcMillis();
    long a0 = System.nanoTime();
    for (int i = 0; i < READS; i++) {
      sink += consume(AnvilRegionByteCache.get(files[i % files.length]));
    }
    long pooledNanos = System.nanoTime() - a0;
    long pooledAlloc = allocSupported ? allocatedBytes() - a0alloc : -1L;
    long pooledGc = gcCount() - a0gc;
    long pooledGcMs = gcMillis() - a0gcMs;
    AnvilRegionByteCache.Stats stats = AnvilRegionByteCache.stats();
    int poolSize = AnvilRegionByteCache.bufferPoolSize();

    // ---- Arm B: pre-pooling behaviour (fresh array per probe) ------------------------------
    // Files.readAllBytes is exactly what the class's own javadoc describes as the behaviour the
    // cache replaced, so this is the honest counterfactual rather than an invented strawman.
    for (int i = 0; i < WARMUP_READS; i++) {
      sink += consume(Files.readAllBytes(files[i % files.length]));
    }
    long b0alloc = allocatedBytes();
    long b0gc = gcCount();
    long b0gcMs = gcMillis();
    long b0 = System.nanoTime();
    for (int i = 0; i < READS; i++) {
      sink += consume(Files.readAllBytes(files[i % files.length]));
    }
    long reallocNanos = System.nanoTime() - b0;
    long reallocAlloc = allocSupported ? allocatedBytes() - b0alloc : -1L;
    long reallocGc = gcCount() - b0gc;
    long reallocGcMs = gcMillis() - b0gcMs;

    String section = "L3 region read";
    REPORT.add(section, "working set", "region files", Integer.toString(REGION_FILES), Provenance.MEASURED);
    REPORT.add(section, "working set", "bytes per region file", Integer.toString(REGION_BYTES), Provenance.MEASURED);
    REPORT.add(section, "working set", "sequential reads per arm", Integer.toString(READS), Provenance.MEASURED);

    REPORT.add(section, "pooled (shipped)", "us per read", pooledNanos / 1_000.0 / READS, Provenance.MEASURED);
    REPORT.add(section, "reallocate per probe", "us per read", reallocNanos / 1_000.0 / READS, Provenance.MEASURED);

    if (allocSupported) {
      double pooledPer = (double) pooledAlloc / READS;
      double reallocPer = (double) reallocAlloc / READS;
      REPORT.add(section, "pooled (shipped)", "bytes allocated per read", pooledPer, Provenance.MEASURED);
      REPORT.add(section, "reallocate per probe", "bytes allocated per read", reallocPer, Provenance.MEASURED);
      if (pooledPer > 0) {
        REPORT.add(section, "pooling win", "allocation ratio (realloc / pooled)", reallocPer / pooledPer, Provenance.DERIVED);
      }
      REPORT.add(section, "pooling win", "bytes avoided per read", reallocPer - pooledPer, Provenance.DERIVED);
    }

    REPORT.add(section, "pooled (shipped)", "gc collections", Long.toString(pooledGc), Provenance.MEASURED);
    REPORT.add(section, "reallocate per probe", "gc collections", Long.toString(reallocGc), Provenance.MEASURED);
    REPORT.add(section, "pooled (shipped)", "gc millis", Long.toString(pooledGcMs), Provenance.MEASURED);
    REPORT.add(section, "reallocate per probe", "gc millis", Long.toString(reallocGcMs), Provenance.MEASURED);

    REPORT.add(section, "pooled (shipped)", "cache hit rate", stats.hitRate(), Provenance.MEASURED);
    REPORT.add(section, "pooled (shipped)", "cold misses", Long.toString(stats.misses()), Provenance.MEASURED);
    REPORT.add(section, "pooled (shipped)", "avg cold read ms", stats.avgColdMissMs(), Provenance.MEASURED);
    REPORT.add(section, "pooled (shipped)", "buffer pool size after run", Integer.toString(poolSize), Provenance.MEASURED);

    REPORT.note(
        "Page-cache warm on both arms: the working set is written by this test moments earlier, so "
            + "'cold' here means LRU-cold, not disk-cold. Absolute read times are therefore a floor.");
    REPORT.note("Checksum sink (ignore): " + sink);

    // Self-consistency only.
    assertTrue(stats.total() > 0, "the pooled arm must have recorded region reads");
    assertTrue(REPORT.size() > 0, "report must contain rows");
  }

  @Test
  @DisplayName("measured region-read cost distribution (cost oracle calibration)")
  void regionReadCostDistribution(@TempDir Path tmp) throws IOException {
    Path[] files = writeRegionFiles(tmp);

    // Cold arm: the working set (24 x 4 MB = 96 MB) exceeds the 16-entry cache, so every read in
    // a strictly increasing sweep is an LRU miss and pays a real file read.
    AnvilRegionByteCache.resetAll();
    long sink = 0L;
    long[] cold = new long[files.length];
    for (int i = 0; i < files.length; i++) {
      long t = System.nanoTime();
      sink += consume(AnvilRegionByteCache.get(files[i]));
      cold[i] = System.nanoTime() - t;
    }

    // Warm arm: re-read the most recent file repeatedly - always an LRU hit, so this isolates the
    // cache lookup from the read it avoids.
    //
    // Timed in blocks, not per call. A single hit is a stat + map lookup, which on Windows lands
    // below System.nanoTime's effective resolution: per-call sampling here produced values
    // quantised to exactly 62 500 ns and 230 400 ns - artefacts of the platform clock, not of the
    // cache. Blocks of WARM_BLOCK_SIZE amortise the clock above its own granularity, and the
    // spread across blocks still shows the variance a single mean would hide.
    Path hot = files[files.length - 1];
    final int warmBlocks = 64;
    final int warmBlockSize = 512;
    long[] warm = new long[warmBlocks];
    for (int b = 0; b < warmBlocks; b++) {
      long t = System.nanoTime();
      for (int i = 0; i < warmBlockSize; i++) {
        sink += consume(AnvilRegionByteCache.get(hot));
      }
      warm[b] = (System.nanoTime() - t) / warmBlockSize;
    }

    // Attribution arm: the staleness check (isRegularFile + getLastModifiedTime) that get(Path)
    // used to pay on every call and now pays at most once per revalidation window. Timing the two
    // syscalls alone keeps the warm-hit figure above attributable: the gap between this row and
    // the warm row is the throttle's win, and a warm row that regresses back toward this row
    // means the window stopped taking effect.
    final int statBlocks = 32;
    final int statBlockSize = 512;
    long[] stat = new long[statBlocks];
    for (int b = 0; b < statBlocks; b++) {
      long t = System.nanoTime();
      for (int i = 0; i < statBlockSize; i++) {
        if (Files.isRegularFile(hot)) {
          sink += Files.getLastModifiedTime(hot).toMillis();
        }
      }
      stat[b] = (System.nanoTime() - t) / statBlockSize;
    }

    Arrays.sort(cold);
    Arrays.sort(warm);
    Arrays.sort(stat);

    String section = "cost oracle";
    REPORT.add(section, "staleness stat only", "p50 ns per call", stat[stat.length / 2], Provenance.MEASURED);
    if (warm[warm.length / 2] > 0) {
      REPORT.add(
          section,
          "staleness stat only",
          "share of warm hit cost",
          (double) stat[stat.length / 2] / warm[warm.length / 2],
          Provenance.DERIVED);
    }
    REPORT.add(section, "cold (LRU miss)", "p50 us", cold[cold.length / 2] / 1_000.0, Provenance.MEASURED);
    REPORT.add(section, "cold (LRU miss)", "max us", cold[cold.length - 1] / 1_000.0, Provenance.MEASURED);
    REPORT.add(section, "cold (LRU miss)", "samples", Integer.toString(cold.length), Provenance.MEASURED);
    REPORT.add(section, "warm (LRU hit)", "p50 ns per hit", warm[warm.length / 2], Provenance.MEASURED);
    REPORT.add(section, "warm (LRU hit)", "max ns per hit", warm[warm.length - 1], Provenance.MEASURED);
    REPORT.add(section, "warm (LRU hit)", "hits per timed block", Integer.toString(warmBlockSize), Provenance.MEASURED);
    if (warm[warm.length / 2] > 0) {
      REPORT.add(
          section,
          "prefilter leverage",
          "cold read / warm hit ratio",
          (double) cold[cold.length / 2] / warm[warm.length / 2],
          Provenance.DERIVED);
    }
    REPORT.note(
        "These two distributions are the shared cost basis for the behavioural tier: every "
            + "simulated strategy draws from the same samples with the same seed, so differences "
            + "between strategies come only from the operations they choose to perform.");
    REPORT.note("Checksum sink (ignore): " + sink);

    assertTrue(cold[0] > 0, "cold reads must be measurable");
    assertTrue(warm[0] >= 0, "warm reads must be measurable");
  }
}
