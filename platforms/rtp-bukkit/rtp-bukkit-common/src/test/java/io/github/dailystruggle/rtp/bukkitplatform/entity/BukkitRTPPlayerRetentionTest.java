package io.github.dailystruggle.rtp.bukkitplatform.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A cached {@code BukkitRTPPlayer} (teleport history, cooldown records) must not keep a quit
 * player's {@link Player} reachable. A plain {@link Proxy} stands in for the player so no mocking
 * framework holds an extra strong reference.
 */
class BukkitRTPPlayerRetentionTest {

  private static Player fakePlayer(UUID uuid, String name) {
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getUniqueId":
                  return uuid;
                case "getName":
                  return name;
                case "isOnline":
                  return true;
                case "hashCode":
                  return System.identityHashCode(proxy);
                case "equals":
                  return proxy == args[0];
                case "toString":
                  return "FakePlayer[" + name + "]";
                default:
                  break;
              }
              Class<?> r = method.getReturnType();
              if (r == boolean.class) return false;
              if (r == int.class || r == short.class || r == byte.class) return 0;
              if (r == long.class) return 0L;
              if (r == double.class) return 0.0d;
              if (r == float.class) return 0.0f;
              if (r == char.class) return '\0';
              if (Set.class.isAssignableFrom(r)) return Collections.emptySet();
              if (List.class.isAssignableFrom(r) || r == Collection.class) return Collections.emptyList();
              if (Map.class.isAssignableFrom(r)) return Collections.emptyMap();
              return null;
            });
  }

  private static boolean collected(WeakReference<?> ref) throws InterruptedException {
    for (int i = 0; i < 50 && ref.get() != null; i++) {
      System.gc();
      Thread.sleep(20);
    }
    return ref.get() == null;
  }

  private record Bound(BukkitRTPPlayer cached, RTPCommandSender history, WeakReference<Player> probe) {}

  // Strong Player reference lives only in this frame, so it is unreachable once this returns.
  private static Bound bindThenDrop(UUID uuid) {
    Player player = fakePlayer(uuid, "Leaky");
    BukkitRTPPlayer cached = new BukkitRTPPlayer(player);
    RTPCommandSender history = cached.clone();
    assertSame(player, cached.player(), "bound while the server still holds the player");
    return new Bound(cached, history, new WeakReference<>(player));
  }

  @Test
  @DisplayName("Cached BukkitRTPPlayer and its clone do not pin the Player after the server drops it")
  void cachedWrapperDoesNotPinPlayer() throws Exception {
    UUID uuid = UUID.randomUUID();
    Bound bound = bindThenDrop(uuid);
    BukkitRTPPlayer cached = bound.cached();
    RTPCommandSender history = bound.history();
    WeakReference<Player> probe = bound.probe();

    assertTrue(collected(probe), "Player must be collectable while its wrapper is still cached");
    assertNull(cached.player());
    assertFalse(cached.isOnline());
    assertEquals(uuid, history.uuid());
    assertEquals("Leaky", history.name());
    assertSame(cached, cached.toPureData());
    assertTrue(cached.isSelfContained());
  }

  @Test
  @DisplayName("Quit cleanup of a player without boss bars leaves the bar registry untouched")
  void clearAllDropsOnlyThatPlayersBars() {
    UUID mine = UUID.randomUUID();
    int before = PersonalBossBars.sizeForTesting();
    PersonalBossBars.clearAll(mine);
    PersonalBossBars.clear(mine, "missing");
    assertEquals(before, PersonalBossBars.sizeForTesting());
  }
}
