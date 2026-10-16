package io.github.dailystruggle.rtp.fabric.effects;

import com.mojang.authlib.GameProfile;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("FabricHandles 1.21.11 drift resilience")
class FabricHandlesDriftTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void tearDown() {
        FabricVersionAdapterRegistry.clearForTesting();
        FabricHandles.setServer(null);
        FabricEffectRuntime.unbindServer();
    }

    private static MinecraftServer inlineServer() {
        MinecraftServer server = mock(MinecraftServer.class);
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(server).execute(any(Runnable.class));
        return server;
    }

    @Test
    @DisplayName("dispatchConsoleCommand: uses the server bound on SERVER_STARTED (FabricEffectRuntime)")
    void dispatchConsoleCommandUsesRuntimeServer() {
        MinecraftServer server = inlineServer();
        AtomicReference<String> dispatched = new AtomicReference<>();
        FabricVersionAdapter adapter = mock(FabricVersionAdapter.class);
        when(adapter.dispatchConsoleCommand(any(), anyString())).thenAnswer(inv -> {
            dispatched.set(inv.getArgument(1));
            return true;
        });
        FabricVersionAdapterRegistry.install(adapter);
        FabricEffectRuntime.bindServer(server);

        FabricHandles.register();
        HandleRegistry.dispatchConsoleCommand("say sweep-console");

        assertEquals("say sweep-console", dispatched.get());
    }

    @Test
    @DisplayName("performCommand: carrier dispatchPlayerCommand wins over the void-linked common call (1.20.1)")
    void performCommandPrefersAdapterPlayerDispatch() {
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = inlineServer();
        Commands commands = mock(Commands.class);
        when(server.getCommands()).thenReturn(commands);
        FabricEffectRuntime.bindServer(server);

        FabricVersionAdapter adapter = mock(FabricVersionAdapter.class);
        when(adapter.dispatchPlayerCommand(player, "me hi")).thenReturn(true);
        FabricVersionAdapterRegistry.install(adapter);

        FabricHandles.wrap(player).performCommand("me hi");

        verify(adapter).dispatchPlayerCommand(player, "me hi");
        verify(commands, never()).performPrefixedCommand(any(), anyString());
    }

    @Test
    @DisplayName("kill: falls back to a silent /kill <uuid> when Entity#kill() does not link (1.21.2+)")
    void killFallsBackToKillCommand() {
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = inlineServer();
        Commands commands = mock(Commands.class);
        CommandSourceStack console = mock(CommandSourceStack.class);
        CommandSourceStack silent = mock(CommandSourceStack.class);
        UUID id = UUID.randomUUID();
        when(player.getUUID()).thenReturn(id);
        when(server.getCommands()).thenReturn(commands);
        when(server.createCommandSourceStack()).thenReturn(console);
        when(console.withSuppressedOutput()).thenReturn(silent);
        doThrow(new NoSuchMethodError("Entity.kill()V")).when(player).kill();
        FabricEffectRuntime.bindServer(server);

        FabricHandles.wrap(player).kill(true, null);

        verify(commands).performPrefixedCommand(silent, "kill " + id);
    }

    @Test
    @DisplayName("name(): resolves name via record name() accessor when available")
    void nameResolvesViaRecordAccessor() {
        ServerPlayer player = mock(ServerPlayer.class);
        UUID id = UUID.randomUUID();
        when(player.getUUID()).thenReturn(id);

        RecordShapedProfile profile = new RecordShapedProfile(id, "RecordPlayer");
        when(player.getGameProfile()).thenReturn(profile);

        PlayerHandle handle = FabricHandles.wrap(player);
        assertEquals("RecordPlayer", handle.name());
    }

    @Test
    @DisplayName("name(): falls back to getName().getString() when GameProfile has no name() or getName()")
    void nameFallsBackToComponentString() {
        ServerPlayer player = mock(ServerPlayer.class);
        UUID id = UUID.randomUUID();
        when(player.getUUID()).thenReturn(id);

        when(player.getGameProfile()).thenReturn(null);
        when(player.getName()).thenReturn(Component.literal("EntityPlayer"));

        PlayerHandle handle = FabricHandles.wrap(player);
        assertEquals("EntityPlayer", handle.name());
    }

    @Test
    @DisplayName("performCommand: dispatches via reflectively detected CommandSourceStack method")
    void performCommandDispatchesViaReflectiveStack() {
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = inlineServer();
        Commands commands = mock(Commands.class);
        CommandSourceStack css = mock(CommandSourceStack.class);

        when(server.getCommands()).thenReturn(commands);
        when(player.createCommandSourceStack()).thenReturn(css);
        FabricEffectRuntime.bindServer(server);

        PlayerHandle handle = FabricHandles.wrap(player);
        handle.performCommand("spawn");

        verify(commands).performPrefixedCommand(css, "spawn");
    }

    @Test
    @DisplayName("startGlide: routes through FabricVersionAdapter#teleport and uses ServerLevel#getServer for watchdog")
    void startGlideRoutesThroughAdapterAndServerLevel() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);

        when(player.serverLevel()).thenReturn(level);
        when(level.getServer()).thenReturn(server);
        when(player.getX()).thenReturn(100.0);
        when(player.getY()).thenReturn(64.0);
        when(player.getZ()).thenReturn(-200.0);
        when(player.getYRot()).thenReturn(90.0f);
        when(player.getXRot()).thenReturn(0.0f);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(server.getTickCount()).thenReturn(1000);

        AtomicBoolean adapterCalled = new AtomicBoolean(false);
        FabricVersionAdapter adapter = mock(FabricVersionAdapter.class);
        when(adapter.teleport(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat()))
                .thenAnswer(inv -> {
                    adapterCalled.set(true);
                    return true;
                });
        FabricVersionAdapterRegistry.install(adapter);

        PlayerHandle handle = FabricHandles.wrap(player);
        handle.startGlide(50, 255, 100, false, false, "STONE");

        assertTrue(adapterCalled.get(), "FabricVersionAdapter#teleport must be consulted");
        verify(player).startFallFlying();
        // ServerLevel#getServer() must be queried for the watchdog, NOT player.getServer()
        verify(level, atLeastOnce()).getServer();
        verify(server).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("dispatchConsoleCommand: routes through FabricVersionAdapter#dispatchConsoleCommand")
    void dispatchConsoleCommandRoutesThroughAdapter() {
        MinecraftServer server = mock(MinecraftServer.class);
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(server).execute(any(Runnable.class));

        AtomicReference<String> dispatched = new AtomicReference<>();
        FabricVersionAdapter adapter = mock(FabricVersionAdapter.class);
        when(adapter.dispatchConsoleCommand(any(), anyString())).thenAnswer(inv -> {
            dispatched.set(inv.getArgument(1));
            return true;
        });
        FabricVersionAdapterRegistry.install(adapter);
        FabricHandles.setServer(server);

        FabricHandles.register();
        HandleRegistry.dispatchConsoleCommand("say hello");

        assertEquals("say hello", dispatched.get());
    }

    public static class RecordShapedProfile extends GameProfile {
        public RecordShapedProfile(UUID id, String name) {
            super(id, name);
        }

        public String name() {
            return getName();
        }
    }
}
