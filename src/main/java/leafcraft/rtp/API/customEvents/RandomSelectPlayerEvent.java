package leafcraft.rtp.API.customEvents;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class RandomSelectPlayerEvent extends Event implements Cancellable {
    private final Location to;
    private final Player player;
    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private boolean isCancelled;

    public RandomSelectPlayerEvent(Location to, Player player) {
        this.to = to;
        this.player = player;
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

    public Player getPlayer() {
        return player;
    }
}
