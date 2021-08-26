package leafcraft.rtp.tools.selection;

import io.papermc.lib.PaperLib;
import it.unimi.dsi.fastutil.Hash;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.softdepends.GriefPreventionChecker;
import leafcraft.rtp.tools.softdepends.WorldGuardChecker;
import leafcraft.rtp.tools.HashableChunk;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class TeleportRegion {
    private static final Set<String> regionParams = new HashSet<>();
    static {
        regionParams.add("world");
        regionParams.add("shape");
        regionParams.add("radius");
        regionParams.add("centerRadius");
        regionParams.add("centerX");
        regionParams.add("centerZ");
        regionParams.add("weight");
        regionParams.add("minY");
        regionParams.add("maxY");
        regionParams.add("requireSkyLight");
        regionParams.add("requirePermission");
        regionParams.add("worldBorderOverride");
        regionParams.add("uniquePlacements");
    }

    private static final Set<Material> acceptableAir = new HashSet<>();

    static {
        acceptableAir.add(Material.AIR);
        acceptableAir.add(Material.CAVE_AIR);
        acceptableAir.add(Material.VOID_AIR);
    }

    public String name;

    private final Configs configs;
    private final Cache cache;

    private final World world;
    private double totalSpace;

    //location queue for this region and associated chunks
    private ConcurrentLinkedQueue<Location> locationQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Location, List<CompletableFuture<Chunk>>> locAssChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<HashableChunk, CompletableFuture<Chunk>> currChunks = new ConcurrentHashMap<>();

    //player reservation queue for reserving a spot on death
    private final ConcurrentHashMap<UUID,Location> playerNextLocation = new ConcurrentHashMap<>();

    //list of bad chunks in this region to avoid retries
    private ConcurrentSkipListMap<Long,Long> badLocations = new ConcurrentSkipListMap<>();
    private long badLocationSum = 0;

    public enum Shapes{SQUARE,CIRCLE}
    public Shapes shape;

    private final double weight;

    public boolean requireSkyLight, uniquePlacements, expand;

    public int r, cr, cx, cz, minY, maxY;

    public TeleportRegion(String name, Map<String,String> params, Configs configs, Cache cache) {
            this.name = name;
            this.configs = configs;
            this.cache = cache;
            String worldName = params.getOrDefault("world","world");
            if(!configs.worlds.checkWorldExists(worldName)) worldName = "world";
            this.world = Bukkit.getWorld(worldName);

            String shapeStr =   params.get("shape");
            String rStr =       params.get("radius");
            String crStr =      params.get("centerRadius");
            String cxStr =      params.get("centerX");
            String czStr =      params.get("centerZ");
            String weightStr =  params.get("weight");
            String minYStr =    params.get("minY");
            String maxYStr =    params.get("maxY");
            String rslStr =     params.get("requireSkyLight");
            String upStr =      params.get("uniquePlacements");
            String expandStr =  params.get("expand");

            r = Integer.parseInt(rStr);
            cr = Integer.parseInt(crStr);
            cx = Integer.parseInt(cxStr);
            cz = Integer.parseInt(czStr);

            weight = Double.parseDouble(weightStr);
            minY = Integer.parseInt(minYStr);
            maxY = Integer.parseInt(maxYStr);
            requireSkyLight = Boolean.parseBoolean(rslStr);
            uniquePlacements = Boolean.parseBoolean(upStr);
            expand = Boolean.parseBoolean(expandStr);

            try{
                this.shape = TeleportRegion.Shapes.valueOf(shapeStr.toUpperCase(Locale.ENGLISH));
            }
            catch (IllegalArgumentException exception) {
                this.shape = TeleportRegion.Shapes.CIRCLE;
            }

            this.totalSpace = (r-cr)*(r+cr);
            if (this.shape == Shapes.SQUARE) {
                this.totalSpace = totalSpace * 4;
            } else {
                this.totalSpace = totalSpace * Math.PI;
            }
        }

        public void shutdown() {
            for(List<CompletableFuture<Chunk>> chunks : locAssChunks.values()) {
                for(CompletableFuture<Chunk> chunk : chunks) {
                    if(!chunk.isDone()) {
                        chunk.cancel(true);
                    }
                }
            }
            for(CompletableFuture<Chunk> chunk : currChunks.values()) {
                if(!chunk.isDone()) {
                    chunk.cancel(true);
                }
            }
            locAssChunks.clear();
            locationQueue.clear();
        }

        public Location getLocation(boolean urgent, CommandSender sender, Player player) {
            Location res = null;

            if(playerNextLocation.containsKey(player.getUniqueId())) {
                Location location = playerNextLocation.get(player.getUniqueId());
                playerNextLocation.remove(player.getUniqueId());
                return location;
            }

            try{
                res = locationQueue.remove();
            }
            catch (NoSuchElementException | NullPointerException exception) {
                if(sender.hasPermission("rtp.unqueued")) {
                    res = getRandomLocation(urgent);
                    if(res == null) {
                        Integer maxAttempts = configs.config.maxAttempts;
                        player.sendMessage(configs.lang.getLog("unsafe",maxAttempts.toString()));
                        if(!sender.getName().equals(player.getName()))
                            sender.sendMessage(configs.lang.getLog("unsafe",maxAttempts.toString()));
                    }
                }
                else {
                    player.sendMessage(configs.lang.getLog("noLocationsQueued"));
                    if(!sender.getName().equals(player.getName()))
                        sender.sendMessage(configs.lang.getLog("noLocationsQueued"));
                }
            }
            return res;
        }

        private void addChunks(Location location, boolean urgent) {
            List<CompletableFuture<Chunk>> chunks = new ArrayList<>();
            locAssChunks.put(location,chunks);

            int vd = Bukkit.getViewDistance();
            int cx = (location.getBlockX()>0) ? location.getBlockX()/16 : location.getBlockX()/16-1;
            int cz = (location.getBlockZ()>0) ? location.getBlockZ()/16 : location.getBlockZ()/16-1;
            for(int i = -vd; i < vd; i++) {
                for(int j = -vd; j < vd; j++) {
                    HashableChunk hc = new HashableChunk(location.getWorld(), cx+i, cz+j);
                    cache.keepChunks.putIfAbsent(hc, 0L);
                    cache.keepChunks.compute(hc, (k, v) -> v + 1);
                    CompletableFuture<Chunk> cfChunk;
                    cfChunk = (urgent) ? PaperLib.getChunkAtAsyncUrgently(Objects.requireNonNull(location.getWorld()), cx+i, cz+j, true)
                            : PaperLib.getChunkAtAsync(Objects.requireNonNull(location.getWorld()), cx+i, cz+j, true);
                    chunks.add(cfChunk);
                    if(uniquePlacements) {
                        addBadLocation(cx+i,cz+j);
                    }
                }
            }
        }

        public List<CompletableFuture<Chunk>> getChunks(Location location) {
            return locAssChunks.getOrDefault(location,new ArrayList<>());
        }

        public void removeChunks(Location location) {
            locAssChunks.remove(location);
        }

        public void queueLocation(Location location) {
            if(location == null) return;
            locationQueue.offer(location);
        }

        public void queueRandomLocation() {
            if(locationQueue == null) {
                locationQueue = new ConcurrentLinkedQueue<>();
            }
            Integer queueLen = (Integer)configs.regions.getRegionSetting(name,"queueLen",0);
            if(locationQueue.size() >= queueLen) return;

            Location location = getRandomLocation(false);
            if(location == null) {
                return;
            }
            queueLocation(location);
        }

        public void queueRandomLocation(Player player) {
            if(playerNextLocation.containsKey(player.getUniqueId())) return;
            if(locationQueue.size() > 1 && locationQueue.size() >= (Integer)configs.regions.getRegionSetting(name,"queueLen",0)) {
                playerNextLocation.put(player.getUniqueId(),locationQueue.remove());
                return;
            }

            Location location = getRandomLocation(true);
            if(location == null) {
                return;
            }
            playerNextLocation.put(player.getUniqueId(),location);
        }

        public void recyclePlayerLocation(Player player) {
            if(!playerNextLocation.containsKey(player.getUniqueId())) return;
            Location location = playerNextLocation.get(player.getUniqueId());
            playerNextLocation.remove(player.getUniqueId());
            queueLocation(location);
        }

        public void addBadLocation(int chunkX, int chunkZ) {
            double location = (shape.equals(Shapes.SQUARE)) ?
                    Translate.xzToSquareLocation(cr,chunkX,chunkZ,cx,cz) :
                    Translate.xzToCircleLocation(cr,chunkX,chunkZ,cx,cz);
            addBadLocation((long)location);
        }

        private void addBadLocation(Long location) {
            if(location < 0) return;
            if(location > totalSpace) return;

            if(badLocations == null) badLocations = new ConcurrentSkipListMap<>();

            Map.Entry<Long,Long> lower = badLocations.floorEntry(location);
            Map.Entry<Long,Long> upper = badLocations.ceilingEntry(location);

            //goal: merge adjacent values
            // if within bounds of lower entry, do nothing
            // if lower start+length meets position, add 1 to its length and use that
            if((lower!=null) && (location < lower.getKey()+lower.getValue())) {
//                Bukkit.getLogger().warning("[rtp] Location:" + location + " was already known bad, skipping...");
                return;
            }
            else if((lower!=null) && (location == lower.getKey()+lower.getValue())) {
                badLocations.put(lower.getKey(),lower.getValue()+1);
            }
            else {
                badLocations.put(location, 1L);
            }

            lower = badLocations.floorEntry(location);

            // if upper start meets position + length, merge its length and delete upper entry
            if((upper!=null)&&(lower.getKey()+lower.getValue() >= upper.getKey())) {
                badLocations.put(lower.getKey(),lower.getValue()+upper.getValue());
                badLocations.remove(upper.getKey());
            }

            badLocationSum++;
        }

        private long select() {
            double space = totalSpace;
            if(!expand) space -= badLocationSum;
            double res = (space) * Math.pow(ThreadLocalRandom.current().nextDouble(),weight);
            return (long)res;
        }

        private Location getRandomLocation(boolean urgent) {
            Location res;

            boolean rerollLiquid = configs.config.rerollLiquid;
            boolean rerollWorldGuard = configs.config.rerollWorldGuard;
            boolean rerollGriefPrevention = configs.config.rerollGriefPrevention;

            double selectTime = 0D;
            double chunkTime = 0D;
            double yTime = 0D;

            long start = System.currentTimeMillis();
            long location = select();

            Map.Entry<Long,Long> idx = badLocations.firstEntry();
            while((idx!=null) && (location >= (idx.getKey()))) {
                location += idx.getValue();
                idx = badLocations.ceilingEntry(idx.getKey()+idx.getValue());
            }

            int[] xz = new int[2];
            int[] xzChunk;

            xzChunk = shape.equals(TeleportRegion.Shapes.SQUARE) ?
                    (Translate.squareLocationToXZ(cr, cx, cz, location)) :
                    (Translate.circleLocationToXZ(cr, cx, cz, location));

            xz[0] = (xzChunk[0]*16)+7;
            xz[1] = (xzChunk[1]*16)+7;

            long stop = System.currentTimeMillis();
            selectTime += (stop-start);

            start = System.currentTimeMillis();
            CompletableFuture<Chunk> cfChunk = (urgent) ?
                    PaperLib.getChunkAtAsyncUrgently(world,xzChunk[0],xzChunk[1],true) :
                    PaperLib.getChunkAtAsync(world,xzChunk[0],xzChunk[1],true);
            HashableChunk hashableChunk = new HashableChunk(world,xzChunk[0],xzChunk[1]);
            currChunks.put(hashableChunk,cfChunk);

            ChunkSnapshot chunkSnapshot;
            try {
                chunkSnapshot = cfChunk.get().getChunkSnapshot(); //wait on chunk load/gen
            } catch (ExecutionException e) {
                e.printStackTrace();
                return null;
            } catch (InterruptedException | CancellationException | StackOverflowError e) {
                return null;
            }
            currChunks.remove(hashableChunk);

            stop = System.currentTimeMillis();
            chunkTime += (stop-start);

            int y;
            start = System.currentTimeMillis();
            y = this.getFirstNonAir(chunkSnapshot);
            y = this.getLastNonAir(chunkSnapshot,y);
            res = new Location(world,xz[0],y,xz[1]);
            stop = System.currentTimeMillis();
            yTime += (stop - start);

            Integer numAttempts = 1;
            Integer maxAttempts = configs.config.maxAttempts;
            while(numAttempts < maxAttempts &&
                    ( acceptableAir.contains(res.getBlock().getType())
                            || (res.getBlockY() >= maxY)
                            || (rerollLiquid && res.getBlock().isLiquid())
                            || (rerollWorldGuard && WorldGuardChecker.isInRegion(res))
                            || (rerollGriefPrevention && GriefPreventionChecker.isInClaim(res)))) {
                addBadLocation(location);

                start = System.currentTimeMillis();
                location = select();

                idx = badLocations.firstEntry();
                while((idx!=null) && (location >= (idx.getKey()))) {
                    location += idx.getValue();
                    idx = badLocations.ceilingEntry(idx.getKey()+idx.getValue());
                }

                xzChunk = shape.equals(TeleportRegion.Shapes.SQUARE) ?
                        (Translate.squareLocationToXZ(cr, cx, cz, location)) :
                        (Translate.circleLocationToXZ(cr, cx, cz, location));

                xz[0] = (xzChunk[0]*16)+7;
                xz[1] = (xzChunk[1]*16)+7;

                stop = System.currentTimeMillis();
                selectTime += (stop-start);

                start = System.currentTimeMillis();
                cfChunk = (urgent) ?
                        PaperLib.getChunkAtAsyncUrgently(world,xzChunk[0],xzChunk[1],true) :
                        PaperLib.getChunkAtAsync(world,xzChunk[0],xzChunk[1],true);
                hashableChunk = new HashableChunk(world,xzChunk[0],xzChunk[1]);
                currChunks.put(hashableChunk,cfChunk);

                try {
                    chunkSnapshot = cfChunk.get().getChunkSnapshot(); //wait on chunk load/gen
                } catch (ExecutionException e) {
                    e.printStackTrace();
                    return null;
                } catch (InterruptedException | CancellationException | StackOverflowError e) {
                    return null;
                }
                currChunks.remove(hashableChunk);

                stop = System.currentTimeMillis();
                chunkTime += (stop-start);

                start = System.currentTimeMillis();
                y = this.getFirstNonAir(chunkSnapshot);
                y = this.getLastNonAir(chunkSnapshot,y);
                res = new Location(world,xz[0],y,xz[1]);
                stop = System.currentTimeMillis();
                yTime += (stop - start);

                numAttempts++;
            }

            res.setY(res.getBlockY()+1);
            res.setX(res.getBlockX()+0.5);
            res.setZ(res.getBlockZ()+0.5);

            if(numAttempts >= maxAttempts) {
                return null;
            }

            this.cache.numTeleportAttempts.put(res, numAttempts);
            addChunks(res, urgent);

//        Bukkit.getLogger().warning(ChatColor.AQUA + "AVG TIME SPENT ON SELECTION: " + selectTime/numAttempts);
//        Bukkit.getLogger().warning(ChatColor.LIGHT_PURPLE + "AVG TIME SPENT ON CHUNKS: " + chunkTime/numAttempts);
//        Bukkit.getLogger().warning(ChatColor.GREEN + "AVG TIME SPENT ON BLOCKS: " + yTime/numAttempts);

            return res;
        }

        public int getFirstNonAir(ChunkSnapshot chunk) {
            int i = minY;
            //iterate over a good distance to reduce thin floors
            for(; i <= maxY; i+=8) {
                if(!acceptableAir.contains(chunk.getBlockType(7,i,7))) {
                    break;
                }
                if(i >= this.maxY-8) return this.maxY;
            }
            return i;
        }

        public int getLastNonAir(ChunkSnapshot chunk, int y) {
            int oldY = y;
            int minY = y;
            int maxY = this.maxY;

            //iterate over a larger distance first, then fine-tune
            for(int it_length = (maxY-minY)/16; it_length > 0; it_length = it_length/2) {
                int i = minY;
                for(; i <= maxY; i+=it_length) {
                    int skyLight = 15;
                    if(requireSkyLight) skyLight = chunk.getBlockSkyLight(7,i,7);

                    if(acceptableAir.contains(chunk.getBlockType(7,i,7))
                            && acceptableAir.contains(chunk.getBlockType(7,i+1,7))
                            && skyLight>0) {
                        minY = oldY;
                        maxY = i;
                        break;
                    }
                    if(i >= this.maxY-it_length) return this.maxY;
                    oldY = i;
                }
            }

            for(int i = minY; i <= maxY; i++) {
                int skyLight = 15;
                if(requireSkyLight) skyLight = chunk.getBlockSkyLight(7,i,7);

                if(acceptableAir.contains(chunk.getBlockType(7,i,7))
                        && acceptableAir.contains(chunk.getBlockType(7,i+1,7))
                        && skyLight>0) {
                    minY = oldY;
                    break;
                }
                oldY = i;
            }
            return minY;
        }

        public boolean isKnownBad(int x, int z) {
            double location = (shape.equals(Shapes.SQUARE)) ?
                    Translate.xzToSquareLocation(cr,x,z,cx,cz) :
                    Translate.xzToCircleLocation(cr,x,z,cx,cz);
            return isKnownBad((long)location);
        }

        public boolean isKnownBad(long location) {
            Map.Entry<Long,Long> lower = badLocations.floorEntry(location);

            if((lower!=null) && (location < lower.getKey()+lower.getValue())) {
                return true;
            }
            return false;
        }

        public boolean isInBounds(int x, int z) {
            double location = (shape.equals(Shapes.SQUARE)) ?
                    Translate.xzToSquareLocation(cr,x,z,cx,cz) :
                    Translate.xzToCircleLocation(cr,x,z,cx,cz);
            return isInBounds((long)location);
        }

        public boolean isInBounds(long location) {
            if((location > totalSpace) || (location < 0)) {
                return false;
            }
            return true;
        }

        public void loadFile() {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("RTP");
            File f = new File(plugin.getDataFolder(), "regions"+File.separatorChar+name+".dat");
            if(!f.exists()) return;

            ArrayList<String> linesArray = new ArrayList<>();
            try {
                Scanner scanner = new Scanner(
                        new File(f.getAbsolutePath()));
                while (scanner.hasNextLine()) {
                    linesArray.add(scanner.nextLine() + "");
                }
                scanner.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            String name = linesArray.get(0).substring(5);
            Shapes shape = Shapes.valueOf(linesArray.get(1).substring(6));
            String worldName = linesArray.get(2).substring(6);
            int cr = Integer.parseInt(linesArray.get(3).substring(3));
            int cx = Integer.parseInt(linesArray.get(4).substring(3));
            int cz = Integer.parseInt(linesArray.get(5).substring(3));
            int minY = Integer.parseInt(linesArray.get(6).substring(5));
            int maxY = Integer.parseInt(linesArray.get(7).substring(5));
            boolean requireSkyLight = Boolean.parseBoolean(linesArray.get(8).substring(16));
            boolean uniquePlacements = Boolean.parseBoolean(linesArray.get(9).substring(17));

            if(!name.equals(this.name)) return;
            if(!shape.equals(this.shape)) return;
            if(!worldName.equals(this.world.getName())) return;
            if(!(cr==this.cr)) return;
            if(!(cx==this.cx)) return;
            if(!(cz==this.cz)) return;
            if(!(minY==this.minY)) return;
            if(!(maxY==this.maxY)) return;
            if(!(requireSkyLight==this.requireSkyLight)) return;
            if(!(uniquePlacements==this.uniquePlacements)) return;

            for(int i = 11; i < linesArray.size(); i++) {
                String val = linesArray.get(i).substring(3);
                int delimiterIdx = val.indexOf(',');
                Long location = Long.parseLong(val.substring(0,delimiterIdx));
                Long length = Long.parseLong(val.substring(delimiterIdx+1));
                if(location<0) continue;
                badLocations.put(location,length);
                badLocationSum+=length;
            }
        }

        public void storeFile() {
            ArrayList<String> linesArray = new ArrayList<>();
            linesArray.add("name:"+name);
            linesArray.add("shape:"+shape.toString());
            linesArray.add("world:"+world.getName());
            linesArray.add("cr:"+cr);
            linesArray.add("cx:"+cx);
            linesArray.add("cz:"+cz);
            linesArray.add("minY:"+minY);
            linesArray.add("maxY:"+maxY);
            linesArray.add("requireSkyLight:"+requireSkyLight);
            linesArray.add("uniquePlacements:"+uniquePlacements);
            linesArray.add("badLocations:");
            for(Map.Entry<Long,Long> entry : badLocations.entrySet()) {
                linesArray.add("  -" + entry.getKey() + "," + entry.getValue());
            }

            Plugin plugin = Bukkit.getPluginManager().getPlugin("RTP");
            File f = new File(plugin.getDataFolder(), "regions"+File.separatorChar+name+".dat");
            File parentDir = f.getParentFile();
            if(!parentDir.exists()) {
                parentDir.mkdirs();
            }
            if(!f.exists()) {
                try {
                    f.createNewFile();
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
            }

            FileWriter fw;
            try {
                fw = new FileWriter(f.getAbsolutePath());
                for (String s : linesArray) {
                    fw.write(s + "\n");
                }
                fw.close();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        }
    }
