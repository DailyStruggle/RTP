package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Peak residency accounting: resident chunks and plugin chunk tickets held.
 *
 * <p>Why residency and not just heap: the retained term that separates these
 * designs is platform-owned. One family keeps coordinate tuples plus tickets
 * that are tracked and released; another keeps resident chunk neighbourhoods
 * alive. The bytes of a resident chunk - palettes, sections, heightmaps,
 * entity lists - are never attributable to the plugin by any JVM counter, so
 * a chunk-count peak is the honest observable proxy for them. Peaks rather
 * than averages: an average residency hides exactly the retention a peak
 * exposes.
 *
 * <p>Resident chunks are tracked as a signed delta over a one-time baseline
 * taken on a tick thread at enable ({@link #captureBaseline()}); every
 * subsequent change comes from MONITOR {@link ChunkLoadEvent} /
 * {@link ChunkUnloadEvent} increments on primitive counters. This avoids
 * calling {@code World#getLoadedChunks()} - which allocates a full array of
 * every loaded chunk - anywhere near the measured path.
 *
 * <p>Plugin chunk tickets are read from Paper's
 * {@code World#getPluginChunkTickets()} on its own low-frequency timer
 * (default 1000 ms), never per attempt. The call is absent on Spigot and is
 * not region-safe on Folia, so on those platforms the ticket peaks stay at
 * the {@code -1} not-measured sentinel and are never reported as zero.
 */
public final class ResidencySampler implements Listener {

    private final Plugin plugin;
    /** Ticket-sample period in ms. Off the measured path by construction. */
    private final long ticketSamplePeriodMs;

    /** Baseline count of loaded chunks at enable, or {@code -1} until captured. */
    private volatile long residentBaseline = -1L;
    /** Signed load/unload delta since the baseline. */
    private final AtomicLong residentDelta = new AtomicLong();

    /** Peaks since the last {@link #resetPhase()}. {@code -1} = not measured. */
    private final AtomicLong peakResidentChunks = new AtomicLong(-1L);
    private final AtomicLong peakPluginTickets = new AtomicLong(-1L);
    private final AtomicLong peakTargetTickets = new AtomicLong(-1L);

    /** Plugin name whose tickets are broken out separately; empty = no breakout. */
    private volatile String targetLabel = "";

    /** {@code World#getPluginChunkTickets()} - Paper API, resolved once. */
    private volatile Method getPluginChunkTicketsMethod;
    private volatile boolean ticketsSupported = false;

    private Object ticketTaskId = null;
    private volatile boolean registered = false;

    public ResidencySampler(Plugin plugin, long ticketSamplePeriodMs) {
        this.plugin = plugin;
        this.ticketSamplePeriodMs = Math.max(250L, ticketSamplePeriodMs);
    }

    /** Sets the arm's target plugin name for the per-plugin ticket breakout. */
    public void setTargetLabel(String label) {
        this.targetLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
    }

    public void register() {
        if (registered) return;
        // Ticket residency is a Paper-only reading, and on Folia the per-world
        // ticket map is not safe to walk from the global region thread, so the
        // path is gated off there at runtime rather than by a build variant.
        if (!Sched.isFolia()) {
            try {
                getPluginChunkTicketsMethod = World.class.getMethod("getPluginChunkTickets");
                ticketsSupported = true;
            } catch (NoSuchMethodException e) {
                ticketsSupported = false;
            }
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // One-time baseline plus the low-frequency ticket sample, both on a
        // tick thread. getLoadedChunks() allocates, which is why it is called
        // exactly once and never during an attempt.
        Sched.runGlobal(plugin, this::captureBaseline);
        if (ticketsSupported) {
            long ticks = Math.max(1L, ticketSamplePeriodMs / 50L);
            ticketTaskId = Sched.runGlobalTimer(plugin, this::sampleTickets, ticks);
        }
        registered = true;
        plugin.getLogger().info("[StressTestRTP] ResidencySampler: chunk residency tracked; "
                + (ticketsSupported
                        ? "plugin chunk tickets sampled every " + ticketSamplePeriodMs + " ms."
                        : "plugin chunk tickets NOT available on this platform (columns stay -1)."));
    }

    public void unregister() {
        if (!registered) return;
        HandlerList.unregisterAll(this);
        Sched.cancel(ticketTaskId);
        ticketTaskId = null;
        registered = false;
    }

    /** Reads the current loaded-chunk count once, on a tick thread. */
    private void captureBaseline() {
        long total = 0L;
        try {
            for (World w : Bukkit.getWorlds()) {
                total += w.getLoadedChunks().length;
            }
        } catch (Throwable t) {
            residentBaseline = -1L;
            return;
        }
        residentBaseline = total;
        residentDelta.set(0L);
        bumpPeak(peakResidentChunks, total);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        long now = residentDelta.incrementAndGet();
        observeResident(now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        residentDelta.decrementAndGet();
    }

    private void observeResident(long delta) {
        long base = residentBaseline;
        if (base < 0L) return;                     // baseline not captured yet
        bumpPeak(peakResidentChunks, base + delta);
    }

    /** Monotonic max update on a primitive counter; no allocation, no lock. */
    private static void bumpPeak(AtomicLong peak, long value) {
        if (value < 0L) return;
        long cur = peak.get();
        while (value > cur) {
            if (peak.compareAndSet(cur, value)) return;
            cur = peak.get();
        }
    }

    /** Low-frequency ticket census. Runs on a tick thread by necessity (the
     *  Bukkit world view is not thread-safe) but never on an attempt path. */
    @SuppressWarnings("unchecked")
    private void sampleTickets() {
        Method m = getPluginChunkTicketsMethod;
        if (m == null) return;
        long all = 0L;
        long target = 0L;
        String label = targetLabel;
        try {
            for (World w : Bukkit.getWorlds()) {
                Object raw = m.invoke(w);
                if (!(raw instanceof Map<?, ?> map)) continue;
                for (Map.Entry<Plugin, Collection<?>> e
                        : ((Map<Plugin, Collection<?>>) map).entrySet()) {
                    Collection<?> chunks = e.getValue();
                    if (chunks == null) continue;
                    int n = chunks.size();
                    all += n;
                    Plugin p = e.getKey();
                    String name = p == null ? null : p.getName();
                    if (!label.isEmpty() && name != null
                            && name.toLowerCase(Locale.ROOT).equals(label)) {
                        target += n;
                    }
                }
            }
        } catch (Throwable t) {
            // Degrade once, never spam: a platform that rejects the query
            // leaves the columns at their not-measured sentinel.
            getPluginChunkTicketsMethod = null;
            ticketsSupported = false;
            Sched.cancel(ticketTaskId);
            ticketTaskId = null;
            plugin.getLogger().warning("[StressTestRTP] ResidencySampler: "
                    + "getPluginChunkTickets() unavailable; ticket columns stay -1.");
            return;
        }
        bumpPeak(peakPluginTickets, all);
        if (!label.isEmpty()) bumpPeak(peakTargetTickets, target);
    }

    /** Clears the peaks for a new phase. The resident counter itself is
     *  cumulative and is not reset - only the peak window moves. */
    public void resetPhase() {
        long base = residentBaseline;
        peakResidentChunks.set(base < 0L ? -1L : Math.max(0L, base + residentDelta.get()));
        peakPluginTickets.set(ticketsSupported ? 0L : -1L);
        peakTargetTickets.set(ticketsSupported && !targetLabel.isEmpty() ? 0L : -1L);
    }

    /** Peak resident chunk count in the current phase, or {@code -1}. */
    public long peakResidentChunks() { return peakResidentChunks.get(); }

    /** Peak plugin chunk tickets held server-wide in the current phase, or {@code -1}. */
    public long peakPluginTickets() { return peakPluginTickets.get(); }

    /** Peak tickets held by the arm's target plugin, or {@code -1}. */
    public long peakTargetPluginTickets() { return peakTargetTickets.get(); }
}
