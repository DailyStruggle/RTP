package io.github.dailystruggle.rtp.example;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Reference example addon for RTP.
 *
 * <p>This class is intentionally small but exercises the four most common RTP API touch-points an
 * addon will need:
 *
 * <ol>
 *   <li><b>Config</b> — register a {@link ConfigParser} backed by {@link ExampleKeys} so the addon
 *       participates in {@code /rtp reload}.
 *   <li><b>Safety contribution</b> — register a {@link GlobalRegionVerifiers} predicate that is
 *       consulted asynchronously by the teleport pipeline (S-003 / S-005 compliant; never inline).
 *   <li><b>Event handling</b> — register a Bukkit {@link org.bukkit.event.Listener} for one of
 *       RTP's public events (here, {@code PostTeleportEvent}).
 *   <li><b>Reload hook</b> — use {@link Configs#onReload(Runnable)} so operator reloads are
 *       observable without a server restart.
 * </ol>
 *
 * <p>See {@code README.md} next to this file for a step-by-step walkthrough.
 */
public final class RTPExampleAddon extends JavaPlugin {

  @Override
  public void onEnable() {
    // 1. Register the addon's config file (example.yml) with RTP's Configs registry.
    RTP.configs.putParser(registerParser());

    // 4. Re-register on /rtp reload so operators see config changes without a restart.
    Configs.onReload(() -> RTP.configs.putParser(registerParser()));

    // 2. Contribute a safety predicate. The lambda is invoked asynchronously by the pipeline;
    //    it must NOT block, perform chunk I/O on the main thread, or swallow exceptions.
    GlobalRegionVerifiers.addGlobalRegionVerifier(
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
