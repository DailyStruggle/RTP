package io.github.dailystruggle.rtp.neoforge.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.dailystruggle.commandsapi.brigadier.BrigadierBridgeContext;
import io.github.dailystruggle.commandsapi.brigadier.BrigadierCommandAdapter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.CoreRtpRoot;
import io.github.dailystruggle.rtp.neoforge.tools.NeoForgeBrigadierSourceBridge;
import net.minecraft.commands.CommandSourceStack;

import java.util.UUID;
import java.util.logging.Level;

/**
 * NeoForge command-registration trampoline.
 *
 * <p>NeoForge fires {@code RegisterCommandsEvent} on the game bus, exposing the
 * vanilla {@link CommandDispatcher} of {@link CommandSourceStack}
 * ({@code NEOFORGE_NOTES.md} section 2). Both NeoForge and Fabric terminate in vanilla
 * Brigadier, so the {@code commands-api} Brigadier bridge
 * ({@code commands-api-ADR-001}) is reused verbatim - this class is the
 * platform-specific trampoline that builds the shared {@code /rtp} tree, wraps
 * the NeoForge {@link CommandSourceStack} via {@link NeoForgeBrigadierSourceBridge},
 * and registers the literal against the dispatcher.</p>
 *
 * <p>Unlike Fabric's {@code FabricCommandRegistrar}, no reflective {@code Proxy}
 * dance is required: NeoForge is Mojmap-at-runtime, so {@link CommandSourceStack}
 * is a stable typed reference and the bytecode never carries an intermediary
 * name that could fail JVM verification (rtp-neoforge-ADR-001).</p>
 */
public final class NeoForgeCommandRegistrar {

  private NeoForgeCommandRegistrar() {
  }

  /**
   * Builds the default {@code /rtp} command tree and registers it against the NeoForge
   * dispatcher supplied by {@code RegisterCommandsEvent}.
   *
   * @param dispatcher the vanilla Brigadier dispatcher
   */
  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    CoreRtpRoot root = new CoreRtpRoot();
    var testCmd = resolveTestCmd(root);
    if (testCmd != null) {
      root.addSubCommand(testCmd);
    }
    RTP.baseCommand = root;
    register(dispatcher, root, "rtp", "wild");
    if (testCmd != null) {
      registerTestCmd(dispatcher, testCmd);
    }
  }

  /**
   * Attempts to resolve or instantiate the platform-neutral {@code TestCmd} tree.
   *
   * @param parent parent command to attach to, if applicable
   * @return resolved {@link io.github.dailystruggle.commandsapi.common.CommandsAPICommand} or {@code null} if unavailable
   */
  public static CommandsAPICommand resolveTestCmd(Object parent) {
    if (parent instanceof TreeCommand treeCmd) {
      CommandsAPICommand existing = treeCmd.getCommandLookup().get("TEST");
      if (existing != null) {
        return existing;
      }
    }
    try {
      Class<?> testCmdClz = Class.forName("io.github.dailystruggle.rtp.bukkit.commands.test.TestCmd");
      var ctor = testCmdClz.getConstructor(CommandsAPICommand.class);
      return (CommandsAPICommand) ctor.newInstance(
          parent instanceof CommandsAPICommand p ? p : null);
    } catch (Throwable ignored) {
      return null;
    }
  }

  /**
   * Registers the {@code TestCmd} tree directly into the dispatcher via {@link BrigadierCommandAdapter}.
   *
   * @param dispatcher the vanilla Brigadier dispatcher
   * @param testCmd    the test command root
   */
  public static void registerTestCmd(CommandDispatcher<CommandSourceStack> dispatcher,
                                     io.github.dailystruggle.commandsapi.common.CommandsAPICommand testCmd) {
    if (testCmd != null) {
      register(dispatcher, testCmd);
    }
  }

  /**
   * Builds the Brigadier tree for {@code root} using a custom bridge context.
   *
   * @param dispatcher Brigadier dispatcher
   * @param root       command root
   * @param bridgeCtx  bridge context
   * @param <S>        command source type
   */
  public static <S> void register(CommandDispatcher<S> dispatcher,
                                  CommandsAPICommand root,
                                  BrigadierBridgeContext<S> bridgeCtx) {
    if (dispatcher == null || root == null || bridgeCtx == null) return;
    LiteralArgumentBuilder<S> builder = BrigadierCommandAdapter.toBrigadier(root, bridgeCtx);
    dispatcher.register(builder);
  }

  /**
   * Builds the Brigadier tree for {@code root} and registers it along with any
   * {@code aliases} against the NeoForge dispatcher.
   *
   * @param dispatcher the vanilla Brigadier dispatcher
   * @param root       the command root (CommandsAPICommand)
   * @param aliases    optional command aliases (e.g. "wild")
   */
  public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                              Object root,
                              String... aliases) {
    if (dispatcher == null) {
      RTP.log(Level.WARNING, "[RTP][NeoForge] command registration skipped: null dispatcher.");
      return;
    }
    if (!(root instanceof io.github.dailystruggle.commandsapi.common.CommandsAPICommand cmdRoot)) {
      RTP.log(Level.WARNING, "[RTP][NeoForge] command registration skipped: invalid root " + root);
      return;
    }
    try {
      BrigadierBridgeContext<CommandSourceStack> bridgeCtx =
          new BrigadierBridgeContext<>(
              NeoForgeBrigadierSourceBridge::resolveSenderUuid,
              NeoForgeBrigadierSourceBridge::checkPermission,
              (src, msg) -> {
                if (msg == null) return;
                try {
                  UUID uuid = NeoForgeBrigadierSourceBridge.resolveSenderUuid(src);
                  if (uuid != null && !uuid.equals(RTPAPI.serverId)) {
                    // Player source - formats placeholders + legacy colour codes
                    // and dispatches through the player's RTPCommandSender.
                    RTP.serverAccessor.sendMessage(uuid, msg, null);
                  } else {
                    // Console / command-block / serverId sentinel: route through
                    // RTP.log so the message lands in the server console with
                    // colour codes preserved.
                    RTP.log(Level.INFO, msg);
                  }
                } catch (Throwable t) {
                  RTP.log(Level.WARNING,
                      "[RTP][NeoForge] Brigadier sendMessage failed: " + t.getMessage());
                }
              });

      LiteralArgumentBuilder<CommandSourceStack> builder =
          BrigadierCommandAdapter.toBrigadier(cmdRoot, bridgeCtx);
      com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> node = dispatcher.register(builder);
      if (aliases != null) {
        for (String alias : aliases) {
          if (alias != null && !alias.isEmpty() && !alias.equalsIgnoreCase(cmdRoot.name())) {
            dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal(alias).redirect(node));
          }
        }
      }
      RTP.log(Level.INFO, "[RTP][NeoForge] /" + cmdRoot.name() + " command tree registered.");
    } catch (Throwable t) {
      // S-004: never silently swallow a registration failure.
      RTP.log(Level.WARNING,
          "[RTP][NeoForge] Brigadier registration failed for " + cmdRoot.name() + ": "
              + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
    }
  }
}
