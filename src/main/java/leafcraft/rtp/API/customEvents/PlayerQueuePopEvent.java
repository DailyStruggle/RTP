package leafcraft.rtp.API.customEvents;

import leafcraft.rtp.API.selection.region.TeleportRegionInterface;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class PlayerQueuePopEvent extends Event {
    private final TeleportRegionInterface region;
    private final UUID playerId;
    private final Location location;

    private static final HandlerList HANDLERS_LIST = new HandlerList();

    public PlayerQueuePopEvent(TeleportRegionInterface region, UUID playerId, Location location) {
        super(true);
        this.region = region;
        this.playerId = playerId;
        this.location = location;
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

    public Location getLocation() {
        return location;
    }

    public TeleportRegionInterface getRegion() {
        return region;
    }
}
