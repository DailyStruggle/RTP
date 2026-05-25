package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Platform-supplied hooks consumed by {@link MenuWiringSupport#attachTo}.
 *
 * <p>ADR-050 Stage 3β.D.2b (2026-05-24): the {@code MenuTokenRegistry}
 * binding is gone. The renderer emits concrete {@code /rtp menu ...}
 * commands; no token is consulted.
 *
 * <ul>
 *   <li>{@link #permissionProbe} bridges to the platform's permission
 *       resolver - on Bukkit/Paper/Folia it reaches {@code Bukkit#getPlayer},
 *       on Fabric it reaches {@code FabricEffectivePermissionsResolver}
 *       ({@code rtp-fabric-ADR-011}). The viewer-keyed
 *       {@link Function} returns a per-permission {@link Predicate} so the
 *       resolver can cache lookups per call site.</li>
 *   <li>{@link #renderer} is the resolved
 *       {@link MenuRenderer} for this platform, or {@code null} if no
 *       renderer is on the runtime classpath (e.g. a lite Paper jar without
 *       {@code rtp-paper-common}, or Fabric before a renderer is wired).
 *       Callers tolerate {@code null}: the redeem path emits the
 *       configurable {@code menuInvalid} message per REQ-RTP-F-013 /
 *       REQ-RTP-S-007 instead of throwing.</li>
 *   <li>{@link #anvilOpener} is the platform's anvil-GUI input opener
 *       (ADR-045), or {@code null} when unavailable (Spigot without
 *       {@code AnvilGUI}, Fabric for the chat-prompt substitute described
 *       in {@code rtp-fabric-ADR-012}). The redeem path falls back to
 *       {@code menuInvalid} when the picker row is clicked without an
 *       opener available.</li>
 * </ul>
 *
 * @param permissionProbe viewer-keyed permission resolver. Must not be
 *                        {@code null}. Empty / null permission strings
 *                        must return {@code true} (treated as
 *                        "no permission required").
 * @param renderer        the {@link MenuRenderer}, or {@code null} if
 *                        none is available on this platform.
 * @param anvilOpener     the {@link MenuRedeemSubcommand.AnvilInputOpener},
 *                        or {@code null} if unavailable.
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
