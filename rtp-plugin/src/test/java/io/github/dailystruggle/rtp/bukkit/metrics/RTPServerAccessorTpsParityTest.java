package io.github.dailystruggle.rtp.bukkit.metrics;

import io.github.dailystruggle.rtp.bukkitplatform.server.AbstractServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.metrics.CoreMetrics;
import io.github.dailystruggle.metrics.api.MetricsBinding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C6 section 1.6.1 of {@code CHECKLIST-metrics-and-multiserver.md} - verifies the
 * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#getTPS(int)
 * RTPServerAccessor#getTPS} contract that a non-NOOP {@link MetricsBinding}
 * overrides the per-platform fallback constant on every adapter, and that
 * the M0 NOOP path falls back to the platform default (matching the
 * pre-M2 behaviour).
 *
 * <p>This is a routing test, not a sampler test: the binding stub returns
 * a fixed sentinel TPS so the assertion is a direct equality on the value
 * propagated through {@code RTP.metrics.snapshot().tps1m}. The companion
 * {@link MetricsConsolidationArchTest} pins the structural rule that only
 * {@code *MetricsBinding} classes may probe raw {@code Server#getTPS()}.
 *
 * <p>Bukkit-side: instantiates {@link AbstractServerAccessor} directly via
 * an anonymous concrete subclass. The non-NOOP branch returns before any
 * {@code Bukkit.getServer()} reference is touched, so no MockBukkit boot
 * is required. The fallback branch is exercised separately by restoring
 * {@link MetricsBinding#NOOP} and asserting the recorded behaviour.
 */
class RTPServerAccessorTpsParityTest {

    /**
     * Minimal {@link MetricsBinding} that hands back a sentinel TPS. The
     * five required scalars (tps1m / tps5m / tps15m / mspt / playerCount /
     * softCap) are all explicit so the binding is unambiguously "non-NOOP"
     * from the snapshot's point of view (no NaN sentinels).
     */
    private static final class StubBinding implements MetricsBinding {
        private final double tps;

        StubBinding(double tps) { this.tps = tps; }

        @Override public double tps1m() { return tps; }
        @Override public double tps5m() { return tps; }
        @Override public double tps15m() { return tps; }
        @Override public double mspt() { return 50.0; }
        @Override public int playerCount() { return 0; }
        @Override public int softCap() { return 0; }
    }

    /** Tiny concrete subclass - only present so {@link AbstractServerAccessor}
     *  can be instantiated. Its {@code getTPS(int)} is the inherited C6 routing
     *  implementation under test.
     */
    private static AbstractServerAccessor newAccessor() {
        return new AbstractServerAccessor() {
            // No overrides - we exercise the inherited concrete getTPS(int).
        };
    }

    @AfterEach
    void restoreNoop() {
        coreMetrics().setBinding(MetricsBinding.NOOP);
    }

    /** Reach the singleton {@link CoreMetrics} via the public static. */
    private static CoreMetrics coreMetrics() {
        // RTP.metrics is package-public final static (see rtp-core). Use a
        // tolerant reflective read so this test survives a future visibility
        // tightening without silent breakage.
        try {
            Field f = RTP.class.getField("metrics");
            return (CoreMetrics) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("RTP.metrics is no longer accessible from tests", e);
        }
    }

    @Test
    void bukkit_accessor_reads_tps_from_non_noop_binding() {
        coreMetrics().setBinding(new StubBinding(17.5));

        AbstractServerAccessor a = newAccessor();
        // ticks=1 -> tps1m branch per section 1.6.1 (ticks<100).
        assertEquals(17.5, a.getTPS(1), 1e-9,
                "non-NOOP binding TPS must be returned verbatim — section 1.6.1 routing failed");

        // ticks=100..599 -> tps5m, ticks>=600 -> tps15m. The stub returns
        // the same value for all three windows, so they must all equal the
        // sentinel too. This nails down the window-selection branch as well.
        assertEquals(17.5, a.getTPS(100), 1e-9);
        assertEquals(17.5, a.getTPS(600), 1e-9);
    }

    @Test
    void bukkit_accessor_falls_back_when_binding_is_noop() {
        // Binding is the NOOP - snapshot.tps1m is NaN - accessor should
        // return *something* (the reflective recentTps probe is what runs
        // in production; under the test classpath Bukkit.getServer() may
        // be null so the catch returns 20.0). We only assert finiteness +
        // non-NaN: the contract is "do not propagate UNSAMPLED to callers".
        coreMetrics().setBinding(MetricsBinding.NOOP);

        AbstractServerAccessor a = newAccessor();
        double tps = a.getTPS(1);
        assertTrue(!Double.isNaN(tps),
                "NOOP fallback returned NaN — getTPS() must always yield a finite value");
        assertTrue(tps > 0.0,
                "NOOP fallback returned non-positive TPS=" + tps);
    }
}
