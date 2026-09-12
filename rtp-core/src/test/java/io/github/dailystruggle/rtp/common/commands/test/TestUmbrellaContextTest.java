package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dailystruggle.rtp.common.RTP;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link TestUmbrellaContext}, the carrier for platform-supplied
 * {@code /rtp test ...} umbrella SPI implementations. Verifies null-argument
 * rejection, the audit-sink fallback, and the {@code require()} fail-loud
 * contract (S-006). Traces item 14 of ENTERPRISE_READINESS.md.
 */
class TestUmbrellaContextTest {

  private TestUmbrellaContext previous;

  @AfterEach
  void restore() {
    RTP.testUmbrellaContext = previous;
  }

  private static TestUmbrellaSender noopSender() {
    return new TestUmbrellaSender() {
      @Override
      public void log(Level level, String message) {}

      @Override
      public String resolveCallerName(UUID callerId) {
        return "CONSOLE";
      }
    };
  }

  private static TestUmbrellaScheduler inlineScheduler() {
    return (delayMillis, task) -> task.run();
  }

  @Test
  void accessors_returnInstalledCollaborators() {
    TestUmbrellaSender sender = noopSender();
    TestUmbrellaScheduler scheduler = inlineScheduler();
    List<String> audit = new ArrayList<>();
    Consumer<String> sink = audit::add;

    TestUmbrellaContext ctx = new TestUmbrellaContext(sender, scheduler, sink);
    assertSame(sender, ctx.sender());
    assertSame(scheduler, ctx.scheduler());

    ctx.auditSink().accept("line");
    assertEquals(List.of("line"), audit);
  }

  @Test
  void auditSink_fallsBackToNoopWhenNull() {
    TestUmbrellaContext ctx = new TestUmbrellaContext(noopSender(), inlineScheduler(), null);
    assertNotNull(ctx.auditSink());
    // Must be safe to call the fallback sink.
    ctx.auditSink().accept("ignored");
  }

  @Test
  void constructor_rejectsNullCollaborators() {
    assertThrows(
        NullPointerException.class,
        () -> new TestUmbrellaContext(null, inlineScheduler(), null));
    assertThrows(
        NullPointerException.class, () -> new TestUmbrellaContext(noopSender(), null, null));
  }

  @Test
  void require_throwsWhenNotInstalled() {
    previous = RTP.testUmbrellaContext;
    RTP.testUmbrellaContext = null;
    assertThrows(IllegalStateException.class, TestUmbrellaContext::require);
  }

  @Test
  void require_returnsInstalledContext() {
    previous = RTP.testUmbrellaContext;
    TestUmbrellaContext ctx = new TestUmbrellaContext(noopSender(), inlineScheduler(), null);
    RTP.testUmbrellaContext = ctx;
    assertSame(ctx, TestUmbrellaContext.require());
  }
}
