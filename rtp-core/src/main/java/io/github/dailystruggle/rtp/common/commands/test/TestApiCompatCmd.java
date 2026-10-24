package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.server.api.PlatformFamily;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test api-compat} - read-only reflective probe of every
 * Bukkit/Paper/Folia API method RTP relies on; surfaces {@link NoSuchMethodError}
 * risk at test time (ADR-121 section 11). Curated list (not bytecode-scanned)
 * to stay in lock-step with adapter notes. Missing classes report as {@code skipped}
 * (expected for platform-conditional APIs); missing required methods report as failures.
 * S-004 (warn + send on miss), S-005 (pure reflection), no method is invoked.
 */
public class TestApiCompatCmd extends BaseRTPCmdImpl {

  public static final BitCode MISSING_METHOD =
      RtpBitCodes.TEST.register(
          4001,
          "TEST_API_COMPAT_MISSING_METHOD",
          java.lang.System.Logger.Level.ERROR,
          BitTier.IBIT,
          "test.api-compat",
          "",
          "Check the platform method signature required by the runtime adapter.");
  public static final BitCode RESOLUTION_ERROR =
      RtpBitCodes.TEST.register(
          4002,
          "TEST_API_COMPAT_RESOLUTION_ERROR",
          java.lang.System.Logger.Level.ERROR,
          BitTier.IBIT,
          "test.api-compat",
          "",
          "Resolve the reported class-loading or reflection error for this platform.");
  public static final BitCode MISSING_OPTIONAL_METHOD =
      RtpBitCodes.TEST.register(
          4003,
          "TEST_API_COMPAT_OPTIONAL_METHOD_MISSING",
          java.lang.System.Logger.Level.WARNING,
          BitTier.IBIT,
          "test.api-compat",
          "",
          "Pre-generate destination worlds or use Paper/Folia for asynchronous chunk loading.");
  public static final BitComponent COMPONENT = registerComponent();

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
    public final boolean optional;

    public ApiProbe(
        String className, String methodName, List<String> paramTypeNames, String callSiteHint) {
      this(className, methodName, paramTypeNames, callSiteHint, false);
    }

    public ApiProbe(
        String className,
        String methodName,
        List<String> paramTypeNames,
        String callSiteHint,
        boolean optional) {
      this.className = className;
      this.methodName = methodName;
      this.paramTypeNames =
          paramTypeNames == null
              ? Collections.emptyList()
              : Collections.unmodifiableList(new ArrayList<>(paramTypeNames));
      this.callSiteHint = callSiteHint;
      this.optional = optional;
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
    /** Optional method is absent on a platform that has a safe fallback. */
    public final List<String> missingOptional = new ArrayList<>();
    /** Something else blew up during resolution. */
    public final List<String> errors = new ArrayList<>();

    public int failures() {
      return missingMethod.size() + errors.size();
    }
  }

  public TestApiCompatCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter("json", new BooleanParameter("rtp.test", "emit the BIT report as JSON", (uuid, value) -> true));
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
  @SuppressWarnings("java:S3516") // Diagnostic command returns true on completion
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    List<ApiProbe> probes = defaultProbes();
    ProbeReport report = runProbes(probes);
    BitResult bitResult =
        RtpBit.RUNNER.run(COMPONENT.withCheck(() -> toBitReport(report)), Runnable::run);

