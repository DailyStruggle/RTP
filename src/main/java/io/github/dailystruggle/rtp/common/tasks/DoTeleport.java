package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.ChunkSet;
import io.github.dailystruggle.rtp.common.substitutions.RTPLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

public record DoTeleport(RTPCommandSender sender,
                         RTPPlayer player,
                         RTPLocation location,
                         Region region) implements Runnable {
    public static final List<Consumer<DoTeleport>> preActions = new ArrayList<>();
    public static final List<Consumer<DoTeleport>> postActions = new ArrayList<>();

    @Override
    public void run() {
        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(this.sender);

        preActions.forEach(consumer -> consumer.accept(this));

        RTP.log(Level.WARNING,"[RTP] at doTeleport");

        //todo: run pre-teleport event
        player.setLocation(location);


        RTP.getInstance().chunkCleanupPipeline.add(new ChunkCleanup(location,region));
        postActions.forEach(consumer -> consumer.accept(this));
    }
}
