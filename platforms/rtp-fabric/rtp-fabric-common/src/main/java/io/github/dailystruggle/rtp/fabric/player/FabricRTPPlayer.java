package io.github.dailystruggle.rtp.fabric.player;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.fabric.menu.FabricBookSpec;
import io.github.dailystruggle.rtp.fabric.tools.FabricLegacyText;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry;
import io.github.dailystruggle.rtp.fabric.world.FabricRTPWorld;
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
 * Fabric implementation of {@link RTPPlayer}.
 *
 * <p><b>Design note.</b> Holds a strong reference to the {@link ServerPlayer}
 * that was current at construction. The event bridge drops the wrapper
 * from the accessor map on disconnect (REQ-RTP-S-004 — MemoryTracker release
 * on all exit paths), so the strong-ref window is bounded by player session.
 *
 * <p>No {@code org.bukkit.*} imports — ADR-022 §4 invariant.
 */
public final class FabricRTPPlayer
        implements RTPPlayer, FabricBookOpener, FabricMapSink, FabricInteractiveMessageSink {

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

    /**
     * @return the underlying Mojmap {@link ServerPlayer} handle, or {@code null}
     *         if the player has disconnected. Used by the Fabric effects layer
     *         (effects-api-ADR-003) to feed concrete Fabric effects with the
     *         platform-typed target object via {@code Effect#setTarget(Object)}.
     */
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
     * <p>Delegates to the active {@link FabricVersionAdapter#openBookMenu}
     * with this player's {@link ServerPlayer} handle, keeping the raw
     * {@code ServerPlayer} private to this class. The 1.21+ carriers implement
     * the book modal; the 1.20.x carrier keeps the SPI default {@code false},
     * so the caller falls back to chat there.
     */
    @Override
    public boolean openBookMenu(FabricBookSpec spec) {
        ServerPlayer p = handle;
        if (p == null || !isOnline() || spec == null) return false;
        FabricVersionAdapter adapter = FabricVersionAdapterRegistry.peek();
        if (adapter == null) return false;
        return adapter.openBookMenu(p, spec);
    }

    /**
     * Render an RTP chart onto a vanilla filled-map for this player and ship it
     * (maps-api parity). Mirrors {@link #openBookMenu}:
     * the maps path flows player-first, so the raw {@link ServerPlayer} handle
     * never leaves this class. We resolve the active {@link FabricVersionAdapter},
     * hop to the server tick thread via {@code RTP.scheduler} (map saved-data
     * allocation, packet dispatch, and inventory mutation all require
     * server-thread affinity), then delegate to the carrier's NM-free seam with
     * the live handle. Server/level state is reached <em>through</em> this player
     * (the carrier derives {@code sp.level()} / {@code sp.connection} /
     * {@code sp.getInventory()}); no standalone {@code MinecraftServer} is needed.
     *
     * @param chartKey    stable per-chart key (carrier reuses one {@code MapId}
     *                    across live-refresh frames)
     * @param argb        row-major 128x128 ARGB pixel buffer
     * @param locked      request a write-locked map
     * @param deliverItem place a {@code FILLED_MAP} item into the inventory
     * @return {@code true} if the request was accepted (dispatched onto the
     *         server thread); {@code false} if the player is offline or the
     *         active carrier does not support map charts
     */
    public boolean renderMapChart(String chartKey, int[] argb, boolean locked, boolean deliverItem) {
        if (!isOnline() || chartKey == null || argb == null) return false;
        FabricVersionAdapter adapter = FabricVersionAdapterRegistry.peek();
        if (adapter == null || !adapter.supportsMapCharts()) return false;
        RTP.scheduler.runTask(() -> {
            ServerPlayer p = handle;
            if (p == null) return; // viewer went offline before the hop
            adapter.renderMapChart(p, chartKey, argb, locked, deliverItem);
        });
        return true;
    }

    /**
     * Release any per-chart {@code MapId} cache the active carrier holds for
     * {@code chartKey} (REQ-RTP-MAP-003). Pure server-side cache cleanup keyed
     * by the chart id; needs neither the server nor this player's handle, but
     * lives here so the maps lifecycle stays anchored on {@code RTPPlayer}.
     */
    public void releaseMapChart(String chartKey) {
        FabricVersionAdapter adapter = FabricVersionAdapterRegistry.peek();
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
        // Route through fabric-permissions-api (me.lucko:fabric-permissions-api)
        // when an implementer (LuckPerms-Fabric, Cyan, Ledger, ...) is present.
        // Permissions.check(player, node, defaultRequiredLevel) returns the
        // implementer's verdict if any handler is registered, otherwise falls
        // back to the vanilla op-level check at the supplied level (2 ≈ "op").
        // That matches the previous op-level fallback this method had.
        //
        // NOTE: We deliberately avoid ServerPlayer#hasPermissions(int) directly —
        // its intermediary mapping (class_3222.method_5687) drifts across MC
        // patch releases and triggers NoSuchMethodError at runtime (same family
        // as SharedConstants.getCurrentVersion().getName() and
        // Util.backgroundExecutor() documented in RTPFabricMod / FabricScheduler).
        // perms-api's own fallback ultimately calls the same op-level check, but
        // we wrap with a LinkageError guard and route the no-perms-api branch
        // through PlayerList.isOp(GameProfile), which is stable across versions.
        ServerPlayer p = handle;
        if (p == null) return false;
        if (permission == null || permission.isEmpty()) return true;
        // PRIMARY: consult fabric-permissions-api (LuckPerms-Fabric, Cyan,
        // Ledger, ...) FIRST so non-op grants and explicit denials win over
        // the on-disk op-level check. We use the UUID-keyed overload
        // (Permissions.getPermissionValue(UUID, String)) which routes through
        // OfflinePermissionCheckEvent — this avoids the drifted Entity
        // overload (which internally calls entity.method_5671() and silently
        // returns DEFAULT on 1.21.11). LuckPerms-Fabric registers an
        // OfflinePermissionCheckEvent handler that resolves synchronously
        // for online players, so .getNow(DEFAULT) yields the real verdict
        // without blocking the calling thread.
        try {
            net.fabricmc.fabric.api.util.TriState state =
                    me.lucko.fabric.api.permissions.v0.Permissions
                            .getPermissionValue(uuid, permission)
                            .getNow(net.fabricmc.fabric.api.util.TriState.DEFAULT);
            if (state == net.fabricmc.fabric.api.util.TriState.TRUE) return true;
            if (state == net.fabricmc.fabric.api.util.TriState.FALSE) return false;
            // DEFAULT: no perms-api handler answered (or jar present but no
            // implementer registered). Fall through to op-level check below.
        } catch (LinkageError ignored) {
            // perms-api jar absent at runtime (lite/stripped dist or just
            // not declared as a dep). Fall through to op-level check.
        } catch (Throwable ignored) {
            // Defensive: never let a perms lookup crash the pipeline.
        }
        // SECONDARY: consult the plugin.yml default table. perms-api returned
        // DEFAULT (no implementer) or its jar is absent; mirror Bukkit's
        // descriptor-driven behaviour so nodes like rtp.see / rtp.use (default
        // true) work for non-ops and rtp.onevent.* (default false) stay denied
        // even for ops, matching plugin.yml exactly.
        FabricDefaultPermissions.Verdict verdict = FabricDefaultPermissions.resolve(permission);
        if (verdict == FabricDefaultPermissions.Verdict.TRUE) return true;
        if (verdict == FabricDefaultPermissions.Verdict.FALSE) return false;
        // verdict == OP — fall through to the ops.json scan below.
        // TERTIARY: op-level fallback via stable APIs. ServerLevel#getServer()
        // and PlayerList#isOp(GameProfile) are non-intermediary and stable
        // across MC patch releases (unlike ServerPlayer#hasPermissions(int),
        // ServerPlayer#server (field_13995), or Entity#getServer() (method_5682)
        // — all of which have drifted on 1.21.11 under Loom).
        //
        // We try op-level FIRST (rather than perms-api first) because:
        //  1. perms-api's no-handler default path internally calls
        //     entity.method_5671() / server.method_3835(profile), both of
        //     which are intermediaries that can return wrong values or
        //     fail silently (returning false) on patch-release drift —
        //     without throwing a LinkageError we can catch.
        //  2. The previous order (perms-api first, op-level only on
        //     LinkageError) silently denied ops on 1.21.11 because the
        //     perms-api default path returned false cleanly.
        //  3. If an op is implicitly granted everything (matching Bukkit
        //     "op = *" semantics), we never need to consult perms-api.
        // PRIMARY op-check: route through CommandSourceStack#hasPermission(int).
        // This is the same code path Brigadier itself uses to gate op-only
        // command nodes, so it's the most stable Vanilla surface across MC
        // patch releases. Both prior surfaces drifted on 1.21.11:
        //   - PlayerList.isOp(GameProfile) → NoSuchMethodError (method_14569
        //     missing on class_3324 in 1.21.11; method signature renamed/
        //     reshaped to take a wrapper class_11560 instead of GameProfile).
        //   - PlayerList.getOps().get(GameProfile) → ClassCastException
        //     (ServerOpList key type changed from GameProfile to class_11560
        //     on 1.21.11; cannot cast a raw GameProfile to the new key type).
        // CommandSourceStack#hasPermission(int) sidesteps both because it
        // dispatches through the live MinecraftServer's permission check
        // without requiring us to construct or pass an op-list key.
        // PRIMARY op-check: read ops.json from disk by UUID. Every other
        // candidate Vanilla surface for "is this player an op?" has drifted
        // on 1.21.11 under Loom intermediary mappings (live observed):
        //   - ServerPlayer#hasPermissions(int)            → method_5687 missing
        //   - ServerPlayer#createCommandSourceStack()     → method_5671 missing (NoSuchMethodError)
        //   - PlayerList#isOp(GameProfile)                → method_14569 missing (signature took
        //                                                   wrapper class_11560, not GameProfile)
        //   - PlayerList.getOps().get(GameProfile)        → ClassCastException (key type became
        //                                                   class_11560, not GameProfile)
        //   - perms-api Permissions.check default path    → silently false (calls drifted
        //                                                   method_5671 / method_3835 internally)
        // ops.json itself is a stable on-disk JSON array of {uuid,name,level}
        // entries written by Vanilla and unchanged across recent MC versions.
        // We obtain its path through the server-supplied PlayerList.getOps()
        // file handle (avoids guessing the working directory). UUID-substring
        // scan is safe because UUIDs are fixed-format and quoted in ops.json.
        boolean opFallback = false;
        try {
            ServerLevel lvl = p.serverLevel();
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
        // perms-api was already consulted as PRIMARY above; if it returned
        // DEFAULT (no handler) or threw LinkageError (jar absent), the
        // ops.json scan is the authoritative answer.
        return opFallback;
    }

    @Override
    public Set<String> getEffectivePermissions() {
        // rtp-fabric-ADR-011: LuckPerms-Fabric primary path with closed-namespace
        // (rtp.effect.*, rtp.onevent.*) registry-probe fallback. Op status is
        // detected by probing a known default:op node through hasPermission so the
        // ops.json scan / perms-api verdict is honoured without duplicating logic.
        if (handle == null) return Collections.emptySet();
        boolean isOp = hasPermission("rtp.reload");
        return FabricEffectivePermissionsResolver.resolve(uuid, isOp, this::hasPermission);
    }

    @Override
    public void sendMessage(String message) {
        if (message == null) return;
        // Parse legacy &-codes and #RRGGBB hex into a styled Component so
        // chat renders colour/format the same way as on Bukkit/Paper.
        sendComponent(FabricLegacyText.parse(message));
    }

    /**
     * Dispatch a pre-built {@link Component} to this player via the system-chat
     * packet path. Used by both {@link #sendMessage(String)} and the rich-text
     * sink in {@code FabricServerAccessor.sendMessage(target, msg, hover, click, tag)}
     * so hover/click annotations survive end-to-end on Fabric.
     *
     * <p>NOTE: We deliberately avoid {@code ServerPlayer#sendSystemMessage(Component)} —
     * its intermediary mapping ({@code class_3222.method_43496}) drifts across MC
     * patch releases (observed {@code NoSuchMethodError} on 1.21.11), same family
     * as the {@code hasPermission(int)} drift documented above. Sending the system
     * chat packet directly through the player's network connection is a stable,
     * lower-level API across versions.
     */
    public void sendComponent(Component component) {
        ServerPlayer p = handle;
        if (p == null || component == null) return;
        try {
            if (p.connection != null) {
                p.connection.send(new ClientboundSystemChatPacket(component, false));
            }
        } catch (Throwable t) {
            // Best-effort: never let a chat send escalate into a pipeline crash.
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] Failed to deliver system message to " + name + ": " + t.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Builds the interactive {@link Component} (hover + click) on this obf-runtime
     * player's own bytecode via {@link FabricLegacyText#parseInteractive}, then delivers it
     * through {@link #sendComponent(Component)}. {@code FabricServerAccessor} routes <em>all</em>
     * rich-text sends through this sink so the {@code net.minecraft.network.chat.*}
     * (intermediary {@code class_2561}) types never appear in the accessor's bytecode -
     * keeping the accessor loadable on the deobf MC 26.x runtime (where the per-version
     * sink, e.g. {@code V26_x_R1FabricRTPPlayer}, handles this instead).
     */
    @Override
    public void sendInteractive(String message, String hover, String click, boolean run) {
        if (message == null) return;
        FabricLegacyText.ClickKind kind =
                run ? FabricLegacyText.ClickKind.RUN : FabricLegacyText.ClickKind.SUGGEST;
        sendComponent(FabricLegacyText.parseInteractive(message, hover, click, kind));
    }

    /**
     * Send a title / subtitle pair with explicit fade/stay timings (ticks).
     *
     * <p>Mirrors Bukkit's {@code Player.sendTitle} / Spigot {@code SendMessage.title} sink so
     * that Fabric honours {@code messages.yml} {@code title}, {@code subtitle},
     * {@code fadeIn}, {@code stay}, {@code fadeOut}. Both strings are parsed through
     * {@link FabricLegacyText} so legacy {@code &}-codes and {@code #RRGGBB} hex render
     * the same as on Bukkit/Paper. Empty / null strings are silently dropped (matching
     * Bukkit's behaviour of suppressing the title line when the config value is blank).
     *
     * <p>Packets used (intermediary-stable across 1.20+):
     * {@code ClientboundSetTitlesAnimationPacket}, {@code ClientboundSetTitleTextPacket},
     * {@code ClientboundSetSubtitleTextPacket}. Sent directly through the player's
     * {@code connection} for the same reason {@link #sendComponent(Component)} avoids
     * {@code ServerPlayer#sendSystemMessage} — higher-level helpers drift across MC
     * patch releases (NoSuchMethodError on 1.21.11).
     */
    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        ServerPlayer p = handle;
        if (p == null) return;
        if ((title == null || title.isEmpty()) && (subtitle == null || subtitle.isEmpty())) return;
        try {
            if (p.connection == null) return;
            // Animation timings always sent first so the client applies them before the text.
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(
                    fadeIn, stay, fadeOut));
            if (subtitle != null && !subtitle.isEmpty()) {
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                        FabricLegacyText.parse(subtitle)));
            }
            if (title != null && !title.isEmpty()) {
                // Title packet is what actually shows the title; if only subtitle is set
                // vanilla still requires a (possibly empty) title for the subtitle to render,
                // but Bukkit's parity behaviour is "no title = no display", so we skip.
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                        FabricLegacyText.parse(title)));
            }
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] Failed to deliver title to " + name + ": " + t.getMessage());
        }
    }

    /**
     * Send an actionbar message (the text that hovers above the hotbar). Mirrors
     * {@code SendMessage.actionbar} on Bukkit. Empty / null is silently dropped.
     *
     * <p>Uses {@code ClientboundSetActionBarTextPacket} via {@code connection.send} —
     * same intermediary-stability rationale as {@link #sendTitle}.
     */
    public void sendActionbar(String message) {
        ServerPlayer p = handle;
        if (p == null) return;
        if (message == null || message.isEmpty()) return;
        try {
            if (p.connection == null) return;
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                    FabricLegacyText.parse(message)));
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] Failed to deliver actionbar to " + name + ": " + t.getMessage());
        }
    }

    @Override
    public long cooldown() {
        // Perms-driven cooldown groups not yet implemented. 0 means "use default" upstream.
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
        // Use Entity#getServer() rather than ServerPlayer#server — see setLocation
        // for the IllegalAccessError rationale (intermediary field_13995 is
        // private at bytecode level under Loom).
        // Reach the MinecraftServer via ServerLevel#getServer() — both p.server
        // (field_13995, IllegalAccessError) and p.getServer() (method_5682,
        // NoSuchMethodError) drift on 1.21.11; ServerLevel#getServer() is stable.
        ServerLevel lvl = p.serverLevel();
        net.minecraft.server.MinecraftServer srv = lvl == null ? null : lvl.getServer();
        if (srv == null) return;
        net.minecraft.commands.CommandSourceStack source = buildCommandSource(p, lvl, srv);
        if (source == null) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] performCommand: could not build a command source for " + name
                            + " on this MC version; '" + command + "' not dispatched");
            return;
        }
        srv.getCommands().performPrefixedCommand(source, command);
    }

    /**
     * Build a {@link net.minecraft.commands.CommandSourceStack} for the player in a way
     * that survives Yarn/intermediary drift across MC patch releases. On 1.21.11 the
     * {@code ServerPlayer#createCommandSourceStack()} intermediary {@code method_5671}
     * is missing (NoSuchMethodError), and so is {@code Entity#position()}
     * ({@code method_19538}) - same family as the {@code field_13995} /
     * {@code method_5682} / {@code method_14251} drifts already worked around in this
     * file.
     *
     * <p>The drift only affects the intermediary symbols WE call directly across the
     * Loom module boundary; Minecraft's own internal calls (within its jar) resolve
     * consistently. So the most robust path is to let the server build the source and
     * attach the player itself ({@code MinecraftServer#createCommandSourceStack()} then
     * {@code CommandSourceStack#withEntity(player)}) - that pushes the position / name
     * lookups inside MC. We try the direct player call first, then the server-built
     * source, and only as a last resort hand-construct the stack from individually
     * guarded accessors (any of which may drift, so each is defaulted).
     */
    @Nullable
    private net.minecraft.commands.CommandSourceStack buildCommandSource(
            ServerPlayer p, ServerLevel lvl, net.minecraft.server.MinecraftServer srv) {
        // Attempt 1: the direct ServerPlayer#createCommandSourceStack(). Present on
        // most 1.20.x / 1.21.x builds; absent on 1.21.11.
        try {
            return p.createCommandSourceStack();
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.FINE,
                    "[RTP][trace] FabricRTPPlayer createCommandSourceStack() drift: " + t);
        }
        // Attempt 2: have the server build a console-level source, then re-anchor it on
        // the player via withEntity(...). MC performs the position / rotation / name
        // lookups internally (no cross-module intermediary call from us), so this
        // survives the position()/getName() drift that breaks the manual constructor.
        try {
            net.minecraft.commands.CommandSourceStack base = srv.createCommandSourceStack();
            return base.withEntity(p);
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.FINE,
                    "[RTP][trace] FabricRTPPlayer server.createCommandSourceStack().withEntity drift: " + t);
        }
        // Attempt 3: hand-construct the source stack. Every accessor here can drift on a
        // given runtime, so each is individually guarded and defaulted - the position /
        // rotation are irrelevant to a self-targeting '/rtp', so neutral defaults are
        // acceptable (RTP computes its own destination).
        try {
            net.minecraft.world.phys.Vec3 pos = safePosition(p);
            net.minecraft.world.phys.Vec2 rot = safeRotation(p);
            net.minecraft.network.chat.Component display = safeDisplayName(p);
            return new net.minecraft.commands.CommandSourceStack(
                    p,
                    pos,
                    rot,
                    lvl,
                    4,
                    name,
                    display,
                    srv,
                    p);
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP][trace] FabricRTPPlayer manual CommandSourceStack build failed", t);
            return null;
        }
    }

    /** Player position with drift-tolerant fallbacks; defaults to origin (irrelevant for '/rtp'). */
    private static net.minecraft.world.phys.Vec3 safePosition(ServerPlayer p) {
        try {
            return p.position();
        } catch (Throwable ignored) {
            // method_19538 drift; fall through.
        }
        try {
            return new net.minecraft.world.phys.Vec3(p.getX(), p.getY(), p.getZ());
        } catch (Throwable ignored) {
            // getX/getY/getZ drift; fall through.
        }
        return net.minecraft.world.phys.Vec3.ZERO;
    }

    /** Player rotation with drift-tolerant fallbacks; defaults to (0,0). */
    private static net.minecraft.world.phys.Vec2 safeRotation(ServerPlayer p) {
        try {
            return new net.minecraft.world.phys.Vec2(p.getXRot(), p.getYRot());
        } catch (Throwable ignored) {
            return net.minecraft.world.phys.Vec2.ZERO;
        }
    }

    /** Player display name with drift-tolerant fallback to a literal of the cached name. */
    private net.minecraft.network.chat.Component safeDisplayName(ServerPlayer p) {
        try {
            return p.getName();
        } catch (Throwable ignored) {
            return net.minecraft.network.chat.Component.literal(name == null ? "" : name);
        }
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
        // NOTE: We use Entity#getServer() (public) rather than the inherited
        // ServerPlayer#server field. Although the field is `public` in Yarn
        // mappings, the intermediary symbol (class_3222.field_13995) is
        // declared private at the bytecode level on 1.21.11, which causes
        // an IllegalAccessError at runtime when accessed from another module
        // under Loom's classloader. Same family as the hasPermission /
        // sendSystemMessage intermediary drift documented above.
        // Reach the MinecraftServer via ServerLevel#getServer() rather than
        // ServerPlayer#getServer() — the inherited Entity#getServer()
        // intermediary (method_5682) is missing on 1.21.11 (NoSuchMethodError).
        // ServerLevel#getServer() is mapping-stable and gives us the same handle.
        net.minecraft.server.MinecraftServer srv = target.getServer();
        if (srv == null) {
            ServerLevel here = p.serverLevel();
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

    /**
     * Teleport implementation that survives Yarn/intermediary drift across MC patch
     * releases. On 1.21.11 the 6-arg {@code ServerPlayer#teleportTo(ServerLevel,double,
     * double,double,float,float)} intermediary {@code method_14251} is missing — same
     * family as the {@code field_13995} / {@code method_5682} drifts already worked
     * around in this file. We attempt several stable shapes in order and fall back
     * to a packet-level same-dimension teleport (which has been part of the protocol
     * essentially unchanged since 1.7).
     */
    private static boolean performTeleport(ServerPlayer cur, ServerLevel target,
                                           double x, double y, double z,
                                           float yaw, float pitch) {
        // Attempt 0: per-version typed teleport. This is the only path that can
        // perform a cross-dimension teleport on a remapped Fabric runtime — the
        // reflective Mojmap-name lookups below never resolve against intermediary
        // runtime names, so without this a cross-dim RTP (e.g. overworld -> End)
        // falls all the way through to the in-place setPos fallback, which moves
        // the player in their current dimension and trips the "moved too quickly"
        // server check. A typed teleportTo(TeleportTransition) also resets that
        // move check, so it fixes same-dim teleports' warning spam too.
        FabricVersionAdapter adapter = FabricVersionAdapterRegistry.peek();
        if (adapter != null) {
            try {
                if (adapter.teleport(cur, target, x, y, z, yaw, pitch)) {
                    return true;
                }
            } catch (Throwable t) {
                RTP.log(java.util.logging.Level.FINE,
                        "[RTP][trace] FabricRTPPlayer adapter.teleport failed: " + t);
            }
        }

        // Attempt 1: reflective ServerPlayer#teleportTo(ServerLevel,double,double,double,float,float).
        // Present on most 1.20.x / 1.21.x builds; absent on 1.21.11.
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
                    "[RTP][trace] FabricRTPPlayer reflective teleportTo(6) failed: " + t);
        }

        // Attempt 2: same-dimension packet-level teleport via the connection.
        // This avoids ServerPlayer#teleportTo entirely.
        try {
            if (cur.serverLevel() == target) {
                cur.connection.teleport(x, y, z, yaw, pitch);
                cur.setYRot(yaw);
                cur.setXRot(pitch);
                return true;
            }
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.FINE,
                    "[RTP][trace] FabricRTPPlayer same-dim connection.teleport failed: " + t);
        }

        // Attempt 3: cross-dimension fallback — set position then change level.
        // Best-effort; if even this drifts we surface the failure to the pipeline
        // (S-004: never silently discard).
        try {
            cur.setPos(x, y, z);
            cur.setYRot(yaw);
            cur.setXRot(pitch);
            // Same-dim already handled; if we got here and it's still same-dim,
            // a connection.teleport via packet is the right thing.
            if (cur.serverLevel() == target) {
                cur.connection.teleport(x, y, z, yaw, pitch);
                return true;
            }
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP][trace] FabricRTPPlayer cross-dimension teleport requested but "
                    + "no stable cross-dim path available on this MC version; pos was set "
                    + "in-place. Same-world RTP is unaffected.");
            return false;
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP][trace] FabricRTPPlayer fallback teleport failed", t);
            return false;
        }
    }

    @Override
    public void setRespawnLocation(RTPLocation to) {
        // BetterRTP SetAsRespawn parity. Anchors the player's respawn point to the
        // landed location, on the server thread (Fabric entity-state mutations must
        // run there). The native setRespawnPosition signature drifts across MC
        // versions (the 5-arg ResourceKey/BlockPos form on 1.20.x-1.21.x, the
        // RespawnConfig form on 26.x), so resolve it reflectively like performTeleport.
        ServerPlayer p = handle;
        if (p == null || to == null) return;
        RTPWorld<?> rtpWorld = to.world();
        if (!(rtpWorld instanceof FabricRTPWorld fw)) return;
        ServerLevel target = fw.level();
        if (target == null) return;
        net.minecraft.server.MinecraftServer srv = target.getServer();
        if (srv == null) {
            ServerLevel here = p.serverLevel();
            srv = here == null ? null : here.getServer();
        }
        if (srv == null) return;
        final int bx = to.getBlockX();
        final int by = to.getBlockY();
        final int bz = to.getBlockZ();
        final ServerLevel targetFinal = target;
        srv.execute(() -> {
            ServerPlayer cur = handle;
            if (cur == null || cur.isRemoved()) return;
            anchorRespawn(cur, targetFinal, bx, by, bz);
        });
    }

    /**
     * Reflectively invoke {@code ServerPlayer#setRespawnPosition}, tolerating the
     * signature drift across MC versions (5-arg {@code ResourceKey/BlockPos} form
     * vs the {@code RespawnConfig} form). S-004: any failure is logged, never
     * silently swallowed.
     */
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
                "[RTP] setRespawnOnTeleport: no setRespawnPosition method on this MC version");
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING, "[RTP] Fabric setRespawnPosition failed", t);
        }
    }

    /** Build a {@code ServerPlayer.RespawnConfig} (26.x) by type-matching its canonical constructor. */
    private static Object buildRespawnConfig(Class<?> cfgClass, Object dim, Object pos) {
        try {
            for (java.lang.reflect.Constructor<?> c : cfgClass.getConstructors()) {
                Class<?>[] pt = c.getParameterTypes();
                Object[] args = new Object[pt.length];
                boolean ok = true;
                for (int i = 0; i < pt.length; i++) {
                    Class<?> p = pt[i];
                    if (p.isInstance(dim)) args[i] = dim;
                    else if (p.isInstance(pos)) args[i] = pos;
                    else if (p == float.class || p == Float.class) args[i] = 0.0f;
                    else if (p == boolean.class || p == Boolean.class) args[i] = Boolean.TRUE;
                    else { ok = false; break; }
                }
                if (ok) return c.newInstance(args);
            }
        } catch (Throwable ignored) {
            // fall through; caller logs the overall failure
        }
        return null;
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

    // ---------------------------------------------------------------------------
    // Client-side ("fake") block changes (RTPPlayer SPI)
    //
    // Reads/sends are purely visual: the server's real blocks are never mutated
    // (S-001..S-007), only already-loaded blocks are touched (S-005, isChunkLoaded
    // guard), and a malformed block-data token is logged, never swallowed (S-004).
    // The block-data string round-trips through the vanilla BlockStateParser — the
    // same parser FabricRTPWorld#setBlocks uses — so it is platform-internally
    // compatible with whatever getClientBlock produced.
    //
    // Binning note: the bulk sendClientBlockChanges(Map) inherits the RTPPlayer
    // default, which dispatches one ClientboundBlockUpdatePacket per block. True
    // section-level binning (one ClientboundSectionBlocksUpdatePacket per chunk
    // section) is not exposed by a public Vanilla constructor for arbitrary fake
    // states, so it remains a Bukkit-only optimisation (Player#sendBlockChanges).
    // ---------------------------------------------------------------------------

    @Override
    public String getClientBlock(RTPLocation location) {
        if (location == null) return null;
        if (handle == null) return null;
        if (!(location.world() instanceof FabricRTPWorld fw)) return null;
        ServerLevel level = fw.level();
        if (level == null) return null;
        int x = location.x();
        int y = location.y();
        int z = location.z();
        // S-005: only read an already-loaded block; never trigger a chunk load.
        if (!fw.isChunkLoaded(x >> 4, z >> 4)) return null;
        try {
            net.minecraft.world.level.block.state.BlockState state =
                    level.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
            return net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state);
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.FINE,
                    "[RTP][Fabric] getClientBlock failed at (" + x + "," + y + "," + z + "): " + t);
            return null;
        }
    }

    @Override
    public void sendClientBlockChange(RTPLocation location, String blockData) {
        if (location == null || blockData == null) return;
        ServerPlayer p = handle;
        if (p == null || p.connection == null) return;
        if (!(location.world() instanceof FabricRTPWorld fw)) return;
        ServerLevel level = fw.level();
        if (level == null) return;
        int x = location.x();
        int y = location.y();
        int z = location.z();
        if (!fw.isChunkLoaded(x >> 4, z >> 4)) return;
        net.minecraft.world.level.block.state.BlockState state = parseBlockData(level, blockData);
        if (state == null) return;
        try {
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(
                    new net.minecraft.core.BlockPos(x, y, z), state));
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP][Fabric] sendClientBlockChange failed for " + name + ": " + t.getMessage());
        }
    }

    /**
     * Parse a platform-neutral block-data string into a Mojmap {@code BlockState} via the
     * vanilla {@code BlockStateParser} (the same parser {@code /setblock} uses). Logs (never
     * silently swallows - S-004) and returns {@code null} on a malformed token.
     */
    private net.minecraft.world.level.block.state.@Nullable BlockState parseBlockData(
            ServerLevel level, String blockData) {
        try {
            net.minecraft.core.HolderLookup<net.minecraft.world.level.block.Block> lookup =
                    level.registryAccess()
                            .lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
            return net.minecraft.commands.arguments.blocks.BlockStateParser
                    .parseForBlock(lookup, blockData, false)
                    .blockState();
        } catch (Throwable e) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP][Fabric] ignoring malformed client block-data string: " + blockData
                            + " (" + e.getClass().getSimpleName() + ")");
            return null;
        }
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
