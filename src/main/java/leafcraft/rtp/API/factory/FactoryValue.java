package leafcraft.rtp.api.factory;

import com.google.common.base.Function;
import leafcraft.rtp.api.configuration.RTPConfigurable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * this exists solely because java is so stubborn about constructors and generics.
 * rather than calling a constructor, the factory value will be copied from a value in the factory
 * @param <E> enum of available parameters
 */
public abstract class FactoryValue<E extends Enum<E>> implements Cloneable, RTPConfigurable {
    //hacky way just to do Enum.valueOf on the correct enum
    protected final Class<E> myClass;

    /**
     * data - container for arbitrary data values
     *      mapped enum value to object to stay organized
     */
    protected EnumMap<E,Object> data;

    protected FactoryValue(Class<E> myClass) {
        this.myClass = myClass;
        data = new EnumMap<>(myClass);
    }

    /**
     * generic getter
     * @return copy of data, to prevent editing
     */
    public EnumMap<E,Object> getData() {
        return data.clone();
    }

    /**
     * @param data - data to apply.
     */
    public void setData(final EnumMap<? extends Enum<?>,?> data) throws IllegalArgumentException {
        data.forEach((key, value) -> {
            if (!myClass.isAssignableFrom(key.getClass())) {
                throw new IllegalArgumentException("invalid assignment"
                        + "\nexpected:" + myClass.getSimpleName()
                        + "\nreceived:" + key.getClass().getSimpleName()
                );
            }
            this.data.put((E) key, value);
        });
    }

    /**
     * todo: guard value types??
     * @param data - data to apply. key is case-sensitive
     */
    public void setData(final Map<String,Object> data) throws IllegalArgumentException {
        data.forEach((key1, value) -> {
            E key = Enum.valueOf(myClass, key1);
            this.data.put(key, value);
        });
    }

    @Override
    public FactoryValue<E> clone() {
        try {
            FactoryValue<E> clone = (FactoryValue<E>) super.clone();
            clone.setData(getData());
            return clone;
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static final Map<Class<? extends Number>, Function<String, Number>> numberParsers = new HashMap<>();
    static {
        numberParsers.put(Double.class,Double::parseDouble);
        numberParsers.put(Float.class,Float::parseFloat);
        numberParsers.put(Long.class,Long::parseLong);
        numberParsers.put(Integer.class,Integer::parseInt);
        numberParsers.put(Short.class,Short::parseShort);
        numberParsers.put(Byte.class,Byte::parseByte);
    }

    protected Number getNumber(E key, Number def) throws NumberFormatException {
        Number res = def;

        Object resObj = data.getOrDefault(key,def);
        if(resObj instanceof Number) {
            res = (Number) resObj;
        }
        else if(resObj instanceof String) {
            res = numberParsers.get(def.getClass()).apply((String) resObj);
        } else if(resObj instanceof Character) {
            res = Integer.parseInt(((Character) resObj).toString());
        }
        data.put(key,res);
        return res;
    }

    private Set<String> keys = null;
    @Override
    public Collection<String> keys() {
        if(keys == null) keys = Arrays.stream(myClass.getEnumConstants()).map(Enum::name).collect(Collectors.toSet());
        return keys;
    }
}
