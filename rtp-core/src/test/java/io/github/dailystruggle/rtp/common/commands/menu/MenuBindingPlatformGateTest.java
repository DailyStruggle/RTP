package io.github.dailystruggle.rtp.common.commands.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the platform-gate selection rule {@link MenuBindingSupport} applies to
 * discovered {@code MenuRendererProvider} / {@code AnvilInputOpenerProvider}
 * instances. The gate exists because a universal jar shades the Paper, Fabric,
 * and NeoForge adapters together, so all three {@code "book"} providers are on
 * the runtime classpath at once; without it the wrong platform's renderer (e.g.
 * the Fabric book renderer on Paper) could be selected and would degrade to the
 * chat fallback (ADR-070).
 */
@DisplayName("MenuBindingSupport platform gate")
final class MenuBindingPlatformGateTest {

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
}
