package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;

import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * ADR-073 config-default inheritance resolver.
 *
 * <p>A region/world setting may carry an {@code @<file>} reference token instead of a
 * literal value. The token names the base name (no extension) of the resource that owns
 * the corresponding global default, so the resolver knows which parser to ask:
 *
 * <ul>
 *   <li>{@code @config} resolves from {@code config.yml#defaults.<setting>},</li>
 *   <li>{@code @economy} resolves from {@code economy.yml#<setting>},</li>
 *   <li>{@code @safety} resolves from {@code safety.yml#<setting>}.</li>
 * </ul>
 *
 * <p>Resolution happens at read time and never rewrites a file. When a reference cannot
 * be resolved (missing global default or unknown file) the supplied fallback is returned
 * and a warning is logged, so a misconfigured reference can never produce a zero-radius
 * teleport or an NPE.
 */
public final class ConfigDefaultResolver {

  private ConfigDefaultResolver() {}

  /**
   * @param value a candidate config value
   * @return {@code true} when {@code value} is a string {@code @<file>} reference token
   */
  public static boolean isReference(Object value) {
    return value instanceof String s && s.trim().startsWith("@") && s.trim().length() > 1;
  }

  /**
   * @param value a config value
   * @return the lower-cased base file name a reference names (e.g. {@code "config"} for
   *     {@code "@config"}), or {@code null} when {@code value} is not a reference token
   */
  public static String referencedFile(Object value) {
    if (!isReference(value)) return null;
    return ((String) value).trim().substring(1).toLowerCase(Locale.ROOT);
  }

  /**
   * Resolves a scalar/block reference token for the given canonical setting name.
   *
   * <p>For the type-bearing {@code shape}/{@code vert} blocks the {@code settingName} is
   * {@code "shape"}/{@code "vert"} and the returned value is the whole named block
   * ({@link RtpYamlSection} or {@link Map}) as stored under {@code config.yml#defaults}.
   *
   * @param raw the raw configured value (a reference token or a literal)
   * @param settingName the canonical setting name (matches the key inside the owning file)
   * @param fallback the value to return when the reference cannot be resolved
   * @return the resolved value, or {@code raw} when it is a literal, or {@code fallback}
   */
  public static Object resolve(Object raw, String settingName, Object fallback) {
    String file = referencedFile(raw);
    if (file == null) return raw;
    switch (file) {
      case "config":
        return fromConfigDefaults(settingName, fallback);
      case "economy":
        return fromParser(EconomyKeys.class, settingName, fallback);
      case "safety":
        return fromParser(SafetyKeys.class, settingName, fallback);
      default:
        RTP.log(Level.WARNING,
            "[RTP] ADR-073: unknown config-default reference '@" + file + "' for setting '"
                + settingName + "'; using fallback " + fallback);
        return fallback;
    }
  }

  @SuppressWarnings("unchecked")
  private static Object fromConfigDefaults(String settingName, Object fallback) {
    if (RTP.configs == null) return fallback;
    FactoryValue<ConfigKeys> fv = RTP.configs.getParser(ConfigKeys.class);
    if (!(fv instanceof ConfigParser)) return fallback;
    ConfigParser<ConfigKeys> cp = (ConfigParser<ConfigKeys>) fv;
    Object defaultsObj = cp.getData(ConfigKeys.defaults);
    Map<String, Object> defaults = asMap(defaultsObj);
    if (defaults == null || !defaults.containsKey(settingName)) {
      RTP.log(Level.WARNING,
          "[RTP] ADR-073: config.yml#defaults." + settingName
              + " is missing; using fallback " + fallback);
      return fallback;
    }
    return defaults.get(settingName);
  }

  private static <T extends Enum<T>> Object fromParser(Class<T> enumClass, String settingName,
                                                       Object fallback) {
    if (RTP.configs == null) return fallback;
    FactoryValue<T> fv = RTP.configs.getParser(enumClass);
    if (!(fv instanceof ConfigParser)) return fallback;
    ConfigParser<T> cp = (ConfigParser<T>) fv;
    T key;
    try {
      key = Enum.valueOf(enumClass, settingName);
    } catch (IllegalArgumentException e) {
      RTP.log(Level.WARNING,
          "[RTP] ADR-073: " + enumClass.getSimpleName() + " has no default for setting '"
              + settingName + "'; using fallback " + fallback);
      return fallback;
    }
    Object raw = cp.getData(key);
    if (raw == null) return fallback;
    return raw;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    if (o instanceof RtpYamlSection section) return section.getMapValues(false);
    if (o instanceof Map) return (Map<String, Object>) o;
    return null;
  }
}
