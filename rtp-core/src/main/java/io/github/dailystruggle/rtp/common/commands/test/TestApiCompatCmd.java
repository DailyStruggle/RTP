package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test api-compat} - read-only reflective probe of every
 * Bukkit/Paper/Folia API method RTP relies on; surfaces {@link NoSuchMethodError}
 * risk at test time (RUNTIME_TEST_SUITE_PLAN section 3.7). Curated list (not bytecode-scanned)
 * to stay in lock-step with adapter notes. Missing classes report as {@code skipped}
 * (expected for platform-conditional APIs); missing methods report as failures.
 * S-004 (warn + send on miss), S-005 (pure reflection), no method is invoked.
 */
public class TestApiCompatCmd extends BaseRTPCmdImpl {

  /**
   * A single (declaring class FQN, method name, parameter-type FQNs) tuple
   * that RTP relies on at runtime. Parameter types are FQNs so the probe
   * itself does not have to link against Folia/Paper API classes.
   */
  public static final class ApiProbe {
    public final String className;
    public final String methodName;
    public final List<String> paramTypeNames;
    /** Free-form hint describing where RTP calls this method. */
    public final String callSiteHint;

    public ApiProbe(
        String className, String methodName, List<String> paramTypeNames, String callSiteHint) {
      this.className = className;
      this.methodName = methodName;
      this.paramTypeNames =
          paramTypeNames == null
              ? Collections.emptyList()
              : Collections.unmodifiableList(new ArrayList<>(paramTypeNames));
      this.callSiteHint = callSiteHint;
    }

    @Override
    public String toString() {
      return className + "#" + methodName + "(" + String.join(",", paramTypeNames) + ")";
    }
  }

  /** Result buckets from a probe sweep. */
  public static final class ProbeReport {
    public final List<String> ok = new ArrayList<>();
    /** Class is not on the classpath; expected for platform-conditional probes. */
    public final List<String> skipped = new ArrayList<>();
    /** Class is present but the method signature no longer resolves. */
    public final List<String> missingMethod = new ArrayList<>();
    /** Something else blew up during resolution. */
    public final List<String> errors = new ArrayList<>();

    public int failures() {
      return missingMethod.size() + errors.size();
    }
  }

  public TestApiCompatCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "api-compat";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "reflectively probes every Bukkit/Paper/Folia method RTP calls; surfaces NoSuchMethodError risk";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    List<ApiProbe> probes = defaultProbes();
    ProbeReport report = runProbes(probes);

