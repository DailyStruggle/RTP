package io.github.dailystruggle.rtp.paper.menu;

import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.menu.MenuRendererProvider;
import io.github.dailystruggle.rtp.api.server.PlatformFamily;

/**
 * {@link MenuRendererProvider} for the Paper Adventure-{@code Book} renderer
 * ({@link BookMenuRenderer}), discovered via {@link java.util.ServiceLoader}
 * (see {@code META-INF/services/io.github.dailystruggle.rtp.api.menu.MenuRendererProvider}).
 *
 * <p>This is the typed, discoverable replacement for the former
 * {@code Class.forName("...BookMenuRenderer")} lookup in the Bukkit bootstrap:
 * the renderer class is only present on the runtime classpath when a Paper
 * adapter is shaded in, and {@code ServiceLoader} surfaces this provider exactly
 * in that case.</p>
 */
public final class PaperBookMenuRendererProvider implements MenuRendererProvider {

    @Override
    public String id() {
        return "book";
    }

    @Override
    public MenuRenderer create() {
        return new BookMenuRenderer();
    }

    @Override
    public PlatformFamily platformFamily() {
        return PlatformFamily.BUKKIT;
    }
}
