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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

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

    /**
     * Default diagnostic sink for {@link #applyByType} when an effect doesn't
     * route through a caller-supplied {@link Consumer}. Mirrors the
     * {@code commands-api} pattern of injecting a {@code Consumer<String>}
     * message method instead of binding {@code effects-api} to {@code rtp-core}
     * logging. Tests / hosts can swap it out via
     * {@link #setDefaultWarn(Consumer)}.
     *
     * <p>Default routes to {@link System#err}, which is strictly an
     * improvement over the prior {@code printStackTrace()} behavior on
     * mistyped permission tokens (see AGENTS.md: "Zero
     * {@code printStackTrace()}").
     */
    private static volatile Consumer<String> defaultWarn = msg -> System.err.println("[effects-api] " + msg);

    /**
     * Replace the default diagnostic sink used by {@link #applyByType} when
     * an effect calls it without an explicit {@link Consumer}. Intended for
     * the host plugin to wire its own logger (e.g. {@code RTP.log(WARNING, …)}).
     */
    public static void setDefaultWarn(Consumer<String> warn) {
        defaultWarn = (warn != null) ? warn : msg -> {};
    }

    /**
     * Convenience: parse positional tokens against {@code keyOrder} using
     * the host-configured default warn sink. See
     * {@link #applyByType(Enum[], String[], Consumer)}.
     */
    protected final void applyByType(T[] keyOrder, String[] tokens) {
        applyByType(keyOrder, tokens, defaultWarn);
    }

    /**
     * Type-driven adaptive positional fill (effects-api-ADR-002).
     *
     * <p>Walks {@code keyOrder} with a non-rewinding cursor. For each input
     * {@code token}, advances the cursor to the first remaining key whose
     * default-type can parse the token ({@link #canParse(Object, String)}),
     * assigns it, and advances past that key. Tokens that no remaining key
     * accepts are reported once via {@code warn} (S-004 — never silently
     * dropped) and the cursor is not advanced. Keys that are never assigned
     * keep their constructor-set defaults.
     *
     * <p>Order of acceptance is preserved (left-to-right): the cursor never
     * rewinds, so {@link #toPermission()} round-trips remain deterministic.
     *
     * <p>This method is pure with respect to chunks/IO: {@link #canParse} only
     * performs in-memory map / enum / number parsing. S-005 compliant.
     *
     * @param keyOrder positional / declared key order (typically the enum
     *                 constants array of {@code T})
     * @param tokens   raw permission/config tokens (may be shorter than
     *                 {@code keyOrder})
     * @param warn     consumer invoked with a single human-readable diagnostic
     *                 line per unparsed token; may be {@code null} for a
     *                 no-op (caller is then responsible for surfacing
     *                 misconfiguration in some other way — discouraged)
     */
    protected final void applyByType(T[] keyOrder, String[] tokens, Consumer<String> warn) {
        if (keyOrder == null || keyOrder.length == 0) return;
        if (tokens == null || tokens.length == 0) {
            this.data = fixData(this.data);
            return;
        }
        int cursor = 0;
        List<String> unparsed = null;
        for (String token : tokens) {
            if (token == null) continue;
            int chosen = -1;
            for (int j = cursor; j < keyOrder.length; j++) {
                Object def = defaults.get(keyOrder[j]);
                if (canParse(def, token)) {
                    chosen = j;
                    break;
                }
            }
            if (chosen < 0) {
                if (unparsed == null) unparsed = new ArrayList<>();
                unparsed.add(token);
                continue;
            }
            this.data.put(keyOrder[chosen], token);
            cursor = chosen + 1;
            if (cursor >= keyOrder.length) {
                // remaining tokens cannot land anywhere — record them
                // as unparsed rather than silently dropping (S-004).
                continue;
            }
        }
        this.data = fixData(this.data);
        if (unparsed != null && warn != null) {
            warn.accept("[" + getClass().getSimpleName()
                    + "] ignored " + unparsed.size()
                    + " token(s) that matched no remaining key: " + unparsed);
        }
    }

    /**
     * Side-effect-free predicate: would {@code token} parse to a value
     * compatible with {@code def}'s runtime type? Mirrors the type ladder in
     * {@link #str2Obj} but never throws and performs no Bukkit world load /
     * chunk I/O (S-005).
     */
    protected final boolean canParse(Object def, String token) {
        if (token == null) return false;
        if (def == null) {
            // Unknown default type — be permissive so a token can land
            // somewhere rather than getting stuck on an empty slot.
            return true;
        }
        if (def instanceof String) return true;
        String upper = token.toUpperCase();
        if (def instanceof Boolean) {
            return upper.equals("TRUE") || upper.equals("FALSE");
        }
        if (def instanceof Long || def instanceof Integer) {
            try { Long.parseLong(token); return true; }
            catch (NumberFormatException nfe) { return false; }
        }
        if (def instanceof Double || def instanceof Float) {
            try {
                float f = Float.parseFloat(token);
                return !Float.isNaN(f) && !Float.isInfinite(f);
            } catch (NumberFormatException nfe) { return false; }
        }
        if (def instanceof Color) {
            if (resolveNamedColor(token) != null) return true;
            try { Integer.parseInt(token, 16); return true; }
            catch (NumberFormatException nfe) { return false; }
        }
        if (def instanceof Sound || def.getClass().getName().equals("org.bukkit.Sound")) {
            return resolveSound(token) != null;
        }
        // Generic enum / registry-backed: try valueOf, getByName, then registry.
        try {
            def.getClass().getMethod("valueOf", String.class).invoke(null, upper);
            return true;
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            // try next
        } catch (InvocationTargetException ite) {
            // valueOf threw — token didn't match
        }
        try {
            Object r = def.getClass().getMethod("getByName", String.class).invoke(null, token);
            if (r != null) return true;
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            // try next
        } catch (InvocationTargetException ite) {
            // getByName threw — token didn't match
        }
        return resolveViaRegistry(def.getClass(), token) != null;
    }

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
