package io.github.dailystruggle.rtp.guiaddon.bukkit;

import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.guiaddon.common.MenuEntry;
import io.github.dailystruggle.rtp.guiaddon.common.MenuIcons;
import io.github.dailystruggle.rtp.guiaddon.common.MenuLayout;
import io.github.dailystruggle.rtp.guiaddon.common.MenuModel;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bukkit chest-inventory rendering of a platform-neutral {@link MenuModel}.
 *
 * <p>This is the {@link InventoryHolder} for our menu. Using a custom holder is the
 * safe way to recognise "our" inventory in the click listener - far better than
 * matching on the (player-spoofable, locale-dependent) title. The holder also carries
 * the slot-to-{@link RtpTarget} map so the listener resolves a click without
 * re-deriving anything.
 *
 * <p>Pure presentation: it maps the model's neutral material <em>names</em> to Bukkit
 * {@link Material} and arranges items. No teleport, status, or config logic lives here.
 */
public final class DestinationPickerGui implements InventoryHolder {

  private static final int COLUMNS = MenuLayout.COLUMNS;

  private final Inventory inventory;
  private final int dashboardSlot;
  private final Map<Integer, RtpTarget> slotTargets = new HashMap<>();

  private DestinationPickerGui(MenuModel model, int rows, int dashboardSlot) {
    this.inventory =
        Bukkit.createInventory(this, rows * COLUMNS,
            ChatColor.translateAlternateColorCodes('&', model.title()));
    this.dashboardSlot = dashboardSlot;
  }

  /**
   * Builds a chest inventory laid out from {@code model}.
   *
   * <p>The grid auto-sizes to the number of destinations and centres each row
   * horizontally (and the whole block vertically) so a handful of buttons no
   * longer cling to the top-left corner of an oversized chest. The optional
   * server-health tile sits centred on the bottom row.
   *
   * @param model the pre-resolved, platform-neutral menu contents
   * @return a ready-to-open holder
   */
  public static DestinationPickerGui from(MenuModel model) {
    MenuLayout layout = MenuLayout.compute(model);
    DestinationPickerGui gui = new DestinationPickerGui(model, layout.rows(), layout.dashboardSlot());

    for (Map.Entry<Integer, MenuEntry> placed : layout.slotEntries().entrySet()) {
      int slot = placed.getKey();
      MenuEntry entry = placed.getValue();
      gui.inventory.setItem(slot, icon(entry));
      gui.slotTargets.put(slot, entry.target());
    }

    if (layout.hasDashboard()) {
      gui.inventory.setItem(layout.dashboardSlot(), dashboardTile(model));
    }
    gui.applyFiller(model);
    return gui;
  }

  /**
   * Resolves the {@link RtpTarget} bound to a clicked slot, or {@code null} if the slot
   * is decoration (dashboard tile or filler).
   *
   * @param slot the raw slot index
   * @return the bound target, or {@code null}
   */
  public RtpTarget targetAt(int slot) {
    return slotTargets.get(slot);
  }

  @Override
  public Inventory getInventory() {
    return inventory;
  }

  // ----- presentation helpers -----

  private void applyFiller(MenuModel model) {
    Material filler = material(model.fillerName(), Material.AIR);
    if (filler == Material.AIR) {
      return;
    }
    ItemStack pane = new ItemStack(filler);
    ItemMeta meta = pane.getItemMeta();
    if (meta != null) {
      meta.setDisplayName(" ");
      pane.setItemMeta(meta);
    }
    for (int i = 0; i < inventory.getSize(); i++) {
      if (inventory.getItem(i) == null) {
        inventory.setItem(i, pane);
      }
    }
  }

  private static ItemStack icon(MenuEntry entry) {
    ItemStack item = new ItemStack(material(entry.iconName(), Material.COMPASS));
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&b" + entry.displayName()));
      meta.setLore(translate(MenuIcons.entryLore(entry)));
      item.setItemMeta(meta);
    }
    return item;
  }

  private static ItemStack dashboardTile(MenuModel model) {
    ItemStack item = new ItemStack(material(model.dashboardIconName(), Material.PAPER));
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', MenuIcons.dashboardTitle()));
      meta.setLore(translate(MenuIcons.dashboardLore(model)));
      item.setItemMeta(meta);
    }
    return item;
  }

  private static List<String> translate(List<String> lines) {
    List<String> out = new ArrayList<>(lines.size());
    for (String line : lines) {
      out.add(ChatColor.translateAlternateColorCodes('&', line));
    }
    return out;
  }

  private static Material material(String name, Material fallback) {
    if (name == null || name.trim().isEmpty()) {
      return fallback;
    }
    Material m = Material.matchMaterial(name.trim().toUpperCase());
    return (m == null) ? fallback : m;
  }
}
