package io.github.dailystruggle.effectsapi.fabric_unobf;

import io.github.dailystruggle.effectsapi.common.Effect;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
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
public final class FabricEffectRuntimeUnobf {

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

    public FabricEffectRuntimeUnobf() {
        this(false);
    }

    /**
     * @param strict if {@code true}, {@link #schedule(Runnable, long)} throws
     *               on no-server-bound (test-friendly); if {@code false} it
     *               warns once to {@code System.err} and drops the task
     *               (production-friendly — RTP shouldn't crash if effects fire
     *               between disabling and JVM exit).
     */
    public FabricEffectRuntimeUnobf(boolean strict) {
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
            String msg = "[effects-api/fabric] FabricEffectRuntimeUnobf.schedule called before SERVER_STARTED "
                    + "(or after SERVER_STOPPED). Task dropped.";
            if (strict) throw new IllegalStateException(msg);
            System.err.println(msg);
            return;
        }
        s.execute(task);
    }

    // ---------------------------------------------------------------------
    // Functional dispatch hooks for mapping-dependent effect surfaces
    // (sound, particle). Per-version Loom adapters (rtp-fabric-v*) register
    // a lambda at startup that uses direct, compiler-bound mapped calls;
    // effects-api falls back to its in-tree reflective implementation when
    // nothing has been registered (default behavior on every runtime today,
    // preserves bug-for-bug compatibility with the prior code path).
    //
    // Rationale: effects-api is non-Loom and platform-agnostic, so it can
    // only reach ServerPlayer#playNotifySound / ServerLevel#sendParticles
    // by reflection across MC versions (1.20 → 1.21.11). That reflection
    // is fragile (constructor arity changes, Holder<SoundEvent> vs raw
    // SoundEvent, boolean-prefix on sendParticles, intermediary remapping).
    // Per-version adapters compile against Yarn/intermediary mappings via
    // Loom and don't have those degrees of freedom — registering a lambda
    // from there bypasses the resolver entirely.
    //
    // See effects-api/docs/adr/effects-api-ADR-003-… (Fabric platform split)
    // and AGENTS.md "Architecture Boundaries".
    // ---------------------------------------------------------------------

    /**
     * Plays a sound directly to {@code player}. Implementations may target the
     * player's connection (preferred — bypasses the chunk tracker, which drops
     * broadcast packets right after a long teleport) or fall back to a level
     * broadcast.
     */
    @FunctionalInterface
    public interface SoundDispatcher {
        void play(@NotNull ServerPlayer player,
                  @NotNull SoundEvent sound,
                  @NotNull SoundSource source,
                  double x, double y, double z,
                  float volume, float pitch);
    }

    /**
     * Spawns particles at {@code (x,y,z)} for {@code recipient} (or broadcast
     * to the level when implementations choose). Implementations should prefer
     * the targeted overload of sendParticles so the packet bypasses the chunk
     * tracker (same post-teleport drop hazard as sound).
     */
    @FunctionalInterface
    public interface ParticleDispatcher {
        void send(@NotNull ServerPlayer recipient,
                  @NotNull ParticleOptions options,
                  double x, double y, double z,
                  int count,
                  double dx, double dy, double dz, double speed);
    }

    private static final AtomicReference<SoundDispatcher> SOUND_DISPATCHER = new AtomicReference<>();
    private static final AtomicReference<ParticleDispatcher> PARTICLE_DISPATCHER = new AtomicReference<>();

    /**
     * Register a sound dispatcher. The most-recent registration wins; passing
     * {@code null} clears the registration and reverts to the in-tree default
     * (typically the reflective fallback installed by {@code FabricSoundEffect}).
     */
    public static void registerSound(@Nullable SoundDispatcher dispatcher) {
        SoundDispatcher prev = SOUND_DISPATCHER.getAndSet(dispatcher);
        if (prev != null && dispatcher != null && prev != dispatcher) {
            System.err.println("[effects-api] [FabricEffectRuntimeUnobf] sound dispatcher overridden ("
                    + prev.getClass().getName() + " -> " + dispatcher.getClass().getName() + ")");
        }
    }

    /** @return the currently registered sound dispatcher, or {@code null}. */
    public static @Nullable SoundDispatcher getSoundDispatcher() {
        return SOUND_DISPATCHER.get();
    }

    /** Register a particle dispatcher. Same semantics as {@link #registerSound}. */
    public static void registerParticle(@Nullable ParticleDispatcher dispatcher) {
        ParticleDispatcher prev = PARTICLE_DISPATCHER.getAndSet(dispatcher);
        if (prev != null && dispatcher != null && prev != dispatcher) {
            System.err.println("[effects-api] [FabricEffectRuntimeUnobf] particle dispatcher overridden ("
                    + prev.getClass().getName() + " -> " + dispatcher.getClass().getName() + ")");
        }
    }

    /** @return the currently registered particle dispatcher, or {@code null}. */
    public static @Nullable ParticleDispatcher getParticleDispatcher() {
        return PARTICLE_DISPATCHER.get();
    }

    /**
     * Builds and applies a {@code MobEffectInstance} to {@code player}.
     *
     * <p>Required because the {@code MobEffectInstance} ctor changed shape in
     * 1.20.5 ({@code MobEffect} → {@code Holder<MobEffect>}); effects-api is
     * non-Loom and cannot pick the correct ctor at compile time. Per-version
     * Loom adapters (rtp-fabric-v*) register an implementation that calls the
     * mapped ctor directly. Per AGENTS.md "no reflection in the api — if we
     * need Mojang mappings we use an interface completed by each server
     * version adapter".
     */
    @FunctionalInterface
    public interface PotionDispatcher {
        void apply(@NotNull ServerPlayer player,
                   @NotNull MobEffect effect,
                   int duration,
                   int amplifier,
                   boolean ambient,
                   boolean visible,
                   boolean showIcon);
    }

    private static final AtomicReference<PotionDispatcher> POTION_DISPATCHER = new AtomicReference<>();

    /** Register a potion dispatcher. Same semantics as {@link #registerSound}. */
    public static void registerPotion(@Nullable PotionDispatcher dispatcher) {
        PotionDispatcher prev = POTION_DISPATCHER.getAndSet(dispatcher);
        if (prev != null && dispatcher != null && prev != dispatcher) {
            System.err.println("[effects-api] [FabricEffectRuntimeUnobf] potion dispatcher overridden ("
                    + prev.getClass().getName() + " -> " + dispatcher.getClass().getName() + ")");
        }
    }

    /** @return the currently registered potion dispatcher, or {@code null}. */
    public static @Nullable PotionDispatcher getPotionDispatcher() {
        return POTION_DISPATCHER.get();
    }
}
