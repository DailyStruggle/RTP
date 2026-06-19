package io.github.dailystruggle.rtp.guiaddon.bukkit;

import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.guiaddon.common.MenuActions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Translates a click in our chest menu into a teleport intent.
 *
 * <p>Recognises "our" inventory by its {@link DestinationPickerGui} holder (not its
 * title), always cancels the click so items can never be removed, and delegates the
 * actual teleport + feedback to the platform-neutral {@link MenuActions} in
 * {@code common}. This listener therefore holds zero teleport/status logic.
 *
 * <p>Drags are cancelled separately ({@link #onDrag}): {@link InventoryClickEvent}
 * does not fire for click-drags across slots, so a bare click-cancel would still
 * leave the menu open to a drag-deposit (the classic GUI item-dupe vector).
 */
public final class DestinationPickerListener implements Listener {

  @EventHandler
  public void onClick(InventoryClickEvent event) {
    InventoryHolder holder = event.getInventory().getHolder();
    if (!(holder instanceof DestinationPickerGui gui)) {
      return; // not our menu
    }

    // Always cancel: this is a display-only menu, never an item container.
    event.setCancelled(true);

    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    // Ignore clicks in the player's own inventory or outside the top inventory.
    if (event.getClickedInventory() == null
        || !event.getClickedInventory().equals(gui.getInventory())) {
      return;
    }

    RtpTarget target = gui.targetAt(event.getRawSlot());
    if (target == null) {
      return; // decoration (dashboard / filler), not a destination button
    }

    player.closeInventory();
    MenuActions.submit(player.getUniqueId(), target);
  }

  @EventHandler
  public void onDrag(InventoryDragEvent event) {
    if (!(event.getInventory().getHolder() instanceof DestinationPickerGui)) {
      return; // not our menu
    }
    // Cancel if any dragged slot lands in the top (menu) inventory. Raw slots below
    // the top inventory size belong to our display-only menu; never let items in.
    int topSize = event.getView().getTopInventory().getSize();
    for (int rawSlot : event.getRawSlots()) {
      if (rawSlot < topSize) {
        event.setCancelled(true);
        return;
      }
    }
  }
}
