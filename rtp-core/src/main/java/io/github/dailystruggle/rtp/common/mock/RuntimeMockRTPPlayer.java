package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;

import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
  private final java.util.concurrent.atomic.AtomicBoolean online = new java.util.concurrent.atomic.AtomicBoolean(true);
  private final List<RTPLocation> destinations = new CopyOnWriteArrayList<>();
  private final CompletableFuture<RTPLocation> teleportFuture = new CompletableFuture<>();

  public RuntimeMockRTPPlayer(UUID uuid, String name, RTPLocation initialLocation) {
    this.uuid = uuid;
    this.name = name;
    this.location = initialLocation;
  }

  @Override
  public CompletableFuture<Boolean> setLocation(RTPLocation to) {
    this.location = to;
    destinations.add(to);
    teleportFuture.complete(to);
    RTPRuntime.log(
        Level.INFO,
        "[MOCK] player '" + name + "' (" + uuid + ") teleported to "
            + (to == null ? "null" : to.world().name() + " " + to.x() + "," + to.y() + "," + to.z()));
    return CompletableFuture.completedFuture(true);
  }

  /** Every {@link #setLocation} destination in call order; immutable snapshot. */
  public List<RTPLocation> destinations() {
    return Collections.unmodifiableList(new java.util.ArrayList<>(destinations));
  }

  public int setLocationCount() {
    return destinations.size();
  }

  /** Completes with the first {@link #setLocation} destination; never completes otherwise. */
  public CompletableFuture<RTPLocation> teleportFuture() {
    return teleportFuture.copy();
  }

  @Override
  public RTPLocation getLocation() {
    return location;
  }

  @Override
  public boolean isOnline() {
    return online.get();
  }

  public void setOnline(boolean online) {
    this.online.set(online);
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
    RTPRuntime.log(Level.INFO, "[MOCK->" + name + "] " + message);
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
    RTPRuntime.log(Level.INFO, "[MOCK->" + name + "] performCommand: " + command);
  }

  @Override
  public RTPCommandSender clone() {
    RuntimeMockRTPPlayer copy = new RuntimeMockRTPPlayer(uuid, name, location);
    copy.online.set(this.online.get());
    return copy;
  }
}
