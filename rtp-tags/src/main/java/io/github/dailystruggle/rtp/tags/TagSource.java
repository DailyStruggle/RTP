package io.github.dailystruggle.rtp.tags;

import java.util.List;

/**
 * A pull-based enumeration of tag files from some underlying store — a
 * filesystem tree, a jar entry, an in-memory fixture, or a data-pack archive.
 *
 * <p>Sources are ordered in the {@link TagResolver} input list; earlier sources
 * are loaded first. Sources with {@code replace=true} entries override earlier
 * sources' values for the same tag id. Because this safety-list compiler takes
 * the <b>union</b> of reachable materials across all sources (per Slice 3a
 * scope), data-pack ordering is advisory only — strict vanilla ordering is a
 * future-slice concern for non-safety use cases.
 *
 * <p>Implementations shall be thread-safe if they are intended to be shared
 * across resolver invocations; the bundled {@link DiskTagSource} is stateless
 * and therefore trivially thread-safe.
 */
public interface TagSource {

  /**
   * Returns all tag files this source can enumerate for the block tag
   * registry. Tag files for other registries (items, fluids, entity-types,
   * game-events) shall be ignored by this call — a caller interested in those
   * would use a different {@link TagSource} instance or, in a future slice, a
   * registry-parameterised variant.
   *
   * @return an immutable list of tag files; never {@code null}; may be empty.
   */
  List<TagFile> loadBlockTags();
}
