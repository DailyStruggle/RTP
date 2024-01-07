package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.tasks.teleport.DoTeleport;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PostTeleportEvent extends Event {
    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private final DoTeleport doTeleport;

    public PostTeleportEvent( DoTeleport doTeleport ) {
        super( !Bukkit.isPrimaryThread() );
        this.doTeleport = doTeleport;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public DoTeleport getDoTeleport() {
        return doTeleport;
    }
}
