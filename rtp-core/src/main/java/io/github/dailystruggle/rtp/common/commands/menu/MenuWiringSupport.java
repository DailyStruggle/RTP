package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.commands.admin.AdminCmd;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabCommand;


/**
 * Platform-agnostic installer for the full {@code /rtp menu} wiring
 * (ADR-035 / ADR-044). Installs:
 *
 * <ul>
 *   <li>ADR-050: no token registry is wired any more; the renderer
 *       emits concrete {@code /rtp menu ...} commands directly,</li>
 *   <li>all eleven {@link MenuRedeemSubcommand} builder callbacks
 *       (page / param-picker / config-subtree / curated-page /
 *       config-search / info-book) - every callback is platform-neutral
 *       (it reads {@code RTP.configs}, the live command tree, and the
 *       supplied permission probe),</li>
 *   <li>the {@link MenuRedeemSubcommand} itself, plus its
 *       multiconfig binding and the staging-cart sink wiring,</li>
 *   <li>the top-level {@code /rtp admin} verb ({@link AdminCmd} +
 *       {@link PrefabCommand}) with an admin-panel opener Consumer
 *       that reuses the platform's {@link MenuRenderer}, and</li>
 *   <li>the deferred {@code RTPRunnable} that attaches the
 *       {@code /rtp config search} handler at ~8 ticks after init,
 *       routing search hits through the renderer.</li>
 * </ul>
 *
 * <p>Platform-specific pieces (permission probe, renderer selection,
 * anvil-input opener) are supplied by the caller through
 * {@link MenuPlatformBindings}. Lifted verbatim from
 * {@code RTPCmdBukkit:210-731} (2026-05-24) so Bukkit + Folia behaviour
 * is byte-identical at runtime; Fabric reuses the same code path with
 * a Fabric-flavoured {@link MenuPlatformBindings}.
 */
public final class MenuWiringSupport {

    private MenuWiringSupport() {}

    /**
     * Install the full menu wiring on {@code root}. Must be called once,
     * after the root command's params and non-menu subcommands have been
     * registered, so that {@code rtpRoot.getCommandLookup()} can resolve
     * {@code CONFIG} for the config-subtree builder.
     *
     * @param root     the {@code /rtp} root command this wiring decorates.
     * @param bindings platform-supplied permission probe + renderer +
     *                 anvil-input opener (any of which may be {@code null};
     *                 the redeem path degrades to the configurable
     *                 {@code menuInvalid} message in that case per
     *                 REQ-RTP-S-004 / REQ-RTP-F-013 / REQ-RTP-S-007).
     */
    public static void attachTo(TreeCommand root, MenuPlatformBindings bindings) {
        new MenuWiringSupportInstaller(root, bindings).install();
    }
}
