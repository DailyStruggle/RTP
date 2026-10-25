package io.github.dailystruggle.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServerAccessorsTest {
  private static final Logger LOGGER = Logger.getLogger("Server");

  @BeforeEach
  void clearInstalledAccessor() {
    ServerAccessors.set(null);
  }

  @AfterEach
  void clearAccessor() {
    ServerAccessors.set(null);
  }

  @Test
  void storesAccessorAndDelegatesLogging() {
    StubAccessor accessor = new StubAccessor("paper", 21);
    ServerAccessors.set(accessor);

    assertSame(accessor, ServerAccessors.get());
    ServerAccessors.log(Level.INFO, "delegated");
    assertEquals(List.of("INFO:delegated"), accessor.messages);
  }

  @Test
  void loggingFallsBackToGenericLoggerWithoutAccessor() {
    List<LogRecord> records = new ArrayList<>();
    Handler handler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {}

      @Override
      public void close() {}
    };
    Level oldLevel = LOGGER.getLevel();
    boolean oldParentHandlers = LOGGER.getUseParentHandlers();
    try {
      LOGGER.addHandler(handler);
      LOGGER.setLevel(Level.ALL);
      LOGGER.setUseParentHandlers(false);

      ServerAccessors.log(Level.WARNING, "fallback");

      assertEquals(1, records.size());
      assertEquals(Level.WARNING, records.get(0).getLevel());
      assertEquals("fallback", records.get(0).getMessage());
    } finally {
      LOGGER.removeHandler(handler);
      LOGGER.setLevel(oldLevel);
      LOGGER.setUseParentHandlers(oldParentHandlers);
    }
  }

  @Test
  @DisplayName("REQ-API-NF-002: common server API signatures expose no RTP types")
  void commonApiTypesDoNotExposeRtpTypes() {
    Class<?>[] apiTypes = {
      ServerAccessor.class,
      PlatformFamily.class,
      ServerAccessors.class,
      TaskHandle.class,
      TaskScheduler.class,
      ProgressBar.class,
      PlayerLifecycleHook.class,
      NoopPlayerLifecycleHook.class,
      DispatchingPlayerLifecycleHook.class
    };
    for (Class<?> apiType : apiTypes) {
      assertFalse(apiType.getName().startsWith("io.github.dailystruggle.rtp."));
      for (Method method : apiType.getDeclaredMethods()) {
        assertFalse(method.getReturnType().getName().startsWith("io.github.dailystruggle.rtp."));
        for (Class<?> parameterType : method.getParameterTypes()) {
          assertFalse(parameterType.getName().startsWith("io.github.dailystruggle.rtp."));
        }
      }
    }
  }

  @Test
  void defaultCompatibilityHelpersUsePlatformFamilyAndVersion() {
    StubAccessor accessor = new StubAccessor("paper", 21);

    assertEquals(PlatformFamily.BUKKIT, accessor.getPlatformFamily());
    assertTrue(accessor.isPlatformFamily(PlatformFamily.BUKKIT));
    assertTrue(accessor.isServerVersionAtLeast(21));
    assertTrue(accessor.isServerVersionAtMost(21));
    assertTrue(accessor.isCompatible(PlatformFamily.BUKKIT, 21, 21));
    assertFalse(accessor.isCompatible(PlatformFamily.FABRIC, 21, 21));
    assertFalse(new StubAccessor("paper", null).isCompatible(PlatformFamily.BUKKIT, 21, 21));
  }

  @Test
  void defaultHostPrimitivesProvideSafeFallbacks() {
    StubAccessor accessor = new StubAccessor("paper", 21);
    UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // Identity & directories
    assertNull(accessor.getServerName());
    assertEquals(new File("."), accessor.getPluginDirectory());
    assertTrue(accessor.isPrimaryThread());
    assertEquals(0L, accessor.overTime());
    assertEquals(20.0, accessor.getTPS(20));

    // Online roster, lifecycle hook, progress bars & player probes
    assertTrue(accessor.getOnlinePlayerNames().isEmpty());
    assertSame(NoopPlayerLifecycleHook.INSTANCE, accessor.getPlayerLifecycleHook());
    accessor.clearProgressBars();
    assertFalse(accessor.menuPermissionProbe(player).test("rtp.use"));
    assertTrue(accessor.menuEffectivePermissions(player).isEmpty());
    assertEquals("en_us", accessor.menuLocale(player));
    assertEquals("", accessor.menuRegionDescriptor(player));

    // Command registration & dispatch
    accessor.registerCommands(new Object(), "rtp", "wild");
    assertFalse(accessor.executeCommand(player, "rtp"));
    assertFalse(accessor.executeCommandWithCapture("rtp", line -> {}));
    var failedCapture = accessor.executeCommandWithCapture("rtp");
    assertTrue(failedCapture.isDone());
    assertTrue(failedCapture.join().isEmpty());

    ServerAccessor capturingAccessor =
        new StubAccessor("paper", 21) {
          @Override
          public boolean executeCommandWithCapture(
              String commandLine, Consumer<String> lineConsumer) {
            lineConsumer.accept("out:" + commandLine);
            return true;
          }
        };
    assertEquals(List.of("out:help"), capturingAccessor.executeCommandWithCapture("help").join());

    // Scoreboard tags & objectives
    assertTrue(accessor.getScoreboardTags(player).isEmpty());
    assertFalse(accessor.addScoreboardTag(player, "tag"));
    assertFalse(accessor.removeScoreboardTag(player, "tag"));
    assertNull(accessor.getScoreboardScore(player, "obj"));
    accessor.setScoreboardScore(player, "obj", 42);
    accessor.resetScoreboardScore(player, "obj");
    assertTrue(accessor.hasScoreboardObjective("obj").isEmpty());
    accessor.ensureScoreboardObjective("obj", "dummy");

    // Per-player world border & health
    List<String> borderCalls = new ArrayList<>();
    ServerAccessor borderAccessor =
        new StubAccessor("paper", 21) {
          @Override
          public void sendWorldBorder(
              UUID playerId,
              double centerX,
              double centerZ,
              double oldSize,
              double newSize,
              long shrinkSeconds,
              double damageAmount,
              double damageBuffer) {
            borderCalls.add(
                centerX
                    + ":"
                    + centerZ
                    + ":"
                    + oldSize
                    + ":"
                    + newSize
                    + ":"
                    + shrinkSeconds
                    + ":"
                    + damageAmount
                    + ":"
                    + damageBuffer);
          }
        };
    borderAccessor.sendWorldBorder(player, 10.0, 20.0, 100.0);
    borderAccessor.sendWorldBorder(player, 10.0, 20.0, 100.0, 50.0, 5L);
    assertEquals(
        List.of("10.0:20.0:100.0:100.0:0:0.0:0.0", "10.0:20.0:100.0:50.0:5:0.0:0.0"), borderCalls);
    accessor.sendWorldBorder(player, 0.0, 0.0, 100.0, 50.0, 5L, 0.2, 5.0);
    accessor.resetWorldBorder(player);
    accessor.damagePlayer(player, 2.0);

    // Registry & palette snapshot surface
    assertTrue(accessor.materials().isEmpty());
    assertTrue(accessor.blockTagSnapshot().isEmpty());
    accessor.rebuildBlockTagSnapshot();
    assertNull(accessor.reconcilePaletteIdentifier(null));
    assertEquals("", accessor.reconcilePaletteIdentifier("   "));
    assertEquals("STONE", accessor.reconcilePaletteIdentifier("minecraft:stone"));
    assertEquals("DIRT", accessor.reconcilePaletteIdentifier("dirt"));

    assertTrue(accessor.reconcilePaletteIdentifiers(null).isEmpty());
    assertTrue(accessor.reconcilePaletteIdentifiers(Collections.emptyList()).isEmpty());
    Set<String> reconciled =
        accessor.reconcilePaletteIdentifiers(Arrays.asList(null, "  ", "minecraft:stone", "dirt"));
    assertEquals(Set.of("STONE", "DIRT"), reconciled);
    assertThrows(UnsupportedOperationException.class, () -> reconciled.add("GRASS"));

    assertFalse(accessor.matchesPaletteIdentifier("minecraft:stone", null));
    assertFalse(accessor.matchesPaletteIdentifier("minecraft:stone", Collections.emptySet()));
    assertFalse(accessor.matchesPaletteIdentifier(null, reconciled));
    assertFalse(accessor.matchesPaletteIdentifier("   ", reconciled));
    assertTrue(accessor.matchesPaletteIdentifier("minecraft:stone", reconciled));
    assertFalse(accessor.matchesPaletteIdentifier("minecraft:lava", reconciled));
  }

  @Test
  void progressBarAndLifecycleHooksFollowValueAndDispatchContracts() throws Exception {
    ProgressBar bar = new ProgressBar("&aScan", 0.75, "rtp.see");
    assertEquals("&aScan", bar.title());
    assertEquals(0.75, bar.progress());
    assertEquals("rtp.see", bar.viewerPermission());
    assertEquals(bar, new ProgressBar("&aScan", 0.75, "rtp.see"));
    assertEquals(bar.hashCode(), new ProgressBar("&aScan", 0.75, "rtp.see").hashCode());
    assertNotEquals(bar, new ProgressBar("&aScan", 0.25, "rtp.see"));
    assertEquals("", new ProgressBar(null, 0.0, null).title());
    assertTrue(bar.toString().contains("Scan"));

    NoopPlayerLifecycleHook noop = NoopPlayerLifecycleHook.INSTANCE;
    noop.onPlayerJoin(id -> {}).close();
    noop.onPlayerQuit(id -> {}).close();

    DispatchingPlayerLifecycleHook dispatching = new DispatchingPlayerLifecycleHook();
    assertFalse(dispatching.hasSubscribers());
    assertThrows(IllegalArgumentException.class, () -> dispatching.onPlayerJoin(null));
    assertThrows(IllegalArgumentException.class, () -> dispatching.onPlayerQuit(null));

    AtomicReference<UUID> joined = new AtomicReference<>();
    AtomicInteger quitCount = new AtomicInteger();
    AutoCloseable joinSub =
        dispatching.onPlayerJoin(
            id -> {
              throw new RuntimeException("boom");
            });
    dispatching.onPlayerJoin(joined::set);
    AutoCloseable quitSub = dispatching.onPlayerQuit(id -> quitCount.incrementAndGet());
    assertTrue(dispatching.hasSubscribers());

    UUID id = UUID.randomUUID();
    dispatching.fireJoin(id);
    dispatching.fireQuit(id);
    dispatching.fireJoin(null);
    dispatching.fireQuit(null);
    assertEquals(id, joined.get());
    assertEquals(1, quitCount.get());

    joinSub.close();
    quitSub.close();
    dispatching.fireQuit(id);
    assertEquals(1, quitCount.get());
  }

  private static class StubAccessor implements ServerAccessor {
    private final String platform;
    private final Integer version;
    private final List<String> messages = new ArrayList<>();

    private StubAccessor(String platform, Integer version) {
      this.platform = platform;
      this.version = version;
    }

    @Override
    public String getServerVersion() {
      return "test";
    }

    @Override
    public String getPlatform() {
      return platform;
    }

    @Override
    public Integer getServerIntVersion() {
      return version;
    }

    @Override
    public void log(Level level, String message) {
      messages.add(level.getName() + ":" + message);
    }

    @Override
    public void log(Level level, String message, Throwable throwable) {
      messages.add(level.getName() + ":" + message);
    }
  }
}
