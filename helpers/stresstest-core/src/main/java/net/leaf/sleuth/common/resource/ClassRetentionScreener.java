package net.leaf.sleuth.common.resource;

import io.github.dailystruggle.helpers.stresstestrtp.LeakProbe;
import net.leaf.sleuth.common.fuzz.JarClassOwnershipIndex;

import javax.management.JMException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Post-GC per-class object retention screener wrapping {@link LeakProbe} ({@code gcClassHistogram})
 * and applying Bonferroni-corrected ordinary least squares (OLS) regression across checkpoints
 * ({@code ADR-125}, {@code leafsleuth-ADR-006}).
 *
 * <p>Screens every class in the post-GC histogram against player departures ({@code "departure"}),
 * command executions ({@code "command"}), or checkpoint index ({@code "checkpoint"}) at
 * family-wise significance level {@code alpha = 0.05 / K} across {@code K} candidate classes,
 * attributing leaking classes to their unique owning plugin {@code .jar} via
 * {@link JarClassOwnershipIndex}.
 *
 * @governedBy ADR-115
 * @governedBy ADR-122
 * @governedBy ADR-125
 * @governedBy ADR-129
 * @governedBy leafsleuth-ADR-005
 * @governedBy leafsleuth-ADR-006
 * @trace REQ-SLEUTH-FUZZ-002
 * @trace REQ-SLEUTH-RES-001
 * @trace S-005
 */
public final class ClassRetentionScreener {

    /** Default family-wise significance level {@code alpha = 0.05} before Bonferroni division by {@code K}. */
    public static final double DEFAULT_FAMILY_WISE_ALPHA = 0.05;

    /** Minimum checkpoints required to fit an OLS slope with positive degrees of freedom. */
    public static final int MIN_CHECKPOINTS = 3;

    private static final double MIN_R_SQUARED = 0.55;
    private static final double MIN_SLOPE_PER_EVENT = 0.05;
    private static final double RESIDUAL_SD_FLOOR = 1e-4;
    private static final int MAX_CHECKPOINT_HISTORY = 64;

    /**
     * Immutable post-GC class histogram checkpoint.
     *
     * @param label                checkpoint label
     * @param timestampMillis      wall-clock capture timestamp in milliseconds
     * @param cumulativeDepartures cumulative player departures at capture time
     * @param cumulativeCommands   cumulative command executions at capture time
     * @param oldGenAfterGcMb      post-GC old-generation heap occupancy in MB
     * @param classCounts          parsed per-class instance and byte counts
     */
    public record HistogramCheckpoint(
            String label,
            long timestampMillis,
            long cumulativeDepartures,
            long cumulativeCommands,
            double oldGenAfterGcMb,
            List<LeakProbe.ClassCount> classCounts
    ) {
        public HistogramCheckpoint {
            label = (label == null || label.isBlank()) ? "checkpoint" : label.trim();
            cumulativeDepartures = Math.max(0L, cumulativeDepartures);
            cumulativeCommands = Math.max(0L, cumulativeCommands);
            classCounts = classCounts == null ? List.of() : List.copyOf(classCounts);
        }
    }

    private volatile JarClassOwnershipIndex ownershipIndex;
    private final List<HistogramCheckpoint> checkpoints = new ArrayList<>();
    private final AtomicInteger recordedCheckpointCount = new AtomicInteger();
    private final AtomicLong totalDepartures = new AtomicLong();
    private final AtomicLong totalCommands = new AtomicLong();
    private final AtomicBoolean snapshotBusy = new AtomicBoolean(false);
    private volatile List<ClassRetentionFinding> latestFindings = List.of();

    public ClassRetentionScreener() {
        this(JarClassOwnershipIndex.empty());
    }

    public ClassRetentionScreener(JarClassOwnershipIndex ownershipIndex) {
        this.ownershipIndex = Objects.requireNonNull(ownershipIndex, "ownershipIndex");
    }

