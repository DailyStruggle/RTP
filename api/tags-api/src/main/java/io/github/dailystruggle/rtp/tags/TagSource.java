package io.github.dailystruggle.rtp.tags;

import java.util.List;

/**
 * A pull-based source of tag files (e.g., filesystem, jar, in-memory).
 *
 * <p>Sources are ordered; {@code replace=true} overrides previous values.
 * The resolver computes the union of all sources.
 *
 * <p>Implementations should be thread-safe.
 */
public interface TagSource {

  /**
   * Returns all tag files this source can enumerate for the block tag
   * registry. Tag files for other registries (items, fluids, entity-types,
   * game-events) shall be ignored by this call - a caller interested in those
   * would use a different {@link TagSource} instance or, in a future slice, a
   * registry-parameterised variant.
   *
   * @return an immutable list of tag files; never {@code null}; may be empty.
   */
  List<TagFile> loadBlockTags();
}
