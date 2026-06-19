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
 * class never touches a platform UI type. Each platform's entry point registers its
 * own renderer programmatically (the Bukkit plugin in {@code onEnable}, the Fabric /
 * NeoForge initializers at server-start); there is no {@code MenuRenderer} SPI file,
 * because every platform's chest renderer shares the {@code "chest"} style key and a
 * cross-platform SPI loop would let the wrong-platform renderer clobber the live one.
 */
public final class RTPGuiCommonAddon implements RTPAddon {

  /** Style keys this addon registered via the SPI hook, detached again on unload. */
  private final java.util.List<String> serviceStyles = new java.util.ArrayList<>();

  @Override
  public void onLoad() {
    // Register guimenu.yml with RTP's config system (first-boot create + /rtp reload).
    RTP.configs.putParser(registerParser());
    Configs.onReload(() -> RTP.configs.putParser(registerParser()));

    // Optional third-party renderer SPI hook. The bundled platform renderers are
    // NOT discovered here - each platform entry point registers its own renderer
    // programmatically (they all share the "chest" key, so a cross-platform SPI
    // loop would let one clobber another). This call only picks up any extra
    // MenuRenderer a third-party addon publishes via META-INF/services, and even
    // then skips one that is not isAvailable() on this runtime so it can never
    // shadow the live renderer.
    registerServiceRenderers();

    // Bind bare /rtp to open the menu. Returning false (no renderer, or offline)
    // defers to RTP's classic teleport so the command never silently no-ops.
    try {
      RTP.log(Level.INFO, "[RTP-GUI] bare /rtp root action bound");
      RTPAPI.hooks().rootAction().bind((playerId, feedback) -> {
        String style = GuiMenuConfig.INSTANCE.menuStyle();
        MenuRenderer renderer = GuiRenderers.resolve(style);
        RTP.log(Level.INFO, "[RTP-GUI] bare /rtp for " + playerId + ": menuStyle=" + style
            + ", registeredStyles=" + GuiRenderers.registeredStyles()
            + ", resolvedRenderer=" + (renderer == null ? "null" : renderer.key())
            + ", available=" + (renderer != null && renderer.isAvailable()));
        if (renderer == null || !renderer.isAvailable()) {
          RTP.log(Level.INFO, "[RTP-GUI] bare /rtp for " + playerId
              + ": no available renderer (resolved=" + (renderer == null ? "null" : renderer.key())
              + "); deferring to classic teleport");
          return false; // no platform renderer installed; classic teleport
        }
        if (RTP.serverAccessor == null || RTP.serverAccessor.getPlayer(playerId) == null) {
          RTP.log(Level.INFO, "[RTP-GUI] bare /rtp for " + playerId
              + ": player not resolvable (serverAccessor="
              + (RTP.serverAccessor == null ? "null" : "present")
              + "); deferring to classic teleport");
          return false; // offline / not resolvable; classic teleport
        }
        RTP.log(Level.INFO, "[RTP-GUI] bare /rtp for " + playerId
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
    try {
      java.util.ServiceLoader<MenuRenderer> loader =
          java.util.ServiceLoader.load(MenuRenderer.class, getClass().getClassLoader());
      for (java.util.Iterator<MenuRenderer> it = loader.iterator(); it.hasNext(); ) {
        try {
          MenuRenderer renderer = it.next();

          // Platform renderers share a style key (e.g. every platform's chest
          // renderer registers under "chest" so `menuStyle: chest` works
          // everywhere). Because GuiRenderers stores one renderer per key, a
          // wrong-platform renderer discovered here would CLOBBER the correct
          // one already installed by the platform entry point (e.g. on Bukkit
          // the Fabric/NeoForge chest renderers would overwrite the working
          // Bukkit chest renderer), and since they report isAvailable()==false
          // off their platform, a bare /rtp would then resolve to null and fall
          // back to the classic teleport. Skip any renderer that is not
          // available on this runtime so it can never shadow the live one.
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
          RTP.log(Level.FINE, "[RTP-GUI] skipped a MenuRenderer service entry", badEntry);
        }
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP-GUI] MenuRenderer service discovery failed", t);
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
