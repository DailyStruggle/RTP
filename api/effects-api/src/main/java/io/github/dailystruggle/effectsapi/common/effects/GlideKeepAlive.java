package io.github.dailystruggle.effectsapi.common.effects;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps a {@code GLIDE} effect gliding until the player lands, on platforms without a toggle-glide
 * event to cancel (Fabric, NeoForge; Bukkit uses {@code GlideSafetyListener}).
 *
 * <p>Without an elytra, vanilla {@code LivingEntity#updateFallFlying} clears the fall-flying flag
 * on the player's next tick, so the lift would end in a free fall with fall damage. Each server
 * tick this re-applies the flag and resets fall distance. A glide ends when the player lands
 * (ground, fluid, vehicle) after having been seen airborne, is removed or dead, reaches the landing
 * timeout, or never leaves the ground within {@link #LIFT_GRACE_TICKS} (a failed lift).
 *
 * <p>Platform-neutral: each carrier wraps its player in a {@link Glider} and drives {@link #tick()}
 * from its server-tick hook. {@link #tick()} must run on the server thread; {@link #track} may run
 * on any thread.
 */
public final class GlideKeepAlive {

    /** Hard cap when no landing timeout is configured: five minutes. */
    public static final int MAX_TICKS = 20 * 60 * 5;

    /**
     * Ticks to wait for the first airborne sample. The server keeps the pre-lift {@code onGround}
     * until the client acknowledges the lift teleport, so a landing is only trusted after an
     * airborne sample.
     */
    public static final int LIFT_GRACE_TICKS = 40;

    private static final Logger LOGGER = Logger.getLogger(GlideKeepAlive.class.getName());
    private static final Map<UUID, Entry> ACTIVE = new ConcurrentHashMap<>();

    private GlideKeepAlive() {}

    /** Platform player view used by the keep-alive. Implementations read and write live state. */
    public interface Glider {
        @NotNull UUID id();

        /** Removed from the world, dead or disconnected. */
        boolean isGone();

        /** On the ground, in water or lava, or riding a vehicle. */
        boolean isLanded();

        boolean isGliding();

        void startGliding();

        void stopGliding();

        void resetFallDistance();
    }

    private static final class Entry {
        final Glider glider;
        final int limit;
        int ticks;
        boolean airborne;

        Entry(Glider glider, int limit) {
            this.glider = glider;
            this.limit = limit;
        }
    }

    /**
     * Starts keeping {@code glider} gliding. A second call for the same player restarts its timer.
     *
     * @param landingTimeoutTicks ticks before a forced landing; values outside
     *                            {@code (0, MAX_TICKS)} (including {@link Integer#MAX_VALUE}, "no
     *                            timeout") use {@link #MAX_TICKS}
     */
    public static void track(@NotNull Glider glider, int landingTimeoutTicks) {
        int limit = (landingTimeoutTicks > 0 && landingTimeoutTicks < MAX_TICKS) ? landingTimeoutTicks : MAX_TICKS;
        ACTIVE.put(glider.id(), new Entry(glider, limit));
    }

    public static boolean isTracked(@NotNull UUID id) {
        return ACTIVE.containsKey(id);
    }

    /** Advances every tracked glide by one server tick. */
    public static void tick() {
        if (ACTIVE.isEmpty()) return;
        ACTIVE.values().removeIf(GlideKeepAlive::step);
    }

    /** Drops every tracked glide without touching the players (server stop). */
    public static void clear() {
        ACTIVE.clear();
    }

    /** @return {@code true} when the glide is over and the entry is dropped */
    private static boolean step(Entry e) {
        Glider g = e.glider;
        try {
            if (g.isGone()) return true;
            e.ticks++;
            boolean landed = g.isLanded();
            if (!landed) e.airborne = true;
            boolean liftFailed = !e.airborne && e.ticks > LIFT_GRACE_TICKS;
            if ((e.airborne && landed) || liftFailed || e.ticks > e.limit) {
                g.stopGliding();
                return true;
            }
            g.resetFallDistance();
            if (!g.isGliding()) g.startGliding();
            return false;
        } catch (RuntimeException | LinkageError t) {
            LOGGER.log(Level.WARNING, t, () -> "GLIDE keep-alive stopped for " + g.id());
            return true;
        }
    }
}
