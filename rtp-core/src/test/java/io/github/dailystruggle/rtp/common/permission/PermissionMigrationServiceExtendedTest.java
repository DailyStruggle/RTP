package io.github.dailystruggle.rtp.common.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PermissionMigrationService Extended Tests")
public class PermissionMigrationServiceExtendedTest {

    @Test
    @DisplayName("Format and parse user and group permission commands and plans")
    void testFormatAndMigrationPlan() {
        PermissionMigrationService service = new PermissionMigrationService();

        // Template getters and setters
        service.setUserSetTemplate("lp user [user] permission set [permission] [value][contexts]");
        assertEquals("lp user [user] permission set [permission] [value][contexts]", service.getUserSetTemplate());

        service.setUserUnsetTemplate("lp user [user] permission unset [permission][contexts]");
        assertEquals("lp user [user] permission unset [permission][contexts]", service.getUserUnsetTemplate());

        service.setUserGetTemplate("lp user [user] permission info");
        assertEquals("lp user [user] permission info", service.getUserGetTemplate());

        service.setUserListTemplate("lp listusers");
        assertEquals("lp listusers", service.getUserListTemplate());

        service.setGroupSetTemplate("lp group [group] permission set [permission] [value][contexts]");
        assertEquals("lp group [group] permission set [permission] [value][contexts]", service.getGroupSetTemplate());

        service.setGroupUnsetTemplate("lp group [group] permission unset [permission][contexts]");
        assertEquals("lp group [group] permission unset [permission][contexts]", service.getGroupUnsetTemplate());

        service.setGroupGetTemplate("lp group [group] permission info");
        assertEquals("lp group [group] permission info", service.getGroupGetTemplate());

        service.setGroupListTemplate("lp listgroups");
        assertEquals("lp listgroups", service.getGroupListTemplate());

        // Formatting
        String userSet = service.formatUserSet("Player1", "rtp.use", true, "world=world_nether");
        assertEquals("lp user Player1 permission set rtp.use true world=world_nether", userSet);

        String userUnset = service.formatUserUnset("Player1", "rtp.use", "");
        assertEquals("lp user Player1 permission unset rtp.use", userUnset);

        String groupSet = service.formatGroupSet("vip", "rtp.biome.*", true, "server=survival");
        assertEquals("lp group vip permission set rtp.biome.* true server=survival", groupSet);

        String groupUnset = service.formatGroupUnset("vip", "rtp.biome.*", null);
        assertEquals("lp group vip permission unset rtp.biome.*", groupUnset);

        assertEquals("lp user Player1 permission info", service.formatUserGet("Player1"));
        assertEquals("lp group vip permission info", service.formatGroupGet("vip"));

        // MigrationPlan and PermissionEntry
        PermissionMigrationService.MigrationPlan plan = new PermissionMigrationService.MigrationPlan(true);
        assertTrue(plan.isApplied());
        PermissionMigrationService.PermissionEntry entry = new PermissionMigrationService.PermissionEntry(
                "user", "Player1", "betterrtp.use", "rtp.use", true, "world=survival"
        );
        assertEquals("user", entry.getTargetType());
        assertEquals("Player1", entry.getTargetName());
        assertEquals("betterrtp.use", entry.getSourcePermission());
        assertEquals("rtp.use", entry.getTargetPermission());
        assertTrue(entry.getValue());
        assertEquals("world=survival", entry.getContexts());

        plan.addEntry(entry, userSet);
        assertEquals(1, plan.getMappedEntries().size());
        assertEquals(1, plan.getGeneratedCommands().size());
        plan.getExecutedCommands().add(userSet);
        assertEquals(1, plan.getExecutedCommands().size());
        plan.getErrors().add("Simulated error");
        assertEquals(1, plan.getErrors().size());
    }

