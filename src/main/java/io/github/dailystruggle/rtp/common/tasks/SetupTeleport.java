package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.ChunkSet;
import io.github.dailystruggle.rtp.common.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

public record SetupTeleport(RTPCommandSender sender,
                            RTPPlayer player,
                            @NotNull Region region,
                            @Nullable Set<String> biomes) implements Runnable {
    public static final List<Consumer<SetupTeleport>> preActions = new ArrayList<>();
    public static final List<Consumer<SetupTeleport>> postActions = new ArrayList<>();

    @Override
    public void run() {
        preActions.forEach(consumer -> consumer.accept(this));

        RTP rtp = RTP.getInstance();

        RTP.log(Level.WARNING,"[RTP] at setupTeleport");
        player.sendMessage("[RTP] at setupTeleport");

        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(sender.uuid());

        teleportData.targetRegion = this.region;

        teleportData.time = 0; //temporary refund for command purposes

        if(this.region.hasLocation(player.uuid())) {
            teleportData.originalLocation = player.getLocation();

            RTPLocation location = this.region.getLocation(sender.uuid(), player.uuid(), null);

            rtp.loadChunksPipeline.add(new LoadChunks(this.sender,this.player,location,this.region));
            postActions.forEach(consumer -> consumer.accept(this));
            return;
        }

        teleportData.time = teleportData.priorTime;
        sender.sendMessage("did not teleport, no spots identified");

        postActions.forEach(consumer -> consumer.accept(this));

        //todo: append player queue or load chunks
    }
}
