package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

public class Region extends FactoryValue<RegionKeys> {
  public static final List<BiConsumer<Region, UUID>> onPlayerQueuePush = new ArrayList<>();
  public static final List<BiConsumer<Region, UUID>> onPlayerQueuePop = new ArrayList<>();
  private final AtomicBoolean isRefillingCache = new AtomicBoolean(false);

  public final RegionQueueManager queueManager = new RegionQueueManager(this);
  public final RegionChunkManager chunkManager = new RegionChunkManager(this);
  public final AtomicInteger inFlightCalculations =
      new AtomicInteger(0);

  public RTPTaskPipe cachePipeline;
  public RTPTaskPipe miscPipeline;
  protected RTPWorld<?> savedWorld = null;

  private RegionSettings settings;

  public Region(String name, RegionSettings settings) {
    super(RegionKeys.class, name);
    this.name = name;
    this.settings = settings;
    this.cachePipeline = (RTPTaskPipe) RTP.serverAccessor.createCachePipe();
    this.miscPipeline = (RTPTaskPipe) RTP.serverAccessor.createTaskPipe();

    Shape<?> shape = settings.shape();

    if (shape instanceof MemoryShape<?>) {
      long iter = ((MemoryShape<?>) shape).fillIter.get();
      if (iter > 0 && iter < Double.valueOf(((MemoryShape<?>) shape).getRange()).longValue()) {
        FillTask task = new FillTask(this, iter);
        RTP.getInstance().fillTasks.put(name, task);
        RTP.scheduler.runTaskAsynchronously(task);
      }
    }

    final long cacheCap = settings.cacheCap();
    final long playerQueueSize = queueManager.playerQueue.size();
    final long totalCap = Math.max(cacheCap, playerQueueSize);

    for (long i = cachePipeline.size() + inFlightCalculations.get(); i < totalCap; i++) {
      cachePipeline.add(new RegionCacheTask(this));
      inFlightCalculations.incrementAndGet();
    }
  }

  public RegionSettings getSettings() {
    return settings;
  }

  public void setSettings(RegionSettings settings) {
    this.settings = settings;
    long cacheCap = settings.cacheCap();
    long playerQueueSize = queueManager.playerQueue.size();
    long totalCap = Math.max(cacheCap, playerQueueSize);
    for (long i = cachePipeline.size() + inFlightCalculations.get(); i < totalCap; i++) {
      cachePipeline.add(new RegionCacheTask(this));
      inFlightCalculations.incrementAndGet();
    }
  }


