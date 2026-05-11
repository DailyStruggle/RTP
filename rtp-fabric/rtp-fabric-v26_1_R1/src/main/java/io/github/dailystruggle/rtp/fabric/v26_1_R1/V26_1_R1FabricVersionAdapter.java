package io.github.dailystruggle.rtp.fabric.v26_1_R1;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.fabric.unobf.effects.FabricEffectsHandlerUnobf;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import io.github.dailystruggle.rtp.fabric.version.RTPBlockHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPBlockStateHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPChunkHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.fabric.version.RTPRegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * MC 26.1.2 implementation of {@link FabricVersionAdapter}.
 *
 * <p><b>Status: stub (rtp-fabric-ADR-001).</b> Bodies are deferred to a follow-up
 * Phase 2.5 task. The 26.1 porting diff against the v1_21_R1 reference is
 * non-trivial and benefits from being done in one focused pass once a JDK 25
 * + Loom 1.15 build environment is verified locally:</p>
 *
 * <ul>
 *   <li>{@code ChunkStatus} moved from {@code net.minecraft.world.level.chunk}
 *       to {@code net.minecraft.world.level.chunk.status} starting in 1.21.3
 *       and remains there in 26.1.</li>
 *   <li>26.1 is the first deobfuscated MC release; some class/method names
 *       shifted as part of the deobfuscation pass. The exact rename diff
 *       affecting the SPI surface (registry access, biome holder, chunk
 *       source, Blocks accessor) needs to be catalogued at port time. The
 *       SPI surface itself is now Mojmap-name-stable per
 *       {@code rtp-fabric-ADR-007}; only adapter-internal types are
 *       affected by the deobf pass.</li>
 * </ul>
 *
 * <p>Methods throw {@link UnsupportedOperationException} with a
 * {@code TODO(rtp-fabric-ADR-001)} marker — fail-loud per S-006.</p>
 */
public final class V26_1_R1FabricVersionAdapter implements FabricVersionAdapter {

    // Non-persistent RTP-owned chunk ticket — see rtp-fabric-ADR-003 / -006.
    // Same TicketType shape as 1.21.11: (long timeout, int flags). Omit
    // FLAG_PERSIST so the ticket is never written to level.dat (S-002 safe).
    private static final int RTP_TICKET_RADIUS = 3;
    private static final int RTP_TICKET_FLAGS =
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION;
    private static final TicketType RTP_TICKET_TYPE =
            new TicketType(TicketType.NO_TIMEOUT, RTP_TICKET_FLAGS);

    @Override
    public String mcVersion() {
        return "26.1.2";
    }

    @Override
    public @Nullable RTPRegistryKey blockKey(RTPBlockHandle block) {
        // TODO(rtp-fabric-ADR-001): cast block.as(Block.class), then
        // BuiltInRegistries.BLOCK.getKey(b) → RTPRegistryKey.
        throw new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (rtp-fabric-ADR-001)");
    }

    @Override
    public @Nullable RTPRegistryKey biomeKeyAt(RTPLevelHandle level, int x, int y, int z) {
        // TODO(rtp-fabric-ADR-001): port from V1_21_R1FabricVersionAdapter; Holder<Biome>
        // pattern is expected to survive deobfuscation but verify.
        throw new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (rtp-fabric-ADR-001)");
    }

    @Override
    public CompletableFuture<RTPChunkHandle> getChunkFull(RTPLevelHandle level, int cx, int cz) {
        // TODO(rtp-fabric-ADR-001): import ChunkStatus from
        // net.minecraft.world.level.chunk.status.ChunkStatus (post-1.21.3 path).
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (rtp-fabric-ADR-001)"));
    }

    @Override
    public boolean hasChunk(RTPLevelHandle level, int cx, int cz) {
        // TODO(rtp-fabric-ADR-001): port from V1_21_R1FabricVersionAdapter.
        throw new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (rtp-fabric-ADR-001)");
    }

    @Override
    public RTPBlockStateHandle airState() {
        // TODO(rtp-fabric-ADR-001): RTPBlockStateHandle.of(Blocks.AIR.defaultBlockState()).
        throw new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (rtp-fabric-ADR-001)");
    }

    @Override
    public void installEffectsDispatchers() {
        // Independent of the unimplemented stubs above — see V26_1_R1FabricEffectDispatchers.
        V26_1_R1FabricEffectDispatchers.install();
    }

