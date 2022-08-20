package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPBlock;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class FillTask extends RTPRunnable {
    private final Region region;
    private final long start;

    public FillTask(Region region, long start) {
        this.region = region;
        this.start = start;
    }

    @Override
    public void run() {
        long timingStart = System.currentTimeMillis();
        Set<String> biomeNames = Region.defaultBiomes;

        EnumMap<RegionKeys, Object> data = region.getData();

        Object shapeObj = data.get(RegionKeys.shape);
        if(!(shapeObj instanceof MemoryShape shape)) return;

        Object vertObj = data.get(RegionKeys.vert);
        if(!(vertObj instanceof VerticalAdjustor vert)) return;

        Object worldObj = data.get(RegionKeys.world);
        if(!(worldObj instanceof RTPWorld world)) return;

        ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.getInstance().configs.getParser(SafetyKeys.class);

        Set<String> unsafeBlocks = safety.yamlFile.getStringList("unsafeBlocks")
                .stream().map(String::toUpperCase).collect(Collectors.toSet());

        int safetyRadius = safety.yamlFile.getInt("safetyRadius", 0);
        safetyRadius = Math.max(safetyRadius,7);

        long pos;
        for(pos = start; pos < shape.getRange() && System.currentTimeMillis()-timingStart<5000; pos++) {
            int[] select = shape.locationToXZ(pos);

            String currBiome = world.getBiome(select[0], (vert.maxY() + vert.minY()) / 2, select[1]);

            if(!biomeNames.contains(currBiome)) {
                shape.addBadLocation(pos);
                continue;
            }


            if(!RTP.serverAccessor.getWorldBorder(world.name()).isInside()
                    .apply(new RTPLocation(world,select[0]*16, (vert.maxY()-vert.minY())/2+vert.minY(), select[1]*16))) {
                continue;
            }


            CompletableFuture<RTPChunk> cfChunk = world.getChunkAt(select[0], select[1]);

            RTPChunk chunk;

            try {
                chunk = cfChunk.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                return;
            }

            RTPLocation location = vert.adjust(chunk);
            if(location == null) {
                shape.addBadLocation(pos);
                continue;
            }

            currBiome = world.getBiome(location.x(), location.y(), location.z());

            if(!biomeNames.contains(currBiome)) {
                shape.addBadLocation(pos);
                continue;
            }

            boolean pass = location != null;

            //todo: waterlogged check
            RTPBlock block;
            for(int x = location.x()-safetyRadius; x < location.x()+safetyRadius && pass; x++) {
                for(int z = location.z()-safetyRadius; z < location.z()+safetyRadius && pass; z++) {
                    for(int y = location.y()-safetyRadius; y < location.y()+safetyRadius && pass; y++) {
                        block = chunk.getBlockAt(x,y,z);
                        if(unsafeBlocks.contains(block.getMaterial())) pass = false;
                    }
                }
            }


            if(pass) pass = Region.checkGlobalRegionVerifiers(location);

            if(pass) {
                shape.addBiomeLocation(pos,currBiome);
                break;
            }
            else {
                shape.addBadLocation(pos);
                shape.removeBiomeLocation(pos,currBiome);
            }
        }

        long completedChecks = pos-start;
        RTP.log(Level.INFO, "[plugin] completed " + completedChecks + " checks");

        RTP.getInstance().fillTasks.putIfAbsent(region.name,new RTPTaskPipe());
        RTP.getInstance().fillTasks.get(region.name).add(new FillTask(region,pos));
    }
}
