package io.github.dailystruggle.bstats.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * bStats platform endpoint and its root-field vocabulary, mirrored from the
 * upstream {@code bstats-bukkit} 3.x {@code appendPlatformData}. The only place
 * platform-specific wire names live.
 *
 * <p>Only server endpoints are modelled: proxies report no server-side metrics.
 */
public enum BStatsPlatform {

    /** Bukkit family (Spigot / Paper / Folia); also used by hosts without their own endpoint. */
    BUKKIT("bukkit", BStatsConfig.Format.YAML);

    private final String endpoint;
    private final BStatsConfig.Format defaultConfigFormat;

    BStatsPlatform(String endpoint, BStatsConfig.Format defaultConfigFormat) {
        this.endpoint = endpoint;
        this.defaultConfigFormat = defaultConfigFormat;
    }

    /** Path segment of {@code /api/v2/data/<endpoint>}. */
    public String endpoint() {
        return endpoint;
    }

    /** Config format the upstream library writes on this platform. */
    public BStatsConfig.Format defaultConfigFormat() {
        return defaultConfigFormat;
    }

    /** Root payload fields for {@code info}, in upstream order; {@code null} values are dropped on write. */
    public Map<String, Object> rootFields(ServerInfo info) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (info == null) return m;
        m.put("playerAmount", Math.max(0, info.playerCount()));
        m.put("onlineMode", (info.onlineMode() == null) ? null : (info.onlineMode() ? 1 : 0));
        m.put("bukkitVersion", info.version());
        m.put("bukkitName", info.name());
        return m;
    }
}
