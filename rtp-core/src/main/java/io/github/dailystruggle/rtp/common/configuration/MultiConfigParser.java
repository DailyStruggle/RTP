package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

public class MultiConfigParser<E extends Enum<E>> extends FactoryValue<E> implements ConfigLoader {
  public final File pluginDirectory;
  public final File myDirectory;
  public final String name;
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
  public final YamlFileDatabase fileDatabase;
  protected final File langMap;
  public Factory<ConfigParser<E>> configParserFactory = new Factory<>();
  AtomicReference<Map<String, RtpYamlConfig>> cachedLookup;
  private ClassLoader classLoader = this.getClass().getClassLoader();

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

    this.fileDatabase = new YamlFileDatabase(this.myDirectory);
    cachedLookup = fileDatabase.cachedLookup;
    Map<String, RtpYamlConfig> connect = this.fileDatabase.connect();
    this.fileDatabase.disconnect(connect);

    this.langMap = dotLangMap(pluginDirectory, this.directory);
    if (!this.myDirectory.exists() && !myDirectory.mkdirs()) return;

    File d = new File(myDirectory.getAbsolutePath() + File.separator + "default.yml");
    if (!d.exists()) {
      try {
        saveResourceFromJar(this.directory + "/default.yml", true);
      } catch (IllegalArgumentException e) {
        RTP.log(Level.WARNING, e.getMessage(), e);
      }
    }

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

    this.fileDatabase = new YamlFileDatabase(this.myDirectory);
    Map<String, RtpYamlConfig> connect = this.fileDatabase.connect();
    this.fileDatabase.disconnect(connect);

    try {
      File[] files = myDirectory.listFiles();
      if (files == null) {
//        System.out.println("[RTP-DEBUG] MultiConfig: listFiles() returned null for " + myDirectory.getAbsolutePath());
      }

//      System.out.println("[RTP-DEBUG] MultiConfig: Loaded " + files.length + " files for " + name);

    } catch (Throwable T) {
      T.printStackTrace();
    }

    this.langMap = dotLangMap(pluginDirectory, this.directory);
    if (!this.myDirectory.exists() && !myDirectory.mkdirs()) return;

    File d = new File(myDirectory.getAbsolutePath() + File.separator + "default.yml");
    if (!d.exists()) {
      try {
        saveResourceFromJar(this.directory + "/default.yml", true);
      } catch (IllegalArgumentException e) {
        RTP.log(Level.WARNING, e.getMessage(), e);
      }
    }

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

  @NotNull
  public Set<String> listParsers() {
    return configParserFactory.map.values().stream()
        .map(eConfigParser -> eConfigParser.name.replace(".yml", ""))
        .collect(Collectors.toSet());
  }

  @NotNull
  public ConfigParser<E> getParser(String name) {
    name = ConfigParser.sanitizeName(name).toUpperCase();
    if (!name.endsWith(".YML")) name = name + ".YML";
    if (configParserFactory.contains(name)) return configParserFactory.map.get(name);
    else {
      String worldName = name.replace(".YML", "");
      if (RTP.serverAccessor.getRTPWorld(worldName) == null) {
        ConfigParser<E> parser = (ConfigParser<E>) configParserFactory.getOrDefault("DEFAULT.YML");
        if (parser != null) return parser;
        return new ConfigParser<>(
            myClass, name, version, myDirectory, langMap, fileDatabase, locale, this.directory);
      }

      ConfigParser<E> parser = (ConfigParser<E>) configParserFactory.getOrDefault(name);
      if (parser != null) configParserFactory.add(name, parser);
      return parser;
    }
  }

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
  public void addParser(String name, String fromName) {
    String safe = ConfigParser.sanitizeName(name);
    String from = (fromName == null) ? null : ConfigParser.sanitizeName(fromName);
    ConfigParser<E> value = (ConfigParser<E>) configParserFactory.construct(safe, from);
    if (value != null) configParserFactory.add(safe, value);
  }

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
