package io.github.dailystruggle.rtp.bukkitplatform.world;

import io.github.dailystruggle.rtp.api.platform.BlockDelta;
import io.github.dailystruggle.rtp.api.schematic.PlacedBlockEntity;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Shared Bukkit-family native block writer (ADR-058). Hosts the two platform-neutral primitives
 * the single region-schematic paster ({@code WorldBlockSchematicPaster}) drives -
 * {@link #setBlocks(World, List)} (block-state writes) and
 * {@link #restoreBlockEntities(World, List)} (container-inventory restore) - so that both
 * {@code BukkitRTPWorld} (Bukkit/Paper) and {@code FoliaRTPWorld} (which extends
 * {@code RTPWorld} directly) can expose them without a per-platform {@code SchematicPaster}
 * subclass. This is the "we do not need more than one schematic translator" symmetrization: the
 * decode/plan half lives once in {@code rtp-api}; the only platform-specific code is these block
 * writes, behind {@link org.bukkit.World}.
 *
 * <p>Both methods must be invoked on the region-owning thread (Bukkit/Paper main thread, Folia
 * region thread) by the caller. They write to blocks that are already loaded and never load
 * chunks (S-005). Per-block / per-entity failures are counted and audited, never thrown (S-004).
 *
 * <p>No WorldEdit dependency: block states are parsed natively via
 * {@code Bukkit.createBlockData(String)}.
 */
public final class BukkitBlockWriter {

  private BukkitBlockWriter() {
  }

  /**
   * Writes each {@link BlockDelta}'s canonical block-state token (the in-repo Sponge decoder's
   * {@code namespace:id[props]} form, accepted by {@code Bukkit.createBlockData(String)}) into the
   * world via {@code block.setBlockData(data, false)} (no physics).
   *
   * @return the number of blocks successfully placed
   */
  public static int setBlocks(World world, List<BlockDelta> blocks) {
    if (world == null || blocks == null || blocks.isEmpty()) {
      return 0;
    }
    int placed = 0;
    int failed = 0;
    String firstFailedState = null;
    String firstFailedReason = null;
    for (BlockDelta delta : blocks) {
      org.bukkit.block.Block block = world.getBlockAt(delta.x(), delta.y(), delta.z());
      org.bukkit.Material before = block.getType();
      try {
        BlockData data = Bukkit.createBlockData(delta.token());
        block.setBlockData(data, false);
        placed++;
        RTP.log(Level.FINER, "[RTP] schematic set block (" + delta.x() + "," + delta.y() + ","
            + delta.z() + ") " + before + " -> '" + delta.token() + "' now=" + block.getType()
            + ".");
      } catch (RuntimeException e) {
        failed++;
        if (firstFailedState == null) {
          firstFailedState = delta.token();
          firstFailedReason = e.getClass().getSimpleName()
              + (e.getMessage() != null ? ": " + e.getMessage() : "");
        }
      }
    }
    if (failed > 0) {
      RTP.log(Level.WARNING, "[RTP] schematic paste applied " + placed + " block(s); " + failed
          + " block-state(s) could not be parsed on this server version and were skipped (S-004). "
          + "First failure: '" + firstFailedState + "' (" + firstFailedReason + ").");
    }
    return placed;
  }

  /**
   * Restores container inventories (chests, etc.) from each block entity's decoded {@code Items}
   * NBT list, against the blocks already written by {@link #setBlocks(World, List)}. Per-entity
   * failures are counted and audited (S-004); they never abort the paste.
   *
   * @return the number of containers successfully filled
   */
  public static int restoreBlockEntities(World world, List<PlacedBlockEntity> entities) {
    if (world == null || entities == null || entities.isEmpty()) {
      return 0;
    }
    int containers = 0;
    int stacks = 0;
    int skipped = 0;
    int scanned = 0;
    int withItems = 0;
    int noContainer = 0;
    int verifiedStacks = 0;
    for (PlacedBlockEntity placed : entities) {
      scanned++;
      List<?> items = asList(placed.data().nbt().get("Items"));
      if (items == null || items.isEmpty()) {
        continue;
      }
      withItems++;
      org.bukkit.block.Block beBlock = world.getBlockAt(placed.x(), placed.y(), placed.z());
      org.bukkit.block.BlockState state = beBlock.getState();
      RTP.log(Level.FINE, "[RTP] schematic block-entity '" + placed.data().id() + "' at ("
          + placed.x() + "," + placed.y() + "," + placed.z() + ") liveMaterial="
          + beBlock.getType() + " stateClass=" + state.getClass().getSimpleName() + " items="
          + items.size() + ".");
      if (!(state instanceof org.bukkit.block.Container container)) {
        skipped += items.size();
        noContainer++;
        RTP.log(Level.FINE, "[RTP] schematic block-entity at (" + placed.x() + "," + placed.y()
            + "," + placed.z() + ") carries " + items.size() + " item(s) but the block there is "
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
          RTP.log(Level.FINER, "[RTP] schematic set item slot=" + slot + " " + stack.getType()
              + " x" + stack.getAmount() + " into live " + container.getType() + ".");
        } catch (RuntimeException e) {
          skipped++;
          RTP.log(Level.FINE, "[RTP] schematic FAILED to set item slot=" + slot + " reason="
              + e.getClass().getSimpleName()
              + (e.getMessage() != null ? ": " + e.getMessage() : "") + ".");
        }
      }
      // CRITICAL: do NOT call container.update(...) here. For a block already placed in the world
      // (which the freshly-pasted chest is), org.bukkit.block.Container#getInventory() returns the
      // LIVE tile-entity inventory, so the setItem/addItem calls above already wrote the items
      // straight into the world. Calling update(true,false) afterwards copies the BlockState
      // SNAPSHOT's inventory -- captured empty at getState() time -- back over the live tile,
      // silently WIPING the items. The live write needs no commit step.
      containers++;
      org.bukkit.block.BlockState confirm =
          world.getBlockAt(placed.x(), placed.y(), placed.z()).getState();
      if (confirm instanceof org.bukkit.block.Container confirmContainer) {
        for (org.bukkit.inventory.ItemStack s : confirmContainer.getInventory().getContents()) {
          if (s != null && !s.getType().isAir()) {
            verifiedStacks++;
          }
        }
      }
    }
    if (scanned > 0) {
      RTP.log(Level.FINE, "[RTP] schematic block-entity restore: entities=" + scanned
          + " withItems=" + withItems + " containersFilled=" + containers + " stacksSet=" + stacks
          + " verifiedStacks=" + verifiedStacks + " noContainer=" + noContainer + " skippedItems="
          + skipped + ".");
    }
    return containers;
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
