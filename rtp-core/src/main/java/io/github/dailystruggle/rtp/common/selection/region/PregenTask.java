package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.*;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.tools.CfDiag;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * State machine for the pregen path of {@link LocationGenerator}. One task instance
 * drives the attempt loop, rescheduling across async stages (chunk I/O, verifiers)
 * to avoid blocking worker threads.
 *
 * <p>Preserves all {@link LocationGenerator.FailTypes} attributions from the blocking
 * implementation.
 */
final class PregenTask implements Runnable {

    private final PregenState state;
    private final CompletableFuture<GenerationResult> result;
    private long i;

    // Trampoline state. `inRunAttempt` is true while the while-loop in run() is
    // executing a single attempt. When a synchronous rejection path asks to
    // advance to the next attempt, it sets `needsReschedule=true` rather than
    // recursing, and the while-loop picks that up on the next iteration. If an
    // async callback lands after run()'s while-loop has already returned, the
    // callback invokes run() itself on its own thread, starting a fresh loop.
    private volatile boolean inRunAttempt = false;
    private volatile boolean needsReschedule = false;

    // Biome-recall draw tables, gathered from the shape's per-biome run tables and held for as
    // long as MemoryShape.biomeTableVersion() is unchanged. The tables themselves are the shape's
    // own arrays (never copied), so a gather costs one map lookup per requested biome; without
    // this the whole gather - plus a derived widths array and a boxed entry per recorded run -
    // was rebuilt on every attempt of every request.
    private long biomeTablesVersion = Long.MIN_VALUE;
    private MemoryShape.BiomeUnionTable.BiomeView[] biomeDrawViews = null;
    private double[] biomeDrawWeights = null;
    private int biomeDrawCount = 0;
    private double biomeDrawPGray = 0.0d;
    // Flattened (key, width) run list for the legacy uniform-over-runs draw. Built only when
    // biomeWeighted is off, since the weighted path never reads it.
    private long[] flatDrawKeys = null;
    private long[] flatDrawWidths = null;

    // ADR-110: true while this attempt holds a pin slot (native loads allowed, result pinned).
    private final java.util.concurrent.atomic.AtomicBoolean pinHeld = new java.util.concurrent.atomic.AtomicBoolean();

    PregenTask(PregenState state, CompletableFuture<GenerationResult> result, long initialAttempt) {
        this.state = state;
        this.result = result;
        this.i = initialAttempt;
        result.whenComplete((r, ex) -> releasePin());
    }

    private boolean speculative() {
        return state.purpose == LoadPurpose.SPECULATIVE;
    }

    /** Speculative and not yet pinned: resident chunks and region-file reads only (ADR-110). */
    private boolean readOnly() {
        return speculative() && !pinHeld.get();
    }

    private LiveLoadGate gate() {
        Region r = state.region;
        return LiveLoadGate.of((r == null) ? null : r.name);
    }

    /** Claims a pin slot bounded by the kept queue's capacity; idempotent per attempt. */
    private boolean tryPin() {
        if (pinHeld.get()) return true;
        Region r = state.region;
        if (r == null || r.queueManager == null) return false;
        int cap;
        try {
            cap = (int) Math.min(Integer.MAX_VALUE, r.getSettings().activeChunkCap());
        } catch (Throwable t) {
            return false;
        }
        if (!gate().tryAcquirePin(() -> r.queueManager.keptLocations.size(), cap)) return false;
        if (!pinHeld.compareAndSet(false, true)) gate().releasePin();
        gate().onPinnedLoad();
        return true;
    }

    private void releasePin() {
        if (pinHeld.compareAndSet(true, false)) gate().releasePin();
    }

    /**
     * Advance to the next attempt. Synchronous paths set a flag for the loop in {@link #run()};
     * async callback paths submit a fresh task to prevent {@link CompletableFuture} graph growth.
     */
    private void rescheduleNextAttempt() {
        i++;
        releasePin();
        if (result.isDone()) return;
        CfDiag.pregenReschedule.increment();
        if (inRunAttempt) {
            needsReschedule = true;
        } else {
            // Prevent CompletableFuture graph leak: do not call run() directly from callbacks.
            // Fresh submission bounds BiApply/CoCompletion chain depth (see LESSONS_LEARNED.md).
            RTP.serverAccessor.getScheduler().runTaskAsynchronously(this);
        }
    }

    /**
     * Record a per-attempt outcome breadcrumb in {@link PregenState#attemptOutcomes}.
     * Always runs regardless of {@code verbose}, so a failing log always reveals the
     * code path even if the {@code failMap} bucketing is ambiguous.
     */
    private void recordOutcome(String name) {
        try {
            state.attemptOutcomes.add("attempt=" + i + " outcome=" + name);
        } catch (Throwable ignored) {
            // best-effort breadcrumb; never let diagnostics break the pipeline
        }
        // Always-on outcome metrics (independent of verbose). The breadcrumb name
        // begins with the cause token ("biome/...", "safetyExternal/...", etc.) or
        // "success ..." - parse the leading token and feed the process-global
        // accumulator so success/failure rates and the per-cause breakdown fill
        // from real traffic. Best-effort: never let metrics break the pipeline.
        try {
            recordOutcomeMetric(name);
        } catch (Throwable ignored) {
            // defensive: metrics accounting must never affect generation
        }
    }

    /**
     * Maps an outcome breadcrumb to the process-global
     * {@link io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats}.
     * The leading token (up to the first {@code '/'} or space) is either
     * {@code "success"} or the name of a {@link LocationGenerator.FailTypes}
     * constant. Unrecognised tokens are bucketed as {@code FailTypes.misc}.
     */
    private static void recordOutcomeMetric(String name) {
        if (name == null || name.isEmpty()) return;
        int cut = name.length();
        for (int idx = 0; idx < name.length(); idx++) {
            char c = name.charAt(idx);
            if (c == '/' || c == ' ') { cut = idx; break; }
        }
        String token = name.substring(0, cut);
        if (token.equals("success")) {
            io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats.GLOBAL.recordSuccess();
            return;
        }
        LocationGenerator.FailTypes cause;
        try {
            cause = LocationGenerator.FailTypes.valueOf(token);
        } catch (IllegalArgumentException ex) {
            cause = LocationGenerator.FailTypes.misc;
        }
        io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats.GLOBAL.recordFailure(cause);
    }

    /**
     * Null-safe reservation close. Anvil-backed candidates evaluate with
     * {@code reservation == null}; live-backed candidates hold a non-null
     * reservation that must be released on every exit path.
     */
    private static void closeIfPresent(@org.jetbrains.annotations.Nullable ChunkReservation reservation) {
        if (reservation != null) reservation.close();
    }

    /**
     * ADR-062 bounded biome-probability weighted draw.
     * Selects a target biome with equal probability among those in spatial memory,
     * then a run within that biome weighted by run width, then a uniform offset.
     *
     * @param perBiome non-empty list of {@code {keys, widths}} parallel-array pairs.
     * @return drawn 1D location index.
     */
    static long drawWeightedBiome(List<long[][]> perBiome) {
        return drawWeightedBiome(perBiome, null);
    }

