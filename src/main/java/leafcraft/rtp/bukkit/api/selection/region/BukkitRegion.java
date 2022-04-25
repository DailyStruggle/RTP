package leafcraft.rtp.bukkit.api.selection.region;

import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.configuration.Configs;
import leafcraft.rtp.api.configuration.enums.ConfigKeys;
import leafcraft.rtp.api.configuration.enums.PerformanceKeys;
import leafcraft.rtp.api.configuration.enums.RegionKeys;
import leafcraft.rtp.api.selection.region.Region;
import leafcraft.rtp.api.selection.region.selectors.shapes.Shape;
import leafcraft.rtp.api.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import leafcraft.rtp.api.substitutions.RTPChunk;
import leafcraft.rtp.api.substitutions.RTPLocation;
import org.bukkit.block.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BukkitRegion extends Region {
    public BukkitRegion(String name, EnumMap<RegionKeys,Object> params) {
        super(name, params);
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
                pass &= RTPAPI.getInstance().selectionAPI.checkGlobalRegionVerifiers(location);
                if(pass) return location;
            }
        }

        //todo: put player on queue, process queue


        return getLocation(biomeNames);
    }

    @Override
    @Nullable
    public RTPLocation getLocation(@Nullable Set<String> biomeNames) {
        Shape<?> shape = (Shape<?>) data.get(RegionKeys.shape);
        if(shape == null) return null;

        VerticalAdjustor<?> vert = (VerticalAdjustor<?>) data.get(RegionKeys.vert);
        if(shape == null) return null;

        Configs configs = RTPAPI.getInstance().configs;

        long maxAttempts = configs.performance.getNumber(PerformanceKeys.maxAttempts, 20).longValue();
        maxAttempts = Math.max(maxAttempts,1);

        RTPLocation location = null;
        for(long i = 0; i < maxAttempts; i++) {
            RTPChunk select = shape.select(biomeNames);

            location = vert.adjust(select);

            boolean pass = location != null;
            pass &= RTPAPI.getInstance().selectionAPI.checkGlobalRegionVerifiers(location);

            if(pass) break;
            else location = null;
        }

        return location;
    }

    @Override
    public void shutDown() {
        //todo: check shape type for memory
    }
}
