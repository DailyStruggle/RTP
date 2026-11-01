package io.github.dailystruggle.rtp.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-115 invariant 3: {@code RTP}'s static initializer shall run before the platform installs
 * the accessor, so default-shape registration does no plugin-directory I/O at class init. The
 * former {@code RTP.serverAccessor} field write forced this; {@link RTP#ensureInitialized()}
 * makes it explicit. Each case loads {@code rtp-core} in a fresh class loader so the static
 * initializer has not yet run.
 */
@DisplayName("ADR-115 invariant 3: RTP static init runs before the accessor is installed")
class RTPStaticInitOrderTest {

  @TempDir Path pluginDir;

  @Test
  @DisplayName("ensureInitialized before install: class init writes nothing to the plugin dir")
  void initBeforeInstallDoesNoPluginDirIo() throws Exception {
    try (URLClassLoader loader = isolatedLoader()) {
      Class<?> rtp = Class.forName(RTP.class.getName(), false, loader);
      assertDoesNotThrow(() -> invokeStatic(rtp, "ensureInitialized"));
      installAccessor(loader, pluginDir.toFile());
      assertDoesNotThrow(() -> invokeStatic(rtp, "ensureInitialized"), "second call is a no-op");
    }
    assertEquals(0, countFiles(pluginDir), "static init must not touch the plugin directory");
  }

  @Test
  @DisplayName("install before init (the hazard): class init reaches the plugin dir")
  void installBeforeInitTouchesPluginDir() throws Exception {
    boolean initFailed = false;
    try (URLClassLoader loader = isolatedLoader()) {
      Class<?> rtp = Class.forName(RTP.class.getName(), false, loader);
      installAccessor(loader, pluginDir.toFile());
      try {
        invokeStatic(rtp, "ensureInitialized");
      } catch (InvocationTargetException | ExceptionInInitializerError e) {
        initFailed = true;
      }
    }
    assertTrue(initFailed || countFiles(pluginDir) > 0,
        "reversed order must be observable, or the guard above proves nothing");
  }

  @Test
  @DisplayName("static init fills FactoryRegistry; selection shares its shape factory")
  void staticInitPopulatesFactoryRegistry() throws Exception {
    try (URLClassLoader loader = isolatedLoader()) {
      Class<?> names =
          Class.forName("io.github.dailystruggle.rtp.common.factory.FactoryNames", true, loader);
      Class<?> registry =
          Class.forName("io.github.dailystruggle.rtp.common.factory.FactoryRegistry", true, loader);
      Method get = registry.getMethod("get", names);
      Object[] keys = names.getEnumConstants();
      for (Object k : keys) {
        assertNull(get.invoke(null, k), "registry starts empty: " + k);
      }
      Class<?> rtp = Class.forName(RTP.class.getName(), false, loader);
      invokeStatic(rtp, "ensureInitialized");
      for (Object k : keys) {
        assertNotNull(get.invoke(null, k), "registered during static init: " + k);
      }
      Object shapeFactory = get.invoke(null, keys[0]);
      Object selection = Class.forName(
          "io.github.dailystruggle.rtp.common.selection.ActiveSelection", true, loader)
          .getMethod("get").invoke(null);
      assertSame(shapeFactory, selection.getClass().getMethod("getShapeFactory").invoke(selection));
      assertEquals(Boolean.TRUE,
          shapeFactory.getClass().getMethod("contains", String.class).invoke(shapeFactory, "SQUARE"));
    }
  }

  @Test
  @DisplayName("holders keep the former RTP static defaults before and after RTP static init")
  void holdersKeepFormerRtpDefaults() throws Exception {
    try (URLClassLoader loader = isolatedLoader()) {
      String p = "io.github.dailystruggle.rtp.common.";
      Class<?> hooks = Class.forName(p + "network.NetworkHooks", true, loader);
      Class<?> actions = Class.forName(p + "action.ActionServices", true, loader);
      Class<?> metrics = Class.forName(p + "metrics.CoreMetrics", true, loader);
      Class<?> triggers = Class.forName(p + "trigger.PhysicalTriggerManager", true, loader);
      Class<?> selection = Class.forName(p + "selection.ActiveSelection", true, loader);
      Object localOnly = Class.forName(
          "io.github.dailystruggle.rtp.api.network.NetworkCommandHook", true, loader)
          .getField("LOCAL_ONLY").get(null);
      for (int pass = 0; pass < 2; pass++) {
        String when = pass == 0 ? "before RTP init: " : "after RTP init: ";
        assertSame(localOnly, hooks.getMethod("commandHook").invoke(null), when + "commandHook");
        assertNull(hooks.getMethod("bridgeFactory").invoke(null), when + "bridgeFactory");
        assertNull(hooks.getMethod("backendStateSamplerFactory").invoke(null),
            when + "backendStateSamplerFactory");
        assertEquals(Boolean.FALSE, hooks.getMethod("lobbyMode").invoke(null), when + "lobbyMode");
        assertNull(actions.getMethod("actionManager").invoke(null), when + "actionManager");
        assertNull(actions.getMethod("cacheWarmTask").invoke(null), when + "cacheWarmTask");
        assertNotNull(selection.getMethod("get").invoke(null), when + "selection");
        assertSame(metrics.getMethod("instance").invoke(null),
            metrics.getMethod("instance").invoke(null), when + "metrics singleton");
        assertSame(triggers.getMethod("instance").invoke(null),
            triggers.getMethod("instance").invoke(null), when + "trigger manager singleton");
        // Per-instance state: null until an RTP instance is constructed, as getInstance() was.
        assertNull(Class.forName(p + "playerData.TeleportSessions", true, loader)
            .getMethod("current").invoke(null), when + "sessions");
        assertNull(Class.forName(p + "tasks.scan.ScanTasks", true, loader)
            .getMethod("current").invoke(null), when + "scan tasks");
        Class<?> db = Class.forName(p + "database.ActiveDatabase", true, loader);
        assertNull(db.getMethod("accessor").invoke(null), when + "database accessor");
        assertNull(db.getMethod("teleportLimitStore").invoke(null), when + "limit store");
        Class<?> misc = Class.forName(p + "tasks.tick.MiscTasks", true, loader);
        for (String pipe : new String[] {"sync", "async", "startup", "cancel", "chunksToUnload"}) {
          assertNull(misc.getMethod(pipe).invoke(null), when + pipe);
        }
        if (pass == 0) {
          invokeStatic(Class.forName(RTP.class.getName(), false, loader), "ensureInitialized");
        }
      }
    }
  }

  private static void installAccessor(ClassLoader loader, File dir) throws Exception {
    Class<?> accessorType =
        Class.forName("io.github.dailystruggle.rtp.api.server.RTPServerAccessor", false, loader);
    Object accessor = Proxy.newProxyInstance(loader, new Class<?>[] {accessorType},
        (proxy, method, args) -> {
          if (method.getName().equals("getPluginDirectory")) return dir;
          Class<?> r = method.getReturnType();
          if (r == boolean.class) return false;
          if (r == int.class || r == long.class || r == short.class || r == byte.class) return 0;
          if (r == double.class || r == float.class) return 0;
          if (r == char.class) return '\0';
          return null;
        });
    Class<?> runtime =
        Class.forName("io.github.dailystruggle.rtp.common.runtime.RTPRuntime", true, loader);
    runtime.getMethod("setServerAccessor", accessorType).invoke(null, accessor);
  }

  private static void invokeStatic(Class<?> type, String name) throws Exception {
    Method m = type.getMethod(name);
    m.invoke(null);
  }

  private static long countFiles(Path dir) throws Exception {
    try (Stream<Path> s = Files.walk(dir)) {
      return s.filter(Files::isRegularFile).count();
    }
  }

  /** Loads the test classpath with no app-loader parent, so {@code RTP} is a fresh class. */
  private static URLClassLoader isolatedLoader() throws MalformedURLException {
    List<URL> urls = new ArrayList<>();
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      if (!entry.isEmpty()) urls.add(new File(entry).toURI().toURL());
    }
    return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
  }
}
