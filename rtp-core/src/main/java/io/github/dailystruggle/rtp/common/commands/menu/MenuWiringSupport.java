package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.commands.admin.AdminCmd;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabCommand;


/**
 * Platform-agnostic installer for the full {@code /rtp menu} wiring (ADR-035, ADR-044, ADR-050).
 * Registers {@link MenuRedeemSubcommand}, builder callbacks, carts, and {@code /rtp admin} openers.
 */
public final class MenuWiringSupport {

    private MenuWiringSupport() {}

    /**
     * Installs menu wiring onto {@code root} using platform-supplied bindings.
     * Must be called after root parameters and subcommands are registered.
     *
     * @param root root command node
     * @param bindings platform bindings (permission probe, renderer, anvil opener)
     */
    public static void attachTo(TreeCommand root, MenuPlatformBindings bindings) {
        new MenuWiringSupportInstaller(root, bindings).install();
    }
}
