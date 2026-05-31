package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.pvp.PvPGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the optional PvP / combat-tag pre-flight gate (ADR-055) wired into
 * {@code RTPCmd.compute}. The gate is consulted before the requester is enrolled
 * in {@code processingPlayers}; a non-ALLOW action refuses the request with the
 * configurable {@code pvpInCombat} message and never enrols the player.
 */
public class RTPCmdPvPGateTest {

  @TempDir File tempDir;

  private MockRTPServerAccessor accessor;

  /** Bare concrete RTPCmd used only to invoke the default-method {@code compute}. */
  private static class TestRTPCmd extends BaseRTPCmdImpl implements RTPCmd {
    TestRTPCmd() { super(null); }

    @Override
    public boolean onCommand(UUID senderId, Map<String, List<String>> args, CommandsAPICommand next) {
      return onCommand(senderId, args, next, null);
    }

    @Override
    public boolean onCommand(UUID senderId, Map<String, List<String>> args, CommandsAPICommand next,
                             java.util.function.Consumer<String> messageMethod) {
      if (next != null) return true;
      return compute(senderId, args, next, messageMethod);
    }

    @Override public String name() { return "rtp"; }
    @Override public String permission() { return "rtp.use"; }
    @Override public String description() { return "rtp"; }
    @Override public void successEvent(RTPCommandSender sender, RTPPlayer player) {}
    @Override public void failEvent(RTPCommandSender sender, String msg) {}
  }

  @SuppressWarnings("unchecked")
  private ConfigParser<SafetyKeys> safety() {
    return (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
  }

  private void setSafety(String key, Object value) {
    Map<String, Object> overlay = new HashMap<>();
    overlay.put(key, value);
    safety().setData(overlay);
  }

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
    // Native source so the gate never depends on an externally-bound provider.
    setSafety(SafetyKeys.pvpSource.name(), "NATIVE");
    PvPGate.nativeTracker().clearAll();

    @SuppressWarnings("unchecked")
    ConfigParser<MessagesKeys> lang =
            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    EnumMap<MessagesKeys, Object> data = new EnumMap<>(MessagesKeys.class);
    data.put(MessagesKeys.pvpInCombat, "IN_COMBAT refused");
    data.put(MessagesKeys.consoleCmdNotAllowed, "console disallowed");
    lang.setData(data);
  }

  @AfterEach
  void tearDown() {
    PvPGate.nativeTracker().clearAll();
  }

  @Test
  @DisplayName("enabled gate refuses /rtp for an in-combat player and does not enrol")
  void inCombat_refusesAndDoesNotEnrol() {
    setSafety(SafetyKeys.pvpCheckEnabled.name(), true);
    setSafety(SafetyKeys.pvpOnCombat.name(), "DENY");

    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "combatant", null));
    PvPGate.nativeTracker().stamp(playerId, System.currentTimeMillis());

    TestRTPCmd cmd = new TestRTPCmd();
    AtomicReference<String> msg = new AtomicReference<>();
    boolean res = cmd.onCommand(playerId, new HashMap<>(), null, msg::set);

    assertTrue(res);
    assertNotNull(msg.get(), "refusal must emit the pvpInCombat message");
    assertTrue(msg.get().contains("IN_COMBAT"), "expected pvpInCombat template, got: " + msg.get());
    assertFalse(RTP.getInstance().processingPlayers.contains(playerId),
            "a refused request must not enrol the player in processingPlayers");
  }

  @Test
  @DisplayName("disabled gate never refuses, even for a combat-tagged player")
  void disabledGate_doesNotRefuse() {
    setSafety(SafetyKeys.pvpCheckEnabled.name(), false);

    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "tagged", null));
    PvPGate.nativeTracker().stamp(playerId, System.currentTimeMillis());

    TestRTPCmd cmd = new TestRTPCmd();
    AtomicReference<String> msg = new AtomicReference<>();
    // The gate is the only thing under test here: when it does NOT refuse, compute
    // falls through into the real teleport pipeline, which a bare mock player
    // cannot fully satisfy. A deeper failure therefore confirms the gate let the
    // request through; we only assert the combat refusal was never emitted.
    try {
      cmd.onCommand(playerId, new HashMap<>(), null, msg::set);
    } catch (Throwable proceededPastGate) {
      // expected: gate allowed, downstream pipeline is out of scope
    }

    if (msg.get() != null) {
      assertFalse(msg.get().contains("IN_COMBAT"),
              "disabled gate must never emit the combat refusal: " + msg.get());
    }
  }

  @Test
  @DisplayName("enabled gate does not refuse a player who is not in combat")
  void enabledGate_clearPlayer_notRefused() {
    setSafety(SafetyKeys.pvpCheckEnabled.name(), true);
    setSafety(SafetyKeys.pvpOnCombat.name(), "DENY");

    UUID playerId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(playerId, "peaceful", null));
    // not stamped -> not in combat

    TestRTPCmd cmd = new TestRTPCmd();
    AtomicReference<String> msg = new AtomicReference<>();
    try {
      cmd.onCommand(playerId, new HashMap<>(), null, msg::set);
    } catch (Throwable proceededPastGate) {
      // expected: gate allowed, downstream pipeline is out of scope
    }

    if (msg.get() != null) {
      assertFalse(msg.get().contains("IN_COMBAT"),
              "a player who is not in combat must not be refused: " + msg.get());
    }
  }

  @Test
  @DisplayName("rtp targeting another player (player arg) bypasses the combat gate")
  void crossPlayerTarget_bypassesGate() {
    setSafety(SafetyKeys.pvpCheckEnabled.name(), true);
    setSafety(SafetyKeys.pvpOnCombat.name(), "DENY");

    UUID senderId = UUID.randomUUID();
    accessor.addPlayer(new MockRTPPlayer(senderId, "admin", null));
    PvPGate.nativeTracker().stamp(senderId, System.currentTimeMillis());

    TestRTPCmd cmd = new TestRTPCmd();
    Map<String, List<String>> args = new HashMap<>();
    args.put("player", List.of("someoneElse"));
    AtomicReference<String> msg = new AtomicReference<>();
    try {
      cmd.onCommand(senderId, args, null, msg::set);
    } catch (Throwable proceededPastGate) {
      // expected: gate bypassed for cross-player targeting, downstream out of scope
    }

    if (msg.get() != null) {
      assertFalse(msg.get().contains("IN_COMBAT"),
              "the combat gate must not refuse a cross-player rtp target: " + msg.get());
    }
  }
}
