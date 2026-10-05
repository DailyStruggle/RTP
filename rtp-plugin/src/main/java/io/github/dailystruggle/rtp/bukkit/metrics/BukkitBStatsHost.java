package io.github.dailystruggle.rtp.bukkit.metrics;

import io.github.dailystruggle.bstats.api.BStatsConfig;
import io.github.dailystruggle.bstats.api.ServerInfo;
import io.github.dailystruggle.rtp.common.metrics.bstats.RtpBStats;
import io.github.dailystruggle.rtp.common.metrics.bstats.RtpBStatsCatalogue;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bukkit-family {@link RtpBStatsCatalogue.Host} for {@link RtpBStats}: platform
 * detection, whitelisted addons, online players, game / plugin version and the
 * server facts the upstream Bukkit library sends.
 *
 * <p>Suppliers call the Bukkit API, so collection runs on the main / global thread
 * and the send runs async.
 */
public final class BukkitBStatsHost implements RtpBStatsCatalogue.Host {

  private final JavaPlugin plugin;

  public BukkitBStatsHost(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public String platform() {
    return detectPlatform();
  }

  @Override
  public String chunkLoadMode() {
    return detectChunkLoadMode();
  }

  @Override
  public int onlinePlayerCount() {
    return detectOnlinePlayerCount();
  }

  @Override
  public String gameVersion() {
    return detectGameVersion();
  }

  @Override
  public String pluginVersion() {
    try {
      String v = (plugin != null) ? plugin.getDescription().getVersion() : null;
      return (v == null || v.isBlank()) ? detectPluginVersion() : v;
    } catch (Throwable t) {
      return detectPluginVersion();
    }
  }

  @Override
  public Map<String, Integer> addonsLoaded() {
    return detectAddonsLoaded();
  }

  /** Upstream Bukkit root fields: players, online mode, server version and name. Main thread. */
  @Override
  public ServerInfo serverInfo() {
    int players = detectOnlinePlayerCount();
    try {
      return new ServerInfo(players, Bukkit.getOnlineMode(), Bukkit.getName(), Bukkit.getVersion());
    } catch (Throwable ignored) {
      return new ServerInfo(players, null, null, null); // no live server (test fixture)
    }
  }

  @Override
  public boolean collectOnMainThread() {
    return true;
  }

  @Override
  public BStatsConfig.Format configFormat() {
    return BStatsConfig.Format.YAML;
  }

  /**
   * Host Minecraft version reduced to {@code major.minor}, read from
   * {@link Bukkit#getBukkitVersion()} (e.g. {@code "1.21.4-R0.1-SNAPSHOT"}).
   */
  static String detectGameVersion() {
    try {
      return RtpBStatsCatalogue.majorMinor(Bukkit.getBukkitVersion());
    } catch (Throwable t) {
      return "unknown";
    }
  }

  /** The RTP plugin version from its description, or {@code "unknown"}. */
  static String detectPluginVersion() {
    try {
      org.bukkit.plugin.Plugin rtp = Bukkit.getPluginManager().getPlugin("RTP");
      if (rtp == null) return "unknown";
      String version = rtp.getDescription().getVersion();
      return (version == null || version.isBlank()) ? "unknown" : version;
    } catch (Throwable t) {
      return "unknown";
    }
  }

  static String detectPlatform() {
    try {
      Server s = Bukkit.getServer();
      String name = s.getName();
      String version = s.getVersion();
      // Version-string probe: no Folia/Paper import in rtp-plugin.
      if (version != null && version.toLowerCase().contains("folia")) return "folia";
      if ("Paper".equalsIgnoreCase(name)) return "paper";
      if ("Spigot".equalsIgnoreCase(name)) return "spigot";
      // Forks (Purpur, Pufferfish, ...) collapse to their upstream family.
      if (version != null && version.toLowerCase().contains("paper")) return "paper-fork";
      return (name == null || name.isBlank()) ? "unknown" : name.toLowerCase();
    } catch (Throwable t) {
      return "unknown";
    }
  }

  /**
   * Chunk-load mode assumed from the detected platform: Paper / forks / Folia load
   * off the tick thread; Spigot / CraftBukkit on the main thread.
   */
  static String detectChunkLoadMode() {
    switch (detectPlatform()) {
      case "folia":
      case "paper":
      case "paper-fork":
        return "async";
      case "spigot":
      case "craftbukkit":
        return "sync";
      default:
        return "unknown";
    }
  }

  /**
   * Integration plugin names mirrored from {@code plugin.yml > softdepend}. Only
   * these are ever reported, so arbitrary plugin lists can't fingerprint a server.
   */
  static final List<String> KNOWN_ADDON_PLUGINS =
          Collections.unmodifiableList(Arrays.asList(
                  "WorldGuard",
                  "WorldEdit",
                  "PlaceholderAPI",
                  "Vault",
                  "Chunky",
                  "ChunkyBorder",
                  "GriefDefender",
                  "GriefPrevention",
                  "Towny",
                  "Factions",
                  "FactionsBridge",
                  "Lands",
                  "RedProtect",
                  "Residence",
                  "CrashClaim",
                  "HuskClaims",
                  "Kingdoms"));

  /** Enabled plugins from {@link #KNOWN_ADDON_PLUGINS}; {@code "none"} when zero. */
  static Map<String, Integer> detectAddonsLoaded() {
    Map<String, Integer> tally = new HashMap<>();
    try {
      var pm = Bukkit.getPluginManager();
      for (String name : KNOWN_ADDON_PLUGINS) {
        try {
          if (pm.isPluginEnabled(name)) tally.put(name, 1);
        } catch (Throwable ignored) {
          // skip - chart must never throw.
        }
      }
      if (tally.isEmpty()) tally.put("none", 1);
    } catch (Throwable ignored) {
      tally.clear();
      tally.put("none", 1);
    }
    return tally;
  }

  /** Online players via {@link Bukkit#getOnlinePlayers()}, {@code -1} when unresolved. */
  static int detectOnlinePlayerCount() {
    try {
      return Bukkit.getOnlinePlayers().size();
    } catch (Throwable t) {
      return -1;
    }
  }
}
