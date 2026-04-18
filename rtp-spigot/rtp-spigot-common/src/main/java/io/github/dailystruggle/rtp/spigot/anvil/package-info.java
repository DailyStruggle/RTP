/**
 * Read-only Anvil region-file pre-filter for vanilla Spigot.
 *
 * <p>See {@code docs/adr/ADR-016-anvil-readonly-prefilter.md} for the authoritative
 * decision and {@code docs/dev/ANVIL_PREFILTER_PLAN.md} for the implementation map.</p>
 *
 * <h2>Scope boundary (Spigot-exclusive)</h2>
 * This package is deliberately confined to {@code rtp-spigot-common}. It is never imported
 * from {@code rtp-api}, {@code rtp-core}, {@code rtp-paper}, or {@code rtp-folia}:
 * <ul>
 *   <li>{@code PaperRTPWorld} already overrides {@code getChunkAt} with Paper's native async
 *       path, so the pre-filter would be pure overhead on Paper.</li>
 *   <li>{@code FoliaRTPWorld} extends {@link io.github.dailystruggle.rtp.api.world.RTPWorld}
 *       directly (not {@code BukkitRTPWorld}), so the pre-filter is structurally unreachable
 *       on Folia.</li>
 * </ul>
 * Structural exclusivity is enforced by an ArchUnit boundary rule; no runtime server-brand
 * sniff or {@code instanceof} is required.
 *
 * <h2>Three-verdict contract</h2>
 * The pre-filter returns exactly one of {@code REJECT}, {@code ACCEPT}, or {@code UNKNOWN}.
 * {@code REJECT} short-circuits the candidate off-thread without a live chunk load; the other
 * two verdicts fall through to the existing load path where the authoritative live
 * {@code chunk.isSafe(...)} re-check remains the source of truth.
 */
package io.github.dailystruggle.rtp.spigot.anvil;
