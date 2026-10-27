package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.governor.ComputeBudget;
import io.github.dailystruggle.rtp.common.governor.ComputeGovernor;
import io.github.dailystruggle.rtp.common.predict.BernoulliEstimator;
import io.github.dailystruggle.rtp.common.predict.ComparativeCostGovernor;
import io.github.dailystruggle.rtp.common.predict.CostContext;
import io.github.dailystruggle.rtp.common.predict.EwmaEstimator;
import io.github.dailystruggle.rtp.common.tools.GenerationBudget;
import io.github.dailystruggle.rtp.common.tools.ServerHealthGate;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;

/**
 * Measured-cost and thread-weighted deduplication governor for on-demand chunk reads (ADR-114).
 * Manages empirical EWMA cost estimation, the dynamic thread-weighted deduplication threshold,
 * generation semaphores, and tick damping. One instance per region.
 */
public final class ImmediateLoadGovernor {

    public static final double DEFAULT_FILE_READ_MS = 2.5;
    public static final double DEFAULT_NATIVE_LOAD_MS = 15.0;
    public static final double DEFAULT_NATIVE_SYNC_MS = 1.0;
    public static final double DEFAULT_REJECT_RATE = 0.05;

    public static final int MAX_REGIONAL_PERMITS = 1;
    public static final int MAX_GLOBAL_PERMITS = 2;

    private static final ConcurrentHashMap<String, ImmediateLoadGovernor> REGISTRY = new ConcurrentHashMap<>();
    private static final AtomicInteger GLOBAL_IN_FLIGHT = new AtomicInteger(0);
    private static final ConcurrentLinkedQueue<QueuedRequest> WAITERS = new ConcurrentLinkedQueue<>();

    public interface GenerationPermit extends AutoCloseable {
        @Override
        void close();
    }

    private static final class QueuedRequest {
        final ImmediateLoadGovernor governor;
        final CompletableFuture<GenerationPermit> future;
        final long deadlineMs;
        final DoubleSupplier msptSupplier;

        QueuedRequest(ImmediateLoadGovernor governor, CompletableFuture<GenerationPermit> future, long deadlineMs, DoubleSupplier msptSupplier) {
            this.governor = governor;
            this.future = future;
            this.deadlineMs = deadlineMs;
            this.msptSupplier = msptSupplier;
        }
    }

    /** Shared governor for {@code region}. */
    public static ImmediateLoadGovernor of(String region) {
        String r = (region == null) ? "" : region;
        return REGISTRY.computeIfAbsent(r, ImmediateLoadGovernor::new);
    }

    /** Rows for every governor, keyed by region, sorted. */
    public static Map<String, ImmediateGovernorRow> snapshotAll() {
        if (REGISTRY.isEmpty()) return Collections.emptyMap();
        Map<String, ImmediateGovernorRow> out = new TreeMap<>();
        REGISTRY.forEach((k, g) -> out.put(k, g.snapshot()));
        return Collections.unmodifiableMap(out);
    }

    /** Drops the governor for {@code region}. For tests only. */
    public static void reset(String region) {
        if (region == null) return;
        REGISTRY.remove(region);
    }

    /** Drops all governors and clears semaphores. For tests only. */
    public static void resetAll() {
        REGISTRY.clear();
        GLOBAL_IN_FLIGHT.set(0);
        WAITERS.clear();
    }

    /** Current global in-flight generations. */
    public static int globalInFlight() {
        return GLOBAL_IN_FLIGHT.get();
    }

    /**
     * Effective server-wide generation permit cap {@code g_eff}, shared with {@link GenerationBudget}
     * through {@link ComputeBudget#generationCap()} (ADR-087 Section 4.3 item 7 & 7.3, ADR-114 Decision 4).
     */
    public static int effectiveGlobalPermitCap() {
        ComputeBudget budget = ComputeGovernor.currentBudget();
        if (budget != null) {
            return Math.max(1, budget.generationCap());
        }
        return MAX_GLOBAL_PERMITS;
    }

