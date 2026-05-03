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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
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
 * <h3>Attribution chain</h3>
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

    /** Snapshots at the start of the current phase, for {@link #phaseTotal()}. */
    private volatile long phaseBaselineTotal = 0L;
    private volatile long phaseBaselineBackground = 0L;
    private volatile long phaseBaselineAttributed = 0L;

    /** Currently-in-flight attempts, in dispatch order (most recent at tail).
     *  Concurrent deque so the event handler can iterate it without locking;
     *  attempts are added in {@link #beginAttempt} and removed in
     *  {@link #endAttempt}. Iteration cost is bounded by the runner's
     *  concurrency cap (typically 1–4). */
    private final Deque<MetricsRecorder.Attempt> inFlight = new ConcurrentLinkedDeque<>();
    /** Per-attempt load tally, keyed by attempt id. Removed by
     *  {@link #endAttempt}, which copies the tally into
     *  {@link MetricsRecorder.Attempt#attributedChunkLoads}. */
    private final ConcurrentHashMap<java.util.UUID, AtomicLong> attemptCounts = new ConcurrentHashMap<>();

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

        // Step 1: plugin-ticket attribution (Paper only).
        if (pluginTicketsSupported) {
            MetricsRecorder.Attempt a = attributeByPluginTicket(event.getChunk());
            if (a != null) {
                bump(a);
                return;
            }
        }

        // Step 2: main-thread temporal attribution.
        if (Bukkit.isPrimaryThread()) {
            MetricsRecorder.Attempt a = inFlight.peekLast();
            if (a != null) {
                bump(a);
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

    private void bump(MetricsRecorder.Attempt a) {
        AtomicLong c = attemptCounts.get(a.attemptId);
        if (c == null) {
            // Race: attempt ended between inFlight.peekLast() and this lookup.
            // Treat as background to keep totals reconciled.
            phaseBackgroundLoads.incrementAndGet();
            return;
        }
        c.incrementAndGet();
        phaseAttributedLoads.incrementAndGet();
    }

    /** Called by {@link MetricsRecorder#onDispatch} to register an attempt
     *  for chunk-load attribution. Idempotent — re-registering an already-known
     *  attempt is a no-op. */
    public void beginAttempt(MetricsRecorder.Attempt a) {
        if (a == null) return;
        if (attemptCounts.putIfAbsent(a.attemptId, new AtomicLong(0L)) == null) {
            inFlight.add(a);
        }
    }

    /** Called by {@link MetricsRecorder#onComplete}/{@link MetricsRecorder#onTimeout}
     *  to finalise an attempt's chunk-load tally. Writes the count into
     *  {@link MetricsRecorder.Attempt#attributedChunkLoads} and removes the
     *  per-attempt accumulator. Safe to call multiple times. */
    public void endAttempt(MetricsRecorder.Attempt a) {
        if (a == null) return;
        AtomicLong c = attemptCounts.remove(a.attemptId);
        if (c != null) {
            a.attributedChunkLoads = c.get();
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
}
