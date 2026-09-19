package io.github.dailystruggle.rtp.guiaddon.bukkit;

import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.common.tools.MiniMessageColorExpander;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

  private static final Pattern HEX_PATTERN_1 = Pattern.compile("&#([0-9a-fA-F]{6})");
  private static final Pattern HEX_PATTERN_2 = Pattern.compile("#([0-9a-fA-F]{6})");
  private static final Pattern COLOR_PREFIX_PATTERN =
      Pattern.compile("^(?:[&\u00a7][0-9a-fk-orA-FK-OR]|(?:&?#[0-9a-fA-F]{6})|<[^>]+>)");

  private DestinationPickerGui(MenuModel model, int rows, int dashboardSlot) {
    this.inventory =
        Bukkit.createInventory(this, rows * COLUMNS, colorize(model.title()));
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
      String name = entry.displayName();
      if (name != null && !COLOR_PREFIX_PATTERN.matcher(name).find()) {
        name = "&b" + name;
      }
      meta.setDisplayName(colorize(name));
      meta.setLore(translate(MenuIcons.entryLore(entry)));
      item.setItemMeta(meta);
    }
    return item;
  }

  private static ItemStack dashboardTile(MenuModel model) {
    ItemStack item = new ItemStack(material(model.dashboardIconName(), Material.PAPER));
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.setDisplayName(colorize(MenuIcons.dashboardTitle()));
      meta.setLore(translate(MenuIcons.dashboardLore(model)));
      item.setItemMeta(meta);
    }
    return item;
  }

  private static List<String> translate(List<String> lines) {
    List<String> out = new ArrayList<>(lines.size());
    for (String line : lines) {
      out.add(colorize(line));
    }
    return out;
  }

  /**
   * Colorizes text for Bukkit/Paper menus: expands MiniMessage markup, translates
   * hex color codes (&#rrggbb and #rrggbb into §x§r§r§g§g§b§b), and converts legacy
   * '&' color/formatting codes to section symbols.
   */
  public static String colorize(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    try {
      text = MiniMessageColorExpander.expand(text);
    } catch (Throwable ignored) {
      // Best-effort expander fallback
    }
    Matcher m1 = HEX_PATTERN_1.matcher(text);
    if (m1.find()) {
      StringBuilder sb = new StringBuilder(text.length() + 32);
      int last = 0;
      m1.reset();
      while (m1.find()) {
        sb.append(text, last, m1.start());
        String hex = m1.group(1);
        sb.append('\u00a7').append('x');
        for (int i = 0; i < 6; i++) {
          sb.append('\u00a7').append(Character.toLowerCase(hex.charAt(i)));
        }
        last = m1.end();
      }
      sb.append(text, last, text.length());
      text = sb.toString();
    }
    Matcher m2 = HEX_PATTERN_2.matcher(text);
    if (m2.find()) {
      StringBuilder sb = new StringBuilder(text.length() + 32);
      int last = 0;
      m2.reset();
      while (m2.find()) {
        sb.append(text, last, m2.start());
        String hex = m2.group(1);
        sb.append('\u00a7').append('x');
        for (int i = 0; i < 6; i++) {
          sb.append('\u00a7').append(Character.toLowerCase(hex.charAt(i)));
        }
        last = m2.end();
      }
      sb.append(text, last, text.length());
      text = sb.toString();
    }
    return ChatColor.translateAlternateColorCodes('&', text);
  }

  private static Material material(String name, Material fallback) {
    if (name == null || name.isBlank()) {
      return fallback;
    }
    Material m = Material.matchMaterial(name.trim().toUpperCase());
    return (m == null) ? fallback : m;
  }
}
