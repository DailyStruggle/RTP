package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds Adventure Book {@link MenuModel} instances for each stage of the Setup Wizard.
 * Adheres strictly to the Book Menu Color Contrast guidelines (no yellow/white on parchment).
 * Buttons execute concrete commands (/rtp admin setup pick ..., /rtp admin setup confirm, etc.)
 * per ADR-038 Contract 9.
 */
public final class SetupBookMenuBuilder {

    private static final int MAX_DIFF_LINES_PER_FILE = 12;
    private static final int LINES_PER_PAGE = 13;

    public MenuModel build(SetupSession session, Map<String, List<PrefabApplier.Change>> diffPreview) {
        Objects.requireNonNull(session, "session");

        return switch (session.currentStage()) {
            case WORLD -> buildWorldPage(session);
            case GAMEPLAY -> buildGameplayPage(session);
            case PERFORMANCE -> buildPerformancePage(session);
            case ADDONS -> buildAddonsPage(session);
            case PREVIEW -> buildPreviewPage(session, diffPreview != null ? diffPreview : Map.of());
        };
    }

    private MenuModel buildWorldPage(SetupSession session) {
        List<MenuLine> lines = new ArrayList<>();
        lines.add(header("Step 1/5: World Topology"));
        lines.add(hint("Choose how RTP configures your worlds:"));
        lines.add(emptyLine());

        boolean isSingle = "single".equalsIgnoreCase(session.worldChoice());
        boolean isMulti = "multi".equalsIgnoreCase(session.worldChoice());

        lines.add(choiceLine(
                isSingle ? "&2&l▶ [Single World (Default)]" : "&0  [Single World (Default)]",
                "Keep one default region for the primary world",
                new String[]{"admin", "setup", "world", "choice=single"}
        ));
        lines.add(choiceLine(
                isMulti ? "&2&l▶ [Multi-World (All Worlds)]" : "&0  [Multi-World (All Worlds)]",
                "Synthesise one region per loaded world",
                new String[]{"admin", "setup", "world", "choice=multi"}
        ));

        lines.add(emptyLine());
        addNavigationFooter(lines, false, true, "gameplay");

        return new MenuModel("RTP Setup: World Topology", List.of(new MenuPage(lines)));
    }

    private MenuModel buildGameplayPage(SetupSession session) {
        List<MenuLine> lines = new ArrayList<>();
        lines.add(header("Step 2/5: Gameplay Style"));
        lines.add(hint("Select server gameplay template:"));
        lines.add(emptyLine());

        String current = session.gameplayChoice().toLowerCase();
        lines.add(choiceLine(
                current.equals("survival") ? "&2&l▶ [Standard Survival]" : "&0  [Standard Survival]",
                "Vanilla-like wild RTP with default boundaries",
                new String[]{"admin", "setup", "gameplay", "choice=survival"}
        ));
        lines.add(choiceLine(
                current.equals("arena") ? "&2&l▶ [PvP / Arena Minigame]" : "&0  [PvP / Arena Minigame]",
                "Tighter radius and rapid respawn pacing",
                new String[]{"admin", "setup", "gameplay", "choice=arena"}
        ));
        lines.add(choiceLine(
                current.equals("skyblock") ? "&2&l▶ [Skyblock Server]" : "&0  [Skyblock Server]",
                "Fixed vertical safe-height adjustor for void",
                new String[]{"admin", "setup", "gameplay", "choice=skyblock"}
        ));
        lines.add(choiceLine(
                current.equals("oneblock") ? "&2&l▶ [OneBlock Mode]" : "&0  [OneBlock Mode]",
                "Safety adjustments for OneBlock progression",
                new String[]{"admin", "setup", "gameplay", "choice=oneblock"}
        ));

        lines.add(emptyLine());
        addNavigationFooter(lines, true, true, "performance");

        return new MenuModel("RTP Setup: Gameplay Style", List.of(new MenuPage(lines)));
    }

