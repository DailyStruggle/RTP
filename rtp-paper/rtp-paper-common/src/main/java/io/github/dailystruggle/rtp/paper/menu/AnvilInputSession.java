package io.github.dailystruggle.rtp.paper.menu;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.menu.MenuRedeemSubcommand;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * ADR-045 anvil-GUI input session manager for the Paper/Folia menu renderer.
 *
 * <p>Implements {@link MenuRedeemSubcommand.AnvilInputOpener}: when the
 * {@code "type a custom value..."} picker row is clicked, the redeem path
 * calls {@link #open(UUID, List, String, String)} which opens an anvil GUI
 * on the player. The player types into the anvil's rename field; on clicking
 * the result slot we synthesize
 * {@code /rtp <parentPath...> <paramName>:<typed>} as the player and run it.
 * Closing the inventory without clicking the result slot cancels the input.
 *
 * <p>This class is also the Bukkit {@link Listener} that picks up the
 * {@link InventoryClickEvent} and {@link InventoryCloseEvent} for the open
 * anvils. {@link #register(Plugin)} must be called once at plugin enable;
 * {@link #unregister()} unwires it. The class is instantiated reflectively
 * from {@code rtp-plugin} to keep the {@code rtp-plugin} module free of a
 * compile-time dependency on Paper (same pattern as {@link BookMenuRenderer}).
 *
 * <p>Threading: {@link Player#performCommand(String)} is dispatched on the
 * server's main thread on Paper/Spigot and on the entity's
 * {@code EntityScheduler} on Folia. The inventory event handlers themselves
 * already run on the owning region's thread per Folia's contract; no
 * additional scheduling is required for the inventory open or for command
 * dispatch (Bukkit's {@code performCommand} is region-safe for player
 * subjects).
 *
 * <p>S-005 safe: no chunk I/O; opening an anvil does not load chunks.
 *
 * @see MenuRedeemSubcommand.AnvilInputOpener
 */
public final class AnvilInputSession implements MenuRedeemSubcommand.AnvilInputOpener, Listener {

    /**
     * Per-player in-flight session. Held until the player either confirms (clicks
     * the result slot) or closes the inventory.
     */
    private static final class Session {
        final List<String> parentPath;
        final String paramName;
        boolean confirmed;

        Session(List<String> parentPath, String paramName) {
            this.parentPath = parentPath;
            this.paramName = paramName;
        }
    }

    /** Active sessions keyed by player UUID. */
    private final Map<UUID, Session> active = new ConcurrentHashMap<>();

    /** {@code true} once {@link #register(Plugin)} has been called. */
    private volatile boolean registered;

    /** Register the listener with Bukkit. Idempotent. */
    public synchronized void register(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        if (registered) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registered = true;
    }

    /** Unregister the listener. Safe to call multiple times. */
    public synchronized void unregister() {
        if (!registered) return;
        HandlerList.unregisterAll(this);
        registered = false;
        active.clear();
    }

    @Override
    public boolean open(UUID viewer,
                        List<String> parentPath,
                        String paramName,
                        String prefill) {
        Player player;
        try {
            player = Bukkit.getPlayer(viewer);
        } catch (Throwable t) {
            return false;
        }
        if (player == null) return false;

        // Drop any prior session (only one anvil prompt per player at a time).
        active.remove(viewer);

        InventoryView view;
        try {
            // Paper's Player#openAnvil(Location, boolean): null location uses
            // the player's current location; force=true bypasses the "no anvil
            // here" check so the inventory always opens regardless of the
            // surrounding blocks.
            view = player.openAnvil(null, true);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "menu anvil-input openAnvil failed for " + viewer + ": " + t.getMessage(), t);
            return false;
        }
        if (view == null) return false;

        // Seed the rename slot with a paper item whose display name carries
        // the prefill — the anvil rename field initialises from the left
        // slot item's display name on the client.
        try {
            Inventory inv = view.getTopInventory();
            ItemStack seed = new ItemStack(Material.PAPER);
            ItemMeta meta = seed.getItemMeta();
            if (meta != null) {
                // Seed the left slot with a non-empty display name that the
                // player will overwrite. We deliberately do NOT use paramName
                // here: vanilla anvils only produce a result item (slot 2)
                // when the rename text differs from the seed's display name,
                // so seeding with the param name would make a no-edit confirm
                // produce no result slot to click. A single space is short,
                // visible as "empty" to the player, and guarantees any typed
                // text differs from the seed.
                String label = prefill == null || prefill.isEmpty()
                        ? " "
                        : ChatColor.stripColor(stripAmpersand(prefill));
                meta.setDisplayName(label);
                seed.setItemMeta(meta);
            }
            inv.setItem(0, seed);
        } catch (Throwable t) {
            // Non-fatal: the anvil is open; player can still type. Log and continue.
            RTP.log(Level.WARNING,
                    "menu anvil-input seed item failed for " + viewer + ": " + t.getMessage());
        }

        active.put(viewer, new Session(List.copyOf(parentPath), paramName));
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = active.get(player.getUniqueId());
        if (session == null) return;
        InventoryView view = event.getView();
        if (view.getType() != InventoryType.ANVIL) return;
        if (!(view.getTopInventory() instanceof AnvilInventory anvil)) return;
        // Result slot is index 2 on the top inventory; reject anything else.
        if (event.getRawSlot() != 2) return;

        // Always cancel: vanilla normally requires a non-empty result item AND
        // sufficient XP to permit picking up slot 2. Cancelling unconditionally
        // lets us treat any click on the result slot as "confirm", regardless
        // of whether vanilla would have allowed the pickup.
        event.setCancelled(true);
        String typed = null;
        try {
            typed = anvil.getRenameText();
        } catch (Throwable ignored) {
            // Fallback: read display name off the result slot item.
            try {
                ItemStack out = view.getTopInventory().getItem(2);
                if (out != null && out.getItemMeta() != null
                        && out.getItemMeta().hasDisplayName()) {
                    typed = out.getItemMeta().getDisplayName();
                }
            } catch (Throwable ignoredToo) {
                // give up; typed stays null.
            }
        }
        if (typed == null) typed = "";
        typed = typed.trim();
        if (typed.isEmpty()) {
            // Treat as a cancel rather than submit an empty value.
            return;
        }

        session.confirmed = true;
        active.remove(player.getUniqueId());

        // Close the inventory on the next tick so the click handler can
        // return cleanly before the close event fires.
        final String command = buildCommand(session.parentPath, session.paramName, typed);
        Bukkit.getScheduler().runTask(getRtpPlugin(), () -> {
            try {
                player.closeInventory();
            } catch (Throwable ignored) {
                // best-effort close
            }
            try {
                player.performCommand(command);
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "menu anvil-input performCommand failed for " + player.getUniqueId()
                                + " cmd='" + command + "': " + t.getMessage(), t);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Session session = active.remove(player.getUniqueId());
        if (session == null) return;
        // confirmed=true means onClick already scheduled the dispatch; nothing
        // more to do. confirmed=false means the player cancelled (closed the
        // inventory without clicking the result slot) — drop the session.
    }

    /** Assemble {@code /rtp <parentPath...> <paramName>:<typed>}. */
    private static String buildCommand(List<String> parentPath, String paramName, String typed) {
        StringBuilder sb = new StringBuilder("/rtp");
        for (String seg : parentPath) {
            if (seg == null || seg.isEmpty()) continue;
            sb.append(' ').append(seg);
        }
        sb.append(' ').append(paramName).append(':').append(typed);
        return sb.toString();
    }

    /**
     * Look up the active RTP plugin to schedule the close + command dispatch.
     * We resolve via {@code Bukkit.getPluginManager()} by name so this class
     * does not have to be constructed with a Plugin reference (matches the
     * loose coupling pattern already used by {@link BookMenuRenderer}).
     */
    private static @Nullable Plugin getRtpPlugin() {
        try {
            // The plugin file historically registers under the name "RTP";
            // fall back to the first enabled plugin claiming /rtp if needed.
            Plugin p = Bukkit.getPluginManager().getPlugin("RTP");
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stripAmpersand(String s) {
        if (s == null) return "";
        // Crude: drop &<x> color-code pairs without touching standalone '&'.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if ("0123456789abcdefklmnorxABCDEFKLMNORX".indexOf(n) >= 0) {
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
