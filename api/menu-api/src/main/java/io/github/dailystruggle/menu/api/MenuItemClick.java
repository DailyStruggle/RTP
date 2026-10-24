package io.github.dailystruggle.menu.api;

import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable context describing a viewer's click interaction within a rendered menu.
 *
 * @param viewer clicking player UUID; never {@code null}
 * @param pageIndex zero-based page index ({@code >= 0})
 * @param slot zero-based slot index, or {@code -1} for non-slotted text/book fragment clicks
 * @param clickType interaction gesture classification; never {@code null}
 * @param action bound declarative {@link MenuAction}, or {@code null} if none
 */
public record MenuItemClick(
    UUID viewer,
    int pageIndex,
    int slot,
    ClickType clickType,
    @Nullable MenuAction action) {

  /** Classification of the viewer's click gesture. */
  public enum ClickType {
    LEFT,
    RIGHT,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    MIDDLE,
    DROP,
    UNKNOWN
  }

  public MenuItemClick {
    Objects.requireNonNull(viewer, "viewer");
    Objects.requireNonNull(clickType, "clickType");
    if (pageIndex < 0) {
      throw new IllegalArgumentException("pageIndex must be >= 0, got " + pageIndex);
    }
    if (slot < -1) {
      throw new IllegalArgumentException("slot must be >= -1, got " + slot);
    }
  }

  /** Convenience factory for a slot-indexed GUI item click. */
  public static MenuItemClick ofSlot(
      UUID viewer, int pageIndex, int slot, ClickType clickType, @Nullable MenuAction action) {
    if (slot < 0) {
      throw new IllegalArgumentException("slot must be >= 0 for slot click, got " + slot);
    }
    return new MenuItemClick(viewer, pageIndex, slot, clickType, action);
  }

  /** Convenience factory for a non-slotted book or chat fragment click. */
  public static MenuItemClick ofFragment(
      UUID viewer, int pageIndex, @Nullable MenuAction action) {
    return new MenuItemClick(viewer, pageIndex, -1, ClickType.LEFT, action);
  }
}
