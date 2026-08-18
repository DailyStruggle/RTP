package io.github.dailystruggle.rtp.common.commands.version;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp version} (alias {@code /rtp about}) descriptive subcommand.
 * Reports running plugin version, platform brand, and server version.
 */
public class VersionCmd extends BaseRTPCmdImpl {
  /**
   * Alias under which this command is additionally reachable
   * ({@code /rtp about}). Roots that register {@link VersionCmd} should mirror
   * the instance into their command lookup under this key (uppercased).
   */
  public static final String ALIAS = "about";

  public VersionCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "version";
  }

  @Override
  public String permission() {
    return "rtp.version";
  }

  @Override
  public String description() {
    return "describe the running RTP plugin and server";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    RTP.serverAccessor.sendMessage(callerId, "&2--- RTP ---");
    RTP.serverAccessor.sendMessage(
        callerId, "&7Plugin: &fRTP &7v&f" + RTP.serverAccessor.getPluginVersion());
    RTP.serverAccessor.sendMessage(callerId, "&7Platform: &f" + RTP.serverAccessor.getPlatform());
    RTP.serverAccessor.sendMessage(
        callerId, "&7Server: &f" + RTP.serverAccessor.getServerVersion());
    return true;
  }
}
