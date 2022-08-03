package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.serverSide.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.*;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.tasks.LoadChunks;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class Region extends FactoryValue<RegionKeys> {
    public static Set<String> defaultBiomes;
    public static final List<BiConsumer<Region,UUID>> onPlayerQueuePush = new ArrayList<>();
    public static final List<BiConsumer<Region,UUID>> onPlayerQueuePop = new ArrayList<>();

    public static int maxBiomeChecksPerGen = 100;

    /**
     * public/shared cache for this region
     */
    public ConcurrentLinkedQueue<RTPLocation> locationQueue = new ConcurrentLinkedQueue<>();
    public ConcurrentHashMap<RTPLocation, ChunkSet> locAssChunks = new ConcurrentHashMap<>();
    protected ConcurrentLinkedQueue<UUID> playerQueue = new ConcurrentLinkedQueue<>();

    /**
     * When reserving/recycling locations for specific players,
     * I want to guard against
     */
    public ConcurrentHashMap<UUID, ConcurrentLinkedQueue<RTPLocation>> perPlayerLocationQueue = new ConcurrentHashMap<>();

    /**
     *
     */
    public ConcurrentHashMap<UUID, CompletableFuture<RTPLocation>> fastLocations = new ConcurrentHashMap<>();

    //localized generic task for
    protected class Cache implements Runnable {
        private UUID playerId;

        public Cache() {
            playerId = null;
        }

        public Cache(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public void run() {
            RTPLocation location = getLocation(defaultBiomes);
            if(location != null) {
                ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.getInstance().configs.getParser(PerformanceKeys.class);
                long radius = perf.getNumber(PerformanceKeys.viewDistanceSelect,0L).longValue();

                ChunkSet chunkSet = chunks(location, radius);

                chunkSet.whenComplete(aBoolean -> {
                    if(aBoolean) {
                        if(playerId == null) {
                            locationQueue.add(location);
                            locAssChunks.put(location, chunkSet);
                        }
                        else if(fastLocations.containsKey(playerId) && !fastLocations.get(playerId).isDone()) {
                            fastLocations.get(playerId).complete(location);
                        }
                        else {
                            perPlayerLocationQueue.putIfAbsent(playerId,new ConcurrentLinkedQueue<>());
                            perPlayerLocationQueue.get(playerId).add(location);
                        }
                    }
                    else chunkSet.keep(false);
                });
            }
            cachePipeline.add(new Cache());
        }
    }

    public RTPTaskPipe cachePipeline = new RTPTaskPipe();
    public RTPTaskPipe miscPipeline = new RTPTaskPipe();

    public void execute(long availableTime) {
        long start = System.nanoTime();

        miscPipeline.execute(availableTime);

        RTP instance = RTP.getInstance();
        long cacheCap = getNumber(RegionKeys.cacheCap,10L).longValue();
        cacheCap = Math.max(cacheCap,playerQueue.size());
        if(locationQueue.size()>=cacheCap) return;
        while(cachePipeline.size()<cacheCap)
            cachePipeline.add(new Cache());
        cachePipeline.execute(availableTime - (System.nanoTime()-start));

        while (locationQueue.size() > 0 && playerQueue.size() > 0) {
            UUID playerId = playerQueue.poll();
            RTPServerAccessor serverAccessor = instance.serverAccessor;
            RTPPlayer player = serverAccessor.getPlayer(playerId);
            if(player == null) continue;
            RTPLocation location = locationQueue.poll();
            if(location == null) {
                playerQueue.add(playerId);
                continue;
            }

            TeleportData teleportData = instance.latestTeleportData.get(playerId);
            if(teleportData == null) {
                teleportData = new TeleportData();
                teleportData.sender = RTP.getInstance().serverAccessor.getSender(CommandsAPI.serverId);
                teleportData.targetRegion = this;
                teleportData.completed = true;
            }

            if(teleportData.completed) {
                instance.priorTeleportData.put(playerId,teleportData);
                teleportData = new TeleportData();
            }

            RTPCommandSender sender = serverAccessor.getSender(CommandsAPI.serverId);
            LoadChunks loadChunks = new LoadChunks(sender,player,location,this);
            teleportData.nextTask = loadChunks;
            instance.latestTeleportData.put(playerId,teleportData);
            instance.loadChunksPipeline.add(loadChunks);
            onPlayerQueuePop.forEach(consumer -> consumer.accept(this,playerId));
        }
    }

    public Region(String name, EnumMap<RegionKeys,Object> params) {
        super(RegionKeys.class, name);
        this.name = name;
        this.data.putAll(params);

        Object shape = params.get(RegionKeys.shape);
        Object world = params.get(RegionKeys.world);
        String worldName;
        if(world instanceof RTPWorld rtpWorld) worldName = rtpWorld.name();
        else {
            worldName = String.valueOf(world);
        }
        if(shape instanceof MemoryShape<?> memoryShape) {
            memoryShape.load(name + ".yml",worldName);
        }

        long cacheCap = getNumber(RegionKeys.cacheCap,10L).longValue();
        for(long i = cachePipeline.size(); i < cacheCap; i++) {
            cachePipeline.add(new Cache());
        }

        Set<String> defaultBiomes;
        ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.getInstance().configs.getParser(SafetyKeys.class);
        Object configBiomes = safety.getConfigValue(SafetyKeys.biomes,null);
        if(configBiomes instanceof Collection collection) {
            boolean whitelist;
            Object configValue = safety.getConfigValue(SafetyKeys.biomeWhitelist, false);
            if(configValue instanceof Boolean b) whitelist = b;
            else whitelist = Boolean.parseBoolean(configValue.toString());

            Set<String> collect = (Set<String>) collection.stream().map(Object::toString).collect(Collectors.toSet());

            defaultBiomes = whitelist
                    ? collect
                    : RTP.getInstance().serverAccessor.getBiomes().stream().filter(s -> !collect.contains(s)).collect(Collectors.toSet());
        }
        else defaultBiomes = RTP.getInstance().serverAccessor.getBiomes();
        Region.defaultBiomes = defaultBiomes;
    }

    public boolean hasLocation(@Nullable UUID uuid) {
        boolean res = locationQueue.size() > 0;
        res |= (uuid != null) && (perPlayerLocationQueue.containsKey(uuid));
        return res;
    }

    @Nullable
    public RTPLocation getLocation(RTPCommandSender sender, RTPPlayer player, @Nullable Set<String> biomeNames) {
        RTPLocation location = null;

        UUID playerId = player.uuid();

        if(perPlayerLocationQueue.containsKey(playerId)) {
            ConcurrentLinkedQueue<RTPLocation> playerLocationQueue = perPlayerLocationQueue.get(playerId);
            while(playerLocationQueue.size()>0) {
                location = playerLocationQueue.poll();
                boolean pass = location != null;
                pass &= RTP.getInstance().selectionAPI.checkGlobalRegionVerifiers(location);
                if(pass) return location;
            }
        }

        if(locationQueue.size()>0) {
            location = locationQueue.poll();
            boolean pass = location != null;
            pass &= RTP.getInstance().selectionAPI.checkGlobalRegionVerifiers(location);
            if(pass) return location;
        }

        if(sender.hasPermission("rtp.unqueued")) {
            location = getLocation(biomeNames);
        }
        else {
            onPlayerQueuePush.forEach(consumer -> consumer.accept(this,playerId));
            playerQueue.add(playerId);
        }
        return location;
    }

    @Nullable
    public RTPLocation getLocation(@Nullable Set<String> biomeNames) {
        if(biomeNames == null) {
            biomeNames = defaultBiomes;
        }

        Shape<?> shape = (Shape<?>) data.get(RegionKeys.shape);
        if(shape == null) return null;

        VerticalAdjustor<?> vert = (VerticalAdjustor<?>) data.get(RegionKeys.vert);
        if(vert == null) return null;

        ConfigParser<PerformanceKeys> performance = (ConfigParser<PerformanceKeys>) RTP.getInstance().configs.getParser(PerformanceKeys.class);

        long maxAttempts = performance.getNumber(PerformanceKeys.maxAttempts, 20).longValue();
        maxAttempts = Math.max(maxAttempts,1);
        long maxBiomeChecks = maxBiomeChecksPerGen*maxAttempts;
        long biomeChecks = 0L;

        RTPWorld world = (RTPWorld) data.getOrDefault(RegionKeys.world,RTP.getInstance().serverAccessor.getRTPWorlds().get(0));

        RTPLocation location = null;
        for(long i = 0; i < maxAttempts; i++) {
            int[] select = shape.select();
            String currBiome = world.getBiome(select[0], (vert.maxY()+vert.minY())/2, select[1]);

            for(; biomeChecks < maxBiomeChecks && !biomeNames.contains(currBiome); biomeChecks++) {
                select = shape.select();
                currBiome = world.getBiome(select[0], (vert.maxY()+vert.minY())/2, select[1]);
            }
            if(biomeChecks>=maxBiomeChecks) return null;

            CompletableFuture<RTPChunk> cfChunk = world.getChunkAt(select[0], select[1]);

            RTPChunk chunk;

            try {
                chunk = cfChunk.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                return null;
            }

            location = vert.adjust(chunk);

            boolean pass = location != null;
            pass &= RTP.getInstance().selectionAPI.checkGlobalRegionVerifiers(location);

            if(pass) {
                if(shape instanceof MemoryShape<?> memoryShape) {
                    memoryShape.addBiomeLocation((long) memoryShape.xzToLocation(select[0], select[1]),currBiome);
                }
                break;
            }
            else {
                if(shape instanceof MemoryShape<?> memoryShape) {
                    long l = (long) memoryShape.xzToLocation(select[0], select[1]);
                    memoryShape.addBadLocation(l);
                    memoryShape.removeBiomeLocation(l,currBiome);
                }
                location = null;
            }
        }

        return location;
    }

    public void shutDown() {
        Shape<?> shape = (Shape<?>) data.get(RegionKeys.shape);
        if(shape == null) return;

        RTPWorld world = (RTPWorld) data.get(RegionKeys.world);
        if(world == null) return;

        if(shape instanceof MemoryShape<?> memoryShape) {
            memoryShape.save(this.name + ".yml", world.name());
        }
    }

    @Override
    public Region clone() {
        Region clone = (Region) super.clone();
        clone.locationQueue = new ConcurrentLinkedQueue<>();
        clone.locAssChunks = new ConcurrentHashMap<>();
        clone.playerQueue = new ConcurrentLinkedQueue<>();
        clone.perPlayerLocationQueue = new ConcurrentHashMap<>();
        return clone;
    }

    public Map<String,String> params() {
        Map<String,String> res = new ConcurrentHashMap<>();
        for(var e : data.entrySet()) {
            Object value = e.getValue();
            if(value instanceof RTPWorld world) {
                res.put("world",world.name());
            }
            else if(value instanceof Shape shape) {
                res.put("shape", shape.name);
                EnumMap<? extends Enum<?>,Object> data = shape.getData();
                for(var dataEntry : data.entrySet()) {
                    res.put(dataEntry.getKey().name(),dataEntry.getValue().toString());
                }
            }
            else if(value instanceof VerticalAdjustor verticalAdjustor) {
                res.put("vert", verticalAdjustor.name);
                EnumMap<? extends Enum<?>,Object> data = verticalAdjustor.getData();
                for(var dataEntry : data.entrySet()) {
                    res.put(dataEntry.getKey().name(),dataEntry.getValue().toString());
                }
            }
            else if(value instanceof String str)
                res.put(e.getKey().name(),str);
            else {
                res.put(e.getKey().name(),value.toString());
            }
        }
        return res;
    }

    public ChunkSet chunks(RTPLocation location, long radius) {
        long sz = (radius*2+1)*(radius*2+1);
        if(locAssChunks.containsKey(location)) {
            ChunkSet chunkSet = locAssChunks.get(location);
            if(chunkSet.chunks().size()>=sz) return chunkSet;

            chunkSet.keep(false);
        }

        int cx = location.x();
        int cz = location.z();
        cx = (cx >0) ? cx /16 : cx /16-1;
        cz = (cz >0) ? cz /16 : cz /16-1;

        List<CompletableFuture<RTPChunk>> chunks = new ArrayList<>();

        Shape<?> shape = (Shape<?>) data.get(RegionKeys.shape);
        if(shape == null) return null;

        VerticalAdjustor<?> vert = (VerticalAdjustor<?>) data.get(RegionKeys.vert);
        if(vert == null) return null;

        RTPWorld rtpWorld = (RTPWorld) data.get(RegionKeys.world);
        if(rtpWorld == null) return null;

        for(long i = -radius; i <= radius; i++) {
            for(long j = -radius; j <= radius; j++) {
                CompletableFuture<RTPChunk> cfChunk = location.world().getChunkAt(cx + i, cz + j);
                if(shape instanceof MemoryShape<?> memoryShape) {
                    long finalJ = j;
                    long finalI = i;
                    cfChunk.whenComplete((chunk, throwable) -> {
                        RTPLocation rtpLocation = chunk.getBlockAt(7, (vert.minY() + vert.maxY()) / 2, 7).getLocation();
                        String currBiome = rtpWorld.getBiome(rtpLocation.x(),rtpLocation.y(),rtpLocation.z());

                        rtpLocation = vert.adjust(chunk);

                        boolean pass;
                        if (rtpLocation == null) {
                            pass = false;
                        } else {
                            pass = RTP.getInstance().selectionAPI.checkGlobalRegionVerifiers(rtpLocation);
                        }

                        if (pass) {
                            memoryShape.addBiomeLocation((long) memoryShape.xzToLocation(finalI, finalJ), currBiome);
                        } else {
                            long l = (long) memoryShape.xzToLocation(finalI, finalJ);
                            memoryShape.addBadLocation(l);
                            memoryShape.removeBiomeLocation(l, currBiome);
                        }
                    });
                }
                chunks.add(cfChunk);
            }
        }

        return new ChunkSet(chunks,new CompletableFuture<>());
    }

    public void removeChunks(RTPLocation location) {
        if(!locAssChunks.containsKey(location)) return;
        ChunkSet chunkSet = locAssChunks.get(location);
        chunkSet.keep(false);
        locAssChunks.remove(location);
    }

    public CompletableFuture<RTPLocation> fastQueue(UUID id) {
        if(fastLocations.containsKey(id)) return fastLocations.get(id);
        CompletableFuture<RTPLocation> res = new CompletableFuture<>();
        fastLocations.put(id,res);
        miscPipeline.add(new Cache(id));
        return res;
    }

    public void queue(UUID id) {
        perPlayerLocationQueue.putIfAbsent(id,new ConcurrentLinkedQueue<>());
        miscPipeline.add(new Cache(id));
    }
}