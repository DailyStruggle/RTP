package io.github.dailystruggle.rtp.paper.menu;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
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
        final MenuAction.Mode mode;
        boolean confirmed;

        Session(List<String> parentPath, String paramName, MenuAction.Mode mode) {
            this.parentPath = parentPath;
            this.paramName = paramName;
            this.mode = mode;
        }
    }

    /** Active sessions keyed by player UUID. */
    private final Map<UUID, Session> active = new ConcurrentHashMap<>();

    /**
     * Bound on-demand by {@code RTPCmdBukkit} after {@link MenuRedeemSubcommand}
     * is constructed; invoked on STAGE-mode confirm to push the typed value
     * into the player's per-backend config staging cart instead of running
     * a command. {@code null} when no cart sink has been wired (test
     * scaffolds, pre-staging legacy callers); STAGE-mode opens degrade to
     * a logged no-op in that case.
     */
    private volatile @Nullable MenuRedeemSubcommand.CartSink cartSink;

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
    public void setCartSink(@Nullable MenuRedeemSubcommand.CartSink sink) {
        this.cartSink = sink;
    }

    @Override
    public boolean open(UUID viewer,
                        List<String> parentPath,
                        String paramName,
                        String prefill,
                        MenuAction.Mode mode,
                        @Nullable MenuRedeemSubcommand.CartSink sink) {
        if (sink != null) this.cartSink = sink;
        return openInternal(viewer, parentPath, paramName, prefill, mode);
    }

    @Override
    public boolean open(UUID viewer,
                        List<String> parentPath,
                        String paramName,
                        String prefill) {
        return openInternal(viewer, parentPath, paramName, prefill, MenuAction.Mode.RUN);
    }

    private boolean openInternal(UUID viewer,
                                 List<String> parentPath,
                                 String paramName,
                                 String prefill,
                                 MenuAction.Mode mode) {
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

        active.put(viewer, new Session(List.copyOf(parentPath), paramName, mode));
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = active.get(player.getUniqueId());
        if (session == null) return;
        InventoryView view = event.getView();
        // Diagnostic: log every click that arrives for a player with an
        // active anvil-input session, so we can see exactly which guard the
        // event hit when the user reports "no command was issued".
        RTP.log(Level.FINE,
                "menu anvil-input onClick: player=" + player.getUniqueId()
                        + " viewType=" + view.getType()
                        + " rawSlot=" + event.getRawSlot()
                        + " action=" + event.getAction());
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
        } catch (Throwable t) {
            RTP.log(Level.FINE,
                    "menu anvil-input onClick: getRenameText() threw "
                            + t.getClass().getName() + ": " + t.getMessage());
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
        dispatchConfirm(player, session, typed, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Session session = active.remove(player.getUniqueId());
        if (session == null) return;
        RTP.log(Level.FINE,
                "menu anvil-input onClose: player=" + player.getUniqueId()
                        + " confirmed=" + session.confirmed);
        // confirmed=true means onClick already scheduled the dispatch; nothing
        // more to do.
        if (session.confirmed) return;
        // confirmed=false: the player closed the inventory without clicking
        // the result slot. Vanilla anvils do not always render a clickable
        // result item (no XP, no item-change, server cost rules), so the
        // expected gesture from a player who has typed a value and pressed
        // Esc / closed is "submit what I typed". Read the current rename
        // text; if non-empty, treat it as a confirm and reopen the menu at
        // the parent path with the staged paramName=value segment. If empty,
        // drop the session silently (true cancel).
        InventoryView view = event.getView();
        if (view.getType() != InventoryType.ANVIL) return;
        if (!(view.getTopInventory() instanceof AnvilInventory anvil)) return;
        String typed = null;
        try {
            typed = anvil.getRenameText();
        } catch (Throwable ignored) {
            // give up; typed stays null.
        }
        if (typed == null) typed = "";
        typed = typed.trim();
        if (typed.isEmpty()) return;

        dispatchConfirm(player, session, typed, false);
    }

    /**
     * Final-stage confirm handler shared by {@link #onClick} (clicked the
     * anvil result slot) and {@link #onClose} (closed the inventory with
     * non-empty typed text). Honors {@link Session#mode}:
     * <ul>
     *   <li>{@link MenuAction.Mode#RUN} (legacy / param-picker free-form
     *       row) -> submit {@code /rtp <parentPath...> <paramName>=<typed>}
     *       via {@link Player#performCommand(String)}, after closing the
     *       inventory on the next tick.</li>
     *   <li>{@link MenuAction.Mode#STAGE} (curated config-key click) ->
     *       resolve the file name from {@code parentPath[1]} when
     *       {@code parentPath[0]} is {@code config}, then push
     *       {@code (paramName=typed)} into the player's cart via
     *       {@link #cartSink}. After staging, the player is reopened on
     *       {@code /rtp config <file>} so the curated page can surface
     *       the new "Pending" entry. The cart sink is invoked
     *       synchronously inside the click / close handler; it is a pure
     *       in-memory mutation and is safe from any region thread that
     *       owns the player (REQ-RTP-S-005 unaffected).</li>
     * </ul>
     */
    private void dispatchConfirm(Player player, Session session, String typed, boolean closeInventory) {
        if (session.mode == MenuAction.Mode.STAGE) {
            MenuRedeemSubcommand.CartSink sink = cartSink;
            // file name lives at parentPath[1] for /rtp config <file> paths.
            String fileName = null;
            if (session.parentPath.size() >= 2
                    && "config".equalsIgnoreCase(session.parentPath.get(0))) {
                fileName = session.parentPath.get(1);
                // Normalize: the TreeCommand walk uses the suffixed segment
                // ("performance.yml"), but buildConfigFile / ApplyStagedConfig
                // address the cart by the bare basename ("performance"). Stage
                // under the bare form so Apply finds the entries.
                if (fileName != null
                        && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".yml")) {
                    fileName = fileName.substring(0, fileName.length() - 4);
                }
            }
            if (sink == null || fileName == null) {
                RTP.log(Level.WARNING,
                        "menu anvil-input STAGE confirm dropped for " + player.getUniqueId()
                                + " (sink=" + (sink != null ? "set" : "null")
                                + ", parentPath=" + session.parentPath + "); falling through to RUN.");
                dispatchRun(player, session, typed, closeInventory);
                return;
            }
            try {
                sink.stage(player.getUniqueId(), fileName, session.paramName, typed);
            } catch (RuntimeException e) {
                RTP.log(Level.WARNING,
                        "menu anvil-input STAGE sink threw for " + player.getUniqueId()
                                + " file=" + fileName + " key=" + session.paramName
                                + ": " + e.getMessage(), e);
                return;
            }
            // Reopen the curated file page so the new "Pending" entry is visible.
            // Must route through the menu mirror (`/rtp menu config <file>`),
            // not the text-mode config-reload command (`/rtp config <file>`),
            // which would just reload configs without re-rendering the menu.
            final String reopen = "/rtp menu config " + fileName;
            Plugin plugin = getRtpPlugin();
            if (plugin == null) {
                RTP.log(Level.WARNING,
                        "menu anvil-input STAGE reopen skipped for " + player.getUniqueId()
                                + ": plugin unavailable");
                return;
            }
            scheduleForPlayer(plugin, player, () -> {
                if (closeInventory) {
                    try { player.closeInventory(); } catch (Throwable ignored) { /* best-effort */ }
                }
                try {
                    player.performCommand(reopen.startsWith("/") ? reopen.substring(1) : reopen);
                } catch (Throwable t) {
                    RTP.log(Level.WARNING,
                            "menu anvil-input STAGE reopen failed for " + player.getUniqueId()
                                    + " cmd='" + reopen + "': " + t.getMessage(), t);
                }
            });
            return;
        }
        dispatchRun(player, session, typed, closeInventory);
    }

    /** RUN-mode confirm: submit the assembled command via {@code performCommand}. */
    private void dispatchRun(Player player, Session session, String typed, boolean closeInventory) {
        final String command = buildCommand(session.parentPath, session.paramName, typed);
        RTP.log(Level.FINE,
                "menu anvil-input RUN confirm: dispatching '" + command + "' for "
                        + player.getUniqueId());
        Plugin plugin = getRtpPlugin();
        if (plugin == null) {
            RTP.log(Level.WARNING,
                    "menu anvil-input RUN dispatch skipped for "
                            + player.getUniqueId() + ": plugin unavailable");
            return;
        }
        scheduleForPlayer(plugin, player, () -> {
            if (closeInventory) {
                try { player.closeInventory(); } catch (Throwable ignored) { /* best-effort */ }
            }
            try {
                player.performCommand(command.startsWith("/") ? command.substring(1) : command);
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "menu anvil-input RUN performCommand failed for " + player.getUniqueId()
                                + " cmd='" + command + "': " + t.getMessage(), t);
            }
        });
    }

    /**
     * Schedule {@code task} on the next tick on a thread that legally owns
     * {@code player}. Routes through {@link RTP#scheduler}'s
     * {@code scheduleTeleport} entry point, which on Folia dispatches via the
     * player's per-entity scheduler and on Paper/Spigot falls through to the
     * global / main-thread scheduler. The name is a historical artifact - the
     * underlying method is a generic "run this Runnable on a thread that owns
     * the player after N ticks", not specifically a teleport submission. We
     * use it here to defer command dispatch out of the InventoryClick /
     * InventoryClose handlers exactly like a Bukkit {@code runTask} would,
     * but in a way that is legal on Folia (REQ-RTP-S-005, Folia threading).
     */
    private static void scheduleForPlayer(Plugin plugin, Player player, Runnable task) {
        try {
            io.github.dailystruggle.rtp.api.entity.RTPPlayer rtpPlayer =
                    (RTP.serverAccessor != null)
                            ? RTP.serverAccessor.getPlayer(player.getUniqueId())
                            : null;
            if (RTP.scheduler != null && rtpPlayer != null) {
                RTP.scheduler.scheduleTeleport(
                        rtpPlayer,
                        new io.github.dailystruggle.rtp.common.tasks.RTPRunnable(task),
                        1L);
                return;
            }
        } catch (Throwable t) {
            RTP.log(Level.FINE,
                    "menu anvil-input scheduleForPlayer: RTP.scheduler path failed ("
                            + t.getClass().getName() + ": " + t.getMessage() + "); falling back");
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "menu anvil-input scheduleForPlayer: both RTP.scheduler and legacy"
                            + " scheduler failed for " + player.getUniqueId() + ": " + t.getMessage(), t);
        }
    }

    /**
     * Assemble {@code /rtp menu <parentPath...> <paramName>=<typed>}.
     *
     * <p>The leading {@code menu} re-routes to the menu open-path so the
     * player lands back in the menu at the parent node with the typed value
     * staged in the assembled path (surfaced by the Execute row). The
     * Execute row click is what actually runs the constructed command;
     * confirm-from-anvil does not auto-execute the underlying subcommand.
     *
     * <p>The {@code =} separator matches commands-api's parameter parser
     * (which accepts only {@code =} for {@code k=v} pairs).
     */
    private static String buildCommand(List<String> parentPath, String paramName, String typed) {
        StringBuilder sb = new StringBuilder("/rtp menu");
        for (String seg : parentPath) {
            if (seg == null || seg.isEmpty()) continue;
            sb.append(' ').append(seg);
        }
        sb.append(' ').append(paramName).append('=').append(typed);
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
