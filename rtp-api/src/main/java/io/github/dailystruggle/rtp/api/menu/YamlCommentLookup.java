package io.github.dailystruggle.rtp.api.menu;

import java.util.Optional;

/**
 * SPI for retrieving the block comment associated with a YAML key.
 *
 * <p>Decouples the menu reflector (ADR-044) from the in-house YAML substrate
 * ({@code rtp-core/.../configuration/yaml/RtpYamlSection#getComment}) so that
 * {@code rtp-api} carries no dependency on {@code rtp-core} or the substrate's
 * implementation types. ADR-042 guarantees that block comments survive the
 * load → mutate → save round-trip; this interface is the read-side surface.
 *
 * <p>Implementations are expected to be cheap and lock-free: the reflector
 * invokes one lookup per leaf parameter at mint time, so a small map fetch
 * keyed by {@code (fileBasename, dottedKeyPath)} is the norm.
 */
@FunctionalInterface
public interface YamlCommentLookup {

    /**
     * Resolve the block comment attached to a parameter in a config file.
     *
     * @param fileBasename  config file basename without {@code .yml} (e.g.
     *                      {@code "performance"}, {@code "config"}).
     * @param dottedKeyPath canonical dotted path to the parameter (e.g.
     *                      {@code "queue.threadCount"}).
     * @return the comment text with leading {@code #} markers stripped and
     *         lines joined by newline, or {@link Optional#empty()} when no
     *         comment is attached. Producers should fall back to declared
     *         type + bounds when empty.
     */
    Optional<String> commentFor(String fileBasename, String dottedKeyPath);

    /** A lookup that always returns empty; useful as a default for non-config consumers. */
    YamlCommentLookup EMPTY = (file, key) -> Optional.empty();
}
