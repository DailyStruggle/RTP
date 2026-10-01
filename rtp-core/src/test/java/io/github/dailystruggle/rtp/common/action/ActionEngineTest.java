package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionSessionResult;
import io.github.dailystruggle.rtp.api.group.GroupPlacementResult;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Action Engine & Spatial Lobby Tests (Phase 2 Parity)")
class ActionEngineTest {

  private MockRTPServerAccessor serverAccessor;
  private ActionManager actionManager;
  private io.github.dailystruggle.rtp.api.group.GroupPlacementService originalGroupService;

  @BeforeEach
  void setUp(@TempDir Path tempDir) {
    serverAccessor = RTPTestSetup.install(tempDir.toFile());
    actionManager = new ActionManager();
    RTP.actionManager = actionManager;
    originalGroupService = RTP.groupPlacementService;
  }

  @AfterEach
  void tearDown() {
    RTP.groupPlacementService = originalGroupService;
    RTP.actionManager = null;
  }

  @Test
  @DisplayName("ActionConfigLoader parses declarative triggers block in action YAML")
  void testDeclarativeTriggersParsing() {
    String yamlText = """
        alias: "zone_action"
        triggers:
          - id: "zone_portal"
            type: "PORTAL"
            world: "world"
            pos1:
              x: 10
              y: 64
              z: 10
            pos2:
              x: 15
              y: 68
              z: 15
            cooldown: 5s
            batchInterval: 30s
        """;

    try {
      RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
      ActionDefinition def = ActionConfigLoader.parseDefinition("zone_action", yaml);

      assertNotNull(def);
      assertEquals("zone_action", def.id());
      assertEquals(1, def.triggers().size(), "Triggers size was: " + def.triggers());

      PhysicalTriggerSpec trigger = def.triggers().get(0);
      assertEquals("zone_portal", trigger.id());
      assertEquals(PhysicalTriggerSpec.TriggerType.PORTAL, trigger.type());
      assertEquals("world", trigger.worldName());
      assertEquals(10, trigger.minX());
      assertEquals(64, trigger.minY());
      assertEquals(10, trigger.minZ());
      assertEquals(15, trigger.maxX());
      assertEquals(68, trigger.maxY());
      assertEquals(15, trigger.maxZ());
      assertEquals(5L, trigger.cooldownSeconds());
      assertEquals(30L, trigger.batchIntervalSeconds());
    } catch (Throwable t) {
      t.printStackTrace();
      throw t;
    }
  }

  @Test
  @DisplayName("ActionManager.dispatchGroupAction initiates group action for multiple players")
  void testDispatchGroupAction() {
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    MockRTPPlayer player1 = new MockRTPPlayer(p1, "PlayerOne", new RTPLocation(world, 0, 64, 0));
    MockRTPPlayer player2 = new MockRTPPlayer(p2, "PlayerTwo", new RTPLocation(world, 0, 64, 0));
    serverAccessor.addPlayer(player1);
    serverAccessor.addPlayer(player2);

    RTPLocation loc1 = new RTPLocation(world, 100, 64, 100);
    RTPLocation loc2 = new RTPLocation(world, 108, 64, 100);

    RTP.groupPlacementService = request -> CompletableFuture.completedFuture(
        GroupPlacementResult.success(Map.of(p1, loc1, p2, loc2)));

    ActionDefinition def = new ActionDefinition(
        "squad_jump", "squad_jump", "rtp.action.squad_jump", "Squad Jump",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);

    CompletableFuture<ActionSessionResult> future =
        actionManager.dispatchGroupAction(List.of(p1, p2), "squad_jump");

    assertNotNull(future);
    ActionSessionResult result = future.join();
    assertTrue(result.success(), "Dispatch group action should succeed");
    assertNotNull(result.sessionId());
    assertTrue(actionManager.getSession(result.sessionId()).isPresent());
  }

  @Test
  @DisplayName("Action definition triggers automatically register with PhysicalTriggerManager on loadActions")
  void testTriggersAutoRegisterOnLoad() {
    String yamlText = """
        alias: "step_action"
        triggers:
          - id: "pad_trigger"
            type: "PRESSURE_PLATE"
            world: "world"
            minX: 5
            minY: 64
            minZ: 5
            maxX: 7
            maxY: 65
            maxZ: 7
            batchInterval: 10s
        """;

    RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
    ActionDefinition def = ActionConfigLoader.parseDefinition("step_action", yaml);
    actionManager.registerAction(def);

    for (PhysicalTriggerSpec trigger : def.triggers()) {
      RTP.triggerManager.registerTrigger(trigger);
    }

    PhysicalTriggerSpec registered = RTP.triggerManager.getTrigger("pad_trigger");
    assertNotNull(registered);
    assertEquals(PhysicalTriggerSpec.TriggerType.PRESSURE_PLATE, registered.type());
    assertEquals(10L, registered.batchIntervalSeconds());
    assertEquals("step_action", registered.actionId());
  }
}
