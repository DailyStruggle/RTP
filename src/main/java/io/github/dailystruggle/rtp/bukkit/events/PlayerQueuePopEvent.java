package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.selection.region.Region;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class PlayerQueuePopEvent extends Event {
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

    public PlayerQueuePopEvent(Region region, UUID playerId) {
        super(true);
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