    /**
     * Effective CPU target knee {@code min(35 ms, T_cpu)} from {@link ComputeBudget#cpuTarget()}
     * ({@code 35.0} when {@code autoTune} is disabled; ADR-087 Section 4.3 item 7 & 7.3, ADR-114).
     */
    public static double effectiveCpuTargetMs() {
        ComputeBudget budget = ComputeGovernor.currentBudget();
        return budget != null ? budget.cpuTarget() : ComputeBudget.DEFAULT_PREFILTER_KNEE_MS;
    }

    /** Computes dynamic shadow price multiplier of main-thread tick time W_tick(MSPT). */
    public static double computeTickWeight(double mspt) {
        return computeTickWeight(mspt, effectiveCpuTargetMs());
    }

    /** Computes dynamic shadow price multiplier of main-thread tick time W_tick(MSPT) with explicit knee. */
    public static double computeTickWeight(double mspt, double cpuTargetMs) {
        if (Double.isNaN(mspt) || mspt <= 0.0) mspt = 20.0;
        double knee = (Double.isNaN(cpuTargetMs) || cpuTargetMs <= 0.0)
                ? ComputeBudget.DEFAULT_PREFILTER_KNEE_MS
                : Math.min(ComputeBudget.DEFAULT_PREFILTER_KNEE_MS, cpuTargetMs);
        double excess = Math.max(0.0, mspt - knee) / 10.0;
        return 5.0 + 100.0 * (excess * excess);
    }

    /** Branch decision for chunk pre-filtering vs bypass. */
    public enum Decision {
        PREFILTER,
        BYPASS
    }

    private static final ComparativeCostGovernor<Decision> COMPARATIVE_GOVERNOR = ComparativeCostGovernor.of(ctx -> {
        if (ctx.get("isPredicateConstrained", 0.0) > 0.5) return Decision.PREFILTER;
        double mspt = ctx.get("mspt", 20.0);
        double knee = ctx.get("cpuTarget", ComputeBudget.DEFAULT_PREFILTER_KNEE_MS);
        if (mspt > knee) return Decision.PREFILTER;

        double pReject = ctx.get("pReject", 0.0);
        double cFile = ctx.get("cFile", DEFAULT_FILE_READ_MS);
        double cNative = ctx.get("cNative", DEFAULT_NATIVE_LOAD_MS);
        double cSync = ctx.get("cSync", DEFAULT_NATIVE_SYNC_MS);
        double wTick = computeTickWeight(mspt, knee);

        double denom = cNative + wTick * cSync;
        double threshold = (denom <= 0.0) ? 0.0 : (cFile / denom);

        return pReject > threshold ? Decision.PREFILTER : Decision.BYPASS;
    });

    private final String region;
    private final AtomicInteger regionalInFlight = new AtomicInteger(0);

    final LongAdder bypassedAnvilReads = new LongAdder();
    final LongAdder prefilteredAnvilReads = new LongAdder();
    final LongAdder throttledGenerations = new LongAdder();

    private final EwmaEstimator fileReadCost = EwmaEstimator.ofWindow(64, DEFAULT_FILE_READ_MS);
    private final EwmaEstimator nativeLoadCost = EwmaEstimator.ofWindow(64, DEFAULT_NATIVE_LOAD_MS);
    private final EwmaEstimator nativeSyncCost = EwmaEstimator.ofWindow(64, DEFAULT_NATIVE_SYNC_MS);
    private final BernoulliEstimator rejectRate = BernoulliEstimator.ofWindow(64, DEFAULT_REJECT_RATE);
    private volatile String mode = "BYPASS";

    ImmediateLoadGovernor(String region) {
        this.region = region;
    }

    public String region() {
        return region;
    }

    public int regionalInFlight() {
        return regionalInFlight.get();
    }

    public double ewmaFileReadMs() {
        return fileReadCost.mean();
    }

    public double ewmaNativeLoadMs() {
        return nativeLoadCost.mean();
    }

