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
            assertEquals(SetupCmd.CMD_PERMISSION,
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

    @Test
    @DisplayName("SetupConfirmCmd writes full per-world region files seeded with comments from default template")
    void testSetupConfirmWritesFullRegionsWithComments(@org.junit.jupiter.api.io.TempDir java.io.File tempDir) throws Exception {
        // Setup mock server accessor with temporary directory and worlds
        io.github.dailystruggle.rtp.api.server.RTPServerAccessor mockAccessor = org.mockito.Mockito.mock(io.github.dailystruggle.rtp.api.server.RTPServerAccessor.class);
        org.mockito.Mockito.when(mockAccessor.getPluginDirectory()).thenReturn(tempDir);

        io.github.dailystruggle.rtp.api.world.RTPWorld<?> overworld = org.mockito.Mockito.mock(io.github.dailystruggle.rtp.api.world.RTPWorld.class);
        org.mockito.Mockito.when(overworld.name()).thenReturn("world");
        org.mockito.Mockito.when(overworld.environment()).thenReturn("NORMAL");

        io.github.dailystruggle.rtp.api.world.RTPWorld<?> nether = org.mockito.Mockito.mock(io.github.dailystruggle.rtp.api.world.RTPWorld.class);
        org.mockito.Mockito.when(nether.name()).thenReturn("world_nether");
        org.mockito.Mockito.when(nether.environment()).thenReturn("NETHER");
        org.mockito.Mockito.when(nether.getMaxHeight()).thenReturn(128);
        org.mockito.Mockito.when(nether.getMinHeight()).thenReturn(0);

        io.github.dailystruggle.rtp.api.world.RTPWorld<?> end = org.mockito.Mockito.mock(io.github.dailystruggle.rtp.api.world.RTPWorld.class);
        org.mockito.Mockito.when(end.name()).thenReturn("world_the_end");
        org.mockito.Mockito.when(end.environment()).thenReturn("THE_END");
        org.mockito.Mockito.when(end.getMaxHeight()).thenReturn(256);
        org.mockito.Mockito.when(end.getMinHeight()).thenReturn(0);

        org.mockito.Mockito.when(mockAccessor.getRTPWorlds()).thenReturn(java.util.List.of(overworld, nether, end));
        org.mockito.Mockito.when(mockAccessor.getRTPWorld("world")).thenReturn((io.github.dailystruggle.rtp.api.world.RTPWorld) overworld);
        org.mockito.Mockito.when(mockAccessor.getRTPWorld("world_nether")).thenReturn((io.github.dailystruggle.rtp.api.world.RTPWorld) nether);
        org.mockito.Mockito.when(mockAccessor.getRTPWorld("world_the_end")).thenReturn((io.github.dailystruggle.rtp.api.world.RTPWorld) end);

        io.github.dailystruggle.rtp.common.RTP.serverAccessor = mockAccessor;

        // Create default.yml with comments in definitions/regions/
        java.io.File defRegionDir = new java.io.File(tempDir, "definitions/regions");
        defRegionDir.mkdirs();
        java.io.File defFile = new java.io.File(defRegionDir, "default.yml");
        String defaultContent = "# --- RTP Default Region ---\n"
                + "world: \"[0]\"\n"
                + "# Shape comment\n"
                + "shape: \"@config\"\n"
                + "# Vert comment\n"
                + "vert: \"@config\"\n"
                + "price: 0.0\n";
        java.nio.file.Files.writeString(defFile.toPath(), defaultContent, java.nio.charset.StandardCharsets.UTF_8);

        SetupSessionRegistry registry = new SetupSessionRegistry();
        UUID callerId = UUID.randomUUID();
        SetupSession session = registry.getOrCreate(callerId);
        session.setWorldChoice("multi");
        session.setGameplayChoice("survival");
        session.setPerformanceChoice("high");

        SetupConfirmCmd confirmCmd = new SetupConfirmCmd(null, registry);
        boolean confirmed = confirmCmd.onCommand(callerId, Map.of(), null);
        assertTrue(confirmed, "Confirm command should succeed");

        // Verify definitions/regions/world_the_end.yml exists and has full content
        java.io.File endFile = new java.io.File(defRegionDir, "world_the_end.yml");
        assertTrue(endFile.exists(), "world_the_end.yml must be written to disk");
        String endContent = java.nio.file.Files.readString(endFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(endContent.contains("world: world_the_end") || endContent.contains("world: \"world_the_end\"") || endContent.contains("world: 'world_the_end'"),
                "world_the_end.yml must contain world key, was: " + endContent);
        assertTrue(endContent.contains("shape: '@config'") || endContent.contains("shape: \"@config\"") || endContent.contains("shape: @config"),
                "world_the_end.yml must contain shape key, was: " + endContent);
        assertTrue(endContent.contains("vert:"), "world_the_end.yml must contain vert key, was: " + endContent);
        assertTrue(endContent.contains("requireSkyLight: false"), "world_the_end.yml vert must have requireSkyLight: false");

        // Verify comments from default template are preserved
        assertTrue(endContent.contains("# --- RTP Default Region ---") || endContent.contains("# Shape comment"),
                "world_the_end.yml should carry comments from default template");

        // Verify definitions/regions/world_nether.yml exists and has full content
        java.io.File netherFile = new java.io.File(defRegionDir, "world_nether.yml");
        assertTrue(netherFile.exists(), "world_nether.yml must be written to disk");
        String netherContent = java.nio.file.Files.readString(netherFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(netherContent.contains("world: world_nether") || netherContent.contains("world: \"world_nether\"") || netherContent.contains("world: 'world_nether'"),
                "world_nether.yml must contain world key, was: " + netherContent);
        assertTrue(netherContent.contains("shape: '@config'") || netherContent.contains("shape: \"@config\"") || netherContent.contains("shape: @config"),
                "world_nether.yml must contain shape key, was: " + netherContent);
        assertTrue(netherContent.contains("vert:"), "world_nether.yml must contain vert key");
        assertTrue(netherContent.contains("requireSkyLight: false"), "world_nether.yml vert must have requireSkyLight: false");
    }

    @Test
    @DisplayName("SetupBookMenuBuilder builds menu pages across all wizard stages")
    void testSetupBookMenuBuilderAllStages() {
        SetupBookMenuBuilder builder = new SetupBookMenuBuilder();
        SetupSession session = new SetupSession(UUID.randomUUID());

        // 1. World page - single vs multi
        session.setCurrentStage(SetupStage.WORLD);
        session.setWorldChoice("single");
        var m1 = builder.build(session, Map.of());
        assertNotNull(m1);
        assertEquals("RTP Setup: World Topology", m1.title());

        session.setWorldChoice("multi");
        var m1Multi = builder.build(session, Map.of());
        assertNotNull(m1Multi);

        // 2. Gameplay page - survival, arena, skyblock, oneblock
        session.setCurrentStage(SetupStage.GAMEPLAY);
        for (String style : List.of("survival", "arena", "skyblock", "oneblock")) {
            session.setGameplayChoice(style);
            var m = builder.build(session, Map.of());
            assertNotNull(m);
            assertEquals("RTP Setup: Gameplay Style", m.title());
        }

        // 3. Performance page - high, low, folia
        session.setCurrentStage(SetupStage.PERFORMANCE);
        for (String perf : List.of("high", "low", "folia")) {
            session.setPerformanceChoice(perf);
            var m = builder.build(session, Map.of());
            assertNotNull(m);
            assertEquals("RTP Setup: Performance", m.title());
        }

        // 4. Addons page - toggles
        session.setCurrentStage(SetupStage.ADDONS);
        session.setToggle("claimIntegrations", true);
        session.setToggle("cinematicEffects", true);
        var mAddons = builder.build(session, Map.of());
        assertNotNull(mAddons);
        assertEquals("RTP Setup: Addons & Effects", mAddons.title());

        session.setToggle("claimIntegrations", false);
        session.setToggle("cinematicEffects", false);
        var mAddons2 = builder.build(session, Map.of());
        assertNotNull(mAddons2);
    }

    @Test
    @DisplayName("SetupStatusCmd outputs formatted status")
    void testSetupStatusCmd() {
        SetupSessionRegistry registry = new SetupSessionRegistry();
        SetupStatusCmd statusCmd = new SetupStatusCmd(null, registry);
        assertEquals("status", statusCmd.name());
        assertEquals("rtp.admin.setup", statusCmd.permission());
        assertNotNull(statusCmd.description());

        UUID callerId = UUID.randomUUID();
        SetupSession session = registry.getOrCreate(callerId);
        session.setWorldChoice("single");
        session.setGameplayChoice("survival");
        session.setPerformanceChoice("high");

        assertTrue(statusCmd.onCommand(callerId, Map.of(), null));
    }
}
