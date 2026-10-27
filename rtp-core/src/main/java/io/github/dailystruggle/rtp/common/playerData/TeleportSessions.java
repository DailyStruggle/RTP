package io.github.dailystruggle.rtp.common.playerData;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Per-core teleport session state (ADR-115 seam 4), formerly instance fields of {@code RTP}.
 *
 * <p>Each {@code RTP} instance owns one and installs it when it becomes the current instance, so
 * {@link #current()} is {@code null} exactly while no core instance exists, as
 * {@code RTP.getInstance()} was. A newer core instance replaces the slot; nothing clears it.
 */
public final class TeleportSessions {
  private static volatile TeleportSessions current;

  public final TimeBinnedCache<UUID, TeleportData> priorTeleportData =
      new TimeBinnedCache<>(60_000L, 120_000L);
  public final TimeBinnedCache<UUID, TeleportData> latestTeleportData =
      new TimeBinnedCache<>(60_000L, 120_000L);
  public final ConcurrentSkipListSet<UUID> processingPlayers = new ConcurrentSkipListSet<>();
  public final Set<UUID> queuedPlayers = ConcurrentHashMap.newKeySet();
  @SuppressWarnings("java:S1319") // Public field type retained for binary compatibility
  public final ConcurrentHashMap<UUID, Long> invulnerablePlayers = new ConcurrentHashMap<>();
  public final ConcurrentMap<UUID, Map<String, Long>> regionTeleportTimes = new ConcurrentHashMap<>();
  /**
   * Per-player rolling usage-cap state backing the BetterRTP {@code LockAfter} parity feature
   * ({@code lockAfterUses} / {@code lockAfterResetSeconds}). Inert while {@code lockAfterUses <= 0}.
   */
  public final UsageCapTracker usageCaps = new UsageCapTracker();

  /** The installed sessions, or {@code null} before the first core instance is constructed. */
  public static TeleportSessions current() {
    return current;
  }

  /** Installs {@code sessions} as current; {@code null} clears the slot (tests). */
  public static void install(TeleportSessions sessions) {
    current = sessions;
  }
}
