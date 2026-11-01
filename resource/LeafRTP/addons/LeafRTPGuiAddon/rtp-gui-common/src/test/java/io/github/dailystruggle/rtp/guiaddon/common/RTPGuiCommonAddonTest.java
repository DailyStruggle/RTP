package io.github.dailystruggle.rtp.guiaddon.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.ConfigReloadLifecycle;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ADR-056 / REQ-API-F-006: RTPGuiCommonAddon openOnBareRtp root action toggle")
public class RTPGuiCommonAddonTest {

  @TempDir
  File tempDir;

  private RTPServerAccessor originalServerAccessor;
  private RTPScheduler originalScheduler;
  private RTPServerAccessor originalApiServerAccessor;
  private TreeCommand originalBaseCommand;
  private Configs originalConfigs;
  private ConfigReloadLifecycle originalReloadLifecycle;

  private final List<UUID> openedForPlayers = new ArrayList<>();
  private RTPGuiCommonAddon addon;

  @BeforeEach
  void setUp() {
    originalServerAccessor = RTPRuntime.serverAccessor();
    originalScheduler = RTPRuntime.scheduler();
    originalApiServerAccessor = RTPAPI.serverAccessor;
    originalBaseCommand = RTP.baseCommand;
    originalConfigs = ActiveConfigs.get();

    openedForPlayers.clear();

    RTPCommandSender mockSender = (RTPCommandSender) Proxy.newProxyInstance(
        RTPCommandSender.class.getClassLoader(),
        new Class<?>[]{RTPCommandSender.class},
        (proxy, method, args) -> {
          if ("hasPermission".equals(method.getName())) {
            return true;
          }
          return null;
        });

    RTPPlayer mockPlayer = (RTPPlayer) Proxy.newProxyInstance(
        RTPPlayer.class.getClassLoader(),
        new Class<?>[]{RTPPlayer.class},
        (proxy, method, args) -> null);

    RTPServerAccessor mockAccessor = (RTPServerAccessor) Proxy.newProxyInstance(
        RTPServerAccessor.class.getClassLoader(),
        new Class<?>[]{RTPServerAccessor.class},
        (proxy, method, args) -> {
          String name = method.getName();
          if ("getPluginDirectory".equals(name)) {
            return tempDir;
          }
          if ("getSender".equals(name)) {
            return mockSender;
          }
          if ("getPlayer".equals(name)) {
            return mockPlayer;
          }
          if ("getRTPWorlds".equals(name)) {
            return Collections.emptyList();
          }
          return null;
        });

    RTPScheduler mockScheduler = (RTPScheduler) Proxy.newProxyInstance(
        RTPScheduler.class.getClassLoader(),
        new Class<?>[]{RTPScheduler.class},
        (proxy, method, args) -> {
          if ("runTask".equals(method.getName()) && args != null && args.length > 0 && args[0] instanceof Runnable r) {
            r.run();
          }
          return null;
        });

    RTP.ensureInitialized();
    originalReloadLifecycle = Configs.getReloadLifecycle();
    Configs.setReloadLifecycle(ConfigReloadLifecycle.NONE);
    RTPRuntime.setServerAccessor(mockAccessor);
    RTPRuntime.setScheduler(mockScheduler);
    RTPAPI.serverAccessor = mockAccessor;

    new Configs(tempDir);

    RTP.baseCommand = new BaseRTPCmdImpl(null) {
      @Override
      public String name() {
        return "rtp";
      }

      @Override
      public String permission() {
        return "rtp.use";
      }

      @Override
      public boolean onCommand(
          UUID callerId,
          Map<String, List<String>> parameterValues,
          CommandsAPICommand nextCommand) {
        return true;
      }
    };

    GuiRenderers.clear();
    GuiRenderers.register(createRecordingRenderer());
    RTPAPI.hooks().rootAction().clear();

    addon = new RTPGuiCommonAddon();
  }

  @AfterEach
  void tearDown() {
    if (addon != null) {
      addon.onUnload();
    }
    GuiRenderers.clear();
    RTPAPI.hooks().rootAction().clear();
    Configs.setReloadLifecycle(originalReloadLifecycle);
    RTP.baseCommand = originalBaseCommand;
    ActiveConfigs.set(originalConfigs);
    RTPRuntime.setServerAccessor(originalServerAccessor);
    RTPRuntime.setScheduler(originalScheduler);
    RTPAPI.serverAccessor = originalApiServerAccessor;
  }

  private MenuRenderer createRecordingRenderer() {
    return new MenuRenderer() {
      @Override
      public String key() {
        return "chest";
      }

      @Override
      public boolean isAvailable() {
        return true;
      }

      @Override
      public void open(UUID playerId, MenuModel model) {
        openedForPlayers.add(playerId);
      }
    };
  }

  @Test
  @DisplayName("openOnBareRtp defaults to true and binds rootAction")
  void openOnBareRtp_defaultsToTrue_andBindsRootAction() {
    addon.onLoad();

    assertTrue(GuiMenuConfig.INSTANCE.openOnBareRtp(), "openOnBareRtp should default to true");
    assertNotNull(RTPAPI.hooks().rootAction().current(), "rootAction should be bound when openOnBareRtp is true");

    UUID playerId = UUID.randomUUID();
    boolean handled = RTPAPI.hooks().rootAction().current().run(playerId, msg -> {});
    assertTrue(handled, "bound rootAction should handle bare /rtp");
    assertEquals(List.of(playerId), openedForPlayers);

    assertNotNull(RTP.baseCommand.getCommandLookup().get("GUI"), "/rtp gui must be registered");
    assertNotNull(RTP.baseCommand.getCommandLookup().get("MENU"), "/rtp menu must be registered");
  }

