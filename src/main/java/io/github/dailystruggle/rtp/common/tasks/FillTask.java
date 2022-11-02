package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPBlock;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class FillTask extends RTPRunnable {
    public static final AtomicLong fillIncrement = new AtomicLong(0L);
    private static final AtomicLong cps = new AtomicLong(128);
    public AtomicBoolean pause = new AtomicBoolean(false);

    private final Region region;
    private final long start;
    private final CompletableFuture<Boolean> done = new CompletableFuture<>();
    private final AtomicLong completionCounter = new AtomicLong();
    private final Semaphore completionGuard = new Semaphore(1);
    private final List<CompletableFuture<RTPChunk>> chunks = new ArrayList<>();
    private final List<CompletableFuture<Boolean>> tests = new ArrayList<>();

    public FillTask(Region region, long start) {
        this.region = region;
        this.start = start;

        if(fillIncrement.get() <= 0) {
            long cpu = Runtime.getRuntime().availableProcessors();
            fillIncrement.set(cpu * 10000 / 64);
        }
        else {
            //try for 5 seconds between messages
            fillIncrement.set(cps.get()*5);
        }
    }

    @Override
    public void run() {
        if(pause.get() || isCancelled() || fillIncrement.get()<=0) return;

        isRunning.setValue(true);
        long timingStart = System.currentTimeMillis();

        MemoryShape<?> shape = (MemoryShape<?>) region.getShape();

        long range = Double.valueOf(shape.getRange()).longValue();
        long pos;
        long limit = fillIncrement.get();
        for(pos = start; pos < range && pos < start+limit; pos++) {
            if(pause.get() || isCancelled()) {
                isRunning.setValue(false);
                return;
            }

            if(shape.isKnownBad(pos)) {
                try {
                    completionGuard.acquire();
                    completionCounter.addAndGet(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    done.complete(false);
                }
                finally {
                    completionGuard.release();
                }
                continue;
            }

            CompletableFuture<Boolean> future = testPos(region, pos);

            long finalPos = pos;
            future.whenComplete((aBoolean, throwable) -> {
                if(isCancelled()) return;
                if(!aBoolean) shape.addBadLocation(finalPos);
                try {
                    completionGuard.acquire();
                    long l = completionCounter.addAndGet(1);
                    if(finalPos == range-1 || l == limit) done.complete(true);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    done.complete(false);
                }
                finally {
                    completionGuard.release();
                }
            });
        }

        long finalPos1 = pos;
        done.whenComplete((aBoolean, throwable) -> {
            if(isCancelled()) return;

            long completedChecks = finalPos1-start;
            long dt = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()-timingStart);
            if(dt <= 0) dt = 1;
            long cps_local = (long) (((double) completedChecks)/(dt));
            cps.set(cps.get()/2+cps_local/2);
            long numLoadsRemaining = range-finalPos1;
            if(numLoadsRemaining<0 || numLoadsRemaining>range) numLoadsRemaining = 0;
            long estRemaining = numLoadsRemaining/cps_local;

            ConfigParser<MessagesKeys> langParser = (ConfigParser<MessagesKeys>) RTP.getInstance().configs.getParser(MessagesKeys.class);
            String msg = langParser.getConfigValue(MessagesKeys.fillStatus, "").toString();
            if(msg!=null && !msg.isEmpty()) {
                long days = TimeUnit.SECONDS.toDays(estRemaining);
                long hours = TimeUnit.SECONDS.toHours(estRemaining) % 24;
                long minutes = TimeUnit.SECONDS.toMinutes(estRemaining) % 60;
                long seconds = estRemaining % 60;

                String replacement = "";
                if (days > 0) replacement += days + langParser.getConfigValue(MessagesKeys.days, "").toString() + " ";
                if (hours > 0) replacement += hours + langParser.getConfigValue(MessagesKeys.hours, "").toString() + " ";
                if (minutes > 0) replacement += minutes + langParser.getConfigValue(MessagesKeys.minutes, "").toString() + " ";
                if (seconds > 0) replacement += seconds + langParser.getConfigValue(MessagesKeys.seconds, "").toString();

                msg = StringUtils.replaceIgnoreCase(msg, "[chunks]", String.valueOf(finalPos1));
                msg = StringUtils.replaceIgnoreCase(msg, "[totalChunks]", String.valueOf(range));
                msg = StringUtils.replaceIgnoreCase(msg, "[cps]", String.valueOf(cps.get()));
                msg = StringUtils.replaceIgnoreCase(msg, "[eta]", replacement);
                msg = StringUtils.replaceIgnoreCase(msg, "[region]", region.name);

                RTP.serverAccessor.announce(msg,"rtp.fill");
            }

            shape.fillIter.set(finalPos1);
            shape.save(region.name,region.getWorld().name());

            if(aBoolean && finalPos1<range && !isCancelled()) RTP.getInstance().fillTasks.put(region.name, new FillTask(region, finalPos1));
            else RTP.getInstance().fillTasks.remove(region.name);
            isRunning.setValue(false);
        });
    }

    public CompletableFuture<Boolean> testPos(Region region, long pos) {
        CompletableFuture<Boolean> res = new CompletableFuture<>();
        tests.add(res);

        MemoryShape<?> shape = (MemoryShape<?>) region.getShape();
        RTPWorld world = region.getWorld();
        VerticalAdjustor<?> vert = region.getVert();

        int[] select = shape.locationToXZ(pos);

        String initialBiome = world.getBiome(select[0], (vert.maxY() + vert.minY()) / 2, select[1]);

        if(!Region.defaultBiomes.contains(initialBiome)) {
            res.complete(false);
            return res;
        }

        boolean isInside = true;
        try {
            isInside = RTP.serverAccessor.getWorldBorder(world.name()).isInside()
                    .apply(new RTPLocation(world,select[0]*16, (vert.maxY()-vert.minY())/2+vert.minY(), select[1]*16));
        }
        catch (IllegalStateException ignored) {

        }

        if(!isInside || isCancelled() || pause.get()) {
            res.complete(false);
            return res;
        }

        CompletableFuture<RTPChunk> cfChunk = world.getChunkAt(select[0], select[1]);
        chunks.add(cfChunk);

        cfChunk.whenComplete((chunk, throwable) -> {
            if(isCancelled()) return;
            RTPLocation location = vert.adjust(chunk);
            if(location == null) {
                res.complete(false);
                chunk.unload();
                return;
            }

            String currBiome = world.getBiome(location.x(), location.y(), location.z());

            if(!Region.defaultBiomes.contains(currBiome)) {
                res.complete(false);
                chunk.unload();
                return;
            }

            boolean pass = location != null;

            ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.getInstance().configs.getParser(SafetyKeys.class);
            ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.getInstance().configs.getParser(PerformanceKeys.class);

            Set<String> unsafeBlocks = safety.yamlFile.getStringList("unsafeBlocks")
                    .stream().map(String::toUpperCase).collect(Collectors.toSet());

            int safetyRadius = safety.yamlFile.getInt("safetyRadius", 0);
            safetyRadius = Math.max(safetyRadius,7);

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


            if(isCancelled()) {
                chunk.unload();
                return;
            }
            if(pass) pass = Region.checkGlobalRegionVerifiers(location);

            if(pass) {
                res.complete(true);
                if(Boolean.parseBoolean(perf.getConfigValue(PerformanceKeys.biomeRecall, false).toString()))
                    shape.addBiomeLocation(pos,currBiome);
            }
            else {
                res.complete(false);
            }
            chunk.unload();
        });
        return res;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        if(cancelled) {
            try {
                done.cancel(true);
            } catch (CancellationException | CompletionException ignored) {

            }
            try {
                chunks.forEach(rtpChunkCompletableFuture -> rtpChunkCompletableFuture.cancel(true));
            } catch (CancellationException | CompletionException ignored) {

            }
            try {
                tests.forEach(test -> test.cancel(true));
            } catch (CancellationException | CompletionException ignored) {

            }
        }
        super.setCancelled(cancelled);
    }

    public static void kill() {
        RTP.getInstance().fillTasks.forEach((s, fillTask) -> fillTask.setCancelled(true));
        RTP.getInstance().fillTasks.clear();
    }
}
