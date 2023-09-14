package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.selection.region.Region;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class PlayerQueuePushEvent extends Event {
    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private final Region region;
    private final UUID playerId;

    public PlayerQueuePushEvent( Region region, UUID playerId ) {
        super( !Bukkit.isPrimaryThread() );
        this.region = region;
        this.playerId = playerId;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public Region getRegion() {
        return region;
    }

    public UUID getPlayerId() {
        return playerId;
    }
}