    /**
     * ADR-062 weighted-biome overload. Selects target biome in proportion to
     * {@code biomeWeights}. Falls back to equal pick if weights are null or sum <= 0.
     *
     * @param perBiome parallel {@code {keys, widths}} array list.
     * @param biomeWeights optional per-biome weights.
     * @return drawn 1D location index.
     */
    static long drawWeightedBiome(List<long[][]> perBiome, double @org.jetbrains.annotations.Nullable [] biomeWeights) {
        int count = perBiome.size();
        long[][] keys = new long[count][];
        long[][] sums = new long[count][];
        for (int b = 0; b < count; b++) {
            long[][] pair = perBiome.get(b);
            long[] widths = pair[1];
            long[] prefix = new long[widths.length];
            long acc = 0L;
            for (int k = 0; k < widths.length; k++) {
                acc += Math.max(0L, widths[k]);
                prefix[k] = acc;
            }
            keys[b] = pair[0];
            sums[b] = prefix;
        }
        return drawWeightedBiome(keys, sums, count, biomeWeights);
    }

    /**
     * ADR-062 weighted-biome draw over the run tables as {@link MemoryShape} already stores them:
     * start keys plus prefix sums of run width. Allocation-free, and the within-biome run pick is
     * a binary search over the prefix sums rather than a running sum over per-run widths.
     *
     * @param perBiomeKeys run start keys per biome; first {@code count} entries are used.
     * @param perBiomeSums prefix sums of run width per biome, aligned 1:1 with the keys.
     * @param count number of populated biome entries.
     * @param biomeWeights optional per-biome weights; equal pick when null, wrong-length or sum <= 0.
     * @return drawn 1D location index.
     */
    static long drawWeightedBiome(
            long[][] perBiomeKeys,
            long[][] perBiomeSums,
            int count,
            double @org.jetbrains.annotations.Nullable [] biomeWeights) {
        int chosenIdx;
        if (biomeWeights == null || biomeWeights.length < count) {
            chosenIdx = LocationGenerator.rng().nextInt(count);
        } else {
            double totalW = 0.0d;
            for (int idx = 0; idx < count; idx++) totalW += Math.max(0.0d, biomeWeights[idx]);
            if (totalW <= 0.0d) {
                chosenIdx = LocationGenerator.rng().nextInt(count);
            } else {
                double pick = LocationGenerator.rng().nextDouble() * totalW;
                double acc = 0.0d;
                chosenIdx = count - 1;
                for (int idx = 0; idx < count; idx++) {
                    acc += Math.max(0.0d, biomeWeights[idx]);
                    if (pick < acc) {
                        chosenIdx = idx;
                        break;
                    }
                }
            }
        }
        long[] keys = perBiomeKeys[chosenIdx];
        long[] sums = perBiomeSums[chosenIdx];

        long total = sums[sums.length - 1];
        int k;
        if (total <= 0L) {
            // Degenerate: all runs are zero-width. Fall back to a uniform run pick.
            k = LocationGenerator.rng().nextInt(keys.length);
        } else {
            long pick = (long) (LocationGenerator.rng().nextDouble() * total);
            k = firstRunAbove(sums, pick);
        }

        long width = sums[k] - ((k > 0) ? sums[k - 1] : 0L);
        return keys[k] + (width > 0L ? (long) (LocationGenerator.rng().nextDouble() * width) : 0L);
    }

    /**
     * ADR-062 weighted-biome draw over {@link MemoryShape} run views. Identical semantics to the
     * {@code long[][]} form - the views read the shape's blocked union rather than a per-biome
     * {@code long[]} pair, so no run table is duplicated to serve the draw.
     *
     * @param views per-biome run views; first {@code count} entries are used.
     * @param count number of populated entries.
     * @param biomeWeights optional per-biome weights; equal pick when null, wrong-length or sum &lt;= 0.
     * @return drawn 1D location index.
     */
    static long drawWeightedBiome(
            MemoryShape.BiomeUnionTable.BiomeView[] views,
            int count,
            double @org.jetbrains.annotations.Nullable [] biomeWeights) {
        MemoryShape.BiomeUnionTable.BiomeView view = views[chooseBiome(count, biomeWeights)];
        int runs = view.length();
        long total = view.sumAt(runs - 1);
        int k;
        if (total <= 0L) {
            // Degenerate: all runs are zero-width. Fall back to a uniform run pick.
            k = LocationGenerator.rng().nextInt(runs);
        } else {
            long pick = (long) (LocationGenerator.rng().nextDouble() * total);
            k = firstRunAbove(view, runs, pick);
        }

        long width = view.widthAt(k);
        return view.keyAt(k)
                + (width > 0L ? (long) (LocationGenerator.rng().nextDouble() * width) : 0L);
    }

    /**
     * ADR-062 biome pick: proportional to {@code biomeWeights}, equal otherwise.
     *
     * @param count number of candidate biomes.
     * @param biomeWeights optional weights; equal pick when null, wrong-length or sum &lt;= 0.
     * @return chosen index in {@code [0, count)}.
     */
    private static int chooseBiome(int count, double @org.jetbrains.annotations.Nullable [] biomeWeights) {
        if (biomeWeights == null || biomeWeights.length < count) {
            return LocationGenerator.rng().nextInt(count);
        }
        double totalW = 0.0d;
        for (int idx = 0; idx < count; idx++) totalW += Math.max(0.0d, biomeWeights[idx]);
        if (totalW <= 0.0d) return LocationGenerator.rng().nextInt(count);
        double pick = LocationGenerator.rng().nextDouble() * totalW;
        double acc = 0.0d;
        for (int idx = 0; idx < count; idx++) {
            acc += Math.max(0.0d, biomeWeights[idx]);
            if (pick < acc) return idx;
        }
        return count - 1;
    }

