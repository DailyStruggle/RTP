package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import io.github.dailystruggle.rtp.api.editor.EditorExtension;

import java.util.List;
import java.util.Set;

/**
 * Multi-binding SPI for web editor extensions (ADR-107 §4.1).
 *
 * <p>Registration takes effect at the next editor session: a session's extension list is fixed
 * when its snapshot is built. Unregistering closes the extension's part of any open session and
 * drops its inbound types from then on. Thread-safe.
 */
@PublicApi
public interface EditorExtensionRegistry {

  /** Extension id grammar; ids are namespaces for {@code <id>.<type>} messages. */
  String ID_REGEX = "[a-z][a-z0-9\\-]{1,23}";

  /** Ids core keeps for itself. */
  Set<String> RESERVED_IDS = Set.of("rtp", "core", "editor", "bundle", "ext", "sys");

  /**
   * Adds {@code extension}.
   *
   * @throws IllegalArgumentException when its id does not match {@link #ID_REGEX}, is reserved,
   *     or its declared types, tabs or files are invalid
   * @throws IllegalStateException when another extension already holds the id
   */
  void register(EditorExtension extension);

  /** @return {@code true} if an extension with {@code id} was removed. */
  boolean unregister(String id);

  /** @return registered extensions in id order; an immutable snapshot. */
  List<EditorExtension> registered();
}
