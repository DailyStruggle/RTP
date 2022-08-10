package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.tasks.LoadChunks;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PreLoadChunksEvent extends Event implements Cancellable {
    private boolean cancelled = false;
    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private final LoadChunks loadChunks;

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    public PreLoadChunksEvent(LoadChunks loadChunks) {
        super(!Bukkit.isPrimaryThread());
        this.loadChunks = loadChunks;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    public LoadChunks getLoadChunks() {
        return loadChunks;
    }
}
