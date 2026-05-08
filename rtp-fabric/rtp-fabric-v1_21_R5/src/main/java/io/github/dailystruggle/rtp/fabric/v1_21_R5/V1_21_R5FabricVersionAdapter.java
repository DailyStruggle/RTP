package io.github.dailystruggle.rtp.fabric.v1_21_R5;

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
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.TicketType.TicketUse;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * MC 1.21.5+ implementation of {@link FabricVersionAdapter} — covers the
 * post-refactor {@code DistanceManager}/{@code TicketStorage} API range
 * (1.21.5 through the next breaking change). See {@code rtp-fabric-ADR-004}.
 *
 * <p><b>Why a separate module from {@code v1_21_R1}:</b> Mojang refactored
 * the chunk-ticket API in 1.21.5. {@code DistanceManager#addRegionTicket}
 * (and the matching {@code removeRegionTicket}) was removed; the public
 * entry points now live on {@link ServerChunkCache} itself:
 * {@code addTicketWithRadius(TicketType, ChunkPos, int)} and
 * {@code removeTicketWithRadius(TicketType, ChunkPos, int)}. The old
 * adapter cannot bridge that on a 1.21.5+ runtime because the old methods
 * literally do not exist.</p>
 *
 * <p><b>Implementation:</b> direct typed Mojang-mappings calls — no
 * reflection — using {@code addTicketWithRadius} / {@code removeTicketWithRadius}
 * with an RTP-owned {@link TicketType} ({@code timeout = NO_TIMEOUT},
 * {@code persist = false}, {@code use = LOADING_AND_SIMULATION} — the same
 * shape as vanilla {@code FORCED}, minus the {@code persist} flag) and a
 * radius of {@code 3}, which yields effective ticket level
 * {@code 33 - 3 = 30} = {@code ENTITY_TICKING} — parity with Bukkit's
 * {@code addPluginChunkTicket} and with {@code TicketType.FORCED}. Because
 * explicit removal is supported and the type carries no auto-expiry, no
 * periodic refresh is needed; the {@link #tickRefresh()} SPI hook stays at
 * the interface default no-op. See
 * {@code rtp-fabric-ADR-006-ticket-radius-and-non-expiring-type.md} for the
 * radius-correctness and non-expiring-type rationale.</p>
 *
 * <p><b>Why not {@code TicketType.UNKNOWN}:</b> in 1.21.5+ {@code TicketType}
 * is a record {@code (long timeout, boolean persist, TicketUse use)} and the
 * vanilla {@code UNKNOWN} constant is registered with {@code timeout = 1L}
 * and {@code use = LOADING} only. A 1-tick auto-expiry would silently evict
 * kept-cache chunks every tick (the original symptom this ADR addresses),
 * and {@code LOADING}-only tickets do not enable entity ticking. Both are
 * wrong for the kept-cache use case.</p>
 *
 * <p><b>S-002 / non-persistent guarantee:</b> we deliberately do not call
 * {@link ServerChunkCache#updateChunkForced} (which persists into
 * {@code level.dat}). The radius-based ticket created here lives only for
 * the JVM lifetime — same contract as Bukkit's {@code addPluginChunkTicket}
 * and the v1_21_R1 adapter's behaviour.</p>
 */
public final class V1_21_R5FabricVersionAdapter implements FabricVersionAdapter {

    /**
     * Ticket <em>radius</em> (in chunks) we hand to
     * {@code ServerChunkCache#addTicketWithRadius(TicketType, ChunkPos, int radius)}.
     *
     * <p>Vanilla translates this to an effective ticket level via
     * {@code effectiveLevel = ChunkMap.MAX_CHUNK_DISTANCE - radius} (i.e.
     * {@code 33 - radius}). {@code radius = 3} resolves to effective level
     * {@code 30} = {@code ENTITY_TICKING}, the same end state Bukkit's
     * {@code World#addPluginChunkTicket} produces and the same value
     * {@code TicketType.FORCED} uses.</p>
     *
     * <p>Earlier revisions of this adapter passed {@code 31} as the radius
     * under the mistaken belief it was a ticket level — that would have
     * force-loaded a {@code (2*31+1)² = 3969}-chunk square per kept
     * location, which the chunk system clamps/rejects, leaving kept-cache
     * entries unpinned and silently evicted. See
     * {@code rtp-fabric-ADR-006-ticket-radius-and-non-expiring-type.md}.</p>
     */
    private static final int RTP_TICKET_RADIUS = 3;

    /**
     * RTP-owned non-persistent, no-timeout {@link TicketType} used for both
     * {@code addTicketWithRadius} and the matching {@code removeTicketWithRadius}
     * call. Equivalent to {@code TicketType.FORCED}'s shape minus the
     * {@code persist = true} flag — i.e.
     * {@code (timeout = NO_TIMEOUT, persist = false, use = LOADING_AND_SIMULATION)}.
     *
     * <p>The public record constructor is sufficient; no registry call (and
     * therefore no class-init ordering hazard against the chunk subsystem)
     * is required. Identity equality is what {@code addTicketWithRadius} and
     * {@code removeTicketWithRadius} compare on, and a single static instance
     * is reused for every call from this adapter, so add/remove will pair
     * cleanly.</p>
     *
     * <p>This deliberately replaces an earlier use of {@link TicketType#UNKNOWN},
     * whose vanilla registration has {@code timeout = 1L} (1-tick auto-expiry)
     * and {@code use = LOADING} only — both wrong for kept-cache pinning. See
     * {@code rtp-fabric-ADR-006-ticket-radius-and-non-expiring-type.md}.</p>
     */
    private static final TicketType RTP_TICKET_TYPE =
            new TicketType(TicketType.NO_TIMEOUT, /*persist=*/ false, TicketUse.LOADING_AND_SIMULATION);

    @Override
    public String mcVersion() {
        return "1.21.5+";
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

    // Non-blocking chunk-future dispatch — see rtp-fabric-ADR-008.
    private static volatile Method GET_CHUNK_FUTURE_METHOD;

    private static Method resolveGetChunkFutureMethod(ServerChunkCache cache) throws ReflectiveOperationException {
        Method cached = GET_CHUNK_FUTURE_METHOD;
        if (cached != null) return cached;
        synchronized (V1_21_R5FabricVersionAdapter.class) {
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

    @Override
    public CompletableFuture<RTPChunkHandle> requestFullChunkAsync(RTPLevelHandle level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("null ServerLevel"));
        }
        try {
            ServerLevel sl = level.as(ServerLevel.class);
            ServerChunkCache cache = sl.getChunkSource();
            Method getter = resolveGetChunkFutureMethod(cache);

            // Temporary load-ticket — see V1_20_R1FabricVersionAdapter#requestFullChunkAsync
            // for the rationale. 1.21.5 uses the public addTicketWithRadius /
            // removeTicketWithRadius API.
            ChunkPos cp = new ChunkPos(cx, cz);
            boolean ticketAdded = false;
            try {
                cache.addTicketWithRadius(RTP_TICKET_TYPE, cp, RTP_TICKET_RADIUS);
                ticketAdded = true;
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP][Fabric 1.21.5+] temp load-ticket apply failed for chunk=("
                                + cx + "," + cz + "): " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            final boolean addedTicket = ticketAdded;

            Object raw = getter.invoke(cache, cx, cz, ChunkStatus.FULL, /*create=*/ true);
            if (!(raw instanceof CompletableFuture<?> cf)) {
                if (addedTicket) tryRemoveLoadTicket(cache, cp);
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "getChunkFuture returned non-CompletableFuture: " + (raw == null ? "null" : raw.getClass())));
            }
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

    private static void tryRemoveLoadTicket(ServerChunkCache cache, ChunkPos cp) {
        try {
            cache.removeTicketWithRadius(RTP_TICKET_TYPE, cp, RTP_TICKET_RADIUS);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric 1.21.5+] temp load-ticket release failed for chunk=("
                            + cp.x + "," + cp.z + "): " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static ChunkAccess unwrapEitherLeft(Object either) {
        try {
            Method leftMethod = either.getClass().getMethod("left");
            Object opt = leftMethod.invoke(either);
            if (opt == null) return null;
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
                    "[RTP][Fabric 1.21.5+] applyTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
    }

    @Override
    public void installEffectsDispatchers() {
        V1_21_R5FabricEffectDispatchers.install();
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
                    "[RTP][Fabric 1.21.5+] releaseTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
    }
}
