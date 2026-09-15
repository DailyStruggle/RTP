package io.github.dailystruggle.rtp.neoforge.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NeoForgeTestUmbrellaWiringTest {

  private TestUmbrellaContext previousContext;

  @BeforeEach
  void setUp() {
    previousContext = RTP.testUmbrellaContext;
    RTP.testUmbrellaContext = null;
  }

  @AfterEach
  void tearDown() {
    RTP.testUmbrellaContext = previousContext;
  }

  @Test
  @DisplayName("wireTestUmbrellaContext installs NeoForgeTestUmbrellaSender and Scheduler onto RTP.testUmbrellaContext")
  void wireContext() {
    assertNull(RTP.testUmbrellaContext);
    RTP.testUmbrellaContext = new TestUmbrellaContext(
        new NeoForgeTestUmbrellaSender(),
        new NeoForgeTestUmbrellaScheduler(),
        null);

    TestUmbrellaContext ctx = TestUmbrellaContext.require();
    assertNotNull(ctx);
    assertTrue(ctx.sender() instanceof NeoForgeTestUmbrellaSender);
    assertTrue(ctx.scheduler() instanceof NeoForgeTestUmbrellaScheduler);
  }
}
