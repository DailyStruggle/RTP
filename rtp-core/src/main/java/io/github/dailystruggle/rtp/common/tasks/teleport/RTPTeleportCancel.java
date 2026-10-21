package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class RTPTeleportCancel extends RTPRunnable {
  public static final List<Consumer<RTPTeleportCancel>> preActions = new CopyOnWriteArrayList<>();
  public static final List<Consumer<RTPTeleportCancel>> postActions = new CopyOnWriteArrayList<>();
  private final UUID playerId;

  public RTPTeleportCancel(UUID playerId) {
    this.playerId = playerId;
  }

  @SuppressWarnings("unchecked") // configParserMap keyed by EconomyKeys.class guarantees the parser's E
  public static void refund(UUID playerId) {
    if (ActiveConfigs.get() == null || ActiveConfigs.get().configParserMap == null) return;
    ConfigParser<EconomyKeys> eco =
        (ConfigParser<EconomyKeys>) ActiveConfigs.get().configParserMap.get(EconomyKeys.class);
    if (eco == null) return;
    Object configValue = eco.getConfigValue(EconomyKeys.refundOnCancel, Boolean.TRUE);

    boolean refund;
    if (configValue instanceof Boolean) refund = (Boolean) configValue;
    else if (configValue instanceof String) refund = Boolean.parseBoolean((String) configValue);
    else refund = Boolean.parseBoolean(configValue.toString());

    // check if teleporting
    TeleportData data = TeleportSessions.current().latestTeleportData.get(playerId);
    if (data == null) return;
    if (data.completed) return;

    if (data.selectedCoords != null && data.targetRegion != null) {
      // (Reservation cleanup handled by task cancellation)
    }

    // reset player data
    TeleportData repData = TeleportSessions.current().priorTeleportData.get(playerId);
    TeleportSessions.current().priorTeleportData.remove(playerId);
    if (repData != null) TeleportSessions.current().latestTeleportData.put(playerId, repData);
    else TeleportSessions.current().latestTeleportData.remove(playerId);

    if (RTPRuntime.economy() != null && data.cost != 0.0 && refund && data.sender instanceof RTPPlayer) {
      final double refundAmount = data.cost;
      final UUID refundTarget = data.sender.uuid();
      io.github.dailystruggle.rtp.common.economy.EconomyHop.run(
          () -> RTPRuntime.economy().give(refundTarget, refundAmount));
    }

    TeleportSessions.current().processingPlayers.remove(playerId);
  }

  public static void message(UUID playerId) {
    String msg = ActiveConfigs.get().getConfigValue(PlayerMessages.teleportCancel, "").toString();
    RTPRuntime.serverAccessor().sendMessage(playerId, msg);
  }

  @Override
  public void run() {
    preActions.forEach(rtpTeleportCancelConsumer -> rtpTeleportCancelConsumer.accept(this));

    // check if teleporting
    TeleportData data = TeleportSessions.current().latestTeleportData.get(playerId);
    if (data == null) return;
    if (data.completed) return;
    if (data.nextTask == null) return;

    // check no-cancel permission
    RTPPlayer player = RTPRuntime.serverAccessor().getPlayer(playerId);
    if (player != null && player.isOnline() && player.hasPermission("rtp.noCancel")) return;

    data.nextTask.setCancelled(true);

    // dump location back onto the pile
    //        if( data.selectedCoords!=null ) data.targetRegion.keptLocations.add( new
    // AbstractMap.SimpleEntry<>( data.selectedCoords,data.attempts) );

    refund(playerId);

    message(playerId);

    postActions.forEach(rtpTeleportCancelConsumer -> rtpTeleportCancelConsumer.accept(this));
  }

  public UUID getPlayerId() {
    return playerId;
  }
}
