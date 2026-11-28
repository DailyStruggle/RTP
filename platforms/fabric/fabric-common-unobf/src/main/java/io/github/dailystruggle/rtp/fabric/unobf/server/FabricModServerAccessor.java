package io.github.dailystruggle.rtp.fabric.unobf.server;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.dailystruggle.commandsapi.brigadier.BrigadierBridgeContext;
import io.github.dailystruggle.commandsapi.brigadier.BrigadierCommandAdapter;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.fabric.unobf.tools.FabricLegacyText;
import io.github.dailystruggle.server.api.AbstractModServerAccessor;
import io.github.dailystruggle.server.api.BlockBreakContext;
import io.github.dailystruggle.server.api.BlockPos3i;
import io.github.dailystruggle.server.api.BookMenuSpec;
import io.github.dailystruggle.server.api.CallerLocation;
import io.github.dailystruggle.server.api.ChunkLoadContext;
import io.github.dailystruggle.server.api.PlayerBedEnterContext;
import io.github.dailystruggle.server.api.PlayerBedLeaveContext;
import io.github.dailystruggle.server.api.PlayerInteractBlockContext;
import io.github.dailystruggle.server.api.PlayerSleepSnapshot;
import io.github.dailystruggle.server.api.SaplingGrowContext;
import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.TaggedItemSpec;
import io.github.dailystruggle.server.api.WeatherState;
import io.github.dailystruggle.server.api.WorldTimeSkipContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Standalone Fabric 26.x {@link ServerAccessor} implementation reusable by any Fabric mod in the
 * monorepo, extending {@link AbstractModServerAccessor} (ADR-049, ADR-115, ADR-116).
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 * @governedBy ADR-116
 */
public class FabricModServerAccessor extends AbstractModServerAccessor {

  private static final int PLACE_FLAGS =
      Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
  private static final int MAX_PLUGIN_PAYLOAD_BYTES = 32767;
  private static final Map<Identifier, CustomPacketPayload.Type<RawPluginPayload>>
      REGISTERED_PAYLOAD_TYPES = new ConcurrentHashMap<>();
  private static final Set<FabricModServerAccessor> ACTIVE_ACCESSORS =
      ConcurrentHashMap.newKeySet();

