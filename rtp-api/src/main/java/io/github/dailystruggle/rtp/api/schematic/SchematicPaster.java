package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.rtp.api.world.RTPLocation;

import java.util.concurrent.CompletableFuture;

/**
 * Platform-neutral SPI for loading and pasting a region-specific schematic at the
 * confirmed arrival {@link RTPLocation}. See ADR-058.
 *
 * <p>The contract enforces a strict two-phase split:
 *
 * <ul>
 *   <li>{@link #load} performs the blocking file read + decode and MUST run off any
 *       tick / region thread (S-005). It returns a {@link CompletableFuture} so the
 *       caller never blocks the pipeline on a {@code .get()}.
 *   <li>{@link #paste} performs only the block writes and MUST be invoked by the caller
 *       on the thread that owns the target region (Folia region thread; Bukkit/Paper main
 *       thread). It performs no file I/O.
 * </ul>
 *
 * <p>Implementations never throw to abort a teleport: every non-success condition is
 * reported as a {@link PasteResult} so the core invocation point can audit it (S-004)
 * and proceed with the teleport unmodified.
 */
public interface SchematicPaster {
  /**
   * Loads and decodes a schematic source off-thread. Implementations MUST NOT touch the
   * world or load chunks here (S-005).
   *
   * @param source the resolved source descriptor; never {@code null}
   * @return a future completing with the decoded handle, or completing with {@code null}
   *     when the source is missing / undecodable (the caller maps {@code null} to a
   *     {@link PasteResult#MISSING_SOURCE} / {@link PasteResult#DECODE_ERROR} audit)
   */
  CompletableFuture<LoadedSchematic> load(SchematicSource source);

  /**
   * Pastes a previously-loaded schematic at the arrival location. MUST be invoked on the
   * region-owning thread (caller's contract). Performs block writes only; no file I/O.
   *
   * @param schematic the decoded handle from {@link #load}; never {@code null}
   * @param at        the confirmed arrival location; never {@code null}
   * @param options   paste tuning; never {@code null}
   * @return the outcome, for the caller to audit; never {@code null}
   */
  PasteResult paste(LoadedSchematic schematic, RTPLocation at, PasteOptions options);

  /**
   * @param source the source descriptor; never {@code null}
   * @return {@code true} when this paster can service the source on the running platform
   */
  boolean supports(SchematicSource source);
}
