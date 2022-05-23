package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.SetupTeleport;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public abstract class Region extends FactoryValue<RegionKeys> {
    public static int maxBiomeChecksPerGen = 100;
    public String name;

    protected ConcurrentLinkedQueue<RTPLocation> locationQueue = new ConcurrentLinkedQueue<>();
    protected ConcurrentHashMap<RTPLocation, ChunkSet> locAssChunks = new ConcurrentHashMap<>();
    protected ConcurrentLinkedQueue<UUID> playerQueue = new ConcurrentLinkedQueue<>();

    protected ConcurrentHashMap<UUID, ConcurrentLinkedQueue<RTPLocation>> perPlayerLocationQueue = new ConcurrentHashMap<>();

    private class Cache implements Runnable {
        @Override
        public void run() {
            RTPLocation location = getLocation(null);
            if(location != null) {
                ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.getInstance().configs.getParser(PerformanceKeys.class);
                long radius = perf.getNumber(PerformanceKeys.viewDistanceSelect,0L).longValue();

                ChunkSet chunkSet = chunks(location, radius);

                chunkSet.whenComplete(aBoolean -> {
                    RTP.log(Level.WARNING,"LOCATION!!");
                    if(aBoolean) {
                        locationQueue.add(location);
                        locAssChunks.put(location,chunkSet);
                    }
                    else chunkSet.keep(false);
                });
            }
            cachePipeline.add(new Cache());
        }
    }

    public RTPTaskPipe cachePipeline = new RTPTaskPipe();

    public void execute(long availableTime) {
        long cacheCap = getNumber(RegionKeys.cacheCap,10L).longValue();
        if(locationQueue.size()>=cacheCap) return;
        for(long i = cachePipeline.size(); i < cacheCap; i++) {
            cachePipeline.add(new Cache());
        }
        cachePipeline.execute(availableTime);

        while (locationQueue.size() > 0 && playerQueue.size() > 0) {
            UUID playerId = playerQueue.poll();
            TeleportData teleportData = RTP.getInstance().latestTeleportData.get(playerId);
            if(teleportData == null) {
                teleportData = new TeleportData();
                teleportData.sender = CommandsAPI.serverId;
                teleportData.targetRegion = this;
                teleportData.completed = true;
            }

            if(teleportData.completed) {
                teleportData.completed = false;
                teleportData.priorTime = teleportData.time;
                teleportData.time = System.nanoTime();
            }

            getLocation(CommandsAPI.serverId, playerId,teleportData.biomes);

            RTPServerAccessor serverAccessor = RTP.getInstance().serverAccessor;

            SetupTeleport setupTeleport = new SetupTeleport(serverAccessor.getSender(CommandsAPI.serverId), serverAccessor.getPlayer(playerId), this, null);
            RTP.getInstance().setupTeleportPipeline.add(setupTeleport);
        }
    }

    public Region(String name, EnumMap<RegionKeys,Object> params) {
        super(RegionKeys.class);
        this.name = name;
        this.data = params;
        long cacheCap = getNumber(RegionKeys.cacheCap,10L).longValue();
        for(long i = cachePipeline.size(); i < cacheCap; i++) {
            cachePipeline.add(new Cache());
        }
    }

    public boolean hasLocation(@Nullable UUID uuid) {
        boolean res = locationQueue.size() > 0;
        res |= (uuid != null) && (perPlayerLocationQueue.containsKey(uuid));
        return res;
    }

    @Nullable
    public abstract RTPLocation getLocation(UUID sender, UUID player, @Nullable Set<String> biomes);

    @Nullable
    public abstract RTPLocation getLocation(@Nullable Set<String> biomes);

    public abstract void shutDown();

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
        }

        int cx = location.x();
        int cz = location.z();
        cx = (cx >0) ? cx /16 : cx /16-1;
        cz = (cz >0) ? cz /16 : cz /16-1;

        List<CompletableFuture<RTPChunk>> chunks = new ArrayList<>();

        for(long i = -radius; i <= radius; i++) {
            for(long j = -radius; j <= radius; j++) {
                CompletableFuture<RTPChunk> chunk = location.world().getChunkAt(cx + i, cz + j);
                chunks.add(chunk);
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
}