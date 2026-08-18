package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.rtp.api.platform.PlatformCreator;
import io.github.dailystruggle.rtp.api.world.RTPLocation;

import java.util.concurrent.CompletableFuture;

/**
 * SPI for loading and pasting region-specific schematics at arrival locations (ADR-058).
 *
 * <p>Enforces a two-phase split: asynchronous off-thread file decode ({@link #load}),
 * followed by region-thread block placement ({@link #paste}).
 */
public interface SchematicPaster extends PlatformCreator {
  /**
   * Loads and decodes a schematic source asynchronously off-thread without loading chunks (S-005).
   *
   * @param source resolved source descriptor; never {@code null}
   * @return future completing with decoded handle, or {@code null} on failure
   */
  CompletableFuture<LoadedSchematic> load(SchematicSource source);

  /**
   * Pastes a decoded schematic at the arrival location on the region-owning thread.
   *
   * @param schematic decoded handle from {@link #load}; never {@code null}
   * @param at        arrival location; never {@code null}
   * @param options   paste options; never {@code null}
   * @return paste outcome for caller auditing; never {@code null}
   */
  PasteResult paste(LoadedSchematic schematic, RTPLocation at, PasteOptions options);

  /**
   * @param source the source descriptor; never {@code null}
   * @return {@code true} when this paster can service the source on the running platform
   */
  boolean supports(SchematicSource source);
}
