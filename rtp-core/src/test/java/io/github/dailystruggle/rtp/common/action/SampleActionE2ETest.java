package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionSession;
import io.github.dailystruggle.rtp.api.action.ActionSessionResult;
import io.github.dailystruggle.rtp.api.group.GroupPlacementResult;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification that the SHIPPED sample action definitions
 * ({@code rtp-plugin/src/main/resources/definitions/actions/*.yml}) actually parse and
 * drive the real {@link ActionConfigLoader} -> {@link ActionManager} -> {@link ActionSessionImpl}
 * lifecycle as they will in production (ADR-093 / ADR-095).
 *
 * <p>Unlike {@code ActionManagerTest} (which builds definitions in-code), this test loads the
 * exact YAML files bundled in the plugin jar, so a broken/mis-parsed shipped config fails here.
 */
class SampleActionE2ETest {

  /** Recording accessor: captures every dispatched command line so lifecycle side effects are observable. */
  private static final class RecordingServerAccessor extends MockRTPServerAccessor {
    final List<String> dispatched = new CopyOnWriteArrayList<>();

    RecordingServerAccessor(File dir) {
      super(dir);
    }

    @Override
    public boolean executeCommand(UUID senderId, String commandLine) {
      dispatched.add(senderId + " :: " + commandLine);
      return true;
    }
  }

  private RecordingServerAccessor accessor;
  private ActionManager manager;
  private io.github.dailystruggle.rtp.api.group.GroupPlacementService originalGroupService;

  /** Resolves the shipped resources dir regardless of whether tests run from the module or repo root. */
  private static File shippedResourcesDir() {
    File[] candidates = {
      new File("../rtp-plugin/src/main/resources"),
      new File("rtp-plugin/src/main/resources"),
    };
    for (File c : candidates) {
      if (new File(c, "definitions/actions").isDirectory()) {
        return c;
      }
    }
    fail("Could not locate shipped definitions/actions dir; cwd=" + new File(".").getAbsolutePath());
    return null;
  }

  @BeforeEach
  void setUp() {
    accessor = new RecordingServerAccessor(new File("."));
    RTP.serverAccessor = accessor;
    originalGroupService = RTP.groupPlacementService;
    manager = new ActionManager();

    // Deterministic placement stub: every participant lands at a fixed safe slot.
    RTP.groupPlacementService = request -> {
      java.util.Map<UUID, RTPLocation> placements = new java.util.HashMap<>();
      int i = 0;
      for (UUID pid : request.participants()) {
        placements.put(pid, new RTPLocation(accessor.getRTPWorld("world"), 100 + i * 40, 64, 100));
        i++;
      }
      return CompletableFuture.completedFuture(GroupPlacementResult.success(placements));
    };

    // Load the ACTUAL shipped YAML action definitions.
    ActionConfigLoader.loadActions(shippedResourcesDir(), manager);
  }

  @Test
  @DisplayName("MultiConfigParser unpacks bundled definitions to plugin directory if empty")
  void testUnpackBundledDefinitionsToPluginDir(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
    ActionManager m = new ActionManager();
    io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.ActionKeys> actions =
        new io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<>(
            io.github.dailystruggle.rtp.common.configuration.enums.ActionKeys.class, "actions", "1.0", tempDir.toFile(), "definitions/actions", "en");
    ActionConfigLoader.loadActions(actions, m);

    File actionsDir = new File(tempDir.toFile(), "definitions/actions");
    assertTrue(actionsDir.exists());
    File[] ymls = actionsDir.listFiles((dir, name) -> name.endsWith(".yml"));
    assertNotNull(ymls);
    assertTrue(actionsDir.isDirectory());
  }

  @AfterEach
  void tearDown() {
    RTP.groupPlacementService = originalGroupService;
  }

  @Test
  @DisplayName("All shipped sample actions parse and register (scatter/nearplayer/nearclaim/location)")
  void testShippedActionsRegister() {
    for (String id : List.of("scatter", "nearplayer", "nearclaim", "location")) {
      assertTrue(manager.getActionIds().contains(id),
          "shipped action '" + id + "' must be registered from its YAML file");
    }
  }

  @Test
  @DisplayName("Shipped one-shot actions run the full trigger->onStart->placement lifecycle end to end")
  void testShippedLifecycleEndToEnd() {
    for (String id : List.of("scatter", "nearplayer", "nearclaim", "location")) {
      accessor.dispatched.clear();
      UUID player = UUID.randomUUID();

      // nearplayer/location may consult context for a live anchor; supply one so the flow is realistic.
      ActionContext ctx = ActionContext.of(
          Map.of("anchorWorld", "world", "anchorX", 250, "anchorY", 70, "anchorZ", -120));

      ActionSessionResult res = manager.trigger(id, List.of(player), ctx).join();
      assertTrue(res.success(), "shipped action '" + id + "' should trigger successfully: " + res.failureReason());
      assertNotNull(res.sessionId());

      Optional<ActionSession> session = manager.getSession(res.sessionId());
      assertTrue(session.isPresent(), "session for '" + id + "' should be active");
      assertEquals(1, session.get().participants().size());

      // The shipped onStart step is `PLAYER: "msg ..."`; it MUST dispatch to the participant.
      boolean onStartFired = false;
      for (String line : accessor.dispatched) {
        if (line.startsWith(player.toString()) && line.contains("msg")) {
          onStartFired = true;
        }
      }
      assertTrue(onStartFired,
          "shipped action '" + id + "' onStart PLAYER message did not dispatch. Captured=" + accessor.dispatched);

      manager.disarm(res.sessionId());
      assertFalse(manager.getSession(res.sessionId()).isPresent(),
          "session for '" + id + "' should be cleaned up after disarm");
    }
  }
}
