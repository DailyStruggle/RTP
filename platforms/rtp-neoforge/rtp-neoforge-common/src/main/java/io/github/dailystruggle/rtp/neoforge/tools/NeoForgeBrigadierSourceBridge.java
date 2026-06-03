package io.github.dailystruggle.rtp.neoforge.tools;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.common.RTP;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Translates a Brigadier {@link CommandSourceStack} into the platform-agnostic
 * identifiers used by {@code commands-api} (NeoForge analogue of Fabric's
 * {@code FabricBrigadierSourceBridge}).
 *
 * <p>Unlike Fabric, NeoForge ships Mojang-mapped names at runtime
 * (rtp-neoforge-ADR-001, {@code NEOFORGE_NOTES.md} §0), so there is no
 * obf/intermediary split and no need for the reflective hierarchy walk the
 * Fabric bridge uses to stay NM-clean. We reference {@link CommandSourceStack},
 * {@link Entity}, and {@link ServerPlayer} directly.</p>
 *
 * <p>Inputs that are not a player {@code CommandSourceStack} collapse to the
 * console-equivalent sentinel {@link RTPAPI#serverId} / "permission granted"
 * (matching Bukkit {@code ConsoleCommandSender} semantics).</p>
 */
public final class NeoForgeBrigadierSourceBridge {

    private NeoForgeBrigadierSourceBridge() {
    }

    /**
     * @return UUID for the source: player UUID for a {@link ServerPlayer}
     *         executor, {@link RTPAPI#serverId} for console / command blocks /
     *         unknown sources.
     */
    public static UUID resolveSenderUuid(Object src) {
        if (!(src instanceof CommandSourceStack stack)) return RTPAPI.serverId;
        try {
            Entity entity = stack.getEntity();
            if (entity instanceof ServerPlayer player) {
                return player.getUUID();
            }
        } catch (Throwable t) {
            RTP.log(Level.FINE,
                    "[RTP][NeoForge] resolveSenderUuid failed: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return RTPAPI.serverId;
    }

    /**
     * Permission predicate. Non-player sources (console / command blocks /
     * serverId sentinel) are treated as fully privileged; players consult
     * {@code RTP.serverAccessor.getSender(uuid).hasPermission(perm)}; unknown
     * sender entries fail closed.
     */
    public static boolean checkPermission(Object src, String permission) {
        if (permission == null || permission.isEmpty()) return true;
        UUID uuid = resolveSenderUuid(src);
        if (RTPAPI.serverId.equals(uuid)) return true;
        try {
            RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
            if (sender == null) return false;
            return sender.hasPermission(permission);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][NeoForge] Brigadier permission check failed for '" + permission
                            + "' (uuid=" + uuid + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }
}
