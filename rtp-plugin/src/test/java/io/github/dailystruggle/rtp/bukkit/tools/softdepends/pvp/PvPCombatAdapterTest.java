package io.github.dailystruggle.rtp.bukkit.tools.softdepends.pvp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Coverage for the bundled combat-tag adapters (ADR-055) bound to {@code PvPCombatStateRegistry}.
 *
 * <p>REQ-RTP-S-004: a missing combat plugin or an offline player must never block a teleport - the
 * adapters report "not in combat" rather than throwing, so a buggy or absent integration never
 * stalls the PvP gate.
 */
class PvPCombatAdapterTest {

  private MockedStatic<Bukkit> bukkit;
  private PluginManager pluginManager;

  @BeforeEach
  void setUp() {
    bukkit = mockStatic(Bukkit.class);
    pluginManager = mock(PluginManager.class);
    bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
  }

  @AfterEach
  void tearDown() {
    bukkit.close();
  }

  @Test
  @DisplayName("isAvailable() is false when the combat plugin is not enabled")
  void availability_false_when_plugin_absent() {
    when(pluginManager.isPluginEnabled("PvPManager")).thenReturn(false);
    when(pluginManager.isPluginEnabled("CombatLogX")).thenReturn(false);
    when(pluginManager.isPluginEnabled("SimpleCombatLog")).thenReturn(false);

    assertFalse(PvPManagerChecker.isAvailable());
    assertFalse(CombatLogXChecker.isAvailable());
    assertFalse(SimpleCombatLogChecker.isAvailable());
  }

  @Test
  @DisplayName("REQ-RTP-S-004: an offline / unknown player is reported as not-in-combat, never throws")
  void offline_player_is_not_in_combat() {
    UUID uuid = UUID.randomUUID();
    bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(null);

    assertFalse(PvPManagerChecker.isInCombat(uuid));
    assertFalse(CombatLogXChecker.isInCombat(uuid));
    assertFalse(SimpleCombatLogChecker.isInCombat(uuid));
  }
}
