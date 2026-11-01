package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.rtp.api.world.RTPLocation;

import java.util.concurrent.CompletableFuture;

/**
 * Default no-op {@link SchematicPaster} singleton (ADR-058, S-006).
 *
 * <p>Returns {@link PasteResult#SKIPPED_UNSUPPORTED} and completes empty futures.
 */
public final class NoOpSchematicPaster implements SchematicPaster {
  /** Shared stateless instance. */
  public static final NoOpSchematicPaster INSTANCE = new NoOpSchematicPaster();

  private NoOpSchematicPaster() {}

  @Override
  public CompletableFuture<LoadedSchematic> load(SchematicSource source) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public PasteResult paste(LoadedSchematic schematic, RTPLocation at, PasteOptions options) {
    return PasteResult.SKIPPED_UNSUPPORTED;
  }

  @Override
  public boolean supports(SchematicSource source) {
    return false;
  }
}
