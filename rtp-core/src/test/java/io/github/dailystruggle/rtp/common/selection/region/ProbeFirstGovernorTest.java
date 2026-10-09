package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.metrics.ProbeGovernorRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-109: measured-cost probe-first gate. Synthetic timings, seeded noise. */
class ProbeFirstGovernorTest {

    private static final long MS = 1_000_000L;

    private final AtomicLong clock = new AtomicLong();

    @AfterEach
    void cleanup() {
        ProbeFirstGovernor.resetAll();
    }

    private ProbeFirstGovernor governor() {
        return new ProbeFirstGovernor("r", ProbeFirstGovernor.Path.FILL, clock::get);
    }

    /** Run n candidates: probes reject with probability p; times jittered by +-noise. */
    private static int drive(ProbeFirstGovernor g, int n, double p, double tpMs, double tfMs,
                             double noise, Random rnd) {
        int probed = 0;
        for (int i = 0; i < n; i++) {
            if (g.shouldProbe()) {
                probed++;
                boolean rejected = rnd.nextDouble() < p;
                g.recordProbe(jitter(tpMs, noise, rnd), rejected);
                if (!rejected) g.recordLoad(true, jitter(tfMs, noise, rnd));
            } else {
                g.recordLoad(false, jitter(tfMs, noise, rnd));
            }
        }
        return probed;
    }

    /**
     * Fresh-world fill: candidates arrive in runs of 4-24 per region file, and the probes of one run
     * share one coalesced drain, so each pays {@code perChunk + open / k} for k probes in the run.
     * {@code reportGroups == false} records the same per-chunk share without the group size.
     */
    private static void fill(ProbeFirstGovernor g, int runs, double p, double openMs, double perChunkMs,
                             double tfMs, boolean reportGroups, Random rnd, int[] nextFile) {
        for (int r = 0; r < runs; r++) {
            long bin = ProbeFirstGovernor.binKey(32 * nextFile[0]++, 0);
            boolean[] probed = new boolean[4 + rnd.nextInt(21)];
            int k = 0;
            for (int i = 0; i < probed.length; i++) {
                probed[i] = g.shouldProbe(bin);
                if (probed[i]) k++;
            }
            for (boolean pr : probed) {
                if (!pr) {
                    g.recordLoad(false, jitter(tfMs, 0.1, rnd));
                    continue;
                }
                boolean rejected = rnd.nextDouble() < p;
                long share = jitter(perChunkMs + openMs / k, 0.1, rnd);
                if (reportGroups) {
                    g.recordProbe(share, k, rejected);
                } else {
                    g.recordProbe(share, rejected);
                }
                if (!rejected) g.recordLoad(true, jitter(tfMs, 0.1, rnd));
            }
        }
    }

    private static long jitter(double ms, double noise, Random rnd) {
        return Math.max(0L, Math.round(ms * MS * (1.0 + noise * (2.0 * rnd.nextDouble() - 1.0))));
    }

    @Test
    @DisplayName("Starts in PROBE and probes every candidate until the probe arm is sampled")
    void warmupProbesFirst() {
        ProbeFirstGovernor g = governor();
        for (int i = 0; i < ProbeFirstGovernor.MIN_SAMPLES; i++) {
            assertTrue(g.shouldProbe(), "decision " + i);
            g.recordProbe(MS, false);
        }
        assertEquals(ProbeFirstGovernor.Mode.PROBE, g.mode());
        assertTrue(g.snapshot().warmingUp());
    }

    @Test
    @DisplayName("High reject rate with a cheap probe keeps PROBE")
    void highRejectRateKeepsProbe() {
        ProbeFirstGovernor g = governor();
        drive(g, 4000, 0.6, 0.5, 2.0, 0.3, new Random(1));
        ProbeGovernorRow row = g.snapshot();
        assertEquals("PROBE", row.mode());
        assertEquals(0L, row.flips());
        assertFalse(row.warmingUp());
        assertTrue(row.costProbeMs() < row.costDirectMs(), row.toString());
    }

    @Test
    @DisplayName("No rejects and a costly probe flips to SKIP")
    void lowRejectRateFlipsToSkip() {
        ProbeFirstGovernor g = governor();
        drive(g, 4000, 0.0, 1.0, 2.0, 0.3, new Random(2));
        ProbeGovernorRow row = g.snapshot();
        assertEquals("SKIP", row.mode(), row.toString());
        assertEquals(1L, row.flips());
        assertTrue(row.skipShare() > 0.9, row.toString());
    }

