package io.github.dailystruggle.rtp.bukkit.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.MessageSink;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * Bukkit-family {@link MessageSink}. Delivers reply templates through
 * {@link SendMessage#sendMessage(CommandSender, String)} so placeholders,
 * legacy {@code &} / hex colour codes, and PAPI are resolved at the platform
 * boundary, and so the REQ-RTP-S-004 interceptor fires exactly once for audit
 * coverage.
 *
 * <p>This replaces the per-command {@code messageMethodFactory} that
 * {@code RTPCmdBukkit} previously set and the {@code msgInvalidCommand} /
 * {@code msgBadParameter} overrides that {@code BukkitBaseRTPCmd} previously
 * carried: the platform-agnostic {@code BaseRTPCmd} message defaults now
 * pre-format error templates and route them here, while normal replies route
 * here verbatim, matching the prior behaviour byte-for-byte.</p>
 */
public final class BukkitMessageSink implements MessageSink {

  @Override
  public void send(UUID callerId, String rawTemplate) {
    if (rawTemplate == null || rawTemplate.isEmpty()) return;
    CommandSender sender =
        callerId.equals(CommandsAPI.serverId)
            ? Bukkit.getConsoleSender()
            : Bukkit.getPlayer(callerId);
    if (sender != null) {
      SendMessage.sendMessage(sender, rawTemplate);
    }
  }
}
