package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.tasks.teleport.SetupTeleport;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PostSetupTeleportEvent extends Event {
    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private final SetupTeleport setupTeleport;

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    public PostSetupTeleportEvent(SetupTeleport setupTeleport) {
        super(!Bukkit.isPrimaryThread());
        this.setupTeleport = setupTeleport;
    }

    public SetupTeleport getSetupTeleport() {
        return setupTeleport;
    }
}
