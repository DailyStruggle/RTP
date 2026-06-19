package io.github.dailystruggle.rtp.fabric.v1_21_R5;

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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.tags.TagKey;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
     * {@code 33 - radius}). {@code radius = 1} resolves to effective level
     * {@code 32} ({@code FULL}/BORDER but below {@code ENTITY_TICKING}): the
     * chunk stays pinned and block-readable for {@code RTPChunk#isSafe}, but
     * its entity-ticking pipeline (mob spawning, AI, scheduled ticks) does not
     * run, so a player-less kept-cache chunk costs effectively zero MSPT. The
     * player's own arrival ticket promotes it to {@code ENTITY_TICKING} on
     * teleport without a regen, preserving immediacy. This supersedes
     * {@code rtp-fabric-ADR-006}'s {@code ENTITY_TICKING} end-state for the
     * kept cache; see
     * {@code rtp-fabric-ADR-016-kept-cache-non-entity-ticking.md}.</p>
     *
     * <p>Earlier revisions of this adapter passed {@code 31} as the radius
     * under the mistaken belief it was a ticket level — that would have
     * force-loaded a {@code (2*31+1)² = 3969}-chunk square per kept
     * location, which the chunk system clamps/rejects, leaving kept-cache
     * entries unpinned and silently evicted; {@code radius = 0} (level
     * {@code 33}) sits exactly on the eviction boundary and is avoided for the
     * same drop-risk reason. See
     * {@code rtp-fabric-ADR-006-ticket-radius-and-non-expiring-type.md}.</p>
     */
    private static final int RTP_TICKET_RADIUS = 1;

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


    /**
     * Typed block-tag snapshot for MC 1.21.5+ (rtp-fabric-ADR-010). Walks
     * {@link BuiltInRegistries#BLOCK} via Loom-mapped types — no reflection —
     * and inverts each block's {@code builtInRegistryHolder().tags()} stream
     * into the {@code namespace:path -> upper-case "namespace:path"} multimap
     * shape documented on {@link FabricVersionAdapter#snapshotBlockTags()}.
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
                try { holder = block.builtInRegistryHolder(); } catch (Throwable t) { continue; }
                if (holder == null) continue;
                java.util.stream.Stream<TagKey<Block>> tagStream;
                try { tagStream = holder.tags(); } catch (Throwable t) { continue; }
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
     * Typed override — direct {@code MinecraftServer.getCommands().performPrefixedCommand(
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
     * Typed cross-dimension teleport for MC 1.21.5+. Drives
     * {@code ServerPlayer#teleport(TeleportTransition)} — Loom remaps the
     * {@code TeleportTransition} constructor and {@code teleport} descriptors
     * to the correct intermediary symbols at compile time. This single call
     * handles both same-dimension and cross-dimension destinations and resets
     * the server-side movement check, so it replaces the common module's
     * reflective {@code teleportTo(6)} / packet-teleport fallback (which cannot
     * change dimensions and trips the "moved too quickly" warning).
     */
    @Override
    public boolean teleport(Object serverPlayer, Object serverLevel,
                            double x, double y, double z, float yaw, float pitch) {
        if (!(serverPlayer instanceof ServerPlayer sp)) return false;
        if (!(serverLevel instanceof ServerLevel target)) return false;
        try {
            net.minecraft.world.level.portal.TeleportTransition transition =
                    new net.minecraft.world.level.portal.TeleportTransition(
                            target,
                            new net.minecraft.world.phys.Vec3(x, y, z),
                            net.minecraft.world.phys.Vec3.ZERO,
                            yaw, pitch,
                            net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING);
            sp.teleport(transition);
            return true;
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][Fabric 1.21.5+] teleport failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Typed written-book modal for MC 1.21.5+ (rtp-fabric-ADR-012 §4
     * un-defer). Mirrors the v1_21_R1 implementation; see that class for the
     * transient-slot rationale.
     */
    @Override
    public boolean openBookMenu(Object serverPlayer, FabricBookSpec spec) {
        if (!(serverPlayer instanceof ServerPlayer sp) || spec == null) return false;
        try {
            java.util.List<net.minecraft.network.chat.Component> pageComponents =
                    new java.util.ArrayList<>(spec.pages().size());
            for (FabricBookSpec.Page page : spec.pages()) {
                net.minecraft.network.chat.MutableComponent pageComp =
                        net.minecraft.network.chat.Component.empty();
                boolean firstLine = true;
                for (FabricBookSpec.Line line : page.lines()) {
                    if (!firstLine) pageComp.append("\n");
                    firstLine = false;
                    for (FabricBookSpec.Fragment frag : line.fragments()) {
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
            RTP.log(Level.WARNING, "[RTP][Fabric 1.21.5+] openBookMenu failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }
}
