package io.github.dailystruggle.rtp.api.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Platform-neutral, immutable menu model (ADR-044).
 *
 * @param title plain-text title rendered as heading
 * @param pages ordered, non-empty list of pages
 * @governedBy ADR-035
 * @governedBy ADR-044
 * @governedBy ADR-115
 */
public record MenuModel(String title, List<MenuPage> pages) {

    public MenuModel {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(pages, "pages");
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("MenuModel must contain at least one page");
        }
        for (int i = 0; i < pages.size(); i++) {
            Objects.requireNonNull(pages.get(i), "pages[" + i + "]");
        }
        pages = List.copyOf(pages);
    }

    /**
     * Converts this model to a platform-neutral {@link
     * io.github.dailystruggle.menu.api.MenuDescriptor}.
     *
     * @return menu descriptor; never {@code null}
     */
    public io.github.dailystruggle.menu.api.MenuDescriptor toMenuDescriptor() {
        List<io.github.dailystruggle.menu.api.MenuDescriptor.Page> descriptorPages =
                new ArrayList<>(pages.size());
        for (MenuPage page : pages) {
            descriptorPages.add(page.toDescriptorPage());
        }
        return new io.github.dailystruggle.menu.api.MenuDescriptor(title, descriptorPages);
    }

    /**
     * Creates a {@link MenuModel} from a platform-neutral {@link
     * io.github.dailystruggle.menu.api.MenuDescriptor}.
     *
     * @param descriptor menu descriptor; must not be {@code null}
     * @return converted {@link MenuModel}
     */
    public static MenuModel fromMenuDescriptor(
            io.github.dailystruggle.menu.api.MenuDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        List<MenuPage> converted = new ArrayList<>(descriptor.pages().size());
        for (io.github.dailystruggle.menu.api.MenuDescriptor.Page page : descriptor.pages()) {
            converted.add(MenuPage.fromDescriptorPage(page));
        }
        return new MenuModel(descriptor.title(), converted);
    }
}
