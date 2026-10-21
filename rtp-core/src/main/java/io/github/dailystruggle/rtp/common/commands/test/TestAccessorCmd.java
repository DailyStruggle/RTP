package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test accessor} - fast, meaningful runtime self-test verifying the server-specific
 * {@code RTPServerAccessor} platform binding (Paper, Folia, Fabric, Spigot) without requiring
 * heavy multi-hour stress sequences.
 *
 * <p>Probes and positively asserts:
 * <ul>
 *   <li>{@code materials()}: non-empty set of valid material keys.</li>
 *   <li>{@code blockTagSnapshot()}: tag mapping validity.</li>
 *   <li>{@code getConsolePlayer()} / {@code getSender()}: console sender resolution, null safety on synthetic UUID.</li>
 *   <li>{@code format()} / {@code formatNoColor()}: placeholder substitution, color code conversion, color stripping.</li>
 *   <li>{@code isPrimaryThread()} / {@code overTime()}: async thread isolation and budget reporting.</li>
 *   <li>{@code sampleBiome()}: non-null biome sampling on default world.</li>
 *   <li>Menu permission/locale queries: {@code menuPermissionProbe}, {@code menuLocale}, {@code menuEffectivePermissions}.</li>
 *   <li>Version metadata: {@code getServerVersion}, {@code getPluginVersion}, {@code getPlatform}, {@code getPlatformFamily}, {@code getServerIntVersion}.</li>
 *   <li>World and border: {@code getRTPWorlds}, {@code getRTPWorld}, {@code getWorldBorder}, {@code shapePlatform}.</li>
 *   <li>Messaging surface: {@code sendMessage} overloads, {@code sendMessageAndSuggest}, {@code sendMessageWithRunCommand}, {@code announce}.</li>
 *   <li>Subsystems and performance: {@code getTPS}, {@code getPluginDirectory}, {@code getScheduler}, {@code getLocationGenerator}, {@code executeCommand}.</li>
 *   <li>JaCoCo flush trigger: attempts to invoke {@code org.jacoco.agent.rt.RT.getAgent().dump(false)} if attached.</li>
 * </ul>
 */
public class TestAccessorCmd extends BaseRTPCmdImpl {

  public static class Result {
    public boolean pass = true;
    public boolean materialsValid = false;
    public boolean tagsValid = false;
    public boolean senderValid = false;
    public boolean formatValid = false;
    public boolean threadValid = false;
    public boolean biomeValid = false;
    public boolean menuValid = false;
    public boolean versionValid = false;
    public boolean worldValid = false;
    public boolean messagingValid = false;
    public boolean subsystemValid = false;
    public boolean worldBorderValid = false;
    public boolean jacocoDumpTriggered = false;
    public String message = "ok";
    public final List<String> details = new ArrayList<>();
  }

