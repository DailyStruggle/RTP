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
        addParameter("token",
                new PrefabTokenParameter(PrefabCommand.PERMISSION,
                        "confirmation token from /rtp admin prefab apply",
                        (uuid, s) -> true));
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
        List<String> tokenValues = parameterValues == null ? null : parameterValues.get("token");
        String prefabId = (idValues == null || idValues.isEmpty()) ? null : idValues.get(0);
        String token = (tokenValues == null || tokenValues.isEmpty()) ? null : tokenValues.get(0);
        if (prefabId == null || prefabId.isEmpty()) {
            send(callerId, "&cUsage: &f/rtp admin prefab confirm id=<id> [token=<token>]");
            return false;
        }
        // Token is optional: when omitted (menu Confirm row dispatch), the
        // store resolves the newest caller-bound entry. The opaque nonce
        // was a chat-path replay defense only; clicks in the curated book
        // menu are already caller-bound by Bukkit's command sender, so
        // forcing the user to copy a long token into the menu flow is
        // redundant and was reported as a UX defect (2026-05-22).
        PrefabNonceStore.ConsumeResult cr = (token == null || token.isEmpty())
                ? nonceStore.consumeByCaller(callerId, prefabId)
                : nonceStore.consume(token, callerId, prefabId);
        switch (cr.kind()) {
            case NOT_FOUND:
                RTP.log(Level.WARNING,
                        "[prefab] confirm rejected NOT_FOUND: caller=" + callerId
                                + " prefab=" + prefabId + " token=" + token);
                send(callerId, "&cNo such pending confirmation. Tokens are single-use and "
                        + "expire after ~" + (PrefabNonceStore.DEFAULT_TTL_MILLIS / 1000) + "s.");
                return false;
            case EXPIRED:
                RTP.log(Level.WARNING,
                        "[prefab] confirm rejected EXPIRED: caller=" + callerId
                                + " prefab=" + prefabId);
                send(callerId, "&cConfirmation token expired. Re-run "
                        + "&f/rtp admin prefab apply " + prefabId + "&c.");
                return false;
            case WRONG_CALLER:
                RTP.log(Level.WARNING,
                        "[prefab] confirm rejected WRONG_CALLER: caller=" + callerId
                                + " prefab=" + prefabId);
                send(callerId, "&cThat confirmation token belongs to a different admin.");
                return false;
            case WRONG_PREFAB:
                RTP.log(Level.WARNING,
                        "[prefab] confirm rejected WRONG_PREFAB: caller=" + callerId
                                + " prefab=" + prefabId);
                send(callerId, "&cThat confirmation token is for a different prefab.");
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
