package io.github.dailystruggle.effectsapi.fabric;

import io.github.dailystruggle.effectsapi.common.Effect;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Fabric scheduler chokepoint for the effects API.
 *
 * <p>Phase-1 scope per <em>effects-api-ADR-003</em>: this runtime exposes
 * only the scheduling primitive needed to push an {@link Effect} run() onto
 * the main server thread. Sound / particle / potion mechanics live inline in
 * each concrete Fabric effect (mirroring the Bukkit-side pattern where
 * {@code SoundEffect#run()} calls {@code Player#playSound} directly), because
 * the concrete effect types already need Mojmap objects (SoundEvent, ParticleType,
 * MobEffect) to do their work and routing those through the platform-neutral
 * {@code EffectRuntime} SPI provides no decoupling benefit on the Fabric side.
 *
 * <p>S-005: {@link #schedule} forwards via {@link MinecraftServer#execute}, which
 * enqueues onto the server's tick processor. Never blocks, never touches chunks.
 *
 * <p>The {@link MinecraftServer} reference is bound at server-start time by
 * {@code RTPFabricMod}'s {@code ServerLifecycleEvents.SERVER_STARTED} listener.
 * Calls before binding (or after server stop) become no-ops with a single
 * S-004-compliant diagnostic to {@code System.err}.
 */
public final class FabricEffectRuntime {

    private static final AtomicReference<MinecraftServer> SERVER = new AtomicReference<>();

    /** Bind the MinecraftServer reference. Idempotent; called from SERVER_STARTED. */
    public static void bindServer(@NotNull MinecraftServer server) {
        SERVER.set(server);
    }

    /** Drop the binding on SERVER_STOPPING / SERVER_STOPPED. */
    public static void unbindServer() {
        SERVER.set(null);
    }

    /** @return the bound server, or {@code null} if not yet started or already stopped. */
    public static @Nullable MinecraftServer server() {
        return SERVER.get();
    }

    private final boolean strict;

    public FabricEffectRuntime() {
        this(false);
    }

    /**
     * @param strict if {@code true}, {@link #schedule(Runnable, long)} throws
     *               on no-server-bound (test-friendly); if {@code false} it
     *               warns once to {@code System.err} and drops the task
     *               (production-friendly — RTP shouldn't crash if effects fire
     *               between disabling and JVM exit).
     */
    public FabricEffectRuntime(boolean strict) {
        this.strict = strict;
    }

    /**
     * Schedule a one-shot task on the server thread. {@code delayTicks} is
     * accepted for API parity with the Bukkit side; Phase-1 ignores it (all
     * effect runs go onto the next tick via {@link MinecraftServer#execute}).
     * A future slice can layer real tick-delay support via the existing
     * {@code FabricScheduler} infrastructure if any concrete effect grows a
     * scheduled-cancel use case.
     */
    public void schedule(@NotNull Runnable task, long delayTicks) {
        MinecraftServer s = SERVER.get();
        if (s == null) {
            String msg = "[effects-api/fabric] FabricEffectRuntime.schedule called before SERVER_STARTED "
                    + "(or after SERVER_STOPPED). Task dropped.";
            if (strict) throw new IllegalStateException(msg);
            System.err.println(msg);
            return;
        }
        s.execute(task);
    }
}
