package io.github.dailystruggle.rtp.api.menu;

import java.util.Optional;

/**
 * SPI for retrieving block comments associated with YAML configuration keys (ADR-044).
 *
 * <p>Decouples menu reflection from YAML substrate types.
 */
@FunctionalInterface
public interface YamlCommentLookup {

    /**
     * Resolves the block comment attached to a config parameter.
     *
     * @param fileBasename  config file basename without {@code .yml} (e.g. {@code "performance"})
     * @param dottedKeyPath canonical dotted parameter path (e.g. {@code "queue.threadCount"})
     * @return stripped comment text, or {@link Optional#empty()} if none attached
     */
    Optional<String> commentFor(String fileBasename, String dottedKeyPath);

    /** A lookup that always returns empty; useful as a default for non-config consumers. */
    YamlCommentLookup EMPTY = (file, key) -> Optional.empty();
}
