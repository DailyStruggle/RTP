package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import org.jetbrains.annotations.Nullable;

/**
 * Service-discovery provider for a {@link MenuRedeemSubcommand.AnvilInputOpener}.
 */
public interface AnvilInputOpenerProvider {

    /**
     * Instantiate and fully wire the {@link MenuRedeemSubcommand.AnvilInputOpener}.
     *
     * @return a ready-to-use anvil or chat-prompt input opener; never {@code null}
     */
    MenuRedeemSubcommand.AnvilInputOpener create();

    /**
     * Platform family this opener is bound to, or {@code null} if platform-neutral.
     *
     * @return the platform family, or {@code null}
     */
    default @Nullable PlatformFamily platformFamily() {
        return null;
    }
}
