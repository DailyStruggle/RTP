package io.github.dailystruggle.helpers.stresstestrtp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Setup-phase measurement of what one plugin chunk ticket actually costs
 * (ADR-122 Decision 1 & 2).
 */
public final class TicketFootprintProbe {

    public static final long NO_DATA = -1L;
    public static final String HEAP_LABEL = "UNCOLLECTED_ALLOCATION_INCLUSIVE";
    public static final String RECLAIM_GC_DURING_WINDOW = "GC_DURING_WINDOW_UNATTRIBUTABLE";
    public static final String RECLAIM_PROMPT = "RECLAIMED_ON_UNLOAD";
    public static final String RECLAIM_DEFERRED = "RETAINED_PENDING_COLLECTION";
    public static final String RECLAIM_NET_ZERO = "NO_NET_GROWTH";

    private static final double PROMPT_RECLAIM_FRACTION = 0.5;
    private static final int COUNT_RADIUS = 7;
    private static final int MAX_CANDIDATES = 16;
    private static final int CANDIDATE_STRIDE = 32;
    static final long MAX_LOAD_WAIT_TICKS = 400L;
    private static final long LOAD_POLL_TICKS = 5L;
    static final long MAX_PLAUSIBLE_BYTES_PER_CHUNK = 16L * 1024L * 1024L;
    public static final String HEAP_LABEL_UNATTRIBUTABLE = "UNATTRIBUTABLE_CONCURRENT_ALLOCATION";
    private static final int MAX_RERUNS = 2;

    private final StressHost plugin;
    private final int originDistanceBlocks;
    private final long settleTicks;
    private final Path reportPath;

    private volatile int windowPhase = 0;
    private volatile String probeWorld = "";
    private volatile int centerX = 0;
    private volatile int centerZ = 0;
    private final AtomicLong windowLoads = new AtomicLong();
    private final AtomicLong windowUnloads = new AtomicLong();
    private final AtomicLong windowNoise = new AtomicLong();

    private volatile boolean everProbed = false;
    private volatile long chunksPerTicket = NO_DATA;
    private volatile long chunksReleased = NO_DATA;
    private volatile long noiseLoads = NO_DATA;
    private volatile long heapUsedBeforeBytes = NO_DATA;
    private volatile long heapUsedAfterLoadBytes = NO_DATA;
    private volatile long heapUsedAfterUnloadBytes = NO_DATA;
    private volatile long heapDeltaBytes = NO_DATA;
    private volatile long bytesPerChunk = NO_DATA;
    private volatile long heapRetainedAfterUnloadBytes = NO_DATA;
    private volatile long heapReclaimedBytes = NO_DATA;
    private volatile long committedDeltaBytes = NO_DATA;
    private volatile long windowCollections = NO_DATA;
    private volatile String reclaimLabel = "";
    private volatile String shape = "";
    private volatile String probeCenter = "";
    private volatile String failureReason = "";

    private volatile boolean registered = false;
    private volatile boolean probing = false;
    private final AtomicInteger reruns = new AtomicInteger(0);
    private long heapBeforeBytes = NO_DATA;
    private long committedBeforeBytes = NO_DATA;
    private long collectionsBefore = NO_DATA;
    private Runnable loadUnsub;
    private Runnable unloadUnsub;

    public TicketFootprintProbe(StressHost plugin, int originDistanceBlocks, long settleTicks, Path reportPath) {
        this.plugin = plugin;
        this.originDistanceBlocks = Math.max(1024, originDistanceBlocks);
        this.settleTicks = Math.max(5L, settleTicks);
        this.reportPath = reportPath;
    }

    public void start() {
        if (registered) return;
        if (plugin != null) {
            loadUnsub = plugin.onChunkLoad(this::onChunkLoad);
            unloadUnsub = plugin.onChunkUnload(this::onChunkUnload);
        }
        registered = true;
        Sched.runGlobalLater(plugin, this::beginProbe, settleTicks * 2L);
    }

