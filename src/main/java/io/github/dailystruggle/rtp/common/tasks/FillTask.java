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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
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
    private final Semaphore testsGuard = new Semaphore(1);
    private final AtomicReference<List<CompletableFuture<Boolean>>> tests = new AtomicReference<>(new ArrayList<>());

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
        isRunning.setTrue();
        if(pause.get() || isCancelled() || fillIncrement.get()<=0) return;


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
                    long l = completionCounter.incrementAndGet();
                    if(pos == range-1 || l == limit) {
                        done.complete(true);
                    }
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
            future.thenAccept(aBoolean -> {
                if(isCancelled()) return;
                if(!aBoolean) shape.addBadLocation(finalPos);
                try {
                    completionGuard.acquire();
                    long l = completionCounter.incrementAndGet();
                    if(finalPos == range-1 || l == limit) {
                        done.complete(true);
                    }
                } catch (InterruptedException | IllegalStateException e) {
                    e.printStackTrace();
                    done.complete(false);
                }
                finally {
                    completionGuard.release();
                }
            });
        }

        long finalPos1 = pos;
        done.thenAccept(aBoolean -> {
            if(isCancelled()) return;

            long completedChecks = finalPos1-start;
            long dt = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()-timingStart);
            if(dt <= 0) dt = 1;
            long cps_local = (long) (((double) completedChecks)/(dt));
            cps.set(cps.get()/2+cps_local/2);
            long numLoadsRemaining = range-finalPos1;
            if(numLoadsRemaining<0 || numLoadsRemaining>range) numLoadsRemaining = 0;
            long estRemaining = numLoadsRemaining/cps_local;

            ConfigParser<MessagesKeys> langParser = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
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

                msg = msg.replace("[chunks]", String.valueOf(finalPos1));
                msg = msg.replace("[totalChunks]", String.valueOf(range));
                msg = msg.replace("[cps]", String.valueOf(cps.get()));
                msg = msg.replace("[eta]", replacement);
                msg = msg.replace("[region]", region.name);

                RTP.serverAccessor.announce(msg,"rtp.fill");
            }

            shape.fillIter.set(finalPos1);
            shape.save(region.name,region.getWorld().name());
            region.getWorld().save();

            if(aBoolean && finalPos1<range && !isCancelled()) {
                RTP.getInstance().fillTasks.put(region.name, new FillTask(region, finalPos1));
            }
            else RTP.getInstance().fillTasks.remove(region.name);
            isRunning.setValue(false);
        });
    }

    public CompletableFuture<Boolean> testPos(Region region, long pos) {
        CompletableFuture<Boolean> res = new CompletableFuture<>();
        try {
            testsGuard.acquire();
            tests.get().add(res);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            testsGuard.release();
        }


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

        cfChunk.thenAccept(chunk -> {
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

            ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
            ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);

            Object o = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
            Set<String> unsafeBlocks = ((o instanceof Collection) ? (Collection<?>)o : new ArrayList<>())
                    .stream().map(o1 -> o1.toString().toUpperCase()).collect(Collectors.toSet());

            int safetyRadius = safety.getNumber(SafetyKeys.safetyRadius,0).intValue();
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
                testsGuard.acquire();
                tests.get().forEach(test -> test.cancel(true));
            } catch (CancellationException | CompletionException | InterruptedException ignored) {

            } finally {
                testsGuard.release();
            }
        }
        super.setCancelled(cancelled);
    }

    public static void kill() {
        RTP.getInstance().fillTasks.forEach((s, fillTask) -> fillTask.setCancelled(true));
        RTP.getInstance().fillTasks.clear();
    }
}
