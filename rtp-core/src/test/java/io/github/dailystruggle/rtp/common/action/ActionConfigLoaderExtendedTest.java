package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.api.action.ParameterType;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ActionConfigLoader Extended Branch Coverage Tests")
public class ActionConfigLoaderExtendedTest {

    @Test
    @DisplayName("Parse comprehensive action definition with command, subcommands, and parameters")
    void testParseFullCommandSpecification() {
        String yamlText = """
                alias: "grand_tourney"
                permission: "tourney.use"
                description: "Tournament Action"
                icon: "DIAMOND_SWORD"
                title: "Grand Tournament"
                command:
                  name: "tourney"
                  permission: "tourney.cmd"
                  description: "Tourney base command"
                  aliases:
                    - "gt"
                    - "tournament"
                  parameters:
                    - name: "target"
                      type: "PLAYER"
                      required: true
                    - name: "count"
                      type: "NUMBER"
                      required: false
                      default: "5"
                  subcommands:
                    start:
                      permission: "tourney.start"
                      description: "Start tourney"
                      aliases:
                        - "begin"
                        - "s"
                      run:
                        - CONSOLE: "broadcast Tournament starting!"
                        - MESSAGE: "Welcome to the tournament!"
                        - PLAYER: "me ready"
                        - ACTION: PULL_BACK
                    cancel:
                      permission: "tourney.cancel"
                      description: "Cancel tourney"
                      actions:
                        - BROADCAST: "Tournament canceled"
                        - ACTION: PULL_BACK
                placement:
                  region: "arena_world"
                  shape:
                    name: "CIRCLE"
                    radius: "128b"
                    centerRadius: "16b"
                    weight: 2.0
                  minSeparation: 32
                  elevationTolerance: 10
                  anchor: "BED"
                  retries: 5
                  cacheSize: 10
                  enabled: true
                confinement:
                  boundary: "SHAPE"
                  shape:
                    name: "CIRCLE"
                    radius: "64b"
                    centerRadius: "8b"
                  leashRadius: 64.0
                  duration: "10m"
                  initialSize: 200
                  shrinkTo: 50
                  shrinkOver: "8m"
                  damage: 4.0
                  damageBuffer: 2.0
                  damageInterval: 3s
                  maxDistanceOutside: 15.0
                  outsideActions:
                    - PLAYER: "spawn"
                lifecycle:
                  onStart:
                    - MESSAGE: "Match begins now!"
                  onBoundaryViolation:
                    - ACTION: PULL_BACK
                  onExpire:
                    - MESSAGE: "Time is up!"
                  onDeath:
                    - CONSOLE: "say death occurred"
                  onEnqueue:
                    - MESSAGE: "In queue"
                  onCancel:
                    - MESSAGE: "Canceled"
                triggers:
                  - id: "plate_trigger"
                    type: "PRESSURE_PLATE"
                    location: "arena_world,0,64,0"
                  - id: "sensor_trigger"
                    type: "SENSOR"
                    radius: 10
                    world: "arena_world"
                """;

        RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
        ActionDefinition def = ActionConfigLoader.parseDefinition("grand_tourney", yaml);

        assertNotNull(def);
        assertEquals("grand_tourney", def.id());
        assertEquals("grand_tourney", def.alias());
        assertEquals("tourney.use", def.permission());
        assertEquals("Tournament Action", def.description());
        assertEquals("DIAMOND_SWORD", def.icon());
        assertEquals("Grand Tournament", def.title());

        // Command & Subcommands
        ActionDefinition.CommandSpec cmd = def.command();
        assertEquals("tourney", cmd.name());
        assertEquals(2, cmd.aliases().size());
        assertEquals(2, cmd.parameters().size());
        assertEquals("target", cmd.parameters().get(0).name());
        assertEquals(ParameterType.PLAYER, cmd.parameters().get(0).type());
        assertTrue(cmd.parameters().get(0).required());

        assertEquals(2, cmd.subcommands().size());
        ActionDefinition.SubcommandSpec startSub = cmd.subcommands().get("start");
        assertNotNull(startSub);
        assertEquals(4, startSub.actions().size());

        // Placement
        ActionDefinition.PlacementSpec placement = def.placement();
        assertTrue(placement.enabled());
        assertEquals("arena_world", placement.region());
        assertEquals("CIRCLE", placement.shapeName());
        assertEquals(128, placement.radius());
        assertEquals(16, placement.centerRadius());
        assertEquals(32, placement.minSeparation());
        assertEquals(10, placement.elevationTolerance());
        assertEquals(5, placement.retries());
        assertEquals(10, placement.cacheSize());

        // Confinement
        ActionDefinition.ConfinementSpec conf = def.confinement();
        assertEquals(ConfinementBoundary.SHAPE, conf.boundary());
        assertEquals(600L, conf.durationSeconds());
        assertEquals(4.0, conf.damageAmount());
        assertEquals(2.0, conf.damageBuffer());
        assertEquals(3L, conf.damageIntervalSeconds());
        assertEquals(15.0, conf.maxDistanceOutside());
        assertEquals(1, conf.outsideActions().size());

        // Lifecycle
        ActionDefinition.LifecycleSpec life = def.lifecycle();
        assertFalse(life.onStart().isEmpty());
        assertFalse(life.onBoundaryViolation().isEmpty());
        assertFalse(life.onExpire().isEmpty());
        assertFalse(life.onDeath().isEmpty());
        assertFalse(life.onEnqueue().isEmpty());
        assertFalse(life.onCancel().isEmpty());

        // Triggers
        assertFalse(def.triggers().isEmpty());
        assertEquals(2, def.triggers().size());
    }

    @Test
    @DisplayName("Parse disabled placement and alternate confinement boundary types")
    void testParseDisabledPlacementAndBoundaries() {
        String yamlText = """
                placement: "none"
                confinement:
                  boundary: "REGION"
                """;
        RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
        ActionDefinition def = ActionConfigLoader.parseDefinition("disabled_placement", yaml);

        assertNotNull(def);
        assertEquals(ActionDefinition.PlacementSpec.DISABLED, def.placement());
        assertEquals(ConfinementBoundary.REGION, def.confinement().boundary());

        String yamlText2 = """
                placement: false
                confinement:
                  boundary: "SUBSPACE"
                """;
        RtpYamlConfig yaml2 = RtpYamlConfig.parse(yamlText2);
        ActionDefinition def2 = ActionConfigLoader.parseDefinition("disabled_placement_2", yaml2);
        assertEquals(ActionDefinition.PlacementSpec.DISABLED, def2.placement());
        assertEquals(ConfinementBoundary.SUBSPACE, def2.confinement().boundary());
    }
}
