package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp admin prefab apply id=<id>} - preview prefab and stash confirmation nonce.
 * Must confirm with {@code /rtp admin prefab confirm id=<id>} to apply changes (REQ-RTP-S-004).
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
        // Snapshot the live trees so the diff describes the true delta from the on-disk state.
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
        // expandPerWorld prefabs also repoint each synthesised world's
        // worlds/<world>.yml "region" field at its new per-world region. Every
        // loaded world has an in-memory WorldKeys parser by definition (a
        // worlds/<world>.yml file may not exist on disk when the world runs on
        // defaults), so seed the baseline from RTP.configs rather than reading
        // the file. That makes the diff describe the true delta from the live
        // region binding (e.g. "default" -> "<world>") instead of an absent
        // value when the physical file is missing.
        if (prefab.expandPerWorld() && RTP.configs != null) {
            for (String world : worldNames) {
                String fileId = "worlds/" + world;
                Map<String, Object> worldTree = new LinkedHashMap<>();
                try {
                    ConfigParser<WorldKeys> worldParser = RTP.configs.getWorldParser(world);
                    if (worldParser != null) {
                        Object region = worldParser.getConfigValue(WorldKeys.region, null);
                        if (region != null) {
                            worldTree.put("region", region);
                        }
                    }
                } catch (RuntimeException re) {
                    RTP.log(Level.WARNING,
                            "[prefab] apply: world region lookup failed for " + fileId
                                    + " - falling back to empty baseline: " + re.getMessage());
                }
                baseline.put(fileId, worldTree);
            }
        }
        // For expandPerWorld prefabs, repair each synthesised per-world region
        // overlay for its destination dimension before the diff is computed, so
        // the preview matches what confirm writes. The dimension rules are not
        // duplicated here: route through the canonical NetherEndConfigAmender
        // (the same helper /rtp config region uses), reading the live
        // regions/default vert as the template and the real world height
        // bounds.
        MultiWorldExpander.RegionOverlayAmender vertAmender = buildDimensionVertAmender(prefab);
        PrefabApplier.Result result = PrefabApplier.apply(baseline, prefab, worldNames, vertAmender);
        Map<String, List<PrefabApplier.Change>> diff = result.perFileDiff();
        PrefabNonceStore.Entry entry = nonceStore.mint(callerId, prefab.id(), diff, result.newTrees());

        int changeCount = 0;
        for (List<PrefabApplier.Change> changes : diff.values()) {
            changeCount += changes.size();
        }

        RTP.log(Level.INFO,
                "[prefab] apply requested: caller=" + callerId
                        + " prefab=" + prefab.id()
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
        // Token-removal (2026-05-24, mirrors ADR-050): the pending diff is
        // keyed solely on (callerId, prefabId); no opaque token surfaces.
        send(callerId, "&7Confirm with: &f/rtp admin prefab confirm id=" + prefab.id());
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

    /**
     * Builds dimension vert amender for {@code expandPerWorld} prefab, or null if unavailable.
     * Delegates rules to canonical {@code NetherEndConfigAmender}.
     */
    @SuppressWarnings("unchecked")
    private static MultiWorldExpander.RegionOverlayAmender buildDimensionVertAmender(Prefab prefab) {
        if (!prefab.expandPerWorld()) return null;
        if (RTP.serverAccessor == null || RTP.configs == null) return null;
        MultiConfigParser<RegionKeys> regions =
                (MultiConfigParser<RegionKeys>) RTP.configs.multiConfigParserMap.get(RegionKeys.class);
        if (regions == null) return null;
        // getParser(...) assumes a "default" entry exists (it falls back to it
        // for unknown names) and NPEs when the regions tree is empty, so guard
        // on the registered set first.
        if (!regions.listParsers().contains(MultiWorldExpander.DEFAULT_REGION_ID)) return null;
        ConfigParser<RegionKeys> defParser = regions.getParser(MultiWorldExpander.DEFAULT_REGION_ID);
        if (defParser == null) return null;
        return (world, overlay) -> {
            try {
                io.github.dailystruggle.rtp.api.world.RTPWorld<?> rtpWorld =
                        RTP.serverAccessor.getRTPWorld(world);
                if (rtpWorld == null) return;
                String wn = rtpWorld.name();
                if (wn == null || !(wn.endsWith("_nether") || wn.endsWith("_the_end"))) return;
                Map<String, List<String>> pv = new HashMap<>();
                io.github.dailystruggle.rtp.common.commands.menu.multiconfig.NetherEndConfigAmender
                        .amend(pv, defParser, rtpWorld);
                Object vertObj = overlay.get("vert");
                Map<String, Object> vert;
                if (vertObj instanceof Map<?, ?> m) {
                    vert = (Map<String, Object>) m;
                } else {
                    vert = new LinkedHashMap<>();
                    overlay.put("vert", vert);
                }
                if (pv.containsKey("vert")) putVert(vert, "name", pv.get("vert").get(0));
                putVertInt(vert, "maxY", pv.get("maxy"));
                putVertInt(vert, "minY", pv.get("miny"));
                if (pv.containsKey("requireskylight")) {
                    putVert(vert, "requireSkyLight",
                            Boolean.parseBoolean(pv.get("requireskylight").get(0)));
                }
            } catch (RuntimeException re) {
                RTP.log(Level.WARNING,
                        "[prefab] dimension vert repair failed for world " + world
                                + " - " + re.getMessage());
            }
        };
    }

    private static void putVert(Map<String, Object> vert, String key, Object value) {
        for (Map.Entry<String, Object> e : vert.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                e.setValue(value);
                return;
            }
        }
        vert.put(key, value);
    }

    private static void putVertInt(Map<String, Object> vert, String key, List<String> values) {
        if (values == null || values.isEmpty()) return;
        try {
            putVert(vert, key, Integer.parseInt(values.get(0).trim()));
        } catch (NumberFormatException ignored) {
            // Non-numeric amender output is not expected; leave the cloned value.
        }
    }
}
