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
 * {@code rtp test chunk-probe-perf} &mdash; A/B micro-benchmark comparing the
 * probe-first fast path ({@link RTPWorld#probeChunkColumn(int, int, int, int,
 * boolean)}) against the authoritative full-chunk load path
 * ({@link RTPWorld#getChunkAtAsync(int, int)}), sampled over a random set of
 * <em>pregenerated</em> chunks discovered by scanning the world's {@code region/}
 * folder.
 *
 * <p>Rationale for the pregenerated-chunk sampling strategy: the previous
 * near-spawn spiral produced misleading ratios (~0.95x) because every sampled
 * chunk was already in the server's live chunk cache — {@code getChunkAtAsync}
 * became an in-memory lookup while {@code probeChunkColumn} unconditionally went
 * to disk. Sampling randomly across all existing {@code r.X.Z.mca} files gives
 * an aggregate picture of the probe vs full-load tradeoff on chunks the server
 * may or may not have loaded, which is closer to what {@code PregenTask} sees at
 * steady state.
 *
 * <p>Safety compliance:
 * <ul>
 *   <li><b>S-004:</b> surfaces a single {@code INFO} summary; per-sample
 *       exceptions are counted and still published.</li>
 *   <li><b>S-005:</b> runs entirely on the async scheduler; the region-folder
 *       scan and per-header reads are off-thread file I/O.</li>
 *   <li>Read-only: no teleport, no chunk ticket, no config mutation.</li>
 * </ul>
 */
public class TestChunkProbePerfCmd extends BaseRTPCmdImpl {

  static final int MIN_SAMPLES = 1;
  static final int MAX_SAMPLES = 4096;
  static final int DEFAULT_SAMPLES = 256;

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
        RTP.serverAccessor.sendMessage(callerId, msg);
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
    RTP.serverAccessor.sendMessage(callerId, startMsg);
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
      RTP.serverAccessor.sendMessage(callerId, msg);
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
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg, t);
      return;
    }

    if (pool.isEmpty()) {
      String msg =
          "[RTP test/chunk-probe-perf] no pregenerated chunks found in "
              + regionDir
              + " — nothing to sample";
      RTP.serverAccessor.sendMessage(callerId, msg);
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
        ChunkColumnProbe p = world.probeChunkColumn(cx, cz, minY, maxY, false).join();
        probeTotalNs += System.nanoTime() - t0;
        if (p == null) probeNulls++;
      } catch (Throwable t) {
        probeTotalNs += System.nanoTime() - t0;
        probeFailures++;
      }

      long t1 = System.nanoTime();
      try {
        world.getChunkAtAsync(cx, cz).join();
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
        long t2 = System.nanoTime();
        try {
          byte[] bytes = Files.readAllBytes(regionFile.toPath());
          AnvilReader.readChunk(bytes, cx, cz);
          anvilTotalNs += System.nanoTime() - t2;
          anvilSamples++;
        } catch (Throwable t) {
          anvilTotalNs += System.nanoTime() - t2;
          anvilFailures++;
          anvilSamples++;
        }
      }
    }

    long probeAvgNs = effective == 0 ? 0 : probeTotalNs / effective;
    long fullAvgNs = effective == 0 ? 0 : fullTotalNs / effective;
    long anvilAvgNs = anvilSamples == 0 ? 0 : anvilTotalNs / anvilSamples;
    double ratio = probeAvgNs == 0 ? 0.0 : (double) fullAvgNs / (double) probeAvgNs;
    double anvilRatio = probeAvgNs == 0 ? 0.0 : (double) anvilAvgNs / (double) probeAvgNs;
    double fullOverAnvil = anvilAvgNs == 0 ? 0.0 : (double) fullAvgNs / (double) anvilAvgNs;
    double nullRate = effective == 0 ? 0.0 : (double) probeNulls / (double) effective;

    String summary =
        String.format(
            "[RTP test/chunk-probe-perf] done: poolSize=%d samples=%d"
                + " probe total=%dms avg=%dµs failures=%d nullRate=%.3f"
                + " full  total=%dms avg=%dµs failures=%d"
                + " anvil total=%dms avg=%dµs failures=%d samples=%d"
                + " full/probe=%.2fx anvil/probe=%.2fx full/anvil=%.2fx",
            pool.size(),
            effective,
            TimeUnit.NANOSECONDS.toMillis(probeTotalNs),
            TimeUnit.NANOSECONDS.toMicros(probeAvgNs),
            probeFailures,
            nullRate,
            TimeUnit.NANOSECONDS.toMillis(fullTotalNs),
            TimeUnit.NANOSECONDS.toMicros(fullAvgNs),
            fullFailures,
            TimeUnit.NANOSECONDS.toMillis(anvilTotalNs),
            TimeUnit.NANOSECONDS.toMicros(anvilAvgNs),
            anvilFailures,
            anvilSamples,
            ratio,
            anvilRatio,
            fullOverAnvil);
    RTP.serverAccessor.sendMessage(callerId, summary);
    RTP.log(Level.INFO, summary);

    if (probeNulls == effective) {
      String note =
          "[RTP test/chunk-probe-perf] every probe returned null — fast path is inert for this"
              + " world/adapter. Check SafetyKeys.anvilPrefilterEnabled and that the world is"
              + " an .mca-backed Bukkit/Paper/Folia world.";
      RTP.serverAccessor.sendMessage(callerId, note);
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
   * a comfortable oversample pool has been accumulated — roughly
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
        // Skip unreadable region files — diagnostic, not fatal.
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
}
