package io.github.dailystruggle.rtp.fabric.events;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.LockFreeLocationBuffer;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import io.github.dailystruggle.rtp.common.tools.ParsePermissions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Fabric mirror of {@code OnEventTeleports#onPlayerJoin} (Bukkit listener) for
 * the join-time RTP path. Drives ADR-023 consumption of the login reserve
 * cache and routes through the platform-neutral
 * {@code TeleportPipelineTask} just like the Bukkit listener.
 *
 * <p><b>Permissions.</b> {@link ParsePermissions#hasPerm} resolves through
 * {@code RTPCommandSender.hasPermission}, which on Fabric is implemented by
 * {@code FabricRTPPlayer.hasPermission} — that method already consults
 * {@code fabric-permissions-api} (LuckPerms-Fabric et al.) and falls back to
 * an on-disk {@code ops.json} scan when no perms-api implementer is
 * registered. So {@code rtp.onevent.firstjoin}, {@code rtp.onevent.join} and
 * {@code rtp.nocooldown} work without any additional Fabric-specific code.
 *
 * <p><b>First-join detection.</b> No Vanilla API exposes Bukkit's
 * {@code Player#hasPlayedBefore()} on Fabric. We probe the on-disk
 * {@code <world>/playerdata/<uuid>.dat} file under
 * {@link MinecraftServer#getWorldPath(LevelResource)} — Vanilla writes this
 * file on first save after the player logs in, so its absence at JOIN time
 * is a reliable "first join" signal (one tiny stat() per join; no I/O on
 * subsequent joins beyond that).
 *
 * <p><b>S-005 compliance.</b> Like the Bukkit handler, this method itself
 * does no chunk I/O — it dispatches a {@code TeleportPipelineTask} via
 * {@code RTP.scheduler.runTaskAsynchronously}; all chunk-touching work
 * happens off the server thread inside the pipeline. The disk stat() of
 * {@code playerdata/<uuid>.dat} is a single non-blocking file-existence
 * check; on hot disk it is nanoseconds, on cold disk we accept the cost
 * once per first-ever join (parity with Bukkit's
 * {@code Player#hasPlayedBefore()} which reads the same file).
 *
 * <p>No {@code org.bukkit.*} imports — ADR-022 §4 invariant.
 */
public final class FabricOnEventTeleports {

    private FabricOnEventTeleports() {
        // static-only
    }

    /**
     * Entry point called by {@link FabricEventBridge}'s JOIN proxy after
     * {@code accessor.registerPlayerObject(player)}. Mirrors
     * {@code OnEventTeleports#onPlayerJoin}.
     *
     * <p>The {@code player} parameter is intentionally {@link Object}-typed:
     * naming {@code net.minecraft.server.level.ServerPlayer} in this
     * (obf-carrier) class would pin the intermediary class
     * {@code class_3222} into the bytecode constant pool, and that class is
     * absent on the MC 26.1 deobf runtime — link would fail at JOIN. UUID
     * is resolved through {@link io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter#getPlayerUUID(Object)}
     * (Loom-remapped per carrier) with a reflective {@code getUUID()}
     * fallback, mirroring {@code FabricServerAccessor#unregisterPlayerObject}.
     * Display name (used only for verbose logging) is resolved reflectively
     * with a {@code toString()} fallback.
     */
    public static void onJoin(MinecraftServer server, Object player) {
        if (server == null || player == null) return;
        try {
            UUID id = resolvePlayerUuid(player);
            if (id == null) return;
            long start = System.nanoTime();

            RTPCommandSender sender = RTP.serverAccessor.getSender(id);
            if (sender == null) return;

            boolean hasFirstJoin = ParsePermissions.hasPerm(sender, "rtp.onevent.", "firstjoin");
            boolean hasJoin = ParsePermissions.hasPerm(sender, "rtp.onevent.", "join");
            if (!hasFirstJoin && !hasJoin) return;

            long cooldownTime = sender.cooldown();

            @SuppressWarnings("unchecked")
            ConfigParser<LoggingKeys> logging =
                    (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
            boolean verbose = false;
            if (logging != null) {
                Object o = logging.getConfigValue(LoggingKeys.event_join, false);
                if (o instanceof Boolean) {
                    verbose = (Boolean) o;
                } else if (o != null) {
                    verbose = Boolean.parseBoolean(o.toString());
                }
            }

            boolean firstJoin = hasFirstJoin && !hasPlayedBefore(server, id);
            if (firstJoin) {
                if (verbose) {
                    RTP.log(Level.INFO,
                            "#0080FF[RTP] teleporting player:" + resolvePlayerName(player)
                                    + " on first join");
                }
                primeFromLoginCache(id);
                teleportAction(id);
                return;
            }
            if (hasJoin) {
                TeleportData data = RTP.getInstance().latestTeleportData.get(id);
                long time = (data == null) ? 0 : data.time;
                if (!sender.hasPermission("rtp.nocooldown") && (start - time) < cooldownTime) {
                    RTP.serverAccessor.sendMessage(id, MessagesKeys.cooldownMessage);
                    return;
                }
                if (verbose) {
                    RTP.log(Level.INFO,
                            "#0080FF[RTP] teleporting player:" + resolvePlayerName(player)
                                    + " on join");
                }
                primeFromLoginCache(id);
                teleportAction(id);
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] FabricOnEventTeleports.onJoin failed: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    /**
     * Resolve the player's UUID via the carrier-aware
     * {@link io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter}
     * SPI (mojmap on 26.x, intermediary on 1.20/1.21.x), falling back to a
     * reflective {@code getUUID()} lookup. Never names {@code ServerPlayer}
     * symbolically in this class's bytecode — see {@link #onJoin}.
     */
    private static UUID resolvePlayerUuid(Object player) {
        if (player == null) return null;
        try {
            io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
                    io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
            if (adapter != null) {
                UUID uuid = adapter.getPlayerUUID(player);
                if (uuid != null) return uuid;
            }
        } catch (Throwable ignored) {
            // fall through to reflective probe
        }
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("getUUID");
            Object u = m.invoke(player);
            if (u instanceof UUID resolved) return resolved;
        } catch (Throwable ignored) {
            // best-effort
        }
        return null;
    }

    /**
     * Best-effort resolution of the player's display name for verbose
     * logging. Reflectively walks {@code getName()} then {@code getString()};
     * any failure falls back to {@code toString()} so logging never throws.
     */
    private static String resolvePlayerName(Object player) {
        if (player == null) return "<null>";
        try {
            Object component = player.getClass().getMethod("getName").invoke(player);
            if (component == null) return player.toString();
            try {
                Object s = component.getClass().getMethod("getString").invoke(component);
                if (s != null) return s.toString();
            } catch (NoSuchMethodException ignored) {
                // not a Component — fall back below
            }
            return component.toString();
        } catch (Throwable ignored) {
            return player.toString();
        }
    }

    /**
     * ADR-023 consumer. Mirrors {@code OnEventTeleports#primeFromLoginCache}:
     * deposit one pre-verified location from the player's region login
     * buffer into {@code fastLocations} so the dispatched
     * {@code TeleportPipelineTask} short-circuits to it. Silent no-op on
     * empty / null / wrong-region; never blocks join.
     */
    private static void primeFromLoginCache(UUID id) {
        try {
            RTPPlayer rtpPlayer = RTP.serverAccessor.getPlayer(id);
            if (rtpPlayer == null) return;
            Region region = RTP.selectionAPI.getRegion(rtpPlayer);
            if (region == null) return;
            LockFreeLocationBuffer login = region.queueManager.loginLocations;
            if (login == null || login.isEmpty()) return;
            RTPLocation loc = login.poll();
            if (loc == null) return;
            CompletableFuture<RTPLocation> future = new CompletableFuture<>();
            future.complete(loc);
            region.queueManager.fastLocations.put(id, future);
        } catch (Throwable t) {
            RTP.log(Level.FINE, "[RTP] login-cache prime skipped: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Mirror of {@code OnEventTeleports#teleportAction}. Builds a
     * {@code TeleportPipelineTask} and dispatches it asynchronously. The
     * pipeline picks up the {@code fastLocations} entry (if any) primed by
     * {@link #primeFromLoginCache(UUID)}.
     */
    private static void teleportAction(UUID id) {
        if (RTP.getInstance().processingPlayers.contains(id)) return;
        RTP.getInstance().processingPlayers.add(id);
        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(id);
        if (teleportData != null) {
            if (!teleportData.completed) return;
            RTP.getInstance().priorTeleportData.put(id, teleportData);
        }
        RTP.getInstance().latestTeleportData.remove(id);
        RTPPlayer rtpPlayer = RTP.serverAccessor.getPlayer(id);
        if (rtpPlayer == null) return;
        Region region = RTP.selectionAPI.getRegion(rtpPlayer);
        if (region == null) return;
        TeleportPipelineTask pipelineTask =
                new TeleportPipelineTask(new GenerationContext(rtpPlayer, rtpPlayer, null), region);
        pipelineTask.setDelay(10);
        pipelineTask.region().inFlightCalculations.incrementAndGet();
        RTP.scheduler.runTaskAsynchronously(pipelineTask);
    }

    /**
     * Fabric analogue of Bukkit's {@code Player#hasPlayedBefore()}. Probes
     * {@code <world-root>/playerdata/<uuid>.dat} — Vanilla writes this file
     * on the first auto-save after a player joins, so its absence at JOIN
     * time means "fresh UUID, never seen before". Reflective fallback for
     * {@code getWorldPath} mapping drift across MC versions.
     */
    static boolean hasPlayedBefore(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) return false;
        try {
            Path worldRoot = server.getWorldPath(LevelResource.ROOT);
            return hasPlayedBefore(worldRoot, uuid);
        } catch (LinkageError | RuntimeException e) {
            // Fallback for mapping drift: try reflectively.
            try {
                java.lang.reflect.Method m = server.getClass().getMethod("getWorldPath", LevelResource.class);
                Object p = m.invoke(server, LevelResource.ROOT);
                if (p instanceof Path path) {
                    return hasPlayedBefore(path, uuid);
                }
            } catch (Throwable ignored) {
                // best-effort
            }
            return false;
        }
    }

    /**
     * World-root-path overload, exposed package-private for unit testing
     * without a live {@code MinecraftServer}. Returns {@code true} iff
     * {@code <worldRoot>/playerdata/<uuid>.dat} exists as a regular file.
     */
    static boolean hasPlayedBefore(Path worldRoot, UUID uuid) {
        if (worldRoot == null || uuid == null) return false;
        try {
            File f = worldRoot.resolve("playerdata").resolve(uuid + ".dat").toFile();
            return f.isFile();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
