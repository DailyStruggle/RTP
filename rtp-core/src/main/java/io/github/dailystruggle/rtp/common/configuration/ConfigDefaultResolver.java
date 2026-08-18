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
 * Resolves {@code @<file>} reference tokens in region/world settings to global defaults
 * (e.g. {@code @config}, {@code @economy}, {@code @safety}).
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
   * @return lower-cased base file name (e.g. {@code "config"} for {@code "@config"}), or {@code null}
   */
  public static String referencedFile(Object value) {
    if (!isReference(value)) return null;
    return ((String) value).trim().substring(1).toLowerCase(Locale.ROOT);
  }

  /**
   * Resolves a scalar/block reference token for the given canonical setting name.
   *
   * @param raw raw configured value (reference token or literal)
   * @param settingName canonical setting name matching key in owning file
   * @param fallback value returned if reference cannot be resolved
   * @return resolved value, {@code raw} if literal, or {@code fallback}
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
