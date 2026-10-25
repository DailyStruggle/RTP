package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Peak residency accounting: resident chunks and plugin chunk tickets held
 * (ADR-122 Decision 1 & 2).
 */
public final class ResidencySampler {

    private final StressHost plugin;
    private final long ticketSamplePeriodMs;

    private volatile long residentBaseline = -1L;
    private final AtomicLong residentDelta = new AtomicLong();

    private final AtomicLong peakResidentChunks = new AtomicLong(-1L);
    private final AtomicLong peakPluginTickets = new AtomicLong(-1L);
    private final AtomicLong peakTargetTickets = new AtomicLong(-1L);

    private volatile String targetLabel = "";
    private volatile boolean ticketsSupported = false;

    private Object ticketTaskId = null;
    private volatile boolean registered = false;
    private Runnable loadUnsub;
    private Runnable unloadUnsub;

    public ResidencySampler(StressHost plugin, long ticketSamplePeriodMs) {
        this.plugin = plugin;
        this.ticketSamplePeriodMs = Math.max(250L, ticketSamplePeriodMs);
    }

    public void setTargetLabel(String label) {
        this.targetLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
    }

    public void register() {
        if (registered) return;
        ticketsSupported = !Sched.isFolia() && plugin != null && plugin.supportsChunkTickets();
        if (plugin != null) {
            loadUnsub = plugin.onChunkLoad(this::onChunkLoad);
            unloadUnsub = plugin.onChunkUnload(this::onChunkUnload);
        }
        Sched.runGlobal(plugin, this::captureBaseline);
        if (ticketsSupported) {
            long ticks = Math.max(1L, ticketSamplePeriodMs / 50L);
            ticketTaskId = Sched.runGlobalTimer(plugin, this::sampleTickets, ticks);
        }
        registered = true;
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().info("[StressTestRTP] ResidencySampler: chunk residency tracked; "
                    + (ticketsSupported
                            ? "plugin chunk tickets sampled every " + ticketSamplePeriodMs + " ms."
                            : "plugin chunk tickets NOT available on this platform (columns stay -1)."));
        }
    }

    public void unregister() {
        if (!registered) return;
        if (loadUnsub != null) { loadUnsub.run(); loadUnsub = null; }
        if (unloadUnsub != null) { unloadUnsub.run(); unloadUnsub = null; }
        Sched.cancel(ticketTaskId);
        ticketTaskId = null;
        registered = false;
    }

    private void captureBaseline() {
        long total = 0L;
        try {
            for (StressWorld w : plugin.worlds()) {
                total += w.loadedChunkCount();
            }
        } catch (Throwable t) {
            residentBaseline = -1L;
            return;
        }
        residentBaseline = total;
        residentDelta.set(0L);
        bumpPeak(peakResidentChunks, total);
    }

    public void onChunkLoad(StressHost.ChunkEvent event) {
        long now = residentDelta.incrementAndGet();
        observeResident(now);
    }

    public void onChunkUnload(StressHost.ChunkEvent event) {
        residentDelta.decrementAndGet();
    }

    private void observeResident(long delta) {
        long base = residentBaseline;
        if (base < 0L) return;
        bumpPeak(peakResidentChunks, base + delta);
    }

    private static void bumpPeak(AtomicLong peak, long value) {
        if (value < 0L) return;
        long cur = peak.get();
        while (value > cur) {
            if (peak.compareAndSet(cur, value)) return;
            cur = peak.get();
        }
    }

    private void sampleTickets() {
        if (!ticketsSupported || plugin == null) return;
        StressHost.Census census = plugin.chunkTicketCensus();
        if (!census.measured()) {
            ticketsSupported = false;
            Sched.cancel(ticketTaskId);
            ticketTaskId = null;
            if (plugin.getLogger() != null) {
                plugin.getLogger().warning("[StressTestRTP] ResidencySampler: "
                        + "getPluginChunkTickets() unavailable; ticket columns stay -1.");
            }
            return;
        }
        long all = 0L;
        long target = 0L;
        String label = targetLabel;
        for (Map.Entry<String, Integer> e : census.counts().entrySet()) {
            int n = e.getValue() != null ? e.getValue() : 0;
            all += n;
            String name = e.getKey();
            if (!label.isEmpty() && name != null
                    && name.toLowerCase(Locale.ROOT).equals(label)) {
                target += n;
            }
        }
        bumpPeak(peakPluginTickets, all);
        if (!label.isEmpty()) bumpPeak(peakTargetTickets, target);
    }

    public void resetPhase() {
        long base = residentBaseline;
        peakResidentChunks.set(base < 0L ? -1L : Math.max(0L, base + residentDelta.get()));
        peakPluginTickets.set(ticketsSupported ? 0L : -1L);
        peakTargetTickets.set(ticketsSupported && !targetLabel.isEmpty() ? 0L : -1L);
    }

    public long peakResidentChunks() { return peakResidentChunks.get(); }

    public long peakPluginTickets() { return peakPluginTickets.get(); }

    public long peakTargetPluginTickets() { return peakTargetTickets.get(); }
}
