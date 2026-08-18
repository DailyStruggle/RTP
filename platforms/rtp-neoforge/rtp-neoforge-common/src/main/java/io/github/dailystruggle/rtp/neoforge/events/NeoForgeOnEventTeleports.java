package io.github.dailystruggle.rtp.neoforge.events;

import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.LockFreeLocationBuffer;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import io.github.dailystruggle.rtp.common.tools.ParsePermissions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * NeoForge mirror of {@code OnEventTeleports#onPlayerJoin} (Bukkit listener)
 * for the join-time RTP path. Drives ADR-023 consumption of the login reserve
 * cache and routes through the platform-neutral {@code TeleportPipelineTask}.
 *
 * <p>NeoForge analogue of {@code FabricOnEventTeleports}. Because NeoForge ships
 * Mojang-mapped names at runtime, the player parameter is a typed
 * {@link ServerPlayer} (no reflective UUID / name resolution like Fabric's
 * obf-carrier path).</p>
 *
 * <p><b>Permissions.</b> {@link ParsePermissions#hasPerm} resolves through
 * {@code RTPCommandSender.hasPermission}, implemented on NeoForge by
 * {@code NeoForgeRTPPlayer.hasPermission} (op-level scan until the full
 * permission resolver lands in N2.5). So {@code rtp.onevent.firstjoin},
 * {@code rtp.onevent.join} and {@code rtp.nocooldown} work without extra code.</p>
 *
 * <p><b>First-join detection.</b> Probes the on-disk
 * {@code <world>/playerdata/<uuid>.dat} file under
 * {@link MinecraftServer#getWorldPath(LevelResource)} - Vanilla writes this on
 * first save after a player joins, so its absence at JOIN time is a reliable
 * "first join" signal.</p>
 *
 * <p><b>S-005 compliance.</b> This method does no chunk I/O - it dispatches a
 * {@code TeleportPipelineTask} via {@code RTP.scheduler.runTaskAsynchronously};
 * all chunk-touching work happens off the server thread inside the pipeline.</p>
 *
 * <p>No {@code org.bukkit.*} imports.</p>
 */
public final class NeoForgeOnEventTeleports {

    private NeoForgeOnEventTeleports() {
        // static-only
    }

    /**
     * Entry point called by {@link NeoForgeEventBridge} after
     * {@code accessor.registerPlayer(player)}. Mirrors
     * {@code OnEventTeleports#onPlayerJoin}.
     *
     * @param server the running server
     * @param player the joining player (typed)
     */
    public static void onJoin(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) return;
        try {
            UUID id = player.getUUID();
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
                            "#0080FF[RTP] teleporting player:" + player.getName().getString()
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
                    RTP.serverAccessor.sendMessage(id, PlayerMessages.cooldownMessage);
                    return;
                }
                if (verbose) {
                    RTP.log(Level.INFO,
                            "#0080FF[RTP] teleporting player:" + player.getName().getString()
                                    + " on join");
                }
                primeFromLoginCache(id);
                teleportAction(id);
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] NeoForgeOnEventTeleports.onJoin failed: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    /**
     * ADR-023 consumer. Mirrors {@code OnEventTeleports#primeFromLoginCache}:
     * deposit one pre-verified location from the player's region login buffer
     * into {@code fastLocations} so the dispatched {@code TeleportPipelineTask}
     * short-circuits to it. Silent no-op on empty / null / wrong-region.
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
     * NeoForge analogue of Bukkit's {@code Player#hasPlayedBefore()}. Probes
     * {@code <world-root>/playerdata/<uuid>.dat} - Vanilla writes this file on
     * the first auto-save after a player joins, so its absence at JOIN time
     * means "fresh UUID, never seen before".
     */
    static boolean hasPlayedBefore(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) return false;
        try {
            Path worldRoot = server.getWorldPath(LevelResource.ROOT);
            return hasPlayedBefore(worldRoot, uuid);
        } catch (LinkageError | RuntimeException e) {
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
