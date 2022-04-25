package leafcraft.rtp.api.selection.region.selectors.verticalAdjustors;

import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.factory.Factory;
import leafcraft.rtp.api.factory.FactoryValue;
import leafcraft.rtp.api.selection.SelectionAPI;
import leafcraft.rtp.api.substitutions.RTPBlock;
import leafcraft.rtp.api.substitutions.RTPChunk;
import leafcraft.rtp.api.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.function.Predicate;

public abstract class VerticalAdjustor<E extends Enum<E>> extends FactoryValue<E> {
    protected final List<Predicate<RTPBlock>> verifiers;

    protected VerticalAdjustor(Class<E> eClass, String name, List<Predicate<RTPBlock>> verifiers, EnumMap<E,Object> def) {
        super(eClass);
        this.verifiers = verifiers;
        setData(def);
        Factory<VerticalAdjustor<?>> vertAdjustorFactory = (Factory<VerticalAdjustor<?>>) RTPAPI.getInstance().factoryMap.get(RTPAPI.factoryNames.vert);
        if(!vertAdjustorFactory.contains(name))
            vertAdjustorFactory.add(name,this);
    }

    public abstract @Nullable RTPLocation adjust(@NotNull RTPChunk input);
    public abstract boolean testPlacement(@NotNull RTPBlock location);
}
