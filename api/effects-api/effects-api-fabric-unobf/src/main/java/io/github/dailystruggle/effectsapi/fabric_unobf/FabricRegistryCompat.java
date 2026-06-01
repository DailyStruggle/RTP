package io.github.dailystruggle.effectsapi.fabric_unobf;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cross-version shim for {@code Registry#get(Identifier)} drift.
 *
 * <p>MC 1.21.2 (Mojmap) renamed {@code Registry.get(Identifier)} to
 * {@code Registry.getValue(Identifier)} (returning {@code T}); the
 * old {@code get(Identifier)} now returns
 * {@code Optional<Holder.Reference<T>>}. A binary built against 1.21.1
 * therefore throws {@code NoSuchMethodError} on 1.21.2+ servers (yarn
 * mapping {@code class_2378.method_10223(class_2960)} → Object).
 *
 * <p>This helper resolves the right method once per {@link Registry}
 * subclass via {@link MethodHandles}, preferring {@code getValue} (new),
 * then the legacy {@code get} (T return), then unwrapping the
 * {@code Optional<Holder.Reference<T>>} shape if present.
 *
 * <p>S-005: pure registry lookup, no chunk I/O.
 */
public final class FabricRegistryCompat {

    private FabricRegistryCompat() {
    }

    private enum Shape { GET_VALUE, LEGACY_GET, OPTIONAL_HOLDER, NONE }

    private record Resolved(Shape shape, MethodHandle handle) { }

    private static final ConcurrentMap<Class<?>, Resolved> CACHE = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public static <T> T resolve(Registry<T> registry, Identifier key) {
        if (registry == null || key == null) return null;
        Resolved r = CACHE.computeIfAbsent(registry.getClass(), FabricRegistryCompat::probe);
        try {
            switch (r.shape) {
                case GET_VALUE:
                case LEGACY_GET:
                    return (T) r.handle.invoke(registry, key);
                case OPTIONAL_HOLDER: {
                    Object opt = r.handle.invoke(registry, key);
                    if (opt instanceof Optional<?> o && o.isPresent()) {
                        Object holder = o.get();
                        // Holder.Reference#value()
                        try {
                            return (T) holder.getClass().getMethod("value").invoke(holder);
                        } catch (Throwable t) {
                            System.err.println("[effects-api] [FabricRegistryCompat] "
                                    + "Holder.value() failed for key=" + key + " on "
                                    + registry.getClass().getName() + ": " + t);
                            return null;
                        }
                    }
                    return null;
                }
                case NONE:
                default:
                    return null;
            }
        } catch (Throwable t) {
            System.err.println("[effects-api] [FabricRegistryCompat] resolve("
                    + registry.getClass().getSimpleName() + ", " + key
                    + ") threw via shape=" + r.shape + ": " + t);
            return null;
        }
    }

    private static Resolved probe(Class<?> registryClass) {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        // 1.21.2+: getValue(Identifier) -> T
        try {
            MethodHandle mh = lookup.findVirtual(registryClass, "getValue",
                    MethodType.methodType(Object.class, Identifier.class));
            return new Resolved(Shape.GET_VALUE, mh);
        } catch (Throwable ignored) { /* fall through */ }
        // Legacy (≤1.21.1): get(Identifier) -> T
        try {
            MethodHandle mh = lookup.findVirtual(registryClass, "get",
                    MethodType.methodType(Object.class, Identifier.class));
            return new Resolved(Shape.LEGACY_GET, mh);
        } catch (Throwable ignored) { /* fall through */ }
        // 1.21.2+: get(Identifier) -> Optional<Holder.Reference<T>>
        try {
            MethodHandle mh = lookup.findVirtual(registryClass, "get",
                    MethodType.methodType(Optional.class, Identifier.class));
            return new Resolved(Shape.OPTIONAL_HOLDER, mh);
        } catch (Throwable ignored) { /* fall through */ }

        // Fallback: scan by signature instead of by name. On a production
        // Fabric server with intermediary mappings (the common case), the
        // Mojmap method names "getValue" / "get" do NOT exist at runtime —
        // Loom only remaps method *references* in bytecode, not the string
        // literals passed to reflection / MethodHandles. So a name-based
        // findVirtual misses on a real server, but the *signature*
        // ((Identifier) -> Object) is unique enough among Registry
        // methods (only the value-by-key getter takes a single
        // Identifier and returns Object/Optional). Walk the public
        // method list and pick the first match.
        java.lang.reflect.Method legacy = null;   // (Identifier) -> T (Object-assignable, non-primitive)
        java.lang.reflect.Method opt = null;      // (Identifier) -> Optional
        for (java.lang.reflect.Method m : registryClass.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || p[0] != Identifier.class) continue;
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            Class<?> ret = m.getReturnType();
            // Reject primitives (notably containsKey -> boolean) and void;
            // both have a (Identifier) param shape but neither is a
            // value getter. Without this guard the legacy slot can bind
            // containsKey on an intermediary-mapped Registry, causing every
            // resolve to invoke containsKey, ClassCast to T, and silently
            // return null (root cause of the persistent [PORTAL] warning,
            // 2026-05-07).
            if (ret == void.class || ret.isPrimitive()) continue;
            if (ret == Optional.class) {
                if (opt == null) opt = m;
            } else if (legacy == null) {
                legacy = m;
            }
        }
        // Prefer the direct getter (T return) when both shapes are present;
        // matches the pre-1.21.2 priority and avoids the Optional<Holder>
        // unwrap path's reflection cost.
        if (legacy != null) {
            try {
                return new Resolved(Shape.LEGACY_GET, lookup.unreflect(legacy));
            } catch (Throwable ignored) { /* fall through */ }
        }
        if (opt != null) {
            try {
                return new Resolved(Shape.OPTIONAL_HOLDER, lookup.unreflect(opt));
            } catch (Throwable ignored) { /* fall through */ }
        }
        // No method bound — log loudly so the operator can see why every
        // sound/particle/potion token resolves to null (cosmetic effects
        // would otherwise silently no-op).
        System.err.println("[effects-api] [FabricRegistryCompat] no "
                + "(Identifier -> T) getter found on " + registryClass.getName()
                + "; available 1-arg public methods: " + describeOneArgMethods(registryClass));
        return new Resolved(Shape.NONE, null);
    }

    private static String describeOneArgMethods(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (java.lang.reflect.Method m : cls.getMethods()) {
            if (m.getParameterTypes().length != 1) continue;
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (!first) sb.append(", ");
            first = false;
            sb.append(m.getName()).append('(')
                    .append(m.getParameterTypes()[0].getSimpleName())
                    .append(")->").append(m.getReturnType().getSimpleName());
        }
        return sb.toString();
    }
}