    /**
     * Effects-api wiring for deobf MC 26.1.2 — Phase 5 item 21 + ADR-009 step 7.
     *
     * <p>The default obf-carrier path ({@code FabricEffectsHandler.setupEffects} →
     * {@code FabricEffectRuntime.bindServer}) names intermediary NM types
     * ({@code class_3222}, {@code class_2596}, {@code class_7923}, ...) in its
     * constant pool — these aliases don't exist on the deobfuscated 26.1.2
     * runtime, so the obf path raises {@link NoClassDefFoundError} on link.
     *
     * <p>This override routes the same setup through the unobf carrier
     * ({@link FabricEffectsHandlerUnobf}, compiled by Loom 1.15 with no
     * mappings step), whose constant pool names Mojang names natively and
     * links cleanly on 26.1.2. Returning {@code true} suppresses the caller's
     * obf-carrier fallback per the {@link FabricVersionAdapter#installEffectsWiring}
     * contract.
     */
    @Override
    public boolean installEffectsWiring(Object server) {
        if (!(server instanceof MinecraftServer mc)) {
            RTP.log(Level.WARNING,
                    "[RTP] V26_1_R1 installEffectsWiring received non-MinecraftServer: "
                            + (server == null ? "null" : server.getClass().getName()));
            return false;
        }
        FabricEffectsHandlerUnobf.setupEffects(mc);
        return true;
    }

    /**
     * Typed mojmap implementation of {@link FabricVersionAdapter#extractPlayerFromConnection}.
     * MC 26.x exposes {@code ServerGamePacketListenerImpl#getPlayer()}; no reflection.
     */
    @Override
    public @Nullable Object extractPlayerFromConnection(Object handler) {
        if (!(handler instanceof ServerGamePacketListenerImpl impl)) return null;
        return impl.getPlayer();
    }

    /**
     * Typed mojmap implementation of {@link FabricVersionAdapter#getPlayerUUID}.
     * MC 26.x exposes {@code ServerPlayer#getUUID()}; no reflection.
     */
    @Override
    public java.util.@Nullable UUID getPlayerUUID(Object player) {
        if (!(player instanceof ServerPlayer sp)) return null;
        return sp.getUUID();
    }

    /**
     * Construct an MC 26.1.2 player wrapper. The default {@code FabricRTPPlayer}
     * in {@code rtp-fabric-common} is compiled by Loom against intermediary
     * mappings (e.g. {@code class_3222}) which don't exist on the deobfuscated
     * 26.1.2 runtime; any class linkage on it raises {@link NoClassDefFoundError}.
     * This module is built with Loom's unobfuscated plugin and skips the
     * mappings step, so {@link V26_1_R1FabricRTPPlayer}'s bytecode references
     * Mojang names natively and links cleanly.
     */
    @Override
    public @Nullable RTPPlayer createPlayer(Object serverPlayer) {
        if (!(serverPlayer instanceof ServerPlayer sp)) return null;
        return new V26_1_R1FabricRTPPlayer(sp);
    }

    @Override
    public void rebindPlayer(RTPPlayer existing, Object serverPlayer) {
        if (existing instanceof V26_1_R1FabricRTPPlayer wrapper
                && serverPlayer instanceof ServerPlayer sp) {
            wrapper.rebind(sp);
        }
    }

    /**
     * Construct an MC 26.1.2 world wrapper. Same rationale as
     * {@link #createPlayer(Object)} — the default {@code FabricRTPWorld} in
     * {@code rtp-fabric-common} is Loom-remapped to intermediary aliases
     * ({@code class_3218} for {@code ServerLevel}, etc.) that don't exist on
     * the deobfuscated 26.1.2 runtime. This module's bytecode references
     * Mojang names natively and links cleanly.
     */
    @Override
    public @Nullable RTPWorld<?> createWorld(Object serverLevel) {
        if (!(serverLevel instanceof ServerLevel sl)) return null;
        return new V26_1_R1FabricRTPWorld(sl);
    }

