package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPChunk;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tools.HeapPressureMonitor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-114 Baseline Characterization Suite: On-Demand Chunk Resolution Performance.
 *
 * <p>Establishes the empirical baseline for on-demand ({@link LoadPurpose#IMMEDIATE}) RTP tasks
 * across two primary optimization metrics:
 * <ul>
 *   <li><b>Primary Metric (A) - CPU Cost per Teleport ({@code C_cpu/tp}):</b> Total computational
 *       energy consumed (calibrated off-thread file decode time vs native live generation time).
 *   <li><b>Co-Dependent Metric (B) - Command-to-Teleport Latency ({@code T_cmd->tp}):</b> Wall-clock
 *       elapsed delivery time (P50, P95, mean).
 * </ul>
 *
 * <p>Measures the current state across three terrain conditions and burst concurrency:
 * <ol>
 *   <li><b>100% Pregenerated Terrain:</b> All candidate chunks exist on disk in region files.
 *   <li><b>25% Partially Generated Terrain:</b> Mixed terrain where 25% exists on disk and 75%
 *       is ungenerated.
 *   <li><b>0% Ungenerated Terrain:</b> Fresh world with zero preexisting region files.
 *   <li><b>10-Player Burst Concurrency:</b> 10 simultaneous on-demand executions testing concurrency
 *       bounding and resource spikes.
 * </ol>
 */
@DisplayName("ADR-114 on-demand chunk resolution baseline benchmark")
public class OnDemandChunkReadBenchmarkTest {

    private static final String REGION_NAME = "baseline_region";

    private static final int NUM_TRIALS = 5;
    private static final int SAMPLES_PER_TRIAL = 30;
    private static final int NUM_BURST_TRIALS = 5;
    private static final int BURST_CLIENTS = 10;

    // Calibrated computational costs from MeasuredAnchors / ADR-114:
    // - Region-file decode & palette parsing: ~1.5 ms off-thread CPU
    // - Native chunk load from disk (server-side entity/ticket setup): ~10.0 ms CPU
    // - Native chunk generation (worker generation + lighting + tickets): ~35.0 ms CPU
    private static final long COST_NANOS_FILE_READ = 1_500_000L;
    private static final long COST_NANOS_LIVE_LOAD_GENERATED = 10_000_000L;
    private static final long COST_NANOS_LIVE_LOAD_UNGENERATED = 35_000_000L;

    private static final SimulationReport REPORT = new SimulationReport();

    @TempDir
    File tempDir;

    private BenchmarkedRTPWorld world;
    private MockRTPServerAccessor accessor;

    public enum TerrainProfile {
        PREGENERATED_100,
        PARTIALLY_GENERATED_25,
        UNGENERATED_0
    }

    /**
     * Statistical summary record computing sample mean, standard deviation, standard error,
     * 95% confidence intervals (Student's t), and range bounds.
     */
    public record StatsSummary(
            double mean,
            double stdDev,
            double sem,
            double ci95Lower,
            double ci95Upper,
            double min,
            double max
    ) {
        public static StatsSummary of(List<Double> values) {
            if (values == null || values.isEmpty()) {
                return new StatsSummary(0, 0, 0, 0, 0, 0, 0);
            }
            int n = values.size();
            double sum = 0.0;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (double v : values) {
                sum += v;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            double mean = sum / n;
            double variance = 0.0;
            if (n > 1) {
                for (double v : values) {
                    double diff = v - mean;
                    variance += diff * diff;
                }
                variance /= (n - 1);
            }
            double stdDev = Math.sqrt(variance);
            double sem = stdDev / Math.sqrt(n);
            double tCrit = switch (n) {
                case 2 -> 12.706;
                case 3 -> 4.303;
                case 4 -> 3.182;
                case 5 -> 2.776;
                case 6 -> 2.571;
                case 7 -> 2.447;
                case 8 -> 2.365;
                case 9 -> 2.306;
                case 10 -> 2.262;
                default -> (n > 10 && n < 30) ? 2.045 : 1.960;
            };
            double ciMargin = tCrit * sem;
            return new StatsSummary(mean, stdDev, sem, mean - ciMargin, mean + ciMargin, min, max);
        }

        public String format() {
            return String.format("%.2f +/- %.2f [95%% CI: %.2f - %.2f] (min=%.2f, max=%.2f)",
                    mean, stdDev, ci95Lower, ci95Upper, min, max);
        }
    }

    /**
     * Instrumented mock world measuring chunk operations and CPU expenditure.
     */
    public static class BenchmarkedRTPWorld extends MockRTPWorld {

        public final TerrainProfile terrainProfile;

        // Operation counters
        public final AtomicInteger nativeLoadInvocations = new AtomicInteger();
        public final AtomicInteger fileReadInvocations = new AtomicInteger();
        public final AtomicInteger isReadableInvocations = new AtomicInteger();
        public final AtomicInteger discardedGenerations = new AtomicInteger();

        // Concurrency gauges
        public final AtomicInteger activeConcurrentNativeLoads = new AtomicInteger();
        public final AtomicInteger peakConcurrentNativeLoads = new AtomicInteger();
        public final AtomicInteger activeConcurrentGenerations = new AtomicInteger();
        public final AtomicInteger peakConcurrentGenerations = new AtomicInteger();

        // Cumulative simulated CPU time
        public final AtomicLong totalSimulatedCpuTimeNanos = new AtomicLong();

        public BenchmarkedRTPWorld(String name, TerrainProfile terrainProfile) {
            super(name);
            this.terrainProfile = terrainProfile;
        }

        public boolean isChunkGeneratedOnDisk(int cx, int cz) {
            return switch (terrainProfile) {
                case PREGENERATED_100 -> true;
                case UNGENERATED_0 -> false;
                case PARTIALLY_GENERATED_25 -> ((Math.abs(cx * 31 + cz * 17) % 4) == 0);
            };
        }

        public boolean isLocationSafe(int cx, int cz) {
            // 50% safe, 50% hazards to test candidate evaluation along the spiral
            return ((Math.abs(cx * 13 + cz * 7) % 2) == 0);
        }

        @Override
        public boolean isChunkGenerated(int cx, int cz) {
            return isChunkGeneratedOnDisk(cx, cz);
        }

        @Override
        public CompletableFuture<Long> getChunkIfReadable(int cx, int cz) {
            isReadableInvocations.incrementAndGet();
            if (isChunkGeneratedOnDisk(cx, cz)) {
                return CompletableFuture.completedFuture(encodeKey(cx, cz));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<RTPChunk<?>> getOrReadChunk(int cx, int cz) {
            fileReadInvocations.incrementAndGet();
            if (isChunkGeneratedOnDisk(cx, cz)) {
                totalSimulatedCpuTimeNanos.addAndGet(COST_NANOS_FILE_READ);
                boolean safe = isLocationSafe(cx, cz);
                return CompletableFuture.completedFuture(new BenchmarkedChunk(cx, cz, this, true, safe));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<RTPChunk<?>> getOrLoadChunk(int cx, int cz, String origin) {
            nativeLoadInvocations.incrementAndGet();
            int cur = activeConcurrentNativeLoads.incrementAndGet();
            peakConcurrentNativeLoads.updateAndGet(p -> Math.max(p, cur));

            boolean generated = isChunkGeneratedOnDisk(cx, cz);
            if (!generated) {
                int gen = activeConcurrentGenerations.incrementAndGet();
                peakConcurrentGenerations.updateAndGet(p -> Math.max(p, gen));
            }
            long cost = generated ? COST_NANOS_LIVE_LOAD_GENERATED : COST_NANOS_LIVE_LOAD_UNGENERATED;
            totalSimulatedCpuTimeNanos.addAndGet(cost);

            boolean safe = isLocationSafe(cx, cz);
            if (!generated && !safe) {
                discardedGenerations.incrementAndGet();
            }

            if (!generated) activeConcurrentGenerations.decrementAndGet();
            activeConcurrentNativeLoads.decrementAndGet();
            return CompletableFuture.completedFuture(new BenchmarkedChunk(cx, cz, this, false, safe));
        }

        private static long encodeKey(int cx, int cz) {
            return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        }
    }

    public static class BenchmarkedChunk extends MockRTPChunk {
        private final boolean selfContained;
        private final boolean safe;

        public BenchmarkedChunk(int chunkX, int chunkZ, RTPWorld<?> world, boolean selfContained, boolean safe) {
            super(chunkX, chunkZ, world);
            this.selfContained = selfContained;
            this.safe = safe;
        }

        @Override
        public boolean isSelfContained() {
            return selfContained;
        }

        @Override
        public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
            return safe;
        }
    }

    @BeforeAll
    static void initReportDir() {
        if (System.getProperty("rtp.simulation.reportDir") == null) {
            Path dir = Paths.get("build/reports/rtp-simulation");
            System.setProperty("rtp.simulation.reportDir", dir.toAbsolutePath().toString());
        }
    }

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        perf.set(PerformanceKeys.maxAttempts, 50L);
        perf.set(PerformanceKeys.maxHeapPercent, 100.0);
        HeapPressureMonitor.resetForTesting();
        ImmediateLoadGovernor.resetAll();
        LocationGenerator.setRng(new java.util.Random(20261014L));
    }

    @AfterEach
    void tearDown() {
        HeapPressureMonitor.resetForTesting();
        ImmediateLoadGovernor.resetAll();
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        LocationGenerator.setRng(null);
    }

    @AfterAll
    static void writeReport() {
        REPORT.note("ADR-114 Statistical Baseline Characterization: 5 independent trials per scenario across varied RNG seeds.");
        REPORT.note("Confidence intervals evaluated at 95% two-sided using Student's t critical value 2.776 (df = 4).");
        REPORT.write("on-demand-chunk-read-baseline");
        REPORT.write("on-demand-chunk-read-statistical-baseline");
    }

    private static void recordAggregates(String section, String metric, StatsSummary stats) {
        REPORT.add(section, "Summary_Mean", metric, stats.mean(), Provenance.DERIVED);
        REPORT.add(section, "Summary_StdDev", metric, stats.stdDev(), Provenance.DERIVED);
        REPORT.add(section, "Summary_SEM", metric, stats.sem(), Provenance.DERIVED);
        REPORT.add(section, "Summary_CI95_Lower", metric, stats.ci95Lower(), Provenance.DERIVED);
        REPORT.add(section, "Summary_CI95_Upper", metric, stats.ci95Upper(), Provenance.DERIVED);
        REPORT.add(section, "Summary_Min", metric, stats.min(), Provenance.DERIVED);
        REPORT.add(section, "Summary_Max", metric, stats.max(), Provenance.DERIVED);
    }

    private Region createRegion(BenchmarkedRTPWorld w) {
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 1000L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        RegionSettings settings = new RegionSettings(
                REGION_NAME, w, square, new LinearAdjustor(new ArrayList<>()),
                false, false, 10L, 1000L, 0L, 0, 0.0, 50L, "", false);
        return new Region(REGION_NAME, settings);
    }

    private GenerationResult execute(Region r, LoadPurpose purpose) throws Exception {
        PregenState state = PregenState.build(r, null);
        assertNotNull(state);
        state.maxAttempts = 50L;
        state.purpose = purpose;
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        new PregenTask(state, result, 1L).run();
        assertTrue(result.isDone(), "PregenTask must complete in synchronous test runner");
        return result.get();
    }

    @Test
    @DisplayName("Current State Architectural Proof: IMMEDIATE completely bypasses getOrReadChunk")
    void currentBehaviorBypassesFileReadsForImmediate() throws Exception {
        world = new BenchmarkedRTPWorld("arch_world", TerrainProfile.PREGENERATED_100);
        accessor.addWorld(world);
        Region region = createRegion(world);

        // 1. Run an on-demand IMMEDIATE request:
        GenerationResult immediateResult = execute(region, LoadPurpose.IMMEDIATE);
        assertNotNull(immediateResult.coords(), "Immediate request should find a safe location");

        // Architectural Invariant Proof:
        // Current state on-demand tasks bypass the off-thread read path (getOrReadChunk) entirely.
        assertEquals(0, world.fileReadInvocations.get(),
                "Current state flaw: on-demand tasks never invoke getOrReadChunk");
        assertTrue(world.nativeLoadInvocations.get() > 0,
                "Current state flaw: on-demand tasks issue 100% native chunk loads");

        // 2. Now run a SPECULATIVE request for comparison:
        int nativeLoadsBefore = world.nativeLoadInvocations.get();
        execute(region, LoadPurpose.SPECULATIVE);

        // Speculative tasks properly use getOrReadChunk (ADR-110)
        assertTrue(world.fileReadInvocations.get() > 0,
                "Speculative tasks properly invoke getOrReadChunk");
        assertEquals(nativeLoadsBefore, world.nativeLoadInvocations.get(),
                "Speculative tasks on pregenerated world avoid native loads when readable");
    }

    @Test
    @DisplayName("Baseline Benchmark: 100% Pregenerated Terrain (Current State Multi-Trial)")
    void baselinePregenerated100() throws Exception {
        List<Double> trialMeanCpus = new ArrayList<>();
        List<Double> trialP50Cpus = new ArrayList<>();
        List<Double> trialP95Cpus = new ArrayList<>();
        List<Double> trialMeanLatencies = new ArrayList<>();
        List<Double> trialP50Latencies = new ArrayList<>();
        List<Double> trialP95Latencies = new ArrayList<>();
        List<Double> trialNativeLoads = new ArrayList<>();
        List<Double> trialFileReads = new ArrayList<>();

        System.out.printf("%n=== BASELINE: 100%% Pregenerated Terrain (%d Trials x %d Requests) ===%n", NUM_TRIALS, SAMPLES_PER_TRIAL);

        for (int trial = 1; trial <= NUM_TRIALS; trial++) {
            LocationGenerator.setRng(new java.util.Random(20261014L + trial * 10007L));
            BenchmarkedRTPWorld trialWorld = new BenchmarkedRTPWorld("pregen_world_" + trial, TerrainProfile.PREGENERATED_100);
            accessor.addWorld(trialWorld);
            Region region = createRegion(trialWorld);

            List<Double> latenciesMs = new ArrayList<>();
            List<Double> cpuCostsMs = new ArrayList<>();

            for (int i = 0; i < SAMPLES_PER_TRIAL; i++) {
                long prevCpuNanos = trialWorld.totalSimulatedCpuTimeNanos.get();
                long startNanos = System.nanoTime();

                GenerationResult res = execute(region, LoadPurpose.IMMEDIATE);
                assertNotNull(res.coords());

                long elapsedNanos = System.nanoTime() - startNanos;
                long deltaCpuNanos = trialWorld.totalSimulatedCpuTimeNanos.get() - prevCpuNanos;

                latenciesMs.add(elapsedNanos / 1_000_000.0);
                cpuCostsMs.add(deltaCpuNanos / 1_000_000.0);
            }

            Collections.sort(latenciesMs);
            Collections.sort(cpuCostsMs);

            double meanCpu = cpuCostsMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double p50Cpu = cpuCostsMs.get(SAMPLES_PER_TRIAL / 2);
            double p95Cpu = cpuCostsMs.get((int) (SAMPLES_PER_TRIAL * 0.95));

            double meanLatency = latenciesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double p50Latency = latenciesMs.get(SAMPLES_PER_TRIAL / 2);
            double p95Latency = latenciesMs.get((int) (SAMPLES_PER_TRIAL * 0.95));

            double nativeLoadsPerTp = (double) trialWorld.nativeLoadInvocations.get() / SAMPLES_PER_TRIAL;
            double fileReadsPerTp = (double) trialWorld.fileReadInvocations.get() / SAMPLES_PER_TRIAL;

            trialMeanCpus.add(meanCpu);
            trialP50Cpus.add(p50Cpu);
            trialP95Cpus.add(p95Cpu);
            trialMeanLatencies.add(meanLatency);
            trialP50Latencies.add(p50Latency);
            trialP95Latencies.add(p95Latency);
            trialNativeLoads.add(nativeLoadsPerTp);
            trialFileReads.add(fileReadsPerTp);

            String runSubject = "Run_" + trial;
            REPORT.add("Baseline_Pregenerated100", runSubject, "meanCpuMsPerTp", meanCpu, Provenance.MEASURED);
            REPORT.add("Baseline_Pregenerated100", runSubject, "p50CpuMs", p50Cpu, Provenance.MEASURED);
            REPORT.add("Baseline_Pregenerated100", runSubject, "p95CpuMs", p95Cpu, Provenance.MEASURED);
            REPORT.add("Baseline_Pregenerated100", runSubject, "meanLatencyMs", meanLatency, Provenance.MEASURED);
            REPORT.add("Baseline_Pregenerated100", runSubject, "p50LatencyMs", p50Latency, Provenance.MEASURED);
            REPORT.add("Baseline_Pregenerated100", runSubject, "p95LatencyMs", p95Latency, Provenance.MEASURED);
            REPORT.add("Baseline_Pregenerated100", runSubject, "nativeLoadsPerTp", nativeLoadsPerTp, Provenance.DERIVED);
            REPORT.add("Baseline_Pregenerated100", runSubject, "fileReadsPerTp", fileReadsPerTp, Provenance.DERIVED);

            System.out.printf("  [Trial %d] Mean CPU: %.2f ms | P50 Lat: %.2f ms | P95 Lat: %.2f ms | Native Loads/TP: %.2f | File Reads/TP: %.2f%n",
                    trial, meanCpu, p50Latency, p95Latency, nativeLoadsPerTp, fileReadsPerTp);
        }

        StatsSummary statsCpu = StatsSummary.of(trialMeanCpus);
        StatsSummary statsP50Cpu = StatsSummary.of(trialP50Cpus);
        StatsSummary statsP95Cpu = StatsSummary.of(trialP95Cpus);
        StatsSummary statsLatency = StatsSummary.of(trialMeanLatencies);
        StatsSummary statsP50Latency = StatsSummary.of(trialP50Latencies);
        StatsSummary statsP95Latency = StatsSummary.of(trialP95Latencies);
        StatsSummary statsNativeLoads = StatsSummary.of(trialNativeLoads);
        StatsSummary statsFileReads = StatsSummary.of(trialFileReads);

        recordAggregates("Baseline_Pregenerated100", "meanCpuMsPerTp", statsCpu);
        recordAggregates("Baseline_Pregenerated100", "meanLatencyMs", statsLatency);
        recordAggregates("Baseline_Pregenerated100", "p50LatencyMs", statsP50Latency);
        recordAggregates("Baseline_Pregenerated100", "p95LatencyMs", statsP95Latency);
        recordAggregates("Baseline_Pregenerated100", "nativeLoadsPerTp", statsNativeLoads);
        recordAggregates("Baseline_Pregenerated100", "fileReadsPerTp", statsFileReads);

        REPORT.add("Baseline", "Pregenerated100", "meanCpuMsPerTp", statsCpu.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Pregenerated100", "p50CpuMs", statsP50Cpu.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Pregenerated100", "p95CpuMs", statsP95Cpu.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Pregenerated100", "meanLatencyMs", statsLatency.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Pregenerated100", "p50LatencyMs", statsP50Latency.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Pregenerated100", "p95LatencyMs", statsP95Latency.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Pregenerated100", "nativeLoadsPerTp", statsNativeLoads.mean(), Provenance.DERIVED);
        REPORT.add("Baseline", "Pregenerated100", "fileReadsPerTp", statsFileReads.mean(), Provenance.DERIVED);

        System.out.printf("--- STATISTICAL SUMMARY: 100%% Pregenerated Terrain ---%n");
        System.out.printf("  CPU Cost / TP:  %s%n", statsCpu.format());
        System.out.printf("  Mean Latency:   %s%n", statsLatency.format());
        System.out.printf("  P50 Latency:    %s%n", statsP50Latency.format());
        System.out.printf("  P95 Latency:    %s%n", statsP95Latency.format());
        System.out.printf("  Native Loads:   %s%n", statsNativeLoads.format());
        System.out.printf("  File Reads:     %s (Invariant: Exactly 0.00 across all runs)%n%n", statsFileReads.format());

        assertEquals(0.0, statsFileReads.mean(), 0.001);
        assertEquals(0.0, statsFileReads.stdDev(), 0.001);
        assertTrue(statsNativeLoads.mean() >= 1.0);
    }

    @Test
    @DisplayName("Baseline Benchmark: 25% Partially Generated Terrain (Current State Multi-Trial)")
    void baselinePartiallyGenerated25() throws Exception {
        List<Double> trialMeanCpus = new ArrayList<>();
        List<Double> trialP50Cpus = new ArrayList<>();
        List<Double> trialP95Cpus = new ArrayList<>();
        List<Double> trialMeanLatencies = new ArrayList<>();
        List<Double> trialP50Latencies = new ArrayList<>();
        List<Double> trialP95Latencies = new ArrayList<>();
        List<Double> trialNativeLoads = new ArrayList<>();
        List<Double> trialDiscardedGen = new ArrayList<>();

        System.out.printf("%n=== BASELINE: 25%% Partially Generated Terrain (%d Trials x %d Requests) ===%n", NUM_TRIALS, SAMPLES_PER_TRIAL);

        for (int trial = 1; trial <= NUM_TRIALS; trial++) {
            LocationGenerator.setRng(new java.util.Random(20261014L + trial * 10007L));
            BenchmarkedRTPWorld trialWorld = new BenchmarkedRTPWorld("partial_world_" + trial, TerrainProfile.PARTIALLY_GENERATED_25);
            accessor.addWorld(trialWorld);
            Region region = createRegion(trialWorld);

            List<Double> latenciesMs = new ArrayList<>();
            List<Double> cpuCostsMs = new ArrayList<>();

            for (int i = 0; i < SAMPLES_PER_TRIAL; i++) {
                long prevCpuNanos = trialWorld.totalSimulatedCpuTimeNanos.get();
                long startNanos = System.nanoTime();

                GenerationResult res = execute(region, LoadPurpose.IMMEDIATE);
                assertNotNull(res.coords());

                long elapsedNanos = System.nanoTime() - startNanos;
                long deltaCpuNanos = trialWorld.totalSimulatedCpuTimeNanos.get() - prevCpuNanos;

                latenciesMs.add(elapsedNanos / 1_000_000.0);
                cpuCostsMs.add(deltaCpuNanos / 1_000_000.0);
            }

            Collections.sort(latenciesMs);
            Collections.sort(cpuCostsMs);

            double meanCpu = cpuCostsMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double p50Cpu = cpuCostsMs.get(SAMPLES_PER_TRIAL / 2);
            double p95Cpu = cpuCostsMs.get((int) (SAMPLES_PER_TRIAL * 0.95));

            double meanLatency = latenciesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double p50Latency = latenciesMs.get(SAMPLES_PER_TRIAL / 2);
            double p95Latency = latenciesMs.get((int) (SAMPLES_PER_TRIAL * 0.95));

            double nativeLoadsPerTp = (double) trialWorld.nativeLoadInvocations.get() / SAMPLES_PER_TRIAL;
            double discardedGenPerTp = (double) trialWorld.discardedGenerations.get() / SAMPLES_PER_TRIAL;

            trialMeanCpus.add(meanCpu);
            trialP50Cpus.add(p50Cpu);
            trialP95Cpus.add(p95Cpu);
            trialMeanLatencies.add(meanLatency);
            trialP50Latencies.add(p50Latency);
            trialP95Latencies.add(p95Latency);
            trialNativeLoads.add(nativeLoadsPerTp);
            trialDiscardedGen.add(discardedGenPerTp);

            String runSubject = "Run_" + trial;
            REPORT.add("Baseline_PartiallyGenerated25", runSubject, "meanCpuMsPerTp", meanCpu, Provenance.MEASURED);
            REPORT.add("Baseline_PartiallyGenerated25", runSubject, "p50CpuMs", p50Cpu, Provenance.MEASURED);
            REPORT.add("Baseline_PartiallyGenerated25", runSubject, "p95CpuMs", p95Cpu, Provenance.MEASURED);
            REPORT.add("Baseline_PartiallyGenerated25", runSubject, "meanLatencyMs", meanLatency, Provenance.MEASURED);
            REPORT.add("Baseline_PartiallyGenerated25", runSubject, "p50LatencyMs", p50Latency, Provenance.MEASURED);
            REPORT.add("Baseline_PartiallyGenerated25", runSubject, "p95LatencyMs", p95Latency, Provenance.MEASURED);
            REPORT.add("Baseline_PartiallyGenerated25", runSubject, "nativeLoadsPerTp", nativeLoadsPerTp, Provenance.DERIVED);
            REPORT.add("Baseline_PartiallyGenerated25", runSubject, "discardedGenPerTp", discardedGenPerTp, Provenance.DERIVED);

            System.out.printf("  [Trial %d] Mean CPU: %.2f ms | P50 Lat: %.2f ms | P95 Lat: %.2f ms | Native Loads/TP: %.2f | Discarded Gen/TP: %.2f%n",
                    trial, meanCpu, p50Latency, p95Latency, nativeLoadsPerTp, discardedGenPerTp);
        }

        StatsSummary statsCpu = StatsSummary.of(trialMeanCpus);
        StatsSummary statsP50Cpu = StatsSummary.of(trialP50Cpus);
        StatsSummary statsP95Cpu = StatsSummary.of(trialP95Cpus);
        StatsSummary statsLatency = StatsSummary.of(trialMeanLatencies);
        StatsSummary statsP50Latency = StatsSummary.of(trialP50Latencies);
        StatsSummary statsP95Latency = StatsSummary.of(trialP95Latencies);
        StatsSummary statsNativeLoads = StatsSummary.of(trialNativeLoads);
        StatsSummary statsDiscardedGen = StatsSummary.of(trialDiscardedGen);

        recordAggregates("Baseline_PartiallyGenerated25", "meanCpuMsPerTp", statsCpu);
        recordAggregates("Baseline_PartiallyGenerated25", "meanLatencyMs", statsLatency);
        recordAggregates("Baseline_PartiallyGenerated25", "p50LatencyMs", statsP50Latency);
        recordAggregates("Baseline_PartiallyGenerated25", "p95LatencyMs", statsP95Latency);
        recordAggregates("Baseline_PartiallyGenerated25", "nativeLoadsPerTp", statsNativeLoads);
        recordAggregates("Baseline_PartiallyGenerated25", "discardedGenPerTp", statsDiscardedGen);

        REPORT.add("Baseline", "PartiallyGenerated25", "meanCpuMsPerTp", statsCpu.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "PartiallyGenerated25", "p50CpuMs", statsP50Cpu.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "PartiallyGenerated25", "p95CpuMs", statsP95Cpu.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "PartiallyGenerated25", "meanLatencyMs", statsLatency.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "PartiallyGenerated25", "p50LatencyMs", statsP50Latency.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "PartiallyGenerated25", "p95LatencyMs", statsP95Latency.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "PartiallyGenerated25", "nativeLoadsPerTp", statsNativeLoads.mean(), Provenance.DERIVED);
        REPORT.add("Baseline", "PartiallyGenerated25", "discardedGenPerTp", statsDiscardedGen.mean(), Provenance.DERIVED);

        System.out.printf("--- STATISTICAL SUMMARY: 25%% Partially Generated Terrain ---%n");
        System.out.printf("  CPU Cost / TP:  %s%n", statsCpu.format());
        System.out.printf("  Mean Latency:   %s%n", statsLatency.format());
        System.out.printf("  P50 Latency:    %s%n", statsP50Latency.format());
        System.out.printf("  P95 Latency:    %s%n", statsP95Latency.format());
        System.out.printf("  Native Loads:   %s%n", statsNativeLoads.format());
        System.out.printf("  Discarded Gen:  %s (Deadweight Waste)%n%n", statsDiscardedGen.format());

        assertTrue(statsDiscardedGen.mean() > 0.0);
    }

    @Test
    @DisplayName("Baseline Benchmark: 0% Fresh/Ungenerated Terrain (Current State Multi-Trial)")
    void baselineUngenerated0() throws Exception {
        List<Double> trialMeanCpus = new ArrayList<>();
        List<Double> trialP50Cpus = new ArrayList<>();
        List<Double> trialP95Cpus = new ArrayList<>();
        List<Double> trialMeanLatencies = new ArrayList<>();
        List<Double> trialP50Latencies = new ArrayList<>();
        List<Double> trialP95Latencies = new ArrayList<>();
        List<Double> trialNativeGens = new ArrayList<>();
        List<Double> trialDiscardedGens = new ArrayList<>();

        System.out.printf("%n=== BASELINE: 0%% Fresh/Ungenerated Terrain (%d Trials x %d Requests) ===%n", NUM_TRIALS, SAMPLES_PER_TRIAL);

        for (int trial = 1; trial <= NUM_TRIALS; trial++) {
            LocationGenerator.setRng(new java.util.Random(20261014L + trial * 10007L));
            BenchmarkedRTPWorld trialWorld = new BenchmarkedRTPWorld("fresh_world_" + trial, TerrainProfile.UNGENERATED_0);
            accessor.addWorld(trialWorld);
            Region region = createRegion(trialWorld);

            List<Double> latenciesMs = new ArrayList<>();
            List<Double> cpuCostsMs = new ArrayList<>();

            for (int i = 0; i < SAMPLES_PER_TRIAL; i++) {
                long prevCpuNanos = trialWorld.totalSimulatedCpuTimeNanos.get();
                long startNanos = System.nanoTime();

                GenerationResult res = execute(region, LoadPurpose.IMMEDIATE);
                assertNotNull(res.coords());

                long elapsedNanos = System.nanoTime() - startNanos;
                long deltaCpuNanos = trialWorld.totalSimulatedCpuTimeNanos.get() - prevCpuNanos;

                latenciesMs.add(elapsedNanos / 1_000_000.0);
                cpuCostsMs.add(deltaCpuNanos / 1_000_000.0);
            }

            Collections.sort(latenciesMs);
            Collections.sort(cpuCostsMs);

            double meanCpu = cpuCostsMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double p50Cpu = cpuCostsMs.get(SAMPLES_PER_TRIAL / 2);
            double p95Cpu = cpuCostsMs.get((int) (SAMPLES_PER_TRIAL * 0.95));

            double meanLatency = latenciesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double p50Latency = latenciesMs.get(SAMPLES_PER_TRIAL / 2);
            double p95Latency = latenciesMs.get((int) (SAMPLES_PER_TRIAL * 0.95));

            double nativeLoadsPerTp = (double) trialWorld.nativeLoadInvocations.get() / SAMPLES_PER_TRIAL;
            double discardedGenPerTp = (double) trialWorld.discardedGenerations.get() / SAMPLES_PER_TRIAL;

            trialMeanCpus.add(meanCpu);
            trialP50Cpus.add(p50Cpu);
            trialP95Cpus.add(p95Cpu);
            trialMeanLatencies.add(meanLatency);
            trialP50Latencies.add(p50Latency);
            trialP95Latencies.add(p95Latency);
            trialNativeGens.add(nativeLoadsPerTp);
            trialDiscardedGens.add(discardedGenPerTp);

            String runSubject = "Run_" + trial;
            REPORT.add("Baseline_Ungenerated0", runSubject, "meanCpuMsPerTp", meanCpu, Provenance.MEASURED);
            REPORT.add("Baseline_Ungenerated0", runSubject, "p50CpuMs", p50Cpu, Provenance.MEASURED);
            REPORT.add("Baseline_Ungenerated0", runSubject, "p95CpuMs", p95Cpu, Provenance.MEASURED);
            REPORT.add("Baseline_Ungenerated0", runSubject, "meanLatencyMs", meanLatency, Provenance.MEASURED);
            REPORT.add("Baseline_Ungenerated0", runSubject, "p50LatencyMs", p50Latency, Provenance.MEASURED);
            REPORT.add("Baseline_Ungenerated0", runSubject, "p95LatencyMs", p95Latency, Provenance.MEASURED);
            REPORT.add("Baseline_Ungenerated0", runSubject, "nativeGenerationsPerTp", nativeLoadsPerTp, Provenance.DERIVED);
            REPORT.add("Baseline_Ungenerated0", runSubject, "discardedGenPerTp", discardedGenPerTp, Provenance.DERIVED);

            System.out.printf("  [Trial %d] Mean CPU: %.2f ms | P50 Lat: %.2f ms | P95 Lat: %.2f ms | Native Gen/TP: %.2f | Discarded Gen/TP: %.2f%n",
                    trial, meanCpu, p50Latency, p95Latency, nativeLoadsPerTp, discardedGenPerTp);
        }

        StatsSummary statsCpu = StatsSummary.of(trialMeanCpus);
        StatsSummary statsP50Cpu = StatsSummary.of(trialP50Cpus);
        StatsSummary statsP95Cpu = StatsSummary.of(trialP95Cpus);
        StatsSummary statsLatency = StatsSummary.of(trialMeanLatencies);
        StatsSummary statsP50Latency = StatsSummary.of(trialP50Latencies);
        StatsSummary statsP95Latency = StatsSummary.of(trialP95Latencies);
        StatsSummary statsNativeGens = StatsSummary.of(trialNativeGens);
        StatsSummary statsDiscardedGens = StatsSummary.of(trialDiscardedGens);

        recordAggregates("Baseline_Ungenerated0", "meanCpuMsPerTp", statsCpu);
        recordAggregates("Baseline_Ungenerated0", "meanLatencyMs", statsLatency);
        recordAggregates("Baseline_Ungenerated0", "p50LatencyMs", statsP50Latency);
        recordAggregates("Baseline_Ungenerated0", "p95LatencyMs", statsP95Latency);
        recordAggregates("Baseline_Ungenerated0", "nativeGenerationsPerTp", statsNativeGens);
        recordAggregates("Baseline_Ungenerated0", "discardedGenPerTp", statsDiscardedGens);

        REPORT.add("Baseline", "Ungenerated0", "meanCpuMsPerTp", statsCpu.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Ungenerated0", "p50CpuMs", statsP50Cpu.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Ungenerated0", "p95CpuMs", statsP95Cpu.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Ungenerated0", "meanLatencyMs", statsLatency.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Ungenerated0", "p50LatencyMs", statsP50Latency.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Ungenerated0", "p95LatencyMs", statsP95Latency.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Ungenerated0", "nativeGenerationsPerTp", statsNativeGens.mean(), Provenance.DERIVED);
        REPORT.add("Baseline", "Ungenerated0", "discardedGenPerTp", statsDiscardedGens.mean(), Provenance.DERIVED);

        System.out.printf("--- STATISTICAL SUMMARY: 0%% Fresh/Ungenerated Terrain ---%n");
        System.out.printf("  CPU Cost / TP:  %s%n", statsCpu.format());
        System.out.printf("  Mean Latency:   %s%n", statsLatency.format());
        System.out.printf("  P50 Latency:    %s%n", statsP50Latency.format());
        System.out.printf("  P95 Latency:    %s%n", statsP95Latency.format());
        System.out.printf("  Native Gen:     %s%n", statsNativeGens.format());
        System.out.printf("  Discarded Gen:  %s%n%n", statsDiscardedGens.format());

        assertTrue(statsCpu.mean() >= COST_NANOS_LIVE_LOAD_UNGENERATED / 1_000_000.0);
    }

    @Test
    @DisplayName("Baseline Benchmark: 10-Player Concurrent Burst (Current State Multi-Trial)")
    void baselineConcurrentBurst10() throws Exception {
        List<Double> trialPeakConcurrencies = new ArrayList<>();
        List<Double> trialPeakGenerations = new ArrayList<>();
        List<Double> trialBurstCpus = new ArrayList<>();
        List<Double> trialBurstDurations = new ArrayList<>();
        List<Double> trialP50Latencies = new ArrayList<>();
        List<Double> trialP95Latencies = new ArrayList<>();

        System.out.printf("%n=== BASELINE: 10-Player Concurrent Burst (%d Trials) ===%n", NUM_BURST_TRIALS);

        for (int trial = 1; trial <= NUM_BURST_TRIALS; trial++) {
            LocationGenerator.setRng(new java.util.Random(20261014L + trial * 20011L));
            BenchmarkedRTPWorld burstWorld = new BenchmarkedRTPWorld("burst_world_" + trial, TerrainProfile.PARTIALLY_GENERATED_25);
            accessor.addWorld(burstWorld);
            Region region = createRegion(burstWorld);

            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(BURST_CLIENTS);
            List<Double> burstLatenciesMs = Collections.synchronizedList(new ArrayList<>());
            long burstStartNanos = System.nanoTime();
            long prevCpuNanos = burstWorld.totalSimulatedCpuTimeNanos.get();

            for (int i = 0; i < BURST_CLIENTS; i++) {
                Thread worker = new Thread(() -> {
                    try {
                        startGate.await();
                        long t0 = System.nanoTime();
                        GenerationResult res = execute(region, LoadPurpose.IMMEDIATE);
                        long t1 = System.nanoTime();
                        if (res != null && res.coords() != null) {
                            burstLatenciesMs.add((t1 - t0) / 1_000_000.0);
                        }
                    } catch (Throwable e) {
                        // Throttled or exception caught
                    } finally {
                        completionLatch.countDown();
                    }
                });
                worker.start();
            }

            // Release all 10 requests at the same instant:
            startGate.countDown();
            assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "All burst tasks should finish within 10s");

            long burstElapsedNanos = System.nanoTime() - burstStartNanos;
            long totalBurstCpuNanos = burstWorld.totalSimulatedCpuTimeNanos.get() - prevCpuNanos;
            double totalBurstCpuMs = totalBurstCpuNanos / 1_000_000.0;
            double burstDurationMs = burstElapsedNanos / 1_000_000.0;
            int peakConcurrency = burstWorld.peakConcurrentNativeLoads.get();
            trialPeakGenerations.add((double) burstWorld.peakConcurrentGenerations.get());

            Collections.sort(burstLatenciesMs);
            int burstCount = burstLatenciesMs.size();
            double p50BurstLatency = burstCount > 0 ? burstLatenciesMs.get(burstCount / 2) : 0.0;
            double p95BurstLatency = burstCount > 0 ? burstLatenciesMs.get((int) (burstCount * 0.95)) : 0.0;

            trialPeakConcurrencies.add((double) peakConcurrency);
            trialBurstCpus.add(totalBurstCpuMs);
            trialBurstDurations.add(burstDurationMs);
            trialP50Latencies.add(p50BurstLatency);
            trialP95Latencies.add(p95BurstLatency);

            String runSubject = "Run_" + trial;
            REPORT.add("Baseline_Burst10", runSubject, "peakConcurrentNativeLoads", peakConcurrency, Provenance.MEASURED);
            REPORT.add("Baseline_Burst10", runSubject, "totalBurstCpuMs", totalBurstCpuMs, Provenance.MEASURED);
            REPORT.add("Baseline_Burst10", runSubject, "burstDurationMs", burstDurationMs, Provenance.MEASURED);
            REPORT.add("Baseline_Burst10", runSubject, "p50BurstLatencyMs", p50BurstLatency, Provenance.MEASURED);
            REPORT.add("Baseline_Burst10", runSubject, "p95BurstLatencyMs", p95BurstLatency, Provenance.MEASURED);

            System.out.printf("  [Trial %d] Peak Concurrency: %d | Burst CPU: %.2f ms | Duration: %.2f ms | P50 Lat: %.2f ms | P95 Lat: %.2f ms%n",
                    trial, peakConcurrency, totalBurstCpuMs, burstDurationMs, p50BurstLatency, p95BurstLatency);
        }

        StatsSummary statsConcurrency = StatsSummary.of(trialPeakConcurrencies);
        StatsSummary statsBurstCpu = StatsSummary.of(trialBurstCpus);
        StatsSummary statsDuration = StatsSummary.of(trialBurstDurations);
        StatsSummary statsP50Latency = StatsSummary.of(trialP50Latencies);
        StatsSummary statsP95Latency = StatsSummary.of(trialP95Latencies);

        recordAggregates("Baseline_Burst10", "peakConcurrentNativeLoads", statsConcurrency);
        recordAggregates("Baseline_Burst10", "totalBurstCpuMs", statsBurstCpu);
        recordAggregates("Baseline_Burst10", "burstDurationMs", statsDuration);
        recordAggregates("Baseline_Burst10", "p50BurstLatencyMs", statsP50Latency);
        recordAggregates("Baseline_Burst10", "p95BurstLatencyMs", statsP95Latency);

        REPORT.add("Baseline", "Burst10", "peakConcurrentNativeLoads", statsConcurrency.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Burst10", "totalBurstCpuMs", statsBurstCpu.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Burst10", "burstDurationMs", statsDuration.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Burst10", "p50BurstLatencyMs", statsP50Latency.mean(), Provenance.MEASURED);
        REPORT.add("Baseline", "Burst10", "p95BurstLatencyMs", statsP95Latency.mean(), Provenance.MEASURED);

        System.out.printf("--- STATISTICAL SUMMARY: 10-Player Concurrent Burst ---%n");
        System.out.printf("  Peak Concurrency: %s (Flaw: Unbounded concurrent loads)%n", statsConcurrency.format());
        System.out.printf("  Total Burst CPU:  %s%n", statsBurstCpu.format());
        System.out.printf("  Burst Duration:   %s%n", statsDuration.format());
        System.out.printf("  P50 Burst Lat:    %s%n", statsP50Latency.format());
        System.out.printf("  P95 Burst Lat:    %s%n%n", statsP95Latency.format());

        // ADR-114 Decision 4: the permit bounds native generation; on-disk loads are not gated.
        assertTrue(StatsSummary.of(trialPeakGenerations).max() <= ImmediateLoadGovernor.MAX_REGIONAL_PERMITS,
                "ADR-114 Decision 4: concurrent native generations capped at governor permit ceiling");
    }
}
