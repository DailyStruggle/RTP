package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/**
 * Bootstrap loader for active locale (ADR-020).
 * Reads {@code language.yml} before {@link ConfigParser} construction to allow direct locale loading.
 */
public final class LanguageBootstrap {

  /** File name used at the plugin root to declare the active locale. */
  public static final String FILE_NAME = "language.yml";

  /** Single key inside {@link #FILE_NAME}. */
  public static final String KEY = "language";

  /** Default locale when no value is configured. */
  public static final String DEFAULT_LOCALE = "en";

  private LanguageBootstrap() {}

  /**
   * Resolve the active locale, creating {@code language.yml} on first run.
   *
   * @param pluginDirectory the plugin data directory (must not be null)
   * @return a sanitized locale identifier (e.g. {@code "en"}, {@code "de"}); never null/blank
   */
  public static String resolve(File pluginDirectory) {
    if (pluginDirectory == null) return DEFAULT_LOCALE;
    if (!pluginDirectory.exists()) {
      //noinspection ResultOfMethodCallIgnored
      pluginDirectory.mkdirs();
    }

    File file = new File(pluginDirectory, FILE_NAME);
    if (!file.exists()) {
      RTP.log(Level.FINE, "[RTP] LanguageBootstrap: " + FILE_NAME
          + " missing; writing default and using '" + DEFAULT_LOCALE + "'");
      writeDefault(file);
      return DEFAULT_LOCALE;
    }

    try {
      RTP.log(Level.FINER, "[RTP] LanguageBootstrap: reading " + file.getPath());
      RtpYamlConfig yaml = new RtpYamlConfig(file.getPath());
      yaml.loadWithComments();
      Object raw = yaml.get(KEY);
      String locale = (raw == null) ? DEFAULT_LOCALE : raw.toString().trim();
      String sanitized = sanitize(locale);
      RTP.log(Level.FINE, "[RTP] LanguageBootstrap: resolved locale '" + sanitized + "'");
      return sanitized;
    } catch (IOException e) {
      RTP.log(Level.WARNING,
          "[RTP] Failed to read " + FILE_NAME + "; falling back to '" + DEFAULT_LOCALE + "'.", e);
      return DEFAULT_LOCALE;
    }
  }

  /**
   * Validate and normalize a locale string. Rejects path-traversal and unsafe characters.
   *
   * @param locale raw locale string
   * @return sanitized locale, or {@link #DEFAULT_LOCALE} if invalid
   */
  public static String sanitize(String locale) {
    if (locale == null) return DEFAULT_LOCALE;
    String normalized = locale.trim();
    if (normalized.isEmpty()) return DEFAULT_LOCALE;
    if (!normalized.matches("[A-Za-z0-9_-]+")) {
      RTP.log(Level.WARNING,
          "[RTP] Invalid language '" + locale + "' in " + FILE_NAME
              + "; falling back to '" + DEFAULT_LOCALE + "'.");
      return DEFAULT_LOCALE;
    }
    return normalized;
  }

  private static void writeDefault(File file) {
    // Prefer the bundled resource (carries documentation comments) when available.
    try (InputStream in = LanguageBootstrap.class.getClassLoader().getResourceAsStream(FILE_NAME)) {
      if (in != null) {
        Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return;
      }
    } catch (IOException e) {
      RTP.log(Level.WARNING, "[RTP] Failed to extract bundled " + FILE_NAME + "; writing minimal default.", e);
    }
    try {
      RtpYamlConfig yaml = new RtpYamlConfig(file.getPath());
      yaml.set(KEY, DEFAULT_LOCALE);
      yaml.setComment(KEY,
          "Active locale for all configuration files. Bundled locales: en, de, es, fr.\n"
              + "On change, run /rtp reload (or restart). Locale-specific files live in lang/<locale>/.");
      yaml.save();
    } catch (IOException e) {
      RTP.log(Level.WARNING, "[RTP] Failed to create " + FILE_NAME, e);
    }
  }
}
