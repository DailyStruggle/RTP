package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.stream.Collectors;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Class for parsing configuration files
 *
 * @param <E> enum of configuration keys
 */
public class ConfigParser<E extends Enum<E>> extends FactoryValue<E> implements ConfigLoader {
  /** The file database used by this parser */
  public final YamlFileDatabase fileDatabase;

  /** The version of the configuration */
  public String version;

  /** The directory where the configuration is stored */
  public File pluginDirectory;

  /**
   * ADR-076: rename-map file this parser reads/writes. For a {@link MultiConfigParser}
   * child, shared folder sibling map; for standalone, co-located {@code .<name>.lang.yml}.
   * Retained to preserve the map on clone/construct paths.
   */
  public File langFile;

  /**
   * ADR-071: normalized relative sub-directory below {@link #pluginDirectory}. Empty for
   * root-level configs. Drives {@link #configDir()} and {@link #jarPrefix()}.
   */
  public String subDir = "";

  /**
   * JAR-resource sub-directory override decoupled from {@link #subDir}. Used by
   * {@link MultiConfigParser} children whose bundled resources live under a definitions sub-directory.
   */
  public String jarSubDir = "";

  /**
   * Active locale for this parser (e.g. {@code "en"}, {@code "de"}). Drives the JAR resource
   * path used for first-extraction and the {@code .lang.yml} key-mapping path. See
   * {@link LanguageBootstrap}.
   */
  public String locale = LanguageBootstrap.DEFAULT_LOCALE;

  /** Map for language translations */
  public Map<String, Object> language_mapping = new ConcurrentHashMap<String, Object>();

  /** Reverse map for language translations */
  public Map<String, String> reverse_language_mapping = new ConcurrentHashMap<>();

  /**
   * ADR-073: records the raw {@code @<file>} reference token a key was configured with,
   * when its value inherits a global default rather than being a literal. Populated as
   * settings are resolved (e.g. by {@code RegionConfigLoader}); used by the menu to show
   * an "inheriting from &lt;file&gt;" state and to offer the inherit/override toggle. A key
   * absent from this map is a local literal (or was never resolved).
   */
  public final Map<E, String> defaultReferences = new ConcurrentHashMap<>();

  /** Cached lookup for YAML files */
  AtomicReference<Map<String, RtpYamlConfig>> cachedLookup;

  private ClassLoader classLoader = this.getClass().getClassLoader();

  /**
   * Number of rotating {@code <name>.bak.<ts>} siblings retained per config
   * file on each {@link #save()}. Shared by the live config commands and the
   * admin prefab pipeline so both honor the same retention cap.
   */
  public static int bakRetention = ConfigBackups.DEFAULT_BAK_RETENTION;

  /** Constructor with default English locale. */
  public ConfigParser(
      Class<E> eClass,
      final String name,
      final String version,
      final File pluginDirectory,
      File langFile,
      YamlFileDatabase fileDatabase,
      ClassLoader classLoader) {
    this(eClass, name, version, pluginDirectory, langFile, fileDatabase, classLoader,
        LanguageBootstrap.DEFAULT_LOCALE);
  }

  /** Locale-aware constructor. */
  public ConfigParser(
      Class<E> eClass,
      final String name,
      final String version,
      final File pluginDirectory,
      File langFile,
      YamlFileDatabase fileDatabase,
      ClassLoader classLoader,
      String locale) {
    super(eClass, sanitizeName(leafName(name)));
    this.version = version;
    this.pluginDirectory = pluginDirectory;
    this.name = resolveName(name);
    // ADR-071: a subpathed parser reads/writes under pluginDirectory/<subDir>, so it
    // operates against a file database rooted there (mirroring MultiConfigParser);
    // a plain root-level parser keeps the shared, root-rooted database.
    this.fileDatabase = subDir.isEmpty() ? fileDatabase : new YamlFileDatabase(configDir());
    this.classLoader = classLoader;
    this.locale = LanguageBootstrap.sanitize(locale);
    check(version, pluginDirectory, langFile);
  }

  /** Constructor without custom class loader, default English locale. */
  public ConfigParser(
      Class<E> eClass,
      final String name,
      final String version,
      final File pluginDirectory,
      File langFile,
      YamlFileDatabase fileDatabase) {
    this(eClass, name, version, pluginDirectory, langFile, fileDatabase,
        LanguageBootstrap.DEFAULT_LOCALE);
  }

  /** Locale-aware constructor without custom class loader. */
  public ConfigParser(
      Class<E> eClass,
      final String name,
      final String version,
      final File pluginDirectory,
      File langFile,
      YamlFileDatabase fileDatabase,
      String locale) {
    this(eClass, name, version, pluginDirectory, langFile, fileDatabase, locale, null);
  }

  /**
   * Locale-aware constructor with explicit JAR resource sub-directory override.
   *
   * @param jarSubDir JAR/{@code lang/} resource sub-directory, or null/empty for none
   */
  public ConfigParser(
      Class<E> eClass,
      final String name,
      final String version,
      final File pluginDirectory,
      File langFile,
      YamlFileDatabase fileDatabase,
      String locale,
      String jarSubDir) {
    super(eClass, sanitizeName(leafName(name)));
    this.version = version;
    this.pluginDirectory = pluginDirectory;
    this.name = resolveName(name);
    this.jarSubDir = normalizeJarSubDir(jarSubDir);
    this.fileDatabase = subDir.isEmpty() ? fileDatabase : new YamlFileDatabase(configDir());
    this.locale = LanguageBootstrap.sanitize(locale);
    check(version, pluginDirectory, langFile);
  }

  /** Constructor with default language file, class loader, and English locale. */
  public ConfigParser(
      Class<E> eClass,
      final String name,
      final String version,
      final File pluginDirectory,
      YamlFileDatabase fileDatabase) {
    this(eClass, name, version, pluginDirectory, fileDatabase, LanguageBootstrap.DEFAULT_LOCALE);
  }

  /** Locale-aware constructor with default language file and class loader. */
  public ConfigParser(
      Class<E> eClass,
      final String name,
      final String version,
      final File pluginDirectory,
      YamlFileDatabase fileDatabase,
      String locale) {
    super(eClass, sanitizeName(leafName(name)));
    this.version = version;
    this.pluginDirectory = pluginDirectory;
    this.name = resolveName(name);
    this.fileDatabase = subDir.isEmpty() ? fileDatabase : new YamlFileDatabase(configDir());
    this.locale = LanguageBootstrap.sanitize(locale);
    check(version, pluginDirectory, null);
  }

  /**
   * Sanitize a configuration name for use as a filesystem path component. Replaces characters
   * that are illegal on Windows ({@code : \ / * ? " < > |}) with {@code _}. Necessary for
   * Fabric world identifiers like {@code minecraft:overworld} that would otherwise crash
   * with {@link java.nio.file.InvalidPathException} when used to build a per-world config or
   * lang file path.
   */
  static String sanitizeName(String name) {
    if (name == null) return null;
    return name.replaceAll("[:\\\\/*?\"<>|]", "_");
  }

