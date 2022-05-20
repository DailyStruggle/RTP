package leafcraft.rtp.common.selection.region;

import leafcraft.rtp.common.RTP;
import leafcraft.rtp.common.RTPTaskPipe;
import leafcraft.rtp.common.configuration.ConfigParser;
import leafcraft.rtp.common.configuration.enums.PerformanceKeys;
import leafcraft.rtp.common.configuration.enums.RegionKeys;
import leafcraft.rtp.common.factory.FactoryValue;
import leafcraft.rtp.common.selection.region.selectors.shapes.Shape;
import leafcraft.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import leafcraft.rtp.common.substitutions.RTPChunk;
import leafcraft.rtp.common.substitutions.RTPLocation;
import leafcraft.rtp.common.substitutions.RTPWorld;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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
                locationQueue.add(location);
                List<CompletableFuture<RTPChunk>> chunks = new ArrayList<>();
                ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.getInstance().configs.getParser(PerformanceKeys.class);
                long radius = perf.getNumber(PerformanceKeys.viewDistanceSelect,0L).longValue();
                for(long i = -radius; i < radius; i++) {
                    for(long j = -radius; j < radius; j++) {
                        long cx = location.x()/16;
                        if(location.x()<0) cx--;
                        long cz = location.z()/16;
                        if(location.x()<0) cz--;
                        chunks.add(location.world().getChunkAt(cx+i,cz+j));
                    }
                }
                locAssChunks.put(location,new ChunkSet(chunks,new CompletableFuture<>()));
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
        res &= (uuid == null) || (perPlayerLocationQueue.containsKey(uuid));
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
        Map<String,String> res = new HashMap<>();
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
}