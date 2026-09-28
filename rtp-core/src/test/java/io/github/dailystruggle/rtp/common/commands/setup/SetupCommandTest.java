package io.github.dailystruggle.rtp.common.commands.setup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SetupCommandTest {

    @Test
    @DisplayName("SetupCmd exposes all subcommands with proper permissions")
    void testSetupCommandStructure() {
        SetupSessionRegistry registry = new SetupSessionRegistry();
        SetupBookMenuBuilder builder = new SetupBookMenuBuilder();
        SetupCmd setupCmd = new SetupCmd(null, registry, builder, null);

        assertEquals("setup", setupCmd.name());
        assertEquals("rtp.admin.setup", setupCmd.permission());

        Map<String, ?> children = setupCmd.getCommandLookup();
        assertNotNull(children);
        assertTrue(children.containsKey("WORLD"), "missing world");
        assertTrue(children.containsKey("GAMEPLAY"), "missing gameplay");
        assertTrue(children.containsKey("PERF"), "missing perf");
        assertTrue(children.containsKey("NEXT"), "missing next");
        assertTrue(children.containsKey("TOGGLE"), "missing toggle");
        assertTrue(children.containsKey("BACK"), "missing back");
        assertTrue(children.containsKey("PREVIEW"), "missing preview");
        assertTrue(children.containsKey("CONFIRM"), "missing confirm");
        assertTrue(children.containsKey("CANCEL"), "missing cancel");
        assertTrue(children.containsKey("STATUS"), "missing status");

        for (var sub : children.values()) {
            assertEquals(SetupCmd.PERMISSION,
                    ((io.github.dailystruggle.commandsapi.common.CommandsAPICommand) sub).permission(),
                    "all setup sub-commands must share rtp.admin.setup");
        }

        // Verify choice parameters per stage
        var worldCmd = (io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand) children.get("WORLD");
        assertEquals(java.util.Set.of("single", "multi"), worldCmd.getParameterLookup().get("choice").values());

        var gameplayCmd = (io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand) children.get("GAMEPLAY");
        assertEquals(java.util.Set.of("survival", "arena", "skyblock", "oneblock"), gameplayCmd.getParameterLookup().get("choice").values());

        var perfCmd = (io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand) children.get("PERF");
        assertEquals(java.util.Set.of("high", "low", "folia"), perfCmd.getParameterLookup().get("choice").values());

        var toggleCmd = (io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand) children.get("TOGGLE");
        assertEquals(java.util.Set.of("claimIntegrations", "cinematicEffects", "economyIntegration"), toggleCmd.getParameterLookup().get("key").values());
    }

    @Test
    @DisplayName("Setup subcommands mutate session stage and choices")
    void testSubcommandExecution() {
        SetupSessionRegistry registry = new SetupSessionRegistry();
        SetupBookMenuBuilder builder = new SetupBookMenuBuilder();
        SetupCmd setupCmd = new SetupCmd(null, registry, builder, null);

        UUID callerId = UUID.randomUUID();

        // 1. world choice=multi
        setupCmd.getCommandLookup().get("WORLD").onCommand(callerId, Map.of(
                "choice", List.of("multi")
        ), null);

        SetupSession session = registry.get(callerId).orElseThrow();
        assertEquals("multi", session.worldChoice());
        assertEquals(SetupStage.GAMEPLAY, session.currentStage());

        // 2. gameplay choice=arena
        setupCmd.getCommandLookup().get("GAMEPLAY").onCommand(callerId, Map.of(
                "choice", List.of("arena")
        ), null);
        assertEquals("arena", session.gameplayChoice());
        assertEquals(SetupStage.PERFORMANCE, session.currentStage());

        // 3. perf choice=low
        setupCmd.getCommandLookup().get("PERF").onCommand(callerId, Map.of(
                "choice", List.of("low")
        ), null);
        assertEquals("low", session.performanceChoice());
        assertEquals(SetupStage.ADDONS, session.currentStage());

        // 4. Step back
        setupCmd.getCommandLookup().get("BACK").onCommand(callerId, Map.of(), null);
        assertEquals(SetupStage.PERFORMANCE, session.currentStage());

        // 5. Next
        setupCmd.getCommandLookup().get("NEXT").onCommand(callerId, Map.of(), null);
        assertEquals(SetupStage.ADDONS, session.currentStage());

        // 6. Toggle addon key=cinematicEffects
        setupCmd.getCommandLookup().get("TOGGLE").onCommand(callerId, Map.of(
                "key", List.of("cinematicEffects")
        ), null);
        assertTrue(session.addonToggles().get("cinematicEffects"));

        // 7. Jump to preview
        setupCmd.getCommandLookup().get("PREVIEW").onCommand(callerId, Map.of(), null);
        assertEquals(SetupStage.PREVIEW, session.currentStage());

        // 8. Cancel
        setupCmd.getCommandLookup().get("CANCEL").onCommand(callerId, Map.of(), null);
        assertTrue(registry.get(callerId).isEmpty());
    }

    @Test
    @DisplayName("End-to-end command dispatch through TreeCommand with arguments executes child successfully")
    void testEndToEndTreeCommandExecution() {
        SetupSessionRegistry registry = new SetupSessionRegistry();
        SetupBookMenuBuilder builder = new SetupBookMenuBuilder();
        io.github.dailystruggle.rtp.common.commands.admin.AdminCmd adminCmd =
                new io.github.dailystruggle.rtp.common.commands.admin.AdminCmd(null);
        SetupCmd setupCmd = new SetupCmd(adminCmd, registry, builder, null);
        adminCmd.addSubCommand(setupCmd);

        UUID callerId = UUID.randomUUID();

        // Simulate: /rtp admin setup world choice=multi
        // TreeCommand parses args: ["setup", "world", "choice=multi"] starting from adminCmd
        java.util.concurrent.CompletableFuture<Boolean> future = adminCmd.onCommand(
                callerId,
                perm -> true,
                msg -> {},
                new String[]{"setup", "world", "choice=multi"},
                0,
                null
        );

        // Drain CommandsAPI pipeline
        while (!io.github.dailystruggle.commandsapi.common.CommandsAPI.commandPipeline.isEmpty()) {
            io.github.dailystruggle.commandsapi.common.CommandsAPI.execute();
        }

        assertTrue(future.isDone());
        assertTrue(future.join());

        SetupSession session = registry.get(callerId).orElseThrow();
        assertEquals("multi", session.worldChoice());
        assertEquals(SetupStage.GAMEPLAY, session.currentStage());
    }

    @Test
    @DisplayName("SetupBookMenuBuilder preview page paginates diff and keeps action buttons accessible")
    void testPreviewPagePagination() {
        SetupBookMenuBuilder builder = new SetupBookMenuBuilder();
        SetupSession session = new SetupSession(UUID.randomUUID());
        session.setCurrentStage(SetupStage.PREVIEW);

        // 1. Small diff (fits on 1 page with actions)
        Map<String, List<io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier.Change>> smallDiff = Map.of(
                "advanced/performance", List.of(
                        new io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier.Change("period", 20, 60)
                )
        );
        io.github.dailystruggle.rtp.api.menu.MenuModel smallModel = builder.build(session, smallDiff);
        assertNotNull(smallModel);
        assertEquals(1, smallModel.pages().size(), "Small diff should fit on a single page");

        // 2. Large diff with multiple files and many changes (must paginate across >= 2 pages)
        java.util.List<io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier.Change> changes1 = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            changes1.add(new io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier.Change("key" + i, "old" + i, "new" + i));
        }
        java.util.List<io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier.Change> changes2 = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            changes2.add(new io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier.Change("key" + i, "old" + i, "new" + i));
        }

        Map<String, List<io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier.Change>> largeDiff = new java.util.LinkedHashMap<>();
        largeDiff.put("advanced/performance", changes1);
        largeDiff.put("definitions/regions/default", changes2);

        io.github.dailystruggle.rtp.api.menu.MenuModel largeModel = builder.build(session, largeDiff);
        assertNotNull(largeModel);
        assertTrue(largeModel.pages().size() >= 2, "Large diff must produce multiple pages");

        // Verify that every page has at most 13 lines
        for (int i = 0; i < largeModel.pages().size(); i++) {
            io.github.dailystruggle.rtp.api.menu.MenuPage page = largeModel.pages().get(i);
            assertTrue(page.lines().size() <= 13, "Page " + i + " must not exceed 13 lines, got " + page.lines().size());
        }

        // Verify that the last page contains the action buttons
        io.github.dailystruggle.rtp.api.menu.MenuPage lastPage = largeModel.pages().get(largeModel.pages().size() - 1);
        boolean hasApply = lastPage.lines().stream()
                .flatMap(l -> l.fragments().stream())
                .anyMatch(f -> f.text().contains("APPLY CONFIG"));
        boolean hasCancel = lastPage.lines().stream()
                .flatMap(l -> l.fragments().stream())
                .anyMatch(f -> f.text().contains("CANCEL SETUP"));
        assertTrue(hasApply, "Last page must contain APPLY CONFIG button");
        assertTrue(hasCancel, "Last page must contain CANCEL SETUP button");
    }
}
