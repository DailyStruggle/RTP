package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PreTeleportEvent extends Event implements Cancellable {
  private static final HandlerList HANDLERS_LIST = new HandlerList();
  private final TeleportPipelineTask doTeleport;
  private boolean cancelled = false;

  public PreTeleportEvent(TeleportPipelineTask doTeleport) {
    super(!Bukkit.isPrimaryThread());
    this.doTeleport = doTeleport;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS_LIST;
  }

  @Override
  public @NotNull HandlerList getHandlers() {
    return HANDLERS_LIST;
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public void setCancelled(boolean cancel) {
    cancelled = cancel;
  }

  public TeleportPipelineTask getDoTeleport() {
    return doTeleport;
  }
}
