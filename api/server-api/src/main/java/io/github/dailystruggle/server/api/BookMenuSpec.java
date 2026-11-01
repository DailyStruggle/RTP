package io.github.dailystruggle.server.api;

import java.util.List;
import java.util.Objects;

/**
 * Platform-neutral written-book and interactive chat-line specification consumed by {@link
 * MenuContextAccessor#openBook} and {@link MenuContextAccessor#sendInteractiveLine} (ADR-035,
 * ADR-044, ADR-048, ADR-049, ADR-115).
 *
 * <p>Text fields carry legacy {@code &}-coded strings (plus optional {@code #RRGGBB} hex tags) so
 * each platform carrier can translate them through its native component pipeline (Adventure on
 * Paper/Folia, BungeeChat on Spigot, Mojang {@code Component} + {@code WrittenBookContent} on
 * Fabric and NeoForge) without leaking platform UI imports into plugin modules.
 *
 * @param title book title (legacy colour codes permitted)
 * @param author book author credit (defaults to {@code "Leaf"} when blank)
 * @param pages ordered pages; empty books are padded with a single blank page by carriers
 * @governedBy ADR-035
 * @governedBy ADR-044
 * @governedBy ADR-048
 * @governedBy ADR-049
 * @governedBy ADR-115
 * @trace REQ-SLEUTH-UI-001
 */
public record BookMenuSpec(String title, String author, List<Page> pages) {

  public BookMenuSpec {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(pages, "pages");
    author = (author == null || author.isBlank()) ? "Leaf" : author;
    pages = List.copyOf(pages);
  }

  /** Convenience constructor defaulting {@code author} to {@code "Leaf"}. */
  public BookMenuSpec(String title, List<Page> pages) {
    this(title, "Leaf", pages);
  }

  /** Supported click-action kinds on a {@link Fragment}. */
  public enum ClickAction {
    NONE,
    RUN_COMMAND,
    SUGGEST_COMMAND,
    CHANGE_PAGE,
    OPEN_URL
  }

  /**
   * Single page of a {@link BookMenuSpec}, composed of ordered {@link Line}s joined with {@code
   * '\n'}.
   *
   * @param lines ordered lines on this page
   */
  public record Page(List<Line> lines) {
    public Page {
      Objects.requireNonNull(lines, "lines");
      lines = List.copyOf(lines);
    }

    public static Page of(Line... lines) {
      return new Page(List.of(lines));
    }
  }

  /**
   * Single horizontal line of a book page or interactive chat message, composed of contiguous
   * {@link Fragment}s.
   *
   * @param fragments ordered text/hover/click segments
   */
  public record Line(List<Fragment> fragments) {
    public Line {
      Objects.requireNonNull(fragments, "fragments");
      fragments = List.copyOf(fragments);
    }

    public static Line empty() {
      return new Line(List.of());
    }

    public static Line plain(String text) {
      return new Line(List.of(Fragment.plain(text)));
    }

    public static Line of(Fragment... fragments) {
      return new Line(List.of(fragments));
    }

    /** Returns the concatenated legacy-formatted text of all fragments on this line. */
    public String plainText() {
      if (fragments.isEmpty()) {
        return "";
      }
      StringBuilder sb = new StringBuilder();
      for (Fragment f : fragments) {
        sb.append(f.text());
      }
      return sb.toString();
    }
  }

  /**
   * Contiguous styled text segment with optional hover tooltip and click action.
   *
   * @param text legacy-formatted display text
   * @param hover optional legacy-formatted hover tooltip ({@code null} or empty for none)
   * @param clickAction click action kind (defaults to {@link ClickAction#NONE} when {@code null})
   * @param clickValue command string, URL, or 0-based page index string for the click action
   */
  public record Fragment(
      String text, String hover, ClickAction clickAction, String clickValue) {
    public Fragment {
      Objects.requireNonNull(text, "text");
      if (hover != null && hover.isEmpty()) {
        hover = null;
      }
      if (clickValue != null && clickValue.isEmpty()) {
        clickValue = null;
      }
      if (clickAction == null || clickValue == null) {
        clickAction = ClickAction.NONE;
        clickValue = null;
      }
    }

    public static Fragment plain(String text) {
      return new Fragment(text, null, ClickAction.NONE, null);
    }

    public static Fragment withHover(String text, String hover) {
      return new Fragment(text, hover, ClickAction.NONE, null);
    }

    public static Fragment runCommand(String text, String hover, String command) {
      return new Fragment(text, hover, ClickAction.RUN_COMMAND, command);
    }

    public static Fragment suggestCommand(String text, String hover, String prefix) {
      return new Fragment(text, hover, ClickAction.SUGGEST_COMMAND, prefix);
    }

    public static Fragment changePage(String text, String hover, int zeroBasedPageIndex) {
      return new Fragment(
          text,
          hover,
          ClickAction.CHANGE_PAGE,
          Integer.toString(Math.max(0, zeroBasedPageIndex)));
    }

    public static Fragment openUrl(String text, String hover, String url) {
      return new Fragment(text, hover, ClickAction.OPEN_URL, url);
    }
  }
}
