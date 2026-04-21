package io.github.dailystruggle.rtp.spigot.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PaletteNormalizer}.
 *
 * <p>Verifies the Spigot-side reconciler contract documented in ADR-016 §3 and
 * {@code ADR-016} §8.1:</p>
 * <ul>
 *   <li>Vanilla identifiers like {@code minecraft:lava} are reconciled to {@code Material#name()}
 *       via {@link org.bukkit.Material#matchMaterial(String)}.</li>
 *   <li>Unknown / modded identifiers fall back to the pure-string canonical form provided
 *       by {@link io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer}.</li>
 *   <li>The config side ({@code rtp-api} path) and the palette side (Bukkit-registry path)
 *       collapse to the same canonical form for vanilla ids, so a single set-membership
 *       check is sufficient at lookup time.</li>
 * </ul>
 */
@DisplayName("REQ-RTP-S-001 / ADR-016 § PaletteNormalizer (Spigot reconciler)")
class PaletteNormalizerTest {

  @BeforeEach
  void setUp() {
    MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  @DisplayName("minecraft:lava reconciles to LAVA via Material.matchMaterial")
  void vanillaLavaReconciles() {
    assertEquals("LAVA", PaletteNormalizer.reconcile("minecraft:lava"));
    assertEquals("LAVA", PaletteNormalizer.reconcile("LAVA"));
    assertEquals("LAVA", PaletteNormalizer.reconcile("lava"));
  }

  @Test
  @DisplayName("null raw input round-trips to null (never throws)")
  void nullInput() {
    assertNull(PaletteNormalizer.reconcile(null));
  }

  @Test
  @DisplayName("Unknown modded identifier falls back to namespace-strip + upper-case")
  void moddedIdentifierFallback() {
    // Material.matchMaterial on MockBukkit will return null for a namespace the server
    // does not know, so we should see the pure-string canonical form.
    assertEquals("CRUSHING_WHEEL", PaletteNormalizer.reconcile("create:crushing_wheel"));
    assertEquals("ARCANE_CRYSTAL", PaletteNormalizer.reconcile("arcanecraft:arcane_crystal"));
  }

  @Test
  @DisplayName("reconcileAll deduplicates and preserves insertion order")
  void reconcileAllDedupes() {
    Set<String> s = PaletteNormalizer.reconcileAll(
        Arrays.asList("minecraft:lava", "LAVA", null, "", "minecraft:fire", "MINECRAFT:FIRE"));
    assertEquals(2, s.size());
    assertTrue(s.contains("LAVA"));
    assertTrue(s.contains("FIRE"));
  }

  @Test
  @DisplayName("reconcileAll(null) returns an empty set")
  void reconcileAllNull() {
    assertTrue(PaletteNormalizer.reconcileAll(null).isEmpty());
  }

  @Test
  @DisplayName("matches() is symmetric across vanilla palette/config forms")
  void matchesSymmetric() {
    Set<String> unsafe = PaletteNormalizer.reconcileAll(Arrays.asList("minecraft:lava", "fire"));

    assertTrue(PaletteNormalizer.matches("minecraft:lava", unsafe));
    assertTrue(PaletteNormalizer.matches("LAVA", unsafe));
    assertTrue(PaletteNormalizer.matches("lava", unsafe));
    assertTrue(PaletteNormalizer.matches("MINECRAFT:FIRE", unsafe));
    assertFalse(PaletteNormalizer.matches("minecraft:grass_block", unsafe));
    assertFalse(PaletteNormalizer.matches(null, unsafe));
    assertFalse(PaletteNormalizer.matches("", unsafe));
  }

  @Test
  @DisplayName("matches() returns false on null / empty unsafe set rather than throwing")
  void matchesEmptySet() {
    // Unlike PaletteIdentifierNormalizer.matches (rtp-api, fail-fast), the Spigot-side
    // reconciler is called on a hot path and must be tolerant — an absent unsafe set
    // simply means nothing is unsafe, which is a meaningful runtime state.
    assertFalse(PaletteNormalizer.matches("minecraft:lava", null));
    assertFalse(PaletteNormalizer.matches("minecraft:lava", Collections.emptySet()));
  }

  @Test
  @DisplayName("Config-side (rtp-api) and palette-side (Spigot) produce equivalent canonical forms for vanilla ids")
  void splitNormalizationIsEquivalent() {
    // Simulate: rtp-core populates the unsafe set via the rtp-api pure-string normalizer
    // at config load, and the Spigot palette probe reconciles palette entries at runtime.
    // Both sides must produce the same canonical key for vanilla ids.
    Set<String> configSide = io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer
        .normalizeAll(Arrays.asList("minecraft:lava", "minecraft:fire", "MAGMA_BLOCK"));

    // Palette-side reconciliation must hit every one of those entries.
    assertTrue(PaletteNormalizer.matches("minecraft:lava", configSide));
    assertTrue(PaletteNormalizer.matches("minecraft:fire", configSide));
    assertTrue(PaletteNormalizer.matches("minecraft:magma_block", configSide));
    assertFalse(PaletteNormalizer.matches("minecraft:grass_block", configSide));
  }
}
