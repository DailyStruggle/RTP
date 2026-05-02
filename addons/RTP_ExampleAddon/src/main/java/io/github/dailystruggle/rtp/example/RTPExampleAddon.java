package io.github.dailystruggle.rtp.example;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Reference example addon for RTP.
 *
 * <p>Demonstrates common RTP API touch-points:
 * <ul>
 *   <li><b>Config</b>: Registers {@link ConfigParser} with {@link ExampleKeys} for {@code /rtp reload}.</li>
 *   <li><b>Safety</b>: Registers an async verifier predicate via {@link RTPAPI#hooks()}. (See ADR-026).</li>
 *   <li><b>Events</b>: Implements {@link org.bukkit.event.Listener} for RTP lifecycle events.</li>
 *   <li><b>Reload</b>: Uses {@link Configs#onReload(Runnable)} for live config updates.</li>
 * </ul>
 *
 * <p>See {@code README.md} for a walkthrough.
 */
public final class RTPExampleAddon extends JavaPlugin {

  @Override
  public void onEnable() {
    // 1. Register the addon's config file (example.yml) with RTP's Configs registry.
    RTP.configs.putParser(registerParser());

    // 4. Re-register on /rtp reload so operators see config changes without a restart.
    Configs.onReload(() -> RTP.configs.putParser(registerParser()));

    // 2. Contribute a safety predicate via the public RTPHooks facade (ADR-026).
    //    The lambda is invoked asynchronously by the pipeline; it must NOT block, perform
    //    chunk I/O on the main thread, or swallow exceptions.
    RTPAPI.hooks().verifiers().register(
        coords -> {
          @SuppressWarnings("unchecked")
          ConfigParser<ExampleKeys> parser =
              (ConfigParser<ExampleKeys>) RTP.configs.getParser(ExampleKeys.class);
          if (parser == null) return true;
          Object flag = parser.getConfigValue(ExampleKeys.rejectInClaim, false);
          boolean reject =
              (flag instanceof Boolean)
                  ? (Boolean) flag
                  : Boolean.parseBoolean(String.valueOf(flag));
          // Example: blanket-accept in the example addon. Replace with your own logic
          // (claim lookup, biome lookup, etc.). Return `true` to accept, `false` to reject.
          if (!reject) return true;
          // Replace this stub with your own claim / biome / distance check.
          return true;
        });

    // 3. Register a Bukkit listener for RTP's lifecycle events.
    Bukkit.getPluginManager().registerEvents(new ExampleTeleportListener(), this);
  }

  /**
   * Builds a {@link ConfigParser} for {@link ExampleKeys} targeting {@code example.yml} inside the
   * RTP plugin data folder.
   */
  private ConfigParser<ExampleKeys> registerParser() {
    return new ConfigParser<>(
        ExampleKeys.class,
        "example",
        "1.0",
        RTP.serverAccessor.getPluginDirectory(),
        null,
        RTP.configs.fileDatabase,
        this.getClass().getClassLoader());
  }

  @Override
  public void onDisable() {
    // Addons should be able to come and go without leaving RTP in a half-state. If you allocated
    // tickets, scheduled tasks, or wrote to the DB, release/cancel/flush them here.
  }
}
