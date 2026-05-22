package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp admin prefab apply <id>} - preview a prefab and stash a
 * confirmation nonce. Per the locked design decision (2026-05-20, no
 * {@code --commit} flag), this verb is preview-only; the caller must follow
 * up with {@code /rtp admin prefab confirm <id> <token>} to write to disk
 * (the disk-write itself lands in Session 4b - see
 * {@code CHECKLIST-admin-panel-prefabs.md} step 4).
 *
 * <p>Session 4a scope: the diff displayed is "every key the overlay would
 * write", computed against an empty baseline. Session 4b will swap in the
 * live config trees so the diff shows the true delta from the current
 * on-disk state. Either way the nonce stashes the diff that the confirm
 * verb will replay - the security shape (nonce TTL, single-use, caller
 * binding) is testable today.
 *
 * <p>Audit-logs at INFO on every successful apply per REQ-RTP-S-004:
 * &lt;callerId&gt;, prefab id, token, and the number of changes per file.
 */
public class PrefabApplyCmd extends BaseRTPCmdImpl {

    private final PrefabNonceStore nonceStore;

    public PrefabApplyCmd(@Nullable CommandsAPICommand parent, PrefabNonceStore nonceStore) {
        super(parent);
        this.nonceStore = nonceStore;
    }

    @Override
    public String name() {
        return "apply";
    }

    @Override
    public String permission() {
        return PrefabCommand.PERMISSION;
    }

    @Override
    public String description() {
        return "preview a prefab and mint a confirmation token";
    }

