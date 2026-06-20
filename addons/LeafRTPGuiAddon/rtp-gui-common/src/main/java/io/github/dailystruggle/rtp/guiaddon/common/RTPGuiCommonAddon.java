package io.github.dailystruggle.rtp.guiaddon.common;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;

import java.util.logging.Level;

/**
 * Platform-neutral entry point for the RTP GUI addon.
 *
 * <p>Discovered by {@code rtp-core} through {@link java.util.ServiceLoader} (see
 * {@code META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon}), so it
 * loads on every RTP platform with no Bukkit plugin loader. It owns everything that
 * is the <em>same</em> on all platforms:
 *
 * <ul>
 *   <li><b>Config</b>: registers a {@link ConfigParser} for {@link GuiMenuKeys}
 *       ({@code guimenu.yml}), so the file is created on first boot, lives in the RTP
 *       data folder, and reloads on {@code /rtp reload} - no manual pasting.</li>
 *   <li><b>Bare {@code /rtp}</b>: binds the {@link RTPAPI#hooks()} root action to open
 *       the menu via whatever {@link MenuRenderer} the platform module installed
 *       (ADR-056). With no renderer installed, it defers to the classic teleport.</li>
 * </ul>
 *
 * <p>The actual menu drawing is delegated to a platform {@link MenuRenderer}; this
 * class never touches a platform UI type. Each platform publishes its renderer via a
 * {@code MenuRenderer} {@code META-INF/services} descriptor, discovered here on the
 * {@link java.util.ServiceLoader} load path (the {@code plugins/RTP/addons/} folder or
 * the bundled-in-jar demo). The shared {@code "chest"} style key is safe because every
 * renderer's {@link MenuRenderer#isAvailable()} is gated on its platform loader, so the
 * wrong-platform renderer is skipped rather than clobbering the live one. Standalone
 * plugin/mod entry points also register the same renderer programmatically - harmless,
 * since {@code GuiRenderers} keys by style. Discovery reads the service files directly
 * (not {@code ServiceLoader.load}) so a wrong-platform provider whose classes are absent
 * cannot abort the iteration before the live renderer is reached.
 */
public final class RTPGuiCommonAddon implements RTPAddon {

  /** Style keys this addon registered via the SPI hook, detached again on unload. */
  private final java.util.List<String> serviceStyles = new java.util.ArrayList<>();

  @Override
  public void onLoad() {
    // Register guimenu.yml with RTP's config system (first-boot create + /rtp reload).
    RTP.configs.putParser(registerParser());
    Configs.onReload(() -> RTP.configs.putParser(registerParser()));

    // Discover platform renderers via META-INF/services. This is the load path that
    // matters when the addon is dropped into plugins/RTP/addons/ or extracted from the
    // bundled RTP jar: the platform JavaPlugin/mod entry point does NOT run there, so
    // the renderer can only be registered through the SPI. Each candidate is skipped
    // unless it is isAvailable() on this runtime (a wrong-platform renderer's classes
    // fail to load and are caught), so the shared "chest" key never lets one platform's
    // renderer clobber another's. When the addon IS installed as a standalone plugin/mod,
    // its entry point also registers the same renderer programmatically - harmless, since
    // GuiRenderers keys by style.
    registerServiceRenderers();

    // Bind bare /rtp to open the menu. Returning false (no renderer, or offline)
    // defers to RTP's classic teleport so the command never silently no-ops.
    try {
      RTP.log(Level.FINE, "[RTP-GUI] bare /rtp root action bound");
      RTPAPI.hooks().rootAction().bind((playerId, feedback) -> {
        String style = GuiMenuConfig.INSTANCE.menuStyle();
        MenuRenderer renderer = GuiRenderers.resolve(style);
        RTP.log(Level.FINE, "[RTP-GUI] bare /rtp for " + playerId + ": menuStyle=" + style
            + ", registeredStyles=" + GuiRenderers.registeredStyles()
            + ", resolvedRenderer=" + (renderer == null ? "null" : renderer.key())
            + ", available=" + (renderer != null && renderer.isAvailable()));
        if (renderer == null || !renderer.isAvailable()) {
          RTP.log(Level.FINE, "[RTP-GUI] bare /rtp for " + playerId
              + ": no available renderer (resolved=" + (renderer == null ? "null" : renderer.key())
              + "); deferring to classic teleport");
          return false; // no platform renderer installed; classic teleport
        }
        if (RTP.serverAccessor == null || RTP.serverAccessor.getPlayer(playerId) == null) {
          RTP.log(Level.FINE, "[RTP-GUI] bare /rtp for " + playerId
              + ": player not resolvable (serverAccessor="
              + (RTP.serverAccessor == null ? "null" : "present")
              + "); deferring to classic teleport");
          return false; // offline / not resolvable; classic teleport
        }
        RTP.log(Level.FINE, "[RTP-GUI] bare /rtp for " + playerId
            + ": opening menu via renderer " + renderer.key());
        renderer.open(playerId, MenuModel.build(playerId, GuiMenuConfig.INSTANCE));
        return true; // handled: suppress the classic teleport
      });
    } catch (IllegalStateException coreNotLoaded) {
      RTP.log(Level.WARNING,
          "[RTP-GUI] core not loaded; bare /rtp will not open the menu", coreNotLoaded);
    }
  }