  private record RawPluginPayload(
      CustomPacketPayload.Type<RawPluginPayload> payloadType, byte[] data)
      implements CustomPacketPayload {
    @Override
    public CustomPacketPayload.Type<RawPluginPayload> type() {
      return payloadType;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof RawPluginPayload that)) return false;
      return java.util.Objects.equals(payloadType, that.payloadType)
          && java.util.Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
      return 31 * java.util.Objects.hashCode(payloadType) + java.util.Arrays.hashCode(data);
    }

    @Override
    public String toString() {
      return "RawPluginPayload[payloadType="
          + payloadType
          + ", data="
          + java.util.Arrays.toString(data)
          + "]";
    }
  }

  private volatile MinecraftServer server;
  private final Map<String, BlockState> blockStateCache = new ConcurrentHashMap<>();
  private final Map<String, net.minecraft.server.level.ServerBossEvent> personalBossBars =
      new ConcurrentHashMap<>();
  private boolean tickRegistered;

  public FabricModServerAccessor(String loggerName, Path rootFolder) {
    super(loggerName, rootFolder);
    ACTIVE_ACCESSORS.add(this);
  }

  /** Registers Fabric lifecycle, block interaction, and block break listeners. */
  public void registerEvents() {
    ensureTickRegistered();
    UseBlockCallback.EVENT.register(this::onUseBlock);
    PlayerBlockBreakEvents.AFTER.register(
        (level, player, pos, state, blockEntity) -> {
          if (level.isClientSide()) return;
          String worldName = resolveWorldName(level);
          String blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
          String biomeKey = getBiomeKey(worldName, pos.getX(), pos.getY(), pos.getZ());
          dispatchingWorldEventHook()
              .fireBlockBreak(
                  new BlockBreakContext(
                      player != null ? player.getUUID() : null,
                      worldName,
                      pos.getX(),
                      pos.getY(),
                      pos.getZ(),
                      blockKey,
                      biomeKey));
        });
    EntitySleepEvents.ALLOW_SLEEPING.register(
        (player, sleepingPos) -> {
          if (!(player instanceof ServerPlayer sp)
              || !(sp.level() instanceof ServerLevel level)) {
            return null;
          }
          String worldName = resolveWorldName(level);
          String result = probeBedSleepingProblem(sp, level, sleepingPos);
          PlayerBedEnterContext ctx =
              new PlayerBedEnterContext(
                  sp.getUUID(),
                  sp.getName().getString(),
                  worldName,
                  sleepingPos.getX(),
                  sleepingPos.getY(),
                  sleepingPos.getZ(),
                  result,
                  perm -> hasModPermission(sp.getUUID(), perm, resolveOpsFile()));
          dispatchingWorldEventHook().firePlayerBedEnter(ctx);
          if (ctx.isCancelled()) {
            return Player.BedSleepingProblem.OTHER_PROBLEM;
          }
          if ("OK".equals(result)) {
            MinecraftServer srv = level.getServer();
            if (srv != null) {
              srv.execute(
                  () -> {
                    if (!sp.isSleeping()) {
                      dispatchingWorldEventHook()
                          .firePlayerBedLeave(
                              new PlayerBedLeaveContext(
                                  sp.getUUID(),
                                  sp.getName().getString(),
                                  worldName,
                                  sleepingPos.getX(),
                                  sleepingPos.getY(),
                                  sleepingPos.getZ(),
                                  Math.floorMod(level.getDefaultClockTime(), 24000L)));
                    }
                  });
            }
          }
          return null;
        });
    EntitySleepEvents.STOP_SLEEPING.register(
        (entity, sleepingPos) -> {
          if (!(entity instanceof ServerPlayer sp)
              || !(sp.level() instanceof ServerLevel level)) {
            return;
          }
          String worldName = resolveWorldName(level);
          PlayerBedLeaveContext ctx =
              new PlayerBedLeaveContext(
                  sp.getUUID(),
                  sp.getName().getString(),
                  worldName,
                  sleepingPos.getX(),
                  sleepingPos.getY(),
                  sleepingPos.getZ(),
                  Math.floorMod(level.getDefaultClockTime(), 24000L));
          dispatchingWorldEventHook().firePlayerBedLeave(ctx);
        });
    EntitySleepEvents.ALLOW_RESETTING_TIME.register(
        player -> {
          if (!(player instanceof ServerPlayer sp)
              || !(sp.level() instanceof ServerLevel level)) {
            return true;
          }
          String worldName = resolveWorldName(level);
          long current = level.getDefaultClockTime();
          long target = ((current / 24000L) + 1L) * 24000L;
          WorldTimeSkipContext ctx =
              new WorldTimeSkipContext(
                  worldName, "NIGHT_SKIP", Math.max(0L, target - current), target);
          dispatchingWorldEventHook().fireWorldTimeSkip(ctx);
          return !ctx.isCancelled();
        });
    ServerPlayConnectionEvents.JOIN.register(
        (handler, sender, srv) -> {
          if (handler != null && handler.player != null) {
            dispatchingPlayerLifecycleHook().fireJoin(handler.player.getUUID());
          }
        });
    ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, srv) -> {
          if (handler != null && handler.player != null) {
            dispatchingPlayerLifecycleHook().fireQuit(handler.player.getUUID());
          }
        });
    ServerChunkEvents.CHUNK_LOAD.register(
        (level, chunk, generated) -> {
          if (level == null || chunk == null) return;
          String worldName = resolveWorldName(level);
          int cx = chunk.getPos().x();
          int cz = chunk.getPos().z();
          long seed = level.getSeed();
          // Fabric fires ServerChunkEvents.CHUNK_LOAD inside ChunkStatusTasks.full before the
          // ChunkHolder FULL future completes; deferring to the tick queue prevents re-entrant
          // ServerChunkCache#getChunk(..., FULL, true) self-deadlock on heightmap/block queries.
          scheduleDelayedTask(
              worldName,
              cx,
              cz,
              () ->
                  dispatchingWorldEventHook()
                      .fireChunkLoad(new ChunkLoadContext(worldName, cx, cz, generated, seed)),
              1L);
        });
  }

  private void ensureTickRegistered() {
    if (tickRegistered) return;
    tickRegistered = true;
    ServerTickEvents.END_SERVER_TICK.register(
        srv -> {
          if (this.server != srv && srv != null) {
            this.server = srv;
            try {
              io.github.dailystruggle.effectsapi.fabric_unobf.FabricEffectRuntimeUnobf.bindServer(
                  srv);
              io.github.dailystruggle.effectsapi.fabric_unobf.FabricHandlesUnobf.setServer(srv);
              io.github.dailystruggle.effectsapi.fabric_unobf.FabricEffectsInitializer
                  .registerAll();
            } catch (RuntimeException | LinkageError ex) {
              logger()
                  .log(
                      java.util.logging.Level.WARNING,
                      "FabricEffectsInitializer registration failed",
                      ex);
            }
          }
          this.server = srv;
          tickScheduledTasks();
        });
  }

  private static String probeBedSleepingProblem(
      ServerPlayer sp, ServerLevel level, BlockPos sleepingPos) {
    if (sp == null || level == null || sleepingPos == null) {
      return "OTHER_PROBLEM";
    }
    if (sp.isSleeping() || !sp.isAlive()) {
      return "OTHER_PROBLEM";
    }
    if (level.dimension().equals(Level.NETHER) || level.dimension().equals(Level.END)) {
      return "NOT_POSSIBLE_HERE";
    }
    Vec3 bedCenter = Vec3.atBottomCenterOf(sleepingPos);
    if (Math.abs(sp.getX() - bedCenter.x()) > 3.0
        || Math.abs(sp.getY() - bedCenter.y()) > 2.0
        || Math.abs(sp.getZ() - bedCenter.z()) > 3.0) {
      return "TOO_FAR_AWAY";
    }
    BlockPos above = sleepingPos.above();
    if (level.getBlockState(above).isSuffocating(level, above)) {
      return "OBSTRUCTED";
    }
    if (!sp.isCreative()) {
      List<net.minecraft.world.entity.monster.Monster> monsters =
          level.getEntitiesOfClass(
              net.minecraft.world.entity.monster.Monster.class,
              new net.minecraft.world.phys.AABB(
                  bedCenter.x() - 8.0,
                  bedCenter.y() - 5.0,
                  bedCenter.z() - 8.0,
                  bedCenter.x() + 8.0,
                  bedCenter.y() + 5.0,
                  bedCenter.z() + 8.0),
              m -> m.isPreventingPlayerRest(level, sp));
      if (!monsters.isEmpty()) {
        return "NOT_SAFE";
      }
    }
    return "OK";
  }

  private InteractionResult onUseBlock(
      Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
    if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
      return InteractionResult.PASS;
    }
    ItemStack inHand = player.getItemInHand(hand);
    BlockPos clicked = hitResult.getBlockPos();
    BlockPos target = clicked.above();
    boolean liquid =
        !level.getFluidState(clicked).isEmpty() || !level.getFluidState(target).isEmpty();
    String worldName = resolveWorldName(level);
    String itemKey = BuiltInRegistries.ITEM.getKey(inHand.getItem()).toString();
    Map<String, String> tags = extractItemTags(inHand);

    if (!tags.isEmpty()) {
      PlayerInteractBlockContext ctx =
          new PlayerInteractBlockContext(
              player.getUUID(),
              worldName,
              clicked.getX(),
              clicked.getY(),
              clicked.getZ(),
              target.getX(),
              target.getY(),
              target.getZ(),
              true,
              player.isCreative(),
              liquid,
              itemKey,
              tags,
              perm -> hasModPermission(player.getUUID(), perm, resolveOpsFile()),
              inHand::shrink);
      dispatchingWorldEventHook().firePlayerInteractBlock(ctx);
      return ctx.isCancelled() ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    if ("minecraft:bone_meal".equals(itemKey)) {
      BlockState clickedState = level.getBlockState(clicked);
      String clickedKey = BuiltInRegistries.BLOCK.getKey(clickedState.getBlock()).toString();
      if (clickedKey.endsWith("_sapling")
          || clickedKey.endsWith("_propagule")
          || clickedKey.endsWith("azalea")) {
        SaplingGrowContext growCtx =
            new SaplingGrowContext(
                worldName,
                clicked.getX(),
                clicked.getY(),
                clicked.getZ(),
                clickedKey,
                getBiomeKey(worldName, clicked.getX(), clicked.getY(), clicked.getZ()),
                liquid);
        dispatchingWorldEventHook().fireSaplingGrow(growCtx);
        if (growCtx.isCancelled()) {
          if (!player.isCreative()) {
            inHand.shrink(1);
          }
          return InteractionResult.SUCCESS;
        }
      }
    }
    return InteractionResult.PASS;
  }

  /**
   * Dispatches a tree-grow event to all active {@link FabricModServerAccessor} instances when
   * {@code TreeGrower#growTree} runs (from either natural random ticks or bone meal).
   *
   * @return {@code true} if any listener cancelled vanilla tree growth
   */
  public static boolean onTreeGrow(ServerLevel level, BlockPos pos, BlockState state) {
    if (level == null || pos == null || state == null) return false;
    String blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    boolean onLiquid =
        !level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty();
    String worldName = resolveWorldName(level);
    boolean cancelled = false;
    for (FabricModServerAccessor accessor : ACTIVE_ACCESSORS) {
      SaplingGrowContext ctx =
          new SaplingGrowContext(
              worldName,
              pos.getX(),
              pos.getY(),
              pos.getZ(),
              blockKey,
              accessor.getBiomeKey(worldName, pos.getX(), pos.getY(), pos.getZ()),
              onLiquid);
      accessor.dispatchingWorldEventHook().fireSaplingGrow(ctx);
      if (ctx.isCancelled()) {
        cancelled = true;
      }
    }
    return cancelled;
  }

  private static Map<String, String> extractItemTags(ItemStack stack) {
    if (stack == null || stack.isEmpty()) return Map.of();
    CustomData data = stack.get(DataComponents.CUSTOM_DATA);
    if (data == null) return Map.of();
    CompoundTag tag = data.copyTag();
    if (tag.isEmpty()) return Map.of();
    Map<String, String> out = new HashMap<>();
    for (String key : tag.keySet()) {
      String val = tag.getStringOr(key, "");
      if (!val.isEmpty()) {
        out.put(key, val);
      }
    }
    return out;
  }

  private static ItemStack buildItemStack(TaggedItemSpec spec) {
    Item item = resolveItem(spec.itemKey());
    ItemStack stack = new ItemStack(item, Math.max(1, spec.amount()));
    if (!spec.customTags().isEmpty()) {
      CompoundTag tag = new CompoundTag();
      spec.customTags().forEach(tag::putString);
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }
    if (!spec.displayName().isBlank()) {
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(spec.displayName()));
    }
    if (!spec.lore().isEmpty()) {
      stack.set(
          DataComponents.LORE,
          new ItemLore(spec.lore().stream().<Component>map(Component::literal).toList()));
    }
    return stack;
  }

  private static Item resolveItem(String materialName) {
    if (materialName == null || materialName.isBlank()) return Items.OAK_SAPLING;
    String idStr =
        materialName.contains(":")
            ? materialName.toLowerCase(Locale.ROOT)
            : "minecraft:" + materialName.toLowerCase(Locale.ROOT);
    try {
      Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(idStr));
      return (item == null || item == Items.AIR) ? Items.OAK_SAPLING : item;
    } catch (Exception ex) {
      return Items.OAK_SAPLING;
    }
  }

  @Override
  public String getServerVersion() {
    MinecraftServer srv = this.server;
    return srv != null ? srv.getServerVersion() : "26.1.2";
  }

  @Override
  public String getPlatform() {
    return "fabric";
  }

  @Override
  public Set<String> getOnlinePlayerNames() {
    MinecraftServer srv = this.server;
    if (srv == null) return Collections.emptySet();
    Set<String> out = new HashSet<>();
    for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
      out.add(p.getName().getString());
    }
    return Collections.unmodifiableSet(out);
  }

  @Override
  public String getBiomeKey(String worldName, int x, int y, int z) {
    ServerLevel level = resolveLevel(worldName);
    if (level == null || !level.hasChunk(x >> 4, z >> 4)) return "minecraft:plains";
    return level
        .getBiome(new BlockPos(x, y, z))
        .unwrapKey()
        .map(key -> key.identifier().toString())
        .orElse("minecraft:plains");
  }

  @Override
  public String getBlockState(String worldName, int x, int y, int z) {
    ServerLevel level = resolveLevel(worldName);
    if (level == null || !level.hasChunk(x >> 4, z >> 4)) return null;
    BlockState state = level.getBlockState(new BlockPos(x, y, z));
    return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
  }

  @Override
  public boolean isReplaceable(String worldName, int x, int y, int z) {
    ServerLevel level = resolveLevel(worldName);
    if (level == null || !level.hasChunk(x >> 4, z >> 4)) return false;
    BlockState state = level.getBlockState(new BlockPos(x, y, z));
    if (state.isAir() || state.canBeReplaced()) return true;
    String key = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().toUpperCase(Locale.ROOT);
    return key.endsWith("_LEAVES") || key.endsWith("_SAPLING") || key.endsWith("SNOW");
  }

  @Override
  public int getHighestBlockY(String worldName, int x, int z) {
    ServerLevel level = resolveLevel(worldName);
    if (level == null || !level.hasChunk(x >> 4, z >> 4)) return Integer.MIN_VALUE;
    return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
  }

  @Override
  public int getMinHeight(String worldName) {
    ServerLevel level = resolveLevel(worldName);
    return level != null ? level.getMinY() : -64;
  }

  @Override
  public boolean placeStructure(String worldName, int x, int y, int z, String structureKey) {
    ServerLevel level = resolveLevel(worldName);
    MinecraftServer srv = this.server;
    if (level == null || srv == null) return false;
    Identifier id;
    try {
      id = Identifier.parse(structureKey);
    } catch (Exception ex) {
      logger().warning("Invalid structure key '" + structureKey + "': " + ex.getMessage());
      return false;
    }
    Optional<StructureTemplate> template = srv.getStructureManager().get(id);
    if (template.isEmpty()) return false;
    StructureTemplate tpl = template.get();
    Vec3i size = tpl.getSize();
    BlockPos origin = new BlockPos(x - size.getX() / 2, y, z - size.getZ() / 2);
    try {
      return tpl.placeInWorld(
          level, origin, origin, new StructurePlaceSettings(), RandomSource.create(), PLACE_FLAGS);
    } catch (Exception ex) {
      logger().warning("Failed to place structure '" + structureKey + "': " + ex.getMessage());
      return false;
    }
  }

  @Override
  public boolean giveItem(UUID fallbackPlayerId, String playerName, TaggedItemSpec spec) {
    if (spec == null) return false;
    ItemStack prototype = buildItemStack(spec);
    if (playerName != null && !playerName.isBlank()) {
      return giveStackToPlayer(playerName, prototype, spec.amount());
    }
    MinecraftServer srv = this.server;
    if (srv != null && fallbackPlayerId != null && !fallbackPlayerId.equals(CommandsAPI.serverId)) {
      ServerPlayer player = srv.getPlayerList().getPlayer(fallbackPlayerId);
      if (player != null) {
        return giveStackToPlayer(player.getName().getString(), prototype, spec.amount());
      }
    }
    return false;
  }

  @Override
  public void dropItem(String worldName, int x, int y, int z, TaggedItemSpec spec) {
    ServerLevel level = resolveLevel(worldName);
    if (level == null || spec == null) return;
    Block.popResource(level, new BlockPos(x, y, z), buildItemStack(spec));
  }

  private boolean giveStackToPlayer(String playerName, ItemStack prototype, int amount) {
    MinecraftServer srv = this.server;
    if (srv == null || playerName == null || prototype == null || prototype.isEmpty()) return false;
    ServerPlayer player = srv.getPlayerList().getPlayerByName(playerName);
    if (player == null) return false;

    int remaining = Math.max(1, amount);
    int maxStack = prototype.getMaxStackSize();
    while (remaining > 0) {
      int give = Math.min(remaining, maxStack);
      ItemStack stack = prototype.copyWithCount(give);
      if (!player.getInventory().add(stack)) {
        player.drop(stack, false);
      }
      remaining -= give;
    }
    return true;
  }

  @Override
  public void registerCommands(Object rootCommand, String... aliases) {
    if (!(rootCommand instanceof CommandsAPICommand root)) return;
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, registryAccess, environment) -> {
          BrigadierBridgeContext<CommandSourceStack> ctx =
              new BrigadierBridgeContext<>(
                  source -> {
                    UUID id = resolveSourceId(source);
                    captureCallerLocation(id, source);
                    return id;
                  },
                  (source, permission) ->
                      hasModPermission(resolveSourceId(source), permission, resolveOpsFile()),
                  (source, msg) -> sendMessage(resolveSourceId(source), msg, false));
          LiteralArgumentBuilder<CommandSourceStack> builder =
              BrigadierCommandAdapter.toBrigadier(root, ctx);
          var rootNode = dispatcher.register(builder);
          if (aliases != null) {
            for (String alias : aliases) {
              if (alias != null && !alias.isBlank() && !alias.equalsIgnoreCase(root.name())) {
                dispatcher.register(
                    LiteralArgumentBuilder.<CommandSourceStack>literal(alias)
                        .executes(rootNode.getCommand())
                        .redirect(rootNode));
              }
            }
          }
        });
    ensureTickRegistered();
  }

  private static UUID resolveSourceId(CommandSourceStack source) {
    if (source == null) return CommandsAPI.serverId;
    ServerPlayer player = source.getPlayer();
    return player != null ? player.getUUID() : CommandsAPI.serverId;
  }

  private void captureCallerLocation(UUID id, CommandSourceStack source) {
    if (source == null) return;
    try {
      Vec3 pos = source.getPosition();
      if (pos == null) return;
      String worldName =
          source.getLevel() != null
              ? resolveWorldName(source.getLevel())
              : null;
      recordCallerLocation(
          id,
          new CallerLocation(
              worldName,
              (int) Math.floor(pos.x),
              (int) Math.floor(pos.y),
              (int) Math.floor(pos.z)));
    } catch (Exception ignored) {
    }
  }

  @Override
  public void sendMessage(UUID playerId, String message, boolean error) {
    MinecraftServer srv = this.server;
    if (srv == null) return;
    Component comp = FabricLegacyText.parse(message == null ? "" : message);
    if (playerId == null || playerId.equals(CommandsAPI.serverId)) {
      srv.sendSystemMessage(comp);
      return;
    }
    ServerPlayer player = srv.getPlayerList().getPlayer(playerId);
    if (player != null) {
      player.sendSystemMessage(comp);
    } else {
      srv.sendSystemMessage(comp);
    }
  }

  @Override
  public boolean openBook(UUID playerId, BookMenuSpec book) {
    MinecraftServer srv = this.server;
    if (srv == null || playerId == null || playerId.equals(CommandsAPI.serverId) || book == null) {
      return false;
    }
    ServerPlayer sp = srv.getPlayerList().getPlayer(playerId);
    if (sp == null) {
      return false;
    }
    try {
      List<Component> pageComponents = new ArrayList<>(Math.max(1, book.pages().size()));
      for (BookMenuSpec.Page page : book.pages()) {
        net.minecraft.network.chat.MutableComponent pageComp = Component.empty();
        boolean firstLine = true;
        for (BookMenuSpec.Line line : page.lines()) {
          if (!firstLine) {
            pageComp.append("\n");
          }
          firstLine = false;
          for (BookMenuSpec.Fragment frag : line.fragments()) {
            boolean runCmd = frag.clickAction() == BookMenuSpec.ClickAction.RUN_COMMAND;
            String clickVal =
                (frag.clickAction() == BookMenuSpec.ClickAction.RUN_COMMAND
                        || frag.clickAction() == BookMenuSpec.ClickAction.SUGGEST_COMMAND)
                    ? frag.clickValue()
                    : null;
            pageComp.append(
                FabricLegacyText.parseInteractive(frag.text(), frag.hover(), clickVal, runCmd));
          }
        }
        pageComponents.add(pageComp);
      }
      if (pageComponents.isEmpty()) {
        pageComponents.add(Component.empty());
      }
      List<net.minecraft.server.network.Filterable<Component>> filtered =
          new ArrayList<>(pageComponents.size());
      for (Component c : pageComponents) {
        filtered.add(net.minecraft.server.network.Filterable.passThrough(c));
      }
      net.minecraft.world.item.component.WrittenBookContent content =
          new net.minecraft.world.item.component.WrittenBookContent(
              net.minecraft.server.network.Filterable.passThrough(
                  FabricLegacyText.toLegacyString(book.title())),
              book.author(),
              0,
              filtered,
              false);
      ItemStack writtenBook = new ItemStack(Items.WRITTEN_BOOK);
      writtenBook.set(DataComponents.WRITTEN_BOOK_CONTENT, content);

      int hotbar = sp.getInventory().getSelectedSlot();
      int slotId = 36 + hotbar;
      ItemStack real = sp.getInventory().getItem(hotbar);
      int containerId = sp.inventoryMenu.containerId;
      sp.connection.send(
          new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
              containerId, sp.inventoryMenu.incrementStateId(), slotId, writtenBook));
      sp.connection.send(
          new net.minecraft.network.protocol.game.ClientboundOpenBookPacket(
              InteractionHand.MAIN_HAND));
      sp.connection.send(
          new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
              containerId, sp.inventoryMenu.incrementStateId(), slotId, real));
      return true;
    } catch (Throwable t) {
      logger().log(java.util.logging.Level.FINE, "openBook fallback to interactive chat", t);
      return false;
    }
  }

  @Override
  public void sendInteractiveLine(UUID playerId, BookMenuSpec.Line line) {
    if (line == null) {
      return;
    }
    MinecraftServer srv = this.server;
    if (srv == null) {
      return;
    }
    net.minecraft.network.chat.MutableComponent out = Component.empty();
    for (BookMenuSpec.Fragment frag : line.fragments()) {
      boolean runCmd = frag.clickAction() == BookMenuSpec.ClickAction.RUN_COMMAND;
      String clickVal =
          (frag.clickAction() == BookMenuSpec.ClickAction.RUN_COMMAND
                  || frag.clickAction() == BookMenuSpec.ClickAction.SUGGEST_COMMAND)
              ? frag.clickValue()
              : null;
      out.append(FabricLegacyText.parseInteractive(frag.text(), frag.hover(), clickVal, runCmd));
    }
    if (playerId == null || playerId.equals(CommandsAPI.serverId)) {
      srv.sendSystemMessage(out);
      return;
    }
    ServerPlayer player = srv.getPlayerList().getPlayer(playerId);
    if (player != null) {
      player.sendSystemMessage(out);
    } else {
      srv.sendSystemMessage(out);
    }
  }

  @Override
  public boolean setBlock(String worldName, int x, int y, int z, String blockData) {
    ServerLevel level = resolveLevel(worldName);
    if (level == null) return false;
    BlockState state = parseBlockState(blockData);
    if (state == null) return false;
    return level.setBlock(new BlockPos(x, y, z), state, PLACE_FLAGS);
  }

  @Override
  public boolean isChunkLoaded(String worldName, int chunkX, int chunkZ) {
    ServerLevel level = resolveLevel(worldName);
    return level != null && level.hasChunk(chunkX, chunkZ);
  }

  @Override
  public long getWorldTime(String worldName) {
    ServerLevel level = resolveLevel(worldName);
    return level != null ? Math.floorMod(level.getDefaultClockTime(), 24000L) : 0L;
  }

  @Override
  public long getWorldFullTime(String worldName) {
    ServerLevel level = resolveLevel(worldName);
    return level != null ? level.getDefaultClockTime() : 0L;
  }

  @Override
  public void setWorldTime(String worldName, long timeTicks) {
    ServerLevel level = resolveLevel(worldName);
    if (level == null) return;
    long targetTotal;
    if (timeTicks >= 24000L) {
      targetTotal = timeTicks;
    } else {
      long currentFull = level.getDefaultClockTime();
      long dayBase = currentFull - Math.floorMod(currentFull, 24000L);
      targetTotal = dayBase + Math.max(0L, timeTicks);
    }
    level
        .dimensionType()
        .defaultClock()
        .ifPresent(clock -> level.clockManager().setTotalTicks(clock, targetTotal));
  }

  @Override
  public WeatherState getWeather(String worldName) {
    ServerLevel level = resolveLevel(worldName);
    if (level == null) return WeatherState.CLEAR;
    if (level.isThundering()) return WeatherState.THUNDER;
    if (level.isRaining()) return WeatherState.RAIN;
    return WeatherState.CLEAR;
  }

  @Override
  public void setWeather(String worldName, WeatherState state, int durationTicks) {
    ServerLevel level = resolveLevel(worldName);
    if (level == null || state == null) return;
    int ticks = durationTicks > 0 ? durationTicks : 6000;
    var weatherData = level.getWeatherData();
    switch (state) {
      case CLEAR -> {
        weatherData.setClearWeatherTime(ticks);
        weatherData.setRainTime(0);
        weatherData.setThunderTime(0);
        weatherData.setRaining(false);
        weatherData.setThundering(false);
      }
      case RAIN -> {
        weatherData.setClearWeatherTime(0);
        weatherData.setRainTime(ticks);
        weatherData.setThunderTime(0);
        weatherData.setRaining(true);
        weatherData.setThundering(false);
      }
      case THUNDER -> {
        weatherData.setClearWeatherTime(0);
        weatherData.setRainTime(ticks);
        weatherData.setThunderTime(ticks);
        weatherData.setRaining(true);
        weatherData.setThundering(true);
      }
    }
  }

  @Override
  public List<PlayerSleepSnapshot> getWorldPlayers(String worldName) {
    ServerLevel level = resolveLevel(worldName);
    if (level == null) return Collections.emptyList();
    List<ServerPlayer> players = level.players();
    List<PlayerSleepSnapshot> out = new ArrayList<>(players.size());
    for (ServerPlayer p : players) {
      String gm = "SURVIVAL";
      if (p.isCreative()) gm = "CREATIVE";
      else if (p.isSpectator()) gm = "SPECTATOR";
      int restTicks = 0;
      try {
        restTicks = p.getStats().getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
      } catch (RuntimeException ex) {
        logger().log(java.util.logging.Level.WARNING, "Could not read TIME_SINCE_REST stat", ex);
      }
      Set<String> tags = readModEntityTags(p);
      long idleElapsedMs = 0L;
      int idleTimeoutMinutes = 0;
      try {
        long lastAction = p.getLastActionTime();
        if (lastAction > 0L) {
          idleElapsedMs = Math.max(0L, net.minecraft.util.Util.getMillis() - lastAction);
        }
        MinecraftServer srv = this.server;
        if (srv != null) {
          idleTimeoutMinutes = srv.playerIdleTimeout();
        }
      } catch (RuntimeException ex) {
        logger().log(java.util.logging.Level.FINE, "Could not read player idle time", ex);
      }
      out.add(
          new PlayerSleepSnapshot(
              p.getUUID(),
              p.getName().getString(),
              p.isSleeping(),
              p.getSleepTimer(),
              isModPlayerSleepIgnored(tags),
              gm,
              isModPlayerVanishedOrAfk(p, tags, idleElapsedMs, idleTimeoutMinutes),
              restTicks,
              resolveModPermissionSet(p.getUUID(), resolveOpsFile())));
    }
    return Collections.unmodifiableList(out);
  }

  @Override
  public void wakePlayer(UUID playerId, boolean setSpawn) {
    MinecraftServer srv = this.server;
    if (srv == null || playerId == null) return;
    ServerPlayer player = srv.getPlayerList().getPlayer(playerId);
    if (player != null && player.isSleeping()) {
      player.stopSleepInBed(!setSpawn, true);
    }
  }

  @Override
  public void resetTimeSinceRest(UUID playerId) {
    MinecraftServer srv = this.server;
    if (srv == null || playerId == null) return;
    ServerPlayer player = srv.getPlayerList().getPlayer(playerId);
    if (player != null) {
      try {
        player.resetStat(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
      } catch (RuntimeException ex) {
        logger().log(java.util.logging.Level.WARNING, "Could not reset TIME_SINCE_REST stat", ex);
      }
    }
  }

  @Override
  public int getMaxPlayers() {
    MinecraftServer srv = this.server;
    return srv != null ? Math.max(1, srv.getMaxPlayers()) : 20;
  }

  @Override
  public AutoCloseable registerMessagingChannel(
      String channel, BiConsumer<UUID, byte[]> receiver) {
    if (channel == null || channel.isBlank() || receiver == null) {
      return () -> {};
    }
    ACTIVE_ACCESSORS.add(this);
    ensureFabricChannelRegistered(channel);
    return super.registerMessagingChannel(channel, receiver);
  }

  @Override
  public boolean sendPluginMessage(String channel, byte[] payload) {
    if (channel == null || channel.isBlank() || payload == null) {
      return false;
    }
    MinecraftServer srv = this.server;
    if (srv == null) {
      return super.sendPluginMessage(channel, payload);
    }
    try {
      CustomPacketPayload.Type<RawPluginPayload> type = ensureFabricChannelRegistered(channel);
      if (type != null && srv.getPlayerList() != null) {
        for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
          if (player != null && player.connection != null) {
            player.connection.send(
                new ClientboundCustomPayloadPacket(new RawPluginPayload(type, payload.clone())));
            return true;
          }
        }
      }
    } catch (RuntimeException | LinkageError ex) {
      logger().log(java.util.logging.Level.FINE, "Fabric sendPluginMessage failed on " + channel, ex);
    }
    return false;
  }

  private CustomPacketPayload.Type<RawPluginPayload> ensureFabricChannelRegistered(String channel) {
    Identifier id = parseChannelIdentifier(channel);
    if (id == null) {
      return null;
    }
    return REGISTERED_PAYLOAD_TYPES.computeIfAbsent(
        id,
        channelId -> {
          CustomPacketPayload.Type<RawPluginPayload> type =
              new CustomPacketPayload.Type<>(channelId);
          StreamCodec<RegistryFriendlyByteBuf, RawPluginPayload> codec =
              StreamCodec.of(
                  (buf, msg) -> {
                    if (msg != null && msg.data() != null) {
                      buf.writeBytes(msg.data());
                    }
                  },
                  buf -> {
                    int readable = Math.min(buf.readableBytes(), MAX_PLUGIN_PAYLOAD_BYTES);
                    byte[] bytes = new byte[Math.max(0, readable)];
                    buf.readBytes(bytes);
                    if (buf.readableBytes() > 0) {
                      buf.skipBytes(buf.readableBytes());
                    }
                    return new RawPluginPayload(type, bytes);
                  });
          try {
            PayloadTypeRegistry.serverboundPlay().register(type, codec);
            PayloadTypeRegistry.clientboundPlay().register(type, codec);
            ServerPlayNetworking.registerGlobalReceiver(
                type,
                (msg, context) -> {
                  if (msg == null || msg.data() == null) return;
                  UUID senderId = context.player() != null ? context.player().getUUID() : null;
                  String chKey = channelId.toString();
                  for (FabricModServerAccessor acc : ACTIVE_ACCESSORS) {
                    acc.dispatchLocalMessagingChannel(chKey, senderId, msg.data());
                    if (!chKey.equals(channel)) {
                      acc.dispatchLocalMessagingChannel(channel, senderId, msg.data());
                    }
                  }
                });
          } catch (RuntimeException | LinkageError ex) {
            logger()
                .log(
                    java.util.logging.Level.FINE,
                    "Fabric PayloadTypeRegistry registration skipped for " + channelId,
                    ex);
          }
          return type;
        });
  }

  private static Identifier parseChannelIdentifier(String channel) {
    if (channel == null || channel.isBlank()) return null;
    String raw = channel.trim().toLowerCase(Locale.ROOT);
    String normalized = raw.contains(":") ? raw : "leafsleep:" + raw;
    return Identifier.tryParse(normalized);
  }

  @Override
  public void sendActionBar(UUID playerId, String message) {
    MinecraftServer srv = this.server;
    if (srv == null || playerId == null || message == null || message.isEmpty()) return;
    ServerPlayer player = srv.getPlayerList().getPlayer(playerId);
    if (player == null || player.connection == null) return;
    try {
      player.connection.send(
          new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
              FabricLegacyText.parse(message)));
    } catch (RuntimeException | LinkageError ex) {
      logger().log(java.util.logging.Level.FINE, "sendActionBar failed", ex);
    }
  }

  @Override
  public void showBossBar(UUID playerId, String barId, String title, double progress) {
    MinecraftServer srv = this.server;
    if (srv == null || playerId == null || barId == null || barId.isBlank()) return;
    ServerPlayer player = srv.getPlayerList().getPlayer(playerId);
    if (player == null) return;
    try {
      String key = playerId + ":" + barId;
      float clamped = (float) Math.max(0.0, Math.min(1.0, progress));
      net.minecraft.world.BossEvent.BossBarColor color = parseBossBarColor(title);
      Component comp = FabricLegacyText.parse(title == null ? "" : title);
      net.minecraft.server.level.ServerBossEvent bar = personalBossBars.get(key);
      if (bar == null) {
        bar =
            new net.minecraft.server.level.ServerBossEvent(
                UUID.randomUUID(),
                comp,
                color,
                net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
        bar.addPlayer(player);
        personalBossBars.put(key, bar);
      } else {
        bar.setName(comp);
        bar.setColor(color);
        if (!bar.getPlayers().contains(player)) {
          bar.addPlayer(player);
        }
      }
      bar.setProgress(clamped);
    } catch (RuntimeException | LinkageError ex) {
      logger().log(java.util.logging.Level.FINE, "showBossBar failed", ex);
    }
  }

  @Override
  public void clearBossBar(UUID playerId, String barId) {
    if (playerId == null || barId == null || barId.isBlank()) return;
    net.minecraft.server.level.ServerBossEvent bar =
        personalBossBars.remove(playerId + ":" + barId);
    if (bar != null) {
      try {
        bar.removeAllPlayers();
      } catch (RuntimeException | LinkageError ex) {
        logger().log(java.util.logging.Level.FINE, "clearBossBar failed", ex);
      }
    }
  }

  private static net.minecraft.world.BossEvent.BossBarColor parseBossBarColor(String template) {
    if (template == null) return net.minecraft.world.BossEvent.BossBarColor.GREEN;
    for (int i = 0; i + 1 < template.length(); i++) {
      char prefix = template.charAt(i);
      if (prefix != '&' && prefix != '§') continue;
      char c = Character.toLowerCase(template.charAt(i + 1));
      switch (c) {
        case '4', 'c' -> {
          return net.minecraft.world.BossEvent.BossBarColor.RED;
        }
        case '6', 'e' -> {
          return net.minecraft.world.BossEvent.BossBarColor.YELLOW;
        }
        case '2', 'a' -> {
          return net.minecraft.world.BossEvent.BossBarColor.GREEN;
        }
        case '1', '3', '9', 'b' -> {
          return net.minecraft.world.BossEvent.BossBarColor.BLUE;
        }
        case '5' -> {
          return net.minecraft.world.BossEvent.BossBarColor.PURPLE;
        }
        case 'd' -> {
          return net.minecraft.world.BossEvent.BossBarColor.PINK;
        }
        case 'f', '7', '8', '0' -> {
          return net.minecraft.world.BossEvent.BossBarColor.WHITE;
        }
        default -> {}
      }
    }
    return net.minecraft.world.BossEvent.BossBarColor.GREEN;
  }

  @Override
  public Object wrapEffectPlayer(Object player) {
    if (player instanceof ServerPlayer sp) {
      try {
        return io.github.dailystruggle.effectsapi.fabric_unobf.FabricHandlesUnobf.wrap(sp);
      } catch (RuntimeException | LinkageError ex) {
        logger().log(java.util.logging.Level.FINE, "FabricHandlesUnobf wrap fallback", ex);
        return sp;
      }
    }
    MinecraftServer srv = this.server;
    if (srv != null && player instanceof UUID playerId) {
      ServerPlayer resolved = srv.getPlayerList().getPlayer(playerId);
      if (resolved != null) {
        try {
          return io.github.dailystruggle.effectsapi.fabric_unobf.FabricHandlesUnobf.wrap(resolved);
        } catch (RuntimeException | LinkageError ex) {
          logger().log(java.util.logging.Level.FINE, "FabricHandlesUnobf wrap fallback", ex);
          return resolved;
        }
      }
    }
    return null;
  }

  @Override
  public boolean setChunkBlocks(
      String worldName, int chunkX, int chunkZ, Map<BlockPos3i, String> blocks) {
    ServerLevel level = resolveLevel(worldName);
    if (level == null) return false;
    if (blocks == null || blocks.isEmpty()) return true;

    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (Map.Entry<BlockPos3i, String> entry : blocks.entrySet()) {
      String data = entry.getValue();
      if (data == null) continue;
      String base = data.split("\\[")[0].toLowerCase(Locale.ROOT);
      if (base.equals("minecraft:air")
          || base.equals("minecraft:cave_air")
          || base.equals("air")
          || base.equals("cave_air")) {
        continue;
      }
      BlockState state = parseBlockState(data);
      if (state == null) continue;
      BlockPos3i p = entry.getKey();
      pos.set(p.x(), p.y(), p.z());
      level.setBlock(pos, state, PLACE_FLAGS);
    }
    return true;
  }

  private java.io.File resolveOpsFile() {
    MinecraftServer srv = this.server;
    if (srv != null) {
      try {
        return srv.getPlayerList().getOps().getFile();
      } catch (RuntimeException ex) {
        logger().log(java.util.logging.Level.FINE, "Could not resolve ops file", ex);
      }
    }
    return null;
  }

  private static String resolveWorldName(Level level) {
    if (level == null) return readLevelName();
    String base = readLevelName();
    if (level.dimension().equals(Level.OVERWORLD)) {
      return base;
    }
    if (level.dimension().equals(Level.NETHER)) {
      return base + "_nether";
    }
    if (level.dimension().equals(Level.END)) {
      return base + "_the_end";
    }
    return level.dimension().identifier().getPath();
  }

  @Override
  public List<String> getWorldNames() {
    MinecraftServer srv = this.server;
    if (srv == null) return super.getWorldNames();
    List<String> out = new ArrayList<>();
    for (ServerLevel level : srv.getAllLevels()) {
      out.add(resolveWorldName(level));
    }
    return out.isEmpty() ? super.getWorldNames() : Collections.unmodifiableList(out);
  }

  private ServerLevel resolveLevel(String worldName) {
    MinecraftServer srv = this.server;
    if (srv == null) return null;
    if (worldName != null) {
      for (ServerLevel level : srv.getAllLevels()) {
        if (resolveWorldName(level).equalsIgnoreCase(worldName)
            || level.dimension().identifier().getPath().equalsIgnoreCase(worldName)
            || level.dimension().identifier().toString().equalsIgnoreCase(worldName)) {
          return level;
        }
      }
    }
    return srv.overworld();
  }

  private BlockState parseBlockState(String blockData) {
    BlockState cached = blockStateCache.get(blockData);
    if (cached != null) return cached;
    MinecraftServer srv = this.server;
    if (srv == null) return null;
    try {
      HolderLookup<Block> lookup = srv.registryAccess().lookupOrThrow(Registries.BLOCK);
      BlockState state = BlockStateParser.parseForBlock(lookup, blockData, false).blockState();
      blockStateCache.put(blockData, state);
      return state;
    } catch (CommandSyntaxException ex) {
      logger().warning("Failed to parse block '" + blockData + "': " + ex.getMessage());
      return null;
    }
  }
}