    /**
     * Capture the {@code <id>} positional from the args-form dispatch path.
     *
     * <p>TreeCommand's default parser routes any non-{@code key=value} token
     * through subcommand lookup; an unknown token (e.g. a hyphenated prefab
     * id like {@code low-performance}) hits {@code msgInvalidCommand} and
     * never reaches the map-form {@link #onCommand(UUID, Map, CommandsAPICommand)}.
     * Both the menu redeem path ({@code /rtp admin prefab apply <id>}) and
     * a player typing the verb in chat surface this bug; this override
     * captures the first free positional into {@code parameterValues}
     * (under its own value as key, the shape
     * {@link #extractFirstPositional(Map)} already accepts) and invokes the
     * map form directly. Subcommand-style suffixes (none today) would still
     * be routed by the super impl; this verb has no children so a free
     * positional is unambiguously the prefab id.
     */
    @Override
    public CompletableFuture<Boolean> onCommand(@NotNull UUID callerId,
                                                @NotNull Predicate<String> permissionCheckMethod,
                                                @NotNull Consumer<String> messageMethod,
                                                @NotNull String[] args,
                                                int i,
                                                @Nullable Map<String, CommandParameter> tempParameters) {
        if (!permissionCheckMethod.test(permission())) {
            return CompletableFuture.completedFuture(false);
        }
        Map<String, List<String>> parameterValues = new HashMap<>();
        for (; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isEmpty()) continue;
            int eq = arg.indexOf('=');
            if (eq < 0) {
                // Free positional: stash under the value as key with an empty
                // list, matching what extractFirstPositional() accepts.
                parameterValues.computeIfAbsent(arg, k -> new ArrayList<>());
            } else {
                String key = arg.substring(0, eq).toLowerCase(Locale.ROOT);
                String val = arg.substring(eq + 1);
                parameterValues.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
            }
        }
        return CompletableFuture.completedFuture(
                onCommand(callerId, parameterValues, null, messageMethod));
    }

    @Override
    public boolean onCommand(UUID callerId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand) {
        if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);
        if (callerId == null) {
            RTP.log(Level.WARNING, "/rtp admin prefab apply rejected: no caller UUID");
            return false;
        }
        String prefabId = extractFirstPositional(parameterValues);
        if (prefabId == null || prefabId.isEmpty()) {
            send(callerId, "&cUsage: &f/rtp admin prefab apply <id>&c. Try &f/rtp admin prefab list&c.");
            return false;
        }
        Optional<Prefab> opt = PrefabRegistry.byId(prefabId);
        if (opt.isEmpty()) {
            send(callerId, "&cUnknown prefab id: &f" + prefabId
                    + "&c. Try &f/rtp admin prefab list&c.");
            return false;
        }
        Prefab prefab = opt.get();
        // Session 4b: snapshot the live trees so the diff describes the true
        // delta from the on-disk state. Falls back to an empty baseline when
        // RTP.serverAccessor is null (test scaffolds that exercise the
        // command surface without a plugin directory).
        Map<String, Map<String, Object>> baseline;
        try {
            java.io.File pluginDir = (RTP.serverAccessor == null) ? null
                    : RTP.serverAccessor.getPluginDirectory();
            baseline = (pluginDir == null)
                    ? new LinkedHashMap<>()
                    : PrefabDiskIO.snapshotLive(pluginDir, prefab);
        } catch (RuntimeException re) {
            RTP.log(Level.WARNING,
                    "[prefab] apply: live snapshot failed for " + prefab.id()
                            + " - falling back to empty baseline: " + re.getMessage());
            baseline = new LinkedHashMap<>();
        }
        PrefabApplier.Result result = PrefabApplier.apply(baseline, prefab);
        Map<String, List<PrefabApplier.Change>> diff = result.perFileDiff();
        PrefabNonceStore.Entry entry = nonceStore.mint(callerId, prefab.id(), diff, result.newTrees());

        int changeCount = 0;
        for (List<PrefabApplier.Change> changes : diff.values()) {
            changeCount += changes.size();
        }

        RTP.log(Level.INFO,
                "[prefab] apply requested: caller=" + callerId
                        + " prefab=" + prefab.id()
                        + " token=" + entry.token()
                        + " files=" + diff.size()
                        + " changes=" + changeCount);

        send(callerId, "&7Prefab &a" + prefab.id() + "&7: " + prefab.description());
        if (diff.isEmpty()) {
            send(callerId, "&7  (no changes - identity overlay)");
        } else {
            for (Map.Entry<String, List<PrefabApplier.Change>> e : diff.entrySet()) {
                send(callerId, "&f  " + e.getKey() + ".yml &7(" + e.getValue().size() + " changes):");
                int shown = 0;
                for (PrefabApplier.Change c : e.getValue()) {
                    if (shown++ >= 8) {
                        send(callerId, "&7    ... and " + (e.getValue().size() - 8) + " more");
                        break;
                    }
                    String oldStr = c.oldValue() == null ? "&8(absent)" : "&7" + c.oldValue();
                    send(callerId, "&7    &f" + c.keyPath() + " &7= &a" + c.newValue() + " &8(was " + oldStr + "&8)");
                }
            }
        }
        send(callerId, "&7Confirm with: &f/rtp admin prefab confirm "
                + prefab.id() + " " + entry.token());
        send(callerId, "&7Token expires in ~"
                + (PrefabNonceStore.DEFAULT_TTL_MILLIS / 1000) + "s.");
        return true;
    }

    /**
     * Pull the first non-flag positional out of the parameter map. CommandsAPI
     * surfaces positionals under the {@code name:value} key as values of the
     * parameter's declared name; in the absence of such a declaration here we
     * accept any single-element list whose key isn't a known meta-key.
     */
    private static @Nullable String extractFirstPositional(Map<String, List<String>> params) {
        if (params == null || params.isEmpty()) return null;
        for (Map.Entry<String, List<String>> e : params.entrySet()) {
            String key = e.getKey();
            if (key == null) continue;
            String k = key.toLowerCase(Locale.ROOT);
            if (k.equals("admin") || k.equals("prefab") || k.equals("apply")) continue;
            List<String> v = e.getValue();
            if (v == null || v.isEmpty()) {
                if (!k.isEmpty()) return key;
                continue;
            }
            return v.get(0);
        }
        return null;
    }

    private static void send(UUID callerId, String msg) {
        if (callerId == null || RTP.serverAccessor == null) return;
        try {
            RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg);
        } catch (RuntimeException ignored) {
            // Tolerant of test scaffolds without a real sender.
        }
    }
}
