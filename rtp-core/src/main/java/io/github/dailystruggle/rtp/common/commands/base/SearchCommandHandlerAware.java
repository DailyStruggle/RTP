package io.github.dailystruggle.rtp.common.commands.base;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Capability contract for command nodes accepting a dynamic search query handler.
 * Decouples menu wiring installers from concrete configuration search commands.
 */
public interface SearchCommandHandlerAware {
    /**
     * Installs or clears the search handler callback.
     *
     * @param handler callback receiving caller UUID and query string, or null to disable
     */
    void setSearchHandler(BiConsumer<UUID, String> handler);
}
