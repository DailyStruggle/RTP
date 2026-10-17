package io.github.dailystruggle.rtp.common.hooks;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import io.github.dailystruggle.rtp.api.claim.ClaimBoundaryProvider;
import io.github.dailystruggle.rtp.api.hooks.ClaimBoundaryRegistry;
import io.github.dailystruggle.rtp.common.selection.region.claim.ClaimAnchoredRegionTrackerTest.RectangularClaimBoundary;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClaimBoundaryRegistryTest {

  private DefaultRTPHooks hooks;
  private ClaimBoundaryRegistry registry;

  @BeforeEach
  void setUp() {
    hooks = new DefaultRTPHooks();
    registry = hooks.claimBoundaries();
  }

  @Test
  @DisplayName("Registration, ordering by priority, and unregistration")
  void testRegistrationAndPriority() {
    ClaimBoundaryProvider lowPri = createMockProvider("low", 1, null);
    ClaimBoundaryProvider highPri = createMockProvider("high", 100, null);
    ClaimBoundaryProvider medPri = createMockProvider("med", 50, null);

    registry.register(lowPri);
    registry.register(highPri);
    AutoCloseable hMed = registry.register(medPri);

    assertEquals(3, registry.size());
    assertEquals("high", registry.providers().get(0).namespace());
    assertEquals("med", registry.providers().get(1).namespace());
    assertEquals("low", registry.providers().get(2).namespace());

    assertTrue(registry.unregister(highPri));
    assertEquals(2, registry.size());
    assertEquals("med", registry.providers().get(0).namespace());

    try {
      hMed.close();
    } catch (Exception e) {
      fail(e);
    }
    assertEquals(1, registry.size());
    assertEquals("low", registry.providers().get(0).namespace());

    registry.clear();
    assertEquals(0, registry.size());
  }

  @Test
  @DisplayName("Resolve by exact namespace")
  void testResolveByNamespace() {
    UUID playerId = UUID.randomUUID();
    ClaimBoundary facBoundary = new RectangularClaimBoundary("fac1", "world", 0, 0, 10, 10);
    ClaimBoundary townBoundary = new RectangularClaimBoundary("town1", "world", 100, 100, 110, 110);

    registry.register(createMockProvider("factions", 10, (u, w) -> u.equals(playerId) ? Optional.of(facBoundary) : Optional.empty()));
    registry.register(createMockProvider("towny", 20, (u, w) -> u.equals(playerId) ? Optional.of(townBoundary) : Optional.empty()));

    // Target factions explicitly even though towny has higher priority
    Optional<ClaimBoundary> res = registry.resolve(playerId, "world", "factions");
    assertTrue(res.isPresent());
    assertEquals("fac1", res.get().id());

    // Target towny explicitly
    res = registry.resolve(playerId, "world", "towny");
    assertTrue(res.isPresent());
    assertEquals("town1", res.get().id());

    // Target nonexistent namespace
    res = registry.resolve(playerId, "world", "unknown");
    assertFalse(res.isPresent());
  }

  @Test
  @DisplayName("Resolve by automatic priority fallback")
  void testResolveByAutoPriority() {
    UUID playerId = UUID.randomUUID();
    ClaimBoundary facBoundary = new RectangularClaimBoundary("fac1", "world", 0, 0, 10, 10);
    ClaimBoundary townBoundary = new RectangularClaimBoundary("town1", "world", 100, 100, 110, 110);

    // towny higher priority (50) than factions (10)
    registry.register(createMockProvider("factions", 10, (u, w) -> u.equals(playerId) ? Optional.of(facBoundary) : Optional.empty()));
    registry.register(createMockProvider("towny", 50, (u, w) -> u.equals(playerId) ? Optional.of(townBoundary) : Optional.empty()));

    // Auto resolution chooses higher priority towny
    Optional<ClaimBoundary> res = registry.resolve(playerId, "world", null);
    assertTrue(res.isPresent());
    assertEquals("town1", res.get().id());

    res = registry.resolve(playerId, "world", "auto");
    assertTrue(res.isPresent());
    assertEquals("town1", res.get().id());

    // If towny has no claim for player in "nether", falls back to factions
    registry.clear();
    registry.register(createMockProvider("factions", 10, (u, w) -> "nether".equals(w) ? Optional.of(facBoundary) : Optional.empty()));
    registry.register(createMockProvider("towny", 50, (u, w) -> "world".equals(w) ? Optional.of(townBoundary) : Optional.empty()));

    res = registry.resolve(playerId, "nether");
    assertTrue(res.isPresent());
    assertEquals("fac1", res.get().id());
  }

  @Test
  @DisplayName("Fails safe when provider throws exception")
  void testFailsSafeOnException() {
    UUID playerId = UUID.randomUUID();
    ClaimBoundary safeBoundary = new RectangularClaimBoundary("safe", "world", 0, 0, 10, 10);

    registry.register(createMockProvider("broken", 100, (u, w) -> {
      throw new RuntimeException("Simulated provider crash");
    }));
    registry.register(createMockProvider("fallback", 10, (u, w) -> Optional.of(safeBoundary)));

    // Broken provider throws, caught and logged, fallback succeeds
    Optional<ClaimBoundary> res = registry.resolve(playerId, "world");
    assertTrue(res.isPresent());
    assertEquals("safe", res.get().id());
  }

  @Test
  @DisplayName("Resolve at coordinates by priority ordering and namespace")
  void testResolveAtCoordinates() {
    ClaimBoundary townBoundary = new RectangularClaimBoundary("town_at", "world", 100, 100, 150, 150);
    ClaimBoundary facBoundary = new RectangularClaimBoundary("fac_at", "world", 100, 100, 150, 150);

    ClaimBoundaryProvider townProvider = new ClaimBoundaryProvider() {
      @Override public String namespace() { return "towny"; }
      @Override public int priority() { return 50; }
      @Override public Optional<ClaimBoundary> getBoundary(UUID playerId, String worldName) { return Optional.empty(); }
      @Override public Optional<ClaimBoundary> getBoundaryAt(String worldName, int x, int z) {
        return townBoundary.contains(x, z) ? Optional.of(townBoundary) : Optional.empty();
      }
    };

    ClaimBoundaryProvider facProvider = new ClaimBoundaryProvider() {
      @Override public String namespace() { return "factions"; }
      @Override public int priority() { return 20; }
      @Override public Optional<ClaimBoundary> getBoundary(UUID playerId, String worldName) { return Optional.empty(); }
      @Override public Optional<ClaimBoundary> getBoundaryAt(String worldName, int x, int z) {
        return facBoundary.contains(x, z) ? Optional.of(facBoundary) : Optional.empty();
      }
    };

    registry.register(facProvider);
    registry.register(townProvider);

    // Auto resolution chooses higher priority towny (50) over factions (20)
    Optional<ClaimBoundary> resolved = registry.resolveAt("world", 120, 120);
    assertTrue(resolved.isPresent());
    assertEquals("town_at", resolved.get().id());

    // Explicit namespace query targets factions
    resolved = registry.resolveAt("world", 120, 120, "factions");
    assertTrue(resolved.isPresent());
    assertEquals("fac_at", resolved.get().id());

    // Coordinates outside bounds return empty
    resolved = registry.resolveAt("world", 500, 500);
    assertFalse(resolved.isPresent());
  }

  private ClaimBoundaryProvider createMockProvider(
      String ns,
      int priority,
      java.util.function.BiFunction<UUID, String, Optional<ClaimBoundary>> resolver) {
    return new ClaimBoundaryProvider() {
      @Override
      public String namespace() {
        return ns;
      }

      @Override
      public int priority() {
        return priority;
      }

      @Override
      public Optional<ClaimBoundary> getBoundary(UUID playerId, String worldName) {
        if (resolver != null) {
          return resolver.apply(playerId, worldName);
        }
        return Optional.empty();
      }
    };
  }
}
