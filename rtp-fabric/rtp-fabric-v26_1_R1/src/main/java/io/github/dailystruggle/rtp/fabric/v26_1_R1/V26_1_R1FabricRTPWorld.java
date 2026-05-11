package io.github.dailystruggle.rtp.fabric.v26_1_R1;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * MC 26.1.2 RTPWorld wrapper.
 *
 * <p>Lives in {@code rtp-fabric-v26_1_R1} (Loom unobfuscated, no intermediary
 * remapping) so its compiled bytecode references {@code net.minecraft.server.level.ServerLevel}
 * and friends by Mojang names — which is the actual class layout on a deobfuscated
 * 26.1.2 runtime. Common's {@code FabricRTPWorld} cannot link there because its
 * constant pool contains intermediary aliases ({@code class_3218} etc.) that
 * don't exist on that runtime.
 *
 * <p>Live-mode chunk-system implementation only — no anvil-prefilter integration
 * yet (tracked in {@code docs/dev/scratch/CHECKLIST-fabric-26-1-2-bringup.md}).
 */
public final class V26_1_R1FabricRTPWorld extends RTPWorld<ServerLevel> {

    private final String name;
    private final UUID id;

    /** Cache of recently-loaded chunks keyed by {@code (cz << 32) | cx}. */
    private final ConcurrentHashMap<Long, V26_1_R1FabricRTPChunk> chunkCache = new ConcurrentHashMap<>();

    public V26_1_R1FabricRTPWorld(ServerLevel level) {
        super(level);
        this.name = resolveDimensionName(level);
        // Stable UUID derived from the dimension name (matches FabricRTPWorld convention).
        this.id = UUID.nameUUIDFromBytes(("rtp-fabric-world:" + this.name).getBytes());
    }

    private static String resolveDimensionName(ServerLevel level) {
        // On MC 26.1.2 (verified via javap on the deobf jar) Level exposes its
        // ResourceKey<Level> via dimension(); ResourceKey was renamed
        // location() -> identifier() on this MC release.
        try {
            return level.dimension().identifier().toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override public String name() { return name; }
    @Override public UUID id() { return id; }

    private static long key(int cx, int cz) {
        return ((long) cx & 0xffffffffL) | ((long) cz << 32);
    }

    /**
     * Loads {@code (cx, cz)} at {@code ChunkStatus.FULL} via the server tick
     * thread (S-005-safe: dispatched through {@link MinecraftServer#submit}),
     * caches a {@link V26_1_R1FabricRTPChunk} wrapper and resolves the future
     * with the chunk key.
     */
    @Override
    public CompletableFuture<Long> getChunkAt(int cx, int cz) {
        ServerLevel level = world;
        if (level == null) return CompletableFuture.completedFuture(null);
        MinecraftServer server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);
        long k = key(cx, cz);
        // Cache hit short-circuits — keeps getOrLoadChunk's cached path cheap.
        if (chunkCache.containsKey(k)) {
            return CompletableFuture.completedFuture(k);
        }
        return server.submit(() -> {
            try {
                ChunkAccess ca = level.getChunk(cx, cz, ChunkStatus.FULL, true);
                if (ca == null) return null;
                chunkCache.put(k, new V26_1_R1FabricRTPChunk(ca, level, id));
                return k;
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP][v26_1_R1] getChunkAt(" + cx + "," + cz + ") failed for "
                                + name + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
                return null;
            }
        });
    }

