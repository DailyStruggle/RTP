package io.github.dailystruggle.rtp.bukkitplatform.world;

import io.github.dailystruggle.rtp.api.schematic.AbstractFileSchematicPaster;
import io.github.dailystruggle.rtp.api.schematic.BlockPlacement;
import io.github.dailystruggle.rtp.api.schematic.LoadedSchematic;
import io.github.dailystruggle.rtp.api.schematic.PasteOptions;
import io.github.dailystruggle.rtp.api.schematic.PasteResult;
import io.github.dailystruggle.rtp.api.schematic.SchematicPlacementPlanner;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.logging.Level;

/**
 * Bukkit / Paper / Folia {@link io.github.dailystruggle.rtp.api.schematic.SchematicPaster}
 * built on the in-house Sponge decoder (ADR-058 Amendment 1) and the platform-neutral
 * {@link SchematicPlacementPlanner}. {@link #load} (decode) is inherited; {@code paste}
 * applies the planned {@link BlockPlacement}s through the server's native block parser
 * ({@code Bukkit.createBlockData(String)}) and must be invoked on the region-owning thread
 * by the caller (Bukkit/Paper main thread, Folia region thread).
 *
 * <p>No WorldEdit dependency: block states are parsed natively. WorldEdit/FAWE may later be
 * wired as an optional accelerator for very large schematics, but it is never required.
 *
 * <p>S-004: a per-block parse/place failure is counted and audited, never thrown; the paste
 * returns {@link PasteResult#PASTED} if any block landed and {@link PasteResult#PASTE_ERROR}
 * only if nothing could be applied. The caller treats either as best-effort and never aborts
 * the teleport.
 */
public final class BukkitSchematicPaster extends AbstractFileSchematicPaster {

  @Override
  public PasteResult paste(LoadedSchematic schematic, RTPLocation at, PasteOptions options) {
    if (schematic == null || at == null || options == null) {
      return PasteResult.PASTE_ERROR;
    }
    RTPWorld<?> rtpWorld = at.world();
    if (!(rtpWorld instanceof BukkitRTPWorld) || !(rtpWorld.world() instanceof World world)) {
      return PasteResult.SKIPPED_UNSUPPORTED;
    }

    List<BlockPlacement> placements = SchematicPlacementPlanner.plan(schematic, at, options);
    int placed = 0;
    int failed = 0;
    for (BlockPlacement p : placements) {
      try {
        BlockData data = Bukkit.createBlockData(p.blockState());
        world.getBlockAt(p.x(), p.y(), p.z()).setBlockData(data, false);
        placed++;
      } catch (RuntimeException e) {
        failed++;
      }
    }

    if (failed > 0) {
      RTP.log(Level.WARNING, "[RTP] schematic '" + schematic.source().name() + "' paste applied "
          + placed + " block(s); " + failed + " block-state(s) could not be parsed on this "
          + "server version and were skipped (S-004).");
    }
    // Block-entity NBT (chest contents, sign text) is not yet reconstructed here; the block
    // is placed but its inventory/text stays default. Audited so it is never silently lost.
    if (!schematic.blockEntities().isEmpty()) {
      RTP.log(Level.INFO, "[RTP] schematic '" + schematic.source().name() + "' has "
          + schematic.blockEntities().size() + " block-entity payload(s) that are placed as "
          + "empty blocks (NBT restore not yet implemented).");
    }
    return placed > 0 ? PasteResult.PASTED : PasteResult.PASTE_ERROR;
  }
}
