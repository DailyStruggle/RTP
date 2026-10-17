package io.github.dailystruggle.rtp.actionaddon;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RTPActionAddonServiceLoaderTest {

  @Test
  @DisplayName("LeafRTPActionAddon is discoverable via ServiceLoader")
  void testServiceLoaderDiscovery() {
    ServiceLoader<RTPAddon> loader = ServiceLoader.load(RTPAddon.class);
    boolean found = false;
    for (RTPAddon addon : loader) {
      if (addon instanceof RTPActionAddon) {
        found = true;
        assertEquals("LeafRTPActionAddon", addon.name());
        break;
      }
    }
    assertTrue(found, "LeafRTPActionAddon must be discoverable via ServiceLoader");
  }

  @Test
  @DisplayName("LeafRTPActionAddon lifecycle registers and unregisters cleanly")
  void testLifecycleCleanliness() {
    RTPActionAddon addon = new RTPActionAddon();
    assertNull(RTP.actionManager);
    assertNull(RTPAPI.actionService);

    addon.onLoad();
    assertNotNull(RTP.actionManager);
    assertNotNull(RTPAPI.actionService);
    assertEquals(RTP.actionManager, RTPAPI.actionService);

    addon.onUnload();
    assertNull(RTP.actionManager);
    assertNull(RTPAPI.actionService);
  }
}
