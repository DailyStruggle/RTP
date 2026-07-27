package io.github.dailystruggle.rtp.partyaddon;

import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;

/**
 * LeafRTP party addon: teleport a whole party/group to one prepared destination (or a small
 * adjacent cluster) in a single operation.
 *
 * <p>This is a platform-neutral addon discovered by {@code rtp-core} through
 * {@link java.util.ServiceLoader} (see
 * {@code META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon}), so it runs on
 * Bukkit/Paper/Folia and Fabric/NeoForge alike with no {@code org.bukkit.*} imports (ADR-057).
 *
 * <p><b>Design intent.</b> Unlike a search-per-player model, RTP prepares locations ahead of time in
 * its supply pipeline (active-loaded / prefiltered / selected). Serving one already-verified
 * coordinate to N party members - or reserving a small adjacent cluster - is therefore a natural,
 * cheap extension of the existing pipeline rather than a new search mode. See
 * {@code docs/adr/leafrtp-party-addon-ADR-001-party-teleport-shared-destination.md} and
 * {@code REQUIREMENTS.md} for the full contract; the party-orchestration logic itself is not yet
 * implemented (this class wires configuration and lifecycle only).
 */
public final class RTPPartyAddon implements RTPAddon {

  @Override
  public void onLoad() {
    // Register the addon's config file (party.yml) with RTP's Configs registry, and re-register on
    // /rtp reload so operators see config changes without a restart.
    RTP.configs.putParser(registerParser());
    Configs.onReload(() -> RTP.configs.putParser(registerParser()));

    // Feature wiring is intentionally not present yet: party detection, coordinate reservation, and
    // the grouped teleport dispatch are specified in REQUIREMENTS.md / the subproject ADR and are
    // the next implementation step. Registering nothing here keeps the addon a safe no-op until the
    // orchestration lands, rather than half-registering a command that cannot serve a party.
  }

  /**
   * Builds a {@link ConfigParser} for {@link PartyKeys} targeting {@code addons/party.yml} inside
   * the RTP plugin data folder.
   */
  private ConfigParser<PartyKeys> registerParser() {
    return new ConfigParser<>(
        PartyKeys.class,
        "addons/party",
        "1.0",
        RTP.serverAccessor.getPluginDirectory(),
        null,
        RTP.configs.fileDatabase,
        this.getClass().getClassLoader());
  }

  @Override
  public void onUnload() {
    // No tickets, tasks, or DB writes are allocated yet, so there is nothing to release. When the
    // grouped-teleport orchestration lands, cancel any scheduled work and release any reserved
    // coordinates here so the addon can come and go without leaving RTP in a half-state.
  }
}
