package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp admin prefab confirm <id> <token>} - validate a confirmation
 * nonce. Per the locked design decision (no {@code --commit} flag), this is
 * the only path that actually applies a prefab.
 *
 * <p><strong>Session 4a scope:</strong> this verb fully validates the nonce
 * (single-use, ~60 s TTL, caller-bound, prefab-id-bound), audit-logs every
 * outcome, and rejects with an explicit "on-disk write lands in Session 4b"
 * notice on success. The on-disk write itself - atomic temp+rename to the
 * actual {@code performance.yml} / {@code regions/&lt;id&gt;.yml}, sibling
 * {@code .bak.&lt;ts&gt;} retention, and the reload pipeline invocation -
 * is wired in Session 4b. See {@code CHECKLIST-admin-panel-prefabs.md} §4.
 *
 * <p>Nonce consumption happens regardless of the 4b stub: the nonce is
 * removed from the store on success, so an admin cannot replay it. This is
 * the security shape we want to validate in 4a tests.
 */
public class PrefabConfirmCmd extends BaseRTPCmdImpl {

    private final PrefabNonceStore nonceStore;

    public PrefabConfirmCmd(@Nullable CommandsAPICommand parent, PrefabNonceStore nonceStore) {
        super(parent);
        this.nonceStore = nonceStore;
        addParameter("id",
                new PrefabIdParameter(PrefabCommand.PERMISSION,
                        "bundled prefab id",
                        (uuid, s) -> true));
        // Token-removal (2026-05-24, mirrors ADR-050): the opaque `token=`
        // parameter is gone. The pending diff is resolved from
        // (callerId, prefabId) via PrefabNonceStore.consumeByCaller.
    }

    @Override
    public String name() {
        return "confirm";
    }

    @Override
    public String permission() {
        return PrefabCommand.PERMISSION;
    }

    @Override
    public String description() {
        return "confirm a pending prefab apply by token";
    }

