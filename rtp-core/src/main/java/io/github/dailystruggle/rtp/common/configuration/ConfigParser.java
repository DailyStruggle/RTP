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
   * ADR-071: relative sub-directory (below {@link #pluginDirectory}, '/'-normalized,
   * no leading/trailing separator) carried by a subpathed parser {@code name} such as
   * {@code "advanced/blocks.yml"} or {@code "messages/player.yml"}. Empty for a plain
   * root-level config. Drives {@link #configDir()} (the on-disk directory and the
   * sub-rooted file database) and {@link #jarPrefix()} (the JAR-resource and
   * {@code lang/<locale>/} mirror prefix). {@link #name} always holds the bare leaf
   * file name, so the file-database key and the {@code /rtp config} sub-command name
   * stay unqualified.
   */
  public String subDir = "";

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

  /** Cached lookup for YAML files */
  AtomicReference<Map<String, RtpYamlConfig>> cachedLookup;

  private ClassLoader classLoader = this.getClass().getClassLoader();

  /**
   * Number of rotating {@code <name>.bak.<ts>} siblings retained per config
   * file on each {@link #save()}. Shared by the live config commands and the
   * admin prefab pipeline so both honor the same retention cap.
   */
  public static int bakRetention = ConfigBackups.DEFAULT_BAK_RETENTION;

  /**
   * Member file names (relative to the {@code <pluginDir>/messages} directory,
   * e.g. {@code "player.yml"}) when this parser operates in merged-directory
   * mode, or {@code null} for a normal single-file parser. In merged mode the
   * value data for a single enum schema ({@link io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys})
   * is split across several physical files in a {@code messages/} directory;
   * the parser loads and merges them, routing each key's write-back to the file
   * that owns it. Only used for the English baseline (other locales keep the
   * single {@code lang/<locale>/messages.yml}).
   */
  private List<String> mergedMembers = null;

  /** The directory (relative to {@link #pluginDirectory}) that holds the merged member files. */
  private String mergedDir = null;

  /** Live member YAML configs in merged mode, keyed by member file name. */
  private Map<String, RtpYamlConfig> mergedMemberConfigs = new LinkedHashMap<>();

  /** Maps an on-disk top-level YAML key to the member file that owns it (merged mode). */
  private Map<String, String> mergedKeyOwner = new HashMap<>();

  /** Fallback owner member for keys not present in any member file (merged mode). */
  private String mergedPrimaryMember = null;

  /**
   * Merged-directory constructor. Splits a single enum schema across several
   * physical files under {@code <pluginDir>/<dir>/} (e.g. {@code messages/}),
   * loading and merging them into one logical parser. Write-back (set/save) is
   * routed to the member file that owns each key, and an existing single-file
   * {@code <name>} left over from a previous version is migrated into the split
   * tree on first load.
   *
   * @param eClass          the enum class for configuration keys
   * @param name            the logical config name (e.g. {@code "messages.yml"}), used for the
   *                        {@code .lang.yml} mapping and the {@code /rtp config} sub-command name
   * @param version         the required version string
   * @param pluginDirectory the plugin data directory
   * @param fileDatabase    the file database
   * @param locale          the locale code (merged mode is intended for the English baseline)
   * @param dir             the sub-directory holding the member files (e.g. {@code "messages"})
   * @param members         the member file names within {@code dir} (e.g. {@code ["player.yml", ...]})
   */
  public ConfigParser(
      Class<E> eClass,
      final String name,
      final String version,
      final File pluginDirectory,
      YamlFileDatabase fileDatabase,
      String locale,
      String dir,
      List<String> members) {
    super(eClass, sanitizeName(name));
    this.fileDatabase = fileDatabase;
    String sn = sanitizeName(name);
    this.name = (sn.endsWith(".yml")) ? sn : sn + ".yml";
    this.version = version;
    this.pluginDirectory = pluginDirectory;
    this.locale = LanguageBootstrap.sanitize(locale);
    this.mergedDir = dir;
    this.mergedMembers = new ArrayList<>(members);
    // Default ownership for unknown / brand-new keys: the last member (system.yml
    // for messages), which also carries the version stamp.
    this.mergedPrimaryMember = this.mergedMembers.isEmpty()
        ? null
        : this.mergedMembers.get(this.mergedMembers.size() - 1);
    check(version, pluginDirectory, null);
  }

  /**
   * Constructor for ConfigParser
   *
   * @param eClass the enum class for configuration keys
   * @param name the name of the configuration
   * @param version the version of the configuration
   * @param pluginDirectory the plugin directory
   * @param langFile the language file
   * @param fileDatabase the file database
   * @param classLoader the class loader
   */
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

  /**
   * Locale-aware constructor.
   *
   * @param eClass the enum class for configuration keys
   * @param name the name of the configuration
   * @param version the version of the configuration
   * @param pluginDirectory the plugin directory
   * @param langFile the language file (or null to derive from name)
   * @param fileDatabase the file database
   * @param classLoader the class loader
   * @param locale active locale (e.g. {@code "en"}); see {@link LanguageBootstrap}
   */
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

  /**
   * Constructor for ConfigParser without a custom class loader
   *
   * @param eClass the enum class for configuration keys
   * @param name the name of the configuration
   * @param version the version of the configuration
   * @param pluginDirectory the plugin directory
   * @param langFile the language file
   * @param fileDatabase the file database
   */
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

  /**
   * Locale-aware constructor (no custom class loader).
   *
   * @param eClass          the enum class for configuration keys
   * @param name            the name of the configuration file (without {@code .yml} if desired)
   * @param version         the version string stamped into the file
   * @param pluginDirectory the plugin data directory
   * @param langFile        the language file, or {@code null} to use the default
   * @param fileDatabase    the file database used for async I/O
   * @param locale          the locale code, e.g. {@code "en"}
   */
  public ConfigParser(
      Class<E> eClass,
      final String name,
      final String version,
      final File pluginDirectory,
      File langFile,
      YamlFileDatabase fileDatabase,
      String locale) {
    super(eClass, sanitizeName(leafName(name)));
    this.version = version;
    this.pluginDirectory = pluginDirectory;
    this.name = resolveName(name);
    this.fileDatabase = subDir.isEmpty() ? fileDatabase : new YamlFileDatabase(configDir());
    this.locale = LanguageBootstrap.sanitize(locale);
    check(version, pluginDirectory, langFile);
  }

  /**
   * Constructor for ConfigParser with default language file and class loader
   *
   * @param eClass the enum class for configuration keys
   * @param name the name of the configuration
   * @param version the version of the configuration
   * @param pluginDirectory the plugin directory
   * @param fileDatabase the file database
   */
  public ConfigParser(
      Class<E> eClass,
      final String name,
      final String version,
      final File pluginDirectory,
      YamlFileDatabase fileDatabase) {
    this(eClass, name, version, pluginDirectory, fileDatabase, LanguageBootstrap.DEFAULT_LOCALE);
  }

  /**
   * Locale-aware constructor (default lang file, no class loader).
   *
   * @param eClass          the enum class for configuration keys
   * @param name            the name of the configuration file
   * @param version         the version string stamped into the file
   * @param pluginDirectory the plugin data directory
   * @param fileDatabase    the file database used for async I/O
   * @param locale          the locale code, e.g. {@code "en"}
   */
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
    return (subDir == null || subDir.isEmpty()) ? "" : subDir + "/";
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
    String localeDirName = isEnglish() ? "" : (locale + File.separator);
    // ADR-071: a subpathed parser mirrors its lang map under lang/<locale>/<subDir>/.
    String subPart =
        (subDir == null || subDir.isEmpty()) ? "" : subDir.replace('/', File.separatorChar) + File.separator;
    String langSubdir = "lang" + File.separator + localeDirName + subPart;

    if (langFile == null) {
      File langDir = new File(pluginDirectory, langSubdir);
      if (!langDir.exists()) {
        boolean mkdir = langDir.mkdirs();
        if (!mkdir) throw new IllegalStateException();
      }
      langFile = new File(langDir, name.replace(".yml", ".lang.yml"));
    }

    RtpYamlConfig langYaml = new RtpYamlConfig(langFile.getPath());
    language_mapping.clear();
    reverse_language_mapping.clear();
    if (!langFile.exists()) {
      // Try locale-specific JAR resource first, then English fallback. The
      // jarPrefix() carries any ADR-071 subdirectory (e.g. advanced/, messages/).
      String jarPath =
          "lang/" + localeDirName.replace(File.separatorChar, '/') + jarPrefix() + langFile.getName();
      try {
        java.io.InputStream in = RTP.class.getClassLoader().getResourceAsStream(jarPath);
        if (in == null && !isEnglish()) {
          // Fallback: English baseline mapping.
          in = RTP.class.getClassLoader().getResourceAsStream("lang/" + jarPrefix() + langFile.getName());
        }
        if (in != null) {
          File parent = langFile.getParentFile();
          if (parent != null && !parent.exists()) parent.mkdirs();
          java.io.FileOutputStream out = new java.io.FileOutputStream(langFile);
          byte[] buf = new byte[1024];
          int len;
          while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
          }
          out.close();
          in.close();
        }
      } catch (Exception ignored) {
      }
      if (!langFile.exists()) {
        for (String key : keys()) { // default data, to guard exceptions
          langYaml.set(key, key);
          language_mapping.put(key, key);
          reverse_language_mapping.put(key, key);
        }
        langYaml.save();
      }
    }

    langYaml.loadWithComments();
    language_mapping = langYaml.getMapValues(true);
    language_mapping.forEach((s, o) -> reverse_language_mapping.put(o.toString(), s));
  }

  /**
   * Detect a locale-mismatched on-disk YAML (e.g. an English {@code messages.yml} left over
   * from a previous run, while {@code language.yml} now selects {@code es}). When detected,
   * preserve user-customized scalar values keyed by their enum constant, back up the foreign
   * file via {@link #renameFiles()}, and extract the locale-specific resource from the JAR.
   *
   * <p>Heuristic: if zero on-disk top-level keys round-trip through the active locale's
   * {@code reverse_language_mapping}, but at least one round-trips through the canonical
   * English enum names, the file is foreign-locale. Files where some active-locale keys are
   * already present are left untouched (assumed valid for this locale).
   *
   * <p>Customized values are recovered by mapping each foreign key to its enum constant via
   * the lowercased {@code enumLookup}; container/section values are skipped (keeping the
   * fresh defaults). Recovery is best-effort: keys that cannot be resolved to an enum are
   * dropped, with the original on-disk file retained as {@code <name>.old1}.
   *
   * @param pluginDirectory the plugin data directory
   * @return enum→value map of customizations to re-apply after extraction; empty when no
   *     migration was performed
   */
  private Map<E, Object> detectAndPreserveLocaleMismatch(File pluginDirectory) {
    Map<E, Object> preserved = new EnumMap<>(myClass);
    // Only the non-default locales can have their JAR resource re-extracted by
    // renameFiles() / saveResource(). The English baseline is the source of
    // truth, so if locale=="en" there is nothing to migrate towards.
    if (isEnglish()) return preserved;
    File f = new File(configDir(), this.name);
    if (!f.exists()) return preserved;

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
    if (!anyRename) return preserved;

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
      // Spanish files and therefore carry no locale signal — counting them
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
    // defaults — defeating the locale switch (the values would stay English even
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

    // Back up and re-extract the locale-specific JAR resource.
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
    return preserved;
  }

  /**
   * Load the English baseline defaults for {@link #name} from the JAR root resource (e.g.
   * {@code messages.yml} at the JAR root, not {@code lang/<locale>/messages.yml}). Top-level
   * keys in the English baseline are canonical enum names (the English {@code *.lang.yml}
   * file ships identity mappings), so they map 1:1 to {@link E} via {@link #enumLookup}.
   *
   * <p>Used to decide, on locale switch, whether an on-disk value equals the shipped English
   * default (drop it, let the localized default win) or has been customized by the operator
   * (preserve it across the migration).
   *
   * <p>Best-effort: if the resource is missing or fails to parse, returns an empty map and
   * every on-disk value is treated as customized.
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
   * Move a corrupt on-disk YAML aside (to {@code <name>.corrupt-<timestamp>}) and re-extract a
   * clean default copy from the JAR (locale-specific when applicable, English baseline
   * otherwise). The file-database cache entry for {@link #name} is evicted so the next
   * {@code fileDatabase.connect()} call re-reads the freshly extracted file.
   *
   * <p>Best-effort: any IO failure here is logged but does not propagate, because callers are
   * already operating in degraded-recovery paths and must continue.
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
    if (mergedMembers != null) {
      checkMerged(pluginDirectory, langFile);
      return;
    }
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

  /**
   * Merged-directory variant of {@link #check}. Ensures the {@code <pluginDir>/<dir>}
   * member files exist (extracting from the JAR when missing), migrates any legacy
   * single-file {@code <name>} into the split tree, then loads and merges every
   * member file into one logical {@link #data} map. An in-memory aggregate
   * {@link RtpYamlConfig} is published under {@link #name} in the shared file-database
   * cache so {@link #getYamlRoot()} (menu hover) keeps working unchanged.
   */
  private void checkMerged(File pluginDirectory, @Nullable File langFile) {
    try {
      loadLangFile(langFile);
    } catch (IOException | IllegalArgumentException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }

    cachedLookup = fileDatabase.cachedLookup;
    if (cachedLookup.get() == null) fileDatabase.connect();

    File dir = new File(pluginDirectory, mergedDir);
    if (!dir.exists() && !dir.mkdirs()) {
      RTP.log(Level.WARNING, "[RTP] unable to create directory " + dir.getAbsolutePath());
    }

    // Extract any missing member from the JAR (English baseline at messages/<member>).
    for (String member : mergedMembers) {
      File f = new File(dir, member);
      if (!f.exists()) extractMergedMember(member, f);
    }

    // Migrate a legacy single-file messages.yml (operator customizations) into the tree.
    migrateLegacyMessages(pluginDirectory, dir);

    // Load + merge members; build the aggregate and the key->owner map.
    mergedMemberConfigs = new LinkedHashMap<>();
    mergedKeyOwner = new HashMap<>();
    RtpYamlConfig aggregate = new RtpYamlConfig(new File(pluginDirectory, this.name).getPath());
    for (String member : mergedMembers) {
      File f = new File(dir, member);
      if (!f.exists()) continue;
      RtpYamlConfig cfg = new RtpYamlConfig(f.getPath());
      try {
        cfg.loadWithComments();
      } catch (IOException | RuntimeException e) {
        RTP.log(Level.WARNING, "[RTP] failed to load merged member " + f + ": " + e.getMessage());
        continue;
      }
      mergedMemberConfigs.put(member, cfg);
      for (String key : cfg.getKeys(false)) {
        mergedKeyOwner.put(key, member);
        Object value = cfg.get(key);
        aggregate.set(key, value);
        String comment = cfg.getComment(key);
        if (comment != null && !comment.isEmpty()) {
          try {
            aggregate.setComment(key, comment);
          } catch (RuntimeException ignored) {
          }
        }
      }
    }
    cachedLookup.get().put(this.name, aggregate);

    // Populate the enum-keyed data map from the merged aggregate.
    data.clear();
    for (E v : myClass.getEnumConstants()) {
      Object keyName = language_mapping.get(v.name());
      if (keyName == null) keyName = v.name();
      Object fromString = aggregate.get(keyName.toString());
      if (fromString != null) data.put(v, fromString);
    }
  }

  /** Extract a merged member ({@code <dir>/<member>}) from the JAR to {@code target}. */
  private void extractMergedMember(String member, File target) {
    String jarPath = mergedDir + "/" + member;
    try (java.io.InputStream in = RTP.class.getClassLoader().getResourceAsStream(jarPath)) {
      if (in == null) {
        RTP.log(Level.WARNING, "[RTP] missing JAR resource for merged member " + jarPath);
        return;
      }
      File parent = target.getParentFile();
      if (parent != null && !parent.exists()) parent.mkdirs();
      try (FileOutputStream out = new FileOutputStream(target)) {
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
      }
    } catch (IOException e) {
      RTP.log(Level.WARNING, "[RTP] failed to extract merged member " + jarPath, e);
    }
  }

  /**
   * One-time migration of a legacy single-file {@code <pluginDir>/<name>} (e.g.
   * {@code messages.yml}) into the merged member tree. Only operator-customized
   * values (those differing from the shipped English baseline) are transferred,
   * each into the member file that owns its key. The legacy file is then renamed
   * to {@code <name>.migrated} (kept, not deleted) so nothing is lost.
   */
  private void migrateLegacyMessages(File pluginDirectory, File dir) {
    File legacy = new File(pluginDirectory, this.name);
    if (!legacy.exists() || !legacy.isFile()) return;

    RtpYamlConfig old = new RtpYamlConfig(legacy.getPath());
    try {
      old.loadWithComments();
    } catch (IOException | RuntimeException e) {
      RTP.log(Level.WARNING,
          "[RTP] legacy " + this.name + " could not be parsed for migration: " + e.getMessage());
      return;
    }

    // Load live (on-disk) member configs to write the customized values into,
    // and derive key ownership from them so migration works even when the JAR
    // member resources are not on the classpath.
    Map<String, String> keyToMember = new HashMap<>();
    Map<String, RtpYamlConfig> live = new LinkedHashMap<>();
    for (String member : mergedMembers) {
      File f = new File(dir, member);
      RtpYamlConfig cfg = new RtpYamlConfig(f.getPath());
      try {
        if (f.exists()) cfg.loadWithComments();
      } catch (IOException | RuntimeException ignored) {
        continue;
      }
      live.put(member, cfg);
      if (f.exists()) {
        for (String key : cfg.getKeys(false)) keyToMember.putIfAbsent(key, member);
      }
    }

    // Shipped English baseline values (from the bundled member resources), used
    // to skip transferring values the operator never actually changed.
    Map<String, Object> baselineValues = new HashMap<>();
    for (String member : mergedMembers) {
      RtpYamlConfig jar = parseJarYaml(mergedDir + "/" + member);
      if (jar == null) continue;
      for (String key : jar.getKeys(false)) baselineValues.put(key, jar.get(key));
    }

    Set<String> changedMembers = new HashSet<>();
    for (String key : old.getKeys(false)) {
      if (key.equalsIgnoreCase("version")) continue;
      String member = keyToMember.get(key);
      if (member == null) continue; // unknown / removed key - drop
      Object oldVal = old.get(key);
      if (oldVal == null) continue;
      if (valuesEqual(oldVal, baselineValues.get(key))) continue; // not customized
      RtpYamlConfig cfg = live.get(member);
      if (cfg == null) continue;
      cfg.set(key, oldVal);
      changedMembers.add(member);
    }

    for (String member : changedMembers) {
      RtpYamlConfig cfg = live.get(member);
      try {
        cfg.save();
      } catch (IOException e) {
        RTP.log(Level.WARNING, "[RTP] migration: failed to save member " + member + ": " + e.getMessage());
      }
    }

    // Archive the legacy file so it is not re-migrated and nothing is lost.
    File archived = new File(pluginDirectory, this.name + ".migrated");
    try {
      Files.deleteIfExists(archived.toPath());
      Files.move(legacy.toPath(), archived.toPath());
      RTP.log(Level.INFO,
          "[RTP] migrated legacy " + this.name + " into " + mergedDir + "/ (archived as "
              + archived.getName() + ")");
    } catch (IOException e) {
      RTP.log(Level.WARNING, "[RTP] could not archive legacy " + this.name + ": " + e.getMessage());
    }
  }

  /** Parse a bundled JAR YAML resource into a loaded {@link RtpYamlConfig}, or {@code null}. */
  @Nullable
  private RtpYamlConfig parseJarYaml(String jarPath) {
    java.io.InputStream in = RTP.class.getClassLoader().getResourceAsStream(jarPath);
    if (in == null) return null;
    File tmp = null;
    try {
      tmp = File.createTempFile("rtp-merged-", "-" + jarPath.replace('/', '_'));
      try (FileOutputStream out = new FileOutputStream(tmp)) {
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
      }
      RtpYamlConfig cfg = new RtpYamlConfig(tmp.getPath());
      cfg.loadWithComments();
      return cfg;
    } catch (IOException | RuntimeException e) {
      return null;
    } finally {
      try {
        in.close();
      } catch (IOException ignored) {
      }
      if (tmp != null) {
        try {
          Files.deleteIfExists(tmp.toPath());
        } catch (IOException ignored) {
        }
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
    return data.getOrDefault(key, def);
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
   * Returns the loaded YAML document root for this parser, or {@code null}
   * when the file has not been cached yet. The root is a
   * {@link RtpYamlSection} (concretely an {@code RtpYamlConfig}) that
   * preserves block comments, so callers can resolve a key's documentation
   * comment via {@link RtpYamlSection#getComment(String)} (dotted paths
   * accepted). Used by the menu config-search hover resolver.
   *
   * @return the YAML root section, or {@code null} when unavailable
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

        // 2. Overlay values from oldYaml to preserve user settings
        for (String key : oldYaml.getKeys(true)) {
          if (key.equalsIgnoreCase("version")) continue;
          if (!oldYaml.isConfigurationSection(key)) {
            RtpYamlConfig.set(key, oldYaml.get(key));
          }
        }

        RtpYamlConfig.save();
      }
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
    clone.check(version, pluginDirectory, null);
    return clone;
  }

  @Override
  public void set(@NotNull E key, @NotNull Object value) throws IllegalArgumentException {
    super.set(key, value);

    if (mergedMembers != null) {
      setMerged(key, value);
      return;
    }

    RtpYamlConfig RtpYamlConfig = cachedLookup.get().get(name);
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
        IllegalArgumentException exception = new IllegalArgumentException();
        exception.printStackTrace();
        throw exception;
      }
      RtpYamlConfig.set(yamlKeyStr, o);
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
   * Merged-mode write-back: route the edited key to the member file that owns it
   * (defaulting to the primary member for a brand-new key) and keep the in-memory
   * aggregate in sync. {@link #data} was already updated by {@code super.set}.
   */
  private void setMerged(E key, Object value) {
    Object yamlKey = language_mapping.get(key.name());
    if (yamlKey == null) yamlKey = key.name();
    String yamlKeyStr = yamlKey.toString();

    String member = mergedKeyOwner.get(yamlKeyStr);
    if (member == null) {
      member = mergedPrimaryMember;
      if (member != null) mergedKeyOwner.put(yamlKeyStr, member);
    }
    if (member != null) {
      RtpYamlConfig cfg = mergedMemberConfigs.get(member);
      if (cfg != null) cfg.set(yamlKeyStr, value);
    }
    RtpYamlConfig aggregate = cachedLookup.get().get(name);
    if (aggregate != null) aggregate.set(yamlKeyStr, value);
  }

  /**
   * Save the configuration to disk
   *
   * @throws IOException if an I/O error occurs
   */
  public void save() throws IOException {
    if (mergedMembers != null) {
      saveMerged();
      return;
    }
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

  /** Merged-mode save: back up and write every member file. */
  private void saveMerged() throws IOException {
    for (RtpYamlConfig cfg : mergedMemberConfigs.values()) {
      cfg.options().copyDefaults(true);
      cfg.options().indent(2);
      try {
        File configFile = cfg.getConfigurationFile();
        if (configFile != null) {
          ConfigBackups.backup(configFile, bakRetention);
        }
      } catch (IOException | RuntimeException backupFailure) {
        RTP.log(Level.WARNING,
            "[RTP] config backup failed for a " + name + " member - proceeding with save: "
                + backupFailure.getMessage());
      }
      cfg.save();
    }
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