    /**
     * Updates the {@link JarClassOwnershipIndex} used to attribute leaking classes to plugin JARs
     * and refreshes JAR ownership labels on any cached {@link #latestFindings()}.
     *
     * @param ownershipIndex non-null ownership index
     */
    public void setOwnershipIndex(JarClassOwnershipIndex ownershipIndex) {
        JarClassOwnershipIndex idx = Objects.requireNonNull(ownershipIndex, "ownershipIndex");
        this.ownershipIndex = idx;
        List<ClassRetentionFinding> current = this.latestFindings;
        if (!current.isEmpty()) {
            List<ClassRetentionFinding> reattributed = new ArrayList<>(current.size());
            for (ClassRetentionFinding f : current) {
                reattributed.add(new ClassRetentionFinding(
                        f.className(),
                        resolveOwningJarLabel(idx, f.className()),
                        f.slopePerEvent(),
                        f.rSquared(),
                        f.pValue(),
                        f.baselineInstances(),
                        f.peakInstances(),
                        f.baselineBytes(),
                        f.peakBytes(),
                        f.eventBasis()
                ));
            }
            sortFindings(reattributed);
            this.latestFindings = List.copyOf(reattributed);
        }
    }

    /**
     * Returns the current {@link JarClassOwnershipIndex}.
     *
     * @return current JAR class ownership index
     */
    public JarClassOwnershipIndex ownershipIndex() {
        return ownershipIndex;
    }

    /**
     * Records a player quit/kick departure event for per-departure retention regression.
     *
     * @return updated cumulative departure count
     */
    public long recordPlayerDeparture() {
        return totalDepartures.incrementAndGet();
    }

    /**
     * Records a command execution event for per-command retention regression.
     *
     * @return updated cumulative command count
     */
    public long recordCommandExecution() {
        return totalCommands.incrementAndGet();
    }

    /**
     * Returns cumulative recorded player departures.
     *
     * @return total player departures
     */
    public long totalDepartures() {
        return totalDepartures.get();
    }

    /**
     * Returns cumulative recorded command executions.
     *
     * @return total command executions
     */
    public long totalCommands() {
        return totalCommands.get();
    }

    /**
     * Returns {@code true} if a live {@code gcClassHistogram} snapshot is currently in progress.
     *
     * @return whether a diagnostic snapshot is running
     */
    public boolean isSnapshotBusy() {
        return snapshotBusy.get();
    }

    /**
     * Returns {@code true} if {@code nowNanos} falls within the diagnostic GC pause exclusion window
     * ({@code +/- 500 ms} around {@link LeakProbe#captureHistogram()}).
     *
     * @param nowNanos monotonic timestamp in nanoseconds
     * @return whether {@code nowNanos} is inside a diagnostic GC pause window
     */
    public boolean inGcPauseWindow(long nowNanos) {
        return LeakProbe.inGcPauseWindow(nowNanos);
    }

    /**
     * Captures a live post-GC class histogram via {@link LeakProbe#captureHistogram()} using the
     * screener's current departure and command counters. Must be called off the main tick thread.
     *
     * @param label checkpoint label
     * @return recorded histogram checkpoint
     * @throws JMException if the JVM diagnostic MBean invocation fails
     */
    public HistogramCheckpoint captureLiveSnapshot(String label) throws JMException {
        return captureLiveSnapshot(label, totalDepartures.get(), totalCommands.get());
    }

