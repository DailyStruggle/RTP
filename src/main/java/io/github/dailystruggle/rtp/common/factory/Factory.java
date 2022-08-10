package io.github.dailystruggle.rtp.common.factory;

import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Enumeration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On request, find a stored object with the correct name, clone it, and return it
 * @param <T> type of values this factory will hold
 */
public class Factory<T extends FactoryValue<?>> {
    public final ConcurrentHashMap<String,T> map = new ConcurrentHashMap<>();

    public void add(String name, T value) {
        map.put(name.toUpperCase(), value);
    }

    public Enumeration<String> list() {
        return map.keys();
    }

    public boolean contains(String name) {
        name = name.toUpperCase();
        if(!name.endsWith(".YML")) name = name + ".YML";
        return map.containsKey(name);
    }

    @Nullable
    public FactoryValue<?> construct(String name, EnumMap<? extends Enum<?>,Object> data) {
        name = name.toUpperCase();
        if(!name.endsWith(".YML")) name = name + ".YML";
        //guard constructor
        T value = map.get(name);
        if(value == null) {
            if(map.containsKey("default")) {
                value = (T) construct("default", data);
                Objects.requireNonNull(value);
                map.put(name, value);
            }
            else return null;
        }
        FactoryValue<?> res = value.clone();
        res.setData(data);
        return res;
    }

    /**
     * @param name name of item
     * @return mutable copy of item
     */
    @Nullable
    public FactoryValue<?> construct(String name) {
        name = name.toUpperCase();
        if(!name.endsWith(".YML")) name = name + ".YML";
        //guard constructor
        T value = map.get(name);
        if(value == null) {
            if(map.containsKey("DEFAULT.YML")) {
                value = map.get("DEFAULT.YML");
                T clone = (T) value.clone();
                clone.name=name;

                if(clone instanceof ConfigParser configParser) {
                    configParser.check(configParser.version,configParser.pluginDirectory,null);
                }

                map.put(name, clone);
            }
            else return null;
        }
        return value.clone();
    }

    @NotNull
    public FactoryValue<?> getOrDefault(String name) {
        name = name.toUpperCase();
        //guard constructor
        T value = map.get(name);
        if(value == null) {
            if(map.containsKey("default")) {
                value = (T) construct("default");
                map.put(name, value);
            }
            else return map.values().stream().toList().get(0);
        }
        return value;
    }
}
