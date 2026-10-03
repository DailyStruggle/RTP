package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.effectsapi.common.hologram.HologramHandle;
import io.github.dailystruggle.effectsapi.common.hologram.HologramProvider;
import io.github.dailystruggle.effectsapi.common.hologram.HologramRegistry;
import io.github.dailystruggle.effectsapi.common.volumetric.Vector3d;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.bukkitplatform.commands.parameters.OnlinePlayerParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.IntegerParameter;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Diagnostic test command for hologram and floating display providers (Paper TextDisplay,
 * DecentHolograms, HolographicDisplays, or fallback).
 *
 * <p>Usage: {@code /rtp test hologram [player:<name>] [seconds:1..60]}
 *
 * <p>Spawns a temporary floating hologram in front of the target player (or caller),
 * displaying the resolved provider and a live ticking countdown before automatically
 * closing and despawning via {@link HologramHandle#close()}.
 */
public class TestHologramCmd extends BaseRTPCmdImpl {
  public static final int MIN_SECONDS = 1;
  public static final int MAX_SECONDS = 60;
  public static final int DEFAULT_SECONDS = 5;

  public TestHologramCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter(
        "player",
        new OnlinePlayerParameter(
            "rtp.test", "target player to spawn test hologram for (defaults to caller)", (sender, s) -> true));
    addParameter(
        "seconds",
        new IntegerParameter(
            "rtp.test", "duration in seconds before despawning (1..60, default 5)", (uuid, s) -> true));
  }

  @Override
  public String name() {
    return "hologram";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "spawn a temporary test hologram to verify active hologram provider";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    HologramProvider provider = HologramRegistry.getProvider();
    String providerName = provider != null ? provider.getClass().getSimpleName() : "None";

    // Resolve target player: parameter first, else caller
    RTPPlayer target = null;
    List<String> playerNames = parameterValues.get("player");
    if (playerNames != null && !playerNames.isEmpty()) {
      target = RTP.serverAccessor.getPlayer(playerNames.get(0));
      if (target == null) {
        String msg = "&c[RTP test/hologram] unknown player: " + playerNames.get(0);
        RTP.serverAccessor.sendMessage(callerId, msg);
        RTP.log(Level.WARNING, msg);
        return true;
      }
    } else if (!callerId.equals(RTPAPI.serverId)) {
      target = RTP.serverAccessor.getPlayer(callerId);
    }

    int seconds = DEFAULT_SECONDS;
    List<String> secValues = parameterValues.get("seconds");
    if (secValues != null && !secValues.isEmpty()) {
      try {
        seconds = Integer.parseInt(secValues.get(0));
      } catch (NumberFormatException ignored) {
        // use default
      }
    }
    seconds = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));

    // If caller is console and no player specified, output provider status dump
    if (target == null) {
      String msg = "&a[RTP test/hologram] Active HologramProvider: &b" + providerName
          + " &7(Specify player:<name> to spawn in-world)";
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.INFO, "[RTP test/hologram] Active HologramProvider: " + providerName);
      return true;
    }

    RTPLocation loc = target.getLocation();
    if (loc == null || loc.world() == null) {
      String msg = "&c[RTP test/hologram] Unable to resolve player location.";
      RTP.serverAccessor.sendMessage(callerId, msg);
      return true;
    }

    // Position 2 blocks ahead / eye height
    String worldName = loc.world().name();
    Vector3d spawnPos = new Vector3d(loc.x() + 1.5, loc.y() + 1.8, loc.z() + 1.5);
    String holoId = "rtp_test_" + UUID.randomUUID().toString().substring(0, 8);

    final int totalSec = seconds;
    List<String> initialLines = List.of(
        "&a&lLeafRTP Hologram Test",
        "&7Provider: &b" + providerName,
        "&eDespawning in &c" + totalSec + "s"
    );

    HologramHandle handle;
    try {
      handle = provider != null
          ? provider.spawnHologram(holoId, worldName, spawnPos, initialLines)
          : null;
    } catch (Throwable t) {
      String err = "&c[RTP test/hologram] Failed to spawn hologram: " + t.getMessage();
      RTP.serverAccessor.sendMessage(callerId, err);
      RTP.log(Level.WARNING, "[RTP test/hologram] Spawn failed: " + t.getMessage(), t);
      return true;
    }

    if (handle == null) {
      String msg = "&c[RTP test/hologram] No hologram spawned (provider returned null handle).";
      RTP.serverAccessor.sendMessage(callerId, msg);
      return true;
    }

    String startMsg = "&a[RTP test/hologram] Spawned test hologram (&b" + providerName
        + "&a) for &e" + totalSec + "s&a at &7" + worldName + " " + spawnPos.x() + ", " + spawnPos.y() + ", " + spawnPos.z();
    RTP.serverAccessor.sendMessage(callerId, startMsg);

    AtomicInteger remaining = new AtomicInteger(totalSec);
    AtomicBoolean closed = new AtomicBoolean(false);
    final Object[] taskHolder = new Object[1];

    Runnable ticker = () -> {
      int left = remaining.decrementAndGet();
      if (left > 0 && !closed.get()) {
        try {
          handle.updateLines(List.of(
              "&a&lLeafRTP Hologram Test",
              "&7Provider: &b" + providerName,
              "&eDespawning in &c" + left + "s"
          ));
        } catch (Throwable t) {
          RTP.log(Level.FINE, "[RTP test/hologram] Update lines failed: " + t.getMessage());
        }
      } else {
        if (closed.compareAndSet(false, true)) {
          if (taskHolder[0] != null && RTP.scheduler != null) {
            try {
              RTP.scheduler.cancelTask(taskHolder[0]);
            } catch (Throwable ignored) {}
          }
          try {
            handle.close();
            RTP.log(Level.FINE, "[RTP test/hologram] Closed test hologram " + holoId);
          } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP test/hologram] Failed to close handle: " + t.getMessage(), t);
          }
        }
      }
    };

    if (RTP.scheduler != null) {
      // 20 ticks = 1 second
      taskHolder[0] = RTP.scheduler.runTaskTimerAsynchronously(ticker, 20L, 20L);
    }

    return true;
  }
}
