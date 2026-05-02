package io.github.dailystruggle.rtp.bukkit;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.bukkit.commands.RTPCmdBukkit;
import io.github.dailystruggle.rtp.bukkit.server.BukkitServerProvider;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Shared bootstrap helpers used by both {@code RTPBukkitPlugin} (full / Pro) and
 * {@code io.github.dailystruggle.rtp.bukkit.lite.RTPBukkitLitePlugin} (ADR-024).
 *
 * <p>The two bootstraps must remain top-to-bottom readable with no {@code if (lite)}
 * conditionals. Genuinely shared steps live here so both classes call the same
 * helpers; genuinely divergent steps (login cache, language bootstrap, integrations,
 * effect parsing, Folia branch) stay in {@code RTPBukkitPlugin} alone.
 *
 * <p>This class is intentionally package-private at construction time — it is a
 * static-helper holder, not a service.
 */
public final class BootstrapSupport {

    private BootstrapSupport() {}

    /**
     * Resolve the server model, instantiate the matching {@link RTPServerAccessor}
     * and {@link RTPScheduler}, and assign them to the static {@code RTP} fields.
     *
     * <p>Mirrors the reflection block at the head of both bootstraps' {@code onEnable}.
     * Returns {@code true} on success; the caller is responsible for {@code onDisable()}
     * on failure.
     *
     * @param plugin the JavaPlugin instance (full or lite bootstrap)
     * @param logTag short tag inserted in lifecycle logs ("LIFECYCLE" or "LIFECYCLE-LITE")
     * @return {@code true} on success, {@code false} if reflection failed
     */
    public static boolean wireServerAccessorAndScheduler(JavaPlugin plugin, String logTag) {
        try {
            BukkitServerProvider.ServerModel serverModel = BukkitServerProvider.resolveServerModel(plugin);
            RTP.serverAccessor = (RTPServerAccessor)
                    Class.forName(serverModel.accessorClassName)
                            .getDeclaredConstructor().newInstance();
            RTP.scheduler = (RTPScheduler)
                    Class.forName(serverModel.schedulerClassName)
                            .getDeclaredConstructor(JavaPlugin.class).newInstance(plugin);
            return true;
        } catch (Exception e) {
            RTP.log(Level.WARNING,
                    "[" + logTag + "] onEnable reflection failure -- bailing out via onDisable", e);
            return false;
        }
    }

    /**
     * Bind the shared {@code /rtp} and {@code /wild} executors / tab-completers and
     * publish the shared {@code RTP.baseCommand} reference. Matches the block in
     * {@code RTPBukkitPlugin.onEnable()} that runs on both editions.
     */
    public static void registerRtpAndWildCommands(JavaPlugin plugin) {
        RTPCmdBukkit mainCommand = new RTPCmdBukkit(plugin);
        RTP.baseCommand = mainCommand;

        PluginCommand rtpCommand = plugin.getCommand("rtp");
        if (rtpCommand != null) {
            rtpCommand.setExecutor(mainCommand);
            rtpCommand.setTabCompleter(mainCommand);
        }
        PluginCommand wildCommand = plugin.getCommand("wild");
        if (wildCommand != null) {
            wildCommand.setExecutor(mainCommand);
            wildCommand.setTabCompleter(mainCommand);
        }
    }

    /**
     * Drain the startup-tasks queue until empty. Both bootstraps drain at multiple
     * points around region-config load and listener registration.
     */
    public static void drainStartupTasks() {
        RTP rtp = RTP.getInstance();
        if (rtp == null) return;
        while (rtp.startupTasks.size() > 0) {
            rtp.startupTasks.execute(Long.MAX_VALUE);
        }
    }
}
