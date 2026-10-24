package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.ActiveDatabase;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.metrics.LandingHeatmap;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportLimitStore;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Single gate between the teleport pipeline and its persistent sinks (ADR-121 section 11). For a
 * registered BIT mock ({@link MockPlayerRegistry#isMock}) no sink is touched: no teleport history,
 * no usage-cap count, no shared last-teleport time, no ADR-104 heatmap landing; mock landings go to
 * {@link BitTeleportLog} instead. Real players reach each sink exactly as before. Persistence
 * modules stay unaware of mocks.
 */
public final class SyntheticSideEffects {

  /** Shared last-teleport sink; replaceable by same-package tests only. */
  static volatile BiConsumer<UUID, Long> sharedTimeSink = RTP::updateSharedLastTeleportTime;

  private SyntheticSideEffects() {}

  public static boolean isSynthetic(UUID id) {
    return MockPlayerRegistry.isMock(id);
  }

  /** {@code false} for a BIT mock: platform and command events shall not be published for it. */
  public static boolean publishesEvents(RTPPlayer player) {
    return player == null || !isSynthetic(player.uuid());
  }

  /** Real: {@code ActiveDatabase.accessor().cacheValue(d)} when the database is open. */
  public static void persistTeleportData(UUID id, TeleportData d) {
    if (isSynthetic(id)) return;
    DatabaseAccessor<?> db = ActiveDatabase.accessor();
    if (db != null) db.cacheValue(d);
  }

  /** Real: {@code ActiveDatabase.teleportLimitStore().recordSuccess(...)} when installed. */
  public static void recordLimitUse(UUID id, long uses, long resetMs, long now) {
    if (isSynthetic(id)) return;
    TeleportLimitStore store = ActiveDatabase.teleportLimitStore();
    if (store != null) store.recordSuccess(id, uses, resetMs, now);
  }

  /** Real: {@code RTP.updateSharedLastTeleportTime(id, epochMs)}. */
  public static void recordSharedLastTeleport(UUID id, long epochMs) {
    if (isSynthetic(id)) return;
    sharedTimeSink.accept(id, epochMs);
  }

  /** Real: {@code LandingHeatmap.record}. Mock: one {@link BitTeleportLog} record. */
  public static void recordLanding(UUID id, String region, RTPLocation at, TeleportData d) {
    if (at == null) return;
    if (!isSynthetic(id)) {
      LandingHeatmap.record(region, at.x(), at.z());
      return;
    }
    String world = at.world() != null ? at.world().name() : null;
    BitTeleportLog.append(new MockTeleportRecord(id, world, at.x(), at.y(), at.z(), region,
        d != null ? d.attempts : 0L, d != null ? d.processingTime : 0L,
        System.currentTimeMillis()));
  }
}
