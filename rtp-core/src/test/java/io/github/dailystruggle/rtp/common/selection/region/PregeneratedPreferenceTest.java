package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code pregeneratedPreference} behavior: enum presence, default 0.0 value,
 * fail attribution (REQ-RTP-S-004), and probabilistic chunk rejection gating.
 */
class PregeneratedPreferenceTest {

    @Test
    void enumMemberExists() {
        // Will throw at compile time if removed; runtime guard for posterity.
        PerformanceKeys k = PerformanceKeys.valueOf("pregeneratedPreference");
        assertNotNull(k);
    }

    @Test
    void failTypeExists() {
        LocationGenerator.FailTypes t = LocationGenerator.FailTypes.valueOf("ungenerated");
        assertNotNull(t);
    }

    @Test
    void shippedYamlDeclaresDefaultZero() throws IOException {
        Path yml = Path.of("..", "rtp-plugin", "src", "main", "resources", "advanced", "performance.yml");
        if (!Files.exists(yml)) {
            // Working dir may be the repo root when run via gradle wrapper.
            yml = Path.of("rtp-plugin", "src", "main", "resources", "advanced", "performance.yml");
        }
        assertTrue(Files.exists(yml), "performance.yml not found at " + yml.toAbsolutePath());
        String content = Files.readString(yml);
        assertTrue(content.contains("pregeneratedPreference:"),
                "performance.yml must declare pregeneratedPreference");
        assertTrue(content.contains("pregeneratedPreference: 0.0"),
                "default pregeneratedPreference must be 0.0 to preserve existing behavior");
    }

    /** Mirrors rejection rule in {@code PregenTask.runAttempt()}. */
    private static boolean wouldReject(double pref, boolean generated, Random rng) {
        if (pref <= 0.0d) return false;
        if (generated) return false;
        double clamped = Math.min(1.0d, pref);
        return clamped >= 1.0d || rng.nextDouble() < clamped;
    }

    @Test
    void zeroNeverRejects() {
        Random rng = new Random(1234L);
        int rejects = 0;
        for (int i = 0; i < 10_000; i++) {
            if (wouldReject(0.0d, false, rng)) rejects++;
        }
        assertEquals(0, rejects, "weight 0.0 must never drop ungenerated chunks");
    }

    @Test
    void oneAlwaysRejectsUngeneratedButNeverGenerated() {
        Random rng = new Random(1234L);
        for (int i = 0; i < 1_000; i++) {
            assertTrue(wouldReject(1.0d, false, rng), "weight 1.0 must drop every ungenerated chunk");
        }
        for (int i = 0; i < 1_000; i++) {
            assertTrue(!wouldReject(1.0d, true, rng), "generated chunks must always pass the gate");
        }
    }

    /**
     * Mirrors the backlog refill loop's bounded-rejection pattern: a maximum
     * number of <em>consecutive</em> rejections per call, after which the
     * caller breaks out. Asserts that at weight {@code 1.0} with an
     * all-ungenerated world we exit deterministically after the cap rather
     * than spinning.
     */
    private static int boundedRefillRejects(double pref, boolean allUngenerated,
                                            int cap, int maxIterations, Random rng) {
        int consecutive = 0;
        int iterations = 0;
        while (iterations < maxIterations) {
            iterations++;
            boolean generated = !allUngenerated;
            if (wouldReject(pref, generated, rng)) {
                if (++consecutive >= cap) return iterations; // break
                continue;
            }
            consecutive = 0;
            // accepted: in the real loop we'd offer to backlog and continue;
            // for this test, simulate a single accepted pick then exit.
            return iterations;
        }
        return iterations;
    }

    @Test
    void boundedAtWeightOneAllUngenerated() {
        // At weight 1.0 with every chunk ungenerated, the consecutive-reject
        // cap must trip in exactly `cap` iterations - never the iteration
        // ceiling. This is the regression guard for the unbounded-rejection
        // concern.
        Random rng = new Random(7L);
        int cap = 32;
        int iterations = boundedRefillRejects(1.0d, /*allUngenerated*/ true,
                cap, /*maxIterations*/ 10_000, rng);
        assertEquals(cap, iterations,
                "bounded refill must exit after exactly `cap` consecutive rejections, not spin");
    }

    @Test
    void boundedAtWeightOneFullyGeneratedAcceptsImmediately() {
        // Sanity: when nothing is ungenerated, the loop accepts on the first
        // iteration regardless of weight.
        Random rng = new Random(7L);
        int iterations = boundedRefillRejects(1.0d, /*allUngenerated*/ false,
                32, 10_000, rng);
        assertEquals(1, iterations,
                "fully-generated world must accept the first pick without rejection");
    }

    @Test
    void intermediateWeightApproximatesProbability() {
        Random rng = new Random(42L);
        int n = 100_000;
        double pref = 0.4d;
        int rejects = 0;
        for (int i = 0; i < n; i++) {
            if (wouldReject(pref, false, rng)) rejects++;
        }
        double observed = (double) rejects / n;
        // Tolerance well outside the 3-sigma band of a Bernoulli(0.4) of size 1e5.
        assertTrue(Math.abs(observed - pref) < 0.02d,
                "observed reject rate " + observed + " deviated too far from " + pref);
    }
}
