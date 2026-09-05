package io.github.dailystruggle.rtp.common.selection.region.selectors.memory;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.TtlKeys;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Parses and resolves cause-based TTL and per-verifier retention durations (ADR-079).
 * Durations <= 0 indicate infinite / static retention.
 */
public final class TtlConfig {
  private static final long INFINITE = -1L;
  private static final long DEFAULT_DYNAMIC_TTL_SECONDS = 14L * 86400L; // 14 days

  private static final Map<LocationGenerator.FailTypes, Long> causeTtls =
      new EnumMap<>(LocationGenerator.FailTypes.class);
  private static final Map<String, Long> verifierTtls = new ConcurrentHashMap<>();

  static {
    resetDefaults();
  }

  private TtlConfig() {}

  public static void resetDefaults() {
    causeTtls.clear();
    verifierTtls.clear();

    // Static tier: infinite (<= 0)
    causeTtls.put(LocationGenerator.FailTypes.biome, INFINITE);
    causeTtls.put(LocationGenerator.FailTypes.worldBorder, INFINITE);
    causeTtls.put(LocationGenerator.FailTypes.vert, INFINITE);
    causeTtls.put(LocationGenerator.FailTypes.safety, INFINITE);
    causeTtls.put(LocationGenerator.FailTypes.prefilterBiome, INFINITE);
    causeTtls.put(LocationGenerator.FailTypes.prefilterBlock, INFINITE);
    causeTtls.put(LocationGenerator.FailTypes.prefilterRange, INFINITE);
    causeTtls.put(LocationGenerator.FailTypes.misc, INFINITE);

    // Dynamic tier: finite default
    causeTtls.put(LocationGenerator.FailTypes.uniquePlacement, 30L * 86400L); // 30 days
    causeTtls.put(LocationGenerator.FailTypes.safetyExternal, DEFAULT_DYNAMIC_TTL_SECONDS); // 14 days

    // Transient tier: 0 (discard immediately / not retained)
    causeTtls.put(LocationGenerator.FailTypes.timeout, 0L);
    causeTtls.put(LocationGenerator.FailTypes.nullChunk, 0L);
    causeTtls.put(LocationGenerator.FailTypes.ungenerated, 0L);
  }

  /**
   * Resolves retention duration in seconds for a cause and optional verifier class.
   *
   * @param cause         rejection cause
   * @param verifierClass optional verifier class that vetoed the candidate
   * @return retention duration in seconds; {@code <= 0} indicates infinite retention
   */
  public static long resolveTtlSeconds(
      LocationGenerator.FailTypes cause, Class<?> verifierClass) {
    if (verifierClass != null) {
      String simpleName = verifierClass.getSimpleName();
      Long override = verifierTtls.get(simpleName.toLowerCase(Locale.ROOT));
      if (override != null) return override;

      String fqn = verifierClass.getName();
      override = verifierTtls.get(fqn.toLowerCase(Locale.ROOT));
      if (override != null) return override;
    }

    if (cause != null) {
      Long ttl = causeTtls.get(cause);
      if (ttl != null) return ttl;
    }

    return INFINITE;
  }

  /**
   * Parses duration string like "14d", "30m", "12h", "100s", "1d12h", "2h30m", "-1", "infinite".
   * Delegates to {@link ConfigParser#parseDurationSeconds(Object)}.
   *
   * @param input string or object representation of duration
   * @return duration in seconds, or {@code -1L} if infinite or invalid
   */
  public static long parseDurationSeconds(Object input) {
    if (input == null) return INFINITE;
    if (input instanceof Number num) {
      long val = num.longValue();
      return val <= 0 ? INFINITE : val;
    }
    String s = input.toString().trim().toLowerCase(Locale.ROOT);
    if (s.isEmpty() || s.equals("-1") || s.equals("infinite") || s.equals("permanent")) {
      return INFINITE;
    }
    io.github.dailystruggle.rtp.common.selection.region.util.DurationParser.ParsedDuration parsed =
        io.github.dailystruggle.rtp.common.selection.region.util.DurationParser.parse(
            s, io.github.dailystruggle.rtp.common.selection.region.util.TemporalUnit.SECOND);
    if (parsed == null) return INFINITE;
    long seconds = (long) Math.round(parsed.toSeconds());
    return seconds <= 0 ? INFINITE : seconds;
  }

  /**
   * Reloads configuration from the provided raw maps.
   */
  public static void load(Map<?, ?> causesMap, Map<?, ?> verifiersMap) {
    resetDefaults();
    if (causesMap != null) {
      for (Map.Entry<?, ?> entry : causesMap.entrySet()) {
        String keyStr = String.valueOf(entry.getKey());
        try {
          LocationGenerator.FailTypes cause = LocationGenerator.FailTypes.valueOf(keyStr);
          causeTtls.put(cause, ConfigParser.parseDurationSeconds(entry.getValue()));
        } catch (IllegalArgumentException ignored) {
          // unknown cause enum
        }
      }
    }
    if (verifiersMap != null) {
      for (Map.Entry<?, ?> entry : verifiersMap.entrySet()) {
        String keyStr = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
        verifierTtls.put(keyStr, ConfigParser.parseDurationSeconds(entry.getValue()));
      }
    }
  }

  /**
   * Reloads configuration from the provided {@link ConfigParser}.
   */
  public static void loadFromConfig(ConfigParser<TtlKeys> parser) {
    resetDefaults();
    if (parser == null) return;

    try {
      load(parser.getMap(TtlKeys.causes), parser.getMap(TtlKeys.verifiers));
    } catch (Exception e) {
      RTP.log(Level.WARNING, "[TtlConfig] Error parsing ttl.yml: " + e.getMessage(), e);
    }
  }
}