  @Override
  public void onUnload() {
    // Detach the SPI-discovered renderers so they do not outlive this addon
    // (e.g. across a reload); the Bukkit plugin detaches its own chest style.
    for (String style : serviceStyles) {
      GuiRenderers.unregister(style);
    }
    serviceStyles.clear();

    // Release the bare-/rtp binding so it does not outlive this addon.
    try {
      RTPAPI.hooks().rootAction().clear();
    } catch (IllegalStateException ignored) {
      // core already gone; nothing to clear
    }
  }

  @Override
  public String name() {
    return "RTP-GUI";
  }

  /**
   * Extensibility hook: discovers and registers any third-party {@link MenuRenderer}
   * published through {@link java.util.ServiceLoader} on this addon's classloader.
   * The bundled per-platform renderers are registered programmatically by their
   * entry points instead (they share the {@code "chest"} key, so SPI discovery would
   * let them overwrite each other). A renderer that is not {@link
   * MenuRenderer#isAvailable() available} on this runtime, or that fails to
   * instantiate, is skipped so it can never shadow the live renderer and one bad
   * service entry never prevents the bare-/rtp binding from loading.
   */
  private void registerServiceRenderers() {
    // NOTE: we deliberately do NOT use java.util.ServiceLoader here. Its lazy
    // iterator resolves each provider's constructor inside hasNext(), so a single
    // wrong-platform provider (e.g. BukkitMenuRenderer, which references
    // org.bukkit.event.Listener) throws NoClassDefFoundError from the loop
    // CONDITION on a Fabric/NeoForge runtime where Bukkit is absent. That escapes
    // the per-entry try/catch and aborts the whole iteration before the live
    // renderer is reached. Instead we read the service files ourselves and load
    // each class in its own try/catch, so one unloadable entry can never prevent
    // the platform's real renderer from registering.
    ClassLoader cl = getClass().getClassLoader();
    String resource = "META-INF/services/" + MenuRenderer.class.getName();
    java.util.LinkedHashSet<String> classNames = new java.util.LinkedHashSet<>();
    try {
      java.util.Enumeration<java.net.URL> urls = cl.getResources(resource);
      while (urls.hasMoreElements()) {
        java.net.URL url = urls.nextElement();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(url.openStream(), java.nio.charset.StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            int hash = line.indexOf('#');
            if (hash >= 0) {
              line = line.substring(0, hash);
            }
            line = line.trim();
            if (!line.isEmpty()) {
              classNames.add(line);
            }
          }
        } catch (Throwable badFile) {
          RTP.log(Level.FINE, "[RTP-GUI] could not read a MenuRenderer service file", badFile);
        }
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP-GUI] MenuRenderer service discovery failed", t);
      return;
    }

    for (String className : classNames) {
      try {
        // Loading and instantiating a wrong-platform renderer throws
        // NoClassDefFoundError (its platform UI types are absent); the per-entry
        // try/catch swallows that so the live renderer still registers.
        Class<?> clazz = Class.forName(className, false, cl);
        MenuRenderer renderer = clazz.asSubclass(MenuRenderer.class)
            .getDeclaredConstructor().newInstance();

        // Platform renderers share a style key (e.g. every platform's chest
        // renderer registers under "chest" so `menuStyle: chest` works
        // everywhere). Because GuiRenderers stores one renderer per key, a
        // wrong-platform renderer discovered here would CLOBBER the correct
        // one already installed by the platform entry point, and since they
        // report isAvailable()==false off their platform, a bare /rtp would
        // then resolve to null and fall back to the classic teleport. Skip any
        // renderer that is not available on this runtime so it can never
        // shadow the live one.
        if (!renderer.isAvailable()) {
          RTP.log(Level.FINE, "[RTP-GUI] skipping unavailable MenuRenderer for style '"
              + renderer.key() + "' (" + renderer.getClass().getName()
              + "); not the current platform");
          continue;
        }

        GuiRenderers.register(renderer);
        if (renderer.key() != null) {
          serviceStyles.add(renderer.key());
        }
      } catch (Throwable badEntry) {
        RTP.log(Level.FINE, "[RTP-GUI] skipped a MenuRenderer service entry '"
            + className + "'", badEntry);
      }
    }
  }

  private ConfigParser<GuiMenuKeys> registerParser() {
    return new ConfigParser<>(
        GuiMenuKeys.class,
        "guimenu",
        "1.0",
        RTP.serverAccessor.getPluginDirectory(),
        null,
        RTP.configs.fileDatabase,
        this.getClass().getClassLoader());
  }
}
