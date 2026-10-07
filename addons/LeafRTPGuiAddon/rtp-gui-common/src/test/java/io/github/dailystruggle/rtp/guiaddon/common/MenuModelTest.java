package io.github.dailystruggle.rtp.guiaddon.common;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.RtpTargetStatus;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class MenuModelTest {

    @Test
    void displayName_worldTarget_formatsWorldName() {
        RtpTarget target = RtpTarget.world("world_nether");
        assertEquals("World: world_nether", MenuModel.displayName(target, null));
    }

    @Test
    void displayName_biomeTarget_formatsBiomeName() {
        RtpTarget target = RtpTarget.biome("plains");
        assertEquals("Biome: plains", MenuModel.displayName(target, null));
    }

    @Test
    void displayName_networkAndRegionTargets_unifiedAsRegion() {
        RtpTarget local = RtpTarget.region("wild");
        assertEquals("Region: wild", MenuModel.displayName(local, null));

        RtpTarget net = RtpTarget.network("survival-1", "wild");
        assertEquals("Region: wild", MenuModel.displayName(net, null));
    }

    @Test
    void displayName_customLabelOverridesDefault() {
        RtpTarget target = RtpTarget.biome("desert");
        RtpTargetStatus status = new RtpTargetStatus(
                RtpTargetStatus.Availability.READY, 0L, 0.0, null, "NORMAL", "&6The Hot Desert");
        assertEquals("&6The Hot Desert", MenuModel.displayName(target, status));
    }

    @Test
    void defaultBiomeIcon_mapsCommonBiomes() {
        assertEquals("SAND", GuiMenuConfig.defaultBiomeIcon("desert"));
        assertEquals("SAND", GuiMenuConfig.defaultBiomeIcon("badlands"));
        assertEquals("SNOW_BLOCK", GuiMenuConfig.defaultBiomeIcon("snowy_plains"));
        assertEquals("NETHERRACK", GuiMenuConfig.defaultBiomeIcon("nether_wastes"));
        assertEquals("END_STONE", GuiMenuConfig.defaultBiomeIcon("the_end"));
        assertEquals("JUNGLE_SAPLING", GuiMenuConfig.defaultBiomeIcon("jungle"));
        assertEquals("SPRUCE_SAPLING", GuiMenuConfig.defaultBiomeIcon("taiga"));
        assertEquals("OAK_SAPLING", GuiMenuConfig.defaultBiomeIcon("plains"));
    }

    @Test
    void iconName_worldAndBiomeDefaults() {
        GuiMenuConfig config = new GuiMenuConfig();
        RtpTarget worldNether = RtpTarget.world("world_nether");
        assertEquals("NETHERRACK", config.iconName(worldNether, (RtpTargetStatus) null));

        RtpTarget worldEnd = RtpTarget.world("world_the_end");
        assertEquals("END_STONE", config.iconName(worldEnd, (RtpTargetStatus) null));

        RtpTarget worldOverworld = RtpTarget.world("world");
        assertEquals("GRASS_BLOCK", config.iconName(worldOverworld, (RtpTargetStatus) null));

        RtpTarget biomeDesert = RtpTarget.biome("desert");
        assertEquals("SAND", config.iconName(biomeDesert, (RtpTargetStatus) null));
    }

    @Test
    void actionsMenu_buildsCorrectEntriesAndIcons() {
        GuiMenuConfig config = new GuiMenuConfig();
        assertEquals("&6&lSpecial Teleports", config.titleActionsMenu());
        assertEquals("NETHERITE_SWORD", config.iconActionsSelector());
        assertEquals("DIAMOND_SWORD", config.iconActionDefault());

        // When actionService is null (optional addon absent), building actions menu produces empty entries cleanly
        java.util.UUID testPlayer = java.util.UUID.randomUUID();
        MenuModel model = MenuModel.buildActionsMenu(testPlayer, config, 0);
        assertNotNull(model);
        assertTrue(model.entries().isEmpty() || model.entries().stream().anyMatch(e -> e.displayName().contains("Back")));
    }

    @Test
    void displayName_defaultFallbackIgnoredForDefaultTarget() {
        RtpTarget target = RtpTarget.defaultRegion();
        RtpTargetStatus statusWithDefault = new RtpTargetStatus(
                RtpTargetStatus.Availability.READY, 0L, 0.0, null, "NORMAL", "default");
        assertEquals("Random teleport", MenuModel.displayName(target, statusWithDefault));

        RtpTargetStatus statusWithCustom = new RtpTargetStatus(
                RtpTargetStatus.Availability.READY, 0L, 0.0, null, "NORMAL", "&aWild Overworld");
        assertEquals("&aWild Overworld", MenuModel.displayName(target, statusWithCustom));
    }

    @Test
    void operatorMenu_configDefaults() {
        GuiMenuConfig config = new GuiMenuConfig();
        assertTrue(config.showOperatorTools());
        assertEquals("rtp.menu.admin", config.permissionOperatorTools());
        assertEquals("&6&lOperator Control Hub", config.titleOperatorMenu());
        assertEquals("&6&lOperator Tools...", config.titleOperatorSelector());
        assertEquals("COMMAND_BLOCK", config.iconOperatorSelector());
        assertEquals("NETHER_STAR", config.iconOperatorSetup());
        assertEquals("HOPPER", config.iconOperatorImport());
        assertEquals("REPEATER", config.iconOperatorConfig());
        assertEquals("FILLED_MAP", config.iconOperatorVisualizations());
        assertEquals("CLOCK", config.iconOperatorStatus());
        assertEquals("WRITABLE_BOOK", config.iconOperatorAdminBook());
        assertEquals("REDSTONE_TORCH", config.iconOperatorReload());
        assertEquals("&a&lSetup Wizard", config.titleOperatorSetup());
        assertEquals("&e&lImport Configs", config.titleOperatorImport());
        assertEquals("&b&lConfig Editor", config.titleOperatorConfig());
        assertEquals("&d&lVisualizations", config.titleOperatorVisualizations());
        assertEquals("&f&lStatus & Metrics", config.titleOperatorStatus());
        assertEquals("&6&lAdmin Book Panel", config.titleOperatorAdminBook());
        assertEquals("&c&lQuick Reload", config.titleOperatorReload());
        assertEquals("&e[Previous Page]", config.textPreviousPage());
        assertEquals("&e[Next Page]", config.textNextPage());
        assertEquals("&c[Back to Worlds]", config.textBackToMainMenu());
    }

    @Test
    void operatorMenu_buildOperatorMenu_containsNavigationButtonWhenNoPerms() {
        GuiMenuConfig config = new GuiMenuConfig();
        UUID testPlayer = UUID.randomUUID();
        MenuModel model = MenuModel.buildOperatorMenu(testPlayer, config);
        assertNotNull(model);
        assertEquals(config.titleOperatorMenu(), model.title());
        // With no serverAccessor/permissions, only the Back to Worlds button is added
        assertEquals(1, model.entries().size());
        MenuEntry backEntry = model.entries().get(0);
        assertEquals("menu:main", backEntry.target().name());
    }

    @Test
    void operatorMenu_buildOperatorMenu_withPermittedPlayer() {
        GuiMenuConfig config = new GuiMenuConfig();
        UUID testPlayer = UUID.randomUUID();

        RTPServerAccessor originalAccessor = RTPAPI.serverAccessor;
        try {
            RTPCommandSender sender = (RTPCommandSender) java.lang.reflect.Proxy.newProxyInstance(
                    RTPCommandSender.class.getClassLoader(),
                    new Class<?>[]{RTPCommandSender.class},
                    (proxy, method, args) -> {
                        if ("hasPermission".equals(method.getName())) {
                            return true;
                        }
                        return null;
                    });

            RTPServerAccessor mockAccessor = (RTPServerAccessor) java.lang.reflect.Proxy.newProxyInstance(
                    RTPServerAccessor.class.getClassLoader(),
                    new Class<?>[]{RTPServerAccessor.class},
                    (proxy, method, args) -> {
                        if ("getSender".equals(method.getName())) {
                            return sender;
                        }
                        return null;
                    });

            RTPAPI.serverAccessor = mockAccessor;

            MenuModel model = MenuModel.buildOperatorMenu(testPlayer, config);
            assertNotNull(model);
            assertEquals(8, model.entries().size());
            assertTrue(model.entries().stream().anyMatch(e -> "action:operator:setup".equals(e.target().name())));
            assertTrue(model.entries().stream().anyMatch(e -> "action:operator:import".equals(e.target().name())));
            assertTrue(model.entries().stream().anyMatch(e -> "action:operator:config".equals(e.target().name())));
            assertTrue(model.entries().stream().anyMatch(e -> "action:operator:visualizations".equals(e.target().name())));
            assertTrue(model.entries().stream().anyMatch(e -> "action:operator:status".equals(e.target().name())));
            assertTrue(model.entries().stream().anyMatch(e -> "action:operator:adminbook".equals(e.target().name())));
            assertTrue(model.entries().stream().anyMatch(e -> "action:operator:reload".equals(e.target().name())));
            assertTrue(model.entries().stream().anyMatch(e -> "menu:main".equals(e.target().name())));
        } finally {
            RTPAPI.serverAccessor = originalAccessor;
        }
    }

    @Test
    void canUseAction_enforcesActionPermissionOrWildcard() {
        UUID testPlayer = UUID.randomUUID();
        java.util.Set<String> granted = new java.util.HashSet<>();
        var open = new io.github.dailystruggle.rtp.api.action.ActionDefinition(
                "open", null, null, null, null, null, null);
        var gated = new io.github.dailystruggle.rtp.api.action.ActionDefinition(
                "gated", null, "rtp.action.gated", null, null, null, null);
        assertTrue(open.isGuiEligible(), "Default action must be GUI-eligible for this test");

        RTPServerAccessor originalAccessor = RTPAPI.serverAccessor;
        try {
            RTPCommandSender sender = (RTPCommandSender) java.lang.reflect.Proxy.newProxyInstance(
                    RTPCommandSender.class.getClassLoader(),
                    new Class<?>[]{RTPCommandSender.class},
                    (proxy, method, args) -> "hasPermission".equals(method.getName())
                            ? granted.contains(String.valueOf(args[0])) : null);
            RTPAPI.serverAccessor = (RTPServerAccessor) java.lang.reflect.Proxy.newProxyInstance(
                    RTPServerAccessor.class.getClassLoader(),
                    new Class<?>[]{RTPServerAccessor.class},
                    (proxy, method, args) -> "getSender".equals(method.getName()) ? sender : null);

            assertFalse(MenuModel.canUseAction(testPlayer, null), "Missing definition is never usable");
            assertFalse(MenuModel.canUseAction(null, open));
            assertTrue(MenuModel.canUseAction(testPlayer, open), "Unpermissioned action is open to all");
            assertFalse(MenuModel.canUseAction(testPlayer, gated), "Revoked permission must deny");

            granted.add("rtp.action.gated");
            assertTrue(MenuModel.canUseAction(testPlayer, gated));

            granted.clear();
            granted.add("rtp.action.*");
            assertTrue(MenuModel.canUseAction(testPlayer, gated), "Wildcard grants every action");
        } finally {
            RTPAPI.serverAccessor = originalAccessor;
        }
    }

    @Test
    void entryLore_operatorEntries_haveSpecializedLore() {
        MenuEntry setupEntry = new MenuEntry(
                RtpTarget.action("action:operator:setup"),
                RtpTargetStatus.Availability.READY,
                "Setup Wizard",
                "NETHER_STAR",
                0L,
                0.0);
        java.util.List<String> setupLore = MenuIcons.entryLore(setupEntry);
        assertTrue(setupLore.stream().anyMatch(line -> line.contains("wizard")));

        MenuEntry opMenuEntry = new MenuEntry(
                RtpTarget.action("menu:operator"),
                RtpTargetStatus.Availability.READY,
                "Operator Tools",
                "COMMAND_BLOCK",
                0L,
                0.0);
        java.util.List<String> opLore = MenuIcons.entryLore(opMenuEntry);
        assertTrue(opLore.stream().anyMatch(line -> line.contains("Operator management")));
    }

    @Test
    void menuActions_submitOperatorMenu_opensWhenPermitted() {
        GuiMenuConfig config = new GuiMenuConfig();
        UUID testPlayer = UUID.randomUUID();

        AtomicReference<MenuModel> openedModel = new AtomicReference<>();
        MenuRenderer testRenderer = new MenuRenderer() {
            @Override
            public String key() {
                return "test_renderer";
            }

            @Override
            public void open(UUID playerId, MenuModel model) {
                openedModel.set(model);
            }
        };

        GuiRenderers.register(testRenderer);
        RTPServerAccessor originalAccessor = RTPAPI.serverAccessor;
        try {
            RTPCommandSender sender = (RTPCommandSender) java.lang.reflect.Proxy.newProxyInstance(
                    RTPCommandSender.class.getClassLoader(),
                    new Class<?>[]{RTPCommandSender.class},
                    (proxy, method, args) -> {
                        if ("hasPermission".equals(method.getName())) {
                            return true;
                        }
                        return null;
                    });

            RTPServerAccessor mockAccessor = (RTPServerAccessor) java.lang.reflect.Proxy.newProxyInstance(
                    RTPServerAccessor.class.getClassLoader(),
                    new Class<?>[]{RTPServerAccessor.class},
                    (proxy, method, args) -> {
                        if ("getSender".equals(method.getName())) {
                            return sender;
                        }
                        return null;
                    });

            RTPAPI.serverAccessor = mockAccessor;

            MenuActions.submit(testPlayer, RtpTarget.action("menu:operator"));
            assertNotNull(openedModel.get());
            assertEquals(config.titleOperatorMenu(), openedModel.get().title());
        } finally {
            GuiRenderers.unregister("test_renderer");
            RTPAPI.serverAccessor = originalAccessor;
        }
    }

    @Test
    void menuActions_isMenuNavigation_identifiesNavigationActions() {
        assertTrue(MenuActions.isMenuNavigation(RtpTarget.action("menu:main")));
        assertTrue(MenuActions.isMenuNavigation(RtpTarget.action("menu:biomes:0")));
        assertTrue(MenuActions.isMenuNavigation(RtpTarget.action("menu:biomes:1")));
        assertTrue(MenuActions.isMenuNavigation(RtpTarget.action("menu:actions:0")));
        assertTrue(MenuActions.isMenuNavigation(RtpTarget.action("menu:operator")));
        assertTrue(MenuActions.isMenuNavigation(RtpTarget.action("action:operator:reload")));

        assertFalse(MenuActions.isMenuNavigation(RtpTarget.action("action:operator:setup")));
        assertFalse(MenuActions.isMenuNavigation(RtpTarget.action("action:operator:import")));
        assertFalse(MenuActions.isMenuNavigation(RtpTarget.action("action:trigger:custom_action")));
        assertFalse(MenuActions.isMenuNavigation(RtpTarget.biome("plains")));
        assertFalse(MenuActions.isMenuNavigation(RtpTarget.region("default")));
        assertFalse(MenuActions.isMenuNavigation(RtpTarget.defaultRegion()));
        assertFalse(MenuActions.isMenuNavigation(null));
    }

    @Test
    void build_paginatesDestinationsAndReservesSubmenuRow() {
        GuiMenuConfig config = new GuiMenuConfig();
        UUID playerId = UUID.randomUUID();

        java.util.function.Function<UUID, java.util.List<RtpTarget>> origAllowed = RTPAPI.allowedTargetsDelegate;
        java.util.function.BiFunction<UUID, RtpTarget, RtpTargetStatus> origStatus = RTPAPI.targetStatusDelegate;
        try {
            java.util.List<RtpTarget> targets = new java.util.ArrayList<>();
            for (int i = 0; i < 30; i++) {
                targets.add(RtpTarget.region("region_" + i));
            }

            RTPAPI.allowedTargetsDelegate = uuid -> targets;
            RTPAPI.targetStatusDelegate = (uuid, t) ->
                    new RtpTargetStatus(RtpTargetStatus.Availability.READY, 0L, 0.0);

            MenuModel page0 = MenuModel.build(playerId, config, 0);
            assertTrue(page0.isRoot(), "Page 0 of main menu must be root");
            assertTrue(page0.title().contains("(1/2)"), "Title must indicate pagination: " + page0.title());
            long destCountPage0 = page0.entries().stream().filter(e -> e.target().kind() != RtpTarget.Kind.ACTION).count();
            assertEquals(21, destCountPage0);
            assertTrue(page0.entries().stream().anyMatch(e -> "menu:main:1".equals(e.target().name())));

            MenuModel page1 = MenuModel.build(playerId, config, 1);
            assertFalse(page1.isRoot(), "Page 1 of main menu must not be root");
            assertTrue(page1.title().contains("(2/2)"), "Title must indicate page 2: " + page1.title());
            long destCountPage1 = page1.entries().stream().filter(e -> e.target().kind() != RtpTarget.Kind.ACTION).count();
            assertEquals(9, destCountPage1);
            assertTrue(page1.entries().stream().anyMatch(e -> "menu:main:0".equals(e.target().name())));
        } finally {
            RTPAPI.allowedTargetsDelegate = origAllowed;
            RTPAPI.targetStatusDelegate = origStatus;
        }
    }
}
