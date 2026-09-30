package io.github.dailystruggle.rtp.guiaddon.common;

import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.RtpTargetStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
