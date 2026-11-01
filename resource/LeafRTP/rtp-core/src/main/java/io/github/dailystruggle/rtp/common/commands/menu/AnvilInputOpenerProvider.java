package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.server.api.PlatformFamily;
import org.jetbrains.annotations.Nullable;

/**
 * Service-discovery provider for a {@link MenuRedeemSubcommand.AnvilInputOpener}.
 *
 * @governedBy ADR-045
 * @governedBy ADR-070
 * @governedBy ADR-115
 */
public interface AnvilInputOpenerProvider
        extends io.github.dailystruggle.menu.api.AnvilInputOpenerProvider {

    /**
     * Instantiate and fully wire the {@link MenuRedeemSubcommand.AnvilInputOpener}.
     *
     * @return a ready-to-use anvil or chat-prompt input opener; never {@code null}
     */
    @Override
    MenuRedeemSubcommand.AnvilInputOpener create();

    /**
     * Platform family this opener is bound to, or {@code null} if platform-neutral.
     *
     * @return the platform family, or {@code null}
     */
    @Override
    default @Nullable PlatformFamily platformFamily() {
        return null;
    }
}
