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

    /**
     * Executes a command as this player (effects-api-ADR-007).
     *
     * @param command command string to run
     */
    default void performCommand(@NotNull String command) {}

    /**
     * Drops the player's inventory contents naturally at the specified location (or their current location
     * if null) and clears their inventory.
     *
     * @param dropLocation the location to drop the inventory at, or null for current location
     */
    default void dropInventory(@org.jetbrains.annotations.Nullable LocationHandle dropLocation) {}

    /**
     * Drops the player's inventory contents naturally at their current location and clears their inventory.
     */
    default void dropInventory() {
        dropInventory((LocationHandle) null);
    }

    /**
     * Drops experience naturally at the specified location (or player's current location if null)
     * according to the vanilla Minecraft player death experience formula (Math.min(level * 7, 100)),
     * and clears the player's experience.
     *
     * @param dropLocation the location to drop experience at, or null for current location
     */
    default void dropExperience(@org.jetbrains.annotations.Nullable LocationHandle dropLocation) {}

    /**
     * Drops experience naturally at the player's current location according to the vanilla Minecraft
     * player death experience formula (Math.min(level * 7, 100)), and clears the player's experience.
     */
    default void dropExperience() {
        dropExperience((LocationHandle) null);
    }

    /**
     * Kills the player, triggering the standard Minecraft death event and death screen.
     */
    default void kill() {
        kill(true, null);
    }

    /**
     * Kills the player, triggering the standard Minecraft death event and death screen,
     * and optionally sets their respawn position to the target location.
     *
     * @param setBed whether to anchor the respawn point (bed/respawn anchor)
     * @param respawnLocation the location to respawn at, or null to keep standard respawn
     */
    default void kill(boolean setBed, @org.jetbrains.annotations.Nullable LocationHandle respawnLocation) {}
}
