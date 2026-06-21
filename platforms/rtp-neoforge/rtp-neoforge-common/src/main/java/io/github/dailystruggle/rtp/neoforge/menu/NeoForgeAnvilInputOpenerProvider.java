package io.github.dailystruggle.rtp.neoforge.menu;

import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import io.github.dailystruggle.rtp.common.commands.menu.AnvilInputOpenerProvider;
import io.github.dailystruggle.rtp.common.commands.menu.MenuRedeemSubcommand;

/**
 * {@link AnvilInputOpenerProvider} for the NeoForge typed-value input opener
 * ({@link NeoForgeChatPromptCallback}), discovered via {@link java.util.ServiceLoader}
 * (see {@code META-INF/services/io.github.dailystruggle.rtp.common.commands.menu.AnvilInputOpenerProvider}).
 *
 * <p>Completes the same {@code AnvilInputOpenerProvider} SPI the Paper adapter
 * implements, so {@code rtp-core}'s {@code MenuBindingSupport} resolves the input
 * opener identically on every platform (ADR-070). The callback self-registers its
 * chat listener in its constructor, so the seam stays self-contained and the
 * consumer need not pass any platform handle.</p>
 */
public final class NeoForgeAnvilInputOpenerProvider implements AnvilInputOpenerProvider {

    @Override
    public MenuRedeemSubcommand.AnvilInputOpener create() {
        return new NeoForgeChatPromptCallback();
    }

    @Override
    public PlatformFamily platformFamily() {
        return PlatformFamily.NEOFORGE;
    }
}
