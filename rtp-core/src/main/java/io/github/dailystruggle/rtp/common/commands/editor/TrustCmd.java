package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Subcommand {@code /rtp editor trust nonce=<code>} (ADR-106 §5.2): trusts the browser key that
 * received {@code <code>} in an open editor session, so its signed edits are accepted. The code is
 * shown both on the page and in the operator's prompt, single-use, and expires after five minutes.
 * The trusted-editors file is written off the main thread; every outcome is a configurable message.
 * After {@link #MAX_FAILURES} unknown or expired codes inside {@link #FAILURE_WINDOW_MILLIS} a sender
 * is refused until the oldest failure leaves the window (the code space is small).
 */
public class TrustCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = EditorCmd.PERMISSION;
    static final int MAX_FAILURES = 5;
    static final long FAILURE_WINDOW_MILLIS = 60_000L;
    private static final Pattern NONCE = Pattern.compile("[a-z0-9]{8}");
    /** The console (and any caller without an id) shares one budget. */
    private static final UUID NO_SENDER = new UUID(0L, 0L);
    private static final Map<UUID, ArrayDeque<Long>> FAILURES = new ConcurrentHashMap<>();
    static volatile LongSupplier clock = System::currentTimeMillis;

    public TrustCmd(@Nullable CommandsAPICommand parent) {
        super(parent);
        addParameter("nonce", new CommandParameter(PERMISSION, "trust code shown by the web editor page",
                (uuid, val) -> val != null && NONCE.matcher(val.trim().toLowerCase(Locale.ROOT)).matches()) {
            @Override
            public Set<String> values() {
                // Pending codes are not suggested: the operator must read the one the page shows
                return Set.of();
            }
        });
    }

    @Override
    public String name() {
        return "trust";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "trust a web editor browser by the code its page shows (ADR-106)";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand) {
        if (nextCommand != null) return true;
        String nonce = null;
        if (parameterValues != null) {
            List<String> v = parameterValues.get("nonce");
            if (v != null && !v.isEmpty() && v.get(0) != null) nonce = v.get(0).trim().toLowerCase(Locale.ROOT);
        }
        if (nonce == null || !NONCE.matcher(nonce).matches()) {
            EditorChannelWiring.tell(callerId, EditorChannelWiring.message(CommandMessages.editorTrustUsage,
                    "[P0] Usage: /rtp editor trust nonce=<code shown by the editor page>"));
            return true;
        }
        if (rateLimited(callerId, clock.getAsLong())) {
            RTP.log(Level.WARNING, "[editor] trust command refused for " + callerId + ": " + MAX_FAILURES
                    + " wrong codes in the last minute");
            EditorChannelWiring.tell(callerId, EditorChannelWiring.message("editorTrustRateLimited",
                    "[P0] Too many wrong editor trust codes. Wait a minute, then try again."));
            return true;
        }
        final String code = nonce;
        // The trusted-editors file write stays off the main thread
        EditorSecurity.supplyAsync(() -> EditorChannel.trustAny(code)).whenComplete((result, t) -> {
            if (t != null) {
                RTP.log(Level.WARNING, "[editor] trust command failed: " + t.getMessage(), t);
                result = EditorChannel.TrustResult.UNKNOWN;
            }
            CommandMessages key;
            String fallback;
            switch (result) {
                case TRUSTED -> {
                    key = CommandMessages.editorTrustAccepted;
                    fallback = "[P0] Trusted: the web editor page with code [nonce] can now preview and Hot-Apply.";
                }
                case ALREADY_TRUSTED -> {
                    key = CommandMessages.editorTrustAlready;
                    fallback = "[P0] The browser with code [nonce] was already trusted.";
                }
                case EXPIRED -> {
                    key = CommandMessages.editorTrustExpired;
                    fallback = "[P0] Code [nonce] has expired. Reload the editor page to get a new one.";
                }
                default -> {
                    key = CommandMessages.editorTrustUnknown;
                    fallback = "[P0] No open editor session issued code [nonce].";
                }
            }
            if (result == EditorChannel.TrustResult.UNKNOWN || result == EditorChannel.TrustResult.EXPIRED) {
                recordFailure(callerId, clock.getAsLong());
                RTP.log(Level.INFO, "[editor] trust code refused (" + result + ")");
            }
            EditorChannelWiring.tell(callerId, EditorChannelWiring.message(key, fallback).replace("[nonce]", code));
        });
        return true;
    }

    /** {@code true} once {@code caller} has {@link #MAX_FAILURES} failures inside the window. */
    static boolean rateLimited(UUID caller, long now) {
        UUID k = caller == null ? NO_SENDER : caller;
        ArrayDeque<Long> q = FAILURES.get(k);
        if (q == null) return false;
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= FAILURE_WINDOW_MILLIS) q.pollFirst();
            if (q.isEmpty()) {
                FAILURES.remove(k, q);
                return false;
            }
            return q.size() >= MAX_FAILURES;
        }
    }

    static void recordFailure(UUID caller, long now) {
        ArrayDeque<Long> q = FAILURES.computeIfAbsent(caller == null ? NO_SENDER : caller, x -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= FAILURE_WINDOW_MILLIS) q.pollFirst();
            q.addLast(now);
            while (q.size() > MAX_FAILURES) q.pollFirst();
        }
    }

    static void resetFailures() {
        FAILURES.clear();
    }
}
