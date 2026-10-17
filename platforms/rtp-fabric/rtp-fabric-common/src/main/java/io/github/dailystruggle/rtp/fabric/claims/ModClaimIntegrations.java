package io.github.dailystruggle.rtp.fabric.claims;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.logging.Level;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Boots mod-side claim verifiers on Fabric (ADR-026). Verifiers return {@code true} to allow a
 * location, so each checker's "in a claim" verdict is negated.
 */
public final class ModClaimIntegrations {
  private ModClaimIntegrations() {}

  public static void registerAll() {
    FabricLoader loader = FabricLoader.getInstance();
    if (loader == null) return;

    if (loader.isModLoaded("openpartiesandclaims")) {
      try {
        RTPAPI.hooks().verifiers().register(OpenPartiesAndClaimsChecker.class,
            coords -> !OpenPartiesAndClaimsChecker.isInClaim(coords));
        RTP.log(Level.INFO, "[RTP] Registered OpenPartiesAndClaims region verifier on Fabric.");
      } catch (Throwable t) {
        RTP.log(Level.WARNING, "[RTP] Failed to register OpenPartiesAndClaims verifier: " + t.getMessage(), t);
      }
    }

    if (loader.isModLoaded("ftbchunks")) {
      try {
        RTPAPI.hooks().verifiers().register(FTBChunksChecker.class,
            coords -> !FTBChunksChecker.isInClaim(coords));
        RTP.log(Level.INFO, "[RTP] Registered FTB Chunks region verifier on Fabric.");
      } catch (Throwable t) {
        RTP.log(Level.WARNING, "[RTP] Failed to register FTB Chunks verifier: " + t.getMessage(), t);
      }
    }
  }
}