    String summary =
        "[RTP test/api-compat] ok="
            + report.ok.size()
            + " skipped="
            + report.skipped.size()
            + " missing-method="
            + report.missingMethod.size()
            + " missing-optional="
            + report.missingOptional.size()
            + " errors="
            + report.errors.size();
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTPRuntime.serverAccessor().sendMessage(callerId, summary);
    }
    if (report.failures() == 0 && report.missingOptional.isEmpty()) {
      RTPRuntime.log(Level.INFO, summary);
    } else {
      RTPRuntime.log(Level.WARNING, summary);
    }

    emitDetail(callerId, "skipped (class not on classpath)", report.skipped, Level.INFO);
    emitDetail(callerId, "MISSING method", report.missingMethod, Level.WARNING);
    emitDetail(callerId, "OPTIONAL method missing", report.missingOptional, Level.WARNING);
    emitDetail(callerId, "resolution ERROR", report.errors, Level.WARNING);
    ProbeBitReport.emit(callerId, parameterValues, COMPONENT, bitResult);
    return true;
  }

  private static BitComponent registerComponent() {
    return new BitComponent(
            "api-compat",
            "test.api-compat",
            BitTier.IBIT,
            BitSideEffect.SAFE,
            60_000,
            () -> toBitReport(runProbes(defaultProbes())));
  }

  static BitReport toBitReport(ProbeReport probe) {
    ProbeBitReport.Builder report = ProbeBitReport.forProbe("api-compat");
    for (String item : probe.missingMethod) {
      report.check(MISSING_METHOD, false, item, "Required platform API method could not be resolved.");
    }
    for (String item : probe.missingOptional) {
      report.check(MISSING_OPTIONAL_METHOD, false, item,
          "Optional platform API method is absent; the safe fallback remains active.");
    }
    for (String item : probe.errors) {
      report.check(RESOLUTION_ERROR, false, item, "Reflective platform API resolution failed.");
    }
    return report.build();
  }

  private void emitDetail(UUID callerId, String tag, List<String> items, Level level) {
    if (items.isEmpty()) return;
    for (String line : items) {
      String msg = "[RTP test/api-compat] " + tag + ": " + line;
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTPRuntime.serverAccessor().sendMessage(callerId, msg);
      }
      RTPRuntime.log(level, msg);
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
      if (m != null || (paramTypes.length == 0 && hasField(declaring, p.methodName))) {
        report.ok.add(p.toString());
      } else {
        List<String> missing = p.optional ? report.missingOptional : report.missingMethod;
        missing.add(p + " [" + p.callSiteHint + "]");
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
      case "byte":
        return byte.class;
      case "short":
        return short.class;
      case "char":
        return char.class;
      default:
        return Class.forName(name, false, cl);
    }
  }

  /** Walks public and declared methods of the class and interface hierarchy. */
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
    for (Class<?> iface : declaring.getInterfaces()) {
      try {
        return iface.getDeclaredMethod(name, paramTypes);
      } catch (NoSuchMethodException ignored) {
        // continue
      }
    }
    return null;
  }

  private static boolean hasField(Class<?> declaring, String name) {
    try {
      Field f = declaring.getField(name);
      return f != null;
    } catch (NoSuchFieldException ignored) {
      // fall through to declared-field walk
    }
    Class<?> c = declaring;
    while (c != null) {
      try {
        Field f = c.getDeclaredField(name);
        return f != null;
      } catch (NoSuchFieldException ignored) {
        c = c.getSuperclass();
      }
    }
    return false;
  }

  private static final Map<String, Supplier<List<ApiProbe>>> PLATFORM_PROBE_SUPPLIERS =
      new ConcurrentHashMap<>();
  private static volatile @Nullable Supplier<List<ApiProbe>> overrideProbeSupplier;

  /**
   * Registers a platform-specific {@link ApiProbe} supplier keyed by case-insensitive platform
   * name (e.g. {@code "Spigot"}, {@code "Paper"}, {@code "Folia"}, {@code "fabric"},
   * {@code "neoforge"}).
   */
  public static void registerProbeSupplier(String platform, Supplier<List<ApiProbe>> supplier) {
    if (platform == null || platform.isBlank()) return;
    if (supplier == null) {
      PLATFORM_PROBE_SUPPLIERS.remove(platform.trim().toLowerCase(Locale.ROOT));
    } else {
      PLATFORM_PROBE_SUPPLIERS.put(platform.trim().toLowerCase(Locale.ROOT), supplier);
    }
  }

  /**
   * Registers or clears a global override {@link ApiProbe} supplier. Passing {@code null} clears
   * the override so platform-family resolution takes effect.
   */
  public static void registerProbeSupplier(@Nullable Supplier<List<ApiProbe>> supplier) {
    overrideProbeSupplier = supplier;
  }

  /** Clears all custom probe suppliers (for test isolation). */
  public static void clearProbeSuppliers() {
    overrideProbeSupplier = null;
    PLATFORM_PROBE_SUPPLIERS.clear();
  }

  /**
   * Canonical list of API methods RTP calls at runtime for the active server platform.
   */
  public static List<ApiProbe> defaultProbes() {
    Supplier<List<ApiProbe>> override = overrideProbeSupplier;
    if (override != null) {
      List<ApiProbe> supplied = override.get();
      if (supplied != null && !supplied.isEmpty()) {
        return supplied;
      }
    }
    String platform =
        RTPRuntime.serverAccessor() != null ? RTPRuntime.serverAccessor().getPlatform() : "Spigot";
    if (platform != null) {
      Supplier<List<ApiProbe>> registered =
          PLATFORM_PROBE_SUPPLIERS.get(platform.trim().toLowerCase(Locale.ROOT));
      if (registered != null) {
        List<ApiProbe> supplied = registered.get();
        if (supplied != null && !supplied.isEmpty()) {
          return supplied;
        }
      }
    }
    return probesForPlatform(platform);
  }

  /**
   * Resolves the curated {@link ApiProbe} suite for the given platform identifier across all 5
   * supported server families (Bukkit/Spigot, Paper, Folia, Fabric, NeoForge).
   */
  public static List<ApiProbe> probesForPlatform(@Nullable String platform) {
    String norm = platform == null ? "" : platform.trim();
    if (norm.equalsIgnoreCase("fabric")) {
      return fabricProbes();
    }
    if (norm.equalsIgnoreCase("neoforge")) {
      return neoForgeProbes();
    }
    if (RTPRuntime.serverAccessor() != null && (norm.isEmpty() || norm.equalsIgnoreCase("mock"))) {
      try {
        PlatformFamily family = RTPRuntime.serverAccessor().getPlatformFamily();
        if (family == PlatformFamily.FABRIC) return fabricProbes();
        if (family == PlatformFamily.NEOFORGE) return neoForgeProbes();
      } catch (RuntimeException ignored) {
        // fall through to Bukkit baseline
      }
    }
    return bukkitProbes(norm);
  }

  /**
   * Curated probe suite for Bukkit, Paper, and Folia platforms.
   */
  public static List<ApiProbe> bukkitProbes(@Nullable String platform) {
    List<ApiProbe> p = new ArrayList<>();
    String norm = platform == null ? "" : platform.trim();
    boolean isFolia = norm.equalsIgnoreCase("Folia");
    boolean paperOrFolia = isFolia || norm.equalsIgnoreCase("Paper");

    // --- Core chunk & ticket APIs -------------------------------------
    p.add(
        new ApiProbe(
            "org.bukkit.World",
            "getChunkAtAsync",
            List.of("int", "int"),
            "LocationGenerator/Region/RegionQueueManager async chunk load",
            !paperOrFolia));
    p.add(
        new ApiProbe(
            "org.bukkit.World",
            "addPluginChunkTicket",
            List.of("int", "int", "org.bukkit.plugin.Plugin"),
            "BukkitRTPWorld/FoliaRTPWorld non-persistent chunk ticket acquire (S-002)"));
    p.add(
        new ApiProbe(
            "org.bukkit.World",
            "removePluginChunkTicket",
            List.of("int", "int", "org.bukkit.plugin.Plugin"),
            "BukkitRTPWorld/FoliaRTPWorld non-persistent chunk ticket release (S-002)"));
    p.add(
        new ApiProbe(
            "org.bukkit.World",
            "getMinHeight",
            Collections.emptyList(),
            "BukkitRTPWorld/FoliaRTPWorld vertical bounds floor"));
    p.add(
        new ApiProbe(
            "org.bukkit.World",
            "getMaxHeight",
            Collections.emptyList(),
            "BukkitRTPWorld/FoliaRTPWorld vertical bounds ceiling"));
    p.add(
        new ApiProbe(
            "org.bukkit.World",
            "getWorldBorder",
            Collections.emptyList(),
            "AbstractServerAccessor native WorldBorder resolution"));

    // --- Core Bukkit & Entity / Teleport / UI surfaces ----------------
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
    p.add(
        new ApiProbe(
            "org.bukkit.entity.Entity",
            "teleportAsync",
            List.of("org.bukkit.Location"),
            "Entity#teleportAsync asynchronous entity teleportation",
            !paperOrFolia));
    p.add(
        new ApiProbe(
            "org.bukkit.entity.Player",
            "teleportAsync",
            List.of("org.bukkit.Location"),
            "PaperRTPWorld/PaperPlayer async teleport",
            !paperOrFolia));
    p.add(
        new ApiProbe(
            "org.bukkit.entity.Player",
            "openBook",
            List.of("org.bukkit.inventory.ItemStack"),
            "written-book menu modal dispatch"));
    p.add(
        new ApiProbe(
            "net.kyori.adventure.audience.Audience",
            "openBook",
            List.of("net.kyori.adventure.inventory.Book"),
            "Paper Adventure book menu dispatch",
            !paperOrFolia));
    p.add(
        new ApiProbe(
            "org.bukkit.inventory.InventoryView",
            "getTopInventory",
            Collections.emptyList(),
            "InventoryView 1.21+ class-to-interface binary compatibility check"));
    p.add(
        new ApiProbe(
            "org.bukkit.inventory.InventoryView",
            "getBottomInventory",
            Collections.emptyList(),
            "InventoryView 1.21+ class-to-interface binary compatibility check"));

    // --- Registry, NamespacedKey & Scoreboard APIs --------------------
    p.add(
        new ApiProbe(
            "org.bukkit.Registry",
            "get",
            List.of("org.bukkit.NamespacedKey"),
            "Registry#get lookup for biomes and materials"));
    p.add(
        new ApiProbe(
            "org.bukkit.Registry",
            "BIOME",
            Collections.emptyList(),
            "Registry.BIOME field for biome enumeration"));
    p.add(
        new ApiProbe(
            "org.bukkit.Registry",
            "MATERIAL",
            Collections.emptyList(),
            "Registry.MATERIAL field for material enumeration"));
    p.add(
        new ApiProbe(
            "org.bukkit.NamespacedKey",
            "minecraft",
            List.of("java.lang.String"),
            "NamespacedKey#minecraft canonical key factory"));
    p.add(
        new ApiProbe(
            "org.bukkit.NamespacedKey",
            "fromString",
            List.of("java.lang.String"),
            "NamespacedKey#fromString key parser"));
    p.add(
        new ApiProbe(
            "org.bukkit.Bukkit",
            "getScoreboardManager",
            Collections.emptyList(),
            "AbstractServerAccessor scoreboard manager access"));
    p.add(
        new ApiProbe(
            "org.bukkit.scoreboard.Scoreboard",
            "getObjective",
            List.of("java.lang.String"),
            "Action/gate scoreboard objective lookup"));
    p.add(
        new ApiProbe(
            "org.bukkit.scoreboard.Scoreboard",
            "registerNewObjective",
            List.of("java.lang.String", "java.lang.String", "java.lang.String"),
            "Action/gate scoreboard objective creation"));
    p.add(
        new ApiProbe(
            "org.bukkit.scoreboard.Objective",
            "getScore",
            List.of("java.lang.String"),
            "Action/gate scoreboard score lookup"));

    // --- Folia regional threading surfaces ----------------------------
    // Required when platform == Folia, optional otherwise.
    p.add(
        new ApiProbe(
            "org.bukkit.Bukkit",
            "isOwnedByCurrentRegion",
            List.of("org.bukkit.Location"),
            "FoliaSchedulerImpl region-ownership shortcut",
            !isFolia));
    p.add(
        new ApiProbe(
            "org.bukkit.Bukkit",
            "getRegionScheduler",
            Collections.emptyList(),
            "FoliaSchedulerImpl region-scheduler dispatch",
            !isFolia));
    p.add(
        new ApiProbe(
            "org.bukkit.Bukkit",
            "getAsyncScheduler",
            Collections.emptyList(),
            "FoliaSchedulerImpl async-scheduler dispatch",
            !isFolia));
    p.add(
        new ApiProbe(
            "org.bukkit.Bukkit",
            "getGlobalRegionScheduler",
            Collections.emptyList(),
            "FoliaSchedulerImpl global-region-scheduler dispatch",
            !isFolia));
    p.add(
        new ApiProbe(
            "org.bukkit.entity.Entity",
            "getScheduler",
            Collections.emptyList(),
            "FoliaSchedulerImpl entity-scheduler dispatch",
            !isFolia));

    return p;
  }

  /**
   * Curated probe suite for Fabric servers, verifying the version-carrier bridge
   * ({@code FabricVersionAdapter} / {@code FabricVersionBridge}) and underlying
   * {@code net.minecraft.*} server, chunk, ticket, player, command, and registry surfaces.
   */
  public static List<ApiProbe> fabricProbes() {
    List<ApiProbe> p = new ArrayList<>();
    p.add(
        new ApiProbe(
            "io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry",
            "require",
            Collections.emptyList(),
            "FabricVersionBridge active version-carrier lookup"));
    p.add(
        new ApiProbe(
            "io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter",
            "requestFullChunkAsync",
            List.of(
                "io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle",
                "int",
                "int"),
            "FabricVersionBridge non-blocking async chunk generation (S-005)"));
    p.add(
        new ApiProbe(
            "io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter",
            "applyTicket",
            List.of(
                "io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle",
                "int",
                "int"),
            "FabricVersionBridge non-persistent chunk ticket acquire (S-002)"));
    p.add(
        new ApiProbe(
            "io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter",
            "releaseTicket",
            List.of(
                "io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle",
                "int",
                "int"),
            "FabricVersionBridge non-persistent chunk ticket release (S-002)"));
    p.add(
        new ApiProbe(
            "io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter",
            "teleport",
            List.of(
                "java.lang.Object",
                "java.lang.Object",
                "double",
                "double",
                "double",
                "float",
                "float"),
            "FabricVersionBridge cross-dimension player teleport"));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.ServerLevel",
            "getChunkSource",
            Collections.emptyList(),
            "ServerLevel#getChunkSource chunk cache access",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.ServerLevel",
            "getWorldBorder",
            Collections.emptyList(),
            "ServerLevel#getWorldBorder border bounds",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.ServerLevel",
            "getSeed",
            Collections.emptyList(),
            "ServerLevel#getSeed world seed access",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.ServerChunkCache",
            "getChunkFuture",
            List.of(
                "int",
                "int",
                "net.minecraft.world.level.chunk.status.ChunkStatus",
                "boolean"),
            "ServerChunkCache#getChunkFuture async chunk pipeline",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.ServerChunkCache",
            "addTicketWithRadius",
            List.of(
                "net.minecraft.server.level.TicketType",
                "net.minecraft.world.level.ChunkPos",
                "int"),
            "ServerChunkCache#addTicketWithRadius 26.x non-persistent ticket",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.ServerChunkCache",
            "removeTicketWithRadius",
            List.of(
                "net.minecraft.server.level.TicketType",
                "net.minecraft.world.level.ChunkPos",
                "int"),
            "ServerChunkCache#removeTicketWithRadius 26.x ticket release",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.TicketType",
            "FLAG_LOADING",
            Collections.emptyList(),
            "TicketType.FLAG_LOADING 26.x ticket flag constant",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.ServerPlayer",
            "teleportTo",
            List.of("double", "double", "double"),
            "ServerPlayer#teleportTo coordinate teleportation",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.commands.Commands",
            "performPrefixedCommand",
            List.of(
                "net.minecraft.commands.CommandSourceStack",
                "java.lang.String"),
            "Commands#performPrefixedCommand console/player command dispatch",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.commands.Commands",
            "getDispatcher",
            Collections.emptyList(),
            "Commands#getDispatcher Brigadier command registration",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.core.registries.BuiltInRegistries",
            "BLOCK",
            Collections.emptyList(),
            "BuiltInRegistries.BLOCK material & tag snapshot lookup",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.core.RegistryAccess",
            "lookupOrThrow",
            List.of("net.minecraft.resources.ResourceKey"),
            "RegistryAccess#lookupOrThrow biome registry lookup",
            true));
    return p;
  }

  /**
   * Curated probe suite for NeoForge servers, verifying the version-carrier bridge
   * ({@code NeoForgeVersionAdapter} / {@code NeoForgeVersionBridge}) and underlying
   * {@code net.minecraft.*} server, chunk, ticket, player, command, and registry surfaces.
   */
  public static List<ApiProbe> neoForgeProbes() {
    List<ApiProbe> p = new ArrayList<>();
    p.add(
        new ApiProbe(
            "io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapterRegistry",
            "require",
            Collections.emptyList(),
            "NeoForgeVersionBridge active version-carrier lookup"));
    p.add(
        new ApiProbe(
            "io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapter",
            "requestFullChunkAsync",
            List.of(
                "net.minecraft.server.level.ServerLevel",
                "int",
                "int"),
            "NeoForgeVersionBridge non-blocking async chunk generation (S-005)"));
    p.add(
        new ApiProbe(
            "io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapter",
            "applyTicket",
            List.of(
                "net.minecraft.server.level.ServerLevel",
                "int",
                "int"),
            "NeoForgeVersionBridge non-persistent chunk ticket acquire (S-002)"));
    p.add(
        new ApiProbe(
            "io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapter",
            "releaseTicket",
            List.of(
                "net.minecraft.server.level.ServerLevel",
                "int",
                "int"),
            "NeoForgeVersionBridge non-persistent chunk ticket release (S-002)"));
    p.add(
        new ApiProbe(
            "io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapter",
            "openBookMenu",
            List.of("java.lang.Object", "java.lang.Object"),
            "NeoForgeVersionBridge written-book modal dispatch"));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.ServerLevel",
            "getChunkSource",
            Collections.emptyList(),
            "ServerLevel#getChunkSource chunk cache access"));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.ServerLevel",
            "getWorldBorder",
            Collections.emptyList(),
            "ServerLevel#getWorldBorder border bounds"));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.ServerLevel",
            "getSeed",
            Collections.emptyList(),
            "ServerLevel#getSeed world seed access"));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.ServerChunkCache",
            "getChunkFuture",
            List.of(
                "int",
                "int",
                "net.minecraft.world.level.chunk.status.ChunkStatus",
                "boolean"),
            "ServerChunkCache#getChunkFuture async chunk pipeline",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.ServerChunkCache",
            "addTicketWithRadius",
            List.of(
                "net.minecraft.server.level.TicketType",
                "net.minecraft.world.level.ChunkPos",
                "int"),
            "ServerChunkCache#addTicketWithRadius 26.x non-persistent ticket",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.ServerChunkCache",
            "removeTicketWithRadius",
            List.of(
                "net.minecraft.server.level.TicketType",
                "net.minecraft.world.level.ChunkPos",
                "int"),
            "ServerChunkCache#removeTicketWithRadius 26.x ticket release",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.TicketType",
            "FLAG_LOADING",
            Collections.emptyList(),
            "TicketType.FLAG_LOADING 26.x ticket flag constant",
            true));
    p.add(
        new ApiProbe(
            "net.minecraft.server.level.ServerPlayer",
            "teleportTo",
            List.of("double", "double", "double"),
            "ServerPlayer#teleportTo coordinate teleportation"));
    p.add(
        new ApiProbe(
            "net.minecraft.commands.Commands",
            "performPrefixedCommand",
            List.of(
                "net.minecraft.commands.CommandSourceStack",
                "java.lang.String"),
            "Commands#performPrefixedCommand console/player command dispatch"));
    p.add(
        new ApiProbe(
            "net.minecraft.commands.Commands",
            "getDispatcher",
            Collections.emptyList(),
            "Commands#getDispatcher Brigadier command registration"));
    p.add(
        new ApiProbe(
            "net.minecraft.core.registries.BuiltInRegistries",
            "BLOCK",
            Collections.emptyList(),
            "BuiltInRegistries.BLOCK material & tag snapshot lookup"));
    p.add(
        new ApiProbe(
            "net.minecraft.core.RegistryAccess",
            "lookupOrThrow",
            List.of("net.minecraft.resources.ResourceKey"),
            "RegistryAccess#lookupOrThrow biome registry lookup"));
    return p;
  }
}
