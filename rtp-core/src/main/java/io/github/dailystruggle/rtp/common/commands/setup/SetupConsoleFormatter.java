package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Text formatter for console and chat fallback execution per ADR-038 Contract 9.
 * Prints structured CLI output showing current selections and exact commands to execute.
 */
public final class SetupConsoleFormatter {

    private SetupConsoleFormatter() {
    }

    public static List<String> formatStatus(SetupSession session, Map<String, List<PrefabApplier.Change>> diffPreview) {
        Objects.requireNonNull(session, "session");
        List<String> out = new ArrayList<>();

        out.add("[RTP Setup Wizard] Current Progress: Stage " + session.currentStage().stageNumber() + "/5 - " + session.currentStage().title());
        out.add("--------------------------------------------------");
        out.add("  1. World Topology:      " + session.worldChoice() + " (cmd: /rtp admin setup world choice=<single|multi>)");
        out.add("  2. Gameplay Style:      " + session.gameplayChoice() + " (cmd: /rtp admin setup gameplay choice=<survival|arena|skyblock|oneblock>)");
        out.add("  3. Performance Profile: " + session.performanceChoice() + " (cmd: /rtp admin setup perf choice=<high|low|folia>)");

        StringBuilder toggles = new StringBuilder();
        for (Map.Entry<String, Boolean> e : session.addonToggles().entrySet()) {
            if (!toggles.isEmpty()) toggles.append(", ");
            toggles.append(e.getKey()).append("=").append(e.getValue());
        }
        out.add("  4. Addons/Effects:      [" + toggles + "] (cmd: /rtp admin setup toggle key=<key>)");

        if (session.currentStage() == SetupStage.PREVIEW || diffPreview != null) {
            out.add("--------------------------------------------------");
            out.add("Pending Config Diff Preview:");
            if (diffPreview == null || diffPreview.isEmpty()) {
                out.add("  (No changes against current configuration)");
            } else {
                for (Map.Entry<String, List<PrefabApplier.Change>> entry : diffPreview.entrySet()) {
                    out.add("  File: " + entry.getKey() + ".yml");
                    for (PrefabApplier.Change c : entry.getValue()) {
                        out.add("    " + c.keyPath() + ": " + c.oldValue() + " -> " + c.newValue());
                    }
                }
            }
            out.add("--------------------------------------------------");
            out.add("To apply changes: /rtp admin setup confirm");
            out.add("To cancel setup:  /rtp admin setup cancel");
        } else {
            out.add("--------------------------------------------------");
            out.add("Next actions: /rtp admin setup next | /rtp admin setup preview | /rtp admin setup cancel");
        }

        return out;
    }
}
