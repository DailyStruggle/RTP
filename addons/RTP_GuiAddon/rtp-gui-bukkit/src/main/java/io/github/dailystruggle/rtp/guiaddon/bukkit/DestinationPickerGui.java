package io.github.dailystruggle.rtp.guiaddon.bukkit;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.RtpTargetStatus;
import io.github.dailystruggle.rtp.guiaddon.common.MenuEntry;
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

  /**
   * Destination icons per content row. The menu is framed like the polished
   * "donut-clone" server menus: a full glass border (top + bottom rows, left +
   * right columns) wraps a centred inner content area, so destinations sit in a
   * tidy block rather than clinging to the chest edges.
   */
  private static final int ITEMS_PER_ROW = 7;
  private static final int COLUMNS = 9;
  private static final int MAX_ROWS = 6;
  /** First / last usable inner column (column 0 and 8 are always border). */
  private static final int INNER_FIRST_COL = 1;

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
    int entryCount = model.entries().size();

    // Content lives in the inner area, framed by a one-cell border on every
    // edge. The dashboard tile, when shown, sits on the bottom border row, so
    // it costs no extra inner row.
    int contentRows = Math.max(1, (int) Math.ceil(entryCount / (double) ITEMS_PER_ROW));

    // Total rows = inner content + top border + bottom border, clamped so the
    // frame is always present (minimum 3 rows -> at least one inner row).
    int rows = Math.max(3, Math.min(MAX_ROWS, contentRows + 2));
    int innerRows = rows - 2;
    contentRows = Math.min(contentRows, innerRows);

    // Vertically centre the content block within the inner rows (offset by the
    // single top border row).
    int topRow = 1 + Math.max(0, (innerRows - contentRows) / 2);

    int dashboardSlot = model.showDashboard() ? (rows - 1) * COLUMNS + (COLUMNS / 2) : -1;
    DestinationPickerGui gui = new DestinationPickerGui(model, rows, dashboardSlot);

    int maxEntries = contentRows * ITEMS_PER_ROW;
    int placed = 0;
    for (MenuEntry entry : model.entries()) {
      if (placed >= maxEntries) break;
      int rowIndex = placed / ITEMS_PER_ROW;
      int rowCount = Math.min(ITEMS_PER_ROW, entryCount - rowIndex * ITEMS_PER_ROW);
      int colInRow = placed % ITEMS_PER_ROW;
      // Centre this row horizontally within the 7-wide inner area.
      int startCol = INNER_FIRST_COL + Math.max(0, (ITEMS_PER_ROW - rowCount) / 2);
      int slot = (topRow + rowIndex) * COLUMNS + startCol + colInRow;
      gui.inventory.setItem(slot, icon(entry));
      gui.slotTargets.put(slot, entry.target());
      placed++;
    }

    if (model.showDashboard()) {
      gui.inventory.setItem(dashboardSlot, dashboardTile(model));
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
      meta.setDisplayName(ChatColor.AQUA + entry.displayName());

      List<String> lore = new ArrayList<>();
      RtpTargetStatus.Availability availability = entry.availability();
      lore.add(ChatColor.GRAY + "Status: " + colorFor(availability) + availability.name());
      if (availability == RtpTargetStatus.Availability.ON_COOLDOWN
          && entry.remainingCooldownMillis() > 0L) {
        long secs = (entry.remainingCooldownMillis() + 999L) / 1000L;
        lore.add(ChatColor.GRAY + "Cooldown: " + ChatColor.YELLOW + secs + "s");
      }
      if (entry.cost() > 0.0) {
        lore.add(ChatColor.GRAY + "Cost: " + ChatColor.GOLD + entry.cost());
      }
      lore.add("");
      lore.add(ChatColor.translateAlternateColorCodes('&', readyOrUnavailableLore(entry)));
      meta.setLore(lore);
      item.setItemMeta(meta);
    }
    return item;
  }

  private static String readyOrUnavailableLore(MenuEntry entry) {
    // The neutral model does not carry the ready/unavailable lore strings, so derive
    // a sensible default here; the configured strings drive the chat feedback path.
    return entry.ready() ? "&aClick to teleport!" : "&cUnavailable right now.";
  }

  private static ItemStack dashboardTile(MenuModel model) {
    ItemStack item = new ItemStack(material(model.dashboardIconName(), Material.PAPER));
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.setDisplayName(ChatColor.GOLD + "Server health");
      List<String> lore = new ArrayList<>();
      MetricsSnapshot snapshot = model.metrics();
      if (snapshot == null) {
        lore.add(ChatColor.GRAY + "Metrics unavailable.");
      } else {
        lore.add(ChatColor.GRAY + "TPS (1m): " + ChatColor.AQUA + format(snapshot.tps1m));
        lore.add(ChatColor.GRAY + "MSPT: " + ChatColor.AQUA + format(snapshot.mspt) + " ms");
        lore.add(ChatColor.GRAY + "Players: " + ChatColor.AQUA + snapshot.playerCount);
      }
      meta.setLore(lore);
      item.setItemMeta(meta);
    }
    return item;
  }

  private static String format(double value) {
    return Double.isNaN(value) ? "n/a" : String.format("%.1f", value);
  }

  private static Material material(String name, Material fallback) {
    if (name == null || name.trim().isEmpty()) {
      return fallback;
    }
    Material m = Material.matchMaterial(name.trim().toUpperCase());
    return (m == null) ? fallback : m;
  }

  private static ChatColor colorFor(RtpTargetStatus.Availability availability) {
    switch (availability) {
      case READY:
        return ChatColor.GREEN;
      case ON_COOLDOWN:
      case NO_FUNDS:
        return ChatColor.YELLOW;
      case NO_PERMISSION:
      case DISABLED:
        return ChatColor.RED;
      default:
        return ChatColor.GRAY;
    }
  }
}