  /**
   * execute - localized task for pre-generating locations
   *
   * @param availableTime available time in nanoseconds
   */
  public void execute(long availableTime) {
    long start = System.nanoTime();
    long currentAvailable = availableTime;

    while (!queueManager.locationQueue.isEmpty() && !queueManager.playerQueue.isEmpty()) {
      UUID playerId = queueManager.playerQueue.poll();
      if (playerId == null) break;

      TeleportData teleportData = RTP.getInstance().latestTeleportData.get(playerId);
      if (teleportData == null || teleportData.completed) {
        RTP.getInstance().processingPlayers.remove(playerId);
        continue;
      }

      RTPPlayer player = RTP.serverAccessor.getPlayer(playerId);
      if (player == null) continue;

      Map.Entry<RTPCoords, Long> pair = queueManager.locationQueue.poll();
      if (pair == null) {
        queueManager.playerQueue.offer(playerId);
        continue;
      }

      teleportData.attempts = pair.getValue();
      teleportData.selectedCoords = pair.getKey();

      RTPCommandSender sender = RTP.serverAccessor.getSender(CommandsAPI.serverId);
      TeleportPipelineTask pipelineTask = new TeleportPipelineTask(new GenerationContext(sender, player, null), this, pair.getKey());
      teleportData.nextTask = pipelineTask;
      RTP.scheduler.runTaskAsynchronously(pipelineTask);

      RTP.getInstance().latestTeleportData.put(playerId, teleportData);
      inFlightCalculations.incrementAndGet();
      for (int i = 0; i < onPlayerQueuePop.size(); i++) {
        onPlayerQueuePop.get(i).accept(this, playerId);
      }

      Iterator<UUID> iterator = queueManager.playerQueue.iterator();
      int i = 0;
      while (iterator.hasNext()) {
        UUID id = iterator.next();
        ++i;
        TeleportData data = RTP.getInstance().latestTeleportData.get(id);
        RTP.getInstance().processingPlayers.add(id);
        if (data == null) {
          data = new TeleportData();
          data.completed = false;
          data.sender = RTP.serverAccessor.getSender(CommandsAPI.serverId);
          data.time = System.currentTimeMillis();
          data.delay = sender.delay();
          data.targetRegion = this;
          data.originalCoords =
              new RTPCoords(
                  player.getLocation().world().name(),
                  player.getLocation().x(),
                  player.getLocation().y(),
                  player.getLocation().z());
          RTP.getInstance().latestTeleportData.put(id, data);
        }
        data.queueLocation = i;
        RTP.serverAccessor.sendMessage(id, MessagesKeys.queueUpdate);
      }
    }

    currentAvailable -= (System.nanoTime() - start);
    if (currentAvailable <= 0) return;

    boolean miscFinished = miscPipeline.execute(currentAvailable);
    if (!miscFinished) return;
    currentAvailable -= (System.nanoTime() - start);
    if (currentAvailable <= 0) return;

    long cacheCap = settings.cacheCap();
    cacheCap = Math.max(cacheCap, queueManager.playerQueue.size());
    if (!isRefillingCache.compareAndSet(false, true)) return;
    try {
      if (queueManager.locationQueue.size() + inFlightCalculations.get() >= cacheCap) return;
      while (cachePipeline.size() + queueManager.locationQueue.size() + inFlightCalculations.get()
          < cacheCap + queueManager.playerQueue.size()) {
        cachePipeline.add(new RegionCacheTask(this));
        inFlightCalculations.incrementAndGet();
      }
      cachePipeline.execute(currentAvailable);
    } finally {
      isRefillingCache.set(false);
    }
  }

  /**
   * hasLocation - check if this region has a location ready for a player
   *
   * @param uuid player uuid
   * @return true if location is ready
   */
  public boolean hasLocation(@Nullable UUID uuid) {
    boolean res = !queueManager.locationQueue.isEmpty();
    res |= (uuid != null) && (queueManager.perPlayerLocationQueue.containsKey(uuid));
    return res;
  }

  /**
   * getLocation - get a location from cache or generate one
   *
   * @param context generation context
   * @return location and number of attempts
   */
  public GenerationResult getLocation(GenerationContext context) {
    return LocationGenerator.getLocation(this, context);
  }

  /**
   * getLocation - generate a location with biome requirements
   *
   * @param biomeNames set of biomes to filter by
   * @return location and number of attempts
   */
  public GenerationResult getLocation(@Nullable Set<String> biomeNames) {
    return LocationGenerator.generateLocation(this, new GenerationContext(null, null, biomeNames));
  }

  /** shutDown - save and clear data */
  public void shutDown() {
    Shape<?> shape = getShape();
    if (shape == null) return;

    RTPWorld world = getWorld();
    if (world == null) return;

    if (shape instanceof MemoryShape<?>) {
      ((MemoryShape<?>) shape).save(this.name + ".bin", world.name());
    }

    cachePipeline.stop();
    cachePipeline.clear();

    for (Map.Entry<RTPCoords, Long> pair : queueManager.locationQueue) {
      chunkManager.removeChunks(pair.getKey());
    }
    queueManager.locationQueue.clear();

    for (java.util.concurrent.ConcurrentLinkedQueue<Map.Entry<RTPCoords, Long>> queue :
        queueManager.perPlayerLocationQueue.values()) {
      for (Map.Entry<RTPCoords, Long> pair : queue) {
        chunkManager.removeChunks(pair.getKey());
      }
    }
    queueManager.perPlayerLocationQueue.clear();

    chunkManager.locAssChunks.forEach((coords, chunkSet) -> chunkSet.keep(false, getWorld()));
    chunkManager.locAssChunks.clear();
  }

  @Override
  public Region clone() {
    Region clone = (Region) super.clone();
    clone.settings = settings;
    return clone;
  }

