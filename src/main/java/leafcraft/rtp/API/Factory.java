package leafcraft.rtp.API;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class Factory<T> {
    private final ConcurrentHashMap<String,Class<? extends T>> map = new ConcurrentHashMap<>();

    public void add(String name, Class<? extends T> parameterType) {
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

    public boolean verifyConstructorParameterTypes(String name, Object... parameters) {
        Class<?>[] constructorParameters = getConstructorParameterTypes(name);
        if(!(parameters.length == constructorParameters.length)) return false;
        for(int i = 0; i < parameters.length; i++) {
            if(!constructorParameters[i].isAssignableFrom(parameters.getClass()))
                return false;
        }
        return true;
    }

    @Nullable
    private Class<?>[] getConstructorParameterTypes(String name) {
        Class<?> type = map.get(name);
        if(type == null) return null;
        Constructor<?>[] constructors = type.getConstructors();
        Optional<Constructor<?>> longestConstructorOptional = Arrays.stream(constructors).max(Comparator.comparingInt(Constructor::getParameterCount));
        if(longestConstructorOptional.isEmpty()) return null;
        Constructor<?> longestConstructor = longestConstructorOptional.get();
        return longestConstructor.getParameterTypes();
    }
}
