package io.github.dailystruggle.rtp.neoforge.player;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.neoforge.menu.NeoForgeBookSpec;
import io.github.dailystruggle.rtp.neoforge.tools.NeoForgeLegacyText;
import io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapter;
import io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapterRegistry;
import io.github.dailystruggle.rtp.neoforge.world.NeoForgeRTPWorld;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * NeoForge implementation of {@link RTPPlayer} (N2.1 scope), the NeoForge
 * analogue of {@code FabricRTPPlayer} trimmed to the platform-core surface:
 * identity, messaging (chat / title / action-bar), teleport, location, online
 * status, and op-level permission resolution.
 *
 * <p><b>Deferred to later sub-phases:</b> full permission resolution
 * (NeoForge {@code PermissionAPI} / LuckPerms, N2.5) and the book/menu/map
 * sinks (N2.6). {@link #getEffectivePermissions()} returns an empty set for now
 * and {@link #hasPermission(String)} falls back to the on-disk {@code ops.json}
 * op-level check.</p>
 *
 * <p>Mojmap-at-runtime: compiles directly against {@code net.minecraft}; no
 * obf/intermediary drift workarounds are needed beyond the version-robust
 * teleport fallbacks kept for the 1.21.x carrier line. No {@code org.bukkit.*}
 * imports.</p>
 */
public final class NeoForgeRTPPlayer implements RTPPlayer, NeoForgeBookOpener, NeoForgeMapSink {

    private final UUID uuid;
    private final String name;
    private volatile @Nullable ServerPlayer handle;

    public NeoForgeRTPPlayer(ServerPlayer player) {
        this.uuid = player.getUUID();
        this.name = resolveName(player);
        this.handle = player;
    }

    private static String resolveName(ServerPlayer player) {
        try {
            return player.getGameProfile().getName();
        } catch (NoSuchMethodError ignored) {
            // authlib record-shape: no getName(); fall through to Entity name.
        }
        return player.getName().getString();
    }

    /** Called by the event bridge when a player rejoins; refreshes the handle. */
    public void rebind(ServerPlayer player) {
        this.handle = player;
    }

    /** @return the underlying Mojmap {@link ServerPlayer} handle, or {@code null} if disconnected. */
    public @Nullable ServerPlayer handle() {
        return handle;
    }

    /** Called by the event bridge on disconnect; subsequent calls treat the player as offline. */
    public void unbind() {
        this.handle = null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to the active {@link NeoForgeVersionAdapter#openBookMenu}
     * with this player's {@link ServerPlayer} handle, keeping the raw
     * {@code ServerPlayer} private to this class. Carriers that implement the
     * book modal return {@code true}; carriers that keep the SPI default
     * {@code false} cause the renderer to fall back to chat.
     */
    @Override
    public boolean openBookMenu(NeoForgeBookSpec spec) {
        ServerPlayer p = handle;
        if (p == null || !isOnline() || spec == null) return false;
        NeoForgeVersionAdapter adapter = NeoForgeVersionAdapterRegistry.peek();
        if (adapter == null) return false;
        return adapter.openBookMenu(p, spec);
    }

    /**
     * Render an RTP chart onto a vanilla filled-map for this player and ship it
     * (maps-api parity). Mirrors {@link #openBookMenu}: the maps path flows
     * player-first, so the raw {@link ServerPlayer} handle never leaves this
     * class. Resolves the active {@link NeoForgeVersionAdapter}, hops to the
     * server tick thread via {@code RTP.scheduler} (map saved-data allocation,
     * packet dispatch, and inventory mutation all require server-thread
     * affinity), then delegates to the carrier's NM-free seam with the live
     * handle.
     */
    @Override
    public boolean renderMapChart(String chartKey, int[] argb, boolean locked, boolean deliverItem) {
        if (!isOnline() || chartKey == null || argb == null) return false;
        NeoForgeVersionAdapter adapter = NeoForgeVersionAdapterRegistry.peek();
        if (adapter == null || !adapter.supportsMapCharts()) return false;
        RTP.scheduler.runTask(() -> {
            ServerPlayer p = handle;
            if (p == null) return; // viewer went offline before the hop
            adapter.renderMapChart(p, chartKey, argb, locked, deliverItem);
        });
        return true;
    }

    /**
     * Release any per-chart cache the active carrier holds for {@code chartKey}
     * (REQ-RTP-MAP-003). Pure server-side cache cleanup keyed by the chart id.
     */
    @Override
    public void releaseMapChart(String chartKey) {
        NeoForgeVersionAdapter adapter = NeoForgeVersionAdapterRegistry.peek();
        if (adapter != null) adapter.releaseMapChart(chartKey);
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean hasPermission(String permission) {
        ServerPlayer p = handle;
        if (p == null) return false;
        if (permission == null || permission.isEmpty()) return true;
        // N2.5: PRIMARY — consult LuckPerms (if present) FIRST so explicit
        // grants and denials win over the plugin.yml defaults and the op-level
        // check. The reflective enumerator routes through LuckPerms' cached
        // permission data (wildcard- and inheritance-aware) and degrades to a
        // null verdict when LuckPerms is absent or the user is uncached.
        Boolean lp = LuckPermsNeoForgeEnumerator.checkPermission(uuid, permission);
        if (lp != null) return lp;
        // SECONDARY: consult the plugin.yml default table so nodes like
        // rtp.see / rtp.use (default true) work for non-ops and rtp.onevent.*
        // (default false) stay denied even for ops, matching plugin.yml.
        NeoForgeDefaultPermissions.Verdict verdict = NeoForgeDefaultPermissions.resolve(permission);
        if (verdict == NeoForgeDefaultPermissions.Verdict.TRUE) return true;
        if (verdict == NeoForgeDefaultPermissions.Verdict.FALSE) return false;
        // TERTIARY: op-level fallback via the on-disk ops.json (UUID scan).
        // ops.json is a stable on-disk JSON array of {uuid,name,level}.
        boolean opFallback = false;
        try {
            ServerLevel lvl = resolveServerLevel(p);
            net.minecraft.server.MinecraftServer srv = lvl == null ? null : lvl.getServer();
            if (srv != null) {
                java.io.File opsFile = srv.getPlayerList().getOps().getFile();
                if (opsFile != null && opsFile.isFile()) {
                    String body = new String(java.nio.file.Files.readAllBytes(opsFile.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8);
                    if (body.contains("\"" + uuid.toString() + "\"")) {
                        opFallback = true;
                    }
                }
            }
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] hasPermission op-check via ops.json scan failed for "
                            + name + " (" + t.getClass().getSimpleName() + "): " + t.getMessage());
        }
        return opFallback;
    }

    @Override
    public Set<String> getEffectivePermissions() {
        // N2.5: LuckPerms primary path with closed-namespace (rtp.effect.*,
        // rtp.onevent.*) registry-probe fallback (rtp-fabric-ADR-011 port). Op
        // status is detected by probing a known default:op node through
        // hasPermission so the LuckPerms verdict / ops.json scan is honoured
        // without duplicating logic.
        if (handle == null) return Collections.emptySet();
        boolean isOp = hasPermission("rtp.reload");
        return NeoForgeEffectivePermissionsResolver.resolve(uuid, isOp, this::hasPermission);
    }

    @Override
    public void sendMessage(String message) {
        if (message == null) return;
        sendComponent(NeoForgeLegacyText.parse(message));
    }

    /**
     * Dispatch a pre-built {@link Component} to this player via the system-chat
     * packet path so hover/click annotations survive end-to-end.
     */
    public void sendComponent(Component component) {
        ServerPlayer p = handle;
        if (p == null || component == null) return;
        try {
            if (p.connection != null) {
                p.connection.send(new ClientboundSystemChatPacket(component, false));
            }
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] Failed to deliver system message to " + name + ": " + t.getMessage());
        }
    }

    /** Send a title / subtitle pair with explicit fade/stay timings (ticks). */
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
                        NeoForgeLegacyText.parse(subtitle)));
            }
            if (title != null && !title.isEmpty()) {
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                        NeoForgeLegacyText.parse(title)));
            }
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] Failed to deliver title to " + name + ": " + t.getMessage());
        }
    }

    /** Send an actionbar message (text hovering above the hotbar). */
    public void sendActionbar(String message) {
        ServerPlayer p = handle;
        if (p == null) return;
        if (message == null || message.isEmpty()) return;
        try {
            if (p.connection == null) return;
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                    NeoForgeLegacyText.parse(message)));
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] Failed to deliver actionbar to " + name + ": " + t.getMessage());
        }
    }

    @Override
    public long cooldown() {
        return 0L;
    }

    @Override
    public long delay() {
        return 0L;
    }

    @Override
    public void performCommand(@Nullable RTPPlayer player, String command) {
        ServerPlayer p = handle;
        if (p == null || command == null) return;
        ServerLevel lvl = resolveServerLevel(p);
        net.minecraft.server.MinecraftServer srv = lvl == null ? null : lvl.getServer();
        if (srv == null) return;
        net.minecraft.commands.CommandSourceStack source = buildCommandSource(p, lvl, srv);
        if (source == null) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] performCommand: could not build a command source for " + name
                            + "; '" + command + "' not dispatched");
            return;
        }
        srv.getCommands().performPrefixedCommand(source, command);
    }

    @Nullable
    private net.minecraft.commands.CommandSourceStack buildCommandSource(
            ServerPlayer p, ServerLevel lvl, net.minecraft.server.MinecraftServer srv) {
        try {
            return p.createCommandSourceStack();
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.FINE,
                    "[RTP][trace] NeoForgeRTPPlayer createCommandSourceStack() drift: " + t);
        }
        try {
            net.minecraft.commands.CommandSourceStack base = srv.createCommandSourceStack();
            return base.withEntity(p);
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.FINE,
                    "[RTP][trace] NeoForgeRTPPlayer server.createCommandSourceStack().withEntity drift: " + t);
        }
        try {
            net.minecraft.world.phys.Vec3 pos = safePosition(p);
            net.minecraft.world.phys.Vec2 rot = safeRotation(p);
            net.minecraft.network.chat.Component display = safeDisplayName(p);
            return new net.minecraft.commands.CommandSourceStack(
                    p, pos, rot, lvl, 4, name, display, srv, p);
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP][trace] NeoForgeRTPPlayer manual CommandSourceStack build failed", t);
            return null;
        }
    }

    private static net.minecraft.world.phys.Vec3 safePosition(ServerPlayer p) {
        try {
            return p.position();
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            return new net.minecraft.world.phys.Vec3(p.getX(), p.getY(), p.getZ());
        } catch (Throwable ignored) {
            // fall through
        }
        return net.minecraft.world.phys.Vec3.ZERO;
    }

    private static net.minecraft.world.phys.Vec2 safeRotation(ServerPlayer p) {
        try {
            return new net.minecraft.world.phys.Vec2(p.getXRot(), p.getYRot());
        } catch (Throwable ignored) {
            return net.minecraft.world.phys.Vec2.ZERO;
        }
    }

    private net.minecraft.network.chat.Component safeDisplayName(ServerPlayer p) {
        try {
            return p.getName();
        } catch (Throwable ignored) {
            return net.minecraft.network.chat.Component.literal(name == null ? "" : name);
        }
    }

    @Override
    public RTPCommandSender clone() {
        ServerPlayer p = handle;
        if (p == null) {
            return new DetachedClone(uuid, name);
        }
        return new NeoForgeRTPPlayer(p);
    }

    @Override
    public CompletableFuture<Boolean> setLocation(RTPLocation to) {
        ServerPlayer p = handle;
        if (p == null || to == null) {
            return CompletableFuture.completedFuture(false);
        }
        RTPWorld<?> rtpWorld = to.world();
        if (!(rtpWorld instanceof NeoForgeRTPWorld nw)) {
            return CompletableFuture.completedFuture(false);
        }
        ServerLevel target = nw.level();
        net.minecraft.server.MinecraftServer srv = target.getServer();
        if (srv == null) {
            ServerLevel here = resolveServerLevel(p);
            srv = here == null ? null : here.getServer();
        }
        if (srv == null) {
            return CompletableFuture.completedFuture(false);
        }
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
        // BetterRTP SetAsRespawn parity. Anchors the respawn point on the server
        // thread via reflective setRespawnPosition (signature drift across the
        // 1.21.x carrier line). S-004: failures are logged, never swallowed.
        ServerPlayer p = handle;
        if (p == null || to == null) return;
        RTPWorld<?> rtpWorld = to.world();
        if (!(rtpWorld instanceof NeoForgeRTPWorld nw)) return;
        ServerLevel target = nw.level();
        if (target == null) return;
        net.minecraft.server.MinecraftServer srv = target.getServer();
        if (srv == null) {
            ServerLevel here = resolveServerLevel(p);
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
            RTP.log(java.util.logging.Level.WARNING,
                "[RTP][NeoForge] no setRespawnPosition method on this MC version");
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING,
                "[RTP][NeoForge] setRespawnPosition failed", t);
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

    /**
     * Teleport implementation that survives mapping drift across the 1.21.x
     * carrier line. Attempts the reflective 6-arg {@code teleportTo} first, then
     * a same-dimension packet teleport, then a position-set fallback.
     */
    private static boolean performTeleport(ServerPlayer cur, ServerLevel target,
                                           double x, double y, double z,
                                           float yaw, float pitch) {
        try {
            java.lang.reflect.Method m = ServerPlayer.class.getMethod(
                    "teleportTo", ServerLevel.class,
                    double.class, double.class, double.class, float.class, float.class);
            m.invoke(cur, target, x, y, z, yaw, pitch);
            return true;
        } catch (NoSuchMethodException ignored) {
            // fall through
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.FINE,
                    "[RTP][trace] NeoForgeRTPPlayer reflective teleportTo(6) failed: " + t);
        }

        try {
            if (resolveServerLevel(cur) == target) {
                cur.connection.teleport(x, y, z, yaw, pitch);
                cur.setYRot(yaw);
                cur.setXRot(pitch);
                return true;
            }
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.FINE,
                    "[RTP][trace] NeoForgeRTPPlayer same-dim connection.teleport failed: " + t);
        }

        try {
            cur.setPos(x, y, z);
            cur.setYRot(yaw);
            cur.setXRot(pitch);
            if (resolveServerLevel(cur) == target) {
                cur.connection.teleport(x, y, z, yaw, pitch);
                return true;
            }
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP][trace] NeoForgeRTPPlayer cross-dimension teleport requested but "
                    + "no stable cross-dim path available on this MC version; pos was set "
                    + "in-place. Same-world RTP is unaffected.");
            return false;
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP][trace] NeoForgeRTPPlayer fallback teleport failed", t);
            return false;
        }
    }

    /**
     * Resolve a player's {@link ServerLevel} in a way that survives the 1.21.x
     * mapping drift. {@code ServerPlayer.serverLevel()} exists through MC 1.21.8
     * but was removed/renamed in the 1.21.11 mappings; fall back to the inherited
     * {@code Entity.level()} accessor (whose runtime value for an online player
     * is always a {@link ServerLevel}).
     */
    private static volatile java.lang.reflect.Method LEVEL_ACCESSOR;
    private static volatile java.lang.reflect.Field LEVEL_FIELD;
    private static volatile boolean LEVEL_RESOLVED;

    @Nullable
    public static ServerLevel resolveServerLevel(ServerPlayer p) {
        if (p == null) return null;
        // Fast path: a previously-resolved accessor (method or field).
        java.lang.reflect.Method cachedM = LEVEL_ACCESSOR;
        if (cachedM != null) {
            try {
                Object r = cachedM.invoke(p);
                if (r instanceof ServerLevel sl) return sl;
            } catch (Throwable ignored) { /* fall through to rescan */ }
        }
        java.lang.reflect.Field cachedF = LEVEL_FIELD;
        if (cachedF != null) {
            try {
                Object r = cachedF.get(p);
                if (r instanceof ServerLevel sl) return sl;
            } catch (Throwable ignored) { /* fall through to rescan */ }
        }
        // Direct compiled accessors (cheapest when the carrier mappings match).
        try {
            net.minecraft.world.level.Level lvl = p.level();
            if (lvl instanceof ServerLevel sl) return sl;
        } catch (Throwable ignored) {
            // level() drifted on this mapping; fall through to a structural scan.
        }
        return scanForServerLevel(p);
    }

    /**
     * Mapping-name-agnostic resolution of an online player's {@link ServerLevel}.
     * Across the 1.21.x line both {@code ServerPlayer.serverLevel()} and the
     * inherited {@code Entity.level()} accessor have drifted, so we structurally
     * scan {@link ServerPlayer} (and its superclasses) for a no-arg method whose
     * runtime result is a {@link ServerLevel}, then a field of a {@code Level}
     * type, caching whichever resolves.
     */
    @Nullable
    private static ServerLevel scanForServerLevel(ServerPlayer p) {
        // Prefer well-known names first to avoid invoking unrelated zero-arg methods.
        for (String name : new String[]{"serverLevel", "level", "getLevel", "getCommandSenderWorld"}) {
            try {
                java.lang.reflect.Method m = findNoArgMethod(p.getClass(), name);
                if (m == null) continue;
                Object r = m.invoke(p);
                if (r instanceof ServerLevel sl) {
                    LEVEL_ACCESSOR = m;
                    LEVEL_RESOLVED = true;
                    return sl;
                }
            } catch (Throwable ignored) { /* try next */ }
        }
        // Structural method scan: any no-arg method returning a Level subtype.
        for (Class<?> c = p.getClass(); c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!net.minecraft.world.level.Level.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(p);
                    if (r instanceof ServerLevel sl) {
                        LEVEL_ACCESSOR = m;
                        LEVEL_RESOLVED = true;
                        return sl;
                    }
                } catch (Throwable ignored) { /* try next */ }
            }
        }
        // Field scan: any field of a Level type holding a ServerLevel.
        for (Class<?> c = p.getClass(); c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!net.minecraft.world.level.Level.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object r = f.get(p);
                    if (r instanceof ServerLevel sl) {
                        LEVEL_FIELD = f;
                        LEVEL_RESOLVED = true;
                        return sl;
                    }
                } catch (Throwable ignored) { /* try next */ }
            }
        }
        return null;
    }

    @Nullable
    private static java.lang.reflect.Method findNoArgMethod(Class<?> start, String name) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) { /* try superclass */ }
        }
        return null;
    }

    @Override
    public RTPLocation getLocation() {
        ServerPlayer p = handle;
        if (p == null) return null;
        ServerLevel level = resolveServerLevel(p);
        if (level == null) return null;
        RTPWorld<?> rtpWorld = RTP.serverAccessor == null
                ? null
                : RTP.serverAccessor.getRTPWorld(
                        io.github.dailystruggle.rtp.neoforge.world.NeoForgeRTPWorld
                                .resolveDimensionName(level, false));
        if (rtpWorld == null) return null;
        return new RTPLocation(rtpWorld, p.getBlockX(), p.getBlockY(), p.getBlockZ());
    }

    @Override
    public boolean isOnline() {
        ServerPlayer p = handle;
        return p != null && !p.isRemoved();
    }

    /** Minimal detached sender for clone() when the handle has been released. */
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
        @Override public CompletableFuture<Boolean> setLocation(RTPLocation to) { return CompletableFuture.completedFuture(false); }
        @Override public RTPLocation getLocation() { return null; }
        @Override public boolean isOnline() { return false; }
    }
}
