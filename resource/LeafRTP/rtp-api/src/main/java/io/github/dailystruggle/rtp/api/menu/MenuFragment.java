package io.github.dailystruggle.rtp.api.menu;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Plain-text styled run within a {@link MenuLine} (ADR-035).
 *
 * @param text   visible text; never {@code null}
 * @param hover  optional tooltip shown on cursor hover (ADR-044), or {@code null}
 * @param action optional click action, or {@code null} if purely decorative
 * @governedBy ADR-035
 * @governedBy ADR-044
 * @governedBy ADR-115
 */
public record MenuFragment(String text, @Nullable String hover, @Nullable MenuAction action) {

    public MenuFragment {
        Objects.requireNonNull(text, "text");
    }

    /** Convenience factory for a decorative (non-clickable, no-hover) fragment. */
    public static MenuFragment plain(String text) {
        return new MenuFragment(text, null, null);
    }

    /**
     * Converts this fragment to a platform-neutral {@link
     * io.github.dailystruggle.menu.api.MenuDescriptor.Fragment}.
     *
     * @return descriptor fragment; never {@code null}
     */
    public io.github.dailystruggle.menu.api.MenuDescriptor.Fragment toDescriptorFragment() {
        return new io.github.dailystruggle.menu.api.MenuDescriptor.Fragment(
                text, hover, action == null ? null : action.toDescriptorAction());
    }

    /**
     * Creates a {@link MenuFragment} from a platform-neutral {@link
     * io.github.dailystruggle.menu.api.MenuDescriptor.Fragment}.
     *
     * @param fragment descriptor fragment; must not be {@code null}
     * @return converted {@link MenuFragment}
     */
    public static MenuFragment fromDescriptorFragment(
            io.github.dailystruggle.menu.api.MenuDescriptor.Fragment fragment) {
        Objects.requireNonNull(fragment, "fragment");
        return new MenuFragment(
                fragment.text(),
                fragment.hover(),
                fragment.action() == null ? null : MenuAction.fromDescriptorAction(fragment.action()));
    }
}
