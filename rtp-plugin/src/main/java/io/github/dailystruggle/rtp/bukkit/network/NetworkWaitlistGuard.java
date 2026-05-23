package io.github.dailystruggle.rtp.bukkit.network;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Slice 4 (Slice 1+2+3 SPI / ADR-015 / REQ-RTP-NET-015): a sender-check
 * predicate registered on {@code RTPCmdBukkit} via
 * {@code addSenderCheck(...)} that rejects new {@code /rtp*} invocations
 * while the caller already has a non-terminal cross-server enrolment.
 *
 * <p>Lookup is read-only against {@link NetworkStatusCache} (never blocks
 * the command thread on the transport). When the player's state is one of
 * {@code QUEUED}, {@link NetworkStatusCache.QueueStatus.State#WAITLISTED
 * WAITLISTED}, {@code ROUTING}, {@code RESERVED}, or {@code TRANSFERRING},
 * we send the configured {@code msgAlreadyQueued} message and short-circuit
 * the command by returning {@code false}.</p>
 *
 * <p>Console / non-player senders always pass (their UUID is the synthetic
 * server-id and can never sit on the waitlist). When network mode is off
 * the cache is empty, so the predicate is a no-op without any extra branch.
 * </p>
 *
 * <p>The rendered body is sourced from
 * {@link MessagesKeys#alreadyQueued} in {@code messages.yml} per
 * REQ-RTP-F-013. Placeholder {@code [position]} is substituted client-side
 * with the FIFO position when known; an empty value is emitted when the
 * proxy has not yet assigned one (e.g. {@code TRANSFERRING}).</p>
 */
public final class NetworkWaitlistGuard implements Predicate<CommandSender> {

    /**
     * Hardcoded fallback used only when {@code RTP.configs} has not been
     * initialised (test contexts, very early bootstrap). Production
     * rendering reads {@link MessagesKeys#alreadyQueued} from
     * {@code messages.yml} per REQ-RTP-F-013.
     */
    private static final String DEFAULT_ALREADY_QUEUED =
            "&cYou are already waiting in the cross-server queue (position [position]). Please wait.";

    private final NetworkStatusCache statusCache;

    public NetworkWaitlistGuard(NetworkStatusCache statusCache) {
        this.statusCache = Objects.requireNonNull(statusCache, "statusCache");
    }

    @Override
    public boolean test(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        UUID uuid = player.getUniqueId();
        Optional<NetworkStatusCache.QueueStatus> snap = statusCache.get(uuid);
        if (snap.isEmpty()) return true;
        NetworkStatusCache.QueueStatus status = snap.get();
        if (!status.nonTerminal()) return true;

        String msg = formatMessage(status);
        try {
            SendMessage.sendMessage(sender, msg);
        } catch (Throwable t) {
            // S-004: never silently swallow. Surface but don't let a
            // formatter exception accidentally let the command through -
            // the predicate result is what gates the command, and we still
            // return false below to keep the lock honest.
            RTP.log(Level.WARNING,
                    "[NETWORK] NetworkWaitlistGuard message dispatch failed for "
                            + uuid + ": " + t.getMessage(), t);
        }
        return false;
    }

    /** Visible for tests. */
    static String formatMessage(NetworkStatusCache.QueueStatus status) {
        int pos = status.positionInQueue();
        String template = resolveTemplate();
        return template.replace("[position]", pos > 0 ? Integer.toString(pos) : "");
    }

    @SuppressWarnings("unchecked")
    private static String resolveTemplate() {
        try {
            if (RTP.configs != null) {
                ConfigParser<MessagesKeys> parser =
                        (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
                if (parser != null) {
                    Object v = parser.getConfigValue(
                            MessagesKeys.alreadyQueued, DEFAULT_ALREADY_QUEUED);
                    if (v != null) return v.toString();
                }
            }
        } catch (Throwable ignored) {
            // fall through to default
        }
        return DEFAULT_ALREADY_QUEUED;
    }
}
