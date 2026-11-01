package io.github.dailystruggle.rtp.neoforge.effects.local;

import net.minecraft.core.Registry;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Fully-reflective registry / resource-id resolver for the NeoForge effect
 * layer.
 *
 * <p><b>Why fully reflective.</b> The resource-identifier class
 * {@code net.minecraft.resources.ResourceLocation} was renamed to
 * {@code Identifier} in the MC 1.21.11 mappings (see
 * {@code NeoForgeResourceIds}). Any bytecode that names {@code ResourceLocation}
 * (or {@code Identifier}) as a parameter / field type fails on the other
 * runtime with {@code NoClassDefFoundError}. This helper therefore never names
 * the id class at compile time: the id class is discovered at runtime, the id
 * object is parsed via its static {@code tryParse(String)}, and the registry
 * getter ({@code getValue}/{@code get}) is resolved by signature among the
 * registry's public methods.</p>
 *
 * <p>{@link Registry} and {@code net.minecraft.core.registries.BuiltInRegistries}
 * keep their Mojmap names across the 1.21.x line, so the caller may pass the
 * typed registry instances directly; only the id type is volatile.</p>
 *
 * <p>S-005: pure in-memory registry lookups, no chunk I/O. Fail-soft: every
 * accessor returns {@code null} on any error rather than propagating a linkage
 * failure into the cosmetic effect path.</p>
 */
public final class NeoForgeRegistryResolver {

    private NeoForgeRegistryResolver() {}

    private static volatile Class<?> ID_CLASS;
    private static volatile boolean ID_CLASS_RESOLVED;
    private static volatile Method ID_TRY_PARSE;

    private enum Shape { GET_VALUE, OPTIONAL_HOLDER }

    private record Resolved(Shape shape, Method method) {}

    private static final ConcurrentMap<Class<?>, Resolved> REGISTRY_CACHE = new ConcurrentHashMap<>();

    /** Resolve the runtime identifier class (ResourceLocation pre-1.21.11, Identifier on 1.21.11+). */
    private static Class<?> idClass() {
        if (ID_CLASS_RESOLVED) return ID_CLASS;
        synchronized (NeoForgeRegistryResolver.class) {
            if (ID_CLASS_RESOLVED) return ID_CLASS;
            Class<?> cls = null;
            for (String name : new String[]{
                    "net.minecraft.resources.ResourceLocation",
                    "net.minecraft.resources.Identifier"}) {
                try {
                    cls = Class.forName(name);
                    break;
                } catch (Throwable ignored) {
                    // try the next candidate name
                }
            }
            if (cls != null) {
                try {
                    ID_TRY_PARSE = cls.getMethod("tryParse", String.class);
                } catch (Throwable ignored) {
                    ID_TRY_PARSE = null;
                }
            }
            ID_CLASS = cls;
            ID_CLASS_RESOLVED = true;
            return cls;
        }
    }

    /** Parse {@code raw} into an opaque resource-id object, or {@code null}. */
    private static Object parseId(String raw) {
        if (raw == null) return null;
        idClass();
        Method tryParse = ID_TRY_PARSE;
        if (tryParse == null) return null;
        try {
            Object id = tryParse.invoke(null, raw);
            if (id != null) return id;
        } catch (Throwable ignored) {
            // fall through to the namespaced retry
        }
        try {
            return tryParse.invoke(null, "minecraft:" + raw.toLowerCase());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Resolve a registry value by token, applying the legacy
     * Bukkit-token normalisations (lowercase, {@code _}->{@code .} in the path).
     * Returns the resolved value cast to {@code T}, or {@code null}.
     */
    public static <T> T resolve(Registry<T> registry, String raw) {
        if (registry == null || raw == null) return null;
        T v = resolveExact(registry, raw);
        if (v != null) return v;
        String lower = raw.toLowerCase();
        if (!lower.equals(raw)) {
            v = resolveExact(registry, lower);
            if (v != null) return v;
        }
        String dotted = legacyEnumToPath(lower);
        if (dotted != null && !dotted.equals(lower) && !dotted.equals(raw)) {
            v = resolveExact(registry, dotted);
            if (v != null) return v;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T resolveExact(Registry<T> registry, String raw) {
        Object id = parseId(raw);
        if (id == null) return null;
        Resolved r = REGISTRY_CACHE.computeIfAbsent(registry.getClass(), NeoForgeRegistryResolver::probe);
        if (r == null) return null;
        try {
            Object out = r.method.invoke(registry, id);
            if (out == null) return null;
            if (r.shape == Shape.OPTIONAL_HOLDER) {
                if (out instanceof Optional<?> o && o.isPresent()) {
                    Object holder = o.get();
                    try {
                        return (T) holder.getClass().getMethod("value").invoke(holder);
                    } catch (Throwable t) {
                        return null;
                    }
                }
                return null;
            }
            return (T) out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Probe the registry class for a single-arg id getter. Prefers the direct
     * value getter ({@code (id)->T}) and falls back to the
     * {@code (id)->Optional<Holder.Reference<T>>} shape (MC 1.21.2+). Resolved
     * by signature, not by name, so it binds regardless of mapping drift.
     */
    private static Resolved probe(Class<?> registryClass) {
        Class<?> idCls = idClass();
        if (idCls == null) return null;
        Method legacy = null;
        Method opt = null;
        for (Method m : registryClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || p[0] != idCls) continue;
            Class<?> ret = m.getReturnType();
            if (ret == void.class || ret.isPrimitive()) continue;
            if (ret == Optional.class) {
                if (opt == null) opt = m;
            } else if (legacy == null) {
                legacy = m;
            }
        }
        if (legacy != null) return new Resolved(Shape.GET_VALUE, legacy);
        if (opt != null) return new Resolved(Shape.OPTIONAL_HOLDER, opt);
        return null;
    }

    private static String legacyEnumToPath(String lower) {
        if (lower == null || lower.indexOf('_') < 0) return lower;
        int colon = lower.indexOf(':');
        if (colon < 0) return lower.replace('_', '.');
        return lower.substring(0, colon + 1) + lower.substring(colon + 1).replace('_', '.');
    }
}
