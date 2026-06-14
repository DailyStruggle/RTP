package io.github.dailystruggle.effectsapi.common.spi;

import org.jetbrains.annotations.NotNull;

/**
 * Platform-neutral runtime surface for the effects API. Concrete implementations
 * live in the per-platform subpackages of {@code effects-api}:
 *
 * <ul>
 *   <li>{@code io.github.dailystruggle.effectsapi.bukkit.BukkitEffectRuntime}
 *       — backed by Bukkit's scheduler, {@code World#playSound}, {@code World#spawnParticle}
 *       and {@code Player#addPotionEffect}.</li>
 *   <li>{@code io.github.dailystruggle.effectsapi.fabric.FabricEffectRuntime}
 *       — backed by {@code MinecraftServer#execute}, {@code ServerLevel#playSound},
 *       {@code ServerLevel#sendParticles} and clientbound packets.</li>
 * </ul>
 *
 * <p>This interface is the single chokepoint that decouples
 * {@link io.github.dailystruggle.effectsapi.Effect} from any platform import.
 * It is intentionally narrow: title / actionbar are already routed through
 * {@code RTPPlayer}-equivalent surfaces (see {@code FabricRTPPlayer#sendTitle}
 * and {@code SendMessage#title} on Bukkit) and are therefore not duplicated
 * here.</p>
 *
 * <p>Per {@code effects-api-ADR-003}: keys passed to this surface are plain
 * Mojang resource location strings (e.g. {@code "minecraft:entity.player.levelup"}
 * for sounds, {@code "minecraft:flame"} for particles, {@code "minecraft:speed"}
 * for potions). Each implementation resolves them to its native enum / registry
 * lookup. Unknown keys shall be logged via {@code RTP.log} and treated as no-ops
 * (per S-007 — never silently dropped without a configurable warning).</p>
 *
 * <p><b>Thread context (S-005):</b> Implementations are responsible for
 * dispatching all calls onto the correct thread. On Folia, {@link #schedule}
 * shall use the entity / region scheduler; on Fabric, it shall use
 * {@code MinecraftServer#execute}. Callers shall not assume any particular
 * thread.</p>
 */
public interface EffectRuntime {

    /**
     * Schedule a one-shot task with a delay measured in server ticks
     * (1 tick = 50 ms on a healthy server).
     *
     * @param task        the runnable to execute; must not be {@code null}
     * @param delayTicks  delay in ticks before the first execution; {@code 0}
     *                    means "as soon as the next tick boundary"
     */
    void schedule(@NotNull Runnable task, long delayTicks);

    /**
     * Schedule a repeating task. Implementations shall guarantee that the
     * task is cancelled if the owning effect is cancelled (e.g. on player
     * disconnect or world unload).
     *
     * @param task         the runnable to execute; must not be {@code null}
     * @param delayTicks   delay in ticks before the first execution
     * @param periodTicks  ticks between subsequent executions; must be {@code > 0}
     */
    void scheduleRepeating(@NotNull Runnable task, long delayTicks, long periodTicks);

    /**
     * Play a sound at the given target's location. The {@code soundKey} is a
     * Mojang resource-location string (e.g. {@code "minecraft:entity.player.levelup"}).
     *
     * @param target    the spatial / per-player target; must not be {@code null}
     * @param soundKey  resource-location key; must not be {@code null}
     * @param volume    sound volume (typically {@code 0.0f}–{@code 1.0f}; values
     *                  above {@code 1.0f} extend audible range as on vanilla)
     * @param pitch     sound pitch ({@code 0.5f}–{@code 2.0f} on vanilla)
     */
    void playSound(@NotNull EffectTarget target, @NotNull String soundKey,
                   float volume, float pitch);

    /**
     * Spawn a particle effect at the given target's location.
     *
     * @param target        the spatial / per-player target; must not be {@code null}
     * @param particleKey   resource-location key (e.g. {@code "minecraft:flame"});
     *                      must not be {@code null}
     * @param count         number of particles to spawn; must be {@code >= 0}
     * @param dx            x-axis spread (block radius)
     * @param dy            y-axis spread (block radius)
     * @param dz            z-axis spread (block radius)
     * @param speed         particle motion speed (vanilla parameter; typically {@code 0.0}–{@code 1.0})
     */
    void spawnParticle(@NotNull EffectTarget target, @NotNull String particleKey,
                       int count, double dx, double dy, double dz, double speed);

    /**
     * Apply a potion effect to the target's player. If
     * {@link EffectTarget#player()} is {@code null}, implementations shall
     * treat this as a no-op (ambient targets cannot receive potions).
     *
     * @param target          the per-player target; must not be {@code null}
     * @param potionKey       resource-location key (e.g. {@code "minecraft:speed"});
     *                        must not be {@code null}
     * @param durationTicks   duration in ticks ({@code 20} per second on vanilla);
     *                        must be {@code >= 0}
     * @param amplifier       potion amplifier ({@code 0} = level I); must be {@code >= 0}
     */
    void givePotion(@NotNull EffectTarget target, @NotNull String potionKey,
                    int durationTicks, int amplifier);
}
