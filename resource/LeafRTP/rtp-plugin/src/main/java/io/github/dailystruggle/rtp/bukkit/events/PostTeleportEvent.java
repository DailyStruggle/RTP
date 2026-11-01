package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PostTeleportEvent extends Event {
  private static final HandlerList HANDLERS_LIST = new HandlerList();
  private final TeleportPipelineTask doTeleport;

  public PostTeleportEvent(TeleportPipelineTask doTeleport) {
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

  public TeleportPipelineTask getDoTeleport() {
    return doTeleport;
  }
}
