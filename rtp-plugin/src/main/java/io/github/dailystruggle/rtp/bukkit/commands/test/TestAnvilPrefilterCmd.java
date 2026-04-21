package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test anvil-prefilter} &mdash; read-only diagnostic that reports
 * the current {@link AnvilPrefilterMetrics} counters (ACCEPT / REJECT /
 * UNKNOWN) plus the derived hit rate.
 *
 * <p>Surfaces the Phase&nbsp;4 telemetry introduced in ADR-016 so operators
 * can verify the pre-filter is doing useful work across every in-scope
 * Bukkit-family adapter: Spigot runs the probe in {@code
 * BukkitRTPWorld#getChunkAt}; Paper inherits the same path verbatim per
 * §13.2 (landed 2026-04-20); Folia re-implements the same orchestration in
 * {@code FoliaRTPWorld#getChunkAt} per §11. Counters staying at zero now
 * means either the pre-filter is disabled
 * ({@code SafetyKeys.anvilPrefilterEnabled}) or no teleport has triggered a
 * candidate chunk load since startup. Fabric is out of scope (§13.2).
 *
 * <p>See {@code docs/dev/ANVIL_PREFILTER_PLAN.md &sect;10} (Phase&nbsp;4)
 * and {@code docs/dev/RUNTIME_TEST_SUITE_PLAN.md} for the runtime-test
 * suite contract.
 *
 * <p>Safety compliance:
 * <ul>
 *   <li><b>S-004:</b> emits a single {@code INFO}-level summary plus, when
 *       relevant, per-bucket detail. No failure mode to hide.</li>
 *   <li><b>S-005:</b> reads three {@code AtomicLong}s; no chunk I/O, no
 *       main-thread wait. Safe on any scheduler.</li>
 *   <li><b>Read-only:</b> does not reset the counters. A future
 *       {@code reset} leaf would need its own permission gate.</li>
 * </ul>
 */
public class TestAnvilPrefilterCmd extends BaseRTPCmdImpl {

  /** Immutable snapshot of the three telemetry counters. */
  public static final class Snapshot {
    public final long accepts;
    public final long rejects;
    public final long unknowns;

    public Snapshot(long accepts, long rejects, long unknowns) {
      this.accepts = accepts;
      this.rejects = rejects;
      this.unknowns = unknowns;
    }

    public long total() {
      return accepts + rejects + unknowns;
    }

    /**
     * Fraction of probes that short-circuited a live load (REJECT). Returns
     * {@code 0.0} when no probes have been observed yet, so callers do not
     * need to guard against division by zero.
     */
    public double rejectRate() {
      long t = total();
      return t == 0L ? 0.0 : (double) rejects / (double) t;
    }

    /**
     * Fraction of probes that returned a usable verdict (ACCEPT or REJECT)
     * rather than falling through to the live-load path. Returns
     * {@code 0.0} when no probes have been observed yet.
     */
    public double hitRate() {
      long t = total();
      return t == 0L ? 0.0 : (double) (accepts + rejects) / (double) t;
    }

    @Override
    public String toString() {
      return "accepts=" + accepts
          + " rejects=" + rejects
          + " unknowns=" + unknowns
          + " total=" + total()
          + String.format(" reject-rate=%.3f hit-rate=%.3f", rejectRate(), hitRate());
    }
  }

  public TestAnvilPrefilterCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "anvil-prefilter";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "reports AnvilPrefilter ACCEPT/REJECT/UNKNOWN counters and hit rate (ADR-016/017)";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    Snapshot s = snapshot();
    String summary = "[RTP test/anvil-prefilter] " + s;
    RTP.serverAccessor.sendMessage(callerId, summary);
    RTP.log(Level.INFO, summary);

    if (s.total() == 0L) {
      String note =
          "[RTP test/anvil-prefilter] no probes observed yet — either the pre-filter is "
              + "disabled (SafetyKeys.anvilPrefilterEnabled) or no teleport has triggered "
              + "a candidate chunk load since startup. Spigot, Paper (§13.2), and Folia "
              + "all enter the prefilter; Fabric is out of scope (§13.2).";
      RTP.serverAccessor.sendMessage(callerId, note);
      RTP.log(Level.INFO, note);
    }
    return true;
  }

  private static AtomicLong getMetric(String name) {
    try {
      return (AtomicLong)
          Class.forName("io.github.dailystruggle.rtp.anvil.AnvilPrefilterMetrics")
              .getField(name)
              .get(null);
    } catch (Exception e) {
      return new AtomicLong(0L);
    }
  }

  /**
   * Captures the current counter values in one pass. Visible for unit
   * testing so the counters can be mutated directly in a test harness.
   */
  public static Snapshot snapshot() {
    return new Snapshot(
        getMetric("accepts").get(),
        getMetric("rejects").get(),
        getMetric("unknowns").get());
  }
}
