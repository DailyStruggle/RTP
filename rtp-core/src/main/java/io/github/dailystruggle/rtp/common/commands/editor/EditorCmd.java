package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Command verb {@code /rtp editor} (ADR-104).
 *
 * <p>Posts active server configurations and documentation to the byte-store transport
 * asynchronously on {@code RTP.scheduler} (S-005) and returns a clickable session URL to
 * the operator, never silently swallowing errors (S-004) and failing closed pre-init (S-006).
 *
 * <p>Hosted sessions are two-way (ADR-106 §5.4): a relay channel is opened before the upload, the
 * snapshot names it, and {@link EditorLiveFeed#startHosted} pushes curve, hazard and land updates
 * over it for the session lifetime. Without a relay the session is snapshot-only (logged).
 */
public class EditorCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = "rtp.editor";

    private final Supplier<EditorHttpTransport> transports;

    /** Bytebin and relay URLs from {@code advanced/network.yml editor}, re-read for every session. */
    public EditorCmd(@Nullable CommandsAPICommand parent) {
        this(parent, (Supplier<EditorHttpTransport>) EditorHttpTransport::fromConfig);
    }

    public EditorCmd(@Nullable CommandsAPICommand parent, EditorHttpTransport transport) {
        this(parent, () -> transport);
    }

    private EditorCmd(@Nullable CommandsAPICommand parent, Supplier<EditorHttpTransport> transports) {
        super(parent);
        this.transports = transports;
        addSubCommand(new EditorLocalSubCmd(this));
        addSubCommand(new ApplyCmd(this, transports));
        addSubCommand(new TrustCmd(this));
        addSubCommand(new UntrustCmd(this));
    }

    @Override
    public String name() {
        return "editor";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "open web configuration and region editor session (ADR-104)";
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

        sendMessage(callerId, "RTP: Generating editor session payload and uploading...");

        // S-005: 100% async scheduling for network and disk I/O (the URL settings are read from disk too).
        // ADR-106 §5.6: the relay channel opens before the upload so the snapshot can name it; without it
        // (null) the session is snapshot-only. Composed, never awaited: openHosted completes (with null on
        // failure) within its own timeouts.
        AtomicReference<EditorHttpTransport> http = new AtomicReference<>();
        AtomicReference<EditorChannel> hosted = new AtomicReference<>();
        EditorSecurity.supplyAsync(transports::get)
        .thenCompose(t -> {
            http.set(t);
            return EditorChannelWiring.openHosted(callerId, t, EditorLiveFeed::active);
        })
        .thenCompose(ch -> EditorSecurity.supplyAsync(() -> {
            hosted.set(ch);
            try {
                EditorSessionManager sessions = EditorSessionManager.getInstance();
                return sessions.createPayloadJson(sessions.collectCurrentConfigs(), ch == null ? null : ch.snapshotBlock());
            } catch (Exception e) {
                RTP.log(Level.WARNING, "Failed to create editor session payload: " + e.getMessage(), e);
                throw new RuntimeException("Failed to prepare payload: " + e.getMessage(), e);
            }
        })).thenCompose(json -> http.get().postPayload(json))
        .thenAccept(token -> {
            EditorHttpTransport transport = http.get();
            EditorChannel ch = hosted.get();
            if (ch != null) {
                try {
                    // Live curve / hazard / land producers for this session, closed with the channel
                    EditorLiveFeed.startHosted(ch, transport::postPayload);
                } catch (RuntimeException e) {
                    RTP.log(Level.WARNING, "[editor] hosted live updates unavailable; the session is snapshot-only: "
                            + e.getMessage(), e);
                    ch.close("live feed failed: " + e.getMessage());
                }
            }
            // The operator needs the link; logs only carry a token prefix
            RTP.log(Level.FINE, "[editor] hosted session " + EditorSecurity.tokenPrefix(token) + " uploaded");
            String editorUrl = transport.buildEditorUrl(token);
            sendMessage(callerId, "RTP: Editor session created! Open link to edit: " + editorUrl);
            sendMessage(callerId, "RTP: Apply changes back when done using: /rtp editor apply token=" + token);
        }).exceptionally(throwable -> {
            EditorChannel ch = hosted.get();
            if (ch != null) ch.close("upload failed");
            // S-004: Zero silent swallows on HTTP failure
            Throwable cause = (throwable.getCause() != null) ? throwable.getCause() : throwable;
            String errMsg = "RTP: Failed to upload editor session to byte-store: " + cause.getMessage();
            RTP.log(Level.WARNING, errMsg, cause);
            sendMessage(callerId, errMsg);

            // Fallback: automatically generate local standalone editor bundle when remote upload fails
            try {
                File pluginDir = (RTP.serverAccessor != null) ? RTP.serverAccessor.getPluginDirectory() : new File(".");
                if (pluginDir == null) pluginDir = new File(".");
                java.nio.file.Path localPath = pluginDir.toPath().resolve("editor").resolve("index.html");
                Map<String, String> configs = EditorSessionManager.getInstance().collectCurrentConfigs();
                EditorLiveFeed.exportAndStart(localPath, configs, callerId);
                sendMessage(callerId, "RTP: Generated offline local editor bundle at " + localPath.toAbsolutePath());
            } catch (Exception localEx) {
                RTP.log(Level.WARNING, "Failed to generate fallback local editor: " + localEx.getMessage(), localEx);
            }
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
