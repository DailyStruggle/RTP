package io.github.dailystruggle.effectsapi.bukkit.BukkitListeners;

import io.github.dailystruggle.effectsapi.bukkit.events.PlayerLandEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Listener + state holder for {@code GlideEffect} (effects-api-ADR-001).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Track currently-gliding players and their per-effect settings.</li>
 *   <li>Cancel premature {@link EntityToggleGlideEvent}s until the player is
 *       actually above ground/liquid (mirrors the legacy {@code RTP_Glide}
 *       addon behaviour).</li>
 *   <li>Suppress firework-rocket use during the glide window when
 *       {@code allowFireworks=false}.</li>
 *   <li>Fire {@code PlayerLandEvent} with the appropriate reason on every
 *       exit path (grounded, timeout, disconnect, shutdown).</li>
 *   <li>On plugin disable, place still-gliding players at the highest safe
 *       block below them (or synthesize a platform).</li>
 * </ul>
 *
 * S-004: every exit path fires {@code PlayerLandEvent} or logs via the plugin
 * logger; failures are not swallowed silently.
 * S-005: shutdown column scan only inspects already-loaded chunks; any chunk
 * that is unloaded is logged and skipped.
 */
public class GlideSafetyListener implements Listener {

    /** Per-player glide state. */
    public static final class GlideState {
        public final UUID playerId;
        public final boolean allowFireworks;
        public final boolean placeOnShutdown;
        public final Material shutdownPlatformMaterial;
        /** BukkitTask id of the timeout watchdog, or {@code -1} if none. */
        public volatile int watchdogTaskId;

        public GlideState(UUID playerId, boolean allowFireworks, boolean placeOnShutdown,
                          Material shutdownPlatformMaterial) {
            this.playerId = playerId;
            this.allowFireworks = allowFireworks;
            this.placeOnShutdown = placeOnShutdown;
            this.shutdownPlatformMaterial = shutdownPlatformMaterial;
            this.watchdogTaskId = -1;
        }
    }

    /** UUID → state. Static so the listener and effect share one source of truth. */
    private static final ConcurrentHashMap<UUID, GlideState> gliders = new ConcurrentHashMap<>();

    public final Plugin caller;

    public GlideSafetyListener(Plugin caller) {
        this.caller = caller;
    }

    public static boolean isGliding(UUID playerId) {
        return gliders.containsKey(playerId);
    }

    public static GlideState getState(UUID playerId) {
        return gliders.get(playerId);
    }

    public static void register(GlideState state) {
        gliders.put(state.playerId, state);
    }

    /**
     * End the glide for the given player, firing
     * {@link PlayerLandEvent} with {@code reason} and cancelling any pending
     * watchdog. Idempotent.
     */
    public static void endGlide(Player player, PlayerLandEvent.Reason reason) {
        if (player == null) return;
        GlideState s = gliders.remove(player.getUniqueId());
        if (s == null) return;
        if (s.watchdogTaskId != -1) {
            try {
                Bukkit.getScheduler().cancelTask(s.watchdogTaskId);
            } catch (Throwable ignored) {
                // scheduler may be torn down at shutdown — non-fatal
            }
        }
        try {
            if (player.isOnline()) player.setGliding(false);
        } catch (Throwable ignored) {
            // entity may already be gone
        }
        try {
            Bukkit.getPluginManager().callEvent(new PlayerLandEvent(player, reason));
        } catch (Throwable ignored) {
            // event bus may be torn down at shutdown
        }
    }

