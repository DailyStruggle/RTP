package io.github.dailystruggle.rtp.common.commands.trigger;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared base command implementation for physical trigger commands.
 * Eliminates duplicate forward command routing and player resolution boilerplate.
 */
public abstract class BaseTriggerCmd extends BaseRTPCmdImpl {

  public BaseTriggerCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String permission() {
    return "rtp.trigger";
  }

  @Override
  public boolean onCommand(
      UUID senderId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
    if (nextCommand != null) {
      return nextCommand.onCommand(senderId, parameterValues, null);
    }
    return execute(senderId, parameterValues);
  }

  /**
   * Execute the trigger command after nextCommand forwarding check has passed.
   *
   * @param senderId        sender UUID
   * @param parameterValues parsed parameters
   * @return true if command execution was handled
   */
  protected abstract boolean execute(UUID senderId, Map<String, List<String>> parameterValues);

  /**
   * Resolves the sender as an in-game player. If the sender is not a player,
   * sends the provided error message and returns null.
   *
   * @param senderId sender UUID
   * @param notPlayerMessage error message to send if sender is not an in-game player
   * @return the resolved {@link RTPPlayer}, or null if console/non-player
   */
  @Nullable
  protected RTPPlayer requirePlayer(UUID senderId, String notPlayerMessage) {
    RTPPlayer player = RTP.serverAccessor.getPlayer(senderId);
    if (player == null) {
      RTP.serverAccessor.sendMessage(senderId, senderId, notPlayerMessage);
      return null;
    }
    return player;
  }
}
