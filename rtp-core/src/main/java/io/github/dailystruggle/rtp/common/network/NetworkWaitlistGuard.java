package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Rejects new {@code /rtp*} invocations while caller has non-terminal cross-server enrolment.
 * Read-only lookup against {@link NetworkStatusCache} (ADR-015 / REQ-RTP-NET-015 / REQ-RTP-F-013).
 */
public final class NetworkWaitlistGuard implements Predicate<RTPCommandSender> {

    /**
     * Hardcoded fallback used only when {@code RTP.configs} has not been
     * initialised (test contexts, very early bootstrap). Production
     * rendering reads {@link NetworkMessages#alreadyQueued} from
     * {@code messages.yml} per REQ-RTP-F-013.
     */
    private static final String DEFAULT_ALREADY_QUEUED =
            "&cYou are already waiting in the cross-server queue (position [position]). Please wait.";

    private final NetworkStatusCache statusCache;

    public NetworkWaitlistGuard(NetworkStatusCache statusCache) {
        this.statusCache = Objects.requireNonNull(statusCache, "statusCache");
    }

    @Override
    public boolean test(RTPCommandSender sender) {
        if (!(sender instanceof RTPPlayer player)) {
            return true;
        }
        UUID uuid = player.uuid();
        Optional<NetworkStatusCache.QueueStatus> snap = statusCache.get(uuid);
        if (snap.isEmpty()) {
            // The post-arrival /rtp lands here after JoinTriggerSource.onRedeemed
            // evicts the lobby-seeded row. Logged at FINE so devstack repros can
            // confirm the guard did NOT short-circuit the dispatched /rtp without
            // spamming the console at INFO on every invocation.
            RTP.log(Level.FINE,
                    "[RTP][trace] NetworkWaitlistGuard.test: no cached status for "
                            + uuid + "; allowing /rtp to proceed");
            return true;
        }
        NetworkStatusCache.QueueStatus status = snap.get();
        if (!status.nonTerminal()) {
            RTP.log(Level.FINE,
                    "[RTP][trace] NetworkWaitlistGuard.test: cached status=" + status.state()
                            + " is terminal for " + uuid + "; allowing /rtp to proceed");
            return true;
        }
        RTP.log(Level.FINE,
                "[RTP][trace] NetworkWaitlistGuard.test: REJECTING /rtp for " + uuid
                        + " (cached status=" + status.state() + " is non-terminal, position="
                        + status.positionInQueue() + ")");

        String msg = formatMessage(status);
        try {
            RTP.serverAccessor.sendMessage(uuid, msg);
        } catch (Throwable t) {
            // S-004: never silently swallow. Surface but don't let a
            // formatter exception accidentally let the command through -
            // the predicate result is what gates the command, and we still
            // return false below to keep the lock honest.
            RTP.log(Level.WARNING,
                    "[RTP] NetworkWaitlistGuard message dispatch failed for "
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
                if (RTP.configs != null) {
                    Object v = RTP.configs.getConfigValue(
                            NetworkMessages.alreadyQueued, DEFAULT_ALREADY_QUEUED);
                    if (v != null) return v.toString();
                }
            }
        } catch (Throwable ignored) {
            // fall through to default
        }
        return DEFAULT_ALREADY_QUEUED;
    }
}
