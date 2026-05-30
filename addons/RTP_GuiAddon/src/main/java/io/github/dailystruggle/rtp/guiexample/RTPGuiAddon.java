package io.github.dailystruggle.rtp.guiexample;

import io.github.dailystruggle.rtp.api.RTPAPI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Reference example addon: an <b>inventory (chest) destination picker</b> for RTP.
 *
 * <p>This addon exists to demonstrate that a third-party GUI can be built entirely on
 * RTP's <b>stable, semver-guaranteed {@code rtp-api} surface</b> - it never links
 * {@code rtp-core} and never touches a region, queue, or teleport primitive directly.
 * The division of responsibility is exactly the one described in
 * {@code docs/dev/scratch/PROPOSAL-gui-author-spi.md}:
 *
 * <ul>
 *   <li><b>RTP owns safety + validation.</b> Permission gating, cooldown/cost
 *       resolution, and the S-001..S-007 prohibitions all live behind the API calls.
 *       This addon only ever submits a <em>validated intent</em>
 *       ({@link io.github.dailystruggle.rtp.api.RTPAPI#teleport}).</li>
 *   <li><b>The GUI author owns presentation + click handling.</b> Inventory layout,
 *       icons, lore, and the open/close/click lifecycle are this addon's problem -
 *       and any bug there cannot bypass RTP's safety, because the only mutating call
 *       it can make is {@code teleport(...)}.</li>
 * </ul>
 *
 * <p>API touch-points demonstrated:
 * <ul>
 *   <li>{@link io.github.dailystruggle.rtp.api.RTPAPI#getAllowedTargets(java.util.UUID)}
 *       - permission-gated destination list (one chest slot per target).</li>
 *   <li>{@link io.github.dailystruggle.rtp.api.RTPAPI#getTargetStatus(java.util.UUID,
 *       io.github.dailystruggle.rtp.api.RtpTarget)} - decorate each icon with
 *       availability / cooldown / cost.</li>
 *   <li>{@link io.github.dailystruggle.rtp.api.RTPAPI#teleport(java.util.UUID,
 *       io.github.dailystruggle.rtp.api.RtpTarget)} - submit the teleport intent on
 *       click; RTP re-validates everything.</li>
 *   <li>{@link io.github.dailystruggle.rtp.api.RTPAPI#getMetricsSnapshot()} - a single
 *       dashboard tile (TPS / MSPT) at the bottom of the menu.</li>
 * </ul>
 *
 * <p>See {@code README.md} for a walkthrough and the cross-platform caveats (Fabric
 * has no Bukkit inventory API, so a chest renderer is Bukkit-family only).
 */
public final class RTPGuiAddon extends JavaPlugin {

  @Override
  public void onEnable() {
    // Presentation/click handling is the addon's responsibility; register our listener.
    getServer().getPluginManager().registerEvents(new DestinationPickerListener(), this);

    // Wire bare `/rtp` (no arguments) to open this picker (ADR-056). Subcommands
    // (`/rtp admin`, `/rtp config`, ...) are untouched - they resolve before the
    // root action. Returning false (e.g. offline player) defers to RTP's classic
    // teleport. `depend: [ RTP ]` guarantees core is loaded first, but we still
    // guard the IllegalStateException thrown per REQ-RTP-S-006 just in case.
    try {
      RTPAPI.hooks().rootAction().bind((playerId, feedback) -> {
        Player player = getServer().getPlayer(playerId);
        if (player == null) {
          return false; // defer to the classic teleport
        }
        player.openInventory(DestinationPickerGui.build(player).getInventory());
        return true; // handled: suppress the classic teleport
      });
    } catch (IllegalStateException coreNotLoaded) {
      getLogger().log(Level.WARNING,
          "RTP core not loaded; bare /rtp will not open the GUI menu", coreNotLoaded);
    }
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!command.getName().equalsIgnoreCase("rtpgui")) {
      return false;
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage("Only players can open the RTP destination picker.");
      return true;
    }
    // Build a fresh picker for this player (status is a point-in-time read) and open it.
    player.openInventory(DestinationPickerGui.build(player).getInventory());
    return true;
  }

  @Override
  public void onDisable() {
    // Release the bare-/rtp binding so it does not outlive this addon (e.g. across
    // a `/reload`); a bare /rtp then reverts to the classic teleport.
    try {
      RTPAPI.hooks().rootAction().clear();
    } catch (IllegalStateException ignored) {
      // core already gone; nothing to unbind.
    }
  }
}
