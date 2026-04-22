package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.RTP;
import org.simpleyaml.configuration.file.YamlFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Lazy per-locale message overlay for {@link MessagesKeys}.
 *
 * <p>REQ-RTP-F-013 / ADR-020 — applies a locale file located at
 * {@code <pluginDirectory>/lang/<locale>/messages.yml} on top of an already-loaded
 * English baseline {@link ConfigParser}. The locale file is extracted from the jar
 * resource {@code lang/<locale>/messages.yml} on first use; missing keys in the
 * locale file fall back silently to the baseline English values. Unknown locales
 * (no such jar resource and no on-disk file) log a warning and leave the baseline
 * untouched.
 *
 * <p>Values are coerced to {@link String} via {@link Object#toString()} to guard
 * against the YAML 1.1 "Norway problem" (e.g. {@code no}/{@code on}/{@code off}
 * parsed as booleans).
 *
 * <p>An optional per-locale {@code messages.lang.yml} sibling is supported as a
 * future extension point (see ADR-020 §5) but is not yet applied here; the
 * baseline key-name map remains authoritative until that phase lands.
 */
public final class LocaleOverlay {

  private LocaleOverlay() {}

  /**
   * Apply the locale overlay, if any, to the already-loaded messages parser.
   *
   * @param messagesParser the English baseline parser (already loaded)
   * @param pluginDirectory the plugin data directory
   * @param locale the requested locale (e.g. "en", "es"); "en", null, or blank is a no-op
   */
  public static void apply(ConfigParser<MessagesKeys> messagesParser,
                           File pluginDirectory,
                           String locale) {
    if (messagesParser == null) return;
    if (locale == null) return;
    String normalized = locale.trim();
    if (normalized.isEmpty()) return;
    if (normalized.equalsIgnoreCase("en")) return;

    // Sanitize to prevent path-traversal via a malicious language: key.
    if (!normalized.matches("[A-Za-z0-9_-]+")) {
      RTP.log(Level.WARNING,
          "[RTP] Invalid language '" + locale + "' in config.yml; falling back to English.");
      return;
    }

    String resourcePath = "lang/" + normalized + "/messages.yml";
    File localeDir = new File(pluginDirectory, "lang" + File.separator + normalized);
    File localeFile = new File(localeDir, "messages.yml");

    if (!localeFile.exists()) {
      if (!extractFromJar(resourcePath, localeDir, localeFile)) {
        RTP.log(Level.WARNING,
            "[RTP] Unknown language '" + normalized
                + "' (no bundled lang/" + normalized + "/messages.yml); falling back to English.");
        return;
      }
    }

    Map<String, Object> overlayValues = readYamlFlat(localeFile);
    if (overlayValues.isEmpty()) return;

    int applied = 0;
    for (MessagesKeys key : MessagesKeys.values()) {
      Object raw = overlayValues.get(key.name());
      if (raw == null) continue;
      messagesParser.set(key, raw.toString());
      applied++;
    }

    RTP.log(Level.INFO,
        "[RTP] Locale overlay '" + normalized + "' applied: "
            + applied + "/" + MessagesKeys.values().length + " keys translated.");
  }

  private static boolean extractFromJar(String resourcePath, File targetDir, File targetFile) {
    try {
      InputStream in = RTP.class.getClassLoader().getResourceAsStream(resourcePath);
      if (in == null) return false;
      if (!targetDir.exists() && !targetDir.mkdirs()) {
        RTP.log(Level.WARNING,
            "[RTP] Failed to create locale directory " + targetDir.getAbsolutePath());
        in.close();
        return false;
      }
      try (FileOutputStream out = new FileOutputStream(targetFile)) {
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) {
          out.write(buf, 0, len);
        }
      }
      in.close();
      return true;
    } catch (Exception e) {
      RTP.log(Level.WARNING, "[RTP] Failed to extract " + resourcePath, e);
      return false;
    }
  }

  private static Map<String, Object> readYamlFlat(File file) {
    Map<String, Object> result = new HashMap<>();
    try {
      YamlFile yaml = new YamlFile(file.getPath());
      yaml.loadWithComments();
      Map<String, Object> raw = yaml.getMapValues(false);
      if (raw != null) result.putAll(raw);
    } catch (Exception e) {
      RTP.log(Level.WARNING, "[RTP] Failed to read locale file " + file.getAbsolutePath(), e);
    }
    return result;
  }
}
