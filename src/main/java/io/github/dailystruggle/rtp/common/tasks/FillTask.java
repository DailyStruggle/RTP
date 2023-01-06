package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPBlock;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tools.ChunkyRTPShape;
import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.Selection;
import org.popcraft.chunky.shape.Shape;
import org.popcraft.chunky.shape.ShapeFactory;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AtomicLong completionCounter = new AtomicLong();
    private final Semaphore completionGuard = new Semaphore(1);
    private final List<CompletableFuture<RTPChunk>> chunks = new ArrayList<>();
    private final Semaphore testsGuard = new Semaphore(1);
    private final AtomicReference<List<CompletableFuture<Boolean>>> tests = new AtomicReference<>(new ArrayList<>());
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
                try {
                    completionGuard.acquire();
                    long l = completionCounter.incrementAndGet();
                    if (pos == range - 1 || l == limit) {
                        done.complete(true);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    done.complete(false);
                } finally {
                    completionGuard.release();
                }
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

        String initialBiome = world.getBiome(select[0] * 16, (vert.maxY() + vert.minY()) / 2, select[1] * 16);

        if(!Region.defaultBiomes.contains(initialBiome)) {
            res.complete(false);

            ConfigParser<PerformanceKeys> performance = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
            boolean biomeRecall = Boolean.parseBoolean(performance.getConfigValue(PerformanceKeys.biomeRecall, false).toString());
            if (biomeRecall) {
                shape.addBadLocation(pos);
            }
            return res;
        }

        boolean isInside = true;
        try {
            isInside = RTP.serverAccessor.getWorldBorder(world.name()).isInside()
                    .apply(new RTPLocation(world, select[0] * 16, (vert.maxY() - vert.minY()) / 2 + vert.minY(), select[1] * 16));
        } catch (IllegalStateException ignored) {

        }

        if (isInside && shape instanceof ChunkyRTPShape) {
            ChunkyRTPShape chunkyRTPShape = (ChunkyRTPShape) shape;
            Selection.Builder builder = Selection.builder(ChunkyProvider.get(), null);
            builder.centerX(chunkyRTPShape.getNumber(RectangleParams.centerX, 0).doubleValue());
            builder.centerZ(chunkyRTPShape.getNumber(RectangleParams.centerZ, 0).doubleValue());

            builder.radius(chunkyRTPShape.getNumber(RectangleParams.width, 256).doubleValue());
            builder.radiusX(chunkyRTPShape.getNumber(RectangleParams.width, 256).doubleValue());
            builder.radiusZ(chunkyRTPShape.getNumber(RectangleParams.height, 256).doubleValue());

            builder.shape(chunkyRTPShape.chunkyShapeName);
            Shape shape1 = ShapeFactory.getShape(builder.build());

            if (!shape1.isBounding(select[0], select[1])) isInside = false;
        }

        if (!isInside || isCancelled() || pause.get()) {
            res.complete(false);
            return res;
        }

        CompletableFuture<RTPChunk> cfChunk = world.getChunkAt(select[0], select[1]);
        chunks.add(cfChunk);

        cfChunk.thenAccept(chunk -> {
            if (isCancelled()) return;
            RTPLocation location = vert.adjust(chunk);
            if (location == null) {
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

            boolean pass = location.y() < vert.maxY();
            if(!pass) {
                shape.addBadLocation(pos);
                res.complete(false);
                chunk.unload();
                return;
            }

            ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
            ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);

            Object o = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
            Set<String> unsafeBlocks = ((o instanceof Collection) ? (Collection<?>) o : new ArrayList<>())
                    .stream().map(o1 -> o1.toString().toUpperCase()).collect(Collectors.toSet());

            int safetyRadius = safety.getNumber(SafetyKeys.safetyRadius, 0).intValue();
            safetyRadius = Math.max(safetyRadius, 7);

            //todo: waterlogged check
            RTPBlock block;
            for (int x = location.x() - safetyRadius; x < location.x() + safetyRadius && pass; x++) {
                for (int z = location.z() - safetyRadius; z < location.z() + safetyRadius && pass; z++) {
                    for (int y = location.y() - safetyRadius; y < location.y() + safetyRadius && pass; y++) {
                        block = chunk.getBlockAt(x, y, z);
                        if (unsafeBlocks.contains(block.getMaterial())) pass = false;
                    }
                }
            }


            if (isCancelled()) {
                chunk.unload();
                res.complete(false);
                return;
            }
            if (pass) pass = Region.checkGlobalRegionVerifiers(location);

            if (pass) {
                if (Boolean.parseBoolean(perf.getConfigValue(PerformanceKeys.biomeRecall, false).toString()))
                    shape.addBiomeLocation(pos, currBiome);
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
}
