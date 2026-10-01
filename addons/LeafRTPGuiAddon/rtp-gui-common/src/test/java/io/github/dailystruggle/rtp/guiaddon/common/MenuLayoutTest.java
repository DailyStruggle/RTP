package io.github.dailystruggle.rtp.guiaddon.common;

import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.RtpTargetStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MenuLayoutTest {

    @Test
    void spreadColumn_spreadsEvenlyForThreeOrFewerItems() {
        // 1 item -> centered at inner col 3
        assertEquals(3, MenuLayout.spreadColumn(0, 1));

        // 2 items -> spaced at inner cols 1 and 5
        assertEquals(1, MenuLayout.spreadColumn(0, 2));
        assertEquals(5, MenuLayout.spreadColumn(1, 2));

        // 3 items -> spaced at inner cols 1, 3, and 5 (alternating slots across row)
        assertEquals(1, MenuLayout.spreadColumn(0, 3));
        assertEquals(3, MenuLayout.spreadColumn(1, 3));
        assertEquals(5, MenuLayout.spreadColumn(2, 3));
    }

    @Test
    void spreadColumn_packsContiguouslyWhenExceedingFiftyPercent() {
        // 4 items (> 50% of 7 cols) -> packed contiguously centered: cols 1, 2, 3, 4
        assertEquals(1, MenuLayout.spreadColumn(0, 4));
        assertEquals(2, MenuLayout.spreadColumn(1, 4));
        assertEquals(3, MenuLayout.spreadColumn(2, 4));
        assertEquals(4, MenuLayout.spreadColumn(3, 4));

        // 5 items -> cols 1, 2, 3, 4, 5
        assertEquals(1, MenuLayout.spreadColumn(0, 5));
        assertEquals(2, MenuLayout.spreadColumn(1, 5));
        assertEquals(3, MenuLayout.spreadColumn(2, 5));
        assertEquals(4, MenuLayout.spreadColumn(3, 5));
        assertEquals(5, MenuLayout.spreadColumn(4, 5));
    }

    @Test
    void compute_separatesDestinationsAndSubmenusOntoDistinctRows() {
        GuiMenuConfig config = new GuiMenuConfig();
        List<MenuEntry> entries = new ArrayList<>();

        // Add 1 region destination
        entries.add(new MenuEntry(
                RtpTarget.region("wild"),
                RtpTargetStatus.Availability.READY,
                "Region: wild",
                "GRASS_BLOCK",
                0L,
                0.0));

        // Add 2 submenu action buttons
        entries.add(new MenuEntry(
                RtpTarget.action("menu:biomes:0"),
                RtpTargetStatus.Availability.READY,
                "Select Biome...",
                "COMPASS",
                0L,
                0.0));
        entries.add(new MenuEntry(
                RtpTarget.action("menu:actions:0"),
                RtpTargetStatus.Availability.READY,
                "Special Teleports",
                "NETHERITE_SWORD",
                0L,
                0.0));

        MenuModel model = MenuModel.buildActionsMenu(java.util.UUID.randomUUID(), config, 0);
        // Construct a model with our custom entries
        MenuModel customModel = new MenuModel(
                "Teleport",
                4,
                "GRAY_STAINED_GLASS_PANE",
                false,
                "BEACON",
                entries,
                null);

        MenuLayout layout = MenuLayout.compute(customModel);
        Map<Integer, MenuEntry> placed = layout.slotEntries();

        // Total content rows = 2 (1 dest row + 1 submenu row)
        // With top border (row 0) and bottom border (row 3), rows = 4
        assertEquals(4, layout.rows());

        // Row 1 (destinations): slots 9..17
        // 1 destination -> inner col 3 -> slot 9 + 1 + 3 = 13 (dead center of row 1)
        assertTrue(placed.containsKey(13));
        assertEquals("Region: wild", placed.get(13).displayName());

        // Row 2 (submenus): slots 18..26
        // 2 submenus -> inner cols 1 and 5 -> slot 18 + 1 + 1 = 20, and 18 + 1 + 5 = 24
        assertTrue(placed.containsKey(20));
        assertTrue(placed.containsKey(24));
        assertEquals("Select Biome...", placed.get(20).displayName());
        assertEquals("Special Teleports", placed.get(24).displayName());
    }
}
