package io.github.dailystruggle.rtp.neoforge.v26_1_R1;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * MC 26.x (Minecraft 26.1) implementation of {@link NeoForgeVersionAdapter} —
 * the second NeoForge carrier per rtp-neoforge-ADR-001, cloned from
 * {@code V1_21_R1NeoForgeVersionAdapter} and pinned to the deobfuscated 26.1
 * runtime (NeoForge 26.1.x, Java 25).
 *
 * <p><b>Mojmap-at-runtime.</b> NeoForge ships Mojang-mapped names at runtime,
 * and the 26.x line is fully deobfuscated (Mojang parameter names ship
 * natively), so this carrier compiles directly against {@code net.minecraft.*}
 * Mojmap types — there is no Loom/intermediary remap step. The
 * {@code DistanceManager} region-ticket pair is package-private, so it is
 * resolved reflectively (one-shot, cached) by structural signature rather than
 * via an access transformer; that structural resolution is deliberately
 * version-robust so the same code absorbs the post-1.21.5 ticket refactors that
 * are baked into the 26.1 runtime.</p>
 *
 * <p><b>Compiled against the real runtime.</b> This carrier is built and
 * linked against the real NeoForge 26.1.2 userdev artifacts (neoforgeVersion
 * {@code 26.1.2.71}, JDK 25) and loads cleanly at runtime (confirmed by the
 * {@code Active version adapter: 26.1.2} boot line on a dev server), so the
 * direct typed calls below (the {@code TicketType} ticket pair, the chunk-source
 * ticket/chunk-future accessors, and the maps / written-book component APIs)
 * match the shipped Mojmap surface. 26.1 remains a moving target: re-run
 * {@code .\gradlew :rtp-neoforge:rtp-neoforge-v26_1_R1:build} on a JDK-25 host
 * and re-pin the versions here and in
 * {@code RTPNeoForgeMod#installVersionAdapter} after any 26.1/26.2 bump.</p>
 */
public final class V26_1_R1NeoForgeVersionAdapter implements NeoForgeVersionAdapter {

    @Override
    public String mcVersion() {
        return "26.1.2";
    }

    // -------------------------------------------------------------------------
    // Block-tag snapshot — typed walk of BuiltInRegistries.BLOCK (no reflection).
    // -------------------------------------------------------------------------

    @Override
    public @Nullable Map<String, Set<String>> snapshotBlockTags() {
        try {
            Map<String, Set<String>> out = new HashMap<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                String materialName = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds
                        .registryKeyString(BuiltInRegistries.BLOCK, block);
                if (materialName == null) continue;
                materialName = materialName.toUpperCase();
                Holder.Reference<Block> holder;
                try {
                    holder = block.builtInRegistryHolder();
                } catch (Throwable t) {
                    continue;
                }
                if (holder == null) continue;
                java.util.stream.Stream<TagKey<Block>> tagStream;
                try {
                    tagStream = holder.tags();
                } catch (Throwable t) {
                    continue;
                }
                if (tagStream == null) continue;
                java.util.Iterator<TagKey<Block>> it = tagStream.iterator();
                while (it.hasNext()) {
                    TagKey<Block> tagKey = it.next();
                    if (tagKey == null) continue;
                    Object tagId = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds
                            .location(tagKey);
                    if (tagId == null) continue;
                    String key = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds.namespace(tagId)
                            + ":" + io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds.path(tagId);
                    out.computeIfAbsent(key, k -> new HashSet<>()).add(materialName);
                }
            }
            Map<String, Set<String>> immutable = new HashMap<>(out.size());
            for (Map.Entry<String, Set<String>> e : out.entrySet()) {
                immutable.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
            }
            return Collections.unmodifiableMap(immutable);
        } catch (Throwable t) {
            // Hard failure (linkage, registry torn down) — fall back to the
            // reflective walk in NeoForgeServerAccessor by returning null.
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Chunk access.
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<ChunkAccess> getChunkFull(ServerLevel level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("null ServerLevel"));
        }
        try {
            ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
            return CompletableFuture.completedFuture(chunk);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    private static volatile Method GET_CHUNK_FUTURE_METHOD;

    private static Method resolveGetChunkFutureMethod(ServerChunkCache cache) throws ReflectiveOperationException {
        Method cached = GET_CHUNK_FUTURE_METHOD;
        if (cached != null) return cached;
        synchronized (V26_1_R1NeoForgeVersionAdapter.class) {
            cached = GET_CHUNK_FUTURE_METHOD;
            if (cached != null) return cached;
            // The 4-arg (int,int,ChunkStatus,boolean)->CompletableFuture signature
            // is NOT unique on ServerChunkCache: vanilla declares BOTH the public
            // entry point getChunkFuture(...) AND the private
            // getChunkFutureMainThread(...). On 26.1.2 only getChunkFuture exists
            // with this signature; getChunkFutureMainThread is absent. We prefer
            // getChunkFuture (the public entry point) and pair it with an explicit
            // temporary load-ticket (addTicketWithRadius, applied just before the
            // call in requestFullChunkAsync) so the chunk holder reaches FULL
            // status. Without the ticket, getChunkFuture(create=true) resolves
            // immediately to ChunkResult.error("Unloaded chunk") on the 26.1
            // NeoForge chunk system - the L1 kept cache stays at 0 and /rtp never
            // lands. Fall back to a structural scan for runtimes where the public
            // method was renamed.
            Method found = null;
            String[] preferredNames = { "getChunkFuture" };
            for (String name : preferredNames) {
                for (Class<?> c = cache.getClass(); c != null && found == null; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (!name.equals(m.getName())) continue;
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
                if (found != null) break;
            }
            if (found == null) {
                // Fallback: structural scan (any name) for runtimes where the
                // public method was renamed.
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
            }
            if (found == null) {
                throw new NoSuchMethodException(
                        "ServerChunkCache#getChunkFuture(int,int,ChunkStatus,boolean) not found on "
                                + cache.getClass().getName());
            }
            RTP.log(Level.INFO,
                    "[RTP][NeoForge 26.1] resolved chunk-future method '" + found.getName()
                            + "' on " + found.getDeclaringClass().getName()
                            + " (preferring the public getChunkFuture entry point so create=true"
                            + " self-issues the generation ticket; a temporary load-ticket is"
                            + " applied before the call so the holder reaches FULL status).");
            GET_CHUNK_FUTURE_METHOD = found;
            return found;
        }
    }

    @Override
    public CompletableFuture<ChunkAccess> requestFullChunkAsync(ServerLevel level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("null ServerLevel"));
        }
        try {
            ServerChunkCache cache = level.getChunkSource();
            Method getter = resolveGetChunkFutureMethod(cache);

            // No temporary load-ticket here. create=true makes vanilla
            // self-issue the transient generation ticket that drives the holder
            // to FULL status, exactly as the proven Fabric 26.1 carrier
            // (V26_1_R1FabricRTPWorld#requestChunkFuture) does. The earlier
            // explicit addTicketWithRadius(...)/removeTicketWithRadius(...) wrapper
            // pinned the holder but left generation never settling: the FULL
            // future hung indefinitely, so the L2->L1 in-flight counter
            // saturated at the deficit cap ("gets all the way to 10 in flight")
            // and the kept cache never filled - loads only ever cleared on the
            // 30s deadline. The persistent kept-cache ticket is still applied
            // separately via applyTicket(...) after cold->hot promotion, so the
            // promoted chunk stays pinned (S-002 unaffected).
            RTP.log(Level.FINER,
                    "[RTP][NeoForge 26.1] invoking getChunkFuture('" + getter.getName()
                            + "', cx=" + cx + ", cz=" + cz + ", FULL, create=true) on "
                            + cache.getClass().getSimpleName() + " (tickThread=" + level.getServer().isSameThread() + ")");
            Object raw = getter.invoke(cache, cx, cz, ChunkStatus.FULL, /*create=*/ true);
            if (!(raw instanceof CompletableFuture<?> cf)) {
                RTP.log(Level.WARNING,
                        "[RTP][NeoForge 26.1] getChunkFuture returned non-CompletableFuture: "
                                + (raw == null ? "null" : raw.getClass()) + " for chunk=(" + cx + "," + cz + ")");
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "getChunkFuture returned non-CompletableFuture: " + (raw == null ? "null" : raw.getClass())));
            }
            RTP.log(Level.FINER,
                    "[RTP][NeoForge 26.1] getChunkFuture returned future id@"
                            + System.identityHashCode(cf) + " done=" + cf.isDone()
                            + " for chunk=(" + cx + "," + cz + ")");
            return cf.thenApply(either -> {
                ChunkAccess unwrapped = (either == null) ? null : unwrapEitherLeft(either);
                if (unwrapped != null) return unwrapped;

                // The generation future resolved with no ChunkAccess. We do NOT
                // fall back to a blocking ServerChunkCache#getChunk(...) here: this
                // callback runs inside the MinecraftServer#execute task that
                // dispatched the request (on the tick thread), so a blocking
                // getChunk would re-drive the main-thread task queue from inside
                // one of its own tasks and deadlock the chunk-generation
                // dependency graph - the exact failure mode documented in
                // rtp-fabric-ADR-008. Emit the S-004 diagnostic instead so a
                // genuine failure is never silently discarded; log the concrete
                // result shape + (for a ChunkResult) its error text so a
                // generation refusal ("Unloaded chunk ...") is distinguishable
                // from an unrecognised unwrap shape.
                if (either == null) {
                    RTP.log(Level.FINE,
                            "[RTP][NeoForge 26.1] requestFullChunkAsync: chunk-future (create=true) future "
                                    + "completed with a NULL result for chunk=(" + cx + "," + cz + ").");
                    return null;
                }
                String detail;
                try {
                    detail = either.getClass().getName() + " -> " + String.valueOf(either);
                } catch (Throwable t) {
                    detail = either.getClass().getName() + " (toString threw "
                            + t.getClass().getSimpleName() + ")";
                }
                String errText = null;
                try {
                    Method errM = either.getClass().getMethod("getError");
                    Object err = errM.invoke(either);
                    if (err != null) errText = String.valueOf(err);
                } catch (Throwable ignored) {
                    // no getError(); not a ChunkResult error-bearing shape
                }
                RTP.log(Level.FINE,
                        "[RTP][NeoForge 26.1] requestFullChunkAsync: chunk-future (create=true) resolved "
                                + "but no ChunkAccess could be unwrapped for chunk=(" + cx + "," + cz
                                + "). result=" + detail
                                + (errText != null ? " chunkResultError=" + errText : "")
                                + " (a non-null error here means vanilla refused to generate/load the chunk;"
                                + " an unrecognised result class means the unwrap shape needs updating).");
                return null;
            });
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    private static ChunkAccess unwrapEitherLeft(Object either) {
        // 1.21.x getChunkFuture returns CompletableFuture<ChunkResult<ChunkAccess>>;
        // older shapes returned an Either. Handle both structurally.
        try {
            if (either instanceof ChunkAccess ca) return ca;
            // ChunkResult#orElse(Object) on 1.21.x.
            try {
                Method orElse = either.getClass().getMethod("orElse", Object.class);
                Object value = orElse.invoke(either, (Object) null);
                if (value instanceof ChunkAccess ca2) return ca2;
            } catch (NoSuchMethodException ignored) {
                // fall through to Either#left()
            }
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

    // -------------------------------------------------------------------------
    // Non-persistent chunk tickets — see NeoForgeVersionAdapter Javadoc and
    // rtp-fabric-ADR-006 / -016 (ported). A non-persistent, non-expiring
    // TicketType (timeout NO_TIMEOUT, FLAG_PERSIST omitted); distance 1 ->
    // effective level 33-1=32 (FULL/BORDER, below ENTITY_TICKING): the chunk
    // stays pinned and block-readable for RTPChunk#isSafe but does not
    // entity-tick, so a player-less kept chunk costs ~0 MSPT; the player's own
    // arrival ticket promotes it to ENTITY_TICKING on teleport without a regen.
    // Supersedes ADR-006's ENTITY_TICKING end-state for the kept cache
    // (rtp-fabric-ADR-016).
    // -------------------------------------------------------------------------

    private static final int RTP_TICKET_DISTANCE = 1;

    // 26.1 Mojmap: TicketType(long timeout, int flags), flags a bitfield over
    // FLAG_PERSIST | FLAG_LOADING | FLAG_SIMULATION | ... . Omit FLAG_PERSIST for
    // S-002 (non-persistent: never written to level.dat). Tickets are applied
    // through the public ServerChunkCache#addTicketWithRadius /
    // #removeTicketWithRadius pair (radius 1 -> effective level 32, FULL/BORDER
    // below ENTITY_TICKING: chunk pinned and block-readable for RTPChunk#isSafe
    // but not entity-ticking, ~0 MSPT until the player's arrival ticket promotes
    // it). Matches the Fabric 26.x / 1.21.11 carriers - no DistanceManager
    // reflection on this runtime.
    private static final int RTP_TICKET_FLAGS =
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION;
    private static final TicketType RTP_TICKET_TYPE =
            new TicketType(TicketType.NO_TIMEOUT, RTP_TICKET_FLAGS);

    @Override
    public CompletableFuture<Void> applyTicket(ServerLevel level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("null ServerLevel"));
        }
        try {
            ServerChunkCache cache = level.getChunkSource();
            cache.addTicketWithRadius(RTP_TICKET_TYPE, new ChunkPos(cx, cz), RTP_TICKET_DISTANCE);
            return CompletableFuture.completedFuture(null);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][NeoForge 26.1] applyTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
    }

    @Override
    public CompletableFuture<Void> releaseTicket(ServerLevel level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            ServerChunkCache cache = level.getChunkSource();
            cache.removeTicketWithRadius(RTP_TICKET_TYPE, new ChunkPos(cx, cz), RTP_TICKET_DISTANCE);
            return CompletableFuture.completedFuture(null);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][NeoForge 26.1] releaseTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
    }

    // -------------------------------------------------------------------------
    // Typed player / command seams (Mojmap — no reflection needed).
    // -------------------------------------------------------------------------

    @Override
    public Object extractPlayerFromConnection(Object handler) {
        if (!(handler instanceof ServerGamePacketListenerImpl impl)) return null;
        return impl.player;
    }

    @Override
    public UUID getPlayerUUID(Object player) {
        if (!(player instanceof ServerPlayer sp)) return null;
        return sp.getUUID();
    }

    @Override
    public UUID resolveSenderUuid(Object src) {
        if (!(src instanceof CommandSourceStack css)) return null;
        Entity entity = css.getEntity();
        if (!(entity instanceof ServerPlayer sp)) return null;
        return sp.getUUID();
    }

    @Override
    public @Nullable Thread getServerThread(Object server) {
        if (!(server instanceof MinecraftServer s)) return null;
        return s.getRunningThread();
    }

    @Override
    public boolean dispatchConsoleCommand(Object server, String command) {
        if (!(server instanceof MinecraftServer s) || command == null) return false;
        s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), command);
        return true;
    }

    // -------------------------------------------------------------------------
    // Menu parity — typed written-book modal (NeoForge analogue of the Fabric
    // v1_21_R1 carrier's openBookMenu).
    // -------------------------------------------------------------------------

    /**
     * Typed written-book modal for MC 1.21.0-1.21.4 on NeoForge. Builds a
     * {@code WRITTEN_BOOK} {@link net.minecraft.world.item.ItemStack} carrying a
     * {@link net.minecraft.world.item.component.WrittenBookContent} data
     * component (one styled {@link net.minecraft.network.chat.Component} per
     * page, fragments built by {@code NeoForgeLegacyText#parseInteractive}),
     * sends it transiently to the player's held hotbar slot, opens the book,
     * then reverts the slot - the server-side inventory is never mutated.
     */
    @Override
    public boolean openBookMenu(Object serverPlayer, Object specObj) {
        if (!(serverPlayer instanceof ServerPlayer sp)) return false;
        if (!(specObj instanceof io.github.dailystruggle.rtp.neoforge.menu.NeoForgeBookSpec spec)) return false;
        try {
            java.util.List<net.minecraft.network.chat.Component> pageComponents =
                    new java.util.ArrayList<>(spec.pages().size());
            for (io.github.dailystruggle.rtp.neoforge.menu.NeoForgeBookSpec.Page page : spec.pages()) {
                net.minecraft.network.chat.MutableComponent pageComp =
                        net.minecraft.network.chat.Component.empty();
                boolean firstLine = true;
                for (io.github.dailystruggle.rtp.neoforge.menu.NeoForgeBookSpec.Line line : page.lines()) {
                    if (!firstLine) pageComp.append("\n");
                    firstLine = false;
                    for (io.github.dailystruggle.rtp.neoforge.menu.NeoForgeBookSpec.Fragment frag : line.fragments()) {
                        pageComp.append(io.github.dailystruggle.rtp.neoforge.tools.NeoForgeLegacyText.parseInteractive(
                                frag.text(), frag.hover(), frag.runCommand(),
                                io.github.dailystruggle.rtp.neoforge.tools.NeoForgeLegacyText.ClickKind.RUN));
                    }
                }
                pageComponents.add(pageComp);
            }
            if (pageComponents.isEmpty()) {
                pageComponents.add(net.minecraft.network.chat.Component.empty());
            }

            java.util.List<net.minecraft.server.network.Filterable<net.minecraft.network.chat.Component>> filtered =
                    new java.util.ArrayList<>(pageComponents.size());
            for (net.minecraft.network.chat.Component c : pageComponents) {
                filtered.add(net.minecraft.server.network.Filterable.passThrough(c));
            }
            net.minecraft.world.item.component.WrittenBookContent content =
                    new net.minecraft.world.item.component.WrittenBookContent(
                            net.minecraft.server.network.Filterable.passThrough(spec.title()),
                            "RTP", 0, filtered, false);
            net.minecraft.world.item.ItemStack book =
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITTEN_BOOK);
            book.set(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT, content);

            int hotbar = sp.getInventory().getSelectedSlot();
            int slotId = 36 + hotbar;
            net.minecraft.world.item.ItemStack real = sp.getInventory().getItem(hotbar);
            int containerId = sp.inventoryMenu.containerId;
            sp.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                    containerId, sp.inventoryMenu.incrementStateId(), slotId, book));
            sp.connection.send(new net.minecraft.network.protocol.game.ClientboundOpenBookPacket(
                    net.minecraft.world.InteractionHand.MAIN_HAND));
            sp.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                    containerId, sp.inventoryMenu.incrementStateId(), slotId, real));
            return true;
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][NeoForge 26.1] openBookMenu failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Maps-api parity (rtp-fabric-ADR-015 port / MULTI_PLATFORM_PLAN Step NK).
    // Vanilla filled-map rendering for the MC 1.21.1 runtime. 1.21.1 ships the
    // same Mojmap maps API as the deobf 26.x line (MapId / getFreeMapId /
    // MapItemSavedData.MapPatch / ClientboundMapItemDataPacket), so this is a
    // faithful typed port of the Fabric v26_2_R1 carrier implementation.
    // -------------------------------------------------------------------------

    /** chartKey -> the vanilla MapId allocated for that chart (reused across live frames). */
    private final java.util.Map<String, net.minecraft.world.level.saveddata.maps.MapId> mapIds =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public boolean supportsMapCharts() {
        return true;
    }

    @Override
    public void releaseMapChart(String chartKey) {
        if (chartKey != null) {
            mapIds.remove(chartKey);
        }
    }

    @Override
    public boolean renderMapChart(Object serverPlayer,
                                  String chartKey,
                                  int[] argb,
                                  boolean locked,
                                  boolean deliverItem) {
        // Maps flow player-first: the viewer arrives as the raw ServerPlayer the
        // NeoForgeRTPPlayer resolved and owns, so all server/level state is
        // reached through the player handle (sp.level()) rather than a standalone
        // MinecraftServer. The caller (NeoForgeRTPPlayer#renderMapChart) has
        // already hopped to the server tick thread via RTP.scheduler, so map
        // allocation, colour writes, packet dispatch, and inventory mutation are
        // all server-thread-safe here.
        if (!(serverPlayer instanceof ServerPlayer sp)
                || chartKey == null || argb == null) {
            return false;
        }
        try {
            ServerLevel level = (ServerLevel) sp.level();

            // Client-only "fake map": we deliberately do NOT register any
            // MapItemSavedData on the server (no level.setMapData). The server
            // therefore has no saved data to tick, so vanilla's held-map terrain
            // scan never runs and can never overwrite our custom chart pixels.
            // The client maintains its own per-MapId colour cache populated purely
            // from the ClientboundMapItemDataPacket we push below, so chart
            // fidelity is unaffected by the absence of server-side saved data. We
            // only need a stable MapId per chart so live frames target the same
            // client-side map.
            net.minecraft.world.level.saveddata.maps.MapId id = mapIds.get(chartKey);
            if (id == null) {
                id = level.getFreeMapId();
                mapIds.put(chartKey, id);
            }

            // Translate the ARGB buffer to vanilla MapColor packed bytes and push
            // them as a full-canvas patch. setColorsDirty / getUpdatePacket are
            // private, so we build a 128x128 MapPatch and send it ourselves.
            int side = 128;
            int n = Math.min(argb.length, side * side);
            byte[] patchColors = new byte[side * side];
            for (int i = 0; i < n; i++) {
                int pixel = argb[i];
                int alpha = (pixel >>> 24) & 0xFF;
                byte packed = (alpha == 0)
                        ? 0 // MapColor.NONE -> transparent
                        : matchColor((pixel >> 16) & 0xFF, (pixel >> 8) & 0xFF, pixel & 0xFF);
                patchColors[i] = packed;
            }
            net.minecraft.world.level.saveddata.maps.MapItemSavedData.MapPatch patch =
                    new net.minecraft.world.level.saveddata.maps.MapItemSavedData.MapPatch(
                            0, 0, side, side, patchColors);
            sp.connection.send(new net.minecraft.network.protocol.game.ClientboundMapItemDataPacket(
                    id, (byte) 0, locked,
                    (java.util.Collection<net.minecraft.world.level.saveddata.maps.MapDecoration>) null,
                    patch));

            if (deliverItem) {
                net.minecraft.world.item.ItemStack map =
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FILLED_MAP);
                map.set(net.minecraft.core.component.DataComponents.MAP_ID, id);
                if (!sp.getInventory().add(map)) {
                    // Inventory full: drop at the player's feet (parity with
                    // BukkitMapBinding.deliverTo's dropItem fallback).
                    sp.drop(map, false);
                }
            }
        } catch (Throwable t) {
            // S-004: never silently swallow: log loud, but do not rethrow into the
            // server tick (would disconnect the viewer).
            RTP.log(Level.WARNING, "[RTP][NeoForge 26.1] renderMapChart failed for chartKey="
                    + chartKey + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Match an RGB triple to the nearest vanilla map-colour packed byte by
     * walking every {@code MapColor} x {@code Brightness} pair and minimising
     * squared Euclidean distance in RGB space. Mirrors the role of Bukkit's
     * {@code MapPalette.matchColor} but against the Mojmap {@code MapColor}
     * table.
     */
    private static byte matchColor(int r, int g, int b) {
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int packed = 0; packed < 256; packed++) {
            net.minecraft.world.level.material.MapColor color =
                    net.minecraft.world.level.material.MapColor.byId(packed >> 2);
            if (color == null || color.id == 0) continue; // skip NONE (transparent)
            int rgb = net.minecraft.world.level.material.MapColor.getColorFromPackedId(packed);
            int rr = (rgb >> 16) & 0xFF;
            int gg = (rgb >> 8) & 0xFF;
            int bb = rgb & 0xFF;
            long dr = r - rr, dg = g - gg, db = b - bb;
            long dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = packed;
            }
        }
        return (byte) best;
    }
}
