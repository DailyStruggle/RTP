package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.Collection;
import java.util.Deque;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-attempt chunk-load attribution (ADR-122 Decision 1 & 2).
 */
public final class ChunkLoadCounter {

    private final StressHost plugin;
    private final SyncLoadAttributor syncAttributor;

    public SyncLoadAttributor syncAttributor() { return syncAttributor; }

    private final AtomicLong totalLoads = new AtomicLong();
    private final AtomicLong phaseBackgroundLoads = new AtomicLong();
    private final AtomicLong phaseAttributedLoads = new AtomicLong();
    private final AtomicLong phaseSelectionLoads = new AtomicLong();
    private final AtomicLong phaseRegionReads = new AtomicLong();
    private final AtomicLong phaseBinCandidates = new AtomicLong();
    private final AtomicLong phaseBinOccupancyMax = new AtomicLong();
    private final AtomicLong phaseOnTickLoads = new AtomicLong();
    private final AtomicLong phaseOffTickLoads = new AtomicLong();
    private final AtomicLong phaseLandingLoads = new AtomicLong();
    private final LongAdder phaseListenerNanos = new LongAdder();

    private volatile long phaseBaselineTotal = 0L;
    private volatile long phaseBaselineBackground = 0L;
    private volatile long phaseBaselineAttributed = 0L;
    private volatile long phaseBaselineSelection = 0L;
    private volatile long phaseBaselineRegionReads = 0L;
    private volatile long phaseBaselineBinCandidates = 0L;
    private volatile long phaseBaselineOnTick = 0L;
    private volatile long phaseBaselineOffTick = 0L;
    private volatile long phaseBaselineLanding = 0L;

    private static final class Landing {
        final String world;
        final int destX;
        final int destZ;
        final int radius;
        final long seq;

        Landing(String world, int destX, int destZ, int radius, long seq) {
            this.world = world;
            this.destX = destX;
            this.destZ = destZ;
            this.radius = radius;
            this.seq = seq;
        }
    }

    private final ConcurrentHashMap<String, Landing> landings = new ConcurrentHashMap<>();
    private final AtomicLong landingSeq = new AtomicLong();

    private volatile int configuredViewDistance = -1;

    private final Deque<Attempt> inFlight = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<java.util.UUID, Tally> attemptCounts = new ConcurrentHashMap<>();

    private static final class Tally {
        final AtomicLong count = new AtomicLong(0L);
        final Queue<Long> chunks = new ConcurrentLinkedQueue<>();
        volatile long onTick = 0L;

        static final AtomicLongFieldUpdater<Tally> ON_TICK =
                AtomicLongFieldUpdater.newUpdater(Tally.class, "onTick");

        final Object burstLock = new Object();
        long burstStartNs = 0L;
        long burstLastNs = 0L;
    }

    private static final long BURST_GAP_NS = 10_000_000L; // 10 ms

    private static long packKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    private static int keyX(long k) { return (int) (k >> 32); }

    private static int keyZ(long k) { return (int) k; }

    private static final int REGION_SHIFT = 5;

