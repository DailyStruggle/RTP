package io.github.dailystruggle.rtp.bukkitplatform.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BukkitCommandRegistrarSenderResolutionTest {

  @Test
  @DisplayName("Player sender resolves to player unique ID")
  void testPlayerSenderResolution() {
    UUID playerUuid = UUID.randomUUID();
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(playerUuid);

    AtomicReference<UUID> observedCaller = new AtomicReference<>();
    StringCommandDispatcher dispatcher = (callerId, label, args) -> {
      observedCaller.set(callerId);
      return true;
    };

    BukkitCommandRegistrar registrar = new BukkitCommandRegistrar(null, mock(TreeCommand.class), dispatcher);
    Command command = mock(Command.class);

    boolean result = registrar.onCommand(player, command, "rtp", new String[]{"test"});
    assertTrue(result);
    assertEquals(playerUuid, observedCaller.get());
  }

  @Test
  @DisplayName("RemoteConsoleCommandSender (RCON) resolves to CommandsAPI.serverId and is not rejected")
  void testRemoteConsoleSenderResolution() {
    RemoteConsoleCommandSender rconSender = mock(RemoteConsoleCommandSender.class);
    when(rconSender.getName()).thenReturn("Rcon");

    AtomicReference<UUID> observedCaller = new AtomicReference<>();
    StringCommandDispatcher dispatcher = (callerId, label, args) -> {
      observedCaller.set(callerId);
      return true;
    };

    BukkitCommandRegistrar registrar = new BukkitCommandRegistrar(null, mock(TreeCommand.class), dispatcher);
    Command command = mock(Command.class);

    boolean result = registrar.onCommand(rconSender, command, "rtp", new String[]{"test", "accessor"});
    assertTrue(result);
    assertEquals(CommandsAPI.serverId, observedCaller.get());
  }

  @Test
  @DisplayName("ConsoleCommandSender resolves to CommandsAPI.serverId")
  void testConsoleSenderResolution() {
    ConsoleCommandSender consoleSender = mock(ConsoleCommandSender.class);
    when(consoleSender.getName()).thenReturn("CONSOLE");

    AtomicReference<UUID> observedCaller = new AtomicReference<>();
    StringCommandDispatcher dispatcher = (callerId, label, args) -> {
      observedCaller.set(callerId);
      return true;
    };

    BukkitCommandRegistrar registrar = new BukkitCommandRegistrar(null, mock(TreeCommand.class), dispatcher);
    Command command = mock(Command.class);

    boolean result = registrar.onCommand(consoleSender, command, "rtp", new String[]{"test"});
    assertTrue(result);
    assertEquals(CommandsAPI.serverId, observedCaller.get());
  }

  @Test
  @DisplayName("Unsupported arbitrary sender returns false and messages sender")
  void testUnsupportedSenderResolution() {
    CommandSender unknownSender = mock(CommandSender.class);
    when(unknownSender.getName()).thenReturn("MysterySender");

    AtomicReference<String> messageSent = new AtomicReference<>();
    Mockito.doAnswer(inv -> {
      messageSent.set(inv.getArgument(0));
      return null;
    }).when(unknownSender).sendMessage(Mockito.anyString());

    BukkitCommandRegistrar registrar = new BukkitCommandRegistrar(null, mock(TreeCommand.class), null);
    Command command = mock(Command.class);

    boolean result = registrar.onCommand(unknownSender, command, "rtp", new String[]{"test"});
    assertFalse(result);
    assertEquals("alternate command senders not currently supported", messageSent.get());
  }
}
