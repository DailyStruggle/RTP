package io.github.dailystruggle.rtp.common.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link RTPCommandEvents} fan-out seam that lets the platform-neutral
 * {@code CoreRtpRoot} publish command outcomes without knowing what (if anything)
 * is listening - the seam that replaced the bespoke Bukkit root's direct event
 * firing (ADR-070).
 */
class RTPCommandEventsTest {

  @BeforeEach
  @AfterEach
  void reset() {
    RTPCommandEvents.clear();
  }

  @Test
  void fireSuccess_invokesEverySubscriber_andUnregisterStops() throws Exception {
    AtomicInteger a = new AtomicInteger();
    AtomicInteger b = new AtomicInteger();
    RTPCommandEvents.onSuccess((s, p) -> a.incrementAndGet());
    AutoCloseable hb = RTPCommandEvents.onSuccess((s, p) -> b.incrementAndGet());

    RTPCommandEvents.fireSuccess((RTPCommandSender) null, (RTPPlayer) null);
    assertEquals(1, a.get());
    assertEquals(1, b.get());

    hb.close();
    RTPCommandEvents.fireSuccess((RTPCommandSender) null, (RTPPlayer) null);
    assertEquals(2, a.get());
    assertEquals(1, b.get(), "unregistered subscriber must not fire again");
  }

  @Test
  void fireFail_isIsolatedFromThrowingSubscriber() {
    AtomicInteger reached = new AtomicInteger();
    RTPCommandEvents.onFail((s, m) -> { throw new RuntimeException("boom"); });
    RTPCommandEvents.onFail((s, m) -> reached.incrementAndGet());

    // A faulty subscriber must not abort fan-out to the others.
    RTPCommandEvents.fireFail((RTPCommandSender) null, "msg");
    assertEquals(1, reached.get());
  }

  @Test
  void noSubscribers_isANoOp() {
    // Fan-out with nothing registered (Fabric / NeoForge baseline) is harmless.
    RTPCommandEvents.fireSuccess((RTPCommandSender) null, (RTPPlayer) null);
    RTPCommandEvents.fireFail((RTPCommandSender) null, "msg");
    assertTrue(true);
  }
}
