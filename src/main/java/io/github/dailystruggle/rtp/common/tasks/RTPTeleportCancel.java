package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class RTPTeleportCancel extends RTPRunnable {
    public static final List<Consumer<RTPTeleportCancel>> preActions = new ArrayList<>();
    public static final List<Consumer<RTPTeleportCancel>> postActions = new ArrayList<>();
    private final UUID playerId;

    public RTPTeleportCancel(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public void run() {
        preActions.forEach(rtpTeleportCancelConsumer -> rtpTeleportCancelConsumer.accept(this));

        //check if teleporting
        TeleportData data = RTP.getInstance().latestTeleportData.get(playerId);
        if(data == null) return;
        if(data.completed) return;
        if(data.nextTask == null) return;

        //check no-cancel permission
        RTPPlayer player = RTP.serverAccessor.getPlayer(playerId);
        if(player!=null && player.isOnline() && player.hasPermission("rtp.noCancel")) return;

        data.nextTask.setCancelled(true);

        //dump location back onto the pile
        if(data.selectedLocation!=null) data.targetRegion.locationQueue.add(new ImmutablePair<>(data.selectedLocation,data.attempts));

        refund(playerId);

        message(playerId);

        postActions.forEach(rtpTeleportCancelConsumer -> rtpTeleportCancelConsumer.accept(this));
    }

    public static void refund(UUID playerId) {
        ConfigParser<EconomyKeys> eco = (ConfigParser<EconomyKeys>) RTP.getInstance().configs.configParserMap.get(EconomyKeys.class);
        Object configValue = eco.getConfigValue(EconomyKeys.refundOnCancel, true);
        boolean refund = Boolean.getBoolean(configValue.toString());

        //check if teleporting
        TeleportData data = RTP.getInstance().latestTeleportData.get(playerId);
        if(data == null) return;
        if(data.completed) return;
        if(data.nextTask == null) return;

        //reset player data
        TeleportData repData = RTP.getInstance().priorTeleportData.get(playerId);
        RTP.getInstance().priorTeleportData.remove(playerId);
        if(repData!=null)
            RTP.getInstance().latestTeleportData.put(playerId,repData);
        else
            RTP.getInstance().latestTeleportData.remove(playerId);

        if(RTP.economy !=null && data.cost != 0.0) {
            if (refund && data.sender instanceof RTPPlayer player1) {
                RTP.economy.give(player1.uuid(),data.cost);
            }
        }

        RTP.getInstance().processingPlayers.remove(playerId);
    }

    public static void message(UUID playerId) {
        ConfigParser<LangKeys> lang = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);
        String msg = lang.getConfigValue(LangKeys.teleportCancel,"").toString();
        RTP.serverAccessor.sendMessage(playerId,msg);
    }



    public UUID getPlayerId() {
        return playerId;
    }
}