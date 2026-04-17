package io.github.dailystruggle.effectsapi;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public abstract class Effect<T extends Enum<T>> extends BukkitRunnable implements Cloneable {
    public final Class<T> persistentClass;
    protected Object target;
    protected EnumMap<T, Object> data;
    protected EnumMap<T, Object> defaults;

    public Effect(EnumMap<T, Object> defaults) throws IllegalArgumentException {
        this.defaults = defaults.clone();
        this.data = defaults.clone();
        this.persistentClass = (Class<T>) ((ParameterizedType) getClass()
                .getGenericSuperclass()).getActualTypeArguments()[0];
    }

    //get parameters. Make sure to use setData to make changes
    public EnumMap<T, Object> getData() {
        return data.clone();
    }

    //apply parameters
    public void setData(EnumMap<T, Object> data) throws IllegalArgumentException {
        this.data = data.clone();
        this.data = fixData(this.data);
    }

    public abstract String toPermission();

    public abstract void setData(String... data);

    //get parameters. Make sure to use setData to make changes
    public void setTarget(Object target) throws IllegalArgumentException {
        if (!(target instanceof Location || target instanceof Entity)) {
            throw new IllegalArgumentException("target must be an entity or location");
        }
        this.target = target;
    }

    public EnumMap<T, Object> fixData(EnumMap<T, Object> data) {
        for (Map.Entry<T, Object> entry : defaults.entrySet()) {
            data.putIfAbsent(entry.getKey(), entry.getValue());
            Class<?> type = entry.getValue().getClass();
            Object val = data.get(entry.getKey());
            Object res = entry.getValue();
            if (!(type.isAssignableFrom(val.getClass()))) {
                if(val instanceof String) {
                    try {
                        res = str2Obj(entry.getKey(), (String) val);
                    } catch (IllegalArgumentException exception) {
                        exception.printStackTrace();
                    }
                }
                else if (res instanceof Color) {
                    String str = val.toString();
                    if (str.contains(String.valueOf(CommandsAPI.parameterDelimiter)))
                        str = str.substring(str.indexOf(CommandsAPI.parameterDelimiter));
                    res = Color.fromRGB(Integer.parseInt(str, 16));
                } else {
                    try {
                        res = type.getMethod("valueOf", val.getClass()).invoke(null, val);
                    } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e1) {
                        try {
                            res = type.getMethod("getByName", val.getClass()).invoke(null, val);
                        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e2) {
                            e2.printStackTrace();
                            continue;
                        }
                    }
                }
            }
            data.put(entry.getKey(), res);
        }
        return data;
    }

    @Override
    public Effect<T> clone() {
        try {
            Effect<T> clone = (Effect<T>) super.clone();
            clone.setData(data);
            if (target instanceof Location) {
                clone.target = ((Location) target).clone();
            }
            for (Map.Entry<T, Object> entry : data.entrySet()) {
                Object o = entry.getValue();
                if (o instanceof Cloneable) {
                    Object copy;
                    try {
                        copy = o.getClass().getMethod("clone", o.getClass()).invoke(o, (Object) null);
                    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                        e.printStackTrace();
                        continue;
                    }
                    clone.data.put(entry.getKey(), copy);
                }
            }
            // TODO: copy mutable state here, so the clone can't change the internals of the original
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }

    private Object str2Obj(T key, String string) throws IllegalArgumentException {
        Object o;
        Object def = defaults.get(key);
        if(string == null) return def;
        string = string.toUpperCase();
        if(def instanceof String) o = string;
        else if(def instanceof Boolean) {
            o = Boolean.parseBoolean(string);
        }
        else if(def instanceof Long || def instanceof Integer) {
            o = Integer.parseInt(string);
        }
        else if(def instanceof Double || def instanceof Float) {
            o = Float.parseFloat(string)/100;
        }
        else if(def instanceof Color) {
            o = Color.fromRGB(Integer.parseInt(string, 16));
        }
        else {
            try {
                o = def.getClass().getMethod("valueOf", String.class).invoke(null, string);
            } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e1) {
                try {
                    o = def.getClass().getMethod("getByName", String.class).invoke(null, string);
                } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e2) {
                    throw new IllegalArgumentException("unexpected input - " + string);
                }
            }
        }
        return o;
    }
}
