package leafcraft.rtp.API.customEvents;

import org.bukkit.Location;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class RandomSelectQueueEvent extends Event implements Cancellable {
    private final Location to;
    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private boolean isCancelled;

    public RandomSelectQueueEvent(Location to) {
        super(true);
        this.to = to;
        this.isCancelled = false;
    }

    @Override
    public boolean isCancelled() {
        return isCancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        isCancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    public Location getTo() {
        return to;
    }
}
