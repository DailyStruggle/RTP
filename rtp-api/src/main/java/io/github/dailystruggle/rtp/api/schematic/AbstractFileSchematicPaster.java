package io.github.dailystruggle.rtp.api.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Base {@link SchematicPaster} for platforms that read the in-repo Sponge {@code .schem}
 * format (ADR-058 Amendment 1). It implements the platform-neutral half of the contract:
 *
 * <ul>
 *   <li>{@link #load} decodes the file with {@link SpongeSchematicDecoder} and wraps the
 *       result in a completed future. The decode is blocking I/O, so {@code load} MUST be
 *       called off any tick / region thread (S-005); a missing or malformed file yields a
 *       {@code null}-completed future (the caller maps that to a {@code MISSING_SOURCE} /
 *       {@code DECODE_ERROR} audit, never an exception that aborts the teleport).
 *   <li>{@link #supports} accepts {@code .schem} / {@code .schematic} sources whose file
 *       exists.
 * </ul>
 *
 * <p>Subclasses implement only {@link #paste}, applying the planned
 * {@link BlockPlacement}s (see {@link SchematicPlacementPlanner}) through the platform's
 * native block parser on the region-owning thread.
 */
public abstract class AbstractFileSchematicPaster implements SchematicPaster {

  @Override
  public CompletableFuture<LoadedSchematic> load(SchematicSource source) {
    try {
      return CompletableFuture.completedFuture(SpongeSchematicDecoder.decode(source));
    } catch (IOException | RuntimeException e) {
      // Decode failure is reported as a null result (S-004 audit happens at the call site).
      return CompletableFuture.completedFuture(null);
    }
  }

  @Override
  public boolean supports(SchematicSource source) {
    if (source == null) {
      return false;
    }
    String hint = source.formatHint().toLowerCase(Locale.ROOT);
    boolean spongeFormat = hint.isEmpty() || hint.equals("schem") || hint.equals("schematic");
    return spongeFormat && Files.isRegularFile(source.path());
  }
}
