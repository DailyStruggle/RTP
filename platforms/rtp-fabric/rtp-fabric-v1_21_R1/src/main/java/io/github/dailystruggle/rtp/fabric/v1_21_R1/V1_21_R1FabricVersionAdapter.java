package io.github.dailystruggle.rtp.fabric.v1_21_R1;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import io.github.dailystruggle.rtp.fabric.version.RTPChunkHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle;
import io.github.dailystruggle.rtp.fabric.menu.FabricBookSpec;
import io.github.dailystruggle.rtp.fabric.tools.FabricLegacyText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.tags.TagKey;
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
 * MC 1.21.0“1.21.4 implementation of {@link FabricVersionAdapter} - the reference
 * implementation per rtp-fabric-ADR-001.
 *
 * <p><b>Scope:</b> this adapter targets the {@code DistanceManager} API as
 * it existed before the 1.21.5 refactor - i.e. the 4-arg
 * {@code addRegionTicket(TicketType, ChunkPos, int, T)} /
 * {@code removeRegionTicket(...)} pair. From 1.21.5 onward Mojang replaced
 * that pair with {@code addTicket(long, Ticket)} on a value-object
 * {@code Ticket}; routing for 1.21.5+ goes to
 * {@code v1_21_R5} instead - see {@code rtp-fabric-ADR-004}.</p>
 *
 * <p>v1_20_R1 and v26_1_R1 will port from this class. Notable per-version
 * concerns this implementation captures:</p>
 * <ul>
 *   <li>{@link ChunkStatus} is at {@code net.minecraft.world.level.chunk.ChunkStatus}
 *       on 1.21.1; the package move to {@code .chunk.status} happens in 1.21.3.</li>
 *   <li>Biome registry access goes through the {@link Holder} produced by
 *       {@code level.getBiome(pos)} (the level's {@code registryAccess()} owns
 *       the biome registry on 1.21.x; there is no static {@code BuiltInRegistries}
 *       field for biomes).</li>
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


    /**
     * Typed block-tag snapshot for MC 1.21.0-1.21.4. Walks
     * {@link BuiltInRegistries#BLOCK} directly via Loom-mapped types - no
     * reflection - and inverts each block's
     * {@link Block#builtInRegistryHolder()}.{@code tags()} stream into the
     * {@code namespace:path -> upper-case "namespace:path"} multimap shape
     * documented on {@link FabricVersionAdapter#snapshotBlockTags()} and
     * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#blockTagSnapshot()}.
     *
     * <p>Returning an empty map (rather than {@code null}) is intentional when
     * the registry is reachable but tag bindings haven't been attached yet -
     * caller preserves {@code #tag} tokens for a later retry rather than
     * falling back to the reflective walk, which would yield the same empty
     * result for the same reason.
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
                    ResourceLocation tagId = tagKey.location();
                    if (tagId == null) continue;
                    String key = tagId.getNamespace() + ":" + tagId.getPath();
                    out.computeIfAbsent(key, k -> new HashSet<>()).add(materialName);
                }
            }
            Map<String, Set<String>> immutable = new HashMap<>(out.size());
            for (Map.Entry<String, Set<String>> e : out.entrySet()) {
                immutable.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
            }
            return Collections.unmodifiableMap(immutable);
        } catch (Throwable t) {
            // Hard failure (linkage, registry torn down) - fall back to the
            // reflective walk in FabricServerAccessor by returning null.
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
    // Non-blocking chunk-future dispatch - see rtp-fabric-ADR-008.
    // Structurally resolves the private ServerChunkCache#getChunkFutureMainThread
    // (int, int, ChunkStatus, boolean) under both Mojmap and intermediary names.
    // As on 1.20.1, three declared methods share that signature: the public
    // getChunkFuture, which managedBlocks the tick thread until generation
    // finishes (S-005), and a synthetic supplyAsync lambda (method_14098), so
    // the scan must not depend on declaration order.
    // -------------------------------------------------------------------------

    private static volatile Method GET_CHUNK_FUTURE_METHOD;

    private static Method resolveGetChunkFutureMethod(ServerChunkCache cache) throws ReflectiveOperationException {
        Method cached = GET_CHUNK_FUTURE_METHOD;
        if (cached != null) return cached;
        synchronized (V1_21_R1FabricVersionAdapter.class) {
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
            RTP.log(Level.INFO, "[RTP][Fabric 1.21.1] chunk-future method bound: "
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

    @Override
    public CompletableFuture<RTPChunkHandle> requestFullChunkAsync(RTPLevelHandle level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("null ServerLevel"));
        }
        try {
            ServerLevel sl = level.as(ServerLevel.class);
            ServerChunkCache cache = sl.getChunkSource();
            Method getter = resolveGetChunkFutureMethod(cache);

            // Temporary load ticket - see V1_20_R1FabricVersionAdapter#requestFullChunkAsync
            // (without it the holder drops below FULL mid-generation and the future
            // resolves to a failed ChunkResult). Its own TicketType, so releasing it
            // cannot collapse with and drop a kept-cache ticket on the same chunk (S-002).
            ChunkPos cp = new ChunkPos(cx, cz);
            boolean ticketAdded = false;
            try {
                cache.addRegionTicket(loadTicketType(), cp, RTP_TICKET_DISTANCE, cp);
                ticketAdded = true;
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP][Fabric 1.21.1] temp load-ticket apply failed for chunk=("
                                + cx + "," + cz + "): " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            final boolean addedTicket = ticketAdded;

            Object raw = getter.invoke(cache, cx, cz, ChunkStatus.FULL, /*create=*/ true);
            if (!(raw instanceof CompletableFuture<?> cf)) {
                if (addedTicket) tryRemoveLoadTicket(cache, cp);
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "getChunkFuture returned non-CompletableFuture: " + (raw == null ? "null" : raw.getClass())));
            }
            // cf is CompletableFuture<ChunkResult<ChunkAccess>>.
            return cf.thenApply(result -> {
                Object value = chunkResultValue(result);
                return value instanceof ChunkAccess chunk ? RTPChunkHandle.of(chunk) : null;
            }).whenComplete((handle, ex) -> {
                if (addedTicket) tryRemoveLoadTicket(cache, cp);
            });
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    private static void tryRemoveLoadTicket(ServerChunkCache cache, ChunkPos cp) {
        try {
            cache.removeRegionTicket(loadTicketType(), cp, RTP_TICKET_DISTANCE, cp);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric 1.21.1] temp load-ticket release failed for chunk=("
                            + cp.x + "," + cp.z + "): " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Success value of a {@link ChunkResult}, {@code null} for a failed result or a
     * non-ChunkResult. Since 1.20.5 the chunk future yields {@code ChunkResult}, not
     * DataFixerUpper {@code Either}; the old reflective {@code left()} lookup threw
     * {@link NoSuchMethodException} and read every loaded chunk as missing.
     */
    static Object chunkResultValue(Object result) {
        return result instanceof ChunkResult<?> r && r.isSuccess() ? r.orElse(null) : null;
    }


    // -------------------------------------------------------------------------
    // Non-persistent chunk-ticket support - see FabricVersionAdapter Javadoc.
    //
    // We allocate a process-wide non-persistent TicketType ("rtp") and hand
    // it to ServerChunkCache#addRegionTicket. {@code TicketType.create(name,
    // comparator, /*timeout*/ 0)} produces a non-persistent type (the
    // PERSISTENT set in TicketType is opt-in via the deprecated
    // {@code #createPersistent} factory; the public {@code #create} factory
    // never marks the type as such). Timeout 0 means "no auto-expiry -
    // lives until removeRegionTicket is called", matching Bukkit's
    // addPluginChunkTicket lifetime contract.
    //
    // Tickets go through the public ServerChunkCache#addRegionTicket /
    // #removeRegionTicket (Loom-remapped). A reflective DistanceManager scan
    // cannot pick them: DistanceManager declares addTicket / removeTicket
    // (level) before addRegionTicket / removeRegionTicket (distance), all
    // (TicketType, ChunkPos, int, T). The scan bound addTicket(level) and, on
    // intermediary names, a mismatched remove, so tickets went in at level 1
    // (a radius-32 load area) and were never released (S-002). Callers are
    // on the server thread.
    // -------------------------------------------------------------------------

    /**
     * Process-wide non-persistent ticket type. Created lazily because
     * {@link TicketType#create} is a registry call that we want to avoid
     * during static class init in a test JVM that may not have the chunk
     * subsystem initialised. The comparator orders by packed long position
     * (matches {@code TicketType.FORCED}'s ordering); timeout 0 = no expiry.
     */
    private static volatile TicketType<ChunkPos> RTP_TICKET_TYPE;

    /** Temporary load ticket, distinct from {@link #RTP_TICKET_TYPE} (see requestFullChunkAsync). */
    private static volatile TicketType<ChunkPos> RTP_LOAD_TICKET_TYPE;

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
     * <p>{@code distance = 1} resolves to effective level {@code 32}
     * ({@code FULL}/BORDER but below {@code ENTITY_TICKING}): the chunk stays
     * pinned and block-readable for {@code RTPChunk#isSafe}, but its
     * entity-ticking pipeline (mob spawning, AI, scheduled ticks) does not run,
     * so a player-less kept-cache chunk costs effectively zero MSPT. The
     * player's own arrival ticket promotes it to {@code ENTITY_TICKING} on
     * teleport without a regen, preserving immediacy. This mirrors the
     * 2026-05-08 1.20.1 fix (which dropped {@code 3 → 1} for the same reason)
     * and supersedes {@code rtp-fabric-ADR-006}'s {@code ENTITY_TICKING}
     * end-state for the kept cache. See
     * {@code rtp-fabric-ADR-016-kept-cache-non-entity-ticking.md}.</p>
     *
     * <p>Earlier revisions passed {@code 31} into this slot under the mistaken
     * belief it was the ticket level - that yielded effective level {@code 2}
     * which the chunk system clamps/rejects, leaving kept-cache entries unpinned
     * and silently evicted; {@code distance = 0} (level {@code 33}) sits exactly
     * on the eviction boundary and is avoided for the same drop-risk reason.
     * See {@code rtp-fabric-ADR-006-ticket-radius-and-non-expiring-type.md}.</p>
     */
    private static final int RTP_TICKET_DISTANCE = 1;

    private static TicketType<ChunkPos> ticketType() {
        TicketType<ChunkPos> t = RTP_TICKET_TYPE;
        if (t != null) return t;
        synchronized (V1_21_R1FabricVersionAdapter.class) {
            t = RTP_TICKET_TYPE;
            if (t != null) return t;
            // create(name, comparator, timeout) - non-persistent variant.
            t = TicketType.create("rtp", Comparator.comparingLong(ChunkPos::toLong), 0);
            RTP_TICKET_TYPE = t;
            return t;
        }
    }

    private static TicketType<ChunkPos> loadTicketType() {
        TicketType<ChunkPos> t = RTP_LOAD_TICKET_TYPE;
        if (t != null) return t;
        synchronized (V1_21_R1FabricVersionAdapter.class) {
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
                    "[RTP][Fabric 1.21.1] applyTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
    }

    @Override
    public void installEffectsDispatchers() {
        V1_21_R1FabricEffectDispatchers.install();
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
                    "[RTP][Fabric 1.21.1] releaseTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
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

    /**
     * Typed written-book modal for MC 1.21.0-1.21.4 (rtp-fabric-ADR-012 section 4
     * un-defer). Builds a {@code WRITTEN_BOOK} {@link net.minecraft.world.item.ItemStack}
     * carrying a {@link net.minecraft.world.item.component.WrittenBookContent}
     * data component (one styled {@link net.minecraft.network.chat.Component}
     * per page, fragments built by {@link FabricLegacyText#parseInteractive}),
     * sends it transiently to the player's held hotbar slot, opens the book,
     * then reverts the slot - the server-side inventory is never mutated.
     */
    @Override
    public boolean openBookMenu(Object serverPlayer, FabricBookSpec spec) {
        if (!(serverPlayer instanceof ServerPlayer sp) || spec == null) return false;
        try {
            java.util.List<net.minecraft.network.chat.Component> pageComponents =
                    new java.util.ArrayList<>(spec.pages().size());
            for (io.github.dailystruggle.rtp.api.menu.BookSpec.Page page : spec.pages()) {
                net.minecraft.network.chat.MutableComponent pageComp =
                        net.minecraft.network.chat.Component.empty();
                boolean firstLine = true;
                for (io.github.dailystruggle.rtp.api.menu.BookSpec.Line line : page.lines()) {
                    if (!firstLine) pageComp.append("\n");
                    firstLine = false;
                    for (io.github.dailystruggle.rtp.api.menu.BookSpec.Fragment frag : line.fragments()) {
                        pageComp.append(FabricLegacyText.parseInteractive(
                                frag.text(), frag.hover(), frag.runCommand(),
                                FabricLegacyText.ClickKind.RUN));
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

            int hotbar = sp.getInventory().selected;
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
            RTP.log(Level.WARNING, "[RTP][Fabric 1.21.1] openBookMenu failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }
}
