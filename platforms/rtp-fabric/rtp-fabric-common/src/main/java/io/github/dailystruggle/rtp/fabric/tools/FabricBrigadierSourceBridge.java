package io.github.dailystruggle.rtp.fabric.tools;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

/**
 * NM-touching helpers for translating a Brigadier {@code CommandSourceStack}
 * into the platform-agnostic identifiers used by {@code commands-api}.
 *
 * <p>Lives in {@code rtp-fabric-common} (where {@code net.minecraft.*} imports
 * are permitted) so that {@code RTPFabricMod} - the Fabric entry-point class -
 * can stay free of {@code net.minecraft.*} references and avoid JVM
 * verification failures on MC versions where intermediary names like
 * {@code class_2168} (CommandSourceStack) are not exposed on the runtime
 * classpath. See rtp-fabric-ADR-002 / rtp-fabric-ADR-007 and the 26.1
 * deobfuscated release crash chain.</p>
 *
 * <p>All methods accept {@link Object} so callers can keep their bytecode NM-clean.
 * Inputs that are not {@code CommandSourceStack} collapse to the console-equivalent
 * sentinel {@link RTPAPI#serverId} / "permission granted" (matching Bukkit
 * {@code ConsoleCommandSender} semantics).</p>
 */
public final class FabricBrigadierSourceBridge {

    private FabricBrigadierSourceBridge() {}

    // Cached reflective handles. Resolved lazily from the *runtime* class of
    // the first non-null source we see, so this class's bytecode never names
    // CommandSourceStack (intermediary class_2168), Entity, or ServerPlayer
    // (class_3222) - both of which are absent on MC 26.1.2's deobfuscated
    // runtime and would fail JVM verify on instanceof / invokevirtual.
    private static volatile Method getEntityMethod;     // CommandSourceStack#getEntity() : Entity
    private static volatile Class<?> serverPlayerCls;   // resolved from runtime entity class chain
    private static volatile Method getUuidMethod;       // Entity#getUUID() : UUID
    private static volatile boolean reflectionInitFailed;

    /**
     * @return UUID for the source: player UUID for {@code ServerPlayer},
     *         {@link RTPAPI#serverId} for console / command blocks / unknown.
     */
    public static UUID resolveSenderUuid(Object src) {
        if (src == null) return RTPAPI.serverId;
        // Prefer per-version adapter's typed implementation.
        // Adapters that override return a non-null UUID for player sources, or
        // null to mean "not a player → use console sentinel". The default
        // SPI implementation also returns null, which is indistinguishable from
        // "not a player" - so we use a sentinel-based contract: if the adapter
        // explicitly overrode (we detect via a marker call), trust its null;
        // otherwise fall back to reflection.
        // Implementation: probe via Throwable on a no-op marker isn't worth
        // the complexity - instead, we always call the adapter, and if it
        // returns non-null we use it; if null, we still fall through to the
        // legacy reflective path. Cost on 26.1.2: one extra adapter call per
        // non-player source (e.g. console /rtp), which is negligible.
        try {
            io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
                    io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
            if (adapter != null) {
                UUID viaAdapter = adapter.resolveSenderUuid(src);
                if (viaAdapter != null) return viaAdapter;
            }
        } catch (Throwable ignored) {
            // Registry not yet populated, or adapter threw - fall through.
        }
        if (reflectionInitFailed) return RTPAPI.serverId;
        try {
            Method getEntity = getEntityMethod;
            if (getEntity == null) {
                getEntity = findMethod(src.getClass(), "getEntity", 0);
                if (getEntity == null) {
                    // Not a CommandSourceStack-shaped object; treat as console.
                    return RTPAPI.serverId;
                }
                getEntityMethod = getEntity;
            }
            Object entity;
            try {
                entity = getEntity.invoke(src);
            } catch (Throwable t) {
                return RTPAPI.serverId;
            }
            if (entity == null) return RTPAPI.serverId;

            // Recognise ServerPlayer by walking the entity's class hierarchy
            // for a class whose simple name is "ServerPlayer". Avoids loading
            // the intermediary alias by name.
            Class<?> spCls = serverPlayerCls;
            if (spCls == null) {
                spCls = findClassBySimpleName(entity.getClass(), "ServerPlayer");
                if (spCls != null) serverPlayerCls = spCls;
            }
            if (spCls == null || !spCls.isInstance(entity)) {
                return RTPAPI.serverId;
            }

            Method getUuid = getUuidMethod;
            if (getUuid == null) {
                getUuid = findMethod(entity.getClass(), "getUUID", 0);
                if (getUuid == null) {
                    return RTPAPI.serverId;
                }
                getUuidMethod = getUuid;
            }
            Object uuid = getUuid.invoke(entity);
            if (uuid instanceof UUID u) return u;
            return RTPAPI.serverId;
        } catch (Throwable t) {
            // Don't sticky-flag on transient failures, but do log once-per-call
            // at FINE so the standard auth path still works on next try.
            RTP.log(Level.FINE,
                    "[RTP][Fabric] resolveSenderUuid reflective fallback failed: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return RTPAPI.serverId;
        }
    }

    /**
     * Walk {@code start} + superclasses + interfaces searching for a public
     * (or made-accessible) method with the given name and arg-count.
     */
    private static Method findMethod(Class<?> start, String name, int argCount) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argCount) {
                    try { m.setAccessible(true); } catch (Throwable ignored) {}
                    return m;
                }
            }
            for (Class<?> i : c.getInterfaces()) {
                Method m = findMethod(i, name, argCount);
                if (m != null) return m;
            }
        }
        return null;
    }

    /**
     * Walk the class hierarchy of {@code start} looking for a class whose
     * {@link Class#getSimpleName() simple name} matches. Used to identify
     * {@code ServerPlayer} without referencing its name at the bytecode level.
     */
    private static Class<?> findClassBySimpleName(Class<?> start, String simpleName) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            if (simpleName.equals(c.getSimpleName())) return c;
        }
        return null;
    }

    /**
     * Permission predicate. Mirrors {@code RTPFabricMod}'s prior contract:
     * non-player sources bypass; players consult
     * {@code RTP.serverAccessor.getSender(uuid).hasPermission(perm)};
     * unknown sender entries fail closed.
     */
    public static boolean checkPermission(Object src, String permission) {
        if (permission == null || permission.isEmpty()) return true;
        UUID uuid = resolveSenderUuid(src);
        if (RTPAPI.serverId.equals(uuid)) return true;
        try {
            io.github.dailystruggle.rtp.api.entity.RTPCommandSender sender =
                    RTP.serverAccessor.getSender(uuid);
            if (sender == null) return false;
            return sender.hasPermission(permission);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] Brigadier permission check failed for '" + permission
                            + "' (uuid=" + uuid + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }
}