    /**
     * Captures a live post-GC class histogram via {@link LeakProbe#captureHistogram()} with explicit
     * departure and command counters. Must be called off the main tick thread.
     *
     * @param label                checkpoint label
     * @param cumulativeDepartures cumulative player departures
     * @param cumulativeCommands   cumulative command executions
     * @return recorded histogram checkpoint
     * @throws JMException if the JVM diagnostic MBean invocation fails
     */
    public HistogramCheckpoint captureLiveSnapshot(
            String label,
            long cumulativeDepartures,
            long cumulativeCommands
    ) throws JMException {
        if (!snapshotBusy.compareAndSet(false, true)) {
            throw new IllegalStateException("A diagnostic gcClassHistogram snapshot is already in progress");
        }
        try {
            long nowMs = System.currentTimeMillis();
            String rawHisto = LeakProbe.captureHistogram();
            double oldGenMb = LeakProbe.oldGenAfterGcMb();
            List<LeakProbe.ClassCount> parsed = LeakProbe.parseHistogram(rawHisto);
            HistogramCheckpoint cp = new HistogramCheckpoint(
                    label,
                    nowMs,
                    cumulativeDepartures,
                    cumulativeCommands,
                    oldGenMb,
                    parsed
            );
            recordCheckpoint(cp);
            return cp;
        } finally {
            snapshotBusy.set(false);
        }
    }

    /**
     * Parses raw {@code GC.class_histogram} text and records a checkpoint.
     *
     * @param label                checkpoint label
     * @param timestampMillis      capture timestamp in milliseconds
     * @param cumulativeDepartures cumulative player departures
     * @param cumulativeCommands   cumulative command executions
     * @param rawHistogram         raw output of {@code GC.class_histogram}
     * @return recorded checkpoint
     */
    public HistogramCheckpoint recordHistogramText(
            String label,
            long timestampMillis,
            long cumulativeDepartures,
            long cumulativeCommands,
            String rawHistogram
    ) {
        List<LeakProbe.ClassCount> parsed = LeakProbe.parseHistogram(rawHistogram);
        HistogramCheckpoint cp = new HistogramCheckpoint(
                label,
                timestampMillis,
                cumulativeDepartures,
                cumulativeCommands,
                -1.0,
                parsed
        );
        recordCheckpoint(cp);
        return cp;
    }

    /**
     * Records a pre-parsed {@link HistogramCheckpoint} and refreshes cached findings when
     * at least {@link #MIN_CHECKPOINTS} checkpoints are available.
     *
     * @param checkpoint non-null checkpoint
     */
    public synchronized void recordCheckpoint(HistogramCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (checkpoints.size() >= MAX_CHECKPOINT_HISTORY) {
            checkpoints.remove(0);
        }
        checkpoints.add(checkpoint);
        recordedCheckpointCount.set(checkpoints.size());
        totalDepartures.updateAndGet(cur -> Math.max(cur, checkpoint.cumulativeDepartures()));
        totalCommands.updateAndGet(cur -> Math.max(cur, checkpoint.cumulativeCommands()));
        if (checkpoints.size() >= MIN_CHECKPOINTS) {
            this.latestFindings = screenClassGrowth();
        }
    }

    /**
     * Returns an immutable copy of all recorded checkpoints.
     *
     * @return recorded checkpoints
     */
    public synchronized List<HistogramCheckpoint> checkpoints() {
        return List.copyOf(checkpoints);
    }

    /**
     * Returns the number of recorded checkpoints without acquiring the screener monitor.
     *
     * @return checkpoint count
     */
    public int checkpointCount() {
        return recordedCheckpointCount.get();
    }

    /**
     * Returns the most recently screened findings.
     *
     * @return immutable list of findings
     */
    public List<ClassRetentionFinding> latestFindings() {
        return latestFindings;
    }

    /**
     * Screens post-GC class growth against player departures ({@code "departure"} basis)
     * using Bonferroni-corrected OLS regression ({@code alpha = 0.05 / K}).
     *
     * @return ranked list of significant per-departure class retention findings
     */
    public synchronized List<ClassRetentionFinding> screenDepartureRetention() {
        return screenByEventBasis("departure", DEFAULT_FAMILY_WISE_ALPHA);
    }

    /**
     * Screens post-GC class growth against command executions ({@code "command"} basis)
     * using Bonferroni-corrected OLS regression ({@code alpha = 0.05 / K}).
     *
     * @return ranked list of significant per-command class retention findings
     */
    public synchronized List<ClassRetentionFinding> screenCommandRetention() {
        return screenByEventBasis("command", DEFAULT_FAMILY_WISE_ALPHA);
    }

