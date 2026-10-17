package io.github.dailystruggle.rtp.neoforge.claims;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.logging.Level;
import net.neoforged.fml.ModList;

/**
 * Boots mod-side claim verifiers on NeoForge (ADR-026). Verifiers return {@code true} to allow a
 * location, so each checker's "in a claim" verdict is negated.
 */
public final class NeoForgeModClaimIntegrations {
  private NeoForgeModClaimIntegrations() {}

  public static void registerAll() {
    ModList modList = ModList.get();
    if (modList == null) return;

    if (modList.isLoaded("openpartiesandclaims")) {
      try {
        RTPAPI.hooks().verifiers().register(NeoForgeOpenPartiesAndClaimsChecker.class,
            coords -> !NeoForgeOpenPartiesAndClaimsChecker.isInClaim(coords));
        RTP.log(Level.INFO, "[RTP] Registered OpenPartiesAndClaims region verifier on NeoForge.");
      } catch (Throwable t) {
        RTP.log(Level.WARNING, "[RTP] Failed to register OpenPartiesAndClaims verifier on NeoForge: " + t.getMessage(), t);
      }
    }

    if (modList.isLoaded("ftbchunks")) {
      try {
        RTPAPI.hooks().verifiers().register(NeoForgeFTBChunksChecker.class,
            coords -> !NeoForgeFTBChunksChecker.isInClaim(coords));
        RTP.log(Level.INFO, "[RTP] Registered FTB Chunks region verifier on NeoForge.");
      } catch (Throwable t) {
        RTP.log(Level.WARNING, "[RTP] Failed to register FTB Chunks verifier on NeoForge: " + t.getMessage(), t);
      }
    }
  }
}
