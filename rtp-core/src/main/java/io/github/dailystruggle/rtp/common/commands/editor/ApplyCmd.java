package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
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
 * Payloads fetched from the byte store must carry their {@code sha256}; logs show a token prefix only.
 */
public class ApplyCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = "rtp.editor";

    private final Supplier<EditorHttpTransport> transports;

    /** Bytebin URL from {@code advanced/network.yml editor}, re-read for every apply. */
    public ApplyCmd(@Nullable CommandsAPICommand parent) {
        this(parent, (Supplier<EditorHttpTransport>) EditorHttpTransport::fromConfig);
    }

    public ApplyCmd(@Nullable CommandsAPICommand parent, EditorHttpTransport transport) {
        this(parent, () -> transport);
    }

    ApplyCmd(@Nullable CommandsAPICommand parent, Supplier<EditorHttpTransport> transports) {
        super(parent);
        this.transports = transports;
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
            return true;
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
        if (!EditorSecurity.isToken(token)) {
            sendMessage(callerId, "RTP: Usage - /rtp editor apply token=<token>");
            return true;
        }
        sendMessage(callerId, "RTP: Fetching configuration payload...");

        applyToken(callerId, token);
        return true;
    }

    /**
     * Executes the full ADR-104 token apply pipeline asynchronously.
     *
     * @param callerId UUID of the invoking sender
     * @param token session token minted by web editor or local session
     * @return CompletableFuture completing when the payload is validated, written, and hot-reloaded
     */
    public CompletableFuture<Void> applyToken(@Nullable UUID callerId, String token) {
        final String finalToken = token;
        final String shown = EditorSecurity.tokenPrefix(token);
        if (!EditorSecurity.isToken(finalToken)) {
            // Also keeps the offline drop lookup below a single file name inside editor/
            return CompletableFuture.failedFuture(new IllegalArgumentException("Malformed session token"));
        }
        RTP.log(Level.FINE, "[editor] applying session " + shown);
        // 1. Check local in-memory ephemeral active sessions first
        String localPayload = EditorSessionManager.getInstance().getSession(finalToken);
        final boolean fromSession = localPayload != null;

        // 2. Check local offline file drop (<pluginDir>/editor/<token>.json or <pluginDir>/editor/apply.json)
        if (localPayload == null && RTP.serverAccessor != null) {
            try {
                java.io.File pluginDir = RTP.serverAccessor.getPluginDirectory();
                if (pluginDir != null) {
                    java.nio.file.Path editorDir = pluginDir.toPath().resolve("editor");
                    java.nio.file.Path tokenFile = editorDir.resolve(finalToken.endsWith(".json") ? finalToken : finalToken + ".json");
                    if (!java.nio.file.Files.exists(tokenFile) && "apply".equalsIgnoreCase(finalToken)) {
                        tokenFile = editorDir.resolve("apply.json");
                    }
                    if (java.nio.file.Files.isRegularFile(tokenFile)) {
                        localPayload = java.nio.file.Files.readString(tokenFile, java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // S-004: an unreadable drop file falls through to the byte store, but is reported
                RTP.log(Level.WARNING, "[editor] offline apply file for session " + shown + " unreadable: " + e.getMessage(), e);
                sendMessage(callerId, EditorChannelWiring.message("editorApplyFileUnreadable",
                        "[P1] The offline apply file could not be read ([reason]); trying the byte store.")
                        .replace("[reason]", String.valueOf(e.getMessage())));
            }
        }

        final boolean remote = localPayload == null;
        CompletableFuture<String> payloadFuture = !remote
                ? CompletableFuture.completedFuture(localPayload)
                : EditorSecurity.supplyAsync(transports::get).thenCompose(t -> t.fetchPayload(finalToken));

        return payloadFuture.thenCompose(payloadJson -> {
            sendMessage(callerId, "RTP: Validating payload integrity and syntax...");
            // Byte-store payloads are untrusted transport: their digest is mandatory
            return EditorSessionManager.getInstance().applyPayload(payloadJson, remote);
        }).thenRun(() -> {
            if (fromSession) EditorSessionManager.getInstance().removeSession(finalToken);
            sendMessage(callerId, "RTP: Successfully applied configurations and hot-reloaded! (.bak copies created for dirty files)");
        }).exceptionally(throwable -> {
            // S-004: Zero silent swallows on invalid token or apply failure; the token is not echoed
            Throwable cause = EditorChannelWiring.cause(throwable);
            if (cause instanceof EditorSessionManager.MissingDigestException) {
                RTP.log(Level.WARNING, "[editor] apply of session " + shown + " refused: " + cause.getMessage());
                sendMessage(callerId, EditorChannelWiring.message("editorApplyDigestMissing",
                        "[P0] The editor payload has no sha256 checksum, so it was not applied. Commit again from an up-to-date editor page."));
                return null;
            }
            RTP.log(Level.WARNING, "[editor] apply of session " + shown + " failed: " + cause.getMessage(), cause);
            sendMessage(callerId, "RTP: Failed to apply payload: " + cause.getMessage());
            return null;
        });
    }

    private void sendMessage(@Nullable UUID callerId, String msg) {
        if (callerId == null || RTP.serverAccessor == null) return;
        try {
            RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg);
        } catch (Exception ignored) {
        }
    }
}
