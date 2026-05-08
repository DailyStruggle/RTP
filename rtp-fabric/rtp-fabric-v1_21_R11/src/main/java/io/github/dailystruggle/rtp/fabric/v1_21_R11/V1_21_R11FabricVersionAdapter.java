package io.github.dailystruggle.rtp.fabric.v1_21_R11;

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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
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

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * MC 1.21.11+ implementation of {@link FabricVersionAdapter} — covers the
 * post-{@code TicketUse} refactor. See {@code rtp-fabric-ADR-007}.
 *
 * <p><b>Why a separate module from {@code v1_21_R5}:</b> Mojang refactored
 * {@code TicketType} again in 1.21.11. The previous record shape
 * {@code TicketType(long timeout, boolean persist, TicketUse use)} (1.21.5)
 * was replaced by {@code TicketType(long timeout, int flags)} where
 * {@code flags} is a bitfield over five named constants (Mojmap, verified
 * via {@code javap} on the Loom-cached 1.21.11 merged jar):
 * {@code FLAG_PERSIST} (0x1), {@code FLAG_LOADING} (0x2),
 * {@code FLAG_SIMULATION} (0x4), {@code FLAG_KEEP_DIMENSION_ACTIVE} (0x8),
 * and {@code FLAG_CAN_EXPIRE_IF_UNLOADED} (0x10). The inner enum
 * {@code TicketType.TicketUse} no longer exists, so
 * the R5 adapter's bytecode (which references it at {@code <clinit>}) fails
 * to load on a 1.21.11 runtime with {@code NoClassDefFoundError:
 * net/minecraft/class_3230$class_10558}.</p>
 *
 * <p><b>Mojmap-name decoupling (ADR-007):</b> the SPI is now wrapper-typed
 * — {@code RTPLevelHandle}, {@code RTPBlockHandle}, etc. — so 1.21.11's
 * {@code ResourceLocation → Identifier} rename does not affect the
 * interface. This adapter unwraps via {@code handle.as(MojmapType.class)}
 * on entry and wraps results on exit.</p>
 *
 * <p><b>Implementation:</b> direct typed Mojang-mappings calls — no
 * reflection — using {@link ServerChunkCache#addTicketWithRadius} /
 * {@link ServerChunkCache#removeTicketWithRadius} with an RTP-owned
 * {@link TicketType} constructed as
 * {@code new TicketType(NO_TIMEOUT, FLAG_LOADING | FLAG_SIMULATION)}.
 * That flag set ({@code = 6}) is identical to vanilla
 * {@link TicketType#FORCED}'s flags <b>minus</b> {@code FLAG_PERSIST} and
 * {@code FLAG_KEEP_DIMENSION_ACTIVE}, yielding a non-persistent (S-002
 * safe), no-expiry, fully-ticking ticket.
 * Radius {@code 3} resolves to effective ticket level {@code 33 - 3 = 30}
 * ({@code ENTITY_TICKING}) — parity with Bukkit's
 * {@code addPluginChunkTicket} on the Bukkit-family adapters. See
 * {@code rtp-fabric-ADR-006-ticket-radius-and-non-expiring-type.md}.</p>
 *
 * <p><b>S-002 / non-persistent guarantee:</b> the {@code FLAG_PERSIST} bit
 * is deliberately omitted from {@link #RTP_TICKET_FLAGS} so the ticket
 * lives only for the JVM lifetime, never written into {@code level.dat}.</p>
 */
public final class V1_21_R11FabricVersionAdapter implements FabricVersionAdapter {

    private static final int RTP_TICKET_RADIUS = 3;

    // 1.21.11 Mojmap: TicketType(long timeout, int flags) where flags is a bitfield over
    // FLAG_PERSIST | FLAG_LOADING | FLAG_SIMULATION | FLAG_KEEP_DIMENSION_ACTIVE | FLAG_CAN_EXPIRE_IF_UNLOADED.
    // Omit FLAG_PERSIST for S-002 (non-persistent: never written to level.dat).
    private static final int RTP_TICKET_FLAGS =
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION;

    private static final TicketType RTP_TICKET_TYPE =
            new TicketType(TicketType.NO_TIMEOUT, RTP_TICKET_FLAGS);

    @Override
    public String mcVersion() {
        return "1.21.11+";
    }

    @Override
    public @Nullable RTPRegistryKey blockKey(RTPBlockHandle block) {
        if (block == null) return null;
        Block b = block.as(Block.class);
        if (b == null) return null;
        Identifier id = BuiltInRegistries.BLOCK.getKey(b);
        return id == null ? null : new RTPRegistryKey(id.getNamespace(), id.getPath());
    }

    @Override
    public @Nullable RTPRegistryKey biomeKeyAt(RTPLevelHandle level, int x, int y, int z) {
        if (level == null) return null;
        try {
            ServerLevel sl = level.as(ServerLevel.class);
            Holder<Biome> holder = sl.getBiome(new BlockPos(x, y, z));
            Identifier id = holder.unwrapKey().map(ResourceKey::identifier).orElse(null);
            return id == null ? null : new RTPRegistryKey(id.getNamespace(), id.getPath());
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
        synchronized (V1_21_R11FabricVersionAdapter.class) {
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
            // for the rationale.
            ChunkPos cp = new ChunkPos(cx, cz);
            boolean ticketAdded = false;
            try {
                cache.addTicketWithRadius(RTP_TICKET_TYPE, cp, RTP_TICKET_RADIUS);
                ticketAdded = true;
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP][Fabric 1.21.11+] temp load-ticket apply failed for chunk=("
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
                    "[RTP][Fabric 1.21.11+] temp load-ticket release failed for chunk=("
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
                    "[RTP][Fabric 1.21.11+] applyTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
    }

    @Override
    public void installEffectsDispatchers() {
        // Direct, mapped vanilla calls — see V1_21_R11FabricEffectDispatchers
        // for rationale (Holder vs Holder.direct on 1.21.11; targeted
        // sendParticles overload for chunk-tracker bypass post-teleport).
        V1_21_R11FabricEffectDispatchers.install();
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
                    "[RTP][Fabric 1.21.11+] releaseTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
    }
}
