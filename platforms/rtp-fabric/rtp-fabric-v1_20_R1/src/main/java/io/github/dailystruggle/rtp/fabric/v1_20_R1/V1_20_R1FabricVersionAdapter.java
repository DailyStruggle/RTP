package io.github.dailystruggle.rtp.fabric.v1_20_R1;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import io.github.dailystruggle.rtp.fabric.version.RTPChunkHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
// 1.20.1 ChunkStatus lives at .chunk.ChunkStatus (the package move to
// .chunk.status happened in 1.21.3 - see V1_21_R1FabricVersionAdapter Javadoc).
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * MC 1.20.1 implementation of {@link FabricVersionAdapter}.
 *
 * <p>Ported from {@code V1_21_R1FabricVersionAdapter} (the reference
 * implementation per rtp-fabric-ADR-001). Chunk tickets go through the public
 * {@code ServerChunkCache#addRegionTicket} / {@code #removeRegionTicket}, which
 * Loom remaps to intermediary at build time.</p>
 *
 * <p>Per-version notable difference vs. v1_21_R1: {@link ChunkStatus} lives
 * at {@code net.minecraft.world.level.chunk.ChunkStatus} on 1.20.1; the
 * package move to {@code .chunk.status} happens in 1.21.3.</p>
 */
public final class V1_20_R1FabricVersionAdapter implements FabricVersionAdapter {

    @Override
    public String mcVersion() {
        return "1.20.1";
    }

    /**
     * Typed block-tag snapshot (mirror of {@code V1_21_R1FabricVersionAdapter}). The
     * reflective fallback in {@code FabricServerAccessor} cannot read the record accessor
     * of {@code TagKey} under intermediary names, so without this override 1.20.1 never
     * flattens {@code #tag} tokens.
     */
    @Override
    public @Nullable Map<String, Set<String>> snapshotBlockTags() {
        try {
            Map<String, Set<String>> out = new HashMap<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
                if (blockId == null) continue;
                String materialName = blockId.toString().toUpperCase();
                Holder.Reference<Block> holder;
                try {
                    holder = block.builtInRegistryHolder();
                } catch (Throwable t) {
                    continue;
                }
                if (holder == null) continue;
                java.util.Iterator<TagKey<Block>> it = holder.tags().iterator();
                while (it.hasNext()) {
                    TagKey<Block> tagKey = it.next();
                    if (tagKey == null) continue;
                    ResourceLocation tagId = tagKey.location();
                    if (tagId == null) continue;
                    out.computeIfAbsent(tagId.getNamespace() + ":" + tagId.getPath(), k -> new HashSet<>())
                            .add(materialName);
                }
            }
            Map<String, Set<String>> immutable = new HashMap<>(out.size());
            for (Map.Entry<String, Set<String>> e : out.entrySet()) {
                immutable.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
            }
            return Collections.unmodifiableMap(immutable);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public CompletableFuture<RTPChunkHandle> getChunkFull(RTPLevelHandle level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("null ServerLevel"));
        }
        try {
            ServerLevel sl = level.as(ServerLevel.class);
            ChunkAccess chunk = sl.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
            return CompletableFuture.completedFuture(RTPChunkHandle.of(chunk));
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }


    // -------------------------------------------------------------------------
    // Non-blocking chunk-future dispatch - see rtp-fabric-ADR-008 and the
    // crash-report 2026-05-08_01.22.29-server.txt deadlock analysis.
    //
    // Resolves the private ServerChunkCache#getChunkFutureMainThread(int, int,
    // ChunkStatus, boolean) structurally so it works under both Mojmap and
    // Fabric intermediary (method_14134) mappings. Three declared methods share
    // that signature on 1.20.1: the public getChunkFuture (method_17299), which
    // on the tick thread calls getChunkFutureMainThread and then managedBlocks
    // until generation finishes, and a synthetic supplyAsync lambda. The
    // dispatch runs on the tick thread, so the public one would generate chunks
    // synchronously there (S-005); the scan must not depend on declaration
    // order. Caches the resolved Method.
    // -------------------------------------------------------------------------

    private static volatile Method GET_CHUNK_FUTURE_METHOD;

    private static Method resolveGetChunkFutureMethod(ServerChunkCache cache) throws ReflectiveOperationException {
        Method cached = GET_CHUNK_FUTURE_METHOD;
        if (cached != null) return cached;
        synchronized (V1_20_R1FabricVersionAdapter.class) {
            cached = GET_CHUNK_FUTURE_METHOD;
            if (cached != null) return cached;
            Method found = null;
            for (Class<?> c = cache.getClass(); c != null && found == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!isNonBlockingChunkFutureMethod(m)) continue;
                    m.setAccessible(true);
                    found = m;
                    break;
                }
            }
            if (found == null) {
                // Fail closed: the public getChunkFuture would block the tick thread.
                throw new NoSuchMethodException(
                        "ServerChunkCache#getChunkFutureMainThread(int,int,ChunkStatus,boolean) not found on "
                                + cache.getClass().getName());
            }
            RTP.log(Level.INFO, "[RTP][Fabric 1.20.1] chunk-future method bound: "
                    + found.getDeclaringClass().getName() + "#" + found.getName());
            GET_CHUNK_FUTURE_METHOD = found;
            return found;
        }
    }

    /**
     * {@code getChunkFutureMainThread} shape: private, non-static, non-synthetic, returns a
     * {@link CompletableFuture}, takes {@code (int, int, ChunkStatus, boolean)}. Excludes the
     * public, main-thread-blocking {@code getChunkFuture} and the synthetic lambda.
     */
    static boolean isNonBlockingChunkFutureMethod(Method m) {
        int mod = m.getModifiers();
        if (!java.lang.reflect.Modifier.isPrivate(mod) || java.lang.reflect.Modifier.isStatic(mod)) return false;
        if (m.isSynthetic() || m.isBridge()) return false;
        if (!CompletableFuture.class.isAssignableFrom(m.getReturnType())) return false;
        Class<?>[] p = m.getParameterTypes();
        return p.length == 4 && p[0] == int.class && p[1] == int.class
                && p[2] == ChunkStatus.class && p[3] == boolean.class;
    }

    /**
     * Non-blocking dispatch via {@code ServerChunkCache#getChunkFutureMainThread}.
     *
     * <p>Returns a future-of-future that we flatten to a {@code RTPChunkHandle}
     * future. The inner vanilla future yields {@code Either<ChunkAccess,
     * ChunkLoadingFailure>}; we treat the Right (failure) case as {@code null}
     * so callers can route it through {@code FailTypes.nullChunk} per
     * REQ-RTP-S-004 - no silent discards.</p>
     */
    @Override
    public CompletableFuture<RTPChunkHandle> requestFullChunkAsync(RTPLevelHandle level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("null ServerLevel"));
        }
        try {
            ServerLevel sl = level.as(ServerLevel.class);
            ServerChunkCache cache = sl.getChunkSource();
            Method getter = resolveGetChunkFutureMethod(cache);

            // Add a temporary ticket on the chunk *before* requesting generation.
            // getChunkFutureMainThread's own UNKNOWN ticket expires after one
            // tick, so without this the holder drops below FULL mid-generation
            // and the future completes with Either.right(ChunkLoadingFailure)
            // (the nullChunk/asyncLoadNull burst seen on 1.20.1+C2ME runs).
            //
            // Released in whenComplete, so it lives only for the load. It uses
            // its own TicketType: tickets with equal type, level and position
            // collapse into one, so sharing the kept-cache type would let this
            // release drop a keep ticket on the same chunk (S-002).
            ChunkPos cp = new ChunkPos(cx, cz);
            boolean ticketAdded = false;
            try {
                cache.addRegionTicket(loadTicketType(), cp, RTP_TICKET_DISTANCE, cp);
                ticketAdded = true;
            } catch (Throwable t) {
                // Non-fatal: best-effort. Some platforms / mod combos may not
                // need the temp ticket; we still attempt the load and let the
                // caller observe the outcome.
                RTP.log(Level.WARNING,
                        "[RTP][Fabric 1.20.1] temp load-ticket apply failed for chunk=("
                                + cx + "," + cz + "): " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            final boolean addedTicket = ticketAdded;

            Object raw;
            try {
                raw = getter.invoke(cache, cx, cz, ChunkStatus.FULL, /*create=*/ true);
            } catch (Throwable t) {
                if (addedTicket) tryRemoveLoadTicket(cache, cp);
                return CompletableFuture.failedFuture(t);
            }
            if (!(raw instanceof CompletableFuture<?> cf)) {
                if (addedTicket) tryRemoveLoadTicket(cache, cp);
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "getChunkFuture returned non-CompletableFuture: " + (raw == null ? "null" : raw.getClass())));
            }
            // cf is CompletableFuture<Either<ChunkAccess, ChunkLoadingFailure>>.
            return cf.thenApply(either -> {
                Object left = eitherLeft(either);
                return left instanceof ChunkAccess chunk ? RTPChunkHandle.of(chunk) : null;
            }).whenComplete((handle, ex) -> {
                if (addedTicket) tryRemoveLoadTicket(cache, cp);
            });
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    /**
     * Best-effort removal of the temporary load ticket added in
     * {@link #requestFullChunkAsync}. Failure here is logged but never
     * propagated - the load itself has already produced its outcome and
     * the caller's pipeline must not be re-failed by ticket cleanup.
     */
    private static void tryRemoveLoadTicket(ServerChunkCache cache, ChunkPos cp) {
        try {
            cache.removeRegionTicket(loadTicketType(), cp, RTP_TICKET_DISTANCE, cp);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric 1.20.1] temp load-ticket release failed for chunk=("
                            + cp.x + "," + cp.z + "): " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * {@code left().orElse(null)} of a DataFixerUpper {@link Either} (a library type, not
     * remapped), {@code null} for the Right "failure" branch or a non-Either. Typed: the
     * runtime classes are the private {@code Either$Left} / {@code Either$Right}, so a
     * reflective {@code getClass().getMethod("left").invoke(..)} throws
     * {@link IllegalAccessException} and would read every loaded chunk as missing.
     */
    static Object eitherLeft(Object either) {
        return either instanceof Either<?, ?> e ? e.left().orElse(null) : null;
    }


    // -------------------------------------------------------------------------
    // Non-persistent chunk tickets (rtp-fabric-ADR-003). Direct calls to the
    // public ServerChunkCache#addRegionTicket / #removeRegionTicket, remapped by
    // Loom. A by-name reflective scan cannot tell these apart on intermediary:
    // DistanceManager declares four (TicketType, ChunkPos, int, T) methods there
    // (addTicket(level), removeTicket(level), addRegionTicket(distance),
    // removeRegionTicket(distance) = method_17290 / 20444 / 17291 / 17292), and
    // the name-sorted fallback bound addTicket(level) + addRegionTicket. Every
    // ticket was then added at level 1 (a radius-32 load area) and never
    // removed, generating thousands of chunks per RTP load until the heap filled.
    // Callers are on the server thread (FabricRTPWorld dispatches via the server
    // executor).
    // -------------------------------------------------------------------------

    private static volatile TicketType<ChunkPos> RTP_TICKET_TYPE;
    private static volatile TicketType<ChunkPos> RTP_LOAD_TICKET_TYPE;

    // 2026-05-08: dropped from 3 to 1 (level 33-1 = 32) so kept-cache chunks
    // stay below ENTITY_TICKING; kept-cache locations only need block-read
    // access (RTPChunk#isSafe). See rtp-fabric-ADR-016.
    private static final int RTP_TICKET_DISTANCE = 1;

    private static TicketType<ChunkPos> ticketType() {
        TicketType<ChunkPos> t = RTP_TICKET_TYPE;
        if (t != null) return t;
        synchronized (V1_20_R1FabricVersionAdapter.class) {
            t = RTP_TICKET_TYPE;
            if (t != null) return t;
            t = TicketType.create("rtp", Comparator.comparingLong(ChunkPos::toLong), 0);
            RTP_TICKET_TYPE = t;
            return t;
        }
    }

    /** Temporary load ticket, distinct from {@link #ticketType()} (see requestFullChunkAsync). */
    private static TicketType<ChunkPos> loadTicketType() {
        TicketType<ChunkPos> t = RTP_LOAD_TICKET_TYPE;
        if (t != null) return t;
        synchronized (V1_20_R1FabricVersionAdapter.class) {
            t = RTP_LOAD_TICKET_TYPE;
            if (t != null) return t;
            t = TicketType.create("rtp_load", Comparator.comparingLong(ChunkPos::toLong), 0);
            RTP_LOAD_TICKET_TYPE = t;
            return t;
        }
    }

    @Override
    public CompletableFuture<Void> applyTicket(RTPLevelHandle level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("null ServerLevel"));
        }
        try {
            ChunkPos cp = new ChunkPos(cx, cz);
            level.as(ServerLevel.class).getChunkSource().addRegionTicket(ticketType(), cp, RTP_TICKET_DISTANCE, cp);
            return CompletableFuture.completedFuture(null);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric 1.20.1] applyTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
    }

    @Override
    public CompletableFuture<Void> releaseTicket(RTPLevelHandle level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            ChunkPos cp = new ChunkPos(cx, cz);
            level.as(ServerLevel.class).getChunkSource().removeRegionTicket(ticketType(), cp, RTP_TICKET_DISTANCE, cp);
            return CompletableFuture.completedFuture(null);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric 1.20.1] releaseTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
    }

    @Override
    public void installEffectsDispatchers() {
        // Effect dispatchers were already implemented for 1.20.1 ahead of the
        // rest of this adapter; see V1_20_R1FabricEffectDispatchers.
        V1_20_R1FabricEffectDispatchers.install();
    }

    /**
     * Typed intermediary implementation of
     * {@link io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter#extractPlayerFromConnection}.
     * Pre-26 intermediary mappings expose the player as the public field
     * {@code player} (mojmap field name preserved by Fabric intermediary even
     * when the {@code getPlayer()} method name is obfuscated to {@code method_xxxxx}).
     * No reflection.
     */
    @Override
    public Object extractPlayerFromConnection(Object handler) {
        if (!(handler instanceof ServerGamePacketListenerImpl impl)) return null;
        return impl.player;
    }

    /**
     * Implementation of {@link io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter#getPlayerUUID}.
     * Typed call to {@code ServerPlayer.getUUID()}; Loom remaps the descriptor
     * to intermediary {@code method_5667} at compile time.
     */
    @Override
    public java.util.UUID getPlayerUUID(Object player) {
        if (!(player instanceof ServerPlayer sp)) return null;
        return sp.getUUID();
    }

    /**
     * Implementation of {@link io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter#resolveSenderUuid}.
     * Typed CommandSourceStack#getEntity() + Entity#getUUID() dispatch; Loom remaps
     * the descriptors to intermediary class_2168/class_1297/method_5667 at compile time.
     * Returns null for non-player sources (console / command block); the bridge
     * interprets that as the RTPAPI.serverId sentinel.
     */
    @Override
    public java.util.UUID resolveSenderUuid(Object src) {
        if (!(src instanceof CommandSourceStack css)) return null;
        Entity entity = css.getEntity();
        if (!(entity instanceof ServerPlayer sp)) return null;
        return sp.getUUID();
    }

    /**
     * Typed override - direct {@code MinecraftServer.getCommands().performPrefixedCommand(
     * server.createCommandSourceStack(), command)}. Loom remaps the descriptors
     * to intermediary {@code class_3176#method_3734} / {@code method_3739} at
     * compile time, eliminating the reflective {@code getMethod("getCommands")}
     * lookup in {@code FabricServerAccessor.FabricConsoleSender#performCommand}
     * which fails with {@code NoSuchMethodException} on intermediary 1.20.x /
     * 1.21.x runtimes.
     */
    @Override
    public boolean dispatchConsoleCommand(Object server, String command) {
        if (!(server instanceof net.minecraft.server.MinecraftServer s) || command == null) return false;
        s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), command);
        return true;
    }

    /**
     * Typed player dispatch: on 1.20.1 {@code performPrefixedCommand} returns {@code int},
     * so the common {@code void}-linked call throws {@link NoSuchMethodError}.
     */
    @Override
    public boolean dispatchPlayerCommand(Object player, String command) {
        if (!(player instanceof ServerPlayer sp) || command == null) return false;
        net.minecraft.server.MinecraftServer s = sp.getServer();
        if (s == null) return false;
        s.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), command);
        return true;
    }

    /**
     * Per-version typed factory for the common {@code FabricRTPPlayer} wrapper.
     * Compiled against intermediary mappings via Loom so the {@code ServerPlayer}
     * cast and the {@code FabricRTPPlayer} constructor reference resolve to the
     * correct runtime descriptors. Replaces the name+arity reflective
     * {@code registerPlayer(...)} lookup that used to live in
     * {@code FabricServerAccessor.registerPlayerObject}.
     */
    @Override
    public io.github.dailystruggle.rtp.api.entity.RTPPlayer createPlayer(Object serverPlayer) {
        if (!(serverPlayer instanceof ServerPlayer sp)) return null;
        return new io.github.dailystruggle.rtp.fabric.player.FabricRTPPlayer(sp);
    }

    @Override
    public void rebindPlayer(io.github.dailystruggle.rtp.api.entity.RTPPlayer existing, Object serverPlayer) {
        if (existing instanceof io.github.dailystruggle.rtp.fabric.player.FabricRTPPlayer fp
                && serverPlayer instanceof ServerPlayer sp) {
            fp.rebind(sp);
        }
    }
}
