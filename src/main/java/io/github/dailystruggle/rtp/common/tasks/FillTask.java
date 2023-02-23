package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPBlock;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class FillTask extends RTPRunnable {
    public static final AtomicLong fillIncrement = new AtomicLong(0L);
    private static final AtomicLong cps = new AtomicLong(128);
    private BigInteger cps_all = new BigInteger("0");
    private BigInteger cps_divisor = new BigInteger("0");
    private static final BigInteger increment_big = new BigInteger("1");

    private final Region region;
    private final long start;
    private final CompletableFuture<Boolean> done = new CompletableFuture<>();
    {
        RTP.futures.add(done);
    }
    private final AtomicLong completionCounter = new AtomicLong();
    private final Semaphore completionGuard = new Semaphore(1);
    private final List<CompletableFuture<RTPChunk>> chunks = new ArrayList<>();
    private final Semaphore testsGuard = new Semaphore(1);
    public AtomicBoolean pause = new AtomicBoolean(false);

    public FillTask(Region region, long start) {
        this.region = region;
        this.start = start;

        if (fillIncrement.get() <= 0) {
            long cpu = Runtime.getRuntime().availableProcessors();
            fillIncrement.set(cpu * 1000 / 32);
        } else {
            //try for 5 seconds between messages
            fillIncrement.set(cps.get() * 5);
        }
    }

    public FillTask(Region region, long start, BigInteger cps_all, BigInteger divisor) {
        this.region = region;
        this.start = start;
        this.cps_all = cps_all;
        this.cps_divisor = divisor;

        if (fillIncrement.get() <= 0) {
            long cpu = Runtime.getRuntime().availableProcessors();
            fillIncrement.set(cpu * 10000 / 64);
        } else {
            //try for 5 seconds between messages
            fillIncrement.set(cps.get() * 5);
        }
    }

    public static void kill() {
        RTP.getInstance().fillTasks.forEach((s, fillTask) -> fillTask.setCancelled(true));
        RTP.getInstance().fillTasks.clear();
    }

    @Override
    public void run() {
        isRunning.set(true);
        if (pause.get() || isCancelled() || fillIncrement.get() <= 0) return;

        long timingStart = System.currentTimeMillis();

        MemoryShape<?> shape = (MemoryShape<?>) region.getShape();

        long range = Double.valueOf(shape.getRange()).longValue();
        long pos;
        long limit = fillIncrement.get();
        for (pos = start; pos < range && pos < start + limit; pos++) {
            if (pause.get() || isCancelled()) {
                isRunning.set(false);
                return;
            }

            if (shape.isKnownBad(pos)) {
                continue;
            }

            CompletableFuture<Boolean> future = testPos(region, pos);

            long finalPos = pos;
            future.thenAccept(aBoolean -> {
                if (isCancelled()) return;
                try {
                    completionGuard.acquire();
                    long l = completionCounter.incrementAndGet();
                    if (finalPos == range - 1 || l == limit) {
                        done.complete(true);
                    }
                } catch (CancellationException e) {
                    done.complete(false);
                } catch (InterruptedException | IllegalStateException e) {
                    e.printStackTrace();
                    done.complete(false);
                } finally {
                    completionGuard.release();
                }
            });
        }

        long finalPos1 = pos;
        done.thenAccept(aBoolean -> {
            if (isCancelled()) return;

            long completedChecks = finalPos1 - start;
            long dt = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - timingStart);
            if (dt <= 0) dt = 1;
            long cps_local = (long) (((double) completedChecks) / (dt));
            cps_all = cps_all.add(new BigInteger(String.valueOf(cps_local)));
            cps_divisor = cps_divisor.add(increment_big);
            cps.set((cps.get()*7/8) + cps_local / 8);

            long numLoadsRemaining = range - finalPos1;
            if (numLoadsRemaining < 0 || numLoadsRemaining > range) numLoadsRemaining = 0;
            long estRemaining = numLoadsRemaining / cps_all.divide(cps_divisor).longValue();

            ConfigParser<MessagesKeys> langParser = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
            String msg = langParser.getConfigValue(MessagesKeys.fillStatus, "").toString();
            if (msg != null && !msg.isEmpty()) {
                long days = TimeUnit.SECONDS.toDays(estRemaining);
                long hours = TimeUnit.SECONDS.toHours(estRemaining) % 24;
                long minutes = TimeUnit.SECONDS.toMinutes(estRemaining) % 60;
                long seconds = estRemaining % 60;

                String replacement = "";
                if (days > 0) replacement += days + langParser.getConfigValue(MessagesKeys.days, "").toString() + " ";
                if (hours > 0)
                    replacement += hours + langParser.getConfigValue(MessagesKeys.hours, "").toString() + " ";
                if (minutes > 0)
                    replacement += minutes + langParser.getConfigValue(MessagesKeys.minutes, "").toString() + " ";
                if (seconds > 0)
                    replacement += seconds + langParser.getConfigValue(MessagesKeys.seconds, "").toString();

                msg = msg.replace("[chunks]", String.valueOf(finalPos1));
                msg = msg.replace("[totalChunks]", String.valueOf(range));
                msg = msg.replace("[cps]", String.valueOf(cps_local));
                msg = msg.replace("[eta]", replacement);
                msg = msg.replace("[region]", region.name);

                RTP.serverAccessor.announce(msg, "rtp.fill");
            }

            shape.fillIter.set(finalPos1);
            shape.save(region.name, region.getWorld().name());
            region.getWorld().save();

            if (aBoolean && finalPos1 < range && !isCancelled() && !pause.get()) {
                RTP.getInstance().fillTasks.put(region.name, new FillTask(region, finalPos1, cps_all,cps_divisor));
            } else RTP.getInstance().fillTasks.remove(region.name);
            isRunning.set(false);
        });
    }

    public CompletableFuture<Boolean> testPos(Region region, final long pos) {
        Set<String> defaultBiomes;

        ConfigParser<PerformanceKeys> performance = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        Object o;
        o = safety.getConfigValue(SafetyKeys.biomeWhitelist, false);
        boolean whitelist = (o instanceof Boolean) ? (Boolean) o : Boolean.parseBoolean(o.toString());

        o = safety.getConfigValue(SafetyKeys.biomes, null);
        List<String> biomeList = (o instanceof List) ? (List<String>) o : null;
        Set<String> biomeSet = (biomeList == null)
                ? new HashSet<>()
                : biomeList.stream().map(String::toUpperCase).collect(Collectors.toSet());
        if (whitelist) {
            defaultBiomes = biomeSet;
        } else {
            Set<String> biomes = RTP.serverAccessor.getBiomes(region.getWorld());
            Set<String> set = new HashSet<>();
            for (String s : biomes) {
                if (!biomeSet.contains(s.toUpperCase())) {
                    set.add(s);
                }
            }
            defaultBiomes = set;
        }

        MemoryShape<?> shape = (MemoryShape<?>) region.getShape();
        if(shape == null) return CompletableFuture.completedFuture(false);

        VerticalAdjustor<?> vert = region.getVert();
        if(vert == null) return CompletableFuture.completedFuture(false);

        o = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
        Set<String> unsafeBlocks = (o instanceof Collection) ? ((Collection<?>) o)
                .stream().map(o1 -> o1.toString().toUpperCase()).collect(Collectors.toSet())
                : new HashSet<>();

        int safetyRadius = safety.getNumber(SafetyKeys.safetyRadius, 0).intValue();

        RTPWorld world = region.getWorld();

        boolean biomeRecall = Boolean.parseBoolean(performance.getConfigValue(PerformanceKeys.biomeRecall, false).toString());

        int[] select = shape.locationToXZ(pos);

        String currBiome = world.getBiome(select[0] * 16 + 7, (vert.maxY() + vert.minY()) / 2, select[1] * 16 + 7);

        if(!defaultBiomes.contains(currBiome)) {
            if (biomeRecall) {
                shape.addBadLocation(pos);
                return CompletableFuture.completedFuture(false);
            }
        }

        WorldBorder border = RTP.serverAccessor.getWorldBorder(world.name());
        if (!border.isInside().apply(new RTPLocation(world, select[0] * 16, (vert.maxY() + vert.minY()) / 2, select[1] * 16))) {
            shape.addBadLocation(pos);
            return CompletableFuture.completedFuture(false);
        }

        if (isCancelled() || pause.get()) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<RTPChunk> cfChunk = world.getChunkAt(select[0], select[1]);
        chunks.add(cfChunk);

        CompletableFuture<Boolean> res = new CompletableFuture<>();
        cfChunk.thenAccept(chunk -> {
            if (chunk == null || isCancelled()) return;
            RTPLocation location = vert.adjust(chunk);
            if (location == null) {
                if(biomeRecall) shape.addBadLocation(pos);
                res.complete(false);
                chunk.unload();
                return;
            }

            String currBiome1 = world.getBiome(location.x(), location.y(), location.z());
            if(!defaultBiomes.contains(currBiome1)) {
                if(biomeRecall) {
                    shape.addBadLocation(pos);
                    res.complete(false);
                    chunk.unload();
                    return;
                }
            }

            boolean pass = location.y() < vert.maxY();
            if(!pass) {
                shape.addBadLocation(pos);
                res.complete(false);
                chunk.unload();
                return;
            }

            //todo: waterlogged check
            RTPBlock block;
            RTPChunk chunk1;
            Map<List<Integer>,RTPChunk> chunks = new HashMap<>();
            chunks.put(Arrays.asList(chunk.x(), chunk.z()), chunk);
            chunk.keep(true);
            for (int x = location.x() - safetyRadius; x < location.x() + safetyRadius && pass; x++) {
                int xx = x;
                int dx = Math.abs(xx/16);
                int chunkX = chunk.x();

                if(xx < 0) {
                    chunkX-=dx+1;
                    if(xx%16==0) xx+=16*dx;
                    else xx+=16*(dx+1);
                } else if(xx >= 16) {
                    chunkX+=dx;
                    xx-=16*dx;
                }

                for (int z = location.z() - safetyRadius; z < location.z() + safetyRadius && pass; z++) {
                    int zz = z;
                    int dz = Math.abs(zz/16);
                    int chunkZ = chunk.x();

                    if(zz < 0) {
                        chunkZ-=dx+1;
                        if(zz%16==0) zz+=16*dz;
                        else zz+=16*(dx+1);
                    } else if(zz >= 16) {
                        chunkZ+=dz;
                        zz-=16*dz;
                    }

                    List<Integer> xz = Arrays.asList(chunkX, chunkZ);
                    if(chunks.containsKey(xz)) chunk1 = chunks.get(xz);
                    else {
                        try {
                            chunk1 = region.getWorld().getChunkAt(chunkX, chunkZ).get();
                            if(chunk1 == null) return;
                            chunks.put(xz,chunk1);
                            chunk1.keep(true);
                        } catch (InterruptedException | ExecutionException e) {
                            return;
                        }
                    }

                    for (int y = location.y() - safetyRadius; y < location.y() + safetyRadius && pass; y++) {
                        block = chunk1.getBlockAt(xx, y, zz);
                        if (unsafeBlocks.contains(block.getMaterial())) {
                            pass = false;
                        }
                    }
                }
            }
            for(RTPChunk usedChunk : chunks.values()) usedChunk.keep(false);

            if (isCancelled()) {
                chunk.unload();
                res.complete(false);
                return;
            }

            if (pass) pass = Region.checkGlobalRegionVerifiers(location);

            if (pass) {
                if (biomeRecall) shape.addBiomeLocation(pos, currBiome1);
                res.complete(true);
            } else {
                shape.addBadLocation(pos);
                res.complete(false);
            }
            chunk.unload();
        });
        return res;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        if (cancelled) {
            try {
                done.cancel(true);
            } catch (CancellationException | CompletionException ignored) {

            }
            try {
                chunks.forEach(rtpChunkCompletableFuture -> rtpChunkCompletableFuture.cancel(true));
            } catch (CancellationException | CompletionException ignored) {

            }
        }
        super.setCancelled(cancelled);
    }
}