    private static long regionKey(int chunkX, int chunkZ) {
        return packKey(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
    }

    private volatile boolean pluginTicketsSupported = false;
    private volatile boolean registered = false;
    private Runnable unsubscribe;

    public ChunkLoadCounter(StressHost plugin) {
        this.plugin = plugin;
        this.syncAttributor = new SyncLoadAttributor(plugin);
    }

    public void register() {
        if (registered) return;
        pluginTicketsSupported = plugin != null && plugin.supportsChunkTickets();
        if (plugin != null && plugin.getLogger() != null) {
            if (pluginTicketsSupported) {
                plugin.getLogger().info(
                        "[StressTestRTP] ChunkLoadCounter: Chunk#getPluginChunkTickets() detected — "
                                + "per-attempt chunk attribution will use plugin tickets when available.");
            } else {
                plugin.getLogger().info(
                        "[StressTestRTP] ChunkLoadCounter: Chunk#getPluginChunkTickets() not available "
                                + "(Spigot/Fabric path) — falling back to main-thread temporal attribution.");
            }
        }
        if (plugin != null) {
            unsubscribe = plugin.onChunkLoad(this::onChunkLoad);
        }
        registered = true;
    }

    public void unregister() {
        if (!registered) return;
        if (unsubscribe != null) {
            unsubscribe.run();
            unsubscribe = null;
        }
        registered = false;
    }

    public void onChunkLoad(StressHost.ChunkEvent event) {
        long start = System.nanoTime();
        try {
            final int cx = event.cx();
            final int cz = event.cz();
            final boolean onTick = TickThreadDetector.ownsChunk(event.worldName(), cx, cz);
            syncAttributor.onLoad(cx, cz, onTick);
            route(event.worldName(), cx, cz, onTick, event.ticketHolders());
        } finally {
            phaseListenerNanos.add(System.nanoTime() - start);
        }
    }

    /** Attribution chain for one load, split from the event handler so it can
     *  be exercised without a server. {@code ticketHolders} may be null when
     *  plugin tickets are unsupported. */
    void route(String worldName, int cx, int cz, boolean onTick, Collection<String> ticketHolders) {
        totalLoads.incrementAndGet();
        if (onTick) {
            phaseOnTickLoads.incrementAndGet();
        } else {
            phaseOffTickLoads.incrementAndGet();
        }

        if (pluginTicketsSupported && ticketHolders != null && !ticketHolders.isEmpty()) {
            Attempt a = attributeByPluginTicket(ticketHolders);
            if (a != null) {
                bump(a, cx, cz, onTick);
                return;
            }
        }

        if (!landings.isEmpty() && matchLanding(worldName, cx, cz) != null) {
            phaseLandingLoads.incrementAndGet();
            return;
        }

        if (onTick) {
            Attempt a = inFlight.peekLast();
            if (a != null) {
                bump(a, cx, cz, onTick);
                return;
            }
        }

        phaseBackgroundLoads.incrementAndGet();
    }

    private Attempt attributeByPluginTicket(Collection<String> tickets) {
        if (tickets == null || tickets.isEmpty()) return null;
        for (Attempt a : inFlight) {
            String label = a.targetLabel == null ? "" : a.targetLabel.toLowerCase(Locale.ROOT);
            if (label.isEmpty()) continue;
            for (String name : tickets) {
                if (name != null && name.toLowerCase(Locale.ROOT).equals(label)) {
                    return a;
                }
            }
        }
        return null;
    }

    private Landing matchLanding(String worldName, int cx, int cz) {
        Landing best = null;
        for (Landing l : landings.values()) {
            if (l.world != null && worldName != null && !l.world.equals(worldName)) continue;
            int cheb = Math.max(Math.abs(cx - l.destX), Math.abs(cz - l.destZ));
            if (cheb > l.radius) continue;
            if (best == null || l.seq > best.seq) best = l;
        }
        return best;
    }

    private void bump(Attempt a, int chunkX, int chunkZ, boolean onTick) {
        Tally t = attemptCounts.get(a.attemptId);
        if (t == null) {
            phaseBackgroundLoads.incrementAndGet();
            return;
        }
        t.count.incrementAndGet();
        t.chunks.add(packKey(chunkX, chunkZ));
        phaseAttributedLoads.incrementAndGet();
        if (onTick) {
            Tally.ON_TICK.incrementAndGet(t);
            noteOnTickBurst(a, t, System.nanoTime());
        }
    }

    private void noteOnTickBurst(Attempt a, Tally t, long nowNs) {
        synchronized (t.burstLock) {
            if (t.burstStartNs != 0L && nowNs - t.burstLastNs <= BURST_GAP_NS) {
                t.burstLastNs = nowNs;
                return;
            }
            if (t.burstStartNs != 0L) {
                a.recordTickInterval(t.burstStartNs, t.burstLastNs);
            }
            t.burstStartNs = nowNs;
            t.burstLastNs = nowNs;
        }
    }

    private int viewDistanceChunks() {
        int vd = configuredViewDistance;
        if (vd <= 0) {
            try {
                vd = plugin != null ? plugin.viewDistance() : 10;
            } catch (Throwable ignore) {
                vd = 10;
            }
        }
        return Math.max(1, vd);
    }

    private long computeSelection(Attempt a, Tally t, long raw) {
        if (!a.success) return raw;
        if (a.toX == 0.0 && a.toZ == 0.0) return raw;
        int destX = (int) Math.floor(a.toX / 16.0);
        int destZ = (int) Math.floor(a.toZ / 16.0);
        int r = viewDistanceChunks() + 1;
        long arrival = 0L;
        for (Long packed : t.chunks) {
            int cheb = Math.max(Math.abs(keyX(packed) - destX), Math.abs(keyZ(packed) - destZ));
            if (cheb == 0) continue;
            if (cheb <= r) arrival++;
        }
        long sel = raw - arrival;
        return sel < 0 ? 0 : sel;
    }

    private void computeRegionAccounting(Attempt a, Tally t) {
        int destX = Integer.MIN_VALUE;
        int destZ = Integer.MIN_VALUE;
        int r = -1;
        if (a.success && !(a.toX == 0.0 && a.toZ == 0.0)) {
            destX = (int) Math.floor(a.toX / 16.0);
            destZ = (int) Math.floor(a.toZ / 16.0);
            r = viewDistanceChunks() + 1;
        }
        java.util.HashMap<Long, int[]> bins = new java.util.HashMap<>();
        long candidates = 0L;
        for (Long packed : t.chunks) {
            int cx = keyX(packed);
            int cz = keyZ(packed);
            if (r >= 0) {
                int cheb = Math.max(Math.abs(cx - destX), Math.abs(cz - destZ));
                if (cheb != 0 && cheb <= r) continue;
            }
            candidates++;
            bins.computeIfAbsent(regionKey(cx, cz), k -> new int[1])[0]++;
        }
        long reads = bins.size();
        a.regionFileReads = reads;
        a.binCandidates = candidates;
        long maxOcc = 0L;
        for (int[] occ : bins.values()) {
            if (occ[0] > maxOcc) maxOcc = occ[0];
        }
        a.binOccupancyMax = maxOcc;
        phaseRegionReads.addAndGet(reads);
        phaseBinCandidates.addAndGet(candidates);
        phaseBinOccupancyMax.accumulateAndGet(maxOcc, Math::max);
    }

    public void setViewDistance(int viewDistance) {
        this.configuredViewDistance = viewDistance;
    }

    public void beginAttempt(Attempt a) {
        if (a == null) return;
        if (a.player != null) landings.remove(a.player);
        if (attemptCounts.putIfAbsent(a.attemptId, new Tally()) == null) {
            inFlight.add(a);
        }
    }

    public void endAttempt(Attempt a) {
        if (a == null) return;
        Tally t = attemptCounts.remove(a.attemptId);
        if (t != null) {
            long raw = t.count.get();
            a.attributedChunkLoads = raw;
            long selection = computeSelection(a, t, raw);
            a.selectionChunkLoads = selection;
            phaseSelectionLoads.addAndGet(selection);
            computeRegionAccounting(a, t);
            long onTick = t.onTick;
            a.onTickChunkLoads = onTick;
            a.offTickChunkLoads = Math.max(0L, raw - onTick);
            synchronized (t.burstLock) {
                if (t.burstStartNs != 0L) {
                    a.recordTickInterval(t.burstStartNs, t.burstLastNs);
                    t.burstStartNs = 0L;
                    t.burstLastNs = 0L;
                }
            }
        }
        inFlight.remove(a);
        openLanding(a);
    }

    private void openLanding(Attempt a) {
        if (a.player == null) return;
        if (!a.success || (a.toX == 0.0 && a.toZ == 0.0)) {
            landings.remove(a.player);
            return;
        }
        landings.put(a.player, new Landing(a.world,
                (int) Math.floor(a.toX / 16.0), (int) Math.floor(a.toZ / 16.0),
                viewDistanceChunks() + 1, landingSeq.incrementAndGet()));
    }

    public long total() {
        return totalLoads.get();
    }

    public void resetPhase() {
        syncAttributor.resetPhase();
        phaseListenerNanos.reset();
        phaseBaselineTotal = totalLoads.get();
        phaseBaselineBackground = phaseBackgroundLoads.get();
        phaseBaselineAttributed = phaseAttributedLoads.get();
        phaseBaselineSelection = phaseSelectionLoads.get();
        phaseBaselineOnTick = phaseOnTickLoads.get();
        phaseBaselineOffTick = phaseOffTickLoads.get();
        phaseBaselineLanding = phaseLandingLoads.get();
        landings.clear();
        phaseBaselineRegionReads = phaseRegionReads.get();
        phaseBaselineBinCandidates = phaseBinCandidates.get();
        phaseBinOccupancyMax.set(0L);
    }

    public long phaseTotal() {
        return Math.max(0L, totalLoads.get() - phaseBaselineTotal);
    }

    public long phaseBackground() {
        return Math.max(0L, phaseBackgroundLoads.get() - phaseBaselineBackground);
    }

    public long phaseLanding() {
        return Math.max(0L, phaseLandingLoads.get() - phaseBaselineLanding);
    }

    public long phaseAttributed() {
        return Math.max(0L, phaseAttributedLoads.get() - phaseBaselineAttributed);
    }

    public long phaseSelection() {
        return Math.max(0L, phaseSelectionLoads.get() - phaseBaselineSelection);
    }

    public long phaseOnTick() {
        return Math.max(0L, phaseOnTickLoads.get() - phaseBaselineOnTick);
    }

    public long phaseOffTick() {
        return Math.max(0L, phaseOffTickLoads.get() - phaseBaselineOffTick);
    }

    public long phaseRegionReads() {
        return Math.max(0L, phaseRegionReads.get() - phaseBaselineRegionReads);
    }

    public long phaseBinCandidates() {
        return Math.max(0L, phaseBinCandidates.get() - phaseBaselineBinCandidates);
    }

    public long phaseBinOccupancyMax() {
        return phaseBinOccupancyMax.get();
    }

    public long phaseListenerNanos() {
        return phaseListenerNanos.sum();
    }

    public long phaseListenerMs() {
        return phaseListenerNanos.sum() / 1_000_000L;
    }

    public void reportPhaseListenerTime(String phaseLabel) {
        long nanos = phaseListenerNanos.sum();
        long ms = nanos / 1_000_000L;
        long loads = phaseTotal();
        if (plugin != null && plugin.getLogger() != null) {
            double usPerLoad = loads > 0 ? (double) nanos / (loads * 1000.0) : 0.0;
            plugin.getLogger().info(() -> String.format(java.util.Locale.ROOT,
                    "[StressTestRTP] ChunkLoadCounter listener time for phase '%s': %d ms across %d chunk loads (%.2f \u00b5s/load)",
                    phaseLabel, ms, loads, usPerLoad));
        }
    }
}
