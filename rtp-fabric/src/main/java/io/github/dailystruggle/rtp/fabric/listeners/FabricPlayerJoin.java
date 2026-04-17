package io.github.dailystruggle.rtp.fabric.listeners;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public class FabricPlayerJoin {
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (handler.player.hasPermissionLevel(2)) { // Basic permission check
                Region region = RTP.selectionAPI.getRegion(RTP.serverAccessor.getPlayer(handler.player.getUuid()));
                if (region == null) return;
                region.queue(handler.player.getUuid());
            }
        });
    }
}
