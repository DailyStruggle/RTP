package io.github.dailystruggle.rtp.common.playerData;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A platform-agnostic, pure Java representation of {@link RTPPlayer}.
 * Holds zero platform handles, making it safe for retention and offline storage.
 */
public class PureDataRTPPlayer extends PureDataRTPCommandSender implements RTPPlayer {

  public PureDataRTPPlayer(
      UUID uuid,
      String name,
      long cooldown,
      long delay,
      Set<String> effectivePermissions) {
    super(uuid, name, cooldown, delay, effectivePermissions);
  }

  public PureDataRTPPlayer(RTPPlayer player) {
    super(player);
  }

  @Override
  public CompletableFuture<Boolean> setLocation(RTPLocation to) {
    if (RTP.serverAccessor != null) {
      RTPPlayer live = RTP.serverAccessor.getPlayer(uuid);
      if (live != null) {
        return live.setLocation(to);
      }
    }
    return CompletableFuture.completedFuture(false);
  }

  @Override
  public RTPLocation getLocation() {
    if (RTP.serverAccessor != null) {
      RTPPlayer live = RTP.serverAccessor.getPlayer(uuid);
      if (live != null) {
        return live.getLocation();
      }
    }
    return null;
  }

  @Override
  public boolean isOnline() {
    if (RTP.serverAccessor != null) {
      RTPPlayer live = RTP.serverAccessor.getPlayer(uuid);
      return live != null && live.isOnline();
    }
    return false;
  }

  @Override
  public RTPPlayer toPureData() {
    return this;
  }

  @Override
  public PureDataRTPPlayer clone() {
    return (PureDataRTPPlayer) super.clone();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || !(obj instanceof RTPPlayer)) return false;
    RTPPlayer that = (RTPPlayer) obj;
    return Objects.equals(this.uuid, that.uuid());
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid);
  }

  @Override
  public String toString() {
    return "PureDataRTPPlayer[uuid=" + uuid + ", name=" + name + "]";
  }
}
