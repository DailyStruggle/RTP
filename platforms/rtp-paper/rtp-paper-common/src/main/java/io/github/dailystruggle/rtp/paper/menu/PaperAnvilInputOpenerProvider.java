package io.github.dailystruggle.rtp.paper.menu;

import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.menu.AnvilInputOpenerProvider;
import io.github.dailystruggle.rtp.common.commands.menu.MenuRedeemSubcommand;

import java.util.logging.Level;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@link AnvilInputOpenerProvider} for the Paper anvil-input opener
 * ({@link AnvilInputSession}), discovered via {@link java.util.ServiceLoader}
 * (see {@code META-INF/services/io.github.dailystruggle.rtp.common.commands.menu.AnvilInputOpenerProvider}).
 *
 * <p>Typed, discoverable replacement for the former
 * {@code Class.forName("...AnvilInputSession")} + reflective {@code register(Plugin)}
 * in the Bukkit bootstrap. The opener is a Bukkit {@code Listener}, so this
 * provider resolves the owning RTP plugin itself ({@link JavaPlugin#getProvidingPlugin}:
 * this class ships shaded inside the RTP plugin jar) and registers the listener
 * before returning, keeping the seam self-contained so the consumer need not pass
 * a {@code Plugin} handle.</p>
 */
public final class PaperAnvilInputOpenerProvider implements AnvilInputOpenerProvider {

    @Override
    public MenuRedeemSubcommand.AnvilInputOpener create() {
        AnvilInputSession session = new AnvilInputSession();
        try {
            Plugin plugin = JavaPlugin.getProvidingPlugin(PaperAnvilInputOpenerProvider.class);
            session.register(plugin);
        } catch (Throwable t) {
            // Registration is best-effort: if the owning plugin cannot be
            // resolved the opener still functions for callers that do not
            // depend on its inventory event handlers; log and continue.
            RTP.log(Level.WARNING,
                    "failed to register menu anvil-input listener: " + t.getMessage(), t);
        }
        return session;
    }

    @Override
    public PlatformFamily platformFamily() {
        return PlatformFamily.BUKKIT;
    }
}
