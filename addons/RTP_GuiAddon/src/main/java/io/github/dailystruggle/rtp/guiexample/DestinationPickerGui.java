package io.github.dailystruggle.rtp.guiexample;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.RtpTargetStatus;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and owns the chest-inventory destination picker for one player.
 *
 * <p>This is the {@link InventoryHolder} for our menu. Using a custom holder is the
 * safe, recommended way to recognise "our" inventory in the click listener - far
 * better than matching on the inventory title, which players can spoof and which
 * breaks across locales. The holder also carries the slot-to-{@link RtpTarget}
 * mapping so the listener can resolve a click without re-deriving anything.
 *
 * <p>Everything here is pure presentation: it reads from {@code rtp-api} and arranges
 * items. It performs no teleport and no validation of its own - that is RTP's job
 * behind {@link RTPAPI#teleport}.
 */
public final class DestinationPickerGui implements InventoryHolder {

  /** Last row is reserved for the dashboard tile; targets fill the rows above it. */
  private static final int ROWS = 6;
  private static final int SIZE = ROWS * 9;
  private static final int DASHBOARD_SLOT = SIZE - 5; // centre of the bottom row.

  private final Inventory inventory;
  private final Map<Integer, RtpTarget> slotTargets = new HashMap<>();

  private DestinationPickerGui() {
    this.inventory = Bukkit.createInventory(this, SIZE, ChatColor.DARK_AQUA + "RTP Destinations");
  }

  /**
   * Builds a picker populated for {@code player}: one icon per permission-gated
   * target, each decorated with the player's live {@link RtpTargetStatus}, plus a
   * single server-health tile.
   *
   * @param player the viewer; used for the permission-gated target list and per-target status
   * @return a ready-to-open picker holder
   */
  public static DestinationPickerGui build(Player player) {
    DestinationPickerGui gui = new DestinationPickerGui();

    List<RtpTarget> targets = RTPAPI.getAllowedTargets(player.getUniqueId());
    int slot = 0;
    for (RtpTarget target : targets) {
      if (slot >= DASHBOARD_SLOT) break; // leave the bottom row for the dashboard.
      RtpTargetStatus status = RTPAPI.getTargetStatus(player.getUniqueId(), target);
      gui.inventory.setItem(slot, icon(target, status));
      gui.slotTargets.put(slot, target);
      slot++;
    }

    gui.inventory.setItem(DASHBOARD_SLOT, dashboardTile());
    return gui;
  }

  /**
   * Resolves the {@link RtpTarget} bound to a clicked slot, or {@code null} if that
   * slot is not a destination button (e.g. the dashboard tile or empty filler).
   *
   * @param slot the raw slot index that was clicked
   * @return the bound target, or {@code null}
   */
  public RtpTarget targetAt(int slot) {
    return slotTargets.get(slot);
  }

  @Override
  public Inventory getInventory() {
    return inventory;
  }

  // ----- presentation helpers (no RTP state mutated below this line) -----

  private static ItemStack icon(RtpTarget target, RtpTargetStatus status) {
    RtpTargetStatus.Availability availability =
        (status == null) ? RtpTargetStatus.Availability.UNKNOWN : status.availability();

    Material material;
    switch (availability) {
      case READY -> material = materialFor(target);
      case ON_COOLDOWN -> material = Material.CLOCK;
      case NO_PERMISSION, DISABLED -> material = Material.BARRIER;
      case NO_FUNDS -> material = Material.GOLD_NUGGET;
      default -> material = Material.GRAY_DYE;
    }

    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.setDisplayName(ChatColor.AQUA + displayName(target));

      List<String> lore = new ArrayList<>();
      lore.add(ChatColor.GRAY + "Status: " + colorFor(availability) + availability.name());
      if (status != null) {
        if (availability == RtpTargetStatus.Availability.ON_COOLDOWN
            && status.remainingCooldownMillis() > 0L) {
          long secs = (status.remainingCooldownMillis() + 999L) / 1000L;
          lore.add(ChatColor.GRAY + "Cooldown: " + ChatColor.YELLOW + secs + "s");
        }
        if (status.cost() > 0.0) {
          lore.add(ChatColor.GRAY + "Cost: " + ChatColor.GOLD + status.cost());
        }
      }
      lore.add("");
      if (availability == RtpTargetStatus.Availability.READY) {
        lore.add(ChatColor.GREEN + "Click to teleport!");
      } else {
        lore.add(ChatColor.RED + "Unavailable right now.");
      }
      meta.setLore(lore);
      item.setItemMeta(meta);
    }
    return item;
  }

  private static ItemStack dashboardTile() {
    ItemStack item = new ItemStack(Material.PAPER);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.setDisplayName(ChatColor.GOLD + "Server health");
      List<String> lore = new ArrayList<>();
      MetricsSnapshot snapshot = RTPAPI.getMetricsSnapshot();
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

  private static Material materialFor(RtpTarget target) {
    return switch (target.kind()) {
      case DEFAULT -> Material.COMPASS;
      case WORLD -> Material.GRASS_BLOCK;
      case REGION -> Material.MAP;
    };
  }

  private static String displayName(RtpTarget target) {
    return switch (target.kind()) {
      case DEFAULT -> "Random teleport";
      case WORLD -> "World: " + target.name();
      case REGION -> "Region: " + target.name();
    };
  }

  private static ChatColor colorFor(RtpTargetStatus.Availability availability) {
    return switch (availability) {
      case READY -> ChatColor.GREEN;
      case ON_COOLDOWN, NO_FUNDS -> ChatColor.YELLOW;
      case NO_PERMISSION, DISABLED -> ChatColor.RED;
      default -> ChatColor.GRAY;
    };
  }
}
