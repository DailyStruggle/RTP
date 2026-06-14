package io.github.dailystruggle.rtp.api.server;

import java.util.Objects;

/**
 * Platform-neutral description of a single on-screen progress bar (e.g. a Bukkit
 * {@code BossBar}). Carries only platform-agnostic data so that {@code rtp-core} and the
 * platform-neutral adapters can request progress feedback without depending on any
 * platform UI type.
 *
 * <p>The concrete rendering (boss-bar vs. action-bar vs. no-op) is decided by the platform
 * implementation of {@link RTPServerAccessor#updateProgressBars(java.util.Map)}.
 */
public final class ProgressBar {
  private final String title;
  private final double progress;
  private final String viewerPermission;

  /**
   * @param title the bar title, may contain legacy {@code &x} / {@code #RRGGBB} color codes;
   *     the platform decides how (or whether) to render and/or strip them
   * @param progress fill fraction in the range {@code [0, 1]} (clamped by the implementation)
   * @param viewerPermission permission a player must hold to be shown the bar; {@code null} or
   *     empty means "all players"
   */
  public ProgressBar(String title, double progress, String viewerPermission) {
    this.title = title == null ? "" : title;
    this.progress = progress;
    this.viewerPermission = viewerPermission;
  }

  /** @return the bar title, possibly containing legacy/hex color codes (never {@code null}). */
  public String title() {
    return title;
  }

  /** @return the fill fraction in {@code [0, 1]} (not yet clamped). */
  public double progress() {
    return progress;
  }

  /** @return the permission required to view the bar, or {@code null}/empty for everyone. */
  public String viewerPermission() {
    return viewerPermission;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ProgressBar)) return false;
    ProgressBar that = (ProgressBar) o;
    return Double.compare(that.progress, progress) == 0
        && Objects.equals(title, that.title)
        && Objects.equals(viewerPermission, that.viewerPermission);
  }

  @Override
  public int hashCode() {
    return Objects.hash(title, progress, viewerPermission);
  }

  @Override
  public String toString() {
    return "ProgressBar{title='" + title + "', progress=" + progress
        + ", viewerPermission='" + viewerPermission + "'}";
  }
}
