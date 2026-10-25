package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.UUID;

/**
 * Platform-neutral command sender for {@code /rtpstress} (ADR-122 Decision 2).
 */
public interface StressSender {

    String name();

    default UUID playerUuid() {
        return null;
    }

    default boolean isPlayer() {
        return playerUuid() != null;
    }

    boolean hasPermission(String node);

    void sendMessage(String message);
}
