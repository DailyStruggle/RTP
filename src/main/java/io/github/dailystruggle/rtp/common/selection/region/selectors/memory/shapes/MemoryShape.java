package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @param <E> enum for configuration values
 */
public abstract class MemoryShape<E extends Enum<E>> extends Shape<E> {
    /**
     * @param name - unique name of shape
     */
    public MemoryShape(Class<E> eClass, String name, EnumMap<E,Object> data) throws IllegalArgumentException {
        super(eClass,name,data);
    }

    public final ConcurrentSkipListMap<Long,Long> badLocations = new ConcurrentSkipListMap<>();
    public final AtomicLong badLocationSum = new AtomicLong(0L);
    public final ConcurrentHashMap<String, ConcurrentSkipListMap<Long,Long>> biomeLocations = new ConcurrentHashMap<>();

    public abstract double getRange();
    public abstract double xzToLocation(long x, long z);
    public abstract int[] locationToXZ(long location);

    public boolean isKnownBad(int x, int z) {
        return isKnownBad((long)xzToLocation(x,z));
    }

    public boolean isKnownBad(long location) {
        Map.Entry<Long,Long> lower = badLocations.floorEntry(location);
        if(lower!=null) {
            return (location < (lower.getKey() + lower.getValue()));
        }
        return false;
    }

    public void save(String fileName, String worldName) {
        Map<String,Object> params = new HashMap<>();
        params.put("world",worldName);
        for (var e : data.entrySet())
            params.put(e.getKey().name(),e.getValue().toString());

        Yaml fileYAML = new Yaml();

        File pluginDir = RTP.serverAccessor.getPluginDirectory();
        String dirPath = pluginDir.getAbsolutePath() + File.separator + "rawShapeData";
        String filePath = dirPath + File.separator + fileName;
        File dir = new File(dirPath);
        File file = new File(filePath);

        if(!dir.exists()) {
            boolean mkdirs = dir.mkdirs();
            if (!mkdirs) throw new IllegalStateException("failed to make directory");
        }

        if(!file.exists()) {
            try {
                boolean newFile = file.createNewFile();
                if(!newFile) throw new IllegalStateException("failed to make file");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        PrintWriter writer;
        try {
            writer = new PrintWriter(file);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return;
        }

        params.put("badLocations",badLocations);
        params.put("biomeLocations",biomeLocations);

        fileYAML.dump(params,writer);
    }

    public void load(String fileName, String worldName) {
        Map<String,?> resultMap;
        Map<String,Object> params = new HashMap<>();
        params.put("world",worldName);
        for (var e : data.entrySet())
            params.put(e.getKey().name(),e.getValue().toString());

        Yaml fileYAML = new Yaml();
        File pluginDir = RTP.serverAccessor.getPluginDirectory();
        String filePath = pluginDir.getAbsolutePath() + File.separator + "rawShapeData" + File.separator + fileName;
        File file = new File(filePath);
        if(!file.exists()) return;

        InputStream inputStream;
        try {
            inputStream = new FileInputStream(file);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return;
        }
        resultMap = fileYAML.load(inputStream);

        boolean eq = true;
        for (var e : params.entrySet()) {
            if(!resultMap.containsKey(e.getKey())) {
                eq = false;
                break;
            }
            if(!resultMap.get(e.getKey()).equals(e.getValue())) {
                eq = false;
                break;
            }
        }

        if(!eq) return;

        Map<?,?> badLocations = (Map<?, ?>) resultMap.get("badLocations");
        if(badLocations == null) return;
        for(var e : badLocations.entrySet()) {
            String key = String.valueOf(e.getKey());
            String val = String.valueOf(e.getValue());

            try {
                Long k = Long.parseLong(key);
                Long v = Long.parseLong(val);

                this.badLocations.put(k,v);
                this.badLocationSum.addAndGet(v);
            }
            catch (NumberFormatException exception) {
                exception.printStackTrace();
                return;
            }
        }

        Map<?,?> biomeLocations = (Map<?, ?>) resultMap.get("biomeLocations");
        if(biomeLocations == null) return;
        for(var b : biomeLocations.entrySet()) {
            String biome = String.valueOf(b.getKey());
            Map<?,?> biomeMap = (Map<?, ?>) b.getValue();
            if(biomeMap == null) continue;

            ConcurrentSkipListMap<Long,Long> locations = new ConcurrentSkipListMap<>();

            for(var e : biomeMap.entrySet()) {
                String key = String.valueOf(e.getKey());
                String val = String.valueOf(e.getValue());

                try {
                    Long k = Long.parseLong(key);
                    Long v = Long.parseLong(val);

                    locations.put(k,v);
                }
                catch (NumberFormatException exception) {
                    exception.printStackTrace();
                    return;
                }
            }
            this.biomeLocations.put(biome,locations);
        }
    }

    public void addBadLocation(Long location) {
        if(location<0) return;
        if(isKnownBad(location)) return;

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

        badLocationSum.incrementAndGet();
    }

    public void addBiomeLocation(Long location, String biome) {
        biomeLocations.putIfAbsent(biome, new ConcurrentSkipListMap<>());
        ConcurrentSkipListMap<Long, Long> map = biomeLocations.get(biome);

        Map.Entry<Long,Long> lower = map.floorEntry(location);
        Map.Entry<Long,Long> upper = map.ceilingEntry(location);

        //goal: merge adjacent values
        // if within bounds of lower entry, do nothing
        // if lower start+length meets position, add 1 to its length and use that
        if((lower!=null) && (location < lower.getKey()+lower.getValue())) {
            return;
        }
        else if((lower!=null) && (location == lower.getKey()+lower.getValue())) {
            map.put(lower.getKey(),lower.getValue()+1);
        }
        else {
            map.put(location, 1L);
        }

        lower = map.floorEntry(location);

        // if upper start meets position + length, merge its length and delete upper entry
        if((upper!=null)&&(lower.getKey()+lower.getValue() >= upper.getKey())) {
            map.put(lower.getKey(),lower.getValue()+upper.getValue());
            map.remove(upper.getKey());
        }
    }

    public void removeBiomeLocation(Long location, String biome) {
        biomeLocations.putIfAbsent(biome, new ConcurrentSkipListMap<>());
        ConcurrentSkipListMap<Long, Long> map = biomeLocations.get(biome);

        Map.Entry<Long,Long> lower = map.floorEntry(location);
        if(lower!=null) {
            long key = lower.getKey();
            long val = lower.getValue();
            if (location < key + val){ //if within bounds, slice the bounds to remove the location
                map.remove(lower.getKey());
                if (location > key) {
                    map.put(key, location - key);
                }
                if (location+1 < key + val) {
                    map.put(location+1, (key + val) - (location+1));
                }
            }
        }
    }
}