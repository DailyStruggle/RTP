package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Storage-class characterisation for the world directory, plus a measured
 * cold-read latency distribution taken from the actual device.
 *
 * <p><b>Why.</b> The headline leverage argument of the region-bytes prefilter
 * is a ratio between a cold region-file read and a warm in-memory hit. That
 * ratio is page-cache and device dependent: the same code answers in ~2 ms on
 * a spinning disk, ~200 us on a SATA SSD, and ~50 us on NVMe. A number quoted
 * without its device class is machine-relative and not portable, so every run
 * records the class, the method that produced the class, and the read-latency
 * distribution the class was derived from. Downstream models select a cost
 * distribution from the recorded histogram instead of assuming one.
 *
 * <p><b>Page-cache honesty (required disclosure).</b> A probe that reads a
 * file necessarily warms that file's pages, so a naive warmup would measure
 * the very pages it had just populated. There is no portable way to drop the
 * OS page cache from inside a JVM (no {@code posix_fadvise DONTNEED}, no
 * {@code /proc/sys/vm/drop_caches} without root, nothing at all on Windows),
 * and this profiler does not attempt one. Instead:
 *
 * <ul>
 *   <li>Every timed sample is a <b>first touch</b> of a distinct region file
 *       at a random offset. A file is recorded in {@link #probedFiles} and is
 *       never read a second time by this profiler, in this phase or any
 *       later one, so no sample can ever measure pages this profiler warmed.</li>
 *   <li>Probe files are selected <b>farthest-first from the world origin</b>,
 *       because the benchmark's teleport radius is centred on origin. The
 *       files this profiler warms are therefore the ones the measured run is
 *       least likely to touch.</li>
 *   <li>What is <em>not</em> controlled is prior warmth: a region file that
 *       the server, a previous run, or a filesystem prefetch already pulled
 *       into the page cache reads warm on its first touch here, and this
 *       profiler cannot tell the difference. The distribution is therefore
 *       labelled {@code FIRST_TOUCH_UNKNOWN_PAGE_CACHE} rather than "cold",
 *       and it is a <b>lower bound</b> on true device cold-read latency.</li>
 * </ul>
 *
 * <p>The label travels with the numbers in every emitted block and column, so
 * the conflation this class cannot avoid is at least never silent.
 *
 * <p><b>Off-path.</b> All I/O runs on {@link Sched#runAsync}; nothing here is
 * ever executed on a tick thread. Results are published into primitive
 * volatile fields and read by {@link MetricsRecorder} without locking.
 */
public final class StorageProfiler {

    /** Device verdict. {@link #UNKNOWN} is written literally; it never means
     *  "fast" and never means "no device". */
    public enum StorageClass {
        NVME, SATA_SSD, SPINNING, NETWORK, UNKNOWN;

        public String token() { return name(); }
    }

    // -----------------------------------------------------------------
    // Pre-registered classification thresholds (ADR-080 gate: fixed before
    // any measurement is read; see the storage track's G-STOR-2 in
    // docs/dev/scratch/CHECKLIST-competitor-simulation-benchmark.md).
    // These are device-physics boundaries, not tunings: NVMe random 4 KiB
    // reads land in the tens of microseconds, SATA SSDs in the hundreds,
    // and a rotational seek cannot beat its own average latency (~2 ms at
    // 7200 rpm, ~4 ms at 5400 rpm).
    // -----------------------------------------------------------------

    /** p50 at or below this (us) classifies NVMe. */
    static final long NVME_MAX_P50_US = 250L;
    /** p50 at or below this (us) classifies SATA SSD. */
    static final long SATA_SSD_MAX_P50_US = 1_500L;

    /** Filesystem type fragments that identify a network mount. Matched
     *  case-insensitively against {@link FileStore#type()} and the store name. */
    private static final String[] NETWORK_FS_MARKERS = {
            "nfs", "cifs", "smb", "sshfs", "9p", "afs", "davfs", "gluster", "ceph", "webdav"
    };

    /** Bytes per timed read. One 4 KiB page: the smallest unit the device can
     *  be asked for, so the sample is dominated by access latency rather than
     *  by transfer bandwidth. */
    private static final int READ_BYTES = 4096;

    /** Timed reads attempted per probe. Bounded so a phase-start probe on a
     *  spinning disk costs well under a second of async time. */
    private static final int PROBE_READS = 24;

    /** Sentinel for every unmeasured numeric this class publishes. */
    public static final long NO_DATA = -1L;

    /** Distribution label. Stated next to every latency figure emitted. */
    public static final String LATENCY_LABEL = "FIRST_TOUCH_UNKNOWN_PAGE_CACHE";

    private final Plugin plugin;
    private final Path worldDir;

    /** Region files already read by this profiler. Never re-probed, so a
     *  later phase can never time pages this profiler itself warmed. */
    private final Set<String> probedFiles = new HashSet<>();

    // Published results. Primitive / immutable only; written by the async
    // probe, read by the recorder.
    private volatile StorageClass storageClass = StorageClass.UNKNOWN;
    private volatile String classificationMethod = "";
    private volatile String fsDescription = "";
    private volatile long probeReads = NO_DATA;
    private volatile long p50Us = NO_DATA;
    private volatile long p90Us = NO_DATA;
    private volatile long p99Us = NO_DATA;
    private volatile long maxUs = NO_DATA;
    private volatile boolean everProfiled = false;

    public StorageProfiler(Plugin plugin, Path worldDir) {
        this.plugin = plugin;
        this.worldDir = worldDir;
    }

    public StorageClass storageClass()    { return storageClass; }
    public String classificationMethod()  { return classificationMethod; }
    public String fsDescription()         { return fsDescription; }
    public long probeReads()              { return probeReads; }
    public long coldReadP50Us()           { return p50Us; }
    public long coldReadP90Us()           { return p90Us; }
    public long coldReadP99Us()           { return p99Us; }
    public long coldReadMaxUs()           { return maxUs; }
    public boolean everProfiled()         { return everProfiled; }

    /**
     * Runs a probe off-tick and appends a phase header block to
     * {@code blockPath} when it completes. Safe to call from a tick thread:
     * it schedules and returns immediately.
     */
    public void probePhaseAsync(String phaseLabel, Path blockPath) {
        Sched.runAsync(plugin, () -> {
            try {
                probeNow();
                appendBlock(phaseLabel, blockPath);
            } catch (Throwable t) {
                // Characterisation is diagnostic-only: a failed probe must
                // never take a benchmark run down with it. The fields keep
                // their NO_DATA sentinels and the block records the failure.
                plugin.getLogger().log(Level.WARNING,
                        "[StressTestRTP] storage probe failed; storage columns stay -1", t);
            }
        });
    }

    /**
     * Synchronous probe. Never call from a tick thread - it performs blocking
     * disk reads by design (S-005). Package-visible for the async wrapper.
     */
    void probeNow() {
        classifyFilesystem();
        long[] samples = timeUntouchedReads();
        everProfiled = true;
        if (samples.length == 0) {
            probeReads = 0L;
            p50Us = p90Us = p99Us = maxUs = NO_DATA;
            if (storageClass == StorageClass.UNKNOWN) {
                classificationMethod = "NO_REGION_FILES_AVAILABLE";
            }
            return;
        }
        Arrays.sort(samples);
        probeReads = samples.length;
        p50Us = percentileUs(samples, 0.50);
        p90Us = percentileUs(samples, 0.90);
        p99Us = percentileUs(samples, 0.99);
        maxUs = samples[samples.length - 1] / 1_000L;
        // A network mount is already decided by filesystem type; latency on a
        // network store says more about the link than about the medium, so it
        // does not override the type verdict.
        if (storageClass != StorageClass.NETWORK) {
            if (p50Us <= NVME_MAX_P50_US) {
                storageClass = StorageClass.NVME;
            } else if (p50Us <= SATA_SSD_MAX_P50_US) {
                storageClass = StorageClass.SATA_SSD;
            } else {
                storageClass = StorageClass.SPINNING;
            }
            classificationMethod = "RANDOM_4K_READ_P50_VS_FIXED_THRESHOLDS";
        }
    }

    /**
     * Type-based classification. Only decides the NETWORK case: no portable
     * Java API reports rotational-vs-solid state, and {@link FileStore#type()}
     * returns the filesystem ("NTFS", "ext4", "apfs"), not the medium. A UNC
     * path is treated as a network mount on Windows.
     */
    private void classifyFilesystem() {
        String type = "";
        String name = "";
        try {
            FileStore store = Files.getFileStore(worldDir);
            type = String.valueOf(store.type());
            name = String.valueOf(store.name());
        } catch (IOException | RuntimeException e) {
            // Leave both empty; the latency path still classifies.
        }
        fsDescription = (type + " " + name).trim();
        String haystack = (type + " " + name + " " + worldDir).toLowerCase(Locale.ROOT);
        boolean unc = worldDir.toString().startsWith("\\\\");
        for (String marker : NETWORK_FS_MARKERS) {
            if (haystack.contains(marker)) { unc = true; break; }
        }
        if (unc) {
            storageClass = StorageClass.NETWORK;
            classificationMethod = "FILESTORE_TYPE_OR_UNC_PATH";
        }
    }

    /**
     * Times one 4 KiB read at a random offset in each of up to
     * {@link #PROBE_READS} region files this profiler has never read before,
     * farthest-from-origin first. Returns raw nanosecond durations.
     */
    private long[] timeUntouchedReads() {
        List<Path> candidates = untouchedRegionFiles();
        if (candidates.isEmpty()) return new long[0];
        List<Long> out = new ArrayList<>(Math.min(PROBE_READS, candidates.size()));
        for (Path file : candidates) {
            if (out.size() >= PROBE_READS) break;
            long ns = timeOneRead(file);
            // Mark probed whether or not the read succeeded: a failed open
            // still must not be retried, and a successful one has warmed
            // pages that must never be timed again.
            probedFiles.add(file.toAbsolutePath().toString());
            if (ns > 0L) out.add(ns);
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** One timed 4 KiB read at a random page-aligned offset. {@code -1} on failure. */
    private long timeOneRead(Path file) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size < READ_BYTES) return NO_DATA;
            long maxPage = (size - READ_BYTES) / READ_BYTES;
            long offset = maxPage <= 0 ? 0L
                    : ThreadLocalRandom.current().nextLong(maxPage + 1) * READ_BYTES;
            ByteBuffer buf = ByteBuffer.allocate(READ_BYTES);
            long t0 = System.nanoTime();
            int read = ch.read(buf, offset);
            long ns = System.nanoTime() - t0;
            return read > 0 ? ns : NO_DATA;
        } catch (IOException | RuntimeException e) {
            return NO_DATA;
        }
    }

    /**
     * Region files not yet probed, ordered farthest-from-origin first so the
     * pages this profiler warms are the ones the origin-centred benchmark
     * radius is least likely to read. Covers both Anvil ({@code .mca}) and
     * Linear ({@code .linear}) region formats.
     */
    private List<Path> untouchedRegionFiles() {
        List<Path> found = new ArrayList<>();
        for (String sub : new String[] {"region", "DIM-1/region", "DIM1/region"}) {
            Path dir = worldDir.resolve(sub);
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    String fn = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!fn.endsWith(".mca") && !fn.endsWith(".linear")) continue;
                    if (probedFiles.contains(p.toAbsolutePath().toString())) continue;
                    found.add(p);
                }
            } catch (IOException | RuntimeException e) {
                // Unreadable directory: skip it, the others may still work.
            }
        }
        found.sort((a, b) -> Long.compare(originDistanceSq(b), originDistanceSq(a)));
        return found;
    }

    /** Squared region-grid distance from origin, parsed from {@code r.X.Z.mca}.
     *  Unparseable names sort last (distance 0) rather than being dropped. */
    private static long originDistanceSq(Path p) {
        String[] parts = p.getFileName().toString().split("\\.");
        if (parts.length < 3) return 0L;
        try {
            long x = Long.parseLong(parts[1]);
            long z = Long.parseLong(parts[2]);
            return x * x + z * z;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Nearest-rank percentile of a sorted nanosecond array, in microseconds. */
    private static long percentileUs(long[] sorted, double p) {
        if (sorted.length == 0) return NO_DATA;
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx] / 1_000L;
    }

    /**
     * Appends the phase header block. Plain text sidecar rather than CSV
     * comment lines, because the analysis scripts parse the first CSV line as
     * the header via {@code csv.DictReader}.
     */
    private void appendBlock(String phaseLabel, Path blockPath) {
        StringBuilder sb = new StringBuilder(512);
        String nl = System.lineSeparator();
        sb.append("=== storage profile: phase ").append(phaseLabel == null ? "" : phaseLabel)
          .append(" ===").append(nl);
        sb.append("world_dir=").append(worldDir).append(nl);
        sb.append("filesystem=").append(fsDescription).append(nl);
        sb.append("storage_class=").append(storageClass.token()).append(nl);
        sb.append("classification_method=").append(classificationMethod).append(nl);
        sb.append("read_bytes=").append(READ_BYTES).append(nl);
        sb.append("probe_reads=").append(probeReads).append(nl);
        sb.append("latency_label=").append(LATENCY_LABEL).append(nl);
        sb.append("read_p50_us=").append(p50Us).append(nl);
        sb.append("read_p90_us=").append(p90Us).append(nl);
        sb.append("read_p99_us=").append(p99Us).append(nl);
        sb.append("read_max_us=").append(maxUs).append(nl);
        sb.append("note=-1 means NOT MEASURED, never zero. Latencies are a lower bound:")
          .append(nl);
        sb.append("note=each sample is this profiler's first touch of a distinct region file,")
          .append(nl);
        sb.append("note=but pages already resident from the server or a prior run read warm")
          .append(nl);
        sb.append("note=and are indistinguishable. No page-cache drop is portable from a JVM.")
          .append(nl);
        sb.append(nl);
        try {
            Files.writeString(blockPath, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "[StressTestRTP] could not append storage profile block", e);
        }
    }
}
