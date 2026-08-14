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
 *
 * <p>The declared {@link PlatformFamily#BUKKIT} family also matches plain
 * Spigot, which does <em>not</em> ship the Adventure API that
 * {@link BookMenuRenderer} links against. Rather than let the eager
 * {@code new BookMenuRenderer()} surface a {@link NoClassDefFoundError} at the
 * caller, {@link #create()} first probes for Adventure and returns {@code null}
 * when it is absent, so menu discovery degrades cleanly to the configurable
 * {@code menuInvalid} fallback.</p>
 */
public final class PaperBookMenuRendererProvider implements MenuRendererProvider {

    /** Adventure marker type the {@link BookMenuRenderer} requires at runtime. */
    private static final String ADVENTURE_COMPONENT =
            "net.kyori.adventure.text.Component";

    @Override
    public String id() {
        return "book";
    }

    @Override
    public MenuRenderer create() {
        // The BUKKIT family matches plain Spigot too, where Adventure is not on
        // the classpath. Probe before instantiating so we degrade to null (the
        // menuInvalid fallback) instead of throwing NoClassDefFoundError.
        if (!adventureAvailable()) {
            return null;
        }
        return new BookMenuRenderer();
    }

    private static boolean adventureAvailable() {
        try {
            Class.forName(
                    ADVENTURE_COMPONENT,
                    false,
                    PaperBookMenuRendererProvider.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }

    @Override
    public PlatformFamily platformFamily() {
        return PlatformFamily.BUKKIT;
    }
}
