package io.github.dailystruggle.rtp.common.commands.trigger;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TriggerRemoveCmd extends BaseTriggerCmd {

  public TriggerRemoveCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "remove";
  }

  @Override
  public String description() {
    return "Remove a physical trigger: /rtp trigger remove <id>";
  }

  @Override
  protected boolean execute(UUID senderId, Map<String, List<String>> parameterValues) {
    RTP.serverAccessor.sendMessage(
        senderId, senderId, "[RTP] Use /rtp trigger remove <id> to delete a physical trigger.");
    return true;
  }
}
