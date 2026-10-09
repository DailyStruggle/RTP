package io.github.dailystruggle.rtp.paperplatform.entity;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.bukkitplatform.entity.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkitplatform.world.BukkitRTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.World;
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
 *
 * <p>In addition, {@link #setLocation(RTPLocation)} routes destination teleportation through
 * Paper's native asynchronous {@link Player#teleportAsync(Location)} API. This loads the arrival
 * chunk asynchronously off the main thread before transitioning the entity, preventing synchronous
 * chunk loads (S-005) when the player ticks on arrival. When the caller is on the main thread and
 * the destination chunk is already loaded, {@link #tryDirectTeleport} teleports inline instead,
 * skipping the tick {@code teleportAsync} waits in Paper's process queue (ADR-005 Amendment 2,
 * {@code performance.yml teleportPath}).
 */
public class PaperRTPPlayer extends BukkitRTPPlayer {

  public PaperRTPPlayer(Player player) {
    super(player);
  }

  @Override
  public CompletableFuture<Boolean> setLocation(RTPLocation to) {
    World world = ((BukkitRTPWorld) to.world()).world();
    double x = to.x() + 0.5;
    double y = to.y();
    double z = to.z() + 0.5;
    Location current = player().getLocation();
    Location location =
        new Location(
            world,
            x,
            y,
            z,
            current != null ? current.getYaw() : 0f,
            current != null ? current.getPitch() : 0f);

    CompletableFuture<Boolean> future = new CompletableFuture<>();
    if (tryDirectTeleport(location, to.x(), to.z(), future)) return future;
    try {
      player()
          .teleportAsync(location)
          .whenComplete(
              (success, throwable) -> {
                if (throwable != null) {
                  RTP.log(Level.WARNING, "[RTP] teleportAsync failed", throwable);
                  future.complete(false);
                } else {
                  future.complete(Boolean.TRUE.equals(success));
                }
              });
    } catch (Throwable t) {
      // S-004: never silently swallow a teleport failure. Fall back to sync teleport on the scheduler.
      RTP.log(Level.WARNING, "[RTP] teleportAsync threw unexpectedly, falling back to sync teleport", t);
      Runnable tpTask = () -> future.complete(player().teleport(location));
      RTP.scheduler.runTask(tpTask);
    }
    return future;
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
