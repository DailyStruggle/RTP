package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Subcommand verb {@code /rtp editor apply <token>} (ADR-104).
 *
 * <p>Fetches configuration updates from the byte-store transport by token,
 * verifies SHA-256 integrity, runs AST & geometry validation, creates {@code .bak} copies
 * of dirty files, and executes an atomic in-memory swap and hot reload.
 *
 * <p>Conforms to S-004 (no silent swallows on invalid token / payload failure),
 * S-005 (100% async scheduling for network and disk I/O), and S-006 (fail-closed pre-init).
 */
public class ApplyCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = "rtp.editor";

    private final EditorHttpTransport transport;

    public ApplyCmd(@Nullable CommandsAPICommand parent) {
        this(parent, new EditorHttpTransport());
    }

    public ApplyCmd(@Nullable CommandsAPICommand parent, EditorHttpTransport transport) {
        super(parent);
        this.transport = transport;
        addParameter("token", new EditorTokenParameter(PERMISSION, "session token minted by editor"));
    }

    @Override
    public String name() {
        return "apply";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "apply edited configuration payload by session token (ADR-104)";
    }

    @Override
    public boolean onCommand(
            UUID callerId,
            Map<String, List<String>> parameterValues,
            @Nullable CommandsAPICommand nextCommand
    ) {
        if (nextCommand != null) {
            return nextCommand.onCommand(callerId, parameterValues, null);
        }

        // S-006: Pre-init fail-closed guard
        if (RTP.serverAccessor == null || RTP.configs == null) {
            sendMessage(callerId, "RTP: Core is not initialized yet. Please wait.");
            return true;
        }

        // Extract token argument from parameterValues or subcommands
        String token = null;
        if (parameterValues != null && !parameterValues.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : parameterValues.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    token = entry.getValue().get(0);
                    break;
                }
            }
        }

        if (token == null || token.isBlank()) {
            sendMessage(callerId, "RTP: Usage - /rtp editor apply token=<token>");
            return true;
        }

        token = token.trim();
        sendMessage(callerId, "RTP: Fetching configuration payload for token '" + token + "'...");

        final String finalToken = token;
        // Check local ephemeral active sessions first
        String localPayload = EditorSessionManager.getInstance().getSession(finalToken);
        CompletableFuture<String> payloadFuture = (localPayload != null)
                ? CompletableFuture.completedFuture(localPayload)
                : transport.fetchPayload(finalToken);

        payloadFuture.thenCompose(payloadJson -> {
            sendMessage(callerId, "RTP: Validating payload integrity and syntax...");
            return EditorSessionManager.getInstance().applyPayload(payloadJson);
        }).thenRun(() -> {
            sendMessage(callerId, "RTP: Successfully applied configurations and hot-reloaded! (.bak copies created for dirty files)");
        }).exceptionally(throwable -> {
            // S-004: Zero silent swallows on invalid token or apply failure
            Throwable cause = (throwable.getCause() != null) ? throwable.getCause() : throwable;
            String errMsg = "RTP: Failed to apply payload for token '" + finalToken + "': " + cause.getMessage();
            RTP.log(Level.WARNING, errMsg, cause);
            sendMessage(callerId, errMsg);
            return null;
        });

        return true;
    }

    private void sendMessage(@Nullable UUID callerId, String msg) {
        if (callerId == null || RTP.serverAccessor == null) return;
        try {
            RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg);
        } catch (Exception ignored) {
        }
    }
}
