package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Subcommand {@code /rtp editor local} (ADR-104).
 * Generates a self-contained, standalone offline web workspace HTML file at
 * {@code <dataFolder>/editor/index.html}.
 *
 * <p>Embeds active configurations, region definitions, and offline documentation
 * so operators in air-gapped or restricted network environments can view, configure,
 * and test staging visualizers without needing outbound internet access or an external byte-store.
 *
 * <p>Executes asynchronously off the main thread (S-005), logs failures (S-004),
 * and fails closed pre-initialization (S-006).
 */
public class EditorLocalSubCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = "rtp.editor";

    public EditorLocalSubCmd(@Nullable CommandsAPICommand parent) {
        super(parent);
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "generate local standalone web editor HTML bundle (ADR-104)";
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

        File pluginDir = RTP.serverAccessor.getPluginDirectory();
        if (pluginDir == null) {
            pluginDir = new File(".");
        }
        Path targetPath = pluginDir.toPath().resolve("editor").resolve("index.html");

        sendMessage(callerId, "RTP: Asynchronously generating standalone local web editor HTML bundle...");

        final Path finalTargetPath = targetPath;
        // S-005: Zero synchronous disk I/O on main thread; RTP.scheduler's async pool
        EditorSecurity.runAsync(() -> {
            try {
                Map<String, String> configs = EditorSessionManager.getInstance().collectCurrentConfigs();
                EditorLiveFeed.exportAndStart(finalTargetPath, configs, callerId);
            } catch (Exception e) {
                RTP.log(Level.WARNING, "Failed to generate local editor HTML bundle at " + finalTargetPath + ": " + e.getMessage(), e);
                throw new RuntimeException("Failed to generate local editor HTML: " + e.getMessage(), e);
            }
        }).thenRun(() -> {
            String successMsg = "RTP: Standalone web editor generated successfully at " + finalTargetPath.toAbsolutePath();
            sendMessage(callerId, successMsg);
            sendMessage(callerId, "RTP: You can open this file directly in any web browser (file:///) offline.");
            sendMessage(callerId, "RTP: Opened from this folder, the page refreshes metrics and land data every "
                    + (EditorLiveFeed.PERIOD_MILLIS / 1000L) + "s for " + (EditorLiveFeed.DEFAULT_TTL_MILLIS / 60_000L) + " minutes.");
        }).exceptionally(throwable -> {
            // S-004: Never silently swallow failure
            Throwable cause = (throwable.getCause() != null) ? throwable.getCause() : throwable;
            String errMsg = "RTP: Failed to generate local editor HTML bundle: " + cause.getMessage();
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
