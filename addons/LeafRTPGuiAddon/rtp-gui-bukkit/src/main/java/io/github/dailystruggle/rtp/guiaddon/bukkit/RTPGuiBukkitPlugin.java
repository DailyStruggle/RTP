package io.github.dailystruggle.rtp.guiaddon.bukkit;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.guiaddon.common.GuiRenderers;
import io.github.dailystruggle.rtp.guiaddon.common.RTPGuiCommonAddon;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bukkit-family entry point for the RTP GUI addon.
 *
 * <p>Thin by design. The platform-neutral {@code rtp-gui-common} module (loaded via
 * {@code ServiceLoader} as an {@code RTPAddon}) owns config registration and the bare
 * {@code /rtp} root-action binding. This plugin only does the Bukkit-specific wiring:
 *
 * <ul>
 *   <li>installs the {@link BukkitMenuRenderer} into {@link GuiRenderers} so the
 *       common root action can draw a chest menu, and</li>
 *   <li>registers the {@link DestinationPickerListener} for clicks.</li>
 * </ul>
 *
 * <p>The explicit menu opener is {@code /rtp gui}, registered by the platform-neutral
 * {@code rtp-gui-common} addon on RTP's own command tree, so it works however the addon
 * is loaded and on every platform. This plugin no longer declares a Bukkit
 * {@code /rtpgui} command (that command was invisible when the addon was loaded via the
 * {@code plugins/RTP/addons/} folder rather than as a standalone {@code JavaPlugin}).
 */
public final class RTPGuiBukkitPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    // Register the chest renderer first so the common addon's bare-/rtp binding has
    // a style to resolve the moment it loads. Other modules may register additional
    // styles (e.g. "book"); the menuStyle config value picks which one opens.
    GuiRenderers.register(new BukkitMenuRenderer(this));
    // Register the click/drag listener through the shared, once-per-JVM guard so the
    // SPI load path (which also instantiates the renderer and registers the listener)
    // cannot double-register it when the addon is installed as a standalone plugin.
    BukkitMenuRenderer.registerClickListener(this);

    // The platform-neutral addon (config + bare-/rtp binding) is shaded into this jar,
    // so it lives on the Bukkit plugin classloader rather than core's. Register it
    // programmatically; the registry loads it immediately (core is already up because
    // plugin.yml declares depend: [ RTP ]).
    RTP.addons.register(new RTPGuiCommonAddon());
  }

  @Override
  public void onDisable() {
    // Force-close any open destination pickers before our click/drag listener is
    // unregistered. A menu left open after disable would no longer have its clicks
    // cancelled, reopening the item-dupe vector this addon guards against.
    for (Player player : Bukkit.getOnlinePlayers()) {
      InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
      if (holder instanceof DestinationPickerGui) {
        player.closeInventory();
      }
    }

    // Detach our style so the renderer does not outlive this plugin (e.g. across a
    // /reload); a bare /rtp then reverts to RTP's classic teleport if no style remains.
    GuiRenderers.unregister(BukkitMenuRenderer.STYLE);
  }

}
