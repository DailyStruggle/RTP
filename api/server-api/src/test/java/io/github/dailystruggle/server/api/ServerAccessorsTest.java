package io.github.dailystruggle.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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
      TaskScheduler.class
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

  private static final class StubAccessor implements ServerAccessor {
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
