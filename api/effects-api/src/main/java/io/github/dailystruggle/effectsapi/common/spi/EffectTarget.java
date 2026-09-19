package io.github.dailystruggle.effectsapi.common.spi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Platform-neutral target for a single {@link io.github.dailystruggle.effectsapi.Effect}
 * application. Wraps the player the effect is acting on (may be
 * {@code null} for ambient world effects) and the location the
 * effect is anchored to (e.g. the destination of a teleport for the
 * {@code postteleport} hook).
 *
 * <p>Per {@code effects-api-ADR-003}, this type is the only platform-neutral
 * carrier passed across the {@link EffectRuntime} surface; concrete
 * Bukkit / Fabric runtimes resolve it back to {@code Player} / {@code Location}
 * (Bukkit) or {@code ServerPlayer} / {@code Vec3} (Fabric) at the implementation
 * boundary.</p>
 *
 * <p>Immutable.</p>
 */
public final class EffectTarget {
    private final @Nullable PlayerHandle player;
    private final @NotNull LocationHandle location;

    public EffectTarget(@Nullable Object player, @NotNull Object location) {
        this.player = player == null ? null : HandleRegistry.wrapPlayer(player);
        LocationHandle loc = HandleRegistry.wrapLocation(location);
        if (loc == null) {
            throw new IllegalArgumentException("EffectTarget location shall not be null or unresolvable");
        }
        this.location = loc;
    }

    /** The player the effect targets, or {@code null} for ambient world effects. */
    public @Nullable PlayerHandle player() {
        return player;
    }

    /** The location the effect is anchored to. Never {@code null}. */
    public @NotNull LocationHandle location() {
        return location;
    }

    @Override
    public String toString() {
        return "EffectTarget{player=" + (player == null ? "null" : player.uuid())
                + ", location=" + location + '}';
    }
}
