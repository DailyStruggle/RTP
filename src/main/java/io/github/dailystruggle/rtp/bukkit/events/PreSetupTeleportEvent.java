package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.tasks.SetupTeleport;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PreSetupTeleportEvent extends Event implements Cancellable {
    private final SetupTeleport setupTeleport;
    private boolean cancelled = false;
    private static final HandlerList HANDLERS_LIST = new HandlerList();

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    public PreSetupTeleportEvent(SetupTeleport setupTeleport) {
        super(!Bukkit.isPrimaryThread());
        this.setupTeleport = setupTeleport;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    public SetupTeleport getSetupTeleport() {
        return setupTeleport;
    }
}
