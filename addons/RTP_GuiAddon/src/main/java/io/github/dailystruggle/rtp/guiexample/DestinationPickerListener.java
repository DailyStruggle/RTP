package io.github.dailystruggle.rtp.guiexample;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RtpTarget;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Handles clicks inside the {@link DestinationPickerGui}.
 *
 * <p>This is the part a GUI author "owns" - and the part where inventory footguns
 * live (click-spam, item dupe, drag-into-menu). Note how RTP's safety is unaffected
 * by anything that could go wrong here: the only mutating call available is
 * {@link RTPAPI#teleport}, which re-runs every permission, cooldown, cost, and
 * S-001..S-007 check server-side. The worst a buggy GUI can do is submit a teleport
 * intent that RTP then rejects.
 */
public final class DestinationPickerListener implements Listener {

  @EventHandler
  public void onClick(InventoryClickEvent event) {
    InventoryHolder holder = event.getInventory().getHolder();
    if (!(holder instanceof DestinationPickerGui gui)) {
      return; // Not our menu - leave it alone.
    }

    // Our menu is display-only: cancel every click so nothing can be moved, taken,
    // or duped out of it (the standard anti-dupe guard for a read-only chest GUI).
    event.setCancelled(true);

    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    // Ignore clicks in the player's own inventory or on empty/non-target slots.
    if (event.getClickedInventory() == null
        || event.getClickedInventory().getHolder() != gui) {
      return;
    }

    RtpTarget target = gui.targetAt(event.getRawSlot());
    if (target == null) {
      return; // Decoration (dashboard tile / filler), not a destination button.
    }

    // Submit the validated intent. RTP resolves the target, enforces permission /
    // cooldown / cost / safety off-thread, and always completes with a result
    // (never a silent no-op, per REQ-RTP-S-004).
    player.closeInventory();
    player.sendMessage(ChatColor.AQUA + "Finding you a spot...");
    RTPAPI.teleport(player.getUniqueId(), target)
        .whenComplete(
            (result, error) -> {
              // This callback may run off the main thread; only do main-thread-safe
              // work here. Sending a chat message is safe on Bukkit/Paper.
              if (error != null) {
                player.sendMessage(ChatColor.RED + "Teleport failed: " + error.getMessage());
              } else if (result == null || !result.isSuccess()) {
                String reason = (result == null) ? "unknown" : result.message();
                player.sendMessage(ChatColor.RED + "Teleport failed: " + reason);
              } else {
                player.sendMessage(ChatColor.GREEN + "Teleported!");
              }
            });
  }
}
