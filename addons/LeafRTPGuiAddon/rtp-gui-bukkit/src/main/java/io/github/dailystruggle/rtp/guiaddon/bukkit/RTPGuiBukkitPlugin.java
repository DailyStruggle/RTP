package io.github.dailystruggle.rtp.guiaddon.bukkit;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.guiaddon.common.GuiMenuConfig;
import io.github.dailystruggle.rtp.guiaddon.common.GuiRenderers;
import io.github.dailystruggle.rtp.guiaddon.common.MenuModel;
import io.github.dailystruggle.rtp.guiaddon.common.RTPGuiCommonAddon;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
 * <p>It also exposes {@code /rtpgui} as an explicit opener (and {@code /rtpgui reload}).
 */
public final class RTPGuiBukkitPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    // Register the chest renderer first so the common addon's bare-/rtp binding has
    // a style to resolve the moment it loads. Other modules may register additional
    // styles (e.g. "book"); the menuStyle config value picks which one opens.
    GuiRenderers.register(new BukkitMenuRenderer(this));
    getServer().getPluginManager().registerEvents(new DestinationPickerListener(), this);

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

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!command.getName().equalsIgnoreCase("rtpgui")) {
      return false;
    }
    if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
      // guimenu.yml is owned by RTP's config system; full reload is via /rtp reload.
      sender.sendMessage("[RTP-GUI] guimenu.yml is reloaded by /rtp reload.");
      return true;
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage("Only players can open the RTP destination picker.");
      return true;
    }
    new BukkitMenuRenderer(this)
        .open(player.getUniqueId(), MenuModel.build(player.getUniqueId(), GuiMenuConfig.INSTANCE));
    return true;
  }
}
