package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.rtp.api.platform.BlockDelta;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Platform-neutral {@link SchematicPaster} that pastes through the world's native bulk
 * block-write primitive ({@link RTPWorld#setBlocks(java.util.List)}) instead of a
 * platform-specific block parser (ADR-058).
 *
 * <p>It inherits the decode half ({@link #load} / {@link #supports}) from
 * {@link AbstractFileSchematicPaster} (the in-repo Sponge {@code .schem} decoder) and implements
 * {@link #paste} entirely in terms of the platform-neutral {@link SchematicPlacementPlanner}: the
 * planned {@link BlockPlacement}s are converted to {@link BlockDelta}s (a location plus the
 * canonical block-state token) and handed to {@code RTPWorld#setBlocks}, which each adapter
 * implements with its native block writer; block-entity payloads (planned via
 * {@link SchematicPlacementPlanner#planBlockEntities}) are then restored through
 * {@link RTPWorld#restoreBlockEntities(java.util.List)}. This is the "one schematic translator"
 * design: the decode/plan half and the paste orchestration live here once, and every platform
 * (Bukkit, Paper, Folia, Fabric) supplies only the two native primitives, so no platform needs a
 * bespoke {@code SchematicPaster} subclass.
 *
 * <p>S-004: the paste is best-effort. A world that does not implement {@code setBlocks} (default
 * no-op returning {@code 0}) yields {@link PasteResult#PASTE_ERROR} and the caller falls back to
 * the default platform; nothing here ever throws to abort the teleport. {@code setBlocks} must be
 * invoked on the region-owning thread, which is the {@code paste} contract the caller already
 * honours.
 *
 * <p>Block-entity payloads (chest contents, ...) are restored on platforms whose
 * {@link RTPWorld#restoreBlockEntities(java.util.List)} override implements a native container
 * API (e.g. Bukkit/Paper/Folia). Platforms that leave it the default no-op (e.g. Fabric, this
 * pass) paste block states only.
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
