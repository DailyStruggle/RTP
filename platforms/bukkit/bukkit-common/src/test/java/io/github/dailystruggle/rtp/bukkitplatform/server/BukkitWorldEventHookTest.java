package io.github.dailystruggle.rtp.bukkitplatform.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import io.github.dailystruggle.server.api.WorldTimeSkipContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.world.TimeSkipEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link BukkitWorldEventHook} cross-version {@link TimeSkipEvent} dispatch across legacy
 * Spigot/Paper ({@code TimeSkipEvent$SkipReason}) and Paper 26.x ({@code
 * ClockTimeSkipEvent$SkipReason}) descriptor boundaries (ADR-049, ADR-115).
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
@DisplayName("BukkitWorldEventHook TimeSkipEvent cross-version compatibility tests")
class BukkitWorldEventHookTest {

  private ServerMock server;
  private WorldMock world;

  public enum SimulatedClockSkipReason {
    COMMAND,
    CUSTOM,
    NIGHT_SKIP
  }

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    world = server.addSimpleWorld("world");
    world.setTime(6000L);
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  @DisplayName("onWorldTimeSkip dispatches legacy Spigot TimeSkipEvent and defaults null reason")
  void timeSkipHandlesLegacySpigotEnumAndNullReason() {
    BukkitWorldEventHook hook = new BukkitWorldEventHook();
    AtomicReference<WorldTimeSkipContext> captured = new AtomicReference<>();
    hook.onWorldTimeSkip(
        ctx -> {
          captured.set(ctx);
          ctx.cancel();
        });

    TimeSkipEvent commandSkip =
        new TimeSkipEvent(world, TimeSkipEvent.SkipReason.COMMAND, 1000L);
    hook.onWorldTimeSkip(commandSkip);

    WorldTimeSkipContext ctx = captured.get();
    assertNotNull(ctx);
    assertEquals("world", ctx.worldName());
    assertEquals("COMMAND", ctx.skipReason());
    assertFalse(ctx.isNightSkip());
    assertEquals(1000L, ctx.skipAmountTicks());
    assertEquals(7000L, ctx.targetTimeTicks());
    assertTrue(commandSkip.isCancelled());

    captured.set(null);
    TimeSkipEvent nullReasonSkip = new TimeSkipEvent(world, null, 2000L);
    hook.onWorldTimeSkip(nullReasonSkip);

    WorldTimeSkipContext nullReasonCtx = captured.get();
    assertNotNull(nullReasonCtx);
    assertEquals("NIGHT_SKIP", nullReasonCtx.skipReason());
    assertTrue(nullReasonCtx.isNightSkip());
  }

