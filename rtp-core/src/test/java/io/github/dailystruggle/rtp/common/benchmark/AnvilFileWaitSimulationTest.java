package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import java.io.EOFException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;
import java.util.zip.CRC32;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REQ-RTP-S-005 / ADR-016 / ADR-080 - reproduces the region-file open cost seen in server
 * profiles with the same syscall sequence {@code AnvilSectorReader} issues per probe (open READ,
 * size, positioned sector read, close), and separates thread <i>occupancy</i> from core
 * <i>consumption</i>.
 *
 * <p><b>Opt-in.</b> Tagged {@code simulation}; run with {@code ./gradlew :rtp-core:simulationBenchmark
 * --tests "*AnvilFileWaitSimulationTest*"}.
 *
 * <p><b>Why wall and CPU are both reported.</b> A thread inside a synchronous {@code FileChannel}
 * call is unavailable to its pool either way. Thread CPU time (user + kernel, which includes
 * filesystem filter drivers running in the caller's context) says whether that occupancy also
 * burns a core. {@code cpu share of wall} near 1 means the open is kernel work on our thread;
 * near 0 means the thread is parked waiting on the device or another process (e.g. an AV scan
 * service), which only a wall-clock profiler shows.
 *
 * <p><b>Arms.</b> {@code per-chunk open} is the shipped probe shape with a cached location table;
 * {@code one open per bin} reads the table once and every chunk of the bin through one channel.
 * {@code churn} adds a writer that rewrites chunk sectors through persistent handles, as the
 * server's region storage does on chunk save, so the files are continuously modified while read.
 *
 * <p><b>Prefetch.</b> {@code serial} opens and reads each bin on the pulse thread, then decodes it.
 * {@code prefetch alternate slab} reads bin {@code r+1} on a helper thread into the second of two
 * slabs while bin {@code r} decodes from the first, so the pulse only stalls for the part of the
 * file wait that exceeds decode time. The next bin is known (backlog bin order), so this is
 * read-ahead, not speculation. Identical digests across arms prove the slabs never alias.
 * {@code injected open wait} parks after each open to model device/AV latency the local disk
 * may not exhibit; its rows are {@code MODELED}.
 *
 * <p><b>Limits.</b> Files are written moments before reading, so reads are page-cache warm and
 * device latency is not reproduced unless {@code -Drtp.simulation.fileWait.dir} points at a disk
 * whose working set exceeds RAM or whose cache was dropped. Windows thread CPU time advances in
 * scheduler ticks (~15.6 ms); CPU rows are only meaningful when {@code thread cpu ms total} is
 * well above that. Assertions are self-consistency only.
 */
@Tag("simulation")
@DisplayName("REQ-RTP-S-005 / ADR-016 / ADR-080 region-file open wait: wall versus thread CPU")
class AnvilFileWaitSimulationTest {

  private static final int SECTOR = 4096;

  private static final int REGION_FILES =
      Integer.getInteger("rtp.simulation.fileWait.regionFiles", 8);

  /** Allocated slots per region file (max 1024). */
  private static final int OCCUPIED =
      Math.min(1024, Integer.getInteger("rtp.simulation.fileWait.occupiedChunks", 512));

  private static final int SECTORS_PER_CHUNK =
      Integer.getInteger("rtp.simulation.fileWait.sectorsPerChunk", 2);

  /** Candidates staged per region-file bin; one bin is one verification batch. */
  private static final int BIN =
      Math.min(OCCUPIED, Integer.getInteger("rtp.simulation.fileWait.binSize", 32));

  private static final int ROUNDS = Integer.getInteger("rtp.simulation.fileWait.rounds", 160);

  private static final int ROUNDS_PER_THREAD =
      Integer.getInteger("rtp.simulation.fileWait.roundsPerThread", 40);

  private static final int SPLIT_OPS = Integer.getInteger("rtp.simulation.fileWait.splitOps", 4096);

  /** Mirrors {@code AnvilIoPool} sizing. */
  private static final int THREADS =
      Integer.getInteger(
          "rtp.simulation.fileWait.threads",
          Math.max(8, 2 * Runtime.getRuntime().availableProcessors()));

  private static final long WRITE_INTERVAL_NANOS =
      Long.getLong("rtp.simulation.fileWait.writeIntervalMicros", 2_000L) * 1_000L;

  /** Vanilla {@code sync-chunk-writes=true} opens region files with DSYNC. */
  private static final boolean WRITER_DSYNC =
      Boolean.parseBoolean(System.getProperty("rtp.simulation.fileWait.writerDsync", "true"));

  /** Reopen per write instead of holding handles; models storage without a handle cache. */
  private static final boolean WRITER_REOPEN = Boolean.getBoolean("rtp.simulation.fileWait.writerReopen");

  /** CRC32 passes per chunk run; stand-in for decompress + heightmap decode on the pulse. */
  private static final int DECODE_PASSES = Integer.getInteger("rtp.simulation.fileWait.decodePasses", 16);

  /** Modeled non-CPU wait parked after each open in the injected arm; 0 disables the arm. */
  private static final long INJECT_OPEN_NANOS =
      Long.getLong("rtp.simulation.fileWait.injectOpenMicros", 500L) * 1_000L;

  private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
  private static final boolean CPU_SUPPORTED = enableCpuTime();

  private static final SimulationReport REPORT = new SimulationReport();
  private static final LongAdder SINK = new LongAdder();

  @AfterAll
  static void writeReport() {
    REPORT.note(
        "cpu share of wall ~1: open/read is kernel work on the calling thread (incl. filter "
            + "drivers). ~0: the thread is parked on the device or another process. Both occupy "
            + "the thread; only the first consumes a core.");
    REPORT.note(
        "busy cores = summed thread CPU / elapsed; occupied threads = summed thread wall / "
            + "elapsed. A gap between them is waiting, not compute.");
    REPORT.note(
        "writer: dsync=" + WRITER_DSYNC + ", reopen=" + WRITER_REOPEN + ", interval us="
            + WRITE_INTERVAL_NANOS / 1_000L + "; work dir="
            + System.getProperty("rtp.simulation.fileWait.dir", "<junit temp dir>"));
    REPORT.note(
        "prefetch hides at most min(file wait, decode) per bin and moves file CPU to the helper "
            + "thread rather than removing it; only batching removes opens. injected open wait "
            + "uses parkNanos, which Windows rounds up to its timer tick (~15.6 ms): read 'us file "
            + "wall per bin', not the requested value.");
    if (!CPU_SUPPORTED) {
      REPORT.note("Thread CPU time unsupported on this JVM; CPU rows omitted.");
    }
    REPORT.note("Checksum sink (ignore): " + SINK.sum());
    REPORT.write("anvil-file-wait");
  }

  // ---- tests ---------------------------------------------------------------------------------

  @Test
  @DisplayName("syscall split: open+size+close versus positioned sector read")
  void syscallSplit(@TempDir Path tmp) throws IOException {
    Path dir = workDir(tmp);
    try {
      Fixture fx = writeRegionFiles(dir);
      openOnly(fx, SPLIT_OPS / 4);
      readOnly(fx, SPLIT_OPS / 4);

      Cost open = openOnly(fx, SPLIT_OPS);
      Cost read = readOnly(fx, SPLIT_OPS);
      splitRows("quiet", open, read);

      long writes;
      Cost openChurn;
      Cost readChurn;
      try (ChurnWriter w = new ChurnWriter(fx)) {
        openChurn = openOnly(fx, SPLIT_OPS);
        readChurn = readOnly(fx, SPLIT_OPS);
        writes = w.writes();
      }
      splitRows("churn", openChurn, readChurn);
      REPORT.add("syscall split", "churn", "writer writes", Long.toString(writes), Provenance.MEASURED);

      assertEquals(SPLIT_OPS, open.opens(), "open arm must open once per op");
      assertEquals(1L, read.opens(), "read arm must hold one channel");
    } finally {
      cleanup(tmp, dir);
    }
  }

  @Test
  @DisplayName("bin verification: per-chunk open versus one open per bin (single thread)")
  void binVerification(@TempDir Path tmp) throws IOException {
    Path dir = workDir(tmp);
    try {
      Fixture fx = writeRegionFiles(dir);
      Plan warm = plan(99L, Math.max(1, ROUNDS / 4));
      runPlan(fx, warm, false);
      runPlan(fx, warm, true);

      Plan p = plan(1L, ROUNDS);
      Cost perChunk = runPlan(fx, p, false);
      Cost batched = runPlan(fx, p, true);
      binRows("bin verify", "quiet", perChunk, batched);

      Cost perChunkChurn;
      Cost batchedChurn;
      long writes;
      try (ChurnWriter w = new ChurnWriter(fx)) {
        perChunkChurn = runPlan(fx, p, false);
        batchedChurn = runPlan(fx, p, true);
        writes = w.writes();
      }
      binRows("bin verify", "churn", perChunkChurn, batchedChurn);
      REPORT.add("bin verify", "churn", "writer writes", Long.toString(writes), Provenance.MEASURED);

      assertEquals((long) ROUNDS * BIN, perChunk.chunks());
      assertEquals(perChunk.chunks(), perChunk.opens(), "per-chunk arm opens once per chunk");
      assertEquals(ROUNDS, batched.opens(), "batched arm opens once per bin");
    } finally {
      cleanup(tmp, dir);
    }
  }

  @Test
  @DisplayName("pool contention: per-chunk open versus one open per bin at AnvilIoPool width")
  void poolContention(@TempDir Path tmp) throws Exception {
    Path dir = workDir(tmp);
    try {
      Fixture fx = writeRegionFiles(dir);
      Plan warm = plan(99L, Math.max(1, ROUNDS / 4));
      runPlan(fx, warm, false);
      runPlan(fx, warm, true);

      Concurrent perChunk = runConcurrent(fx, false, 1_000L);
      Concurrent batched = runConcurrent(fx, true, 1_000L);
      poolRows("quiet", perChunk, batched);

      Concurrent perChunkChurn;
      Concurrent batchedChurn;
      long writes;
      try (ChurnWriter w = new ChurnWriter(fx)) {
        perChunkChurn = runConcurrent(fx, false, 1_000L);
        batchedChurn = runConcurrent(fx, true, 1_000L);
        writes = w.writes();
      }
      poolRows("churn", perChunkChurn, batchedChurn);
      REPORT.add("pool contention", "churn", "writer writes", Long.toString(writes), Provenance.MEASURED);

      long expected = (long) THREADS * ROUNDS_PER_THREAD * BIN;
      assertEquals(expected, perChunk.sum().chunks());
      assertEquals(expected, perChunk.sum().opens());
      assertEquals((long) THREADS * ROUNDS_PER_THREAD, batched.sum().opens());
      assertTrue(REPORT.size() > 0);
    } finally {
      cleanup(tmp, dir);
    }
  }

  @Test
  @DisplayName("prefetch: next bin read into the alternate slab while the current bin decodes")
  void prefetchAlternateSlab(@TempDir Path tmp) throws Exception {
    Path dir = workDir(tmp);
    ExecutorService io =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "rtp-sim-prefetch");
              t.setDaemon(true);
              return t;
            });
    try {
      Fixture fx = writeRegionFiles(dir);
      Plan warm = plan(99L, Math.max(1, ROUNDS / 4));
      runPipeline(fx, warm, null, 0L);
      runPipeline(fx, warm, io, 0L);

      Plan p = plan(2L, ROUNDS);
      Pipeline serial = runPipeline(fx, p, null, 0L);
      Pipeline prefetch = runPipeline(fx, p, io, 0L);
      pipelineRows("quiet", Provenance.MEASURED, serial, prefetch);
      assertEquals(serial.digest(), prefetch.digest(), "prefetch must decode the same bytes");

      long writes;
      try (ChurnWriter w = new ChurnWriter(fx)) {
        pipelineRows("churn", Provenance.MEASURED, runPipeline(fx, p, null, 0L), runPipeline(fx, p, io, 0L));
        writes = w.writes();
      }
      REPORT.add("prefetch", "churn", "writer writes", Long.toString(writes), Provenance.MEASURED);

      if (INJECT_OPEN_NANOS > 0L) {
        Pipeline serialWait = runPipeline(fx, p, null, INJECT_OPEN_NANOS);
        Pipeline prefetchWait = runPipeline(fx, p, io, INJECT_OPEN_NANOS);
        REPORT.add("prefetch", "injected open wait", "us parked per open", INJECT_OPEN_NANOS / 1_000.0, Provenance.MODELED);
        pipelineRows("injected open wait", Provenance.MODELED, serialWait, prefetchWait);
        // Churn rewrote sectors since the quiet arms; compare the two post-churn arms only.
        assertEquals(serialWait.digest(), prefetchWait.digest(), "prefetch must decode the same bytes");
      }
      assertEquals(ROUNDS, serial.bins());
    } finally {
      io.shutdownNow();
      cleanup(tmp, dir);
    }
  }

  // ---- arms ----------------------------------------------------------------------------------

  /** open(READ) + size + close per op; no data read. */
  private static Cost openOnly(Fixture fx, int ops) throws IOException {
    long sink = 0L;
    long c0 = cpuNow();
    long t0 = System.nanoTime();
    for (int i = 0; i < ops; i++) {
      try (FileChannel ch = FileChannel.open(fx.files()[i % fx.files().length], StandardOpenOption.READ)) {
        sink += ch.size();
      }
    }
    long wall = System.nanoTime() - t0;
    long cpu = cpuNow() - c0;
    SINK.add(sink);
    return new Cost(ops, ops, wall, cpu);
  }

  /** One held channel, one positioned chunk-run read per op. */
  private static Cost readOnly(Fixture fx, int ops) throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(SECTORS_PER_CHUNK * SECTOR);
    int[] table = fx.offsets()[0];
    long sink = 0L;
    long c0 = cpuNow();
    long t0 = System.nanoTime();
    try (FileChannel ch = FileChannel.open(fx.files()[0], StandardOpenOption.READ)) {
      for (int i = 0; i < ops; i++) {
        readFully(ch, buf, (long) table[i % OCCUPIED] * SECTOR, SECTORS_PER_CHUNK * SECTOR);
        sink += buf.get(0);
      }
    }
    long wall = System.nanoTime() - t0;
    long cpu = cpuNow() - c0;
    SINK.add(sink);
    return new Cost(ops, 1L, wall, cpu);
  }

  /**
   * Per-chunk: cached table, one open per chunk (shipped shape). Batched: one open per bin, the
   * 4 KiB table read through it, then one positioned read per chunk.
   */
  private static Cost runPlan(Fixture fx, Plan p, boolean batched) throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(SECTORS_PER_CHUNK * SECTOR);
    ByteBuffer header = ByteBuffer.allocate(SECTOR);
    long sink = 0L;
    long opens = 0L;
    long chunks = 0L;
    long c0 = cpuNow();
    long t0 = System.nanoTime();
    for (int r = 0; r < p.fileOf().length; r++) {
      int f = p.fileOf()[r];
      Path file = fx.files()[f];
      int[] slots = p.slotsOf()[r];
      if (batched) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
          opens++;
          if (ch.size() < SECTOR) throw new EOFException("short region file: " + file);
          readFully(ch, header, 0L, SECTOR);
          for (int slot : slots) {
            int i = slot * 4;
            int off = ((header.get(i) & 0xFF) << 16) | ((header.get(i + 1) & 0xFF) << 8) | (header.get(i + 2) & 0xFF);
            int count = header.get(i + 3) & 0xFF;
            readFully(ch, buf, (long) off * SECTOR, count * SECTOR);
            sink += buf.get(0);
            chunks++;
          }
        }
      } else {
        int[] table = fx.offsets()[f];
        for (int slot : slots) {
          try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            opens++;
            if (ch.size() < SECTOR) throw new EOFException("short region file: " + file);
            readFully(ch, buf, (long) table[slot] * SECTOR, SECTORS_PER_CHUNK * SECTOR);
            sink += buf.get(0);
            chunks++;
          }
        }
      }
    }
    long wall = System.nanoTime() - t0;
    long cpu = cpuNow() - c0;
    SINK.add(sink);
    return new Cost(chunks, opens, wall, cpu);
  }

  /** One bin's runs plus the I/O cost of reading them; one of two alternating buffers. */
  private static final class Slab {
    final ByteBuffer header = ByteBuffer.allocate(SECTOR);
    final byte[] data = new byte[BIN * SECTORS_PER_CHUNK * SECTOR];
    final int[] off = new int[BIN];
    final int[] len = new int[BIN];
    int n;
    long ioWallNanos;
    long ioCpuNanos;
  }

  /** One open per bin: table, then every run into {@code slab}. Runs on the calling thread. */
  private static Slab load(Fixture fx, Plan p, int r, Slab slab, long injectNanos) throws IOException {
    long c0 = cpuNow();
    long t0 = System.nanoTime();
    Path file = fx.files()[p.fileOf()[r]];
    int[] slots = p.slotsOf()[r];
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
      if (injectNanos > 0L) {
        long end = t0 + injectNanos;
        for (long rem = injectNanos; rem > 0L; rem = end - System.nanoTime()) {
          LockSupport.parkNanos(rem);
        }
      }
      if (ch.size() < SECTOR) throw new EOFException("short region file: " + file);
      ByteBuffer header = slab.header;
      readFully(ch, header, 0L, SECTOR);
      int at = 0;
      for (int k = 0; k < slots.length; k++) {
        int i = slots[k] * 4;
        int sector = ((header.get(i) & 0xFF) << 16) | ((header.get(i + 1) & 0xFF) << 8) | (header.get(i + 2) & 0xFF);
        int bytes = (header.get(i + 3) & 0xFF) * SECTOR;
        if (bytes > SECTORS_PER_CHUNK * SECTOR) throw new IOException("run exceeds slab: " + bytes);
        readFully(ch, ByteBuffer.wrap(slab.data, at, bytes).slice(), (long) sector * SECTOR, bytes);
        slab.off[k] = at;
        slab.len[k] = bytes;
        at += bytes;
      }
      slab.n = slots.length;
    }
    slab.ioWallNanos = System.nanoTime() - t0;
    slab.ioCpuNanos = cpuNow() - c0;
    return slab;
  }

  private static long decode(Slab slab) {
    long digest = 0L;
    CRC32 crc = new CRC32();
    for (int k = 0; k < slab.n; k++) {
      crc.reset();
      for (int pass = 0; pass < DECODE_PASSES; pass++) {
        crc.update(slab.data, slab.off[k], slab.len[k]);
      }
      digest += crc.getValue();
    }
    return digest;
  }

  /**
   * Pulse loop over the plan. {@code io == null}: load inline, then decode. Otherwise bin r+1 is
   * submitted into the other slab before bin r decodes; the pulse blocks only in {@code get()}.
   */
  private static Pipeline runPipeline(Fixture fx, Plan p, ExecutorService io, long injectNanos)
      throws Exception {
    int n = p.fileOf().length;
    Slab[] slabs = {new Slab(), new Slab()};
    long stall = 0L;
    long ioWall = 0L;
    long ioCpu = 0L;
    long digest = 0L;
    long c0 = cpuNow();
    long t0 = System.nanoTime();
    Future<Slab> next = io == null || n == 0 ? null : io.submit(() -> load(fx, p, 0, slabs[0], injectNanos));
    for (int r = 0; r < n; r++) {
      long w0 = System.nanoTime();
      Slab cur;
      if (io == null) {
        cur = load(fx, p, r, slabs[r & 1], injectNanos);
      } else {
        cur = next.get(10, TimeUnit.MINUTES);
        if (r + 1 < n) {
          int nr = r + 1;
          next = io.submit(() -> load(fx, p, nr, slabs[nr & 1], injectNanos));
        }
      }
      stall += System.nanoTime() - w0;
      ioWall += cur.ioWallNanos;
      ioCpu += cur.ioCpuNanos;
      digest += decode(cur);
    }
    long elapsed = System.nanoTime() - t0;
    long pulseCpu = cpuNow() - c0;
    return new Pipeline(n, elapsed, stall, pulseCpu, ioWall, ioCpu, digest);
  }

  private static Concurrent runConcurrent(Fixture fx, boolean batched, long seed) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      CountDownLatch ready = new CountDownLatch(THREADS);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Cost>> futures = new ArrayList<>(THREADS);
      for (int t = 0; t < THREADS; t++) {
        Plan p = plan(seed + t, ROUNDS_PER_THREAD);
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return runPlan(fx, p, batched);
                }));
      }
      ready.await();
      long t0 = System.nanoTime();
      go.countDown();
      Cost sum = Cost.ZERO;
      for (Future<Cost> f : futures) {
        sum = sum.plus(f.get(10, TimeUnit.MINUTES));
      }
      return new Concurrent(sum, System.nanoTime() - t0);
    } finally {
      pool.shutdownNow();
    }
  }

  // ---- reporting -----------------------------------------------------------------------------

  private static void splitRows(String phase, Cost open, Cost read) {
    String section = "syscall split";
    costRows(section, phase + " open+size+close", "op", open);
    costRows(section, phase + " positioned read", "op", read);
    double openWall = (double) open.wallNanos() / open.chunks();
    double readWall = (double) read.wallNanos() / read.chunks();
    if (openWall + readWall > 0) {
      REPORT.add(section, phase, "open share of open+read (wall)", openWall / (openWall + readWall), Provenance.DERIVED);
    }
    if (CPU_SUPPORTED) {
      double openCpu = (double) open.cpuNanos() / open.chunks();
      double readCpu = (double) read.cpuNanos() / read.chunks();
      if (openCpu + readCpu > 0) {
        REPORT.add(section, phase, "open share of open+read (cpu)", openCpu / (openCpu + readCpu), Provenance.DERIVED);
      }
    }
  }

  private static void binRows(String section, String phase, Cost perChunk, Cost batched) {
    costRows(section, phase + " per-chunk open", "chunk", perChunk);
    costRows(section, phase + " one open per bin", "chunk", batched);
    if (batched.wallNanos() > 0) {
      REPORT.add(section, phase, "wall ratio (per-chunk / batched)", (double) perChunk.wallNanos() / batched.wallNanos(), Provenance.DERIVED);
    }
    if (CPU_SUPPORTED && batched.cpuNanos() > 0) {
      REPORT.add(section, phase, "cpu ratio (per-chunk / batched)", (double) perChunk.cpuNanos() / batched.cpuNanos(), Provenance.DERIVED);
    }
  }

  private static void poolRows(String phase, Concurrent perChunk, Concurrent batched) {
    String section = "pool contention";
    REPORT.add(section, phase, "threads", Integer.toString(THREADS), Provenance.MEASURED);
    concurrentRows(section, phase + " per-chunk open", perChunk);
    concurrentRows(section, phase + " one open per bin", batched);
    if (batched.elapsedNanos() > 0) {
      REPORT.add(section, phase, "throughput ratio (batched / per-chunk)", (double) perChunk.elapsedNanos() / batched.elapsedNanos(), Provenance.DERIVED);
    }
  }

  private static void pipelineRows(String phase, Provenance tier, Pipeline serial, Pipeline prefetch) {
    String section = "prefetch";
    Provenance derived = tier == Provenance.MODELED ? Provenance.MODELED : Provenance.DERIVED;
    pipelineCost(section, phase + " serial", tier, serial);
    pipelineCost(section, phase + " prefetch alternate slab", tier, prefetch);
    if (prefetch.elapsedNanos() > 0) {
      REPORT.add(section, phase, "pulse speedup (serial / prefetch)", (double) serial.elapsedNanos() / prefetch.elapsedNanos(), derived);
    }
    if (serial.stallNanos() > 0) {
      REPORT.add(section, phase, "file wait hidden from pulse", 1.0 - (double) prefetch.stallNanos() / serial.stallNanos(), derived);
    }
  }

  private static void pipelineCost(String section, String subject, Provenance tier, Pipeline c) {
    double bins = Math.max(1L, c.bins());
    REPORT.add(section, subject, "bins", Long.toString(c.bins()), tier);
    REPORT.add(section, subject, "us pulse wall per bin", c.elapsedNanos() / 1_000.0 / bins, tier);
    REPORT.add(section, subject, "us pulse stalled on file per bin", c.stallNanos() / 1_000.0 / bins, tier);
    REPORT.add(section, subject, "us file wall per bin", c.ioWallNanos() / 1_000.0 / bins, tier);
    if (CPU_SUPPORTED) {
      REPORT.add(section, subject, "us pulse cpu per bin", c.pulseCpuNanos() / 1_000.0 / bins, tier);
      REPORT.add(section, subject, "us file cpu per bin", c.ioCpuNanos() / 1_000.0 / bins, tier);
    }
  }

  private static void concurrentRows(String section, String subject, Concurrent c) {
    costRows(section, subject, "chunk", c.sum());
    double elapsed = c.elapsedNanos();
    REPORT.add(section, subject, "elapsed ms", elapsed / 1e6, Provenance.MEASURED);
    REPORT.add(section, subject, "chunks per s", c.sum().chunks() / (elapsed / 1e9), Provenance.DERIVED);
    REPORT.add(section, subject, "occupied threads", c.sum().wallNanos() / elapsed, Provenance.DERIVED);
    if (CPU_SUPPORTED) {
      REPORT.add(section, subject, "busy cores", c.sum().cpuNanos() / elapsed, Provenance.DERIVED);
    }
  }

  private static void costRows(String section, String subject, String unit, Cost c) {
    REPORT.add(section, subject, unit + "s", Long.toString(c.chunks()), Provenance.MEASURED);
    REPORT.add(section, subject, "opens per " + unit, (double) c.opens() / c.chunks(), Provenance.MEASURED);
    REPORT.add(section, subject, "us wall per " + unit, c.wallNanos() / 1_000.0 / c.chunks(), Provenance.MEASURED);
    if (CPU_SUPPORTED) {
      REPORT.add(section, subject, "us thread cpu per " + unit, c.cpuNanos() / 1_000.0 / c.chunks(), Provenance.MEASURED);
      REPORT.add(section, subject, "thread cpu ms total", c.cpuNanos() / 1e6, Provenance.MEASURED);
      if (c.wallNanos() > 0) {
        REPORT.add(section, subject, "cpu share of wall", (double) c.cpuNanos() / c.wallNanos(), Provenance.DERIVED);
      }
    }
  }

  // ---- fixture -------------------------------------------------------------------------------

  private record Fixture(Path[] files, int[][] offsets) {}

  private record Plan(int[] fileOf, int[][] slotsOf) {}

  private record Cost(long chunks, long opens, long wallNanos, long cpuNanos) {
    static final Cost ZERO = new Cost(0L, 0L, 0L, 0L);

    Cost plus(Cost o) {
      return new Cost(chunks + o.chunks, opens + o.opens, wallNanos + o.wallNanos, cpuNanos + o.cpuNanos);
    }
  }

  private record Concurrent(Cost sum, long elapsedNanos) {}

  private record Pipeline(
      long bins, long elapsedNanos, long stallNanos, long pulseCpuNanos, long ioWallNanos, long ioCpuNanos, long digest) {}

  /** Valid location table (slot i at sector 2 + i * SECTORS_PER_CHUNK), random chunk payload. */
  private static Fixture writeRegionFiles(Path dir) throws IOException {
    Path[] files = new Path[REGION_FILES];
    int[][] offsets = new int[REGION_FILES][OCCUPIED];
    Random rng = new Random(20261008L);
    byte[] payload = new byte[OCCUPIED * SECTORS_PER_CHUNK * SECTOR];
    for (int f = 0; f < REGION_FILES; f++) {
      ByteBuffer header = ByteBuffer.allocate(2 * SECTOR);
      for (int slot = 0; slot < OCCUPIED; slot++) {
        int off = 2 + slot * SECTORS_PER_CHUNK;
        offsets[f][slot] = off;
        header.put(slot * 4, (byte) (off >>> 16));
        header.put(slot * 4 + 1, (byte) (off >>> 8));
        header.put(slot * 4 + 2, (byte) off);
        header.put(slot * 4 + 3, (byte) SECTORS_PER_CHUNK);
      }
      rng.nextBytes(payload);
      files[f] = dir.resolve("r." + f + ".0.mca");
      try (FileChannel ch =
          FileChannel.open(files[f], StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        writeFully(ch, header, 0L);
        writeFully(ch, ByteBuffer.wrap(payload), 2L * SECTOR);
      }
    }
    return new Fixture(files, offsets);
  }

  /** {@code rounds} bins: a random file and BIN distinct occupied slots each. */
  private static Plan plan(long seed, int rounds) {
    Random rng = new Random(seed);
    int[] pool = IntStream.range(0, OCCUPIED).toArray();
    int[] fileOf = new int[rounds];
    int[][] slotsOf = new int[rounds][];
    for (int r = 0; r < rounds; r++) {
      fileOf[r] = rng.nextInt(REGION_FILES);
      for (int k = 0; k < BIN; k++) {
        int j = k + rng.nextInt(OCCUPIED - k);
        int tmp = pool[k];
        pool[k] = pool[j];
        pool[j] = tmp;
      }
      slotsOf[r] = Arrays.copyOf(pool, BIN);
    }
    return new Plan(fileOf, slotsOf);
  }

  private static Path workDir(Path tmp) throws IOException {
    String override = System.getProperty("rtp.simulation.fileWait.dir");
    if (override == null || override.isBlank()) return tmp;
    Path base = Paths.get(override);
    Files.createDirectories(base);
    return Files.createTempDirectory(base, "rtp-file-wait-");
  }

  /** Removes an override work dir; JUnit owns the temp dir. */
  private static void cleanup(Path tmp, Path dir) throws IOException {
    if (dir.equals(tmp)) return;
    try (var entries = Files.list(dir)) {
      for (Path p : (Iterable<Path>) entries::iterator) {
        Files.deleteIfExists(p);
      }
    }
    Files.deleteIfExists(dir);
  }

  // ---- io helpers ----------------------------------------------------------------------------

  private static void readFully(FileChannel ch, ByteBuffer buf, long pos, int len) throws IOException {
    buf.clear().limit(len);
    long at = pos;
    while (buf.hasRemaining()) {
      int n = ch.read(buf, at);
      if (n < 0) throw new EOFException("short read at " + at);
      at += n;
    }
  }

  private static void writeFully(FileChannel ch, ByteBuffer buf, long pos) throws IOException {
    long at = pos;
    while (buf.hasRemaining()) {
      at += ch.write(buf, at);
    }
  }

  private static boolean enableCpuTime() {
    if (!CPU.isCurrentThreadCpuTimeSupported()) return false;
    try {
      if (!CPU.isThreadCpuTimeEnabled()) CPU.setThreadCpuTimeEnabled(true);
    } catch (UnsupportedOperationException | SecurityException e) {
      return false;
    }
    return CPU.isThreadCpuTimeEnabled();
  }

  private static long cpuNow() {
    return CPU_SUPPORTED ? CPU.getCurrentThreadCpuTime() : 0L;
  }

  /** Rewrites random chunk sectors while readers run, as region storage does on chunk save. */
  private static final class ChurnWriter implements AutoCloseable {
    private final Fixture fx;
    private final Thread thread;
    private final AtomicLong writes = new AtomicLong();
    private volatile boolean stop;
    private volatile IOException failure;

    ChurnWriter(Fixture fx) {
      this.fx = fx;
      this.thread = new Thread(this::run, "rtp-sim-churn-writer");
      this.thread.setDaemon(true);
      this.thread.start();
    }

    private static OpenOption[] options() {
      return WRITER_DSYNC
          ? new OpenOption[] {StandardOpenOption.WRITE, StandardOpenOption.DSYNC}
          : new OpenOption[] {StandardOpenOption.WRITE};
    }

    private void run() {
      Random rng = new Random(7L);
      ByteBuffer sector = ByteBuffer.allocate(SECTOR);
      FileChannel[] held = new FileChannel[fx.files().length];
      try {
        if (!WRITER_REOPEN) {
          for (int i = 0; i < held.length; i++) {
            held[i] = FileChannel.open(fx.files()[i], options());
          }
        }
        while (!stop) {
          int f = rng.nextInt(held.length);
          long pos = (long) fx.offsets()[f][rng.nextInt(OCCUPIED)] * SECTOR;
          rng.nextBytes(sector.array());
          sector.clear();
          if (WRITER_REOPEN) {
            try (FileChannel ch = FileChannel.open(fx.files()[f], options())) {
              writeFully(ch, sector, pos);
            }
          } else {
            writeFully(held[f], sector, pos);
          }
          writes.incrementAndGet();
          LockSupport.parkNanos(WRITE_INTERVAL_NANOS);
        }
      } catch (IOException e) {
        failure = e;
      } finally {
        for (FileChannel ch : held) {
          if (ch == null) continue;
          try {
            ch.close();
          } catch (IOException ignored) {
            // Close failure on a test fixture cannot affect measured rows.
          }
        }
      }
    }

    long writes() {
      return writes.get();
    }

    @Override
    public void close() throws IOException {
      stop = true;
      try {
        thread.join(30_000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (failure != null) throw failure;
    }
  }
}
