package io.github.dailystruggle.commandsapi.common;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class Factory {
    private final ConcurrentHashMap<String,Class<?>> map = new ConcurrentHashMap<>();

    public void add(String name, Class<?> parameterType) {
        map.put(name.toUpperCase(),parameterType);
    }

    public Enumeration<String> list() {
        return map.keys();
    }

    public boolean contains(String name) {
        return map.containsKey(name.toUpperCase());
    }

    @Nullable
    public Object construct(String name, Object... parameters) {
        Object res;
        Class<?>[] parameterTypes = new Class<?>[parameters.length];
        for(int i = 0; i < parameters.length; i++) {
            parameterTypes[i] = parameters[i].getClass();
        }
        try {
            res = map.get(name.toUpperCase()).getConstructor(parameterTypes).newInstance(parameters);
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException e) {
            e.printStackTrace();
            return null;
        }
        return res;
    }

    @Nullable
    public Class<?>[] getConstructorParameterTypes(String name) {
        Class<?> type = map.get(name);
        if(type == null) return null;
        Constructor<?>[] constructors = type.getConstructors();
        Optional<Constructor<?>> longestConstructorOptional = Arrays.stream(constructors).max(Comparator.comparingInt(Constructor::getParameterCount));
        if(!longestConstructorOptional.isPresent()) return null;
        Constructor<?> longestConstructor = longestConstructorOptional.get();
        return longestConstructor.getParameterTypes();
    }
}