    public double ewmaNativeSyncMs() {
        return nativeSyncCost.mean();
    }

    public double pReject() {
        return rejectRate.rate();
    }

    /**
     * Computes the current empirical deduplication threshold:
     * C_file,async / (C_native,async + W_tick(MSPT) * C_native,sync).
     */
    public double computeDedupThreshold(double currentMspt) {
        double wTick = computeTickWeight(currentMspt);
        double cFile = fileReadCost.mean();
        double cNativeAsync = nativeLoadCost.mean();
        double cNativeSync = nativeSyncCost.mean();
        double denom = cNativeAsync + wTick * cNativeSync;
        if (denom <= 0.0) return 0.0;
        return cFile / denom;
    }

    /**
     * Determines whether the candidate should run off-thread Anvil pre-filtering (Tier 2)
     * or bypass directly to Tier 3 native loading (deduplication bypass).
     *
     * @param isPredicateConstrained {@code true} for /rtp biome:... or non-spatial predicates
     * @param currentMspt            current server tick duration in ms
     * @return {@code true} to engage off-thread Anvil pre-filtering, {@code false} to bypass directly to native load
     */
    public boolean shouldPrefilter(boolean isPredicateConstrained, double currentMspt) {
        double mspt = Double.isNaN(currentMspt) || currentMspt <= 0.0 ? 20.0 : currentMspt;
        double knee = effectiveCpuTargetMs();
        CostContext ctx = CostContext.builder()
                .with("isPredicateConstrained", isPredicateConstrained ? 1.0 : 0.0)
                .with("mspt", mspt)
                .with("cpuTarget", knee)
                .with("pReject", pReject())
                .with("cFile", ewmaFileReadMs())
                .with("cNative", ewmaNativeLoadMs())
                .with("cSync", ewmaNativeSyncMs())
                .build();

        Decision decision = COMPARATIVE_GOVERNOR.evaluate(ctx);
        if (decision == Decision.PREFILTER) {
            prefilteredAnvilReads.increment();
            this.mode = "PREFILTER";
            return true;
        } else {
            bypassedAnvilReads.increment();
            this.mode = "BYPASS";
            return false;
        }
    }

    public void recordFileRead(double ms) {
        fileReadCost.recordSample(ms);
    }

    public void recordNativeLoad(double asyncMs, double syncMs) {
        if (Double.isNaN(asyncMs) || asyncMs < 0.0) asyncMs = DEFAULT_NATIVE_LOAD_MS;
        if (Double.isNaN(syncMs) || syncMs < 0.0) syncMs = DEFAULT_NATIVE_SYNC_MS;
        nativeLoadCost.recordSample(asyncMs);
        nativeSyncCost.recordSample(syncMs);
        GenerationBudget.server().recordLoadLatencyMs(asyncMs + syncMs);
    }

    public void recordOutcome(boolean rejected) {
        rejectRate.recordOutcome(rejected);
    }

    /**
     * Non-blocking attempt to acquire a native generation permit subject to regional (1)
     * and global ({@link ComputeBudget#generationCap()}) limits (ADR-087 Section 4.3 item 7 & 7.3, ADR-114).
     *
     * @param currentMspt current MSPT (retained for signature compatibility)
     * @return permit if acquired, or {@code null} if slots are occupied
     */
    @SuppressWarnings("java:S1172") // currentMspt retained for signature compatibility
    public GenerationPermit tryAcquirePermit(double currentMspt) {
        int maxGlobal = effectiveGlobalPermitCap();

        if (regionalInFlight.get() >= MAX_REGIONAL_PERMITS) {
            throttledGenerations.increment();
            return null;
        }
        if (GLOBAL_IN_FLIGHT.get() >= maxGlobal) {
            throttledGenerations.increment();
            return null;
        }

        if (regionalInFlight.incrementAndGet() > MAX_REGIONAL_PERMITS) {
            regionalInFlight.decrementAndGet();
            throttledGenerations.increment();
            return null;
        }

        if (GLOBAL_IN_FLIGHT.incrementAndGet() > maxGlobal) {
            GLOBAL_IN_FLIGHT.decrementAndGet();
            regionalInFlight.decrementAndGet();
            throttledGenerations.increment();
            return null;
        }

        return createPermit();
    }

