package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.serverSide.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.*;
import io.github.dailystruggle.rtp.common.tasks.LoadChunks;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class Region extends FactoryValue<RegionKeys> {
    public static Set<String> defaultBiomes;
    public static final List<BiConsumer<Region,UUID>> onPlayerQueuePush = new ArrayList<>();
    public static final List<BiConsumer<Region,UUID>> onPlayerQueuePop = new ArrayList<>();

    public static int maxBiomeChecksPerGen = 100;

    /**
     * public/shared cache for this region
     */
    public ConcurrentLinkedQueue<Pair<RTPLocation,Long>> locationQueue = new ConcurrentLinkedQueue<>();
    public ConcurrentHashMap<RTPLocation, ChunkSet> locAssChunks = new ConcurrentHashMap<>();
    protected ConcurrentLinkedQueue<UUID> playerQueue = new ConcurrentLinkedQueue<>();

    /**
     * When reserving/recycling locations for specific players,
     * I want to guard against
     */
    public ConcurrentHashMap<UUID, ConcurrentLinkedQueue<Pair<RTPLocation,Long>>> perPlayerLocationQueue = new ConcurrentHashMap<>();

    /**
     *
     */
    public ConcurrentHashMap<UUID, CompletableFuture<Pair<RTPLocation,Long>>> fastLocations = new ConcurrentHashMap<>();

    //localized generic task for
    protected class Cache implements Runnable {
        private static final long lastUpdate = 0;
        private final UUID playerId;

        public Cache() {
            playerId = null;
        }

        public Cache(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public void run() {
            Pair<RTPLocation, Long> pair = getLocation(null);
            if(pair != null) {
                RTPLocation location = pair.getLeft();
                if(location == null) {
                    cachePipeline.add(new Cache());
                    return;
                }

                ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.getInstance().configs.getParser(PerformanceKeys.class);
                long radius = perf.getNumber(PerformanceKeys.viewDistanceSelect,0L).longValue();

                ChunkSet chunkSet = chunks(location, radius);

                chunkSet.whenComplete(aBoolean -> {
                    if(aBoolean) {
                        if(playerId == null) {
                            locationQueue.add(pair);
                            locAssChunks.put(pair.getLeft(), chunkSet);
                        }
                        else if(fastLocations.containsKey(playerId) && !fastLocations.get(playerId).isDone()) {
                            fastLocations.get(playerId).complete(pair);
                        }
                        else {
                            perPlayerLocationQueue.putIfAbsent(playerId,new ConcurrentLinkedQueue<>());
                            perPlayerLocationQueue.get(playerId).add(pair);
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
            Pair<RTPLocation, Long> pair = locationQueue.poll();
            if(pair == null) {
                playerQueue.add(playerId);
                continue;
            }

            TeleportData teleportData = instance.latestTeleportData.get(playerId);
            if (teleportData != null) {
                instance.priorTeleportData.put(playerId,teleportData);
            }
            teleportData = new TeleportData();
            teleportData.sender = RTP.serverAccessor.getSender(CommandsAPI.serverId);
            teleportData.targetRegion = this;
            teleportData.attempts = pair.getRight();

            RTPCommandSender sender = serverAccessor.getSender(CommandsAPI.serverId);
            LoadChunks loadChunks = new LoadChunks(sender,player,pair.getLeft(),this);
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
                    : RTP.serverAccessor.getBiomes().stream().filter(s -> !collect.contains(s)).collect(Collectors.toSet());
        }
        else defaultBiomes = RTP.serverAccessor.getBiomes();
        Region.defaultBiomes = defaultBiomes;
    }

    public boolean hasLocation(@Nullable UUID uuid) {
        boolean res = locationQueue.size() > 0;
        res |= (uuid != null) && (perPlayerLocationQueue.containsKey(uuid));
        return res;
    }

    public Pair<RTPLocation, Long> getLocation(RTPCommandSender sender, RTPPlayer player, @Nullable Set<String> biomeNames) {
        Pair<RTPLocation, Long> pair = null;

        UUID playerId = player.uuid();

        if(perPlayerLocationQueue.containsKey(playerId)) {
            ConcurrentLinkedQueue<Pair<RTPLocation, Long>> playerLocationQueue = perPlayerLocationQueue.get(playerId);
            while(playerLocationQueue.size()>0) {
                pair = playerLocationQueue.poll();
                if(pair == null || pair.getLeft() == null) continue;
                RTPLocation left = pair.getLeft();
                boolean pass = true;

                int cx = (left.x() > 0) ? left.x()/16 : left.x()/16-1;
                int cz = (left.z() > 0) ? left.z()/16 : left.z()/16-1;
                CompletableFuture<RTPChunk> chunkAt = left.world().getChunkAt(cx, cz);
                RTPChunk rtpChunk;
                try {
                    rtpChunk = chunkAt.get();
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                    continue;
                }

                ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.getInstance().configs.getParser(SafetyKeys.class);
                Set<String> unsafeBlocks = safety.yamlFile.getStringList("unsafeBlocks")
                        .stream().map(String::toUpperCase).collect(Collectors.toSet());

                int safetyRadius = safety.yamlFile.getInt("safetyRadius", 0);
                safetyRadius = Math.max(safetyRadius,7);

                //todo: waterlogged check
                RTPBlock block;
                boolean fail = false;
                for(int x = left.x()-safetyRadius; x < left.x()+safetyRadius && !fail; x++) {
                    for(int z = left.z()-safetyRadius; z < left.z()+safetyRadius && !fail; z++) {
                        for(int y = left.y()-safetyRadius; y < left.y()+safetyRadius && !fail; y++) {
                            block = rtpChunk.getBlockAt(x,y,z);
                            if(unsafeBlocks.contains(block.getMaterial())) fail = true;
                        }
                    }
                }

                pass &= RTP.getInstance().selectionAPI.checkGlobalRegionVerifiers(left);
                if(pass) return pair;
            }
        }

        if(locationQueue.size()>0) {
            pair = locationQueue.poll();
            if(pair == null || pair.getLeft() == null) return null;
            RTPLocation left = pair.getLeft();
            boolean pass = RTP.getInstance().selectionAPI.checkGlobalRegionVerifiers(left);
            if(pass) return pair;
        }

        if(sender.hasPermission("rtp.unqueued")) {
            pair = getLocation(biomeNames);
            long attempts = pair.getRight();
            TeleportData data = RTP.getInstance().latestTeleportData.get(playerId);
            if(data!=null && !data.completed) {
                data.attempts = attempts;
            }
        }
        else {
            onPlayerQueuePush.forEach(consumer -> consumer.accept(this,playerId));
            playerQueue.add(playerId);
        }
        return pair;
    }

    @Nullable
    public Pair<RTPLocation, Long> getLocation(@Nullable Set<String> biomeNames) {
        boolean defaultBiomes = false;
        if(biomeNames == null || biomeNames.size()==0) {
            defaultBiomes = true;
            ConfigParser<SafetyKeys> parser = (ConfigParser<SafetyKeys>) RTP.getInstance().configs.getParser(SafetyKeys.class);
            boolean whitelist = parser.yamlFile.getBoolean("biomeWhitelist", false);
            List<String> biomeList = parser.yamlFile.getStringList("biomes");
            Set<String> biomeSet = (biomeList==null) ? new HashSet<>() : new HashSet<>(biomeList);
            biomeSet = biomeSet.stream().map(String::toUpperCase).collect(Collectors.toSet());
            if(whitelist) {
                biomeNames = biomeSet;
            }
            else {
                Set<String> finalBiomeSet = biomeSet;
                biomeNames = RTP.serverAccessor.getBiomes().stream().filter(
                        s -> !finalBiomeSet.contains(s.toUpperCase())).collect(Collectors.toSet());
            }
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

        RTPWorld world = (RTPWorld) data.getOrDefault(RegionKeys.world,RTP.serverAccessor.getRTPWorlds().get(0));

        RTPLocation location = null;
        String currBiome = "";
        long i = 1;
        for(; i <= maxAttempts; i++) {
            int[] select = shape.select();
            currBiome = world.getBiome(select[0], (vert.maxY()+vert.minY())/2, select[1]);

            for(; biomeChecks < maxBiomeChecks && !biomeNames.contains(currBiome); biomeChecks++) {
                select = shape.select();
                currBiome = world.getBiome(select[0], (vert.maxY()+vert.minY())/2, select[1]);
            }
            if(biomeChecks>=maxBiomeChecks) return new ImmutablePair<>(null,i);

            if(!biomeNames.contains(currBiome)) {
                biomeChecks++;
                if(shape instanceof MemoryShape<?> memoryShape && defaultBiomes) {
                    long idx = (long) memoryShape.xzToLocation(select[0], select[1]);
                    memoryShape.removeBiomeLocation(idx,currBiome);
                    memoryShape.addBadLocation(idx);
                }
                continue;
            }

            CompletableFuture<RTPChunk> cfChunk = world.getChunkAt(select[0], select[1]);

            RTPChunk chunk;

            try {
                chunk = cfChunk.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                return new ImmutablePair<>(null,i);
            }

            location = vert.adjust(chunk);
            if(location == null) {
                if(shape instanceof MemoryShape<?> memoryShape) {
                    long l = (long) memoryShape.xzToLocation(select[0], select[1]);
                    memoryShape.addBadLocation(l);
                    memoryShape.removeBiomeLocation(l,currBiome);
                }
                continue;
            }

            currBiome = world.getBiome(location.x(), location.y(), location.z());

            if(!biomeNames.contains(currBiome)) {
                biomeChecks++;
                if(shape instanceof MemoryShape<?> memoryShape && defaultBiomes) {
                    long idx = (long) memoryShape.xzToLocation(select[0], select[1]);
                    memoryShape.removeBiomeLocation(idx,currBiome);
                    memoryShape.addBadLocation(idx);
                }
                continue;
            }

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

        return new ImmutablePair<>(location,i);
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
                CompletableFuture<RTPChunk> cfChunk = location.world().getChunkAt((int)(cx + i), (int)(cz + j));
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

        ChunkSet chunkSet = new ChunkSet(chunks, new CompletableFuture<>());
        chunkSet.keep(true);
        locAssChunks.put(location,chunkSet);
        return chunkSet;
    }

    public void removeChunks(RTPLocation location) {
        if(!locAssChunks.containsKey(location)) return;
        ChunkSet chunkSet = locAssChunks.get(location);
        chunkSet.keep(false);
        locAssChunks.remove(location);
    }

    public CompletableFuture<Pair<RTPLocation, Long>> fastQueue(UUID id) {
        if(fastLocations.containsKey(id)) return fastLocations.get(id);
        CompletableFuture<Pair<RTPLocation, Long>> res = new CompletableFuture<>();
        fastLocations.put(id,res);
        miscPipeline.add(new Cache(id));
        return res;
    }

    public void queue(UUID id) {
        perPlayerLocationQueue.putIfAbsent(id,new ConcurrentLinkedQueue<>());
        miscPipeline.add(new Cache(id));
    }

    public long getTotalQueueLength(UUID uuid) {
        long res = locationQueue.size();
        ConcurrentLinkedQueue<Pair<RTPLocation, Long>> queue = perPlayerLocationQueue.get(uuid);
        if(queue!=null) res+= queue.size();
        if(fastLocations.containsKey(uuid)) res++;
        return res;
    }

    public long getPublicQueueLength() {
        return locationQueue.size();
    }

    public long getPersonalQueueLength(UUID uuid) {
        long res = 0;
        ConcurrentLinkedQueue<Pair<RTPLocation, Long>> queue = perPlayerLocationQueue.get(uuid);
        if(queue!=null) res += queue.size();
        if(fastLocations.containsKey(uuid)) res++;
        return res;
    }
}