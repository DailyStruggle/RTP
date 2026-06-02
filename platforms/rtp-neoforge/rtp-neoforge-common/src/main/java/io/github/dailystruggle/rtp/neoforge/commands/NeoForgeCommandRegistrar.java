package io.github.dailystruggle.rtp.neoforge.commands;

import com.mojang.brigadier.CommandDispatcher;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.logging.Level;
import net.minecraft.commands.CommandSourceStack;

/**
 * NeoForge command-registration trampoline (Phase N1 scaffold).
 *
 * <p>NeoForge fires {@code RegisterCommandsEvent} on the game bus, exposing the
 * vanilla {@link CommandDispatcher} of {@link CommandSourceStack}
 * (NEOFORGE_NOTES.md §2). Both NeoForge and Fabric terminate in vanilla
 * Brigadier, so the {@code commands-api} Brigadier bridge
 * ({@code commands-api-ADR-001}) is reusable verbatim — this class is only the
 * platform-specific trampoline that hands the dispatcher to that bridge,
 * mirroring {@code FabricCommandRegistrar}.</p>
 *
 * <p><b>Phase N1 TODO (@leaf_26):</b> port {@code FabricCommandRegistrar} /
 * {@code RTPCmdFabricRoot}: build the shared command tree, wrap the NeoForge
 * {@link CommandSourceStack} in the {@code commands-api} source bridge, and
 * register the {@code /rtp} literal against {@code dispatcher}. The S-007
 * configurable busy/invalid-command messages are inherited from the shared
 * command tree. The exit gate for Phase N1 is a single {@code /rtp} round-trip
 * on the default world.</p>
 */
public final class NeoForgeCommandRegistrar {

  private NeoForgeCommandRegistrar() {
  }

  /**
   * Registers the {@code /rtp} command tree against the NeoForge dispatcher.
   *
   * @param dispatcher the vanilla Brigadier dispatcher from {@code RegisterCommandsEvent}
   */
  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    // TODO(Phase N1, @leaf_26): wire the commands-api Brigadier bridge here.
    // Until the bridge wiring lands, log loudly so the gap is visible at
    // runtime rather than silently swallowed (S-004 posture).
    RTP.log(Level.WARNING,
        "[RTP/NeoForge] command registration is a Phase N1 scaffold — "
            + "/rtp is not yet wired on NeoForge. See "
            + "platforms/rtp-neoforge/REQUIREMENTS.md and NEOFORGE_NOTES.md §10.");
  }
}
