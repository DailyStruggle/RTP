package io.github.dailystruggle.rtp.api.server;

/**
 * Platform-neutral description of an on-screen progress bar (e.g. Bukkit BossBar).
 */
public final class ProgressBar extends io.github.dailystruggle.server.api.ProgressBar {

  /**
   * @param title the bar title, may contain legacy {@code &x} / {@code #RRGGBB} color codes;
   *     the platform decides how (or whether) to render and/or strip them
   * @param progress fill fraction in the range {@code [0, 1]} (clamped by the implementation)
   * @param viewerPermission permission a player must hold to be shown the bar; {@code null} or
   *     empty means "all players"
   */
  public ProgressBar(String title, double progress, String viewerPermission) {
    super(title, progress, viewerPermission);
  }

  /** @return the bar title, possibly containing legacy/hex color codes (never {@code null}). */
  @Override
  public String title() {
    return super.title();
  }

  /** @return the fill fraction in {@code [0, 1]} (not yet clamped). */
  @Override
  public double progress() {
    return super.progress();
  }

  /** @return the permission required to view the bar, or {@code null}/empty for everyone. */
  @Override
  public String viewerPermission() {
    return super.viewerPermission();
  }

  @Override
  public boolean equals(Object o) {
    return super.equals(o);
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }

  @Override
  public String toString() {
    return super.toString();
  }
}
