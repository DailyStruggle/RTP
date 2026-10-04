package io.github.dailystruggle.rtp.common.commands.docs;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Subcommand {@code /rtp docs export [path]} (ADR-045, ADR-104).
 * Asynchronously exports a standalone single-file HTML documentation bundle.
 * Ensures zero synchronous file I/O on the main thread (S-005) and logs failures (S-004).
 */
public class DocsExportSubCmd extends BaseRTPCmdImpl {

    public static final String PERMISSION = "rtp.admin.docs";

    public DocsExportSubCmd(@Nullable CommandsAPICommand parent) {
        super(parent);
    }

    @Override
    public String name() {
        return "export";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public String description() {
        return "export standalone offline HTML documentation bundle (ADR-104)";
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

        // Determine target path
        Path targetPath;
        File pluginDir = (RTP.serverAccessor != null) ? RTP.serverAccessor.getPluginDirectory() : new File(".");
        targetPath = pluginDir.toPath().resolve("docs-bundle.html");

        sendMessage(callerId, "RTP: Asynchronously generating standalone HTML documentation bundle...");

        final Path finalTargetPath = targetPath;
        // Zero sync I/O on main thread (S-005): run asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                DocsRegistry.getInstance().exportLocalHtmlBundle(finalTargetPath);
            } catch (Exception e) {
                RTP.log(Level.WARNING, "Failed to export documentation bundle to " + finalTargetPath + ": " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }).thenRun(() -> {
            String msg = "RTP: Documentation bundle exported successfully to " + finalTargetPath.toAbsolutePath();
            sendMessage(callerId, msg);
        }).exceptionally(throwable -> {
            // S-004: Never silently swallow failure
            Throwable cause = (throwable.getCause() != null) ? throwable.getCause() : throwable;
            String err = "RTP: Failed to export documentation bundle: " + cause.getMessage();
            RTP.log(Level.WARNING, err, cause);
            sendMessage(callerId, err);
            return null;
        });

        return true;
    }

    private void sendMessage(@Nullable UUID callerId, String msg) {
        if (callerId == null || RTP.serverAccessor == null) return;
        try {
            RTP.serverAccessor.sendMessage(callerId, msg);
        } catch (RuntimeException ignored) {
        }
    }
}