    public void stop() {
        if (!registered) return;
        if (loadUnsub != null) { loadUnsub.run(); loadUnsub = null; }
        if (unloadUnsub != null) { unloadUnsub.run(); unloadUnsub = null; }
        registered = false;
    }

    public boolean needsRerun() {
        return registered && !everProbed && !probing && reruns.get() < MAX_RERUNS;
    }

    public void rerun() {
        if (!registered || probing) return;
        int rerunCount = reruns.incrementAndGet();
        plugin.getLogger().info("[StressTestRTP] TicketFootprintProbe: rerun " + rerunCount
                + " of " + MAX_RERUNS + " (previous result: "
                + (failureReason.isEmpty() ? "none" : failureReason) + ").");
        Sched.runGlobal(plugin, this::beginProbe);
    }

    private void beginProbe() {
        if (probing) return;
        probing = true;
        failureReason = "";
        if (!plugin.supportsChunkTickets()) {
            fail(StressHost.NOT_MEASURED);
            return;
        }
        StressWorld world;
        try {
            List<StressWorld> worlds = plugin.worlds();
            if (worlds.isEmpty()) {
                fail("no world loaded");
                return;
            }
            world = worlds.get(0);
        } catch (Throwable t) {
            fail("world lookup failed: " + t.getClass().getSimpleName());
            return;
        }
        tryCandidate(world, 0);
    }

    private void tryCandidate(StressWorld world, int attempt) {
        if (attempt >= MAX_CANDIDATES) {
            fail("no unloaded candidate chunk within " + MAX_CANDIDATES + " tries");
            return;
        }
        double bearing = ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0;
        int baseChunk = originDistanceBlocks >> 4;
        int reach = baseChunk + attempt * CANDIDATE_STRIDE;
        int cx = (int) Math.round(Math.cos(bearing) * reach);
        int cz = (int) Math.round(Math.sin(bearing) * reach);
        Sched.runOnRegion(plugin, world, cx, cz, () -> {
            try {
                if (world.isChunkLoaded(cx, cz)) {
                    Sched.runGlobal(plugin, () -> tryCandidate(world, attempt + 1));
                    return;
                }
                openWindow(world, cx, cz);
                if (!plugin.addChunkTicket(world.name(), cx, cz)) {
                    windowPhase = 0;
                    fail("addPluginChunkTicket returned false at " + cx + "," + cz);
                    return;
                }
            } catch (Throwable t) {
                windowPhase = 0;
                fail("ticket apply failed: " + t.getClass().getSimpleName());
                return;
            }
            Sched.runGlobalLater(plugin, () -> awaitCenterLoad(world, cx, cz, 0L), LOAD_POLL_TICKS);
        });
    }

    private void awaitCenterLoad(StressWorld world, int cx, int cz, long waitedTicks) {
        Sched.runOnRegion(plugin, world, cx, cz, () -> {
            boolean loaded;
            try {
                loaded = world.isChunkLoaded(cx, cz) || windowLoads.get() > 0L;
            } catch (Throwable t) {
                loaded = windowLoads.get() > 0L;
            }
            if (loaded) {
                Sched.runGlobalLater(plugin, () -> closeLoadWindow(world, cx, cz), settleTicks);
                return;
            }
            long waited = waitedTicks + LOAD_POLL_TICKS;
            if (waited >= MAX_LOAD_WAIT_TICKS) {
                windowPhase = 0;
                try {
                    plugin.removeChunkTicket(world.name(), cx, cz);
                } catch (Throwable ignored) {
                }
                fail("ticketed chunk at " + cx + "," + cz + " did not load within "
                        + MAX_LOAD_WAIT_TICKS + " ticks (" + windowNoise.get()
                        + " loads elsewhere); a zero footprint would be a timing miss");
                return;
            }
            Sched.runGlobalLater(plugin, () -> awaitCenterLoad(world, cx, cz, waited), LOAD_POLL_TICKS);
        });
    }

