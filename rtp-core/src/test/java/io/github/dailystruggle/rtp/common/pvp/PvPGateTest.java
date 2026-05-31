package io.github.dailystruggle.rtp.common.pvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.hooks.PvPCombatAction;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wiring coverage for the central {@link PvPGate} decision point (ADR-055).
 *
 * <p>This locks in the contract consumed by the two production call sites - the
 * {@code /rtp} pre-dispatch surface ({@code RTPCmd}) and the pre-execution
 * prefilter ({@code TeleportPipelineTask#runTeleport}) - so the later combat-tag
 * implementation has a stable, testable seam. It uses the native combat source
 * (no external provider bound) and drives state through
 * {@link PvPGate#nativeTracker()}.
 */
class PvPGateTest {

  @TempDir File pluginDir;

  @SuppressWarnings("unchecked")
  private ConfigParser<SafetyKeys> safety() {
    return (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
  }

  /**
   * Merge a safety value straight into the parser's in-memory map. Avoids
   * {@link ConfigParser#set} (which writes through to a YAML backing file that
   * is not materialised in the rtp-core test classpath) while still feeding the
   * exact map {@code getConfigValue}/{@code getNumber} read.
   */
  private void setSafety(String key, Object value) {
    Map<String, Object> overlay = new HashMap<>();
    overlay.put(key, value);
    safety().setData(overlay);
  }

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(pluginDir);
    // Force the native source so the test never depends on a bound provider.
    setSafety(SafetyKeys.pvpSource.name(), "NATIVE");
    PvPGate.nativeTracker().clearAll();
  }

  @AfterEach
  void tearDown() {
    PvPGate.nativeTracker().clearAll();
  }

  @Test
  @DisplayName("disabled gate is a no-op even for a combat-tagged player")
  void disabledGateAllows() {
    setSafety(SafetyKeys.pvpCheckEnabled.name(), false);
    UUID player = UUID.randomUUID();
    PvPGate.nativeTracker().stamp(player, System.currentTimeMillis());

    assertFalse(PvPGate.isEnabled(), "default/disabled gate");
    assertFalse(PvPGate.isInCombat(player), "disabled gate never reports in-combat");
    assertEquals(PvPCombatAction.ALLOW, PvPGate.evaluate(player));
  }

  @Test
  @DisplayName("enabled gate reports in-combat and returns the configured action")
  void enabledGateDeniesInCombat() {
    setSafety(SafetyKeys.pvpCheckEnabled.name(), true);
    setSafety(SafetyKeys.pvpOnCombat.name(), "DENY");
    UUID player = UUID.randomUUID();
    PvPGate.nativeTracker().stamp(player, System.currentTimeMillis());

    assertTrue(PvPGate.isEnabled());
    assertTrue(PvPGate.isInCombat(player));
    assertEquals(PvPCombatAction.DENY, PvPGate.evaluate(player));
  }

  @Test
  @DisplayName("enabled gate allows a player who is not in combat")
  void enabledGateAllowsWhenClear() {
    setSafety(SafetyKeys.pvpCheckEnabled.name(), true);
    setSafety(SafetyKeys.pvpOnCombat.name(), "DENY");
    UUID player = UUID.randomUUID();

    assertFalse(PvPGate.isInCombat(player), "never stamped");
    assertEquals(PvPCombatAction.ALLOW, PvPGate.evaluate(player));
  }

  @Test
  @DisplayName("configured action is honoured verbatim for an in-combat player")
  void configuredActionHonoured() {
    setSafety(SafetyKeys.pvpCheckEnabled.name(), true);
    setSafety(SafetyKeys.pvpOnCombat.name(), "CANCEL");
    UUID player = UUID.randomUUID();
    PvPGate.nativeTracker().stamp(player, System.currentTimeMillis());

    assertEquals(PvPCombatAction.CANCEL, PvPGate.evaluate(player));
  }

  @Test
  @DisplayName("null player is never in combat and always allowed")
  void nullPlayerAllowed() {
    setSafety(SafetyKeys.pvpCheckEnabled.name(), true);
    assertFalse(PvPGate.isInCombat(null));
    assertEquals(PvPCombatAction.ALLOW, PvPGate.evaluate(null));
  }
}
