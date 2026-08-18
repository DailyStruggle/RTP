package io.github.dailystruggle.rtp.tetheraddon;

import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;

/**
 * LeafRTP tether addon: keep a player inside the RTP region they were teleported into ("tether" =
 * confine to a region), composed from existing primitives rather than a bespoke area model.
 *
 * <p>A tether is deliberately thin. It reuses three things the core already owns: an existing RTP
 * region (the bounded area, whose geometry answers "is this coordinate inside?" with pure math, no
 * chunk I/O), RTP's own teleport events (to arm a tether when a player is teleported into the region
 * and disarm it when another teleport takes them out), and the core database interface (to persist
 * who is tethered where). Enforcement uses RTP's platform-neutral move signal to detect a boundary
 * crossing and pulls the player back to a fresh safe destination inside the region.
 *
 * <p>Platform-neutral {@link RTPAddon} discovered by {@code rtp-core} through
 * {@link java.util.ServiceLoader} (ADR-057); no {@code org.bukkit.*} imports, so it runs identically
 * on Bukkit/Paper/Folia and Fabric/NeoForge. Because it depends on RTP's own move signal rather than
 * WorldGuard's Bukkit-only region trap, it is cross-platform by construction.
 *
 * <p><b>Status.</b> This class wires configuration and lifecycle only. The tether enforcement
 * consumes a platform-neutral player-move event SPI that does not yet exist in {@code rtp-api} /
 * {@code rtp-core}; that addition is a cross-module core change specified in
 * {@code docs/adr/ADR-075-platform-neutral-player-move-event-spi.md} (Proposed, D-005 gated). Until
 * the SPI is approved and lands, this addon loads as a safe no-op. See {@code REQUIREMENTS.md} and
 * {@code docs/adr/leafrtp-tether-addon-ADR-001-cross-platform-region-confinement.md} for the full
 * contract.
 */
public final class RTPTetherAddon implements RTPAddon {

  @Override
  public void onLoad() {
    // Register the addon's config file (tether.yml) with RTP's Configs registry, and re-register on
    // /rtp reload so operators see config changes without a restart.
    RTP.configs.putParser(registerParser());
    Configs.onReload(() -> RTP.configs.putParser(registerParser()));

    // Tether enforcement (arm on teleport-into-region, watch the tethered set via the platform-neutral
    // move event, pull a player back to a fresh safe destination on boundary crossing, persist state
    // through the core database interface) depends on the move-event SPI proposed in ADR-075 and is
    // specified in REQUIREMENTS.md / the subproject ADR. Registering nothing here keeps the addon a
    // safe no-op until that SPI is approved and lands.
  }

  /**
   * Builds a {@link ConfigParser} for {@link TetherKeys} targeting {@code addons/tether.yml} inside
   * the RTP plugin data folder.
   */
  private ConfigParser<TetherKeys> registerParser() {
    return new ConfigParser<>(
        TetherKeys.class,
        "addons/tether",
        "1.0",
        RTP.serverAccessor.getPluginDirectory(),
        null,
        RTP.configs.fileDatabase,
        this.getClass().getClassLoader());
  }

  @Override
  public void onUnload() {
    // No tickets, tasks, move-event subscriptions, or DB writes are allocated yet, so there is
    // nothing to release. When tether enforcement lands, unsubscribe from the move event, cancel any
    // scheduled pull-back work, and flush/close tether state here so the addon can come and go
    // without leaving RTP in a half-state.
  }
}
