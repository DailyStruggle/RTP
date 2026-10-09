package io.github.dailystruggle.rtp.common.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.anvil.ChunkCoord;
import io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S-004 / ADR-016: opt-in core Anvil provider for backlog bins fails closed to UNKNOWN. */
class AnvilBatchPrefilterProviderTest {

  @AfterEach
  void clearFlag() {
    System.clearProperty(AnvilBatchPrefilterProvider.ENABLE_PROPERTY);
  }

  @Test
  @DisplayName("Binds only when the JVM flag is set and never replaces an existing provider")
  void bindIfEnabled_isOptInAndNonClobbering() {
    AnvilPrefilterRegistry registry = new DefaultRTPHooks().anvilPrefilter();
    registry.clear();
    AnvilBatchPrefilterProvider.bindIfEnabled(registry);
    assertNull(registry.current(), "off by default");

    System.setProperty(AnvilBatchPrefilterProvider.ENABLE_PROPERTY, "true");
    AnvilBatchPrefilterProvider.bindIfEnabled(registry);
    assertInstanceOf(AnvilBatchPrefilterProvider.class, registry.current());

    AnvilPrefilterRegistry.Provider addon = (w, cx, cz) -> AnvilPrefilterRegistry.Provider.Decision.ACCEPT;
    registry.bind(addon);
    AnvilBatchPrefilterProvider.bindIfEnabled(registry);
    assertSame(addon, registry.current(), "an addon-bound provider is kept");
    registry.clear();
  }

  @Test
  @DisplayName("S-004: a world without an on-disk Anvil store yields no verdicts (live load)")
  void noWorldFolder_answersUnknown() {
    AnvilBatchPrefilterProvider p = new AnvilBatchPrefilterProvider();
    MockRTPWorld world = new MockRTPWorld("anvil_provider_world");
    assertTrue(p.classifyBatch(world, List.of(new ChunkCoord(0, 0))).isEmpty());
    assertEquals(AnvilPrefilterRegistry.Provider.Decision.UNKNOWN, p.classify(world, 0, 0));
  }

  @Test
  @DisplayName("S-004: missing region files classify UNKNOWN, one entry per distinct chunk")
  void missingRegionFile_answersUnknown(@TempDir Path folder) {
    AnvilBatchPrefilterProvider p = new AnvilBatchPrefilterProvider();
    MockRTPWorld world = new MockRTPWorld("anvil_provider_world_2") {
      @Override
      public Path anvilWorldFolder() {
        return folder;
      }
    };
    var out = p.classifyBatch(world, List.of(new ChunkCoord(1, 2), new ChunkCoord(3, 4), new ChunkCoord(1, 2)));
    assertEquals(2, out.size());
    out.values().forEach(d -> assertEquals(AnvilPrefilterRegistry.Provider.Decision.UNKNOWN, d));
  }
}
