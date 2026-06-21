package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import org.jetbrains.annotations.Nullable;

/**
 * Service-discovery provider for a {@link MenuRedeemSubcommand.AnvilInputOpener}.
 *
 * <p>The companion of {@link io.github.dailystruggle.rtp.api.menu.MenuRendererProvider}
 * for the {@code /rtp menu} typed-value input surface. Lives in {@code rtp-core}
 * (rather than {@code rtp-api}) because its product type,
 * {@link MenuRedeemSubcommand.AnvilInputOpener}, is a {@code rtp-core} type.</p>
 *
 * <p>It replaces the former classpath-conditional {@code Class.forName} lookup of
 * the Paper {@code AnvilInputSession}: the adapter ships an implementation
 * registered via {@link java.util.ServiceLoader} (a {@code META-INF/services}
 * entry). The implementation is responsible for any platform-listener
 * registration it needs (the Paper opener self-resolves and registers with its
 * owning plugin), so the consumer need not pass a platform handle.</p>
 */
public interface AnvilInputOpenerProvider {

    /**
     * Instantiate and fully wire the {@link MenuRedeemSubcommand.AnvilInputOpener}
     * (including any platform-listener registration it requires). Called once at
     * command-tree assembly time.
     *
     * @return a ready-to-use anvil / chat-prompt input opener; must not be {@code null}
     */
    MenuRedeemSubcommand.AnvilInputOpener create();

    /**
     * The {@link PlatformFamily} this opener is bound to, or {@code null} when
     * it is platform-neutral. Mirrors
     * {@link io.github.dailystruggle.rtp.api.menu.MenuRendererProvider#platformFamily()}:
     * on a universal jar that shades several adapters together, {@code MenuBindingSupport}
     * skips a provider whose declared family does not match the running
     * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#getPlatformFamily()}.
     *
     * @return the platform family this opener serves, or {@code null} for any
     */
    default @Nullable PlatformFamily platformFamily() {
        return null;
    }
}
