package io.github.dailystruggle.rtp.common.playerData;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A platform-agnostic, pure Java representation of {@link RTPCommandSender}.
 * Holds zero platform handles, making it safe for long-term retention and file caching.
 */
public class PureDataRTPCommandSender implements RTPCommandSender, Cloneable {
  protected final UUID uuid;
  protected final String name;
  protected final long cooldown;
  protected final long delay;
  protected final Set<String> effectivePermissions;

  public PureDataRTPCommandSender(
      UUID uuid,
      String name,
      long cooldown,
      long delay,
      Set<String> effectivePermissions) {
    this.uuid = Objects.requireNonNull(uuid, "uuid must not be null");
    this.name = (name != null) ? name : uuid.toString();
    this.cooldown = cooldown;
    this.delay = delay;
    this.effectivePermissions = (effectivePermissions != null)
        ? Set.copyOf(effectivePermissions)
        : Collections.emptySet();
  }

  public PureDataRTPCommandSender(RTPCommandSender sender) {
    this.uuid = sender.uuid();
    this.name = sender.name();
    this.cooldown = sender.cooldown();
    this.delay = sender.delay();
    this.effectivePermissions = Set.copyOf(sender.getEffectivePermissions());
  }

  @Override
  public UUID uuid() {
    return uuid;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean isSelfContained() {
    return true;
  }

  @Override
  public RTPCommandSender toPureData() {
    return this;
  }

  @Override
  public @Nullable Object getLiveSender() {
    if (RTP.serverAccessor != null) {
      RTPCommandSender live = RTP.serverAccessor.getSender(uuid);
      if (live != null && !live.isSelfContained()) {
        return live.getLiveSender();
      }
    }
    return null;
  }

  @Override
  public boolean hasPermission(String permission) {
    if (permission == null) return false;
    if (effectivePermissions.contains("rtp.*") || effectivePermissions.contains("*")) return true;
    return effectivePermissions.contains(permission.toLowerCase(Locale.ROOT));
  }

  @Override
  public void sendMessage(String message) {
    if (RTP.serverAccessor != null) {
      RTP.serverAccessor.sendMessage(uuid, message);
    }
  }

  @Override
  public long cooldown() {
    return cooldown;
  }

  @Override
  public long delay() {
    return delay;
  }

  @Override
  public Set<String> getEffectivePermissions() {
    return effectivePermissions;
  }

  @Override
  public void performCommand(@Nullable RTPPlayer player, String command) {
    if (RTP.serverAccessor != null) {
      RTPCommandSender live = RTP.serverAccessor.getSender(uuid);
      if (live != null && !live.isSelfContained()) {
        live.performCommand(player, command);
      }
    }
  }

  @Override
  public PureDataRTPCommandSender clone() {
    try {
      return (PureDataRTPCommandSender) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || !(obj instanceof RTPCommandSender)) return false;
    RTPCommandSender that = (RTPCommandSender) obj;
    return Objects.equals(this.uuid, that.uuid());
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid);
  }

  @Override
  public String toString() {
    return "PureDataRTPCommandSender[uuid=" + uuid + ", name=" + name + "]";
  }
}
