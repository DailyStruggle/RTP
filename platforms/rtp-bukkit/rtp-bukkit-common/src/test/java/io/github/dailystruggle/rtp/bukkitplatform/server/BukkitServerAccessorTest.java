package io.github.dailystruggle.rtp.bukkitplatform.server;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockPlugin;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import io.github.dailystruggle.rtp.api.server.ProgressBar;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import org.bukkit.World;
import org.bukkit.permissions.PermissionAttachment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests covering platform adapter server accessor logic in {@code rtp-bukkit-common}
 * using MockBukkit.
 *
 * <p>Lifecycle is strictly guarded via {@link MockBukkit#mock()} in {@link #setUp()}
 * and {@link MockBukkit#unmock()} in {@link #tearDown()} to prevent cross-worker static leaks.</p>
 */
@DisplayName("BukkitServerAccessor platform adapter tests")
class BukkitServerAccessorTest {

    private ServerMock server;
    private MockPlugin plugin;
    private TestBukkitServerAccessor accessor;

    static class TestBukkitServerAccessor extends AbstractServerAccessor {
        TestBukkitServerAccessor() {
            super();
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        accessor = new TestBukkitServerAccessor();
        accessor.start(plugin);
        RTPAPI.serverAccessor = accessor;
        io.github.dailystruggle.rtp.common.RTP.serverAccessor = accessor;
    }

    @AfterEach
    void tearDown() {
        RTPAPI.serverAccessor = null;
        io.github.dailystruggle.rtp.common.RTP.serverAccessor = null;
        if (accessor != null) {
            accessor.stop();
        }
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Platform metadata and version checks")
    void testPlatformAndVersion() {
        assertEquals(PlatformFamily.BUKKIT, accessor.getPlatformFamily());
        assertTrue(accessor.isPlatformFamily(PlatformFamily.BUKKIT));
        assertFalse(accessor.isPlatformFamily(PlatformFamily.FABRIC));
        assertFalse(accessor.isPlatformFamily(PlatformFamily.NEOFORGE));

        accessor.setPlatform("Paper");
        assertEquals("Paper", accessor.getPlatform());
        accessor.setPlatform("Spigot");
        assertEquals("Spigot", accessor.getPlatform());

        assertNotNull(accessor.getServerVersion());
        assertNotNull(accessor.getServerIntVersion());
        assertNotNull(accessor.getPluginVersion());
        assertEquals(plugin, accessor.getPlugin());
        assertEquals(plugin.getDataFolder(), accessor.getPluginDirectory());
    }

    @Test
    @DisplayName("Online player names and world resolution")
    void testWorldAndPlayerResolution() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

        Set<String> onlineNames = accessor.getOnlinePlayerNames();
        assertEquals(2, onlineNames.size());
        assertTrue(onlineNames.contains("Alice"));
        assertTrue(onlineNames.contains("Bob"));

        var onlinePlayers = accessor.getOnlinePlayers();
        assertEquals(2, onlinePlayers.size());

        RTPPlayer rtpAlice = accessor.getPlayer(alice.getUniqueId());
        assertNotNull(rtpAlice);
        assertEquals(alice.getUniqueId(), rtpAlice.uuid());
        assertEquals("Alice", rtpAlice.name());

        RTPPlayer rtpBob = accessor.getPlayer("Bob");
        assertNotNull(rtpBob);
        assertEquals(bob.getUniqueId(), rtpBob.uuid());

        assertNull(accessor.getPlayer(UUID.randomUUID()));
        assertNull(accessor.getPlayer("NonExistentPlayer"));

        // World resolution by name and UUID
        World defaultWorld = server.getWorld("world");
        assertNotNull(defaultWorld);

        RTPWorld<?> rtpWorldByName = accessor.getRTPWorld(defaultWorld.getName());
        assertNotNull(rtpWorldByName);
        assertEquals(defaultWorld.getName(), rtpWorldByName.name());

        RTPWorld<?> rtpWorldByUuid = accessor.getRTPWorld(defaultWorld.getUID());
        assertNotNull(rtpWorldByUuid);
        assertSame(rtpWorldByName, rtpWorldByUuid);

        assertNull(accessor.getRTPWorld("no_such_world"));
        assertNull(accessor.getRTPWorld(UUID.randomUUID()));

        List<RTPWorld<?>> worlds = accessor.getRTPWorlds();
        assertFalse(worlds.isEmpty());
        assertTrue(worlds.contains(rtpWorldByName));
    }

    @Test
    @DisplayName("Player permission probe, effective permissions, and op fallback")
    void testPermissionChecking() {
        PlayerMock player = server.addPlayer("PermUser");
        UUID playerId = player.getUniqueId();

        Predicate<String> probe = accessor.menuPermissionProbe(playerId);
        assertNotNull(probe);
        assertFalse(probe.test("rtp.use"));
        assertFalse(probe.test("rtp.admin"));

        // Add explicit permission
        PermissionAttachment attachment = player.addAttachment(plugin);
        attachment.setPermission("rtp.use", true);
        attachment.setPermission("rtp.biome.*", true);

        assertTrue(probe.test("rtp.use"));
        assertTrue(probe.test("rtp.biome.*"));
        assertFalse(probe.test("rtp.admin"));

        // Effective permissions
        Set<String> effectivePerms = accessor.menuEffectivePermissions(playerId);
        assertTrue(effectivePerms.contains("rtp.use"));
        assertTrue(effectivePerms.contains("rtp.biome.*"));

        // Null checks
        assertFalse(accessor.menuPermissionProbe(null).test("rtp.use"));
        assertFalse(probe.test(null));
        assertTrue(accessor.menuEffectivePermissions(null).isEmpty());

        // Offline / non-existent player fallback to op status
        UUID unknownUuid = UUID.randomUUID();
        Predicate<String> offlineProbe = accessor.menuPermissionProbe(unknownUuid);
        assertFalse(offlineProbe.test("rtp.use"));

        // Op player probe check
        PlayerMock opPlayer = server.addPlayer("OpUser");
        opPlayer.setOp(true);
        Predicate<String> opProbe = accessor.menuPermissionProbe(opPlayer.getUniqueId());
        assertTrue(opProbe.test("rtp.any.node"));
    }

    @Test
    @DisplayName("Command execution and sender resolution")
    void testCommandExecution() {
        PlayerMock player = server.addPlayer("CmdUser");
        player.setOp(true);

        // Register a lightweight mock command in MockBukkit to avoid VersionCommand async thread issues
        org.bukkit.command.Command testCmd = new org.bukkit.command.Command("testcmd") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
                sender.sendMessage("testcmd executed");
                return true;
            }
        };
        server.getCommandMap().register("test", testCmd);

        // Blank or null command handling
        assertFalse(accessor.executeCommand(null, ""));
        assertFalse(accessor.executeCommand(null, "   "));
        assertFalse(accessor.executeCommand(null, null));
        assertFalse(accessor.executeCommand(UUID.randomUUID(), "testcmd"));

        // Console dispatch (via null UUID and serverId)
        boolean consoleDispatched = accessor.executeCommand(null, "testcmd");
        assertTrue(consoleDispatched);

        boolean serverIdDispatched = accessor.executeCommand(RTPAPI.serverId, "testcmd");
        assertTrue(serverIdDispatched);

        // Player dispatch
        boolean playerDispatched = accessor.executeCommand(player.getUniqueId(), "testcmd");
        assertTrue(playerDispatched);

        // RTPCommandSender resolution
        RTPCommandSender consoleSender = accessor.getSender(RTPAPI.serverId);
        assertNotNull(consoleSender);
        assertTrue(consoleSender.hasPermission("rtp.admin"));

        RTPCommandSender playerSender = accessor.getSender(player.getUniqueId());
        assertNotNull(playerSender);
        assertEquals("CmdUser", playerSender.name());

        // Unknown UUID defaults safely to console sender
        RTPCommandSender fallbackSender = accessor.getSender(UUID.randomUUID());
        assertNotNull(fallbackSender);
    }

    @Test
    @DisplayName("executeCommandWithCapture captures console feedback")
    @Timeout(10)
    void testExecuteCommandWithCapture() {
        assertFalse(accessor.executeCommandWithCapture("", s -> {}));
        assertFalse(accessor.executeCommandWithCapture(null, s -> {}));

        org.bukkit.command.Command echoCmd = new org.bukkit.command.Command("echocmd") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
                sender.sendMessage("capture feedback line");
                return true;
            }
        };
        server.getCommandMap().register("test", echoCmd);

        List<String> output = new ArrayList<>();
        boolean executed = accessor.executeCommandWithCapture("echocmd", output::add);
        assertTrue(executed);
        assertTrue(output.contains("capture feedback line"));
    }

    @Test
    @DisplayName("Action triggers and task registry lifecycle")
    void testActionRegistryTriggers() {
        String trackingId = "test-task-" + UUID.randomUUID().toString();
        io.github.dailystruggle.rtp.common.tasks.RTPRunnable runnable = new io.github.dailystruggle.rtp.common.tasks.RTPRunnable() {
            @Override
            public void run() {}
        };
        TrackedRTPTask task = new TrackedRTPTask(runnable, trackingId);

        accessor.registerAction(task);
        Map<String, Long> snapshot = accessor.getTaskSnapshot();
        assertNotNull(snapshot);
        assertTrue(snapshot.containsKey(trackingId));
        assertTrue(snapshot.get(trackingId) >= 0L);

        accessor.removeAction(trackingId);
        Map<String, Long> postRemoval = accessor.getTaskSnapshot();
        assertFalse(postRemoval.containsKey(trackingId));
    }

    @Test
    @DisplayName("Player message dispatching and announcements")
    void testMessageDispatching() {
        PlayerMock player1 = server.addPlayer("P1");
        PlayerMock player2 = server.addPlayer("P2");

        player1.addAttachment(plugin).setPermission("rtp.broadcast", true);

        // Announce message filtered by permission
        accessor.announce("Broadcast message", "rtp.broadcast", null);
        assertEquals("Broadcast message", player1.nextMessage());
        assertNull(player2.nextMessage());

        // Direct player message
        accessor.sendMessage(player2.getUniqueId(), "Direct hello", null);
        assertEquals("Direct hello", player2.nextMessage());

        // Multi-target message
        accessor.sendMessage(player1.getUniqueId(), player2.getUniqueId(), "Dual hello", null);
        assertEquals("Dual hello", player1.nextMessage());
        assertEquals("Dual hello", player2.nextMessage());

        // Console target
        accessor.sendMessage(RTPAPI.serverId, "Console message", null);

        // Formatting
        String formatted = accessor.format(player1.getUniqueId(), "&aGreen");
        assertTrue(formatted.contains("§aGreen") || formatted.contains("Green"));
        String noColor = accessor.formatNoColor(player1.getUniqueId(), "&aGreen");
        assertEquals("&aGreen", noColor);
    }

    @Test
    @DisplayName("Progress bar update and clear lifecycle")
    void testProgressBars() {
        PlayerMock player = server.addPlayer("Viewer");
        player.addAttachment(plugin).setPermission("rtp.progress", true);

        ProgressBar bar = new ProgressBar("&aSearching...", 0.5, "rtp.progress");

        assertDoesNotThrow(() -> {
            accessor.updateProgressBars(Map.of("bar-1", bar));
            accessor.clearProgressBars();

            // Empty / null handling
            accessor.updateProgressBars(null);
            accessor.updateProgressBars(Map.of());
        });
    }

    @Test
    @DisplayName("Scoreboard tag operations")
    void testScoreboardTags() {
        PlayerMock player = server.addPlayer("ScoreboardPlayer");
        UUID id = player.getUniqueId();

        assertTrue(accessor.getScoreboardTags(id).isEmpty());

        // Test safe handling when player is null or tag is invalid
        assertFalse(accessor.addScoreboardTag(null, "tag"));
        assertFalse(accessor.addScoreboardTag(id, ""));
        assertFalse(accessor.addScoreboardTag(id, null));
        assertFalse(accessor.removeScoreboardTag(null, "tag"));
        assertFalse(accessor.removeScoreboardTag(id, ""));
        assertFalse(accessor.removeScoreboardTag(id, null));
        assertTrue(accessor.getScoreboardTags(null).isEmpty());

        // Test non-null response from accessor delegation
        boolean added = accessor.addScoreboardTag(id, "rtp.visited");
        if (added) {
            assertTrue(accessor.getScoreboardTags(id).contains("rtp.visited"));
            assertTrue(accessor.removeScoreboardTag(id, "rtp.visited"));
            assertFalse(accessor.getScoreboardTags(id).contains("rtp.visited"));
        }
    }

    @Test
    @DisplayName("Off-tick accessor contract compliance")
    @Timeout(10)
    void testOffTickContractCompliance() throws ExecutionException, InterruptedException, TimeoutException {
        PlayerMock player = server.addPlayer("OffTickUser");
        UUID playerId = player.getUniqueId();

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            assertFalse(accessor.isPrimaryThread());

            // Menu permission probe off-tick
            Predicate<String> probe = accessor.menuPermissionProbe(playerId);
            assertNotNull(probe);
            assertFalse(probe.test("rtp.admin"));

            // World lookups off-tick
            RTPWorld<?> world = accessor.getRTPWorld("world");
            assertNotNull(world);

            // Palette identifier normalization off-tick
            String stone = accessor.reconcilePaletteIdentifier("minecraft:stone");
            assertEquals("STONE", stone);

            // Task registry off-tick
            String taskId = "async-task";
            io.github.dailystruggle.rtp.common.tasks.RTPRunnable asyncRunnable = new io.github.dailystruggle.rtp.common.tasks.RTPRunnable() {
                @Override public void run() {}
            };
            TrackedRTPTask task = new TrackedRTPTask(asyncRunnable, taskId);
            accessor.registerAction(task);
            assertTrue(accessor.getTaskSnapshot().containsKey(taskId));
            accessor.removeAction(taskId);
            assertFalse(accessor.getTaskSnapshot().containsKey(taskId));
        });

        future.get(5, TimeUnit.SECONDS);
    }
}