    @Test
    @DisplayName("Flips back to PROBE once the reject rate rises")
    void flipsBackWhenRejectRateRises() {
        ProbeFirstGovernor g = governor();
        Random rnd = new Random(3);
        drive(g, 4000, 0.0, 1.0, 2.0, 0.3, rnd);
        assertEquals(ProbeFirstGovernor.Mode.SKIP, g.mode());
        drive(g, 20000, 0.9, 0.2, 2.0, 0.3, rnd);
        assertEquals(ProbeFirstGovernor.Mode.PROBE, g.mode(), g.snapshot().toString());
        assertEquals(2L, g.snapshot().flips());
    }

    @Test
    @DisplayName("Exploration floor: SKIP still probes at least 1 in 32 candidates")
    void explorationFloorHolds() {
        ProbeFirstGovernor g = governor();
        Random rnd = new Random(4);
        drive(g, 4000, 0.0, 5.0, 1.0, 0.05, rnd);
        assertEquals(ProbeFirstGovernor.Mode.SKIP, g.mode());
        int probed = drive(g, 3200, 0.0, 5.0, 1.0, 0.05, rnd);
        assertTrue(probed >= 3200 / 32, "probed=" + probed);
        assertTrue(probed <= 3200 / 4, "probed=" + probed);
    }

    @Test
    @DisplayName("Equal costs under noise do not flap")
    void noFlappingAtParity() {
        // Tp + (1 - p) * Tf == Tf exactly when Tp == p * Tf.
        for (long seed = 5; seed < 13; seed++) {
            ProbeFirstGovernor g = governor();
            drive(g, 8000, 0.25, 0.5, 2.0, 0.5, new Random(seed));
            assertTrue(g.snapshot().flips() <= 1, "seed=" + seed + " " + g.snapshot());
        }
    }

    @Test
    @DisplayName("Fit Tp(g) = perChunk + open/g evaluates at any group size; flat group sizes fall back to the mean")
    void fitSeparatesOpenFromPerChunk() {
        ProbeFirstGovernor.Fit fit = new ProbeFirstGovernor.Fit();
        for (int i = 0; i < 64; i++) {
            fit.add(1.0 / 16.0, 0.5 + 16.0 / 16.0);
            fit.add(1.0, 0.5 + 16.0);
        }
        assertTrue(fit.identified());
        assertEquals(16.0, fit.slope(), 1e-6);
        assertEquals(1.5, fit.at(1.0 / 16.0), 1e-6);
        assertEquals(0.5 + 16.0 / 4.0, fit.at(0.25), 1e-6);

        ProbeFirstGovernor.Fit flat = new ProbeFirstGovernor.Fit();
        for (int i = 0; i < 64; i++) flat.add(1.0, (i % 2 == 0) ? 8.0 : 10.0);
        assertFalse(flat.identified());
        assertEquals(0.0, flat.slope());
        assertEquals(flat.my, flat.at(1.0 / 16.0), 1e-9);
    }

    @Test
    @DisplayName("Fresh-world fill: grouped probe cost is judged at the default group size and flips back to PROBE")
    void groupedTrialsJudgedAtDefaultGroupSize() {
        // open 16 ms, 0.5 ms per chunk, full load 4 ms. Probing every candidate (k ~ 14) costs
        // ~1.6 ms per probe; trial runs in SKIP see k <= 8 and a per-chunk share of 2.5-16.5 ms.
        for (boolean grouped : new boolean[] {true, false}) {
            ProbeFirstGovernor g = governor();
            Random rnd = new Random(21);
            int[] file = {0};
            fill(g, 600, 0.0, 16.0, 0.5, 4.0, grouped, rnd, file);
            assertEquals(ProbeFirstGovernor.Mode.SKIP, g.mode(), "no rejects: skip wins " + g.snapshot());
            fill(g, 1500, 0.7, 16.0, 0.5, 4.0, grouped, rnd, file);
            if (grouped) {
                // 1.6 + 0.3 * 4 = 2.8 ms < 4 ms: probing wins at the default group size.
                assertEquals(ProbeFirstGovernor.Mode.PROBE, g.mode(), g.snapshot().toString());
                assertEquals(2L, g.snapshot().flips());
                assertTrue(g.groupSizeAtDefault() > 10.0, "g=" + g.groupSizeAtDefault());
            } else {
                // Without group sizes the sparse trials are charged their small-group open share.
                assertEquals(ProbeFirstGovernor.Mode.SKIP, g.mode(), g.snapshot().toString());
            }
        }
    }

