package io.github.dailystruggle.rtp.riftaddon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * After the restore a player still inside the rift sphere stands in restored terrain and
 * suffocates (RTP-71), so the restore moves exactly those players back to their standing block.
 */
@DisplayName("RTP-71: rift restore detects a player left inside the carved sphere")
class RiftEffectMoveClearTest {

  private static RTPWorld<?> world(String name) {
    RTPWorld<?> world = mock(RTPWorld.class);
    when(world.name()).thenReturn(name);
    return world;
  }

  @Test
  @DisplayName("bottom of a radius-6 rift is inside; the rim shell (radius + 1) still counts")
  void playerAtRiftBottomIsInside() {
    RTPWorld<?> w = world("world");
    RTPLocation center = new RTPLocation(w, 0, 70, 0);

    assertTrue(RiftEffect.isInsideRift(new RTPLocation(w, 0, 65, 0), center, 6));
    assertTrue(RiftEffect.isInsideRift(new RTPLocation(w, 0, 63, 0), center, 6));
    assertFalse(RiftEffect.isInsideRift(new RTPLocation(w, 0, 62, 0), center, 6));
  }

  @Test
  @DisplayName("a player already teleported away (far, or another world) is left alone")
  void teleportedPlayerIsOutside() {
    RTPWorld<?> w = world("world");
    RTPLocation center = new RTPLocation(w, 0, 70, 0);

    assertFalse(RiftEffect.isInsideRift(new RTPLocation(w, 4000, 80, -200), center, 6));
    assertFalse(RiftEffect.isInsideRift(new RTPLocation(world("world_nether"), 0, 65, 0), center, 6));
    assertFalse(RiftEffect.isInsideRift(null, center, 6));
  }
}
