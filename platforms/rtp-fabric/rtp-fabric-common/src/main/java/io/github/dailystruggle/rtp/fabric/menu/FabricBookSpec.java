package io.github.dailystruggle.rtp.fabric.menu;

import java.util.List;

/**
 * Platform-neutral, fully-formatted page model handed from
 * {@link FabricBookMenuRenderer} (which lives in {@code rtp-fabric-common} and
 * carries no {@code net.minecraft.*} binding) to the per-version
 * {@link io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter#openBookMenu
 * openBookMenu} carrier method, which binds the spec to that MC version's
 * {@code WrittenBookContent} / {@code ClientboundOpenBookPacket} types
 * (rtp-fabric-ADR-012 §4).
 *
 * <p>All text is already run through
 * {@code RTPServerAccessor.format(uuid, raw)} (placeholders + legacy
 * {@code &}/{@code §} colour codes); the carrier feeds each fragment straight
 * into {@code FabricLegacyText.parseInteractive(text, hover, runCommand, RUN)}
 * so the {@code MenuModel} → {@code Component} colour/hover/click translation
 * is not re-implemented per version.
 *
 * <p>Structure: a book is a {@link #title()} plus an ordered list of
 * {@link Page pages}; each page is an ordered list of {@link Line lines};
 * each line is an ordered list of {@link Fragment fragments}. The carrier
 * joins lines with {@code "\n"} and concatenates fragments within a line.
 */
public final class FabricBookSpec {

    /** A single styled, optionally-clickable run of text. */
    public record Fragment(String text, String hover, String runCommand) {}

    /** One book line: an ordered list of fragments. */
    public record Line(List<Fragment> fragments) {}

    /** One book page: an ordered list of lines. */
    public record Page(List<Line> lines) {}

    private final String title;
    private final List<Page> pages;

    public FabricBookSpec(String title, List<Page> pages) {
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
