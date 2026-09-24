package io.github.dailystruggle.rtp.common.commands.trigger;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TriggerListCmd extends BaseRTPCmdImpl {

  public TriggerListCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "list";
  }

  @Override
  public String permission() {
    return "rtp.trigger";
  }

  @Override
  public String description() {
    return "List all registered physical triggers";
  }

  @Override
  public boolean onCommand(
      UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(senderId, parameterValues, null);

    Collection<PhysicalTriggerSpec> triggers = RTP.triggerManager.getTriggers();
    if (triggers.isEmpty()) {
      RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] No physical triggers registered.");
      return true;
    }

    RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] Registered physical triggers (" + triggers.size() + "):");
    for (PhysicalTriggerSpec t : triggers) {
      RTP.serverAccessor.sendMessage(
          senderId, senderId,
          " - " + t.id() + " [" + t.type() + "] -> " + t.actionId() + " (" + t.worldName() + ":" + t.minX() + "," + t.minY() + "," + t.minZ() + ")");
    }
    return true;
  }
}
