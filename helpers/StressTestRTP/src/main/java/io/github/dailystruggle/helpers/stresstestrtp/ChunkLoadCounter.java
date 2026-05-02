package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts {@link ChunkLoadEvent}s globally for the duration of a stress run.
 *
 * <p>Rationale: the per-attempt CSV's {@code cpu_ms_per_attempt_total} column
 * does not capture chunk I/O work that the server attributes to its own
 * worker threads (Paper's chunk system threads, the I/O pool, etc.) rather
 * than to a plugin's listeners. A queue-warming RTP-family plugin like
 * BetterRTP can issue {@code PreloadRadius=5} (an 11×11 chunk square = 121
 * chunks) per safe-location candidate, but most of that work is billed to
 * the server's chunk threads — not the plugin's call stack — so it never
 * shows up in CPU-by-thread sampling.
 *
 * <p>Chunk-load count is the cleanest cross-plugin proxy for "how much I/O
 * did this teleport actually trigger?". Two scopes are tracked:
 * <ul>
 *   <li>Phase counter — accumulates from {@link #resetPhase()} through to
 *       the next reset. Read by {@link MetricsRecorder#endPhase} and
 *       written as a {@code chunks_loaded} column on the phases CSV.</li>
 *   <li>Snapshot for per-attempt deltas — callers (the runner's dispatch
 *       path) capture {@link #total()} at dispatch and again at completion;
 *       the difference is the chunk-load count attributable to that
 *       attempt's window. This conflates background chunk loads from any
 *       other source (player movement, world generation outside the
 *       teleport pipeline, etc.), but those are negligible during a
 *       stress run where the only activity is the dispatched commands.</li>
 * </ul>
 *
 * <p>Listener priority is {@link EventPriority#MONITOR} (read-only) and
 * the handler does no allocation beyond the atomic increment, so it adds
 * no measurable overhead even at peak chunk-load rates.
 */
public final class ChunkLoadCounter implements Listener {

    private final Plugin plugin;
    /** Monotonically-increasing total since plugin enable. */
    private final AtomicLong totalLoads = new AtomicLong();
    /** Snapshot of {@link #totalLoads} at the start of the current phase. */
    private volatile long phaseBaseline = 0L;
    private volatile boolean registered = false;

    public ChunkLoadCounter(Plugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        if (registered) return;
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
    }

    /** Monotonically-increasing global total. Use as a baseline for
     *  per-attempt deltas. */
    public long total() {
        return totalLoads.get();
    }

    /** Reset the phase baseline. Subsequent {@link #phaseTotal()} reads
     *  return the count of chunk loads since this call. Idempotent. */
    public void resetPhase() {
        phaseBaseline = totalLoads.get();
    }

    /** Number of chunk loads observed since the last {@link #resetPhase()}. */
    public long phaseTotal() {
        return Math.max(0L, totalLoads.get() - phaseBaseline);
    }
}
