package io.github.dailystruggle.effectsapi.bukkit.LocalEffects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.EffectsAPI;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.FireworkTypeNames;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.NoteTypeNames;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.ParticleTypeNames;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.PotionTypeNames;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class PotionEffect extends Effect<PotionTypeNames> {
    public PotionEffect() throws IllegalArgumentException {
        super(new EnumMap<>(PotionTypeNames.class));
        EnumMap<PotionTypeNames, Object> data = getData();
        data.put(PotionTypeNames.TYPE, PotionEffectType.BLINDNESS);
        data.put(PotionTypeNames.DURATION, 1);
        data.put(PotionTypeNames.AMPLIFIER, 1);
        data.put(PotionTypeNames.AMBIENT, false);
        data.put(PotionTypeNames.PARTICLES, true);
        data.put(PotionTypeNames.ICON, true);
        this.data = data;
        this.defaults = data.clone();
    }

    @Override
    public void run() {
        int duration=0, amp=0;
        boolean amb=false, part=false, icon=false;

        Object o = data.get(PotionTypeNames.DURATION);
        if(o instanceof Number) duration = ((Number) o).intValue();

        o = data.get(PotionTypeNames.AMPLIFIER);
        if(o instanceof Number) amp = ((Number) o).intValue();

        o = data.get(PotionTypeNames.AMBIENT);
        if(o instanceof Boolean) amb = (Boolean) o;

        o = data.get(PotionTypeNames.PARTICLES);
        if(o instanceof Boolean) part = (Boolean) o;

        o = data.get(PotionTypeNames.ICON);
        if(o instanceof Boolean) icon = (Boolean) o;

        PotionEffectType potionEffectType = (PotionEffectType) data.get(PotionTypeNames.TYPE);
        if(potionEffectType == null) return;

        // Min MC 1.20.1: 6-arg ctor (with icon) is always available.
        org.bukkit.potion.PotionEffect potionEffect =
                new org.bukkit.potion.PotionEffect(potionEffectType, duration, amp, amb, part, icon);
        if (target instanceof Player) {
            applyOnEntityThread((Player) target, potionEffect);
        } else {
            if (target instanceof Entity) target = ((Entity) target).getLocation();
            Location location = (Location) target;
            List<Player> players = Objects.requireNonNull(location.getWorld()).getPlayers()
                    .parallelStream().filter(player -> (player.getLocation().distance(location) < 48))
                    .collect(Collectors.toList());
            for (Player player : players) {
                applyOnEntityThread(player, potionEffect);
            }
        }
    }

    /**
     * Apply a {@link org.bukkit.potion.PotionEffect} to a player on the correct
     * thread. On Folia entity-mutating Bukkit calls (including
     * {@code addPotionEffect}) must be run on the player's
     * {@code EntityScheduler}; on Spigot/Paper they must be run on the main
     * thread. Dispatch goes through {@link #entityDispatcher} (reflective on
     * production, swappable in tests) so {@code effects-api} keeps zero
     * compile-time dependency on the Folia API.
     */
    /**
     * Test seam: callable that dispatches a task on the entity's correct
     * thread. Returns {@code true} when the task was dispatched (or executed)
     * by an entity-aware scheduler; {@code false} when no such scheduler is
     * available so the caller can fall back to the main-thread path. Tests
     * override this to assert that Folia's {@code Player#getScheduler().run}
     * is the path taken.
     */
    @FunctionalInterface
    interface EntityDispatcher {
        boolean dispatch(Player player, org.bukkit.plugin.Plugin caller, Runnable task);
    }

    /** Default dispatcher: reflective Folia/Paper {@code Player#getScheduler}. */
    static volatile EntityDispatcher entityDispatcher = (player, caller, task) -> {
        try {
            Object entityScheduler = Player.class.getMethod("getScheduler").invoke(player);
            if (entityScheduler != null) {
                entityScheduler.getClass()
                        .getMethod("run", org.bukkit.plugin.Plugin.class,
                                java.util.function.Consumer.class, Runnable.class)
                        .invoke(entityScheduler, caller,
                                (java.util.function.Consumer<Object>) t -> task.run(),
                                (Runnable) null);
                return true;
            }
        } catch (NoSuchMethodException notFolia) {
            // Spigot/Paper without EntityScheduler — fall back.
        } catch (Throwable ignored) {
            // Reflection failure — fall back.
        }
        return false;
    };

    static void applyOnEntityThread(Player player, org.bukkit.potion.PotionEffect potionEffect) {
        org.bukkit.plugin.Plugin caller;
        try {
            caller = EffectsAPI.getInstance();
        } catch (IllegalStateException pre) {
            // S-006: getInstance() throws before init(). The dispatcher seam
            // must still be consulted (Folia path / tests), so degrade to a
            // null caller and let downstream guards handle it.
            caller = null;
        }
        // effects-api is platform-agnostic and must not name any host plugin
        // (e.g. "RTP") to look up a Plugin handle — that would be a layering
        // violation. If EffectsAPI.init(Plugin) hasn't run, leave caller null;
        // the dispatcher seam may still accept (Folia/tests), and the legacy
        // fallback below degrades to a best-effort direct call.
        Runnable apply = () -> player.addPotionEffect(potionEffect);
        if (entityDispatcher.dispatch(player, caller, apply)) return;
        boolean primary;
        try {
            primary = Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            primary = false;
        }
        if (primary) {
            safeRun(apply);
        } else if (caller != null) {
            try {
                Bukkit.getScheduler().runTask(caller, apply);
            } catch (Throwable t) {
                logApplyFailure(t);
            }
        } else {
            // No plugin handle: best-effort direct call (legacy behavior).
            safeRun(apply);
        }
    }

    /**
     * Run {@code apply} (typically {@code player.addPotionEffect}) and swallow
     * any throwable. {@code addPotionEffect} can blow up in production
     * ({@code IllegalStateException: Asynchronous effect add} on Folia when
     * routed off-thread, or {@code IllegalArgumentException} for invalid
     * effect data) and in unit tests where the Bukkit server isn't initialized.
     * S-004 attribution is preserved by the warning log; the effects pipeline
     * must not propagate the failure to the teleport caller.
     */
    private static void safeRun(Runnable apply) {
        try {
            apply.run();
        } catch (Throwable t) {
            logApplyFailure(t);
        }
    }

    private static void logApplyFailure(Throwable t) {
        // effects-api cannot reach RTP.log directly (no rtp-core dep). Route
        // through Bukkit.getLogger when a server is available; otherwise
        // (unit tests) silently swallow — the dispatcher seam still records
        // intent and the test asserts at that layer.
        try {
            Bukkit.getLogger().log(java.util.logging.Level.WARNING,
                    "[effects-api] PotionEffect apply failed", t);
        } catch (Throwable ignored) {
            // No server / no logger available — accept the swallow.
        }
    }

    @Override
    public String toPermission() {
        return this.data.get(PotionTypeNames.TYPE).toString().replaceAll("\\.*", "") +
                this.data.get(PotionTypeNames.DURATION).toString().replaceAll("\\.*", "") +
                this.data.get(PotionTypeNames.AMPLIFIER).toString().replaceAll("\\.*", "") +
                this.data.get(PotionTypeNames.AMBIENT).toString().replaceAll("\\.*", "") +
                this.data.get(PotionTypeNames.PARTICLES).toString().replaceAll("\\.*", "") +
                this.data.get(PotionTypeNames.ICON).toString().replaceAll("\\.*", "");
    }

    /**
     * Positional / type-driven key order for {@link PotionEffect}.
     * Unified across Bukkit and Fabric so a single shipped token
     * ({@code POTION.<TYPE>.<DURATION>.<AMPLIFIER>.<AMBIENT>.<PARTICLES>.<ICON>})
     * parses identically on both platforms. Trailing fields are optional and
     * fall back to the defaults set in the ctor.
     */
    private static final PotionTypeNames[] KEY_ORDER = {
            PotionTypeNames.TYPE,
            PotionTypeNames.DURATION,
            PotionTypeNames.AMPLIFIER,
            PotionTypeNames.AMBIENT,
            PotionTypeNames.PARTICLES,
            PotionTypeNames.ICON
    };

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }
}