    /**
     * Mirror of the legacy {@code RTP_Glide} OnGlideToggle: cancel premature
     * stop-gliding events until the player is actually above a solid/liquid
     * block. When ground is detected, finalize the glide.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onGlideToggle(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (!gliders.containsKey(player.getUniqueId())) return;
        // EntityToggleGlideEvent fires both for start and stop. Only act on stop.
        if (event.isGliding()) return;
        Block below = player.getLocation().getBlock().getRelative(BlockFace.DOWN);
        if (below.getType().isSolid() || below.isLiquid() || player.isFlying()) {
            endGlide(player, PlayerLandEvent.Reason.GROUNDED);
        } else {
            event.setCancelled(true);
        }
    }

    /**
     * Suppress firework-rocket use when the glider's effect was configured
     * with {@code allowFireworks=false}. Cancel silently — no chat spam.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        GlideState s = gliders.get(player.getUniqueId());
        if (s == null || s.allowFireworks) return;
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) return;
        ItemStack item = event.getItem();
        if (item == null) return;
        try {
            if (item.getType() == Material.FIREWORK_ROCKET) {
                event.setCancelled(true);
            }
        } catch (Throwable ignored) {
            // Material constant may be absent on very old versions; not our concern.
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (gliders.containsKey(event.getPlayer().getUniqueId())) {
            endGlide(event.getPlayer(), PlayerLandEvent.Reason.DISCONNECT);
        }
    }

    /**
     * Place every still-gliding player on the highest safe block in the
     * column below them, or synthesize an emergency 3×3 platform on top of
     * the first non-air block when no safe block exists. Called from
     * {@code EffectsAPI.disable} (and may be called manually for tests).
     *
     * <p>Bounded: O(gliders × worldHeight). Reads only already-loaded chunks
     * (S-005). Failures are logged via the plugin logger (S-004).
     */
    public void placeAllOnShutdown() {
        for (Map.Entry<UUID, GlideState> entry : gliders.entrySet()) {
            GlideState s = entry.getValue();
            if (!s.placeOnShutdown) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            try {
                placeOnShutdown(player, s);
            } catch (Throwable t) {
                if (caller != null) {
                    caller.getLogger().log(Level.WARNING,
                            "GlideEffect shutdown placement failed for " + player.getName(), t);
                }
            }
        }
        // After placement, drain the map so onDisable doesn't leak watchdogs.
        for (UUID id : gliders.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) endGlide(p, PlayerLandEvent.Reason.SHUTDOWN);
        }
        gliders.clear();
    }

    private void placeOnShutdown(Player player, GlideState state) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) return;
        int minY;
        try {
            minY = world.getMinHeight();
        } catch (NoSuchMethodError e) {
            // pre-1.17 worlds
            minY = 0;
        }
        int startY = loc.getBlockY();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();

        // Step 1 — scan downward for the highest safe block (solid top, two air
        // blocks above for head clearance).
        for (int y = startY; y >= minY; y--) {
            Block ground = world.getBlockAt(x, y, z);
            if (!ground.getChunk().isLoaded()) {
                // S-005 — never force-load at shutdown.
                if (caller != null) caller.getLogger().log(Level.INFO,
                        "GlideEffect shutdown: chunk for " + player.getName() + " unloaded; skipping column scan");
                break;
            }
            if (ground.getType().isAir()) continue;
            if (ground.isLiquid()) continue;
            if (!ground.getType().isSolid()) continue;
            Block head1 = world.getBlockAt(x, y + 1, z);
            Block head2 = world.getBlockAt(x, y + 2, z);
            if (head1.getType().isAir() && head2.getType().isAir()) {
                Location target = new Location(world, x + 0.5, y + 1, z + 0.5, loc.getYaw(), loc.getPitch());
                player.setGliding(false);
                player.teleport(target);
                Bukkit.getPluginManager().callEvent(new PlayerLandEvent(player, PlayerLandEvent.Reason.SHUTDOWN));
                return;
            }
        }

        // Step 2 — no safe block below; synthesize a 3×3 platform on the first
        // non-air block. Only replace AIR / CAVE_AIR / VOID_AIR cells.
        int platformY = minY;
        for (int y = startY; y >= minY; y--) {
            Block b = world.getBlockAt(x, y, z);
            if (!b.getChunk().isLoaded()) {
                if (caller != null) caller.getLogger().log(Level.WARNING,
                        "GlideEffect shutdown: " + player.getName() + " left mid-air (chunk unloaded)");
                return;
            }
            if (!b.getType().isAir()) {
                platformY = y;
                break;
            }
        }
        Material mat = state.shutdownPlatformMaterial != null
                ? state.shutdownPlatformMaterial : Material.STONE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block b = world.getBlockAt(x + dx, platformY + 1, z + dz);
                if (!b.getChunk().isLoaded()) continue;
                if (isReplaceableAir(b.getType())) {
                    try {
                        b.setType(mat, false);
                    } catch (Throwable t) {
                        if (caller != null) caller.getLogger().log(Level.WARNING,
                                "GlideEffect shutdown: failed to place platform block at " + b.getLocation(), t);
                    }
                }
            }
        }
        Block head1 = world.getBlockAt(x, platformY + 2, z);
        Block head2 = world.getBlockAt(x, platformY + 3, z);
        if (!head1.getType().isAir() || !head2.getType().isAir()) {
            if (caller != null) caller.getLogger().log(Level.WARNING,
                    "GlideEffect shutdown: head clearance blocked above synthesized platform for " + player.getName());
            return;
        }
        Location target = new Location(world, x + 0.5, platformY + 2, z + 0.5, loc.getYaw(), loc.getPitch());
        player.setGliding(false);
        player.teleport(target);
        Bukkit.getPluginManager().callEvent(new PlayerLandEvent(player, PlayerLandEvent.Reason.SHUTDOWN_PLATFORM));
    }

    private static boolean isReplaceableAir(Material m) {
        if (m == null) return false;
        if (m == Material.AIR) return true;
        // CAVE_AIR / VOID_AIR added in later versions; tolerate absence.
        try {
            return m.name().equals("CAVE_AIR") || m.name().equals("VOID_AIR")
                    || Objects.equals(m, Material.valueOf("CAVE_AIR"))
                    || Objects.equals(m, Material.valueOf("VOID_AIR"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
