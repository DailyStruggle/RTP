package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.rtp.api.block.BlockStateString;
import io.github.dailystruggle.rtp.api.world.RTPLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Platform-neutral planner that turns a decoded {@link LoadedSchematic} plus an arrival
 * {@link RTPLocation} and {@link PasteOptions} into a flat list of absolute
 * {@link BlockPlacement}s. This holds all the anchor math and air-skip logic so each
 * platform paster only has to apply the resulting placements through its native block
 * parser, and so the logic is fully unit-testable without a live world (ADR-058
 * Amendment 1).
 *
 * <p>Anchor semantics (footprint center is {@code (width-1)/2}, {@code (length-1)/2}):
 *
 * <ul>
 *   <li>{@link PasteAnchor#BOTTOM_CENTER}: arrival is the horizontal center and the
 *       vertical bottom (cell {@code y=0} lands at the arrival Y, structure rises upward).
 *   <li>{@link PasteAnchor#CENTER}: arrival is the geometric center on all three axes.
 *   <li>{@link PasteAnchor#ORIGIN}: the schematic's own origin lands at the arrival block.
 * </ul>
 *
 * <p>When {@link PasteOptions#pasteAir()} is {@code false}, air cells are omitted so the
 * surrounding terrain is preserved (the configured behavior for arrival pads).
 */
public final class SchematicPlacementPlanner {

  private SchematicPlacementPlanner() {
  }

  /**
   * Plans the absolute block writes for a paste.
   *
   * @param schematic the decoded schematic; never {@code null}
   * @param at        the arrival location; never {@code null}
   * @param options   the paste tuning; never {@code null}
   * @return an ordered, immutable list of placements (air-skipped per {@code options})
   */
  public static List<BlockPlacement> plan(LoadedSchematic schematic, RTPLocation at,
                                          PasteOptions options) {
    Objects.requireNonNull(schematic, "schematic");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(options, "options");

    int width = schematic.width();
    int height = schematic.height();
    int length = schematic.length();
    List<String> palette = schematic.palette();

    int[] base = anchorBase(schematic, at, options);
    int baseX = base[0];
    int baseY = base[1];
    int baseZ = base[2];

    List<BlockPlacement> out = new ArrayList<>();
    for (int y = 0; y < height; y++) {
      for (int z = 0; z < length; z++) {
        for (int x = 0; x < width; x++) {
          int idx = schematic.paletteIndexAt(x, y, z);
          if (idx < 0 || idx >= palette.size()) {
            continue;
          }
          String state = palette.get(idx);
          if (state == null) {
            continue;
          }
          if (!options.pasteAir() && isAir(state)) {
            continue;
          }
          out.add(new BlockPlacement(baseX + x, baseY + y, baseZ + z, state));
        }
      }
    }
    return out;
  }

  /**
   * Computes the absolute world position of each decoded {@link BlockEntityData}, applying the
   * same anchor math {@link #plan} uses for block placements. This lets a platform paster restore
   * block-entity payloads (chest contents, sign text, ...) at the correct world coordinates after
   * the blocks are written.
   *
   * @param schematic the decoded schematic; never {@code null}
   * @param at        the arrival location; never {@code null}
   * @param options   the paste tuning; never {@code null}
   * @return an ordered, immutable list of placed block entities (empty when none decoded)
   */
  public static List<PlacedBlockEntity> planBlockEntities(LoadedSchematic schematic,
                                                          RTPLocation at, PasteOptions options) {
    Objects.requireNonNull(schematic, "schematic");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(options, "options");

    List<BlockEntityData> entities = schematic.blockEntities();
    if (entities.isEmpty()) {
      return java.util.Collections.emptyList();
    }
    int[] base = anchorBase(schematic, at, options);
    List<PlacedBlockEntity> out = new ArrayList<>(entities.size());
    for (BlockEntityData be : entities) {
      out.add(new PlacedBlockEntity(
          base[0] + be.x(), base[1] + be.y(), base[2] + be.z(), be));
    }
    return out;
  }

  /**
   * @return the {@code {baseX, baseY, baseZ}} world offset of the schematic's local origin
   *     (cell {@code 0,0,0}) under the given anchor.
   */
  private static int[] anchorBase(LoadedSchematic schematic, RTPLocation at,
                                  PasteOptions options) {
    int width = schematic.width();
    int height = schematic.height();
    int length = schematic.length();
    int centerX = (width - 1) / 2;
    int centerZ = (length - 1) / 2;
    switch (options.anchor()) {
      case CENTER:
        return new int[] {at.x() - centerX, at.y() - (height - 1) / 2, at.z() - centerZ};
      case SURFACE_CENTER: {
        // Shift the schematic down so the player lands on a standable floor in its center
        // column: the lowest solid cell that has at least two air/empty cells above it
        // (feet + head clearance). This is the surface the safety check would accept, so no
        // iterative re-paste is needed. Using the lowest such floor (rather than the topmost
        // solid block) keeps the player *inside* roofed structures instead of on the roof.
        int floorY = standableFloorYInColumn(schematic, centerX, centerZ);
        int baseY = (floorY >= 0) ? at.y() - floorY - 1 : at.y();
        return new int[] {at.x() - centerX, baseY, at.z() - centerZ};
      }
      case ORIGIN:
        return new int[] {
            at.x() + schematic.offsetX(),
            at.y() + schematic.offsetY(),
            at.z() + schematic.offsetZ()};
      case BOTTOM_CENTER:
      default:
        return new int[] {at.x() - centerX, at.y(), at.z() - centerZ};
    }
  }

  /**
   * Finds the lowest standable floor in the given column ({@code x},{@code z}): the lowest
   * relative Y holding a solid cell that has at least two non-solid (air/empty/out-of-bounds)
   * cells directly above it, i.e. feet + head clearance. This is the surface a teleport safety
   * check would accept, and choosing the lowest such floor places the player inside a roofed
   * structure rather than on top of its roof.
   *
   * @return the floor's relative Y, or {@code -1} when the column has no standable floor.
   */
  private static int standableFloorYInColumn(LoadedSchematic schematic, int x, int z) {
    int height = schematic.height();
    for (int y = 0; y < height; y++) {
      if (!isSolidCell(schematic, x, y, z)) {
        continue;
      }
      if (!isSolidCell(schematic, x, y + 1, z) && !isSolidCell(schematic, x, y + 2, z)) {
        return y;
      }
    }
    return -1;
  }

  /**
   * @return {@code true} when the cell at ({@code x},{@code y},{@code z}) holds a solid
   *     (non-air, valid-palette) block; {@code false} for air, empty, or out-of-bounds cells.
   */
  private static boolean isSolidCell(LoadedSchematic schematic, int x, int y, int z) {
    if (y < 0 || y >= schematic.height()) {
      return false;
    }
    List<String> palette = schematic.palette();
    int idx = schematic.paletteIndexAt(x, y, z);
    if (idx < 0 || idx >= palette.size()) {
      return false;
    }
    String state = palette.get(idx);
    return state != null && !isAir(state);
  }

  private static boolean isAir(String state) {
    try {
      return BlockStateString.parse(state).isAir();
    } catch (IllegalArgumentException e) {
      // A malformed palette entry is not air; let the platform parser reject it later.
      return false;
    }
  }
}
