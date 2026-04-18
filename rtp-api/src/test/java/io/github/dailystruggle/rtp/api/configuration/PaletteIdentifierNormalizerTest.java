package io.github.dailystruggle.rtp.api.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PaletteIdentifierNormalizer}.
 *
 * <p>Verifies the symmetric-normalization contract required by ADR-016
 * (Anvil read-only pre-filter) §3: config-side and palette-side entries must collapse to
 * the same canonical form so that matching is a simple set membership check.</p>
 */
@DisplayName("REQ-RTP-S-001 / ADR-016 § PaletteIdentifierNormalizer")
class PaletteIdentifierNormalizerTest {

  @Test
  @DisplayName("minecraft:lava, LAVA, MINECRAFT:LAVA all collapse to LAVA")
  void canonicalFormsAreEquivalent() {
    assertEquals("LAVA", PaletteIdentifierNormalizer.normalize("minecraft:lava"));
    assertEquals("LAVA", PaletteIdentifierNormalizer.normalize("LAVA"));
    assertEquals("LAVA", PaletteIdentifierNormalizer.normalize("MINECRAFT:LAVA"));
    assertEquals("LAVA", PaletteIdentifierNormalizer.normalize("lava"));
    assertEquals("LAVA", PaletteIdentifierNormalizer.normalize("  minecraft:lava  "));
  }

  @Test
  @DisplayName("null input round-trips to null")
  void nullReturnsNull() {
    assertNull(PaletteIdentifierNormalizer.normalize(null));
  }

  @Test
  @DisplayName("whitespace-only and empty inputs collapse to empty string")
  void emptyInputs() {
    assertEquals("", PaletteIdentifierNormalizer.normalize(""));
    assertEquals("", PaletteIdentifierNormalizer.normalize("   "));
  }

  @Test
  @DisplayName("namespace-only input with trailing colon collapses to empty")
  void namespaceOnly() {
    // "minecraft:" → after first-colon split → "" → "" after uppercase.
    assertEquals("", PaletteIdentifierNormalizer.normalize("minecraft:"));
  }

  @Test
  @DisplayName("Modded multi-colon identifiers retain the remaining colons after namespace strip")
  void moddedMultiColonPreservesPath() {
    // Only the FIRST colon is treated as namespace separator.
    assertEquals("NS:BLOCK", PaletteIdentifierNormalizer.normalize("mod:ns:block"));
    assertEquals("CRUSHING_WHEEL", PaletteIdentifierNormalizer.normalize("create:crushing_wheel"));
  }

  @Test
  @DisplayName("Turkish-i locale trap is avoided by upper-casing under Locale.ROOT")
  void turkishILocaleTrap() {
    // "i" must always upper-case to "I" regardless of JVM default locale.
    assertEquals("WATERLOGGED_ITEM", PaletteIdentifierNormalizer.normalize("minecraft:waterlogged_item"));
  }

  @Test
  @DisplayName("normalizeAll deduplicates and skips null/empty entries")
  void normalizeAllDedupes() {
    List<String> raw = Arrays.asList(
        "minecraft:lava", "LAVA", "MINECRAFT:LAVA", null, "", "  ", "minecraft:fire");
    Set<String> normalized = PaletteIdentifierNormalizer.normalizeAll(raw);
    assertEquals(2, normalized.size());
    assertTrue(normalized.contains("LAVA"));
    assertTrue(normalized.contains("FIRE"));
  }

  @Test
  @DisplayName("normalizeAll(null) returns an empty, unmodifiable set")
  void normalizeAllNull() {
    Set<String> s = PaletteIdentifierNormalizer.normalizeAll(null);
    assertTrue(s.isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> s.add("X"));
  }

  @Test
  @DisplayName("normalizeAll returns unmodifiable set")
  void normalizeAllUnmodifiable() {
    Set<String> s = PaletteIdentifierNormalizer.normalizeAll(Collections.singletonList("lava"));
    assertThrows(UnsupportedOperationException.class, () -> s.add("FIRE"));
  }

  @Test
  @DisplayName("matches() handles palette-side inputs symmetrically with config-side normalization")
  void matchesSymmetric() {
    Set<String> configSide = new LinkedHashSet<>();
    configSide.add(PaletteIdentifierNormalizer.normalize("LAVA"));
    configSide.add(PaletteIdentifierNormalizer.normalize("MINECRAFT:FIRE"));
    Set<String> immutable = Collections.unmodifiableSet(configSide);

    assertTrue(PaletteIdentifierNormalizer.matches("minecraft:lava", immutable));
    assertTrue(PaletteIdentifierNormalizer.matches("LAVA", immutable));
    assertTrue(PaletteIdentifierNormalizer.matches("minecraft:fire", immutable));
    assertFalse(PaletteIdentifierNormalizer.matches("minecraft:grass_block", immutable));
    assertFalse(PaletteIdentifierNormalizer.matches(null, immutable));
    assertFalse(PaletteIdentifierNormalizer.matches("", immutable));
  }

  @Test
  @DisplayName("matches() throws on null lookup set (fail-fast contract)")
  void matchesNullSetThrows() {
    assertThrows(NullPointerException.class,
        () -> PaletteIdentifierNormalizer.matches("minecraft:lava", null));
  }
}
