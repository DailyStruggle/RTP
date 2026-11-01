package io.github.dailystruggle.server.api;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Platform-neutral menu viewer permission, locale, and region descriptor probe contract (ADR-048).
 *
 * @governedBy ADR-048
 * @governedBy ADR-115
 */
public interface MenuContextAccessor {
  /**
   * Returns permission probe for menu visibility filtering (ADR-048).
   *
   * @param player player UUID
   * @return non-null permission predicate; defaults to always {@code false}
   */
  default Predicate<String> menuPermissionProbe(UUID player) {
    return node -> false;
  }

  /**
   * Returns a factory mapping a player {@link UUID} to its menu permission probe (ADR-048).
   *
   * @return non-null permission probe factory delegating to {@link #menuPermissionProbe(UUID)}
   */
  default Function<UUID, Predicate<String>> menuPermissionProbeFactory() {
    return this::menuPermissionProbe;
  }

  /**
   * Returns effective permissions snapshot for menu namespaces (ADR-048).
   *
   * @param player player UUID
   * @return permission set; empty if unresolved
   */
  default Set<String> menuEffectivePermissions(UUID player) {
    return Collections.emptySet();
  }

  /**
   * Returns BCP-47 locale tag for menu rendering (ADR-048).
   *
   * @param player player UUID
   * @return locale tag; defaults to {@code "en_us"}
   */
  default String menuLocale(UUID player) {
    return "en_us";
  }

  /**
   * Returns descriptor for player's current region/world (ADR-048).
   *
   * @param player player UUID
   * @return region descriptor; empty string if unknown
   */
  default String menuRegionDescriptor(UUID player) {
    return "";
  }

  /**
   * Opens an interactive written-book view for {@code player} without mutating server-side
   * inventory (ADR-035, ADR-044, ADR-048, ADR-049).
   *
   * @param player target online player UUID
   * @param book platform-neutral book specification
   * @return {@code true} if the book UI was opened for an online player; {@code false} when the
   *     recipient is console, offline, or the runtime carrier does not support book packets
   */
  default boolean openBook(UUID player, BookMenuSpec book) {
    return false;
  }

  /**
   * Sends a single chat line with per-fragment hover tooltips and click actions ({@code
   * RUN_COMMAND}, {@code SUGGEST_COMMAND}, {@code OPEN_URL}), degrading to plain colored chat text
   * for console or non-interactive runtimes (ADR-035, ADR-048, ADR-049).
   *
   * @param player target player UUID (or {@code null} / server console UUID)
   * @param line interactive line specification
   */
  default void sendInteractiveLine(UUID player, BookMenuSpec.Line line) {
    if (line == null) {
      return;
    }
    if (this instanceof ServerAccessor accessor) {
      accessor.sendMessage(player, line.plainText(), false);
    }
  }

  /**
   * Attempts to open {@code book} as an in-game written book via {@link #openBook(UUID,
   * BookMenuSpec)} and, if unavailable (console sender, offline player, or unsupported carrier),
   * renders all pages line-by-line via {@link #sendInteractiveLine(UUID, BookMenuSpec.Line)} so
   * hover and click affordances remain intact (ADR-035, ADR-048).
   *
   * @param player target player UUID (or {@code null} / console UUID)
   * @param book book specification to render
   */
  default void renderBookOrChat(UUID player, BookMenuSpec book) {
    if (book == null) {
      return;
    }
    if (player != null && openBook(player, book)) {
      return;
    }
    for (BookMenuSpec.Page page : book.pages()) {
      for (BookMenuSpec.Line line : page.lines()) {
        sendInteractiveLine(player, line);
      }
    }
  }
}
