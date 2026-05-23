package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
        // Register the prefab id as a typed CommandParameter so the parser
        // accepts the wire form `apply id=<id>` (and tab-completes against the
        // live PrefabRegistry). Mirrors how InfoCmd registers `world=` /
        // `region=` parameters - no per-id sub-command, no args-form override.
        addParameter("id",
                new PrefabIdParameter(PrefabCommand.PERMISSION,
                        "bundled prefab id",
                        (uuid, s) -> true));
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

    @Override
    public boolean onCommand(UUID callerId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand) {
        if (nextCommand != null) return true;
        if (callerId == null) {
            RTP.log(Level.WARNING, "/rtp admin prefab apply rejected: no caller UUID");
            return false;
        }
        List<String> idValues = parameterValues == null ? null : parameterValues.get("id");
        String prefabId = (idValues == null || idValues.isEmpty()) ? null : idValues.get(0);
        if (prefabId == null || prefabId.isEmpty()) {
            send(callerId, "&cUsage: &f/rtp admin prefab apply id=<id>&c. Try &f/rtp admin prefab list&c.");
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
            // expandPerWorld prefabs (MultiWorld) carry no baked region
            // overlays so snapshotLive() returns nothing for regions/*. The
            // MultiWorldExpander requires regions/default in currentTrees to
            // use as the per-world template, so seed it here.
            if (prefab.expandPerWorld() && pluginDir != null
                    && !baseline.containsKey("regions/" + MultiWorldExpander.DEFAULT_REGION_ID)) {
                baseline.put("regions/" + MultiWorldExpander.DEFAULT_REGION_ID,
                        PrefabDiskIO.readLive(pluginDir,
                                "regions/" + MultiWorldExpander.DEFAULT_REGION_ID));
            }
        } catch (RuntimeException re) {
            RTP.log(Level.WARNING,
                    "[prefab] apply: live snapshot failed for " + prefab.id()
                            + " - falling back to empty baseline: " + re.getMessage());
            baseline = new LinkedHashMap<>();
        }
        // Collect live world names so MultiWorldExpander can synthesise a
        // regions/<world>.yml per loaded world. Ignored when the prefab has
        // expandPerWorld=false (2-arg semantics via the 3-arg overload).
        List<String> worldNames = new ArrayList<>();
        if (RTP.serverAccessor != null) {
            try {
                for (io.github.dailystruggle.rtp.api.world.RTPWorld<?> w
                        : RTP.serverAccessor.getRTPWorlds()) {
                    if (w == null) continue;
                    String n = w.name();
                    if (n != null && !n.isEmpty()) worldNames.add(n);
                }
            } catch (RuntimeException re) {
                RTP.log(Level.WARNING,
                        "[prefab] apply: world enumeration failed for " + prefab.id()
                                + " - falling back to empty world list: " + re.getMessage());
            }
        }
        PrefabApplier.Result result = PrefabApplier.apply(baseline, prefab, worldNames);
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
        // Caller-bound confirm: the nonce store resolves the newest entry
        // for (callerId, prefabId) so the player does not need to copy the
        // opaque token. Chat path still shows the token as a fallback for
        // operators who prefer the explicit form.
        send(callerId, "&7Confirm with: &f/rtp admin prefab confirm id=" + prefab.id());
        send(callerId, "&8(or with explicit token: &f/rtp admin prefab confirm id="
                + prefab.id() + " token=" + entry.token() + "&8)");
        send(callerId, "&7Expires in ~"
                + (PrefabNonceStore.DEFAULT_TTL_MILLIS / 1000) + "s.");
        return true;
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