    private void openWindow(StressWorld world, int cx, int cz) {
        probeWorld = world.name();
        centerX = cx;
        centerZ = cz;
        windowLoads.set(0L);
        windowUnloads.set(0L);
        windowNoise.set(0L);
        Runtime rt = Runtime.getRuntime();
        heapBeforeBytes = rt.totalMemory() - rt.freeMemory();
        committedBeforeBytes = rt.totalMemory();
        collectionsBefore = totalCollections();
        windowPhase = 1;
    }

    private void closeLoadWindow(StressWorld world, int cx, int cz) {
        long loads = windowLoads.get();
        long noise = windowNoise.get();
        if (loads == 0L) {
            windowPhase = 0;
            Sched.runOnRegion(plugin, world, cx, cz, () -> {
                try {
                    plugin.removeChunkTicket(world.name(), cx, cz);
                } catch (Throwable ignored) {
                }
            });
            fail("chunk at " + cx + "," + cz + " reported loaded but no ChunkLoadEvent was"
                    + " counted in the window; footprint cannot be measured from events here");
            return;
        }
        Runtime rt = Runtime.getRuntime();
        long heapAfter = rt.totalMemory() - rt.freeMemory();
        long heapDelta = (heapBeforeBytes >= 0 && heapAfter >= heapBeforeBytes)
                ? heapAfter - heapBeforeBytes : NO_DATA;

        chunksPerTicket = loads;
        noiseLoads = noise;
        shape = classifyShape(loads);
        heapUsedBeforeBytes = heapBeforeBytes;
        heapUsedAfterLoadBytes = heapAfter;
        heapDeltaBytes = heapDelta;
        bytesPerChunk = (heapDelta > 0 && loads > 0) ? heapDelta / loads : NO_DATA;
        probeCenter = probeWorld + "@" + cx + "," + cz;

        windowPhase = 2;
        Sched.runOnRegion(plugin, world, cx, cz, () -> {
            try {
                plugin.removeChunkTicket(world.name(), cx, cz);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING,
                        "[StressTestRTP] TicketFootprintProbe: ticket release failed; "
                                + "the probe chunk stays pinned for this session", t);
            }
            Sched.runGlobalLater(plugin, this::closeUnloadWindow, settleTicks);
        });
    }

    private void closeUnloadWindow() {
        chunksReleased = windowUnloads.get();
        Runtime rt = Runtime.getRuntime();
        long heapAfterUnload = rt.totalMemory() - rt.freeMemory();
        long committedAfter = rt.totalMemory();
        long collections = (collectionsBefore >= 0)
                ? Math.max(0L, totalCollections() - collectionsBefore) : NO_DATA;

        heapUsedAfterUnloadBytes = heapAfterUnload;
        windowCollections = collections;
        committedDeltaBytes = (committedBeforeBytes >= 0)
                ? committedAfter - committedBeforeBytes : NO_DATA;

        if (heapBeforeBytes < 0) {
            heapRetainedAfterUnloadBytes = NO_DATA;
            heapReclaimedBytes = NO_DATA;
            reclaimLabel = "";
        } else {
            heapRetainedAfterUnloadBytes = Math.max(0L, heapAfterUnload - heapBeforeBytes);
            long loaded = heapUsedAfterLoadBytes;
            heapReclaimedBytes = (loaded >= 0) ? Math.max(0L, loaded - heapAfterUnload) : NO_DATA;
            reclaimLabel = classifyReclaim(collections, heapDeltaBytes,
                    heapRetainedAfterUnloadBytes);
        }

        windowPhase = 0;
        everProbed = true;
        probing = false;
        writeReport();
        plugin.getLogger().info(String.format(Locale.ROOT,
                "[StressTestRTP] TicketFootprintProbe: one plugin chunk ticket at %s made "
                        + "%d chunk(s) resident (%s); %d released on removal; noise=%d; "
                        + "heap_delta=%d bytes, still resident after unload=%d bytes, "
                        + "reclaimed=%d bytes, committed_delta=%d bytes, gc_in_window=%d "
                        + "[%s / %s].",
                probeCenter, chunksPerTicket, shape, chunksReleased, noiseLoads,
                heapDeltaBytes, heapRetainedAfterUnloadBytes, heapReclaimedBytes,
                committedDeltaBytes, windowCollections, reclaimLabel, heapLabel()));
    }

    public String heapLabel() {
        return heapAttributable(chunksPerTicket, heapDeltaBytes)
                ? HEAP_LABEL : HEAP_LABEL_UNATTRIBUTABLE;
    }

    static boolean heapAttributable(long chunks, long heapDeltaBytes) {
        if (heapDeltaBytes <= 0L) return true;
        if (chunks <= 0L) return false;
        return heapDeltaBytes / chunks <= MAX_PLAUSIBLE_BYTES_PER_CHUNK;
    }

    static String classifyReclaim(long collectionsInWindow, long growthBytes, long retainedBytes) {
        if (collectionsInWindow > 0L) return RECLAIM_GC_DURING_WINDOW;
        if (growthBytes <= 0L || retainedBytes <= 0L) return RECLAIM_NET_ZERO;
        double reclaimed = (double) (growthBytes - retainedBytes) / (double) growthBytes;
        return reclaimed >= PROMPT_RECLAIM_FRACTION ? RECLAIM_PROMPT : RECLAIM_DEFERRED;
    }

    private static long totalCollections() {
        long total = NO_DATA;
        try {
            for (java.lang.management.GarbageCollectorMXBean b
                    : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                long c = b.getCollectionCount();
                if (c < 0) continue;
                total = (total < 0 ? 0L : total) + c;
            }
        } catch (Throwable ignored) {
            return NO_DATA;
        }
        return total;
    }

    private void fail(String reason) {
        failureReason = reason;
        everProbed = false;
        windowPhase = 0;
        probing = false;
        plugin.getLogger().warning("[StressTestRTP] TicketFootprintProbe: not measured (" + reason
                + "); ticket-footprint columns stay -1.");
        writeReport();
    }

    static String classifyShape(long chunks) {
        if (chunks < 0) return "";
        if (chunks == 0) return "NONE";
        long side = Math.round(Math.sqrt((double) chunks));
        if (side * side == chunks && (side % 2L) == 1L) return side + "x" + side;
        return "IRREGULAR";
    }

    public void onChunkLoad(StressHost.ChunkEvent event) {
        if (windowPhase != 1 || event == null) return;
        if (inCountRadius(event.worldName(), event.cx(), event.cz())) {
            windowLoads.incrementAndGet();
        } else {
            windowNoise.incrementAndGet();
        }
    }

    public void onChunkUnload(StressHost.ChunkEvent event) {
        if (windowPhase != 2 || event == null) return;
        if (inCountRadius(event.worldName(), event.cx(), event.cz())) {
            windowUnloads.incrementAndGet();
        }
    }

    private boolean inCountRadius(String world, int cx, int cz) {
        if (!probeWorld.equals(world)) return false;
        return Math.abs(cx - centerX) <= COUNT_RADIUS && Math.abs(cz - centerZ) <= COUNT_RADIUS;
    }

    public boolean everProbed() { return everProbed; }
    public long chunksPerTicket() { return chunksPerTicket; }
    public long chunksReleased() { return chunksReleased; }
    public long noiseLoads() { return noiseLoads; }
    public long heapDeltaBytes() { return heapDeltaBytes; }
    public long bytesPerChunk() { return bytesPerChunk; }
    public long heapUsedBeforeBytes() { return heapUsedBeforeBytes; }
    public long heapUsedAfterLoadBytes() { return heapUsedAfterLoadBytes; }
    public long heapUsedAfterUnloadBytes() { return heapUsedAfterUnloadBytes; }
    public long heapRetainedAfterUnloadBytes() { return heapRetainedAfterUnloadBytes; }
    public long heapReclaimedBytes() { return heapReclaimedBytes; }
    public long committedDeltaBytes() { return committedDeltaBytes; }
    public long windowCollections() { return windowCollections; }
    public String reclaimLabel() { return reclaimLabel; }
    public String shape() { return shape; }
    public String probeCenter() { return probeCenter; }

    private void writeReport() {
        if (reportPath == null) return;
        StringBuilder sb = new StringBuilder(768);
        sb.append("StressTestRTP ticket-footprint probe").append(System.lineSeparator());
        sb.append("platform: ").append(Sched.isFolia() ? "folia" : "bukkit-family")
                .append(System.lineSeparator());
        sb.append("server: ").append(plugin.getServerVersion()).append(System.lineSeparator());
        sb.append("reruns: ").append(reruns.get()).append(System.lineSeparator());
        if (!everProbed) {
            sb.append("result: NOT MEASURED (").append(failureReason).append(')')
                    .append(System.lineSeparator());
            sb.append("All ticket_footprint_* columns are -1. -1 never means one chunk.")
                    .append(System.lineSeparator());
        } else {
            sb.append("probe_center: ").append(probeCenter).append(System.lineSeparator());
            sb.append("settle_ticks: ").append(settleTicks).append(System.lineSeparator());
            sb.append("attribution_radius_chunks: ").append(COUNT_RADIUS)
                    .append(System.lineSeparator());
            sb.append("chunks_per_ticket: ").append(chunksPerTicket)
                    .append("  shape=").append(shape).append(System.lineSeparator());
            sb.append("chunks_released_on_removal: ").append(chunksReleased)
                    .append(System.lineSeparator());
            sb.append("noise_loads_outside_radius: ").append(noiseLoads)
                    .append(noiseLoads == 0L
                            ? "  (quiet window; reading is attributable)"
                            : "  (window was NOT quiet; chunks_per_ticket is an UPPER BOUND)")
                    .append(System.lineSeparator());
            sb.append("heap_used_before_bytes: ").append(heapUsedBeforeBytes)
                    .append(System.lineSeparator());
            sb.append("heap_used_after_load_bytes: ").append(heapUsedAfterLoadBytes)
                    .append(System.lineSeparator());
            sb.append("heap_used_after_unload_bytes: ").append(heapUsedAfterUnloadBytes)
                    .append(System.lineSeparator());
            sb.append("heap_delta_bytes: ").append(heapDeltaBytes)
                    .append("  label=").append(heapLabel()).append(System.lineSeparator());
            sb.append("bytes_per_chunk: ").append(bytesPerChunk)
                    .append(System.lineSeparator());
            sb.append("heap_retained_after_unload_bytes: ").append(heapRetainedAfterUnloadBytes)
                    .append(System.lineSeparator());
            sb.append("heap_reclaimed_bytes: ").append(heapReclaimedBytes)
                    .append(System.lineSeparator());
            sb.append("committed_delta_bytes: ").append(committedDeltaBytes)
                    .append(committedDeltaBytes > 0L
                            ? "  (JVM grew the heap during the probe)" : "")
                    .append(System.lineSeparator());
            sb.append("collections_in_window: ").append(windowCollections)
                    .append(windowCollections == 0L
                            ? "  (none; heap figures are attributable)"
                            : "  (heap figures are NOT attributable to the ticket)")
                    .append(System.lineSeparator());
            sb.append("reclaim_label: ").append(reclaimLabel).append(System.lineSeparator());
        }
        final String body = sb.toString();
        Sched.runAsync(plugin, () -> {
            try {
                if (reportPath.getParent() != null) {
                    Files.createDirectories(reportPath.getParent());
                }
                Files.writeString(reportPath, body, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING,
                        "[StressTestRTP] TicketFootprintProbe: could not write " + reportPath, e);
            }
        });
    }
}
