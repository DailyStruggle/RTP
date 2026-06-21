package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.parameters.WorldParameter;

import java.util.Set;

/**
 * Platform-neutral {@link PlatformCommandParameters} source backed entirely by
 * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor}.
 *
 * <p>The {@code player} and {@code world} parameters are nominally
 * "platform-bound" only because their validators historically reached for
 * platform-native lookups. On every platform whose accessor exposes
 * online-player / world enumeration ({@code getPlayer}, {@code getRTPWorld},
 * {@code getSender}, {@code getOnlinePlayerNames}) the logic is identical, so a
 * single accessor-backed source serves them all. This is what lets the Fabric
 * and NeoForge roots share one neutral {@link CoreRtpRoot} instead of
 * hand-rolling byte-for-byte identical parameter classes.</p>
 *
 * <p>The Bukkit family still supplies its own implementation (it gates on
 * {@code CommandSender}-typed Bukkit permission checks and {@code Bukkit.getPlayer}
 * / {@code Bukkit.getWorld}); it does not use this source.</p>
 */
public final class ServerAccessorCommandParameters implements PlatformCommandParameters {

  @Override
  public CommandParameter playerParameter() {
    return new CommandParameter(
        "rtp.other",
        "teleport someone else",
        (uuid, s) -> {
          RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
          if (sender == null || !sender.hasPermission("rtp.other")) return false;
          RTPPlayer target = RTP.serverAccessor.getPlayer(s);
          if (target == null || !target.name().equalsIgnoreCase(s)) return false;
          RTPCommandSender targetSender = RTP.serverAccessor.getSender(target.uuid());
          // Console (non-player sender) is exempt from rtp.notme - parity with RTPCmdBukkit.
          return targetSender == null
              || !(sender instanceof RTPPlayer)
              || !targetSender.hasPermission("rtp.notme");
        }) {
      @Override
      public Set<String> values() {
        return RTP.serverAccessor.getOnlinePlayerNames();
      }
    };
  }

  @Override
  public CommandParameter worldParameter() {
    return new WorldParameter(
        "rtp.world",
        "select a world to teleport to",
        (uuid, s) ->
            RTP.serverAccessor.getRTPWorld(s) != null
                && RTP.serverAccessor.getSender(uuid).hasPermission("rtp.worlds." + s));
  }
}
