package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitSelector;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.commands.test.TestAllCmd;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("REQ-API-F-009 /rtp test selector syntax")
class TestCmdSelectorSyntaxTest {
  @TempDir File tempDir;
  private CapturingTestCmd command;
  private final List<String> messages = new ArrayList<>();

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
    CommandsAPI.commandPipeline.clear();
    command = new CapturingTestCmd();
  }

  @AfterEach
  void tearDown() {
    CommandsAPI.commandPipeline.clear();
    RTPTestSetup.cleanUp();
  }

  @Test
  void allAndJsonUseTheRealCommandsApiParser() {
    assertTrue(dispatch("all"));
    assertEquals(List.of(BitSelector.parse("all")), command.allSelectors);
    assertFalse(command.allJson);

    command.allSelectors = null;
    assertTrue(dispatch("all", "json=true"));
    assertEquals(List.of(BitSelector.parse("all")), command.allSelectors);
    assertTrue(command.allJson);

    command.allSelectors = null;
    assertFalse(dispatch("all", "json:true"));
    assertNull(command.allSelectors, "colon-form json must not reach the child command");
  }

  @Test
  void tierSelectorAcceptsEqualsAndCommaUnion() {
    assertTrue(dispatch("tier=pbit,ibit"));
    assertEquals(
        List.of(BitSelector.parse("tier:pbit"), BitSelector.parse("tier:ibit")),
        command.selectors);
  }

  @Test
  void singleTierSelectorUsesEqualsSyntax() {
    assertTrue(dispatch("tier=pbit"));
    assertEquals(List.of(BitSelector.parse("tier:pbit")), command.selectors);
  }

  @Test
  void subsystemSelectorIsBoundByTheRealParser() {
    assertTrue(dispatch("subsystem=test.commands"));
    assertEquals(List.of(BitSelector.parse("subsystem:test.commands")), command.selectors);
  }

  @Test
  void networkJsonParameterBelongsToTheParentNotItsLeaves() {
    assertTrue(dispatch("network", "json=true"));
    assertFalse(dispatch("network", "tokens", "json=true"));
  }

  @Test
  void colonFormAndUnknownTierAreNotBound() {
    assertFalse(dispatch("tier:pbit"));
    assertNull(command.selectors);

    assertTrue(dispatch("tier=bogus"));
    assertNull(command.selectors);
    assertTrue(messages.stream().anyMatch(message -> message.contains("tier=bogus")));
  }

  @Test
  void tierAndSubsystemCannotBeCombined() {
    assertFalse(dispatch("tier=pbit", "subsystem=test.commands"));
    assertNull(command.selectors);
  }

  private boolean dispatch(String... args) {
    CompletableFuture<Boolean> future =
        command.onCommand(RTPAPI.serverId, permission -> true, messages::add, args, 0, null);
    while (!CommandsAPI.commandPipeline.isEmpty()) CommandsAPI.execute();
    assertTrue(future.isDone(), "parser must complete after draining the command pipeline");
    return future.join();
  }

  private static final class CapturingTestCmd extends TestCmd {
    List<BitSelector> selectors;
    List<BitSelector> allSelectors;
    boolean allJson;

    CapturingTestCmd() {
      super(null);
      getCommandLookup().put("ALL", new CapturingAll(this));
    }

    @Override
    protected void runSelectors(UUID callerId, List<BitSelector> requested, boolean json) {
      selectors = List.copyOf(requested);
    }
  }

  private static final class CapturingAll extends TestAllCmd {
    private final CapturingTestCmd parent;

    CapturingAll(CapturingTestCmd parent) {
      super(parent);
      this.parent = parent;
    }

    @Override
    protected void runAll(UUID callerId, boolean json) {
      parent.allSelectors = List.of(BitSelector.parse("all"));
      parent.allJson = json;
    }
  }
}
