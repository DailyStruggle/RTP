package io.github.dailystruggle.rtp.fabric.menu;

import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.menu.MenuRendererProvider;
import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import io.github.dailystruggle.rtp.common.commands.menu.ChatMenuRenderer;

/**
 * {@link MenuRendererProvider} for the Fabric {@code /rtp menu} renderer
 * ({@link FabricBookMenuRenderer}), discovered via {@link java.util.ServiceLoader}
 * (see {@code META-INF/services/io.github.dailystruggle.rtp.api.menu.MenuRendererProvider}).
 *
 * <p>Completes the same {@code MenuRendererProvider} SPI the Paper adapter
 * implements on the Bukkit family, so {@code rtp-core}'s
 * {@code MenuBindingSupport} resolves the renderer identically on every platform
 * and honours the {@code menu.renderer} config id ({@code "book"}) here too
 * (ADR-070). The book renderer wraps the rtp-core {@link ChatMenuRenderer} as its
 * transparent fallback on runtimes without the written-book modal (1.20.x).</p>
 */
public final class FabricBookMenuRendererProvider implements MenuRendererProvider {

    @Override
    public String id() {
        return "book";
    }

    @Override
    public MenuRenderer create() {
        return new FabricBookMenuRenderer(new ChatMenuRenderer());
    }

    @Override
    public PlatformFamily platformFamily() {
        return PlatformFamily.FABRIC;
    }
}
