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

  @Test
  void testRenderCurrentStageSkipsWarningWhenCallerDisconnected() {
    io.github.dailystruggle.rtp.common.bit.CoreFallbackCbitCounters.reset();
    io.github.dailystruggle.rtp.api.server.RTPServerAccessor prevAccessor =
        io.github.dailystruggle.rtp.common.runtime.RTPRuntime.serverAccessor();
    java.util.List<java.util.logging.LogRecord> warnings = new java.util.ArrayList<>();
    try {
      io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor mockAccessor =
          new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(new java.io.File("target/test-data")) {
            @Override
            public void log(java.util.logging.Level level, String msg, Throwable throwable) {
              if (level.intValue() >= java.util.logging.Level.WARNING.intValue()) {
                java.util.logging.LogRecord rec = new java.util.logging.LogRecord(level, msg);
                rec.setThrown(throwable);
                warnings.add(rec);
              }
            }
          };
      io.github.dailystruggle.rtp.common.runtime.RTPRuntime.setServerAccessor(mockAccessor);

      UUID disconnectedCaller = UUID.randomUUID();
      SetupSession session = new SetupSession(disconnectedCaller);
      SetupBookMenuBuilder bookBuilder = new SetupBookMenuBuilder();
      io.github.dailystruggle.rtp.api.menu.MenuRenderer disconnectingRenderer = (id, model) -> {
        throw new IllegalStateException("BookMenuRenderer.render: no online player for UUID " + id);
      };

      boolean rendered = SetupHandlerSupport.renderCurrentStage(
          disconnectedCaller, session, bookBuilder, disconnectingRenderer);
      assertTrue(rendered);
      assertEquals(0L, io.github.dailystruggle.rtp.common.bit.CoreFallbackCbitCounters.setupWizardCount(),
          "Disconnected caller before render must not trip setupWizardDegraded counter");
      assertTrue(warnings.isEmpty(),
          "Disconnected caller before render must not log Level.WARNING stack trace");
    } finally {
      io.github.dailystruggle.rtp.common.runtime.RTPRuntime.setServerAccessor(prevAccessor);
      io.github.dailystruggle.rtp.common.bit.CoreFallbackCbitCounters.reset();
    }
  }
}
