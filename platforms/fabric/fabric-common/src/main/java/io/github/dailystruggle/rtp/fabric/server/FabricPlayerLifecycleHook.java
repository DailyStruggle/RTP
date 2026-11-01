package io.github.dailystruggle.rtp.fabric.server;

import io.github.dailystruggle.rtp.api.server.DispatchingPlayerLifecycleHook;
import io.github.dailystruggle.rtp.common.RTP;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Fabric implementation of
 * {@link io.github.dailystruggle.rtp.api.server.PlayerLifecycleHook} backed by
 * {@link DispatchingPlayerLifecycleHook}.
 *
 * <p>Unlike the Bukkit shim, this class does <b>not</b> register Fabric event
 * callbacks itself. Fabric's {@code ServerPlayConnectionEvents.JOIN /
 * DISCONNECT} hooks are owned by
 * {@code io.github.dailystruggle.rtp.fabric.events.FabricEventBridge}, which is
 * registered reflectively at mod-init time to avoid linking against
 * intermediary {@code ServerGamePacketListenerImpl} on the deobf 26.x runtime
 * (see {@link io.github.dailystruggle.rtp.fabric.events.FabricEventBridge}
 * and rtp-fabric-ADR-009 for the obf / unobf carrier split). To avoid
 * duplicating that fragile reflection, this shim exposes
 * {@link #fireJoinFromPlayer(Object)} / {@link #fireQuitFromPlayer(Object)}
 * that the bridge invokes after its existing register / unregister calls.
 *
 * <p>The {@code Object}-typed signatures keep this class's synthetic
 * bytecode free of any reference to {@code ServerPlayer}
 * (intermediary {@code class_3222}), which is absent on MC 26.1.2's
 * deobfuscated runtime and would fail JVM verify on instanceof. UUID
 * extraction goes through {@code player.getClass().getMethod("getUUID")}
 * via reflection, matching the existing pattern in
 * {@code FabricOnEventTeleports}.
 *
 * <p>Introduced by <a href="../../../../../../../../../docs/adr/ADR-049-network-mode-platform-neutral-lift.md">ADR-049</a>.
 *
 * @since 3.0.0-beta.4
 */
public final class FabricPlayerLifecycleHook extends DispatchingPlayerLifecycleHook {

    /**
     * Fan a join event out to subscribers, extracting the UUID reflectively
     * from the supplied {@code ServerPlayer}-typed object. Silently swallows
     * extraction failures (logs at WARNING) so the caller can stay in the
     * critical lifecycle path.
     *
     * @param player a {@code ServerPlayer}-typed object (or {@code null}, in
     *     which case this method is a no-op)
     */
    public void fireJoinFromPlayer(Object player) {
        UUID uuid = extractUuid(player);
        if (uuid != null) fireJoin(uuid);
    }

    /**
     * Fan a quit event out to subscribers, extracting the UUID reflectively
     * from the supplied {@code ServerPlayer}-typed object. Silently swallows
     * extraction failures (logs at WARNING).
     *
     * @param player a {@code ServerPlayer}-typed object (or {@code null}, in
     *     which case this method is a no-op)
     */
    public void fireQuitFromPlayer(Object player) {
        UUID uuid = extractUuid(player);
        if (uuid != null) fireQuit(uuid);
    }

    private static UUID extractUuid(Object player) {
        if (player == null) return null;
        // Prefer the typed FabricVersionAdapter.getPlayerUUID SPI so Loom
        // remaps the descriptor per-runtime (mojmap getUUID on 26.x; intermediary
        // method_5667 on 1.20/1.21.x). Reflective getMethod("getUUID") fails on
        // intermediary because the mojmap method name is obfuscated there.
        try {
            io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
                    io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
            if (adapter != null) {
                UUID uuid = adapter.getPlayerUUID(player);
                if (uuid != null) return uuid;
            }
        } catch (Throwable t) {
            // fall through to reflective attempt
        }
        try {
            Method m = player.getClass().getMethod("getUUID");
            Object out = m.invoke(player);
            return (out instanceof UUID u) ? u : null;
        } catch (NoSuchMethodException nsme) {
            // Intermediary runtime where mojmap name is absent and adapter
            // wasn't available; best-effort no-op (caller logs at WARNING-free
            // level since this is a non-fatal lifecycle path).
            return null;
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] FabricPlayerLifecycleHook: getUUID() failed on "
                            + player.getClass().getName(), t);
            return null;
        }
    }
}
