package leafcraft.rtp.api.factory;

import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On request, find a stored object with the correct name, clone it, and return it
 * @param <T> type of values this factory will hold
 */
public class Factory<T extends FactoryValue<?>> {
    private final ConcurrentHashMap<String,T> map = new ConcurrentHashMap<>();

    public void add(String name, T value) {
        map.put(name.toUpperCase(), value);
    }

    public Enumeration<String> list() {
        return map.keys();
    }

    public boolean contains(String name) {
        return map.containsKey(name.toUpperCase());
    }

    @Nullable
    public FactoryValue<?> construct(String name, EnumMap<? extends Enum<?>,Object> data) {
        //guard constructor
        T value = map.get(name);
        if(value == null) return null;
        FactoryValue<?> res = value.clone();
        res.setData(data);
        return res;
    }

    @Nullable
    public FactoryValue<?> construct(String name) {
        //guard constructor
        T value = map.get(name);
        if(value == null) return null;
        return value.clone();
    }
}
