package io.github.dailystruggle.effectsapi.common.spi;

import io.github.dailystruggle.server.api.ServerAccessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Platform-neutral target for a single {@link io.github.dailystruggle.effectsapi.common.Effect}
 * application. Wraps the player the effect is acting on (may be
 * {@code null} for ambient world effects) and the location the
 * effect is anchored to (e.g. the destination of a teleport for the
 * {@code postteleport} hook).
 *
 * <p>Per {@code effects-api-ADR-003} and {@code ADR-116}, this type is the platform-neutral
 * carrier passed across the effect runtime surface; target wrapping and command dispatch
 * route through {@link ServerAccessors}.</p>
 *
 * <p>Immutable.</p>
 */
public final class EffectTarget {
    private final @Nullable PlayerHandle player;
    private final @NotNull LocationHandle location;
    private final @Nullable LocationHandle destination;

    public EffectTarget(@Nullable Object player, @NotNull Object location) {
        this(player, location, null);
    }

    public EffectTarget(@Nullable Object player, @NotNull Object location, @Nullable Object destination) {
        this.player = player == null ? null : wrapPlayer(player);
        LocationHandle loc = wrapLocation(location);
        if (loc == null) {
            throw new IllegalArgumentException("EffectTarget location shall not be null or unresolvable");
        }
        this.location = loc;
        this.destination = destination == null ? null : wrapLocation(destination);
    }

    /**
     * Resolves a platform-neutral {@link PlayerHandle} from a raw player object,
     * {@link EffectTarget}, or existing {@link PlayerHandle} via {@link ServerAccessors}
     * (ADR-116 Decision 2).
     */
    public static @Nullable PlayerHandle wrapPlayer(@Nullable Object player) {
        if (player == null) return null;
        if (player instanceof EffectTarget et) return et.player();
        if (player instanceof PlayerHandle ph) return ph;
        Object wrapped = ServerAccessors.wrapEffectPlayer(player);
        return wrapped instanceof PlayerHandle ph ? ph : null;
    }

    /**
     * Resolves a platform-neutral {@link LocationHandle} from a raw location/entity object,
     * {@link EffectTarget}, or existing {@link LocationHandle} via {@link ServerAccessors}
     * (ADR-116 Decision 2).
     */
    public static @Nullable LocationHandle wrapLocation(@Nullable Object location) {
        if (location == null) return null;
        if (location instanceof EffectTarget et) return et.location();
        if (location instanceof LocationHandle lh) return lh;
        Object wrapped = ServerAccessors.wrapEffectLocation(location);
        return wrapped instanceof LocationHandle lh ? lh : null;
    }

    /**
     * Resolves the online player standing at {@code location}, if any, via
     * {@link ServerAccessors} (ADR-116 Decision 2).
     */
    public static @Nullable PlayerHandle playerAt(@Nullable LocationHandle location) {
        if (location == null) return null;
        Object wrapped = ServerAccessors.resolveEffectPlayerAt(location);
        return wrapped instanceof PlayerHandle ph ? ph : null;
    }

    /**
     * Dispatches a console command via {@link ServerAccessors} (ADR-116 Decision 2).
     */
    public static void dispatchConsoleCommand(@NotNull String command) {
        ServerAccessors.dispatchConsoleCommand(command);
    }

    /**
     * Dispatches a player command via {@link PlayerHandle#performCommand(String)}
     * (ADR-116 Decision 2).
     */
    public static void dispatchPlayerCommand(@NotNull PlayerHandle player, @NotNull String command) {
        player.performCommand(command);
    }

    /** The player the effect targets, or {@code null} for ambient world effects. */
    public @Nullable PlayerHandle player() {
        return player;
    }

    /** The location the effect is anchored to (e.g. origin/current location). Never {@code null}. */
    public @NotNull LocationHandle location() {
        return location;
    }

    /** The destination of a teleport/transition, if available. */
    public @Nullable LocationHandle destination() {
        return destination;
    }

    @Override
    public String toString() {
        return "EffectTarget{player=" + (player == null ? "null" : player.uuid())
                + ", location=" + location
                + ", destination=" + destination + '}';
    }
}
