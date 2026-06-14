package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.server.ProgressBar;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.tasks.ScanTask;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Platform-neutral driver for the world-scan on-screen progress bar.
 *
 * <p>Each invocation reads the live {@link RTP#scanTasks} map, computes an aggregate title
 * from the {@code scanBossBar} message template, and delegates rendering to the active
 * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#updateProgressBars(Map)}.
 * The accessor (Bukkit boss-bar, Fabric/NeoForge boss-bar, or the default no-op) owns all
 * platform UI decisions, so this driver references no platform type and runs identically on
 * every platform.
 *
 * <p>Scheduled from {@code RTP.start()} as a periodic main/server-thread task; an empty
 * {@code scanBossBar} template or an empty scan-task map clears any bars.
 */
public final class ScanProgressBars {
  /** Permission a player must hold to see a scan progress bar. */
  private static final String SCAN_PERMISSION = "rtp.scan";

  private ScanProgressBars() {}

  /**
   * Recomputes and pushes the current scan progress bars. Safe to call before core is fully
   * initialized (it no-ops) and when no scans are running (it clears bars).
   */
  @SuppressWarnings("unchecked")
  public static void update() {
    if (RTP.getInstance() == null || RTP.serverAccessor == null) return;

    ConfigParser<MessagesKeys> langParser =
        (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    if (langParser == null) return;

    String template = langParser.getConfigValue(MessagesKeys.scanBossBar, "").toString();
    // Empty template = progress bar disabled.
    if (template == null || template.isEmpty()) {
      RTP.serverAccessor.clearProgressBars();
      return;
    }

    Map<String, ScanTask> scanTasks = RTP.getInstance().scanTasks;
    if (scanTasks.isEmpty()) {
      RTP.serverAccessor.clearProgressBars();
      return;
    }

    // Compute aggregate values for placeholders that span all tasks.
    long totalChunks = 0;
    long totalChunksDone = 0;
    long totalCps = 0;
    long maxEta = 0;
    StringBuilder regionNames = new StringBuilder();
    for (Map.Entry<String, ScanTask> entry : scanTasks.entrySet()) {
      ScanTask task = entry.getValue();
      totalChunksDone += task.latestAbsolutePos;
      totalChunks += task.latestAbsoluteTotal;
      totalCps += task.latestCps;
      if (task.latestEtaSeconds > maxEta) maxEta = task.latestEtaSeconds;
      if (regionNames.length() > 0) regionNames.append(", ");
      regionNames.append(entry.getKey());
    }

    String etaStr = formatEta(maxEta, langParser);

    double progressFraction = (totalChunks > 0)
        ? Math.min(1.0, (double) totalChunksDone / totalChunks)
        : 0.0;

    // Substitute placeholders into the template for the title. Color codes are left intact:
    // the platform implementation decides the bar color from them and strips them from the
    // rendered text.
    String title = template
        .replace("[scan_regions]", regionNames.toString())
        .replace("[scan_chunks]", String.valueOf(totalChunksDone))
        .replace("[scan_totalChunks]", String.valueOf(totalChunks))
        .replace("[scan_cps]", String.valueOf(totalCps))
        .replace("[scan_landPercentage]", String.format("%.1f", progressFraction * 100.0))
        .replace("[scan_eta]", etaStr);

    // Build the desired bar state, one bar per active scan region, and hand it to the platform
    // accessor which renders / reconciles it.
    ProgressBar spec = new ProgressBar(title, progressFraction, SCAN_PERMISSION);
    Map<String, ProgressBar> bars = new HashMap<>();
    for (String regionName : scanTasks.keySet()) {
      bars.put(regionName, spec);
    }
    RTP.serverAccessor.updateProgressBars(bars);
  }

  /** Hides and discards every scan progress bar. */
  public static void clear() {
    if (RTP.serverAccessor != null) RTP.serverAccessor.clearProgressBars();
  }

  /** Format seconds into a human-readable ETA string, mirroring PlaceholderProvider. */
  private static String formatEta(long totalSeconds, ConfigParser<MessagesKeys> langParser) {
    long days = TimeUnit.SECONDS.toDays(totalSeconds);
    long hours = TimeUnit.SECONDS.toHours(totalSeconds) % 24;
    long minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60;
    long seconds = totalSeconds % 60;

    StringBuilder sb = new StringBuilder();
    if (days > 0) sb.append(days).append(langParser.getConfigValue(MessagesKeys.days, "d")).append(" ");
    if (hours > 0) sb.append(hours).append(langParser.getConfigValue(MessagesKeys.hours, "h")).append(" ");
    if (minutes > 0) sb.append(minutes).append(langParser.getConfigValue(MessagesKeys.minutes, "m")).append(" ");
    if (seconds > 0 || sb.length() == 0) sb.append(seconds).append(langParser.getConfigValue(MessagesKeys.seconds, "s"));
    return sb.toString().trim();
  }
}
