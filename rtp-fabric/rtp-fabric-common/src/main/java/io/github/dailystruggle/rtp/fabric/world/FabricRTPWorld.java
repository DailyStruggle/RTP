package io.github.dailystruggle.rtp.fabric.world;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Phase 2 Step A — minimal Fabric {@link RTPWorld} implementation.
 *
 * <p><b>Scope (per ADR-022 §5 / {@code MULTI_PLATFORM_PLAN.md} Step A):</b>
 * the safety-critical S-005 path — asynchronous chunk loading off the main
 * server tick thread. {@link #getChunkAt(int, int)} dispatches via
 * {@link MinecraftServer#submit(java.util.function.Supplier)} so the chunk
 * source is touched only on the server tick thread, and the caller never
 * blocks. Other {@link RTPWorld} methods are deliberately stubbed with
 * {@link UnsupportedOperationException}: they will be wired in subsequent
 * Phase 2 steps as the bridge needs them.</p>
 *
 * <p><b>Architectural invariants (ADR-022 §4):</b></p>
 * <ul>
 *   <li>No {@code org.bukkit.*} imports — verified by inspection.</li>
 *   <li>Holds a {@link ServerLevel} reference; the owning server is reached
 *       via {@link ServerLevel#getServer()} so the executor hop is robust to
 *       multi-world setups.</li>
 * </ul>
 *
 * <p><b>S-005 compliance.</b> The chunk source ({@link ServerChunkCache}) is
 * NOT thread-safe and must only be touched on the server tick thread. We
 * therefore submit a {@link java.util.function.Supplier} to the server's
 * task queue; the returned {@link CompletableFuture} resolves on whatever
 * thread the supplier completes on, which is the server tick thread. The
 * caller (always an off-tick worker — typically a {@code TeleportPipelineTask}
 * stage) never blocks.</p>
 *
 * <p><b>Verification.</b> Per the Phase 1 → Step H gate move recorded in
 * {@code MULTI_PLATFORM_PLAN.md}, end-to-end exercise of this code path is
 * deferred to Phase 2 Step H's dual-runtime smoke test, which can actually
 * boot a Fabric server against {@code MinecraftServer}. A unit test would
 * have to mock {@code ServerLevel} / {@code ServerChunkCache} via bytecode
 * tricks; the cost is not justified versus the smoke gate.
 * See {@code TRACEABILITY.md} row REQ-RTP-S-005 (Fabric).</p>
 */
public final class FabricRTPWorld extends RTPWorld<ServerLevel> {

    private final String name;
    private final UUID id;

    public FabricRTPWorld(@NotNull ServerLevel level) {
        super(level);
        // dimension() returns ResourceKey<Level>; its location is the canonical
        // dimension id (e.g. minecraft:overworld). Use that as the world name —
        // this matches how Fabric command/log output identifies dimensions and
        // avoids depending on save-folder names which are server-config-specific.
        this.name = level.dimension().location().toString();
        // Fabric does not assign a per-world UUID the way Bukkit does; derive a
        // deterministic UUID from the dimension id so equals/hashCode behave
        // sensibly across restarts. Step E may revisit if event wiring requires
        // a different identity scheme.
        this.id = UUID.nameUUIDFromBytes(this.name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public UUID id() {
        return id;
    }

    /**
     * Public accessor for the underlying {@link ServerLevel}. The base
     * {@link RTPWorld#world} field is {@code protected}; expose it for
     * Fabric-side adapters (player teleport, event bridge) that legitimately
     * need the native handle. Callers in {@code rtp-core} / {@code rtp-api}
     * must NOT use this — keep the API platform-free.
     */
    public ServerLevel level() {
        return world;
    }

    /**
     * S-005-compliant async chunk load.
     *
     * <p>Hops onto the server tick thread via {@link MinecraftServer#submit}
     * to touch {@link ServerChunkCache}, requests the chunk at
     * {@link ChunkStatus#FULL}, and resolves the returned future with the
     * canonical chunk-key encoding shared with the Bukkit-family adapters
     * ({@code ((long) cx & 0xffffffffL) | ((long) cz << 32)}).</p>
     *
     * <p>The caller MUST be off-tick. This implementation does not assert
     * that — Fabric has no equivalent of {@code Bukkit.isOwnedByCurrentRegion}
     * because the server has a single tick thread; calling from on-tick would
     * still work (the supplier runs immediately) but defeats the S-005 intent.
     * The S-005 regression guard (Step H smoke test) verifies caller-side.</p>
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return future resolving to the canonical packed chunk key
     */
    @Override
    public CompletableFuture<Long> getChunkAt(int chunkX, int chunkZ) {
        totalChunkLoads.incrementAndGet();
        final long key = ((long) chunkX & 0xffffffffL) | ((long) chunkZ << 32);
        final MinecraftServer server = world.getServer();
        if (server == null) {
            // Defensive: a ServerLevel without a server is a torn-down state.
            // Complete exceptionally rather than throwing on the caller thread
            // so the pipeline's existing failure-attribution path picks it up
            // (REQ-RTP-S-004 — no silent discards).
            CompletableFuture<Long> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                "FabricRTPWorld.getChunkAt: ServerLevel has no MinecraftServer (world=" + name + ")"));
            return failed;
        }
        // server.submit(Supplier) returns a CompletableFuture that resolves on
        // the server tick thread once the supplier runs. The supplier itself
        // calls into the chunk source — which is the only thread-safe way to
        // touch it on Fabric.
        return server.submit(() -> {
            ServerChunkCache cache = world.getChunkSource();
            // getChunk(cx, cz, status, load=true) is the documented synchronous
            // entry point on the server thread. We are ON the server thread
            // here (we hopped via server.submit), so this is allowed; the call
            // does not block any other thread. For a non-loading lookup use
            // cache.getChunk(cx, cz, status, false), but Step A's contract is
            // "load if absent", matching BukkitRTPWorld.loadChunkFuture.
            ChunkAccess chunk = cache.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
            // Returning the key is the contract the rest of the pipeline expects;
            // chunk identity is not part of the return shape.
            return key;
        });
    }

    /**
     * Step A scope: not yet implemented. Wired in a later step alongside the
     * stale-chunk guard (ADR-015) and ChunkSet construction.
     */
    @Override
    public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
        throw new UnsupportedOperationException(
            "FabricRTPWorld.getChunkAtAsync: not yet wired — see MULTI_PLATFORM_PLAN.md Phase 2 Step A scope notes; "
                + "follow-up step will implement once ChunkSet construction is needed by the Fabric pipeline.");
    }

    /**
     * Step A scope: not yet implemented. Force-loaded chunk ticket lifecycle
     * (acquire/release with MemoryTracker accounting) is wired alongside the
     * Fabric scheduler in Step C and the event bridge in Step E.
     *
     * <p>The non-{@code Impl} {@link RTPWorld#setForceLoaded(int, int, boolean)}
     * default in the parent already handles ref-counting and {@code MemoryTracker}
     * registration; only this protected hook needs a platform implementation.
     * Until Step C lands, calling this throws — the {@link FabricRTPWorld#getChunkAt}
     * S-005 path does NOT depend on it (chunk loads via the server tick thread
     * are sufficient for Step A's correctness).</p>
     */
    @Override
    protected CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
        throw new UnsupportedOperationException(
            "FabricRTPWorld.setForceLoadedImpl: not yet wired — see MULTI_PLATFORM_PLAN.md Phase 2 Step C "
                + "(Fabric scheduler + chunk-ticket lifecycle).");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Step A scope: the remaining abstract RTPWorld methods are stubbed with
    // UnsupportedOperationException. Each will be implemented in a later
    // Phase 2 step (see per-method note). This deliberate-fail-loud approach
    // matches REQ-RTP-S-006 (no silent no-ops on unimplemented API surface)
    // and gives Steps B–H clear signals about what is still owed.
    // ──────────────────────────────────────────────────────────────────────

    private static UnsupportedOperationException notWired(String method, String stepNote) {
        return new UnsupportedOperationException(
            "FabricRTPWorld." + method + ": not yet wired — " + stepNote
                + " (see MULTI_PLATFORM_PLAN.md Phase 2).");
    }

    @Override
    public CompletableFuture<Integer> getServerForceLoadedCount() {
        throw notWired("getServerForceLoadedCount", "Step C — chunk-ticket lifecycle");
    }

    @Override
    public RTPChunk<?> getCachedChunk(long key) {
        throw notWired("getCachedChunk", "Step C — chunk cache");
    }

    @Override
    public void keepChunkAt(int chunkX, int chunkZ) {
        throw notWired("keepChunkAt", "Step C — chunk-ticket lifecycle");
    }

    @Override
    public void forgetChunkAt(int chunkX, int chunkZ) {
        throw notWired("forgetChunkAt", "Step C — chunk-ticket lifecycle");
    }

    @Override
    public void forgetChunks() {
        throw notWired("forgetChunks", "Step C — chunk-ticket lifecycle");
    }

    @Override
    public String getBiome(int x, int y, int z) {
        throw notWired("getBiome", "Step E — world/event bridge");
    }

    @Override
    public void platform(RTPLocation location) {
        throw notWired("platform", "Step E — world/event bridge");
    }

    @Override
    public boolean isInactive() {
        throw notWired("isInactive", "Step E — world/event bridge");
    }

    @Override
    public void save() {
        // Intentional no-op on Fabric (parity with Paper/Folia overrides).
        // Fabric's chunk system persists generated chunks via its own
        // dirty-tracking and the vanilla autosave path; the forced
        // World.save() that the Spigot adapter performs (to work around
        // Bukkit autosave not flushing Chunky-generated chunks — see
        // docs/dev/LESSONS_LEARNED.md "Pre-Generation & Shutdown") is not
        // needed here. Kept as a no-op rather than UnsupportedOperationException
        // so rtp scan checkpoints don't spam warnings on Fabric.
    }

    @Override
    public int getMaxHeight() {
        throw notWired("getMaxHeight", "Step E — world/event bridge");
    }

    @Override
    public int getMinHeight() {
        throw notWired("getMinHeight", "Step E — world/event bridge");
    }

    @Override
    public int getCacheSize() {
        throw notWired("getCacheSize", "Step C — chunk cache");
    }

    @Override
    public long getSeed() {
        throw notWired("getSeed", "Step E — world/event bridge");
    }
}
