package leafcraft.rtp.bukkit.commonBukkitImpl.region;

import leafcraft.rtp.common.RTP;
import leafcraft.rtp.common.configuration.ConfigParser;
import leafcraft.rtp.common.configuration.enums.PerformanceKeys;
import leafcraft.rtp.common.configuration.enums.RegionKeys;
import leafcraft.rtp.common.configuration.enums.SafetyKeys;
import leafcraft.rtp.common.selection.region.Region;
import leafcraft.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import leafcraft.rtp.common.selection.region.selectors.shapes.Shape;
import leafcraft.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import leafcraft.rtp.common.substitutions.RTPChunk;
import leafcraft.rtp.common.substitutions.RTPLocation;
import leafcraft.rtp.common.substitutions.RTPWorld;
import org.bukkit.block.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class BukkitRegion extends Region {
    private final Set<String> defaultBiomes;

    public BukkitRegion(String name, EnumMap<RegionKeys,Object> params) {
        super(name, params);

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
                    : Arrays.stream(Biome.values()).map(Enum::name).filter(s -> !collect.contains(s)).collect(Collectors.toSet());
        }
        else defaultBiomes = Arrays.stream(Biome.values()).map(Enum::name).collect(Collectors.toSet());
        this.defaultBiomes = defaultBiomes;
    }

    @Override
    public @Nullable RTPLocation getLocation(UUID sender, UUID player, @Nullable Set<String> biomeNames) {
        RTPLocation location;

        //todo: sender cost

        if(perPlayerLocationQueue.containsKey(player)) {
            ConcurrentLinkedQueue<RTPLocation> playerLocationQueue = perPlayerLocationQueue.get(player);
            while(playerLocationQueue.size()>0) {
                location = playerLocationQueue.poll();
                boolean pass = location != null;
                pass &= RTP.getInstance().selectionAPI.checkGlobalRegionVerifiers(location);
                if(pass) return location;
            }
        }

        //todo: put player on queue, process queue


        return getLocation(biomeNames);
    }

    @Override
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

        RTPWorld world = (RTPWorld) data.getOrDefault(RegionKeys.world,RTP.getInstance().serverAccessor.getDefaultRTPWorld());

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

            if(pass) break;
            else {
                location = null;
            }
        }

        return location;
    }

    @Override
    public void shutDown() {
        //todo: save, load
    }
}
