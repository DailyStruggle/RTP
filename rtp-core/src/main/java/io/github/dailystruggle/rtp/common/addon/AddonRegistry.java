package io.github.dailystruggle.rtp.common.addon;

import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.common.RTP;

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
    try {
      ServiceLoader<RTPAddon> loader = ServiceLoader.load(RTPAddon.class, classLoader);
      for (RTPAddon addon : loader) {
        register(addon);
      }
    } catch (ServiceConfigurationError e) {
      RTP.log(Level.WARNING, "[ADDONS] failed to enumerate RTPAddon services", e);
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
      RTP.log(Level.INFO, "[ADDONS] loaded addon: " + addon.name());
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[ADDONS] addon failed to load: " + addon.name(), t);
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
        RTP.log(Level.WARNING, "[ADDONS] addon failed to unload: " + addon.name(), t);
      }
    }
    addons.clear();
    loaded.set(false);
  }

  /**
   * @return an immutable snapshot of the currently-registered addons
   */
  public List<RTPAddon> registered() {
    return List.copyOf(addons);
  }
}
