package io.github.dailystruggle.rtp.claimaddon;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import io.github.dailystruggle.rtp.common.hooks.DefaultRTPHooks;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClaimBoundaryProviderContractTest {

  private DefaultRTPHooks hooks;

  @BeforeEach
  void setUp() {
    hooks = new DefaultRTPHooks();
  }

  @Test
  @DisplayName("TownyBoundaryProvider returns namespace and priority")
  void testTownyProviderContract() {
    TownyBoundaryProvider provider = new TownyBoundaryProvider();
    assertEquals("towny", provider.namespace());
    assertEquals(20, provider.priority());

    // In unit test environment without Bukkit server running, getBoundary safely returns empty without throwing
    Optional<ClaimBoundary> boundary = provider.getBoundary(UUID.randomUUID(), "world");
    assertNotNull(boundary);
    assertTrue(boundary.isEmpty());
  }

  @Test
  @DisplayName("GriefPreventionBoundaryProvider returns namespace and priority")
  void testGriefPreventionProviderContract() {
    GriefPreventionBoundaryProvider provider = new GriefPreventionBoundaryProvider();
    assertEquals("griefprevention", provider.namespace());
    assertEquals(15, provider.priority());

    // In unit test environment without Bukkit server running, getBoundary safely returns empty without throwing
    Optional<ClaimBoundary> boundary = provider.getBoundary(UUID.randomUUID(), "world");
    assertNotNull(boundary);
    assertTrue(boundary.isEmpty());
  }

  @Test
  @DisplayName("FactionsBoundaryProvider returns namespace and priority")
  void testFactionsProviderContract() {
    FactionsBoundaryProvider provider = new FactionsBoundaryProvider();
    assertEquals("factions", provider.namespace());
    assertEquals(10, provider.priority());

    // In unit test environment without Bukkit server running, getBoundary safely returns empty without throwing
    Optional<ClaimBoundary> boundary = provider.getBoundary(UUID.randomUUID(), "world");
    assertNotNull(boundary);
    assertTrue(boundary.isEmpty());
  }

  @Test
  @DisplayName("Provider registration into ClaimBoundaryRegistry maintains priorities")
  void testProviderRegistrationOrder() {
    TownyBoundaryProvider towny = new TownyBoundaryProvider();
    GriefPreventionBoundaryProvider gp = new GriefPreventionBoundaryProvider();
    FactionsBoundaryProvider factions = new FactionsBoundaryProvider();

    hooks.claimBoundaries().register(factions);
    hooks.claimBoundaries().register(towny);
    hooks.claimBoundaries().register(gp);

    assertEquals(3, hooks.claimBoundaries().size());
    // Descending order of priority: Towny (20), GP (15), Factions (10)
    assertEquals("towny", hooks.claimBoundaries().providers().get(0).namespace());
    assertEquals("griefprevention", hooks.claimBoundaries().providers().get(1).namespace());
    assertEquals("factions", hooks.claimBoundaries().providers().get(2).namespace());
  }
}
