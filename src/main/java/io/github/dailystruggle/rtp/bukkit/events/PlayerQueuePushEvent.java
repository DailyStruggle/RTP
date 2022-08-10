package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.selection.region.Region;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class PlayerQueuePushEvent extends Event {
    private final Region region;
    private final UUID playerId;
    private static final HandlerList HANDLERS_LIST = new HandlerList();

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    public PlayerQueuePushEvent(Region region, UUID playerId) {
        super(!Bukkit.isPrimaryThread());
        this.region = region;
        this.playerId = playerId;
    }

    public Region getRegion() {
        return region;
    }

    public UUID getPlayerId() {
        return playerId;
    }
}
