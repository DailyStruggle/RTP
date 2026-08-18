package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

/**
 * Facade aggregating behavior-modification extension points for third-party plugins.
 *
 * <p>Obtain singleton via {@code RTPAPI.hooks()}. Throws {@link IllegalStateException}
 * if accessed before core loads (REQ-RTP-S-006).
 */
@PublicApi
public interface RTPHooks {

  /** @return the region-verifier registry; never {@code null}. */
  @PublicApi
  RegionVerifierRegistry verifiers();

  /** @return the economy provider registry; never {@code null}. */
  @PublicApi
  EconomyProviderRegistry economy();

  /** @return the placeholder provider registry; never {@code null}. */
  @PublicApi
  PlaceholderProviderRegistry placeholders();

  /** @return the world-border provider registry; never {@code null}. */
  @PublicApi
  WorldBorderProviderRegistry worldBorder();

  /** @return the anvil pre-filter registry; never {@code null}. */
  @PublicApi
  AnvilPrefilterRegistry anvilPrefilter();

  /** @return the PvP combat-state registry; never {@code null}. */
  @PublicApi
  PvPCombatStateRegistry pvpCombatState();

  /** @return the bare-{@code /rtp} root-action registry; never {@code null}. */
  @PublicApi
  RootActionRegistry rootAction();

  /** @return the arrival platform-creator registry; never {@code null}. */
  @PublicApi
  PlatformCreatorRegistry platformCreator();
}
