package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.parameters.WorldParameter;

import java.util.Set;

/**
 * Platform-neutral {@link PlatformCommandParameters} implementation backed by
 * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor} for Fabric and NeoForge.
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
