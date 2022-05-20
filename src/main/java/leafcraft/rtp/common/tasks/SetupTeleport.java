package leafcraft.rtp.common.tasks;

import leafcraft.rtp.common.RTP;
import leafcraft.rtp.common.playerData.TeleportData;
import leafcraft.rtp.common.selection.region.Region;
import leafcraft.rtp.common.substitutions.RTPCommandSender;
import leafcraft.rtp.common.substitutions.RTPPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public record SetupTeleport(UUID sender,
                            UUID player,
                            @NotNull Region region,
                            @Nullable Set<String> biomes) implements Runnable {
    @Override
    public void run() {
        RTP rtp = RTP.getInstance();

        RTPCommandSender sender = rtp.serverAccessor.getSender(this.sender);
        if(sender == null) return;
        RTPPlayer player = rtp.serverAccessor.getPlayer(this.player);
        if(player == null) return;

        RTP.log(Level.WARNING,"[RTP] at setupTeleport");
        sender.sendMessage("[RTP] at setupTeleport");
        player.sendMessage("[RTP] at setupTeleport");

        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(this.sender);

        RTP.log(Level.WARNING,"[RTP] time(ms) - " + TimeUnit.NANOSECONDS.toMillis(teleportData.time));
        player.sendMessage("[RTP] time(ms) - " + TimeUnit.NANOSECONDS.toMillis(teleportData.time));
        sender.sendMessage("[RTP] time(ms) - " + TimeUnit.NANOSECONDS.toMillis(teleportData.time));
        teleportData.time = 0; //temporary refund for command purposes
    }
}