    /**
     * Asynchronously acquires a native generation permit, queueing up to {@code timeoutMs}.
     * If timeout expires before permit is acquired, completes exceptionally with {@link TimeoutException}.
     *
     * @param msptSupplier supplier of current MSPT
     * @param timeoutMs    maximum wait in milliseconds
     * @return CompletableFuture completing with permit or exception
     */
    public CompletableFuture<GenerationPermit> acquirePermit(DoubleSupplier msptSupplier, long timeoutMs) {
        double mspt = (msptSupplier != null) ? msptSupplier.getAsDouble() : 20.0;
        GenerationPermit immediate = tryAcquirePermit(mspt);
        if (immediate != null) {
            return CompletableFuture.completedFuture(immediate);
        }
        if (timeoutMs <= 0) {
            CompletableFuture<GenerationPermit> failed = new CompletableFuture<>();
            failed.completeExceptionally(new TimeoutException("Immediate permit unavailable"));
            return failed;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        CompletableFuture<GenerationPermit> future = new CompletableFuture<>();
        QueuedRequest req = new QueuedRequest(this, future, deadline, (msptSupplier != null) ? msptSupplier : () -> 20.0);
        WAITERS.add(req);
        future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).whenComplete((p, ex) -> {
            if (ex != null) {
                WAITERS.remove(req);
                throttledGenerations.increment();
            }
        });
        drainWaiters();
        return future;
    }

    @SuppressWarnings("java:S1172") // currentMspt retained for signature compatibility
    public int permitsAvailable(double currentMspt) {
        int maxGlobal = effectiveGlobalPermitCap();
        if (regionalInFlight.get() >= MAX_REGIONAL_PERMITS || GLOBAL_IN_FLIGHT.get() >= maxGlobal) {
            return 0;
        }
        return 1;
    }

    public ImmediateGovernorRow snapshot() {
        double mspt = ServerHealthGate.lastMspt();
        double knee = effectiveCpuTargetMs();
        double threshold = computeDedupThreshold(mspt);
        String curMode = this.mode;
        if (bypassedAnvilReads.sum() == 0 && prefilteredAnvilReads.sum() == 0) {
            curMode = (pReject() > threshold || (!Double.isNaN(mspt) && mspt > knee)) ? "PREFILTER" : "BYPASS";
        }
        return new ImmediateGovernorRow(
                region,
                curMode,
                pReject(),
                ewmaFileReadMs(),
                ewmaNativeLoadMs(),
                ewmaNativeSyncMs(),
                threshold,
                bypassedAnvilReads.sum(),
                prefilteredAnvilReads.sum(),
                regionalInFlight.get(),
                throttledGenerations.sum(),
                permitsAvailable(mspt)
        );
    }

    private GenerationPermit createPermit() {
        return new GenerationPermit() {
            private final AtomicBoolean closed = new AtomicBoolean(false);

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    GLOBAL_IN_FLIGHT.decrementAndGet();
                    regionalInFlight.decrementAndGet();
                    drainWaiters();
                }
            }
        };
    }

    private static synchronized void drainWaiters() {
        if (WAITERS.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (QueuedRequest req : WAITERS) {
            if (req.future.isDone()) {
                WAITERS.remove(req);
            } else if (now >= req.deadlineMs) {
                WAITERS.remove(req);
                req.future.completeExceptionally(new TimeoutException("Timed out waiting for generation permit"));
            } else {
                double mspt = req.msptSupplier.getAsDouble();
                GenerationPermit permit = req.governor.tryAcquirePermit(mspt);
                if (permit != null) {
                    WAITERS.remove(req);
                    if (!req.future.complete(permit)) {
                        permit.close();
                    }
                }
            }
        }
    }
}
