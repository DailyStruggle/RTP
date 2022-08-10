package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.tasks.DoTeleport;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PostTeleportEvent extends Event {
    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private final DoTeleport doTeleport;

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    public PostTeleportEvent(DoTeleport doTeleport) {
        super(!Bukkit.isPrimaryThread());
        this.doTeleport = doTeleport;
    }

    public DoTeleport getDoTeleport() {
        return doTeleport;
    }
}
