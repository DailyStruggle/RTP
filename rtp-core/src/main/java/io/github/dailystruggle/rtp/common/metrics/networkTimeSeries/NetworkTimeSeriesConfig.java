package io.github.dailystruggle.rtp.common.metrics.networkTimeSeries;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Resolved configuration for ADR-040 cross-backend metric time-series publication
 * ({@code advanced/network.yml > metrics.networkTimeSeries.*}).
 *
 * @param publish         master writer toggle (default {@code false}, REQ-RTP-METTS-007)
 * @param tier            published tier specification (default {@code "24h@1m"}, REQ-RTP-METTS-002)
 * @param retentionDays   per-backend retention horizon in days (default {@code 7}, REQ-RTP-METTS-005)
 * @param perPassWriteCap maximum rows written per periodic flush pass (default {@code 64}, REQ-RTP-METTS-004)
 */
public record NetworkTimeSeriesConfig(
    boolean publish, String tier, int retentionDays, int perPassWriteCap) {

  public static final boolean DEFAULT_PUBLISH = false;
  public static final String DEFAULT_TIER = "24h@1m";
  public static final int DEFAULT_RETENTION_DAYS = 7;
  public static final int DEFAULT_PER_PASS_WRITE_CAP = 64;

  public static final NetworkTimeSeriesConfig DEFAULT =
      new NetworkTimeSeriesConfig(
          DEFAULT_PUBLISH, DEFAULT_TIER, DEFAULT_RETENTION_DAYS, DEFAULT_PER_PASS_WRITE_CAP);

  private static volatile NetworkTimeSeriesConfig activeOverride;
  private static volatile String cachedFilePath;
  private static volatile long cachedLastModified = -1L;
  private static volatile long cachedFileLength = -1L;
  private static volatile NetworkTimeSeriesConfig cachedConfig = DEFAULT;

  public NetworkTimeSeriesConfig {
    tier = (tier == null || tier.isBlank()) ? DEFAULT_TIER : tier.trim();
    if (retentionDays <= 0) {
      throw new IllegalArgumentException("retentionDays must be > 0, got " + retentionDays);
    }
    if (perPassWriteCap <= 0) {
      throw new IllegalArgumentException("perPassWriteCap must be > 0, got " + perPassWriteCap);
    }
  }

  /**
   * Factory helper for programmatic construction in tests or overrides.
   */
  public static NetworkTimeSeriesConfig of(
      boolean publish, String tier, int retentionDays, int perPassWriteCap) {
    return new NetworkTimeSeriesConfig(publish, tier, retentionDays, perPassWriteCap);
  }

  /**
   * Updates the active configuration from a loaded {@code network.yml} document.
   */
  public static NetworkTimeSeriesConfig updateFromYaml(RtpYamlConfig cfg) {
    NetworkTimeSeriesConfig parsed = fromYaml(cfg);
    activeOverride = parsed;
    return parsed;
  }

  /**
   * Clears any programmatic or bootstrap override so {@link #resolve()} falls back to on-disk
   * {@code advanced/network.yml} or {@link #DEFAULT}.
   */
  public static void clearActiveOverride() {
    activeOverride = null;
  }

  /**
   * Parses {@code metrics.networkTimeSeries.*} from a loaded {@code network.yml} {@link RtpYamlConfig}.
   */
  @SuppressWarnings("unchecked")
  public static NetworkTimeSeriesConfig fromYaml(RtpYamlConfig cfg) {
    if (cfg == null) return DEFAULT;
    try {
      RtpYamlSection metricsSec = cfg.getConfigurationSection("metrics");
      if (metricsSec == null) return DEFAULT;
      Map<String, Object> metricsMap = metricsSec.getMapValues(false);
      if (metricsMap == null || metricsMap.isEmpty()) return DEFAULT;

      Object ntsObj = metricsMap.get("networkTimeSeries");
      Map<String, Object> ntsMap = null;
      if (ntsObj instanceof RtpYamlSection section) {
        ntsMap = section.getMapValues(false);
      } else if (ntsObj instanceof Map<?, ?> rawMap) {
        ntsMap = (Map<String, Object>) rawMap;
      } else {
        RtpYamlSection subSec = metricsSec.getConfigurationSection("networkTimeSeries");
        if (subSec != null) {
          ntsMap = subSec.getMapValues(false);
        }
      }

      boolean publish =
          parseBoolean(
              lookup(ntsMap, metricsMap, "publish", "networkTimeSeries.publish"), DEFAULT_PUBLISH);
      String tier =
          parseString(lookup(ntsMap, metricsMap, "tier", "networkTimeSeries.tier"), DEFAULT_TIER);
      int retentionDays =
          Math.max(
              1,
              parseInt(
                  lookup(ntsMap, metricsMap, "retentionDays", "networkTimeSeries.retentionDays"),
                  DEFAULT_RETENTION_DAYS));
      int perPassCap =
          Math.max(
              1,
              parseInt(
                  lookup(
                      ntsMap, metricsMap, "perPassWriteCap", "networkTimeSeries.perPassWriteCap"),
                  DEFAULT_PER_PASS_WRITE_CAP));
      return new NetworkTimeSeriesConfig(publish, tier, retentionDays, perPassCap);
    } catch (Throwable ignored) {
      return DEFAULT;
    }
  }

  /**
   * Resolves the current configuration from {@code advanced/network.yml}, falling back to
   * {@link #DEFAULT} when not configured.
   */
  public static NetworkTimeSeriesConfig resolve() {
    NetworkTimeSeriesConfig override = activeOverride;
    if (override != null) {
      return override;
    }
    try {
      if (RTPRuntime.serverAccessor() == null) return DEFAULT;
      File pluginDir = RTPRuntime.serverAccessor().getPluginDirectory();
      if (pluginDir == null) return DEFAULT;
      File netFile = new File(pluginDir, "advanced" + File.separator + "network.yml");
      if (!netFile.isFile()) {
        File legacy = new File(pluginDir, "network.yml");
        if (legacy.isFile()) {
          netFile = legacy;
        } else {
          return DEFAULT;
        }
      }
      String path = netFile.getAbsolutePath();
      long lastMod = netFile.lastModified();
      long len = netFile.length();
      if (path.equals(cachedFilePath)
          && lastMod == cachedLastModified
          && len == cachedFileLength) {
        return cachedConfig;
      }
      RtpYamlConfig yaml = RtpYamlConfig.load(netFile);
      NetworkTimeSeriesConfig parsed = fromYaml(yaml);
      cachedFilePath = path;
      cachedLastModified = lastMod;
      cachedFileLength = len;
      cachedConfig = parsed;
      return parsed;
    } catch (Throwable ignored) {
      return DEFAULT;
    }
  }

  /** @return retention horizon in seconds ({@code retentionDays * 86400L}) */
  public long retentionSeconds() {
    return (long) retentionDays * 86_400L;
  }

  /** @return bucket width in seconds for {@link #tier()} (e.g. {@code 60} for {@code "24h@1m"}) */
  public int tierWindowSeconds() {
    return parseWindowSeconds(tier);
  }

  /** @return tier retention in seconds for {@link #tier()} (e.g. {@code 86400} for {@code "24h@1m"}) */
  public long tierRetentionSeconds() {
    return parseRetentionSeconds(tier);
  }

  /**
   * Parses the bucket window width (seconds) from a tier spec such as {@code "24h@1m"} or {@code "1h@10s"}.
   */
  public static int parseWindowSeconds(String tierSpec) {
    Objects.requireNonNull(tierSpec, "tierSpec");
    String s = tierSpec.trim();
    int at = s.indexOf('@');
    String windowPart = (at >= 0) ? s.substring(at + 1).trim() : s;
    long sec = parseDurationTokenSeconds(windowPart, 60L);
    return (int) Math.max(1L, sec);
  }

  /**
   * Parses the tier retention window (seconds) from a tier spec such as {@code "24h@1m"} or {@code "1h@10s"}.
   */
  public static long parseRetentionSeconds(String tierSpec) {
    Objects.requireNonNull(tierSpec, "tierSpec");
    String s = tierSpec.trim();
    int at = s.indexOf('@');
    if (at <= 0) return 86_400L;
    String retPart = s.substring(0, at).trim();
    return Math.max(1L, parseDurationTokenSeconds(retPart, 86_400L));
  }

  private static long parseDurationTokenSeconds(String token, long defSeconds) {
    if (token == null || token.isEmpty()) return defSeconds;
    String lower = token.toLowerCase(Locale.ROOT).trim();
    long multiplier = 1L;
    String numPart = lower;
    if (lower.endsWith("d")) {
      multiplier = 86_400L;
      numPart = lower.substring(0, lower.length() - 1).trim();
    } else if (lower.endsWith("h")) {
      multiplier = 3_600L;
      numPart = lower.substring(0, lower.length() - 1).trim();
    } else if (lower.endsWith("m")) {
      multiplier = 60L;
      numPart = lower.substring(0, lower.length() - 1).trim();
    } else if (lower.endsWith("s")) {
      multiplier = 1L;
      numPart = lower.substring(0, lower.length() - 1).trim();
    }
    try {
      long val = Long.parseLong(numPart);
      return val > 0 ? val * multiplier : defSeconds;
    } catch (NumberFormatException e) {
      return defSeconds;
    }
  }

  private static Object lookup(
      Map<String, Object> ntsMap,
      Map<String, Object> metricsMap,
      String shortKey,
      String dottedKey) {
    if (ntsMap != null && ntsMap.containsKey(shortKey)) {
      return ntsMap.get(shortKey);
    }
    if (metricsMap != null && metricsMap.containsKey(dottedKey)) {
      return metricsMap.get(dottedKey);
    }
    return null;
  }

  private static boolean parseBoolean(Object raw, boolean def) {
    if (raw == null) return def;
    if (raw instanceof Boolean b) return b;
    String s = raw.toString().trim();
    if ("true".equalsIgnoreCase(s)) return true;
    if ("false".equalsIgnoreCase(s)) return false;
    return def;
  }

  private static String parseString(Object raw, String def) {
    if (raw == null) return def;
    String s = raw.toString().trim();
    return s.isEmpty() ? def : s;
  }

  private static int parseInt(Object raw, int def) {
    if (raw == null) return def;
    if (raw instanceof Number n) return n.intValue();
    try {
      return Integer.parseInt(raw.toString().trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }
}
