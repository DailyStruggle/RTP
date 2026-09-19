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
 * {@code rtp test biome-source} &mdash; read-only diagnostic that reports the
 * current {@code BiomeSourceMetrics} counters (Anvil-tier vs. live-tier) plus
 * the derived Anvil-hit rate.
 *
 * <p>Closes the ADR-016 section 13.1 runtime verification gap: {@code
 * rtp test anvil-prefilter} tells operators whether the pre-load probe did
 * useful work, while this command tells them whether the <em>post-load</em>
 * biome read actually came from the persisted {@code .mca} palette rather
 * than the live {@code org.bukkit.World#getBiome(...)} getter. A sustained
 * non-zero Anvil-hit rate is the live evidence that upgrade-drift protection
 * is active, not just architecturally asserted.
 *
 * <p>Counters are populated on every Bukkit-family adapter
 * ({@code BukkitRTPWorld#getBiome}, {@code FoliaRTPWorld#getBiome}); Paper
 * inherits the Spigot path per section 13.2. See {@code
 * docs/dev/RUNTIME_TEST_SUITE_PLAN.md} for the runtime-test suite contract.
 *
 * <p>Safety compliance:
 * <ul>
 *   <li><b>S-004:</b> emits a single {@code INFO}-level summary plus a
 *       cold-start note when relevant. No failure mode to hide.</li>
 *   <li><b>S-005:</b> reads two {@link AtomicLong}s; no chunk I/O, no
 *       main-thread wait. Safe on any scheduler.</li>
 *   <li><b>Read-only:</b> does not reset the counters.</li>
 * </ul>
 */
public class TestBiomeSourceCmd extends BaseRTPCmdImpl {

  /** Immutable snapshot of the two biome-source counters. */
  public static final class Snapshot {
    public final long anvilHits;
    public final long liveHits;

    public Snapshot(long anvilHits, long liveHits) {
      this.anvilHits = anvilHits;
      this.liveHits = liveHits;
    }

    public long total() {
      return anvilHits + liveHits;
    }

    /**
     * Fraction of biome reads answered from the Anvil cache. Returns
     * {@code 0.0} when no reads have been observed yet, so callers do not
     * need to guard against division by zero.
     */
    public double anvilHitRate() {
      long t = total();
      return t == 0L ? 0.0 : (double) anvilHits / (double) t;
    }

    @Override
    public String toString() {
      return "anvil-hits=" + anvilHits
          + " live-hits=" + liveHits
          + " total=" + total()
          + String.format(" anvil-hit-rate=%.3f", anvilHitRate());
    }
  }

  public TestBiomeSourceCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "biome-source";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "reports BiomeSourceMetrics Anvil-tier vs live-tier counters (ADR-016 section 13.1)";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    Snapshot s = snapshot();
    String summary = "[RTP test/biome-source] " + s;
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, summary);
    }
    RTP.log(Level.INFO, summary);

    // Reason-keyed breakdown (ADR-016 section 13.1 audit option C). Tells operators
    // *why* live-tier reads happened without relying on log-level-gated
    // diagnostic lines.
    try {
      Map<String, Long> reasons =
          io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.reasonCounters();
      StringBuilder sb = new StringBuilder("[RTP test/biome-source] reasons:");
      for (Map.Entry<String, Long> e : reasons.entrySet()) {
        sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
      }
      String reasonLine = sb.toString();
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, reasonLine);
      }
      RTP.log(Level.INFO, reasonLine);
    } catch (Throwable ignored) {
      // Reason map is best-effort diagnostics; never block the summary on it.
    }

    if (s.total() == 0L) {
      String note =
          "[RTP test/biome-source] no biome reads observed yet — either no teleport "
              + "has triggered candidate evaluation since startup, or the platform "
              + "adapter does not route getBiome through the ADR-016 section 13.1 chain "
              + "(Fabric is out of scope).";
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, note);
      }
      RTP.log(Level.INFO, note);
    }
    return true;
  }

  private static AtomicLong getMetric(String name) {
    try {
      return (AtomicLong)
          Class.forName("io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics")
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
        getMetric("anvilHits").get(),
        getMetric("liveHits").get());
  }
}
