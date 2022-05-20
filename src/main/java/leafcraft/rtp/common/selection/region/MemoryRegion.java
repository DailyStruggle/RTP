package leafcraft.rtp.common.selection.region;

import leafcraft.rtp.common.configuration.enums.RegionKeys;
import org.bukkit.block.Biome;

import java.util.EnumMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

public abstract class MemoryRegion extends Region{
    public MemoryRegion(String name, EnumMap<RegionKeys, Object> params) {
        super(name, params);
    }

    protected final ConcurrentSkipListMap<Long,Long> badLocations = new ConcurrentSkipListMap<>();
    protected final AtomicLong badLocationSum = new AtomicLong(0L);
    protected final ConcurrentHashMap<Biome, ConcurrentSkipListMap<Long,Long>> biomeLocations = new ConcurrentHashMap<>();

    public void save() {
        //todo
        //use name and parameters to store data
    }

    public void load() {
        //todo
        //use name and parameters to get data
    }
}