  @Test
  @DisplayName(
      "onWorldTimeSkip survives Paper 26.x TimeSkipEvent#getSkipReason descriptor shift without NoSuchMethodError")
  void timeSkipResolvesSkipReasonAcrossPaper26DescriptorChange() throws Exception {
    byte[] hookBytes = readClassBytes(BukkitWorldEventHook.class);
    byte[] rawEventBytes = readClassBytes(TimeSkipEvent.class);
    byte[] shiftedEventBytes =
        replaceConstantPoolUtf8(
            rawEventBytes,
            "org/bukkit/event/world/TimeSkipEvent$SkipReason",
            SimulatedClockSkipReason.class.getName().replace('.', '/'));

    ClassLoader paper26Loader =
        new ClassLoader(BukkitWorldEventHookTest.class.getClassLoader()) {
          @Override
          protected Class<?> loadClass(String name, boolean resolve)
              throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
              Class<?> loaded = findLoadedClass(name);
              if (loaded == null) {
                if ("org.bukkit.event.world.TimeSkipEvent$SkipReason".equals(name)) {
                  throw new ClassNotFoundException(
                      "Removed in Paper 26.x (moved to ClockTimeSkipEvent$SkipReason): " + name);
                }
                if (TimeSkipEvent.class.getName().equals(name)) {
                  loaded = defineClass(name, shiftedEventBytes, 0, shiftedEventBytes.length);
                } else if (BukkitWorldEventHook.class.getName().equals(name)) {
                  loaded = defineClass(name, hookBytes, 0, hookBytes.length);
                } else {
                  loaded = super.loadClass(name, false);
                }
              }
              if (resolve) {
                resolveClass(loaded);
              }
              return loaded;
            }
          }
        };

    Class<?> shiftedEventClass =
        Class.forName(TimeSkipEvent.class.getName(), true, paper26Loader);
    Class<?> isolatedHookClass =
        Class.forName(BukkitWorldEventHook.class.getName(), true, paper26Loader);

    Object hookInstance = isolatedHookClass.getDeclaredConstructor().newInstance();
    AtomicReference<WorldTimeSkipContext> captured = new AtomicReference<>();
    Consumer<WorldTimeSkipContext> subscriber =
        ctx -> {
          captured.set(ctx);
          ctx.cancel();
        };
    Method subscribeMethod =
        isolatedHookClass.getMethod("onWorldTimeSkip", Consumer.class);
    subscribeMethod.invoke(hookInstance, subscriber);

    Constructor<?> eventCtor =
        shiftedEventClass.getConstructor(
            World.class, SimulatedClockSkipReason.class, long.class);
    Object shiftedEvent =
        eventCtor.newInstance(world, SimulatedClockSkipReason.COMMAND, 18000L);

    Method handlerMethod =
        isolatedHookClass.getMethod("onWorldTimeSkip", shiftedEventClass);
    handlerMethod.invoke(hookInstance, shiftedEvent);

    WorldTimeSkipContext ctx = captured.get();
    assertNotNull(ctx);
    assertEquals("world", ctx.worldName());
    assertEquals("COMMAND", ctx.skipReason());
    assertEquals(18000L, ctx.skipAmountTicks());
    assertEquals(0L, ctx.targetTimeTicks());

    Method isCancelled = shiftedEventClass.getMethod("isCancelled");
    assertEquals(Boolean.TRUE, isCancelled.invoke(shiftedEvent));
  }

  @Test
  @DisplayName("readKeyedOrName and readSkipReason handle Enum, Keyed, and fallback values")
  void readKeyedOrNameExtractsKeyAndString() {
    Keyed keyed = () -> NamespacedKey.minecraft("oak_sapling");
    assertEquals("minecraft:oak_sapling", BukkitWorldEventHook.readKeyedOrName(keyed));
    assertEquals("COMMAND", BukkitWorldEventHook.readKeyedOrName(SimulatedClockSkipReason.COMMAND));
    assertEquals("", BukkitWorldEventHook.readKeyedOrName(null));

    assertEquals("NIGHT_SKIP", BukkitWorldEventHook.readSkipReason(null));
    assertEquals("NIGHT_SKIP", BukkitWorldEventHook.readSkipReason(new Object()));
    Object keyedReasonEvent =
        new Object() {
          @SuppressWarnings("unused")
          public Keyed getSkipReason() {
            return () -> NamespacedKey.minecraft("day_skip");
          }
        };
    assertEquals("minecraft:day_skip", BukkitWorldEventHook.readSkipReason(keyedReasonEvent));
  }

  private static byte[] readClassBytes(Class<?> clazz) throws IOException {
    String resourcePath = "/" + clazz.getName().replace('.', '/') + ".class";
    try (InputStream in = clazz.getResourceAsStream(resourcePath)) {
      assertNotNull(in, "Missing class resource: " + resourcePath);
      return in.readAllBytes();
    }
  }

  private static byte[] replaceConstantPoolUtf8(byte[] classBytes, String target, String replacement)
      throws IOException {
    byte[] targetBytes = target.getBytes(StandardCharsets.UTF_8);
    byte[] pattern = new byte[3 + targetBytes.length];
    pattern[0] = 1; // CONSTANT_Utf8 tag
    pattern[1] = (byte) ((targetBytes.length >>> 8) & 0xFF);
    pattern[2] = (byte) (targetBytes.length & 0xFF);
    System.arraycopy(targetBytes, 0, pattern, 3, targetBytes.length);

    byte[] replacementBytes = replacement.getBytes(StandardCharsets.UTF_8);
    byte[] repl = new byte[3 + replacementBytes.length];
    repl[0] = 1;
    repl[1] = (byte) ((replacementBytes.length >>> 8) & 0xFF);
    repl[2] = (byte) (replacementBytes.length & 0xFF);
    System.arraycopy(replacementBytes, 0, repl, 3, replacementBytes.length);

    byte[] afterDirect = replaceAllBytes(classBytes, pattern, repl);
    String descTarget = "L" + target + ";";
    String descRepl = "L" + replacement + ";";
    byte[] descTargetBytes = descTarget.getBytes(StandardCharsets.UTF_8);
    byte[] descReplBytes = descRepl.getBytes(StandardCharsets.UTF_8);
    return replaceUtf8DescriptorSubstring(afterDirect, descTargetBytes, descReplBytes);
  }

  private static byte[] replaceAllBytes(byte[] src, byte[] pattern, byte[] repl)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(src.length);
    int i = 0;
    while (i < src.length) {
      if (i + pattern.length <= src.length && matchesAt(src, i, pattern)) {
        out.write(repl);
        i += pattern.length;
      } else {
        out.write(src[i]);
        i++;
      }
    }
    return out.toByteArray();
  }

  private static byte[] replaceUtf8DescriptorSubstring(
      byte[] src, byte[] subTarget, byte[] subRepl) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(src.length);
    int i = 0;
    while (i < src.length) {
      if (src[i] == 1 && i + 3 <= src.length) {
        int len = ((src[i + 1] & 0xFF) << 8) | (src[i + 2] & 0xFF);
        if (len > 0 && i + 3 + len <= src.length) {
          byte[] payload = new byte[len];
          System.arraycopy(src, i + 3, payload, 0, len);
          if (containsSubarray(payload, subTarget) && isProbableDescriptor(payload)) {
            byte[] rewritten = replaceAllBytes(payload, subTarget, subRepl);
            out.write(1);
            out.write((rewritten.length >>> 8) & 0xFF);
            out.write(rewritten.length & 0xFF);
            out.write(rewritten);
            i += 3 + len;
            continue;
          }
        }
      }
      out.write(src[i]);
      i++;
    }
    return out.toByteArray();
  }

  private static boolean isProbableDescriptor(byte[] payload) {
    for (byte b : payload) {
      if (b < 0x20 || b > 0x7E) return false;
    }
    return payload[0] == '(' || payload[0] == 'L';
  }

  private static boolean containsSubarray(byte[] haystack, byte[] needle) {
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      if (matchesAt(haystack, i, needle)) return true;
    }
    return false;
  }

  private static boolean matchesAt(byte[] src, int offset, byte[] pattern) {
    for (int j = 0; j < pattern.length; j++) {
      if (src[offset + j] != pattern[j]) return false;
    }
    return true;
  }
}
