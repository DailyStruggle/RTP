package leafcraft.rtp.common.selection.region.selectors.verticalAdjustors;

import leafcraft.rtp.common.RTP;
import leafcraft.rtp.common.factory.Factory;
import leafcraft.rtp.common.factory.FactoryValue;
import leafcraft.rtp.common.substitutions.RTPBlock;
import leafcraft.rtp.common.substitutions.RTPChunk;
import leafcraft.rtp.common.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.function.Predicate;

public abstract class VerticalAdjustor<E extends Enum<E>> extends FactoryValue<E> {
    protected final List<Predicate<RTPBlock>> verifiers;

    public String name;

    protected VerticalAdjustor(Class<E> eClass, String name, List<Predicate<RTPBlock>> verifiers, EnumMap<E,Object> def) {
        super(eClass);
        this.verifiers = verifiers;
        setData(def);
        Factory<VerticalAdjustor<?>> vertAdjustorFactory = (Factory<VerticalAdjustor<?>>) RTP.getInstance().factoryMap.get(RTP.factoryNames.vert);
        this.name = name;
        if(!vertAdjustorFactory.contains(name))
            vertAdjustorFactory.add(name,this);
    }

    public abstract @Nullable RTPLocation adjust(@NotNull RTPChunk input);
    public abstract boolean testPlacement(@NotNull RTPBlock location);

    public abstract int minY();
    public abstract int maxY();
}
