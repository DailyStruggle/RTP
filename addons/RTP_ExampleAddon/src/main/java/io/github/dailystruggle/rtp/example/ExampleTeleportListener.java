package io.github.dailystruggle.rtp.example;

import io.github.dailystruggle.rtp.bukkit.events.PostTeleportEvent;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import java.util.logging.Level;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Example Bukkit listener for RTP's {@link PostTeleportEvent}.
 *
 * <p>Addons consume RTP's lifecycle events just like any other Bukkit event. This listener logs a
 * line whenever the {@code announceTeleport} flag is enabled in {@code example.yml}.
 */
public final class ExampleTeleportListener implements Listener {

  @EventHandler
  @SuppressWarnings("unchecked")
  public void onPostTeleport(PostTeleportEvent event) {
    ConfigParser<ExampleKeys> parser =
        (ConfigParser<ExampleKeys>) RTP.configs.getParser(ExampleKeys.class);
    if (parser == null) return;

    Object flag = parser.getConfigValue(ExampleKeys.announceTeleport, false);
    boolean enabled =
        (flag instanceof Boolean) ? (Boolean) flag : Boolean.parseBoolean(String.valueOf(flag));
    if (!enabled) return;

    RTP.log(Level.INFO, "[RTP_ExampleAddon] PostTeleportEvent observed: " + event.getDoTeleport());
  }
}
