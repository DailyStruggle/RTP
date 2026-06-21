package io.github.dailystruggle.rtp.bukkit.commands;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * Bukkit per-caller reply-renderer supplied to the platform-neutral
 * {@code CoreRtpRoot} (commands-api-ADR-003 / ADR-070). It reproduces the
 * formatting that the former {@code RTPCmdBukkit} command root applied to
 * {@code /rtp} reply lines:
 *
 * <ul>
 *   <li>lines emitted by commands-api's built-in {@code TreeCommand#help()} for
 *       each subcommand (shaped {@code "  - /<full command> <subname>\n    <description>"})
 *       are detected and rendered through {@code SendMessage}'s hover/click
 *       overload so {@code /rtp help} rows stay clickable (SUGGEST_COMMAND), the
 *       way the removed {@code HelpCmd} used to render them;</li>
 *   <li>every other line falls through to the plain {@code SendMessage} sink so
 *       placeholders, {@code &} / hex colour codes, and PAPI are resolved at the
 *       platform boundary instead of leaking to console as raw templates.</li>
 * </ul>
 *
 * <p>The neutral root calls {@link #apply(UUID)} once per invocation to obtain
 * the {@link Consumer} that {@code RTPCmd.compute} writes reply lines to.</p>
 */
public final class BukkitHelpReplyRenderer implements Function<UUID, Consumer<String>> {

  private static final Pattern HELP_SUBCOMMAND_LINE =
      Pattern.compile("^\\s+-\\s+(/\\S[^\\r\\n]*?)\\s*(?:\\R[\\s\\S]*)?$");

  @Override
  public Consumer<String> apply(UUID senderId) {
    final CommandSender sender =
        senderId.equals(RTPAPI.serverId) ? Bukkit.getConsoleSender() : Bukkit.getPlayer(senderId);
    final RTPCommandSender rtpSender = RTP.serverAccessor.getSender(senderId);
    return msg -> {
      if (msg != null && rtpSender != null) {
        Matcher m = HELP_SUBCOMMAND_LINE.matcher(msg);
        if (m.matches()) {
          String click = m.group(1).trim();
          SendMessage.sendMessage(rtpSender, msg, click, click);
          return;
        }
      }
      if (sender != null) {
        SendMessage.sendMessage(sender, msg);
      }
    };
  }
}
