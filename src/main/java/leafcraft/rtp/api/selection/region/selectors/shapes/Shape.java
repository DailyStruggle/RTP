package leafcraft.rtp.api.selection.region.selectors.shapes;

import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.factory.Factory;
import leafcraft.rtp.api.factory.FactoryValue;
import leafcraft.rtp.api.substitutions.RTPChunk;
import leafcraft.rtp.api.substitutions.RTPLocation;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiPredicate;


public abstract class Shape<E extends Enum<E>> extends FactoryValue<E> {
    public final String name;

    protected final List<BiPredicate<UUID,RTPLocation>> verifiers = new ArrayList<>();

    /**
     * @param name - unique name of shape
     */
    public Shape(Class<E> eClass, String name, EnumMap<E,Object> data) {
        super(eClass);
        this.name = name;
        this.data = data;
        for (E val : myClass.getEnumConstants()) {
            if(!data.containsKey(val)) throw new IllegalArgumentException(
                    "All values must be filled out on shape instantiation");
        }
        Factory<Shape<?>> factory = (Factory<Shape<?>>) RTPAPI.getInstance().factoryMap.get(RTPAPI.factoryNames.shape);
        if (!factory.contains(name)) factory.add(name,this);
    }

    @Override
    public EnumMap<E, Object> getData() {
        return data.clone();
    }

    public abstract RTPChunk select(@Nullable Set<String> biomes); //todo
}