    String summary =
        "[RTP test/api-compat] ok="
            + report.ok.size()
            + " skipped="
            + report.skipped.size()
            + " missing-method="
            + report.missingMethod.size()
            + " errors="
            + report.errors.size();
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, summary);
    }
    if (report.failures() == 0) {
      RTP.log(Level.INFO, summary);
    } else {
      RTP.log(Level.WARNING, summary);
    }

    emitDetail(callerId, "skipped (class not on classpath)", report.skipped, Level.INFO);
    emitDetail(callerId, "MISSING method", report.missingMethod, Level.WARNING);
    emitDetail(callerId, "resolution ERROR", report.errors, Level.WARNING);
    return true;
  }

  private void emitDetail(UUID callerId, String tag, List<String> items, Level level) {
    if (items.isEmpty()) return;
    for (String line : items) {
      String msg = "[RTP test/api-compat] " + tag + ": " + line;
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
      RTP.log(level, msg);
    }
  }

  /**
   * Runs every supplied probe against the current classloader. Visible for
   * unit testing.
   */
  public static ProbeReport runProbes(List<ApiProbe> probes) {
    ProbeReport report = new ProbeReport();
    ClassLoader cl = TestApiCompatCmd.class.getClassLoader();
    for (ApiProbe p : probes) {
      Class<?> declaring;
      try {
        declaring = Class.forName(p.className, false, cl);
      } catch (ClassNotFoundException cnfe) {
        report.skipped.add(p + " [" + p.callSiteHint + "]");
        continue;
      } catch (Throwable t) {
        report.errors.add(p + " class-load threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        continue;
      }

      Class<?>[] paramTypes;
      try {
        paramTypes = resolveParamTypes(cl, p.paramTypeNames);
      } catch (ClassNotFoundException cnfe) {
        // A parameter type is unresolvable - treat as a skipped probe,
        // since the method signature can't exist without the type either.
        report.skipped.add(p + " [param type missing: " + cnfe.getMessage() + "]");
        continue;
      }

      Method m = findMethod(declaring, p.methodName, paramTypes);
      if (m != null) {
        report.ok.add(p.toString());
      } else {
        report.missingMethod.add(p + " [" + p.callSiteHint + "]");
      }
    }
    return report;
  }

  private static Class<?>[] resolveParamTypes(ClassLoader cl, List<String> names)
      throws ClassNotFoundException {
    Class<?>[] out = new Class<?>[names.size()];
    for (int i = 0; i < names.size(); i++) {
      out[i] = resolveType(cl, names.get(i));
    }
    return out;
  }

  private static Class<?> resolveType(ClassLoader cl, String name) throws ClassNotFoundException {
    switch (name) {
      case "int":
        return int.class;
      case "long":
        return long.class;
      case "boolean":
        return boolean.class;
      case "double":
        return double.class;
      case "float":
        return float.class;
      default:
        return Class.forName(name, false, cl);
    }
  }

  /** Walks public and declared methods of the class hierarchy. */
  private static Method findMethod(Class<?> declaring, String name, Class<?>[] paramTypes) {
    try {
      return declaring.getMethod(name, paramTypes);
    } catch (NoSuchMethodException ignored) {
      // fall through to declared-method walk
    }
    Class<?> c = declaring;
    while (c != null) {
      try {
        return c.getDeclaredMethod(name, paramTypes);
      } catch (NoSuchMethodException ignored) {
        c = c.getSuperclass();
      }
    }
    return null;
  }

  /**
   * Canonical list of API methods RTP calls at runtime. Ordered by package,
   * then by call-site centrality. Keep this list in sync with
   * {@code .junie/AGENTS.md} "Already satisfied by" notes.
   */
  public static List<ApiProbe> defaultProbes() {
    List<ApiProbe> p = new ArrayList<>();

    // --- Bukkit core (all platforms) ---------------------------------
    p.add(
        new ApiProbe(
            "org.bukkit.World",
            "getChunkAtAsync",
            List.of("int", "int"),
            "LocationGenerator/Region/RegionQueueManager async chunk load"));
    p.add(
        new ApiProbe(
            "org.bukkit.Bukkit",
            "getScheduler",
            Collections.emptyList(),
            "BukkitScheduler delegate (Spigot/Paper)"));
    p.add(
        new ApiProbe(
            "org.bukkit.Bukkit",
            "getPluginManager",
            Collections.emptyList(),
            "plugin enable/disable listener hooks"));
    p.add(
        new ApiProbe(
            "org.bukkit.entity.Player",
            "teleport",
            List.of("org.bukkit.Location"),
            "fallback teleport path on platforms without teleportAsync"));

    // --- Paper (optional on Spigot) ----------------------------------
    if (RTP.serverAccessor.getPlatform().equalsIgnoreCase("Paper")
        || RTP.serverAccessor.getPlatform().equalsIgnoreCase("Folia")) {
      p.add(
          new ApiProbe(
              "org.bukkit.entity.Player",
              "teleportAsync",
              List.of("org.bukkit.Location"),
              "PaperRTPWorld/PaperPlayer async teleport"));
    }

    // --- Folia (optional on Paper/Spigot) ----------------------------
    if (RTP.serverAccessor.getPlatform().equalsIgnoreCase("Folia")) {
      p.add(
          new ApiProbe(
              "org.bukkit.Bukkit",
              "isOwnedByCurrentRegion",
              List.of("org.bukkit.Location"),
              "FoliaSchedulerImpl region-ownership shortcut"));
      p.add(
          new ApiProbe(
              "org.bukkit.Bukkit",
              "getRegionScheduler",
              Collections.emptyList(),
              "FoliaSchedulerImpl region-scheduler dispatch"));
      p.add(
          new ApiProbe(
              "org.bukkit.Bukkit",
              "getAsyncScheduler",
              Collections.emptyList(),
              "FoliaSchedulerImpl async-scheduler dispatch"));
      p.add(
          new ApiProbe(
              "org.bukkit.Bukkit",
              "getGlobalRegionScheduler",
              Collections.emptyList(),
              "FoliaSchedulerImpl global-region-scheduler dispatch"));
      p.add(
          new ApiProbe(
              "org.bukkit.entity.Entity",
              "getScheduler",
              Collections.emptyList(),
              "FoliaSchedulerImpl entity-scheduler dispatch"));
    }

    return p;
  }
}
