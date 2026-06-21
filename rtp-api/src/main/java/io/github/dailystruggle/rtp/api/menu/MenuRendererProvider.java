package io.github.dailystruggle.rtp.api.menu;

import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import org.jetbrains.annotations.Nullable;

/**
 * Service-discovery provider for a {@link MenuRenderer}.
 *
 * <p>Replaces the former classpath-conditional {@code Class.forName} lookup that
 * the Bukkit family used to resolve the Paper {@code BookMenuRenderer}: a single
 * Bukkit-family jar must run on plain Spigot <em>and</em> Paper, so the renderer
 * implementation is only present transitively on the runtime classpath when a
 * Paper adapter is shaded in. Rather than naming that class by string, the
 * adapter ships an implementation of this provider registered via
 * {@link java.util.ServiceLoader} (a {@code META-INF/services} entry), and the
 * consumer discovers it by {@link #id()} against the {@code menu.renderer}
 * configuration.</p>
 *
 * <p>Platforms that compile-depend on their adapter (Fabric / NeoForge) may
 * continue to construct their renderer directly; this seam exists for the
 * Bukkit family's optional-capability-at-runtime case but is platform-neutral.</p>
 */
public interface MenuRendererProvider {

    /**
     * The lowercase identifier matched against the {@code menu.renderer}
     * configuration list (e.g. {@code "book"}). Must not be {@code null}.
     *
     * @return the renderer id this provider supplies
     */
    String id();

    /**
     * Instantiate the {@link MenuRenderer}. Implementations should perform any
     * platform wiring they require (the call happens once at command-tree
     * assembly time).
     *
     * @return a ready-to-use renderer; must not be {@code null}
     */
    MenuRenderer create();

    /**
     * The {@link PlatformFamily} this provider's renderer is bound to, or
     * {@code null} when the renderer is platform-neutral and may be used on any
     * runtime.
     *
     * <p>This is the selection gate that keeps a universal jar (which shades the
     * Paper, Fabric, and NeoForge adapters together, so every adapter's
     * {@code META-INF/services} entry is merged and several providers advertise
     * the same {@link #id()}) from picking the wrong platform's renderer: when a
     * provider declares a family, {@code MenuBindingSupport} skips it unless it
     * matches {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#getPlatformFamily()}.
     * A {@code null} family is never skipped.</p>
     *
     * @return the platform family this renderer serves, or {@code null} for any
     */
    default @Nullable PlatformFamily platformFamily() {
        return null;
    }
}
