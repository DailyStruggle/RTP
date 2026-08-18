package io.github.dailystruggle.rtp.neoforge.menu;

import java.util.List;

/**
 * Platform-neutral, fully-formatted page model handed from
 * {@link NeoForgeBookMenuRenderer} (which carries no {@code net.minecraft.*}
 * binding) to the per-version
 * {@link io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapter#openBookMenu
 * openBookMenu} carrier method, which binds the spec to that MC version's
 * {@code WrittenBookContent} / {@code ClientboundOpenBookPacket} types (NeoForge
 * analogue of {@code FabricBookSpec}).
 *
 * <p>All text is already run through {@code RTPServerAccessor.format(uuid, raw)}
 * (placeholders + legacy {@code &}/{@code section } colour codes); the carrier feeds
 * each fragment straight into
 * {@code NeoForgeLegacyText.parseInteractive(text, hover, runCommand, RUN)} so
 * the {@code MenuModel} -&gt; {@code Component} colour/hover/click translation is
 * not re-implemented per version.
 *
 * <p>Structure: a book is a {@link #title()} plus an ordered list of
 * {@link Page pages}; each page is an ordered list of {@link Line lines};
 * each line is an ordered list of {@link Fragment fragments}. The carrier
 * joins lines with {@code "\n"} and concatenates fragments within a line.
 */
public final class NeoForgeBookSpec {

    /** A single styled, optionally-clickable run of text. */
    public record Fragment(String text, String hover, String runCommand) {}

    /** One book line: an ordered list of fragments. */
    public record Line(List<Fragment> fragments) {}

    /** One book page: an ordered list of lines. */
    public record Page(List<Line> lines) {}

    private final String title;
    private final List<Page> pages;

    public NeoForgeBookSpec(String title, List<Page> pages) {
        this.title = (title == null) ? "RTP" : title;
        this.pages = List.copyOf(pages);
    }

    public String title() {
        return title;
    }

    public List<Page> pages() {
        return pages;
    }
}
