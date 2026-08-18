package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Synthetic {@link RTPPlayer} used to exercise queues and pipelines without real accounts.
 * Logs teleports and messages to server logs.
 */
public final class RuntimeMockRTPPlayer implements RTPPlayer {

  private final UUID uuid;
  private final String name;
  private volatile RTPLocation location;
  private volatile boolean online = true;

  public RuntimeMockRTPPlayer(UUID uuid, String name, RTPLocation initialLocation) {
    this.uuid = uuid;
    this.name = name;
    this.location = initialLocation;
  }

  @Override
  public CompletableFuture<Boolean> setLocation(RTPLocation to) {
    this.location = to;
    RTP.log(
        Level.INFO,
        "[MOCK] player '" + name + "' (" + uuid + ") teleported to "
            + (to == null ? "null" : to.world().name() + " " + to.x() + "," + to.y() + "," + to.z()));
    return CompletableFuture.completedFuture(true);
  }

  @Override
  public RTPLocation getLocation() {
    return location;
  }

  @Override
  public boolean isOnline() {
    return online;
  }

  public void setOnline(boolean online) {
    this.online = online;
  }

  @Override
  public UUID uuid() {
    return uuid;
  }

  @Override
  public boolean hasPermission(String permission) {
    return true;
  }

  @Override
  public void sendMessage(String message) {
    RTP.log(Level.INFO, "[MOCK->" + name + "] " + message);
  }

  @Override
  public long cooldown() {
    return 0L;
  }

  @Override
  public long delay() {
    return 0L;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Set<String> getEffectivePermissions() {
    return Collections.emptySet();
  }

  @Override
  public void performCommand(@Nullable RTPPlayer player, String command) {
    RTP.log(Level.INFO, "[MOCK->" + name + "] performCommand: " + command);
  }

  @Override
  public RTPCommandSender clone() {
    RuntimeMockRTPPlayer copy = new RuntimeMockRTPPlayer(uuid, name, location);
    copy.online = this.online;
    return copy;
  }
}
