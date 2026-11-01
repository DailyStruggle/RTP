package io.github.dailystruggle.rtp.neoforge.effects.local;

import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Capability-based resolver for a {@link ServerPlayer}'s clientbound packet
 * connection and its single-arg packet {@code send} method, shared by the
 * NeoForge TITLE and SOUND effect prototypes.
 *
 * <p><b>Why capability- (signature-) based rather than name-based.</b> The
 * earlier implementation matched the connection field by the literal type name
 * {@code ServerGamePacketListenerImpl} and the send method by the literal name
 * {@code send}. Both of those identifiers drift in the MC 1.21.11 mojmap (the
 * same rename pass that turned {@code ResourceLocation} into {@code Identifier}),
 * so the name match returned {@code null} and TITLE effects were dropped while
 * the signature-resolved SOUND / PARTICLE paths kept working. This helper never
 * names a drift-prone type or method: it locates the field on {@link ServerPlayer}
 * whose type declares a {@code void}, single-argument method accepting a packet,
 * and caches both. S-004: on a resolution miss it reports {@code false} so the
 * caller can emit one diagnostic line instead of swallowing the failure.</p>
 */
public final class NeoForgeConnection {

    private NeoForgeConnection() {}

    private static volatile Field CONNECTION_FIELD;
    private static volatile Method SEND_METHOD;
    private static volatile boolean RESOLVED;
    private static volatile String FAILURE;

    /** Last resolution failure reason, or {@code null} if resolution succeeded / not attempted. */
    public static String failureReason() {
        return FAILURE;
    }

    /**
     * Send {@code packet} to {@code player}'s connection. Returns {@code true}
     * on a successful dispatch, {@code false} if the connection/send pair could
     * not be resolved or the dispatch threw (cosmetic; never breaks teleport).
     */
    public static boolean send(ServerPlayer player, Object packet) {
        resolve();
        Field f = CONNECTION_FIELD;
        Method send = SEND_METHOD;
        if (f == null || send == null || packet == null) return false;
        try {
            Object conn = f.get(player);
            if (conn == null) return false;
            send.invoke(conn, packet);
            return true;
        } catch (Throwable t) {
            FAILURE = "send threw " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            return false;
        }
    }

    private static void resolve() {
        if (RESOLVED) return;
        synchronized (NeoForgeConnection.class) {
            if (RESOLVED) return;
            try {
                // Walk ServerPlayer + superclasses; for each field, see whether
                // its type exposes a single-arg packet send method.
                for (Class<?> cls = ServerPlayer.class; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
                    for (Field f : cls.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        Method send = findSendMethod(f.getType());
                        if (send == null) continue;
                        try { f.setAccessible(true); } catch (Throwable ignored) { /* best-effort */ }
                        CONNECTION_FIELD = f;
                        SEND_METHOD = send;
                        return;
                    }
                }
                FAILURE = "no ServerPlayer field with a single-arg packet send(...) method found "
                        + "(connection listener type/name drifted)";
            } finally {
                RESOLVED = true;
            }
        }
    }

    /**
     * Find a {@code void}, single-argument method on {@code type} (or a
     * superclass) whose parameter looks like a clientbound packet. Prefers a
     * method literally named {@code send}; falls back to any matching
     * signature so a renamed {@code send} still resolves.
     */
    private static Method findSendMethod(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isArray()
                || type.getName().startsWith("java.")) return null;
        Method fallback = null;
        for (Method m : type.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != void.class) continue;
            if (m.getParameterCount() != 1) continue;
            String paramName = m.getParameterTypes()[0].getName();
            if (!paramName.endsWith("Packet")) continue;
            if ("send".equals(m.getName())) return m;
            if (fallback == null) fallback = m;
        }
        return fallback;
    }
}
