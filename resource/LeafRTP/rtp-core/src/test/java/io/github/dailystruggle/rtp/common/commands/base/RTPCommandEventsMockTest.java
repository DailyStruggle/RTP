package io.github.dailystruggle.rtp.common.commands.base;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.mock.MockPlayerRegistry;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RuntimeMockRTPPlayer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ADR-121 section 11: /rtp command outcome events are not published for BIT mocks")
class RTPCommandEventsMockTest {

  private final AtomicInteger success = new AtomicInteger();
  private final AtomicInteger fail = new AtomicInteger();
  private RuntimeMockRTPPlayer mock;
  private MockRTPPlayer real;

  @BeforeEach
  void setUp() {
    RTPCommandEvents.clear();
    MockPlayerRegistry.clear();
    MockRTPWorld world = new MockRTPWorld("world");
    mock = new RuntimeMockRTPPlayer(UUID.randomUUID(), "BitMock", new RTPLocation(world, 0, 64, 0));
    MockPlayerRegistry.register(mock);
    real = new MockRTPPlayer(UUID.randomUUID(), "Real", new RTPLocation(world, 0, 64, 0));
    RTPCommandEvents.onSuccess((s, p) -> success.incrementAndGet());
    RTPCommandEvents.onFail((s, m) -> fail.incrementAndGet());
  }

  @AfterEach
  void tearDown() {
    RTPCommandEvents.clear();
    MockPlayerRegistry.clear();
  }

  @Test
  @DisplayName("ADR-121 section 11: fireSuccess skips subscribers for a mock player or mock sender")
  void fireSuccess_mock_notPublished() {
    RTPCommandEvents.fireSuccess(real, mock);
    RTPCommandEvents.fireSuccess(mock, mock);
    RTPCommandEvents.fireSuccess(mock, real);
    assertEquals(0, success.get());
  }

  @Test
  @DisplayName("ADR-121 section 11: fireFail skips subscribers for a mock sender")
  void fireFail_mockSender_notPublished() {
    RTPCommandEvents.fireFail(mock, "msg");
    RTPCommandSender mockIdSender = new MockRTPCommandSender(mock.uuid(), "BitMockSender");
    RTPCommandEvents.fireFail(mockIdSender, "msg");
    assertEquals(0, fail.get());
  }

  @Test
  @DisplayName("ADR-121 section 11: real players and null senders still publish events")
  void real_isPublished() {
    RTPCommandEvents.fireSuccess(real, real);
    RTPCommandEvents.fireSuccess(null, null);
    RTPCommandEvents.fireFail(real, "msg");
    RTPCommandEvents.fireFail(null, "msg");
    assertEquals(2, success.get());
    assertEquals(2, fail.get());
  }
}
