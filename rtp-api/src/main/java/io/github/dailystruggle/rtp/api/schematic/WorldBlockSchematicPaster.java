package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.rtp.api.platform.BlockDelta;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Platform-neutral {@link SchematicPaster} pasting via {@link RTPWorld#setBlocks} (ADR-058).
 * Paste is best-effort and never aborts a teleport (S-004).
 */
public final class WorldBlockSchematicPaster extends AbstractFileSchematicPaster {

  @Override
  public PasteResult paste(LoadedSchematic schematic, RTPLocation at, PasteOptions options) {
    if (schematic == null || at == null || options == null) {
      return PasteResult.PASTE_ERROR;
    }
    RTPWorld<?> world = at.world();
    if (world == null) {
      return PasteResult.SKIPPED_UNSUPPORTED;
    }

    List<BlockPlacement> placements = SchematicPlacementPlanner.plan(schematic, at, options);
    String name = schematic.source() != null ? schematic.source().name() : "?";
    if (placements.isEmpty()) {
      logIfPossible(Level.WARNING, "[RTP] schematic '" + name + "' produced no placeable blocks "
          + "(empty or all-air plan); nothing was pasted (S-004).");
      return PasteResult.PASTE_ERROR;
    }

    List<BlockDelta> blocks = new ArrayList<>(placements.size());
    for (BlockPlacement p : placements) {
      blocks.add(new BlockDelta(p.x(), p.y(), p.z(), p.blockState()));
    }

    int placed;
    try {
      placed = world.setBlocks(blocks);
    } catch (RuntimeException e) {
      // The native writer must not abort a teleport (S-004): downgrade any throw to a failed
      // paste and let the caller fall back to the default platform.
      logIfPossible(Level.WARNING, "[RTP] schematic '" + name + "' native block write threw ("
          + e.getClass().getSimpleName()
          + (e.getMessage() != null ? ": " + e.getMessage() : "")
          + "); falling back to default platform (S-004).");
      return PasteResult.PASTE_ERROR;
    }

    logIfPossible(Level.FINE, "[RTP] schematic '" + name + "' native paste placed " + placed
        + " of " + placements.size() + " planned block(s) at (" + at.x() + "," + at.y() + ","
        + at.z() + ") world=" + world.name() + " anchor=" + options.anchor() + ".");

    // Second half of the symmetric primitive: restore block-entity payloads (container contents,
    // ...) after the block states are written. Platforms without a native container API leave
    // RTPWorld#restoreBlockEntities a no-op; the block states are still pasted. S-004: any throw
    // from the native restore is downgraded to an audited warning and never aborts the teleport.
    List<PlacedBlockEntity> blockEntities =
        SchematicPlacementPlanner.planBlockEntities(schematic, at, options);
    if (!blockEntities.isEmpty()) {
      try {
        int restored = world.restoreBlockEntities(blockEntities);
        logIfPossible(Level.FINE, "[RTP] schematic '" + name + "' native restore filled "
            + restored + " of " + blockEntities.size() + " block-entity payload(s).");
      } catch (RuntimeException e) {
        logIfPossible(Level.WARNING, "[RTP] schematic '" + name + "' native block-entity restore "
            + "threw (" + e.getClass().getSimpleName()
            + (e.getMessage() != null ? ": " + e.getMessage() : "")
            + "); block states were still pasted (S-004).");
      }
    }
    return placed > 0 ? PasteResult.PASTED : PasteResult.PASTE_ERROR;
  }

  private static void logIfPossible(Level level, String message) {
    io.github.dailystruggle.rtp.api.server.RTPServerAccessor accessor =
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor;
    if (accessor != null) {
      accessor.log(level, message);
    }
  }
}
