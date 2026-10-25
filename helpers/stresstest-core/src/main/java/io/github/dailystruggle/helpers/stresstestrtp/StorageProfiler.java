package io.github.dailystruggle.helpers.stresstestrtp;

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
 * cold-read latency distribution taken from the actual device (ADR-122 Decision 1).
 */
public final class StorageProfiler {

    public enum StorageClass {
        NVME, SATA_SSD, SPINNING, NETWORK, UNKNOWN;

        public String token() { return name(); }
    }

    static final long NVME_MAX_P50_US = 250L;
    static final long SATA_SSD_MAX_P50_US = 1_500L;

    private static final String[] NETWORK_FS_MARKERS = {
            "nfs", "cifs", "smb", "sshfs", "9p", "afs", "davfs", "gluster", "ceph", "webdav"
    };

    private static final int READ_BYTES = 4096;
    private static final int PROBE_READS = 24;

    public static final long NO_DATA = -1L;
    public static final String LATENCY_LABEL = "FIRST_TOUCH_UNKNOWN_PAGE_CACHE";

    private final StressHost plugin;
    private final Path worldDir;

    private final Set<String> probedFiles = new HashSet<>();

    private volatile StorageClass storageClass = StorageClass.UNKNOWN;
    private volatile String classificationMethod = "";
    private volatile String fsDescription = "";
    private volatile long probeReads = NO_DATA;
    private volatile long p50Us = NO_DATA;
    private volatile long p90Us = NO_DATA;
    private volatile long p99Us = NO_DATA;
    private volatile long maxUs = NO_DATA;
    private volatile boolean everProfiled = false;

    public StorageProfiler(StressHost plugin, Path worldDir) {
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

    public void probePhaseAsync(String phaseLabel, Path blockPath) {
        Sched.runAsync(plugin, () -> {
            try {
                probeNow();
                appendBlock(phaseLabel, blockPath);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING,
                        "[StressTestRTP] storage probe failed; storage columns stay -1", t);
            }
        });
    }

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

    private long[] timeUntouchedReads() {
        List<Path> candidates = untouchedRegionFiles();
        if (candidates.isEmpty()) return new long[0];
        List<Long> out = new ArrayList<>(Math.min(PROBE_READS, candidates.size()));
        for (Path file : candidates) {
            if (out.size() >= PROBE_READS) break;
            long ns = timeOneRead(file);
            probedFiles.add(file.toAbsolutePath().toString());
            if (ns > 0L) out.add(ns);
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

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

    private List<Path> untouchedRegionFiles() {
        List<Path> found = new ArrayList<>();
        for (Path dir : regionDirs(worldDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    String fn = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!fn.endsWith(".mca") && !fn.endsWith(".linear")) continue;
                    if (probedFiles.contains(p.toAbsolutePath().toString())) continue;
                    found.add(p);
                }
            } catch (IOException | RuntimeException e) {
            }
        }
        found.sort((a, b) -> Long.compare(originDistanceSq(b), originDistanceSq(a)));
        return found;
    }

    static List<Path> regionDirs(Path worldDir) {
        List<Path> out = new ArrayList<>();
        for (String sub : new String[] {"region", "DIM-1/region", "DIM1/region"}) {
            Path dir = worldDir.resolve(sub);
            if (Files.isDirectory(dir)) out.add(dir);
        }
        Path dims = worldDir.resolve("dimensions");
        if (!Files.isDirectory(dims)) return out;
        try (DirectoryStream<Path> namespaces = Files.newDirectoryStream(dims, Files::isDirectory)) {
            for (Path ns : namespaces) {
                try (DirectoryStream<Path> keys = Files.newDirectoryStream(ns, Files::isDirectory)) {
                    for (Path key : keys) {
                        Path dir = key.resolve("region");
                        if (Files.isDirectory(dir)) out.add(dir);
                    }
                } catch (IOException | RuntimeException e) {
                }
            }
        } catch (IOException | RuntimeException e) {
        }
        return out;
    }

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

    private static long percentileUs(long[] sorted, double p) {
        if (sorted.length == 0) return NO_DATA;
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx] / 1_000L;
    }

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