  @Test
  @DisplayName("openOnBareRtp false leaves /rtp gui and /rtp menu registered while unbinding rootAction")
  void openOnBareRtp_false_leavesGuiAndMenuRegistered_whileUnbindingRootAction() throws Exception {
    File addonsDir = new File(tempDir, "addons");
    assertTrue(addonsDir.mkdirs() || addonsDir.isDirectory());
    Files.writeString(
        new File(addonsDir, "guimenu.yml").toPath(),
        "openOnBareRtp: false\nmenuStyle: \"chest\"\nversion: \"1.0\"\n",
        StandardCharsets.UTF_8);

    addon.onLoad();

    assertFalse(GuiMenuConfig.INSTANCE.openOnBareRtp(), "openOnBareRtp should be false");
    assertNull(RTPAPI.hooks().rootAction().current(), "rootAction must not be bound when openOnBareRtp is false");

    CommandsAPICommand guiCmd = RTP.baseCommand.getCommandLookup().get("GUI");
    CommandsAPICommand menuCmd = RTP.baseCommand.getCommandLookup().get("MENU");
    assertNotNull(guiCmd, "/rtp gui must remain registered when openOnBareRtp is false");
    assertNotNull(menuCmd, "/rtp menu must remain registered when openOnBareRtp is false");

    UUID playerOne = UUID.randomUUID();
    UUID playerTwo = UUID.randomUUID();
    assertTrue(guiCmd.onCommand(playerOne, Collections.emptyMap(), null));
    assertTrue(menuCmd.onCommand(playerTwo, Collections.emptyMap(), null));
    assertEquals(List.of(playerOne, playerTwo), openedForPlayers);
  }

  @Test
  @DisplayName("menuStyle none or off disables openOnBareRtp and unbinds rootAction while keeping /rtp gui and /rtp menu active")
  void menuStyle_noneOrOff_disablesOpenOnBareRtp_andUnbindsRootAction() throws Exception {
    File addonsDir = new File(tempDir, "addons");
    assertTrue(addonsDir.mkdirs() || addonsDir.isDirectory());
    Files.writeString(
        new File(addonsDir, "guimenu.yml").toPath(),
        "openOnBareRtp: true\nmenuStyle: \"none\"\nversion: \"1.0\"\n",
        StandardCharsets.UTF_8);

    addon.onLoad();

    assertFalse(GuiMenuConfig.INSTANCE.openOnBareRtp(), "menuStyle 'none' must make openOnBareRtp() return false");
    assertNull(RTPAPI.hooks().rootAction().current(), "rootAction must be unbound when menuStyle is 'none'");

    CommandsAPICommand guiCmd = RTP.baseCommand.getCommandLookup().get("GUI");
    CommandsAPICommand menuCmd = RTP.baseCommand.getCommandLookup().get("MENU");
    assertNotNull(guiCmd, "/rtp gui must remain registered when menuStyle is 'none'");
    assertNotNull(menuCmd, "/rtp menu must remain registered when menuStyle is 'none'");

    UUID playerId = UUID.randomUUID();
    assertTrue(guiCmd.onCommand(playerId, Collections.emptyMap(), null));
    assertEquals(List.of(playerId), openedForPlayers);

    @SuppressWarnings("unchecked")
    ConfigParser<GuiMenuKeys> parser =
        (ConfigParser<GuiMenuKeys>) ActiveConfigs.get().getParser(GuiMenuKeys.class);
    assertNotNull(parser);
    parser.set(GuiMenuKeys.menuStyle, "off");
    assertFalse(GuiMenuConfig.INSTANCE.openOnBareRtp(), "menuStyle 'off' must make openOnBareRtp() return false");
  }

  @Test
  @DisplayName("toggling openOnBareRtp across a reload dynamically binds and unbinds rootAction")
  void togglingOpenOnBareRtp_acrossReload_dynamicallyBindsAndUnbindsRootAction() throws Exception {
    addon.onLoad();
    assertTrue(GuiMenuConfig.INSTANCE.openOnBareRtp());
    assertNotNull(RTPAPI.hooks().rootAction().current(), "rootAction must be bound initially");

    @SuppressWarnings("unchecked")
    ConfigParser<GuiMenuKeys> parser =
        (ConfigParser<GuiMenuKeys>) ActiveConfigs.get().getParser(GuiMenuKeys.class);
    assertNotNull(parser);
    parser.set(GuiMenuKeys.openOnBareRtp, false);
    parser.save();

    ActiveConfigs.get().reloadAction();

    assertFalse(GuiMenuConfig.INSTANCE.openOnBareRtp(), "openOnBareRtp must be false after reload");
    assertNull(RTPAPI.hooks().rootAction().current(), "rootAction must be unbound after reloading openOnBareRtp=false");
    assertNotNull(RTP.baseCommand.getCommandLookup().get("GUI"), "/rtp gui must remain registered after reload");
    assertNotNull(RTP.baseCommand.getCommandLookup().get("MENU"), "/rtp menu must remain registered after reload");

    @SuppressWarnings("unchecked")
    ConfigParser<GuiMenuKeys> reloadedParser =
        (ConfigParser<GuiMenuKeys>) ActiveConfigs.get().getParser(GuiMenuKeys.class);
    assertNotNull(reloadedParser);
    reloadedParser.set(GuiMenuKeys.openOnBareRtp, true);
    reloadedParser.save();

    ActiveConfigs.get().reloadAction();

    assertTrue(GuiMenuConfig.INSTANCE.openOnBareRtp(), "openOnBareRtp must be true after second reload");
    assertNotNull(RTPAPI.hooks().rootAction().current(), "rootAction must be re-bound after reloading openOnBareRtp=true");
  }
}
