package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPBlock;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Predicate;

public abstract class VerticalAdjustor<E extends Enum<E>> extends FactoryValue<E> {
    protected final List<Predicate<RTPBlock>> verifiers;

    public String name;

    protected VerticalAdjustor(Class<E> eClass, String name, List<Predicate<RTPBlock>> verifiers, EnumMap<E,Object> def) {
        super(eClass, name);
        this.verifiers = verifiers;
        setData(def);
        Factory<VerticalAdjustor<?>> vertAdjustorFactory = (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
        this.name = name;
        if(!vertAdjustorFactory.contains(name))
            vertAdjustorFactory.add(name,this);
        try {
            loadLangFile("vert");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public abstract @Nullable
    RTPLocation adjust(@NotNull RTPChunk input);
    public abstract boolean testPlacement(@NotNull RTPBlock location);

    public abstract int minY();
    public abstract int maxY();
}
