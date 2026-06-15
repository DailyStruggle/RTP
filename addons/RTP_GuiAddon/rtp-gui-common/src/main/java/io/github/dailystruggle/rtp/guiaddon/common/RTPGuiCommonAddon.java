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
 * class never touches a platform UI type.
 */
public final class RTPGuiCommonAddon implements RTPAddon {

  /** Style keys this addon registered via SPI, detached again on unload. */
  private final java.util.List<String> serviceStyles = new java.util.ArrayList<>();

  @Override
  public void onLoad() {
    // Register guimenu.yml with RTP's config system (first-boot create + /rtp reload).
    RTP.configs.putParser(registerParser());
    Configs.onReload(() -> RTP.configs.putParser(registerParser()));

    // SPI-style renderer discovery: any MenuRenderer published via
    // META-INF/services in this (single) addon jar self-registers here, so the
    // platform GUI renderer comes up on every platform - including Fabric and
    // NeoForge, which have no Bukkit plugin loader to register one
    // programmatically. Each renderer guards itself with isAvailable(), so only
    // the one whose platform is actually present serves a bare /rtp.
    registerServiceRenderers();

    // Bind bare /rtp to open the menu. Returning false (no renderer, or offline)
    // defers to RTP's classic teleport so the command never silently no-ops.
    try {
      RTPAPI.hooks().rootAction().bind((playerId, feedback) -> {
        MenuRenderer renderer = GuiRenderers.resolve(GuiMenuConfig.INSTANCE.menuStyle());
        if (renderer == null || !renderer.isAvailable()) {
          return false; // no platform renderer installed; classic teleport
        }
        if (RTP.serverAccessor == null || RTP.serverAccessor.getPlayer(playerId) == null) {
          return false; // offline / not resolvable; classic teleport
        }
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
   * Discovers and registers every {@link MenuRenderer} published through
   * {@link java.util.ServiceLoader} on this addon's classloader. Each renderer's
   * {@link MenuRenderer#key() style key} decides which {@code menuStyle} it answers
   * to. A renderer that fails to instantiate is logged and skipped so one bad
   * service entry never prevents the others (or the bare-/rtp binding) from loading.
   */
  private void registerServiceRenderers() {
    try {
      java.util.ServiceLoader<MenuRenderer> loader =
          java.util.ServiceLoader.load(MenuRenderer.class, getClass().getClassLoader());
      for (java.util.Iterator<MenuRenderer> it = loader.iterator(); it.hasNext(); ) {
        try {
          MenuRenderer renderer = it.next();
          GuiRenderers.register(renderer);
          if (renderer.key() != null) {
            serviceStyles.add(renderer.key());
          }
        } catch (Throwable badEntry) {
          RTP.log(Level.WARNING, "[RTP-GUI] skipped a MenuRenderer service entry", badEntry);
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
