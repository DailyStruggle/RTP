package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Subcommand {@code /rtp editor trust nonce=<code>} (ADR-106 §5.2): trusts the browser key that
 * received {@code <code>} in an open editor session, so its signed edits are accepted. The code is
 * shown both on the page and in the operator's prompt, single-use, and expires after five minutes.
 * The trusted-editors file is written off the main thread; every outcome is a configurable message.
 */
public class TrustCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = EditorCmd.PERMISSION;
    private static final Pattern NONCE = Pattern.compile("[a-z0-9]{8}");

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
        final String code = nonce;
        // The trusted-editors file write stays off the main thread
        CompletableFuture.supplyAsync(() -> EditorChannel.trustAny(code)).whenComplete((result, t) -> {
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
                RTP.log(Level.INFO, "[editor] trust code refused (" + result + ")");
            }
            EditorChannelWiring.tell(callerId, EditorChannelWiring.message(key, fallback).replace("[nonce]", code));
        });
        return true;
    }
}
