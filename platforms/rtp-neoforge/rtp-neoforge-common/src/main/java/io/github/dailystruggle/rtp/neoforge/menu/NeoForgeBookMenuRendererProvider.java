package io.github.dailystruggle.rtp.neoforge.menu;

import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.menu.MenuRendererProvider;
import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import io.github.dailystruggle.rtp.common.commands.menu.ChatMenuRenderer;

/**
 * {@link MenuRendererProvider} for the NeoForge {@code /rtp menu} renderer
 * ({@link NeoForgeBookMenuRenderer}), discovered via {@link java.util.ServiceLoader}
 * (see {@code META-INF/services/io.github.dailystruggle.rtp.api.menu.MenuRendererProvider}).
 *
 * <p>Completes the same {@code MenuRendererProvider} SPI the Paper adapter
 * implements on the Bukkit family, so {@code rtp-core}'s
 * {@code MenuBindingSupport} resolves the renderer identically on every platform
 * and honours the {@code menu.renderer} config id ({@code "book"}) here too
 * (ADR-070). The book renderer wraps the rtp-core {@link ChatMenuRenderer} as its
 * fallback on runtimes without the written-book modal.</p>
 */
public final class NeoForgeBookMenuRendererProvider implements MenuRendererProvider {

    @Override
    public String id() {
        return "book";
    }

    @Override
    public MenuRenderer create() {
        return new NeoForgeBookMenuRenderer(new ChatMenuRenderer());
    }

    @Override
    public PlatformFamily platformFamily() {
        return PlatformFamily.NEOFORGE;
    }
}
