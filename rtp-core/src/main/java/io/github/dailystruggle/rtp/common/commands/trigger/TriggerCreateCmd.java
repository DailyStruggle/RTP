package io.github.dailystruggle.rtp.common.commands.trigger;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TriggerCreateCmd extends BaseRTPCmdImpl {

  public TriggerCreateCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "create";
  }

  @Override
  public String permission() {
    return "rtp.trigger";
  }

  @Override
  public String description() {
    return "Create a physical trigger around your current position: /rtp trigger create <id> <actionId> [radius]";
  }

  @Override
  public boolean onCommand(
      UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(senderId, parameterValues, null);

    RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
    if (!(sender instanceof RTPPlayer player)) {
      RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] Only in-game players can create physical triggers at current position.");
      return true;
    }

    RTPLocation loc = player.getLocation();
    if (loc == null || loc.world() == null) {
      RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] Could not resolve your current location.");
      return true;
    }

    String id = "trigger_" + System.currentTimeMillis() % 10000;
    String actionId = "scatter";
    int minX = loc.getBlockX() - 1, minY = loc.getBlockY() - 1, minZ = loc.getBlockZ() - 1;
    int maxX = loc.getBlockX() + 1, maxY = loc.getBlockY() + 1, maxZ = loc.getBlockZ() + 1;

    PhysicalTriggerSpec spec = new PhysicalTriggerSpec(
        id, PhysicalTriggerSpec.TriggerType.STEP_IN,
        loc.world().name(),
        minX, minY, minZ,
        maxX, maxY, maxZ,
        actionId, 5L);

    RTP.triggerManager.registerTrigger(spec);
    RTP.serverAccessor.sendMessage(
        senderId, senderId, "[RTP] Physical trigger '" + id + "' created for action '" + actionId + "'! (["
            + minX + "," + minY + "," + minZ + "] to [" + maxX + "," + maxY + "," + maxZ + "])");
    return true;
  }
}
