package io.github.dailystruggle.rtp.common.commands.trigger;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.trigger.PhysicalTriggerManager;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TriggerListCmd extends BaseTriggerCmd {

  public TriggerListCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "list";
  }

  @Override
  public String description() {
    return "List all registered physical triggers";
  }

  @Override
  protected boolean execute(UUID senderId, Map<String, List<String>> parameterValues) {
    Collection<PhysicalTriggerSpec> triggers = PhysicalTriggerManager.instance().getTriggers();
    if (triggers.isEmpty()) {
      RTPRuntime.serverAccessor().sendMessage(senderId, senderId, "[RTP] No physical triggers registered.");
      return true;
    }

    RTPRuntime.serverAccessor().sendMessage(senderId, senderId, "[RTP] Registered physical triggers (" + triggers.size() + "):");
    for (PhysicalTriggerSpec t : triggers) {
      RTPRuntime.serverAccessor().sendMessage(
          senderId, senderId,
          " - " + t.id() + " [" + t.type() + "] -> " + t.actionId() + " (" + t.worldName() + ":" + t.minX() + "," + t.minY() + "," + t.minZ() + ")");
    }
    return true;
  }
}
