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

    int centerX = (width - 1) / 2;
    int centerZ = (length - 1) / 2;

    int baseX;
    int baseY;
    int baseZ;
    switch (options.anchor()) {
      case CENTER:
        baseX = at.x() - centerX;
        baseY = at.y() - (height - 1) / 2;
        baseZ = at.z() - centerZ;
        break;
      case ORIGIN:
        baseX = at.x() + schematic.offsetX();
        baseY = at.y() + schematic.offsetY();
        baseZ = at.z() + schematic.offsetZ();
        break;
      case BOTTOM_CENTER:
      default:
        baseX = at.x() - centerX;
        baseY = at.y();
        baseZ = at.z() - centerZ;
        break;
    }

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

  private static boolean isAir(String state) {
    try {
      return BlockStateString.parse(state).isAir();
    } catch (IllegalArgumentException e) {
      // A malformed palette entry is not air; let the platform parser reject it later.
      return false;
    }
  }
}