    /**
     * {@link #firstRunAbove(long[], long)} over a run view.
     *
     * @param view the biome's run view.
     * @param runs the view's run count.
     * @param pick draw value in {@code [0, totalWidth)}.
     * @return run index in {@code [0, runs)}.
     */
    private static int firstRunAbove(
            MemoryShape.BiomeUnionTable.BiomeView view, int runs, long pick) {
        int lo = 0;
        int hi = runs - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (view.sumAt(mid) > pick) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /**
     * Index of the first run whose prefix sum is strictly greater than {@code pick} - i.e. the run
     * a width-weighted draw of {@code pick} lands in.
     *
     * <p>Explicit binary search rather than {@link java.util.Arrays#binarySearch}: zero-width runs
     * make the prefix sums non-strictly increasing, and duplicates have to resolve to the first
     * such index for the draw to stay width-proportional.
     *
     * @param sums prefix sums of run width, non-decreasing.
     * @param pick draw value in {@code [0, sums[last])}.
     * @return run index in {@code [0, sums.length)}.
     */
    private static int firstRunAbove(long[] sums, long pick) {
        int lo = 0;
        int hi = sums.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sums[mid] > pick) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /**
     * Gather the requested biomes' run tables for the recall draw, reusing the previous gather
     * while the shape's tables are unchanged ({@link MemoryShape#biomeTableVersion()}).
     *
     * <p>The gather references the shape's own {@code long[]} arrays, so nothing is copied and the
     * ADR-062 weights and gray-space probability - which depend only on the tables and on config -
     * are computed once per table version instead of once per attempt.
     *
     * @param memoryShape the region's spatial memory
     * @return {@code true} when at least one requested biome has recorded runs to draw from
     */
    private boolean refreshBiomeDrawTables(MemoryShape<?> memoryShape) {
        long version = memoryShape.biomeTableVersion();
        if (version == biomeTablesVersion) return biomeDrawCount > 0;
        biomeTablesVersion = version;

        int requested = state.biomeNames.size();
        MemoryShape.BiomeUnionTable.BiomeView[] viewsOut =
                new MemoryShape.BiomeUnionTable.BiomeView[requested];
        double[] weightsOut = new double[requested];
        boolean[] recorded = new boolean[requested];
        int count = 0;
        int flatRuns = 0;
        double recordedWeight = 0.0d;
        double grayWeight = 0.0d;

        int at = 0;
        for (String biomeName : state.biomeNames) {
            int slot = at++;
            MemoryShape.BiomeUnionTable.BiomeView view = memoryShape.biomeRunView(biomeName);
            if (view == null || view.length() == 0) continue;
            recorded[slot] = true;
            double cfg = state.biomeWeights.getOrDefault(
                    biomeName.toUpperCase(java.util.Locale.ROOT), 1.0d);
            double gf = grayFraction(view.length(), GRAY_SPACE_MIN_RUNS);
            viewsOut[count] = view;
            weightsOut[count] = cfg * (1.0d - gf);
            recordedWeight += weightsOut[count];
            grayWeight += cfg * gf;
            count++;
            flatRuns += view.length();
        }

        // Requested biomes with no recorded runs defer their whole weight to spiral exploration,
        // but only when the world can actually produce them.
        at = 0;
        for (String biomeName : state.biomeNames) {
            if (recorded[at++]) continue;
            String up = biomeName.toUpperCase(java.util.Locale.ROOT);
            boolean registered = state.worldBiomeRegistry.isEmpty()
                    || state.worldBiomeRegistry.contains(up)
                    || state.worldBiomeRegistry.contains("MINECRAFT:" + up);
            if (!registered) continue; // true 0: world cannot produce it
            double cfg = state.biomeWeights.getOrDefault(up, 1.0d);
            if (cfg <= 0.0d) continue; // explicitly suppressed
            grayWeight += cfg;
        }

        biomeDrawViews = viewsOut;
        biomeDrawWeights = weightsOut;
        biomeDrawCount = count;
        biomeDrawPGray = graySpaceProbability(recordedWeight, grayWeight);

        if (count > 0 && !state.biomeWeighted) {
            long[] fk = new long[flatRuns];
            long[] fw = new long[flatRuns];
            int at2 = 0;
            for (int b = 0; b < count; b++) {
                MemoryShape.BiomeUnionTable.BiomeView view = viewsOut[b];
                for (int k = 0; k < view.length(); k++) {
                    fk[at2] = view.keyAt(k);
                    fw[at2] = view.widthAt(k);
                    at2++;
                }
            }
            flatDrawKeys = fk;
            flatDrawWidths = fw;
        } else {
            flatDrawKeys = null;
            flatDrawWidths = null;
        }
        return count > 0;
    }

    /**
     * ADR-062 minimum-diversity threshold: distinct recorded run count at which
     * a biome is treated as well-recorded. Below this, weight is partially deferred
     * to gray-space exploration to prevent single-run clustering.
     */
    static final long GRAY_SPACE_MIN_RUNS = 8L;

    /**
     * ADR-062: fraction of biome selection weight deferred to gray space.
     * Ramps linearly from 1.0 (unrecorded) to 0.0 (>= minRuns).
     *
     * @param runs distinct recorded run count for the biome.
     * @param minRuns well-recorded threshold (<= 0 disables ramp).
     * @return gray-space deferral fraction in {@code [0.0, 1.0]}.
     */
    static double grayFraction(long runs, long minRuns) {
        if (minRuns <= 0L) return 0.0d;
        if (runs >= minRuns) return 0.0d;
        if (runs <= 0L) return 1.0d;
        return (double) (minRuns - runs) / (double) minRuns;
    }

    /**
     * ADR-062: exploration probability for gray space (unrecorded / thinly recorded biomes)
     * versus recall memory draw.
     *
     * @param recordedWeight summed run-adjusted recall weight.
     * @param grayWeight summed gray-space exploration weight.
     * @return exploration probability in {@code [0.0, 1.0]}.
     */
    static double graySpaceProbability(double recordedWeight, double grayWeight) {
        if (grayWeight <= 0.0d) return 0.0d;
        double total = recordedWeight + grayWeight;
        if (total <= 0.0d) return 0.0d;
        return grayWeight / total;
    }

    /** Continue the current attempt inline (no scheduler hop). */
    private void continueInline(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] PregenTask inline step failed: " + t, t);
            result.complete(null);
        }
    }

    @Override
    public void run() {
        while (!result.isDone()) {
            needsReschedule = false;
            inRunAttempt = true;
            try {
                runAttempt();
            } catch (Throwable t) {
                RTP.log(Level.WARNING, "[RTP] PregenTask attempt threw: " + t, t);
                result.complete(null);
                inRunAttempt = false;
                return;
            } finally {
                inRunAttempt = false;
            }
            if (!needsReschedule) {
                // Attempt is in flight on a future; the callback will re-invoke run().
                return;
            }
        }
    }

    private void runAttempt() {
        CfDiag.pregenAttemptStart.increment();
        if (i > state.maxAttempts) {
            completeExhausted();
            return;
        }

        // --- shape select + biomeRecall ---
        long l = -1;
        int[] select;
        if (state.shape instanceof MemoryShape<?> memoryShape) {
            // Amortized: the attempt loop dirties the same flags it reads (every rejection marks
            // a bad chunk, every success records a biome), so an unconditional rebuild here cost
            // a full O(recorded runs) copy-on-write merge per attempt. Staleness is safe -
            // isKnownBad reads the pending marks directly.
            memoryShape.flushAndRebuildIfNeeded(state.resolution);
            if (state.biomeRecall && !state.defaultBiomes) {
                if (refreshBiomeDrawTables(memoryShape)) {
                    if (state.biomeWeighted) {
                        // ADR-062: pick biome weighted by config weight and run width.
                        // Thinly-recorded biomes defer weight (grayFraction) to spiral
                        // exploration, and unrecorded biomes contribute to grayWeight.
                        double pGray = biomeDrawPGray;
                        if (!state.biomeRecallForced
                                && pGray > 0.0d
                                && LocationGenerator.rng().nextDouble() < pGray) {
                            l = memoryShape.rand();
                        } else {
                            l = drawWeightedBiome(
                                    biomeDrawViews, biomeDrawCount, biomeDrawWeights);
                        }
                    } else {
                        int nextInt = LocationGenerator.rng().nextInt(flatDrawKeys.length);
                        long width = flatDrawWidths[nextInt];
                        l = flatDrawKeys[nextInt]
                                + (long) (LocationGenerator.rng().nextDouble() * width);
                    }
                } else if (state.biomeRecallForced) {
                    RTP.log(Level.WARNING,
                            "[RTP] invalid state, biome recall enabled but biomes are not in memory - "
                                    + Arrays.toString(state.biomeNames.toArray()));
                    result.complete(new GenerationResult(null, i, null));
                    return;
                } else {
                    l = memoryShape.rand();
                }
            } else {
                l = memoryShape.rand();
            }
            select = memoryShape.locationToXZ(l);
        } else {
            select = state.shape.select();
        }

        int blockX = (select[0] << 4) + 7;
        int blockZ = (select[1] << 4) + 7;
        if (state.verbose) {
            state.selections.add(new AbstractMap.SimpleEntry<>((long) select[0], (long) select[1]));
        }

        // --- worldborder ---
        // Null-guard: a platform adapter may return no border for a world (e.g. a
        // per-version Fabric RTPWorld impl that the common accessor's
        // createNativeWorldBorder doesn't recognise via its instanceof check).
        // Treat absent border as "no border in effect" - accept the candidate
        // rather than NPE'ing this attempt every tick. The downstream
        // shape/region constraints still bound the selection space.
        WorldBorder border = (WorldBorder) RTP.serverAccessor.getWorldBorder(state.world.name());
        io.github.dailystruggle.rtp.api.world.RTPLocation borderProbe =
                new io.github.dailystruggle.rtp.api.world.RTPLocation(
                        state.world,
                        blockX,
                        (state.vert.maxY() + state.vert.minY()) / 2,
                        blockZ);
        if (border != null && !border.isInside().apply(borderProbe)) {
            if (state.maxAttempts < state.maxAttemptsCeiling) state.maxAttempts++;
            state.worldBorderFails++;
            if (state.worldBorderFails > 1000L) {
                RTP.log(Level.WARNING,
                        "[RTP] 1000 worldborder checks failed. region/selection is likely outside the worldborder");
                result.complete(new GenerationResult(null, i, null));
                return;
            }
            if (state.verbose) {
                state.failMap.get(LocationGenerator.FailTypes.worldBorder)
                        .put("OUTSIDE_BORDER", state.worldBorderFails);
            }
            recordOutcome("worldBorder/OUTSIDE_BORDER");
            rescheduleNextAttempt();
            return;
        }

        int cx = select[0];
        int cz = select[1];
        final long finalL = l;

        // Probabilistically reject ungenerated chunks (PerformanceKeys.pregeneratedPreference).
        // Uses non-blocking RTPWorld.isChunkGenerated (S-005 safe).
        double pregenPref;
        try {
            Object o = state.performance.getConfigValue(PerformanceKeys.pregeneratedPreference, 0.0d);
            pregenPref = (o instanceof Number n) ? n.doubleValue() : Double.parseDouble(o.toString());
        } catch (Throwable t) {
            pregenPref = 0.0d;
        }
        if (pregenPref > 0.0d && !state.world.isChunkGenerated(cx, cz)) {
            double clamped = Math.min(1.0d, pregenPref);
            if (clamped >= 1.0d || java.util.concurrent.ThreadLocalRandom.current().nextDouble() < clamped) {
                if (state.verbose) {
                    state.failMap.get(LocationGenerator.FailTypes.ungenerated)
                            .merge("UNGENERATED", 1L, Long::sum);
                }
                recordOutcome("ungenerated/UNGENERATED");
                rescheduleNextAttempt();
                return;
            }
        }

        // --- probe-first fast path (ADR-109: measured cost decides whether the probe runs) ---
        ProbeFirstGovernor gov = governor();
        boolean probeFirst = gov == null || gov.shouldProbe(ProbeFirstGovernor.binKey(cx, cz));
        if (probeFirst && tryProbeFirst(cx, cz, finalL, gov)) {
            // Probe rejected (reschedule queued) or async continuation owns the attempt.
            return;
        }

        // --- full path via ADR-016 section 13.1 precedence chain (cached → anvil → live) ---
        requestChunk(cx, cz, finalL, /*staleRetries*/ 0, gov, probeFirst);
    }

    private @org.jetbrains.annotations.Nullable ProbeFirstGovernor governor() {
        Region r = state.region;
        return (r == null) ? null : ProbeFirstGovernor.of(r.name, ProbeFirstGovernor.Path.FILL);
    }

    /**
     * Probe-first gate (ADR-016 section 13). Attempts early reject using a lean
     * {@link io.github.dailystruggle.rtp.api.world.ChunkColumnProbe}.
     *
     * @return {@code true} if candidate was rejected and reschedule was queued;
     *         {@code false} on accept or unavailable probe.
     */
    private boolean tryProbeFirst(int cx, int cz, long finalL,
                                  @org.jetbrains.annotations.Nullable ProbeFirstGovernor gov) {
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor<?> vert =
                state.vert;
        int minY = vert.minY();
        int maxY = vert.maxY();
        if (minY >= maxY) return false;

        CompletableFuture<io.github.dailystruggle.rtp.api.world.ChunkColumnProbe> fut;
        final long probeStart = System.nanoTime();
        try {
            // Widen probe window by one block below minY so that
            // LinearAdjustor/JumpAdjustor.adjustFromProbe (which consults the
            // block at y-1 for standing-surface safety) doesn't trivially
            // reject with probe.minY() > minY - 1. Without this, the fast path
            // would be inert on every candidate and every probe-accept would
            // degrade to a full chunk load.
            fut = state.world.probeChunkColumn(cx, cz, minY - 1, maxY);
        } catch (Throwable t) {
            // Adapter threw - treat as UNKNOWN, fall through to full path.
            RTP.log(Level.FINE,
                    "[RTP] probeChunkColumn threw for world=" + state.world.name()
                            + " chunk=(" + cx + "," + cz + "): " + t);
            return false;
        }
        if (fut == null) return false;

        // If the future is already complete (default no-op adapter), handle synchronously
        // to avoid scheduling overhead and preserve the synchronous runAttempt call shape.
        if (fut.isDone() && !fut.isCompletedExceptionally()) {
            io.github.dailystruggle.rtp.api.world.ChunkColumnProbe probe;
            try {
                probe = fut.getNow(null);
            } catch (Throwable ignored) {
                return false;
            }
            boolean rejected = probe != null && evaluateProbe(probe, cx, cz, finalL);
            long probeNanos = System.nanoTime() - probeStart;
            long drain = (probe != null) ? probe.drainNanos() : 0L;
            long effectiveNanos = (drain > 0L) ? Math.min(drain, probeNanos) : probeNanos;
            if (gov != null) gov.recordProbe(effectiveNanos, probe != null ? probe.groupSize() : 0, rejected);
            return rejected;
        }

        // Async completion - dispatch the evaluation and return true to prevent the
        // caller from also invoking requestChunk. On UNKNOWN / accept, we re-enter
        // the full path from the callback via requestChunk; on reject we reschedule.
        fut.whenComplete((probe, ex) -> {
            long probeNanos = System.nanoTime() - probeStart;
            long drain = (probe != null) ? probe.drainNanos() : 0L;
            long effectiveNanos = (drain > 0L) ? Math.min(drain, probeNanos) : probeNanos;
            int group = (probe != null) ? probe.groupSize() : 0;
            if (ex != null) {
                RTP.log(Level.FINE,
                        "[RTP] probeChunkColumn failed for world=" + state.world.name()
                                + " chunk=(" + cx + "," + cz + "): "
                                + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                if (gov != null) gov.recordProbe(effectiveNanos, group, false);
                continueInline(() -> requestChunk(cx, cz, finalL, /*staleRetries*/ 0, gov, true));
                return;
            }
            if (probe == null) {
                if (gov != null) gov.recordProbe(effectiveNanos, group, false);
                continueInline(() -> requestChunk(cx, cz, finalL, /*staleRetries*/ 0, gov, true));
                return;
            }
            boolean rejected = evaluateProbe(probe, cx, cz, finalL);
            if (gov != null) gov.recordProbe(effectiveNanos, group, rejected);
            if (rejected) {
                // reject already queued rescheduleNextAttempt via evaluateProbe.
                return;
            }
            continueInline(() -> requestChunk(cx, cz, finalL, /*staleRetries*/ 0, gov, true));
        });
        return true;
    }

    /**
     * Evaluate a {@link io.github.dailystruggle.rtp.api.world.ChunkColumnProbe}:
     * run the adjustor's probe-backed Y scan, then biome + unsafe-block check on
     * the accepted Y. Returns {@code true} iff the probe produced a reject
     * verdict (caller must not fall through to the full path) and queues
     * {@link #rescheduleNextAttempt()}. Returns {@code false} on
     * adjustor-UNKNOWN (probe cannot answer - fall back) or probe-accept.
     */
    private boolean evaluateProbe(
            io.github.dailystruggle.rtp.api.world.ChunkColumnProbe probe,
            int cx, int cz, long finalL) {
        RTPCoords picked = state.vert.adjustFromProbe(probe, state.world.name());
        if (picked == null) {
            // UNKNOWN - adjustor could not commit from probe data. Fall through.
            return false;
        }
        int py = picked.y();

        // Center-column biome check (ADR-016 section 13.1 - probe is equivalent to
        // post-chunk-load biomeAt at center column).
        String probeBiome = probe.biomeAt(py);
        if (probeBiome != null) {
            String ub = probeBiome.toUpperCase();
            if (BiomeNames.matches(state.biomeNames, ub) != state.biomeWhitelist) {
                if (state.maxAttempts < state.maxAttemptsCeiling) state.maxAttempts++;
                if (state.verbose) {
                    state.failMap.get(LocationGenerator.FailTypes.prefilterBiome)
                            .compute("biome=" + ub, (s, a) -> (a == null) ? 1L : ++a);
                }
                recordOutcome("prefilterBiome/" + ub);
                continueInline(this::rescheduleNextAttempt);
                return true;
            }
        }

        // Center-column unsafe-block check. The authoritative safety pipeline still
        // runs on accepted candidates; this just rejects obvious losers early.
        String probeBlock = probe.blockAt(py);
        if (probeBlock != null && state.unsafeBlocks != null) {
            String ub = probeBlock.toUpperCase();
            if (MaterialNames.matches(state.unsafeBlocks, ub)) {
                if (state.maxAttempts < state.maxAttemptsCeiling) state.maxAttempts++;
                if (state.verbose) {
                    state.failMap.get(LocationGenerator.FailTypes.prefilterBlock)
                            .compute("block=" + ub, (s, a) -> (a == null) ? 1L : ++a);
                }
                recordOutcome("prefilterBlock/" + ub);
                continueInline(this::rescheduleNextAttempt);
                return true;
            }
        }

        // Probe accepted. Full authoritative pipeline still runs via requestChunk.
        return false;
    }

    /**
     * Request candidate chunk via {@link RTPWorld#getOrLoadChunk(int, int)}.
     * Anvil-backed chunks evaluate on async worker; live-backed chunks allocate
     * a {@link ChunkReservation} and dispatch to the region-owning thread.
     */
    private void requestChunk(int cx, int cz, long finalL, int staleRetries) {
        requestChunk(cx, cz, finalL, staleRetries, null, false);
    }

    /** As above; when {@code gov} is non-null the load's wall time feeds the ADR-109 governor. */
    private void requestChunk(int cx, int cz, long finalL, int staleRetries,
                              @org.jetbrains.annotations.Nullable ProbeFirstGovernor gov, boolean afterProbe) {
        if (readOnly()) {
            // ADR-110: speculative fill reads resident chunks / region files only. A native load
            // happens only with a pin slot (result held until teleport); otherwise defer.
            final long readStart = System.nanoTime();
            state.world.getOrReadChunk(cx, cz).whenComplete((chunk, ex) -> {
                if (ex == null && chunk != null) {
                    gate().onReadResolved();
                    if (gov != null) gov.recordLoad(afterProbe, System.nanoTime() - readStart);
                    continueInline(() -> onChunkResolved(cx, cz, finalL, chunk, staleRetries));
                    return;
                }
                if (tryPin()) {
                    // Native-load time is not a region-file read: keep it out of ADR-109.
                    continueInline(() -> loadChunk(cx, cz, finalL, staleRetries, null, afterProbe));
                    return;
                }
                gate().onDeferredCenter();
                recordOutcome("deferred/needsLive chunk=(" + cx + "," + cz + ")");
                continueInline(this::rescheduleNextAttempt);
            });
            return;
        }
        loadChunk(cx, cz, finalL, staleRetries, speculative() ? null : gov, afterProbe);
    }

    private void loadChunk(int cx, int cz, long finalL, int staleRetries,
                           @org.jetbrains.annotations.Nullable ProbeFirstGovernor gov, boolean afterProbe) {
        // Per-attempt chunk-load: per-chunk deadline lives in the world adapter
        // (it knows when the server is incapable of loading a particular chunk).
        // Letting the future complete naturally means slow loads still warm
        // rtpChunkCache for the next attempt; rejection on null/exception still
        // routes through the existing FailTypes.nullChunk attribution path.
        final long loadStart = System.nanoTime();
        state.world.getOrLoadChunk(cx, cz, "PregenTask.requestChunk")
                .whenComplete((chunk, ex) -> {
                    if (gov != null && ex == null) gov.recordLoad(afterProbe, System.nanoTime() - loadStart);
                    if (ex != null) {
                        RTP.log(Level.WARNING,
                                "[RTP] getOrLoadChunk failed for world=" + state.world.name()
                                        + " chunk=(" + cx + "," + cz + "): "
                                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                        if (state.verbose) {
                            state.failMap.get(LocationGenerator.FailTypes.nullChunk)
                                    .compute("reason=ticketFailed", (s, a) -> (a == null) ? 1L : ++a);
                        }
                        recordOutcome("nullChunk/ticketFailed");
                        continueInline(this::rescheduleNextAttempt);
                        return;
                    }
                    if (chunk == null) {
                        if (state.verbose) {
                            state.failMap.get(LocationGenerator.FailTypes.nullChunk)
                                    .compute("reason=asyncLoadNull", (s, a) -> (a == null) ? 1L : ++a);
                        }
                        recordOutcome("nullChunk/asyncLoadNull chunk=(" + cx + "," + cz + ")");
                        continueInline(this::rescheduleNextAttempt);
                        return;
                    }
                    continueInline(() -> onChunkResolved(cx, cz, finalL, chunk, staleRetries));
                });
    }

    /**
     * Branch on {@link RTPChunk#isSelfContained()}.
     * Anvil chunks evaluate inline on async worker; live chunks await reservation
     * readiness then dispatch to the region thread for stale guard and evaluation.
     */
    @SuppressWarnings("resource")
    private void onChunkResolved(int cx, int cz, long finalL, RTPChunk<?> chunk, int staleRetries) {
        if (chunk.isSelfContained()) {
            // Anvil / view hit: no reservation, no stale guard, no region-thread hop.
            continueInline(() -> proceedWithEvaluation(cx, cz, finalL, chunk, /*reservation*/ null));
            return;
        }

        // Live-backed: allocate reservation + await ticket apply, then dispatch
        // to the region-owning thread for the stale guard and block reads.
        final long chunkKey = ((long) cx & 0xffffffffL) | ((long) cz << 32);
        CfDiag.chunkSetPregenLive.increment();
        ChunkSet ticket = new ChunkSet(
                state.world, cx, cz,
                Collections.singletonList(CompletableFuture.completedFuture(chunkKey)),
                new CompletableFuture<>());
        final ChunkReservation reservation = new ChunkReservation(ticket, state.world);

        reservation.readyFuture().orTimeout(2, TimeUnit.SECONDS).whenComplete((v, ex) -> {
            if (ex != null) {
                RTP.log(Level.WARNING,
                        "[RTP] Chunk ticket application did not complete within 2s ("
                                + state.world.name() + " " + cx + "," + cz
                                + "); rejecting candidate.");
                if (state.verbose) {
                    state.failMap.get(LocationGenerator.FailTypes.timeout)
                            .compute("reason=ticketApplyTimeout", (s, a) -> (a == null) ? 1L : ++a);
                }
                recordOutcome("timeout/ticketApplyTimeout");
                closeIfPresent(reservation);
                continueInline(this::rescheduleNextAttempt);
                return;
            }
            dispatchLiveEvaluation(cx, cz, finalL, chunk, reservation, staleRetries);
        });
    }

    /**
     * Dispatch the live-backed candidate's block evaluation to the region-owning
     * thread. On Folia this is the region scheduler; on Spigot/Paper this is the
     * main thread. Runs the stale-chunk guard authoritatively (ADR-015), then
     * proceeds inline within the region-thread runnable.
     */
    private void dispatchLiveEvaluation(
            int cx, int cz, long finalL,
            RTPChunk<?> chunk, ChunkReservation reservation, int staleRetries) {
        io.github.dailystruggle.rtp.api.world.RTPLocation targetLoc =
                new io.github.dailystruggle.rtp.api.world.RTPLocation(
                        state.world, (cx << 4) + 7, 0, (cz << 4) + 7);
        try {
            RTP.serverAccessor.getScheduler().runTask(targetLoc, () -> {
                try {
                    // Stale-chunk guard on the region-owning thread, where
                    // isChunkLoaded is authoritative and legal to call.
                    if (!state.world.isChunkLoaded(cx, cz)) {
                        closeIfPresent(reservation);
                        if (staleRetries < state.staleChunkRetryLimit) {
                            RTP.log(Level.FINE,
                                    "[RTP] Stale chunk detected on region-thread dispatch ("
                                            + state.world.name() + " " + cx + "," + cz
                                            + "); re-requesting (attempt " + (staleRetries + 1)
                                            + "/" + state.staleChunkRetryLimit + ")");
                            // Bounce back to async pool - the async scheduler is
                            // where the CF chain is safe.
                            RTP.serverAccessor.getScheduler().runTaskAsynchronously(
                                    () -> requestChunk(cx, cz, finalL, staleRetries + 1));
                        } else {
                            if (state.verbose) {
                                state.failMap.get(LocationGenerator.FailTypes.nullChunk)
                                        .compute("reason=staleChunkBeforeVert", (s, a) -> (a == null) ? 1L : ++a);
                            }
                            recordOutcome("nullChunk/staleChunkBeforeVert chunk=(" + cx + "," + cz + ")");
                            RTP.serverAccessor.getScheduler().runTaskAsynchronously(this::rescheduleNextAttempt);
                        }
                        return;
                    }
                    proceedWithEvaluation(cx, cz, finalL, chunk, reservation);
                } catch (Throwable t) {
                    RTP.log(Level.WARNING,
                            "[RTP] Region-thread evaluation threw: " + t, t);
                    closeIfPresent(reservation);
                    RTP.serverAccessor.getScheduler().runTaskAsynchronously(this::rescheduleNextAttempt);
                }
            });
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] Failed to dispatch region-thread evaluation: " + t, t);
            closeIfPresent(reservation);
            continueInline(this::rescheduleNextAttempt);
        }
    }

    /**
     * Evaluate the resolved chunk: vert adjust → biome filter → neighbour
     * grid load → safety y-scan → global verifiers → completeSuccess.
     * {@code reservation} may be {@code null} when the chunk is Anvil-backed
     * ({@link RTPChunk#isSelfContained()}). Every rejection path calls
     * {@link ChunkReservation#close()} only when the reservation is non-null.
     */
    private void proceedWithEvaluation(int cx, int cz, long finalL, RTPChunk<?> chunk,
                                       @org.jetbrains.annotations.Nullable ChunkReservation reservation) {

        // Defensive re-resolve: for live-backed chunks
        // a window exists between the dispatchLiveEvaluation isChunkLoaded guard
        // and this consumer reading block state. Re-fetch the cached reference;
        // on null, reject via existing FailTypes.nullChunk attribution. Anvil
        // (isSelfContained) snapshots can't go stale and bypass the guard.
        if (chunk != null && !chunk.isSelfContained()) {
            long centerKey = ((long) cx & 0xffffffffL) | ((long) cz << 32);
            RTPChunk<?> live = state.world.getCachedChunk(centerKey);
            if (live == null || !state.world.isChunkLoaded(cx, cz)) {
                if (state.verbose) {
                    state.failMap.get(LocationGenerator.FailTypes.nullChunk)
                            .compute("reason=staleAfterDispatch", (s, a) -> (a == null) ? 1L : ++a);
                }
                recordOutcome("nullChunk/staleAfterDispatch chunk=(" + cx + "," + cz + ")");
                closeIfPresent(reservation);
                rescheduleNextAttempt();
                return;
            }
            chunk = live;
        }

        // --- vert.adjust ---
        if (chunk == null) {
            closeIfPresent(reservation);
            rescheduleNextAttempt();
            return;
        }
        RTPCoords res = state.vert.adjust(chunk);
        if (res == null) {
            if (state.defaultBiomes && state.shape instanceof MemoryShape && state.biomeRecall) {
                // addBadChunk: chunk-uniform - within a chunk the per-column selection order
                // is deterministic, so the twin spiral index picks the same column and
                // vert.adjust returns null identically.
                ((MemoryShape<?>) state.shape).addBadChunk(finalL, LocationGenerator.FailTypes.vert);
            }
            if (state.verbose) {
                state.failMap.get(LocationGenerator.FailTypes.vert)
                        .compute("biome=", (s, a) -> (a == null) ? 1L : ++a);
            }
            recordOutcome("vert/no-stand-y");
            closeIfPresent(reservation);
            rescheduleNextAttempt();
            return;
        }

        final int finalX = res.x();
        final int finalY = res.y();
        final int finalZ = res.z();

        // --- biome filter (ADR-016 section 13.1 - read from the resolved chunk) ---
        String currBiome = chunk.getBiome(finalX, finalY, finalZ).toUpperCase();
        if (BiomeNames.matches(state.biomeNames, currBiome) != state.biomeWhitelist) {
            if (state.maxAttempts < state.maxAttemptsCeiling) state.maxAttempts++;
            if (state.defaultBiomes && state.shape instanceof MemoryShape && state.biomeRecall) {
                // addBadChunk: chunk-uniform - biome is a per-chunk property; the twin spiral
                // index decodes to the same chunk and is guaranteed to fail the same biome check.
                ((MemoryShape<?>) state.shape).addBadChunk(finalL, LocationGenerator.FailTypes.biome);
            }
            if (state.verbose) {
                String cb = currBiome;
                state.failMap.get(LocationGenerator.FailTypes.biome)
                        .compute("biome=" + cb, (s, a) -> (a == null) ? 1L : ++a);
            }
            recordOutcome("biome/" + currBiome);
            closeIfPresent(reservation);
            rescheduleNextAttempt();
            return;
        }

        final String resBiome = currBiome;

        loadSafetyNeighbours(cx, cz, finalL, finalX, finalY, finalZ, resBiome, chunk, reservation);
    }

    /**
     * safetyCheck: load the (2r+1)² neighbour grid, then y-scan. ADR-110: while speculative and
     * unpinned, neighbours resolve from resident chunks / region files only; a miss escalates to
     * a pin slot (re-run with native loads) or defers the candidate.
     */
    private void loadSafetyNeighbours(int cx, int cz, long finalL, int finalX, int finalY, int finalZ,
                                      String resBiome, RTPChunk<?> chunk,
                                      @org.jetbrains.annotations.Nullable ChunkReservation reservation) {
        final boolean readOnlyNow = readOnly();
        int safe = state.safetyRadius;
        int L = safe * 2 + 1;
        int centerChunkX = chunk.x();
        int centerChunkZ = chunk.z();
        final RTPChunk<?>[] localChunks = new RTPChunk<?>[L * L];
        localChunks[safe * L + safe] = chunk;

        List<CompletableFuture<Long>> neighbourFutures = new ArrayList<>();
        List<int[]> neighbourIdx = new ArrayList<>();
        for (int dx = -safe; dx <= safe; dx++) {
            for (int dz = -safe; dz <= safe; dz++) {
                if (dx == 0 && dz == 0) continue;
                int ncx = centerChunkX + dx;
                int ncz = centerChunkZ + dz;
                int idx = (dx + safe) * L + (dz + safe);
                state.world.recordChunkLoadOrigin("PregenTask.safetyNeighbourGrid");
                neighbourFutures.add(readOnlyNow
                        ? state.world.getChunkIfReadable(ncx, ncz)
                        : state.world.getChunkAt(ncx, ncz));
                neighbourIdx.add(new int[]{idx});
            }
        }

        if (neighbourFutures.isEmpty()) {
            // safetyRadius == 0: no neighbours to load, evaluate immediately.
            evaluateSafety(cx, cz, finalL, finalX, finalY, finalZ, resBiome, localChunks, L, centerChunkX, centerChunkZ, reservation);
            return;
        }

        // Safety neighbour grid: 5s outer timeout prevents hanging futures from
        // permanently retaining the allOf dependents graph if a load drops.
        CfDiag.pregenAllOfDispatch.increment();
        CompletableFuture.allOf(neighbourFutures.toArray(new CompletableFuture[0]))
                .orTimeout(5, TimeUnit.SECONDS)
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        CfDiag.pregenAllOfTimeout.increment();
                        RTP.log(Level.WARNING,
                                "[RTP] Safety-check neighbour loads failed ("
                                        + state.world.name() + " center=(" + cx + "," + cz + ")): " + ex);
                        if (state.verbose) {
                            state.failMap.get(LocationGenerator.FailTypes.nullChunk)
                                    .compute("reason=neighborNull", (s, a) -> (a == null) ? 1L : ++a);
                        }
                        recordOutcome("nullChunk/neighborNull-allOf");
                        closeIfPresent(reservation);
                        continueInline(this::rescheduleNextAttempt);
                        return;
                    }
                    // Populate the localChunks grid from the resolved neighbour keys.
                    boolean ok = true;
                    for (int idxI = 0; idxI < neighbourFutures.size(); idxI++) {
                        Long nkey;
                        try {
                            nkey = neighbourFutures.get(idxI).getNow(null);
                        } catch (Throwable t) {
                            nkey = null;
                        }
                        RTPChunk<?> nchunk = (nkey != null) ? state.world.getCachedChunk(nkey) : null;
                        if (nchunk == null) {
                            ok = false;
                            break;
                        }
                        localChunks[neighbourIdx.get(idxI)[0]] = nchunk;
                    }
                    if (!ok && readOnlyNow) {
                        if (tryPin()) {
                            continueInline(() -> loadSafetyNeighbours(
                                    cx, cz, finalL, finalX, finalY, finalZ, resBiome, chunk, reservation));
                            return;
                        }
                        gate().onDeferredNeighbour();
                        recordOutcome("deferred/neighbourNeedsLive chunk=(" + cx + "," + cz + ")");
                        closeIfPresent(reservation);
                        continueInline(this::rescheduleNextAttempt);
                        return;
                    }
                    if (!ok) {
                        if (state.verbose) {
                            state.failMap.get(LocationGenerator.FailTypes.nullChunk)
                                    .compute("reason=neighborNull", (s, a) -> (a == null) ? 1L : ++a);
                        }
                        recordOutcome("nullChunk/neighborNull-cacheMiss");
                        closeIfPresent(reservation);
                        continueInline(this::rescheduleNextAttempt);
                        return;
                    }
                    continueInline(() -> evaluateSafety(
                            cx, cz, finalL, finalX, finalY, finalZ, resBiome,
                            localChunks, L, centerChunkX, centerChunkZ, reservation));
                });
    }

    private void evaluateSafety(int cx, int cz, long finalL, int finalX, int finalY, int finalZ,
                                String resBiome,
                                RTPChunk<?>[] localChunks, int L,
                                int centerChunkX, int centerChunkZ,
                                @org.jetbrains.annotations.Nullable ChunkReservation reservation) {
        int safe = state.safetyRadius;
        // ADR-015 Folia-follow-up: no redundant stale-guard here. For the
        // live-backed branch the guard already fired on the region-owning
        // thread inside dispatchLiveEvaluation; for the Anvil-backed branch
        // there is no live chunk state to be stale against. A post-dispatch
        // race manifests organically via neighbour getCachedChunk returning
        // null (reason=neighborNull) so we still cannot false-accept.
        boolean pass = true;

        safetyCheck:
        for (int x = finalX - safe; pass && x <= finalX + safe; x++) {
            int chunkX = x >> 4;
            int xx = x & 15;
            int dcX = chunkX - centerChunkX;
            for (int z = finalZ - safe; z <= finalZ + safe; z++) {
                int chunkZ = z >> 4;
                int zz = z & 15;
                int dcZ = chunkZ - centerChunkZ;

                int idx = (dcX + safe) * L + (dcZ + safe);
                RTPChunk<?> c1 = (idx >= 0 && idx < localChunks.length) ? localChunks[idx] : null;
                if (c1 == null) {
                    if (state.verbose) {
                        state.failMap.get(LocationGenerator.FailTypes.nullChunk)
                                .compute("reason=neighborNull", (s, a) -> (a == null) ? 1L : ++a);
                    }
                    pass = false;
                    break safetyCheck;
                }
                for (int y = finalY - safe; y <= finalY + safe; y++) {
                    if (y > state.world.getMaxHeight() || y < state.world.getMinHeight()) continue;
                    if (!c1.isSafe(xx, y, zz, state.unsafeBlocks)) {
                        pass = false;
                        break safetyCheck;
                    }
                }
            }
        }

        if (!pass) {
            if (state.verbose) {
                final int fx = finalX, fy = finalY, fz = finalZ;
                state.failMap.get(LocationGenerator.FailTypes.safety)
                        .compute("location=(" + fx + "," + fy + "," + fz + ")",
                                (s, a) -> (a == null) ? 1L : ++a);
            }
            recordOutcome("safety/blockReject (" + finalX + "," + finalY + "," + finalZ + ")");
            if (state.shape instanceof MemoryShape) {
                // addBadChunk: chunk-uniform - within a chunk the per-column selection order
                // is deterministic, so the twin spiral index picks the same column and the
                // same (2r+1)^3 safety scan rejects it again.
                ((MemoryShape<?>) state.shape).addBadChunk(finalL, LocationGenerator.FailTypes.safety);
            }
            closeIfPresent(reservation);
            rescheduleNextAttempt();
            return;
        }

        // --- GlobalRegionVerifiers (non-blocking) ---
        RTPCoords resCoords = new RTPCoords(state.world.name(), finalX, finalY, finalZ);
        GlobalRegionVerifiers.checkGlobalRegionVerifiersDetailed(resCoords)
                .whenComplete((verResult, verEx) -> {
                    if (verEx != null || verResult == null || !verResult.passed()) {
                        Class<?> failedClass = (verResult != null) ? verResult.failedVerifierClass() : null;
                        String className = (failedClass != null) ? failedClass.getSimpleName() : "verifier";
                        if (state.verbose) {
                            final int fx = finalX, fy = finalY, fz = finalZ;
                            state.failMap.get(LocationGenerator.FailTypes.safetyExternal)
                                    .compute("location=(" + fx + "," + fy + "," + fz + ")[verifier=" + className + "]",
                                            (s, a) -> (a == null) ? 1L : ++a);
                        }
                        recordOutcome("safetyExternal[" + className + "] ex=" + (verEx == null ? "null" : verEx.getClass().getSimpleName()));
                        if (state.shape instanceof MemoryShape) {
                            io.github.dailystruggle.rtp.common.selection.region.claim.ClaimAnchoredRegionTracker
                                    .encapsulateClaim((MemoryShape<?>) state.shape, state.world.name(), finalX, finalZ, failedClass);
                        }
                        closeIfPresent(reservation);
                        continueInline(this::rescheduleNextAttempt);
                        return;
                    }
                    // SUCCESS
                    continueInline(() -> completeSuccess(finalL, finalX, finalY, finalZ, resBiome, resCoords, reservation));
                });
    }

    private void completeSuccess(long finalL, int finalX, int finalY, int finalZ,
                                 String resBiome, RTPCoords resCoords,
                                 ChunkReservation reservation) {
        recordOutcome("success (" + finalX + "," + finalY + "," + finalZ + ")");
        if (state.shape instanceof MemoryShape && finalL > 0) {
            ((MemoryShape<?>) state.shape).addBiomeLocation(finalL, state.resolution, resBiome);
        }
        long viewDistanceRadius = state.performance.getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
        int radius = Math.max(state.safetyRadius, (int) viewDistanceRadius);
        int ccx = resCoords.x() >> 4;
        int ccz = resCoords.z() >> 4;
        if (readOnly()) {
            // ADR-110: an unpinned result would lose its ring to unload before use; the
            // teleport-time preload (viewDistanceTeleport) loads it when a player is waiting.
            if (radius > 0) gate().onRingSkipped();
            closeIfPresent(reservation);
            result.complete(new GenerationResult(resCoords, i, null));
            return;
        }
        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                state.world.recordChunkLoadOrigin("PregenTask.viewDistanceSuccess");
                chunks.add(state.world.getChunkAt(ccx + x, ccz + z));
            }
        }
        CfDiag.chunkSetPregenVerified.increment();
        ChunkSet verifiedChunks = new ChunkSet(state.world, ccx, ccz, chunks, new CompletableFuture<>());
        if (speculative()) {
            // ADR-110 pinned fill: take the kept-queue pin before dropping the per-iteration
            // ticket (ref-counted, so the center never goes unticketed). The caller hands the
            // reservation to the kept queue or closes it.
            ChunkReservation pin = new ChunkReservation(verifiedChunks, state.world);
            closeIfPresent(reservation);
            result.complete(new GenerationResult(resCoords, i, verifiedChunks, pin));
            return;
        }
        // Close the per-iteration reservation; ownership of the verifiedChunks set
        // transfers via the returned GenerationResult (matches the prior contract).
        closeIfPresent(reservation);
        result.complete(new GenerationResult(resCoords, i, verifiedChunks));
    }

    private void completeExhausted() {
        long reported = Math.min(i, state.maxAttempts);
        // Verbose failure summary - preserves the historical log shape.
        if (state.verbose && i >= state.maxAttempts) {
            RTP.log(Level.INFO,
                    "#00ff80[RTP] ["
                            + state.region.name
                            + "] failed to generate a location within "
                            + state.maxAttempts
                            + " tries. Adjust your configuration.");
            for (Map.Entry<LocationGenerator.FailTypes, Map<String, Long>> mapEntry : state.failMap.entrySet()) {
                Map<String, Long> map = mapEntry.getValue();
                String[] output = new String[map.size()];
                int pos = 0;
                long count = 0;
                for (Map.Entry<String, Long> entry : map.entrySet()) {
                    output[pos] = "#00ff80[RTP] [" + state.region.name + "]  cause="
                            + mapEntry.getKey() + " " + entry.getKey() + " fails=" + entry.getValue();
                    count += entry.getValue();
                    pos++;
                }
                RTP.log(Level.INFO,
                        "#00ff80[RTP] [" + state.region.name + "]  cause=" + mapEntry.getKey() + " fails=" + count);
                for (String out : output) {
                    RTP.log(Level.INFO, out);
                }
            }

            StringBuilder selectionsStr = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<Long, Long> entry : state.selections) {
                if (!first) selectionsStr.append(",");
                first = false;
                selectionsStr.append("(").append(entry.getKey()).append(",").append(entry.getValue()).append(")");
            }
            selectionsStr.append("}");
            RTP.log(Level.INFO, "#0f0080[RTP] [" + state.region.name + "] selections: " + selectionsStr);

            // Per-attempt outcome breadcrumb - always printed (diagnostic: reveals
            // which code path actually fired when the failMap bucketing is
            // ambiguous or appears silent).
            if (state.attemptOutcomes.isEmpty()) {
                RTP.log(Level.INFO,
                        "#ff8040[RTP] [" + state.region.name
                                + "] attemptOutcomes: <empty — no rescheduleNextAttempt site fired;"
                                + " investigate CF callback suppression or task re-entry>");
            } else {
                RTP.log(Level.INFO,
                        "#ff8040[RTP] [" + state.region.name + "] attemptOutcomes ("
                                + state.attemptOutcomes.size() + "):");
                for (String outcome : state.attemptOutcomes) {
                    RTP.log(Level.INFO, "#ff8040[RTP] [" + state.region.name + "]   " + outcome);
                }
            }
        } else if (i >= state.maxAttempts) {
            // [PROMOTE_DIAG] Always-on (verbose-independent) compact exhaustion
            // summary. Without this a non-verbose location generation that fails
            // every attempt completes null silently, so a /rtp that never finds a
            // safe spot looks identical to a hang. attemptOutcomes is populated by
            // recordOutcome regardless of verbose, so aggregate it into a per-cause
            // tally that proves WHICH stage rejected every candidate.
            java.util.Map<String, Integer> causeTally = new java.util.LinkedHashMap<>();
            for (String outcome : state.attemptOutcomes) {
                // breadcrumb shape: "attempt=<n> outcome=<cause>/<detail>"
                String cause = outcome;
                int oc = outcome.indexOf("outcome=");
                if (oc >= 0) cause = outcome.substring(oc + "outcome=".length());
                int slash = cause.indexOf('/');
                int space = cause.indexOf(' ');
                int cut = cause.length();
                if (slash >= 0) cut = Math.min(cut, slash);
                if (space >= 0) cut = Math.min(cut, space);
                cause = cause.substring(0, cut);
                causeTally.merge(cause, 1, Integer::sum);
            }
            RTP.log(Level.INFO,
                    "[RTP][PROMOTE_DIAG] [" + state.region.name + "] location generation FAILED after "
                            + state.maxAttempts + " attempts (every candidate rejected). "
                            + "Per-cause tally=" + causeTally
                            + " (enable verbose for per-location detail). A dominant"
                            + " safety/nullChunk/ungenerated cause here is the same chunk-read"
                            + " failure that leaves the hot kept cache empty.");
        }
        result.complete(new GenerationResult(null, reported, null));
    }
}
