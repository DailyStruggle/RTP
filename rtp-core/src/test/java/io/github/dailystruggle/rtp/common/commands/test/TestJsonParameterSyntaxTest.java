package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real commands-api parser; calling onCommand(Map) directly bypasses tokenization. */
@DisplayName("REQ-API-F-009 /rtp test json parameter uses the commands-api '=' delimiter")
class TestJsonParameterSyntaxTest {
  @TempDir File tempDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
    CommandsAPI.commandPipeline.clear();
  }

  private static final class Capturing extends TestStatusCmd {
    final AtomicReference<Map<String, List<String>>> seen = new AtomicReference<>();

    Capturing() {
      super(null);
    }

    @Override
    public boolean onCommand(
        UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
      seen.set(parameterValues);
      return true;
    }
  }

  private static boolean dispatch(Capturing cmd, String... args) {
    CompletableFuture<Boolean> future =
        cmd.onCommand(RTPAPI.serverId, perm -> true, msg -> {}, args, 0, null);
    while (!CommandsAPI.commandPipeline.isEmpty()) CommandsAPI.execute();
    assertTrue(future.isDone(), "parser must complete synchronously after draining the pipeline");
    return future.join();
  }

  @Test
  void colonFormIsRejectedAsUnknownSubcommand() {
    Capturing cmd = new Capturing();
    assertFalse(dispatch(cmd, "json:true"));
    assertNull(cmd.seen.get(), "json:true must never reach the command body");
  }

  @Test
  void bareStatusDispatchesWithNoParameters() {
    Capturing cmd = new Capturing();
    assertTrue(dispatch(cmd));
    assertFalse(ProbeBitReport.isJson(cmd.seen.get()));
  }

  @Test
  void equalsFormBindsTheJsonParameter() {
    Capturing cmd = new Capturing();
    assertTrue(dispatch(cmd, "json=true"));
    assertEquals(List.of("true"), cmd.seen.get().get("json"));
    assertTrue(ProbeBitReport.isJson(cmd.seen.get()));
  }
}
