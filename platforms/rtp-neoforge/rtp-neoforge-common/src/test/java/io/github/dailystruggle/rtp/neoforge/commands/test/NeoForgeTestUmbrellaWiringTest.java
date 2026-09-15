package io.github.dailystruggle.rtp.neoforge.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaContext;
import io.github.dailystruggle.rtp.neoforge.NeoForgeRTPMod;
import io.github.dailystruggle.rtp.neoforge.RTPNeoForgeMod;
import io.github.dailystruggle.rtp.neoforge.commands.NeoForgeCommandRegistrar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NeoForgeTestUmbrellaWiringTest {

  private TestUmbrellaContext previousContext;
  private TreeCommand previousBaseCommand;

  @BeforeEach
  void setUp() {
    previousContext = RTP.testUmbrellaContext;
    previousBaseCommand = RTP.baseCommand;
    RTP.testUmbrellaContext = null;
    RTP.baseCommand = null;
  }

  @AfterEach
  void tearDown() {
    RTP.testUmbrellaContext = previousContext;
    RTP.baseCommand = previousBaseCommand;
  }

  @Test
  @DisplayName("wireTestUmbrellaContext installs NeoForgeTestUmbrellaSender and Scheduler onto RTP.testUmbrellaContext")
  void wireContext() {
    assertNull(RTP.testUmbrellaContext);
    RTPNeoForgeMod.wireTestUmbrellaContext();

    TestUmbrellaContext ctx = TestUmbrellaContext.require();
    assertNotNull(ctx);
    assertTrue(ctx.sender() instanceof NeoForgeTestUmbrellaSender);
    assertTrue(ctx.scheduler() instanceof NeoForgeTestUmbrellaScheduler);
  }

  @Test
  @DisplayName("NeoForgeRTPMod wireTestUmbrellaContext helper delegates to RTPNeoForgeMod")
  void neoForgeRTPModWireHelper() {
    assertNull(RTP.testUmbrellaContext);
    NeoForgeRTPMod.wireTestUmbrellaContext();

    TestUmbrellaContext ctx = TestUmbrellaContext.require();
    assertNotNull(ctx);
    assertTrue(ctx.sender() instanceof NeoForgeTestUmbrellaSender);
    assertTrue(ctx.scheduler() instanceof NeoForgeTestUmbrellaScheduler);
  }

  @Test
  @DisplayName("wireTestUmbrellaContext is idempotent")
  void wireContextIdempotent() {
    RTPNeoForgeMod.wireTestUmbrellaContext();
    TestUmbrellaContext first = RTP.testUmbrellaContext;
    assertNotNull(first);

    RTPNeoForgeMod.wireTestUmbrellaContext();
    assertSame(first, RTP.testUmbrellaContext);
  }

  @Test
  @DisplayName("resolveTestCmd returns existing test subcommand when already present on parent")
  void resolveTestCmdExisting() {
    TreeCommand mockTree = mock(TreeCommand.class);
    CommandsAPICommand existingTest = mock(CommandsAPICommand.class);
    when(existingTest.name()).thenReturn("test");
    Map<String, CommandsAPICommand> map = new HashMap<>();
    map.put("TEST", existingTest);
    when(mockTree.getCommandLookup()).thenReturn(map);

    CommandsAPICommand resolved = NeoForgeCommandRegistrar.resolveTestCmd(mockTree);
    assertSame(existingTest, resolved);
  }

  @Test
  @DisplayName("resolveTestCmd returns null gracefully when class is not found")
  void resolveTestCmdGracefulNull() {
    CommandsAPICommand resolved = NeoForgeCommandRegistrar.resolveTestCmd(null);
    assertTrue(resolved == null || "test".equalsIgnoreCase(resolved.name()));
  }

  @Test
  @DisplayName("NeoForgeCommandRegistrar.register registers command tree via BrigadierCommandAdapter")
  void registerTestCmdViaBrigadierBridge() {
    com.mojang.brigadier.CommandDispatcher<Object> dispatcher = new com.mojang.brigadier.CommandDispatcher<>();
    CommandsAPICommand mockTestCmd = mock(CommandsAPICommand.class);
    when(mockTestCmd.name()).thenReturn("test");
    when(mockTestCmd.permission()).thenReturn("rtp.test");

    io.github.dailystruggle.commandsapi.brigadier.BrigadierBridgeContext<Object> bridgeCtx =
        new io.github.dailystruggle.commandsapi.brigadier.BrigadierBridgeContext<>(
            src -> java.util.UUID.randomUUID(),
            (src, perm) -> true,
            (src, msg) -> {});

    NeoForgeCommandRegistrar.register(dispatcher, mockTestCmd, bridgeCtx);
    assertNotNull(dispatcher.getRoot().getChild("test"), "Dispatcher should have 'test' node registered");
  }
}
