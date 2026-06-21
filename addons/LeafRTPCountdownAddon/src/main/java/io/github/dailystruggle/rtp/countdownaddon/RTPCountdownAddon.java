package io.github.dailystruggle.rtp.countdownaddon;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;

/**
 * LeafRTP countdown addon for RTP.
 *
 * <p>Ships two live, per-second teleport countdowns - a delay countdown for immediate teleports and
 * a queue-position countdown for players waiting in the public wait queue - and doubles as the
 * canonical reference addon (it also demonstrates config registration and an async safety verifier
 * hook).
 *
 * <p>Platform-agnostic: implements {@link RTPAddon} and is discovered by
 * {@code rtp-core} through {@link java.util.ServiceLoader} (see
 * {@code META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon}), so it runs
 * on Bukkit/Paper/Folia and Fabric alike with no Bukkit plugin loader or
 * {@code org.bukkit.*} imports.
 *
 * <p>Demonstrates common RTP API touch-points:
 * <ul>
 *   <li><b>Config</b>: Registers {@link ConfigParser} with {@link CountdownKeys} for {@code /rtp reload}.</li>
 *   <li><b>Safety</b>: Registers an async verifier predicate via {@link RTPAPI#hooks()}. (See ADR-026).</li>
 *   <li><b>Reload</b>: Uses {@link Configs#onReload(Runnable)} for live config updates.</li>
 * </ul>
 *
 * <p>See {@code README.md} for a walkthrough.
 */
public final class RTPCountdownAddon implements RTPAddon {

  private final CountdownHooks countdownHooks = new CountdownHooks();

  @Override
  public void onLoad() {
    // 1. Register the addon's config file (countdown.yml) with RTP's Configs registry.
    RTP.configs.putParser(registerParser());

    // 4. Re-register on /rtp reload so operators see config changes without a restart.
    Configs.onReload(() -> RTP.configs.putParser(registerParser()));

    // 2. Contribute a safety predicate via the public RTPHooks facade (ADR-026).
    //    The lambda is invoked asynchronously by the pipeline; it must NOT block, perform
    //    chunk I/O on the main thread, or swallow exceptions.
    RTPAPI.hooks().verifiers().register(
        coords -> {
          @SuppressWarnings("unchecked")
          ConfigParser<CountdownKeys> parser =
              (ConfigParser<CountdownKeys>) RTP.configs.getParser(CountdownKeys.class);
          if (parser == null) return true;
          Object flag = parser.getConfigValue(CountdownKeys.rejectInClaim, false);
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

    // 3. Register the two example countdowns (delay + queue), also platform-agnostic.
    countdownHooks.register();
  }

  /**
   * Builds a {@link ConfigParser} for {@link CountdownKeys} targeting {@code countdown.yml} inside
   * the RTP plugin data folder.
   */
  private ConfigParser<CountdownKeys> registerParser() {
    return new ConfigParser<>(
        CountdownKeys.class,
        "countdown",
        "1.0",
        RTP.serverAccessor.getPluginDirectory(),
        null,
        RTP.configs.fileDatabase,
        this.getClass().getClassLoader());
  }

  @Override
  public void onUnload() {
    // Addons should be able to come and go without leaving RTP in a half-state. If you allocated
    // tickets, scheduled tasks, or wrote to the DB, release/cancel/flush them here.
    countdownHooks.unregister();
  }
}
