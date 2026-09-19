package io.github.dailystruggle.effectsapi.common.spi;

import org.jetbrains.annotations.NotNull;
import java.util.UUID;
import java.util.Map;

/**
 * Platform-neutral handle for a player entity.
 *
 * <p>Implemented by {@code rtp-api}'s {@code RTPPlayer} and platform-specific
 * wrappers in {@code effects-api}.</p>
 */
public interface PlayerHandle {
    /**
     * @return the unique ID of the player.
     */
    @NotNull UUID uuid();

    /**
     * @return the name of the player.
     */
    @NotNull String name();

    /**
     * Plays a sound for this player.
     */
    void playSound(Object type, float volume, float pitch, double dx, double dy, double dz);

    /**
     * Spawns particles for this player.
     */
    void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed);

    /**
     * Applies a potion effect to this player.
     */
    void applyPotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon);

    /**
     * Sends a title to this player.
     */
    void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut);

    /**
     * Plays a note for this player.
     */
    void playNote(Object instrument, int tone);

    /**
     * Sets whether this player is gliding.
     */
    void setGliding(boolean gliding);

    /**
     * Spawns a firework for this player.
     */
    void spawnFirework(Map<String, Object> data);

    /**
     * Starts a post-teleport glide for this player (effects-api-ADR-001).
     *
     * <p>The platform implementation lifts the player by {@code relativeLift}
     * blocks (clamped to {@code maxY}), starts gliding, fires any platform glide
     * event, registers safety state, and arms a landing watchdog after
     * {@code landingTimeoutTicks}. Keeping the orchestration behind this method
     * lets platform-neutral effects drive a glide without touching the
     * underlying platform player object.
     *
     * @param relativeLift        blocks to lift the player by before gliding.
     * @param maxY                absolute Y ceiling for the lift.
     * @param landingTimeoutTicks ticks before a forced landing ({@link Integer#MAX_VALUE} = no timeout).
     * @param allowFireworks      whether fireworks are allowed during the glide.
     * @param placeOnShutdown     whether to place a safety platform on shutdown.
     * @param platformMaterial    platform-neutral material name for the safety platform
     *                            (e.g. {@code "STONE"}); the platform implementation
     *                            resolves it by name.
     */
    void startGlide(int relativeLift, int maxY, int landingTimeoutTicks,
                    boolean allowFireworks, boolean placeOnShutdown, String platformMaterial);
}
