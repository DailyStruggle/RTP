package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.*;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.*;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
import io.github.dailystruggle.rtp.common.tasks.LoadChunks;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.MemorySection;
import org.simpleyaml.configuration.file.YamlFile;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class Region extends FactoryValue<RegionKeys> {
    private final Semaphore cacheGuard = new Semaphore(1);
    public static Set<String> defaultBiomes;
    public static final List<BiConsumer<Region,UUID>> onPlayerQueuePush = new ArrayList<>();
    public static final List<BiConsumer<Region,UUID>> onPlayerQueuePop = new ArrayList<>();

    public static int maxBiomeChecksPerGen = 5;

    /**
     * public/shared cache for this region
     */
    public ConcurrentLinkedQueue<Map.Entry<RTPLocation,Long>> locationQueue = new ConcurrentLinkedQueue<>();
    public ConcurrentHashMap<RTPLocation, ChunkSet> locAssChunks = new ConcurrentHashMap<>();
    protected ConcurrentLinkedQueue<UUID> playerQueue = new ConcurrentLinkedQueue<>();

    /**
     * When reserving/recycling locations for specific players,
     * I want to guard against
     */
    public ConcurrentHashMap<UUID, ConcurrentLinkedQueue<Map.Entry<RTPLocation,Long>>> perPlayerLocationQueue = new ConcurrentHashMap<>();

    /**
     *
     */
    public ConcurrentHashMap<UUID, CompletableFuture<Map.Entry<RTPLocation,Long>>> fastLocations = new ConcurrentHashMap<>();

    //semaphore needed in case of async usage
    //storage for region verifiers to use for ALL regions
    private static final Semaphore regionVerifiersLock = new Semaphore(1);
    private static final List<Predicate<RTPLocation>> regionVerifiers = new ArrayList<>();

    /**
     * addGlobalRegionVerifier - add a region verifier to use for ALL regions
     * @param locationCheck verifier method to reference.
     *                 param: world name, 3D point
     *                 return: boolean - true on good location, false on bad location
     */
    public static void addGlobalRegionVerifier(Predicate<RTPLocation> locationCheck) {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return;
        }
        regionVerifiers.add(locationCheck);
        regionVerifiersLock.release();
    }

    public static void clearGlobalRegionVerifiers() {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return;
        }
        regionVerifiers.clear();
        regionVerifiersLock.release();
    }

    public static boolean checkGlobalRegionVerifiers(RTPLocation location) {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return false;
        }

        for(Predicate<RTPLocation> verifier : regionVerifiers) {
            try {
                //if invalid placement, stop and return invalid
                //clone location to prevent methods from messing with the data
                if(!verifier.test(location)) {
                    regionVerifiersLock.release();
                    return false;
                }
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
        regionVerifiersLock.release();
        return true;
    }

    //localized generic task for
    protected class Cache extends RTPRunnable {
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
            long cacheCap = getNumber(RegionKeys.cacheCap,10L).longValue();
            cacheCap = Math.max(cacheCap,playerQueue.size());
            Map.Entry<RTPLocation, Long> pair = getLocation(null);
            if(pair != null) {
                RTPLocation location = pair.getKey();
                if(location == null) {
                    if(cachePipeline.size()+locationQueue.size()<cacheCap+playerQueue.size()) cachePipeline.add(new Cache());
                    return;
                }

                ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
                long radius = perf.getNumber(PerformanceKeys.viewDistanceSelect,0L).longValue();

                ChunkSet chunkSet = chunks(location, radius);

                chunkSet.whenComplete(aBoolean -> {
                    if(aBoolean) {
                        if(playerId == null) {
                            locationQueue.add(pair);
                            locAssChunks.put(pair.getKey(), chunkSet);
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
            if(cachePipeline.size()+locationQueue.size()<cacheCap+playerQueue.size()) cachePipeline.add(new Cache());
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
        try {
            cacheGuard.acquire();
            if(locationQueue.size()>=cacheCap) return;
            while(cachePipeline.size()+locationQueue.size()<cacheCap+playerQueue.size())
                cachePipeline.add(new Cache());
            cachePipeline.execute(availableTime - (System.nanoTime()-start));
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            cacheGuard.release();
        }

        while (locationQueue.size() > 0 && playerQueue.size() > 0) {
            UUID playerId = playerQueue.poll();

            TeleportData teleportData = RTP.getInstance().latestTeleportData.get(playerId);
            if(teleportData == null || teleportData.completed) {
                RTP.getInstance().processingPlayers.remove(playerId);
                return;
            }

            RTPPlayer player = RTP.serverAccessor.getPlayer(playerId);
            if(player == null) continue;

            Map.Entry<RTPLocation, Long> pair = locationQueue.poll();
            if(pair == null) {
                playerQueue.add(playerId);
                continue;
            }

            teleportData.attempts = pair.getValue();

            RTPCommandSender sender = RTP.serverAccessor.getSender(CommandsAPI.serverId);
            LoadChunks loadChunks = new LoadChunks(sender,player,pair.getKey(),this);
            teleportData.nextTask = loadChunks;
            instance.latestTeleportData.put(playerId,teleportData);
            instance.loadChunksPipeline.add(loadChunks);
            onPlayerQueuePop.forEach(consumer -> consumer.accept(this,playerId));

            Iterator<UUID> iterator = playerQueue.iterator();
            int i = 0;
            while (iterator.hasNext()) {
                UUID id = iterator.next();
                ++i;
                TeleportData data = RTP.getInstance().latestTeleportData.get(id);
                RTP.getInstance().processingPlayers.add(id);
                if(data == null) {
                    data = new TeleportData();
                    data.completed=false;
                    data.sender = RTP.serverAccessor.getSender(CommandsAPI.serverId);
                    data.time = System.nanoTime();
                    data.delay = sender.delay();
                    data.targetRegion = this;
                    data.originalLocation = player.getLocation();
                    RTP.getInstance().latestTeleportData.put(id,data);
                }
                data.queueLocation = i;
                RTP.serverAccessor.sendMessage(id, MessagesKeys.queueUpdate);
            }
        }
    }

    public Region(String name, EnumMap<RegionKeys,Object> params) {
        super(RegionKeys.class, name);
        this.name = name;
        this.data.putAll(params);

        Object shape = getShape();
        Object world = params.get(RegionKeys.world);
        String worldName;
        if(world instanceof RTPWorld) worldName = ((RTPWorld) world).name();
        else {
            worldName = String.valueOf(world);
        }
        if(shape instanceof MemoryShape<?>) {
            ((MemoryShape<?>) shape).load(name + ".yml",worldName);
            long iter = ((MemoryShape<?>) shape).fillIter.get();
            if(iter>0 && iter<Double.valueOf(((MemoryShape<?>) shape).getRange()).longValue()) RTP.getInstance().fillTasks.put(name,new FillTask(this,iter));
        }

        long cacheCap = getNumber(RegionKeys.cacheCap,10L).longValue();
        for(long i = cachePipeline.size(); i < cacheCap; i++) {
            cachePipeline.add(new Cache());
        }

        Set<String> defaultBiomes;
        ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        Object configBiomes = safety.getConfigValue(SafetyKeys.biomes,null);
        if(configBiomes instanceof Collection) {
            boolean whitelist;
            Object configValue = safety.getConfigValue(SafetyKeys.biomeWhitelist, false);
            if(configValue instanceof Boolean) whitelist = (Boolean) configValue;
            else whitelist = Boolean.parseBoolean(configValue.toString());

            Set<String> collect = ((Collection<?>) configBiomes).stream().map(Object::toString).collect(Collectors.toSet());

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

    public Map.Entry<RTPLocation, Long> getLocation(RTPCommandSender sender, RTPPlayer player, @Nullable Set<String> biomeNames) {
        Map.Entry<RTPLocation, Long> pair = null;

        UUID playerId = player.uuid();

        boolean custom = biomeNames != null && biomeNames.size() > 0;

        if(!custom && perPlayerLocationQueue.containsKey(playerId)) {
            ConcurrentLinkedQueue<Map.Entry<RTPLocation, Long>> playerLocationQueue = perPlayerLocationQueue.get(playerId);
            while(playerLocationQueue.size()>0) {
                pair = playerLocationQueue.poll();
                if(pair == null || pair.getKey() == null) continue;
                RTPLocation left = pair.getKey();
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

                ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
                YamlFile yamlFile = safety.fileDatabase.cachedLookup.get().get("safety.yml");
                Set<String> unsafeBlocks = yamlFile.getStringList("unsafeBlocks")
                        .stream().map(String::toUpperCase).collect(Collectors.toSet());

                int safetyRadius = yamlFile.getInt("safetyRadius", 0);
                safetyRadius = Math.max(safetyRadius,7);

                //todo: waterlogged check
                RTPBlock block;
                for(int x = left.x()-safetyRadius; x < left.x()+safetyRadius && pass; x++) {
                    for(int z = left.z()-safetyRadius; z < left.z()+safetyRadius && pass; z++) {
                        for(int y = left.y()-safetyRadius; y < left.y()+safetyRadius && pass; y++) {
                            block = rtpChunk.getBlockAt(x,y,z);
                            if(unsafeBlocks.contains(block.getMaterial())) pass = false;
                        }
                    }
                }

                if(pass) pass = checkGlobalRegionVerifiers(left);
                if(pass) return pair;
            }
        }

        if(!custom && locationQueue.size()>0) {
            pair = locationQueue.poll();
            if(pair == null) return null;
            RTPLocation left = pair.getKey();
            if(left == null) return pair;
            boolean pass = checkGlobalRegionVerifiers(left);
            if(pass) return pair;
        }

        if(custom || sender.hasPermission("rtp.unqueued")) {
            pair = getLocation(biomeNames);
            long attempts = pair.getValue();
            TeleportData data = RTP.getInstance().latestTeleportData.get(playerId);
            if(data!=null && !data.completed) {
                data.attempts = attempts;
            }
        }
        else {
            TeleportData data = RTP.getInstance().latestTeleportData.get(playerId);
            RTP.getInstance().processingPlayers.add(playerId);
            if(data == null) {
                data = new TeleportData();
                data.completed=false;
                data.time = System.nanoTime();
                data.delay = sender.delay();
                data.targetRegion = this;
                data.originalLocation = player.getLocation();
                RTP.getInstance().latestTeleportData.put(playerId,data);
            }
            onPlayerQueuePush.forEach(consumer -> consumer.accept(this,playerId));
            playerQueue.add(playerId);
            data.queueLocation = playerQueue.size();
            RTP.serverAccessor.sendMessage(playerId, MessagesKeys.queueUpdate);
        }
        return pair;
    }

    @Nullable
    public Map.Entry<RTPLocation, Long> getLocation(@Nullable Set<String> biomeNames) {
        boolean defaultBiomes = false;
        ConfigParser<PerformanceKeys> performance = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        Object o;
        if(biomeNames == null || biomeNames.size()==0) {
            defaultBiomes = true;
            o = safety.getConfigValue(SafetyKeys.biomeWhitelist, false);
            boolean whitelist = (o instanceof Boolean) ? (Boolean) o : Boolean.parseBoolean(o.toString());

            o = safety.getConfigValue(SafetyKeys.biomes,null);
            List<String> biomeList = (o instanceof List) ? (List<String>) o : null;
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

        ConfigParser<LoggingKeys> logging = (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
        boolean verbose = true;
        if(logging!=null) {
            o = logging.getConfigValue(LoggingKeys.teleport,false);
            if (o instanceof Boolean) {
                verbose = (Boolean) o;
            } else {
                verbose = Boolean.parseBoolean(o.toString());
            }
        }

        Shape<?> shape = getShape();
        if(shape == null) return null;

        VerticalAdjustor<?> vert = getVert();
        if(vert == null) return null;

        o = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
        Set<String> unsafeBlocks = (o instanceof Collection) ? ((Collection<String>) o)
                .stream().map(String::toUpperCase).collect(Collectors.toSet())
                : new HashSet<>();

        int safetyRadius = safety.getNumber(SafetyKeys.safetyRadius,0).intValue();
        safetyRadius = Math.max(safetyRadius,7);

        long maxAttempts = performance.getNumber(PerformanceKeys.maxAttempts, 20).longValue();
        maxAttempts = Math.max(maxAttempts,1);
        long maxBiomeChecks = maxBiomeChecksPerGen*maxAttempts;
        long biomeChecks = 0L;

        RTPWorld world = getWorld();

        long biomeFails = 0L;
        long worldBorderFails = 0L;
        long timeoutFails = 0L;
        long vertFails = 0L;
        long safetyFails = 0L;
        long miscFails = 0L;

        Map<String,Long> biomeSpecificFails = new HashMap<>();
        Map<String,Long> safetySpecificFails = new HashMap<>();
        List<Map.Entry<Long,Long>> selections = new ArrayList<>();

        RTPLocation location = null;
        long i = 1;

        for(; i <= maxAttempts; i++) {
            long l = -1;
            int[] select;
            if(shape instanceof MemoryShape) {
                l = shape.rand();
                select = ((MemoryShape<?>) shape).locationToXZ(l);
                if(verbose) selections.add(new AbstractMap.SimpleEntry<>((long)selections.size(),l));
            }
            else {
                select = shape.select();
                if(verbose) selections.add(new AbstractMap.SimpleEntry<>((long)select[0],(long)select[1]));
            }

            String currBiome = world.getBiome(select[0], (vert.maxY() + vert.minY()) / 2, select[1]);

            for(; biomeChecks < maxBiomeChecks && !biomeNames.contains(currBiome); biomeChecks++, maxAttempts++, i++) {
                if(shape instanceof MemoryShape) {
                    if (defaultBiomes) {
                        ((MemoryShape<?>) shape).addBadLocation(l);
                    }
                    l = shape.rand();
                    select = ((MemoryShape<?>) shape).locationToXZ(l);
                    if(verbose) selections.add(new AbstractMap.SimpleEntry<>((long)selections.size(),l));
                }
                else {
                    select = shape.select();
                    if(verbose) selections.add(new AbstractMap.SimpleEntry<>((long)select[0],(long)select[1]));
                }
                biomeSpecificFails.putIfAbsent(currBiome,0L);
                biomeSpecificFails.put(currBiome, biomeSpecificFails.get(currBiome)+1);
                currBiome = world.getBiome(select[0], (vert.maxY()+vert.minY())/2, select[1]);
                biomeFails++;
            }
            if(biomeChecks>=maxBiomeChecks) return new AbstractMap.SimpleEntry<>(null,i);

            WorldBorder border = RTP.serverAccessor.getWorldBorder(world.name());
            if(!border.isInside().apply(new RTPLocation(world,select[0]*16, (vert.maxY()-vert.minY())/2+vert.minY(), select[1]*16))) {
                maxAttempts++;
                worldBorderFails++;
                if(worldBorderFails>1000) {
                    new IllegalStateException("1000 worldborder checks failed. region/selection is likely outside the worldborder").printStackTrace();
                    return new AbstractMap.SimpleEntry<>(null,i);
                }
                continue;
            }

            CompletableFuture<RTPChunk> cfChunk = world.getChunkAt(select[0], select[1]);

            RTPChunk chunk;

            try {
                chunk = cfChunk.get(5000,TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                return new AbstractMap.SimpleEntry<>(null,i);
            } catch (TimeoutException e) {
                cfChunk.cancel(false);
                timeoutFails++;
                continue;
            }

            location = vert.adjust(chunk);
            if(location == null) {
                if(defaultBiomes && shape instanceof MemoryShape) {
                    ((MemoryShape<?>) shape).addBadLocation(l);
                }
                vertFails++;
                continue;
            }

            currBiome = world.getBiome(location.x(), location.y(), location.z());

            if(!biomeNames.contains(currBiome)) {
                biomeChecks++;
                maxAttempts++;
                if(defaultBiomes && shape instanceof MemoryShape) {
                    ((MemoryShape<?>) shape).addBadLocation(l);
                }
                biomeSpecificFails.putIfAbsent(currBiome,0L);
                biomeSpecificFails.put(currBiome, biomeSpecificFails.get(currBiome)+1);
                biomeFails++;
                continue;
            }

            boolean pass = location != null;

            //todo: waterlogged check
            RTPBlock block;
            for(int x = location.x()-safetyRadius; x < location.x()+safetyRadius && pass; x++) {
                for(int z = location.z()-safetyRadius; z < location.z()+safetyRadius && pass; z++) {
                    for(int y = location.y()-safetyRadius; y < location.y()+safetyRadius && pass; y++) {
                        block = chunk.getBlockAt(x,y,z);
                        String material = block.getMaterial();
                        if(unsafeBlocks.contains(material)) {
                            pass = false;
                            safetyFails++;
                            safetySpecificFails.putIfAbsent(material,0L);
                            safetySpecificFails.put(material, safetySpecificFails.get(material)+1);
                        }
                    }
                }
            }

            if(pass) {
                pass = checkGlobalRegionVerifiers(location);
                if(!pass) miscFails++;
            }

            if(pass) {
                if(shape instanceof MemoryShape) {
                    ((MemoryShape<?>) shape).addBiomeLocation(l,currBiome);
                }
                break;
            }
            else {
                if(shape instanceof MemoryShape) {
                    ((MemoryShape<?>) shape).addBadLocation(l);
                    ((MemoryShape<?>) shape).removeBiomeLocation(l,currBiome);
                }
                location = null;
            }
        }

        if (verbose && i >= maxAttempts) {
            RTP.log(Level.WARNING,"[plugin] ["+name+"] failed to generate a location within " + maxAttempts + " tries");
            RTP.log(Level.WARNING,"[plugin] ["+name+"]     failed biome checks: "+biomeFails);
            if(biomeFails>maxAttempts/2) {
                RTP.log(Level.WARNING,"[plugin] ["+name+"] biomes: \n"+ Arrays.toString(biomeNames.toArray()));
                biomeSpecificFails.forEach((key, value) -> RTP.log(Level.WARNING,
                        "[plugin] [" + name + "]     " + key + ": " + value));
            }
            RTP.log(Level.WARNING,"[plugin] ["+name+"]     failed world border checks: "+worldBorderFails);
            RTP.log(Level.WARNING,"[plugin] ["+name+"]     chunk timeouts: "+timeoutFails);
            RTP.log(Level.WARNING,"[plugin] ["+name+"]     failed height checks: "+vertFails);
            if(vertFails>maxAttempts/2) {
                RTP.log(Level.WARNING,"[plugin] ["+name+"] current vert values: "+vert);
            }
            RTP.log(Level.WARNING,"[plugin] ["+name+"]     failed safety checks: "+safetyFails);
            if(safetyFails>maxAttempts/2) {
                RTP.log(Level.WARNING,"[plugin] ["+name+"] current set of unsafe blocks: \n"+ Arrays.toString(unsafeBlocks.toArray()));
                safetySpecificFails.forEach((key, value) -> RTP.log(Level.WARNING,
                        "[plugin] [" + name + "]     " + key + ": " + value));
            }
            RTP.log(Level.WARNING,"[plugin] ["+name+"]     failed addon checks: "+miscFails);

            if(shape instanceof MemoryShape) {
                RTP.log(Level.INFO,"[plugin] ["+name+"] range: " + ((MemoryShape<?>)shape).getRange());
            }
            RTP.log(Level.INFO,"[plugin] ["+name+"] selections: "+selections);
        }

        i = Math.min(i,maxAttempts);

        return new AbstractMap.SimpleEntry<>(location,i);
    }

    public void shutDown() {
        Shape<?> shape = getShape();
        if(shape == null) return;

        RTPWorld world = getWorld();
        if(world == null) return;

        if(shape instanceof MemoryShape<?>) {
            ((MemoryShape<?>) shape).save(this.name + ".yml", world.name());
        }

        cachePipeline.stop();
        cachePipeline.clear();

        playerQueue.clear();
        perPlayerLocationQueue.clear();
        fastLocations.clear();
        locationQueue.clear();
        locAssChunks.forEach((rtpLocation, chunkSet) -> chunkSet.keep(false));
        locAssChunks.clear();
    }

    @Override
    public Region clone() {
        Region clone = (Region) super.clone();
        clone.locationQueue = new ConcurrentLinkedQueue<>();
        clone.locAssChunks = new ConcurrentHashMap<>();
        clone.playerQueue = new ConcurrentLinkedQueue<>();
        clone.perPlayerLocationQueue = new ConcurrentHashMap<>();
        clone.fastLocations = new ConcurrentHashMap<>();
        return clone;
    }

    public Map<String,String> params() {
        Map<String,String> res = new ConcurrentHashMap<>();
        for(Map.Entry<? extends Enum<?>,?> e : data.entrySet()) {
            Object value = e.getValue();
            if(value instanceof RTPWorld) {
                res.put("world",((RTPWorld) value).name());
            }
            else if(value instanceof Shape) {
                res.put("shape", ((Shape<?>) value).name);
                EnumMap<? extends Enum<?>,Object> data = ((Shape<?>) value).getData();
                for(Map.Entry<? extends Enum<?>,?> dataEntry : data.entrySet()) {
                    res.put(dataEntry.getKey().name(),dataEntry.getValue().toString());
                }
            }
            else if(value instanceof VerticalAdjustor) {
                res.put("vert", ((VerticalAdjustor<?>) value).name);
                EnumMap<? extends Enum<?>,Object> data = ((VerticalAdjustor<?>) value).getData();
                for(Map.Entry<? extends Enum<?>,?> dataEntry : data.entrySet()) {
                    res.put(dataEntry.getKey().name(),dataEntry.getValue().toString());
                }
            }
            else if(value instanceof String)
                res.put(e.getKey().name(), (String) value);
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
            if(chunkSet.chunks.size()>=sz) return chunkSet;
            chunkSet.keep(false);
        }

        int cx = location.x();
        int cz = location.z();
        cx = (cx >0) ? cx /16 : cx /16-1;
        cz = (cz >0) ? cz /16 : cz /16-1;

        List<CompletableFuture<RTPChunk>> chunks = new ArrayList<>();

        Shape<?> shape = getShape();
        if(shape == null) return null;

        VerticalAdjustor<?> vert = getVert();
        if(vert == null) return null;

        RTPWorld rtpWorld = getWorld();
        if(rtpWorld == null) return null;

        for(long i = -radius; i <= radius; i++) {
            for(long j = -radius; j <= radius; j++) {
                CompletableFuture<RTPChunk> cfChunk = location.world().getChunkAt((int)(cx + i), (int)(cz + j));
                if(shape instanceof MemoryShape<?>) {
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
                            pass = checkGlobalRegionVerifiers(rtpLocation);
                        }

                        if (pass) {
                            ((MemoryShape<?>) shape).addBiomeLocation((long) ((MemoryShape<?>) shape).xzToLocation(finalI, finalJ), currBiome);
                        } else {
                            long l = (long) ((MemoryShape<?>) shape).xzToLocation(finalI, finalJ);
                            ((MemoryShape<?>) shape).addBadLocation(l);
                            ((MemoryShape<?>) shape).removeBiomeLocation(l, currBiome);
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

    public CompletableFuture<Map.Entry<RTPLocation, Long>> fastQueue(UUID id) {
        if(fastLocations.containsKey(id)) return fastLocations.get(id);
        CompletableFuture<Map.Entry<RTPLocation, Long>> res = new CompletableFuture<>();
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
        ConcurrentLinkedQueue<Map.Entry<RTPLocation, Long>> queue = perPlayerLocationQueue.get(uuid);
        if(queue!=null) res+= queue.size();
        if(fastLocations.containsKey(uuid)) res++;
        return res;
    }

    public long getPublicQueueLength() {
        return locationQueue.size();
    }

    public long getPersonalQueueLength(UUID uuid) {
        long res = 0;
        ConcurrentLinkedQueue<Map.Entry<RTPLocation, Long>> queue = perPlayerLocationQueue.get(uuid);
        if(queue!=null) res += queue.size();
        if(fastLocations.containsKey(uuid)) res++;
        return res;
    }

    public Shape<?> getShape() {
        boolean wbo = false;
        Object o = data.getOrDefault(RegionKeys.worldBorderOverride,false);
        if(o instanceof Boolean) wbo = (Boolean) o;
        else if(o instanceof String) {
            wbo = Boolean.parseBoolean((String) o);
            data.put(RegionKeys.worldBorderOverride,wbo);
        }

        RTPWorld world;
        o = data.getOrDefault(RegionKeys.world, null);
        if(o instanceof RTPWorld) world = (RTPWorld) o;
        else if(o instanceof String) {
            world = RTP.serverAccessor.getRTPWorld((String) o);
        }
        else world = null;
        if(world == null) world = RTP.serverAccessor.getRTPWorlds().get(0);

        Object shapeObj = data.get(RegionKeys.shape);
        Shape<?> shape;
        if (shapeObj instanceof Shape) {
            shape = (Shape<?>) shapeObj;
        }
        else if(shapeObj instanceof MemorySection) {
            final Map<String, Object> shapeMap = ((MemorySection) shapeObj).getMapValues(true);
            String shapeName = String.valueOf(shapeMap.get("name"));
            Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
            shape = (Shape<?>) factory.get(shapeName);
            EnumMap<?, Object> shapeData = shape.getData();
            for(Map.Entry<? extends Enum<?>,Object> e : shapeData.entrySet()) {
                String name = e.getKey().name();
                if(shapeMap.containsKey(name)) {
                    e.setValue(shapeMap.get(name));
                }
                else {
                    Object altName = shape.language_mapping.get(name);
                    if(altName!=null && shapeMap.containsKey(altName.toString())) {
                        e.setValue(shapeMap.get(altName.toString()));
                    }
                }
            }
            shape.setData(shapeData);
            data.put(RegionKeys.shape,shape);
        }
        else throw new IllegalArgumentException("invalid shape\n" + shapeObj);

        if(wbo) {
            Shape<?> worldShape;
            try {
                worldShape = RTP.serverAccessor.getWorldBorder(world.name()).getShape().get();
            } catch (IllegalStateException ignored) {
                return shape;
            }
            if(!worldShape.equals(shape)) {
                shape = worldShape;
                data.put(RegionKeys.shape,shape);
            }
        }

        return shape;
    }

    public VerticalAdjustor<?> getVert() {
        Object vertObj = data.get(RegionKeys.vert);
        VerticalAdjustor<?> vert;
        if (vertObj instanceof VerticalAdjustor) {
            vert = (VerticalAdjustor<?>) vertObj;
        }
        else if(vertObj instanceof MemorySection) {
            final Map<String, Object> vertMap = ((MemorySection) vertObj).getMapValues(true);
            String shapeName = String.valueOf(vertMap.get("name"));
            Factory<VerticalAdjustor<?>> factory = (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
            vert = (VerticalAdjustor<?>) factory.get(shapeName);
            EnumMap<?, Object> vertData = vert.getData();
            for(Map.Entry<? extends Enum<?>,Object> e : vertData.entrySet()) {
                String name = e.getKey().name();
                if(vertMap.containsKey(name)) {
                    e.setValue(vertMap.get(name));
                }
                else {
                    Object altName = vert.language_mapping.get(name);
                    if(altName!=null && vertMap.containsKey(altName.toString())) {
                        e.setValue(vertMap.get(altName.toString()));
                    }
                }
            }
            vert.setData(vertData);
            data.put(RegionKeys.vert,vert);
        }
        else throw new IllegalArgumentException("invalid shape\n" + vertObj);

        return vert;
    }

    public RTPWorld getWorld() {
        Object world = data.get(RegionKeys.world);
        if(world instanceof RTPWorld) return (RTPWorld) world;
        else {
            String worldName = String.valueOf(world);
            RTPWorld rtpWorld;
            if(worldName.startsWith("[") && worldName.endsWith("]")) {
                int num = Integer.parseInt(worldName.substring(1,worldName.length()-1));
                rtpWorld = RTP.serverAccessor.getRTPWorlds().get(num);
            }
            else rtpWorld = RTP.serverAccessor.getRTPWorld(worldName);
            if(rtpWorld == null) rtpWorld = RTP.serverAccessor.getRTPWorlds().get(0);
            data.put(RegionKeys.world,rtpWorld);
            return rtpWorld;
        }
    }
}