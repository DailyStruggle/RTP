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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Common handler utility for Setup commands to render book or console fallback.
 */
public final class SetupHandlerSupport {

    public static final String CMD_PERMISSION = "rtp.admin.setup";

    private SetupHandlerSupport() {
    }

    public static SetupSession resolveSession(@Nullable UUID callerId, SetupSessionRegistry sessionRegistry) {
        UUID effectiveCaller = (callerId == null) ? SetupSession.CONSOLE_CALLER_ID : callerId;
        return sessionRegistry.getOrCreate(effectiveCaller);
    }

    public static String getFirstParam(Map<String, List<String>> parameterValues, String key) {
        List<String> args = parameterValues.get(key);
        return (args != null && !args.isEmpty()) ? args.get(0) : "";
    }

    public static boolean handleChoice(
            @Nullable UUID callerId,
            Map<String, List<String>> parameterValues,
            SetupSessionRegistry sessionRegistry,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer menuRenderer,
            String usageMessage,
            String invalidChoicePrefix,
            Set<String> validChoices,
            SetupStage nextStage,
            Consumer<String> onValidChoice) {
        String choice = getFirstParam(parameterValues, "choice").toLowerCase(Locale.ROOT);
        if (choice.isEmpty()) {
            sendMessage(callerId, usageMessage);
            return false;
        }

        if (validChoices.contains(choice)) {
            SetupSession session = resolveSession(callerId, sessionRegistry);
            onValidChoice.accept(choice);
            session.setCurrentStage(nextStage);
            return renderCurrentStage(callerId, session, bookBuilder, menuRenderer);
        } else {
            sendMessage(callerId, invalidChoicePrefix + choice + ". Valid: " + String.join(", ", validChoices));
            return false;
        }
    }

    public static boolean renderCurrentStage(
            UUID callerId,
            SetupSession session,
            SetupBookMenuBuilder bookBuilder,
            @Nullable MenuRenderer renderer) {
        if (session == null) {
            return false;
        }

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
            List<Prefab> recipe = SetupRecipe.compileRecipe(session, worldNames);

            Map<String, Map<String, Object>> baseline = loadLiveBaseline(baseDir, recipe);

            var result = SetupRecipe.applyPipeline(baseline, recipe, worldNames);
            return result.perFileDiff();
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING, "Failed to compute setup diff preview: " + e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * Loads the live baseline configuration trees for the given recipe from the plugin directory.
     * Takes snapshots of existing configuration files targeted by the prefabs in the recipe
     * and ensures default region definitions are present.
     *
     * @param baseDir the plugin data directory
     * @param recipe  the compiled recipe prefabs
     * @return map of configuration file IDs to live config trees
     */
    public static Map<String, Map<String, Object>> loadLiveBaseline(@Nullable File baseDir, List<Prefab> recipe) {
        Map<String, Map<String, Object>> baseline = new LinkedHashMap<>();
        if (baseDir != null && baseDir.exists()) {
            for (Prefab p : recipe) {
                Map<String, Map<String, Object>> snap = PrefabDiskIO.snapshotLive(baseDir, p);
                for (Map.Entry<String, Map<String, Object>> e : snap.entrySet()) {
                    if (e.getValue() != null && !e.getValue().isEmpty()) {
                        baseline.putIfAbsent(e.getKey(), e.getValue());
                    }
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
        return baseline;
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