    /**
     * Selects the active independent variable basis once by observed event progression across
     * checkpoints ({@code "departure"} if departures progressed, else {@code "command"} if
     * commands progressed, else {@code "checkpoint"}) without multi-basis fallback testing.
     *
     * @return selected event basis
     */
    public synchronized String selectActiveEventBasis() {
        if (hasVariance("departure")) {
            return "departure";
        }
        if (hasVariance("command")) {
            return "command";
        }
        return "checkpoint";
    }

    /**
     * Automatically selects the single active independent variable basis by event progression
     * ({@link #selectActiveEventBasis()}) and runs Bonferroni-corrected OLS screening once so
     * family-wise significance {@code alpha = 0.05} is never inflated by fallback re-testing.
     *
     * @return ranked list of significant class retention findings
     */
    public synchronized List<ClassRetentionFinding> screenClassGrowth() {
        if (checkpoints.size() < MIN_CHECKPOINTS) {
            return List.of();
        }
        String basis = selectActiveEventBasis();
        List<ClassRetentionFinding> findings = screenByEventBasis(basis, DEFAULT_FAMILY_WISE_ALPHA);
        this.latestFindings = findings;
        return findings;
    }

    /**
     * Screens post-GC class growth against the specified {@code eventBasis}
     * ({@code "departure"}, {@code "command"}, or {@code "checkpoint"}) with Bonferroni
     * family-wise significance threshold {@code familyWiseAlpha / K}.
     *
     * @param eventBasis      independent variable basis
     * @param familyWiseAlpha family-wise Type I error rate (e.g. {@code 0.05})
     * @return immutable ranked list of statistically significant findings
     */
    public synchronized List<ClassRetentionFinding> screenByEventBasis(
            String eventBasis,
            double familyWiseAlpha
    ) {
        int n = checkpoints.size();
        if (n < MIN_CHECKPOINTS) {
            return List.of();
        }
        String basis = (eventBasis == null || eventBasis.isBlank()) ? "checkpoint" : eventBasis.trim().toLowerCase();
        double[] x = new double[n];
        double sumX = 0.0;
        for (int i = 0; i < n; i++) {
            HistogramCheckpoint cp = checkpoints.get(i);
            x[i] = switch (basis) {
                case "departure" -> (double) cp.cumulativeDepartures();
                case "command" -> (double) cp.cumulativeCommands();
                default -> (double) i;
            };
            sumX += x[i];
        }
        double meanX = sumX / n;
        double sxx = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX;
            sxx += dx * dx;
        }
        if (sxx <= 1e-9) {
            return List.of();
        }

        // Collect per-checkpoint aggregated (instances, bytes) per normalized class name
        Set<String> allClasses = new LinkedHashSet<>();
        List<Map<String, long[]>> perCheckpointCounts = new ArrayList<>(n);
        for (HistogramCheckpoint cp : checkpoints) {
            Map<String, long[]> map = new LinkedHashMap<>();
            for (LeakProbe.ClassCount cc : cp.classCounts()) {
                if (cc == null || cc.name() == null || cc.name().contains("jdk.internal.vm.Filler")) {
                    continue;
                }
                String norm = LeakProbe.histogramClass(cc.name());
                if (norm == null || norm.isEmpty()) {
                    norm = cc.name().trim();
                }
                if (norm.startsWith("net.leaf.sleuth.common.resource.ClassRetentionScreener")
                        || norm.startsWith("io.github.dailystruggle.helpers.stresstestrtp.LeakProbe")
                        || norm.startsWith("net.leaf.fuzz.")) {
                    continue;
                }
                allClasses.add(norm);
                long[] acc = map.computeIfAbsent(norm, k -> new long[]{0L, 0L});
                acc[0] += cc.instances();
                acc[1] += cc.bytes();
            }
            perCheckpointCounts.add(map);
        }

