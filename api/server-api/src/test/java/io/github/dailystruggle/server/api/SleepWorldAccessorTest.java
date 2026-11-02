package io.github.dailystruggle.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SleepWorldAccessor}, {@link PlayerSleepSnapshot}, {@link WeatherState}, and
 * sleep event hooks on {@link DispatchingWorldEventHook} and {@link NoopWorldEventHook}.
 */
class SleepWorldAccessorTest {

  private static final ServerAccessor MINIMAL_ACCESSOR =
      new ServerAccessor() {
        @Override
        public String getServerVersion() {
          return "1.21.4";
        }

        @Override
        public String getPlatform() {
          return "paper";
        }

        @Override
        public Integer getServerIntVersion() {
          return 21;
        }

        @Override
        public void log(Level level, String message) {}

        @Override
        public void log(Level level, String message, Throwable throwable) {}
      };

  @Test
  @DisplayName("ServerAccessor provides safe no-op defaults for SleepWorldAccessor")
  void sleepWorldAccessorDefaultsAreNoop() throws Exception {
    assertEquals(0L, MINIMAL_ACCESSOR.getWorldTime("world"));
    assertEquals(0L, MINIMAL_ACCESSOR.getWorldFullTime("world"));
    MINIMAL_ACCESSOR.setWorldTime("world", 1000L);
    assertEquals(WeatherState.CLEAR, MINIMAL_ACCESSOR.getWeather("world"));
    MINIMAL_ACCESSOR.setWeather("world", WeatherState.RAIN, 600);
    assertTrue(MINIMAL_ACCESSOR.getWorldPlayers("world").isEmpty());
    assertEquals(20, MINIMAL_ACCESSOR.getMaxPlayers());
    MINIMAL_ACCESSOR.wakePlayer(UUID.randomUUID(), true);
    MINIMAL_ACCESSOR.resetTimeSinceRest(UUID.randomUUID());
    AutoCloseable sub = MINIMAL_ACCESSOR.registerMessagingChannel("leafsleep:sync", (id, b) -> {});
    assertNotNull(sub);
    sub.close();
    assertFalse(MINIMAL_ACCESSOR.sendPluginMessage("leafsleep:sync", new byte[] {1, 2}));
    UUID p = UUID.randomUUID();
    MINIMAL_ACCESSOR.sendActionBar(p, "&eSkipping night in 3s");
    MINIMAL_ACCESSOR.showBossBar(p, "leafsleep-warmup-overworld", "&aSkipping night", 0.5);
    MINIMAL_ACCESSOR.clearBossBar(p, "leafsleep-warmup-overworld");
  }

  @Test
  @DisplayName("PlayerSleepSnapshot normalizes fields and evaluates permissions")
  void playerSleepSnapshotNormalization() {
    UUID id = UUID.randomUUID();
    PlayerSleepSnapshot snap =
        new PlayerSleepSnapshot(
            id, null, true, 40, false, null, false, 12000, Set.of("leafsleep.sleep"));
    assertEquals(id, snap.playerId());
    assertEquals("", snap.playerName());
    assertEquals("SURVIVAL", snap.gameMode());
    assertTrue(snap.hasPermission("leafsleep.sleep"));
    assertFalse(snap.hasPermission("leafsleep.admin"));
  }

