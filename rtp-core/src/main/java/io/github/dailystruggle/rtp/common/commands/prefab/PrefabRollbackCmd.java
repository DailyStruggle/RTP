package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp admin prefab rollback <id>} - restore the most recent
 * {@code .bak.&lt;ts&gt;} siblings produced by a prior confirm. Per the
 * design decision (last-3 retention with the {@code prefab.bakRetention}
 * knob), rollback walks the retention chain from newest to oldest.
 */
public class PrefabRollbackCmd extends BaseRTPCmdImpl {

    public PrefabRollbackCmd(@Nullable CommandsAPICommand parent) {
        super(parent);
        addParameter("id",
                new PrefabIdParameter(PrefabCommand.PERMISSION,
                        "bundled prefab id",
                        (uuid, s) -> true));
    }

    @Override
    public String name() {
        return "rollback";
    }

    @Override
    public String permission() {
        return PrefabCommand.PERMISSION;
    }

    @Override
    public String description() {
        return "restore the most recent .bak siblings produced by a prefab confirm";
    }

    @Override
    public boolean onCommand(UUID callerId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand) {
        if (nextCommand != null) return true;
        if (callerId == null) {
            RTP.log(Level.WARNING, "/rtp admin prefab rollback rejected: no caller UUID");
            return false;
        }
        List<String> idValues = parameterValues == null ? null : parameterValues.get("id");
        String prefabId = (idValues == null || idValues.isEmpty()) ? null : idValues.get(0);
        if (prefabId == null || prefabId.isEmpty()) {
            send(callerId, "&cUsage: &f/rtp admin prefab rollback id=<id>");
            return false;
        }
        Optional<Prefab> opt = PrefabRegistry.byId(prefabId);
        if (opt.isEmpty()) {
            send(callerId, "&cUnknown prefab id: &f" + prefabId
                    + "&c. Try &f/rtp admin prefab list&c.");
            return false;
        }
        Prefab prefab = opt.get();
        Optional<File> pluginDirOpt = PrefabDiskIO.resolvePluginDirectory(callerId, "rollback", prefabId,
                "&cRollback failed: plugin directory unavailable.");
        if (pluginDirOpt.isEmpty()) {
            return false;
        }
        File pluginDir = pluginDirOpt.get();

        // Enumerate the file ids the prefab touches: perf overlay + region overlays.
        Set<String> fileIds = PrefabDiskIO.enumeratePrefabFileIds(prefab);

        List<String> restored = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String fileId : fileIds) {
            try {
                Path used = PrefabDiskIO.restoreLatest(pluginDir, fileId);
                if (used == null) {
                    empty.add(fileId);
                } else {
                    restored.add(fileId + " <- " + used.getFileName().toString());
                }
            } catch (IOException ioe) {
                failed.add(fileId + ": " + ioe.getMessage());
                RTP.log(Level.WARNING,
                        "[prefab] rollback restore FAILED: caller=" + callerId
                                + " prefab=" + prefabId
                                + " file=" + fileId + " - " + ioe.getMessage(), ioe);
            }
        }

        if (restored.isEmpty() && failed.isEmpty()) {
            RTP.log(Level.INFO,
                    "[prefab] rollback no-op (no backups present): caller=" + callerId
                            + " prefab=" + prefabId
                            + " filesChecked=" + fileIds.size());
            send(callerId, "&eNo &f.bak&e siblings found for prefab &f" + prefabId
                    + "&e (nothing to restore).");
            return false;
        }

        boolean reloaded = false;
        Throwable reloadFailure = null;
        try {
            if (RTP.configs != null) reloaded = RTP.configs.reload();
        } catch (RuntimeException re) {
            reloadFailure = re;
        }

        RTP.log(Level.INFO,
                "[prefab] rollback OK: caller=" + callerId
                        + " prefab=" + prefabId
                        + " restored=" + restored.size()
                        + " empty=" + empty.size()
                        + " failed=" + failed.size()
                        + " reload=" + reloaded);
        if (!restored.isEmpty()) {
            send(callerId, "&aRestored " + restored.size() + " file(s) for prefab &f"
                    + prefabId + "&a:");
            for (String r : restored) send(callerId, "  &7- &f" + r);
        }
        if (!empty.isEmpty()) {
            send(callerId, "&7No backup found for: &f" + String.join(", ", empty));
        }
        if (!failed.isEmpty()) {
            for (String f : failed) send(callerId, "&cRestore failed: &f" + f);
        }
        if (reloaded) {
            send(callerId, "&7Config reload completed.");
        } else if (reloadFailure != null) {
            send(callerId, "&eConfig reload failed: " + reloadFailure.getMessage()
                    + " - try &f/rtp reload&e.");
        } else {
            send(callerId, "&7Run &f/rtp reload&7 to pick up the restored files.");
        }
        return failed.isEmpty();
    }

    private static void send(UUID callerId, String msg) {
        PrefabDiskIO.send(callerId, msg);
    }
}
