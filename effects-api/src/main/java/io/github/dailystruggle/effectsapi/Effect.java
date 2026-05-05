package io.github.dailystruggle.effectsapi;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
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
                    if (str.contains(String.valueOf(CommandsAPI.parameterDelimiterAlt)))
                        str = str.substring(str.indexOf(CommandsAPI.parameterDelimiterAlt) + 1);
                    else if (str.contains(String.valueOf(CommandsAPI.parameterDelimiter)))
                        str = str.substring(str.indexOf(CommandsAPI.parameterDelimiter) + 1);
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
        final String effectName = this.getClass().getSimpleName();
        final String keyName = (key == null) ? "<null>" : key.name();
        final String expected = (def == null) ? "<unknown>" : def.getClass().getSimpleName();
        if(def instanceof String) o = string;
        else if(def instanceof Boolean) {
            o = Boolean.parseBoolean(string);
        }
        else if(def instanceof Long || def instanceof Integer) {
            try {
                o = Integer.parseInt(string);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException(
                        "[" + effectName + "] field '" + keyName + "' expects " + expected
                        + " but got \"" + string + "\". Check permission node argument order.", nfe);
            }
        }
        else if(def instanceof Double || def instanceof Float) {
            try {
                o = Float.parseFloat(string) / 100;
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException(
                        "[" + effectName + "] field '" + keyName + "' expects " + expected
                        + " but got \"" + string + "\". Check permission node argument order.", nfe);
            }
        }
        else if(def instanceof Color) {
            // Accept either named Bukkit Color constants (BLUE, WHITE, RED, ...) or a hex RRGGBB string.
            // Permission tokens like FIREWORK.BALL.1.1.BLUE.WHITE.true.true.true.0.0.0 carry the named form.
            Color named = resolveNamedColor(string);
            if (named != null) {
                o = named;
            } else {
                try {
                    o = Color.fromRGB(Integer.parseInt(string, 16));
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException(
                            "[" + effectName + "] field '" + keyName + "' expects a Color"
                            + " (named constant like BLUE/WHITE or 6-digit hex RRGGBB)"
                            + " but got \"" + string + "\". Check permission node argument order.", nfe);
                }
            }
        }
        else if(def instanceof Sound || (def != null && def.getClass().getName().equals("org.bukkit.Sound"))) {
            // On MC 1.21.3+, org.bukkit.Sound became a registry-backed interface
            // (no enum valueOf). Resolve via Registry.SOUNDS using the namespaced
            // key. Accept both legacy underscored names ("ENTITY_ENDERMAN_TELEPORT")
            // and registry-style keys ("entity.enderman.teleport") by mapping
            // underscores to dots and lowercasing.
            o = resolveSound(string);
            if (o == null) throw new IllegalArgumentException("unknown sound - " + string);
        }
        else {
            // Try in order:
            //   1. enum-style valueOf(String)        — pre-registry types (1.20.1)
            //   2. getByName(String)                  — legacy lookups
            //   3. Registry.<X>.get(NamespacedKey)    — registry-backed types
            //                                            (1.21.3+ Sound, expected
            //                                             on 26.1 for Particle /
            //                                             Biome / EntityType)
            //   4. throw with effect/field/expected diagnostic
            try {
                o = def.getClass().getMethod("valueOf", String.class).invoke(null, string);
            } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e1) {
                try {
                    o = def.getClass().getMethod("getByName", String.class).invoke(null, string);
                } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e2) {
                    Object viaRegistry = resolveViaRegistry(def.getClass(), string);
                    if (viaRegistry != null) {
                        o = viaRegistry;
                    } else {
                        throw new IllegalArgumentException(
                                "[" + effectName + "] field '" + keyName + "' expects "
                                + expected + " but got \"" + string
                                + "\". Not an enum constant, no getByName(String), and not"
                                + " present in Registry. Check permission node argument order.");
                    }
                }
            }
        }
        return o;
    }

    /**
     * Resolve a Bukkit value from a string by walking the {@link Registry}
     * surface reflectively. Used as a fallback when {@code valueOf} /
     * {@code getByName} both fail — covers the 1.21.3+ {@link Sound} migration
     * and the expected 26.1 migration of {@code Particle}/{@code EntityType}/
     * {@code Biome} from enums to registry-backed interfaces. Token may be:
     * <ul>
     *   <li>Legacy underscored: {@code ENTITY_ENDERMAN_TELEPORT}</li>
     *   <li>Namespaced key: {@code minecraft:entity.enderman.teleport}</li>
     *   <li>Bare path: {@code entity.enderman.teleport}</li>
     * </ul>
     * Strategy: scan {@link Registry}'s public static fields, pick the one
     * whose declared parameter type is assignable to {@code targetType}, and
     * call {@code get(NamespacedKey)} on it. Returns {@code null} when no
     * registry matches or the key is absent.
     */
    private static Object resolveViaRegistry(Class<?> targetType, String token) {
        if (token == null || token.isEmpty() || targetType == null) return null;
        String raw = token.trim();
        NamespacedKey key;
        if (raw.contains(":")) {
            key = NamespacedKey.fromString(raw.toLowerCase());
        } else {
            key = NamespacedKey.minecraft(raw.toLowerCase().replace('_', '.'));
        }
        if (key == null) return null;
        for (java.lang.reflect.Field f : Registry.class.getFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (!Registry.class.isAssignableFrom(f.getType())) continue;
            // Inspect the parameterized type to check it carries targetType.
            java.lang.reflect.Type generic = f.getGenericType();
            if (!(generic instanceof ParameterizedType)) continue;
            java.lang.reflect.Type[] args = ((ParameterizedType) generic).getActualTypeArguments();
            if (args.length == 0 || !(args[0] instanceof Class<?>)) continue;
            if (!targetType.isAssignableFrom((Class<?>) args[0])) continue;
            try {
                Registry<?> registry = (Registry<?>) f.get(null);
                if (registry == null) continue;
                Object v = registry.get(key);
                if (v != null) return v;
            } catch (Throwable ignored) {
                // try next registry
            }
        }
        return null;
    }

    /**
     * Resolve an {@link org.bukkit.Sound} from a string token in a way that
     * works on both legacy (enum) and modern (registry-backed interface)
     * Bukkit/Paper/Folia. Accepts:
     * <ul>
     *   <li>Legacy underscored names: {@code ENTITY_ENDERMAN_TELEPORT}</li>
     *   <li>Namespaced keys: {@code minecraft:entity.enderman.teleport}</li>
     *   <li>Bare keys: {@code entity.enderman.teleport}</li>
     * </ul>
     * Returns {@code null} when the token cannot be mapped.
     */
    /**
     * Resolve a named Bukkit {@link Color} constant (e.g. {@code BLUE}, {@code WHITE},
     * {@code RED}) by reflecting on {@link Color}'s {@code public static final Color}
     * fields. Returns {@code null} when {@code token} does not match any named
     * constant, allowing callers to fall back to hex parsing. Token matching is
     * case-insensitive.
     */
    private static Color resolveNamedColor(String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            java.lang.reflect.Field f = Color.class.getField(token.toUpperCase());
            int mods = f.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(mods)
                    && java.lang.reflect.Modifier.isPublic(mods)
                    && Color.class.isAssignableFrom(f.getType())) {
                Object val = f.get(null);
                if (val instanceof Color) return (Color) val;
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // not a named constant
        }
        return null;
    }

    private static Object resolveSound(String token) {
        if (token == null) return null;
        // First try legacy enum-style valueOf via reflection (works pre-1.21.3
        // when Sound is still an enum).
        try {
            return Sound.class.getMethod("valueOf", String.class)
                    .invoke(null, token.toUpperCase());
        } catch (Throwable ignored) {
            // fall through to registry lookup
        }
        // Build a NamespacedKey: "ENTITY_ENDERMAN_TELEPORT" -> "entity.enderman.teleport"
        String raw = token.trim();
        NamespacedKey key;
        if (raw.contains(":")) {
            key = NamespacedKey.fromString(raw.toLowerCase());
        } else {
            String keyPath = raw.toLowerCase().replace('_', '.');
            key = NamespacedKey.minecraft(keyPath);
        }
        if (key == null) return null;
        try {
            return Registry.SOUNDS.get(key);
        } catch (Throwable t) {
            return null;
        }
    }
}