        int growingCandidates = 0;
        for (String cls : allClasses) {
            long firstPairInst = 0L;
            long lastPairInst = 0L;
            long peakPairInst = 0L;
            for (int i = 0; i < n; i++) {
                long[] pair = perCheckpointCounts.get(i).get(cls);
                long inst = pair != null ? pair[0] : 0L;
                if (i == 0) {
                    firstPairInst = inst;
                }
                if (i == n - 1) {
                    lastPairInst = inst;
                }
                peakPairInst = Math.max(peakPairInst, inst);
            }
            if (lastPairInst > firstPairInst && (peakPairInst - firstPairInst) >= 2L) {
                growingCandidates++;
            }
        }
        int kClasses = Math.max(1, Math.max(Math.min(allClasses.size(), 50), growingCandidates));
        double effectiveAlpha = Math.max(1e-12, familyWiseAlpha) / kClasses;
        JarClassOwnershipIndex idx = this.ownershipIndex;

        List<ClassRetentionFinding> findings = new ArrayList<>();
        int dof = Math.max(1, n - 2);

        for (String cls : allClasses) {
            double[] y = new double[n];
            long baselineInst = 0L;
            long peakInst = 0L;
            long baselineBytes = 0L;
            long peakBytes = 0L;
            double sumY = 0.0;

            for (int i = 0; i < n; i++) {
                long[] pair = perCheckpointCounts.get(i).get(cls);
                long inst = pair != null ? pair[0] : 0L;
                long bytes = pair != null ? pair[1] : 0L;
                if (i == 0) {
                    baselineInst = inst;
                    baselineBytes = bytes;
                }
                peakInst = Math.max(peakInst, inst);
                peakBytes = Math.max(peakBytes, bytes);
                y[i] = (double) inst;
                sumY += y[i];
            }

            // Require positive net growth from first to last checkpoint
            if (y[n - 1] <= y[0] || (peakInst - baselineInst) < 2L) {
                continue;
            }

            // If checkpoint 0 -> 1 was an idle control window (zero commands and zero departures)
            // preceding active departure/command batches, exclude classes whose growth rate while
            // idle matches their active growth rate (background timers).
            if (!"checkpoint".equals(basis)
                    && n >= 3
                    && checkpoints.get(1).cumulativeDepartures() == checkpoints.get(0).cumulativeDepartures()
                    && checkpoints.get(1).cumulativeCommands() == checkpoints.get(0).cumulativeCommands()) {
                double idleGrowth = y[1] - y[0];
                double meanStepGrowth = (y[n - 1] - y[0]) / (double) (n - 1);
                if (idleGrowth > 0.0 && idleGrowth >= 0.35 * meanStepGrowth) {
                    continue;
                }
            }

            double meanY = sumY / n;
            double sxy = 0.0;
            double syy = 0.0;
            for (int i = 0; i < n; i++) {
                double dx = x[i] - meanX;
                double dy = y[i] - meanY;
                sxy += dx * dy;
                syy += dy * dy;
            }
            if (sxy <= 0.0 || syy <= 1e-9) {
                continue;
            }

            double slope = sxy / sxx;
            if (slope < MIN_SLOPE_PER_EVENT) {
                continue;
            }
            double rSquared = Math.max(0.0, Math.min(1.0, (sxy * sxy) / (sxx * syy)));
            if (rSquared < MIN_R_SQUARED) {
                continue;
            }

            double rss = Math.max(0.0, syy - slope * sxy);
            double mse = Math.max(RESIDUAL_SD_FLOOR * RESIDUAL_SD_FLOOR, rss / dof);
            double seSlope = Math.sqrt(mse / sxx);
            double tStat = slope / seSlope;
            double pValue = oneSidedStudentTPValue(tStat, dof);

            if (pValue <= effectiveAlpha) {
                String owningJar = resolveOwningJarLabel(idx, cls);
                findings.add(new ClassRetentionFinding(
                        cls,
                        owningJar,
                        slope,
                        rSquared,
                        pValue,
                        baselineInst,
                        peakInst,
                        baselineBytes,
                        peakBytes,
                        basis
                ));
            }
        }

