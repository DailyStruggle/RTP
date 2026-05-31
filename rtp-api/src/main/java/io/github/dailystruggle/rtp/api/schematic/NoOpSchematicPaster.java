package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.rtp.api.world.RTPLocation;

import java.util.concurrent.CompletableFuture;

/**
 * Default {@link SchematicPaster} installed in every adapter's swappable holder until a
 * native (WorldEdit/FAWE or Fabric-NBT) paster, or an addon-supplied one, replaces it.
 *
 * <p>Being a no-op singleton (never {@code null}) satisfies S-006: schematic API entry
 * points never NPE. Every call reports {@link PasteResult#SKIPPED_UNSUPPORTED} so the
 * core invocation point audits the absence of a real paster exactly once and proceeds
 * with the teleport unmodified.
 *
 * <p>See ADR-058 §2.
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
