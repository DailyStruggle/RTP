package io.github.dailystruggle.rtp.api.menu;

import java.util.List;
import java.util.Objects;

/**
 * Platform-neutral, fully-formatted page model for written-book menu renderers.
 *
 * <p>Structure: a book has a {@link #title()} plus an ordered list of
 * {@link Page pages}; each page is an ordered list of {@link Line lines};
 * each line is an ordered list of {@link Fragment fragments}.
 */
public class BookSpec {

  /** A single styled, optionally-clickable run of text. */
  public record Fragment(String text, String hover, String runCommand) {}

  /** One book line: an ordered list of fragments. */
  public record Line(List<Fragment> fragments) {
    public Line {
      fragments = (fragments == null) ? List.of() : List.copyOf(fragments);
    }
  }

  /** One book page: an ordered list of lines. */
  public record Page(List<Line> lines) {
    public Page {
      lines = (lines == null) ? List.of() : List.copyOf(lines);
    }
  }

  private final String title;
  private final List<Page> pages;

  public BookSpec(String title, List<Page> pages) {
    this.title = (title == null) ? "RTP" : title;
    this.pages = (pages == null) ? List.of() : List.copyOf(pages);
  }

  public String title() {
    return title;
  }

  public List<Page> pages() {
    return pages;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof BookSpec bookSpec)) return false;
    return Objects.equals(title, bookSpec.title) && Objects.equals(pages, bookSpec.pages);
  }

  @Override
  public int hashCode() {
    return Objects.hash(title, pages);
  }

  @Override
  public String toString() {
    return "BookSpec{title='" + title + "', pages=" + pages.size() + "}";
  }
}
