package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.metrics.LiveLoadGateRow;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Per-region purpose gate on native chunk loads (ADR-110).
 *
 * <p>A native load costs the server memory (full chunk, light, entities), scheduling (generation
 * workers, Folia region ownership) and region-file cache churn that RTP's own timings never see.
 * Speculative fill therefore verifies only from resident chunks and region-file reads; a native
 * load is allowed only when its result is pinned until teleport, bounded by the kept queue's
 * capacity ({@code activeChunkCap}). Pin slots are counted here so concurrent attempts cannot
 * overcommit the kept queue.</p>
 *
 * <p>Counters feed {@code RTPMetricsExtension.liveLoadGates} and a temporary
 * {@code [RTP][load-gate]} INFO summary (at most once per 60 s per region while traffic flows).
 * Thread-safe.</p>
 */
public final class LiveLoadGate {

    static final long SUMMARY_NANOS = TimeUnit.SECONDS.toNanos(60);

    private static final ConcurrentHashMap<String, LiveLoadGate> REGISTRY = new ConcurrentHashMap<>();

    /** Shared gate for {@code region}. */
    public static LiveLoadGate of(String region) {
        String r = (region == null) ? "" : region;
        return REGISTRY.computeIfAbsent(r, k -> new LiveLoadGate(k, System::nanoTime));
    }

    /** Rows for every gate, keyed by region, sorted. */
    public static Map<String, LiveLoadGateRow> snapshotAll() {
        if (REGISTRY.isEmpty()) return Collections.emptyMap();
        Map<String, LiveLoadGateRow> out = new TreeMap<>();
        REGISTRY.forEach((k, g) -> out.put(k, g.snapshot()));
        return Collections.unmodifiableMap(out);
    }

    /**
     * Logs the {@code [RTP][load-gate]} summary for every gate now, ignoring the rate limit, and
     * restarts each gate's 60 s window. For phase boundaries (stress harness): the rate-limited
     * summary only fires from gate traffic, so short or quiet phases otherwise log nothing.
     *
     * @param tag label appended to each line (e.g. a phase name); {@code null} for none
     * @return number of gates logged
     */
    public static int flushSummaries(String tag) {
        int n = 0;
        for (LiveLoadGate g : REGISTRY.values()) {
            g.lastSummary.set(g.clock.getAsLong());
            g.logSummary(tag);
            n++;
        }
        return n;
    }

    /** Drops all gates. For tests only. */
    static void resetAll() {
        REGISTRY.clear();
    }

    private final String region;
    private final LongSupplier clock;
    private final AtomicInteger pinsInFlight = new AtomicInteger();
    private final AtomicLong lastSummary;
    final LongAdder readResolved = new LongAdder();
    final LongAdder deferredCenter = new LongAdder();
    final LongAdder deferredNeighbour = new LongAdder();
    final LongAdder pinnedLoads = new LongAdder();
    final LongAdder pinnedKept = new LongAdder();
    final LongAdder pinnedOverflow = new LongAdder();
    final LongAdder ringsSkipped = new LongAdder();
    final LongAdder usedResident = new LongAdder();
    final LongAdder usedEvicted = new LongAdder();
    final LongAdder usedPinnedResident = new LongAdder();
    final LongAdder usedPinnedEvicted = new LongAdder();

    LiveLoadGate(String region, LongSupplier clock) {
        this.region = region;
        this.clock = clock;
        this.lastSummary = new AtomicLong(clock.getAsLong());
    }

    /**
     * Claims a pin slot when {@code keptSize + pinsInFlight < keptCap}. The caller must
     * {@link #releasePin()} exactly once when its attempt ends (handed off or rejected).
     */
    public boolean tryAcquirePin(Supplier<Integer> keptSize, int keptCap) {
        if (keptCap <= 0) return false;
        while (true) {
            int cur = pinsInFlight.get();
            int kept;
            try {
                Integer k = keptSize.get();
                kept = (k == null) ? 0 : k;
            } catch (Throwable t) {
                return false;
            }
            if (kept + cur >= keptCap) return false;
            if (pinsInFlight.compareAndSet(cur, cur + 1)) return true;
        }
    }

    /** Releases a slot claimed by {@link #tryAcquirePin}; never drops below zero. */
    public void releasePin() {
        pinsInFlight.updateAndGet(v -> (v > 0) ? v - 1 : 0);
    }

    public int pinsInFlight() {
        return pinsInFlight.get();
    }

    public void onReadResolved() {
        readResolved.increment();
        maybeSummarize();
    }

    public void onDeferredCenter() {
        deferredCenter.increment();
        maybeSummarize();
    }

    public void onDeferredNeighbour() {
        deferredNeighbour.increment();
        maybeSummarize();
    }

    public void onPinnedLoad() {
        pinnedLoads.increment();
        maybeSummarize();
    }

    public void onPinnedHandoff(boolean kept) {
        (kept ? pinnedKept : pinnedOverflow).increment();
    }

    public void onRingSkipped() {
        ringsSkipped.increment();
    }

    /** Records whether a consumed location's chunk was still resident when used. */
    public void onUse(boolean pinned, boolean resident) {
        if (pinned) {
            (resident ? usedPinnedResident : usedPinnedEvicted).increment();
        } else {
            (resident ? usedResident : usedEvicted).increment();
        }
        maybeSummarize();
    }

    public LiveLoadGateRow snapshot() {
        return new LiveLoadGateRow(region,
                readResolved.sum(), deferredCenter.sum(), deferredNeighbour.sum(),
                pinnedLoads.sum(), pinnedKept.sum(), pinnedOverflow.sum(), ringsSkipped.sum(),
                pinsInFlight.get(),
                usedResident.sum(), usedEvicted.sum(),
                usedPinnedResident.sum(), usedPinnedEvicted.sum());
    }

    private void maybeSummarize() {
        long now = clock.getAsLong();
        long last = lastSummary.get();
        if (now - last < SUMMARY_NANOS || !lastSummary.compareAndSet(last, now)) return;
        logSummary(null);
    }

    String summaryLine(String tag) {
        LiveLoadGateRow r = snapshot();
        return "[RTP][load-gate] region=" + region
                + (tag == null || tag.isEmpty() ? "" : " tag=" + tag)
                + " read=" + r.readResolved()
                + " deferCenter=" + r.deferredCenter()
                + " deferNeighbour=" + r.deferredNeighbour()
                + " pinned=" + r.pinnedLoads()
                + " kept=" + r.pinnedKept()
                + " overflow=" + r.pinnedOverflow()
                + " ringsSkipped=" + r.ringsSkipped()
                + " pinsInFlight=" + r.pinsInFlight()
                + " used(resident/evicted)=" + r.usedResident() + "/" + r.usedEvicted()
                + " usedPinned(resident/evicted)=" + r.usedPinnedResident() + "/" + r.usedPinnedEvicted();
    }

    private void logSummary(String tag) {
        RTP.log(Level.INFO, summaryLine(tag));
    }
}
