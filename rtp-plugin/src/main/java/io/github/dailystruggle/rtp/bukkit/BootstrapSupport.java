package io.github.dailystruggle.rtp.bukkit;

import io.github.dailystruggle.commandsapi.bukkit.BukkitCommandRegistrar;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.bukkit.commands.BukkitCommandEvents;
import io.github.dailystruggle.rtp.bukkit.commands.BukkitHelpReplyRenderer;
import io.github.dailystruggle.rtp.bukkit.commands.BukkitMessageSink;
import io.github.dailystruggle.rtp.bukkit.commands.test.BukkitTestCmd;
import io.github.dailystruggle.rtp.bukkit.server.BukkitServerProvider;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.CoreRtpRoot;
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
 * <p>This class is intentionally package-private at construction time - it is a
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
            // Adopt the server type decided at the entrypoint instead of having the
            // accessor re-detect it via independent class lookups. The Folia adapter's
            // accessor reports a constant "Folia" already; the Bukkit-family accessor
            // (also used for the free Folia build) takes the resolved value here.
            if (RTP.serverAccessor instanceof io.github.dailystruggle.rtp.bukkitplatform.server.AbstractServerAccessor abstractAccessor
                    && serverModel.platform != null) {
                abstractAccessor.setPlatform(serverModel.platform);
            }
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
        // Install the Bukkit MessageSink once (commands-api-ADR-002): command
        // reply delivery routes raw templates through SendMessage so
        // placeholders, '&' / hex colour codes, and PAPI are resolved at the
        // platform boundary instead of leaking to console as raw templates.
        CommandsAPI.setMessageSink(new BukkitMessageSink());

        // Subscribe the Bukkit republishers for the neutral command outcome
        // events (ADR-070) once; idempotent across re-enables.
        BukkitCommandEvents.register();

        // The /rtp tree, parameters, subcommands, dispatch, outcome events, and
        // the /rtp menu renderer / anvil-opener selection all live in the
        // platform-neutral CoreRtpRoot (ADR-070): the menu bindings are resolved
        // by rtp-core's MenuBindingSupport through the MenuRendererProvider /
        // AnvilInputOpenerProvider ServiceLoader SPIs, identically on every
        // platform. The Bukkit platform supplies only the genuinely
        // platform-bound reply-renderer (renders /rtp help rows as clickable
        // chat). BukkitTestCmd is Bukkit-only (Bukkit-typed test children) and is
        // registered onto the neutral root here.
        CoreRtpRoot mainCommand = new CoreRtpRoot(new BukkitHelpReplyRenderer());
        mainCommand.addSubCommand(new BukkitTestCmd(mainCommand));
        RTP.baseCommand = mainCommand;

        // commands-api owns the Bukkit registration concern (commands-api-ADR-003):
        // the neutral root does not subclass a Bukkit command type. The registrar
        // binds /rtp and /wild and delegates the legacy String[] command path back
        // to CoreRtpRoot#dispatchString (sender checks + the RTPCmd guard);
        // tab-completion routes through the root's onTabComplete.
        BukkitCommandRegistrar registrar =
                new BukkitCommandRegistrar(plugin, mainCommand, mainCommand::dispatchString);
        registrar.register("rtp", "wild");
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