        sortFindings(findings);
        return List.copyOf(findings);
    }

    private static void sortFindings(List<ClassRetentionFinding> findings) {
        findings.sort(Comparator
                .comparing(ClassRetentionFinding::isPluginOwned).reversed()
                .thenComparingDouble(ClassRetentionFinding::pValue)
                .thenComparing(Comparator.comparingDouble(ClassRetentionFinding::slopePerEvent).reversed())
                .thenComparing(ClassRetentionFinding::className));
    }

    /**
     * Resets all recorded checkpoints and counters.
     */
    public synchronized void reset() {
        checkpoints.clear();
        recordedCheckpointCount.set(0);
        totalDepartures.set(0L);
        totalCommands.set(0L);
        latestFindings = List.of();
        LeakProbe.clearGcPauseWindow();
    }

    private boolean hasVariance(String basis) {
        if (checkpoints.size() < 2) {
            return false;
        }
        long first = "departure".equals(basis)
                ? checkpoints.get(0).cumulativeDepartures()
                : checkpoints.get(0).cumulativeCommands();
        long last = "departure".equals(basis)
                ? checkpoints.get(checkpoints.size() - 1).cumulativeDepartures()
                : checkpoints.get(checkpoints.size() - 1).cumulativeCommands();
        return last > first;
    }

    private static String resolveOwningJarLabel(JarClassOwnershipIndex idx, String className) {
        if (idx == null) {
            return "server/jdk";
        }
        Optional<String> uniqueOwner = idx.ownerJarForClass(className);
        if (uniqueOwner.isPresent()) {
            return uniqueOwner.get();
        }
        if (idx.sharedClasses().contains(className)) {
            return "shared";
        }
        return "server/jdk";
    }

    /**
     * Computes the exact one-sided upper tail probability {@code P(T_dof >= t)} for Student's
     * t-distribution with {@code dof >= 1} degrees of freedom.
     */
    static double oneSidedStudentTPValue(double t, int dof) {
        if (!Double.isFinite(t) || t <= 0.0) {
            return 0.5;
        }
        if (dof <= 0) {
            return 0.5;
        }
        // Exact recurrence over integer degrees of freedom (Abramowitz & Stegun 26.7.3 / 26.7.4)
        double x = t / Math.sqrt(dof);
        double theta = Math.atan(x);
        double sinTheta = Math.sin(theta);
        double cosTheta = Math.cos(theta);
        double twoSidedCdfDiff;
        if (dof % 2 == 1) {
            double sum = 0.0;
            if (dof > 1) {
                double term = sinTheta * cosTheta;
                sum = term;
                for (int k = 3; k <= dof - 2; k += 2) {
                    term *= ((double) (k - 1) / k) * cosTheta * cosTheta;
                    sum += term;
                }
            }
            twoSidedCdfDiff = (2.0 / Math.PI) * (theta + sum);
        } else {
            double term = sinTheta;
            double sum = term;
            for (int k = 2; k <= dof - 2; k += 2) {
                term *= ((double) (k - 1) / k) * cosTheta * cosTheta;
                sum += term;
            }
            twoSidedCdfDiff = sum;
        }
        double oneSided = 0.5 * Math.max(0.0, 1.0 - twoSidedCdfDiff);
        if (oneSided <= 0.0 && t > 0.0) {
            // Asymptotic upper-tail bound when 1 - twoSidedCdfDiff underflows double precision near 1.0
            double logTail = -0.5 * dof * Math.log(1.0 + (t * t) / dof) - Math.log(t);
            return Math.max(1e-300, Math.exp(Math.max(-690.0, logTail)));
        }
        return oneSided;
    }
}
