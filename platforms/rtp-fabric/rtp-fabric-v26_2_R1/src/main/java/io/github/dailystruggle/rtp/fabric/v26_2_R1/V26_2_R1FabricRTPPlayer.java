package io.github.dailystruggle.rtp.fabric.v26_2_R1;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * MC 26.1.2 RTPPlayer wrapper.
 *
 * <p>Lives in {@code rtp-fabric-V26_2_R1} (Loom unobfuscated plugin, no
 * intermediary remapping) so its class file's NM references survive linkage
 * on the deobfuscated 26.1.2 runtime — unlike {@code FabricRTPPlayer} in
 * {@code rtp-fabric-common}, whose intermediary aliases ({@code class_3222},
 * {@code class_2596}, ...) are absent at runtime and cause
 * {@link NoClassDefFoundError} on any class-linkage operation.
 *
 * <p>Behaviorally a slimmed-down port of {@link io.github.dailystruggle.rtp.fabric.player.FabricRTPPlayer}:
 * identity, op-level perm check, system-chat / actionbar / title sinks, and
 * teleport via {@code MinecraftServer#submit}. Cooldown / delay are 0.
 * Effects-api hover/click rich text is supported via
 * {@link #sendComponent(Component)}.
 */
public final class V26_2_R1FabricRTPPlayer implements RTPPlayer,
        io.github.dailystruggle.rtp.fabric.player.FabricInteractiveMessageSink,
        io.github.dailystruggle.rtp.fabric.player.FabricBookOpener,
        io.github.dailystruggle.rtp.fabric.player.FabricMapSink {

    private final UUID uuid;
    private final String name;
    private volatile @Nullable ServerPlayer handle;

    public V26_2_R1FabricRTPPlayer(ServerPlayer player) {
        this.uuid = player.getUUID();
        this.name = resolveName(player);
        this.handle = player;
    }

    private static String resolveName(ServerPlayer player) {
        // authlib's GameProfile is a record on MC 26.1+ — use name() (record component).
        try {
            return player.getGameProfile().name();
        } catch (NoSuchMethodError | NoSuchFieldError ignored) {
            // fall through
        } catch (Throwable ignored) {
            // fall through
        }
        return player.getName().getString();
    }

    public void rebind(ServerPlayer player) {
        this.handle = player;
    }

    public void unbind() {
        this.handle = null;
    }

    public @Nullable ServerPlayer handle() {
        return handle;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Opens the written-book modal on 26.x by delegating to the active
     * deobf carrier's {@code openBookMenu} with this player's deobf-linkable
     * {@code ServerPlayer}, keeping the raw handle private (this player does
     * not extend the common {@code FabricRTPPlayer}).
     */
    @Override
    public boolean openBookMenu(io.github.dailystruggle.rtp.fabric.menu.FabricBookSpec spec) {
        ServerPlayer p = handle;
        if (p == null || !isOnline() || spec == null) return false;
        io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
                io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
        if (adapter == null) return false;
        return adapter.openBookMenu(p, spec);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Renders the chart on 26.x by hopping to the server tick thread and
     * delegating to the active deobf carrier's {@code renderMapChart} with this
     * player's deobf-linkable {@code ServerPlayer} (this player does not extend
     * the common {@code FabricRTPPlayer}).
     */
    @Override
    public boolean renderMapChart(String chartKey, int[] argb, boolean locked, boolean deliverItem) {
        if (!isOnline() || chartKey == null || argb == null) return false;
        io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
                io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
        if (adapter == null || !adapter.supportsMapCharts()) return false;
        RTP.scheduler.runTask(() -> {
            ServerPlayer p = handle;
            if (p == null) return; // viewer went offline before the hop
            adapter.renderMapChart(p, chartKey, argb, locked, deliverItem);
        });
        return true;
    }

    @Override
    public void releaseMapChart(String chartKey) {
        io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
                io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
        if (adapter != null) adapter.releaseMapChart(chartKey);
    }

    @Override public UUID uuid() { return uuid; }
    @Override public String name() { return name; }

    @Override
    public boolean hasPermission(String permission) {
        ServerPlayer p = handle;
        if (p == null) return false;
        if (permission == null || permission.isEmpty()) return true;

        // PRIMARY: fabric-permissions-api via reflection. We avoid a direct call to
        // Permissions.getPermissionValue(UUID, String) because javac's overload resolution
        // would also load the sibling Entity (class_1297) overload, which is not on the
        // V26_2_R1 compile classpath under deobf mappings. Reflection sidesteps that.
        try {
            Class<?> permsCls = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            java.lang.reflect.Method m = permsCls.getMethod("getPermissionValue", UUID.class, String.class);
            Object cf = m.invoke(null, uuid, permission);
            if (cf instanceof java.util.concurrent.CompletableFuture) {
                @SuppressWarnings("unchecked")
                java.util.concurrent.CompletableFuture<Object> future =
                        (java.util.concurrent.CompletableFuture<Object>) cf;
                Class<?> tsCls = Class.forName("net.fabricmc.fabric.api.util.TriState");
                Object def = tsCls.getField("DEFAULT").get(null);
                Object state = future.getNow(def);
                Object trueVal = tsCls.getField("TRUE").get(null);
                Object falseVal = tsCls.getField("FALSE").get(null);
                if (state == trueVal) return true;
                if (state == falseVal) return false;
            }
        } catch (LinkageError | ClassNotFoundException | NoSuchMethodException | NoSuchFieldException ignored) {
            // perms-api jar not on runtime classpath — fall through to ops.json.
        } catch (Throwable ignored) {
            // defensive
        }

        // SECONDARY: consult the plugin.yml default table when no perms-api
        // implementer answered. Mirrors Bukkit descriptor-driven behaviour so
        // baseline player nodes (rtp.see / rtp.use, default true) work for
        // non-ops and rtp.onevent.* (default false) stay denied even for ops.
        io.github.dailystruggle.rtp.fabric.player.FabricDefaultPermissions.Verdict verdict =
                io.github.dailystruggle.rtp.fabric.player.FabricDefaultPermissions.resolve(permission);
        if (verdict == io.github.dailystruggle.rtp.fabric.player.FabricDefaultPermissions.Verdict.TRUE) return true;
        if (verdict == io.github.dailystruggle.rtp.fabric.player.FabricDefaultPermissions.Verdict.FALSE) return false;
        // verdict == OP — fall through to the ops.json scan below.

        // FALLBACK: scan ops.json for this UUID. Stable across MC versions.
        try {
            ServerLevel lvl = (ServerLevel) p.level();
            MinecraftServer srv = lvl == null ? null : lvl.getServer();
            if (srv != null) {
                java.io.File opsFile = srv.getPlayerList().getOps().getFile();
                if (opsFile != null && opsFile.isFile()) {
                    String body = new String(java.nio.file.Files.readAllBytes(opsFile.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8);
                    if (body.contains("\"" + uuid.toString() + "\"")) return true;
                }
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][V26_2_R1] hasPermission ops.json scan failed for " + name
                            + " (" + t.getClass().getSimpleName() + "): " + t.getMessage());
        }
        return false;
    }

    @Override
    public Set<String> getEffectivePermissions() {
        // rtp-fabric-ADR-011: LuckPerms-Fabric primary + closed-namespace registry probe.
        if (handle == null) return Collections.emptySet();
        boolean isOp = hasPermission("rtp.reload");
        return io.github.dailystruggle.rtp.fabric.player.FabricEffectivePermissionsResolver
                .resolve(uuid, isOp, this::hasPermission);
    }

    @Override
    public void sendMessage(String message) {
        if (message == null) return;
        sendComponent(V26_2_R1FabricLegacyText.parse(message));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Builds the hover/click {@link Component} via the v26-local
     * {@link V26_2_R1FabricLegacyText#parseInteractive} (whose NM references
     * link on the deobf 26.x runtime, unlike the common-module parser) and
     * delivers it - keeping the chat menu renderer's clicks and {@code /rtp
     * info} hovers alive on 26.x.
     */
    @Override
    public void sendInteractive(String message, String hover, String click, boolean run) {
        if (message == null) return;
        sendComponent(V26_2_R1FabricLegacyText.parseInteractive(message, hover, click, run));
    }

    public void sendComponent(Component component) {
        ServerPlayer p = handle;
        if (p == null || component == null) return;
        try {
            if (p.connection != null) {
                p.connection.send(new ClientboundSystemChatPacket(component, false));
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][V26_2_R1] Failed to deliver system message to " + name
                            + ": " + t.getMessage());
        }
    }

    /**
     * Send a title / subtitle pair with explicit fade/stay timings (ticks).
     *
     * <p>Mojmap-typed mirror of {@code FabricRTPPlayerUnobf#sendTitle} —
     * required for {@code RTPFabricMod}'s messages.yml title/subtitle/actionbar
     * hook to render on the deobf MC 26.1.x runtime (the common
     * {@code FabricRTPPlayer} class doesn't link there, so its sibling
     * implementation is unreachable). Empty / null strings are silently
     * dropped, mirroring Bukkit's {@code Player.sendTitle} semantics.
     */
    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        ServerPlayer p = handle;
        if (p == null) return;
        if ((title == null || title.isEmpty()) && (subtitle == null || subtitle.isEmpty())) return;
        try {
            if (p.connection == null) return;
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(
                    fadeIn, stay, fadeOut));
            if (subtitle != null && !subtitle.isEmpty()) {
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                        V26_2_R1FabricLegacyText.parse(subtitle)));
            }
            if (title != null && !title.isEmpty()) {
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                        V26_2_R1FabricLegacyText.parse(title)));
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][V26_2_R1] Failed to deliver title to " + name + ": " + t.getMessage());
        }
    }

    /**
     * Send an actionbar message. Mojmap-typed mirror of
     * {@code FabricRTPPlayerUnobf#sendActionbar}. Empty / null is silently dropped.
     */
    public void sendActionbar(String message) {
        ServerPlayer p = handle;
        if (p == null) return;
        if (message == null || message.isEmpty()) return;
        try {
            if (p.connection == null) return;
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                    V26_2_R1FabricLegacyText.parse(message)));
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][V26_2_R1] Failed to deliver actionbar to " + name + ": " + t.getMessage());
        }
    }

    @Override public long cooldown() { return 0L; }
    @Override public long delay() { return 0L; }

    @Override
    public void performCommand(@Nullable RTPPlayer player, String command) {
        ServerPlayer p = handle;
        if (p == null || command == null) return;
        ServerLevel lvl = (ServerLevel) p.level();
        MinecraftServer srv = lvl == null ? null : lvl.getServer();
        if (srv == null) return;
        try {
            srv.getCommands().performPrefixedCommand(p.createCommandSourceStack(), command);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][V26_2_R1] performCommand failed for " + name + ": " + t.getMessage());
        }
    }

    @Override
    public RTPCommandSender clone() {
        ServerPlayer p = handle;
        if (p == null) return new DetachedClone(uuid, name);
        return new V26_2_R1FabricRTPPlayer(p);
    }

    @Override
    public CompletableFuture<Boolean> setLocation(RTPLocation to) {
        ServerPlayer p = handle;
        if (p == null || to == null) return CompletableFuture.completedFuture(false);
        RTPWorld<?> rtpWorld = to.world();
        // V26 worlds extend RTPWorld<ServerLevel> directly (NOT common's
        // FabricRTPWorld, whose intermediary aliases don't link on the deobf
        // 26.1.2 runtime). Accept either: V26_2_R1FabricRTPWorld via direct
        // instanceof, or any other RTPWorld whose world() handle is a
        // ServerLevel (defensive — keeps the path working if a different
        // RTPWorld subtype is registered for the dimension).
        ServerLevel target;
        if (rtpWorld instanceof V26_2_R1FabricRTPWorld v26w) {
            target = v26w.world();
        } else if (rtpWorld != null && rtpWorld.world() instanceof ServerLevel sl) {
            target = sl;
        } else {
            return CompletableFuture.completedFuture(false);
        }
        MinecraftServer srv = target.getServer();
        if (srv == null) {
            ServerLevel here = (ServerLevel) p.level();
            srv = here == null ? null : here.getServer();
        }
        if (srv == null) return CompletableFuture.completedFuture(false);
        final double tx = to.getBlockX() + 0.5;
        final double ty = to.getBlockY();
        final double tz = to.getBlockZ() + 0.5;
        return srv.submit(() -> {
            ServerPlayer cur = handle;
            if (cur == null || cur.isRemoved()) return false;
            return performTeleport(cur, target, tx, ty, tz, cur.getYRot(), cur.getXRot());
        });
    }

    @Override
    public void setRespawnLocation(RTPLocation to) {
        // BetterRTP SetAsRespawn parity (26.x). Anchors the respawn point on the
        // server thread via reflective setRespawnPosition (the MC 26.x RespawnConfig
        // form is resolved at runtime). S-004: failures are logged, never swallowed.
        ServerPlayer p = handle;
        if (p == null || to == null) return;
        RTPWorld<?> rtpWorld = to.world();
        ServerLevel target;
        if (rtpWorld instanceof V26_2_R1FabricRTPWorld v26w) {
            target = v26w.world();
        } else if (rtpWorld != null && rtpWorld.world() instanceof ServerLevel sl) {
            target = sl;
        } else {
            return;
        }
        MinecraftServer srv = target.getServer();
        if (srv == null) {
            ServerLevel here = (ServerLevel) p.level();
            srv = here == null ? null : here.getServer();
        }
        if (srv == null) return;
        final int bx = to.getBlockX();
        final int by = to.getBlockY();
        final int bz = to.getBlockZ();
        final ServerLevel targetFinal = target;
        srv.submit(() -> {
            ServerPlayer cur = handle;
            if (cur == null || cur.isRemoved()) return Boolean.FALSE;
            anchorRespawn(cur, targetFinal, bx, by, bz);
            return Boolean.TRUE;
        });
    }

    private static void anchorRespawn(ServerPlayer cur, ServerLevel target, int x, int y, int z) {
        try {
            Object dim = target.dimension();
            Object pos = new net.minecraft.core.BlockPos(x, y, z);
            for (java.lang.reflect.Method m : ServerPlayer.class.getMethods()) {
                if (!m.getName().equals("setRespawnPosition")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 5) {
                    m.invoke(cur, dim, pos, 0.0f, true, false);
                    return;
                }
                if (pt.length == 2) {
                    Object cfg = buildRespawnConfig(pt[0], dim, pos);
                    if (cfg != null) {
                        m.invoke(cur, cfg, false);
                        return;
                    }
                }
            }
            RTP.log(Level.WARNING,
                "[RTP][V26_2_R1] no setRespawnPosition method on this MC version");
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][V26_2_R1] setRespawnPosition failed", t);
        }
    }

    private static Object buildRespawnConfig(Class<?> cfgClass, Object dim, Object pos) {
        try {
            for (java.lang.reflect.Constructor<?> c : cfgClass.getConstructors()) {
                Class<?>[] pt = c.getParameterTypes();
                Object[] args = new Object[pt.length];
                boolean ok = true;
                for (int i = 0; i < pt.length; i++) {
                    Class<?> pc = pt[i];
                    if (pc.isInstance(dim)) args[i] = dim;
                    else if (pc.isInstance(pos)) args[i] = pos;
                    else if (pc == float.class || pc == Float.class) args[i] = 0.0f;
                    else if (pc == boolean.class || pc == Boolean.class) args[i] = Boolean.TRUE;
                    else { ok = false; break; }
                }
                if (ok) return c.newInstance(args);
            }
        } catch (Throwable ignored) {
            // fall through; caller logs the overall failure
        }
        return null;
    }

    private static boolean performTeleport(ServerPlayer cur, ServerLevel target,
                                           double x, double y, double z,
                                           float yaw, float pitch) {
        // Reflective teleportTo(ServerLevel,double*4,float*2) — present on most MC
        // releases including 26.1.2.
        try {
            java.lang.reflect.Method m = ServerPlayer.class.getMethod(
                    "teleportTo", ServerLevel.class,
                    double.class, double.class, double.class, float.class, float.class);
            m.invoke(cur, target, x, y, z, yaw, pitch);
            return true;
        } catch (NoSuchMethodException ignored) {
            // fall through
        } catch (Throwable t) {
            RTP.log(Level.FINE,
                    "[RTP][V26_2_R1] reflective teleportTo(6) failed: " + t);
        }
        // Fallback: same-dim packet teleport.
        try {
            if (cur.level() == target) {
                cur.connection.teleport(x, y, z, yaw, pitch);
                cur.setYRot(yaw);
                cur.setXRot(pitch);
                return true;
            }
        } catch (Throwable t) {
            RTP.log(Level.FINE,
                    "[RTP][V26_2_R1] same-dim connection.teleport failed: " + t);
        }
        // Last resort: setPos.
        try {
            cur.setPos(x, y, z);
            cur.setYRot(yaw);
            cur.setXRot(pitch);
            if (cur.level() == target) {
                cur.connection.teleport(x, y, z, yaw, pitch);
                return true;
            }
            RTP.log(Level.WARNING,
                    "[RTP][V26_2_R1] cross-dim teleport requested but no stable cross-dim "
                            + "path on this MC version; pos was set in-place.");
            return false;
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][V26_2_R1] fallback teleport failed", t);
            return false;
        }
    }

    @Override
    public RTPLocation getLocation() {
        ServerPlayer p = handle;
        if (p == null) return null;
        ServerLevel level = (ServerLevel) p.level();
        if (level == null) return null;
        // On MC 26.1.2 (verified via javap on the deobf jar) the accessor is
        // dimension() — no `get` prefix — and on this MC release ResourceKey was
        // renamed location() -> identifier(). Direct typed calls because this v26
        // module compiles against Mojang names.
        String worldName;
        try {
            worldName = level.dimension().identifier().toString();
        } catch (Throwable t) {
            return null;
        }
        RTPWorld<?> rtpWorld = RTP.serverAccessor == null
                ? null
                : RTP.serverAccessor.getRTPWorld(worldName);
        if (rtpWorld == null) return null;
        return new RTPLocation(rtpWorld, p.getBlockX(), p.getBlockY(), p.getBlockZ());
    }

    @Override
    public boolean isOnline() {
        ServerPlayer p = handle;
        return p != null && !p.isRemoved();
    }

    /** Detached fallback used by {@link #clone()} when the handle has been released. */
    private static final class DetachedClone implements RTPPlayer {
        private final UUID uuid;
        private final String name;
        DetachedClone(UUID uuid, String name) { this.uuid = uuid; this.name = name; }
        @Override public UUID uuid() { return uuid; }
        @Override public String name() { return name; }
        @Override public boolean hasPermission(String permission) { return false; }
        @Override public Set<String> getEffectivePermissions() { return Collections.emptySet(); }
        @Override public void sendMessage(String message) { /* offline */ }
        @Override public long cooldown() { return 0L; }
        @Override public long delay() { return 0L; }
        @Override public void performCommand(@Nullable RTPPlayer p, String c) { /* offline */ }
        @Override public RTPCommandSender clone() { return this; }
        @Override public CompletableFuture<Boolean> setLocation(RTPLocation to) {
            return CompletableFuture.completedFuture(false);
        }
        @Override public RTPLocation getLocation() { return null; }
        @Override public boolean isOnline() { return false; }
    }
}
