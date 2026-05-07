package io.github.dailystruggle.rtp.fabric.v1_21_R1;

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
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * MC 1.21.0–1.21.4 implementation of {@link FabricVersionAdapter} — the reference
 * implementation per rtp-fabric-ADR-001.
 *
 * <p><b>Scope:</b> this adapter targets the {@code DistanceManager} API as
 * it existed before the 1.21.5 refactor — i.e. the 4-arg
 * {@code addRegionTicket(TicketType, ChunkPos, int, T)} /
 * {@code removeRegionTicket(...)} pair. From 1.21.5 onward Mojang replaced
 * that pair with {@code addTicket(long, Ticket)} on a value-object
 * {@code Ticket}; routing for 1.21.5+ goes to
 * {@code v1_21_R5} instead — see {@code rtp-fabric-ADR-004}.</p>
 *
 * <p>v1_20_R1 and v26_1_R1 will port from this class. Notable per-version
 * concerns this implementation captures:</p>
 * <ul>
 *   <li>{@link ChunkStatus} is at {@code net.minecraft.world.level.chunk.ChunkStatus}
 *       on 1.21.1; the package move to {@code .chunk.status} happens in 1.21.3.</li>
 *   <li>Biome registry access uses {@link BuiltInRegistries#BIOME} via the
 *       {@link Holder} produced by {@code level.getBiome(pos)}.</li>
 *   <li>Block-id lookup goes through {@link BuiltInRegistries#BLOCK}; the
 *       {@code Registries} vs. {@code BuiltInRegistries} split is stable on
 *       1.21.x.</li>
 * </ul>
 */
public final class V1_21_R1FabricVersionAdapter implements FabricVersionAdapter {

    @Override
    public String mcVersion() {
        return "1.21.1";
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

    @Override
    public RTPBlockStateHandle airState() {
        return RTPBlockStateHandle.of(Blocks.AIR.defaultBlockState());
    }

    // -------------------------------------------------------------------------
    // Non-persistent chunk-ticket support — see FabricVersionAdapter Javadoc.
    //
    // We allocate a process-wide non-persistent TicketType ("rtp") and hand
    // it to DistanceManager#addRegionTicket. {@code TicketType.create(name,
    // comparator, /*timeout*/ 0)} produces a non-persistent type (the
    // PERSISTENT set in TicketType is opt-in via the deprecated
    // {@code #createPersistent} factory; the public {@code #create} factory
    // never marks the type as such). Timeout 0 means "no auto-expiry —
    // lives until removeRegionTicket is called", matching Bukkit's
    // addPluginChunkTicket lifetime contract.
    //
    // {@code DistanceManager#addRegionTicket} / {@code #removeRegionTicket}
    // are package-private on Mojmap 1.21.1; we resolve them reflectively at
    // first use (one-shot, cached) rather than authoring an access-widener.
    // The reflection target is stable across 1.21.x patch releases.
    // -------------------------------------------------------------------------

    /**
     * Process-wide non-persistent ticket type. Created lazily because
     * {@link TicketType#create} is a registry call that we want to avoid
     * during static class init in a test JVM that may not have the chunk
     * subsystem initialised. The comparator orders by packed long position
     * (matches {@code TicketType.FORCED}'s ordering); timeout 0 = no expiry.
     */
    private static volatile TicketType<ChunkPos> RTP_TICKET_TYPE;

    /**
     * Cached reflective handles for {@code DistanceManager#addRegionTicket}
     * and {@code #removeRegionTicket}. Resolved on first use; volatile so
     * worker threads observe a fully-published handle. Both signatures are
     * {@code (TicketType, ChunkPos, int level, T value)}.
     */
    private static volatile Method ADD_TICKET_METHOD;
    private static volatile Method REMOVE_TICKET_METHOD;
    private static volatile Method GET_DISTANCE_MANAGER_METHOD;
    /**
     * Field fallback for the distance-manager accessor. Under Fabric
     * intermediary mappings (runtime), {@code ServerChunkCache} (a.k.a.
     * {@code class_3215}) exposes its {@code DistanceManager} as a field
     * (e.g. {@code field_17252}) rather than a no-arg method. We scan
     * declared fields whose type is assignable to {@link DistanceManager}
     * if the method scan turns up nothing.
     */
    private static volatile Field GET_DISTANCE_MANAGER_FIELD;

    /**
     * Ticket <em>distance</em> (NOT level) we hand to
     * {@code DistanceManager#addRegionTicket(TicketType, ChunkPos, int distance, T value)}.
     *
     * <p>Vanilla translates this to an effective ticket level via
     * {@code effectiveLevel = ChunkMap.MAX_CHUNK_DISTANCE - distance}
     * (i.e. {@code 33 - distance}). For a chunk to reach status {@code FULL}
     * (the threshold at which {@code ServerChunkCache#hasChunk} returns true
     * and block reads are valid), {@code effectiveLevel} must be {@code <= 33};
     * for {@code TICKING} {@code <= 32}; for {@code ENTITY_TICKING} {@code <= 31}.</p>
     *
     * <p>{@code distance = 3} matches {@code TicketType.FORCED}'s built-in
     * distance and resolves to effective level {@code 30} = {@code ENTITY_TICKING},
     * the same end state Bukkit's {@code World#addPluginChunkTicket} produces.
     * Earlier revisions of this adapter passed {@code 31} into this slot under
     * the mistaken belief it was the ticket level — that yielded effective
     * level {@code 2} which the chunk system clamps/rejects, leaving kept-cache
     * entries unpinned and silently evicted. See
     * {@code rtp-fabric-ADR-006-ticket-radius-and-non-expiring-type.md}.</p>
     */
    private static final int RTP_TICKET_DISTANCE = 3;

    private static TicketType<ChunkPos> ticketType() {
        TicketType<ChunkPos> t = RTP_TICKET_TYPE;
        if (t != null) return t;
        synchronized (V1_21_R1FabricVersionAdapter.class) {
            t = RTP_TICKET_TYPE;
            if (t != null) return t;
            // create(name, comparator, timeout) — non-persistent variant.
            t = TicketType.create("rtp", Comparator.comparingLong(ChunkPos::toLong), 0);
            RTP_TICKET_TYPE = t;
            return t;
        }
    }

    /**
     * Resolve a structural accessor (method or field) for the
     * {@link DistanceManager} on the given {@link ServerChunkCache}. Returns
     * {@code null} when only a field accessor was found — in that case
     * {@link #GET_DISTANCE_MANAGER_FIELD} is populated as a side effect.
     */
    private static Method resolveDistanceManagerGetter(ServerChunkCache cache) throws ReflectiveOperationException {
        // Method scan first: Mojmap `getDistanceManager()`, yarn `getTicketManager()`,
        // intermediary `method_17293()`, etc.
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
        // Field fallback: under intermediary the accessor is typically the field
        // (e.g. `field_17252`) rather than a getter method.
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
        synchronized (V1_21_R1FabricVersionAdapter.class) {
            if (ADD_TICKET_METHOD != null && REMOVE_TICKET_METHOD != null
                    && (GET_DISTANCE_MANAGER_METHOD != null || GET_DISTANCE_MANAGER_FIELD != null)) return;
            Method getter = resolveDistanceManagerGetter(cache);
            Object dm = (getter != null) ? getter.invoke(cache) : GET_DISTANCE_MANAGER_FIELD.get(cache);
            if (dm == null) {
                throw new IllegalStateException(
                        "ServerChunkCache distance-manager getter returned null on " + cache.getClass().getName());
            }
            // DistanceManager methods are package-private; resolve by signature so this works
            // under both Mojang and intermediary mappings (where names like `addRegionTicket`
            // become e.g. `method_17290`).
            Class<?> dmClass = dm.getClass();
            // Signature-based scan. The 4th parameter is generic `T` which erases to Object,
            // but some toolchains may surface it as the bound type — accept any reference type.
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
            // Fallback: if name-based discrimination failed (e.g. obfuscated names like
            // method_17290 / method_17291), pick the two matching-signature methods in
            // declaration order — addRegionTicket is declared before removeRegionTicket
            // in DistanceManager on 1.21.1.
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
            // Final fallback: relax parameter-type checks. Accept any 4-arg void
            // method declared on DistanceManager (or a superclass) where param[2]
            // is `int` and the other three are reference types. This survives
            // remapped/reloaded TicketType/ChunkPos classes — at this point we
            // trust the structural shape because DistanceManager only declares
            // two such methods (the add/remove pair).
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
                // Diagnostic: dump every declared method on dmClass + superclasses so
                // the next run reveals the actual runtime signatures. One-shot;
                // resolveTicketMethodsOnce won't be re-entered after success.
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
                    "[RTP][Fabric 1.21.1] applyTicket failed for chunk=(" + cx + "," + cz + "): "
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
                    "[RTP][Fabric 1.21.1] releaseTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
    }
}