    private MenuModel buildPerformancePage(SetupSession session) {
        List<MenuLine> lines = new ArrayList<>();
        lines.add(header("Step 3/5: Performance Profile"));
        lines.add(hint("Select hardware pacing profile:"));
        lines.add(emptyLine());

        String current = session.performanceChoice().toLowerCase();
        lines.add(choiceLine(
                current.equals("high") ? "&2&l▶ [Dedicated / High Perf]" : "&0  [Dedicated / High Perf]",
                "Fast caching with high memory concurrency",
                new String[]{"admin", "setup", "perf", "choice=high"}
        ));
        lines.add(choiceLine(
                current.equals("low") ? "&2&l▶ [VPS / Low Performance]" : "&0  [VPS / Low Performance]",
                "Conservative cache and relaxed background pulse",
                new String[]{"admin", "setup", "perf", "choice=low"}
        ));
        lines.add(choiceLine(
                current.equals("folia") ? "&2&l▶ [Folia Region-Tuned]" : "&0  [Folia Region-Tuned]",
                "Optimized for Folia multi-threaded regions",
                new String[]{"admin", "setup", "perf", "choice=folia"}
        ));

        lines.add(emptyLine());
        addNavigationFooter(lines, true, true, "addons");

        return new MenuModel("RTP Setup: Performance", List.of(new MenuPage(lines)));
    }

    private MenuModel buildAddonsPage(SetupSession session) {
        List<MenuLine> lines = new ArrayList<>();
        lines.add(header("Step 4/5: Addons & Effects"));
        lines.add(hint("Toggle feature integrations:"));
        lines.add(emptyLine());

        Map<String, Boolean> toggles = session.addonToggles();
        boolean claims = toggles.getOrDefault("claimIntegrations", true);
        boolean cinematic = toggles.getOrDefault("cinematicEffects", false);

        lines.add(choiceLine(
                claims ? "&2&l☑ [Claim Protection Gating]" : "&8☐ [Claim Protection Gating]",
                "Prevent RTP into Towny/GriefDefender/etc claims",
                new String[]{"admin", "setup", "toggle", "key=claimIntegrations"}
        ));
        lines.add(choiceLine(
                cinematic ? "&2&l☑ [Cinematic UI & Title Effects]" : "&8☐ [Cinematic UI & Title Effects]",
                "Play sound and title cinematic upon teleport",
                new String[]{"admin", "setup", "toggle", "key=cinematicEffects"}
        ));

        lines.add(emptyLine());
        addNavigationFooter(lines, true, true, "preview");

        return new MenuModel("RTP Setup: Addons & Effects", List.of(new MenuPage(lines)));
    }

