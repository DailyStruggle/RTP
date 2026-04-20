/**
 * Platform-neutral vanilla Anvil region-file decode and read-only pre-filter.
 *
 * <p>Originally introduced under ADR-016 inside
 * {@code rtp-spigot-common}; promoted to a shared module under
 * <strong>ADR-016</strong> so that Folia (ADR-016 §11, absorbed from the
 * drafted ADR-016 on 2026-04-19) and eventually Fabric (ADR-016, future)
 * can consume the same decode stack without
 * duplicating ~12 source files plus tests. See
 * {@code docs/dev/ANVIL_SHARED_MODULE_PLAN.md} for the extraction record.</p>
 *
 * <h2>Module invariants</h2>
 * Per ADR-016 §4, this module shall not import:
 * <ul>
 *   <li>any RTP module ({@code rtp-api}, {@code rtp-core},
 *       {@code commands-api}, {@code effects-api}, or any platform adapter);</li>
 *   <li>any platform package ({@code org.bukkit.*}, {@code io.papermc.*},
 *       {@code net.minecraft.*}, {@code net.fabricmc.*}).</li>
 * </ul>
 * Public types here exchange only platform-neutral values: {@code byte[]},
 * {@link java.nio.file.Path}, {@link java.util.Optional}, primitives, and
 * the module's own decode types. The boundary is enforced by
 * {@code AnvilPackageBoundaryArchTest}.
 *
 * <h2>Three-verdict contract (advisory under ADR-016)</h2>
 * {@link io.github.dailystruggle.rtp.anvil.AnvilPrefilter#probeSyncDetailed}
 * returns one of {@link io.github.dailystruggle.rtp.anvil.Verdict#REJECT},
 * {@link io.github.dailystruggle.rtp.anvil.Verdict#ACCEPT}, or
 * {@link io.github.dailystruggle.rtp.anvil.Verdict#UNKNOWN}. Per ADR-016 the
 * verdict is advisory telemetry only — both {@code ACCEPT} and {@code REJECT}
 * carry a decoded
 * {@link io.github.dailystruggle.rtp.anvil.AnvilChunkView}, which the
 * platform adapter wraps in a source-union {@code RTPChunk} so the selection
 * algorithm can probe the chunk off-tick / off-region without a live load.
 * The live {@code RTPChunk.isSafe(...)} re-check at teleport commit remains
 * the authoritative arbiter (ADR-016 §4 / ADR-016).
 *
 * <h2>Reconciler strategy</h2>
 * Palette identifier reconciliation is split: the platform-neutral default
 * ({@link io.github.dailystruggle.rtp.anvil.AnvilPrefilter#DEFAULT_RECONCILER})
 * strips a {@code namespace:} prefix and uppercases via {@link java.util.Locale#ROOT}.
 * Platform adapters that need a registry-aware reconciler (e.g. Bukkit
 * {@code Material.matchMaterial} on Spigot) supply a
 * {@link java.util.function.UnaryOperator UnaryOperator&lt;String&gt;} on the
 * reconciler-aware overloads.
 */
package io.github.dailystruggle.rtp.anvil;
