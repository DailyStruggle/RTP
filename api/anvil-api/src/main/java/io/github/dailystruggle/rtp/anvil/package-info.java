/**
 * Platform-neutral vanilla Anvil region-file decode and read-only pre-filter (ADR-016).
 *
 * <p>Module invariants: no imports of any RTP module or platform package
 * ({@code org.bukkit.*}, {@code io.papermc.*}, {@code net.minecraft.*},
 * {@code net.fabricmc.*}). Public types exchange only {@code byte[]},
 * {@link java.nio.file.Path}, {@link java.util.Optional}, primitives, and
 * the module's own decode types. Enforced by {@code AnvilPackageBoundaryArchTest}.
 *
 * <p>{@link io.github.dailystruggle.rtp.anvil.AnvilPrefilter#probeSyncDetailed}
 * returns {@link io.github.dailystruggle.rtp.anvil.Verdict#REJECT},
 * {@link io.github.dailystruggle.rtp.anvil.Verdict#ACCEPT}, or
 * {@link io.github.dailystruggle.rtp.anvil.Verdict#UNKNOWN} - advisory only;
 * the live {@code RTPChunk.isSafe(...)} at teleport commit remains authoritative.
 *
 * <p>Palette identifier reconciliation: the default
 * ({@link io.github.dailystruggle.rtp.anvil.AnvilPrefilter#DEFAULT_RECONCILER})
 * strips {@code namespace:} and uppercases via {@link java.util.Locale#ROOT};
 * registry-aware reconcilers are passed via
 * {@link java.util.function.UnaryOperator UnaryOperator&lt;String&gt;}.
 */
package io.github.dailystruggle.rtp.anvil;
