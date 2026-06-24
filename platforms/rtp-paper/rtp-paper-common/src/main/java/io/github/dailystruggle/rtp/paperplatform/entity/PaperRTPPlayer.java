package io.github.dailystruggle.rtp.paperplatform.entity;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.bukkitplatform.entity.BukkitRTPPlayer;
import org.bukkit.entity.Player;

/**
 * Paper-family {@link io.github.dailystruggle.rtp.api.entity.RTPPlayer} implementation.
 *
 * <p>Extends the Spigot-classpath {@link BukkitRTPPlayer} but, because this module compiles against
 * paper-api, calls the per-player view-distance APIs directly instead of resolving them
 * reflectively. The important addition is {@link #setSendViewDistance(int)} /
 * {@link #getSendViewDistance()}: pinning the client <i>send</i> view distance lets the teleport
 * view-distance clamp and steady restore (ADR-072) shrink and ramp the server-side <i>tracking</i>
 * view distance without the client ever receiving a view-distance change, removing the visible
 * render-ring "flash" on arrival.
 */
public class PaperRTPPlayer extends BukkitRTPPlayer {

  public PaperRTPPlayer(Player player) {
    super(player);
  }

  @Override
  public int getViewDistance() {
    return player().getViewDistance();
  }

  @Override
  public void setViewDistance(int viewDistance) {
    player().setViewDistance(viewDistance);
  }

  @Override
  public int getSendViewDistance() {
    return player().getSendViewDistance();
  }

  @Override
  public void setSendViewDistance(int viewDistance) {
    player().setSendViewDistance(viewDistance);
  }

  @Override
  public RTPCommandSender clone() {
    return new PaperRTPPlayer(player());
  }
}
