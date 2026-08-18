package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Platform-supplied hooks consumed by {@link MenuWiringSupport#attachTo}.
 *
 * @param permissionProbe viewer-keyed permission resolver (must not be {@code null}).
 * @param renderer        platform {@link MenuRenderer}, or {@code null} if unavailable.
 * @param anvilOpener     platform {@link MenuRedeemSubcommand.AnvilInputOpener}, or {@code null}.
 */
public record MenuPlatformBindings(
        Function<UUID, Predicate<String>> permissionProbe,
        @Nullable MenuRenderer renderer,
        @Nullable MenuRedeemSubcommand.AnvilInputOpener anvilOpener) {

    public MenuPlatformBindings {
        if (permissionProbe == null) {
            throw new NullPointerException("permissionProbe must not be null");
        }
    }
}
