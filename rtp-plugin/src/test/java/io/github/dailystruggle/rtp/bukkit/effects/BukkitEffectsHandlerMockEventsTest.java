package io.github.dailystruggle.rtp.bukkit.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.mock.MockPlayerRegistry;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RuntimeMockRTPPlayer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Bukkit events for BIT mocks (ADR-121 section 11). {@code MockBukkit.mock()} is unusable in this
 * module (paper-api / MockBukkit version drift), so the plugin manager is a Mockito mock that
 * counts {@code callEvent}; a source check pins every event in the handler to the gate.
 */
@DisplayName("ADR-121 section 11: BukkitEffectsHandler publishes no Bukkit events for BIT mocks")
class BukkitEffectsHandlerMockEventsTest {

  private static final Path SOURCE = Paths.get("src", "main", "java",
      "io", "github", "dailystruggle", "rtp", "bukkit", "effects", "BukkitEffectsHandler.java");

  private static final String[] PIPELINE_EVENTS = {
      "PreSetupTeleportEvent", "PostSetupTeleportEvent", "PreLoadChunksEvent",
      "PostLoadChunksEvent", "PreTeleportEvent", "PostTeleportEvent"};

  private MockedStatic<Bukkit> bukkit;
  private final AtomicInteger called = new AtomicInteger();
  private RuntimeMockRTPPlayer mockPlayer;
  private MockRTPPlayer realPlayer;

  private static final class ProbeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
      return HANDLERS;
    }
  }

  @BeforeEach
  void setUp() {
    MockPlayerRegistry.clear();
    PluginManager pm = mock(PluginManager.class);
    doAnswer(inv -> {
      called.incrementAndGet();
      return null;
    }).when(pm).callEvent(any(Event.class));
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getPluginManager).thenReturn(pm);
    MockRTPWorld world = new MockRTPWorld("world");
    mockPlayer = new RuntimeMockRTPPlayer(UUID.randomUUID(), "BitMock", new RTPLocation(world, 0, 64, 0));
    MockPlayerRegistry.register(mockPlayer);
    realPlayer = new MockRTPPlayer(UUID.randomUUID(), "Real", new RTPLocation(world, 0, 64, 0));
  }

  @AfterEach
  void tearDown() {
    MockPlayerRegistry.clear();
    if (bukkit != null) bukkit.close();
  }

  @Test
  @DisplayName("ADR-121 section 11: no event reaches the plugin manager for a mock player or mock id")
  void mock_firesNothing() {
    assertFalse(BukkitEffectsHandler.fire(mockPlayer, new ProbeEvent()));
    assertFalse(BukkitEffectsHandler.fire(mockPlayer.uuid(), new ProbeEvent()));
    assertEquals(0, called.get());
  }

  @Test
  @DisplayName("ADR-121 section 11: real players and player-less tasks still publish events")
  void real_firesEvents() {
    assertTrue(BukkitEffectsHandler.fire(realPlayer, new ProbeEvent()));
    assertTrue(BukkitEffectsHandler.fire(realPlayer.uuid(), new ProbeEvent()));
    assertTrue(BukkitEffectsHandler.fire((io.github.dailystruggle.rtp.api.entity.RTPPlayer) null,
        new ProbeEvent()));
    assertEquals(3, called.get());
  }

  @Test
  @DisplayName("ADR-121 section 11: every callEvent in BukkitEffectsHandler goes through the mock gate")
  void source_routesEveryEventThroughGate() throws IOException {
    String src = Files.readString(SOURCE, StandardCharsets.UTF_8);
    Matcher calls = Pattern.compile("\\.callEvent\\(").matcher(src);
    int n = 0;
    while (calls.find()) n++;
    assertEquals(2, n, "only the two fire(...) helpers may call PluginManager.callEvent");
    for (String name : PIPELINE_EVENTS) {
      Pattern gated = Pattern.compile(
          "fire\\(task\\.player\\(\\), new " + name + "\\(task\\)\\)"
              + "|" + name + " event = new " + name + "\\(task\\);\\s*if \\(fire\\(task\\.player\\(\\), event\\)");
      assertTrue(gated.matcher(src).find(), name + " must be published via fire(task.player(), ...)");
    }
  }
}
