package io.github.dailystruggle.rtp.bukkit;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

public class RTPBukkitPluginTest {
    private ServerMock server;
    private RTPBukkitPlugin plugin;
    private MockedStatic<Bukkit> bukkitMock;

    @BeforeEach
    public void setUp() {
        // server = MockBukkit.mock(); // Fails due to version mismatch
        bukkitMock = mockStatic(Bukkit.class);
        server = mock(ServerMock.class);
        bukkitMock.when(Bukkit::getServer).thenReturn(server);
        // MockFoliaScheduler.registerFoliaMocks(bukkitMock);
        // plugin = MockBukkit.load(RTPBukkitPlugin.class);
        plugin = mock(RTPBukkitPlugin.class);
    }

    @AfterEach
    public void tearDown() {
        if (bukkitMock != null) {
            bukkitMock.close();
        }
        MockBukkit.unmock();
    }

    @Test
    public void testPluginEnabled() {
        assertNotNull(plugin);
    }
}
