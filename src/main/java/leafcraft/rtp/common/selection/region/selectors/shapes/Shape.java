package leafcraft.rtp.common.selection.region.selectors.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import leafcraft.rtp.common.RTP;
import leafcraft.rtp.common.factory.Factory;
import leafcraft.rtp.common.factory.FactoryValue;
import leafcraft.rtp.common.substitutions.RTPChunk;
import leafcraft.rtp.common.substitutions.RTPLocation;
import leafcraft.rtp.common.substitutions.RTPWorld;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiPredicate;


public abstract class Shape<E extends Enum<E>> extends FactoryValue<E> {
    public final String name;

    protected final List<BiPredicate<UUID,RTPLocation>> verifiers = new ArrayList<>();

    /**
     * @param name - unique name of shape
     */
    public Shape(Class<E> eClass, String name, EnumMap<E,Object> data) throws IllegalArgumentException {
        super(eClass);
        this.name = name;
        this.data = data;
        for (E val : myClass.getEnumConstants()) {
            if(!data.containsKey(val)) throw new IllegalArgumentException(
                    "All values must be filled out on shape instantiation");
        }
        Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.getInstance().factoryMap.get(RTP.factoryNames.shape);
        if (!factory.contains(name)) factory.add(name,this);
    }

    @Override
    public @NotNull EnumMap<E, Object> getData() {
        return data.clone();
    }

    public abstract int[] select(); //todo

    public abstract long rand();

    public abstract Map<String, CommandParameter> getParameters();

    @Override
    public Shape<E> clone() {
        return (Shape<E>) super.clone();
    }
}
