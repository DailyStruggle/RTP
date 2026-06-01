package io.github.dailystruggle.effectsapi.common.spi;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Platform-neutral target for a single {@link io.github.dailystruggle.effectsapi.Effect}
 * application. Wraps the {@link RTPPlayer} the effect is acting on (may be
 * {@code null} for ambient world effects) and the {@link RTPLocation} the
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

    private final @Nullable RTPPlayer player;
    private final @NotNull RTPLocation location;

    public EffectTarget(@Nullable RTPPlayer player, @NotNull RTPLocation location) {
        if (location == null) {
            throw new IllegalArgumentException("EffectTarget location shall not be null");
        }
        this.player = player;
        this.location = location;
    }

    /** The player the effect targets, or {@code null} for ambient world effects. */
    public @Nullable RTPPlayer player() {
        return player;
    }

    /** The location the effect is anchored to. Never {@code null}. */
    public @NotNull RTPLocation location() {
        return location;
    }

    @Override
    public String toString() {
        return "EffectTarget{player=" + (player == null ? "null" : player.uuid())
                + ", location=" + location + '}';
    }
}
