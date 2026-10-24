package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.server.ProgressBar;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tasks.scan.ScanTask;
import io.github.dailystruggle.rtp.common.placeholders.PlaceholderProvider;

import io.github.dailystruggle.rtp.common.tasks.scan.ScanTasks;
import java.util.HashMap;
import java.util.Map;

/**
 * Platform-neutral driver for the world-scan on-screen progress bar.
 * Aggregates active {@link ScanTasks#current()} metrics and updates progress bars.
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
    if (TeleportSessions.current() == null || RTPRuntime.serverAccessor() == null) return;

    if (ActiveConfigs.get() == null) return;

    String template = ActiveConfigs.get().getConfigValue(CommandMessages.scanBossBar, "").toString();
    // Empty template = progress bar disabled.
    if (template == null || template.isEmpty()) {
      RTPRuntime.serverAccessor().clearProgressBars();
      return;
    }

    Map<String, ScanTask> scanTasks = ScanTasks.current();
    if (scanTasks.isEmpty()) {
      RTPRuntime.serverAccessor().clearProgressBars();
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

    String etaStr = PlaceholderProvider.formatEta(maxEta);
    double landPercentage = PlaceholderProvider.averageLandPercentage(scanTasks.values());

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
        .replace("[scan_landPercentage]", String.format(java.util.Locale.ROOT, "%.1f", landPercentage))
        .replace("[scan_progress]", String.format(java.util.Locale.ROOT, "%.1f", progressFraction * 100.0))
        .replace("[scan_eta]", etaStr);

    // Build the desired bar state, one bar per active scan region, and hand it to the platform
    // accessor which renders / reconciles it.
    ProgressBar spec = new ProgressBar(title, progressFraction, SCAN_PERMISSION);
    Map<String, ProgressBar> bars = new HashMap<>();
    for (String regionName : scanTasks.keySet()) {
      bars.put(regionName, spec);
    }
    RTPRuntime.serverAccessor().updateProgressBars(bars);
  }

  /** Hides and discards every scan progress bar. */
  public static void clear() {
    if (RTPRuntime.serverAccessor() != null) RTPRuntime.serverAccessor().clearProgressBars();
  }
}