    @Test
    @DisplayName("Parse permission info output with contexts, colors, and flags")
    void testParsePermissionInfoOutput() {
        PermissionMigrationService service = new PermissionMigrationService();

        List<String> rawOutput = List.of(
                "Showing page 1 of 2",
                "[LP] default's Permissions:",
                "§a+ rtp.use (true) [server=survival, world=nether]",
                "§c- rtp.biome (false)",
                "d rtp.world (true)"
        );

        List<PermissionMigrationService.ParsedNode> parsed = service.parsePermissionInfoOutput(rawOutput);
        assertNotNull(parsed);
        assertEquals(3, parsed.size());

        PermissionMigrationService.ParsedNode node1 = parsed.get(0);
        assertEquals("rtp.use", node1.getPermission());
        assertTrue(node1.getValue());
        assertEquals("server=survival world=nether", node1.getContexts());

        PermissionMigrationService.ParsedNode node2 = parsed.get(1);
        assertEquals("rtp.biome", node2.getPermission());
        assertFalse(node2.getValue());

        PermissionMigrationService.ParsedNode node3 = parsed.get(2);
        assertEquals("rtp.world", node3.getPermission());
        assertTrue(node3.getValue());

        assertTrue(service.parsePermissionInfoOutput(Collections.emptyList()).isEmpty());
        assertTrue(service.parsePermissionInfoOutput(null).isEmpty());
    }

    @Test
    @DisplayName("Permission mapping suffixes and edge cases")
    void testMapPermissionVariants() {
        PermissionMigrationService service = new PermissionMigrationService();

        assertTrue(service.mapPermission(null).isEmpty());
        assertTrue(service.mapPermission("").isEmpty());

        assertEquals(List.of("rtp.*"), service.mapPermission("betterrtp.*"));
        assertEquals(List.of("rtp.use"), service.mapPermission("betterrtp.use"));
        assertEquals(List.of("rtp.world"), service.mapPermission("betterrtp.world"));
        assertEquals(List.of("rtp.worlds.*"), service.mapPermission("betterrtp.world.*"));
        assertEquals(List.of("rtp.biome"), service.mapPermission("justrtp.biome"));
        assertEquals(List.of("rtp.biome.*"), service.mapPermission("betterrtp.biome"));
        assertEquals(List.of("rtp.noCooldown"), service.mapPermission("betterrtp.bypass.cooldown"));
        assertEquals(List.of("rtp.noDelay"), service.mapPermission("betterrtp.bypass.delay"));
        assertEquals(List.of("rtp.free"), service.mapPermission("betterrtp.bypass.economy"));
        assertEquals(List.of("rtp.reload"), service.mapPermission("betterrtp.reload"));

        // Extended mapped suffixes
        assertEquals(List.of("rtp.admin"), service.mapPermission("betterrtp.admin"));
        assertEquals(List.of("rtp.admin"), service.mapPermission("betterrtp.permpack.admin"));
        assertEquals(List.of("rtp.worlds.custom_nether"), service.mapPermission("betterrtp.world.custom_nether"));
        assertEquals(List.of("rtp.worlds.custom_end"), service.mapPermission("betterrtp.worlds.custom_end"));
        assertEquals(List.of("rtp.worlds.lobby"), service.mapPermission("betterrtp.gui.world.lobby"));
        assertEquals(List.of("rtp.worlds.vip"), service.mapPermission("betterrtp.gui.paid.vip"));
        assertEquals(List.of("rtp.biome.desert"), service.mapPermission("betterrtp.biome.desert"));
        assertEquals(List.of("rtp.regions.wilderness"), service.mapPermission("betterrtp.use.wilderness"));
        assertEquals(List.of("rtp.regions.custom_profile"), service.mapPermission("betterrtp.profile.custom_profile"));
        assertEquals(List.of("rtp.noCooldown"), service.mapPermission("betterrtp.bypass.cooldown.vip"));
        assertEquals(List.of("rtp.noCooldown"), service.mapPermission("betterrtp.nocooldown.vip"));
        assertEquals(List.of("rtp.noDelay"), service.mapPermission("betterrtp.bypass.delay.vip"));
        assertEquals(List.of("rtp.noDelay"), service.mapPermission("betterrtp.nowarmup.vip"));
    }

