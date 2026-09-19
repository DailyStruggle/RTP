package io.github.dailystruggle.rtp.common.commands.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards platform-gate selection in {@link MenuBindingSupport} (ADR-070).
 * Ensures shaded multi-platform providers match the active {@link PlatformFamily}.
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