    @Test
    @DisplayName("SKIP-mode trials probe a run of one region file, then pay back the explore share")
    void skipTrialsRunWithinOneBin() {
        ProbeFirstGovernor g = governor();
        drive(g, 4000, 0.0, 1.0, 2.0, 0.3, new Random(2));
        assertEquals(ProbeFirstGovernor.Mode.SKIP, g.mode());
        long binA = ProbeFirstGovernor.binKey(0, 0);
        long binB = ProbeFirstGovernor.binKey(32, 0);

        int guard = 0;
        while (!g.shouldProbe(binA)) assertTrue(++guard <= 64, "a trial starts within 1/MIN_EXPLORE decisions");
        assertFalse(g.shouldProbe(binB), "other files are not part of the trial");
        for (int i = 1; i < ProbeFirstGovernor.TRIAL_RUN; i++) assertTrue(g.shouldProbe(binA), "trial probe " + i);
        assertFalse(g.shouldProbe(binA), "trial ends after TRIAL_RUN probes");

        int probed = 0;
        for (int i = 0; i < 6400; i++) if (g.shouldProbe(binA)) probed++;
        assertTrue(probed >= 6400 / 32 - ProbeFirstGovernor.TRIAL_RUN, "probed=" + probed);
        assertTrue(probed <= 6400 / 4 + ProbeFirstGovernor.TRIAL_RUN, "probed=" + probed);
    }

    @Test
    @DisplayName("Bin key groups chunks by region file, including negative coordinates")
    void binKeyIsRegionFile() {
        assertEquals(ProbeFirstGovernor.binKey(0, 0), ProbeFirstGovernor.binKey(31, 31));
        assertNotEquals(ProbeFirstGovernor.binKey(0, 0), ProbeFirstGovernor.binKey(32, 0));
        assertNotEquals(ProbeFirstGovernor.binKey(0, 0), ProbeFirstGovernor.binKey(0, 32));
        assertNotEquals(ProbeFirstGovernor.binKey(0, 0), ProbeFirstGovernor.binKey(-1, 0));
        assertEquals(ProbeFirstGovernor.binKey(-32, -1), ProbeFirstGovernor.binKey(-1, -32));
        assertNotEquals(ProbeFirstGovernor.NO_BIN, ProbeFirstGovernor.binKey(-30_000_000 >> 4, 0));
    }

    @Test
    @DisplayName("Snapshot registry keys governors by region and path")
    void registryKeying() {
        ProbeFirstGovernor a = ProbeFirstGovernor.of("alpha", ProbeFirstGovernor.Path.FILL);
        assertSame(a, ProbeFirstGovernor.of("alpha", ProbeFirstGovernor.Path.FILL));
        assertNotSame(a, ProbeFirstGovernor.of("alpha", ProbeFirstGovernor.Path.CONSUME));
        assertNotSame(a, ProbeFirstGovernor.of("beta", ProbeFirstGovernor.Path.FILL));
        assertEquals(3, ProbeFirstGovernor.snapshotAll().size());
        assertTrue(ProbeFirstGovernor.snapshotAll().containsKey("alpha|consume"));
    }

    @Test
    @DisplayName("Reset drops governors for a specific region or all regions")
    void resetRegionAndAll() {
        ProbeFirstGovernor.of("alpha", ProbeFirstGovernor.Path.FILL);
        ProbeFirstGovernor.of("alpha", ProbeFirstGovernor.Path.CONSUME);
        ProbeFirstGovernor.of("beta", ProbeFirstGovernor.Path.FILL);
        assertEquals(3, ProbeFirstGovernor.snapshotAll().size());

        ProbeFirstGovernor.reset("alpha");
        assertEquals(1, ProbeFirstGovernor.snapshotAll().size());
        assertTrue(ProbeFirstGovernor.snapshotAll().containsKey("beta|fill"));

        ProbeFirstGovernor.resetAll();
        assertTrue(ProbeFirstGovernor.snapshotAll().isEmpty());
    }

    @Test
    @DisplayName("Negative durations are ignored")
    void negativeDurationsIgnored() {
        ProbeFirstGovernor g = governor();
        g.recordProbe(-1L, true);
        g.recordLoad(false, -1L);
        assertEquals(0L, g.snapshot().probeSamples());
        assertEquals(0L, g.snapshot().directSamples());
        clock.addAndGet(ProbeFirstGovernor.SUMMARY_NANOS);
        g.recordProbe(MS, false); // summary due: logs without throwing
        assertEquals(1L, g.snapshot().probeSamples());
    }
}
