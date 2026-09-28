package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabDiskIO;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Common handler utility for Setup commands to render book or console fallback.
 */
public final class SetupHandlerSupport {

    private SetupHandlerSupport() {
    }

    public static boolean renderCurrentStage(
            UUID callerId,
            SetupSession session,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer renderer) {
        if (callerId == null || callerId.equals(SetupSession.CONSOLE_CALLER_ID) || renderer == null) {
            // Render CLI console format
            Map<String, List<PrefabApplier.Change>> diff = null;
            if (session.currentStage() == SetupStage.PREVIEW) {
                diff = computeDiffPreview(session);
            }
            List<String> lines = SetupConsoleFormatter.formatStatus(session, diff);
            for (String line : lines) {
                sendMessage(callerId, line);
            }
            return true;
        }

        // Render In-game Adventure Book menu
        try {
            Map<String, List<PrefabApplier.Change>> diff = null;
            if (session.currentStage() == SetupStage.PREVIEW) {
                diff = computeDiffPreview(session);
            }
            MenuModel model = bookBuilder.build(session, diff);
            renderer.render(callerId, model);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING, "Failed to render setup book menu for " + callerId + ": " + e.getMessage(), e);
            List<String> lines = SetupConsoleFormatter.formatStatus(session, null);
            for (String line : lines) {
                sendMessage(callerId, line);
            }
            return true;
        }
    }

    public static Map<String, List<PrefabApplier.Change>> computeDiffPreview(SetupSession session) {
        try {
            File baseDir = (RTP.serverAccessor != null) ? RTP.serverAccessor.getPluginDirectory() : null;
            List<String> worldNames = collectWorldNames();
            List<Prefab> recipe = SetupRecipe.compileRecipe(session);

            Map<String, Map<String, Object>> baseline = new LinkedHashMap<>();
            if (baseDir != null && baseDir.exists()) {
                for (Prefab p : recipe) {
                    Map<String, Map<String, Object>> snap = PrefabDiskIO.snapshotLive(baseDir, p);
                    for (Map.Entry<String, Map<String, Object>> e : snap.entrySet()) {
                        baseline.putIfAbsent(e.getKey(), e.getValue());
                    }
                }
                // Ensure default region is in baseline if not yet present
                if (!baseline.containsKey("definitions/regions/default")) {
                    Map<String, Object> defRegion = PrefabDiskIO.readLive(baseDir, "definitions/regions/default");
                    if (defRegion.isEmpty()) {
                        defRegion = PrefabDiskIO.readLive(baseDir, "regions/default");
                    }
                    if (!defRegion.isEmpty()) {
                        baseline.put("definitions/regions/default", defRegion);
                    }
                }
            }

            var result = SetupRecipe.applyPipeline(baseline, recipe, worldNames);
            return result.perFileDiff();
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING, "Failed to compute setup diff preview: " + e.getMessage(), e);
            return Map.of();
        }
    }

    public static List<String> collectWorldNames() {
        List<String> worldNames = new ArrayList<>();
        if (RTP.serverAccessor != null) {
            try {
                for (RTPWorld<?> w : RTP.serverAccessor.getRTPWorlds()) {
                    if (w != null && w.name() != null && !w.name().isEmpty()) {
                        worldNames.add(w.name());
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return worldNames;
    }

    public static void sendMessage(@Nullable UUID callerId, String message) {
        if (callerId == null || callerId.equals(SetupSession.CONSOLE_CALLER_ID)) {
            RTP.log(Level.INFO, message);
        } else if (RTP.serverAccessor != null) {
            RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, message);
        }
    }
}
