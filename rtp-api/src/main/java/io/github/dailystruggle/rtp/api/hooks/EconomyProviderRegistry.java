package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.economy.RTPEconomy;

/**
 * Single-binding registry for the platform-agnostic {@link RTPEconomy} that RTP uses
 * to charge and refund teleport requests.
 *
 * <p>Today, the bundled Vault adapter ({@code VaultChecker} in {@code rtp-plugin})
 * is the only producer; this interface is the API surface for any third-party
 * economy provider that wishes to register without depending on Vault or on
 * {@code rtp-core} internals (ADR-026).
 *
 * <p><b>Lifecycle.</b> Bind once during the integration plugin's {@code onEnable}.
 * Re-binding replaces the previous provider. {@link #clear()} restores the no-op
 * implementation supplied by the platform server accessor.
 *
 * <p><b>Threading.</b> Implementations may be invoked from the async generation
 * pipeline; see {@link RTPEconomy} for thread-safety contracts.
 */
public interface EconomyProviderRegistry {

  /**
   * Install {@code provider} as the active economy implementation.
   *
   * @param provider non-null economy implementation
   */
  void bind(RTPEconomy provider);

  /**
   * @return the currently bound provider, or {@code null} if none is bound and the
   *     platform default no-op is in effect.
   */
  RTPEconomy current();

  /** Unbind any third-party provider; restores the platform default. */
  void clear();
}