    private MenuModel buildPreviewPage(SetupSession session, Map<String, List<PrefabApplier.Change>> diff) {
        List<MenuLine> lines = new ArrayList<>();
        lines.add(header("Step 5/5: Preview & Apply"));
        lines.add(hint("Review pending config changes:"));
        lines.add(emptyLine());

        if (diff.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment("&8(no pending changes - matches baseline)", null, null)));
        } else {
            for (Map.Entry<String, List<PrefabApplier.Change>> entry : diff.entrySet()) {
                lines.add(MenuLine.of(new MenuFragment("&1&l" + entry.getKey() + ".yml", null, null)));
                List<PrefabApplier.Change> changes = entry.getValue();
                int shown = Math.min(changes.size(), MAX_DIFF_LINES_PER_FILE);
                for (int i = 0; i < shown; i++) {
                    PrefabApplier.Change c = changes.get(i);
                    lines.add(MenuLine.of(new MenuFragment(
                            "&0  " + c.keyPath() + ": &c" + formatVal(c.oldValue()) + " &8-> &2" + formatVal(c.newValue()),
                            null, null
                    )));
                }
                if (changes.size() > shown) {
                    lines.add(MenuLine.of(new MenuFragment("&8  ... (+" + (changes.size() - shown) + " more)", null, null)));
                }
            }
        }

        // Action buttons
        List<MenuLine> actionRows = new ArrayList<>();
        actionRows.add(MenuLine.of(new MenuFragment(
                "&2&l[✓ APPLY CONFIG]",
                "Apply recipe and write to disk with .bak backups",
                new MenuAction.RunRtpCommand(new String[]{"admin", "setup", "confirm"})
        )));
        actionRows.add(MenuLine.of(new MenuFragment(
                "&c&l[✗ CANCEL SETUP]",
                "Discard session without modifying files",
                new MenuAction.RunRtpCommand(new String[]{"admin", "setup", "cancel"})
        )));
        actionRows.add(MenuLine.of(new MenuFragment(
                "&8[« Back]",
                "Return to previous step",
                new MenuAction.RunRtpCommand(new String[]{"admin", "setup", "back"})
        )));

        List<MenuPage> pages = paginate(lines, actionRows);
        return new MenuModel("RTP Setup: Preview & Apply", pages);
    }

    private static List<MenuPage> paginate(List<MenuLine> bodyLines, List<MenuLine> trailingActionRows) {
        List<MenuPage> pages = new ArrayList<>();
        List<MenuLine> current = new ArrayList<>();

        for (MenuLine line : bodyLines) {
            if (current.size() >= LINES_PER_PAGE) {
                while (!current.isEmpty() && current.get(current.size() - 1).fragments().isEmpty()) {
                    current.remove(current.size() - 1);
                }
                pages.add(new MenuPage(current));
                current = new ArrayList<>();
            }
            if (current.isEmpty() && line != null && line.fragments().isEmpty()) {
                continue;
            }
            current.add(line);
        }

        if (!current.isEmpty()) {
            pages.add(new MenuPage(current));
        }
        if (pages.isEmpty()) {
            pages.add(new MenuPage(new ArrayList<>()));
        }

        if (trailingActionRows != null && !trailingActionRows.isEmpty()) {
            List<MenuLine> lastPageLines = new ArrayList<>(pages.get(pages.size() - 1).lines());
            int neededSlots = trailingActionRows.size() + (lastPageLines.isEmpty() ? 0 : 1);
            boolean spillToNewPage = (lastPageLines.size() + neededSlots) > LINES_PER_PAGE;

            if (spillToNewPage) {
                List<MenuLine> actionPageLines = new ArrayList<>();
                actionPageLines.addAll(trailingActionRows);
                pages.add(new MenuPage(actionPageLines));
            } else {
                if (!lastPageLines.isEmpty()) {
                    MenuLine prev = lastPageLines.get(lastPageLines.size() - 1);
                    if (prev != null && !prev.fragments().isEmpty()) {
                        lastPageLines.add(emptyLine());
                    }
                }
                lastPageLines.addAll(trailingActionRows);
                pages.set(pages.size() - 1, new MenuPage(lastPageLines));
            }
        }

        return pages;
    }

    private static MenuLine header(String text) {
        return MenuLine.of(new MenuFragment("&1&l" + text, null, null));
    }

    private static MenuLine hint(String text) {
        return MenuLine.of(new MenuFragment("&8" + text, null, null));
    }

    private static MenuLine emptyLine() {
        return new MenuLine(List.of());
    }

    private static MenuLine choiceLine(String text, String hover, String[] cmdArgs) {
        return MenuLine.of(new MenuFragment(text, hover, new MenuAction.RunRtpCommand(cmdArgs)));
    }

    private static void addNavigationFooter(List<MenuLine> lines, boolean hasBack, boolean hasNext, String nextChoice) {
        List<MenuFragment> frags = new ArrayList<>();
        if (hasBack) {
            frags.add(new MenuFragment("&8[« Back] ", "Return to previous step",
                    new MenuAction.RunRtpCommand(new String[]{"admin", "setup", "back"})));
        }
        if (hasNext) {
            frags.add(new MenuFragment("&1&l[Next »]", "Proceed to next step",
                    new MenuAction.RunRtpCommand(new String[]{"admin", "setup", "next"})));
        }
        lines.add(new MenuLine(frags));
    }

    private static String formatVal(Object val) {
        if (val == null) return "(unset)";
        String s = val.toString();
        return s.length() > 20 ? s.substring(0, 17) + "..." : s;
    }
}
