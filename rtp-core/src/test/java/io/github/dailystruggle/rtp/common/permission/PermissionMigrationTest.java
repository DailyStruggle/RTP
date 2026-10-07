package io.github.dailystruggle.rtp.common.permission;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.CoreCommandTreeBuilder;
import io.github.dailystruggle.rtp.common.commands.config.ConfigImportPermissionsCmd;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PermissionMigrationTest - Phase 5 Parity & Bridging")
public class PermissionMigrationTest {

    private Path tempDir;
    private PermissionMigrationService service;
    private io.github.dailystruggle.rtp.common.configuration.Configs savedConfigs;
    private RTPServerAccessor savedAccessor;

    @BeforeEach
    void setUp() throws IOException {
        // loadTemplatesFromConfig prefers RTP.configs.pluginDirectory over the accessor;
        // a leftover static Configs from another class would mask the per-test integrations.yml.
        savedConfigs = RTP.configs;
        savedAccessor = RTP.serverAccessor;
        RTP.configs = null;
        RTP.serverAccessor = null;
        tempDir = Files.createTempDirectory("rtp-perm-test");
        service = new PermissionMigrationService();
    }

    @AfterEach
    void tearDown() {
        RTP.configs = savedConfigs;
        RTP.serverAccessor = savedAccessor;
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("5.1 & 5.2 - Configurable command templates loaded and substituted properly")
    void testCommandTemplatesSubstitution() {
        service.setGroupSetTemplate("pex group [group] add [permission] [contexts]");
        service.setUserSetTemplate("pex user [user] add [permission] [contexts]");

        String groupCmd = service.formatGroupSet("vip", "rtp.use", true, null);
        assertEquals("pex group vip add rtp.use", groupCmd);

        String userCmd = service.formatUserSet("Steve", "rtp.world", true, "server=survival");
        assertEquals("pex user Steve add rtp.world server=survival", userCmd);

        // Default LuckPerms templates
        service.setGroupSetTemplate(PermissionMigrationService.DEFAULT_GROUP_SET_TEMPLATE);
        service.setUserSetTemplate(PermissionMigrationService.DEFAULT_USER_SET_TEMPLATE);

        assertEquals("lp group default permission set rtp.use true",
                service.formatGroupSet("default", "rtp.use", true, null));
        assertEquals("lp user Alex permission set rtp.use true world=world_nether",
                service.formatUserSet("Alex", "rtp.use", true, "world=world_nether"));
    }

    @Test
    @DisplayName("5.1 - Load templates from integrations.yml")
    void testLoadTemplatesFromYaml() throws IOException {
        Path integrations = tempDir.resolve("integrations.yml");
        String yamlContent = """
                permissions:
                  command_templates:
                    group_set: "custom perm group [group] set [permission] [value] [contexts]"
                    user_set: "custom perm user [user] set [permission] [value] [contexts]"
                """;
        Files.writeString(integrations, yamlContent);

        // Mock RTP serverAccessor directory
        RTPServerAccessor mockAccessor = new MockRTPServerAccessor(tempDir.toFile());
        RTP.serverAccessor = mockAccessor;

        service.loadTemplatesFromConfig();

        assertEquals("custom perm group [group] set [permission] [value] [contexts]", service.getGroupSetTemplate());
        assertEquals("custom perm user [user] set [permission] [value] [contexts]", service.getUserSetTemplate());
    }

    @Test
    @DisplayName("5.2 - BetterRTP equivalence mapping table")
    void testBetterRtpEquivalenceMapping() {
        assertEquals(List.of("rtp.*"), service.mapPermission("betterrtp.*"));
        assertEquals(List.of("rtp.use"), service.mapPermission("betterrtp.use"));
        assertEquals(List.of("rtp.world"), service.mapPermission("betterrtp.world"));
        assertEquals(List.of("rtp.worlds.world_nether"), service.mapPermission("betterrtp.world.world_nether"));
        assertEquals(List.of("rtp.worlds.*"), service.mapPermission("betterrtp.world.*"));
        assertEquals(List.of("rtp.noCooldown"), service.mapPermission("betterrtp.bypass.cooldown"));
        assertEquals(List.of("rtp.noDelay"), service.mapPermission("betterrtp.bypass.delay"));
        assertEquals(List.of("rtp.free"), service.mapPermission("betterrtp.bypass.economy"));
        assertEquals(List.of("rtp.free"), service.mapPermission("betterrtp.bypass.hunger"));
        assertEquals(List.of("rtp.other"), service.mapPermission("betterrtp.player"));
        assertEquals(List.of("rtp.biome.*"), service.mapPermission("betterrtp.biome"));
        assertEquals(List.of("rtp.biome.plains"), service.mapPermission("betterrtp.biome.plains"));
        assertEquals(List.of("rtp.reload"), service.mapPermission("betterrtp.reload"));
        assertEquals(List.of("rtp.admin"), service.mapPermission("betterrtp.admin"));
    }

    @Test
    @DisplayName("5.2 - JustRTP equivalence mapping table")
    void testJustRtpEquivalenceMapping() {
        assertEquals(List.of("rtp.*"), service.mapPermission("justrtp.*"));
        assertEquals(List.of("rtp.use"), service.mapPermission("justrtp.use"));
        assertEquals(List.of("rtp.use"), service.mapPermission("justrtp.rtp"));
        assertEquals(List.of("rtp.world"), service.mapPermission("justrtp.world"));
        assertEquals(List.of("rtp.worlds.custom_world"), service.mapPermission("justrtp.world.custom_world"));
        assertEquals(List.of("rtp.worlds.*"), service.mapPermission("justrtp.world.*"));
        assertEquals(List.of("rtp.biome"), service.mapPermission("justrtp.biome"));
        assertEquals(List.of("rtp.biome.desert"), service.mapPermission("justrtp.biome.desert"));
        assertEquals(List.of("rtp.biome.*"), service.mapPermission("justrtp.biome.*"));
        assertEquals(List.of("rtp.noCooldown"), service.mapPermission("justrtp.bypass.cooldown"));
        assertEquals(List.of("rtp.noCooldown"), service.mapPermission("justrtp.nocooldown"));
        assertEquals(List.of("rtp.noDelay"), service.mapPermission("justrtp.bypass.delay"));
        assertEquals(List.of("rtp.noDelay"), service.mapPermission("justrtp.nodelay"));
        assertEquals(List.of("rtp.free"), service.mapPermission("justrtp.bypass.cost"));
        assertEquals(List.of("rtp.free"), service.mapPermission("justrtp.free"));
        assertEquals(List.of("rtp.other"), service.mapPermission("justrtp.other"));
        assertEquals(List.of("rtp.admin"), service.mapPermission("justrtp.admin"));
        assertEquals(List.of("rtp.reload"), service.mapPermission("justrtp.reload"));
    }

    @Test
    @DisplayName("5.2 - EzRTP equivalence mapping table")
    void testEzRtpEquivalenceMapping() {
        assertEquals(List.of("rtp.*"), service.mapPermission("ezrtp.*"));
        assertEquals(List.of("rtp.use"), service.mapPermission("ezrtp.use"));
        assertEquals(List.of("rtp.use"), service.mapPermission("ezrtp.rtp"));
        assertEquals(List.of("rtp.world"), service.mapPermission("ezrtp.world"));
        assertEquals(List.of("rtp.worlds.survival"), service.mapPermission("ezrtp.world.survival"));
        assertEquals(List.of("rtp.worlds.*"), service.mapPermission("ezrtp.world.*"));
        assertEquals(List.of("rtp.noCooldown"), service.mapPermission("ezrtp.bypass.cooldown"));
        assertEquals(List.of("rtp.noCooldown"), service.mapPermission("ezrtp.cooldown.bypass"));
        assertEquals(List.of("rtp.noDelay"), service.mapPermission("ezrtp.bypass.delay"));
        assertEquals(List.of("rtp.noDelay"), service.mapPermission("ezrtp.delay.bypass"));
        assertEquals(List.of("rtp.free"), service.mapPermission("ezrtp.bypass.cost"));
        assertEquals(List.of("rtp.free"), service.mapPermission("ezrtp.cost.bypass"));
        assertEquals(List.of("rtp.other"), service.mapPermission("ezrtp.other"));
        assertEquals(List.of("rtp.admin"), service.mapPermission("ezrtp.admin"));
        assertEquals(List.of("rtp.reload"), service.mapPermission("ezrtp.reload"));
    }

    @Test
    @DisplayName("5.2 - JakesRTP equivalence mapping table")
    void testJakesRtpEquivalenceMapping() {
        assertEquals(List.of("rtp.*"), service.mapPermission("jakesrtp.*"));
        assertEquals(List.of("rtp.use"), service.mapPermission("jakesrtp.use"));
        assertEquals(List.of("rtp.use"), service.mapPermission("jakesrtp.usebyname"));
        assertEquals(List.of("rtp.noCooldown"), service.mapPermission("jakesrtp.nocooldown"));
        assertEquals(List.of("rtp.noDelay"), service.mapPermission("jakesrtp.nowarmup"));
        assertEquals(List.of("rtp.other"), service.mapPermission("jakesrtp.others"));
        assertEquals(List.of("rtp.other"), service.mapPermission("jakesrtp.forcertp"));
        assertEquals(List.of("rtp.onEvent.respawn"), service.mapPermission("jakesrtp.rtpondeath"));
        assertEquals(List.of("rtp.admin"), service.mapPermission("jakesrtp.admin"));
        assertEquals(List.of("rtp.admin"), service.mapPermission("jakesrtp.permpack.admin"));
        assertEquals(List.of("rtp.reload"), service.mapPermission("jakesrtp.reload"));
        assertEquals(List.of("rtp.regions.default-settings"), service.mapPermission("jakesrtp.use.default-settings"));
        assertEquals(List.of("rtp.noCooldown"), service.mapPermission("jakesrtp.nocooldown.default-settings"));
        assertEquals(List.of("rtp.noDelay"), service.mapPermission("jakesrtp.nowarmup.default-settings"));
    }

    @Test
    @DisplayName("5.2 - Non-destructive append-only logic never unsets competitor nodes and preserves contexts")
    void testNonDestructiveAppendOnly() {
        List<PermissionMigrationService.ParsedNode> parsedNodes = List.of(
                new PermissionMigrationService.ParsedNode("betterrtp.use", true, ""),
                new PermissionMigrationService.ParsedNode("betterrtp.bypass.cooldown", true, "server=survival"),
                new PermissionMigrationService.ParsedNode("justrtp.world.nether", true, "world=world_nether server=survival")
        );

        // No source= named: the generic mapper only runs for prefixes matching the derived sources.
        PermissionMigrationService.MigrationPlan plan = service.planMigration(
                "group", "members", parsedNodes, null, Set.of("betterrtp", "justrtp"), false);

        assertFalse(plan.isApplied());
        assertEquals(3, plan.getMappedEntries().size());

        // Verify none of the generated commands unset or revoke competitor permissions
        for (String cmd : plan.getGeneratedCommands()) {
            assertFalse(cmd.contains("unset"), "Append-only migration must never unset nodes: " + cmd);
            assertTrue(cmd.contains("set"), "Migration commands should be set commands: " + cmd);
            assertTrue(cmd.startsWith("lp group members permission set rtp."), "Commands should target rtp node: " + cmd);
        }

        // Verify context preservation in generated commands
        assertTrue(plan.getGeneratedCommands().stream().anyMatch(c -> c.contains("rtp.noCooldown true server=survival")));
        assertTrue(plan.getGeneratedCommands().stream().anyMatch(c -> c.contains("rtp.worlds.nether true world=world_nether server=survival")));

        // Verify mapped nodes
        List<String> mappedTargets = plan.getMappedEntries().stream()
                .map(PermissionMigrationService.PermissionEntry::getTargetPermission)
                .toList();
        assertTrue(mappedTargets.contains("rtp.use"));
        assertTrue(mappedTargets.contains("rtp.noCooldown"));
        assertTrue(mappedTargets.contains("rtp.worlds.nether"));
    }

    @Test
    @DisplayName("5.1b - Generic dynamic permission migration")
    void testSchemaDynamicPermissionMigration() {
        // Test AsyncRTP style permission mappings
        List<String> asyncUse = service.mapPermission("asyncrtp.use");
        assertTrue(asyncUse.contains("rtp.use"));

        List<String> asyncWorld = service.mapPermission("asyncrtp.world.custom_world");
        assertTrue(asyncWorld.contains("rtp.worlds.custom_world"));

        List<String> asyncCooldown = service.mapPermission("asyncrtp.bypass.cooldown");
        assertTrue(asyncCooldown.contains("rtp.noCooldown"));

        // Test AdvancedRTP style permission mappings
        List<String> advRtp = service.mapPermission("advancedrtp.rtp");
        assertTrue(advRtp.contains("rtp.use"));

        List<String> advWorld = service.mapPermission("advancedrtp.world.mining");
        assertTrue(advWorld.contains("rtp.worlds.mining"));

        List<String> advDelay = service.mapPermission("advancedrtp.bypass.delay");
        assertTrue(advDelay.contains("rtp.noDelay"));

        // Additional generic permission pattern branches
        assertTrue(service.mapPermission("customrtp.teleport").contains("rtp.use"));
        assertTrue(service.mapPermission("customrtp.world.*").contains("rtp.worlds.*"));
        assertTrue(service.mapPermission("customrtp.worlds.survival").contains("rtp.worlds.survival"));
        assertTrue(service.mapPermission("customrtp.gui.world.hub").contains("rtp.worlds.hub"));
        assertTrue(service.mapPermission("customrtp.gui.paid.vip_world").contains("rtp.worlds.vip_world"));
        assertTrue(service.mapPermission("customrtp.biome.*").contains("rtp.biome.*"));
        assertTrue(service.mapPermission("customrtp.biome.plains").contains("rtp.biome.plains"));
        assertTrue(service.mapPermission("customrtp.cooldown.bypass").contains("rtp.noCooldown"));
        assertTrue(service.mapPermission("customrtp.nowarmup").contains("rtp.noDelay"));
        assertTrue(service.mapPermission("customrtp.free").contains("rtp.free"));
        assertTrue(service.mapPermission("customrtp.other").contains("rtp.other"));
        assertTrue(service.mapPermission("customrtp.use.custom_loc").contains("rtp.regions.custom_loc"));
        assertTrue(service.mapPermission("customrtp.profile.vip_profile").contains("rtp.regions.vip_profile"));
        assertTrue(service.mapPermission("customrtp.rtpondeath").contains("rtp.onEvent.respawn"));
        assertTrue(service.mapPermission("customrtp.reload").contains("rtp.reload"));
        assertTrue(service.mapPermission("customrtp.admin").contains("rtp.admin"));
        assertTrue(service.mapPermission("customrtp.*").contains("rtp.*"));
    }

    @Test
    @DisplayName("5.2 - String parsing group list and permission info output from provider")
    void testStringParsingProviderOutputs() {
        // Test parsing group list output
        List<String> groupLines = List.of(
                "[LP] Groups:",
                "- default",
                "- vip (weight: 10)",
                "- moderator",
                "- admin"
        );
        List<String> groups = service.parseGroupListOutput(groupLines);
        assertEquals(List.of("default", "vip", "moderator", "admin"), groups);

        // Test parsing comma-separated group list
        List<String> commaLines = List.of("Groups: default, vip, admin");
        assertEquals(List.of("default", "vip", "admin"), service.parseGroupListOutput(commaLines));

        // Test parsing permission info output with contexts
        List<String> infoLines = List.of(
                "[LP] default's Permissions:",
                "> betterrtp.use (true)",
                "> betterrtp.world.nether (true) (world=nether, server=survival)",
                "> betterrtp.bypass.cooldown (false) [server=lobby]"
        );
        List<PermissionMigrationService.ParsedNode> nodes = service.parsePermissionInfoOutput(infoLines);
        assertEquals(3, nodes.size());

        assertEquals("betterrtp.use", nodes.get(0).getPermission());
        assertTrue(nodes.get(0).getValue());
        assertEquals("", nodes.get(0).getContexts());

        assertEquals("betterrtp.world.nether", nodes.get(1).getPermission());
        assertTrue(nodes.get(1).getValue());
        assertEquals("world=nether server=survival", nodes.get(1).getContexts());

        assertEquals("betterrtp.bypass.cooldown", nodes.get(2).getPermission());
        assertFalse(nodes.get(2).getValue());
        assertEquals("server=lobby", nodes.get(2).getContexts());
    }

    @Test
    @DisplayName("5.3 - ConfigImportPermissionsCmd command execution (dry-run and apply)")
    void testConfigImportPermissionsCommand() throws IOException {
        // No source= is passed, so the BetterRTP folder beside RTP's data folder is what makes its nodes map.
        Path rtpDir = Files.createDirectories(tempDir.resolve("RTP"));
        Path betterDir = Files.createDirectories(tempDir.resolve("BetterRTP"));
        Files.writeString(betterDir.resolve("config.yml"),
                "Default:\n  MaxRadius: 1000\n  CenterX: 0\nSettings:\n  Cooldown:\n    Time: 600\n");
        List<String> dispatchedCommands = new ArrayList<>();
        MockRTPServerAccessor accessor = new MockRTPServerAccessor(rtpDir.toFile()) {
            @Override
            public boolean executeCommand(UUID senderId, String commandLine) {
                dispatchedCommands.add(commandLine);
                return true;
            }
        };
        // Register simulated responses
        accessor.registerCommandOutput("lp listgroups", List.of(
                "[LP] Showing groups:",
                "- default (weight: 0)",
                "- vip (weight: 10)"
        ));
        accessor.registerCommandOutput("lp group default permission info", List.of(
                "[LP] default's Permissions:",
                "+ betterrtp.use (true)"
        ));
        accessor.registerCommandOutput("lp group vip permission info", List.of(
                "[LP] vip's Permissions:",
                "+ betterrtp.world.nether (true) [world=world_nether]",
                "+ betterrtp.bypass.cooldown (true)"
        ));
        RTP.serverAccessor = accessor;

        ConfigImportPermissionsCmd cmd = new ConfigImportPermissionsCmd(null);

        // Dry-run execution
        Map<String, List<String>> dryRunParams = new HashMap<>();
        dryRunParams.put("apply", List.of("false"));
        boolean dryRunResult = cmd.onCommand(RTPAPI.serverId, dryRunParams, null);
        assertTrue(dryRunResult);
        assertEquals(0, dispatchedCommands.size(), "Dry-run should not dispatch set commands");

        // Apply execution
        Map<String, List<String>> applyParams = new HashMap<>();
        applyParams.put("apply", List.of("true"));
        boolean applyResult = cmd.onCommand(RTPAPI.serverId, applyParams, null);
        assertTrue(applyResult);
        assertEquals(3, dispatchedCommands.size(), "Apply should dispatch 3 permission set commands across groups");
        assertTrue(dispatchedCommands.contains("lp group default permission set rtp.use true"));
        assertTrue(dispatchedCommands.contains("lp group vip permission set rtp.worlds.nether true world=world_nether"));
        assertTrue(dispatchedCommands.contains("lp group vip permission set rtp.noCooldown true"));
    }

    @Test
    @DisplayName("5.3 - PermCmd is not registered in CoreCommandTreeBuilder")
    void testPermCmdTreeRegistration() {
        StubRoot root = new StubRoot();
        CoreCommandTreeBuilder.attachCommonSubcommands(root);
        assertNull(root.getCommandLookup().get("PERM"));
    }

    @Test
    @DisplayName("5.4 - ConfigImportCmd unifies config and permission import")
    void testUnifiedConfigImportWithPermissions() throws IOException {
        List<String> dispatchedCommands = new ArrayList<>();
        MockRTPServerAccessor accessor = new MockRTPServerAccessor(tempDir.toFile()) {
            @Override
            public boolean executeCommand(UUID senderId, String commandLine) {
                dispatchedCommands.add(commandLine);
                return true;
            }
        };
        accessor.registerCommandOutput("lp listgroups", List.of(
                "Groups: default, admin"
        ));
        accessor.registerCommandOutput("lp group default permission info", List.of(
                "+ betterrtp.use (true)"
        ));
        accessor.registerCommandOutput("lp group admin permission info", List.of(
                "+ betterrtp.admin (true)"
        ));
        RTP.serverAccessor = accessor;

        Path customDir = tempDir.resolve("external_plugins_unified");
        Path extBetter = customDir.resolve("BetterRTP");
        Files.createDirectories(extBetter);
        Files.writeString(extBetter.resolve("config.yml"), "Default:\n  MinRadius: 50\n  MaxRadius: 2500\n");

        io.github.dailystruggle.rtp.common.commands.config.ConfigImportCmd importCmd =
                new io.github.dailystruggle.rtp.common.commands.config.ConfigImportCmd(null);

        // 1. Dry-run import with permissions default (true)
        Map<String, List<String>> dryRunParams = new HashMap<>();
        dryRunParams.put("source", List.of("betterrtp"));
        dryRunParams.put("path", List.of(customDir.toString()));
        dryRunParams.put("overwrite", List.of("false"));

        boolean dryRunResult = importCmd.onCommand(RTPAPI.serverId, dryRunParams, null);
        assertTrue(dryRunResult);
        assertEquals(0, dispatchedCommands.size(), "Dry-run import should not dispatch commands to provider");

        // 2. Confirmed import with overwrite=true -> should trigger permission migration dispatch
        Map<String, List<String>> applyParams = new HashMap<>();
        applyParams.put("source", List.of("betterrtp"));
        applyParams.put("path", List.of(customDir.toString()));
        applyParams.put("overwrite", List.of("true"));

        boolean applyResult = importCmd.onCommand(RTPAPI.serverId, applyParams, null);
        assertTrue(applyResult);
        assertEquals(2, dispatchedCommands.size(), "Confirmed import should dispatch permission set commands");
        assertTrue(dispatchedCommands.contains("lp group default permission set rtp.use true"));
        assertTrue(dispatchedCommands.contains("lp group admin permission set rtp.admin true"));

        // 3. Confirmed import with permissions=false -> should skip permission migration
        dispatchedCommands.clear();
        Map<String, List<String>> skipPermsParams = new HashMap<>();
        skipPermsParams.put("source", List.of("betterrtp"));
        skipPermsParams.put("path", List.of(customDir.toString()));
        skipPermsParams.put("overwrite", List.of("true"));
        skipPermsParams.put("permissions", List.of("false"));

        boolean skipResult = importCmd.onCommand(RTPAPI.serverId, skipPermsParams, null);
        assertTrue(skipResult);
        assertEquals(0, dispatchedCommands.size(), "Import with permissions=false should not dispatch provider commands");
    }

    @Test
    @DisplayName("5.5 - Test against variations of competitor configurations from server test directory")
    void testAgainstRealServerConfigurationVariations() {
        File serverPluginsDir = new File("C:\\GameServers\\Minecraft\\testServer\\RTP-Paper\\26.3\\plugins");
        if (!serverPluginsDir.isDirectory()) return;

        // Verify competitor directories exist
        File betterRtpDir = new File(serverPluginsDir, "BetterRTP");
        File justRtpDir = new File(serverPluginsDir, "JustRTP");
        File ezRtpDir = new File(serverPluginsDir, "EzRTP");
        File jakesRtpDir = new File(serverPluginsDir, "JakesRTP");

        assertTrue(betterRtpDir.isDirectory(), "BetterRTP config dir should exist");
        assertTrue(justRtpDir.isDirectory(), "JustRTP config dir should exist");
        assertTrue(ezRtpDir.isDirectory(), "EzRTP config dir should exist");
        assertTrue(jakesRtpDir.isDirectory(), "JakesRTP config dir should exist");

        // Test configuration variations with different providers and permission templates
        // 1. BetterRTP with custom PEX command templates
        service.setGroupListTemplate("pex groups");
        service.setGroupGetTemplate("pex group [group] list");
        service.setGroupSetTemplate("pex group [group] add [permission] [contexts]");

        List<PermissionMigrationService.ParsedNode> betterNodes = List.of(
                new PermissionMigrationService.ParsedNode("betterrtp.use", true, ""),
                new PermissionMigrationService.ParsedNode("betterrtp.world.nether", true, "world=world_nether"),
                new PermissionMigrationService.ParsedNode("betterrtp.bypass.cooldown", true, ""),
                new PermissionMigrationService.ParsedNode("betterrtp.biome.plains", true, "")
        );

        PermissionMigrationService.MigrationPlan betterPlan =
                service.planMigration("group", "default", betterNodes, "betterrtp", false);
        assertEquals(4, betterPlan.getGeneratedCommands().size());
        assertTrue(betterPlan.getGeneratedCommands().contains("pex group default add rtp.use"));
        assertTrue(betterPlan.getGeneratedCommands().contains("pex group default add rtp.worlds.nether world=world_nether"));
        assertTrue(betterPlan.getGeneratedCommands().contains("pex group default add rtp.noCooldown"));
        assertTrue(betterPlan.getGeneratedCommands().contains("pex group default add rtp.biome.plains"));

        // 2. JustRTP with UltraPermissions command templates
        service.setGroupSetTemplate("upc group [group] addPermission [permission] [contexts]");
        List<PermissionMigrationService.ParsedNode> justNodes = List.of(
                new PermissionMigrationService.ParsedNode("justrtp.use", true, ""),
                new PermissionMigrationService.ParsedNode("justrtp.nocooldown", true, ""),
                new PermissionMigrationService.ParsedNode("justrtp.admin", true, "")
        );
        PermissionMigrationService.MigrationPlan justPlan =
                service.planMigration("group", "vip", justNodes, "justrtp", false);
        assertEquals(3, justPlan.getGeneratedCommands().size());
        assertTrue(justPlan.getGeneratedCommands().contains("upc group vip addPermission rtp.use"));
        assertTrue(justPlan.getGeneratedCommands().contains("upc group vip addPermission rtp.noCooldown"));
        assertTrue(justPlan.getGeneratedCommands().contains("upc group vip addPermission rtp.admin"));

        // 3. EzRTP & JakesRTP multi-competitor migration under 'all' source filter
        service.setGroupSetTemplate(PermissionMigrationService.DEFAULT_GROUP_SET_TEMPLATE);
        List<PermissionMigrationService.ParsedNode> mixedNodes = List.of(
                new PermissionMigrationService.ParsedNode("ezrtp.teleport", true, ""),
                new PermissionMigrationService.ParsedNode("ezrtp.bypass.cooldown", true, ""),
                new PermissionMigrationService.ParsedNode("jakesrtp.rtp", true, ""),
                new PermissionMigrationService.ParsedNode("jakesrtp.nocooldown", true, "")
        );
        PermissionMigrationService.MigrationPlan mixedPlan =
                service.planMigration("group", "member", mixedNodes, "all", false);
        assertEquals(4, mixedPlan.getGeneratedCommands().size());
        assertTrue(mixedPlan.getGeneratedCommands().contains("lp group member permission set rtp.use true"));
        assertTrue(mixedPlan.getGeneratedCommands().contains("lp group member permission set rtp.noCooldown true"));
    }

    @Test
    @DisplayName("5.6 - Sanitizes parsed group names to [A-Za-z0-9_-]+ and rejects malicious tokens")
    void testSanitizesGroupNames() {
        List<String> rawOutput = List.of(
                "Groups: default, admin-1, vip_plus, malformed group name, evil;inject",
                "- valid_group (weight: 10)",
                "- drop table users;",
                "> good-group"
        );
        List<String> parsed = service.parseGroupListOutput(rawOutput);
        assertTrue(parsed.contains("default"));
        assertTrue(parsed.contains("admin-1"));
        assertTrue(parsed.contains("vip_plus"));
        assertTrue(parsed.contains("valid_group"));
        assertTrue(parsed.contains("good-group"));

        assertFalse(parsed.contains("malformed group name"));
        assertFalse(parsed.contains("evil;inject"));
        assertFalse(parsed.contains("drop table users;"));

        assertThrows(IllegalArgumentException.class, () -> service.formatGroupGet("evil;command"));
        assertThrows(IllegalArgumentException.class, () -> service.formatGroupSet("bad name", "rtp.use", true, ""));
    }

    private static final class StubRoot extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {
        StubRoot() {
            super(null);
        }
        @Override public String name() { return "rtp"; }
        @Override public String permission() { return "rtp.use"; }
        @Override public String description() { return "test root"; }
        @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, io.github.dailystruggle.commandsapi.common.CommandsAPICommand nextCommand) {
            return true;
        }
    }
}
