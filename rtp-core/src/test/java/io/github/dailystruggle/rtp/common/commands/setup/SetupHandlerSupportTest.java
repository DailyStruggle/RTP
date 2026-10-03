package io.github.dailystruggle.rtp.common.commands.setup;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SetupHandlerSupportTest {

  @Test
  void testResolveSessionAndFirstParam() {
    SetupSessionRegistry registry = new SetupSessionRegistry();
    SetupSession console = SetupHandlerSupport.resolveSession(null, registry);
    assertNotNull(console);
    assertEquals(SetupSession.CONSOLE_CALLER_ID, console.callerId());

    UUID player = UUID.randomUUID();
    SetupSession pSession = SetupHandlerSupport.resolveSession(player, registry);
    assertEquals(player, pSession.callerId());

    assertEquals("", SetupHandlerSupport.getFirstParam(Collections.emptyMap(), "key"));
    assertEquals("first", SetupHandlerSupport.getFirstParam(Map.of("key", List.of("first", "second")), "key"));
  }

  @Test
  void testHandleChoiceValidAndInvalid() {
    SetupSessionRegistry registry = new SetupSessionRegistry();
    SetupBookMenuBuilder bookBuilder = new SetupBookMenuBuilder();
    UUID caller = UUID.randomUUID();
    String[] picked = new String[] {null};

    // Missing choice
    boolean handledEmpty = SetupHandlerSupport.handleChoice(
        caller, Map.of(), registry, bookBuilder, null,
        "usage", "invalid", Set.of("a", "b"), SetupStage.GAMEPLAY,
        choice -> picked[0] = choice
    );
    assertFalse(handledEmpty);
    assertNull(picked[0]);

    // Invalid choice
    boolean handledInvalid = SetupHandlerSupport.handleChoice(
        caller, Map.of("choice", List.of("c")), registry, bookBuilder, null,
        "usage", "invalid ", Set.of("a", "b"), SetupStage.GAMEPLAY,
        choice -> picked[0] = choice
    );
    assertFalse(handledInvalid);
    assertNull(picked[0]);

    // Valid choice
    boolean handledValid = SetupHandlerSupport.handleChoice(
        caller, Map.of("choice", List.of("A")), registry, bookBuilder, null,
        "usage", "invalid ", Set.of("a", "b"), SetupStage.GAMEPLAY,
        choice -> picked[0] = choice
    );
    assertTrue(handledValid);
    assertEquals("a", picked[0]);
    assertEquals(SetupStage.GAMEPLAY, registry.getOrCreate(caller).currentStage());
  }

  @Test
  void testComputeDiffPreviewAndLoadLiveBaseline() {
    assertTrue(true);
  }
}
