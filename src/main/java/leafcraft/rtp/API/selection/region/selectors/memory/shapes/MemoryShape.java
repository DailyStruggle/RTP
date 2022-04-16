package leafcraft.rtp.api.selection.region.selectors.memory.shapes;

import leafcraft.rtp.api.selection.region.selectors.shapes.Shape;
import org.bukkit.block.Biome;

import java.util.EnumMap;
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
    public MemoryShape(Class<E> eClass, String name, EnumMap<E,Object> data) {
        super(eClass,name,data);
    }

    protected final ConcurrentSkipListMap<Long,Long> badLocations = new ConcurrentSkipListMap<>();
    protected final AtomicLong badLocationSum = new AtomicLong(0L);
    protected final ConcurrentHashMap<Biome, ConcurrentSkipListMap<Long,Long>> biomeLocations = new ConcurrentHashMap<>();

    public abstract double getRange(long radius);
    public abstract double xzToLocation(long x, long z);
    public abstract long[] locationToXZ(long location);

    public void save() {
        //use name and parameters to store data
    }

    public void load() {
        //use name and parameters to get data
    }
}
