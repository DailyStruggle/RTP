package io.github.dailystruggle.rtp.api.hooks;

/**
 * Facade aggregating every <i>behavior-modification</i> extension point that RTP
 * exposes to third-party plugins and addons.
 *
 * <p>Obtain the singleton via {@code RTPAPI.hooks()}. Each accessor returns the
 * registry for one category of hook:
 * <ul>
 *   <li>{@link #verifiers()} — claim-plugin and custom location-veto predicates
 *       (REQ-RTP-S-003, REQ-API-F-003).</li>
 *   <li>{@link #economy()} — pluggable economy provider replacing the bundled
 *       Vault adapter.</li>
 *   <li>{@link #placeholders()} — named string resolvers consumed by
 *       PlaceholderAPI / similar bridges.</li>
 *   <li>{@link #worldBorder()} — world-border integrations (ChunkyBorder,
 *       custom borders).</li>
 *   <li>{@link #anvilPrefilter()} — optional anvil/NBT pre-filter SPI
 *       (ADR-016, ADR-026).</li>
 *   <li>{@link #pvpCombatState()} — optional combat-tag authority replacing the
 *       native PvP damage tracker for the {@code /rtp} combat gate.</li>
 *   <li>{@link #rootAction()} — optional override for what a bare {@code /rtp}
 *       (no arguments) does, e.g. open an addon GUI instead of teleporting.</li>
 *   <li>{@link #platformCreator()} — optional override for the arrival platform
 *       a player lands on (schematic paste, procedural pad, …; ADR-058).</li>
 * </ul>
 *
 * <p><b>Effects extension</b> (particles/potions/sounds) is intentionally not
 * routed through this facade; see the {@code effects-api} module and
 * {@code docs/dev/EXTERNAL_HOOKS.md} for that surface.
 *
 * <p><b>Lifecycle.</b> The implementation is provided by {@code rtp-core} during
 * its {@code onEnable}. Calling any registry method before core has loaded throws
 * {@link IllegalStateException} (REQ-RTP-S-006).
 */
public interface RTPHooks {

  /** @return the region-verifier registry; never {@code null}. */
  RegionVerifierRegistry verifiers();

  /** @return the economy provider registry; never {@code null}. */
  EconomyProviderRegistry economy();

  /** @return the placeholder provider registry; never {@code null}. */
  PlaceholderProviderRegistry placeholders();

  /** @return the world-border provider registry; never {@code null}. */
  WorldBorderProviderRegistry worldBorder();

  /** @return the anvil pre-filter registry; never {@code null}. */
  AnvilPrefilterRegistry anvilPrefilter();

  /** @return the PvP combat-state registry; never {@code null}. */
  PvPCombatStateRegistry pvpCombatState();

  /** @return the bare-{@code /rtp} root-action registry; never {@code null}. */
  RootActionRegistry rootAction();

  /** @return the arrival platform-creator registry; never {@code null}. */
  PlatformCreatorRegistry platformCreator();
}
