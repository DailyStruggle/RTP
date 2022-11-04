package leafcraft.rtp.API.customEvents;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class LoadChunksPlayerEvent extends Event implements Cancellable {
    private final Location to;
    private final Player player;
    private final List<CompletableFuture<Chunk>> chunks;
    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private boolean isCancelled;

    public LoadChunksPlayerEvent(Location to, Player player, List<CompletableFuture<Chunk>> chunks) {
        super(!Bukkit.isPrimaryThread());
        this.to = to;
        this.player = player;
        this.chunks = chunks;
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

    public List<CompletableFuture<Chunk>> getChunks() {
        return chunks;
    }
}