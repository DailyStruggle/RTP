package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.logging.Level;

/**
 * LeafRTP claim-integrations addon.
 *
 * <p>Bundles every supported claim/protection-plugin checker behind per-plugin {@code integrations.yml}
 * toggles and registers them as RTP safety verifiers (see {@link ClaimIntegrations}).
 *
 * <p>Every checker is {@code org.bukkit.*}-based, so the addon only does anything on a Bukkit-family
 * runtime. It self-gates on {@link PlatformFamily#BUKKIT} (queried from RTP, not detected here), and
 * is a no-op on Fabric / NeoForge / unknown platforms.
 */
public final class RTPClaimAddon implements RTPAddon {

  @Override
  public void onLoad() {
    PlatformFamily family = RTP.serverAccessor.getPlatformFamily();
    if (family != PlatformFamily.BUKKIT) {
      RTP.log(Level.INFO,
          "[LeafRTPClaimAddon] skipping claim integrations: not a Bukkit-family server (platform family "
              + family + ").");
      return;
    }
    ClaimIntegrations.setup(this.getClass().getClassLoader());
  }
}
