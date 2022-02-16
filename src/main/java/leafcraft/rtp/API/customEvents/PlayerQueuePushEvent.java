package leafcraft.rtp.API.customEvents;

import leafcraft.rtp.API.selection.region.TeleportRegionInterface;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class PlayerQueuePushEvent extends Event {
    private final TeleportRegionInterface region;
    private final UUID playerId;

    private static final HandlerList HANDLERS_LIST = new HandlerList();

    public PlayerQueuePushEvent(TeleportRegionInterface region, UUID playerId) {
        super(true);
        this.region = region;
        this.playerId = playerId;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public TeleportRegionInterface getRegion() {
        return region;
    }
}