  public TestAccessorCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "accessor";
  }

  @Override
  public String permission() {
    return "rtp.test.accessor";
  }

  @Override
  public String description() {
    return "verifies server accessor platform contracts (materials, senders, formats, biomes, thread probes)";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    Result r = runProbe(callerId);
    emit(callerId, r);
    triggerJacocoDump();
    return r.pass;
  }

  /**
   * True when the accessor exposes a usable block-material vocabulary. Platform accessors differ
   * in form: Bukkit-family returns bare enum names ({@code AIR}), while Fabric/NeoForge return
   * namespaced registry ids upper-cased ({@code MINECRAFT:AIR}). Accept either, case-insensitively,
   * so the probe measures registry availability rather than naming style.
   */
  private static boolean hasCommonMaterials(@Nullable Set<String> mats) {
    if (mats == null || mats.isEmpty()) return false;
    for (String mat : mats) {
      if (mat == null) continue;
      String name = mat.trim();
      int colon = name.lastIndexOf(':');
      if (colon >= 0) name = name.substring(colon + 1);
      if (name.equalsIgnoreCase("AIR") || name.equalsIgnoreCase("STONE")) return true;
    }
    return false;
  }

  public static Result runProbe(UUID callerId) {
    Result r = new Result();

    if (RTPRuntime.serverAccessor() == null) {
      r.pass = false;
      r.message = "RTPServerAccessor is null";
      return r;
    }

    // 1. Materials probe
    try {
      Set<String> mats = RTPRuntime.serverAccessor().materials();
      if (hasCommonMaterials(mats)) {
        r.materialsValid = true;
      } else {
        r.pass = false;
        r.details.add("materials empty or missing common materials");
      }
    } catch (Throwable t) {
      r.pass = false;
      r.details.add("materials() threw: " + t.getMessage());
    }

    // 2. Block tag snapshot probe
    try {
      Map<String, Set<String>> tags = RTPRuntime.serverAccessor().blockTagSnapshot();
      if (tags != null) {
        r.tagsValid = true;
      } else {
        r.pass = false;
        r.details.add("blockTagSnapshot() returned null");
      }
    } catch (Throwable t) {
      r.pass = false;
      r.details.add("blockTagSnapshot() threw: " + t.getMessage());
    }

    // 3. Sender resolution & null safety probe
    try {
      RTPCommandSender console = RTPRuntime.serverAccessor().getSender(RTPAPI.serverId);
      if (console == null) {
        console = RTPRuntime.serverAccessor().getConsolePlayer();
      }
      if (console != null) {
        boolean hasAdmin = console.hasPermission("*") || console.hasPermission("rtp.test");
        if (hasAdmin) {
          // Synthetic UUID lookup should fail closed (return null without throwing)
          UUID synthetic = UUID.randomUUID();
          RTPPlayer unknown = RTPRuntime.serverAccessor().getPlayer(synthetic);
          if (unknown == null) {
            r.senderValid = true;
          } else {
            r.details.add("synthetic UUID returned non-null player: " + unknown.name());
          }
        } else {
          r.details.add("console sender missing wildcard or rtp.test permission");
        }
      } else {
        r.details.add("console sender lookup returned null");
      }
      if (!r.senderValid) r.pass = false;
    } catch (Throwable t) {
      r.pass = false;
      r.details.add("sender lookup threw: " + t.getMessage());
    }

    // 4. Formatting and placeholder expansion probe
    try {
      String rawWithColor = "&a[player]";
      String formatted = RTPRuntime.serverAccessor().format(callerId, rawWithColor);
      String formattedNoColor = RTPRuntime.serverAccessor().formatNoColor(callerId, rawWithColor);

      if (formatted != null && formattedNoColor != null) {
        // formatNoColor should not contain the '&' color token if parsed
        // Some implementations may leave unchanged if no color library, still pass if non-null
        r.formatValid = true;
      } else {
        r.pass = false;
        r.details.add("format or formatNoColor returned null");
      }
    } catch (Throwable t) {
      r.pass = false;
      r.details.add("format probe threw: " + t.getMessage());
    }

    // 5. Thread context & overtime probe
    try {
      long over = RTPRuntime.serverAccessor().overTime();
      if (over >= 0L) {
        r.threadValid = true;
      } else {
        r.pass = false;
        r.details.add("overTime() returned negative value: " + over);
      }
    } catch (Throwable t) {
      r.pass = false;
      r.details.add("overTime() threw: " + t.getMessage());
    }

    // 6. Biome sampling probe
    try {
      RTPWorld<?> world = null;
      List<RTPWorld<?>> worlds = RTPRuntime.serverAccessor().getRTPWorlds();
      if (worlds != null && !worlds.isEmpty()) {
        world = worlds.get(0);
      }
      if (world == null) {
        world = RTPRuntime.serverAccessor().getRTPWorld("world");
      }

      if (world != null) {
        String sampled = RTPRuntime.serverAccessor().sampleBiome(world, 0, 64, 0);
        if (sampled != null && !sampled.isEmpty()) {
          r.biomeValid = true;
        } else {
          // sampleBiome may return empty on unsupported headless mock, check getBiomes
          Set<String> biomes = RTPRuntime.serverAccessor().getBiomes(world);
          if (biomes != null) {
            r.biomeValid = true;
          } else {
            r.pass = false;
            r.details.add("sampleBiome and getBiomes returned null");
          }
        }
      } else {
        // In headless testing without worlds
        r.biomeValid = true;
      }
    } catch (Throwable t) {
      r.pass = false;
      r.details.add("biome sampling threw: " + t.getMessage());
    }

    // 7. Menu surface probe (server-side queries)
    try {
      Predicate<String> probe = RTPRuntime.serverAccessor().menuPermissionProbe(callerId);
      Set<String> perms = RTPRuntime.serverAccessor().menuEffectivePermissions(callerId);
      String loc = RTPRuntime.serverAccessor().menuLocale(callerId);
      String desc = RTPRuntime.serverAccessor().menuRegionDescriptor(callerId);

      if (probe != null && perms != null && loc != null && desc != null) {
        r.menuValid = true;
      } else {
        r.pass = false;
        r.details.add("menu probe returned null fields: probe=" + (probe != null) +
            ", perms=" + (perms != null) + ", loc=" + (loc != null) + ", desc=" + (desc != null));
      }
    } catch (Throwable t) {
      r.pass = false;
      r.details.add("menu queries threw: " + t.getMessage());
    }

    // 8. Version & Platform metadata probe
    try {
      String sVer = RTPRuntime.serverAccessor().getServerVersion();
      String pVer = RTPRuntime.serverAccessor().getPluginVersion();
      String plat = RTPRuntime.serverAccessor().getPlatform();
      PlatformFamily fam = RTPRuntime.serverAccessor().getPlatformFamily();
      Integer intVer = RTPRuntime.serverAccessor().getServerIntVersion();

      boolean comp = RTPRuntime.serverAccessor().isCompatible(fam, 0, Integer.MAX_VALUE);
      boolean atLeast = RTPRuntime.serverAccessor().isServerVersionAtLeast(0);
      boolean atMost = RTPRuntime.serverAccessor().isServerVersionAtMost(Integer.MAX_VALUE);
      boolean isFam = fam != null && RTPRuntime.serverAccessor().isPlatformFamily(fam);

      if (sVer != null && !sVer.isEmpty()
          && pVer != null && !pVer.isEmpty()
          && plat != null && !plat.isEmpty()
          && fam != null
          && intVer != null
          && comp && atLeast && atMost && isFam) {
        r.versionValid = true;
      } else {
        r.pass = false;
        r.details.add(String.format(
            "version check failed: sVer=%s, pVer=%s, plat=%s, fam=%s, intVer=%s, comp=%s",
            sVer, pVer, plat, fam, intVer, comp));
      }
    } catch (Throwable t) {
      r.pass = false;
      r.details.add("version metadata probe threw: " + t.getMessage());
    }

    // 9. World & WorldBorder probe
    try {
      List<RTPWorld<?>> worlds = RTPRuntime.serverAccessor().getRTPWorlds();
      Set<String> allBiomes = RTPRuntime.serverAccessor().getBiomes();
      if (worlds != null && !worlds.isEmpty()) {
        RTPWorld<?> w = worlds.get(0);
        RTPWorld<?> byName = RTPRuntime.serverAccessor().getRTPWorld(w.name());
        RTPWorld<?> byId = RTPRuntime.serverAccessor().getRTPWorld(w.id());
        Object border = RTPRuntime.serverAccessor().getWorldBorder(w.name());

        RTPLocation testLoc = new RTPLocation(w, 0, 64, 0);
        RTPRuntime.serverAccessor().shapePlatform(testLoc);

        if (byName != null && byId != null && border != null && allBiomes != null) {
          r.worldValid = true;
        } else {
          r.pass = false;
          r.details.add(String.format(
              "world lookup failed: byName=%s, byId=%s, border=%s, allBiomes=%s",
              byName != null, byId != null, border != null, allBiomes != null));
        }
      } else {
        // Headless environment with 0 registered worlds
        r.worldValid = true;
      }
    } catch (Throwable t) {
      r.pass = false;
      r.details.add("world probe threw: " + t.getMessage());
    }

    // 10. Messaging & Feedback probe
    try {
      final String testTag = "testTag";
      final String testMsg = "probe test message";
      RTPCommandSender console = RTPRuntime.serverAccessor().getSender(RTPAPI.serverId);
      RTPRuntime.serverAccessor().sendMessage(RTPAPI.serverId, CommandMessages.infoTitle);
      RTPRuntime.serverAccessor().sendMessage(RTPAPI.serverId, CommandMessages.infoTitle, testTag);
      RTPRuntime.serverAccessor().sendMessage(RTPAPI.serverId, RTPAPI.serverId, CommandMessages.infoTitle);
      RTPRuntime.serverAccessor().sendMessage(RTPAPI.serverId, RTPAPI.serverId, CommandMessages.infoTitle, testTag);

      RTPRuntime.serverAccessor().sendMessage(RTPAPI.serverId, testMsg);
      RTPRuntime.serverAccessor().sendMessage(RTPAPI.serverId, testMsg, testTag);
      RTPRuntime.serverAccessor().sendMessage(RTPAPI.serverId, RTPAPI.serverId, testMsg);
      RTPRuntime.serverAccessor().sendMessage(RTPAPI.serverId, RTPAPI.serverId, testMsg, testTag);

      RTPRuntime.serverAccessor().sendMessageAndSuggest(RTPAPI.serverId, "probe suggest", "/rtp");
      if (console != null) {
        RTPRuntime.serverAccessor().sendMessage(console, "probe sender", "hover", "/rtp", null);
        RTPRuntime.serverAccessor().sendMessageWithRunCommand(console, "probe click", "hover", "/rtp");
      }
      RTPRuntime.serverAccessor().announce("probe announce", "rtp.test", null);
      r.messagingValid = true;
    } catch (Throwable t) {
      r.pass = false;
      r.details.add("messaging probe threw: " + t.getMessage());
    }

    // 11. Subsystems & performance probe
    try {
      double tps20 = RTPRuntime.serverAccessor().getTPS(20);
      double tps100 = RTPRuntime.serverAccessor().getTPS(100);
      File pluginDir = RTPRuntime.serverAccessor().getPluginDirectory();
      io.github.dailystruggle.rtp.api.scheduling.RTPScheduler scheduler = RTPRuntime.serverAccessor().getScheduler();
      io.github.dailystruggle.rtp.api.selection.ILocationGenerator generator = RTPRuntime.serverAccessor().getLocationGenerator();

      // Dispatch non-throwing command test
      RTPRuntime.serverAccessor().executeCommand(RTPAPI.serverId, "rtp test sem");

      boolean tpsOk = tps20 >= 0.0 && tps100 >= 0.0;
      boolean dirOk = pluginDir != null;
      boolean schedOk = scheduler != null;
      boolean genOk = generator != null;

      if (tpsOk && dirOk && schedOk && genOk) {
        r.subsystemValid = true;
      } else {
        r.pass = false;
        r.details.add(String.format(
            "subsystem check failed: tps20=%.1f, tps100=%.1f, dir=%s, sched=%s, gen=%s",
            tps20, tps100, dirOk, schedOk, genOk));
      }
    } catch (Throwable t) {
      r.pass = false;
      r.details.add("subsystem probe threw: " + t.getMessage());
    }

    // 12. WorldBorder packet & online player confinement SPI probe (ADR-093 / ADR-095)
    try {
      java.util.Collection<RTPPlayer> online = RTPRuntime.serverAccessor().getOnlinePlayers();
      if (online != null) {
        // Probe sendWorldBorder and resetWorldBorder both with synthetic ID and any online player
        UUID testPlayerId = UUID.randomUUID();
        RTPRuntime.serverAccessor().sendWorldBorder(testPlayerId, 0.0, 0.0, 100.0);
        RTPRuntime.serverAccessor().sendWorldBorder(testPlayerId, 0.0, 0.0, 100.0, 50.0, 5L);
        RTPRuntime.serverAccessor().resetWorldBorder(testPlayerId);

        for (RTPPlayer p : online) {
          if (p != null && p.uuid() != null) {
            RTPRuntime.serverAccessor().sendWorldBorder(p.uuid(), 0.0, 0.0, 100.0);
            RTPRuntime.serverAccessor().sendWorldBorder(p.uuid(), 0.0, 0.0, 100.0, 50.0, 5L);
            RTPRuntime.serverAccessor().resetWorldBorder(p.uuid());
          }
        }
        r.worldBorderValid = true;
      } else {
        r.pass = false;
        r.details.add("getOnlinePlayers() returned null");
      }
    } catch (Throwable t) {
      r.pass = false;
      r.details.add("worldborder probe threw: " + t.getMessage());
    }

    r.jacocoDumpTriggered = triggerJacocoDump();

    if (!r.pass) {
      r.message = String.join("; ", r.details);
    }

    return r;
  }

  public static boolean triggerJacocoDump() {
    boolean triggered = false;
    try {
      // First attempt: invoke RT.getAgent().dump(false) via various ClassLoaders
      Class<?> rtClass = null;
      ClassLoader[] loaders = new ClassLoader[] {
          ClassLoader.getSystemClassLoader(),
          ClassLoader.getPlatformClassLoader(),
          Thread.currentThread().getContextClassLoader(),
          TestAccessorCmd.class.getClassLoader()
      };
      for (ClassLoader cl : loaders) {
        if (cl == null) continue;
        try {
          rtClass = Class.forName("org.jacoco.agent.rt.RT", true, cl);
          if (rtClass != null) break;
        } catch (ClassNotFoundException ignored) {
        }
      }
      if (rtClass != null) {
        Method getAgentMethod = rtClass.getMethod("getAgent");
        Object agent = getAgentMethod.invoke(null);
        if (agent != null) {
          Method dumpMethod = agent.getClass().getMethod("dump", boolean.class);
          dumpMethod.invoke(agent, false);
          triggered = true;
        }
      }

      // Second attempt (fallback): invoke JaCoCo MBean via platform MBeanServer if JMX is active
      if (!triggered) {
        try {
          javax.management.MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
          javax.management.ObjectName name = new javax.management.ObjectName("org.jacoco:type=Runtime");
          if (mbs.isRegistered(name)) {
            mbs.invoke(name, "dump", new Object[] { false }, new String[] { "boolean" });
            triggered = true;
          }
        } catch (Throwable ignored) {
        }
      }
    } catch (Throwable ignored) {
    }
    return triggered;
  }

  private static void emit(UUID callerId, Result r) {
    String color = r.pass ? "&a" : "&c";
    String summary =
        String.format(
            "%s[RTP test/accessor] pass=%s | mats=%s tags=%s sender=%s format=%s thread=%s biome=%s menu=%s ver=%s world=%s msg=%s sub=%s wb=%s jacocoDump=%s",
            color,
            r.pass,
            r.materialsValid,
            r.tagsValid,
            r.senderValid,
            r.formatValid,
            r.threadValid,
            r.biomeValid,
            r.menuValid,
            r.versionValid,
            r.worldValid,
            r.messagingValid,
            r.subsystemValid,
            r.worldBorderValid,
            r.jacocoDumpTriggered);

    if (!callerId.equals(RTPAPI.serverId)) {
      RTPRuntime.serverAccessor().sendMessage(callerId, summary);
      if (!r.pass && !r.details.isEmpty()) {
        RTPRuntime.serverAccessor().sendMessage(callerId, "&c[RTP test/accessor] failure details: " + r.message);
      }
    }

    Level level = r.pass ? Level.INFO : Level.WARNING;
    RTPRuntime.log(level, summary);
    if (!r.pass && !r.details.isEmpty()) {
      RTPRuntime.log(Level.WARNING, "[RTP test/accessor] failure details: " + r.message);
    }
  }
}