  /**
   * params - get current selection parameters
   *
   * @return map of parameters
   */
  public Map<String, String> params() {
    Map<String, String> res = new ConcurrentHashMap<>();
    res.put("world", settings.world().name());
    res.put("shape", settings.shape().name);
    for (Map.Entry<? extends Enum<?>, ?> entry : settings.shape().getData().entrySet()) {
      res.put(entry.getKey().name(), entry.getValue().toString());
    }
    res.put("vert", settings.vert().name);
    for (Map.Entry<? extends Enum<?>, ?> entry : settings.vert().getData().entrySet()) {
      res.put(entry.getKey().name(), entry.getValue().toString());
    }
    res.put("worldBorderOverride", String.valueOf(settings.worldBorderOverride()));
    res.put("requirePermission", String.valueOf(settings.requirePermission()));
    res.put("cacheCap", String.valueOf(settings.cacheCap()));
    res.put("price", String.valueOf(settings.price()));
    res.put("override", settings.override());
    return res;
  }


  /**
   * fastQueue - get a location as fast as possible for a player
   *
   * @param id player uuid
   * @return future location and number of attempts
   */
  public CompletableFuture<Map.Entry<RTPCoords, Long>> fastQueue(UUID id) {
    return queueManager.fastQueue(id);
  }

  /**
   * queue - add a player to the queue for this region
   *
   * @param id player uuid
   */
  public void queue(UUID id) {
    queueManager.queue(id);
  }

  /**
   * getTotalQueueLength - get combined length of public and private queues
   *
   * @param uuid player uuid
   * @return combined queue length
   */
  public long getTotalQueueLength(UUID uuid) {
    return queueManager.getTotalQueueLength(uuid);
  }

  /**
   * getPublicQueueLength - get number of locations available to everyone
   *
   * @return public queue length
   */
  public long getPublicQueueLength() {
    return queueManager.getPublicQueueLength();
  }

  /**
   * getPersonalQueueLength - get number of locations reserved for a specific player
   *
   * @param uuid player uuid
   * @return personal queue length
   */
  public long getPersonalQueueLength(UUID uuid) {
    return queueManager.getPersonalQueueLength(uuid);
  }

  public Shape<?> getShape() {
    boolean wbo = settings.worldBorderOverride();
    RTPWorld<?> world = getWorld();
    Shape<?> shape = settings.shape();

    if (wbo) {
      Shape<?> worldShape = ((WorldBorder) RTP.serverAccessor.getWorldBorder(world.name())).getShape().get();
      if (!worldShape.equals(shape)) {
        shape = worldShape;
        settings = new RegionSettings(
            settings.name(),
            settings.world(),
            shape,
            settings.vert(),
            settings.worldBorderOverride(),
            settings.requirePermission(),
            settings.cacheCap(),
            settings.price(),
            settings.override(),
            settings.detailedRegionInit()
        );
        for (Map.Entry<UUID, java.util.concurrent.ConcurrentLinkedQueue<Map.Entry<RTPCoords, Long>>>
            entry : queueManager.perPlayerLocationQueue.entrySet()) {
          java.util.concurrent.ConcurrentLinkedQueue<Map.Entry<RTPCoords, Long>> value =
              entry.getValue();
          for (Map.Entry<RTPCoords, Long> entry1 : value) chunkManager.removeChunks(entry1.getKey());
          value.clear();
        }
        queueManager.perPlayerLocationQueue.clear();
        for (Map.Entry<RTPCoords, Long> entry : queueManager.locationQueue) chunkManager.removeChunks(entry.getKey());
        queueManager.locationQueue.clear();
      }
    }
    return shape;
  }

  public VerticalAdjustor<?> getVert() {
    return settings.vert();
  }

  public RTPWorld<?> getWorld() {
    return settings.world();
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof Region)) return false;
    Region region = (Region) other;

    if (!getShape().equals(region.getShape())) return false;
    if (!getVert().equals(region.getVert())) return false;
    if (!getWorld().equals(region.getWorld())) return false;
    return region.settings.worldBorderOverride() == settings.worldBorderOverride();
  }

  public static int maxBiomeChecksPerGen = 100;
}