  @Test
  @DisplayName("DispatchingWorldEventHook dispatches bed enter, bed leave, and time skip events")
  void dispatchingWorldEventHookSleepEvents() throws Exception {
    DispatchingWorldEventHook hook = new DispatchingWorldEventHook();
    assertFalse(hook.hasSubscribers());

    AtomicInteger enters = new AtomicInteger();
    AtomicInteger leaves = new AtomicInteger();
    AtomicInteger skips = new AtomicInteger();

    AutoCloseable c1 =
        hook.onPlayerBedEnter(
            ctx -> {
              enters.incrementAndGet();
              ctx.cancel();
            });
    AutoCloseable c2 = hook.onPlayerBedLeave(ctx -> leaves.incrementAndGet());
    AutoCloseable c3 = hook.onWorldTimeSkip(ctx -> skips.incrementAndGet());
    assertTrue(hook.hasSubscribers());

    PlayerBedEnterContext enterCtx =
        new PlayerBedEnterContext(UUID.randomUUID(), "world", 10, 64, 10);
    assertTrue(enterCtx.isOk());
    hook.firePlayerBedEnter(enterCtx);
    assertEquals(1, enters.get());
    assertTrue(enterCtx.isCancelled());

    PlayerBedLeaveContext leaveCtx =
        new PlayerBedLeaveContext(UUID.randomUUID(), "world", 10, 64, 10);
    hook.firePlayerBedLeave(leaveCtx);
    assertEquals(1, leaves.get());

    WorldTimeSkipContext skipCtx = new WorldTimeSkipContext("world", 11000L, 0L);
    assertTrue(skipCtx.isNightSkip());
    hook.fireWorldTimeSkip(skipCtx);
    assertEquals(1, skips.get());

    c1.close();
    c2.close();
    c3.close();
    assertFalse(hook.hasSubscribers());

    assertThrows(
        IllegalArgumentException.class, () -> NoopWorldEventHook.INSTANCE.onPlayerBedEnter(null));
    assertThrows(
        IllegalArgumentException.class, () -> NoopWorldEventHook.INSTANCE.onPlayerBedLeave(null));
    assertThrows(
        IllegalArgumentException.class, () -> NoopWorldEventHook.INSTANCE.onWorldTimeSkip(null));
  }

  @Test
  @DisplayName("AbstractModServerAccessor evaluates modded permissions, vanish/AFK tags, idle timeout, and sleep-ignore tags")
  void abstractModServerAccessorEvaluatesPermissionsVanishAfkAndSleepIgnored() throws Exception {
    java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("mod-accessor-test");
    try {
      AbstractModServerAccessor modAccessor =
          new AbstractModServerAccessor("TestMod", tempDir) {
            @Override
            public String getServerVersion() {
              return "26.1.2";
            }

            @Override
            public String getPlatform() {
              return "fabric";
            }

            @Override
            public void registerCommands(Object rootCommand, String... aliases) {}

            @Override
            public void sendMessage(UUID playerId, String message, boolean error) {}
          };

      UUID opId = UUID.randomUUID();
      UUID regularId = UUID.randomUUID();
      java.io.File opsFile = tempDir.resolve("ops.json").toFile();
      java.nio.file.Files.writeString(
          opsFile.toPath(),
          "[{\"uuid\":\"" + opId + "\",\"name\":\"AdminOp\",\"level\":4}]",
          java.nio.charset.StandardCharsets.UTF_8);

      assertTrue(modAccessor.hasModPermission(opId, "leafsleep.reload", opsFile));
      assertFalse(modAccessor.hasModPermission(regularId, "leafsleep.reload", opsFile));
      assertTrue(modAccessor.hasModPermission(regularId, "leafsleep.sleep", opsFile));
      assertFalse(modAccessor.hasModPermission(opId, "leafsleep.bypass", opsFile));

      Set<String> regularPerms = modAccessor.resolveModPermissionSet(regularId, opsFile);
      assertTrue(regularPerms.contains("leafsleep.sleep"));
      assertFalse(regularPerms.contains("leafsleep.reload"));
      assertFalse(regularPerms.contains("leafsleep.bypass"));

      assertTrue(modAccessor.isModPlayerSleepIgnored(Set.of("sleep_ignored")));
      assertFalse(modAccessor.isModPlayerSleepIgnored(Set.of("builder")));

      assertTrue(modAccessor.isModPlayerVanishedOrAfk(null, Set.of("afk"), 0L, 0));
      assertTrue(modAccessor.isModPlayerVanishedOrAfk(null, Set.of("vanished"), 0L, 0));
      assertTrue(modAccessor.isModPlayerVanishedOrAfk(null, Set.of(), 310_000L, 5));
      assertFalse(modAccessor.isModPlayerVanishedOrAfk(null, Set.of(), 120_000L, 5));
      assertTrue(modAccessor.readModEntityTags(new Object()).isEmpty());
      try (AutoCloseable sub =
          modAccessor.registerMessagingChannel(
              "test:err",
              (id, bytes) -> {
                throw new IllegalStateException("expected");
              })) {
        assertTrue(modAccessor.sendPluginMessage("test:err", new byte[] {1}));
      }
    } finally {
      java.nio.file.Files.walk(tempDir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p -> p.toFile().delete());
    }
  }
}
