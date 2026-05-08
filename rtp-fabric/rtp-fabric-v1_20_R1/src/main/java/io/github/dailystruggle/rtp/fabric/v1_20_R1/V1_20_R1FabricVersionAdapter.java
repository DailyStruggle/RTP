package io.github.dailystruggle.rtp.fabric.v1_20_R1;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import io.github.dailystruggle.rtp.fabric.version.RTPBlockHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPBlockStateHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPChunkHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPRegistryKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
// 1.20.1 ChunkStatus lives at .chunk.ChunkStatus (the package move to
// .chunk.status happened in 1.21.3 — see V1_21_R1FabricVersionAdapter Javadoc).
import net.minecraft.world.level.chunk.ChunkStatus;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * MC 1.20.1 implementation of {@link FabricVersionAdapter}.
 *
 * <p>Ported from {@code V1_21_R1FabricVersionAdapter} (the reference
 * implementation per rtp-fabric-ADR-001). The ticket-management approach is
 * structural / mapping-agnostic: it discovers the {@link DistanceManager}
 * accessor and the 4-arg {@code addRegionTicket} / {@code removeRegionTicket}
 * pair reflectively, so this single body works under both Mojmap and
 * Fabric intermediary mappings on 1.20.1.</p>
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

    @Override
    public @Nullable RTPRegistryKey blockKey(RTPBlockHandle block) {
        if (block == null) return null;
        Block b = block.as(Block.class);
        if (b == null) return null;
        ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(b);
        return rl == null ? null : new RTPRegistryKey(rl.getNamespace(), rl.getPath());
    }

    @Override
    public @Nullable RTPRegistryKey biomeKeyAt(RTPLevelHandle level, int x, int y, int z) {
        if (level == null) return null;
        try {
            ServerLevel sl = level.as(ServerLevel.class);
            Holder<Biome> holder = sl.getBiome(new BlockPos(x, y, z));
            ResourceLocation rl = holder.unwrapKey().map(ResourceKey::location).orElse(null);
            return rl == null ? null : new RTPRegistryKey(rl.getNamespace(), rl.getPath());
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

    @Override
    public boolean hasChunk(RTPLevelHandle level, int cx, int cz) {
        if (level == null) return false;
        try {
            return level.as(ServerLevel.class).getChunkSource().hasChunk(cx, cz);
        } catch (Throwable t) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Non-blocking chunk-future dispatch — see rtp-fabric-ADR-008 and the
    // crash-report 2026-05-08_01.22.29-server.txt deadlock analysis.
    //
    // Resolves ServerChunkCache#getChunkFuture(int, int, ChunkStatus, boolean)
    // structurally so it works under both Mojmap and Fabric intermediary
    // mappings on 1.20.1 (and is robust to the rare yarn-shipped
    // "getChunkFutureMainThread" name). Caches the resolved Method.
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
                    if (!CompletableFuture.class.isAssignableFrom(m.getReturnType())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 4) continue;
                    if (p[0] != int.class || p[1] != int.class) continue;
                    if (p[2] != ChunkStatus.class) continue;
                    if (p[3] != boolean.class) continue;
                    m.setAccessible(true);
                    found = m;
                    break;
                }
            }
            if (found == null) {
                throw new NoSuchMethodException(
                        "ServerChunkCache#getChunkFuture(int,int,ChunkStatus,boolean) not found on "
                                + cache.getClass().getName());
            }
            GET_CHUNK_FUTURE_METHOD = found;
            return found;
        }
    }

    /**
     * Non-blocking dispatch via {@code ServerChunkCache#getChunkFuture}.
     *
     * <p>Returns a future-of-future that we flatten to a {@code RTPChunkHandle}
     * future. The inner vanilla future yields {@code Either<ChunkAccess,
     * ChunkLoadingFailure>}; we treat the Right (failure) case as {@code null}
     * so callers can route it through {@code FailTypes.nullChunk} per
     * REQ-RTP-S-004 — no silent discards.</p>
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

            // Add a temporary RTP-typed ticket on the chunk *before* requesting
            // generation. Without an explicit ticket, ServerChunkCache#getChunkFuture
            // (with create=true) allocates a chunk holder at a level too high
            // (>33) for the chunk system to drive generation through to FULL —
            // the future then completes with Either.right(ChunkLoadingFailure)
            // and we'd unwrap that to null, manifesting as the
            // nullChunk/asyncLoadNull burst seen on 1.20.1+C2ME runs.
            //
            // The ticket is paired with a removeRegionTicket in whenComplete so
            // it is alive only for the load itself; the caller's later
            // setForceLoaded (when keeping the chunk) is a separate, longer-lived
            // ticket of the same type — vanilla DistanceManager handles
            // overlapping tickets of the same TicketType correctly.
            ChunkPos cp = new ChunkPos(cx, cz);
            boolean ticketAdded = false;
            try {
                resolveTicketMethodsOnce(cache);
                Object dm = distanceManager(cache);
                ADD_TICKET_METHOD.invoke(dm, ticketType(), cp, RTP_TICKET_DISTANCE, cp);
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

            Object raw = getter.invoke(cache, cx, cz, ChunkStatus.FULL, /*create=*/ true);
            if (!(raw instanceof CompletableFuture<?> cf)) {
                if (addedTicket) tryRemoveLoadTicket(cache, cp);
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "getChunkFuture returned non-CompletableFuture: " + (raw == null ? "null" : raw.getClass())));
            }
            // cf is CompletableFuture<Either<ChunkAccess, ChunkLoadingFailure>>;
            // unwrap reflectively to avoid hard-binding to Mojang's Either type.
            return cf.thenApply(either -> {
                if (either == null) return null;
                ChunkAccess chunk = unwrapEitherLeft(either);
                return chunk == null ? null : RTPChunkHandle.of(chunk);
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
     * propagated — the load itself has already produced its outcome and
     * the caller's pipeline must not be re-failed by ticket cleanup.
     */
    private static void tryRemoveLoadTicket(ServerChunkCache cache, ChunkPos cp) {
        try {
            Object dm = distanceManager(cache);
            REMOVE_TICKET_METHOD.invoke(dm, ticketType(), cp, RTP_TICKET_DISTANCE, cp);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric 1.20.1] temp load-ticket release failed for chunk=("
                            + cp.x + "," + cp.z + "): " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Unwrap {@code com.mojang.datafixers.util.Either<L,R>} via reflection.
     * Returns {@code left().orElse(null)} if {@code left} is a {@link ChunkAccess},
     * otherwise {@code null} (treats the Right "failure" branch as a no-chunk
     * outcome — see {@link #requestFullChunkAsync} Javadoc).
     */
    private static ChunkAccess unwrapEitherLeft(Object either) {
        try {
            Method leftMethod = either.getClass().getMethod("left");
            Object opt = leftMethod.invoke(either);
            if (opt == null) return null;
            // Optional<L>; orElse(null) gives us the ChunkAccess or null.
            Method orElse = opt.getClass().getMethod("orElse", Object.class);
            Object value = orElse.invoke(opt, (Object) null);
            return (value instanceof ChunkAccess ca) ? ca : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public RTPBlockStateHandle airState() {
        return RTPBlockStateHandle.of(Blocks.AIR.defaultBlockState());
    }

    // -------------------------------------------------------------------------
    // Non-persistent chunk-ticket support — see V1_21_R1FabricVersionAdapter
    // Javadoc for the design rationale (rtp-fabric-ADR-006). Approach is
    // identical here; DistanceManager#addRegionTicket(TicketType, ChunkPos,
    // int distance, T value) is structurally identical on 1.20.1.
    // -------------------------------------------------------------------------

    private static volatile TicketType<ChunkPos> RTP_TICKET_TYPE;

    private static volatile Method ADD_TICKET_METHOD;
    private static volatile Method REMOVE_TICKET_METHOD;
    private static volatile Method GET_DISTANCE_MANAGER_METHOD;
    private static volatile Field GET_DISTANCE_MANAGER_FIELD;

    private static final int RTP_TICKET_DISTANCE = 3;

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

    private static Method resolveDistanceManagerGetter(ServerChunkCache cache) throws ReflectiveOperationException {
        for (Class<?> c = cache.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                Class<?> rt = m.getReturnType();
                if (DistanceManager.class.isAssignableFrom(rt)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        for (Class<?> c = cache.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (DistanceManager.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    GET_DISTANCE_MANAGER_FIELD = f;
                    return null;
                }
            }
        }
        throw new NoSuchMethodException(
                "No no-arg method or field of type DistanceManager found on " + cache.getClass().getName());
    }

    private static void resolveTicketMethodsOnce(ServerChunkCache cache) throws ReflectiveOperationException {
        if (ADD_TICKET_METHOD != null && REMOVE_TICKET_METHOD != null
                && (GET_DISTANCE_MANAGER_METHOD != null || GET_DISTANCE_MANAGER_FIELD != null)) return;
        synchronized (V1_20_R1FabricVersionAdapter.class) {
            if (ADD_TICKET_METHOD != null && REMOVE_TICKET_METHOD != null
                    && (GET_DISTANCE_MANAGER_METHOD != null || GET_DISTANCE_MANAGER_FIELD != null)) return;
            Method getter = resolveDistanceManagerGetter(cache);
            Object dm = (getter != null) ? getter.invoke(cache) : GET_DISTANCE_MANAGER_FIELD.get(cache);
            if (dm == null) {
                throw new IllegalStateException(
                        "ServerChunkCache distance-manager getter returned null on " + cache.getClass().getName());
            }
            Class<?> dmClass = dm.getClass();
            Method add = null;
            Method remove = null;
            for (Class<?> c = dmClass; c != null && (add == null || remove == null); c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getReturnType() != void.class) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 4) continue;
                    if (p[0] != TicketType.class) continue;
                    if (p[1] != ChunkPos.class) continue;
                    if (p[2] != int.class) continue;
                    if (p[3].isPrimitive()) continue;
                    String n = m.getName();
                    boolean isAdd = n.contains("add") || n.contains("Add");
                    boolean isRemove = n.contains("remove") || n.contains("Remove");
                    if (add == null && isAdd && !isRemove) {
                        m.setAccessible(true);
                        add = m;
                    } else if (remove == null && isRemove && !isAdd) {
                        m.setAccessible(true);
                        remove = m;
                    }
                }
            }
            if (add == null || remove == null) {
                java.util.List<Method> candidates = new java.util.ArrayList<>();
                for (Class<?> c = dmClass; c != null; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getReturnType() != void.class) continue;
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length != 4) continue;
                        if (p[0] != TicketType.class) continue;
                        if (p[1] != ChunkPos.class) continue;
                        if (p[2] != int.class) continue;
                        if (p[3].isPrimitive()) continue;
                        candidates.add(m);
                    }
                    if (candidates.size() >= 2) break;
                }
                if (candidates.size() >= 2) {
                    candidates.sort(Comparator.comparing(Method::getName));
                    Method a = candidates.get(0);
                    Method b = candidates.get(1);
                    a.setAccessible(true);
                    b.setAccessible(true);
                    if (add == null) add = a;
                    if (remove == null) remove = b;
                }
            }
            if (add == null || remove == null) {
                java.util.List<Method> loose = new java.util.ArrayList<>();
                for (Class<?> c = dmClass; c != null; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getReturnType() != void.class) continue;
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length != 4) continue;
                        if (p[2] != int.class) continue;
                        if (p[0].isPrimitive() || p[1].isPrimitive() || p[3].isPrimitive()) continue;
                        loose.add(m);
                    }
                    if (loose.size() >= 2) break;
                }
                if (loose.size() >= 2) {
                    loose.sort(Comparator.comparing(Method::getName));
                    Method a = loose.get(0);
                    Method b = loose.get(1);
                    a.setAccessible(true);
                    b.setAccessible(true);
                    if (add == null) add = a;
                    if (remove == null) remove = b;
                }
            }
            if (add == null || remove == null) {
                StringBuilder sb = new StringBuilder();
                sb.append("DistanceManager#addRegionTicket / #removeRegionTicket not found on ")
                  .append(dmClass.getName()).append(" (or any superclass). Declared methods:\n");
                for (Class<?> c = dmClass; c != null && c != Object.class; c = c.getSuperclass()) {
                    sb.append("  -- ").append(c.getName()).append(" --\n");
                    for (Method m : c.getDeclaredMethods()) {
                        sb.append("    ").append(m.getReturnType().getSimpleName()).append(' ')
                          .append(m.getName()).append('(');
                        Class<?>[] p = m.getParameterTypes();
                        for (int i = 0; i < p.length; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(p[i].getSimpleName());
                        }
                        sb.append(")\n");
                    }
                }
                RTP.log(Level.WARNING, sb.toString());
                throw new NoSuchMethodException(
                        "DistanceManager#addRegionTicket / #removeRegionTicket not found on "
                                + dmClass.getName() + " (or any superclass).");
            }
            if (getter != null) GET_DISTANCE_MANAGER_METHOD = getter;
            ADD_TICKET_METHOD = add;
            REMOVE_TICKET_METHOD = remove;
            // One-shot diagnostic so the resolved binding is visible in the
            // server log; required to triage AIOOBE crashes inside vanilla
            // class_8257 (DistanceManager LongLinkedOpenHashSet rehash) on
            // 1.20.1, where the structural matcher could in principle bind
            // a sibling 4-arg method with subtly different semantics.
            RTP.log(Level.INFO,
                    "[RTP][Fabric 1.20.1] DistanceManager ticket methods bound: add="
                            + add.getDeclaringClass().getName() + "#" + add.getName()
                            + ", remove=" + remove.getDeclaringClass().getName()
                            + "#" + remove.getName());
        }
    }

    private static Object distanceManager(ServerChunkCache cache) throws ReflectiveOperationException {
        Method m = GET_DISTANCE_MANAGER_METHOD;
        if (m != null) return m.invoke(cache);
        Field f = GET_DISTANCE_MANAGER_FIELD;
        if (f != null) return f.get(cache);
        m = resolveDistanceManagerGetter(cache);
        if (m != null) {
            GET_DISTANCE_MANAGER_METHOD = m;
            return m.invoke(cache);
        }
        return GET_DISTANCE_MANAGER_FIELD.get(cache);
    }

    @Override
    public CompletableFuture<Void> applyTicket(RTPLevelHandle level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("null ServerLevel"));
        }
        try {
            ServerLevel sl = level.as(ServerLevel.class);
            ServerChunkCache cache = sl.getChunkSource();
            resolveTicketMethodsOnce(cache);
            Object dm = distanceManager(cache);
            ADD_TICKET_METHOD.invoke(dm, ticketType(), new ChunkPos(cx, cz), RTP_TICKET_DISTANCE, new ChunkPos(cx, cz));
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
            ServerLevel sl = level.as(ServerLevel.class);
            ServerChunkCache cache = sl.getChunkSource();
            resolveTicketMethodsOnce(cache);
            Object dm = distanceManager(cache);
            REMOVE_TICKET_METHOD.invoke(dm, ticketType(), new ChunkPos(cx, cz), RTP_TICKET_DISTANCE, new ChunkPos(cx, cz));
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
}
