package io.github.dailystruggle.rtp.common.addon;

import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.common.RTP;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Platform-agnostic registry and lifecycle driver for {@link RTPAddon}s.
 *
 * <p>This replaces the Bukkit-only addon loading path (a {@code JavaPlugin} subclass
 * discovered by the server's plugin manager) with a pure-JDK
 * {@link ServiceLoader}-based mechanism that works identically on every platform.
 * Each addon jar declares its implementation in
 * {@code META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon}.
 *
 * <p>Two discovery paths are supported and may be combined:
 * <ul>
 *   <li>{@link #discover()} / {@link #discover(ClassLoader)} - {@code ServiceLoader}
 *       enumeration over a classloader (the core classloader by default).</li>
 *   <li>{@link #register(RTPAddon)} - programmatic registration, used by a platform
 *       adapter that has already loaded an addon jar (e.g. a Bukkit back-compat shim
 *       or a Fabric mod entry) and wants to hand the instance to the registry.</li>
 * </ul>
 *
 * <p>{@link #loadAll()} invokes {@link RTPAddon#onLoad()} on every registered addon
 * exactly once; {@link #unloadAll()} invokes {@link RTPAddon#onUnload()} on shutdown.
 * Both methods isolate per-addon failures so one misbehaving addon cannot abort core
 * startup or shutdown.
 */
public final class AddonRegistry {

  /** Constructs an empty registry. */
  public AddonRegistry() {
  }

  private final List<RTPAddon> addons = new CopyOnWriteArrayList<>();
  private final AtomicBoolean loaded = new AtomicBoolean(false);

  /**
   * Registers an already-instantiated addon. Ignores {@code null} and duplicate
   * instances. If the registry has already completed {@link #loadAll()}, the
   * newly-registered addon is loaded immediately so late registrations (e.g. from a
   * platform adapter that initialises after core) are not silently dropped.
   *
   * @param addon the addon to register
   */
  public void register(RTPAddon addon) {
    if (addon == null || addons.contains(addon)) return;
    addons.add(addon);
    if (loaded.get()) {
      load(addon);
    }
  }

  /**
   * Discovers addons via {@link ServiceLoader} on the classloader that loaded this
   * class, registering each discovered instance.
   */
  public void discover() {
    discover(AddonRegistry.class.getClassLoader());
  }

  /**
   * Discovers addons via {@link ServiceLoader} on the supplied classloader,
   * registering each discovered instance. Platform adapters that load addon jars
   * into a dedicated classloader pass it here.
   *
   * @param classLoader the classloader to enumerate services on
   */
  public void discover(ClassLoader classLoader) {
    if (classLoader == null) classLoader = AddonRegistry.class.getClassLoader();
    ServiceLoader<RTPAddon> loader = ServiceLoader.load(RTPAddon.class, classLoader);
    java.util.Iterator<RTPAddon> it = loader.iterator();
    while (true) {
      RTPAddon addon;
      try {
        if (!it.hasNext()) break;
        addon = it.next();
      } catch (ServiceConfigurationError | LinkageError e) {
        // An addon whose implementation (or a type it references, e.g. an
        // effects-api class absent from the rtp-lite assembly) cannot be linked
        // must not abort discovery of the remaining addons or plugin startup.
        RTP.log(Level.WARNING,
            "[RTP] skipping an addon that could not be loaded (missing dependency?)", e);
        break;
      }
      register(addon);
    }
  }

  /**
   * Discovers addons from a standalone folder of jar files (e.g. {@code plugins/RTP/addons/}).
   *
   * <p>Each {@code .jar} directly inside {@code directory} is added to a child
   * {@link URLClassLoader} whose parent is the classloader that loaded {@code rtp-core}, so the
   * addon's {@link RTPAddon} implementation and the {@code rtp-api}/{@code rtp-core} types it
   * depends on resolve to the same classes the running plugin uses. {@link ServiceLoader}
   * enumeration then registers every discovered implementation.
   *
   * <p>This lets server operators drop an addon jar into a dedicated RTP-owned folder instead of
   * {@code plugins/}, where the server's plugin loader would reject it for lacking a
   * {@code plugin.yml}. A missing or empty folder is a no-op.
   *
   * @param directory the folder to scan for addon jars
   */
  public void discoverFromDirectory(File directory) {
    if (directory == null) return;
    if (!directory.isDirectory()) return;

    File[] jars = directory.listFiles(
        (dir, fileName) -> fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
    if (jars == null || jars.length == 0) return;

    List<URL> urls = new ArrayList<>(jars.length);
    for (File jar : jars) {
      try {
        urls.add(jar.toURI().toURL());
      } catch (MalformedURLException e) {
        RTP.log(Level.WARNING, "[RTP] skipping unreadable addon jar: " + jar.getName(), e);
      }
    }
    if (urls.isEmpty()) return;

    ClassLoader parent = AddonRegistry.class.getClassLoader();
    URLClassLoader addonClassLoader =
        new URLClassLoader(urls.toArray(new URL[0]), parent);
    RTP.log(Level.INFO,
        "[RTP] scanning " + jars.length + " jar(s) in " + directory.getAbsolutePath());
    discover(addonClassLoader);
  }

  /**
   * Extracts addon jars bundled inside the running RTP jar into {@code directory}.
   *
   * <p>RTP ships demo/companion addons (e.g. the GUI destination picker and the
   * claim-plugin integrations) as ordinary jars embedded on its own classpath under
   * {@code bundled-addons/}. On first run - detected by the caller as the
   * {@code addons/} folder not yet existing - these are unpacked here so the operator
   * gets them out of the box without a second download, while still being able to opt
   * out simply by deleting the extracted jar (the folder then exists, so it is never
   * re-extracted) or by deleting the addon.
   *
   * <p>The set of bundled jars is listed, one resource name per line, in the
   * classpath resource {@code bundled-addons/index}. A missing index, a missing jar
   * resource, or an unwritable target is logged and skipped; extraction never aborts
   * startup. Re-extraction is avoided per-file: a jar already present in
   * {@code directory} is left untouched.
   *
   * @param directory the {@code addons/} folder to populate (created by the caller)
   */
  public void extractBundledAddons(File directory) {
    extractBundledAddons(directory, AddonRegistry.class.getClassLoader());
  }

  /**
   * {@link #extractBundledAddons(File)} variant that reads the bundle resources from
   * an explicit {@code loader}. Exposed for testing; production callers use the
   * single-argument form bound to {@code rtp-core}'s own classloader.
   *
   * @param directory the {@code addons/} folder to populate
   * @param loader    the classloader to read {@code bundled-addons/*} resources from
   */
  public void extractBundledAddons(File directory, ClassLoader loader) {
    if (directory == null) return;
    if (loader == null) loader = AddonRegistry.class.getClassLoader();
    java.io.InputStream indexStream = loader.getResourceAsStream("bundled-addons/index");
    if (indexStream == null) return; // no bundled addons in this build
    List<String> names = new ArrayList<>();
    try (java.io.BufferedReader reader =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(indexStream, java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String name = line.trim();
        if (!name.isEmpty() && !name.startsWith("#")) names.add(name);
      }
    } catch (java.io.IOException e) {
      RTP.log(Level.WARNING, "[RTP] failed to read bundled-addons index", e);
      return;
    }
    if (!directory.isDirectory() && !directory.mkdirs()) {
      RTP.log(Level.WARNING,
          "[RTP] could not create addons folder for bundled addons: " + directory.getAbsolutePath());
      return;
    }
    for (String name : names) {
      File target = new File(directory, name);
      if (target.exists()) continue; // never overwrite an operator's copy
      try (java.io.InputStream in = loader.getResourceAsStream("bundled-addons/" + name)) {
        if (in == null) {
          RTP.log(Level.WARNING, "[RTP] bundled addon resource missing: " + name);
          continue;
        }
        java.nio.file.Files.copy(in, target.toPath());
        RTP.log(Level.INFO, "[RTP] extracted bundled resource: " + name);
      } catch (java.io.IOException e) {
        RTP.log(Level.WARNING, "[RTP] failed to extract bundled resource: " + name, e);
      }
    }
  }

  /**
   * Invokes {@link RTPAddon#onLoad()} on every registered addon exactly once.
   * Subsequent calls are no-ops; addons registered after this call are loaded
   * eagerly by {@link #register(RTPAddon)}.
   */
  public void loadAll() {
    if (!loaded.compareAndSet(false, true)) return;
    for (RTPAddon addon : addons) {
      load(addon);
    }
  }

  private void load(RTPAddon addon) {
    try {
      addon.onLoad();
      RTP.log(Level.INFO, "[RTP] loaded addon: " + addon.name());
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP] addon failed to load: " + addon.name(), t);
    }
  }

  /**
   * Invokes {@link RTPAddon#onUnload()} on every registered addon and clears the
   * registry. Safe to call even if {@link #loadAll()} was never invoked.
   */
  public void unloadAll() {
    for (RTPAddon addon : addons) {
      try {
        addon.onUnload();
      } catch (Throwable t) {
        RTP.log(Level.WARNING, "[RTP] addon failed to unload: " + addon.name(), t);
      }
    }
    addons.clear();
    loaded.set(false);
  }

  /**
   * Returns an immutable snapshot of the currently-registered addons.
   *
   * @return an immutable snapshot of the currently-registered addons
   */
  public List<RTPAddon> registered() {
    return List.copyOf(addons);
  }
}
