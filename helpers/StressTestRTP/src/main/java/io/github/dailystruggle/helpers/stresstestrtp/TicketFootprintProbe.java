package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Setup-phase measurement of what one plugin chunk ticket actually costs.
 *
 * <p><b>Why.</b> Every retained-memory figure this harness reports is quoted
 * per cached location, and a cached location is one {@code addPluginChunkTicket}
 * call. Translating that into bytes requires knowing how many chunks the
 * server makes resident in response to a single ticket - which is a platform
 * decision, not a plugin one. Vanilla propagates ticket levels outward, so a
 * ticket below the {@code FULL} threshold pins a neighbourhood rather than one
 * chunk; Paper and Folia reimplemented that subsystem and need not agree with
 * vanilla or with each other. Assuming a value here silently multiplies or
 * divides every downstream bytes-per-entry inference by the factor assumed.
 * So the factor is measured on the machine under test, once, before any
 * teleport is recorded, and travels with the run.
 *
 * <p><b>Method.</b> One ticket, one chunk, counted by events:
 * <ol>
 *   <li>Pick a chunk far from the world origin (the benchmark's teleport
 *       radius is origin-centred, so the probe does not warm ground the run
 *       then measures) that reports {@code isChunkLoaded() == false}. Up to
 *       {@link #MAX_CANDIDATES} candidates are tried before giving up.</li>
 *   <li>Apply exactly one {@code addPluginChunkTicket} on the thread owning
 *       that chunk, and count {@link ChunkLoadEvent}s within
 *       {@link #COUNT_RADIUS} chunks of it for {@link #settleTicks} ticks.</li>
 *   <li>Release the ticket and count {@link ChunkUnloadEvent}s over a second
 *       settle window, so retention is shown to be symmetric rather than
 *       assumed to be.</li>
 * </ol>
 *
 * <p>Counting events rather than diffing {@code World#getLoadedChunks()} is
 * deliberate on two grounds: the array form allocates a reference to every
 * loaded chunk on the server, and it is not region-safe to walk on Folia,
 * whereas chunk events are already delivered on the owning thread. The probe
 * therefore reports the same quantity on every platform.
 *
 * <p><b>Confounder disclosure.</b> The probe cannot stop the server loading
 * chunks for its own reasons during the window. Loads inside the radius are
 * indistinguishable from ticket-caused loads and are counted; loads outside it
 * are counted separately and published as {@link #noiseLoads()}. A run whose
 * noise count is not zero has a footprint reading that is an upper bound, and
 * the emitted block says so. Probing an unpopulated server, before any
 * teleport phase begins, is what keeps that count at zero in practice.
 *
 * <p><b>Heap.</b> A used-heap delta is taken across the same window without
 * forcing a collection - a {@code System.gc()} on a live server under
 * measurement would corrupt the very GC columns the harness records. The
 * figure is consequently allocation-inclusive and is labelled
 * {@link #HEAP_LABEL} wherever it appears; it is an upper bound on retained
 * bytes, never a settled retained-set measurement.
 *
 * <p>All results are published into primitive volatile fields read by
 * {@link MetricsRecorder} without locking, and every unmeasured numeric stays
 * at {@link #NO_DATA} rather than defaulting to zero.
 */
public final class TicketFootprintProbe implements Listener {

    /** Sentinel for every unmeasured numeric this class publishes. */
    public static final long NO_DATA = -1L;

    /** States what the heap delta actually is, next to every use of it. */
    public static final String HEAP_LABEL = "UNCOLLECTED_ALLOCATION_INCLUSIVE";

    /** Chebyshev radius (chunks) around the probe centre within which a load
     *  is attributed to the ticket. Wide enough to contain any plausible
     *  propagation footprint (a 15x15 neighbourhood) so the measurement cannot
     *  silently truncate a large one; loads beyond it are recorded as noise. */
    private static final int COUNT_RADIUS = 7;

    /** Candidate chunks tried before the probe reports NOT MEASURED. */
    private static final int MAX_CANDIDATES = 16;

    /** Chunk separation between successive candidates, in chunks. Larger than
     *  {@link #COUNT_RADIUS} so a rejected candidate's neighbourhood cannot
     *  overlap the one finally probed. */
    private static final int CANDIDATE_STRIDE = 32;

    private final Plugin plugin;
    /** Blocks from origin at which to look for an unloaded probe chunk. */
    private final int originDistanceBlocks;
    /** Ticks awaited after applying, and again after releasing, the ticket. */
    private final long settleTicks;
    /** One-time report written beside the plugin's other setup artefacts. */
    private final Path reportPath;

    // --- window state -------------------------------------------------
    /** 0 = idle, 1 = counting loads, 2 = counting unloads. */
    private volatile int windowPhase = 0;
    private volatile String probeWorld = "";
    private volatile int centerX = 0;
    private volatile int centerZ = 0;
    private final AtomicLong windowLoads = new AtomicLong();
    private final AtomicLong windowUnloads = new AtomicLong();
    private final AtomicLong windowNoise = new AtomicLong();

    // --- published results --------------------------------------------
    private volatile boolean everProbed = false;
    private volatile long chunksPerTicket = NO_DATA;
    private volatile long chunksReleased = NO_DATA;
    private volatile long noiseLoads = NO_DATA;
    private volatile long heapDeltaBytes = NO_DATA;
    private volatile long bytesPerChunk = NO_DATA;
    private volatile String shape = "";
    private volatile String probeCenter = "";
    private volatile String failureReason = "";

    private volatile boolean registered = false;
    private long heapBeforeBytes = NO_DATA;

    public TicketFootprintProbe(Plugin plugin, int originDistanceBlocks, long settleTicks, Path reportPath) {
        this.plugin = plugin;
        this.originDistanceBlocks = Math.max(1024, originDistanceBlocks);
        this.settleTicks = Math.max(5L, settleTicks);
        this.reportPath = reportPath;
    }

    /** Registers the chunk listeners and schedules the one-shot probe. */
    public void start() {
        if (registered) return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registered = true;
        // Delayed so server start-up chunk traffic has drained before the
        // window opens; a busy start would land entirely in the noise count.
        Sched.runGlobalLater(plugin, this::beginProbe, settleTicks * 2L);
    }

    public void stop() {
        if (!registered) return;
        HandlerList.unregisterAll(this);
        registered = false;
    }

    // -----------------------------------------------------------------
    // Probe sequence
    // -----------------------------------------------------------------

    private void beginProbe() {
        World world;
        try {
            if (plugin.getServer().getWorlds().isEmpty()) {
                fail("no world loaded");
                return;
            }
            world = plugin.getServer().getWorlds().get(0);
        } catch (Throwable t) {
            fail("world lookup failed: " + t.getClass().getSimpleName());
            return;
        }
        tryCandidate(world, 0);
    }

    /**
     * Walks outward from the configured origin distance along a random bearing
     * until an unloaded chunk is found. The bearing is random so repeated runs
     * on one server do not keep probing (and keep warming) a single spot.
     */
    private void tryCandidate(World world, int attempt) {
        if (attempt >= MAX_CANDIDATES) {
            fail("no unloaded candidate chunk within " + MAX_CANDIDATES + " tries");
            return;
        }
        double bearing = ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0;
        int baseChunk = originDistanceBlocks >> 4;
        int reach = baseChunk + attempt * CANDIDATE_STRIDE;
        int cx = (int) Math.round(Math.cos(bearing) * reach);
        int cz = (int) Math.round(Math.sin(bearing) * reach);
        // isChunkLoaded and addPluginChunkTicket both belong to the owning
        // region; on Folia a cross-region call is rejected outright.
        Sched.runOnRegion(plugin, world, cx, cz, () -> {
            try {
                if (world.isChunkLoaded(cx, cz)) {
                    Sched.runGlobal(plugin, () -> tryCandidate(world, attempt + 1));
                    return;
                }
                openWindow(world, cx, cz);
                if (!world.addPluginChunkTicket(cx, cz, plugin)) {
                    windowPhase = 0;
                    fail("addPluginChunkTicket returned false at " + cx + "," + cz);
                    return;
                }
            } catch (Throwable t) {
                windowPhase = 0;
                fail("ticket apply failed: " + t.getClass().getSimpleName());
                return;
            }
            Sched.runGlobalLater(plugin, () -> closeLoadWindow(world, cx, cz), settleTicks);
        });
    }

    private void openWindow(World world, int cx, int cz) {
        probeWorld = world.getName();
        centerX = cx;
        centerZ = cz;
        windowLoads.set(0L);
        windowUnloads.set(0L);
        windowNoise.set(0L);
        Runtime rt = Runtime.getRuntime();
        heapBeforeBytes = rt.totalMemory() - rt.freeMemory();
        windowPhase = 1;
    }

    /** Reads the load count, then releases the ticket and opens the unload window. */
    private void closeLoadWindow(World world, int cx, int cz) {
        long loads = windowLoads.get();
        long noise = windowNoise.get();
        Runtime rt = Runtime.getRuntime();
        long heapAfter = rt.totalMemory() - rt.freeMemory();
        long heapDelta = (heapBeforeBytes >= 0 && heapAfter >= heapBeforeBytes)
                ? heapAfter - heapBeforeBytes : NO_DATA;

        chunksPerTicket = loads;
        noiseLoads = noise;
        shape = classifyShape(loads);
        heapDeltaBytes = heapDelta;
        bytesPerChunk = (heapDelta > 0 && loads > 0) ? heapDelta / loads : NO_DATA;
        probeCenter = probeWorld + "@" + cx + "," + cz;

        windowPhase = 2;
        Sched.runOnRegion(plugin, world, cx, cz, () -> {
            try {
                world.removePluginChunkTicket(cx, cz, plugin);
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
        windowPhase = 0;
        everProbed = true;
        writeReport();
        plugin.getLogger().info(String.format(Locale.ROOT,
                "[StressTestRTP] TicketFootprintProbe: one plugin chunk ticket at %s made "
                        + "%d chunk(s) resident (%s); %d released on removal; noise=%d; "
                        + "heap_delta=%d bytes [%s].",
                probeCenter, chunksPerTicket, shape, chunksReleased, noiseLoads,
                heapDeltaBytes, HEAP_LABEL));
    }

    private void fail(String reason) {
        failureReason = reason;
        everProbed = false;
        windowPhase = 0;
        plugin.getLogger().warning("[StressTestRTP] TicketFootprintProbe: not measured (" + reason
                + "); ticket-footprint columns stay -1.");
        writeReport();
    }

    /** Names the footprint when it matches a square neighbourhood exactly.
     *  Anything else is reported literally as IRREGULAR rather than rounded
     *  to the nearest square, because a partial load is a real finding. */
    static String classifyShape(long chunks) {
        if (chunks < 0) return "";
        if (chunks == 0) return "NONE";
        long side = Math.round(Math.sqrt((double) chunks));
        if (side * side == chunks && (side % 2L) == 1L) return side + "x" + side;
        return "IRREGULAR";
    }

    // -----------------------------------------------------------------
    // Event counting
    // -----------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (windowPhase != 1) return;
        if (inCountRadius(event.getChunk().getWorld().getName(),
                event.getChunk().getX(), event.getChunk().getZ())) {
            windowLoads.incrementAndGet();
        } else {
            windowNoise.incrementAndGet();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (windowPhase != 2) return;
        if (inCountRadius(event.getChunk().getWorld().getName(),
                event.getChunk().getX(), event.getChunk().getZ())) {
            windowUnloads.incrementAndGet();
        }
    }

    private boolean inCountRadius(String world, int cx, int cz) {
        if (!probeWorld.equals(world)) return false;
        return Math.abs(cx - centerX) <= COUNT_RADIUS && Math.abs(cz - centerZ) <= COUNT_RADIUS;
    }

    // -----------------------------------------------------------------
    // Published readings
    // -----------------------------------------------------------------

    public boolean everProbed() { return everProbed; }

    /** Chunks made resident by one plugin chunk ticket, or {@link #NO_DATA}. */
    public long chunksPerTicket() { return chunksPerTicket; }

    /** Chunks unloaded after the ticket was released, or {@link #NO_DATA}. */
    public long chunksReleased() { return chunksReleased; }

    /** Loads seen outside the attribution radius during the window. Zero is a
     *  measurement (a quiet probe); {@link #NO_DATA} means no probe ran. */
    public long noiseLoads() { return noiseLoads; }

    /** Used-heap delta across the window. See {@link #HEAP_LABEL}. */
    public long heapDeltaBytes() { return heapDeltaBytes; }

    /** Heap delta divided by chunks loaded, or {@link #NO_DATA}. */
    public long bytesPerChunk() { return bytesPerChunk; }

    /** Square-neighbourhood name (e.g. {@code 1x1}, {@code 5x5}), {@code NONE},
     *  or {@code IRREGULAR}. Empty when no probe ran. */
    public String shape() { return shape; }

    /** {@code world@cx,cz} of the probed chunk. Empty when no probe ran. */
    public String probeCenter() { return probeCenter; }

    // -----------------------------------------------------------------
    // Report
    // -----------------------------------------------------------------

    /** Renders the report and hands the write to an off-tick thread. Every
     *  caller is on a tick or region thread, and nothing waits on the file. */
    private void writeReport() {
        if (reportPath == null) return;
        StringBuilder sb = new StringBuilder(768);
        sb.append("StressTestRTP ticket-footprint probe").append(System.lineSeparator());
        sb.append("platform: ").append(Sched.isFolia() ? "folia" : "bukkit-family")
                .append(System.lineSeparator());
        sb.append("server: ").append(plugin.getServer().getVersion()).append(System.lineSeparator());
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
            sb.append("heap_delta_bytes: ").append(heapDeltaBytes)
                    .append("  label=").append(HEAP_LABEL).append(System.lineSeparator());
            sb.append("bytes_per_chunk: ").append(bytesPerChunk)
                    .append(System.lineSeparator());
            sb.append(System.lineSeparator());
            sb.append("Interpretation. Multiply chunks_per_ticket by the cached-location cap")
                    .append(System.lineSeparator());
            sb.append("under test to get the resident-chunk cost of a full hot cache. A ticket")
                    .append(System.lineSeparator());
            sb.append("released cleanly (chunks_released_on_removal == chunks_per_ticket) shows")
                    .append(System.lineSeparator());
            sb.append("retention is bounded rather than leaked. bytes_per_chunk is allocation-")
                    .append(System.lineSeparator());
            sb.append("inclusive and uncollected: treat it as an upper bound, not a retained set.")
                    .append(System.lineSeparator());
        }
        final String body = sb.toString();
        Sched.runAsync(plugin, () -> {
            try {
                Files.createDirectories(reportPath.getParent());
                Files.writeString(reportPath, body, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING,
                        "[StressTestRTP] TicketFootprintProbe: could not write " + reportPath, e);
            }
        });
    }
}