    @Override
    public boolean onCommand(UUID callerId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand) {
        if (nextCommand != null) return true;
        if (callerId == null) {
            RTP.log(Level.WARNING, "/rtp admin prefab confirm rejected: no caller UUID");
            return false;
        }
        List<String> idValues = parameterValues == null ? null : parameterValues.get("id");
        String prefabId = (idValues == null || idValues.isEmpty()) ? null : idValues.get(0);
        if (prefabId == null || prefabId.isEmpty()) {
            send(callerId, "&cUsage: &f/rtp admin prefab confirm id=<id>");
            return false;
        }
        // Token-removal (2026-05-24, mirrors ADR-050): the pending diff is
        // keyed solely on (callerId, prefabId). No opaque nonce, no TTL,
        // no replay surface — the command sender is the trust boundary.
        PrefabNonceStore.ConsumeResult cr = nonceStore.consumeByCaller(callerId, prefabId);
        switch (cr.kind()) {
            case NOT_FOUND:
                RTP.log(Level.WARNING,
                        "[prefab] confirm rejected NOT_FOUND: caller=" + callerId
                                + " prefab=" + prefabId);
                send(callerId, "&cNo pending confirmation for prefab &f" + prefabId
                        + "&c. Run &f/rtp admin prefab apply id=" + prefabId
                        + "&c first.");
                return false;
            case OK:
                break;
        }
        PrefabNonceStore.Entry entry = cr.entry();
        int changeCount = 0;
        for (List<PrefabApplier.Change> changes : entry.perFileDiff().values()) {
            changeCount += changes.size();
        }

        File pluginDir = (RTP.serverAccessor == null) ? null : RTP.serverAccessor.getPluginDirectory();
        if (pluginDir == null) {
            RTP.log(Level.WARNING,
                    "[prefab] confirm rejected NO_PLUGIN_DIR: caller=" + callerId
                            + " prefab=" + entry.prefabId());
            send(callerId, "&cConfirm failed: plugin directory unavailable (server not fully initialised).");
            return false;
        }

        int retention = resolveBakRetention();
        List<String> writtenFiles = new ArrayList<>();
        List<String> writtenBaks = new ArrayList<>();
        for (Map.Entry<String, List<PrefabApplier.Change>> fe : entry.perFileDiff().entrySet()) {
            String fileId = fe.getKey();
            List<PrefabApplier.Change> changes = fe.getValue();
            if (changes.isEmpty()) continue;
            Map<String, Object> newTree = entry.newTrees().getOrDefault(fileId, java.util.Collections.emptyMap());
            try {
                Path bak = PrefabDiskIO.writeWithBackup(pluginDir, fileId, newTree, changes, retention);
                writtenFiles.add(fileId);
                if (bak != null) writtenBaks.add(bak.getFileName().toString());
            } catch (IOException ioe) {
                RTP.log(Level.WARNING,
                        "[prefab] confirm write FAILED: caller=" + callerId
                                + " prefab=" + entry.prefabId()
                                + " file=" + fileId + " - " + ioe.getMessage(), ioe);
                send(callerId, "&cWrite failed for &f" + fileId + ".yml&c: " + ioe.getMessage());
                send(callerId, "&7Other files (if any) already written remain on disk; "
                        + "use &f/rtp admin prefab rollback " + entry.prefabId() + "&7 to restore.");
                return false;
            }
        }

        // Best-effort schematic install: if the applied prefab references a
        // bundled `.schem` (the per-region `schematic` knob, ADR-058), extract
        // it from the jar into `<pluginDir>/schematics/` so the paste resolves
        // to a real file. Existing files are left untouched; a missing bundled
        // resource is audited (S-004), never fatal to the apply.
        List<String> installedSchematics = new ArrayList<>();
        PrefabRegistry.byId(entry.prefabId()).ifPresent(prefab -> {
            try {
                PrefabSchematicInstaller.Result sr =
                        PrefabSchematicInstaller.install(pluginDir, prefab);
                installedSchematics.addAll(sr.installed());
                if (!sr.installed().isEmpty()) {
                    RTP.log(Level.INFO,
                            "[prefab] schematic install: caller=" + callerId
                                    + " prefab=" + entry.prefabId()
                                    + " installed=" + sr.installed());
                }
                if (!sr.missingResource().isEmpty()) {
                    RTP.log(Level.WARNING,
                            "[prefab] schematic install: caller=" + callerId
                                    + " prefab=" + entry.prefabId()
                                    + " missing bundled resource(s)=" + sr.missingResource()
                                    + " - paste will be a no-op until the file is supplied.");
                }
            } catch (IOException ioe) {
                RTP.log(Level.WARNING,
                        "[prefab] schematic install FAILED: caller=" + callerId
                                + " prefab=" + entry.prefabId()
                                + " - " + ioe.getMessage(), ioe);
            }
        });

        // Best-effort reload: not fatal if it fails (the files are correct on
        // disk; the operator can /rtp reload manually). Mirror ReloadCmd's
        // user-facing chatter (MessagesKeys.reloading / reloaded with
        // [filename] -> "configs") so prefab confirm produces the same
        // "loading configs..." / "successfully loaded configs." lines the
        // operator sees on /rtp reload, instead of a silent swap.
        boolean reloaded = false;
        Throwable reloadFailure = null;
        try {
            if (RTP.configs != null) {
                emitReloadStatus(callerId,
                        io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys.reloading);
                RTP.reloading.set(true);
                try {
                    reloaded = RTP.configs.reload();
                } finally {
                    RTP.reloading.set(false);
                }
                if (reloaded) {
                    emitReloadStatus(callerId,
                            io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys.reloaded);
                }
            }
        } catch (RuntimeException re) {
            reloadFailure = re;
            RTP.reloading.set(false);
        }

        RTP.log(Level.INFO,
                "[prefab] confirm OK: caller=" + callerId
                        + " prefab=" + entry.prefabId()
                        + " files=" + writtenFiles.size()
                        + " changes=" + changeCount
                        + " baks=" + writtenBaks.size()
                        + " reload=" + reloaded);

        send(callerId, "&aPrefab &f" + entry.prefabId() + "&a applied. Wrote "
                + writtenFiles.size() + " file(s), " + changeCount + " change(s).");
        if (!writtenBaks.isEmpty()) {
            send(callerId, "&7Backups: &f" + String.join(", ", writtenBaks));
        }
        if (!installedSchematics.isEmpty()) {
            send(callerId, "&7Installed schematic(s): &f"
                    + String.join(", ", installedSchematics));
        }
        if (reloaded) {
            send(callerId, "&7Config reload completed.");
        } else if (reloadFailure != null) {
            send(callerId, "&eConfig reload failed: " + reloadFailure.getMessage()
                    + " - try &f/rtp reload&e.");
        } else {
            send(callerId, "&7Config reload skipped; run &f/rtp reload&7 to pick up changes.");
        }
        send(callerId, "&7Rollback with: &f/rtp admin prefab rollback " + entry.prefabId());
        return true;
    }

