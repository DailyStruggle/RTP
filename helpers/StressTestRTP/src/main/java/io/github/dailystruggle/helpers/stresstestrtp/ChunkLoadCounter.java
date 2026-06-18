package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Deque;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Per-attempt {@link ChunkLoadEvent} attribution.
 *
 * <p>Earlier versions of this class incremented a single global counter on
 * every {@code ChunkLoadEvent} and let callers diff snapshots at dispatch and
 * completion to estimate "chunks loaded by this teleport". That heuristic
 * silently double-counts in any concurrent run because the in-flight windows
 * of two players overlap completely on Spigot — every chunk loaded for
 * player B's pipeline lands inside player A's snapshot window and vice versa.
 * With queueing-style RTP plugins (RTP's own {@code keptLocations}, BetterRTP's
 * {@code Queue.Enabled}, JakesRTP's pre-cache) it also lies about <em>when</em>
 * the chunk-loading work happened: the plugin paid the cost during the
 * pre-warm phase, and the in-flight window's delta is zero — but the global
 * counter caught view-distance follow-ups from the previous teleport and
 * billed them to this one.
 *
 * <p>This rewrite attributes each load to a specific in-flight attempt using
 * a small, conditional chain of heuristics. Pre-condition: the operator has
 * disabled per-plugin pre-queueing where possible (so loads are caused by the
 * pipeline that fires on dispatch, not by a background pre-warmer).
 *
 * <p><b>Attribution chain</b>
 *
 * <ol>
 *   <li><b>Plugin tickets (Paper).</b> If the loaded {@link Chunk} exposes a
 *       non-empty {@code getPluginChunkTickets()} (Paper-API method, absent on
 *       Spigot 1.20.1), and one of the listed plugins matches the
 *       {@code targetLabel} of an in-flight attempt (case-insensitive), the
 *       load is attributed to that attempt.</li>
 *   <li><b>Main-thread temporal (Spigot fallback).</b> If the event fires on
 *       the server tick thread (i.e. {@link Bukkit#isPrimaryThread()}) while
 *       at least one attempt is in flight, the load is attributed to the
 *       <em>most-recently-dispatched</em> in-flight attempt — the one whose
 *       {@code dispatchCommand} stack frame is, by construction, the only
 *       caller currently on the main thread that could have triggered a
 *       chunk load. With concurrency-cap = 1 this is exact; with concurrency
 *       &gt; 1 it is best-effort and biased toward the latest dispatcher.</li>
 *   <li><b>Background bucket.</b> Loads on chunk-system threads with no
 *       plugin-ticket match, or main-thread loads with zero in-flight
 *       attempts, fall here. The phase row records both the attributed sum
 *       and the background sum so totals reconcile against
 *       {@link #phaseTotal()}.</li>
 * </ol>
 *
 * <p>Listener priority is {@link EventPriority#MONITOR} (read-only) and the
 * handler does no allocation beyond the atomic increment, so it adds no
 * measurable overhead even at peak chunk-load rates.
 */
public final class ChunkLoadCounter implements Listener {

    private final Plugin plugin;

    /** Monotonically-increasing total since plugin enable. Used for sanity
     *  checks and to compute background as {@code total - attributed}. */
    private final AtomicLong totalLoads = new AtomicLong();
    /** Loads attributed to no in-flight attempt during the current phase. */
    private final AtomicLong phaseBackgroundLoads = new AtomicLong();
    /** Sum of per-attempt attributions during the current phase (for reconciliation). */
    private final AtomicLong phaseAttributedLoads = new AtomicLong();
    /** Sum of per-attempt <em>selection</em> loads (attributed minus the
     *  post-teleport arrival ring) finalised during the current phase. */
    private final AtomicLong phaseSelectionLoads = new AtomicLong();

    /** Snapshots at the start of the current phase, for {@link #phaseTotal()}. */
    private volatile long phaseBaselineTotal = 0L;
    private volatile long phaseBaselineBackground = 0L;
    private volatile long phaseBaselineAttributed = 0L;
    private volatile long phaseBaselineSelection = 0L;

    /** Effective render-distance (in chunks) used to size the post-teleport
     *  arrival ring that {@link #endAttempt} subtracts from the raw attributed
     *  count. {@code -1} means "read {@link Bukkit#getViewDistance()} live".
     *  Set by the plugin from config when the server view distance is known. */
    private volatile int configuredViewDistance = -1;

    /** Currently-in-flight attempts, in dispatch order (most recent at tail).
     *  Concurrent deque so the event handler can iterate it without locking;
     *  attempts are added in {@link #beginAttempt} and removed in
     *  {@link #endAttempt}. Iteration cost is bounded by the runner's
     *  concurrency cap (typically 1–4). */
    private final Deque<MetricsRecorder.Attempt> inFlight = new ConcurrentLinkedDeque<>();
    /** Per-attempt load tally, keyed by attempt id. Removed by
     *  {@link #endAttempt}, which copies the raw count into
     *  {@link MetricsRecorder.Attempt#attributedChunkLoads} and the
     *  arrival-filtered count into
     *  {@link MetricsRecorder.Attempt#selectionChunkLoads}. */
    private final ConcurrentHashMap<java.util.UUID, Tally> attemptCounts = new ConcurrentHashMap<>();

    /** Per-attempt accumulator: a raw load count plus the packed chunk
     *  coordinates of each attributed load, so {@link #endAttempt} can
     *  separate the single destination-selection load from the
     *  {@code (2*viewDistance+1)^2} arrival ring the server loads when the
     *  player materialises at the destination. */
    private static final class Tally {
        final AtomicLong count = new AtomicLong(0L);
        final Queue<Long> chunks = new ConcurrentLinkedQueue<>();
    }

    private static long packKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    private static int keyX(long k) { return (int) (k >> 32); }

    private static int keyZ(long k) { return (int) k; }

    /** Cached reflective lookup of {@code Chunk#getPluginChunkTickets()}.
     *  Resolved once at register-time. {@code null} on Spigot. */
    private volatile Method getPluginChunkTicketsMethod = null;
    private volatile boolean pluginTicketsSupported = false;

    private volatile boolean registered = false;

    public ChunkLoadCounter(Plugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        if (registered) return;
        // Reflectively probe for Paper's per-chunk plugin-tickets API. This
        // is the only cross-platform discriminator we have for "which plugin
        // owns this chunk load" — Spigot's ChunkLoadEvent exposes nothing
        // beyond `isNewChunk()`, and neither platform exposes a load-cause
        // enum on the event itself.
        try {
            Method m = Chunk.class.getMethod("getPluginChunkTickets");
            getPluginChunkTicketsMethod = m;
            pluginTicketsSupported = true;
            plugin.getLogger().info(
                    "[StressTestRTP] ChunkLoadCounter: Chunk#getPluginChunkTickets() detected — "
                            + "per-attempt chunk attribution will use plugin tickets when available.");
        } catch (NoSuchMethodException e) {
            pluginTicketsSupported = false;
            plugin.getLogger().info(
                    "[StressTestRTP] ChunkLoadCounter: Chunk#getPluginChunkTickets() not available "
                            + "(Spigot path) — falling back to main-thread temporal attribution.");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registered = true;
    }

    public void unregister() {
        if (!registered) return;
        HandlerList.unregisterAll(this);
        registered = false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        totalLoads.incrementAndGet();

        final int cx = event.getChunk().getX();
        final int cz = event.getChunk().getZ();

        // Step 1: plugin-ticket attribution (Paper only).
        if (pluginTicketsSupported) {
            MetricsRecorder.Attempt a = attributeByPluginTicket(event.getChunk());
            if (a != null) {
                bump(a, cx, cz);
                return;
            }
        }

        // Step 2: main-thread temporal attribution.
        if (Bukkit.isPrimaryThread()) {
            MetricsRecorder.Attempt a = inFlight.peekLast();
            if (a != null) {
                bump(a, cx, cz);
                return;
            }
        }

        // Step 3: background.
        phaseBackgroundLoads.incrementAndGet();
    }

    /**
     * Walks the chunk's plugin-ticket list and matches against the labels of
     * currently-in-flight attempts. The match is case-insensitive on
     * {@code Plugin#getName()} vs {@code Attempt#targetLabel} — for the
     * harness's normal labels ({@code rtp}, {@code betterrtp}, {@code huskhomes},
     * {@code jakesrtp}) the plugin name and label match by convention. If the
     * label does not match a real plugin (e.g. the operator used a custom
     * label), this method returns null and step 2 takes over.
     */
    @SuppressWarnings("unchecked")
    private MetricsRecorder.Attempt attributeByPluginTicket(Chunk chunk) {
        Method m = getPluginChunkTicketsMethod;
        if (m == null) return null;
        Collection<Plugin> tickets;
        try {
            Object raw = m.invoke(chunk);
            if (!(raw instanceof Collection<?>)) return null;
            tickets = (Collection<Plugin>) raw;
        } catch (ReflectiveOperationException e) {
            // Disable the path on first failure to avoid log-spam.
            pluginTicketsSupported = false;
            plugin.getLogger().log(Level.WARNING,
                    "[StressTestRTP] getPluginChunkTickets() failed; disabling plugin-ticket attribution.", e);
            return null;
        }
        if (tickets == null || tickets.isEmpty()) return null;
        for (MetricsRecorder.Attempt a : inFlight) {
            String label = a.targetLabel == null ? "" : a.targetLabel.toLowerCase(Locale.ROOT);
            if (label.isEmpty()) continue;
            for (Plugin p : tickets) {
                if (p == null) continue;
                String name = p.getName();
                if (name != null && name.toLowerCase(Locale.ROOT).equals(label)) {
                    return a;
                }
            }
        }
        return null;
    }

    private void bump(MetricsRecorder.Attempt a, int chunkX, int chunkZ) {
        Tally t = attemptCounts.get(a.attemptId);
        if (t == null) {
            // Race: attempt ended between inFlight.peekLast() and this lookup.
            // Treat as background to keep totals reconciled.
            phaseBackgroundLoads.incrementAndGet();
            return;
        }
        t.count.incrementAndGet();
        t.chunks.add(packKey(chunkX, chunkZ));
        phaseAttributedLoads.incrementAndGet();
    }

    /** Render distance (in chunks) used to size the arrival ring. */
    private int viewDistanceChunks() {
        int vd = configuredViewDistance;
        if (vd <= 0) {
            try {
                vd = Bukkit.getViewDistance();
            } catch (Throwable ignore) {
                vd = 10;
            }
        }
        return Math.max(1, vd);
    }

    /**
     * Splits an attempt's raw attributed loads into selection cost (the chunks
     * the plugin loaded to find/verify the destination) and arrival cost (the
     * render-distance square the server loads around the player on teleport).
     * A load is arrival when its chunk is within {@code viewDistance + 1}
     * chunks (Chebyshev) of the destination chunk but is not the destination
     * chunk itself. Returns the selection count (raw minus arrival). Falls back
     * to the raw count when the destination is unknown (timeouts / failures).
     */
    private long computeSelection(MetricsRecorder.Attempt a, Tally t, long raw) {
        if (!a.success) return raw;
        if (a.toX == 0.0 && a.toZ == 0.0) return raw;
        int destX = (int) Math.floor(a.toX / 16.0);
        int destZ = (int) Math.floor(a.toZ / 16.0);
        int r = viewDistanceChunks() + 1;
        long arrival = 0L;
        for (Long packed : t.chunks) {
            int cheb = Math.max(Math.abs(keyX(packed) - destX), Math.abs(keyZ(packed) - destZ));
            if (cheb == 0) continue;       // destination chunk itself = selection cost
            if (cheb <= r) arrival++;      // within render distance of arrival = server cost
        }
        long sel = raw - arrival;
        return sel < 0 ? 0 : sel;
    }

    /** Set the effective server render distance (chunks) for arrival-ring
     *  sizing. A value &lt;= 0 restores the live {@link Bukkit#getViewDistance()}
     *  lookup. */
    public void setViewDistance(int viewDistance) {
        this.configuredViewDistance = viewDistance;
    }

    /** Called by {@link MetricsRecorder#onDispatch} to register an attempt
     *  for chunk-load attribution. Idempotent — re-registering an already-known
     *  attempt is a no-op. */
    public void beginAttempt(MetricsRecorder.Attempt a) {
        if (a == null) return;
        if (attemptCounts.putIfAbsent(a.attemptId, new Tally()) == null) {
            inFlight.add(a);
        }
    }

    /** Called by {@link MetricsRecorder#onComplete}/{@link MetricsRecorder#onTimeout}
     *  to finalise an attempt's chunk-load tally. Writes the count into
     *  {@link MetricsRecorder.Attempt#attributedChunkLoads} and removes the
     *  per-attempt accumulator. Safe to call multiple times. */
    public void endAttempt(MetricsRecorder.Attempt a) {
        if (a == null) return;
        Tally t = attemptCounts.remove(a.attemptId);
        if (t != null) {
            long raw = t.count.get();
            a.attributedChunkLoads = raw;
            long selection = computeSelection(a, t, raw);
            a.selectionChunkLoads = selection;
            phaseSelectionLoads.addAndGet(selection);
        }
        inFlight.remove(a);
    }

    /** Monotonically-increasing global total since plugin enable. */
    public long total() {
        return totalLoads.get();
    }

    /** Reset phase counters. Per-attempt accumulators are <em>not</em> cleared —
     *  in-flight attempts keep counting across the phase boundary, which is
     *  correct: an attempt that started near the end of phase N and finishes
     *  in phase N+1 should report its full chunk-load cost on its CSV row. */
    public void resetPhase() {
        phaseBaselineTotal = totalLoads.get();
        phaseBaselineBackground = phaseBackgroundLoads.get();
        phaseBaselineAttributed = phaseAttributedLoads.get();
        phaseBaselineSelection = phaseSelectionLoads.get();
    }

    /** Number of chunk loads observed since the last {@link #resetPhase()}. */
    public long phaseTotal() {
        return Math.max(0L, totalLoads.get() - phaseBaselineTotal);
    }

    /** Loads attributed to the background bucket since the last
     *  {@link #resetPhase()} (i.e. with no in-flight attempt to charge to,
     *  or async loads on Spigot, or non-matching plugin-ticket loads on Paper). */
    public long phaseBackground() {
        return Math.max(0L, phaseBackgroundLoads.get() - phaseBaselineBackground);
    }

    /** Loads attributed to one of the in-flight attempts since the last
     *  {@link #resetPhase()}. {@code phaseAttributed() + phaseBackground()}
     *  should equal {@link #phaseTotal()} modulo loads counted against
     *  attempts that began before the phase reset. */
    public long phaseAttributed() {
        return Math.max(0L, phaseAttributedLoads.get() - phaseBaselineAttributed);
    }

    /** Selection loads (raw attributed minus the post-teleport arrival ring)
     *  finalised since the last {@link #resetPhase()}. This is the
     *  view-distance-corrected per-attempt chunk-load metric: it excludes the
     *  server-caused render-distance square loaded when the player arrives, so
     *  it reflects the plugin's destination-selection work rather than a
     *  constant, plugin-independent arrival cost. */
    public long phaseSelection() {
        return Math.max(0L, phaseSelectionLoads.get() - phaseBaselineSelection);
    }
}
