package io.github.dailystruggle.rtp.neoforge.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.dailystruggle.commandsapi.brigadier.BrigadierBridgeContext;
import io.github.dailystruggle.commandsapi.brigadier.BrigadierCommandAdapter;
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
 * ({@code NEOFORGE_NOTES.md} §2). Both NeoForge and Fabric terminate in vanilla
 * Brigadier, so the {@code commands-api} Brigadier bridge
 * ({@code commands-api-ADR-001}) is reused verbatim — this class is the
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
   * Builds the {@code /rtp} command tree and registers it against the NeoForge
   * dispatcher supplied by {@code RegisterCommandsEvent}.
   *
   * @param dispatcher the vanilla Brigadier dispatcher
   */
  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    if (dispatcher == null) {
      RTP.log(Level.WARNING, "[RTP][NeoForge] command registration skipped: null dispatcher.");
      return;
    }
    try {
      // Build the platform-neutral /rtp root directly (ADR-070): the whole
      // command tree, parameters, dispatch, outcome events, and menu-binding
      // selection live in CoreRtpRoot, so NeoForge no longer needs a bespoke
      // root subclass. This mirrors how the Bukkit family now constructs the
      // shared root in BootstrapSupport.
      CoreRtpRoot root = new CoreRtpRoot();
      // Expose the root so other subsystems (info / menu / network) can locate
      // the canonical command tree, mirroring the Bukkit / Fabric entrypoints.
      RTP.baseCommand = root;

      BrigadierBridgeContext<CommandSourceStack> bridgeCtx =
          new BrigadierBridgeContext<>(
              NeoForgeBrigadierSourceBridge::resolveSenderUuid,
              NeoForgeBrigadierSourceBridge::checkPermission,
              (src, msg) -> {
                if (msg == null) return;
                try {
                  UUID uuid = NeoForgeBrigadierSourceBridge.resolveSenderUuid(src);
                  if (uuid != null && !uuid.equals(RTPAPI.serverId)) {
                    // Player source — formats placeholders + legacy colour codes
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
          BrigadierCommandAdapter.toBrigadier(root, bridgeCtx);
      dispatcher.register(builder);
      RTP.log(Level.INFO, "[RTP][NeoForge] /rtp command tree registered.");
    } catch (Throwable t) {
      // S-004: never silently swallow a registration failure.
      RTP.log(Level.WARNING,
          "[RTP][NeoForge] /rtp Brigadier registration failed: "
              + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
    }
  }
}
