package leafcraft.rtp.api.selection.region;

import leafcraft.rtp.api.selection.SelectionAPI;
import leafcraft.rtp.api.selection.region.selectors.cache.ChunkSet;
import leafcraft.rtp.api.selection.region.selectors.memory.shapes.Square;
import leafcraft.rtp.api.selection.region.selectors.shapes.Shape;
import leafcraft.rtp.api.substitutions.RTPLocation;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class Region {
    public abstract String name();
    public Shape<?> shape = new Square();

    protected ConcurrentLinkedQueue<RTPLocation> locationQueue = new ConcurrentLinkedQueue<>();
    protected final ConcurrentHashMap<RTPLocation, ChunkSet> locAssChunks = new ConcurrentHashMap<>();
    protected ConcurrentLinkedQueue<UUID> playerQueue = new ConcurrentLinkedQueue<>();

    protected final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<RTPLocation>> perPlayerLocationQueue = new ConcurrentHashMap<>();


    public Region(String shapeName, Map<String,String> shapeParams) {
        if(!SelectionAPI.shapeFactory.contains(shapeName)) return;
        Shape<? extends Enum<?>> shape = (Shape<? extends Enum<?>>) SelectionAPI.shapeFactory.construct(shapeName);
        assert shape != null;
        EnumMap<? extends Enum<?>, Object> data = shape.getData();
        for(var entry : data.entrySet()) {
            String name = entry.getKey().name();
            if(shapeParams.containsKey(name)) {
                entry.setValue(shapeParams.get(name));
            }
        }
        shape.setData(data);
        this.shape = shape;
    }

    @Nullable
    public abstract RTPLocation getLocation(UUID sender, UUID player, @Nullable Set<String> biomes);

    @Nullable
    public abstract RTPLocation getLocation(@Nullable Set<String> biomes);

    public abstract void shutDown();
}