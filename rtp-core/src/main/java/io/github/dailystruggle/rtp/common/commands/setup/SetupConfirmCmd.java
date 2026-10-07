package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabDiskIO;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * {@code /rtp admin setup confirm}
 * Compiles the final setup recipe, applies overlays to disk with timestamped .bak backups,
 * logs audit records, and clears the session.
 */
public class SetupConfirmCmd extends BaseRTPCmdImpl {

    public static final String CMD_PERMISSION = "rtp.admin.setup";

    private final SetupSessionRegistry sessionRegistry;

    public SetupConfirmCmd(
            @Nullable CommandsAPICommand parent,
            SetupSessionRegistry sessionRegistry) {
        super(parent);
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
    }

    @Override
    public String name() {
        return "confirm";
    }

    @Override
    public String permission() {
        return CMD_PERMISSION;
    }

    @Override
    public String description() {
        return "confirm and apply setup wizard recipe to configuration files";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
        UUID effectiveCaller = (callerId == null) ? SetupSession.CONSOLE_CALLER_ID : callerId;
        var opt = sessionRegistry.get(effectiveCaller);
        if (opt.isEmpty()) {
            SetupHandlerSupport.sendMessage(callerId, "&c[RTP Setup] No active setup session found. Run /rtp admin setup to start.");
            return false;
        }

        SetupSession session = opt.get();
        File baseDir = (RTP.serverAccessor != null) ? RTP.serverAccessor.getPluginDirectory() : null;
        if (baseDir == null) {
            SetupHandlerSupport.sendMessage(callerId, "&c[RTP Setup] Cannot resolve plugin directory for writing configs.");
            return false;
        }

        List<String> worldNames = SetupHandlerSupport.collectWorldNames();
        List<Prefab> recipe = SetupRecipe.compileRecipe(session, worldNames);

        Map<String, Map<String, Object>> baseline = SetupHandlerSupport.loadLiveBaseline(baseDir, recipe);

        PrefabApplier.Result result = SetupRecipe.applyPipeline(baseline, recipe, worldNames);
        Map<String, List<PrefabApplier.Change>> diff = result.perFileDiff();

        if (diff.isEmpty()) {
            sessionRegistry.remove(effectiveCaller);
            SetupHandlerSupport.sendMessage(callerId, "&a[RTP Setup] Setup finished (identity overlay - no configuration changes needed).");
            return true;
        }

        sessionRegistry.remove(effectiveCaller);
        SetupHandlerSupport.sendMessage(callerId, "&7[RTP Setup] Applying setup recipe in background...");

        Runnable applyTask = () -> {
            List<String> writtenFiles = new ArrayList<>();
            List<Path> backups = new ArrayList<>();
            String defaultRegionFileId = "definitions/regions/" + io.github.dailystruggle.rtp.common.commands.prefab.MultiWorldExpander.DEFAULT_REGION_ID;

            try {
                for (Map.Entry<String, List<PrefabApplier.Change>> entry : diff.entrySet()) {
                    String fileId = entry.getKey();
                    Map<String, Object> newTree = result.newTrees().get(fileId);
                    if (newTree == null) continue;

                    String templateFileId = (fileId.startsWith("definitions/regions/") && !fileId.equals(defaultRegionFileId))
                            ? defaultRegionFileId : null;

                    Path bak = PrefabDiskIO.writeWithBackup(
                            baseDir,
                            fileId,
                            newTree,
                            entry.getValue(),
                            PrefabDiskIO.DEFAULT_BAK_RETENTION,
                            templateFileId
                    );
                    writtenFiles.add(fileId + ".yml");
                    if (bak != null) {
                        backups.add(bak);
                    }
                }

                RTP.log(Level.INFO, "[setup] Applied setup wizard recipe for caller=" + callerId
                        + ", writtenFiles=" + writtenFiles.size() + ", backups=" + backups.size());
                SetupHandlerSupport.sendMessage(callerId, "&a[RTP Setup] Successfully applied setup recipe!");
                SetupHandlerSupport.sendMessage(callerId, "&7Written: &f" + String.join(", ", writtenFiles));
                SetupHandlerSupport.sendMessage(callerId, "&7Created &a" + backups.size() + " &7timestamped .bak backups.");

                boolean reloaded = false;
                Throwable reloadFailure = null;
                try {
                    CommandsAPICommand reload = (RTP.baseCommand != null)
                            ? RTP.baseCommand.getCommandLookup().get("reload")
                            : null;
                    if (reload != null) {
                        reloaded = reload.onCommand(callerId, java.util.Collections.emptyMap(), null);
                    } else if (RTP.configs != null) {
                        RTP.reloading.set(true);
                        try {
                            reloaded = RTP.configs.reload();
                        } finally {
                            RTP.reloading.set(false);
                        }
                    }
                } catch (RuntimeException re) {
                    reloadFailure = re;
                    RTP.reloading.set(false);
                }

                if (reloaded) {
                    SetupHandlerSupport.sendMessage(callerId, "&a[RTP Setup] Configuration reload completed!");
                } else if (reloadFailure != null) {
                    SetupHandlerSupport.sendMessage(callerId, "&e[RTP Setup] Config reload failed: "
                            + reloadFailure.getMessage() + " - try &f/rtp reload&e.");
                } else {
                    SetupHandlerSupport.sendMessage(callerId, "&7[RTP Setup] Run &f/rtp reload&7 to pick up changes.");
                }
            } catch (IOException | RuntimeException e) {
                RTP.log(Level.WARNING, "[setup] Failed to write setup recipe to disk: " + e.getMessage(), e);
                SetupHandlerSupport.sendMessage(callerId, "&c[RTP Setup] Error writing setup configuration to disk: " + e.getMessage());
            }
        };

        if (RTP.scheduler != null) {
            RTP.scheduler.runTaskAsynchronously(applyTask);
        } else {
            applyTask.run();
        }

        return true;
    }
}
