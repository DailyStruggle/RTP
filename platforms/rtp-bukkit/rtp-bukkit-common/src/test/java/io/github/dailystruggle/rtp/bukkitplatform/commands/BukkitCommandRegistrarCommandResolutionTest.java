package io.github.dailystruggle.rtp.bukkitplatform.commands;

import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BukkitCommandRegistrarCommandResolutionTest {

  private Server originalServer;
  private Server mockServer;

  @BeforeEach
  void setUp() throws Exception {
    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    originalServer = (Server) serverField.get(null);

    mockServer = mock(Server.class);
    when(mockServer.getLogger()).thenReturn(Logger.getAnonymousLogger());
    when(mockServer.getName()).thenReturn("MockBukkit");
    when(mockServer.getVersion()).thenReturn("1.0");
    when(mockServer.getBukkitVersion()).thenReturn("1.0");

    serverField.set(null, mockServer);
  }

  @AfterEach
  void tearDown() throws Exception {
    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, originalServer);
  }

  @Test
  @DisplayName("Do not hijack foreign plugin command when unqualified command belongs to another plugin")
  void testDoNotHijackForeignPluginCommand() {
    JavaPlugin ownPlugin = mock(JavaPlugin.class);
    when(ownPlugin.getName()).thenReturn("RTP");

    Plugin foreignPlugin = mock(Plugin.class);
    when(foreignPlugin.getName()).thenReturn("EzRTP");

    PluginCommand foreignCommand = mock(PluginCommand.class);
    when(foreignCommand.getPlugin()).thenReturn(foreignPlugin);

    PluginCommand ownCommand = mock(PluginCommand.class);
    when(ownCommand.getPlugin()).thenReturn(ownPlugin);

    // Bukkit.getPluginCommand("rtp") returns EzRTP's command (simulating Linux load order)
    when(mockServer.getPluginCommand("rtp")).thenReturn(foreignCommand);
    when(mockServer.getPluginCommand("rtp:rtp")).thenReturn(ownCommand);

    // ownPlugin.getCommand("rtp") returns RTP's own command
    when(ownPlugin.getCommand("rtp")).thenReturn(ownCommand);

    BukkitCommandRegistrar registrar = new BukkitCommandRegistrar(ownPlugin, mock(TreeCommand.class), null);
    registrar.register("rtp");

    // EzRTP's command MUST NOT have executor or tab completer set by LeafRTP
    verify(foreignCommand, never()).setExecutor(any());
    verify(foreignCommand, never()).setTabCompleter(any());

    // LeafRTP's own command MUST have executor and tab completer set
    verify(ownCommand).setExecutor(registrar);
    verify(ownCommand).setTabCompleter(registrar);
  }

  @Test
  @DisplayName("Fallback to namespaced plugin command when plugin is not JavaPlugin and unqualified belongs to another plugin")
  void testFallbackToNamespacedCommandForGenericPlugin() {
    Plugin ownPlugin = mock(Plugin.class);
    when(ownPlugin.getName()).thenReturn("RTP");

    Plugin foreignPlugin = mock(Plugin.class);
    when(foreignPlugin.getName()).thenReturn("EzRTP");

    PluginCommand foreignCommand = mock(PluginCommand.class);
    when(foreignCommand.getPlugin()).thenReturn(foreignPlugin);

    PluginCommand ownCommand = mock(PluginCommand.class);
    when(ownCommand.getPlugin()).thenReturn(ownPlugin);

    when(mockServer.getPluginCommand("rtp")).thenReturn(foreignCommand);
    when(mockServer.getPluginCommand("rtp:rtp")).thenReturn(ownCommand);

    BukkitCommandRegistrar registrar = new BukkitCommandRegistrar(ownPlugin, mock(TreeCommand.class), null);
    registrar.register("rtp");

    verify(foreignCommand, never()).setExecutor(any());
    verify(foreignCommand, never()).setTabCompleter(any());
    verify(ownCommand).setExecutor(registrar);
    verify(ownCommand).setTabCompleter(registrar);
  }
}
