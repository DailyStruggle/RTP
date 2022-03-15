package leafcraft.rtp.API.selection.region.selectors.memory;

import leafcraft.rtp.API.selection.SelectionAPI;
import leafcraft.rtp.API.selection.region.selectors.SelectorInterface;
import leafcraft.rtp.API.selection.region.selectors.SelectorParams;
import leafcraft.rtp.API.selection.region.selectors.Shape;
import leafcraft.rtp.API.selection.region.selectors.memory.fill.FillTask;
import leafcraft.rtp.API.selection.worldborder.WorldBorder;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public abstract class MemorySelectorInterface implements SelectorInterface {
    protected FillTask fillTask;

    public abstract AtomicLong badLocationsIter();
    public abstract ConcurrentSkipListMap<Long,Long> badLocations();
    public abstract Map<Biome,ConcurrentSkipListMap<Long,Long>> biomeLocations();

    public boolean save() {
        ArrayList<String> linesArray = new ArrayList<>();

        linesArray.add("name:"+name());
        EnumMap<SelectorParams,Object> actualParams = params();
        EnumMap<SelectorParams,Object> fileParams = new EnumMap<>(SelectorParams.class);
        for(SelectorParams param : SelectorParams.values()) {
            Object val = actualParams.get(param);
            String valStr = (val.getClass().isPrimitive())
                    ? val.toString()
                    : val.getClass().getSimpleName();
            linesArray.add(param.name().toLowerCase() + ":" + valStr);
        }

        try {

            fillIterGuard.acquire();
            linesArray.add("iter:" + fillIter);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        } finally {
            fillIterGuard.release();
        }


        linesArray.add("badLocations:");
        for(Map.Entry<Long,Long> entry : badLocations().entrySet()) {
            linesArray.add("  -" + entry.getKey() + "," + entry.getValue());
        }

        linesArray.add("biomes:");
        for(Map.Entry<Biome, ConcurrentSkipListMap<Long, Long>> biomeEntry : biomeLocations().entrySet()) {
            linesArray.add("  " + biomeEntry.getKey().name() +":");
            for(Map.Entry<Long,Long> entry : biomeEntry.getValue().entrySet()) {
                linesArray.add("    -" + entry.getKey() + "," + entry.getValue());
            }
        }

        Plugin plugin = RTP.getInstance();
        File f = new File(plugin.getDataFolder(), "regions"+File.separatorChar+name()+".dat");
        File parentDir = f.getParentFile();
        if(!parentDir.exists()) {
            boolean mkdirs = parentDir.mkdirs();
            if(!mkdirs) throw new DirectoryIteratorException(new IOException("[rtp] failed to create regions directory"));
        }
        if(!f.exists()) {
            try {
                boolean newFile = f.createNewFile();
                if(!newFile) throw new FileAlreadyExistsException("[rtp] failed to create " + f.getName());
            } catch (IOException e) {
                e.printStackTrace();
                return false;
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
        }
        return true;
    }

    public boolean load() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("RTP");
        File f = new File(Objects.requireNonNull(plugin).getDataFolder(), "regions"+File.separatorChar+name()+".dat");
        if(!f.exists()) return false;

        Scanner scanner;
        String line;
        try {
            scanner = new Scanner(
                    new File(f.getAbsolutePath()));
            String name = scanner.nextLine().substring(5);
            if(!name.equalsIgnoreCase(name())) return false;
            EnumMap<SelectorParams,Object> actualParams = params();
            EnumMap<SelectorParams,Object> fileParams = new EnumMap<>(SelectorParams.class);
            for(SelectorParams param : SelectorParams.values()) {
                try {
                    fileParams.put(param, scanner.nextLine().substring(param.name().length()+1));
                } catch (IndexOutOfBoundsException | NoSuchElementException  e) {
                    return false;
                }
            }

            for(SelectorParams param : SelectorParams.values()) {
                if(!actualParams.containsKey(param)) return false;
                if(!fileParams.containsKey(param)) return false;
                String paramValStr = (String) actualParams.get(param);
                Object actualVal = actualParams.get(param);
                String compareStr = (actualVal.getClass().isPrimitive())
                        ? actualVal.toString()
                        : actualVal.getClass().getSimpleName();

                if(!paramValStr.equals(compareStr)) return false;
            }

            if(!scanner.hasNextLine()) {
                scanner.close();
                return false;
            }
            line = scanner.nextLine();

            if(line.startsWith("iter")) {
                Future<Boolean> setCorrectly = setFillIterator(Long.parseLong(line.substring(5)));
                try {
                    if(!setCorrectly.get()) return false;
                } catch (InterruptedException | ExecutionException ignored) {
                    return false;
                }
            }

            while (scanner.hasNextLine() && !line.startsWith("badLocations")){
                line = scanner.nextLine();
            }
            if(!scanner.hasNextLine()) {
                scanner.close();
                return false;
            }

            while(scanner.hasNextLine()) {
                line = scanner.nextLine();
                if (!line.startsWith("  -")) break;
                String val = line.substring(3);
                int delimiterIdx = val.indexOf(',');
                Long location = Long.parseLong(val.substring(0, delimiterIdx));
                Long length = Long.parseLong(val.substring(delimiterIdx + 1));
                Map.Entry<Long, Long> lower = badLocations().floorEntry(location);
                if (lower != null && location == lower.getKey() + lower.getValue()) {
                    badLocations().put(lower.getKey(), lower.getValue() + length);
                    length += lower.getValue();
                } else badLocations().put(location, length);
                badLocationsIter().addAndGet(length);
            }

            while(scanner.hasNextLine()) {
                line = scanner.nextLine();
                if(!line.startsWith("  ")) break;
                if(line.startsWith("    -")) continue;
                Biome biome = Biome.valueOf(line.substring(2,line.length()-1));
                biomeLocations().putIfAbsent(biome, new ConcurrentSkipListMap<>());
                ConcurrentSkipListMap<Long,Long> map = biomeLocations().get(biome);
                while(scanner.hasNextLine()) {
                    line = scanner.nextLine();
                    if(!line.startsWith("    -")) {
                        if(line.startsWith("  ")) {
                            biome = Biome.valueOf(line.substring(2,line.length()-1));
                            biomeLocations().putIfAbsent(biome, new ConcurrentSkipListMap<>());
                            map = biomeLocations().get(biome);
                        }
                        else break;
                        continue;
                    }
                    String val = line.substring(5);
                    int delimiterIdx = val.indexOf(',');
                    Long start = Long.parseLong(val.substring(0,delimiterIdx));
                    Long length = Long.parseLong(val.substring(delimiterIdx+1));

                    Map.Entry<Long,Long> lower = map.floorEntry(start);
                    if(lower!=null && start == lower.getKey()+lower.getValue()) {
                        map.put(lower.getKey(),lower.getValue()+length);
                    }
                    else map.put(start,length);
                }
            }
            scanner.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return true;
    }

    public boolean isKnownBad(int chunkX, int chunkZ) {
        Shape shape = (Shape) params().get(SelectorParams.SHAPE);
        return isKnownBad((long)shape.xzToLocation(chunkX,chunkZ));
    }

    public boolean isKnownBad(long location) {
        Map.Entry<Long,Long> lower = badLocations().floorEntry(location);
        if(lower!=null) return (location < (lower.getKey() + lower.getValue()));
        return false;
    }

    public void addBadLocation(int chunkX, int chunkZ) {
        Shape shape = (Shape) params().get(SelectorParams.SHAPE);
        addBadLocation((long)shape.xzToLocation(chunkX,chunkZ));
    }

    public void addBadLocation(long location) {
        if(!isInBounds(location)) return;

        ConcurrentSkipListMap<Long, Long> badLocations = badLocations();
        if(badLocations == null) badLocations = new ConcurrentSkipListMap<>();

        Map.Entry<Long,Long> lower = badLocations.floorEntry(location);
        Map.Entry<Long,Long> upper = badLocations.ceilingEntry(location);

        //goal: merge adjacent values
        // if within bounds of lower entry, do nothing
        // if lower start+length meets position, add 1 to its length and use that
        if((lower!=null) && (location < lower.getKey()+lower.getValue())) {
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
        if((upper!=null) && (lower.getKey()+lower.getValue() >= upper.getKey())) {
            badLocations.put(lower.getKey(),lower.getValue()+upper.getValue());
            badLocations.remove(upper.getKey());
        }

        badLocationsIter().incrementAndGet();
    }

    public void addBiomeLocation(int chunkX, int chunkZ, Biome biome) {
        Shape shape = (Shape) params().get(SelectorParams.SHAPE);
        addBiomeLocation((long)shape.xzToLocation(chunkX,chunkZ),biome);
    }

    public void addBiomeLocation(long location, Biome biome) {

    }

    public abstract Location getBiomeLocation(Biome biome);

    public abstract void removeBiomeLocation(int chunkX, int chunkZ, Biome biome);
    public abstract void removeBiomeLocation(long location, Biome biome);

    public long memSelect() {
        long r;
        long cr = (Long) params().get(SelectorParams.CR);
        long cx = (Long) params().get(SelectorParams.CX);
        long cz = (Long) params().get(SelectorParams.CZ);
        boolean worldBorderOverride = (Boolean) params().get(SelectorParams.WORLDBORDEROVERRIDE);
        World world = Bukkit.getWorld((String) params().get(SelectorParams.WORLD));
        assert world != null;
        WorldBorder worldBorder = SelectionAPI.getWorldBorder(world.getSpawnLocation());

        if(worldBorderOverride) {
            r = worldBorder.getRadius(world)/16-1;
            Location center = worldBorder.getCenter(world);

            if( r < cr ) cr = r/4;

            int localCX = center.getBlockX();
            if(localCX < 0) localCX = (localCX / 16) - 1;
            else localCX = localCX / 16;

            int localCZ = center.getBlockZ();
            if(localCZ < 0) localCZ = (localCZ / 16) - 1;
            else localCZ = localCZ / 16;

            String shapeName = worldBorder.getShape(world);
            Shape shape = (Shape) params().get(SelectorParams.SHAPE);

            if(localCX != cx || localCZ != cz || !shapeName.equalsIgnoreCase(shape.name())) {
                badLocations().clear();
                biomeLocations().clear();
                cx = localCX;
                cz = localCZ;
                this.shape = shape;
            }
            totalSpace = (r-cr)*(r+cr);
        }

        double space = totalSpace;
        if((!expand) && mode.equals(TeleportRegion.Modes.ACCUMULATE)) space -= badLocationsIter().get();
        else if(expand && !mode.equals(TeleportRegion.Modes.ACCUMULATE)) space += badLocationsIter().get();
        double res = (space) * Math.pow(ThreadLocalRandom.current().nextDouble(),weight);

        return (long)res;
    }
}