  /**
   * ADR-071: returns the bare leaf file-name component of a (possibly subpathed) parser
   * {@code name}, dropping any {@code advanced/} / {@code messages/} relative prefix.
   * Separators are normalized so both {@code /} and {@code \} are honored. Used for the
   * {@link FactoryValue} name passed to {@code super(...)} so the {@code /rtp config}
   * sub-command stays unqualified.
   */
  static String leafName(String name) {
    if (name == null) return null;
    String norm = name.replace('\\', '/');
    int idx = norm.lastIndexOf('/');
    return (idx >= 0) ? norm.substring(idx + 1) : norm;
  }

  /**
   * ADR-071: parse a (possibly subpathed) raw parser {@code name}, set {@link #subDir} to
   * the '/'-normalized, per-segment-sanitized relative directory (empty when none), and
   * return the sanitized bare leaf file name (with a {@code .yml} suffix ensured).
   *
   * @param rawName the raw parser name, e.g. {@code "advanced/blocks.yml"} or {@code "config.yml"}
   * @return the sanitized leaf file name ending in {@code .yml}
   */
  private String resolveName(String rawName) {
    String norm = (rawName == null) ? "" : rawName.replace('\\', '/');
    int idx = norm.lastIndexOf('/');
    if (idx >= 0) {
      StringBuilder sb = new StringBuilder();
      for (String seg : norm.substring(0, idx).split("/")) {
        if (seg.isEmpty()) continue;
        if (sb.length() > 0) sb.append('/');
        sb.append(sanitizeName(seg));
      }
      this.subDir = sb.toString();
      norm = norm.substring(idx + 1);
    } else {
      this.subDir = "";
    }
    String sn = sanitizeName(norm);
    return sn.endsWith(".yml") ? sn : sn + ".yml";
  }

  /**
   * ADR-071: the on-disk directory holding this parser's YAML file - {@link #pluginDirectory}
   * for a root-level config, or {@code pluginDirectory/<subDir>} for a subpathed one.
   */
  private File configDir() {
    if (subDir == null || subDir.isEmpty()) return pluginDirectory;
    return new File(pluginDirectory, subDir.replace('/', File.separatorChar));
  }

  /**
   * ADR-071: the JAR-resource / {@code lang/<locale>/} mirror prefix for this parser -
   * empty for a root-level config, or {@code "<subDir>/"} (always forward-slashed) for a
   * subpathed one.
   */
  private String jarPrefix() {
    if (jarSubDir != null && !jarSubDir.isEmpty()) return jarSubDir + "/";
    return (subDir == null || subDir.isEmpty()) ? "" : subDir + "/";
  }

  /**
   * Normalize a raw JAR sub-directory override: {@code '/'}-normalized, sanitized per segment,
   * with no leading/trailing separator. Empty (never {@code null}) when absent.
   */
  private static String normalizeJarSubDir(String raw) {
    if (raw == null) return "";
    String norm = raw.replace('\\', '/');
    StringBuilder sb = new StringBuilder();
    for (String seg : norm.split("/")) {
      if (seg.isEmpty()) continue;
      if (sb.length() > 0) sb.append('/');
      sb.append(sanitizeName(seg));
    }
    return sb.toString();
  }

  private static void setSection(RtpYamlSection section, Map<?, ?> map) {
    Map<String, Object> mapValues = section.getMapValues(false);
    Map<?, ?> inputClone = new HashMap<>(map);

    for (Map.Entry<?, ?> e : mapValues.entrySet()) {
      String key = e.getKey().toString();
      Object o = e.getValue();
      if (!map.containsKey(key)) {
        section.remove(key);
        continue;
      }
      Object value = map.get(key);
      if (o instanceof RtpYamlSection) {
        if (value instanceof FactoryValue<?>) {
          EnumMap<?, Object> data = ((FactoryValue<?>) value).getData();
          Map<String, Object> subMap = new HashMap<>();
          for (Map.Entry<? extends Enum<?>, ?> d : data.entrySet())
            subMap.put(d.getKey().name(), d.getValue());
          setSection((RtpYamlSection) o, subMap);
        } else if (value instanceof Map) {
          setSection((RtpYamlSection) o, (Map<String, Object>) value);
        } else throw new IllegalArgumentException();
      } else section.set(key, value);
      inputClone.remove(key);
    }

    for (Map.Entry<?, ?> e : inputClone.entrySet()) {
      String key = e.getKey().toString();
      Object o = e.getValue();
      if (o instanceof Map) {
        section.createSection(key, (Map<?, ?>) o);
      } else if (o instanceof FactoryValue) {
        EnumMap<?, Object> data = ((FactoryValue<?>) o).getData();
        Map<String, Object> subMap = new HashMap<>();
        for (Map.Entry<? extends Enum<?>, ?> d : data.entrySet())
          subMap.put(d.getKey().name(), d.getValue());
        section.createSection(key, subMap);
      } else section.set(key, o);
    }
  }