    /**
     * Single-chunk {@link ChunkSet} mirroring common's {@code FabricRTPWorld#getChunkAtAsync}.
     * The {@code done} future is completed on the same callback so consumers
     * attaching via {@code thenCombine}/{@code allOf} are released and their
     * dependent CF graph becomes GC-eligible.
     */
    @Override
    public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
        return getChunkAt(cx, cz).thenApply(k -> {
            CompletableFuture<Boolean> done = new CompletableFuture<>();
            ChunkSet set = new ChunkSet(this, cx, cz,
                    Collections.singletonList(CompletableFuture.completedFuture(k)),
                    done);
            done.complete(k != null);
            return set;
        });
    }

    @Override
    public boolean isChunkLoaded(int cx, int cz) {
        try {
            ServerChunkCache cache = world.getChunkSource();
            return cache != null && cache.hasChunk(cx, cz);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Apply or release a non-persistent RTP-owned chunk ticket via the
     * version adapter (see {@link V26_1_R1FabricVersionAdapter#applyTicket}).
     *
     * <p>Replaces the previous {@code level.setChunkForced(cx, cz, forceLoad)}
     * call, which writes through to {@code level.dat#ForcedChunks} and would
     * persist across an unclean shutdown (S-002 hazard). Tickets issued via
     * {@code DistanceManager.addTicketWithRadius} live only for the JVM
     * lifetime and never touch disk.</p>
     */
    @Override
    protected CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
        ServerLevel level = world;
        if (level == null) return CompletableFuture.completedFuture(null);
        MinecraftServer server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);
        io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
                io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
        if (adapter == null) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                    "V26_1_R1FabricRTPWorld.setForceLoadedImpl: FabricVersionAdapter not yet installed"
                            + " (world=" + name + " chunk=(" + cx + "," + cz + "))"));
            return failed;
        }
        return server.submit(() -> {
            try {
                io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle levelHandle =
                        io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle.of(level);
                CompletableFuture<Void> f = forceLoad
                        ? adapter.applyTicket(levelHandle, cx, cz)
                        : adapter.releaseTicket(levelHandle, cx, cz);
                f.getNow(null);
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP][v26_1_R1] setForceLoadedImpl(" + cx + "," + cz + "," + forceLoad
                                + ") failed for " + name + ": "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Integer> getServerForceLoadedCount() {
        // 26.1.2's ServerLevel does not expose a public getForcedChunks()
        // accessor. Since RTP now uses non-persistent DistanceManager tickets
        // (not setChunkForced), the relevant count is the plugin's own
        // active-ticket count tracked by RTPWorld#chunkTickets.
        return CompletableFuture.completedFuture((int) numForceLoaded());
    }

    @Override
    public RTPChunk<?> getCachedChunk(long key) {
        return chunkCache.get(key);
    }

    @Override
    public void keepChunkAt(int cx, int cz) {
        setForceLoaded(cx, cz, true);
    }

    @Override
    public void forgetChunkAt(int cx, int cz) {
        setForceLoaded(cx, cz, false);
        // Drop our cached wrapper so a subsequent load re-enters the server submit
        // path and observes any state changes that happened while unkept.
        chunkCache.remove(key(cx, cz));
    }

    @Override
    public void forgetChunks() {
        chunkCache.clear();
    }

    @Override
    public String getBiome(int x, int y, int z) {
        ServerLevel level = world;
        if (level == null) return "";
        try {
            Holder<Biome> holder = level.getBiome(new BlockPos(x, y, z));
            // Holder#unwrapKey()'s ResourceKey on 26.1.2 exposes identifier() (was location()).
            return holder.unwrapKey()
                    .map(k -> {
                        try { return k.identifier().toString().toUpperCase(Locale.ROOT); }
                        catch (Throwable t) { return ""; }
                    })
                    .orElseGet(() -> {
                        // Fallback: registry reverse lookup via BuiltInRegistries.
                        try {
                            Identifier id = level.registryAccess()
                                    .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                                    .getKey(holder.value());
                            return (id == null) ? "" : id.toString().toUpperCase(Locale.ROOT);
                        } catch (Throwable t) {
                            return "";
                        }
                    });
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public void platform(RTPLocation location) {
        // No-op — safety platform placement is a follow-up phase. Pipeline still
        // works without it (commit-time recheck remains authoritative).
    }

    @Override
    public boolean isInactive() {
        return false;
    }

    @Override
    public void save() {
        // Server saves on its own cadence; explicit per-world save is not
        // required for /rtp's pipeline.
    }

    @Override
    public int getMaxHeight() {
        try {
            // LevelHeightAccessor#getMaxY() on MC 26.1.2 — inclusive max build Y.
            return world.getMaxY();
        } catch (Throwable t) {
            return 320;
        }
    }

    @Override
    public int getMinHeight() {
        try {
            return world.getMinY();
        } catch (Throwable t) {
            return -64;
        }
    }

    @Override
    public int getCacheSize() {
        return chunkCache.size();
    }

    @Override
    public long getSeed() {
        try {
            return world.getSeed();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** Fabric-internal accessor; not part of the platform-neutral API. */
    public ServerLevel level() {
        return world;
    }
}
