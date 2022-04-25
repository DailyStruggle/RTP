package leafcraft.rtp.api.selection.region;

import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.configuration.enums.RegionKeys;
import leafcraft.rtp.api.factory.Factory;
import leafcraft.rtp.api.factory.FactoryValue;
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

public abstract class Region extends FactoryValue<RegionKeys> {
    public String name;

    protected ConcurrentLinkedQueue<RTPLocation> locationQueue = new ConcurrentLinkedQueue<>();
    protected final ConcurrentHashMap<RTPLocation, ChunkSet> locAssChunks = new ConcurrentHashMap<>();
    protected ConcurrentLinkedQueue<UUID> playerQueue = new ConcurrentLinkedQueue<>();

    protected final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<RTPLocation>> perPlayerLocationQueue = new ConcurrentHashMap<>();

    public Region(String name, EnumMap<RegionKeys,Object> params) {
        super(RegionKeys.class);
        this.name = name;
        setData(params);
    }

    @Nullable
    public abstract RTPLocation getLocation(UUID sender, UUID player, @Nullable Set<String> biomes);

    @Nullable
    public abstract RTPLocation getLocation(@Nullable Set<String> biomes);

    public abstract void shutDown();
}