    /**
     * Read the {@code prefab.bakRetention} knob off {@code performance.yml}.
     * Reads the YAML file directly (rather than going through the typed
     * {@code ConfigParser&lt;PerformanceKeys&gt;}) because the knob is
     * namespaced under a {@code prefab:} sub-map that does not yet appear in
     * {@code PerformanceKeys}; introducing the enum constant requires a
     * locale TSV pipeline pass which is Session 6.
     *
     * <p>Falls back to {@link PrefabDiskIO#DEFAULT_BAK_RETENTION} on any
     * failure (no server accessor, file absent, key absent, non-numeric).
     */
    @SuppressWarnings("unchecked")
    private static int resolveBakRetention() {
        try {
            if (RTP.serverAccessor == null) return PrefabDiskIO.DEFAULT_BAK_RETENTION;
            File pluginDir = RTP.serverAccessor.getPluginDirectory();
            if (pluginDir == null) return PrefabDiskIO.DEFAULT_BAK_RETENTION;
            Map<String, Object> perf = PrefabDiskIO.readLive(pluginDir, "performance");
            Object prefabNode = perf.get("prefab");
            if (!(prefabNode instanceof Map<?, ?>)) return PrefabDiskIO.DEFAULT_BAK_RETENTION;
            Object raw = ((Map<String, Object>) prefabNode).get("bakRetention");
            if (raw == null) return PrefabDiskIO.DEFAULT_BAK_RETENTION;
            if (raw instanceof Number n) return Math.max(1, n.intValue());
            try {
                return Math.max(1, Integer.parseInt(raw.toString().trim()));
            } catch (NumberFormatException nfe) {
                return PrefabDiskIO.DEFAULT_BAK_RETENTION;
            }
        } catch (RuntimeException re) {
            return PrefabDiskIO.DEFAULT_BAK_RETENTION;
        }
    }

    private static final java.util.regex.Pattern FILENAME_PATTERN =
            java.util.regex.Pattern.compile("\\[filename]", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Emit the configurable {@code reloading} / {@code reloaded} message
     * (with {@code [filename]} resolved to {@code "configs"}) so a prefab
     * confirm produces the same operator-visible chatter as {@code /rtp
     * reload}. Mirrors the pattern in {@code ReloadCmd}.
     */
    private void emitReloadStatus(UUID callerId,
                                  io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys key) {
        String tmpl;
        try {
            tmpl = msg(key, "");
        } catch (RuntimeException re) {
            return;
        }
        if (tmpl == null || tmpl.isEmpty()) return;
        String resolved = FILENAME_PATTERN.matcher(tmpl).replaceAll("configs");
        if (RTP.serverAccessor == null) return;
        try {
            RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, resolved);
        } catch (RuntimeException ignored) {
            // Test scaffolds without a real sender are not fatal.
        }
    }

    private static void send(UUID callerId, String msg) {
        if (callerId == null || RTP.serverAccessor == null) return;
        try {
            RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg);
        } catch (RuntimeException ignored) {
            // Test scaffolds without a real sender are not fatal.
        }
    }
}
