package io.github.dailystruggle.rtp.common.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ActionPlaceholderSanitizerTest {

  @Test
  @DisplayName("UUID placeholders are replaced safely")
  void testUuidPlaceholders() {
    UUID pId = UUID.randomUUID();
    String cmd = "tag [player] add rtp_session_[session_id]";
    Map<String, Object> tokens = Map.of(
        "player", pId,
        "session_id", "1234abcd");

    String result = ActionPlaceholderSanitizer.substitute(cmd, tokens);
    assertEquals("tag " + pId + " add rtp_session_1234abcd", result);
  }

  @Test
  @DisplayName("Unsanitized substrings containing semicolons or spaces are stripped")
  void testSanitizerInjectionPrevention() {
    String cmd = "broadcast [winner] won!";
    Map<String, Object> tokens = Map.of(
        "winner", "attacker; op baduser");

    String result = ActionPlaceholderSanitizer.substitute(cmd, tokens);
    assertEquals("broadcast  won!", result, "Dangerous token should be blanked out");
  }

  @Test
  @DisplayName("Unmapped placeholders remain intact")
  void testUnmappedPlaceholders() {
    String cmd = "give [player] [item] 1";
    Map<String, Object> tokens = Map.of("player", "Steve");

    String result = ActionPlaceholderSanitizer.substitute(cmd, tokens);
    assertEquals("give Steve [item] 1", result);
  }

  @Test
  @DisplayName("Collection of UUIDs (e.g. [players], [cluster_1]) formatted safely")
  void testCollectionOfUuidsPlaceholder() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    String cmd = "say Match started with [players] in session [session_id]";
    Map<String, Object> tokens = Map.of(
        "players", java.util.List.of(p1, p2),
        "session_id", "session123");

    String result = ActionPlaceholderSanitizer.substitute(cmd, tokens);
    assertEquals("say Match started with " + p1 + " " + p2 + " in session session123", result);
  }

  @Test
  @DisplayName("Prevent command injection via newline or carriage return")
  void testNewlineCommandInjectionBlocked() {
    String cmd = "say Winner is [winner]";
    Map<String, Object> tokens = Map.of("winner", "Steve\nop Hacker\n");

    String result = ActionPlaceholderSanitizer.substitute(cmd, tokens);
    assertEquals("say Winner is ", result);
  }

  @Test
  @DisplayName("Player name and UUID suffixes (_name, _uuid) resolve properly")
  void testPlayerNameAndUuidSuffixes() {
    UUID pId = UUID.randomUUID();
    String cmd = "tellraw [player_name] hi, your uuid is [player_uuid]";
    Map<String, Object> tokens = Map.of("player", pId);

    String result = ActionPlaceholderSanitizer.substitute(cmd, tokens);
    // Since player is offline in mock, player_name falls back to empty or UUID format
    assertTrue(result.contains(pId.toString()));
  }

  @Test
  @DisplayName("Commands with missing target placeholders are detected")
  void testMissingTargetDetection() {
    String cmd = "tellraw [target_name] hi";
    assertTrue(ActionPlaceholderSanitizer.hasMissingTarget(cmd, Map.of()));
    assertTrue(ActionPlaceholderSanitizer.hasMissingTarget(cmd, Map.of("sender_name", "Alice")));
    assertTrue(ActionPlaceholderSanitizer.hasMissingTarget(cmd, Map.of("target_name", "")));
    assertTrue(ActionPlaceholderSanitizer.hasMissingTarget(cmd, Map.of("target_name", "any")));

    assertFalse(ActionPlaceholderSanitizer.hasMissingTarget(cmd, Map.of("target_name", "Bob")));
    assertFalse(ActionPlaceholderSanitizer.hasMissingTarget("tellraw [player_name] hi", Map.of()));

    String substituted = ActionPlaceholderSanitizer.substitute(cmd, Map.of());
    assertTrue(ActionPlaceholderSanitizer.containsUnresolvedPrefix(substituted, "target"));
  }

  @Test
  @DisplayName("Player entity placeholders prefer name over uuid when player is online")
  void testPlayerEntityPlaceholdersPreferName() {
    io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
        new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(new java.io.File("."));
    io.github.dailystruggle.rtp.common.RTP.serverAccessor = accessor;

    UUID pId = UUID.randomUUID();
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        pId, "leaf26", new io.github.dailystruggle.rtp.api.world.RTPLocation(accessor.getRTPWorld("world"), 0, 64, 0)));

    String cmd = "gamemode adventure [player]";
    String res = ActionPlaceholderSanitizer.substitute(cmd, Map.of("player", pId));
    assertEquals("gamemode adventure leaf26", res, "Should substitute player name instead of UUID");

    String uuidCmd = "tag [player_uuid] add test";
    String resUuid = ActionPlaceholderSanitizer.substitute(uuidCmd, Map.of("player", pId));
    assertEquals("tag " + pId + " add test", resUuid, "_uuid suffix should still resolve to raw UUID");
  }
}
