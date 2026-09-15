package io.github.dailystruggle.rtp.common.commands.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/**
 * Guards platform-gate selection in {@link MenuBindingSupport} (ADR-070).
 * Ensures shaded multi-platform providers match the active {@link PlatformFamily}.
 */
@DisplayName("MenuBindingSupport platform gate")
final class MenuBindingPlatformGateTest {

  @TempDir
  Path tempDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir.toFile());
  }

  @Test
  @DisplayName("a provider matching the running family is eligible")
  void matchingFamilyEligible() {
    assertTrue(MenuBindingSupport.matchesPlatform(PlatformFamily.BUKKIT, PlatformFamily.BUKKIT));
  }

  @Test
  @DisplayName("a provider bound to a different family is skipped (Fabric provider on Paper)")
  void mismatchedFamilySkipped() {
    assertFalse(MenuBindingSupport.matchesPlatform(PlatformFamily.FABRIC, PlatformFamily.BUKKIT));
    assertFalse(MenuBindingSupport.matchesPlatform(PlatformFamily.NEOFORGE, PlatformFamily.BUKKIT));
  }

  @Test
  @DisplayName("a platform-neutral provider (null family) is always eligible")
  void neutralProviderAlwaysEligible() {
    assertTrue(MenuBindingSupport.matchesPlatform(null, PlatformFamily.BUKKIT));
    assertTrue(MenuBindingSupport.matchesPlatform(null, PlatformFamily.FABRIC));
    assertTrue(MenuBindingSupport.matchesPlatform(null, null));
  }

  @Test
  @DisplayName("when the running family is unknown / unavailable, the gate stays open")
  void unknownRunningFamilyAcceptsAll() {
    assertTrue(MenuBindingSupport.matchesPlatform(PlatformFamily.FABRIC, null));
    assertTrue(MenuBindingSupport.matchesPlatform(PlatformFamily.FABRIC, PlatformFamily.UNKNOWN));
  }

  @Test
  @DisplayName("discoverRenderer and discoverAnvilOpener discovery with test stubs")
  void discoverRendererAndAnvil() {
    io.github.dailystruggle.rtp.api.menu.MenuRenderer renderer = MenuBindingSupport.discoverRenderer();
    org.junit.jupiter.api.Assertions.assertNotNull(renderer);
    assertTrue(renderer instanceof StubBookMenuRendererProvider.StubRenderer);

    MenuRedeemSubcommand.AnvilInputOpener opener = MenuBindingSupport.discoverAnvilOpener();
    org.junit.jupiter.api.Assertions.assertNotNull(opener);
    assertTrue(opener instanceof StubAnvilOpenerProvider.StubOpener);
  }

  @Test
  @DisplayName("configuredRendererIds reflection tests")
  void configuredRendererIds_branches() throws Exception {
    java.lang.reflect.Method m = MenuBindingSupport.class.getDeclaredMethod("configuredRendererIds");
    m.setAccessible(true);

    java.util.List<?> ids = (java.util.List<?>) m.invoke(null);
    assertNotNull(ids);
    assertFalse(ids.isEmpty());

    // Configure RTP.configs with menu map
    if (io.github.dailystruggle.rtp.common.RTP.configs != null) {
      var configParser = io.github.dailystruggle.rtp.common.RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.class);
      if (configParser != null) {
        configParser.set(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.menu, java.util.Map.of("renderer", java.util.List.of("book", "chat")));
        java.util.List<?> listIds = (java.util.List<?>) m.invoke(null);
        assertTrue(listIds.contains("book"));

        configParser.set(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.menu, java.util.Map.of("renderer", "chat"));
        java.util.List<?> singleIds = (java.util.List<?>) m.invoke(null);
        assertTrue(singleIds.contains("chat"));

        // When menu is a direct String or empty list
        configParser.set(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.menu, "directString");
        java.util.List<?> directIds = (java.util.List<?>) m.invoke(null);
        assertTrue(directIds.contains("directstring"));

        configParser.set(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.menu, java.util.Collections.emptyList());
        java.util.List<?> emptyIds = (java.util.List<?>) m.invoke(null);
        assertEquals(java.util.List.of("book"), emptyIds);
      }
    }

    // When RTP.configs throws
    var origConfigs = io.github.dailystruggle.rtp.common.RTP.configs;
    try {
      io.github.dailystruggle.rtp.common.RTP.configs = null;
      java.util.List<?> nullConfigsIds = (java.util.List<?>) m.invoke(null);
      assertEquals(java.util.List.of("book"), nullConfigsIds);
    } finally {
      io.github.dailystruggle.rtp.common.RTP.configs = origConfigs;
    }
  }
}
