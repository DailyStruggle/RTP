package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.UUID;

/**
 * Platform-neutral view of an online player exposed by {@link StressHost}
 * (ADR-122 Decision 2).
 */
public interface StressPlayer {

    UUID uuid();

    String name();

    boolean isOnline();

    StressLocation location();

    default String worldName() {
        StressLocation loc = location();
        return loc != null && loc.world() != null ? loc.world() : "";
    }

    boolean hasPermission(String node);

    default boolean isOp() {
        return false;
    }

    default void setOp(boolean value) {
    }

    default void kick(String reason) {
    }

    default boolean performCommand(String command) {
        return false;
    }

    default void capDistances(int distance) {
    }
}
