package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.YamlFileCache;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.server.api.ServerAccessors;
import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Directory-scanning manager for a collection of same-schema {@link ConfigParser} files (e.g.
 * {@code definitions/regions/*.yml}, {@code definitions/worlds/*.yml}).
 *
 * @param <E> enum of configuration keys shared across files in the directory
 */
public class MultiConfigParser<E extends Enum<E>> extends FactoryValue<E> implements ConfigLoader {
  private static final Predicate<String> DEFAULT_DYNAMIC_PARSER_PREDICATE = name -> false;
  private static volatile Predicate<String> defaultCanCreateDynamicParser =
      DEFAULT_DYNAMIC_PARSER_PREDICATE;

  static {
    try {
      Class.forName("io.github.dailystruggle.rtp.common.configuration.Configs");
    } catch (ClassNotFoundException | LinkageError ignored) {
      // Standalone yaml-api usage without rtp-core on the classpath.
    }
  }

  /**
   * Installs the default predicate deciding whether {@link #getParser(String)} can dynamically
   * construct a new child parser when an entry is absent from {@link #configParserFactory}.
   *
   * @param predicate dynamic-entry predicate, or {@code null} to disallow dynamic creation by default
   */
  public static void setDefaultCanCreateDynamicParser(Predicate<String> predicate) {
    defaultCanCreateDynamicParser =
        predicate != null ? predicate : DEFAULT_DYNAMIC_PARSER_PREDICATE;
  }

  /**
   * Returns the process-wide default predicate for dynamic child parser creation.
   *
   * @return default dynamic-entry predicate; never {@code null}
   */
  public static Predicate<String> getDefaultCanCreateDynamicParser() {
    return defaultCanCreateDynamicParser;
  }

  private static void log(Level level, String message) {
    ServerAccessors.log(level, message);
  }

  private static void log(Level level, String message, Throwable throwable) {
    ServerAccessors.log(level, message, throwable);
  }

  public final File pluginDirectory;
  public final File myDirectory;
  /**
   * ADR-076: the on-disk directory (relative to {@code pluginDirectory}) this parser
   * reads/writes, e.g. {@code definitions/regions}. Decoupled from {@link #name} (the
   * kind string, e.g. {@code regions}) so the menu / reload / removal-guards keep
   * resolving parsers by kind while the folder lives under {@code definitions/}.
   */
  public final String directory;
  public final String version;
  /**
   * ADR-020 / REQ-RTP-F-013: the active locale (e.g. {@code "en"}) threaded from
   * {@code Configs.reloadConfigs()} into every per-file child {@link ConfigParser}
   * this parser builds. Without it the children default to
   * {@link LanguageBootstrap#DEFAULT_LOCALE} and never honor an in-game language
   * switch, unlike the single-file parsers.
   */
  public final String locale;
  public final YamlFileCache fileDatabase;
  protected final File langMap;
  public Factory<ConfigParser<E>> configParserFactory = new Factory<>();
  /**
   * Pluggable predicate deciding whether {@link #getParser(String)} may dynamically construct and
   * register a new child {@link ConfigParser} from {@code DEFAULT.YML} when an entry is absent.
   */
  public Predicate<String> canCreateDynamicParser = defaultCanCreateDynamicParser;
  AtomicReference<Map<String, RtpYamlConfig>> cachedLookup;
  private ClassLoader classLoader = this.getClass().getClassLoader();

  /**
   * Installs a custom predicate for dynamic child parser creation on this instance.
   *
   * @param canCreateDynamicParser predicate, or {@code null} to disallow dynamic creation
   */
  public void setCanCreateDynamicParser(Predicate<String> canCreateDynamicParser) {
    this.canCreateDynamicParser =
        canCreateDynamicParser != null ? canCreateDynamicParser : DEFAULT_DYNAMIC_PARSER_PREDICATE;
  }

  /**
   * ADR-076: the shared rename map is a co-located dotfile sibling of the parser
   * directory - {@code <parent>/.<leaf>.lang.yml} beside the {@code <parent>/<leaf>/}
   * folder (e.g. {@code definitions/.regions.lang.yml}) - rather than
   * {@code lang/<name>.lang.yml}.
   */
  private static File dotLangMap(File pluginDirectory, String directory) {
    String dir = directory.replace('\\', '/');
    int slash = dir.lastIndexOf('/');
    String parent = slash >= 0 ? dir.substring(0, slash) : "";
    String leaf = slash >= 0 ? dir.substring(slash + 1) : dir;
    String rel =
        (parent.isEmpty() ? "" : parent.replace('/', File.separatorChar) + File.separator)
            + "."
            + leaf
            + ".lang.yml";
    return new File(pluginDirectory, rel);
  }

  public MultiConfigParser(
      Class<E> eClass, String name, String version, File pluginDirectory, ClassLoader classLoader) {
    this(eClass, name, version, pluginDirectory, classLoader, name);
  }

  public MultiConfigParser(
      Class<E> eClass,
      String name,
      String version,
      File pluginDirectory,
      ClassLoader classLoader,
      String directory) {
    this(eClass, name, version, pluginDirectory, classLoader, directory,
        LanguageBootstrap.DEFAULT_LOCALE);
  }

  public MultiConfigParser(
      Class<E> eClass,
      String name,
      String version,
      File pluginDirectory,
      ClassLoader classLoader,
      String directory,
      String locale) {
    super(eClass, name);
    this.classLoader = classLoader;
    this.locale = LanguageBootstrap.sanitize(locale);
    this.pluginDirectory = pluginDirectory;
    this.name = name;
    this.version = version;
    this.directory = (directory == null || directory.isEmpty()) ? name : directory;
    this.myDirectory =
        new File(pluginDirectory, this.directory.replace('/', File.separatorChar));

    this.fileDatabase = new YamlFileCache(this.myDirectory);
    cachedLookup = fileDatabase.cachedLookup;
    Map<String, RtpYamlConfig> connect = this.fileDatabase.connect();
    this.fileDatabase.disconnect(connect);

    this.langMap = dotLangMap(pluginDirectory, this.directory);
    if (!this.myDirectory.exists() && !myDirectory.mkdirs()) return;

    extractBundledResources();

    File[] files = myDirectory.listFiles();
    if (files == null) return;
    for (File file : files) {
      String fileName = file.getName();
      if (!fileName.endsWith(".yml")) continue;
      if (fileName.contains("old")) continue;
      // ADR-076: a per-file clone (e.g. a per-world parser) writes its colocated rename map
      // `.<name>.lang.yml` beside its value file - i.e. INSIDE this scanned directory. Those
      // dotfiles end in `.yml`, so without this guard the scan would mistake them for value
      // configs, build a parser named `.<name>.lang`, and re-rotate a `.<name>.lang.yml.old<N>`
      // backup on every reload. Skip hidden dotfiles and any `.lang.yml` rename map.
      if (fileName.startsWith(".") || fileName.endsWith(".lang.yml")) continue;

      fileName = fileName.replace(".yml", "");

      ConfigParser<E> parser =
          new ConfigParser<>(
              eClass, fileName, version, myDirectory, this.langMap, fileDatabase, this.locale,
              this.directory);
      addParser(parser);
    }
  }

  public MultiConfigParser(Class<E> eClass, String name, String version, File pluginDirectory) {
    this(eClass, name, version, pluginDirectory, (String) null);
  }

  public MultiConfigParser(
      Class<E> eClass, String name, String version, File pluginDirectory, String directory) {
    this(eClass, name, version, pluginDirectory, directory, LanguageBootstrap.DEFAULT_LOCALE);
  }

  public MultiConfigParser(
      Class<E> eClass,
      String name,
      String version,
      File pluginDirectory,
      String directory,
      String locale) {
    super(eClass, name);
    this.locale = LanguageBootstrap.sanitize(locale);
    this.pluginDirectory = pluginDirectory;
    this.name = name;
    this.version = version;
    this.directory = (directory == null || directory.isEmpty()) ? name : directory;
    this.myDirectory =
        new File(pluginDirectory, this.directory.replace('/', File.separatorChar));

    this.fileDatabase = new YamlFileCache(this.myDirectory);
    Map<String, RtpYamlConfig> connect = this.fileDatabase.connect();
    this.fileDatabase.disconnect(connect);

    try {
      myDirectory.listFiles();
    } catch (Throwable T) {
      log(Level.WARNING, "Error scanning directory for multi config " + name, T);
    }

    this.langMap = dotLangMap(pluginDirectory, this.directory);
    if (!this.myDirectory.exists() && !myDirectory.mkdirs()) return;

    extractBundledResources();

    File[] files = myDirectory.listFiles();
    if (files == null) return;
    for (File file : files) {
      String fileName = file.getName();
      if (!fileName.endsWith(".yml")) continue;
      if (fileName.contains("old")) continue;
      // ADR-076: a per-file clone (e.g. a per-world parser) writes its colocated rename map
      // `.<name>.lang.yml` beside its value file - i.e. INSIDE this scanned directory. Those
      // dotfiles end in `.yml`, so without this guard the scan would mistake them for value
      // configs, build a parser named `.<name>.lang`, and re-rotate a `.<name>.lang.yml.old<N>`
      // backup on every reload. Skip hidden dotfiles and any `.lang.yml` rename map.
      if (fileName.startsWith(".") || fileName.endsWith(".lang.yml")) continue;

      fileName = fileName.replace(".yml", "");

      ConfigParser<E> parser =
          new ConfigParser<>(
              eClass, fileName, version, myDirectory, this.langMap, fileDatabase, this.locale,
              this.directory);
      addParser(parser);
    }
  }

  /**
   * Discovers and extracts all bundled .yml resources from the jar/classpath for this directory.
   * Extraction only triggers if default.yml does not exist on disk.
   */
  private void extractBundledResources() {
    File defaultFile = new File(myDirectory, "default.yml");
    if (defaultFile.exists()) {
      return;
    }

    boolean extractedAny = false;
    try {
      String dirPath = this.directory.replace('\\', '/');
      if (dirPath.startsWith("/")) dirPath = dirPath.substring(1);
      java.util.Enumeration<java.net.URL> urls = getClassLoader().getResources(dirPath);
      while (urls.hasMoreElements()) {
        java.net.URL url = urls.nextElement();
        String protocol = url.getProtocol();
        if ("jar".equals(protocol)) {
          String path = url.getPath();
          int bang = path.indexOf('!');
          if (bang >= 0) {
            String jarPath = path.substring("file:".length(), bang);
            jarPath = java.net.URLDecoder.decode(jarPath, java.nio.charset.StandardCharsets.UTF_8);
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath)) {
              java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
              String prefix = dirPath.endsWith("/") ? dirPath : dirPath + "/";
              while (entries.hasMoreElements()) {
                String entryName = entries.nextElement().getName();
                if (entryName.startsWith(prefix) && entryName.endsWith(".yml") && !entryName.equals(prefix)) {
                  String fileName = entryName.substring(prefix.length());
                  if (!fileName.contains("/")) {
                    File target = new File(myDirectory, fileName);
                    if (!target.exists()) {
                      saveResourceFromJar(entryName, true);
                      extractedAny = true;
                    }
                  }
                }
              }
            }
          }
        } else if ("file".equals(protocol)) {
          File dir = new File(url.toURI());
          File[] list = dir.listFiles((d, f) -> f.endsWith(".yml"));
          if (list != null) {
            for (File f : list) {
              File target = new File(myDirectory, f.getName());
              if (!target.exists()) {
                saveResourceFromJar(dirPath + "/" + f.getName(), true);
                extractedAny = true;
              }
            }
          }
        }
      }
    } catch (Exception e) {
      log(Level.FINE, "Could not enumerate jar resources for " + this.directory, e);
    }

    if (!extractedAny) {
      File d = new File(myDirectory, "default.yml");
      if (!d.exists()) {
        try {
          saveResourceFromJar(this.directory + "/default.yml", true);
        } catch (IllegalArgumentException e) {
          log(Level.WARNING, e.getMessage(), e);
        }
      }
    }
  }

  public Set<String> listParsers() {
    return configParserFactory.map.values().stream()
        .map(eConfigParser -> eConfigParser.name.replace(".yml", ""))
        .collect(Collectors.toSet());
  }

  @SuppressWarnings("unchecked") // configParserFactory only ever holds ConfigParser<E> for this parser's E
  public ConfigParser<E> getParser(String name) {
    if (name == null) name = "DEFAULT.YML";
    String sanitized = ConfigParser.sanitizeName(name);
    name = (sanitized != null ? sanitized : "").toUpperCase();
    if (!name.endsWith(".YML")) name = name + ".YML";
    if (configParserFactory.contains(name)) return configParserFactory.map.get(name);
    else {
      String entryName = name.replace(".YML", "");
      Predicate<String> dynamicCheck =
          this.canCreateDynamicParser != null
              ? this.canCreateDynamicParser
              : defaultCanCreateDynamicParser;
      if (dynamicCheck == null || !dynamicCheck.test(entryName)) {
        ConfigParser<E> parser = (ConfigParser<E>) configParserFactory.getOrDefault("DEFAULT.YML");
        if (parser != null) return parser;
        return new ConfigParser<>(
            myClass, name, version, myDirectory, langMap, fileDatabase, locale, directory);
      }

      ConfigParser<E> parser = (ConfigParser<E>) configParserFactory.getOrDefault(name);
      if (parser != null) configParserFactory.add(name, parser);
      return parser;
    }
  }

  @SuppressWarnings("unchecked") // configParserFactory only ever constructs ConfigParser<E> for this parser's E
  public void addParser(String name) {
    String safe = ConfigParser.sanitizeName(name);
    ConfigParser<E> value = (ConfigParser<E>) configParserFactory.construct(safe);
    if (value != null) configParserFactory.add(safe, value);
  }

  /**
   * Register a new parser seeded from an existing parser {@code fromName}.
   * Falls back to default template if {@code fromName} is missing.
   *
   * @param name     the new parser/file name
   * @param fromName originating parser/file name to clone from
   */
  @SuppressWarnings("unchecked") // configParserFactory only ever constructs ConfigParser<E> for this parser's E
  public void addParser(String name, String fromName) {
    String safe = ConfigParser.sanitizeName(name);
    String from = (fromName == null) ? null : ConfigParser.sanitizeName(fromName);
    ConfigParser<E> value = (ConfigParser<E>) configParserFactory.construct(safe, from);
    if (value != null) configParserFactory.add(safe, value);
  }

  @SuppressWarnings("unchecked") // parser.myClass is verified to equal myClass before the cast
  public void addParser(ConfigParser<?> parser) {
    if (!parser.myClass.equals(myClass)) throw new IllegalStateException("mismatched parser class");
    ConfigParser<E> eConfigParser = (ConfigParser<E>) parser;
    configParserFactory.add(parser.name, eConfigParser);
  }

  public void removeParser(String name) {
    configParserFactory.remove(name);
  }

  public void addAll(String... keys) {
    for (String key : keys) {
      addParser(key);
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
