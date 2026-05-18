package io.github.dailystruggle.rtp.paper.metrics;

import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Server;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * Paper-flavoured {@link MetricsBinding} that publishes server-wide tick metrics
 * (TPS 1m/5m/15m, average MSPT, online player count) into {@code RTP.metrics}.
 *
 * <p>Paper exposes {@code Server#getTPS()} (since 1.16+) and
 * {@code Server#getAverageTickTime()}, so {@code rtp-core} no longer needs a
 * sampler thread on this platform. Players-online is a cheap snapshot.
 *
 * <p>{@code softCap} is sourced from {@link Bukkit#getMaxPlayers()} so the
 * {@code /rtp info} "Players: N / cap" row mirrors the value the proxy-side
 * backend-selector menu observes. The remaining {@link MetricsBinding} fields
 * ({@code chunkLoadBacklog}, {@code databaseLatencyMs}) are intentionally left
 * at their inherited sentinels: those values originate from {@code rtp-core}
 * state and are folded in by
 * {@link io.github.dailystruggle.rtp.common.metrics.CoreMetrics} directly, not
 * by the platform binding.
 *
 * <p>This class is testable without MockBukkit: the protected
 * {@link #PaperMetricsBinding(DoubleSupplier, DoubleSupplier, DoubleSupplier, DoubleSupplier, IntSupplier, IntSupplier)}
 * constructor accepts pluggable suppliers so callers can drive deterministic
 * values from a unit test. Production code uses the public no-arg constructor
 * which binds to {@link Bukkit#getServer()}.
 *
 * <p>Wiring (per {@code METRICS_PLAN.md}, Phase M1): the platform plugin
 * installs an instance via {@code RTP.metrics.setBinding(new PaperMetricsBinding())}
 * during plugin enable, after {@code Bukkit.getServer()} is non-null. All calls
 * are non-blocking and never throw on the calling thread.
 */
public final class PaperMetricsBinding implements MetricsBinding {

    private final DoubleSupplier tps1m;
    private final DoubleSupplier tps5m;
    private final DoubleSupplier tps15m;
    private final DoubleSupplier mspt;
    private final IntSupplier playerCount;
    private final IntSupplier softCap;

    /**
     * Production constructor. Binds to the live {@link Bukkit#getServer()}.
     * If Bukkit is not yet initialised when a getter is invoked, that getter
     * returns its documented sentinel rather than throwing.
     */
    public PaperMetricsBinding() {
        this(
                () -> safeTpsIndex(0),
                () -> safeTpsIndex(1),
                () -> safeTpsIndex(2),
                PaperMetricsBinding::safeMspt,
                PaperMetricsBinding::safePlayerCount,
                PaperMetricsBinding::safeMaxPlayers
        );
    }

    /**
     * Test seam. Each supplier is invoked on the calling thread; suppliers
     * shall not block.
     */
    PaperMetricsBinding(DoubleSupplier tps1m,
                        DoubleSupplier tps5m,
                        DoubleSupplier tps15m,
                        DoubleSupplier mspt,
                        IntSupplier playerCount,
                        IntSupplier softCap) {
        this.tps1m = tps1m;
        this.tps5m = tps5m;
        this.tps15m = tps15m;
        this.mspt = mspt;
        this.playerCount = playerCount;
        this.softCap = softCap;
    }

    @Override
    public double tps1m() { return tps1m.getAsDouble(); }

    @Override
    public double tps5m() { return tps5m.getAsDouble(); }

    @Override
    public double tps15m() { return tps15m.getAsDouble(); }

    @Override
    public double mspt() { return mspt.getAsDouble(); }

    @Override
    public int playerCount() { return playerCount.getAsInt(); }

    @Override
    public int softCap() { return softCap.getAsInt(); }

    private static double safeTpsIndex(int idx) {
        try {
            Server s = Bukkit.getServer();
            if (s == null) return MetricsSnapshot.UNSAMPLED;
            double[] tps = s.getTPS();
            if (tps == null || idx >= tps.length) return MetricsSnapshot.UNSAMPLED;
            return tps[idx];
        } catch (Throwable ignored) {
            return MetricsSnapshot.UNSAMPLED;
        }
    }

    private static double safeMspt() {
        try {
            Server s = Bukkit.getServer();
            if (s == null) return MetricsSnapshot.UNSAMPLED;
            return s.getAverageTickTime();
        } catch (Throwable ignored) {
            return MetricsSnapshot.UNSAMPLED;
        }
    }

    private static int safePlayerCount() {
        try {
            Server s = Bukkit.getServer();
            if (s == null) return 0;
            return s.getOnlinePlayers().size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int safeMaxPlayers() {
        try {
            Server s = Bukkit.getServer();
            if (s == null) return 0;
            return s.getMaxPlayers();
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
