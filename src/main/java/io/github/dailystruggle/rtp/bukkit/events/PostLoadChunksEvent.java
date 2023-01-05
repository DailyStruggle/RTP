package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.tasks.teleport.LoadChunks;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PostLoadChunksEvent extends Event {
    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private final LoadChunks loadChunks;

    public PostLoadChunksEvent(LoadChunks loadChunks) {
        super(!Bukkit.isPrimaryThread());
        this.loadChunks = loadChunks;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public LoadChunks getLoadChunks() {
        return loadChunks;
    }
}