    /**
     * Construct a native-backed worldborder for MC 26.1.2 — direct typed
     * call against {@code ServerLevel#getWorldBorder()}; common's typed
     * path can't link on the deobfuscated runtime. Mirrors the
     * {@code FabricServerAccessor#createNativeWorldBorder} divisor pair
     * (border diameter/32 in chunks, centre/16 in chunks) so the resulting
     * shape matches the Bukkit reference.
     */
    @Override
    public @Nullable io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder
            createNativeWorldBorder(Object serverLevel) {
        if (!(serverLevel instanceof ServerLevel sl)) return null;
        net.minecraft.world.level.border.WorldBorder mcBorder = sl.getWorldBorder();
        if (mcBorder == null) return null;
        return new io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder(
                () -> {
                    io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> shape =
                            (io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?>)
                                    io.github.dailystruggle.rtp.common.RTP.factoryMap
                                            .get(io.github.dailystruggle.rtp.common.RTP.factoryNames.shape)
                                            .get("SQUARE");
                    if (shape instanceof io.github.dailystruggle.rtp.common.selection
                            .region.selectors.memory.shapes.Square square) {
                        square.set(io.github.dailystruggle.rtp.common.selection.region
                                        .selectors.memory.shapes.enums.GenericMemoryShapeParams.radius,
                                (long) (mcBorder.getSize() / 32.0));
                        square.set(io.github.dailystruggle.rtp.common.selection.region
                                        .selectors.memory.shapes.enums.GenericMemoryShapeParams.centerX,
                                (long) (mcBorder.getCenterX() / 16.0));
                        square.set(io.github.dailystruggle.rtp.common.selection.region
                                        .selectors.memory.shapes.enums.GenericMemoryShapeParams.centerZ,
                                (long) (mcBorder.getCenterZ() / 16.0));
                    }
                    return shape;
                },
                rtpLocation -> sl.getWorldBorder().isWithinBounds(
                        (double) rtpLocation.x(), (double) rtpLocation.z()));
    }

    /**
     * Apply a non-persistent RTP-owned chunk ticket via
     * {@link ServerChunkCache#addTicketWithRadius}. The ticket type's
     * {@code FLAG_PERSIST} bit is omitted, so the ticket lives only for the
     * JVM lifetime — replaces the previous leaky
     * {@code level.setChunkForced(cx, cz, true)} call which writes through
     * to {@code level.dat#ForcedChunks} and would survive an unclean
     * shutdown (S-002 hazard). Caller is responsible for hopping to the
     * server tick thread.
     */
    @Override
    public CompletableFuture<Void> applyTicket(RTPLevelHandle level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("null ServerLevel"));
        }
        try {
            ServerLevel sl = level.as(ServerLevel.class);
            ServerChunkCache cache = sl.getChunkSource();
            cache.addTicketWithRadius(RTP_TICKET_TYPE, new ChunkPos(cx, cz), RTP_TICKET_RADIUS);
            return CompletableFuture.completedFuture(null);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric 26.1.2] applyTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
    }

    /**
     * Release a previously-applied non-persistent RTP ticket. No-op if the
     * ticket is not present (matches vanilla {@code DistanceManager#removeRegionTicket}
     * semantics).
     */
    /**
     * Typed override — direct {@code MinecraftServer#getRunningThread()} call.
     * Replaces the reflective lookup in
     * {@code FabricServerAccessor#isPrimaryThread} and
     * {@code FabricScheduler#serverRunningThread} on this runtime.
     */
    @Override
    public @Nullable Thread getServerThread(Object server) {
        if (!(server instanceof MinecraftServer s)) return null;
        return s.getRunningThread();
    }

    /**
     * Typed override — direct
     * {@code server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command)}
     * dispatch. Replaces the reflective lookup in
     * {@code FabricServerAccessor.FabricConsoleSender#performCommand} on this runtime.
     */
    @Override
    public boolean dispatchConsoleCommand(Object server, String command) {
        if (!(server instanceof MinecraftServer s) || command == null) return false;
        s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), command);
        return true;
    }

    /**
     * Typed override — direct {@code CommandSourceStack#getEntity()} +
     * {@code instanceof ServerPlayer} + {@code Entity#getUUID()} dispatch.
     * Replaces the reflective `findMethod` walk in
     * {@code FabricBrigadierSourceBridge#resolveSenderUuid} on this runtime.
     * Returns {@code null} for non-player sources (console / command block);
     * the bridge interprets that as the {@code RTPAPI.serverId} sentinel.
     */
    @Override
    public @Nullable java.util.UUID resolveSenderUuid(Object src) {
        if (!(src instanceof net.minecraft.commands.CommandSourceStack css)) return null;
        net.minecraft.world.entity.Entity entity = css.getEntity();
        if (!(entity instanceof ServerPlayer sp)) return null;
        return sp.getUUID();
    }

    @Override
    public CompletableFuture<Void> releaseTicket(RTPLevelHandle level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            ServerLevel sl = level.as(ServerLevel.class);
            ServerChunkCache cache = sl.getChunkSource();
            cache.removeTicketWithRadius(RTP_TICKET_TYPE, new ChunkPos(cx, cz), RTP_TICKET_RADIUS);
            return CompletableFuture.completedFuture(null);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric 26.1.2] releaseTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
    }
}
