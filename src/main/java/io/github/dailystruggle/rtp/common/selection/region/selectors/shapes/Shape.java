package io.github.dailystruggle.rtp.common.selection.region.selectors.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;
import java.util.function.BiPredicate;


public abstract class Shape<E extends Enum<E>> extends FactoryValue<E> {
    public final String name;

    protected final List<BiPredicate<UUID,RTPLocation>> verifiers = new ArrayList<>();

    /**
     * @param name - unique name of shape
     */
    public Shape(Class<E> eClass, String name, EnumMap<E,Object> data) throws IllegalArgumentException {
        super(eClass, name);
        this.name = name;
        this.data.putAll(data);
        for (E val : myClass.getEnumConstants()) {
            if(!data.containsKey(val)) throw new IllegalArgumentException(
                    "All values must be filled out on shape instantiation");
        }
        try {
            loadLangFile("shape");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int[] rotate(int[] input, long degrees) {
        double angle = Math.toRadians(degrees);

        double s = Math.sin(angle);
        double c = Math.cos(angle);

        int x = input[0];
        int z = input[1];

        // generate new point
        input[0] = (int) (x * c - z * s);
        input[1] = (int) (x * s + z * c);

        return input;
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