    @Test
    @DisplayName("Parse group list output variants")
    void testParseGroupListOutput() {
        PermissionMigrationService service = new PermissionMigrationService();

        assertTrue(service.parseGroupListOutput(null).isEmpty());
        assertTrue(service.parseGroupListOutput(Collections.emptyList()).isEmpty());

        List<String> lines = List.of(
                "[LuckPerms] Groups: (name, weight, tracks)",
                "[LP] Groups: default, vip, moderator",
                "Groups - admin; owner",
                "- default - 0",
                "* vip (weight: 10)",
                "> builder (inherited)",
                "  moderator (displayname: Mod)"
        );

        List<String> groups = service.parseGroupListOutput(lines);
        assertTrue(groups.contains("default"));
        assertTrue(groups.contains("vip"));
        assertTrue(groups.contains("moderator"));
        assertTrue(groups.contains("admin"));
        assertTrue(groups.contains("owner"));
        assertTrue(groups.contains("builder"));
    }

    @Test
    @DisplayName("Plan migration for users and groups with filters and execution")
    void testPlanMigrationScenarios() {
        PermissionMigrationService service = new PermissionMigrationService();

        // Null and empty nodes
        assertTrue(service.planMigration("group", "default", null, null, false).getMappedEntries().isEmpty());
        assertTrue(service.planMigration("user", "test", Collections.emptyList(), null, false).getMappedEntries().isEmpty());

        List<PermissionMigrationService.ParsedNode> nodes = List.of(
                new PermissionMigrationService.ParsedNode("betterrtp.use", true, "world=world_nether"),
                new PermissionMigrationService.ParsedNode("betterrtp.world.survival", true, ""),
                new PermissionMigrationService.ParsedNode("ezrtp.biome.desert", true, "server=survival"),
                new PermissionMigrationService.ParsedNode("betterrtp.world.already_have", true, ""),
                new PermissionMigrationService.ParsedNode("rtp.worlds.already_have", true, "") // Target already exists!
        );

        // Group planning with specific filter
        PermissionMigrationService.MigrationPlan groupPlan = service.planMigration(
                "group", "vip", nodes, "betterrtp", false
        );
        assertFalse(groupPlan.isApplied());
        assertEquals(2, groupPlan.getMappedEntries().size());
        assertTrue(groupPlan.getGeneratedCommands().stream().anyMatch(c -> c.contains("lp group vip permission set rtp.use true world=world_nether")));
        assertTrue(groupPlan.getGeneratedCommands().stream().anyMatch(c -> c.contains("lp group vip permission set rtp.worlds.survival true")));
        assertFalse(groupPlan.getGeneratedCommands().stream().anyMatch(c -> c.contains("rtp.worlds.already_have")));

        // User planning with "all" filter
        PermissionMigrationService.MigrationPlan userPlan = service.planMigration(
                "user", "Steve", nodes, "all", false
        );
        assertTrue(userPlan.getGeneratedCommands().stream().anyMatch(c -> c.contains("lp user Steve permission set rtp.biome.desert true server=survival")));

        // User planning with "*" filter
        PermissionMigrationService.MigrationPlan wildPlan = service.planMigration(
                "user", "Steve", nodes, "*", false
        );
        assertEquals(userPlan.getGeneratedCommands().size(), wildPlan.getGeneratedCommands().size());

        // Plan with apply=true without serverAccessor (should report errors)
        PermissionMigrationService.MigrationPlan appliedPlan = service.planMigration(
                "group", "vip", nodes, "betterrtp", true
        );
        assertTrue(appliedPlan.isApplied());
        assertEquals(2, appliedPlan.getErrors().size());
        assertTrue(appliedPlan.getExecutedCommands().isEmpty());
    }
}
