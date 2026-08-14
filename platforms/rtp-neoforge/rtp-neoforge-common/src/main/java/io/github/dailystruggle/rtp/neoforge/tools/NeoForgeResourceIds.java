package io.github.dailystruggle.rtp.neoforge.tools;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection helper that reads Minecraft resource identifiers without naming the
 * identifier class at compile time.
 *
 * <p><b>Why this exists.</b> The resource-identifier class
 * {@code net.minecraft.resources.ResourceLocation} was renamed (to
 * {@code Identifier}) in the MC 1.21.11 mappings. That rename changes the
 * return-type descriptor of {@code ResourceKey#location()},
 * {@code TagKey#location()} and {@code Registry#getKey(Object)}, so bytecode
 * compiled against the old return type fails at runtime on a 1.21.11 server with
 * {@link NoSuchMethodError}; iterating a {@code keySet()} typed as the old class
 * fails even harder with {@code NoClassDefFoundError} on the {@code checkcast}.</p>
 *
 * <p>Resolving these accessors reflectively by method name -- and treating the
 * returned id as an opaque {@link Object} on which {@code getNamespace()} /
 * {@code getPath()} / {@code toString()} are themselves invoked reflectively --
 * lets the single 1.21.x carrier serve the whole 1.21.x line (1.21.1 through
 * 1.21.11+) regardless of which name the runtime uses for the identifier class.
 * The identifier class keeps the same instance-method names across the rename;
 * only the <em>type reference</em> is version-volatile, so the type is never
 * named here.</p>
 *
 * <p>All accessors are fail-soft (return {@code null} on any error) so a call
 * site can fall back rather than propagate a linkage failure. Resolved
 * {@link Method} handles are cached by declaring-class name to keep the
 * per-block hot paths (material lookups) cheap.</p>
 */
public final class NeoForgeResourceIds {

    private NeoForgeResourceIds() {}

    private static final ConcurrentHashMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    /**
     * Candidate no-arg accessor names for the identifier carried by a
     * {@code ResourceKey} / {@code TagKey}. The method was named
     * {@code location()} through MC 1.21.8, renamed to {@code getValue()} in the
     * MC 1.21.11 mappings (alongside the {@code ResourceLocation} ->
     * {@code Identifier} type rename), and is named {@code identifier()} on the
     * year-based MC 26.x mappings. Trying all three lets the single carrier serve
     * the whole line regardless of which name the runtime uses.
     *
     * <p>Omitting {@code identifier()} previously made every dimension-id lookup
     * on a 26.x runtime return {@code null}, which collapsed the region-file
     * subpath resolution to the legacy overworld root and made already-generated
     * chunks under the unified {@code dimensions/<ns>/<path>/region/} layout look
     * ungenerated (prescan bailing to the full-load scan).</p>
     */
    private static final String[] LOCATION_ACCESSORS = { "location", "getValue", "identifier" };

    private static Method noArg(Class<?> owner, String name) {
        String key = owner.getName() + "#" + name;
        Method cached = METHOD_CACHE.get(key);
        if (cached != null) return cached;
        try {
            // Public method of a public class -> no setAccessible (which could
            // throw InaccessibleObjectException under the NeoForge module layer).
            Method m = owner.getMethod(name);
            METHOD_CACHE.put(key, m);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Invoke {@code location()} on a {@code ResourceKey} / {@code TagKey} and
     * return the raw id object, or {@code null}.
     */
    public static Object location(Object keyWithLocation) {
        if (keyWithLocation == null) return null;
        for (String candidate : LOCATION_ACCESSORS) {
            try {
                Method m = noArg(keyWithLocation.getClass(), candidate);
                if (m == null) continue;
                Object r = m.invoke(keyWithLocation);
                if (r != null) return r;
            } catch (Throwable ignored) {
                // try the next candidate name
            }
        }
        return null;
    }

    /** {@code key.location().toString()} ("namespace:path"), or {@code null}. */
    public static String locationString(Object keyWithLocation) {
        return idString(location(keyWithLocation));
    }

    /** {@code registry.getKey(value)} -&gt; raw id object, or {@code null}. */
    public static Object registryKey(Object registry, Object value) {
        if (registry == null) return null;
        try {
            String key = registry.getClass().getName() + "#getKey(Object)";
            Method m = METHOD_CACHE.computeIfAbsent(key, k -> {
                try {
                    return registry.getClass().getMethod("getKey", Object.class);
                } catch (Throwable t) {
                    return null;
                }
            });
            return m == null ? null : m.invoke(registry, value);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code registry.getKey(value).toString()}, or {@code null}. */
    public static String registryKeyString(Object registry, Object value) {
        return idString(registryKey(registry, value));
    }

    /** {@code id.toString()} ("namespace:path"), or {@code null}. */
    public static String idString(Object id) {
        return id == null ? null : id.toString();
    }

    /** {@code id.getNamespace()}, or {@code null}. */
    public static String namespace(Object id) {
        if (id == null) return null;
        try {
            Method m = noArg(id.getClass(), "getNamespace");
            Object r = (m == null) ? null : m.invoke(id);
            return r == null ? null : r.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code id.getPath()}, or {@code null}. */
    public static String path(Object id) {
        if (id == null) return null;
        try {
            Method m = noArg(id.getClass(), "getPath");
            Object r = (m == null) ? null : m.invoke(id);
            return r == null ? null : r.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
