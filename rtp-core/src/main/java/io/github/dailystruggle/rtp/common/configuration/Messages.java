package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.PlaceholderMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Index facade over per-concern message enums (ADR-071).
 */
public final class Messages {
  private Messages() {}

  /** Lazily-built {@code key name -> enum constant} index across every per-concern enum. */
  private static volatile Map<String, Enum<?>> byNameIndex;

  /**
   * Find the message constant with the given YAML key name across every
   * per-concern enum (names are disjoint across the files), or {@code null} when
   * no enum declares that name.
   *
   * @param name the YAML key name (enum constant name)
   * @return the matching enum constant, or {@code null}
   */
  public static Enum<?> byName(String name) {
    if (name == null) return null;
    Map<String, Enum<?>> idx = byNameIndex;
    if (idx == null) {
      idx = buildIndex();
      byNameIndex = idx;
    }
    return idx.get(name);
  }

  private static Map<String, Enum<?>> buildIndex() {
    Map<String, Enum<?>> m = new LinkedHashMap<>();
    for (PlaceholderMessages k : PlaceholderMessages.values()) m.put(k.name(), k);
    for (PlayerMessages k : PlayerMessages.values()) m.put(k.name(), k);
    for (NetworkMessages k : NetworkMessages.values()) m.put(k.name(), k);
    for (CommandMessages k : CommandMessages.values()) m.put(k.name(), k);
    for (SystemMessages k : SystemMessages.values()) m.put(k.name(), k);
    return m;
  }

  /**
   * Resolve the configured value for a message constant looked up by name,
   * returning {@code def} when the name is unknown or unset.
   *
   * @param name the YAML key name
   * @param def  the fallback value
   * @return the resolved value, or {@code def}
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Object valueByName(String name, Object def) {
    Enum<?> key = byName(name);
    if (key == null || RTP.configs == null) return def;
    return RTP.configs.getConfigValue((Enum) key, def);
  }
}
