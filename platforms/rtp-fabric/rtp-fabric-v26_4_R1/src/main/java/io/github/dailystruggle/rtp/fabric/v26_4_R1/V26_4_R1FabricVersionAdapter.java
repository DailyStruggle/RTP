package io.github.dailystruggle.rtp.fabric.v26_4_R1;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.fabric.unobf.effects.FabricEffectsHandlerUnobf;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import io.github.dailystruggle.rtp.fabric.version.RTPChunkHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle;
import io.github.dailystruggle.rtp.common.RTP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * MC 26.4 implementation of {@link FabricVersionAdapter}.
 */
public final class V26_4_R1FabricVersionAdapter implements FabricVersionAdapter {

    private static final int RTP_TICKET_RADIUS = 1;
    private static final int RTP_TICKET_FLAGS =
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION;
    private static final TicketType RTP_TICKET_TYPE =
            new TicketType(TicketType.NO_TIMEOUT, RTP_TICKET_FLAGS);

    private static final TicketType RTP_TEMP_LOAD_TICKET_TYPE =
            new TicketType(600L, RTP_TICKET_FLAGS);

    @Override
    public String mcVersion() {
        return "26.4";
    }

    @Override
    public CompletableFuture<RTPChunkHandle> getChunkFull(RTPLevelHandle level, int cx, int cz) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("V26_4_R1 getChunkFull not implemented; use requestFullChunkAsync"));
    }

    private static final Object GET_CHUNK_FUTURE_MONITOR = new Object();
    private static volatile java.lang.reflect.Method GET_CHUNK_FUTURE_METHOD;

    private static java.lang.reflect.Method resolveGetChunkFutureMethod(ServerChunkCache cache)
            throws ReflectiveOperationException {
        java.lang.reflect.Method cached = GET_CHUNK_FUTURE_METHOD;
        if (cached != null) return cached;
        synchronized (GET_CHUNK_FUTURE_MONITOR) {
            cached = GET_CHUNK_FUTURE_METHOD;
            if (cached != null) return cached;
            java.lang.reflect.Method found = null;
            String[] preferredNames = { "getChunkFuture", "getChunkFutureMainThread" };
            for (String name : preferredNames) {
                for (Class<?> c = cache.getClass(); c != null && found == null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
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
                for (Class<?> c = cache.getClass(); c != null && found == null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
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
            GET_CHUNK_FUTURE_METHOD = found;
            return found;
        }
    }

    @Override
    public CompletableFuture<RTPChunkHandle> requestFullChunkAsync(RTPLevelHandle level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("null ServerLevel"));
        }
        final ServerLevel sl;
        try {
            sl = level.as(ServerLevel.class);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
        final MinecraftServer server = sl.getServer();
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("null MinecraftServer"));
        }

        final ChunkPos ticketPos = new ChunkPos(cx, cz);
        return server.submit(() -> tryAddTempLoadTicket(sl, ticketPos))
                .thenComposeAsync(ticketAdded -> {
                    CompletableFuture<RTPChunkHandle> cf = requestFullChunkAsyncRaw(sl, cx, cz);
                    return cf.whenComplete((handle, ex) -> {
                        if (Boolean.TRUE.equals(ticketAdded)) {
                            scheduleTempLoadTicketRemoval(sl, ticketPos);
                        }
                    });
                }, RTP_CONTINUATION_EXECUTOR);
    }

    private static final java.util.concurrent.Executor RTP_CONTINUATION_EXECUTOR =
            r -> RTP.scheduler.runTaskAsynchronously(r);

    private CompletableFuture<RTPChunkHandle> requestFullChunkAsyncRaw(ServerLevel level, int cx, int cz) {
        try {
            ServerChunkCache cache = level.getChunkSource();
            java.lang.reflect.Method getter = resolveGetChunkFutureMethod(cache);
            Object raw = getter.invoke(cache, cx, cz, ChunkStatus.FULL, /*create=*/ true);
            if (!(raw instanceof CompletableFuture<?> cf)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "getChunkFuture returned non-CompletableFuture: "
                                + (raw == null ? "null" : raw.getClass())));
            }
            return cf.thenApply(either -> {
                ChunkAccess chunk = (either == null) ? null : unwrapEitherLeft(either);
                return chunk == null ? null : RTPChunkHandle.of(chunk);
            });
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    private static ChunkAccess unwrapEitherLeft(Object either) {
        try {
            if (either instanceof ChunkAccess ca) return ca;
            try {
                java.lang.reflect.Method orElse = either.getClass().getMethod("orElse", Object.class);
                Object value = orElse.invoke(either, (Object) null);
                if (value instanceof ChunkAccess ca2) return ca2;
            } catch (NoSuchMethodException _) {
                // not Optional-shaped; fall through to Either#left
            }
            java.lang.reflect.Method leftMethod = either.getClass().getMethod("left");
            Object opt = leftMethod.invoke(either);
            if (opt == null) return null;
            java.lang.reflect.Method orElse = opt.getClass().getMethod("orElse", Object.class);
            Object value = orElse.invoke(opt, (Object) null);
            return (value instanceof ChunkAccess ca) ? ca : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean tryAddTempLoadTicket(ServerLevel level, ChunkPos cp) {
        try {
            level.getChunkSource().addTicketWithRadius(RTP_TEMP_LOAD_TICKET_TYPE, cp, RTP_TICKET_RADIUS);
            return true;
        } catch (Throwable t) {
            RTP.log(Level.FINE,
                    "[RTP][Fabric 26.4.x] temp load-ticket apply failed for chunk="
                            + cp + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private void scheduleTempLoadTicketRemoval(ServerLevel level, ChunkPos cp) {
        MinecraftServer server = level.getServer();
        Runnable remove = () -> {
            try {
                level.getChunkSource().removeTicketWithRadius(RTP_TEMP_LOAD_TICKET_TYPE, cp, RTP_TICKET_RADIUS);
            } catch (Throwable t) {
                RTP.log(Level.FINE,
                        "[RTP][Fabric 26.4.x] temp load-ticket release failed for chunk="
                                + cp + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        };
        if (server != null && !server.isSameThread()) {
            server.execute(remove);
        } else {
            remove.run();
        }
    }

    @Override
    public void installEffectsDispatchers() {
        V26_4_R1FabricEffectDispatchers.install();
    }

    @Override
    public boolean installEffectsWiring(Object server) {
        if (!(server instanceof MinecraftServer mc)) {
            RTP.log(Level.WARNING,
                    "[RTP] V26_4_R1 installEffectsWiring received non-MinecraftServer: "
                            + (server == null ? "null" : server.getClass().getName()));
            return false;
        }
        FabricEffectsHandlerUnobf.setupEffects(mc);
        return true;
    }

    private io.github.dailystruggle.rtp.fabric.unobf.server.FabricProgressBarsUnobf progressBars;

    @Override
    public boolean supportsProgressBars() {
        return true;
    }

    @Override
    public void dispatchProgressBars(Object server,
                                     java.util.Map<String, io.github.dailystruggle.rtp.api.server.ProgressBar> bars,
                                     java.util.function.Function<String, java.util.Set<java.util.UUID>> eligibleViewers) {
        if (!(server instanceof MinecraftServer mc)) {
            clearProgressBars();
            return;
        }
        if (progressBars == null) {
            progressBars = new io.github.dailystruggle.rtp.fabric.unobf.server.FabricProgressBarsUnobf();
        }
        progressBars.update(mc, eligibleViewers, bars);
    }

    @Override
    public void clearProgressBars() {
        if (progressBars != null) progressBars.clear();
    }

    @Override
    public @Nullable Object extractPlayerFromConnection(Object handler) {
        if (!(handler instanceof ServerGamePacketListenerImpl impl)) return null;
        return impl.getPlayer();
    }

    @Override
    public java.util.@Nullable UUID getPlayerUUID(Object player) {
        if (!(player instanceof ServerPlayer sp)) return null;
        return sp.getUUID();
    }

    @Override
    public @Nullable RTPPlayer createPlayer(Object serverPlayer) {
        if (!(serverPlayer instanceof ServerPlayer sp)) return null;
        return new V26_4_R1FabricRTPPlayer(sp);
    }

    @Override
    public boolean teleport(Object player, Object targetLevel,
                            double x, double y, double z, float yaw, float pitch) {
        if (!(player instanceof ServerPlayer sp) || !(targetLevel instanceof ServerLevel sl)) {
            return false;
        }
        try {
            net.minecraft.world.phys.Vec3 pos = new net.minecraft.world.phys.Vec3(x, y, z);
            net.minecraft.world.phys.Vec3 delta = net.minecraft.world.phys.Vec3.ZERO;
            java.util.Set<net.minecraft.world.entity.Relative> rel = java.util.Collections.emptySet();
            net.minecraft.world.level.portal.TeleportTransition transition =
                    new net.minecraft.world.level.portal.TeleportTransition(
                            sl, pos, delta, yaw, pitch, rel,
                            net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING);
            ServerPlayer result = sp.teleport(transition);
            if (result == null) {
                RTP.log(Level.WARNING,
                        "[RTP][Fabric 26.4.x] teleport returned null (destination rejected by vanilla)");
                return false;
            }
            return true;
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][Fabric 26.4.x] teleport failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    @Override
    public void rebindPlayer(RTPPlayer existing, Object serverPlayer) {
        if (existing instanceof V26_4_R1FabricRTPPlayer wrapper
                && serverPlayer instanceof ServerPlayer sp) {
            wrapper.rebind(sp);
        }
    }

    @Override
    public @Nullable RTPWorld<?> createWorld(Object serverLevel) {
        if (!(serverLevel instanceof ServerLevel sl)) return null;
        return new V26_4_R1FabricRTPWorld(sl);
    }

    @Override
    public @Nullable io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder
            createNativeWorldBorder(Object serverLevel) {
        if (!(serverLevel instanceof ServerLevel sl)) return null;
        net.minecraft.world.level.border.WorldBorder mcBorder = sl.getWorldBorder();
        return new io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder(
                () -> {
                    io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> shape =
                            (io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?>)
                                    io.github.dailystruggle.rtp.common.RTP.factoryMap
                                            .get(io.github.dailystruggle.rtp.common.RTP.factoryNames.shape)
                                            .get("SQUARE");
                    if (shape instanceof io.github.dailystruggle.rtp.common.selection
                            .region.selectors.memory.shapes.Square square) {
                        square.set(io.github.dailystruggle.rtp.common.selection.region
                                        .selectors.memory.shapes.enums.GenericMemoryShapeParams.radius,
                                (long) (mcBorder.getSize() / 32.0));
                        square.set(io.github.dailystruggle.rtp.common.selection.region
                                        .selectors.memory.shapes.enums.GenericMemoryShapeParams.centerX,
                                (long) (mcBorder.getCenterX() / 16.0));
                        square.set(io.github.dailystruggle.rtp.common.selection.region
                                        .selectors.memory.shapes.enums.GenericMemoryShapeParams.centerZ,
                                (long) (mcBorder.getCenterZ() / 16.0));
                    }
                    return shape;
                },
                rtpLocation -> sl.getWorldBorder().isWithinBounds(
                        (double) rtpLocation.x(), (double) rtpLocation.z()));
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
                    "[RTP][Fabric 26.4.x] applyTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
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

    @Override
    public @Nullable java.util.UUID resolveSenderUuid(Object src) {
        if (!(src instanceof net.minecraft.commands.CommandSourceStack css)) return null;
        net.minecraft.world.entity.Entity entity = css.getEntity();
        if (!(entity instanceof ServerPlayer sp)) return null;
        return sp.getUUID();
    }

    @Override
    public boolean openBookMenu(Object serverPlayer, io.github.dailystruggle.rtp.fabric.menu.FabricBookSpec spec) {
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
                        pageComp.append(V26_4_R1FabricLegacyText.parseInteractive(
                                frag.text(), frag.hover(), frag.runCommand(), true));
                    }
                }
                pageComponents.add(pageComp);
            }
            if (pageComponents.isEmpty()) {
                pageComponents.add(net.minecraft.network.chat.Component.empty());
            }

            java.util.List<net.minecraft.server.network.Filterable<net.minecraft.network.chat.Component>> filtered =
                    pageComponents.stream()
                            .map(net.minecraft.server.network.Filterable::passThrough)
                            .toList();
            net.minecraft.world.item.component.WrittenBookContent content =
                    new net.minecraft.world.item.component.WrittenBookContent(
                            net.minecraft.server.network.Filterable.passThrough(spec.title()),
                            sp.getName().getString(),
                            0,
                            filtered,
                            true);
            net.minecraft.world.item.ItemStack book =
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITTEN_BOOK);
            book.set(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT, content);

            net.minecraft.world.InteractionHand bookHand = net.minecraft.world.InteractionHand.MAIN_HAND;
            sp.openItemGui(book, bookHand);
            return true;
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][Fabric 26.4.x] openBookMenu failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<String, net.minecraft.world.level.saveddata.maps.MapId> mapIds =
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
        if (!(serverPlayer instanceof ServerPlayer sp)
                || chartKey == null || argb == null) {
            return false;
        }
        try {
            ServerLevel level = sp.level();

            net.minecraft.world.level.saveddata.maps.MapId id = mapIds.get(chartKey);
            if (id == null) {
                id = level.getFreeMapId();
                mapIds.put(chartKey, id);
            }

            int side = 128;
            int n = Math.min(argb.length, side * side);
            byte[] patchColors = new byte[side * side];
            for (int i = 0; i < n; i++) {
                int pixel = argb[i];
                int alpha = (pixel >>> 24) & 0xFF;
                byte packed = (alpha == 0)
                        ? 0
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
                    // Inventory full: drop at the player's feet. In 26.4, drop method signature
                    // accepts Prediction or fallback reflectively.
                    dropItemAtPlayer(sp, map);
                }
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][Fabric 26.4.x] renderMapChart failed for chartKey="
                    + chartKey + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
        return true;
    }

    private static void dropItemAtPlayer(ServerPlayer sp, net.minecraft.world.item.ItemStack stack) {
        try {
            for (java.lang.reflect.Method m : ServerPlayer.class.getMethods()) {
                if (!m.getName().equals("drop")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 3 && pt[0].isAssignableFrom(stack.getClass()) && pt[1] == boolean.class) {
                    m.invoke(sp, stack, false, null);
                    return;
                }
                if (pt.length == 2 && pt[0].isAssignableFrom(stack.getClass()) && pt[1] == boolean.class) {
                    m.invoke(sp, stack, false);
                    return;
                }
            }
        } catch (Throwable t) {
            RTP.log(Level.FINE, "[RTP][Fabric 26.4.x] dropItemAtPlayer reflective drop failed: " + t);
        }
    }

    private static byte matchColor(int r, int g, int b) {
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int packed = 0; packed < 256; packed++) {
            net.minecraft.world.level.material.MapColor color =
                    net.minecraft.world.level.material.MapColor.byId(packed >> 2);
            if (color.id == 0) continue;
            int rgb = net.minecraft.world.level.material.MapColor.getColorFromPackedId(packed);
            int rr = (rgb >> 16) & 0xFF;
            int gg = (rgb >> 8) & 0xFF;
            int bb = rgb & 0xFF;
            long dr = (long) r - rr;
            long dg = (long) g - gg;
            long db = (long) b - bb;
            long dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = packed;
            }
        }
        return (byte) best;
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
                    "[RTP][Fabric 26.4.x] releaseTicket failed for chunk=(" + cx + "," + cz + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
    }
}
