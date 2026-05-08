package io.github.dailystruggle.effectsapi.common;

import io.github.dailystruggle.effectsapi.common.spi.TypeKey;
import io.github.dailystruggle.effectsapi.common.spi.ValueCoercer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Platform-neutral effect base. Per <em>effects-api-ADR-004</em>, all leaf
 * type-coercion previously hard-coded against {@code org.bukkit.*} types
 * (Color/Sound/Particle/PotionEffectType) now delegates to a bound
 * {@link ValueCoercer} via {@link EffectFactory#getCoercer()}.
 *
 * <p>Per user direction (2026-05-07): {@code Effect} now {@code implements
 * Runnable} rather than {@code extends BukkitRunnable}. Cancel semantics are
 * not used by any caller (verified by audit), so the prior {@code
 * BukkitRunnable} parent only contributed a {@code runTask(Plugin)}
 * convenience that callers now obtain via {@code Bukkit.getScheduler()
 * .runTask(plugin, effect)} on the Bukkit side, or
 * {@link io.github.dailystruggle.effectsapi.common.spi.EffectRuntime#schedule}
 * on the Fabric side. This makes {@code effectsapi.common.Effect} loadable on
 * Fabric servers (no {@code org.bukkit.*} on the classpath there).
 */
public abstract class Effect<T extends Enum<T>> implements Runnable, Cloneable {
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
     * compatible with {@code def}'s runtime type? Delegates to the bound
     * {@link ValueCoercer} via {@link EffectFactory#getCoercer()}.
     * Performs no Bukkit world load / chunk I/O (S-005).
     */
    protected final boolean canParse(Object def, String token) {
        if (token == null) return false;
        ValueCoercer coercer = EffectFactory.getCoercer();
        TypeKey type = coercer.classify(def);
        if (type == TypeKey.UNKNOWN) {
            // Reflective fallback for unknown default-types (legacy behaviour).
            if (def == null) return true; // permissive, matches legacy
            return coercer.resolveReflective(def.getClass(), token) != null;
        }
        return coercer.canParse(type, token);
    }

    //get parameters. Make sure to use setData to make changes
    public void setTarget(Object target) throws IllegalArgumentException {
        if (target == null) {
            throw new IllegalArgumentException("target must be an entity or location");
        }
        // Class-name match keeps effectsapi.common free of org.bukkit.* imports
        // while still validating that callers hand us one of the two legitimate
        // shapes. A Fabric-side effect would pass the equivalent Fabric types
        // (or a wrapper) — concrete subclasses do the platform-typed cast.
        String fqn = target.getClass().getName();
        if (!isAcceptedTargetClass(target.getClass())) {
            throw new IllegalArgumentException(
                    "target must be an entity or location (got " + fqn + ")");
        }
        this.target = target;
    }

    private static boolean isAcceptedTargetClass(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            String n = c.getName();
            if (n.equals("org.bukkit.Location")) return true;
            if (n.equals("org.bukkit.entity.Entity")) return true;
            // Fabric (Mojmap): ServerPlayer extends Player extends LivingEntity
            // extends Entity. Vec3 is the location-equivalent. We match by FQN
            // so effectsapi.common stays free of net.minecraft.* imports.
            if (n.equals("net.minecraft.world.entity.Entity")) return true;
            if (n.equals("net.minecraft.world.phys.Vec3")) return true;
            // Fabric (intermediary mappings, used at runtime in production):
            // class_1297 = Entity, class_243 = Vec3. Match by FQN so
            // effectsapi.common stays free of net.minecraft.* imports.
            if (n.equals("net.minecraft.class_1297")) return true;
            if (n.equals("net.minecraft.class_243")) return true;
            for (Class<?> i : c.getInterfaces()) {
                String in = i.getName();
                if (in.equals("org.bukkit.entity.Entity")) return true;
                if (in.equals("org.bukkit.Location")) return true;
            }
        }
        return false;
    }

    public EnumMap<T, Object> fixData(EnumMap<T, Object> data) {
        ValueCoercer coercer = EffectFactory.getCoercer();
        for (Map.Entry<T, Object> entry : defaults.entrySet()) {
            data.putIfAbsent(entry.getKey(), entry.getValue());
            Object defVal = entry.getValue();
            Object val = data.get(entry.getKey());
            Object res = defVal;
            // A null default carries no type information; keep whatever is in
            // data (which may itself be null) rather than NPE on getClass().
            if (defVal == null || val == null) {
                data.put(entry.getKey(), res);
                continue;
            }
            Class<?> type = defVal.getClass();
            if (!(type.isAssignableFrom(val.getClass()))) {
                if (val instanceof String) {
                    try {
                        res = str2Obj(entry.getKey(), (String) val);
                    } catch (IllegalArgumentException exception) {
                        // S-004: surface via the default warn sink rather than
                        // printing a stack trace (AGENTS.md: zero printStackTrace).
                        defaultWarn.accept("[" + getClass().getSimpleName()
                                + "] fixData: " + exception.getMessage());
                    }
                } else {
                    // Not a String — try the platform's reflective fallback
                    // (valueOf / getByName / Registry on the default's class).
                    Object reflective = coercer.resolveReflective(type, val.toString());
                    if (reflective != null) {
                        res = reflective;
                    }
                    // else: keep the default; legacy behaviour was to log+continue.
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
            // Best-effort deep-copy of a cloneable target without binding to a
            // platform-specific type; concrete effects can override clone() if
            // they need a richer copy semantics.
            if (target instanceof Cloneable) {
                try {
                    clone.target = target.getClass().getMethod("clone").invoke(target);
                } catch (Throwable ignored) {
                    clone.target = target;
                }
            }
            for (Map.Entry<T, Object> entry : data.entrySet()) {
                Object o = entry.getValue();
                if (o instanceof Cloneable) {
                    Object copy;
                    try {
                        copy = o.getClass().getMethod("clone", o.getClass()).invoke(o, (Object) null);
                    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                        // S-004: surface via the default warn sink rather than
                        // printing a stack trace (AGENTS.md: zero printStackTrace).
                        defaultWarn.accept("[" + getClass().getSimpleName()
                                + "] clone: " + e);
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
        if (string == null) return defaults.get(key);
        Object def = defaults.get(key);
        ValueCoercer coercer = EffectFactory.getCoercer();
        TypeKey type = coercer.classify(def);
        final String effectName = this.getClass().getSimpleName();
        final String keyName = (key == null) ? "<null>" : key.name();
        final String expected = (def == null) ? "<unknown>" : def.getClass().getSimpleName();

        if (type == TypeKey.UNKNOWN) {
            // Reflective fallback (default's class isn't a known logical type).
            Object reflective = (def == null) ? null : coercer.resolveReflective(def.getClass(), string);
            if (reflective != null) return reflective;
            throw new IllegalArgumentException(
                    "[" + effectName + "] field '" + keyName + "' expects "
                            + expected + " but got \"" + string
                            + "\". Not an enum constant, no getByName(String), and not"
                            + " present in Registry. Check permission node argument order.");
        }
        try {
            return coercer.parse(type, string);
        } catch (IllegalArgumentException nfe) {
            // Re-wrap with the legacy diagnostic shape so error messages are
            // stable for addons that grep them.
            throw new IllegalArgumentException(
                    "[" + effectName + "] field '" + keyName + "' expects " + expected
                            + " but got \"" + string + "\". Check permission node argument order.",
                    nfe);
        }
    }
}
