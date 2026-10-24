package io.github.dailystruggle.menu.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Platform-neutral, immutable declarative menu descriptor (ADR-035, ADR-044).
 *
 * <p>Supports both line/fragment-oriented renderers (books, chat) and slot/item-oriented
 * renderers (chest/inventory GUIs) with optional filler and style metadata.
 *
 * @param title plain-text or formatted menu title; never {@code null}
 * @param rows logical row count per page ({@code >= 1}, default {@code 6})
 * @param pages ordered, non-empty list of pages
 * @param styleMetadata immutable key-value style/filler metadata
 */
public record MenuDescriptor(
    String title, int rows, List<Page> pages, Map<String, String> styleMetadata) {

  /** Default row count per page. */
  public static final int DEFAULT_ROWS = 6;

  public MenuDescriptor {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(pages, "pages");
    Objects.requireNonNull(styleMetadata, "styleMetadata");
    if (rows < 1) {
      throw new IllegalArgumentException("rows must be >= 1, got " + rows);
    }
    if (pages.isEmpty()) {
      throw new IllegalArgumentException("MenuDescriptor must contain at least one page");
    }
    for (int i = 0; i < pages.size(); i++) {
      Objects.requireNonNull(pages.get(i), "pages[" + i + "]");
    }
    pages = List.copyOf(pages);
    styleMetadata = Map.copyOf(styleMetadata);
  }

  /** Convenience constructor with default row count and empty style metadata. */
  public MenuDescriptor(String title, List<Page> pages) {
    this(title, DEFAULT_ROWS, pages, Map.of());
  }

  /**
   * Returns a new builder initialized with the given title.
   *
   * @param title menu title; must not be {@code null}
   * @return builder instance
   */
  public static Builder builder(String title) {
    return new Builder(title);
  }

  /**
   * Styled run of text within a {@link Line}.
   *
   * @param text visible text; never {@code null}
   * @param hover optional tooltip text, or {@code null}
   * @param action optional click action, or {@code null} if decorative
   */
  public record Fragment(String text, @Nullable String hover, @Nullable MenuAction action) {
    public Fragment {
      Objects.requireNonNull(text, "text");
    }

    /** Convenience factory for a decorative fragment without hover or click action. */
    public static Fragment plain(String text) {
      return new Fragment(text, null, null);
    }
  }

  /**
   * One horizontal line on a {@link Page}, composed of ordered {@link Fragment}s.
   *
   * @param fragments ordered list of fragments; defensively copied
   */
  public record Line(List<Fragment> fragments) {
    public Line {
      Objects.requireNonNull(fragments, "fragments");
      for (int i = 0; i < fragments.size(); i++) {
        Objects.requireNonNull(fragments.get(i), "fragments[" + i + "]");
      }
      fragments = List.copyOf(fragments);
    }

    /** Convenience factory for a single-fragment line. */
    public static Line of(Fragment fragment) {
      return new Line(List.of(fragment));
    }
  }

  /**
   * Slot-addressed item on a {@link Page} for inventory/chest renderers.
   *
   * @param slot zero-based slot index ({@code >= 0})
   * @param label display name; never {@code null}
   * @param lore immutable list of lore lines; never {@code null}
   * @param iconKey optional platform-neutral material/icon identifier, or {@code null}
   * @param action optional click action, or {@code null}
   */
  public record Item(
      int slot,
      String label,
      List<String> lore,
      @Nullable String iconKey,
      @Nullable MenuAction action) {
    public Item {
      if (slot < 0) {
        throw new IllegalArgumentException("slot must be >= 0, got " + slot);
      }
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(lore, "lore");
      for (int i = 0; i < lore.size(); i++) {
        Objects.requireNonNull(lore.get(i), "lore[" + i + "]");
      }
      lore = List.copyOf(lore);
    }
  }

  /**
   * One page of a {@link MenuDescriptor}, carrying ordered lines and/or slot-indexed items.
   *
   * @param lines ordered text lines; defensively copied
   * @param items ordered slot items; defensively copied
   */
  public record Page(List<Line> lines, List<Item> items) {
    public Page {
      Objects.requireNonNull(lines, "lines");
      Objects.requireNonNull(items, "items");
      for (int i = 0; i < lines.size(); i++) {
        Objects.requireNonNull(lines.get(i), "lines[" + i + "]");
      }
      for (int i = 0; i < items.size(); i++) {
        Objects.requireNonNull(items.get(i), "items[" + i + "]");
      }
      lines = List.copyOf(lines);
      items = List.copyOf(items);
    }

    /** Convenience constructor for a line-based page with no slot items. */
    public Page(List<Line> lines) {
      this(lines, List.of());
    }

    /** Convenience factory for a slot-item page with no text lines. */
    public static Page ofItems(List<Item> items) {
      return new Page(List.of(), items);
    }
  }

  /** Fluent builder for {@link MenuDescriptor}. */
  public static final class Builder {
    private final String title;
    private int rows = DEFAULT_ROWS;
    private final List<Page> pages = new ArrayList<>();
    private final Map<String, String> styleMetadata = new LinkedHashMap<>();

    private Builder(String title) {
      this.title = Objects.requireNonNull(title, "title");
    }

    public Builder rows(int rows) {
      if (rows < 1) {
        throw new IllegalArgumentException("rows must be >= 1, got " + rows);
      }
      this.rows = rows;
      return this;
    }

    public Builder addPage(Page page) {
      this.pages.add(Objects.requireNonNull(page, "page"));
      return this;
    }

    public Builder style(String key, String value) {
      this.styleMetadata.put(
          Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
      return this;
    }

    public MenuDescriptor build() {
      return new MenuDescriptor(title, rows, pages, styleMetadata);
    }
  }
}
