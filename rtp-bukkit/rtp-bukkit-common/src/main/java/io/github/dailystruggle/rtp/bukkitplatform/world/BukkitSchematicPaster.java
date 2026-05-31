package io.github.dailystruggle.rtp.bukkitplatform.world;

import io.github.dailystruggle.rtp.api.schematic.AbstractFileSchematicPaster;
import io.github.dailystruggle.rtp.api.schematic.BlockPlacement;
import io.github.dailystruggle.rtp.api.schematic.LoadedSchematic;
import io.github.dailystruggle.rtp.api.schematic.PasteOptions;
import io.github.dailystruggle.rtp.api.schematic.PasteResult;
import io.github.dailystruggle.rtp.api.schematic.PlacedBlockEntity;
import io.github.dailystruggle.rtp.api.schematic.SchematicPlacementPlanner;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.Map;
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
    // ADR-058: gate on the underlying org.bukkit.World, not the wrapper type. Bukkit/Paper use
    // BukkitRTPWorld, but Folia's FoliaRTPWorld extends RTPWorld directly while still wrapping a
    // Bukkit World; an instanceof BukkitRTPWorld check would wrongly skip the Folia region world.
    if (rtpWorld == null || !(rtpWorld.world() instanceof World world)) {
      return PasteResult.SKIPPED_UNSUPPORTED;
    }

    List<BlockPlacement> placements = SchematicPlacementPlanner.plan(schematic, at, options);
    String name = schematic.source().name();
    RTP.log(Level.FINE, "[RTP] schematic '" + name + "' paste begin at (" + at.x() + ","
        + at.y() + "," + at.z() + ") world=" + world.getName() + " anchor=" + options.anchor()
        + " placements=" + placements.size() + " blockEntities="
        + schematic.blockEntities().size() + ".");
    int placed = 0;
    int failed = 0;
    String firstFailedState = null;
    String firstFailedReason = null;
    for (BlockPlacement p : placements) {
      org.bukkit.block.Block block = world.getBlockAt(p.x(), p.y(), p.z());
      org.bukkit.Material before = block.getType();
      try {
        BlockData data = Bukkit.createBlockData(p.blockState());
        block.setBlockData(data, false);
        placed++;
        // Rigorous per-block trace (requested): coordinate, prior material, written state, and
        // the material actually present after the write so a mismatch (e.g. a block that silently
        // reverts) is visible in the log rather than inferred.
        RTP.log(Level.FINER, "[RTP] schematic '" + name + "' set block (" + p.x() + "," + p.y()
            + "," + p.z() + ") " + before + " -> '" + p.blockState() + "' now="
            + block.getType() + ".");
      } catch (RuntimeException e) {
        failed++;
        RTP.log(Level.FINE, "[RTP] schematic '" + name + "' FAILED block (" + p.x() + "," + p.y()
            + "," + p.z() + ") was=" + before + " state='" + p.blockState() + "' reason="
            + e.getClass().getSimpleName()
            + (e.getMessage() != null ? ": " + e.getMessage() : "") + ".");
        if (firstFailedState == null) {
          firstFailedState = p.blockState();
          firstFailedReason = e.getClass().getSimpleName()
              + (e.getMessage() != null ? ": " + e.getMessage() : "");
        }
      }
    }

    if (placements.isEmpty()) {
      // Nothing to place at all (empty/all-air plan) — surface it so an operator who sees no
      // structure appear knows the decode produced no solid blocks rather than a paste error.
      RTP.log(Level.WARNING, "[RTP] schematic '" + schematic.source().name() + "' produced no "
          + "placeable blocks (empty or all-air plan); nothing was pasted (S-004).");
    }
    if (failed > 0) {
      RTP.log(Level.WARNING, "[RTP] schematic '" + schematic.source().name() + "' paste applied "
          + placed + " block(s); " + failed + " block-state(s) could not be parsed on this "
          + "server version and were skipped (S-004). First failure: '" + firstFailedState
          + "' (" + firstFailedReason + ").");
    }
    // Block-entity payloads (chest contents, ...) are restored on a best-effort basis (S-004):
    // container inventories are filled from the decoded NBT Items list. Anything we cannot
    // reconstruct (sign text, custom NBT, unknown ids) is skipped and audited, never thrown.
    if (!schematic.blockEntities().isEmpty()) {
      restoreBlockEntities(schematic, at, options, world);
    }
    return placed > 0 ? PasteResult.PASTED : PasteResult.PASTE_ERROR;
  }

  /**
   * Restores container inventories (chests, etc.) from each block entity's decoded {@code Items}
   * NBT list. Per-entity failures are counted and audited (S-004); they never abort the paste.
   */
  // Package-private (not private) so BukkitSchematicPasterChestTest can drive the real
  // container-fill path against a MockBukkit chest; MockBukkit cannot run Bukkit.createBlockData
  // (UnimplementedOperationException), so the full paste() cannot place the chest block in a
  // test, but the restore logic itself — the part that was leaving the chest empty — is exercised
  // directly by pre-placing a chest and calling this.
  static void restoreBlockEntities(LoadedSchematic schematic, RTPLocation at,
                                           PasteOptions options, World world) {
    int containers = 0;
    int stacks = 0;
    int skipped = 0;
    int entities = 0;
    int withItems = 0;
    int noContainer = 0;
    int verifiedStacks = 0;
    for (PlacedBlockEntity placed : SchematicPlacementPlanner.planBlockEntities(
        schematic, at, options)) {
      entities++;
      List<?> items = asList(placed.data().nbt().get("Items"));
      if (items == null || items.isEmpty()) {
        continue;
      }
      withItems++;
      org.bukkit.block.Block beBlock = world.getBlockAt(placed.x(), placed.y(), placed.z());
      org.bukkit.block.BlockState state = beBlock.getState();
      RTP.log(Level.FINE, "[RTP] schematic '" + schematic.source().name()
          + "' block-entity '" + placed.data().id() + "' at (" + placed.x() + "," + placed.y()
          + "," + placed.z() + ") liveMaterial=" + beBlock.getType() + " stateClass="
          + state.getClass().getSimpleName() + " items=" + items.size() + ".");
      if (!(state instanceof org.bukkit.block.Container container)) {
        skipped += items.size();
        noContainer++;
        // FINE diagnostic: a "chest is empty" report can be traced (with verbose logging on) to
        // the actual reason: the planned block-entity coordinate does not hold a container on the
        // live server (chest block failed to place, or a coordinate/anchor mismatch). S-004:
        // logged, never thrown.
        RTP.log(Level.FINE, "[RTP] schematic '" + schematic.source().name()
            + "' block-entity at (" + placed.x() + "," + placed.y() + "," + placed.z()
            + ") carries " + items.size() + " item(s) but the block there is "
            + state.getType() + " (not a container); items not restored (S-004).");
        continue;
      }
      org.bukkit.inventory.Inventory inv = container.getInventory();
      int size = inv.getSize();
      for (Object o : items) {
        if (!(o instanceof Map<?, ?> item)) {
          continue;
        }
        org.bukkit.inventory.ItemStack stack = toItemStack(item);
        if (stack == null) {
          skipped++;
          continue;
        }
        int slot = intValue(item.get("Slot"), -1);
        try {
          if (slot >= 0 && slot < size) {
            inv.setItem(slot, stack);
          } else {
            inv.addItem(stack);
          }
          stacks++;
          RTP.log(Level.FINER, "[RTP] schematic '" + schematic.source().name()
              + "' set item slot=" + slot + " " + stack.getType() + " x" + stack.getAmount()
              + " into live " + container.getType() + ".");
        } catch (RuntimeException e) {
          skipped++;
          RTP.log(Level.FINE, "[RTP] schematic '" + schematic.source().name()
              + "' FAILED to set item slot=" + slot + " reason="
              + e.getClass().getSimpleName()
              + (e.getMessage() != null ? ": " + e.getMessage() : "") + ".");
        }
      }
      // CRITICAL: do NOT call container.update(...) here. For a block that is already placed in
      // the world (which the freshly-pasted chest is), org.bukkit.block.Container#getInventory()
      // returns the LIVE tile-entity inventory, so the setItem/addItem calls above already wrote
      // the items straight into the world. Calling update(true,false) afterwards copies the
      // BlockState SNAPSHOT's inventory -- captured empty at getState() time -- back over the live
      // tile, silently WIPING the items (and still returning true). That snapshot-overwrite was the
      // root cause of the persistently-empty chest (stacksSet=3 but verifiedStacks=0). The live
      // write needs no commit step.
      containers++;
      // Verify against a fresh read of the world so the count reflects what actually persisted.
      org.bukkit.block.BlockState confirm =
          world.getBlockAt(placed.x(), placed.y(), placed.z()).getState();
      if (confirm instanceof org.bukkit.block.Container confirmContainer) {
        for (org.bukkit.inventory.ItemStack s : confirmContainer.getInventory().getContents()) {
          if (s != null && !s.getType().isAir()) {
            verifiedStacks++;
          }
        }
        RTP.log(Level.FINE, "[RTP] schematic '" + schematic.source().name()
            + "' container at (" + placed.x() + "," + placed.y() + "," + placed.z()
            + ") now holds " + verifiedStacks + " stack(s) after live fill.");
      } else {
        RTP.log(Level.FINE, "[RTP] schematic '" + schematic.source().name()
            + "' verification re-fetch is NOT a container (" + confirm.getType()
            + ") at (" + placed.x() + "," + placed.y() + "," + placed.z() + ").");
      }
    }
    // FINE summary: an empty chest in-game is otherwise indistinguishable from a restore that ran
    // fine, so surface the full accounting (entities scanned, how many carried items, containers
    // actually found at the planned coords, stacks set) under verbose logging. This is the one
    // piece of runtime evidence needed to tell "restore never found a container" from "restore
    // set items but they didn't persist".
    if (entities > 0) {
      RTP.log(Level.FINE, "[RTP] schematic '" + schematic.source().name()
          + "' block-entity restore: entities=" + entities + " withItems=" + withItems
          + " containersFilled=" + containers + " stacksSet=" + stacks
          + " verifiedStacks=" + verifiedStacks
          + " noContainer=" + noContainer + " skippedItems=" + skipped + ".");
    }
  }

  /**
   * Builds a Bukkit {@link org.bukkit.inventory.ItemStack} from a decoded item compound
   * ({@code id} + {@code count}/{@code Count}). Returns {@code null} for an unknown material.
   */
  private static org.bukkit.inventory.ItemStack toItemStack(Map<?, ?> item) {
    Object idObj = item.get("id");
    if (!(idObj instanceof String id) || id.isEmpty()) {
      return null;
    }
    org.bukkit.Material material = org.bukkit.Material.matchMaterial(id);
    if (material == null || material.isAir()) {
      return null;
    }
    // Modern schematics use "count"; pre-1.20.5 used "Count". Default to a single item.
    int count = intValue(item.get("count"), intValue(item.get("Count"), 1));
    if (count < 1) {
      count = 1;
    }
    return new org.bukkit.inventory.ItemStack(material, count);
  }

  private static List<?> asList(Object o) {
    return (o instanceof List<?> list) ? list : null;
  }

  private static int intValue(Object o, int fallback) {
    return (o instanceof Number n) ? n.intValue() : fallback;
  }
}
