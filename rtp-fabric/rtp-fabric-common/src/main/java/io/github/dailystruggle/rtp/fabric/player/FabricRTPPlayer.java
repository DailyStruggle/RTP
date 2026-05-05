package io.github.dailystruggle.rtp.fabric.player;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.fabric.world.FabricRTPWorld;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Fabric implementation of {@link RTPPlayer}. Step E2 scope: real identity,
 * location, online status, and teleport. Permissions are op-level only — full
 * {@code fabric-permissions-api} integration lands in Step F.
 *
 * <p><b>Design note.</b> Holds a strong reference to the {@link ServerPlayer}
 * that was current at construction. Step E's event bridge drops the wrapper
 * from the accessor map on disconnect (REQ-RTP-S-004 — MemoryTracker release
 * on all exit paths), so the strong-ref window is bounded by player session.
 *
 * <p>No {@code org.bukkit.*} imports — ADR-022 §4 invariant.
 */
public final class FabricRTPPlayer implements RTPPlayer {

    private final UUID uuid;
    private final String name;
    private volatile @Nullable ServerPlayer handle;

    public FabricRTPPlayer(ServerPlayer player) {
        this.uuid = player.getUUID();
        this.name = resolveName(player);
        this.handle = player;
    }

    /**
     * Resolves the player's name in a way that is resilient to authlib API drift.
     * <p>In authlib versions shipped with Minecraft 1.21.6+, {@link com.mojang.authlib.GameProfile}
     * was converted to a record and the legacy {@code getName()} accessor was removed in favour
     * of the record component {@code name()}. Calling the old method via reflection-free linkage
     * raises {@link NoSuchMethodError} at runtime (see crash on 1.21.11). Falling back to the
     * Entity-level display name avoids binding to either {@code GameProfile} accessor shape.
     */
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

    /** Called by the event bridge on disconnect; subsequent calls treat the player as offline. */
    public void unbind() {
        this.handle = null;
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
        // Step F replaces this with fabric-permissions-api lookup.
        // Fallback: op level >= 2 is the closest Fabric analogue to a permission grant.
        // NOTE: We deliberately avoid ServerPlayer#hasPermissions(int) — its
        // intermediary mapping (class_3222.method_5687) drifts across MC patch
        // releases and triggers NoSuchMethodError at runtime (same family as
        // SharedConstants.getCurrentVersion().getName() and Util.backgroundExecutor()
        // documented in RTPFabricMod / FabricScheduler). Route through the
        // server's PlayerList op check, which uses GameProfile-keyed lookup
        // and is stable across versions.
        ServerPlayer p = handle;
        if (p == null) return false;
        try {
            return p.server.getPlayerList().isOp(p.getGameProfile());
        } catch (Throwable t) {
            // Best-effort: if even this drifts on a future MC release, fall
            // back to "no permission" rather than crashing the pipeline.
            return false;
        }
    }

    @Override
    public Set<String> getEffectivePermissions() {
        // Step F. Empty until perms-api is wired; op-level players get a true
        // from hasPermission so command gating still works in the meantime.
        return Collections.emptySet();
    }

    @Override
    public void sendMessage(String message) {
        ServerPlayer p = handle;
        if (p == null || message == null) return;
        p.sendSystemMessage(Component.literal(message));
    }

    @Override
    public long cooldown() {
        // Step F (perms-driven cooldown groups). 0 means "use default" upstream.
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
        p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), command);
    }

    @Override
    public RTPCommandSender clone() {
        // Same identity, same handle snapshot. Cloneable contract is loose for
        // command senders — the clone shares the handle until a rebind/unbind.
        ServerPlayer p = handle;
        if (p == null) {
            // No handle to clone from; return a detached placeholder that will
            // no-op on subsequent calls. Acceptable per the offline contract.
            return new DetachedClone(uuid, name);
        }
        return new FabricRTPPlayer(p);
    }

    @Override
    public CompletableFuture<Boolean> setLocation(RTPLocation to) {
        ServerPlayer p = handle;
        if (p == null || to == null) {
            return CompletableFuture.completedFuture(false);
        }
        RTPWorld<?> rtpWorld = to.world();
        if (!(rtpWorld instanceof FabricRTPWorld fw)) {
            return CompletableFuture.completedFuture(false);
        }
        ServerLevel target = fw.level();
        // Hop to the server thread for the teleport — Fabric mutations to
        // entity state must run there. server.submit returns a future that
        // resolves on-tick.
        return p.server.submit(() -> {
            ServerPlayer cur = handle;
            if (cur == null || cur.isRemoved()) return false;
            cur.teleportTo(target,
                    to.getBlockX() + 0.5,
                    to.getBlockY(),
                    to.getBlockZ() + 0.5,
                    cur.getYRot(),
                    cur.getXRot());
            return true;
        });
    }

    @Override
    public RTPLocation getLocation() {
        ServerPlayer p = handle;
        if (p == null) return null;
        ServerLevel level = p.serverLevel();
        RTPWorld<?> rtpWorld = RTP.serverAccessor == null
                ? null
                : RTP.serverAccessor.getRTPWorld(level.dimension().location().toString());
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
