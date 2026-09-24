package io.github.dailystruggle.rtp.common.commands.back;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implements {@code /rtp back} to undo a teleport and return to the previous location.
 * Mirrors EssentialsX {@code /back} by toggling between origin and destination coordinates.
 */
public class BackCmd extends BaseRTPCmdImpl {

  public BackCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "back";
  }

  @Override
  public String permission() {
    return "rtp.back";
  }

  @Override
  public String description() {
    return "return to previous location before last teleport";
  }

  @Override
  public boolean onCommand(
      UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(senderId, parameterValues, null);

    RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
    if (!(sender instanceof RTPPlayer player)) {
      RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.consoleCmdNotAllowed);
      return true;
    }

    if (!sender.hasPermission("rtp.back") && !sender.hasPermission("rtp.*")) {
      RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.noPerms);
      return true;
    }

    UUID uuid = player.uuid();
    TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
    if (data == null || data.originalCoords == null) {
      RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.noBackLocation);
      return true;
    }

    // Cooldown check: bypass if player has rtp.back.bypasscooldown / rtp.nocooldown or config enables bypass
    boolean bypassCooldown = player.hasPermission("rtp.back.bypasscooldown")
        || player.hasPermission("rtp.nocooldown")
        || !player.hasPermission("rtp.back.cooldown");

    if (!bypassCooldown) {
      long lastTpTime = RTP.getEffectiveLastTeleportTime(uuid);
      if (lastTpTime > 0) {
        long dt = System.currentTimeMillis() - lastTpTime;
        if (dt < 0) dt = Long.MAX_VALUE + dt;
        if (dt < player.cooldown()) {
          RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.cooldownMessage);
          return true;
        }
      }
    }

    RTPCoords targetCoords = data.originalCoords;
    String targetServerId = data.originServerId;
    String targetWorldName = (data.originWorldName != null) ? data.originWorldName : targetCoords.worldName();

    // Prepare back-and-forth toggle: current location becomes the new origin
    RTPLocation currentLocation = player.getLocation();
    RTPCoords swapOrigin = new RTPCoords(
        currentLocation.world().name(),
        currentLocation.x(),
        currentLocation.y(),
        currentLocation.z());
    String swapServerId = (io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap.LIVE != null)
        ? io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap.LIVE.serverId() : null;

    io.github.dailystruggle.rtp.api.RtpTarget target =
        io.github.dailystruggle.rtp.api.RtpTarget.coordinate(
            targetServerId, targetWorldName, targetCoords.x(), targetCoords.y(), targetCoords.z());

    RTPAPI.teleport(uuid, target).whenComplete((result, ex) -> {
      if (result != null && result.isSuccess()) {
        // Update origin so subsequent /rtp back teleports back to previous spot
        data.originalCoords = swapOrigin;
        data.originWorldName = swapOrigin.worldName();
        data.originServerId = swapServerId;
        data.time = System.currentTimeMillis();
        RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.backSuccess);
      } else {
        String msg = (result != null && result.message() != null) ? result.message() : "Teleport failed";
        RTP.serverAccessor.sendMessage(senderId, senderId, msg);
      }
    });

    return true;
  }
}
