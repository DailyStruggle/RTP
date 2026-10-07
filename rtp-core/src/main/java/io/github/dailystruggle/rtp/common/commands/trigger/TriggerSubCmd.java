package io.github.dailystruggle.rtp.common.commands.trigger;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.common.RTP;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Command for managing physical world triggers (/rtp trigger create/remove/list).
 */
public class TriggerSubCmd extends BaseTriggerCmd {

  public TriggerSubCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addSubCommand(new TriggerCreateCmd(this));
    addSubCommand(new TriggerRemoveCmd(this));
    addSubCommand(new TriggerListCmd(this));
  }

  @Override
  public String name() {
    return "trigger";
  }

  @Override
  public String description() {
    return "Create, remove, or list physical world triggers";
  }

  @Override
  protected boolean execute(UUID senderId, Map<String, List<String>> parameterValues) {
    RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
    if (sender == null || (!sender.hasPermission("rtp.trigger") && !sender.hasPermission("rtp.*"))) {
      RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] You don't have permission to manage triggers.");
      return true;
    }

    RTP.serverAccessor.sendMessage(
        senderId, senderId, "[RTP] Usage: /rtp trigger <create|remove|list> [args...]");
    return true;
  }
}
