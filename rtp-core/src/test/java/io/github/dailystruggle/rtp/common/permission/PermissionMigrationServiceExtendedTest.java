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
    }
}
