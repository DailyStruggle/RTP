package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.anvil.AnvilReader;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.IntegerParameter;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test chunk-probe-perf} &mdash; A/B benchmark of
 * {@link RTPWorld#probeChunkColumn(int, int, int, int)} vs full chunk load,
 * sampled across random {@code r.X.Z.mca} files (random sampling avoids
 * the misleading ~0.95x ratio you get from re-hitting the live chunk cache).
 *
 * <p>Reports two "full" timings:
 * <ul>
 *   <li>{@code full}: worker-thread wall latency of {@code loadLiveChunk().join()}.
 *       Routes through {@code loadLiveChunk} (NOT {@code getChunkAtAsync}) to bypass
 *       the ADR-016 anvil prefilter short-circuit, which would otherwise resolve
 *       from {@code AnvilRegionByteCache} and under-attribute CPU by ~170×.</li>
 *   <li>{@code full(cpu)}: server/region-thread inner timing of {@code World#getChunkAt},
 *       capped at {@link #CPU_SAMPLES_CAP} samples to bound main-thread occupancy.
 *       Omitted on adapters without the helper (Fabric, test doubles).</li>
 * </ul>
 *
 * <p>Read-only; runs on the async scheduler (S-005 compliant) and emits a single
 * INFO summary (S-004).
 */
public class TestChunkProbePerfCmd extends BaseRTPCmdImpl {

  static final int MIN_SAMPLES = 1;
  static final int MAX_SAMPLES = 4096;
  static final int DEFAULT_SAMPLES = 256;

  /** Server/region-thread sample cap for the {@code full(cpu)} stage. 64 × ~150 ms
   *  ≈ ~10 s worst-case on Spigot; near-instant on Paper/Folia. */
  static final int CPU_SAMPLES_CAP = 64;

  /** Matches vanilla region filenames {@code r.<cx>.<cz>.mca}. */
  private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

  public TestChunkProbePerfCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter(
        "samples",
        new IntegerParameter(
            "rtp.test",
            "number of pregenerated chunks to time (1.." + MAX_SAMPLES + ")",
            (uuid, s) -> true));
  }

  @Override
  public String name() {
    return "chunk-probe-perf";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "A/B timing across a random sample of pregenerated chunks"
        + " (probeChunkColumn vs getChunkAtAsync)";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    RTPWorld<?> resolvedWorld = null;

    RTPPlayer caller = RTP.serverAccessor.getPlayer(callerId);
    if (caller != null) {
      RTPLocation loc = caller.getLocation();
      if (loc != null && loc.world() != null) {
        resolvedWorld = loc.world();
      }
    }

    if (resolvedWorld == null) {
      List<RTPWorld<?>> worlds = RTP.serverAccessor.getRTPWorlds();
      resolvedWorld = worlds.stream().filter(Objects::nonNull).findFirst().orElse(null);
      if (resolvedWorld == null) {
        String msg =
            "&c[RTP test/chunk-probe-perf] no RTP worlds configured — cannot pick a default";
        if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
          RTP.serverAccessor.sendMessage(callerId, msg);
        }
        RTP.log(Level.WARNING, msg);
        return true;
      }
    }

    final RTPWorld<?> world = resolvedWorld;
    final int minY = world.getMinHeight();
    final int maxY = world.getMaxHeight() - 1;
    final int samples =
        clamp(
            TestStressCmd.parseFirst(parameterValues.get("samples"), DEFAULT_SAMPLES),
            MIN_SAMPLES,
            MAX_SAMPLES);

    String startMsg =
        "[RTP test/chunk-probe-perf] starting: world="
            + world.name()
            + " samples="
            + samples
            + " yWindow=["
            + minY
            + ","
            + maxY
            + "] (random sample of pregenerated chunks)";
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, startMsg);
    }
    RTP.log(Level.INFO, startMsg);

    RTP.scheduler.runTaskAsynchronously(
        () -> runBenchmark(callerId, world, minY, maxY, samples));
    return true;
  }

  private static void runBenchmark(
      UUID callerId, RTPWorld<?> world, int minY, int maxY, int samples) {
    File regionDir = resolveRegionFolder(world);
    if (regionDir == null || !regionDir.isDirectory()) {
      String msg =
          "[RTP test/chunk-probe-perf] could not resolve region folder for world="
              + world.name()
              + " — aborting";
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
      RTP.log(Level.WARNING, msg);
      return;
    }

    List<long[]> pool;
    try {
      pool = collectPregeneratedChunks(regionDir, samples);
    } catch (Throwable t) {
      String msg =
          "[RTP test/chunk-probe-perf] failed to scan region folder "
              + regionDir
              + ": "
              + t.getClass().getSimpleName()
              + ": "
              + t.getMessage();
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
      RTP.log(Level.WARNING, msg, t);
      return;
    }

    if (pool.isEmpty()) {
      String msg =
          "[RTP test/chunk-probe-perf] no pregenerated chunks found in "
              + regionDir
              + " — nothing to sample";
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
      RTP.log(Level.INFO, msg);
      return;
    }

    // Bounded reservoir: pool already capped to ~samples * oversample; shuffle + take.
    Collections.shuffle(pool, new Random());
    int effective = Math.min(samples, pool.size());

    long probeTotalNs = 0L;
    long fullTotalNs = 0L;
    long anvilTotalNs = 0L;
    int probeNulls = 0;
    int probeFailures = 0;
    int fullFailures = 0;
    int anvilFailures = 0;
    int anvilSamples = 0;

    for (int i = 0; i < effective; i++) {
      long[] xz = pool.get(i);
      int cx = (int) xz[0];
      int cz = (int) xz[1];

      long t0 = System.nanoTime();
      try {
        ChunkColumnProbe p = world.probeChunkColumn(cx, cz, minY, maxY).join();
        probeTotalNs += System.nanoTime() - t0;
        if (p == null) probeNulls++;
      } catch (Throwable t) {
        probeTotalNs += System.nanoTime() - t0;
        probeFailures++;
      }

      long t1 = System.nanoTime();
      try {
        // ADR-016 fix: route through the shared live-load entry point
        // (BukkitRTPWorld#loadLiveChunk / FoliaRTPWorld#loadLiveChunk),
        // the same method getChunkAt's UNKNOWN fall-through invokes, so
        // this measurement is an actual live chunk load, not the
        // prefilter's cached-anvil-view republish path. Calling
        // world.getChunkAtAsync on a vanilla world with prefilter enabled
        // would short-circuit through the warmed AnvilRegionByteCache and
        // under-report live-load cost by ~170×, breaking StressTestRTP /
        // MetricsRecorder calibration. Falls back to getChunkAtAsync for
        // platforms / test doubles that don't expose the helper (Fabric).
        java.util.concurrent.CompletableFuture<?> fullFuture =
            invokeBenchmarkLiveLoad(world, cx, cz);
        if (fullFuture == null) {
          fullFuture = world.getChunkAtAsync(cx, cz);
        }
        fullFuture.join();
        fullTotalNs += System.nanoTime() - t1;
      } catch (Throwable t) {
        fullTotalNs += System.nanoTime() - t1;
        fullFailures++;
      }

      // Full-anvil-scan path: read .mca bytes + full-decode chunk NBT tree via
      // AnvilReader.readChunk. Represents the legacy "offline full decode" cost
      // that the probe path replaces.
      File regionFile = regionFileForSample(regionDir, cx, cz);
      if (regionFile != null && regionFile.isFile()) {
        // AnvilReader.readChunk expects region-local (0..31) chunk coords, not
        // world-space - mirrors AnvilPrefilter.probeSyncDetailed's convention.
        int rxLocal = Math.floorMod(cx, 32);
        int rzLocal = Math.floorMod(cz, 32);
        long t2 = System.nanoTime();
        try {
          byte[] bytes = Files.readAllBytes(regionFile.toPath());
          AnvilReader.readChunk(bytes, rxLocal, rzLocal);
          anvilTotalNs += System.nanoTime() - t2;
          anvilSamples++;
        } catch (Throwable t) {
          anvilTotalNs += System.nanoTime() - t2;
          anvilFailures++;
          anvilSamples++;
        }
      }
    }

    // full(cpu): server-thread inner timing of World#getChunkAt, capped at
    // CPU_SAMPLES_CAP so we don't stall the primary thread for the entire pool.
    // Returns {totalNs, samples, failures}; all-zero if the platform doesn't
    // expose a usable primary-thread hook (Fabric, test doubles).
    long[] cpuStats = measureCpuChunkLoad(world, pool, Math.min(effective, CPU_SAMPLES_CAP));
    long fullCpuTotalNs = cpuStats[0];
    int fullCpuSamples = (int) cpuStats[1];
    int fullCpuFailures = (int) cpuStats[2];

    long probeAvgNs = effective == 0 ? 0 : probeTotalNs / effective;
    long fullAvgNs = effective == 0 ? 0 : fullTotalNs / effective;
    long anvilAvgNs = anvilSamples == 0 ? 0 : anvilTotalNs / anvilSamples;
    long fullCpuAvgNs = fullCpuSamples == 0 ? 0 : fullCpuTotalNs / fullCpuSamples;
    double ratio = probeAvgNs == 0 ? 0.0 : (double) fullAvgNs / (double) probeAvgNs;
    double anvilRatio = probeAvgNs == 0 ? 0.0 : (double) anvilAvgNs / (double) probeAvgNs;
    double fullOverAnvil = anvilAvgNs == 0 ? 0.0 : (double) fullAvgNs / (double) anvilAvgNs;
    double fullCpuOverAnvil =
        anvilAvgNs == 0 ? 0.0 : (double) fullCpuAvgNs / (double) anvilAvgNs;
    double nullRate = effective == 0 ? 0.0 : (double) probeNulls / (double) effective;

    String summary =
        String.format(
            "[RTP test/chunk-probe-perf] done: poolSize=%d samples=%d"
                + " probe total=%dms avg=%dµs failures=%d nullRate=%.3f"
                + " full  total=%dms avg=%dµs failures=%d"
                + " full(cpu) total=%dms avg=%dµs failures=%d samples=%d"
                + " anvil total=%dms avg=%dµs failures=%d samples=%d"
                + " full/probe=%.2fx anvil/probe=%.2fx full/anvil=%.2fx"
                + " full(cpu)/anvil=%.2fx",
            pool.size(),
            effective,
            TimeUnit.NANOSECONDS.toMillis(probeTotalNs),
            TimeUnit.NANOSECONDS.toMicros(probeAvgNs),
            probeFailures,
            nullRate,
            TimeUnit.NANOSECONDS.toMillis(fullTotalNs),
            TimeUnit.NANOSECONDS.toMicros(fullAvgNs),
            fullFailures,
            TimeUnit.NANOSECONDS.toMillis(fullCpuTotalNs),
            TimeUnit.NANOSECONDS.toMicros(fullCpuAvgNs),
            fullCpuFailures,
            fullCpuSamples,
            TimeUnit.NANOSECONDS.toMillis(anvilTotalNs),
            TimeUnit.NANOSECONDS.toMicros(anvilAvgNs),
            anvilFailures,
            anvilSamples,
            ratio,
            anvilRatio,
            fullOverAnvil,
            fullCpuOverAnvil);
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, summary);
    }
    RTP.log(Level.INFO, summary);

    if (probeNulls == effective) {
      String note =
          "[RTP test/chunk-probe-perf] every probe returned null — fast path is inert for this"
              + " world/adapter. Check SafetyKeys.anvilPrefilterEnabled and that the world is"
              + " an .mca-backed Bukkit/Paper/Folia world.";
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, note);
      }
      RTP.log(Level.INFO, note);
    }
  }

  /**
   * Resolve the {@code region/} folder on disk for the RTP world. Uses
   * {@link Bukkit#getWorld(String)} then
   * {@link World#getWorldFolder()} plus the vanilla dimension subpath
   * ({@code DIM-1} / {@code DIM1} / none). Returns {@code null} if the
   * underlying Bukkit world is not loaded or the region directory is absent.
   */
  @Nullable
  private static File resolveRegionFolder(RTPWorld<?> rtpWorld) {
    World bukkit;
    try {
      bukkit = Bukkit.getWorld(rtpWorld.id());
    } catch (Throwable t) {
      return null;
    }
    if (bukkit == null) return null;
    File worldFolder = bukkit.getWorldFolder();
    if (worldFolder == null) return null;
    String dim;
    try {
      switch (bukkit.getEnvironment()) {
        case NETHER:
          dim = "DIM-1";
          break;
        case THE_END:
          dim = "DIM1";
          break;
        case NORMAL:
        default:
          dim = "";
      }
    } catch (Throwable ignored) {
      dim = "";
    }
    File base = dim.isEmpty() ? worldFolder : new File(worldFolder, dim);
    File region = new File(base, "region");
    return region.isDirectory() ? region : null;
  }

  /**
   * Scan {@code regionDir} for {@code r.X.Z.mca} files, read each file's 4 KiB
   * location table, and collect world-space chunk coordinates for every entry
   * that has a nonzero sector offset (i.e. the chunk is actually stored).
   *
   * <p>To bound memory and scan cost, the collection phase short-circuits once
   * a comfortable oversample pool has been accumulated - roughly
   * {@code targetSamples * 8}, clamped to a floor that still gives adequate
   * spread even for small worlds.
   */
  static List<long[]> collectPregeneratedChunks(File regionDir, int targetSamples) {
    int poolCap = Math.max(targetSamples * 8, 1024);
    List<long[]> out = new ArrayList<>(Math.min(poolCap, 4096));

    File[] files = regionDir.listFiles();
    if (files == null) return out;

    // Shuffle file order so we sample across the world, not just the first
    // region file alphabetically when the pool fills early.
    List<File> shuffled = new ArrayList<>(files.length);
    for (File f : files) {
      if (f != null && f.isFile() && REGION_FILE.matcher(f.getName()).matches()) {
        shuffled.add(f);
      }
    }
    Collections.shuffle(shuffled, new Random());

    byte[] header = new byte[4096];
    for (File f : shuffled) {
      if (out.size() >= poolCap) break;
      Matcher m = REGION_FILE.matcher(f.getName());
      if (!m.matches()) continue;
      int rx;
      int rz;
      try {
        rx = Integer.parseInt(m.group(1));
        rz = Integer.parseInt(m.group(2));
      } catch (NumberFormatException nfe) {
        continue;
      }
      try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
        if (raf.length() < 4096) continue;
        raf.readFully(header);
      } catch (Throwable t) {
        // Skip unreadable region files - diagnostic, not fatal.
        continue;
      }
      for (int i = 0; i < 1024; i++) {
        int off = i * 4;
        int sectorOffset =
            ((header[off] & 0xFF) << 16)
                | ((header[off + 1] & 0xFF) << 8)
                | (header[off + 2] & 0xFF);
        int sectorCount = header[off + 3] & 0xFF;
        if (sectorOffset == 0 || sectorCount == 0) continue;
        int localCx = i & 31;
        int localCz = (i >> 5) & 31;
        int cx = (rx << 5) | localCx;
        int cz = (rz << 5) | localCz;
        out.add(new long[] {cx, cz});
        if (out.size() >= poolCap) break;
      }
    }
    return out;
  }

  /**
   * Resolve the {@code r.X.Z.mca} file for a chunk coordinate under the given
   * region directory. Mirrors
   * {@link io.github.dailystruggle.rtp.anvil.AnvilPrefilter#regionFileFor}
   * but takes the already-resolved region directory instead of recomputing the
   * dimension subpath.
   */
  @Nullable
  private static File regionFileForSample(File regionDir, int cx, int cz) {
    if (regionDir == null) return null;
    int rx = cx >> 5;
    int rz = cz >> 5;
    return new File(regionDir, "r." + rx + "." + rz + ".mca");
  }

  private static int clamp(int v, int lo, int hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
  }

  /**
   * Cache of {@code loadLiveChunk(int,int)} reflective handles per concrete
   * {@link RTPWorld} class. Sentinel value {@link #BENCHMARK_METHOD_ABSENT}
   * marks classes that don't expose the helper (Fabric, test doubles), so we
   * don't repeat the {@code getMethod} lookup per sample.
   */
  private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.lang.reflect.Method>
      BENCHMARK_LIVE_LOAD_METHODS = new java.util.concurrent.ConcurrentHashMap<>();

  private static final java.lang.reflect.Method BENCHMARK_METHOD_ABSENT;

  static {
    java.lang.reflect.Method sentinel;
    try {
      sentinel = TestChunkProbePerfCmd.class.getDeclaredMethod("clamp", int.class, int.class, int.class);
    } catch (NoSuchMethodException nse) {
      sentinel = null;
    }
    BENCHMARK_METHOD_ABSENT = sentinel;
  }

  /**
   * Time {@code World#getChunkAt(cx, cz)} on the server thread for up to
   * {@code limit} samples drawn from {@code pool}. Returns {@code {totalNs,
   * samples, failures}}. The CPU stage stalls the primary (or region) thread
   * for the duration of the loop; callers must cap {@code limit} (see
   * {@link #CPU_SAMPLES_CAP}). All-zero return indicates the platform did not
   * expose a usable primary-thread scheduling hook (Fabric / unit-test
   * doubles); the caller falls back to omitting the CPU column from the
   * summary.
   *
   * <p>On Folia, the global {@link org.bukkit.scheduler.BukkitScheduler#runTask}
   * call throws {@code UnsupportedOperationException}; we then dispatch each
   * sample via {@code Bukkit.getRegionScheduler().execute(plugin, world,
   * cx, cz, ...)} which adds one region-scheduler hop per sample (typically
   * sub-millisecond). The {@code full(cpu)} number on Folia therefore still
   * includes a small per-sample hop cost.
   */
  private static long[] measureCpuChunkLoad(
      RTPWorld<?> world, List<long[]> pool, int limit) {
    long[] empty = new long[] {0L, 0L, 0L};
    if (world == null || pool == null || pool.isEmpty() || limit <= 0) return empty;

    World bukkitWorld;
    try {
      bukkitWorld = Bukkit.getWorld(world.id());
    } catch (Throwable t) {
      return empty;
    }
    if (bukkitWorld == null) return empty;

    org.bukkit.plugin.Plugin plugin;
    try {
      plugin = Bukkit.getPluginManager().getPlugin("RTP");
    } catch (Throwable t) {
      return empty;
    }
    if (plugin == null) return empty;

    int n = Math.min(limit, pool.size());
    List<long[]> samples = new ArrayList<>(n);
    for (int i = 0; i < n; i++) samples.add(pool.get(i));

    java.util.concurrent.CompletableFuture<long[]> result = new java.util.concurrent.CompletableFuture<>();

    // Try the legacy global scheduler first (Spigot/Paper). On Folia this
    // throws UnsupportedOperationException - fall through to per-chunk region
    // scheduling.
    try {
      Bukkit.getScheduler().runTask(plugin, () -> {
        long total = 0L;
        int fails = 0;
        for (long[] xz : samples) {
          int cx = (int) xz[0];
          int cz = (int) xz[1];
          long t0 = System.nanoTime();
          try {
            org.bukkit.Chunk chunk = bukkitWorld.getChunkAt(cx, cz);
            total += System.nanoTime() - t0;
            if (chunk == null) fails++;
          } catch (Throwable t) {
            total += System.nanoTime() - t0;
            fails++;
          }
        }
        result.complete(new long[] {total, samples.size(), fails});
      });
    } catch (UnsupportedOperationException folia) {
      // Folia: dispatch each sample via the region scheduler.
      return measureCpuChunkLoadFolia(plugin, bukkitWorld, samples);
    } catch (Throwable t) {
      RTP.log(Level.FINE,
          "[RTP test/chunk-probe-perf] full(cpu) global scheduler dispatch failed: "
              + t.getClass().getSimpleName() + ": " + t.getMessage());
      return empty;
    }

    try {
      // Bound the wait. Spigot worst-case is roughly limit × 200 ms; pad it.
      return result.get(samples.size() * 2L + 30L, TimeUnit.SECONDS);
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP test/chunk-probe-perf] full(cpu) timed out or failed: "
              + t.getClass().getSimpleName() + ": " + t.getMessage());
      return empty;
    }
  }

  /**
   * Folia-specific {@code full(cpu)} dispatch: schedule each sample on its
   * owning region via reflection (so this class does not need to compile
   * against Folia API). Each sample completes a per-call future; the
   * worker-thread caller aggregates totals. Per-sample region-scheduler hop
   * is included in the timing but is bounded (typically sub-millisecond on
   * idle servers).
   */
  private static long[] measureCpuChunkLoadFolia(
      org.bukkit.plugin.Plugin plugin, World bukkitWorld, List<long[]> samples) {
    long[] empty = new long[] {0L, 0L, 0L};
    Object regionScheduler;
    java.lang.reflect.Method execute;
    try {
      java.lang.reflect.Method getRegionScheduler =
          Bukkit.class.getMethod("getRegionScheduler");
      regionScheduler = getRegionScheduler.invoke(null);
      if (regionScheduler == null) return empty;
      execute = regionScheduler.getClass().getMethod(
          "execute",
          org.bukkit.plugin.Plugin.class,
          World.class,
          int.class,
          int.class,
          Runnable.class);
    } catch (Throwable t) {
      RTP.log(Level.FINE,
          "[RTP test/chunk-probe-perf] full(cpu) region scheduler unavailable: "
              + t.getClass().getSimpleName() + ": " + t.getMessage());
      return empty;
    }

    long totalNs = 0L;
    int fails = 0;
    for (long[] xz : samples) {
      int cx = (int) xz[0];
      int cz = (int) xz[1];
      java.util.concurrent.CompletableFuture<long[]> f =
          new java.util.concurrent.CompletableFuture<>();
      Runnable task = () -> {
        long t0 = System.nanoTime();
        try {
          org.bukkit.Chunk chunk = bukkitWorld.getChunkAt(cx, cz);
          long elapsed = System.nanoTime() - t0;
          f.complete(new long[] {elapsed, chunk == null ? 1L : 0L});
        } catch (Throwable th) {
          long elapsed = System.nanoTime() - t0;
          f.complete(new long[] {elapsed, 1L});
        }
      };
      try {
        execute.invoke(regionScheduler, plugin, bukkitWorld, cx, cz, task);
      } catch (Throwable t) {
        RTP.log(Level.FINE,
            "[RTP test/chunk-probe-perf] full(cpu) region dispatch failed for ("
                + cx + "," + cz + "): "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
        fails++;
        continue;
      }
      try {
        long[] r = f.get(30L, TimeUnit.SECONDS);
        totalNs += r[0];
        if (r[1] != 0L) fails++;
      } catch (Throwable t) {
        fails++;
      }
    }
    return new long[] {totalNs, samples.size(), fails};
  }

  /**
   * Reflectively invoke {@code loadLiveChunk(cx, cz)} on the given world if
   * the concrete adapter (BukkitRTPWorld / FoliaRTPWorld) exposes it,
   * bypassing the ADR-016 anvil prefilter short-circuit so the "full" timing
   * measures an actual live chunk load.
   *
   * @return the {@code CompletableFuture} returned by the helper, or
   *     {@code null} if the world's class does not expose it (caller should
   *     fall back to {@link RTPWorld#getChunkAtAsync(int, int)}).
   */
  @Nullable
  private static java.util.concurrent.CompletableFuture<?> invokeBenchmarkLiveLoad(
      RTPWorld<?> world, int cx, int cz) {
    if (world == null) return null;
    java.lang.reflect.Method m =
        BENCHMARK_LIVE_LOAD_METHODS.computeIfAbsent(
            world.getClass(),
            cls -> {
              Class<?> c = cls;
              while (c != null && c != Object.class) {
                try {
                  return c.getDeclaredMethod("loadLiveChunk", int.class, int.class);
                } catch (NoSuchMethodException ignored) {
                  c = c.getSuperclass();
                }
              }
              return BENCHMARK_METHOD_ABSENT;
            });
    if (m == null || m == BENCHMARK_METHOD_ABSENT) return null;
    try {
      m.setAccessible(true);
      Object result = m.invoke(world, cx, cz);
      if (result instanceof java.util.concurrent.CompletableFuture<?> cf) {
        return cf;
      }
      return null;
    } catch (Throwable t) {
      RTP.log(Level.FINE,
          "[RTP test/chunk-probe-perf] reflective loadLiveChunk failed for "
              + world.getClass().getName() + ": "
              + t.getClass().getSimpleName() + ": " + t.getMessage());
      return null;
    }
  }
}
