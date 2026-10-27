package io.github.dailystruggle.rtp.api.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One page of a {@link MenuModel}, composed of {@link MenuLine}s.
 *
 * <p>The line list is defensively copied at construction and exposed via an
 * unmodifiable view; instances are deeply immutable.
 *
 * @governedBy ADR-035
 * @governedBy ADR-044
 * @governedBy ADR-115
 */
public record MenuPage(List<MenuLine> lines) {

    public MenuPage {
        Objects.requireNonNull(lines, "lines");
        for (int i = 0; i < lines.size(); i++) {
            Objects.requireNonNull(lines.get(i), "lines[" + i + "]");
        }
        lines = List.copyOf(lines);
    }

    /**
     * Converts this page to a platform-neutral {@link
     * io.github.dailystruggle.menu.api.MenuDescriptor.Page}.
     *
     * @return descriptor page; never {@code null}
     */
    public io.github.dailystruggle.menu.api.MenuDescriptor.Page toDescriptorPage() {
        List<io.github.dailystruggle.menu.api.MenuDescriptor.Line> descriptorLines =
                new ArrayList<>(lines.size());
        for (MenuLine line : lines) {
            descriptorLines.add(line.toDescriptorLine());
        }
        return new io.github.dailystruggle.menu.api.MenuDescriptor.Page(descriptorLines);
    }

    /**
     * Creates a {@link MenuPage} from a platform-neutral {@link
     * io.github.dailystruggle.menu.api.MenuDescriptor.Page}.
     *
     * @param page descriptor page; must not be {@code null}
     * @return converted {@link MenuPage}
     */
    public static MenuPage fromDescriptorPage(
            io.github.dailystruggle.menu.api.MenuDescriptor.Page page) {
        Objects.requireNonNull(page, "page");
        List<MenuLine> converted = new ArrayList<>(page.lines().size());
        for (io.github.dailystruggle.menu.api.MenuDescriptor.Line line : page.lines()) {
            converted.add(MenuLine.fromDescriptorLine(line));
        }
        return new MenuPage(converted);
    }
}