  /**
   * Load the language file
   *
   * @param langFile the language file, or null to use default
   * @throws IOException if an I/O error occurs
   */
  protected void loadLangFile(@Nullable File langFile) throws IOException {
    // ADR-076: the rename map is a co-located dotfile sibling of the value file -
    // `.<name>.lang.yml` beside `<name>.yml`, for EVERY locale. The value files are
    // extracted flat into the data directory (configDir()) regardless of locale, so
    // the map must sit right beside them - there is no per-locale `lang/<locale>/`
    // mirror in the extracted data directory. The `lang/<locale>/` layout exists only
    // for the bundled JAR resources (how translations are shipped and read below).
    String dotMapName = "." + name.replace(".yml", ".lang.yml");
    boolean autoResolved = false;

    if (langFile == null) {
      autoResolved = true;
      // One-time cleanup: earlier builds mirrored a non-English locale's maps into
      // plugins/RTP/lang/<locale>/... Remove that stray dotfile so it no longer
      // confuses operators; the authoritative copy is the colocated sibling below.
      purgeLegacyLocaleLangMirror();

      File langDir = configDir();
      if (!langDir.exists()) {
        boolean mkdir = langDir.mkdirs();
        if (!mkdir) throw new IllegalStateException();
      }
      langFile = new File(langDir, dotMapName);
    }

    // Retain the resolved map so clone()/Factory reuse it rather than passing null (which would
    // re-auto-resolve to a stray per-file map inside a MultiConfigParser folder).
    this.langFile = langFile;

    RtpYamlConfig langYaml = new RtpYamlConfig(langFile.getPath());
    language_mapping.clear();
    reverse_language_mapping.clear();

    // Re-extract the active locale's map from the JAR. The colocated dotfile persists across
    // locale switches (the English baseline copy is written on first install), so extracting only
    // when absent would freeze the map at the first-installed locale. The map is read-only bundled
    // metadata (operators do not rename config keys), so overwriting on load is safe and is what
    // makes a language change take effect for the rename map too. This full re-extraction applies
    // to a self-resolved (colocated) map; an externally supplied map (e.g. MultiConfigParser's
    // shared definitions map) is only extracted when absent, preserving that path's original
    // "extract defaults on first install" behavior.
    boolean extracted = false;
    if (autoResolved || !langFile.exists()) {
      // Try locale-specific JAR resource first, then English fallback. The
      // jarPrefix() carries any ADR-071 subdirectory (e.g. advanced/, messages/).
      String localeJarPrefix = isEnglish() ? "" : ("lang/" + locale + "/");
      String jarPath = localeJarPrefix + jarPrefix() + langFile.getName();
      try {
        java.io.InputStream in = RTP.class.getClassLoader().getResourceAsStream(jarPath);
        if (in == null && !isEnglish()) {
          // Fallback: English baseline mapping (co-located sibling at JAR root/subDir).
          in = RTP.class.getClassLoader().getResourceAsStream(jarPrefix() + langFile.getName());
        }
        if (in != null) {
          File parent = langFile.getParentFile();
          if (parent != null && !parent.exists()) parent.mkdirs();
          try (java.io.InputStream src = in;
              java.io.FileOutputStream out = new java.io.FileOutputStream(langFile)) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = src.read(buf)) > 0) {
              out.write(buf, 0, len);
            }
          }
          extracted = true;
        }
      } catch (Exception ignored) {
      }
    }

    if (!extracted && !langFile.exists()) {
      for (String key : keys()) { // default data, to guard exceptions
        langYaml.set(key, key);
        language_mapping.put(key, key);
        reverse_language_mapping.put(key, key);
      }
      langYaml.save();
    }

    langYaml.loadWithComments();
    language_mapping = langYaml.getMapValues(true);
    language_mapping.forEach((s, o) -> reverse_language_mapping.put(o.toString(), s));
  }

  /**
   * Remove the legacy {@code plugins/RTP/lang/<locale>/...} rename-map mirror that earlier builds
   * extracted into the data directory. The active locale's map is now colocated beside its value
   * file at {@link #configDir()}, so the entire data-directory {@code lang/} tree is stray - it
   * only ever held these {@code .lang.yml} dotfiles and is never read anymore. Best-effort: any
   * I/O failure is ignored, and empty ancestor directories are pruned so the {@code lang/} folder
   * disappears once the last file is gone.
   */
  private void purgeLegacyLocaleLangMirror() {
    File legacyRoot = new File(pluginDirectory, "lang");
    if (!legacyRoot.isDirectory()) return;
    try {
      deleteRecursively(legacyRoot);
    } catch (RuntimeException ignored) {
      // best effort; a leftover folder is harmless, just cosmetic
    }
  }

  private static void deleteRecursively(File f) {
    if (f == null || !f.exists()) return;
    if (f.isDirectory()) {
      File[] children = f.listFiles();
      if (children != null) {
        for (File c : children) deleteRecursively(c);
      }
    }
    try {
      Files.deleteIfExists(f.toPath());
    } catch (IOException ignored) {
      // best effort
    }
  }

  /**
   * Detects locale mismatch on disk (e.g. English file with Spanish selected). Preserves
   * user-customized scalar values, backs up the old file, and extracts locale defaults.
   *
   * @param pluginDirectory plugin data directory
   * @return enum-to-value map of customizations to re-apply after extraction
   */
  private Map<E, Object> detectAndPreserveLocaleMismatch(File pluginDirectory) {
    Map<E, Object> preserved = new EnumMap<>(myClass);
    File f = new File(configDir(), this.name);
    if (!f.exists()) return preserved;

    // Primary, format-agnostic gate: if the on-disk file is an unmodified pristine copy of a
    // DIFFERENT bundled locale than the active one, back it up once and re-extract the active
    // locale's resource. This covers key-renamed, value-translated, and comment-only-translated
    // files (e.g. advanced/biomes.yml) uniformly, and both switch directions (English<->locale,
    // including the fr->en return the value-based path below cannot see). It is idempotent - once
    // the file equals the active locale resource it never fires again - so a repeated reload never
    // accumulates redundant .old backups.
    if (onDiskIsPristineForeignLocale(f)) {
      RTP.log(
          Level.INFO,
          "[RTP] Locale switch detected for "
              + this.name
              + " (active locale: "
              + locale
              + "); backing up to "
              + this.name
              + ".old1 and extracting locale-specific defaults.");
      migrateToActiveLocale();
      return preserved;
    }

    // Already exactly in the active locale (a plain reload with no language change, or a second
    // parser build within the same reload): do nothing. This is authoritative over the value/key
    // heuristic below, which for some files (e.g. safety.yml) would otherwise re-decide "needs
    // migration" and rotate a redundant backup on every reload - the double-.old regression.
    if (onDiskMatchesActiveLocale(f)) return preserved;

    // The value/key-preserving migration path below only applies to a non-default locale (the
    // English baseline is the source of truth for value recovery). A pristine foreign file - in
    // either direction - was already handled by the gate above.
    if (isEnglish()) return preserved;

    // If the active locale's key mapping is entirely identity (every value
    // equals its key), the locale does not actually rename any keys for this
    // file. The on-disk English-keyed file IS already a valid locale-keyed
    // file, and migration would just re-extract identical defaults, backing
    // up an unchanged file on every reload (e.g. integrations.yml has no
    // lang/<locale>/ counterpart, so loadLangFile seeded an identity map).
    boolean anyRename = false;
    for (Map.Entry<String, Object> entry : language_mapping.entrySet()) {
      Object v = entry.getValue();
      if (v != null && !v.toString().equalsIgnoreCase(entry.getKey())) {
        anyRename = true;
        break;
      }
    }
    if (!anyRename) {
      // No key renames for this locale: either no localized JAR resource exists (identity map),
      // or translations are value-only (messages). Value-only files migrate on locale switch.
      if (!valueOnlyLocaleMigrationNeeded(f)) return preserved;
    }

    RtpYamlConfig oldYaml = new RtpYamlConfig(f.getPath());
    try {
      oldYaml.loadWithComments();
    } catch (IOException | RuntimeException e) {
      // Corrupt YAML on disk (invalid syntax, truncated file, etc.). Quarantine
      // it so the next load can extract a clean copy from the JAR; otherwise
      // every subsequent reload would re-trip the parser and the plugin would
      // never recover without manual operator intervention.
      RTP.log(
          Level.WARNING,
          "[RTP] " + f + " appears to be corrupt; quarantining and re-extracting defaults.",
          e);
      quarantineCorruptFile(pluginDirectory, f);
      return preserved;
    }

    Set<String> topKeys = oldYaml.getKeys(false);
    if (topKeys.isEmpty()) return preserved;

    int activeLocaleHits = 0;
    int englishBaselineHits = 0;
    Map<String, E> recovered = new HashMap<>();
    for (String key : topKeys) {
      if (key.equalsIgnoreCase("version")) continue;
      // A reverse-mapping hit only signals "active locale" when the active
      // locale renames the key. Identity mappings (e.g. es/messages.lang.yml
      // contains `infoTickets: infoTickets`) appear in BOTH the English and
      // Spanish files and therefore carry no locale signal - counting them
      // here would short-circuit the migration of any English-keyed file that
      // happens to share an identity-mapped key with the active locale (the
      // exact symptom seen with messages.yml: `infoTickets`/`infoMSPT` kept
      // their English names in es so the file looked "already Spanish").
      String mapped = reverse_language_mapping.get(key);
      // Treat as an active-locale hit only when reverse mapping resolves to a
      // *different* canonical (English) name. Identity entries carry no signal.
      if (mapped != null && !mapped.equalsIgnoreCase(key)) activeLocaleHits++;
      E enumKey = enumLookup.get(key.toLowerCase(Locale.ROOT));
      if (enumKey != null) {
        englishBaselineHits++;
        recovered.put(key, enumKey);
      }
    }

    // Already in the active locale (or mixed) → nothing to migrate.
    if (activeLocaleHits > 0) return preserved;
    // No baseline keys either → unknown shape; do not touch the user's file.
    if (englishBaselineHits == 0) return preserved;

    // Foreign-locale file detected. Capture user-set scalar values before backup,
    // but ONLY for entries that genuinely differ from the English baseline default
    // shipped in the JAR. Otherwise an unmodified English `messages.yml` would be
    // preserved verbatim and re-applied over the freshly-extracted localized
    // defaults - defeating the locale switch (the values would stay English even
    // though the keys are now Spanish).
    Map<E, Object> englishDefaults = loadEnglishBaselineDefaults();
    for (Map.Entry<String, E> e : recovered.entrySet()) {
      Object value = oldYaml.get(e.getKey());
      if (value == null) continue;
      if (value instanceof RtpYamlSection) continue; // skip nested sections
      Object englishDefault = englishDefaults.get(e.getValue());
      if (valuesEqual(value, englishDefault)) {
        // Unmodified English default → let the localized default win.
        continue;
      }
      preserved.put(e.getValue(), value);
    }

    RTP.log(
        Level.INFO,
        "[RTP] Locale switch detected for "
            + this.name
            + " (active locale: "
            + locale
            + "). Backing up old file to "
            + this.name
            + ".old1 and extracting locale-specific defaults; preserved "
            + preserved.size()
            + " customized value(s).");

    // Back up and re-extract the locale-specific JAR resource, then re-apply preserved values.
    migrateToActiveLocale();
    return preserved;
  }

  /**
   * Loads English baseline scalar defaults from the JAR root resource.
   * Used during locale switch to separate defaults from user customizations.
   */
  private Map<E, Object> loadEnglishBaselineDefaults() {
    Map<E, Object> defaults = new EnumMap<>(myClass);
    java.io.InputStream in = getResourceFromJar(jarPrefix() + this.name);
    if (in == null) return defaults;
    File tmp = null;
    try {
      tmp = File.createTempFile("rtp-baseline-", "-" + this.name.replace('/', '_'));
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
      }
      RtpYamlConfig baseline = new RtpYamlConfig(tmp.getPath());
      baseline.loadWithComments();
      for (String key : baseline.getKeys(false)) {
        E enumKey = enumLookup.get(key.toLowerCase(Locale.ROOT));
        if (enumKey == null) continue;
        Object value = baseline.get(key);
        if (value == null) continue;
        if (value instanceof RtpYamlSection) continue;
        defaults.put(enumKey, value);
      }
    } catch (IOException | RuntimeException ex) {
      // Treat any failure as "no baseline known"; caller will preserve all values.
    } finally {
      try { in.close(); } catch (IOException ignored) {}
      if (tmp != null) {
        try { java.nio.file.Files.deleteIfExists(tmp.toPath()); } catch (IOException ignored) {}
      }
    }
    return defaults;
  }

  /**
   * Format-agnostic gate. Returns true when the on-disk file is an unmodified copy of
   * a different bundled locale than the active one, needing backup and replacement.
   */
  private boolean onDiskIsPristineForeignLocale(File f) {
    String activeText = normalizeYamlText(readActiveLocaleResourceText());
    if (activeText == null) return false;
    String disk = normalizeYamlText(readFileTextQuietly(f));
    if (disk == null || disk.isEmpty()) return false;
    if (disk.equals(activeText)) return false; // already the active locale

    String enText = normalizeYamlText(readClasspathText(jarPrefix() + this.name));
    if (enText != null && !enText.equals(activeText) && disk.equals(enText)) return true;

    for (String loc : bundledLocales()) {
      if (loc.equalsIgnoreCase(locale)) continue;
      String t = normalizeYamlText(readClasspathText("lang/" + loc + "/" + jarPrefix() + this.name));
      if (t != null && !t.equals(activeText) && disk.equals(t)) return true;
    }
    return false;
  }

  /**
   * @return {@code true} when the on-disk file is (normalized) byte-identical to the active
   *     locale's shipped resource - i.e. already fully localized, so no migration/backup is
   *     warranted. Inert (returns {@code false}) when the active resource is not on the classpath.
   */
  private boolean onDiskMatchesActiveLocale(File f) {
    String activeText = normalizeYamlText(readActiveLocaleResourceText());
    if (activeText == null) return false;
    String disk = normalizeYamlText(readFileTextQuietly(f));
    return disk != null && disk.equals(activeText);
  }

  /** Full text of the active locale's shipped resource, English baseline when {@code isEnglish()}. */
  private String readActiveLocaleResourceText() {
    if (isEnglish()) return readClasspathText(jarPrefix() + this.name);
    String t = readClasspathText("lang/" + locale + "/" + jarPrefix() + this.name);
    return (t != null) ? t : readClasspathText(jarPrefix() + this.name);
  }

  private static String readClasspathText(String path) {
    try (java.io.InputStream in = RTP.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) return null;
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }

  private static String readFileTextQuietly(File f) {
    try {
      return new String(Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Normalize a YAML document for locale-identity comparison: LF line endings, right-trimmed
   * lines, dropped {@code version:} line (identical across locales, independently managed), and
   * no leading/trailing blank lines.
   */
  private static String normalizeYamlText(String s) {
    if (s == null) return null;
    String[] lines = s.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
    StringBuilder sb = new StringBuilder();
    for (String ln : lines) {
      String t = ln.replaceAll("\\s+$", "");
      if (t.matches("(?i)\\s*version\\s*:.*")) continue;
      sb.append(t).append('\n');
    }
    return sb.toString().trim();
  }

  /**
   * Enumerate locale directory names bundled under {@code lang/} on the classpath. Best-effort:
   * returns an empty set when the layout cannot be walked (e.g. unit fixtures without a real
   * {@code lang/} tree).
   */
  private Set<String> bundledLocales() {
    Set<String> out = new LinkedHashSet<>();
    try {
      java.util.Enumeration<java.net.URL> urls = RTP.class.getClassLoader().getResources("lang");
      while (urls.hasMoreElements()) {
        java.net.URL url = urls.nextElement();
        String protocol = url.getProtocol();
        if ("jar".equals(protocol)) {
          String path = url.getPath(); // file:/.../x.jar!/lang
          int bang = path.indexOf('!');
          if (bang < 0) continue;
          String jarPath = path.substring("file:".length(), bang);
          jarPath = java.net.URLDecoder.decode(jarPath, java.nio.charset.StandardCharsets.UTF_8);
          try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath)) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
              String n = entries.nextElement().getName();
              if (n.startsWith("lang/") && n.length() > 5) {
                String rest = n.substring(5);
                int slash = rest.indexOf('/');
                if (slash > 0) out.add(rest.substring(0, slash));
              }
            }
          }
        } else if ("file".equals(protocol)) {
          File dir = new File(url.toURI());
          File[] subs = dir.listFiles(File::isDirectory);
          if (subs != null) for (File d : subs) out.add(d.getName());
        }
      }
    } catch (IOException | java.net.URISyntaxException | RuntimeException e) {
      // best effort; caller degrades to English-baseline comparison only
    }
    return out;
  }

  /**
   * Back up the current on-disk file (rotating to {@code <name>.old1}) and re-extract the active
   * locale's shipped resource in its place, evicting the file-database cache entry so the next
   * {@code connect()} re-reads the freshly extracted file. Seeds a minimal placeholder when no
   * JAR resource exists (test fixtures / addons without a bundled copy).
   */
  private void migrateToActiveLocale() {
    renameFiles();

    // Invalidate the file-database cache for this file. renameFiles() has just
    // replaced the on-disk content with the locale-specific JAR resource, but
    // the cache may still hold the previous (foreign-locale) RtpYamlConfig loaded
    // at startup. Without eviction, the subsequent cachedLookup.containsKey
    // check in check() short-circuits the reconnect, and value lookups via the
    // active locale's key names all return null (the rtp info empty-line bug).
    try {
      Map<String, RtpYamlConfig> cache = fileDatabase.cachedLookup.get();
      if (cache != null) cache.remove(this.name);
      Map<String, Long> mtimes = fileDatabase.cachedLookupLastModified.get();
      if (mtimes != null) mtimes.remove(this.name);
    } catch (Exception ex) {
      RTP.log(Level.WARNING, "[RTP] Failed to invalidate cache for " + this.name, ex);
    }

    // renameFiles() relies on a JAR-bundled localized resource. If it is
    // missing (e.g. test fixtures, addons that ship without lang/<locale>/
    // copies of every config), seed an empty file so the loader has something
    // to populate; preserved values will be written via set() afterwards.
    File seeded = new File(configDir(), this.name);
    if (!seeded.exists()) {
      try {
        File parent = seeded.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (seeded.createNewFile()) {
          try (FileOutputStream out = new FileOutputStream(seeded)) {
            out.write(("version: " + version + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
          }
        }
      } catch (IOException e) {
        RTP.log(Level.WARNING, e.getMessage(), e);
      }
    }

    // The bundled localized resource may carry a stale schema version (a locale value file can
    // lag the English baseline's version bump - e.g. lang/<loc>/safety.yml at 1.0 while the
    // baseline safety.yml is 1.1). If left unstamped, check() sees the mismatch, runs update(),
    // and update() calls renameFiles() a SECOND time - rotating a redundant <name>.old2 on the
    // very same reload (the double-.old regression). Stamp the freshly-extracted file with the
    // required version so the version comparison matches and update() does not re-fire. Locale
    // key-set parity is enforced separately (LocaleParityTest), so the only thing the skipped
    // update() would have changed is this version stamp itself.
    stampVersion(new File(configDir(), this.name), this.version);
  }

  /**
   * Rewrite the top-level {@code version:} line of a just-extracted file to {@code ver} (appending
   * one when absent), preserving all other bytes and the file's existing line endings. Used after
   * a locale migration to prevent a stale-versioned locale resource from immediately re-triggering
   * {@link #update()} (and its backup rotation). No-op on any I/O failure.
   */
  private void stampVersion(File f, String ver) {
    if (f == null || !f.exists()) return;
    try {
      String text = new String(Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
      String eol = text.contains("\r\n") ? "\r\n" : "\n";
      String body = text.replace("\r\n", "\n").replace("\r", "\n");
      String[] lines = body.split("\n", -1);
      boolean replaced = false;
      for (int i = 0; i < lines.length; i++) {
        if (lines[i].matches("(?i)version\\s*:.*")) {
          lines[i] = "version: " + ver;
          replaced = true;
          break;
        }
      }
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < lines.length; i++) {
        sb.append(lines[i]);
        if (i < lines.length - 1) sb.append(eol);
      }
      String out = sb.toString();
      if (!replaced) {
        if (!out.isEmpty() && !out.endsWith(eol)) out += eol;
        out += "version: " + ver + eol;
      }
      Files.write(f.toPath(), out.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (IOException e) {
      RTP.log(Level.WARNING, "[RTP] Failed to stamp version on " + this.name, e);
    }
  }

  /**
   * @return {@code true} when a locale-specific value resource for this file
   *     ({@code lang/<locale>/<subDir>/<name>}) is bundled in the JAR. Always {@code false}
   *     for the English baseline locale (which has no {@code lang/} prefix).
   */
  private boolean localizedResourceExists() {
    if (isEnglish()) return false;
    return RTP.class.getClassLoader().getResource("lang/" + locale + "/" + jarPrefix() + this.name)
        != null;
  }

  /**
   * Loads active locale default scalar values from {@code lang/<locale>/<subDir>/<name>}.
   * Used to distinguish on-disk English baselines from already-localized files.
   */
  private Map<E, Object> loadLocalizedDefaults() {
    Map<E, Object> defaults = new EnumMap<>(myClass);
    java.io.InputStream in = getDefaultsFromJar();
    if (in == null) return defaults;
    File tmp = null;
    try {
      tmp = File.createTempFile("rtp-localized-", "-" + this.name.replace('/', '_'));
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
      }
      RtpYamlConfig localized = new RtpYamlConfig(tmp.getPath());
      localized.loadWithComments();
      for (String key : localized.getKeys(false)) {
        String canonical = reverse_language_mapping.getOrDefault(key, key);
        E enumKey = enumLookup.get(canonical.toLowerCase(Locale.ROOT));
        if (enumKey == null) enumKey = enumLookup.get(key.toLowerCase(Locale.ROOT));
        if (enumKey == null) continue;
        Object value = localized.get(key);
        if (value == null) continue;
        if (value instanceof RtpYamlSection) continue;
        defaults.put(enumKey, value);
      }
    } catch (IOException | RuntimeException ex) {
      // Treat any failure as "no localized baseline known"; caller falls back to no migration.
    } finally {
      try { in.close(); } catch (IOException ignored) {}
      if (tmp != null) {
        try { java.nio.file.Files.deleteIfExists(tmp.toPath()); } catch (IOException ignored) {}
      }
    }
    return defaults;
  }

  /**
   * Decides whether a value-translated file needs active-locale migration by comparing
   * on-disk values against localized defaults for translated keys.
   *
   * @param f on-disk value file
   * @return true when on-disk file should be migrated
   */
  private boolean valueOnlyLocaleMigrationNeeded(File f) {
    if (!localizedResourceExists()) return false;
    Map<E, Object> englishDefaults = loadEnglishBaselineDefaults();
    Map<E, Object> localizedDefaults = loadLocalizedDefaults();
    if (englishDefaults.isEmpty() || localizedDefaults.isEmpty()) return false;

    RtpYamlConfig probe = new RtpYamlConfig(f.getPath());
    try {
      probe.loadWithComments();
    } catch (IOException | RuntimeException e) {
      return false;
    }

    int translated = 0;
    int alreadyLocalized = 0;
    for (E key : myClass.getEnumConstants()) {
      Object en = englishDefaults.get(key);
      Object loc = localizedDefaults.get(key);
      // Only keys the active locale actually translates carry a locale signal.
      if (en == null || loc == null || valuesEqual(en, loc)) continue;
      translated++;
      Object onDiskName = language_mapping.getOrDefault(key.name(), key.name());
      Object onDisk = probe.get(onDiskName.toString());
      if (onDisk != null && valuesEqual(onDisk, loc)) {
        alreadyLocalized++;
      }
    }
    // No translatable signal at all -> nothing to decide on; leave the file untouched.
    if (translated == 0) return false;
    // Migrate unless a majority of translated keys already hold the localized text (i.e. the
    // file is already in the active locale). Using a majority - rather than requiring every
    // key to still be English - tolerates operator customizations and English wording drift
    // between builds without re-backing-up an already-localized file on every reload.
    return alreadyLocalized * 2 < translated;
  }

  /**
   * Structural equality between two YAML-loaded values, tolerant of list/map element types.
   * Falls back to {@link String#valueOf(Object)} comparison so that e.g. a {@code List<String>}
   * loaded from disk compares equal to the same list loaded from the JAR baseline regardless
   * of concrete list implementation.
   */
  private static boolean valuesEqual(Object a, Object b) {
    if (a == b) return true;
    if (a == null || b == null) return false;
    if (a.equals(b)) return true;
    return String.valueOf(a).equals(String.valueOf(b));
  }

  /**
   * Moves corrupt YAML to {@code <name>.corrupt-<ts>} and re-extracts clean defaults.
   * Evicts the file-database cache entry on completion.
   */
  private void quarantineCorruptFile(File pluginDirectory, File corrupt) {
    try {
      File quarantined =
          new File(
              configDir(), this.name + ".corrupt-" + System.currentTimeMillis());
      try {
        Files.move(corrupt.toPath(), quarantined.toPath());
      } catch (IOException moveEx) {
        // Fallback: try delete so saveResource can write a fresh file.
        if (!corrupt.delete()) {
          RTP.log(
              Level.WARNING,
              "[RTP] Could not move or delete corrupt file " + corrupt + ": " + moveEx.getMessage());
          return;
        }
      }

      // Evict cache before re-extraction so subsequent loads see the new file.
      try {
        Map<String, RtpYamlConfig> cache = fileDatabase.cachedLookup.get();
        if (cache != null) cache.remove(this.name);
        Map<String, Long> mtimes = fileDatabase.cachedLookupLastModified.get();
        if (mtimes != null) mtimes.remove(this.name);
      } catch (Exception ignored) {
        // Cache eviction is best-effort.
      }

      // Re-extract a clean default. Prefer the locale-specific JAR resource;
      // fall back to the English baseline.
      boolean extracted = false;
      if (!isEnglish()) {
        extracted = extractLocalizedResource(this.name, true);
      }
      if (!extracted) {
        try {
          saveResource(this.name, true);
        } catch (IOException | IllegalArgumentException e) {
          RTP.log(
              Level.WARNING,
              "[RTP] Failed to re-extract default for corrupt file " + corrupt + ": " + e.getMessage());
        }
      }
    } catch (RuntimeException e) {
      RTP.log(Level.WARNING, "[RTP] quarantineCorruptFile failed for " + corrupt, e);
    }
  }

  /** @return {@code true} when this parser uses the default English locale (no JAR-path prefix). */
  private boolean isEnglish() {
    return locale == null || locale.isEmpty()
        || locale.equalsIgnoreCase(LanguageBootstrap.DEFAULT_LOCALE);
  }

  /**
   * Extract a locale-specific JAR resource ({@code lang/<locale>/<name>}) to
   * {@code <pluginDirectory>/<name>}. Returns {@code true} on success, {@code false} when the
   * locale resource is absent (caller should fall back to the English baseline).
   */
  private boolean extractLocalizedResource(String name, boolean overwrite) {
    String jarPath = "lang/" + locale + "/" + jarPrefix() + name.replace('\\', '/');
    File target = new File(configDir(), name);
    if (target.exists() && !overwrite) return true;
    try (java.io.InputStream in = RTP.class.getClassLoader().getResourceAsStream(jarPath)) {
      if (in == null) return false;
      File parent = target.getParentFile();
      if (parent != null && !parent.exists()) parent.mkdirs();
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
      }
      return true;
    } catch (java.io.IOException e) {
      RTP.log(Level.WARNING, "[RTP] Failed to extract " + jarPath, e);
      return false;
    }
  }

  /**
   * Resolve the JAR input stream for {@link #name}, preferring the locale-specific resource
   * ({@code lang/<locale>/<name>}) and falling back to the English baseline at the JAR root.
   */
  private java.io.InputStream getDefaultsFromJar() {
    if (!isEnglish()) {
      java.io.InputStream localized =
          RTP.class.getClassLoader().getResourceAsStream("lang/" + locale + "/" + jarPrefix() + name);
      if (localized != null) return localized;
    }
    return getResourceFromJar(jarPrefix() + this.name);
  }

  /**
   * Check and update the configuration
   *
   * @param version the required version
   * @param pluginDirectory the plugin directory
   * @param langFile the language file
   */
  public void check(final String version, final File pluginDirectory, @Nullable File langFile) {
    // construct language file from enum vals
    // todo: apply translation to loads and saves
    try {
      loadLangFile(langFile);
    } catch (IOException | IllegalArgumentException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }

    // ADR-020: if the on-disk YAML belongs to a different locale than the one
    // currently resolved by LanguageBootstrap, migrate it. User-customized
    // values are preserved by translating old keys back to enum constants and
    // re-emitting them under the new locale's key names.
    Map<E, Object> preservedValues = detectAndPreserveLocaleMismatch(pluginDirectory);

    File f = new File(configDir(), this.name);
    if (!f.exists()) {
      try {
        saveResource(this.name, true);
      } catch (IOException | IllegalArgumentException e) {
        RTP.log(Level.WARNING, e.getMessage(), e);
      }
    }
    //        loadResource( f );

    cachedLookup = fileDatabase.cachedLookup;
    if (cachedLookup.get() == null || !cachedLookup.get().containsKey(name)) fileDatabase.connect();
    RtpYamlConfig RtpYamlConfig = cachedLookup.get().get(name);

    if (RtpYamlConfig == null) {
      // The file-database failed to load this YAML (likely corrupt syntax).
      // Quarantine the file and re-extract defaults so the plugin self-heals
      // on the next reload, rather than silently running with empty data.
      File corrupt = new File(configDir(), this.name);
      if (corrupt.exists()) {
        RTP.log(
            Level.WARNING,
            "[RTP] " + corrupt + " could not be parsed; quarantining and re-extracting defaults.");
        quarantineCorruptFile(pluginDirectory, corrupt);
        fileDatabase.connect();
        RtpYamlConfig = cachedLookup.get().get(name);
      }
      if (RtpYamlConfig == null) {
        data.clear();
        return;
      }
    }

    String versionStr = RtpYamlConfig.getMapValues(false).getOrDefault("version", "1.0").toString();

    String[] versionArr = Objects.requireNonNull(versionStr).split("\\.");

    boolean update = false;
    String[] split = version.split("\\.");
    List<Integer> parsedVersion =
        Arrays.stream(split).map(Integer::parseUnsignedInt).collect(Collectors.toList());

    if (versionArr.length != parsedVersion.size()) {
      update = true;
    } else {
      for (int i = 0; i < versionArr.length; i++) {
        int v = Integer.parseInt(versionArr[i]);
        int cv = parsedVersion.get(i);
        if (v != cv) {
          update = true;
          break;
        }
      }
    }

    if (update) {
      try {
        update();
      } catch (Exception e) {
        RTP.log(Level.WARNING, e.getMessage(), e);
      }
      f = new File(pluginDirectory, this.name);
      //            loadResource( f );
    }

    data.clear();
    for (E v : myClass.getEnumConstants()) {
      Object name = language_mapping.get(v.name());
      if (name == null) name = v.name();
      Object fromString = RtpYamlConfig.get(name.toString());
      if (fromString != null) {
        data.put(v, fromString);
      }
    }

    // Re-apply customizations recovered from the previous (foreign-locale) file.
    // Uses set() so the new file persists them under the active locale's key names.
    if (preservedValues != null && !preservedValues.isEmpty()) {
      for (Map.Entry<E, Object> entry : preservedValues.entrySet()) {
        try {
          set(entry.getKey(), entry.getValue());
        } catch (IllegalArgumentException ex) {
          RTP.log(
              Level.WARNING,
              "[RTP] Could not preserve customization for "
                  + entry.getKey().name()
                  + " across locale switch: "
                  + ex.getMessage());
        }
      }
      try {
        RtpYamlConfig yf = cachedLookup.get().get(name);
        if (yf != null) yf.save();
      } catch (IOException e) {
        RTP.log(Level.WARNING, e.getMessage(), e);
      }
    }
  }

  /** Rename configuration files if necessary (e.g. on version upgrade) */
  public void renameFiles() {
    // ADR-071: rename within the parser's own (possibly subpathed) directory.
    String dir = configDir().getAbsolutePath();
    // load up a list of files to rename
    ArrayList<File> toRename = new ArrayList<>();
    for (int i = 1; i < 1000; i++) {
      File file = new File(dir + File.separator + name + ".old" + i);
      if (!file.exists()) break;
      toRename.add(file);
    }
    // rename them top-down so as not to overwrite
    for (int i = toRename.size() - 1; i >= 0; i--) {
      File oldFile = toRename.get(i);
      String fileName = oldFile.getName();
      int oldNum = i + 1;
      int newNum = oldNum + 1;
      String newFileName = fileName.replace(Integer.toString(oldNum), Integer.toString(newNum));
      File newFile = new File(dir + File.separator + newFileName);
      try { // ensure can place
        Files.deleteIfExists(newFile.toPath());
      } catch (IOException e) {
        RTP.log(Level.WARNING, e.getMessage(), e);
      }
      boolean b = oldFile.getAbsoluteFile().renameTo(newFile.getAbsoluteFile());
      if (!b)
        RTP.log(
            Level.WARNING,
            "RTP - unable to rename file:" + oldFile.getName() + " to: " + newFile.getName());
    }

    // rename the last one
    File oldFile = new File(dir + File.separator + name);
    File newFile = new File(dir + File.separator + name + ".old1");
    try {
      Files.deleteIfExists(newFile.toPath());
    } catch (IOException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
    boolean b = oldFile.getAbsoluteFile().renameTo(newFile.getAbsoluteFile());
    if (!b) RTP.log(Level.WARNING, "RTP - unable to rename file:" + oldFile.getAbsoluteFile());

    if (isEnglish() || !extractLocalizedResource(this.name, true)) {
      saveResourceFromJar(jarPrefix() + this.name, true);
    }
  }

  /**
   * Get a configuration value
   *
   * @param key the key
   * @param def the default value
   * @return the configuration value
   */
  public Object getConfigValue(E key, Object def) {
    Object value = data.getOrDefault(key, def);
    // ADR-073: a value may be an @<file> reference token that inherits a global default.
    // Resolve at read time so every caller transparently sees the inherited value; the
    // raw token remains in data (and is recorded in defaultReferences) for the menu.
    if (ConfigDefaultResolver.isReference(value)) {
      return ConfigDefaultResolver.resolve(value, key.name(), def);
    }
    return value;
  }

  /**
   * Returns the configuration value for {@code key} as a {@link Map}, or an empty map
   * when the value is absent or not a map type.
   *
   * @param key the configuration key
   * @return the value as a map; never {@code null}
   */
  public Map<String, Object> getMap(E key) {
    Object o = getData(key);
    if (o instanceof RtpYamlSection) {
      return ((RtpYamlSection) o).getMapValues(false);
    }
    if (o instanceof Map) {
      return (Map<String, Object>) o;
    }
    return new HashMap<>();
  }

  /**
   * Returns the loaded YAML document root, or null if uncached.
   * Preserves block comments for {@link RtpYamlSection#getComment(String)}.
   *
   * @return YAML root section, or null when unavailable
   */
  @Nullable
  public RtpYamlSection getYamlRoot() {
    if (cachedLookup == null) return null;
    Map<String, RtpYamlConfig> lookup = cachedLookup.get();
    if (lookup == null) return null;
    return lookup.get(name);
  }

  /**
   * server function for saving a plugin config file from package
   *
   * @param name file name, e.g. "config.yml"
   * @param overwrite whether to overwrite an existing file with that name
   * @throws IOException - file read exceptions
   */
  public void saveResource(String name, boolean overwrite) throws IOException {
    String myDirectory = pluginDirectory.getAbsolutePath();

    String pDirectory = RTP.serverAccessor.getPluginDirectory().getAbsolutePath();
    if (myDirectory.equals(pDirectory)) {
      // Try locale-specific JAR resource first (e.g. lang/de/messages.yml -> messages.yml on disk).
      if (!isEnglish() && extractLocalizedResource(name, overwrite)) {
        return;
      }
      // ADR-071: jarPrefix() carries any advanced/ or messages/ subdirectory so the
      // resource is read from and written under <root>/<subDir>/.
      saveResourceFromJar(jarPrefix() + name, overwrite);
    } else {
      String diff = myDirectory.substring(pDirectory.length() + 1);
      if (name.equals("default.yml")) {
        saveResourceFromJar(diff + File.separator + name, overwrite);
      } else {
        File source = new File(myDirectory + File.separator + "default.yml");
        File target = new File(myDirectory + File.separator + name);
        if (!source.exists()) {
          saveResourceFromJar(diff + File.separator + "default.yml", overwrite);
        }
        if (!target.exists()) {
          boolean newFile = target.createNewFile();
          if (!newFile)
            throw new IOException("failed to create new file - " + target.getAbsolutePath());
        }
        FileOutputStream outputStream = new FileOutputStream(target.getPath());
        Files.copy(source.toPath(), outputStream);
        outputStream.close();
      }
    }
  }

  /** Update the configuration */
  public void update() {
    RtpYamlConfig RtpYamlConfig = cachedLookup.get().get(name);
    if (RtpYamlConfig == null) return;

    // 1. Load existing config into memory to preserve it during rename
    RtpYamlConfig oldYaml = new RtpYamlConfig(new File(configDir(), name));
    try {
      if (oldYaml.exists()) {
        oldYaml.loadWithComments();
      }
    } catch (IOException ignored) {
    }

    renameFiles();

    try {
      RtpYamlConfig.loadWithComments();
      // Ensure RtpYamlConfig has comments from oldYaml if it lost them during rename/load
      for (String key : oldYaml.getKeys(true)) {
        String comment = oldYaml.getComment(key);
        if (comment != null && !comment.isEmpty()) {
          RtpYamlConfig.setComment(key, comment);
        }
      }
      java.io.InputStream in = getDefaultsFromJar();
      if (in != null) {
        RtpYamlConfig defaultYaml = new RtpYamlConfig();
        defaultYaml.loadConfiguration(in, true);

        // 1. Ensure all keys from default are present in RtpYamlConfig
        for (String key : defaultYaml.getKeys(true)) {
          if (!RtpYamlConfig.contains(key)) {
            RtpYamlConfig.set(key, defaultYaml.get(key));
            String comment = defaultYaml.getComment(key);
            if (comment != null) {
              RtpYamlConfig.setComment(key, comment);
            }
          } else {
            // key exists, but maybe it needs a comment if it doesn't have one
            String existingComment = RtpYamlConfig.getComment(key);
            if (existingComment == null || existingComment.isEmpty()) {
              String defaultComment = defaultYaml.getComment(key);
              if (defaultComment != null) {
                RtpYamlConfig.setComment(key, defaultComment);
              }
            }
          }
        }
      }

      // 2. Overlay values from oldYaml to preserve user settings
      for (String key : oldYaml.getKeys(true)) {
        if (key.equalsIgnoreCase("version")) continue;
        if (!oldYaml.isConfigurationSection(key)) {
          RtpYamlConfig.set(key, oldYaml.get(key));
        }
      }

      // Stamp the file with the version this code targets so the next load
      // sees a match and does not re-trigger update()/renameFiles(). Without
      // this, any drift between the bundled resource's version (or a missing
      // version key) and the code's required version rotates a fresh
      // <name>.oldN backup on every single load.
      RtpYamlConfig.set("version", version);

      RtpYamlConfig.save();
    } catch (IOException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  @Override
  public ConfigParser<E> clone() {
    ConfigParser<E> clone = (ConfigParser<E>) super.clone();

    clone.language_mapping = this.language_mapping;
    clone.reverse_language_mapping = this.reverse_language_mapping;
    clone.name = name;
    clone.version = version;
    clone.pluginDirectory = pluginDirectory;
    clone.langFile = langFile;
    clone.check(version, pluginDirectory, langFile);
    return clone;
  }

  @Override
  public void set(@NotNull E key, @NotNull Object value) throws IllegalArgumentException {
    super.set(key, value);

    // Mirror the lazy-load guard used by the constructor and update(): the YAML
    // document may not have been cached yet (e.g. the first mutation happens
    // before any read), in which case reconnect the file database rather than
    // dereferencing a null document.
    if (cachedLookup.get() == null || !cachedLookup.get().containsKey(name)) fileDatabase.connect();
    RtpYamlConfig RtpYamlConfig = cachedLookup.get().get(name);
    if (RtpYamlConfig == null) return;
    Object yamlKey = language_mapping.get(key.name());
    if (yamlKey == null) yamlKey = key.name();
    String yamlKeyStr = yamlKey.toString();

    Object o = RtpYamlConfig.get(yamlKeyStr);
    if (o instanceof RtpYamlSection) {
      RtpYamlSection RtpYamlSection = (RtpYamlSection) o;

      if (RtpYamlSection.getName().equalsIgnoreCase("shape") && value instanceof String) {
        String shapeName = (String) value;
        value = RTP.factoryMap.get(RTP.factoryNames.shape).getOrDefault(shapeName);
      }

      if (RtpYamlSection.getName().equalsIgnoreCase("vert") && value instanceof String) {
        String vertName = (String) value;
        value = RTP.factoryMap.get(RTP.factoryNames.vert).getOrDefault(vertName);
      }

      if (value instanceof FactoryValue<?>) {
        EnumMap<?, Object> data = ((FactoryValue<?>) value).getData();
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<? extends Enum<?>, Object> d : data.entrySet())
          map.put(d.getKey().name(), d.getValue());
        setSection((RtpYamlSection) o, map);
      } else if (value instanceof Map) {
        setSection((RtpYamlSection) o, (Map<String, Object>) value);
      } else {
        throw new IllegalArgumentException(
            "cannot set scalar value on section key '" + yamlKeyStr + "' in " + name);
      }
      RtpYamlConfig.set(yamlKeyStr, o);
    } else if (value instanceof FactoryValue<?> || value instanceof Map) {
      // The new value is a type-bearing block (shape/vert FactoryValue or its
      // map form) but the on-disk slot is not yet a nested section. This is the
      // ADR-073 load path: a region/world file ships `vert: "@config"` (an
      // inheritance reference token) and RegionConfigLoader resolves it, then
      // calls set(...) with the resolved VerticalAdjustor. Writing that object
      // as a raw scalar would serialize it to a mangled single-line string such
      // as `vert: "\nminY: 32\nmaxY: 255\n..."`, destroying both the block and
      // the `@config` inheritance.
      if (ConfigDefaultResolver.isReference(o)) {
        // Preserve the inheritance token verbatim so it keeps inheriting from
        // the global default and is re-resolved cleanly on every load.
        RtpYamlConfig.set(yamlKeyStr, o);
      } else {
        // Otherwise materialize a proper nested section from the block instead
        // of writing the object as a scalar.
        Map<String, Object> map = new LinkedHashMap<>();
        if (value instanceof FactoryValue<?>) {
          FactoryValue<?> fv = (FactoryValue<?>) value;
          map.put("name", fv.name);
          for (Map.Entry<? extends Enum<?>, Object> d : fv.getData().entrySet())
            map.put(d.getKey().name(), d.getValue());
        } else {
          //noinspection unchecked
          map.putAll((Map<String, Object>) value);
        }
        RtpYamlConfig.set(yamlKeyStr, map);
      }
    } else {
      RtpYamlConfig.set(yamlKeyStr, value);
    }
  }

  /**
   * Set a configuration value by its key name
   *
   * @param key the key name
   * @param value the value to set
   */
  public void set(String key, Object value) {
    String translate = reverse_language_mapping.get(key);
    if (translate != null) key = translate;
    E k = enumLookup.get(key.toLowerCase());
    if (k == null) throw new IllegalArgumentException("invalid key - " + key);

    set(k, value);
  }

  /**
   * Sets a configuration value by its string key name. Delegates to {@link #set(String, Object)}.
   *
   * @param key   the string key name
   * @param value the value to set
   */
  public void setConfigValue(String key, Object value) {
    set(key, value);
  }

  /**
   * Save the configuration to disk
   *
   * @throws IOException if an I/O error occurs
   */
  public void save() throws IOException {
    RtpYamlConfig RtpYamlConfig = cachedLookup.get().get(name);
    RtpYamlConfig.options().copyDefaults(true);
    RtpYamlConfig.options().indent(2);
    // Snapshot the current on-disk file as a rotating <name>.bak.<ts> sibling
    // before overwriting it, so an operator (or the prefab rollback path) can
    // restore the previous revision. Best-effort: a backup failure must not
    // block the actual config write.
    try {
      File configFile = RtpYamlConfig.getConfigurationFile();
      if (configFile != null) {
        ConfigBackups.backup(configFile, bakRetention);
      }
    } catch (IOException | RuntimeException backupFailure) {
      RTP.log(Level.WARNING,
          "[RTP] config backup failed for " + name + " - proceeding with save: "
              + backupFailure.getMessage());
    }
    RtpYamlConfig.save();
  }

  @Override
  public File getMainDirectory() {
    return pluginDirectory;
  }

  @Override
  public ClassLoader getClassLoader() {
    return classLoader;
  }
}
