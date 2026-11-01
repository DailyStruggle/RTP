package io.github.dailystruggle.rtp.folia.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FoliaRegionTpsSamplerTest {

    @Test
    @DisplayName("ADR-087: inter-tick gap above 500ms re-seeds without folding into EMA")
    void tick_gap_above_500ms_reseeds_without_folding_into_ema() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FoliaRegionTpsSampler sampler = new FoliaRegionTpsSampler(now::get);

        sampler.tick(); // seed

        // 30 normal ticks at 50ms (20 TPS)
        for (int i = 0; i < 30; i++) {
            now.addAndGet(50_000_000L);
            sampler.tick();
        }
        assertEquals(30, sampler.sampleCount());
        assertTrue(sampler.tps1m() > 19.9 && sampler.tps1m() <= 20.0,
                "expected ~20 TPS, got " + sampler.tps1m());

        // 30-second unload/idle gap
        now.addAndGet(30L * 1_000_000_000L);
        sampler.tick();

        // Sample count did not increment for the gap; TPS did not drop to ~11 TPS
        assertEquals(30, sampler.sampleCount());
        assertTrue(sampler.tps1m() > 19.9,
                "gap must not drop TPS, got " + sampler.tps1m());

        // Resumes ticking normally 50ms later
        now.addAndGet(50_000_000L);
        sampler.tick();
        assertEquals(31, sampler.sampleCount());
        assertTrue(sampler.tps1m() > 19.9,
                "subsequent tick must stay at ~20 TPS, got " + sampler.tps1m());
    }

    @Test
    @DisplayName("ADR-087: valid tick delta is capped at 250ms to prevent single-frame spikes from crashing EMA")
    void tick_delta_is_capped_at_250ms() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FoliaRegionTpsSampler sampler = new FoliaRegionTpsSampler(now::get);

        sampler.tick(); // seed
        now.addAndGet(400_000_000L); // 400ms tick (< 500ms gap threshold)
        sampler.tick();

        assertEquals(1, sampler.sampleCount());
        // 250ms cap corresponds to 4.0 TPS floor for a single tick
        assertEquals(4.0, sampler.tps1m(), 1e-6);
        assertEquals(250.0, sampler.mspt(), 1e-6);
    }
}
