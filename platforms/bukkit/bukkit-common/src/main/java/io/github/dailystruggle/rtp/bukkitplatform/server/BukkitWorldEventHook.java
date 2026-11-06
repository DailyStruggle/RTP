package io.github.dailystruggle.rtp.bukkitplatform.server;

import io.github.dailystruggle.server.api.BlockBreakContext;
import io.github.dailystruggle.server.api.ChunkLoadContext;
import io.github.dailystruggle.server.api.DispatchingPlayerLifecycleHook;
import io.github.dailystruggle.server.api.DispatchingWorldEventHook;
import io.github.dailystruggle.server.api.LeavesDecayContext;
import io.github.dailystruggle.server.api.PlayerBedEnterContext;
import io.github.dailystruggle.server.api.PlayerBedLeaveContext;
import io.github.dailystruggle.server.api.PlayerInteractBlockContext;
import io.github.dailystruggle.server.api.SaplingGrowContext;
import io.github.dailystruggle.server.api.WorldTimeSkipContext;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Bukkit / Paper / Folia implementation of {@link io.github.dailystruggle.server.api.WorldEventHook}
 * backed by {@link DispatchingWorldEventHook} (ADR-049, ADR-115).
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public final class BukkitWorldEventHook extends DispatchingWorldEventHook implements Listener {

  private static final Method TIME_SKIP_REASON_METHOD = resolveTimeSkipReasonMethod();

  private final DispatchingPlayerLifecycleHook playerLifecycleHook =
      new DispatchingPlayerLifecycleHook();
  private volatile BiConsumer<Player, Boolean> sleepSnapshotRecorder;
  private volatile Consumer<UUID> sleepSnapshotRemover;

  /** Returns the player join/quit lifecycle dispatcher backed by this Bukkit listener. */
  public DispatchingPlayerLifecycleHook playerLifecycleHook() {
    return playerLifecycleHook;
  }

  void bindSleepSnapshotHooks(
      BiConsumer<Player, Boolean> recorder, Consumer<UUID> remover) {
    this.sleepSnapshotRecorder = recorder;
    this.sleepSnapshotRemover = remover;
  }

  /**
   * Registers this listener with the Bukkit plugin manager.
   *
   * @param plugin owning plugin; must not be {@code null}
   */
  public void register(Plugin plugin) {
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    if (player == null) return;
    BiConsumer<Player, Boolean> recorder = sleepSnapshotRecorder;
    if (recorder != null) {
      recorder.accept(player, player.isSleeping());
    }
    if (playerLifecycleHook.hasSubscribers()) {
      playerLifecycleHook.fireJoin(player.getUniqueId());
    }
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    if (player == null) return;
    Consumer<UUID> remover = sleepSnapshotRemover;
    if (remover != null) {
      remover.accept(player.getUniqueId());
    }
    if (playerLifecycleHook.hasSubscribers()) {
      playerLifecycleHook.fireQuit(player.getUniqueId());
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
    Player player = event.getPlayer();
    BiConsumer<Player, Boolean> recorder = sleepSnapshotRecorder;
    if (player != null && recorder != null) {
      recorder.accept(player, player.isSleeping());
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
    Player player = event.getPlayer();
    BiConsumer<Player, Boolean> recorder = sleepSnapshotRecorder;
    if (player != null && recorder != null) {
      recorder.accept(player, player.isSleeping());
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    if (!hasSubscribers()) return;
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK
        || event.getClickedBlock() == null
        || event.getClickedBlock().getWorld() == null) {
      return;
    }
    Player player = event.getPlayer();
    Block clicked = event.getClickedBlock();
    Block target = clicked.getRelative(event.getBlockFace());
    boolean mainHand = event.getHand() == EquipmentSlot.HAND;
    ItemStack inHand = event.getItem();
    String itemKey = inHand != null ? readKeyedOrName(inHand.getType()) : "";
    Map<String, String> tags = extractItemTags(inHand);

    PlayerInteractBlockContext ctx =
        new PlayerInteractBlockContext(
            player.getUniqueId(),
            clicked.getWorld().getName(),
            clicked.getX(),
            clicked.getY(),
            clicked.getZ(),
            target.getX(),
            target.getY(),
            target.getZ(),
            mainHand,
            player.getGameMode() == GameMode.CREATIVE,
            isLiquid(clicked) || isLiquid(target),
            itemKey,
            tags,
            player::hasPermission,
            amt -> {
              if (inHand != null) {
                inHand.setAmount(Math.max(0, inHand.getAmount() - amt));
              }
            });
    firePlayerInteractBlock(ctx);
    if (ctx.isCancelled()) {
      event.setCancelled(true);
    }
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void onBlockBreak(BlockBreakEvent event) {
    if (!hasSubscribers()) return;
    Block block = event.getBlock();
    World world = block.getWorld();
    if (world == null) return;
    int x = block.getX();
    int y = block.getY();
    int z = block.getZ();
    BlockBreakContext ctx =
        new BlockBreakContext(
            event.getPlayer() != null ? event.getPlayer().getUniqueId() : null,
            world.getName(),
            x,
            y,
            z,
            readKeyedOrName(block.getType()),
            readBiomeKey(world, x, y, z));
    fireBlockBreak(ctx);
    if (ctx.isCancelled()) {
      event.setCancelled(true);
    }
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void onLeavesDecay(LeavesDecayEvent event) {
    if (!hasSubscribers()) return;
    Block block = event.getBlock();
    World world = block.getWorld();
    if (world == null) return;
    int x = block.getX();
    int y = block.getY();
    int z = block.getZ();
    LeavesDecayContext ctx =
        new LeavesDecayContext(
            world.getName(),
            x,
            y,
            z,
            readKeyedOrName(block.getType()),
            readBiomeKey(world, x, y, z));
    fireLeavesDecay(ctx);
    if (ctx.isCancelled()) {
      event.setCancelled(true);
    }
  }

  @EventHandler
  public void onChunkLoad(ChunkLoadEvent event) {
    if (!hasSubscribers()) return;
    Chunk chunk = event.getChunk();
    World world = chunk.getWorld();
    if (world == null) return;
    fireChunkLoad(
        new ChunkLoadContext(
            world.getName(), chunk.getX(), chunk.getZ(), event.isNewChunk(), world.getSeed()));
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onStructureGrow(StructureGrowEvent event) {
    if (!hasSubscribers()) return;
    Location loc = event.getLocation();
    World world = loc.getWorld();
    if (world == null) return;
    int x = loc.getBlockX();
    int y = loc.getBlockY();
    int z = loc.getBlockZ();
    Block growBlock = world.getBlockAt(x, y, z);
    boolean liquid = isLiquid(growBlock) || isLiquid(growBlock.getRelative(BlockFace.DOWN));
    String blockKey =
        growBlock != null
            ? readKeyedOrName(growBlock.getType())
            : readKeyedOrName(event.getSpecies());
    SaplingGrowContext ctx =
        new SaplingGrowContext(
            world.getName(),
            x,
            y,
            z,
            blockKey,
            readBiomeKey(world, x, y, z),
            liquid);
    fireSaplingGrow(ctx);
    if (ctx.isCancelled()) {
      event.setCancelled(true);
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onPlayerBedEnter(PlayerBedEnterEvent event) {
    Player player = event.getPlayer();
    String result =
        event.getBedEnterResult() != null ? event.getBedEnterResult().name() : "OK";
    BiConsumer<Player, Boolean> recorder = sleepSnapshotRecorder;
    if (player != null && recorder != null && "OK".equalsIgnoreCase(result)) {
      recorder.accept(player, Boolean.TRUE);
    }
    if (!hasSubscribers()) return;
    Block bed = event.getBed();
    World world = bed != null ? bed.getWorld() : (player != null ? player.getWorld() : null);
    if (player == null || world == null) return;
    PlayerBedEnterContext ctx =
        new PlayerBedEnterContext(
            player.getUniqueId(),
            player.getName(),
            world.getName(),
            bed != null ? bed.getX() : player.getLocation().getBlockX(),
            bed != null ? bed.getY() : player.getLocation().getBlockY(),
            bed != null ? bed.getZ() : player.getLocation().getBlockZ(),
            result,
            player::hasPermission);
    firePlayerBedEnter(ctx);
    if (ctx.isCancelled()) {
      event.setCancelled(true);
      if (recorder != null) {
        recorder.accept(player, Boolean.FALSE);
      }
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onPlayerBedLeave(PlayerBedLeaveEvent event) {
    Player player = event.getPlayer();
    BiConsumer<Player, Boolean> recorder = sleepSnapshotRecorder;
    if (player != null && recorder != null) {
      recorder.accept(player, Boolean.FALSE);
    }
    if (!hasSubscribers()) return;
    Block bed = event.getBed();
    World world = bed != null ? bed.getWorld() : (player != null ? player.getWorld() : null);
    if (player == null || world == null) return;
    PlayerBedLeaveContext ctx =
        new PlayerBedLeaveContext(
            player.getUniqueId(),
            player.getName(),
            world.getName(),
            bed != null ? bed.getX() : player.getLocation().getBlockX(),
            bed != null ? bed.getY() : player.getLocation().getBlockY(),
            bed != null ? bed.getZ() : player.getLocation().getBlockZ(),
            world.getTime());
    firePlayerBedLeave(ctx);
    if (ctx.isCancelled()) {
      event.setCancelled(true);
      if (recorder != null) {
        recorder.accept(player, Boolean.TRUE);
      }
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onWorldTimeSkip(TimeSkipEvent event) {
    if (!hasSubscribers()) return;
    World world = event.getWorld();
    if (world == null) return;
    String reason = readSkipReason(event);
    long skipAmount = event.getSkipAmount();
    long targetTime = (world.getTime() + skipAmount) % 24000L;
    WorldTimeSkipContext ctx =
        new WorldTimeSkipContext(world.getName(), reason, skipAmount, targetTime);
    fireWorldTimeSkip(ctx);
    if (ctx.isCancelled()) {
      event.setCancelled(true);
    }
  }

  /**
   * Resolves {@code TimeSkipEvent#getSkipReason()} reflectively so bytecode does not link against
   * the legacy {@code ()Lorg/bukkit/event/world/TimeSkipEvent$SkipReason;} descriptor, which moved
   * to {@code ClockTimeSkipEvent$SkipReason} in Paper 26.x.
   */
  static String readSkipReason(Object event) {
    if (event == null) return "NIGHT_SKIP";
    try {
      Method getter =
          (TIME_SKIP_REASON_METHOD != null && event instanceof TimeSkipEvent)
              ? TIME_SKIP_REASON_METHOD
              : event.getClass().getMethod("getSkipReason");
      Object raw = getter.invoke(event);
      if (raw instanceof Enum<?> enumValue) {
        return enumValue.name();
      }
      if (raw != null) {
        String resolved = readKeyedOrName(raw);
        if (!resolved.isBlank()) return resolved;
      }
      return "NIGHT_SKIP";
    } catch (Throwable t) {
      return "NIGHT_SKIP";
    }
  }

  private static Method resolveTimeSkipReasonMethod() {
    try {
      return TimeSkipEvent.class.getMethod("getSkipReason");
    } catch (Throwable t) {
      return null;
    }
  }

  /**
   * Resolves a {@link World#getBiome(int, int, int)} key through {@link Keyed} rather than
   * invoking {@code Biome.getKey()} directly, avoiding {@link IncompatibleClassChangeError} across
   * Spigot/Paper versions where {@code org.bukkit.block.Biome} changed from an {@code enum} to an
   * {@code interface}.
   */
  static String readBiomeKey(World world, int x, int y, int z) {
    if (world == null) return "";
    try {
      Object biome = world.getBiome(x, y, z);
      return readKeyedOrName(biome);
    } catch (Throwable t) {
      return "";
    }
  }

  static String readKeyedOrName(Object value) {
    if (value == null) return "";
    if (value instanceof Keyed keyed) {
      NamespacedKey key = keyed.getKey();
      if (key != null) return key.toString();
    }
    return value.toString();
  }

  private static Map<String, String> extractItemTags(ItemStack item) {
    if (item == null || !item.hasItemMeta()) return Map.of();
    ItemMeta meta = item.getItemMeta();
    if (meta == null) return Map.of();
    PersistentDataContainer pdc = meta.getPersistentDataContainer();
    if (pdc.isEmpty()) return Map.of();
    Map<String, String> out = new HashMap<>();
    for (NamespacedKey key : pdc.getKeys()) {
      if (pdc.has(key, PersistentDataType.STRING)) {
        String val = pdc.get(key, PersistentDataType.STRING);
        if (val != null) {
          out.put(key.toString(), val);
          out.put(key.getKey(), val);
        }
      }
    }
    return out;
  }

  private static boolean isLiquid(Block block) {
    if (block == null) return false;
    if (block.isLiquid()) return true;
    BlockData data = block.getBlockData();
    return data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
  }
}
