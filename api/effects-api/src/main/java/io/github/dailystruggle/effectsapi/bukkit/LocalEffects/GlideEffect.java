package io.github.dailystruggle.effectsapi.bukkit.LocalEffects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.EffectsAPI;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.GlideTypeNames;
import io.github.dailystruggle.effectsapi.bukkit.BukkitListeners.GlideSafetyListener;
import io.github.dailystruggle.effectsapi.bukkit.events.PlayerGlideEvent;
import io.github.dailystruggle.effectsapi.bukkit.events.PlayerLandEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;

/**
 * Post-teleport glide effect (effects-api-ADR-001).
 *
 * <p>Lifts the player by {@code RELATIVE} blocks (clamped to {@code MAX}),
 * starts elytra-style gliding, fires {@link PlayerGlideEvent}, and arms a
 * watchdog that forces a landing after {@code LANDINGTIMEOUT} ticks.
 *
 * <p>Folia note: lifecycle interactions (teleport, setGliding, scheduling)
 * route through Bukkit's scheduler / current thread on Spigot/Paper. On
 * Folia these are entity-affecting operations and the caller is expected to
 * dispatch this effect on the player's region; effects-api itself stays
 * platform-neutral. The watchdog is registered via the regular Bukkit
 * scheduler so it can be cancelled symmetrically.
 */
public class GlideEffect extends Effect<GlideTypeNames> {

    public GlideEffect() {
        super(new EnumMap<>(GlideTypeNames.class));
        EnumMap<GlideTypeNames, Object> data = getData();
        data.put(GlideTypeNames.SHUTDOWNPLATFORMMATERIAL, Material.STONE);
        data.put(GlideTypeNames.RELATIVE, 75);
        data.put(GlideTypeNames.MAX, 320);
        data.put(GlideTypeNames.LANDINGTIMEOUT, Integer.MAX_VALUE);
        data.put(GlideTypeNames.ALLOWFIREWORKS, false);
        data.put(GlideTypeNames.PLACEONSHUTDOWN, true);
        data.put(GlideTypeNames.WORLD, "*");
        this.data = data;
        this.defaults = data.clone();
    }

    @Override
    public void run() {
        if (target instanceof Entity) target = ((Entity) target).getLocation();
        if (!(target instanceof Location)) return;
        Location loc = (Location) target;
        if (loc.getWorld() == null) return;

        Player player = resolvePlayer(loc);
        if (player == null) return;

        int relative = intOf(GlideTypeNames.RELATIVE, 75);
        int max = intOf(GlideTypeNames.MAX, 320);
        int timeout = intOf(GlideTypeNames.LANDINGTIMEOUT, Integer.MAX_VALUE);
        boolean allowFireworks = boolOf(GlideTypeNames.ALLOWFIREWORKS, false);
        boolean placeOnShutdown = boolOf(GlideTypeNames.PLACEONSHUTDOWN, true);
        Material platformMat = materialOf(GlideTypeNames.SHUTDOWNPLATFORMMATERIAL, Material.STONE);

        Location lifted = player.getLocation().clone();
        int toY = Math.min(lifted.getBlockY() + relative, max);
        lifted.setY(toY);
        try {
            player.teleport(lifted);
            player.setFlying(false);
            player.setGliding(true);
        } catch (Throwable t) {
            // S-004: don't swallow silently; surface via plugin logger.
            Plugin caller = EffectsAPI.getInstance();
            if (caller != null) caller.getLogger().warning(
                    "GlideEffect failed to start glide for " + player.getName() + ": " + t.getMessage());
            return;
        }

        GlideSafetyListener.GlideState state = new GlideSafetyListener.GlideState(
                player.getUniqueId(), allowFireworks, placeOnShutdown, platformMat);
        GlideSafetyListener.register(state);

        Bukkit.getPluginManager().callEvent(new PlayerGlideEvent(player));

        // Arm watchdog. Integer.MAX_VALUE is treated as "no timeout" — skip the
        // schedule entirely to avoid a long-lived task that will never fire.
        if (timeout > 0 && timeout < Integer.MAX_VALUE) {
            Plugin caller = EffectsAPI.getInstance();
            if (caller != null) {
                try {
                    BukkitTask task = Bukkit.getScheduler().runTaskLater(caller, () -> {
                        Player p = Bukkit.getPlayer(state.playerId);
                        if (p != null && GlideSafetyListener.isGliding(state.playerId)) {
                            GlideSafetyListener.endGlide(p, PlayerLandEvent.Reason.TIMEOUT);
                        }
                    }, timeout);
                    state.watchdogTaskId = task.getTaskId();
                } catch (Throwable t) {
                    caller.getLogger().warning(
                            "GlideEffect could not schedule timeout watchdog: " + t.getMessage());
                }
            }
        }
    }

    /**
     * Find the Player to glide. {@code target} may be an Entity (preferred —
     * its location was already extracted above) or a Location (in which case
     * the closest online player at that exact block is the glide target).
     */
    private Player resolvePlayer(Location loc) {
        // The standard call path is setTarget(player); after run() unwraps it
        // to a Location, we lose the reference. Recover by scanning online
        // players in the same world for one within 1 block of the location.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld() != loc.getWorld()) continue;
            if (p.getLocation().distanceSquared(loc) < 4.0) return p;
        }
        return null;
    }

    private int intOf(GlideTypeNames key, int fallback) {
        Object o = data.get(key);
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try { return Integer.parseInt((String) o); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private boolean boolOf(GlideTypeNames key, boolean fallback) {
        Object o = data.get(key);
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof String) return Boolean.parseBoolean((String) o);
        return fallback;
    }

    private Material materialOf(GlideTypeNames key, Material fallback) {
        Object o = data.get(key);
        if (o instanceof Material) return (Material) o;
        if (o instanceof String) {
            Material m = Material.matchMaterial((String) o);
            if (m != null) return m;
        }
        return fallback;
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    private static final GlideTypeNames[] KEY_ORDER = {
            GlideTypeNames.SHUTDOWNPLATFORMMATERIAL,
            GlideTypeNames.RELATIVE,
            GlideTypeNames.MAX,
            GlideTypeNames.LANDINGTIMEOUT,
            GlideTypeNames.ALLOWFIREWORKS,
            GlideTypeNames.PLACEONSHUTDOWN,
            GlideTypeNames.WORLD
    };

    @Override
    public String toPermission() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.data.get(GlideTypeNames.SHUTDOWNPLATFORMMATERIAL)).append('.');
        sb.append(this.data.get(GlideTypeNames.RELATIVE)).append('.');
        sb.append(this.data.get(GlideTypeNames.MAX)).append('.');
        sb.append(this.data.get(GlideTypeNames.LANDINGTIMEOUT)).append('.');
        sb.append(this.data.get(GlideTypeNames.ALLOWFIREWORKS)).append('.');
        sb.append(this.data.get(GlideTypeNames.PLACEONSHUTDOWN)).append('.');
        sb.append(this.data.get(GlideTypeNames.WORLD));
        return sb.toString();
    }
}
