package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives literal action command tokens through the commands-api parser. */
class TestActionCmdSyntaxTest {
  @TempDir File tempDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
    CommandsAPI.commandPipeline.clear();
  }

  @AfterEach
  void tearDown() {
    RTPTestSetup.cleanUp();
  }

  private static final class Capturing extends TestActionCmd {
    final AtomicReference<Map<String, Object>> seen = new AtomicReference<>();

    Capturing() {
      super(null);
    }

    @Override
    protected void executeSimulation(UUID callerId, String actionId, int players, List<String> script) {
      seen.set(Map.of("id", actionId, "players", players, "script", script));
    }

  }

  private static boolean dispatch(Capturing command, String... args) {
    CompletableFuture<Boolean> future = command.onCommand(
        RTPAPI.serverId, permission -> true, ignored -> {}, args, 0, null);
    while (!CommandsAPI.commandPipeline.isEmpty()) CommandsAPI.execute();
    assertTrue(future.isDone());
    return future.join();
  }

  @Test
  void positionalIdAndEqualsOptionsBindThroughTheParser() {
    Capturing command = new Capturing();

    assertTrue(dispatch(command, "koth", "players=2", "script=violate,expire"));

    assertEquals("koth", command.seen.get().get("id"));
    assertEquals(2, command.seen.get().get("players"));
    assertEquals(List.of("violate", "expire"), command.seen.get().get("script"));
  }

  @Test
  void explicitIdParameterIsAccepted() {
    Capturing command = new Capturing();

    assertTrue(dispatch(command, "id=koth", "players=3"));

    assertEquals("koth", command.seen.get().get("id"));
    assertEquals(3, command.seen.get().get("players"));
  }

  @Test
  void colonParameterSyntaxIsRejected() {
    Capturing command = new Capturing();

    assertFalse(dispatch(command, "koth", "players:2"));
    assertNull(command.seen.get());
  }
}
