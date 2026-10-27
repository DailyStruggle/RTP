package io.github.dailystruggle.rtp.api.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One horizontal line of a {@link MenuPage}, composed of {@link MenuFragment}s.
 *
 * <p>The fragment list is defensively copied at construction and exposed via
 * an unmodifiable view; instances are deeply immutable.
 *
 * @governedBy ADR-035
 * @governedBy ADR-044
 * @governedBy ADR-115
 */
public record MenuLine(List<MenuFragment> fragments) {

    public MenuLine {
        Objects.requireNonNull(fragments, "fragments");
        for (int i = 0; i < fragments.size(); i++) {
            Objects.requireNonNull(fragments.get(i), "fragments[" + i + "]");
        }
        fragments = List.copyOf(fragments);
    }

    /** Convenience factory for a single-fragment line. */
    public static MenuLine of(MenuFragment fragment) {
        return new MenuLine(List.of(fragment));
    }

    /**
     * Converts this line to a platform-neutral {@link
     * io.github.dailystruggle.menu.api.MenuDescriptor.Line}.
     *
     * @return descriptor line; never {@code null}
     */
    public io.github.dailystruggle.menu.api.MenuDescriptor.Line toDescriptorLine() {
        List<io.github.dailystruggle.menu.api.MenuDescriptor.Fragment> descriptorFragments =
                new ArrayList<>(fragments.size());
        for (MenuFragment fragment : fragments) {
            descriptorFragments.add(fragment.toDescriptorFragment());
        }
        return new io.github.dailystruggle.menu.api.MenuDescriptor.Line(descriptorFragments);
    }

    /**
     * Creates a {@link MenuLine} from a platform-neutral {@link
     * io.github.dailystruggle.menu.api.MenuDescriptor.Line}.
     *
     * @param line descriptor line; must not be {@code null}
     * @return converted {@link MenuLine}
     */
    public static MenuLine fromDescriptorLine(
            io.github.dailystruggle.menu.api.MenuDescriptor.Line line) {
        Objects.requireNonNull(line, "line");
        List<MenuFragment> converted = new ArrayList<>(line.fragments().size());
        for (io.github.dailystruggle.menu.api.MenuDescriptor.Fragment fragment : line.fragments()) {
            converted.add(MenuFragment.fromDescriptorFragment(fragment));
        }
        return new MenuLine(converted);
    }
}
