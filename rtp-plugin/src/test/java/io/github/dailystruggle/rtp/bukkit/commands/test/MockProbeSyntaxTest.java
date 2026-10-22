package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("REQ-API-F-009 probe arguments use the real command parser")
class MockProbeSyntaxTest {
  @TempDir File tempDir;
  private CapturingBukkitTestCmd command;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
    CommandsAPI.commandPipeline.clear();
    command = new CapturingBukkitTestCmd();
  }

  @AfterEach
  void tearDown() {
    CommandsAPI.commandPipeline.clear();
    RTPTestSetup.cleanUp();
  }

  @Test
  void stressSupportsMockAndPlayerArguments() {
    assertTrue(dispatch("stress"));
    assertEquals(Map.of(), command.stress.arguments);

    assertTrue(dispatch("stress", "count=2", "iterations=3"));
    assertEquals(List.of("2"), command.stress.arguments.get("count"));
    assertEquals(List.of("3"), command.stress.arguments.get("iterations"));

    assertTrue(dispatch("stress", "player=Steve"));
    assertEquals(List.of("Steve"), command.stress.arguments.get("player"));
  }

  @Test
  void asyncReplyAndHologramAcceptLiteralPlayerTokens() {
    assertTrue(dispatch("async-reply", "player=Steve"));
    assertEquals(List.of("Steve"), command.asyncReply.arguments.get("player"));

    assertTrue(dispatch("hologram", "player=Steve", "seconds=3"));
    assertEquals(List.of("Steve"), command.hologram.arguments.get("player"));
    assertEquals(List.of("3"), command.hologram.arguments.get("seconds"));
  }

  @Test
  void queueStarvationRegionAndSampleTokensAreParsed() {
    assertTrue(dispatch("queue-starvation", "region=default", "samples=3"));
    assertEquals(List.of("default"), command.queueStarvation.arguments.get("region"));
    assertEquals(List.of("3"), command.queueStarvation.arguments.get("samples"));
  }

  @Test
  void colonPlayerSyntaxIsRejectedBeforeTheProbeRuns() {
    assertFalse(dispatch("stress", "player:Steve"));
    assertTrue(command.stress.arguments.isEmpty());
  }

  private boolean dispatch(String... args) {
    CompletableFuture<Boolean> future =
        command.onCommand(RTPAPI.serverId, permission -> true, ignored -> {}, args, 0, null);
    while (!CommandsAPI.commandPipeline.isEmpty()) CommandsAPI.execute();
    assertTrue(future.isDone(), "parser must complete after draining the command pipeline");
    return future.join();
  }

  private static final class CapturingBukkitTestCmd extends BukkitTestCmd {
    private final CapturingLeaf stress = new CapturingLeaf(this, "stress", "player", "count", "iterations", "intervalTicks", "region", "json");
    private final CapturingLeaf asyncReply = new CapturingLeaf(this, "async-reply", "player", "region", "json");
    private final CapturingLeaf hologram = new CapturingLeaf(this, "hologram", "player", "seconds", "json");
    private final CapturingLeaf queueStarvation = new CapturingLeaf(this, "queue-starvation", "region", "samples", "json");

    private CapturingBukkitTestCmd() {
      super(null);
      getCommandLookup().put("STRESS", stress);
      getCommandLookup().put("ASYNC-REPLY", asyncReply);
      getCommandLookup().put("HOLOGRAM", hologram);
      getCommandLookup().put("QUEUE-STARVATION", queueStarvation);
    }
  }

  private static final class CapturingLeaf extends BaseRTPCmdImpl {
    private final String commandName;
    private Map<String, List<String>> arguments = Map.of();

    private CapturingLeaf(CommandsAPICommand parent, String commandName, String... parameters) {
      super(parent);
      this.commandName = commandName;
      for (String parameter : parameters) addParameter(parameter, new RawParameter());
    }

    @Override
    public String name() {
      return commandName;
    }

    @Override
    public String permission() {
      return "rtp.test";
    }

    @Override
    public String description() {
      return "capture parser values";
    }

    @Override
    public boolean onCommand(
        UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
      arguments = new HashMap<>(parameterValues);
      return true;
    }
  }

  private static final class RawParameter extends CommandParameter {
    private RawParameter() {
      super("rtp.test", "capture literal value", (uuid, value) -> true);
    }

    @Override
    public Set<String> values() {
      return Set.of();
    }
  }
}
